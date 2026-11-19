package com.quickiptv.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** صف قناة: الرقم، الشعار، الاسم، البرنامج الحالي + شريط التقدّم. */
class ChannelAdapter(
    private val ctx: Context,
    private val onClick: (Channel, Int) -> Unit,
    private val onLong: (Channel, Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var playingId: String = ""
        set(v) {
            val old = items.indexOfFirst { it.id == field }
            field = v
            if (old >= 0) notifyItemChanged(old)
            val n = items.indexOfFirst { it.id == v }
            if (n >= 0) notifyItemChanged(n)
        }

    class VH(val root: LinearLayout, val num: TextView, val logo: ImageView, val name: TextView,
             val prog: TextView, val bar: ProgressBar, val fav: ImageView, val link: ImageView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(ctx) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH, dp(62)).apply { bottomMargin = dp(2) }
            setPadding(dp(10), 0, dp(10), 0)
            background = focusBg()
            isFocusable = true
            isClickable = true
            isLongClickable = true
        }
        val num = text("", 12f, C.FAINT).apply { layoutParams = lp(dp(30), WRAP); gravity = Gravity.CENTER }
        val logoBox = FrameLayout(this).apply {
            layoutParams = lp(dp(58), dp(40)).apply { marginStart = dp(4); marginEnd = dp(10) }
            background = rounded(C.SURFACE2, dpf(7f))
        }
        val logo = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoBox.addView(logo)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(0, WRAP, 1f) }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = text("", 14f, C.TEXT, bold = true).apply { layoutParams = lp(0, WRAP, 1f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        val link = icon(R.drawable.ic_link, C.ACCENT, 14).apply { visibility = View.GONE }
        nameRow.addView(name); nameRow.addView(link)
        val prog = text("", 12f, C.MUTED).apply { setPadding(0, dp(3), 0, 0) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = lp(MATCH, dp(3)).apply { topMargin = dp(5) }
            max = 1000
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(C.SURFACE3)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        col.addView(nameRow); col.addView(prog); col.addView(bar)
        val fav = icon(R.drawable.ic_heart, C.HEART, 16).apply {
            layoutParams = lp(dp(16), dp(16)).apply { marginStart = dp(8) }
        }
        root.addView(num); root.addView(logoBox); root.addView(col); root.addView(fav)
        VH(root, num, logo, name, prog, bar, fav, link)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        h.num.text = (pos + 1).toString()
        h.name.text = ch.name
        if (ch.icon.isNotEmpty()) h.logo.load(ch.icon) { size(160, 110); placeholder(null); error(R.drawable.ic_live) }
        else { h.logo.setImageResource(R.drawable.ic_live); h.logo.imageTintList = ColorStateList.valueOf(C.FAINT) }
        if (ch.icon.isNotEmpty()) h.logo.imageTintList = null
        val now = Epg.nowFor(ch) ?: Live.nowCache[ch.id]?.takeIf { it.isNow }
        if (now != null) {
            h.prog.text = "${now.title}   ${hhmm(now.start)} – ${hhmm(now.end)}"
            h.prog.visibility = View.VISIBLE
            h.bar.visibility = View.VISIBLE
            h.bar.progress = (now.progress * 1000).toInt()
        } else {
            h.prog.visibility = View.GONE
            h.bar.visibility = View.GONE
        }
        h.fav.visibility = if (Store.isFav("live", ch.id)) View.VISIBLE else View.GONE
        h.link.visibility = if (Epg.mode(ch) == "manual") View.VISIBLE else View.GONE
        h.root.isActivated = ch.id == playingId
        h.root.setOnClickListener { onClick(ch, h.bindingAdapterPosition) }
        h.root.setOnLongClickListener { onLong(ch, h.bindingAdapterPosition); true }
    }
}

/** بوستر فيلم/مسلسل (الصورة بحجمها المعروض بس = ذاكرة أقل). */
class PosterAdapter(
    private val ctx: Context,
    private val posterW: Int,   // لا تسمّه width: داخل apply{} يصير View.width (= صفر)
    private val onClick: (Vod) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<Vod> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val root: LinearLayout, val img: ImageView, val title: TextView, val badge: TextView) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = items.size

    /** نوع الخانة = عرضها: المخزن المشترك ما يخلط بين بوسترات بأحجام مختلفة. */
    override fun getItemViewType(position: Int) = posterW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(ctx) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(posterW, WRAP).apply { setMargins(dp(5), dp(5), dp(5), dp(8)) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = focusBg(radius = 12)
            isFocusable = true
            isClickable = true
            focusScale(1.06f)
        }
        val box = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, (posterW * 1.45f).toInt())
            background = rounded(C.SURFACE2, dpf(9f))
            clipToOutline = true
        }
        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val badge = text("", 10f, C.PRIMARY_TEXT, bold = true).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) }
            background = rounded(0xE6F1F3F7.toInt(), dpf(5f))
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        box.addView(img); box.addView(badge)
        val title = text("", 12.5f, C.TEXT, lines = 2).apply { setPadding(dp(2), dp(6), dp(2), 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        root.addView(box); root.addView(title)
        VH(root, img, title, badge)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val v = items[pos]
        h.title.text = v.name
        h.img.load(v.icon.ifEmpty { null }) {
            size(posterW, (posterW * 1.45f).toInt())
            placeholder(null)
            error(null)
        }
        val r = v.rating.toDoubleOrNull()
        if (r != null && r > 0) { h.badge.text = "★ %.1f".format(java.util.Locale.US, r); h.badge.visibility = View.VISIBLE } else h.badge.visibility = View.GONE
        h.root.setOnClickListener { onClick(v) }
    }
}

/** تصنيف: نص بس (قائمة جانبية على التلفزيون، أزرار أفقية على الجوال). */
class CategoryAdapter(
    private val ctx: Context,
    private val horizontal: Boolean,
    private val onClick: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<Category> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var selectedId: String = ""
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val t: TextView) : RecyclerView.ViewHolder(t)

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(ctx) {
        val t = text("", if (horizontal) 13f else 14f, C.TEXT2).apply {
            layoutParams = if (horizontal) RecyclerView.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(6) }
            else RecyclerView.LayoutParams(MATCH, dp(46)).apply { bottomMargin = dp(2) }
            gravity = Gravity.CENTER_VERTICAL or if (horizontal) Gravity.CENTER_HORIZONTAL else Gravity.START
            setPadding(dp(14), 0, dp(14), 0)
            background = if (horizontal) focusBg(normal = C.SURFACE, radius = 18, selected = C.PRIMARY) else focusBg()
            isFocusable = true
            isClickable = true
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        VH(t)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.t.text = c.name
        val sel = c.id == selectedId
        h.t.isSelected = sel
        h.t.setTextColor(if (sel) (if (horizontal) C.PRIMARY_TEXT else C.TEXT) else C.TEXT2)
        h.t.setTypeface(null, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        h.t.setOnClickListener { onClick(c) }
    }
}
