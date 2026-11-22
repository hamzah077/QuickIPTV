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
import java.lang.ref.WeakReference

/**
 * ثيم «ليكود جلاس» (التلفزيون): زجاج شفاف فوق صورة العمل.
 *
 * عشان ما يثقّل أجهزة التلفزيون: الضبابية تنحسب مرة وحدة لكل صورة خلفية (نسخة صغيرة 96×54 ممَوّهة)،
 * وكل لوح زجاج يرسم بس الجزء اللي وراه منها + لون أبيض شفاف + حافة لامعة. بدون صورة = زجاج شفاف عادي.
 */
object Glass {
    val on get() = Store.tvTheme == "glass"

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
    class Pane(private val host: View, private val radiusPx: Float, private val tint: Int = 0x24FFFFFF, private val dark: Boolean = false,
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
            // الحافة اللامعة: أقوى فوق ويسار، وتخف بالنص
            stroke.strokeWidth = host.resources.displayMetrics.density * 1.2f
            stroke.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(0xB3FFFFFF.toInt(), 0x1FFFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            val inset = stroke.strokeWidth / 2
            r.inset(inset, inset)
            c.drawRoundRect(r, radiusPx, radiusPx, stroke)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** زر زجاج: عادي = زجاج، محدد = أبيض (كبسولة). */
    fun button(host: View, radiusPx: Float, backdrop: Boolean = true): Drawable = android.graphics.drawable.StateListDrawable().apply {
        setEnterFadeDuration(120); setExitFadeDuration(120)
        addState(intArrayOf(android.R.attr.state_focused), lens(radiusPx))
        addState(intArrayOf(), Pane(host, radiusPx, backdrop = backdrop))
    }

    /** المحدد: «عدسة زجاج» مضيئة — أبيض ونصّها الفوق أفتح، بحافة بيضاء. */
    fun lens(radiusPx: Float): Drawable = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFFFF.toInt(), 0xEBE6EAF4.toInt())).apply {
        cornerRadius = radiusPx; setStroke(2, 0xFFFFFFFF.toInt())
    }
}
