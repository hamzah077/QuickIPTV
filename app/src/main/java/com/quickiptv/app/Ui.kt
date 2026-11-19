package com.quickiptv.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * خط IBM Plex Sans Arabic (رخصة OFL المفتوحة — نفس خط نسخة الكمبيوتر).
 * عادي للنصوص، وSemiBold للعناوين (أوضح من Bold وما يطلع عن حدود السطر).
 */
object Fonts {
    val regular: Typeface by lazy { load("IBMPlexSansArabic-Regular.ttf", Typeface.NORMAL) }
    val bold: Typeface by lazy { load("IBMPlexSansArabic-SemiBold.ttf", Typeface.BOLD) }
    private fun load(f: String, style: Int): Typeface =
        runCatching { Typeface.createFromAsset(App.instance.assets, "fonts/$f") }.getOrElse { Typeface.defaultFromStyle(style) }
}

/** ألوان التطبيق (نفس ثيم نسخة الكمبيوتر الداكن). */
object C {
    const val BG = 0xFF111317.toInt()
    const val RAIL = 0xFF0C0E11.toInt()
    const val PANEL = 0xFF15181D.toInt()
    const val SURFACE = 0xFF1A1D23.toInt()
    const val SURFACE2 = 0xFF21252C.toInt()
    const val SURFACE3 = 0xFF2A2F38.toInt()
    const val BORDER = 0xFF23272F.toInt()
    const val HOVER = 0xFF1F232A.toInt()
    const val TEXT = 0xFFEEF0F4.toInt()
    const val TEXT2 = 0xFFC3C8D1.toInt()
    const val MUTED = 0xFF8B92A0.toInt()
    const val FAINT = 0xFF5C6370.toInt()
    const val ACCENT = 0xFF7AA2FF.toInt()
    const val ACCENT_SOFT = 0x337AA2FF
    const val LIVE = 0xFFE5484D.toInt()
    const val HEART = 0xFFF0506E.toInt()
    const val PRIMARY = 0xFFF1F3F7.toInt()
    const val PRIMARY_TEXT = 0xFF0E1116.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** تلفزيون؟ (وضع التلفزيون أو جهاز بدون شاشة لمس مثل TV Box). */
/**
 * واجهة التلفزيون؟ (كشف متدرّج)
 * 1) اختيارك من الإعدادات (جوال/تلفزيون) لو حددته.
 * 2) تلقائي: النظام يقول إنه تلفزيون، أو يدعم واجهات التلفزيون (Leanback)، أو ما فيه شاشة لمس.
 * 3) ولو انكشف جوال وبديت تستخدم أسهم الريموت، نسألك مرة وحدة (في MainActivity).
 */
object Device {
    @Volatile private var cache: Boolean? = null
    fun reset() { cache = null }
    fun isTv(ctx: Context): Boolean = cache ?: when (Store.uiMode) {
        "tv" -> true
        "phone" -> false
        else -> autoTv(ctx)
    }.also { cache = it }

    fun autoTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        if (pm.hasSystemFeature("android.software.leanback")) return true
        return !pm.hasSystemFeature("android.hardware.touchscreen")
    }
}

fun Context.isTv(): Boolean = Device.isTv(this)

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

/**
 * خلفية تتغيّر مع التركيز (الريموت) والاختيار واللمس.
 * التركيز: إطار أبيض واضح يبان من بعيد. الاختيار: أزرق خفيف.
 */
fun Context.focusBg(
    normal: Int = Color.TRANSPARENT,
    radius: Int = 10,
    selected: Int = C.ACCENT_SOFT,
    pressed: Int = C.SURFACE3,
): Drawable {
    val r = dpf(radius.toFloat())
    val sw = dp(2)
    return StateListDrawable().apply {
        // الإطار يظهر ويختفي بتلاشي ناعم بدل ما يقفز
        setEnterFadeDuration(130)
        setExitFadeDuration(110)
        addState(intArrayOf(android.R.attr.state_focused), rounded(C.SURFACE2, r, Color.WHITE, sw))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, r))
        addState(intArrayOf(android.R.attr.state_selected), rounded(selected, r))
        addState(intArrayOf(android.R.attr.state_activated), rounded(selected, r))
        addState(intArrayOf(), rounded(normal, r))
    }
}

/** يكبّر العنصر شوي لما يتركز عليه (إحساس التلفزيون). */
fun View.focusScale(scale: Float = 1.05f) {
    isFocusable = true
    setTag(R.id.own_focus_anim, true)
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
    }
}

fun Context.text(
    s: CharSequence = "",
    size: Float = 14f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv()) size * 1.18f else size)
    setTextColor(color)
    typeface = if (bold) Fonts.bold else Fonts.regular
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    includeFontPadding = false
    // محاذاة من بداية السطر حسب اتجاه الواجهة (يمين بالعربي) حتى لو النص إنجليزي
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
}

/** عنوان قسم صغير فوق مجموعة أسطر. */
fun Context.sectionHeader(t: String): TextView = text(t, 12.5f, C.MUTED).apply {
    setPadding(dp(16), dp(18), dp(16), dp(6))
}

/** خط فاصل رفيع. */
fun Context.divider(): View = View(this).apply {
    setBackgroundColor(C.BORDER)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
}

/**
 * سطر قائمة: أيقونة (يمين) ← العنوان والوصف ← القيمة ← سهم (يسار).
 * يشتغل باللمس والريموت (إطار أبيض لما يتحدد).
 */
fun Context.listRow(
    iconRes: Int,
    title: String,
    value: String = "",
    subtitle: String = "",
    chevron: Boolean = true,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(56)
    setPadding(dp(16), dp(10), dp(16), dp(10))
    val fg = if (danger) C.LIVE else C.TEXT
    if (iconRes != 0) {
        addView(icon(iconRes, if (danger) C.LIVE else C.TEXT2, 22))
        addView(View(context), LinearLayout.LayoutParams(dp(14), 1))
    }
    val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    col.addView(text(title, 15f, fg))
    if (subtitle.isNotEmpty()) col.addView(text(subtitle, 12f, C.MUTED, lines = 2).apply { setPadding(0, dp(3), 0, 0) })
    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (value.isNotEmpty()) addView(text(value, 13f, C.MUTED).apply { setPadding(dp(8), 0, dp(4), 0); tag = "value" })
    if (chevron) addView(icon(R.drawable.ic_chev_left, C.FAINT, 18))
    if (onClick != null) {
        isFocusable = true
        isClickable = true
        background = focusBg(radius = 0)
        setOnClickListener { onClick() }
    }
}

fun Context.icon(res: Int, color: Int = C.TEXT2, size: Int = 22): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    scaleType = ImageView.ScaleType.FIT_CENTER
}

/** زر نصي مع أيقونة اختيارية. primary = أبيض بنص داكن (الإجراء الأساسي). */
fun Context.button(label: String, iconRes: Int = 0, primary: Boolean = false, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val pad = dp(14)
        setPadding(pad, dp(10), pad, dp(10))
        isFocusable = true
        isClickable = true
        background = if (primary) {
            StateListDrawable().apply {
                val r = dpf(10f)
                addState(intArrayOf(android.R.attr.state_focused), rounded(C.PRIMARY, r, C.ACCENT, dp(3)))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(C.TEXT2, r))
                addState(intArrayOf(), rounded(C.PRIMARY, r))
            }
        } else focusBg(normal = C.SURFACE2)
        val fg = if (primary) C.PRIMARY_TEXT else C.TEXT
        if (iconRes != 0) {
            addView(icon(iconRes, fg, 18))
            addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        }
        addView(text(label, 14f, fg, bold = true))
        setOnClickListener { onClick() }
        focusScale(1.04f)
    }

fun Context.iconButton(res: Int, desc: String, size: Int = 44, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        background = focusBg()
        isFocusable = true
        isClickable = true
        contentDescription = desc
        addView(icon(res, C.TEXT2, 22).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        setOnClickListener { onClick() }
    }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun fmtTime(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, ss) else "%d:%02d".format(java.util.Locale.US, m, ss)
}

fun hhmm(epochSec: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = epochSec * 1000
    return "%02d:%02d".format(java.util.Locale.US, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

// ---------------------------------------------------------------------
// أشرطة النظام (الساعة فوق، أزرار الجوال تحت، نتوء الكاميرا)
// أندرويد 15 يرسم التطبيق خلفها إجبارياً، فنسوّيها كذا على كل الإصدارات ونحسب المسافات بنفسنا.

/** يخلي النافذة ترسم خلف أشرطة النظام (نفس السلوك على كل إصدارات أندرويد). */
fun android.app.Activity.edgeToEdge() {
    val w = window
    @Suppress("DEPRECATION")
    w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    @Suppress("DEPRECATION")
    w.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    w.navigationBarColor = if (android.os.Build.VERSION.SDK_INT >= 26) Color.TRANSPARENT else 0x99000000.toInt()
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = 1 /* SHORT_EDGES */ }
    }
}

/** يستقبل مسافات أشرطة النظام (يسار، فوق، يمين، تحت) كل ما تتغيّر (مثلاً لما تقلب الجوال). */
fun View.onSystemInsets(cb: (l: Int, t: Int, r: Int, b: Int) -> Unit) {
    setOnApplyWindowInsetsListener { _, ins ->
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            cb(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            cb(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
        }
        ins
    }
    if (isAttachedToWindow) requestApplyInsets()
    else addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { v.removeOnAttachStateChangeListener(this); v.requestApplyInsets() }
        override fun onViewDetachedFromWindow(v: View) {}
    })
}

/** يضيف مسافات الأشرطة فوق الـ padding الأصلي (يحفظ الأصلي عشان ما تتراكم مع كل تغيير). */
fun View.padForSystemBars(left: Boolean = true, top: Boolean = true, right: Boolean = true, bottom: Boolean = true) {
    val pl = paddingLeft; val pt = paddingTop; val pr = paddingRight; val pb = paddingBottom
    onSystemInsets { l, t, r, b ->
        setPadding(pl + (if (left) l else 0), pt + (if (top) t else 0), pr + (if (right) r else 0), pb + (if (bottom) b else 0))
    }
}

/** الجوال مقلوب أفقي؟ */
val Context.landscape: Boolean
    get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

// ---------------------------------------------------------------------
// سحب ناعم

/** مخزن مشترك لخانات البوسترات بين كل الصفوف (ما نسوي خانات جديدة لكل صف). */
private val posterPoolInst = RecyclerView.RecycledViewPool()
private val pooledWidths = HashSet<Int>()
fun posterPool(posterW: Int): RecyclerView.RecycledViewPool {
    if (pooledWidths.add(posterW)) posterPoolInst.setMaxRecycledViews(posterW, 48)
    return posterPoolInst
}

/**
 * صف أفقي داخل قائمة رأسية: لو السحب أفقي أكثر، الصف ياخذه ولا تسرقه القائمة الكبيرة
 * (هذا اللي يخلي السحب "يقطع" خصوصاً بالوضع الأفقي).
 */
fun RecyclerView.horizontalInVertical() {
    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        var x0 = 0f; var y0 = 0f
        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; rv.parent?.requestDisallowInterceptTouchEvent(true) }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(e.x - x0); val dy = Math.abs(e.y - y0)
                    if (dy > slop && dy > dx) rv.parent?.requestDisallowInterceptTouchEvent(false)   // رأسي: للقائمة الكبيرة
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> rv.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return false
        }
        override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    })
}

// ---------------------------------------------------------------------
// حركة خفيفة (شفافية + إزاحة بسيطة بطبقة رسم مؤقتة = سلسة حتى على الأجهزة الضعيفة)

fun View.fadeIn(dy: Int = 0, dur: Long = 170) {
    animate().cancel()
    alpha = 0f; translationY = dy.toFloat()
    animate().alpha(1f).translationY(0f).setDuration(dur)
        .setInterpolator(android.view.animation.DecelerateInterpolator()).withLayer().start()
}

fun View.fadeOut(dy: Int = 0, dur: Long = 140, end: () -> Unit) {
    animate().cancel()
    animate().alpha(0f).translationY(dy.toFloat()).setDuration(dur)
        .setInterpolator(android.view.animation.AccelerateInterpolator()).withLayer()
        .withEndAction { alpha = 1f; translationY = 0f; end() }.start()
}


// ---------------------------------------------------------------------
// حركة التنقّل بالريموت (كل القوائم)

/**
 * تكبير خفيف للعنصر المحدد في كل الشاشة (مستمع واحد للنافذة كاملة):
 * الأزرار والبطاقات 5%، والصفوف العريضة 1.5% بس (عشان ما تطلع عن مكانها).
 */
fun android.app.Activity.installFocusMotion() {
    val screenW = resources.displayMetrics.widthPixels
    val interp = android.view.animation.DecelerateInterpolator()
    fun eligible(v: View?): Boolean = v != null && v.getTag(R.id.own_focus_anim) != true &&
        v !is android.widget.EditText && v !is RecyclerView && v !is android.widget.ScrollView &&
        v.width in 1 until (screenW * 0.92).toInt()
    window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
        if (eligible(old)) old!!.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(interp).start()
        if (eligible(new)) {
            val v = new!!
            // صف عريض (قناة/حلقة/إعداد) تكبير بسيط؛ زر أو بطاقة أوضح
            val s = when { v.width > screenW * 0.4 -> 1.015f; v.width > v.height * 3 -> 1.025f; else -> 1.05f }
            v.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(interp).start()
        }
    }
}

/** يحسب الإزاحة اللي تخلي العنصر المحدد في نص القائمة. */
private fun centerDelta(parent: RecyclerView, child: View, vertical: Boolean): Int =
    if (vertical) (child.top + child.height / 2) - (parent.paddingTop + (parent.height - parent.paddingTop - parent.paddingBottom) / 2)
    else (child.left + child.width / 2) - (parent.paddingLeft + (parent.width - parent.paddingLeft - parent.paddingRight) / 2)

/**
 * قائمة تتحرك بنعومة مع الريموت وتخلي العنصر المحدد في النص (على التلفزيون).
 * على الجوال تتصرف عادي (اللمس).
 */
open class SmoothLM(ctx: Context, orientation: Int = VERTICAL, reverse: Boolean = false) : LinearLayoutManager(ctx, orientation, reverse) {
    private val tv = ctx.isTv()
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        if (!tv) return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        val d = centerDelta(parent, child, orientation == VERTICAL)
        if (d == 0) return false
        if (orientation == VERTICAL) { if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d) }
        else { if (immediate) parent.scrollBy(d, 0) else parent.smoothScrollBy(d, 0) }
        return true
    }
}

class SmoothGridLM(ctx: Context, span: Int) : androidx.recyclerview.widget.GridLayoutManager(ctx, span) {
    private val tv = ctx.isTv()
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        if (!tv) return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        val d = centerDelta(parent, child, true)
        if (d == 0) return false
        if (immediate) parent.scrollBy(0, d) else parent.smoothScrollBy(0, d)
        return true
    }
}
