package com.quickiptv.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class Category(val id: String, val name: String)

data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val icon: String,
    val categoryId: String,
    val epgId: String,
    val archive: Boolean,
)

data class Vod(
    val id: String,
    val name: String,
    val icon: String,
    val categoryId: String,
    val ext: String,
    val rating: String,
    val added: Long,
    val isSeries: Boolean,
)

data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val plot: String, val image: String, val duration: String)

data class Program(val title: String, val desc: String, val start: Long, val end: Long) {
    val isNow: Boolean get() { val n = System.currentTimeMillis() / 1000; return n in start until end }
    val progress: Float get() {
        val n = System.currentTimeMillis() / 1000
        return ((n - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

class XtreamError(msg: String) : Exception(msg)

/**
 * عميل Xtream Codes. كل الدوال تحجب (تُستدعى داخل Dispatchers.IO).
 * القوائم الكبيرة تنحفظ على الجهاز 6 ساعات، وتنقرأ من الذاكرة بعد أول مرة → فتح فوري.
 */
class Xtream(val acc: Account, private val cacheDir: File) {
    private val base = acc.server.trimEnd('/')
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** القائمة تنقرا مرة وحدة بس حتى لو طلبتها أكثر من صفحة بنفس اللحظة (الثانية تنتظر الأولى). */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> once(key: String, force: Boolean = false, load: () -> T): T {
        if (!force) mem[key]?.let { return it as T }
        synchronized(locks.getOrPut(key) { Any() }) {
            if (!force) mem[key]?.let { return it as T }
            return load().also { mem[key] = it }
        }
    }

    /** موجودة بالذاكرة الحين؟ (بدون ما نقراها). */
    fun loadedNow(key: String) = mem.containsKey(key)

    companion object {
        const val UA = "IPTVSmartersPro"
        private const val TTL = 6 * 3600 * 1000L

        fun normalizeServer(s: String): String {
            var x = s.trim().trimEnd('/')
            if (!x.startsWith("http://") && !x.startsWith("https://")) x = "http://$x"
            return x
        }

        /** رابط اشتراك كامل (get.php?username=…&password=…) → (السيرفر، المستخدم، كلمة المرور). */
        fun parseLink(text: String): Triple<String, String, String>? {
            val t = text.trim()
            if (!t.contains("username=") || !t.contains("password=")) return null
            return try {
                val u = URL(if (t.startsWith("http")) t else "http://$t")
                val q = (u.query ?: "").split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
                }.toMap()
                val user = q["username"] ?: return null
                val pass = q["password"] ?: return null
                val port = if (u.port > 0) ":${u.port}" else ""
                Triple("${u.protocol}://${u.host}$port", user, pass)
            } catch (e: Exception) { null }
        }

        fun http(url: String, timeout: Int = 20000): ByteArray {
            var u = url
            repeat(4) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 12000
                c.readTimeout = timeout
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Encoding", "gzip")
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        u = URL(URL(u), c.getHeaderField("Location") ?: throw XtreamError("تحويل بدون رابط")).toString()
                        return@repeat
                    }
                    if (code != 200) throw XtreamError(if (code == 401 || code == 403) "اسم المستخدم أو كلمة المرور غلط" else "السيرفر رد بخطأ $code")
                    val ins = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(c.inputStream) else c.inputStream
                    return ins.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            }
            throw XtreamError("تحويلات كثيرة")
        }

        /** تنزيل مباشر لملف (بدون ما نحمّل الرد كامل بالذاكرة — مهم للقوائم الكبيرة على الأجهزة الضعيفة). */
        fun httpToFile(url: String, out: File, timeout: Int = 120000) {
            var u = url
            repeat(4) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = timeout
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept-Encoding", "gzip")
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        u = URL(URL(u), c.getHeaderField("Location") ?: throw XtreamError("تحويل بدون رابط")).toString()
                        return@repeat
                    }
                    if (code != 200) throw XtreamError(if (code == 401 || code == 403) "اسم المستخدم أو كلمة المرور غلط" else "السيرفر رد بخطأ $code")
                    val ins = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(c.inputStream) else c.inputStream
                    out.parentFile?.mkdirs()
                    val tmp = File(out.path + ".tmp")
                    ins.use { i -> tmp.outputStream().buffered().use { o -> i.copyTo(o, 64 * 1024) } }
                    if (tmp.length() < 2) throw XtreamError("رد فاضي من السيرفر")
                    tmp.renameTo(out)
                    return
                } finally {
                    c.disconnect()
                }
            }
            throw XtreamError("تحويلات كثيرة")
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(java.util.Locale.US, it) }
    }

    private fun apiUrl(action: String?, extra: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder("$base/player_api.php?username=")
            .append(URLEncoder.encode(acc.username, "UTF-8"))
            .append("&password=").append(URLEncoder.encode(acc.password, "UTF-8"))
        if (action != null) sb.append("&action=").append(action)
        extra.forEach { (k, v) -> sb.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8")) }
        return sb.toString()
    }

    /** ملف JSON على الجهاز للقائمة (ينزل مرة كل 6 ساعات، ولو انقطع النت نستخدم آخر نسخة). */
    private fun apiFile(action: String, force: Boolean = false): File {
        val url = apiUrl(action)
        val f = File(cacheDir, "xt/${md5(url)}.json")
        if (!force && f.exists() && f.length() > 2 && System.currentTimeMillis() - f.lastModified() < TTL) return f
        return try {
            httpToFile(url, f)
            f
        } catch (e: Exception) {
            if (f.exists() && f.length() > 2) f else throw XtreamError(friendly(e))
        }
    }

    /**
     * قراءة مصفوفة JSON كبيرة عنصر عنصر (JsonReader) — نحفظ بس الحقول اللي نحتاجها
     * (السيرفر يرسل ~20 حقل لكل فيلم ونحن نستخدم 7) → ذاكرة وقت أقل بكثير على الأجهزة الضعيفة.
     */
    private inline fun <T> readArray(f: File, keys: Array<String>, crossinline item: (Array<String?>) -> T?): List<T> {
        val out = ArrayList<T>()
        val idx = HashMap<String, Int>(keys.size * 2).apply { keys.forEachIndexed { i, k -> put(k, i) } }
        val v = arrayOfNulls<String>(keys.size)
        android.util.JsonReader(java.io.InputStreamReader(f.inputStream().buffered(64 * 1024), Charsets.UTF_8)).use { r ->
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) {   // بعض السيرفرات ترجع {} لما القائمة فاضية
                r.skipValue(); return out
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                java.util.Arrays.fill(v, null)
                r.beginObject()
                while (r.hasNext()) {
                    val i = idx[r.nextName()]
                    if (i == null) { r.skipValue(); continue }
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> v[i] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> v[i] = if (r.nextBoolean()) "1" else "0"
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                item(v)?.let { out.add(it) }
            }
            r.endArray()
        }
        out.trimToSize()
        return out
    }

    // ---------- نسخة سريعة على الجهاز (ثنائية) ----------
    // أول مرة نقرا JSON (بطيء)، ونحفظ النتيجة بصيغة مضغوطة. المرات الجاية نقراها مباشرة (أسرع بكثير).
    private fun binFor(json: File) = File(json.path + ".v1.bin")

    private fun <T> readBin(json: File, read: (java.io.DataInputStream) -> T): List<T>? {
        val b = binFor(json)
        if (!b.exists() || b.lastModified() < json.lastModified()) return null
        return runCatching {
            java.io.DataInputStream(b.inputStream().buffered(64 * 1024)).use { ins ->
                val n = ins.readInt()
                ArrayList<T>(n).apply { repeat(n) { add(read(ins)) } }
            }
        }.getOrNull()
    }

    private fun <T> writeBin(json: File, list: List<T>, write: (java.io.DataOutputStream, T) -> Unit) {
        runCatching {
            val b = binFor(json)
            val tmp = File(b.path + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered(64 * 1024)).use { o ->
                o.writeInt(list.size); list.forEach { write(o, it) }
            }
            tmp.renameTo(b)
        }
    }

    private fun java.io.DataOutputStream.str(s: String) = writeUTF(if (s.length > 2000) s.take(2000) else s)

    /** نص JSON من السيرفر، مع كاش على الجهاز (ولو انقطع النت نرجع آخر نسخة محفوظة). */
    private fun api(action: String?, extra: Map<String, String> = emptyMap(), cache: Boolean = true, force: Boolean = false): String {
        val url = apiUrl(action, extra)
        val f = File(cacheDir, "xt/${md5(url)}.json")
        if (cache && !force && f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) return f.readText()
        return try {
            val txt = String(http(url, 60000), Charsets.UTF_8)
            if (cache) {
                f.parentFile?.mkdirs()
                f.writeText(txt)
            }
            txt
        } catch (e: Exception) {
            if (cache && f.exists()) f.readText() else throw XtreamError(friendly(e))
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is XtreamError -> e.message ?: "خطأ"
        is java.net.UnknownHostException -> "ما قدرنا نوصل للسيرفر. تأكد من الرابط والإنترنت"
        is java.net.SocketTimeoutException -> "السيرفر ما رد في الوقت. جرّب مرة ثانية"
        else -> "تعذر الاتصال: ${e.message ?: e.javaClass.simpleName}"
    }

    fun clearCache() {
        mem.clear()
        File(cacheDir, "xt").deleteRecursively()
    }

    // ---------- الدخول ----------
    fun login(): JSONObject {
        val txt = api(null, cache = false)
        val o = try { JSONObject(txt) } catch (e: Exception) { throw XtreamError("رد غير متوقع من السيرفر") }
        val ui = o.optJSONObject("user_info") ?: throw XtreamError("بيانات الدخول غير صحيحة")
        if (ui.optInt("auth", 1) == 0) throw XtreamError("اسم المستخدم أو كلمة المرور غلط")
        val st = ui.optString("status", "Active")
        if (!st.equals("Active", true)) throw XtreamError("الاشتراك غير فعّال ($st)")
        return o
    }

    // ---------- التصنيفات والقوائم ----------
    private fun cats(action: String): List<Category> = once(action) {
        val a = runCatching { JSONArray(api(action)) }.getOrElse { JSONArray() }
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }

    fun liveCategories() = cats("get_live_categories")
    fun vodCategories() = cats("get_vod_categories")
    fun seriesCategories() = cats("get_series_categories")

    /** القنوات محمّلة بالذاكرة؟ (عشان ما نحجب الواجهة). */
    fun hasLive(): Boolean = mem.containsKey("live")

    fun liveStreams(force: Boolean = false): List<Channel> = once("live", force) {
        val f = apiFile("get_live_streams", force)
        readBin(f) { i -> Channel(i.readUTF(), i.readInt(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readBoolean()) }
            ?: run {
                var n = 0
                readArray(f, arrayOf("stream_id", "num", "name", "stream_icon", "category_id", "epg_channel_id", "tv_archive")) { o ->
                    n++
                    Channel(o[0] ?: return@readArray null, o[1]?.toIntOrNull() ?: n, (o[2] ?: "").trim(),
                        o[3] ?: "", o[4] ?: "", o[5] ?: "", o[6] == "1")
                }.also { list ->
                    writeBin(f, list) { w, c -> w.str(c.id); w.writeInt(c.num); w.str(c.name); w.str(c.icon); w.str(c.categoryId); w.str(c.epgId); w.writeBoolean(c.archive) }
                    saveSummaryLive(list)
                }
            }
    }

    fun vods(series: Boolean): List<Vod> = once(if (series) "series" else "vod") {
        val f = apiFile(if (series) "get_series" else "get_vod_streams")
        readBin(f) { i -> Vod(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), series) }
            ?: readArray(f, if (series) arrayOf("series_id", "name", "cover", "category_id", "container_extension", "rating", "last_modified", "last_modified")
                            else arrayOf("stream_id", "name", "stream_icon", "category_id", "container_extension", "rating", "added", "last_modified")) { o ->
                Vod(o[0] ?: return@readArray null, (o[1] ?: "").trim(), o[2] ?: "", o[3] ?: "",
                    (o[4] ?: "").ifEmpty { "mp4" }, o[5] ?: "", ((o[6] ?: "").ifEmpty { o[7] ?: "" }).toLongOrNull() ?: 0L, series)
            }.also { list ->
                writeBin(f, list) { w, v -> w.str(v.id); w.str(v.name); w.str(v.icon); w.str(v.categoryId); w.str(v.ext); w.str(v.rating); w.writeLong(v.added) }
                saveSummaryVod(series, list)
            }
    }

    // ---------- ملخص صغير للرئيسية ----------
    // الرئيسية تحتاج 20 فيلم و20 مسلسل وكم قناة بس — نحفظها بملف صغير عشان ما نقرا القوائم الكاملة عند الفتح.
    private fun summaryFile(kind: String) = File(cacheDir, "xt/summary_${md5(base + acc.username)}_$kind.bin")

    private fun saveSummaryVod(series: Boolean, list: List<Vod>) {
        val top = list.sortedByDescending { it.added }.take(20)
        runCatching {
            java.io.DataOutputStream(summaryFile(if (series) "series" else "vod").outputStream().buffered()).use { o ->
                o.writeInt(top.size)
                top.forEach { v -> o.str(v.id); o.str(v.name); o.str(v.icon); o.str(v.categoryId); o.str(v.ext); o.str(v.rating); o.writeLong(v.added) }
            }
        }
    }

    private fun saveSummaryLive(list: List<Channel>) {
        val top = list.take(30)
        runCatching {
            java.io.DataOutputStream(summaryFile("live").outputStream().buffered()).use { o ->
                o.writeInt(top.size)
                top.forEach { c -> o.str(c.id); o.writeInt(c.num); o.str(c.name); o.str(c.icon); o.str(c.categoryId); o.str(c.epgId); o.writeBoolean(c.archive) }
            }
        }
    }

    /** أحدث الأفلام/المسلسلات: من الذاكرة لو محمّلة، وإلا من الملخص الصغير (بدون قراءة القائمة كاملة). */
    fun newest(series: Boolean): List<Vod>? {
        val key = if (series) "series" else "vod"
        @Suppress("UNCHECKED_CAST")
        (mem[key] as? List<Vod>)?.let { return it.sortedByDescending { v -> v.added }.take(20) }
        val f = summaryFile(key)
        if (!f.exists()) return null
        return runCatching {
            java.io.DataInputStream(f.inputStream().buffered()).use { i ->
                List(i.readInt()) { Vod(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), series) }
            }
        }.getOrNull()
    }

    fun firstChannels(): List<Channel>? {
        @Suppress("UNCHECKED_CAST")
        (mem["live"] as? List<Channel>)?.let { return it.take(30) }
        val f = summaryFile("live")
        if (!f.exists()) return null
        return runCatching {
            java.io.DataInputStream(f.inputStream().buffered()).use { i ->
                List(i.readInt()) { Channel(i.readUTF(), i.readInt(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readBoolean()) }
            }
        }.getOrNull()
    }

    fun vodInfo(id: String): JSONObject =
        JSONObject(api("get_vod_info", mapOf("vod_id" to id))).optJSONObject("info") ?: JSONObject()

    /** معلومات المسلسل + الحلقات مرتبة حسب الموسم. */
    fun seriesInfo(id: String): Pair<JSONObject, Map<Int, List<Episode>>> {
        val o = JSONObject(api("get_series_info", mapOf("series_id" to id)))
        val info = o.optJSONObject("info") ?: JSONObject()
        val eps = o.optJSONObject("episodes") ?: JSONObject()
        val out = sortedMapOf<Int, List<Episode>>()
        eps.keys().forEach { sk ->
            val arr = eps.optJSONArray(sk) ?: return@forEach
            val list = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val ei = e.optJSONObject("info") ?: JSONObject()
                Episode(
                    e.optString("id"), e.optInt("season", sk.toIntOrNull() ?: 1), e.optInt("episode_num", i + 1),
                    e.optString("title"), e.optString("container_extension", "mp4").ifEmpty { "mp4" },
                    ei.optString("plot"), ei.optString("movie_image"), ei.optString("duration"),
                )
            }.sortedBy { it.num }
            out[sk.toIntOrNull() ?: 1] = list
        }
        return info to out
    }

    /** الدليل المختصر للقناة (طلب خفيف). */
    fun shortEpg(streamId: String, limit: Int = 10): List<Program> {
        val txt = api("get_short_epg", mapOf("stream_id" to streamId, "limit" to limit.toString()), cache = false)
        val a = JSONObject(txt).optJSONArray("epg_listings") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val s = o.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val e = o.optString("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
            Program(b64(o.optString("title")), b64(o.optString("description")), s, e)
        }.filter { it.end > System.currentTimeMillis() / 1000 - 60 }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

    // ---------- روابط التشغيل ----------
    private val up get() = "${enc(acc.username)}/${enc(acc.password)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun liveUrl(id: String, fmt: String = Store.liveFormat) = "$base/live/$up/$id.$fmt"
    fun vodUrl(id: String, ext: String) = "$base/movie/$up/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$base/series/$up/$id.$ext"
}
