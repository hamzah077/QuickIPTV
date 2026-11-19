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
fun Context.isTv(): Boolean {
    val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return !packageManager.hasSystemFeature("android.hardware.touchscreen")
}

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
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
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
