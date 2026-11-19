package com.quickiptv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** عنصر تحميل (فيلم أو حلقة). */
class Dl(
    val id: String,
    val accId: String,
    val key: String,            // movie:123 | ep:456 (نفس مفتاح سجل المشاهدة → الاستئناف يشتغل)
    val kind: String,           // movie | episode
    val title: String,
    val group: String,          // اسم المسلسل (للحلقات)
    val seriesId: String,
    val season: Int,
    val epNum: Int,
    val poster: String,
    val url: String,
    val ext: String,
    val path: String,
    var status: String,         // queued | downloading | paused | completed | failed
    var received: Long = 0,
    var total: Long = 0,
    var error: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    @Volatile var speed: Long = 0
    @Volatile var waiting: Boolean = false     // ينتظر يخلص المشاهدة (حد اتصالات الاشتراك)

    val active get() = status == "queued" || status == "downloading"
    val frac get() = if (total > 0) (received.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val file get() = File(path)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("accId", accId).put("key", key).put("kind", kind)
        .put("title", title).put("group", group).put("seriesId", seriesId).put("season", season).put("epNum", epNum)
        .put("poster", poster).put("url", url).put("ext", ext).put("path", path).put("status", status)
        .put("received", received).put("total", total).put("error", error).put("addedAt", addedAt)

    companion object {
        fun from(o: JSONObject) = Dl(
            o.optString("id"), o.optString("accId"), o.optString("key"), o.optString("kind", "movie"),
            o.optString("title"), o.optString("group"), o.optString("seriesId"), o.optInt("season"), o.optInt("epNum"),
            o.optString("poster"), o.optString("url"), o.optString("ext", "mp4"), o.optString("path"),
            o.optString("status", "paused"), o.optLong("received"), o.optLong("total"), o.optString("error"),
            o.optLong("addedAt", System.currentTimeMillis()),
        )
    }
}

/**
 * محرّك التحميلات (نفس فكرة نسخة الكمبيوتر):
 * طابور + حد للتحميلات المتزامنة، إيقاف/استئناف من نفس المكان (HTTP Range)، إعادة محاولة تلقائية،
 * ومشاركة اتصالات الاشتراك مع المشاهدة (لو حدّك اتصال واحد، التحميل يتوقف وقت المشاهدة ويكمل بعدها).
 */
object Downloads {
    private const val CHUNK = 128 * 1024
    private const val MAX_RETRIES = 6
    private val LIMIT_CODES = setOf(401, 403, 429, 456, 458, 503, 509, 551)

    private lateinit var ctx: Context
    private lateinit var dbFile: File
    private val main = Handler(Looper.getMainLooper())
    private val lock = java.lang.Object()
    private val items = ArrayList<Dl>()
    fun snapshot(): List<Dl> = synchronized(lock) { items.toList() }
    private val threads = HashMap<String, AtomicBoolean>()     // id -> stop flag
    private val stopReason = HashMap<String, String>()
    private val listeners = ArrayList<(Dl?) -> Unit>()
    private var activeConns = 0
    private val watching = HashSet<String>()

    var maxParallel: Int
        get() = Store.dlParallel
        set(v) { Store.dlParallel = v.coerceIn(1, 4); pump() }

    fun init(c: Context) {
        ctx = c.applicationContext
        dbFile = File(ctx.filesDir, "downloads.json")
        runCatching {
            val a = JSONArray(dbFile.readText())
            for (i in 0 until a.length()) items.add(Dl.from(a.getJSONObject(i)))
        }
        // بعد إعادة تشغيل التطبيق: اللي كان شغّال يرجع للطابور ويكمل من نفس المكان
        items.forEach { d ->
            if (d.status == "downloading") d.status = "queued"
            if (d.status != "completed") d.received = File(d.path + ".part").length()
        }
    }

    // ---------- أدوات ----------
    val directory: File
        get() = (ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "downloads")).also { it.mkdirs() }

    fun freeSpace(): Long = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(0L)
    fun usedSpace(): Long = synchronized(lock) { items.filter { it.status == "completed" }.sumOf { it.total.coerceAtLeast(it.received) } }

    fun find(key: String): Dl? = synchronized(lock) {
        val acc = Store.currentId
        items.firstOrNull { it.key == key && it.accId == acc && it.status != "failed" }
    }

    /** الملف المحمّل كامل (لو موجود) عشان نشغّله بدون إنترنت. */
    fun localFile(key: String): File? = find(key)?.takeIf { it.status == "completed" }?.file?.takeIf { it.exists() && it.length() > 0 }

    fun addListener(l: (Dl?) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (Dl?) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private var lastSave = 0L
    private fun persist(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 3000) return
        lastSave = now
        val txt = synchronized(lock) { JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString() }
        runCatching { File(dbFile.path + ".tmp").apply { writeText(txt); renameTo(dbFile) } }
    }

    private fun emit(d: Dl?) {
        main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(d) } } }
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '.').take(120).ifEmpty { "video" }

    private fun uniquePath(folder: File, base: String, ext: String): String {
        val used = synchronized(lock) { items.map { it.path }.toSet() }
        var f = File(folder, "$base.$ext"); var n = 1
        while (f.exists() || f.path in used) { f = File(folder, "$base ($n).$ext"); n++ }
        return f.path
    }

    // ---------- واجهة عامة ----------
    /** يضيف فيلم أو حلقة للطابور. يرجّع رسالة خطأ لو ما انضاف. */
    fun add(key: String, kind: String, title: String, url: String, ext: String, poster: String,
            group: String = "", seriesId: String = "", season: Int = 0, epNum: Int = 0): String? {
        val acc = Store.currentId
        synchronized(lock) {
            items.firstOrNull { it.key == key && it.accId == acc }?.let { old ->
                if (old.status != "failed") return "موجود في التحميلات"
                items.remove(old)       // فشل قبل؟ نبدأ من جديد
            }
            val e = Regex("[^a-zA-Z0-9]").replace(ext, "").ifEmpty { "mp4" }
            var folder = directory
            if (kind == "episode" && group.isNotEmpty()) folder = File(folder, sanitize(group)).also { it.mkdirs() }
            val name = if (kind == "episode") sanitize("$group S%02dE%02d".format(java.util.Locale.US, season, epNum)) else sanitize(title)
            val d = Dl(UUID.randomUUID().toString().take(10), acc, key, kind, title, group, seriesId, season, epNum,
                poster, url, e, uniquePath(folder, name, e), "queued")
            items.add(0, d)
        }
        persist(); emit(null); pump()
        return null
    }

    fun pause(id: String) {
        synchronized(lock) {
            val d = items.firstOrNull { it.id == id } ?: return
            val stop = threads[id]
            if (stop != null) { stopReason[id] = "paused"; stop.set(true) }
            else if (d.status == "queued") { d.status = "paused"; d.speed = 0 }
        }
        persist(); emit(null)
    }

    fun resume(id: String) {
        synchronized(lock) {
            val d = items.firstOrNull { it.id == id } ?: return
            if (threads.containsKey(id) || d.status == "completed") return
            d.status = "queued"; d.error = ""
        }
        persist(); emit(null); pump()
    }

    /** حذف من القائمة ومن الجهاز. */
    fun remove(id: String) {
        val d = synchronized(lock) {
            val d = items.firstOrNull { it.id == id } ?: return
            threads[id]?.let { stopReason[id] = "removed"; it.set(true) }
            items.remove(d); d
        }
        Thread { Thread.sleep(400); File(d.path).delete(); File(d.path + ".part").delete(); File(d.path).parentFile?.takeIf { it != directory && it.list()?.isEmpty() == true }?.delete() }.start()
        persist(); emit(null); pump()
    }

    fun clearFailed() {
        synchronized(lock) { items.filter { it.status == "failed" } }.forEach { remove(it.id) }
    }

    /** مشغّل شغّال من الإنترنت = اتصال محجوز من حد الاشتراك (التحميل يتنازل عنه). */
    fun setWatching(tag: String, on: Boolean) {
        synchronized(lock) { if (on) watching.add(tag) else watching.remove(tag); lock.notifyAll() }
    }

    /** يرجّع true لو فيه تحميل بيتنازل عن اتصاله الحين (المشغّل ينتظر شوي قبل ما يبدأ). */
    fun mustYieldFor(): Boolean = synchronized(lock) { activeConns > budget() }

    private fun budget(): Int {
        val limit = Session.userInfo?.optString("max_connections")?.toIntOrNull() ?: 0
        val w = watching.size
        return if (limit > 0) (limit - w).coerceAtLeast(0) else if (w > 0) 1 else 64
    }

    fun pump() {
        if (!::ctx.isInitialized) return
        var started = false
        synchronized(lock) {
            var slots = maxParallel - threads.size
            for (d in items.filter { it.status == "queued" }.sortedBy { it.addedAt }) {
                if (slots <= 0) break
                val stop = AtomicBoolean(false)
                threads[d.id] = stop
                d.status = "downloading"
                slots--
                Thread({ worker(d, stop) }, "dl-${d.id}").apply { priority = Thread.MIN_PRIORITY }.start()
                started = true
            }
        }
        if (started || hasActive()) DownloadService.start(ctx)
    }

    fun hasActive(): Boolean = synchronized(lock) { items.any { it.active } }

    // ---------- العامل ----------
    private fun acquire(d: Dl, stop: AtomicBoolean): Boolean = synchronized(lock) {
        var waited = false
        while (!stop.get()) {
            if (activeConns < budget()) {
                activeConns++
                if (waited) { d.waiting = false; emit(d) }
                return true
            }
            if (!waited) { waited = true; d.waiting = true; d.speed = 0; emit(d) }
            lock.wait(500)
        }
        d.waiting = false
        false
    }

    private fun release() = synchronized(lock) { activeConns = (activeConns - 1).coerceAtLeast(0); lock.notifyAll() }
    private fun shouldYield(): Boolean = synchronized(lock) { activeConns > budget() }

    private class Yield : Exception()

    private fun worker(d: Dl, stop: AtomicBoolean) {
        var attempt = 0
        var finalStatus = "paused"
        try {
            while (!stop.get()) {
                if (!acquire(d, stop)) break
                try {
                    fetch(d, stop)
                    finalStatus = "completed"
                    break
                } catch (y: Yield) {
                    // تنازلنا عن الاتصال للمشاهدة — نرجع ننتظر ونكمل من نفس المكان
                    attempt = 0
                } catch (e: Exception) {
                    if (stop.get()) break
                    attempt++
                    d.speed = 0
                    val code = (e as? HttpCode)?.code ?: 0
                    if (code == 404 || code == 410 || attempt > MAX_RETRIES) {
                        finalStatus = "failed"
                        d.error = when {
                            code == 404 || code == 410 -> "الملف غير موجود على السيرفر"
                            code in LIMIT_CODES -> "السيرفر رفض (حد الاتصالات؟)"
                            else -> e.message ?: "انقطع الاتصال"
                        }
                        break
                    }
                    d.error = "إعادة المحاولة ($attempt)…"
                    emit(d)
                    val wait = (if (code in LIMIT_CODES) 8000L else 2500L) * attempt
                    val until = System.currentTimeMillis() + wait
                    while (!stop.get() && System.currentTimeMillis() < until) Thread.sleep(250)
                } finally {
                    release()
                }
            }
        } catch (_: InterruptedException) {
        }
        val reason = synchronized(lock) { threads.remove(d.id); stopReason.remove(d.id) }
        d.speed = 0; d.waiting = false
        if (reason != "removed") {
            d.status = if (stop.get()) "paused" else finalStatus
            if (d.status == "completed") d.error = ""
        }
        persist(); emit(null)
        pump()
        if (d.status == "completed") DownloadService.notifyDone(ctx, d)
    }

    private class HttpCode(val code: Int) : IOException("السيرفر رد بخطأ $code")

    private fun fetch(d: Dl, stop: AtomicBoolean) {
        val part = File(d.path + ".part")
        part.parentFile?.mkdirs()
        var have = if (part.exists()) part.length() else 0L
        var u = d.url
        var c: HttpURLConnection? = null
        try {
            for (hop in 0..4) {
                c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", Xtream.UA)
                    setRequestProperty("Accept-Encoding", "identity")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location") ?: throw IOException("تحويل بدون رابط")).toString()
                    c.disconnect(); c = null
                    continue
                }
                break
            }
            val conn = c ?: throw IOException("تحويلات كثيرة")
            val code = conn.responseCode
            if (code == 416 && have > 0 && d.total > 0 && have >= d.total) { finish(d, part); return }
            if (code != 200 && code != 206) throw HttpCode(code)
            if (code == 200 && have > 0) have = 0          // السيرفر ما يدعم الاستئناف → من البداية
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (len > 0) d.total = have + len
            if (d.total > 0 && freeSpace() < (d.total - have) + 50L * 1024 * 1024) throw IOException("المساحة ما تكفي على الجهاز")
            d.received = have; d.error = ""
            emit(d)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have); out.seek(have)
                conn.inputStream.use { ins ->
                    val buf = ByteArray(CHUNK)
                    var winBytes = 0L
                    var winStart = System.currentTimeMillis()
                    var lastEmit = 0L
                    while (true) {
                        if (stop.get()) return
                        if (shouldYield()) { d.speed = 0; emit(d); throw Yield() }
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        d.received += n
                        winBytes += n
                        val now = System.currentTimeMillis()
                        if (now - winStart >= 1000) { d.speed = winBytes * 1000 / (now - winStart); winBytes = 0; winStart = now }
                        if (now - lastEmit >= 700) { lastEmit = now; emit(d); persist(force = false) }
                    }
                }
            }
            if (d.total > 0 && d.received < d.total) throw IOException("انقطع التحميل قبل ما يكمل")
            if (d.total <= 0) d.total = d.received
            finish(d, part)
        } finally {
            c?.disconnect()
        }
    }

    private fun finish(d: Dl, part: File) {
        val f = File(d.path)
        f.delete()
        if (!part.renameTo(f)) throw IOException("تعذر حفظ الملف")
        d.received = f.length(); d.total = d.received
    }
}

/** حجم بأرقام إنجليزية، محاط بعلامة اتجاه (عشان ما يتلخبط داخل جملة عربية). */
fun fmtBytes(b: Long): String = "\u200E" + fmtBytesRaw(b) + "\u200E"

private fun fmtBytesRaw(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(java.util.Locale.US, b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / (1L shl 20).toDouble())
    b >= 1024 -> "%d KB".format(java.util.Locale.US, b / 1024)
    else -> "$b B"
}

/**
 * خدمة أمامية تخلي التحميل يكمل لو قفلت التطبيق أو طفت الشاشة،
 * مع إشعار فيه التقدّم. تقفل نفسها أول ما تخلص التحميلات.
 */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!Downloads.hasActive()) { stopSelf(); return }
            nm().notify(ID, build())
            h.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quickiptv:dl").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), 1 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */)
            else startForeground(ID, build())
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        h.removeCallbacks(tick); h.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        runCatching { wake?.release() }
        super.onDestroy()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun build(): Notification {
        val act = Downloads.snapshot().filter { it.active }
        val cur = act.firstOrNull { it.status == "downloading" } ?: act.firstOrNull()
        val title = if (act.size > 1) "جاري تحميل ${act.size} عناصر" else "جاري التحميل"
        val txt = cur?.let { d ->
            val n = if (d.kind == "episode") "${d.group} · م${d.season} ح${d.epNum}" else d.title
            n + (if (d.total > 0) " · ${(d.frac * 100).toInt()}%" else "") + (if (d.speed > 0) " · ${fmtBytes(d.speed)}/ث" else "")
        } ?: ""
        return builder(this).setContentTitle(title).setContentText(txt).setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(1000, ((cur?.frac ?: 0.0) * 1000).toInt(), cur == null || cur.total <= 0)
            .setContentIntent(openIntent(this)).setOnlyAlertOnce(true).build()
    }

    companion object {
        private const val ID = 4201
        private const val CH = "downloads"

        fun start(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CH, "التحميلات", NotificationManager.IMPORTANCE_LOW))
            }
        }

        @Suppress("DEPRECATION")
        private fun builder(ctx: Context): Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH) else Notification.Builder(ctx)

        private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra("open", "downloads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))

        fun notifyDone(ctx: Context, d: Dl) {
            channel(ctx)
            val n = if (d.kind == "episode") "${d.group} · م${d.season} ح${d.epNum}" else d.title
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(d.id.hashCode(),
                    builder(ctx).setContentTitle("اكتمل التحميل").setContentText(n).setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setAutoCancel(true).setContentIntent(openIntent(ctx)).build())
            }
        }
    }
}
