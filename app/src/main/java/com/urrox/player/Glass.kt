package com.urrox.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * ثيم «ليكود جلاس» (التلفزيون): زجاج شفاف فوق صورة العمل.
 *
 * عشان ما يثقّل أجهزة التلفزيون: الضبابية تنحسب مرة وحدة لكل صورة خلفية (نسخة صغيرة 96×54 ممَوّهة)،
 * وكل لوح زجاج يرسم بس الجزء اللي وراه منها + لون أبيض شفاف + حافة لامعة. بدون صورة = زجاج شفاف عادي.
 */
/** ثيم النوافذ المنبثقة: ليكود جلاس = لوحة زجاج كثيف، وإلا نوافذ أندرويد الغامقة. */
val dialogTheme: Int get() = if (Glass.on) R.style.GlassDialog else android.R.style.Theme_Material_Dialog_Alert

object Glass {
    /** الجهاز تلفزيون (يتحدد مع فتح كل صفحة): الثيم للتلفزيون بس. */
    var tv = false
    val on get() = tv && Store.tvTheme == "glass"

    /** الصورة الممَوّهة الحالية (بحجم الشاشة كلها). */
    var blurred: Bitmap? = null
        private set
    /** يتنادى لما تتغير الصورة الممَوّهة (الخلفية الممَوّهة بـ MainActivity). */
    var onBlurred: ((Bitmap?) -> Unit)? = null
    private val hosts = ArrayList<WeakReference<View>>()

    fun register(v: View) { hosts.removeAll { it.get() == null || it.get() === v }; hosts.add(WeakReference(v)) }

    /** صورة خلفية جديدة: نصغّرها ونمَوّهها مرة وحدة، وكل ألواح الزجاج تعيد رسمها. */
    fun setBackdrop(d: Drawable?) {
        blurred = d?.let { runCatching { blur(it) }.getOrNull() }
        onBlurred?.invoke(blurred)
        hosts.removeAll { it.get() == null }
        hosts.forEach { it.get()?.invalidate() }
    }

    private fun blur(d: Drawable): Bitmap {
        val w = 96; val h = 54
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (d is android.graphics.drawable.LayerDrawable) {
            // الخلفية الملوّنة (مقاساتها بحجم الشاشة): نرسمها بحجم الشاشة ونصغّرها
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val old = Rect(d.bounds)
            c.save(); c.scale(w / dm.widthPixels.toFloat(), h / dm.heightPixels.toFloat())
            d.setBounds(0, 0, dm.widthPixels, dm.heightPixels); d.draw(c); c.restore()
            d.bounds = old
            return b
        }
        // centerCrop مثل ImageView
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w; val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val s = maxOf(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * s).toInt(); val dh = (ih * s).toInt()
        val old = d.bounds.let { Rect(it) }
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
        d.draw(c)
        d.bounds = old
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 3) }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** تمويه صندوقي أفقي ثم رأسي (3 مرات ≈ غاوسي). */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, idx: (Int, Int) -> Int) {
            for (l in 0 until lines) {
                var ra = 0; var rr = 0; var rg = 0; var rb = 0
                for (i in -r..r) { val p = src[idx(l, i.coerceIn(0, len - 1))]; ra += p ushr 24; rr += p shr 16 and 255; rg += p shr 8 and 255; rb += p and 255 }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    dst[idx(l, i)] = (ra / n shl 24) or (rr / n shl 16) or (rg / n shl 8) or (rb / n)
                    val a = src[idx(l, (i - r).coerceIn(0, len - 1))]; val z = src[idx(l, (i + r + 1).coerceIn(0, len - 1))]
                    ra += (z ushr 24) - (a ushr 24); rr += (z shr 16 and 255) - (a shr 16 and 255)
                    rg += (z shr 8 and 255) - (a shr 8 and 255); rb += (z and 255) - (a and 255)
                }
            }
        }
        pass(px, tmp, w, h) { l, i -> l * w + i }
        pass(tmp, px, h, w) { l, i -> i * w + l }
    }

    /**
     * لوح زجاج: [radiusPx] الزوايا، [tint] لون أبيض شفاف فوق الضبابية، [dark] = زجاج داكن (فوق الفيديو).
     */
    class Pane(private val host: View, private val radiusPx: Float, private val tint: Int = 0x2EFFFFFF, private val dark: Boolean = false,
               private val backdrop: Boolean = true,
               /** طبقة «مثلّجة» تحت اللون (0 = بدون): تغطي اللي ورا اللوح (مثل القائمة الجانبية) */
               private val frost: Int = 0) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val r = RectF(); private val src = Rect(); private val dst = RectF()
        private val path = Path()
        private val loc = IntArray(2)
        init { register(host) }

        override fun draw(c: Canvas) {
            val b = bounds
            r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            path.reset(); path.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW)
            val bmp = blurred
            if (bmp != null && !dark && backdrop) {
                host.getLocationOnScreen(loc)
                val dm = host.resources.displayMetrics
                val sx = bmp.width / dm.widthPixels.toFloat(); val sy = bmp.height / dm.heightPixels.toFloat()
                src.set(((loc[0] + b.left) * sx).toInt(), ((loc[1] + b.top) * sy).toInt(),
                    ((loc[0] + b.right) * sx).toInt().coerceAtLeast(1), ((loc[1] + b.bottom) * sy).toInt().coerceAtLeast(1))
                dst.set(r)
                c.save(); c.clipPath(path)
                paint.alpha = 255; c.drawBitmap(bmp, src, dst, paint)
                c.restore()
            }
            paint.shader = null
            if (frost != 0) { paint.color = frost; c.drawPath(path, paint) }
            paint.color = if (dark) 0x8C0E1016.toInt() else tint
            c.drawPath(path, paint)
            if (dark) { paint.color = 0x14FFFFFF; c.drawPath(path, paint) }
            sheen(c, path, r, stroke, paint, radiusPx, host.resources.displayMetrics.density, dark)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * طبقات «الزجاج السائل» فوق اللون: لمعة فوق (ضوء ينعكس)، ظل خفيف تحت (عمق)، وحافة لامعة عريضة
     * أقوى فوق-يمين وتخف بالنص — هذا اللي يخلي الزجاج يبان زجاج مو مستطيل رمادي.
     */
    internal fun sheen(c: Canvas, path: Path, r: RectF, stroke: Paint, paint: Paint, radiusPx: Float, density: Float, dark: Boolean) {
        c.save(); c.clipPath(path)
        paint.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.5f,
            intArrayOf(if (dark) 0x1FFFFFFF else 0x47FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(r, paint)
        paint.shader = LinearGradient(0f, r.bottom - r.height() * 0.35f, 0f, r.bottom,
            intArrayOf(0x00000000, if (dark) 0x33000000 else 0x24000000), null, Shader.TileMode.CLAMP)
        c.drawRect(r, paint)
        paint.shader = null
        c.restore()
        stroke.strokeWidth = density * 2f          // (tvOS: بدون خطوط شعرية — تختفي من بعيد)
        stroke.shader = LinearGradient(r.right, r.top, r.left, r.bottom,
            intArrayOf(0xF2FFFFFF.toInt(), 0x38FFFFFF, 0x99FFFFFF.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val inset = stroke.strokeWidth / 2
        val rr = RectF(r); rr.inset(inset, inset)
        c.drawRoundRect(rr, radiusPx, radiusPx, stroke)
    }

    /** لوح/صف زجاج بدون ما يحتاج صورة وراه (للأزرار والصفوف): لون + لمعة + ظل + حافة لامعة. */
    class Sheet(private val radiusPx: Float, private val tint: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val r = RectF(); private val path = Path()
        override fun draw(c: Canvas) {
            val b = bounds
            r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            path.reset(); path.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW)
            paint.shader = null; paint.color = tint; c.drawPath(path, paint)
            sheen(c, path, r, stroke, paint, radiusPx, density, false)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, radiusPx); outline.alpha = 0f }
    }

    /**
     * خلفية الصفحات اللي ما فيها صورة (الإعدادات، المباشر، البحث…): ألوان ناعمة غامقة —
     * الزجاج يحتاج شي ملوّن وراه عشان يبان زجاج.
     */
    fun ambient(): Drawable {
        fun blob(color: Int, cx: Float, cy: Float, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(color, color and 0x00FFFFFF)
            setGradientCenter(cx, cy); gradientRadius = radius
        }
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val big = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        return android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(0xFF0B0D14.toInt()),
            blob(0x8C3A4FD8.toInt(), 0.22f, 0.25f, big * 0.55f),
            blob(0x738A3FD1.toInt(), 0.82f, 0.72f, big * 0.5f),
            blob(0x591FA39A, 0.62f, 0.12f, big * 0.38f),
            blob(0x4DD96A3F, 0.12f, 0.88f, big * 0.4f),
        ))
    }

    /** زر زجاج: عادي = زجاج، محدد = أبيض (كبسولة). */
    fun button(host: View, radiusPx: Float, backdrop: Boolean = true): Drawable = android.graphics.drawable.StateListDrawable().apply {
        setEnterFadeDuration(120); setExitFadeDuration(120)
        addState(intArrayOf(android.R.attr.state_pressed), pressedLens(radiusPx))     // ضغطة OK: العدسة تغمق لحظة (إحساس الضغط)
        addState(intArrayOf(android.R.attr.state_focused), lens(radiusPx))
        addState(intArrayOf(), Pane(host, radiusPx, backdrop = backdrop))
    }

    /** العدسة وقت الضغط: أغمق شوي. */
    fun pressedLens(radiusPx: Float): Drawable = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFD9DDE6.toInt(), 0xFFC3C8D3.toInt())).apply {
        cornerRadius = radiusPx; setStroke(2, 0xFFFFFFFF.toInt())
    }

    /**
     * تحديد البطاقات (بوستر/متابعة) بليكود جلاس: بدون خلفية رمادية خلف البطاقة —
     * الصورة نفسها تاخذ حافة زجاج بيضاء وتوهّج ناعم، والبطاقة تكبر وتطلع فوق جيرانها، والاسم يبقى أبيض.
     */
    fun focusCard(root: View, box: android.widget.FrameLayout, radiusPx: Float, scale: Float = 1.08f) {
        root.background = null
        root.setTag(R.id.own_focus_anim, true)
        // الإطار ينحط مباشرة مع التحديد (قبل: كان مربوط بحالة ما توصل للصورة = ما يطلع أبداً)
        val d = root.resources.displayMetrics.density
        val ring = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radiusPx; setColor(0); setStroke((d * 3f).toInt(), 0xFFFFFFFF.toInt())
        }
        root.setOnFocusChangeListener { v, has ->
            v.raiseOnFocus(has)                       // فوق اللي جنبه (بدون ظل/توهّج)
            box.foreground = if (has) ring else null
            ((v as? ViewGroup)?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it is android.widget.TextView } } as? android.widget.TextView)
                ?.let { tv -> if (tv.typeface != Fonts.bold || tv.getTag(R.id.keep_clip) == true) { tv.setTag(R.id.keep_clip, true); tv.typeface = if (has) Fonts.bold else Fonts.regular } }      // الاسم عريض وهو محدد
            v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
        }
    }

    /** المحدد: «عدسة زجاج» مضيئة — أبيض ونصّها الفوق أفتح، بحافة بيضاء. */
    fun lens(radiusPx: Float): Drawable = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFFFF.toInt(), 0xEBE6EAF4.toInt())).apply {
        cornerRadius = radiusPx; setStroke(2, 0xFFFFFFFF.toInt())
    }
}
