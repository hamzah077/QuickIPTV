package com.quickiptv.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("server", server).put("username", username).put("password", password)

    companion object {
        fun from(o: JSONObject) = Account(
            o.optString("id"), o.optString("name"), o.optString("server"),
            o.optString("username"), o.optString("password"),
        )
    }
}

/** عنصر في سجل المشاهدة / المتابعة. */
data class HistoryItem(
    val key: String,        // movie:123 | ep:456 | live:789
    val type: String,       // movie | episode | live
    val title: String,
    val sub: String,
    val image: String,
    val url: String,
    val seriesId: String = "",
    val pos: Long = 0,      // ثواني
    val dur: Long = 0,
    val ts: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("type", type).put("title", title).put("sub", sub)
        .put("image", image).put("url", url).put("seriesId", seriesId).put("pos", pos).put("dur", dur).put("ts", ts)

    companion object {
        fun from(o: JSONObject) = HistoryItem(
            o.optString("key"), o.optString("type"), o.optString("title"), o.optString("sub"),
            o.optString("image"), o.optString("url"), o.optString("seriesId"),
            o.optLong("pos"), o.optLong("dur"), o.optLong("ts"),
        )
    }
}

/** التخزين على الجهاز (SharedPreferences + JSON): الحسابات، المفضلة، الاستئناف، السجل، إعدادات الدليل. */
object Store {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("quickiptv", Context.MODE_PRIVATE)
    }

    // ---------- الحسابات ----------
    var accounts: List<Account>
        get() {
            val a = JSONArray(p.getString("accounts", "[]"))
            return (0 until a.length()).map { Account.from(a.getJSONObject(it)) }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(it.toJson()) }
            p.edit().putString("accounts", a.toString()).apply()
        }

    var currentId: String
        get() = p.getString("current", "") ?: ""
        set(v) = p.edit().putString("current", v).apply()

    val current: Account? get() = accounts.firstOrNull { it.id == currentId }

    fun saveAccount(acc: Account) {
        accounts = accounts.filter { it.id != acc.id } + acc
        currentId = acc.id
    }

    fun removeAccount(id: String) {
        accounts = accounts.filter { it.id != id }
        if (currentId == id) currentId = accounts.firstOrNull()?.id ?: ""
    }

    // ---------- إعدادات عامة ----------
    /** عدد التحميلات بنفس الوقت. */
    var dlParallel: Int
        get() = p.getInt("dlParallel", 2)
        set(v) = p.edit().putInt("dlParallel", v).apply()

    var liveFormat: String
        get() = p.getString("liveFormat", "m3u8") ?: "m3u8"
        set(v) = p.edit().putString("liveFormat", v).apply()

    fun getString(k: String, def: String = ""): String = p.getString(k, def) ?: def
    fun putString(k: String, v: String) = p.edit().putString(k, v).apply()

    private fun accKey(k: String) = "$k:${currentId}"

    // ---------- المفضلة ----------
    fun favs(kind: String): MutableList<String> {
        val a = JSONArray(p.getString(accKey("fav_$kind"), "[]"))
        return (0 until a.length()).map { a.getString(it) }.toMutableList()
    }

    fun isFav(kind: String, id: String) = id in favs(kind)

    fun toggleFav(kind: String, id: String): Boolean {
        val f = favs(kind)
        val now = if (id in f) { f.remove(id); false } else { f.add(0, id); true }
        p.edit().putString(accKey("fav_$kind"), JSONArray(f).toString()).apply()
        return now
    }

    // ---------- السجل والاستئناف ----------
    fun history(): List<HistoryItem> {
        val a = JSONArray(p.getString(accKey("history"), "[]"))
        return (0 until a.length()).map { HistoryItem.from(a.getJSONObject(it)) }
    }

    fun addHistory(h: HistoryItem) {
        val list = listOf(h) + history().filter { it.key != h.key }
        val a = JSONArray()
        list.take(80).forEach { a.put(it.toJson()) }
        p.edit().putString(accKey("history"), a.toString()).apply()
    }

    fun saveProgress(key: String, pos: Long, dur: Long) {
        val list = history().map { if (it.key == key) it.copy(pos = pos, dur = dur, ts = System.currentTimeMillis()) else it }
        val a = JSONArray()
        list.sortedByDescending { it.ts }.forEach { a.put(it.toJson()) }
        p.edit().putString(accKey("history"), a.toString()).apply()
    }

    /** نقطة الاستئناف بالثواني (0 = من البداية أو خلص تقريباً). */
    fun resumePoint(key: String): Long {
        val h = history().firstOrNull { it.key == key } ?: return 0
        return if (h.dur > 0 && h.pos > 30 && h.pos < h.dur - 60) h.pos else 0
    }

    fun continueWatching(): List<HistoryItem> =
        history().filter { it.type != "live" && it.dur > 0 && it.pos > 30 && it.pos < it.dur - 60 }

    // ---------- دليل البرامج ----------
    var epgUrls: List<String>
        get() {
            val raw = p.getString(accKey("epgUrls"), null) ?: return Epg.DEFAULT_URLS
            val a = JSONArray(raw)
            return (0 until a.length()).map { a.getString(it) }
        }
        set(v) = p.edit().putString(accKey("epgUrls"), JSONArray(v).toString()).apply()

    /** الربط اليدوي: stream_id → معرّف قناة الدليل ("" = بدون دليل). */
    fun epgMap(): MutableMap<String, String> {
        val o = JSONObject(p.getString(accKey("epgMap"), "{}"))
        val m = mutableMapOf<String, String>()
        o.keys().forEach { m[it] = o.optString(it) }
        return m
    }

    fun setEpgMap(streamId: String, cid: String?) {
        val m = epgMap()
        if (cid == null) m.remove(streamId) else m[streamId] = cid
        p.edit().putString(accKey("epgMap"), JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun lastCategory(kind: String): String = p.getString(accKey("cat_$kind"), "") ?: ""
    fun setLastCategory(kind: String, id: String) = p.edit().putString(accKey("cat_$kind"), id).apply()
}
