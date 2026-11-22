package com.urrox.player

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * التلفزيون — إعلان نتفلكس فوق الصفحة: اسم كبير، تفاصيل (سنة · تصنيف · مدة · تقييم)، قصة قصيرة،
 * وزرين تشغيل/معلومات. وخلف الشاشة كلها صورة العمل (MainActivity.setBackdrop).
 *  - العمل المميّز (featured): يطلع والتحديد على الأزرار.
 *  - تنزل للصفوف (rows): الأزرار تختفي والإعلان يعرض تفاصيل البوستر اللي واقف عليه.
 * العناصر تعرّف نفسها بـ setTag(R.id.hero_item, Vod/HistoryItem/Channel).
 */
class TvHero(private val a: MainActivity, private val screenKey: String) {
    private class Item(
        val key: String, val title: String, val meta: String, val plot: String, val image: String,
        val vod: Vod? = null, val hist: HistoryItem? = null, val infoId: String = "", val infoSeries: Boolean = false,
    )
    private class Info(val plot: String, val meta: String, val backdrop: String)


    private var featured: Item? = null
    private var shown: Item? = null
    var rows = false
        private set
    private var infoJob: Job? = null
    private val w = (a.resources.displayMetrics.widthPixels * 0.5f).toInt()

    // مقاسات ثابتة (سطر للاسم، سطر للتفاصيل، 3 أسطر للقصة حتى لو فاضية): لما تتحمّل القصة ما يتزحزح المحتوى تحت
    // (د) بسيط وأنيق: اسم كبير بظل ناعم، تحته خط قصير، وتفاصيل صغيرة رمادية — كل سطر بمقاس ثابت (ما يتزحزح شي)
    private val title = a.text("", 32f, C.TEXT, bold = true).apply {
        setLines(1); ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setShadowLayer(a.dpf(6f), 0f, a.dpf(2f), 0x8C000000.toInt())
    }
    private val accent = View(a).apply { background = rounded(if (Glass.on) 0xD9FFFFFF.toInt() else 0xFFE50914.toInt(), a.dpf(1.5f)) }
    private val meta = a.text("", 12.5f, 0xFFA3A9B3.toInt()).apply {
        setLines(1); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, a.dp(8), 0, 0)
        setShadowLayer(a.dpf(4f), 0f, a.dpf(1f), 0x66000000)
    }
    private val plot = a.text("", 14.5f, 0xFFE2E4E8.toInt(), lines = 3).apply {
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setLineSpacing(0f, 1.25f); setPadding(0, a.dp(8), 0, 0)
        setLines(3); ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** زر الإعلان: رمادي شفاف، ولما يتحدد أبيض والكلام داكن. */
    private fun btn(icon: Int, label: String, onClick: () -> Unit): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(a.dp(22), 0, a.dp(22), 0)
        isFocusable = true; isClickable = true
        contentDescription = label
        setTag(R.id.own_focus_anim, true)
        background = android.graphics.drawable.StateListDrawable().apply {
            setEnterFadeDuration(110); setExitFadeDuration(110)
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFFFFFFFF.toInt(), a.dpf(6f)))
            addState(intArrayOf(), rounded(0x4DFFFFFF, a.dpf(6f)))
        }
        val ic = a.icon(icon, 0xFFFFFFFF.toInt(), 20)
        val tx = a.text(label, 15f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; includeFontPadding = false; setPaddingRelative(a.dp(10), 0, 0, 0) }
        addView(ic, LinearLayout.LayoutParams(a.dp(20), a.dp(20))); addView(tx)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            val c = if (has) 0xFF111214.toInt() else 0xFFFFFFFF.toInt()
            ic.imageTintList = android.content.res.ColorStateList.valueOf(c); tx.setTextColor(c)
            v.raiseOnFocus(has)
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
        }
    }
    val play = btn(R.drawable.ic_play, "تشغيل") { act(true) }
    private val info = btn(R.drawable.ic_info, "معلومات") { act(false) }
    private val glass = Glass.on
    private val btns = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
        addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(42)))
        addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, a.dp(42)).apply { marginStart = a.dp(12) })
    }
    val view = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
        setPaddingRelative(a.dp(28), a.dp(22), 0, a.dp(8))
        addView(title, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(accent, LinearLayout.LayoutParams(a.dp(40), a.dp(3)).apply { topMargin = a.dp(6) })
        addView(meta, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(plot, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(18) })
        visibility = View.INVISIBLE         // مكانه محجوز من البداية (ما يدف الصفوف لما يطلع)
    }

    init {
        if (glass) {
            // ليكود جلاس: كبسولات زجاج (المحدد أبيض)، والتفاصيل داخل شريط زجاج
            for (b in listOf(play, info)) b.background = Glass.button(b, a.dpf(21f))
            view.setPaddingRelative(a.dp(28), a.dp(18), a.dp(20), a.dp(14))
        }
    }

    val hasFeatured get() = featured != null
    fun contains(v: View): Boolean = isIn(view, v)

    /** العمل المميّز (اللي تشغّله أزرار الإعلان). */
    fun setFeatured(o: Any?) {
        val it = o?.let { itemOf(it) }
        if (it?.key == featured?.key) return
        featured = it
        view.visibility = if (it != null) View.VISIBLE else View.INVISIBLE
        if (it != null && !rows) apply(it)
    }
    fun featuredKey(): String? = featured?.key

    /** الصفحة رجعت تنعرض: الخلفية ترجع. */
    fun restore() { (shown ?: featured)?.let { apply(it, force = true) } }

    /** true = التحديد بالصفوف (الإعلان مختصر بدون أزرار). */
    /**
     * (ج) وأنت بالصفوف: الإعلان يصغر (الاسم + سطر التفاصيل بس) والصفوف تاخذ مساحة أكبر.
     * ترجع للإعلان: يكبر مرة ثانية بالقصة والأزرار. [list] القائمة نفسها ما تدخل بالحركة (أنعم).
     */
    fun setRows(on: Boolean, root: ViewGroup?, list: View? = null) {
        if (rows == on) return
        rows = on
        root?.let {
            val tr = android.transition.ChangeBounds().setDuration(220)
            tr.interpolator = android.view.animation.DecelerateInterpolator()
            if (list != null) tr.excludeChildren(list, true)
            android.transition.TransitionManager.beginDelayedTransition(it, tr)
        }
        btns.animate().cancel(); btns.alpha = 1f
        btns.visibility = if (on) View.GONE else View.VISIBLE
        plot.visibility = if (on) View.GONE else View.VISIBLE
        // ليكود جلاس: وأنت بالصفوف الخلفية تتموّه خفيف (بدون بطاقة حول العنوان)
        if (glass) {
            a.setBlur(if (on) 0.6f else 0f)          // وأنت بالصفوف: الخلفية تتموّه خفيف (عمق)
        }
        if (!on) featured?.let { apply(it) }
    }

    /** تحديد جديد: لو العنصر (أو أحد آبائه) معرّف نفسه، نعرض تفاصيله. */
    fun showFocused(v: View) {
        var p: View? = v
        while (p != null && p.getTag(R.id.hero_item) == null) p = p.parent as? View
        val o = p?.getTag(R.id.hero_item) ?: return
        val it = itemOf(o) ?: return
        if (shown?.key == it.key) return
        apply(it)
    }

    private fun itemOf(o: Any): Item? = when (o) {
        is Vod -> Item("v:${o.id}", o.name,
            listOfNotNull(if (o.isSeries) "مسلسل" else "فيلم", o.rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(java.util.Locale.US, it) }).joinToString(SEP),
            "", o.icon, vod = o, infoId = o.id, infoSeries = o.isSeries)
        is HistoryItem -> {
            val left = if (o.dur > 0 && o.pos < o.dur - 60) "متبقي ${fmtTime(o.dur - o.pos)}" else if (o.type == "episode" && o.finished) "الحلقة التالية" else null
            val m = listOfNotNull(o.sub.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }, left).joinToString(SEP)
            val id = if (o.type == "episode") o.seriesId else o.key.substringAfter("movie:", "")
            Item(o.key, o.title, m, "", o.image, hist = o, infoId = id, infoSeries = o.type == "episode")
        }
        is Channel -> {
            val now = Epg.nowFor(o) ?: Live.nowCache[o.id]?.takeIf { it.isNow }
            Item("c:${o.id}", o.name, listOfNotNull("مباشر", now?.title).joinToString(SEP), now?.desc.orEmpty(), "")
        }
        else -> null
    }

    private fun ck(it: Item) = (if (it.infoSeries) "s:" else "m:") + it.infoId

    private fun apply(item: Item, force: Boolean = false) {
        if (!force && shown === item) return
        val changed = shown?.key != item.key
        shown = item
        title.text = shortTitle(item.title)
        // يتبدّل بتلاشي سريع (بدون ما يتغير مقاسه)
        if (changed) for (v in listOf<View>(title, meta)) { v.animate().cancel(); v.alpha = 0.15f; v.animate().alpha(1f).setDuration(180).start() }
        val cached = if (item.infoId.isNotEmpty()) infoCache[ck(item)] else null
        meta.text = listOf(item.meta, cached?.meta.orEmpty()).filter { it.isNotBlank() }.joinToString(SEP)
        plot.text = item.plot.ifBlank { cached?.plot.orEmpty() }
        if (a.current == screenKey) (cached?.backdrop?.takeIf { it.isNotBlank() } ?: item.image.takeIf { it.isNotBlank() })?.let { a.setBackdrop(it) }
        infoJob?.cancel()
        if (item.infoId.isEmpty() || cached != null) return
        infoJob = a.scope.launch {
            delay(350)          // تتنقل بسرعة بين البوسترات؟ ما نطلب تفاصيل كل واحد
            val inf = withContext(Bg.io) { runCatching { fetch(item.infoId, item.infoSeries) }.getOrNull() } ?: return@launch
            infoCache[ck(item)] = inf
            if (shown !== item) return@launch
            meta.text = listOf(item.meta, inf.meta).filter { it.isNotBlank() }.joinToString(SEP)
            if (item.plot.isBlank()) plot.text = inf.plot
            if (inf.backdrop.isNotBlank() && a.current == screenKey) a.setBackdrop(inf.backdrop)
        }
    }

    private fun fetch(id: String, series: Boolean): Info? {
        val api = Session.api ?: return null
        val o = if (series) api.seriesInfo(id).first else api.vodInfo(id)
        fun s(k: String) = o.optString(k).takeIf { it != "null" }.orEmpty()
        val pl = s("plot").ifBlank { s("description") }
        val year = (if (series) s("releaseDate") else s("releasedate")).take(4)
        val genre = s("genre").split(',', '/').firstOrNull()?.trim().orEmpty()
        val dur = if (series) "" else s("duration").take(5)
        val m = listOf(year, genre, dur).filter { it.isNotBlank() }.joinToString(SEP)
        val bd = o.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it != "null" }.orEmpty().ifBlank { s("cover_big") }
        return Info(pl, m, bd)
    }

    /** تشغيل/معلومات للعمل المميّز. */
    private fun act(playIt: Boolean) {
        val f = featured ?: return
        val h = f.hist
        if (h != null) {
            if (playIt) { a.playHistory(h); return }
            if (f.infoId.isEmpty()) return
            a.openDetails(Vod(f.infoId, h.title, h.image, "", h.url.substringAfterLast('.', "mp4").substringBefore('?'), "", 0, h.type == "episode"))
            return
        }
        val v = f.vod ?: return
        if (!playIt) { a.openDetails(v); return }
        val i = android.content.Intent(a, DetailsActivity::class.java)
            .putExtra("id", v.id).putExtra("series", v.isSeries).putExtra("name", v.name).putExtra("icon", v.icon).putExtra("ext", v.ext)
        if (v.isSeries) i.putExtra("autoResume", true)
        else Session.api?.let { api -> i.putExtra("autoUrl", api.vodUrl(v.id, v.ext.ifEmpty { "mp4" })).putExtra("autoKey", "movie:${v.id}").putExtra("autoSub", "").putExtra("autoType", "movie") }
        a.startActivity(i)
    }

    /** «اسم: عنوان فرعي طويل» = الاسم بس (ما ينقص). */
    private fun shortTitle(t: String): String {
        val s = t.substringBefore(':').substringBefore('：').trim()
        return if (s.length >= 2 && s.length < t.trim().length) s else t
    }

    companion object {
        private const val SEP = "    "
        private val infoCache = HashMap<String, Info>()     // مشتركة بين الصفحات
        /** متابعة التحديد بالنافذة وهي ظاهرة بس: نضيف المراقب مع الظهور ونشيله مع الاختفاء (ما يتسرّب). */
        fun watchFocus(host: View, cb: (View?) -> Unit) {
            val l = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, nv -> cb(nv) }
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnGlobalFocusChangeListener(l) }
                override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnGlobalFocusChangeListener(l) }
            })
        }

        /** أقرب عنصر قابل للتحديد داخل [box] لمكان [from] أفقياً (للتنقل فوق/تحت بين الصفوف). */
        fun nearest(box: View, from: View): View? {
            val list = ArrayList<View>()
            box.addFocusables(list, View.FOCUS_DOWN)
            val r = android.graphics.Rect()
            val loc = IntArray(2)
            from.getLocationOnScreen(loc); val cx = loc[0] + from.width / 2
            val sw = box.resources.displayMetrics.widthPixels
            return list.filter { it.isShown && it.getGlobalVisibleRect(r) && r.width() > 0 && r.left < sw }
                .minByOrNull { v -> v.getLocationOnScreen(loc); Math.abs(loc[0] + v.width / 2 - cx) }
        }

        /** الصف الأفقي داخل قسم (RecyclerView أو HorizontalScrollView). */
        fun rowIn(box: View): View? {
            if (box is androidx.recyclerview.widget.RecyclerView || box is android.widget.HorizontalScrollView) return box
            if (box is ViewGroup) for (i in 0 until box.childCount) rowIn(box.getChildAt(i))?.let { return it }
            return null
        }

        /** ترتيب العنصر المحدد داخل الصف. */
        fun indexIn(row: View, focused: View): Int? {
            var c: View = focused
            while (true) {
                val p = c.parent as? View ?: return null
                if (row is androidx.recyclerview.widget.RecyclerView && p === row) return row.getChildAdapterPosition(c).takeIf { it >= 0 }
                if (row is android.widget.HorizontalScrollView && p.parent === row) return (p as ViewGroup).indexOfChild(c)
                if (p === row) return null
                c = p
            }
        }

        /** يحدد العنصر رقم [idx] بالصف (ولو مو ظاهر نمرّر له أول). */
        fun focusItem(row: View, idx: Int) {
            if (row is androidx.recyclerview.widget.RecyclerView) {
                val n = row.adapter?.itemCount ?: 0
                if (n == 0) return
                val i = idx.coerceIn(0, n - 1)
                val vh = row.findViewHolderForAdapterPosition(i)
                if (vh != null) { vh.itemView.requestFocus(); return }
                row.scrollToPosition(i)
                row.post { (row.findViewHolderForAdapterPosition(i)?.itemView ?: row.getChildAt(0))?.requestFocus() }
            } else if (row is android.widget.HorizontalScrollView) {
                val l = row.getChildAt(0) as? ViewGroup ?: return
                if (l.childCount == 0) return
                l.getChildAt(idx.coerceIn(0, l.childCount - 1)).requestFocus()
            }
        }

        fun isIn(parent: View, v: View): Boolean {
            var p: View? = v
            while (p != null) { if (p === parent) return true; p = p.parent as? View }
            return false
        }
    }
}
