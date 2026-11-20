package com.quickiptv.app

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

    fun open(ctx: Context, acc: Account) {
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
        .setConnectTimeoutMs(12000)
        .setReadTimeoutMs(20000)
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            if (live) 4000 else 15000,      // أقل بفر
            if (live) 20000 else 50000,     // أقصى بفر (ذاكرة محدودة)
            1000,                            // يبدأ التشغيل بعد ثانية
            if (live) 2000 else 3000,        // بعد التقطيع
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val renderers = DefaultRenderersFactory(ctx)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))   // http + ملفات محمّلة
        .setLoadControl(load)
        .setSeekBackIncrementMs(10_000)        // تأخير 10 ثواني
        .setSeekForwardIncrementMs(10_000)     // تقديم 10 ثواني
        .build()
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
        url = u
        Downloads.setWatching("live", true)
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(u))
        p.prepare()
        p.playWhenReady = true
    }

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

    fun release() {
        Downloads.setWatching("live", false)
        player?.release()
        player = null
        url = ""
    }
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
        }, "quickiptv-bg").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
