package com.quickiptv.app

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * المشغّل الصغير تحت (الجوال فقط — زي يوتيوب): سحبت المشغّل لتحت أو طلعت من صفحته وهو شغّال،
 * الفيديو يكمل في شريط صغير فوق الأقسام وتقدر تتنقّل. تضغطه أو تسحبه لفوق = يرجع كامل.
 * - فيلم/حلقة: نفس المشغّل ينتقل من صفحة التفاصيل للشريط (بدون إعادة تحميل)، ويرجع لها.
 * - قناة: نفس مشغّل المباشر المشترك يكمل في الشريط.
 */
object MiniDock {
    var player: ExoPlayer? = null
        private set
    var vod: VodReq? = null
        private set
    var live = false
        private set
    var title = ""
        private set
    var sub = ""
        private set
    /** فتح صفحة التفاصيل من جديد لما تكبّره */
    var reopen: Intent? = null
        private set
    private var watching = false
    /** الواجهة (الشريط في الصفحة الرئيسية) تتحدّث */
    var onChange: (() -> Unit)? = null
    /** مكان الشريط السفلي بالشاشة (أعلاه) — المشغّل الصغير يكون فوقه مباشرة */
    var navTop = 0
    /** ارتفاع المشغّل الصغير الفعلي (عشان الصفحة تنزل لنفس مكانه بالضبط) */
    var dockH = 0

    // ---------- سحب المربع لفوق (الأصبع على الصفحة الرئيسية، والصفحة الحقيقية تتحرك معه) ----------
    /** وين وصل السحب: 1 مربع صغير … 0 صفحة كاملة */
    var dragT = 1f
    var onDragMove: ((Float) -> Unit)? = null
    var onDragEnd: ((Float, Float) -> Unit)? = null
    /** تركت إصبعك قبل ما تجهز الصفحة: تنفّذه أول ما تجهز */
    var pendingEnd: Pair<Float, Float>? = null
    /** الصفحة وصلت مكان المشغّل الصغير بحركتها: يظهر على طول (بدون حركة ثانية) */
    var instant = false

    val active get() = player != null || live

    /** صفحة التفاصيل تسلّم مشغّلها للشريط الصغير. */
    fun handVod(p: ExoPlayer, r: VodReq, watching: Boolean, reopen: Intent) {
        stop()
        player = p; vod = r; this.watching = watching; this.reopen = reopen
        title = SeriesQueue.name.takeIf { r.type == "episode" && it.isNotEmpty() } ?: r.title
        sub = r.sub
        p.playWhenReady = true
        onChange?.invoke()
    }

    /** صفحة التفاصيل تاخذ المشغّل منه (كبّرته). */
    fun takeVod(): Triple<ExoPlayer, VodReq, Boolean>? {
        val p = player ?: return null
        val r = vod ?: return null
        val t = Triple(p, r, watching)
        player = null; vod = null; watching = false; reopen = null
        onChange?.invoke()
        return t
    }

    fun dockLive(name: String, now: String) {
        if (player != null) stop()
        live = true; title = name; sub = now
        onChange?.invoke()
    }

    /** رجعت لصفحة المباشر: البث يرجع لشاشتها (بدون ما ينقطع). */
    fun undockLive() {
        if (!live) return
        live = false
        onChange?.invoke()
    }

    fun save() {
        val p = player ?: return
        val r = vod ?: return
        val d = p.duration
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return
        Store.saveProgress(r.key, p.currentPosition / 1000, d / 1000)
    }

    /** زر الإغلاق: يوقف ويحفظ وين وقفت. */
    fun stop() {
        save()
        player?.release(); player = null; vod = null; reopen = null
        if (watching) { Downloads.setWatching("vod", false); watching = false }
        if (live) { live = false; LivePlayer.release() }
        onChange?.invoke()
    }
}

/** صورة للصفحة اللي تحت (قبل ما تفتح صفحة التشغيل) — تبان ورا صفحة التشغيل وهي تنسحب لتحت. */
object PageSnapshot {
    var bitmap: android.graphics.Bitmap? = null
        private set

    /** لقطة للشاشة كما تبان (PixelCopy: تشمل الصور — الرسم العادي يطلعها فاضية) */
    fun take(a: android.app.Activity) {
        val v = a.window.decorView
        if (v.width <= 0 || v.height <= 0) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val b = android.graphics.Bitmap.createBitmap(v.width / 2, v.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(a.window, b, { r -> if (r == android.view.PixelCopy.SUCCESS) bitmap = b },
                    android.os.Handler(android.os.Looper.getMainLooper()))
                return
            }
        }
        take(v)
    }

    fun take(v: android.view.View) {
        if (v.width <= 0 || v.height <= 0) return
        runCatching {
            val s = 0.5f                // نص الدقة: كافي وهي مغطاة جزئياً وخفيف على الذاكرة
            val b = android.graphics.Bitmap.createBitmap((v.width * s).toInt(), (v.height * s).toInt(), android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(b)
            c.scale(s, s)
            v.draw(c)
            bitmap = b
        }
    }
}

/**
 * حاوية المشغّل اللي فوق الصفحة: سحب لتحت = المشغّل يتبع إصبعك ويصغر، ولو سحبت كفاية يروح للشريط الصغير.
 * الضغطات العادية والسحب يمين/يسار (التقديم) تروح للأزرار عادي — نمسك بس السحب لتحت الواضح.
 */
class DragDownFrame(ctx: Context) : FrameLayout(ctx) {
    var canDrag: () -> Boolean = { false }
    var onDocked: (() -> Unit)? = null
    /** بدل ما الحاوية تتحرك لحالها: نعطي المسافة للصفحة (تتحرك كلها) — المسافة، وهل خلص السحب */
    var onDrag: ((Float, Boolean) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if ((onDocked == null && onDrag == null) || !canDrag()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY; val dx = e.rawX - downX
                if (dy > slop * 2 && dy > abs(dx) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        val dy = maxOf(0f, e.rawY - downY)
        onDrag?.let { f ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> f(dy, false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; f(if (e.actionMasked == MotionEvent.ACTION_UP) dy else 0f, true) }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                pivotX = width / 2f; pivotY = 0f
                translationY = dy * 0.5f
                val s = 1f - minOf(dy, context.dp(320).toFloat()) / context.dp(900)
                scaleX = s; scaleY = s
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (e.actionMasked == MotionEvent.ACTION_UP && dy > context.dp(90)) {
                    onDocked?.invoke()
                    translationY = 0f; scaleX = 1f; scaleY = 1f
                } else {
                    animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
        }
        return true
    }
}


/** تسارع ناعم (بداية ونهاية هادية) — لدخول الفيديو للزاوية */
fun dockEase(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return if (v < 0.5f) 2 * v * v else 1 - (-2 * v + 2) * (-2 * v + 2) / 2
}
