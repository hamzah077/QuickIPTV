package com.urrox.player

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
    /** تحديد التلفزيون: رمادي فاتح (أهدأ من الأبيض الصافي) والكتابة داكنة فوقه */
    const val FOCUS = 0xFFCDD2DA.toInt()
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
 * التلفزيون: التركيز أبيض والكتابة والأيقونات داكنة فوقه (نفس القائمة الجانبية — مثل Google TV). الاختيار: رمادي خفيف.
 * الجوال: رمادي فاتح مع إطار أبيض ناعم.
 */
fun Context.focusBg(
    normal: Int = Color.TRANSPARENT,
    radius: Int = 10,
    selected: Int = 0x1FFFFFFF,
    pressed: Int = C.SURFACE3,
): Drawable {
    val r = dpf(radius.toFloat())
    val sw = dp(2)
    val tv = isTv()
    val states = (if (tv) TvFocusDrawable() else StateListDrawable()).apply {
        // التحديد يظهر ويختفي بتلاشي ناعم بدل ما يقفز
        setEnterFadeDuration(130)
        setExitFadeDuration(110)
        if (tv && Glass.on) {
            // ليكود جلاس (إرشادات Apple TV): المحدد عدسة بيضاء واضحة، والضغط يغمّقها لحظة، والعادي زجاج بحافة
            addState(intArrayOf(android.R.attr.state_pressed), Glass.pressedLens(r))
            addState(intArrayOf(android.R.attr.state_focused), Glass.lens(r))
            addState(intArrayOf(android.R.attr.state_selected), rounded(0x2EFFFFFF, r, 0x59FFFFFF, dp(2)))
            addState(intArrayOf(android.R.attr.state_activated), rounded(0x2EFFFFFF, r, 0x59FFFFFF, dp(2)))
            // العادي: شفاف يبقى شفاف — والملوّن ينقسم: «مختار» (أبيض قوي/لون أساسي) = زجاج أوضح، والباقي زجاج خفيف
            val a8 = normal ushr 24
            val strong = normal == C.PRIMARY || ((normal and 0xFFFFFF) == 0xFFFFFF && a8 >= 0x26)
            addState(intArrayOf(), when {
                normal == Color.TRANSPARENT -> rounded(Color.TRANSPARENT, r)
                strong -> rounded(0x2EFFFFFF, r, 0x59FFFFFF, dp(2))
                else -> rounded(0x1AFFFFFF, r, 0x26FFFFFF, (resources.displayMetrics.density * 1.5f).toInt())
            })
            return@apply
        }
        if (tv) addState(intArrayOf(android.R.attr.state_focused), rounded(C.FOCUS, r))
        else addState(intArrayOf(android.R.attr.state_focused), rounded(0x2EFFFFFF, r, 0x8CFFFFFF.toInt(), sw))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, r))
        addState(intArrayOf(android.R.attr.state_selected), rounded(selected, r))
        addState(intArrayOf(android.R.attr.state_activated), rounded(selected, r))
        addState(intArrayOf(), rounded(normal, r))
    }
    if (tv) return states
    // الجوال: موجة لمس ناعمة فوق الخلفية (مقصوصة على نفس الزوايا)
    return android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x2EFFFFFF), states, rounded(Color.WHITE, r))
}

/** خلفية تحديد التلفزيون البيضاء: اللي يتحدد فيها تنقلب كتابته وأيقوناته داكنة (installFocusMotion). */
class TvFocusDrawable : StateListDrawable()

private const val INK = 0xFF0E1116.toInt()

/** يقلب ألوان الكتابة والأيقونات داخل العنصر المحدد للداكن (on) ويرجعها (off). الصور (شعارات/بوسترات) ما تنلمس. */
private fun ink(v: View, on: Boolean) {
    when (v) {
        is TextView -> {
            if (on) {
                if (v.currentTextColor != INK) { v.setTag(R.id.ink_orig, v.textColors); v.setTextColor(INK) }
            } else (v.getTag(R.id.ink_orig) as? ColorStateList)?.let { v.setTextColor(it); v.setTag(R.id.ink_orig, null) }
        }
        is android.widget.ImageView -> {
            val t = v.imageTintList ?: (v.getTag(R.id.ink_orig) as? ColorStateList) ?: return   // بدون تلوين = صورة: نخليها
            if (on) {
                if (t.defaultColor != INK) { v.setTag(R.id.ink_orig, t); v.imageTintList = ColorStateList.valueOf(INK) }
            } else (v.getTag(R.id.ink_orig) as? ColorStateList)?.let { v.imageTintList = it; v.setTag(R.id.ink_orig, null) }
        }
        is android.view.ViewGroup -> for (i in 0 until v.childCount) ink(v.getChildAt(i), on)
    }
}

/** انضغاط خفيف لما تلمس العنصر (الجوال)، ويرجع لما ترفع إصبعك أو تسحب. */
fun View.pressScale(s: Float = 0.96f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(s).scaleY(s).setDuration(90).start()
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        false       // ما نوقف الضغط ولا السحب
    }
}

/** يكبّر العنصر شوي لما يتركز عليه (إحساس التلفزيون). */
fun View.focusScale(scale: Float = 1.05f) {
    isFocusable = true
    setTag(R.id.own_focus_anim, true)
    pressScale()
    setOnFocusChangeListener { v, has ->
        v.raiseOnFocus(has)
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
    }
}

/** العنصر المحدد يترسم فوق اللي جنبه (كان تكبيره يطلع تحت الصورة المجاورة) — بدون ظل. */
fun View.raiseOnFocus(has: Boolean) {
    // فوق اللي جنبه بس — بدون ظل ولا توهّج تحته
    if (android.os.Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = 0; outlineSpotShadowColor = 0 }
    translationZ = if (has) resources.displayMetrics.density * 4 else 0f
}

fun Context.text(
    s: CharSequence = "",
    size: Float = 14f,
    color: Int = C.TEXT,
    bold: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    text = s
    // التلفزيون من 3 أمتار: كل الكتابات أكبر، وأصغر كتابة 13 (إلا الأرقام داخل الأيقونات)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv()) (if (size >= 9.5f) maxOf(size * 1.18f, 13f) else size * 1.18f) else size)
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
    // ليكود جلاس (التلفزيون): بدل الخط مسافة بين الصفوف الزجاج
    if (isTv() && Glass.on) { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)); return@apply }
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
        // التلفزيون: صف مدوّر بنفس تحديد القائمة الجانبية — ليكود جلاس: كل صف زجاج خفيف بحافة، والمحدد عدسة
        background = if (isTv() && Glass.on) focusBg(normal = 0x1AFFFFFF, radius = 16) else focusBg(radius = if (isTv()) 12 else 0)
        setOnClickListener { onClick() }
        pressScale(0.985f)
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
        val tv = isTv()
        // التلفزيون: كبسولة بنفس نظام القائمة الجانبية — عادي رمادي شفاف خفيف (الأساسي أوضح شوي)،
        // والتحديد رمادي فاتح والكتابة والأيقونة داكنة
        background = if (tv) focusBg(normal = if (primary) 0x29FFFFFF else 0x14FFFFFF, radius = 24)
        else if (primary) {
            StateListDrawable().apply {
                val r = dpf(10f)
                addState(intArrayOf(android.R.attr.state_focused), rounded(C.PRIMARY, r, C.ACCENT, dp(3)))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(C.TEXT2, r))
                addState(intArrayOf(), rounded(C.PRIMARY, r))
            }
        } else focusBg(normal = C.SURFACE2)
        val fg = if (primary && !tv) C.PRIMARY_TEXT else C.TEXT
        if (iconRes != 0) {
            addView(icon(iconRes, fg, 18))
            addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        }
        addView(text(label, 14f, fg, bold = true))
        setOnClickListener { onClick() }
        pressScale()
        focusScale(1.04f)
    }

/** زر تأخير/تقديم 10 ثواني (سهم دائري وفي وسطه 10) — نفس الشكل بالجوال والتلفزيون. */
fun Context.seekButton(forward: Boolean, size: Int, normal: Int, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        background = focusBg(normal = normal, radius = size / 2)
        isFocusable = true; isClickable = true
        contentDescription = if (forward) "تقديم 10 ثواني" else "تأخير 10 ثواني"
        val ic = (size * 0.62f).toInt()
        addView(icon(if (forward) R.drawable.ic_seek_fwd else R.drawable.ic_seek_back, 0xFFFFFFFF.toInt(), ic).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ic), dp(ic), Gravity.CENTER)
        })
        addView(text("10", if (size >= 44) 9.5f else 8f, 0xFFFFFFFF.toInt(), bold = true).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(2) }
            includeFontPadding = false
        })
        setOnClickListener { onClick() }
    }

/** تقديم/تأخير 10 ثواني بالبث المباشر (لو البث يسمح بالرجوع). يرجع false لو ما يسمح. */
fun androidx.media3.common.Player.liveSeek(forward: Boolean): Boolean {
    if (!isCurrentMediaItemSeekable) return false
    if (forward) seekForward() else seekBack()
    return true
}

fun Context.iconButton(res: Int, desc: String, size: Int = 44, onClick: () -> Unit): FrameLayout =
    FrameLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        // التلفزيون: دائرة بنفس نظام القائمة الجانبية
        background = if (isTv()) focusBg(normal = 0x14FFFFFF, radius = size / 2) else focusBg()
        isFocusable = true
        isClickable = true
        contentDescription = desc
        addView(icon(res, C.TEXT2, 22).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
        setOnClickListener { onClick() }
        pressScale(0.9f)
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
    // التلفزيون: العنصر المحدد (أبيض) تنقلب ألوانه داكنة — ونتأكد كل رسمة إنها باقية (لو القائمة تحدّثت وهو محدد)
    var inked: View? = null
    if (isTv()) window.decorView.viewTreeObserver.addOnPreDrawListener {
        inked?.let { v -> if (v.isFocused) ink(v, true) else { ink(v, false); inked = null } }
        true
    }
    window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
        if (isTv() && new != null) roomForFocus(new, window.decorView)       // التحديد (والتكبير) ما ينقص من الأطراف
        if (old != null && old === inked) { ink(old, false); inked = null }
        if (new != null && new.background is TvFocusDrawable) { inked = new; ink(new, true) }
        if (eligible(old)) { old!!.raiseOnFocus(false); old.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(interp).start() }
        if (eligible(new)) {
            val v = new!!
            v.raiseOnFocus(true)
            // الصفوف العريضة (قنوات/تصنيفات/حلقات) تتلوّن بس بدون تكبير — عشان ما تطلع عن حدود القائمة وتنقص
            if (v.width <= v.height * 3 && v.width <= screenW * 0.4)
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(140).setInterpolator(interp).start()
        }
        marquee(old, false)
        marquee(new, true)
    }
}

/**
 * التلفزيون: العنصر المحدد يكبر شوي وحوله إطار — كان ينقص من الأطراف لأن الحاويات اللي فوقه تقص الرسم على حدودها.
 * - الحاويات العادية: نسمح ترسم برا حدودها (clipChildren/clipToPadding = false).
 * - القوائم اللي تتمرّر (أفقي/عمودي): تبقى تقص على حدودها (عشان العناصر المخفية ما تطلع فوق غيرها)،
 *   بس نعطيها هامش داخلي يكفي للتكبير والإطار، والمحتوى يتمرّر داخل الهامش.
 */
private fun roomForFocus(v: View, top: View) {
    val room = (12 * v.resources.displayMetrics.density).toInt()
    var p = v.parent
    while (p is ViewGroup && p !== top) {
        if (p is RecyclerView || p is android.widget.ScrollView || p is android.widget.HorizontalScrollView) {
            if (p.getTag(R.id.focus_room) != true) {
                p.setTag(R.id.focus_room, true)
                p.setPaddingRelative(maxOf(p.paddingStart, room), maxOf(p.paddingTop, room), maxOf(p.paddingEnd, room), maxOf(p.paddingBottom, room))
            }
            if (p.getTag(R.id.keep_clip) != true) p.clipToPadding = false     // (قائمة الصفوف تحت الإعلان: تقص عشان ما تطلع فوقه)
        } else {
            p.clipChildren = false
            p.clipToPadding = false
        }
        p = p.parent
    }
}

/** الاسم الطويل داخل العنصر المحدد يتحرك (يمشي) عشان يبين كامل، ويرجع "…" لما تطلع منه. */
private fun marquee(v: View?, on: Boolean) {
    if (v == null) return
    if (v is TextView) {
        if (v.maxLines != 1 || v.ellipsize == null) return
        if (on) {
            v.setSingleLine(true)
            v.ellipsize = TextUtils.TruncateAt.MARQUEE
            v.marqueeRepeatLimit = -1
            // العنصر نفسه قابل للتحديد (مثل التصنيف)؟ الحركة تشتغل بالتحديد لحالها — ما نلمس "المختار"
            // (كان يمسح تمييز التصنيف المختار لما تطلع منه)
            if (!v.isFocusable) v.isSelected = true
        } else {
            if (!v.isFocusable) v.isSelected = false
            v.ellipsize = TextUtils.TruncateAt.END
        }
        return
    }
    if (v is ViewGroup) for (i in 0 until v.childCount) marquee(v.getChildAt(i), on)
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
    /** التلفزيون (نمط نتفلكس): الصف المحدد يطلع لأول القائمة بدل النص. */
    var alignTop = false
    /** التلفزيون (نتفلكس): البوستر المحدد يبقى أول الصف (يمين بالعربي) والصف هو اللي يتحرك. */
    var alignStart = false
    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        if (!tv) return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        val d = when {
            alignTop && orientation == VERTICAL -> child.top - parent.paddingTop
            alignStart && orientation == HORIZONTAL ->
                if (parent.layoutDirection == View.LAYOUT_DIRECTION_RTL) child.right - (parent.width - parent.paddingRight)
                else child.left - parent.paddingLeft
            else -> centerDelta(parent, child, orientation == VERTICAL)
        }
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
