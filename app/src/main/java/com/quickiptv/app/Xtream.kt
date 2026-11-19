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
    private val mem = HashMap<String, Any>()

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

    /** قراءة مصفوفة JSON كبيرة عنصر عنصر (JsonReader) بدون ما نبني نص أو كائنات ضخمة. */
    private inline fun <T> readArray(f: File, crossinline item: (Map<String, String>) -> T?): List<T> {
        val out = ArrayList<T>()
        android.util.JsonReader(java.io.InputStreamReader(f.inputStream().buffered(64 * 1024), Charsets.UTF_8)).use { r ->
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) {   // بعض السيرفرات ترجع {} لما القائمة فاضية
                r.skipValue(); return out
            }
            r.beginArray()
            val m = HashMap<String, String>(24)
            while (r.hasNext()) {
                m.clear()
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> m[k] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> m[k] = if (r.nextBoolean()) "1" else "0"
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                item(m)?.let { out.add(it) }
            }
            r.endArray()
        }
        return out
    }

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
    private fun cats(action: String): List<Category> {
        @Suppress("UNCHECKED_CAST")
        mem[action]?.let { return it as List<Category> }
        val a = JSONArray(api(action))
        val out = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
        mem[action] = out
        return out
    }

    fun liveCategories() = cats("get_live_categories")
    fun vodCategories() = cats("get_vod_categories")
    fun seriesCategories() = cats("get_series_categories")

    /** القنوات محمّلة بالذاكرة؟ (عشان ما نحجب الواجهة). */
    fun hasLive(): Boolean = mem.containsKey("live")

    @Suppress("UNCHECKED_CAST")
    fun liveStreams(force: Boolean = false): List<Channel> {
        if (!force) mem["live"]?.let { return it as List<Channel> }
        var i = 0
        val out = readArray(apiFile("get_live_streams", force)) { o ->
            i++
            Channel(
                o["stream_id"] ?: return@readArray null, o["num"]?.toIntOrNull() ?: i, (o["name"] ?: "").trim(),
                o["stream_icon"] ?: "", o["category_id"] ?: "", o["epg_channel_id"] ?: "", o["tv_archive"] == "1",
            )
        }
        mem["live"] = out
        return out
    }

    @Suppress("UNCHECKED_CAST")
    fun vods(series: Boolean): List<Vod> {
        val key = if (series) "series" else "vod"
        mem[key]?.let { return it as List<Vod> }
        val out = readArray(apiFile(if (series) "get_series" else "get_vod_streams")) { o ->
            Vod(
                o[if (series) "series_id" else "stream_id"] ?: return@readArray null, (o["name"] ?: "").trim(),
                o[if (series) "cover" else "stream_icon"] ?: "", o["category_id"] ?: "",
                (o["container_extension"] ?: "").ifEmpty { "mp4" }, o["rating"] ?: "",
                ((o["added"] ?: "").ifEmpty { o["last_modified"] ?: "" }).toLongOrNull() ?: 0L, series,
            )
        }
        mem[key] = out
        return out
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
