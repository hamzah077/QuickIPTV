package com.urrox.player

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
    val season: Int = seasonOf(sub),          // رقم الموسم والحلقة (للحلقات): لترتيب "وين وصلت" بالمسلسل
    val epNum: Int = epOf(sub),
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("type", type).put("title", title).put("sub", sub)
        .put("image", image).put("url", url).put("seriesId", seriesId).put("pos", pos).put("dur", dur).put("ts", ts)
        .put("season", season).put("ep", epNum)

    /** خلّصها (أو قريب من النهاية). */
    val finished get() = dur > 0 && pos >= dur - 60

    /** مشاهدة فعلية (مو تشغيل بالغلط): أكثر من دقيقتين أو خلّصها. */
    val meaningful get() = pos >= 120 || finished

    companion object {
        fun from(o: JSONObject): HistoryItem {
            val sub = o.optString("sub")
            return HistoryItem(
                o.optString("key"), o.optString("type"), o.optString("title"), sub,
                o.optString("image"), o.optString("url"), o.optString("seriesId"),
                o.optLong("pos"), o.optLong("dur"), o.optLong("ts"),
                // السجل القديم ما فيه الأرقام: ناخذها من النص "الموسم 1 · الحلقة 10"
                o.optInt("season", 0).takeIf { it > 0 } ?: seasonOf(sub),
                o.optInt("ep", 0).takeIf { it > 0 } ?: epOf(sub),
            )
        }

        private val reSeason = Regex("(?:الموسم|م)\\s*(\\d+)")
        private val reEp = Regex("(?:الحلقة|ح)\\s*(\\d+)")
        fun seasonOf(sub: String) = reSeason.find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        fun epOf(sub: String) = reEp.find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

/** التخزين على الجهاز (SharedPreferences + JSON): الحسابات، المفضلة، الاستئناف، السجل، إعدادات الدليل. */
object Store {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("urrox", Context.MODE_PRIVATE)
    }

    // ---------- المقدمة والتتر: يتعلّمها لكل مسلسل من طريقة مشاهدتك (آخر 3 مرات، ناخذ الوسط) ----------
    private val skipCache = java.util.concurrent.ConcurrentHashMap<String, List<Long>>()
    private fun skipList(k: String): List<Long> = skipCache.getOrPut(k) { (p.getString(k, "") ?: "").split(',').mapNotNull { it.toLongOrNull() } }
    private fun pushSkip(k: String, v: Long) {
        val l = (skipList(k) + v).takeLast(3)
        skipCache[k] = l
        p.edit().putString(k, l.joinToString(",")).apply()
    }
    private fun median(l: List<Long>): Long? = if (l.isEmpty()) null else l.sorted()[l.size / 2]

    /** تخطّيت المقدمة: من [start] إلى [end] (ملّي ثانية من بداية الحلقة). */
    fun learnIntro(series: String, start: Long, end: Long) {
        pushSkip("introS:$series", start); pushSkip("introE:$series", end)
    }

    /** بداية ونهاية المقدمة المتعلّمة لهالمسلسل (أو null). */
    fun intro(series: String): Pair<Long, Long>? {
        val s = median(skipList("introS:$series")) ?: return null
        val e = median(skipList("introE:$series")) ?: return null
        return if (e > s) s to e else null
    }

    /** تخطّيت التتر وكان باقي على نهاية الحلقة [leftMs]. */
    fun learnCredits(series: String, leftMs: Long) = pushSkip("credits:$series", leftMs)

    /** كم باقي على نهاية الحلقة لما يبدأ التتر (أو null لو ما تعلّمناه). */
    fun credits(series: String): Long? = median(skipList("credits:$series"))

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
    /** شكل الواجهة: auto | phone | tv */
    var uiMode: String
        get() = p.getString("uiMode", "auto") ?: "auto"
        set(v) = p.edit().putString("uiMode", v).apply()

    /** سألناه مرة عن واجهة التلفزيون (لما استخدم الريموت على جهاز انكشف جوال)؟ */
    var tvAsked: Boolean
        get() = p.getString("tvAsked", "") == "1"
        set(v) = p.edit().putString("tvAsked", if (v) "1" else "").apply()

    /** المسرّع: عدد الاتصالات لكل ملف. */
    var dlConnections: Int
        get() = p.getInt("dlConnections", 4)
        set(v) = p.edit().putInt("dlConnections", v).apply()

    /** عدد التحميلات بنفس الوقت. */
    var dlParallel: Int
        get() = p.getInt("dlParallel", 2)
        set(v) = p.edit().putInt("dlParallel", v).apply()

    /** شكل صفحة المباشر بالتلفزيون: a = البث يملى الشاشة (الافتراضي)، c = أعمدة، z = مشغّل كبير + قائمة */
    var liveStyle: String
        get() = p.getString("liveStyle", "a") ?: "a"
        set(v) = p.edit().putString("liveStyle", v).apply()

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
    // السجل في الذاكرة (ما نقرا ونحلّل JSON كل مرة) + قفل واحد لكل التعديلات (ما تتضارب)
    private val histLock = Any()
    @Volatile private var histCache: Pair<String, List<HistoryItem>>? = null

    fun history(): List<HistoryItem> {
        val k = accKey("history")
        histCache?.let { (ck, l) -> if (ck == k) return l }
        synchronized(histLock) {
            histCache?.let { (ck, l) -> if (ck == k) return l }
            val a = JSONArray(p.getString(k, "[]"))
            val l = (0 until a.length()).map { HistoryItem.from(a.getJSONObject(it)) }
            histCache = k to l
            return l
        }
    }

    private fun writeHistory(list: List<HistoryItem>) {
        val k = accKey("history")
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        histCache = k to list
        p.edit().putString(k, a.toString()).apply()
    }

    fun addHistory(h: HistoryItem) = synchronized(histLock) {
        // نحافظ على التقدّم المحفوظ للحلقة/الفيلم (التشغيل من جديد ما يصفّره)
        val old = history().firstOrNull { it.key == h.key }
        val item = if (old != null && h.pos == 0L) h.copy(pos = old.pos, dur = old.dur) else h
        writeHistory((listOf(item) + history().filter { it.key != h.key }).take(200))
    }

    fun saveProgress(key: String, pos: Long, dur: Long) = synchronized(histLock) {
        val list = history().map { if (it.key == key) it.copy(pos = pos, dur = dur, ts = System.currentTimeMillis()) else it }
        writeHistory(list.sortedByDescending { it.ts })
    }

    /**
     * وين وصلت في المسلسل: أبعد حلقة (بالموسم والحلقة) شاهدتها فعلاً. تشغيل حلقة قديمة بالغلط ما يغيّره.
     * لو ما فيه مشاهدة فعلية: آخر حلقة شغّلتها.
     */
    fun seriesFrontier(seriesId: String, list: List<HistoryItem> = history()): HistoryItem? {
        val eps = list.filter { it.type == "episode" && it.seriesId == seriesId }
        if (eps.isEmpty()) return null
        val real = eps.filter { it.meaningful }
        if (real.isEmpty()) return eps.maxByOrNull { it.ts }
        return real.maxWithOrNull(compareBy<HistoryItem>({ it.season }, { it.epNum }, { it.ts }))
    }

    /**
     * السجل للعرض: مسلسل واحد = مدخل واحد (الحلقة اللي وصلت لها)، بمكان آخر مرة تفاعلت فيها مع المسلسل.
     * الأفلام والقنوات زي ما هي.
     */
    fun displayHistory(): List<HistoryItem> {
        val all = history()
        val out = ArrayList<HistoryItem>()
        val seen = HashSet<String>()
        for (h in all) {             // مرتّبة من الأحدث
            if (h.type != "episode" || h.seriesId.isEmpty()) { out.add(h); continue }
            if (!seen.add(h.seriesId)) continue
            out.add(seriesFrontier(h.seriesId, all) ?: h)
        }
        return out
    }

    /** نقطة الاستئناف بالثواني (0 = من البداية أو خلص تقريباً). */
    fun resumePoint(key: String): Long {
        val h = history().firstOrNull { it.key == key } ?: return 0
        return if (h.dur > 0 && h.pos > 30 && h.pos < h.dur - 60) h.pos else 0
    }

    /** متابعة المشاهدة: الأفلام اللي ما خلّصتها + مدخل واحد لكل مسلسل (لو خلّصت الحلقة: الحلقة التالية). */
    fun continueWatching(): List<HistoryItem> =
        displayHistory().filter { it.type != "live" && it.dur > 0 && it.pos > 30 && (it.type == "episode" || it.pos < it.dur - 60) }

    // ---------- دليل البرامج ----------
    var epgUrls: List<String>
        get() {
            val raw = p.getString(accKey("epgUrls"), null) ?: return Epg.DEFAULT_URLS
            val a = JSONArray(raw)
            return (0 until a.length()).map { a.getString(it) }
        }
        set(v) = p.edit().putString(accKey("epgUrls"), JSONArray(v).toString()).apply()

    /** الربط اليدوي: stream_id → معرّف قناة الدليل ("" = بدون دليل). */
    /** الربط اليدوي للدليل — محفوظ بالذاكرة (كان ينقرا من التخزين لكل سطر قناة = بطء بالسحب). */
    @Volatile private var epgMapCache: Pair<String, Map<String, String>>? = null

    fun epgMap(): Map<String, String> {
        val k = accKey("epgMap")
        epgMapCache?.takeIf { it.first == k }?.let { return it.second }
        val o = JSONObject(p.getString(k, "{}"))
        val m = HashMap<String, String>()
        o.keys().forEach { m[it] = o.optString(it) }
        epgMapCache = k to m
        return m
    }

    fun setEpgMap(streamId: String, cid: String?) {
        val m = HashMap(epgMap())
        if (cid == null) m.remove(streamId) else m[streamId] = cid
        p.edit().putString(accKey("epgMap"), JSONObject(m as Map<*, *>).toString()).apply()
        epgMapCache = accKey("epgMap") to m
    }

    fun lastCategory(kind: String): String = p.getString(accKey("cat_$kind"), "") ?: ""
    fun setLastCategory(kind: String, id: String) = p.edit().putString(accKey("cat_$kind"), id).apply()
}
