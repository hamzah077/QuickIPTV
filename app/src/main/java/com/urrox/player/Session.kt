package com.urrox.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** الحالة المشتركة بين الشاشات: الحساب الحالي وقائمة التشغيل للمشغّل. */
object Session {
    var api: Xtream? = null
    var userInfo: org.json.JSONObject? = null

    // قائمة القنوات اللي انفتح منها المشغّل (للتنقل فوق/تحت بالريموت)
    var playlist: List<Channel> = emptyList()
    var playIndex: Int = 0
    var playCategory: String = ""

    /** السيرفر ما يشغّل HLS (اشتغلت TS بعد فشل HLS): القنوات الجاية تبدأ TS على طول بدون انتظار الفشل. */
    @Volatile var preferTs = false

    fun open(ctx: Context, acc: Account) {
        preferTs = false
        api = Xtream(acc, ctx.cacheDir)
        Store.currentId = acc.id
    }
}

/**
 * مشغّل خفيف للأجهزة الضعيفة:
 * - بداية سريعة (يبدأ بعد ثانية بفر)، وذاكرة بفر محدودة.
 * - فك الفيديو بقطع الجهاز، ولو فشل يرجع لبديل تلقائياً.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(ctx: Context, live: Boolean): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(Xtream.UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(7000)          // سيرفر ما يرد = نعرف بسرعة (ونجرب الصيغة الثانية) بدل انتظار طويل
        .setReadTimeoutMs(15000)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (live) 4000 else 15000,      // أقل بفر
            if (live) 20000 else 50000,     // أقصى بفر (ذاكرة محدودة)
            500,                             // يبدأ التشغيل بعد نص ثانية (بدل ثانية) = بداية أسرع
            if (live) 2000 else 3000,        // بعد التقطيع
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val renderers = DefaultRenderersFactory(ctx)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    // شاشات التلفزيون: الوضع غير المتزامن لفك الفيديو (الافتراضي بأندرويد 12+) يجمّد الصورة على بعض
    // الشاشات والصوت يكمل — نرجع للوضع العادي المجرّب على كل الأجهزة
    if (Device.autoTv(ctx)) renderers.forceDisableMediaCodecAsynchronousQueueing()
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(FastSourceFactory(ctx, MemoRedirects(http)))   // http + ملفات محمّلة، بداية سريعة لبث TS/HLS
        .setLoadControl(load)
        .setSeekBackIncrementMs(10_000)        // تأخير 10 ثواني
        .setSeekForwardIncrementMs(10_000)     // تقديم 10 ثواني
        .build()
}

/**
 * يحفظ التحويل (redirect) لروابط الأفلام والحلقات: سيرفرات Xtream تحوّل كل طلب لعنوان ثاني،
 * والمشغّل يطلب الملف أكثر من مرة قبل ما يبدأ (البداية، النهاية، الرجوع…). بدل ما نمر على
 * التحويل كل مرة، نروح للعنوان النهائي مباشرة = بداية أسرع وتقديم أسرع.
 * لو العنوان المحفوظ ما اشتغل (انتهت صلاحيته) نرجع للرابط الأصلي تلقائياً.
 */
@OptIn(UnstableApi::class)
private class MemoRedirects(private val up: androidx.media3.datasource.DataSource.Factory) : androidx.media3.datasource.DataSource.Factory {
    override fun createDataSource(): androidx.media3.datasource.DataSource = Src(up.createDataSource())

    private class Src(private val up: androidx.media3.datasource.DataSource) : androidx.media3.datasource.DataSource {
        override fun addTransferListener(l: androidx.media3.datasource.TransferListener) = up.addTransferListener(l)

        override fun open(spec: androidx.media3.datasource.DataSpec): Long {
            val orig = spec.uri.toString()
            val vod = orig.startsWith("http") && (orig.contains("/movie/") || orig.contains("/series/") || orig.contains("/live/"))
            if (!vod) return up.open(spec)
            cache(orig)?.let { fin ->
                try { return up.open(spec.withUri(android.net.Uri.parse(fin))) }
                catch (e: java.io.IOException) { drop(orig); runCatching { up.close() } }
            }
            val n = up.open(spec)
            val fin = up.uri?.toString()
            if (fin != null && fin != orig) put(orig, fin)
            return n
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = up.read(buffer, offset, length)
        override fun getUri(): android.net.Uri? = up.uri
        override fun getResponseHeaders(): Map<String, List<String>> = up.responseHeaders
        override fun close() = up.close()
    }

    companion object {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        fun cache(u: String): String? = map[u]?.takeIf { System.currentTimeMillis() - it.second < 20 * 60_000 }?.first
        fun put(u: String, f: String) { if (map.size > 64) map.clear(); map[u] = f to System.currentTimeMillis() }
        fun drop(u: String) { map.remove(u) }
    }
}

/**
 * مصدر الفيديو مع إعدادات بداية سريعة لبث IPTV:
 * كثير من القنوات (TS/HLS) ما تعلّم الإطارات المفتاحية بالطريقة القياسية، فالمشغّل ينتظر ثواني لين
 * يلقى إطار "كامل" قبل ما يعرض. نسمح بالبداية من أول إطار مفتاحي يلقاه = الصورة تطلع أسرع بكثير.
 */
@OptIn(UnstableApi::class)
private class FastSourceFactory(ctx: Context, http: androidx.media3.datasource.DataSource.Factory) :
    androidx.media3.exoplayer.source.MediaSource.Factory {
    private val dsf = DefaultDataSource.Factory(ctx, http)
    private val tsFlags = androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    private val def = DefaultMediaSourceFactory(dsf, androidx.media3.extractor.DefaultExtractorsFactory().setTsExtractorFlags(tsFlags))
    private val hls = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dsf)
        .setExtractorFactory(androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(tsFlags, true))
        .setAllowChunklessPreparation(true)

    override fun setDrmSessionManagerProvider(p: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): androidx.media3.exoplayer.source.MediaSource.Factory {
        def.setDrmSessionManagerProvider(p); hls.setDrmSessionManagerProvider(p); return this
    }

    override fun setLoadErrorHandlingPolicy(p: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): androidx.media3.exoplayer.source.MediaSource.Factory {
        def.setLoadErrorHandlingPolicy(p); hls.setLoadErrorHandlingPolicy(p); return this
    }

    override fun getSupportedTypes(): IntArray = def.supportedTypes

    override fun createMediaSource(item: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource {
        val u = item.localConfiguration?.uri?.toString().orEmpty().substringBefore('?').lowercase()
        return if (u.endsWith(".m3u8")) hls.createMediaSource(item) else def.createMediaSource(item)
    }
}

/**
 * مشغّل البث المباشر مشترك بين الشاشة الصغيرة وملء الشاشة:
 * لما تكبّر القناة ما ينقطع البث ولا يعيد التحميل — نفس المشغّل ينتقل للشاشة الكبيرة.
 */
object LivePlayer {
    var player: ExoPlayer? = null
        private set
    var url: String = ""
        private set
    /** القناة شغّالة من صفحة المباشر وبترجع لها بعد ملء الشاشة (لا تطفي المشغّل). */
    var keepForPreview = false

    fun get(ctx: Context): ExoPlayer = player ?: buildPlayer(ctx.applicationContext, live = true).also { player = it }

    /** يشغّل الرابط، إلا لو هو نفسه شغّال (ما نعيد الاتصال). */
    fun play(ctx: Context, u: String, force: Boolean = false) {
        val p = get(ctx)
        if (!force && u == url && p.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            p.playWhenReady = true
            return
        }
        // كانت فيه قناة متصلة؟ لازم نعطي السيرفر لحظة يقفل اتصالها قبل نفتح الجديدة —
        // أغلب السيرفرات تسمح باتصال واحد، ولو فتحنا على طول يرفض الجديدة = شاشة سودا والبث يقفل
        val switching = url.isNotEmpty() && p.playbackState != androidx.media3.common.Player.STATE_IDLE
        url = u
        Downloads.setWatching("live", true)
        p.stop()                                  // نقفل اتصال القناة السابقة قبل نفتح الجديدة (حد الاتصالات)
        pending?.let { main.removeCallbacks(it) }
        val start = Runnable {
            pending = null
            if (player !== p || url != u) return@Runnable          // تغيّرت القناة مرة ثانية أثناء الانتظار
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(u))
            p.prepare()
            p.playWhenReady = true
        }
        if (switching) { pending = start; main.postDelayed(start, 350) } else start.run()
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null

    /**
     * حارس بعد نقل البث لشاشة ثانية (تكبير/تصغير): بعض أجهزة التلفزيون تعلّق الصورة لما تتغيّر الشاشة.
     * بعد 3 ثواني: لو ما وصلت صور جديدة أو البث ما تقدّم → نعيد الاتصال بنفس القناة تلقائياً.
     */
    @OptIn(UnstableApi::class)
    fun healAfterSwitch(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
        val p = player ?: return
        val app = ctx.applicationContext
        scope.launch {
            // نعيد الاتصال بس لو البث فعلاً واقف (خطأ/انتهى) — عدّاد الصور يتصفّر طبيعي لما تتغيّر الشاشة
            // فما نعتمد عليه (كان يسبب إعادة اتصال وبافرينج بدون داعي).
            delay(4000)
            if (player !== p || !p.playWhenReady || url.isEmpty()) return@launch
            val state = p.playbackState
            if (state == androidx.media3.common.Player.STATE_IDLE || state == androidx.media3.common.Player.STATE_ENDED) play(app, url, force = true)
        }
    }

    // ---------- حارس الصورة: الصوت شغّال والصورة واقفة؟ نصلّحها تلقائياً ----------
    /** الشاشة اللي تعرض البث الحين (المعاينة أو ملء الشاشة) */
    private var view: androidx.media3.ui.PlayerView? = null
    private var lastFrames = -1
    private var stuckChecks = 0
    private var fixes = 0
    private var fixedUrl = ""

    /** الشاشة صارت تعرض البث: نبدأ نراقب إن الصور تتحرك */
    fun watch(pv: androidx.media3.ui.PlayerView) {
        view = pv
        lastFrames = -1; stuckChecks = 0
        main.removeCallbacks(guard); main.postDelayed(guard, 2000)
    }

    fun unwatch(pv: androidx.media3.ui.PlayerView) {
        if (view === pv) { view = null; main.removeCallbacks(guard) }
    }

    @OptIn(UnstableApi::class)
    private val guard: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pv = view ?: return
            main.postDelayed(this, 1500)
            if (url != fixedUrl) { fixedUrl = url; fixes = 0 }
            // نراقب بس وهو فعلاً يشتغل (مو موقّف ولا يحمّل) وفيه فيديو
            if (!p.isPlaying || p.videoFormat == null || !pv.isShown) { lastFrames = -1; stuckChecks = 0; return }
            val c = p.videoDecoderCounters ?: return
            c.ensureUpdated()
            val n = c.renderedOutputBufferCount
            if (n != lastFrames) { lastFrames = n; stuckChecks = 0; return }
            if (++stuckChecks < 2 || fixes >= 3) return          // ~3 ثواني بدون صور جديدة
            stuckChecks = 0; fixes++
            if (fixes == 1) {
                // أول محاولة: نعيد ربط الشاشة (بدون ما ينقطع الصوت)
                pv.player = null; pv.player = p
            } else {
                // ما نفع: نعيد الاتصال بنفس القناة
                val u = url
                if (u.isNotEmpty()) play(pv.context, u, force = true)
            }
        }
    }

    fun release() {
        main.removeCallbacks(guard); view = null
        pending?.let { main.removeCallbacks(it) }; pending = null
        Downloads.setWatching("live", false)
        player?.release()
        player = null
        url = ""
    }
}

/**
 * يربط المشغّل بالشاشة أول ما يكون سطح العرض جاهز. لو ربطناه قبل، المشغّل يترك الشاشة القديمة
 * ويعرض على "لا شي" لحظة = ومضة سودا. كذا الصورة تبقى على القديمة لين الجديدة تجهز.
 */
fun androidx.media3.ui.PlayerView.attachSmooth(p: androidx.media3.common.Player) {
    val sv = videoSurfaceView as? android.view.SurfaceView
    if (sv == null || sv.holder.surface?.isValid == true) { setTag(R.id.pending_player, null); player = p; return }
    val pv = this
    setTag(R.id.pending_player, p)
    sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(h: android.view.SurfaceHolder) {
            h.removeCallback(this)
            // لسا مطلوب (ما طلعنا من الشاشة قبل ما تجهز)؟
            if (pv.getTag(R.id.pending_player) === p) { pv.setTag(R.id.pending_player, null); if (pv.player == null) pv.player = p }
        }
        override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
        override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
    })
}

/** يفك المشغّل عن الشاشة (ويلغي أي ربط ينتظر). */
fun androidx.media3.ui.PlayerView.detachPlayer() {
    setTag(R.id.pending_player, null)
    player = null
}

/**
 * تسليم مشغّل الفيلم/الحلقة بين المشغّل المصغّر (صفحة التفاصيل) وملء الشاشة:
 * نفس المشغّل ينتقل بدون ما يوقف أو يعيد التحميل.
 */
object VodHandoff {
    var player: ExoPlayer? = null
    var key: String = ""
    var req: VodReq? = null       // الحلقة/الفيلم الحالي (ممكن تغيّر بملء الشاشة: الحلقة التالية)
    var watching = false          // ماسك اتصال مشاهدة من حد الاشتراك
    var ownerAlive = false        // صفحة التفاصيل اللي بترجع لها لسا موجودة
    var toPortrait = false        // ضغط زر التصغير: نرجع عمودي ونكمل في المصغّر حتى لو الجوال بيده أفقي

    fun take(): ExoPlayer? = player.also { player = null }

    fun drop() {
        player?.release(); player = null
        if (watching) { Downloads.setWatching("vod", false); watching = false }
        key = ""; req = null
    }
}

/** طلب تشغيل فيلم/حلقة. */
data class VodReq(val url: String, val key: String, val title: String, val sub: String, val type: String)

/**
 * حلقات المسلسل الحالي بالترتيب (كل المواسم ورا بعض) — للحلقة السابقة/التالية
 * في المشغّل الصغير وملء الشاشة.
 */
object SeriesQueue {
    var seriesId = ""
    var name = ""
    var list: List<Episode> = emptyList()
    var index = -1

    fun set(id: String, title: String, seasons: Map<Int, List<Episode>>, current: Episode) {
        seriesId = id; name = title
        list = seasons.toSortedMap().values.flatMap { eps -> eps.sortedBy { it.num } }
        index = list.indexOfFirst { it.id == current.id }
    }

    fun indexOfKey(key: String) = list.indexOfFirst { "ep:${it.id}" == key }

    fun req(i: Int): VodReq? {
        val e = list.getOrNull(i) ?: return null
        val api = Session.api ?: return null
        return VodReq(api.episodeUrl(e.id, e.ext), "ep:${e.id}", name, "الموسم ${e.season} · الحلقة ${e.num}", "episode")
    }

    /** نفس المسلسل وهذي الحلقة منه؟ */
    fun has(key: String) = key.startsWith("ep:") && indexOfKey(key) >= 0

    fun nav(key: String, open: (Int) -> Unit): EpisodeNav? {
        if (!has(key)) return null
        val sid = seriesId
        return object : EpisodeNav {
            override val seriesId = sid
            private val i get() = indexOfKey(key)
            override val hasPrev get() = i > 0
            override val hasNext get() = i in 0 until list.size - 1
            override fun prev() { if (hasPrev) open(i - 1) }
            override fun next() { if (hasNext) open(i + 1) }
        }
    }
}

/**
 * خيوط خلفية بأولوية منخفضة للشغل الثقيل (قراءة القوائم والدليل):
 * على الأجهزة الضعيفة الواجهة تاخذ الأولوية دايماً، فما تعلّق الشاشة وقت التحديث.
 */
object Bg {
    val io: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "urrox-bg").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
