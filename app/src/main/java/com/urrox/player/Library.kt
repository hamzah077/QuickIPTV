package com.urrox.player

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** صف تصنيف في صفحة الأفلام/المسلسلات: العنوان + "الكل ‹" + بوسترات أفقية. */
private data class Shelf(val id: String, val title: String, val items: List<Vod>)

/**
 * الأفلام / المسلسلات (الاختيار ب): صفوف أفقية لكل تصنيف (المفضلة، أُضيف مؤخراً، ثم التصنيفات).
 * "الكل ‹" يفتح التصنيف كامل كشبكة مع ترتيب (الأحدث / الأعلى تقييم / أ-ي).
 * الصفوف نفسها داخل RecyclerView: ما ينرسم إلا اللي ظاهر (خفيف حتى مع مئات التصنيفات).
 */
class LibraryScreen(private val a: MainActivity, private val series: Boolean) : Screen {
    private val kind = if (series) "series" else "movie"
    private val title = if (series) "المسلسلات" else "الأفلام"
    override val view: View
    private val posterW = a.dp(if (a.tv) 124 else 100)
    private val rows = object : RecyclerView(a) {
        override fun getBottomFadingEdgeStrength(): Float = if (a.tv) 0f else super.getBottomFadingEdgeStrength()
    }.apply {
        if (a.tv) {
            // الصفوف تقص على حدودها (ما تطلع فوق الإعلان) والصف اللي فوق يذوب بنعومة
            clipToPadding = true; setTag(R.id.keep_clip, true)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(a.dp(40))
        }
    }
    private val shelfAdapter = ShelfAdapter()
    private val gridBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val grid = RecyclerView(a)
    private val gridPosters = PosterAdapter(a, posterW) { a.openDetails(it) }
    private val gridTitle = a.text("", 18f, C.TEXT, bold = true)
    private val sortRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val loading = ProgressBar(a).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }
    private val empty = a.text("", 14f, C.MUTED, lines = 3).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private var all: List<Vod> = emptyList()
    private var cats: List<Category> = emptyList()
    private var gridItems: List<Vod> = emptyList()
    private var sort = "new"
    private var lastRowFocus = 0
    private val rowScroll = HashMap<String, Pair<Int, Int>>()     // وين وقفت في كل صف
    /** التلفزيون: إعلان نتفلكس فوق الصفوف. */
    private val hero: TvHero? = if (a.tv) TvHero(a, if (series) "series" else "movie") else null
    private var mainBox: LinearLayout? = null

    private fun onTvFocus(nv: View?) {
        val h = hero ?: return
        if (nv == null || a.current != kind || !view.isAttachedToWindow || gridBox.visibility == View.VISIBLE) return
        if (h.contains(nv)) { h.setRows(false, mainBox, rows); return }
        if (!TvHero.isIn(rows, nv)) return
        h.setRows(true, mainBox, rows)
        h.showFocused(nv)
        (rows.findContainingViewHolder(nv) as? ShelfAdapter.VH)?.let { vh -> TvHero.indexIn(vh.strip, nv)?.let { shelfPos[vh.boundId] = it } }
    }
    private val shelfPos = HashMap<String, Int>()      // آخر مكان بكل صف (نتفلكس)

    /** ينقل التحديد لصف [pos] على آخر مكان كنت فيه (أول مرة = أول بوستر). */
    private fun focusShelf(pos: Int) {
        val vh = rows.findViewHolderForAdapterPosition(pos) as? ShelfAdapter.VH
        if (vh != null) { TvHero.focusItem(vh.strip, shelfPos[vh.boundId] ?: 0); return }
        rows.scrollToPosition(pos)
        rows.post { (rows.findViewHolderForAdapterPosition(pos) as? ShelfAdapter.VH)?.let { v -> TvHero.focusItem(v.strip, shelfPos[v.boundId] ?: 0) } }
    }

    override fun onKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val h = hero ?: return false
        if (!h.hasFeatured || gridBox.visibility == View.VISIBLE) return false
        val f = a.currentFocus ?: return false
        val up = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        if ((up || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) && TvHero.isIn(rows, f)) {
            // فوق/تحت بين الصفوف: للصف اللي فوق/تحت على آخر مكان كنت فيه — وأول صف + فوق = الإعلان
            val vh = rows.findContainingViewHolder(f) as? ShelfAdapter.VH ?: return false
            val j = vh.bindingAdapterPosition + if (up) -1 else 1
            if (j < 0) { h.setRows(false, mainBox, rows); rows.scrollToPosition(0); h.play.requestFocus(); return true }
            if (j >= shelfAdapter.itemCount) return true
            focusShelf(j); return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && h.contains(f)) { focusShelf(0); return true }
        return false
    }

    init {
        val root = FrameLayout(a)
        val main = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        mainBox = main
        val hb = hero
        if (hb != null) {
            // التلفزيون (نتفلكس): الإعلان فوق بدل العنوان — والبحث في القائمة الجانبية
            main.addView(hb.view, lp(MATCH, WRAP))
            TvHero.watchFocus(main) { nv -> onTvFocus(nv) }
        } else {
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(8), a.dp(4)) }
            head.addView(a.text(title, 20f, C.TEXT, bold = true), lp(0, WRAP, 1f))
            head.addView(a.iconButton(R.drawable.ic_search, "بحث", 42) { a.openExtra("search") })
            main.addView(head)
        }
        rows.layoutManager = SmoothLM(a).apply { initialPrefetchItemCount = 3; alignTop = a.tv }     // التلفزيون: الصف المحدد فوق
        rows.adapter = shelfAdapter
        rows.setHasFixedSize(true)
        rows.itemAnimator = null              // بدون حركات = سحب أنعم
        rows.setItemViewCacheSize(4)          // الصفوف اللي طلعت للتو تبقى جاهزة لو رجعت لها
        rows.setItemViewCacheSize(4)
        main.addView(rows, lp(MATCH, 0, 1f))
        root.addView(main)

        // شبكة "الكل"
        val gh = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), a.dp(10), a.dp(16), a.dp(4)) }
        // التلفزيون: بدون زر رجوع على الشاشة (زر الريموت يرجع) — عنوان كبير بس
        if (!a.tv) gh.addView(a.iconButton(R.drawable.ic_chev_right, "رجوع", 42) { closeGrid() })
        else { gridTitle.textSize = 24f; gh.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(6)) }
        gh.addView(gridTitle, lp(0, WRAP, 1f).apply { marginStart = a.dp(6) })
        gridBox.addView(gh)
        sortRow.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(8))
        gridBox.addView(sortRow)
        grid.adapter = gridPosters
        grid.setHasFixedSize(true)
        grid.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
        grid.clipToPadding = false
        grid.layoutManager = SmoothGridLM(a, 3)
        grid.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val span = maxOf(2, (r - l - v.paddingLeft - v.paddingRight) / (posterW + a.dp(10)))
            val lm = grid.layoutManager as GridLayoutManager
            if (lm.spanCount != span) v.post { lm.spanCount = span }
        }
        gridBox.setBackgroundColor(C.BG)
        gridBox.addView(grid, lp(MATCH, 0, 1f))
        root.addView(gridBox, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(loading, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.CENTER))
        root.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(a.dp(30), 0, a.dp(30), 0) })
        view = root
        load()
    }

    private fun load() {
        val api = Session.api ?: return
        loading.visibility = View.VISIBLE
        a.scope.launch {
            try {
                val (c, items) = withContext(Bg.io) {
                    (if (series) api.seriesCategories() else api.vodCategories()) to api.vods(series)
                }
                all = items; cats = c
                buildShelves()
            } catch (e: Exception) {
                empty.text = "${e.message ?: "تعذر التحميل"}\n\nاضغط هنا لإعادة المحاولة"
                empty.visibility = View.VISIBLE
                empty.isFocusable = true; empty.isClickable = true
                empty.setOnClickListener { empty.visibility = View.GONE; load() }
                empty.requestFocus()
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun buildShelves() {
        val byCat = all.groupBy { it.categoryId }
        val list = ArrayList<Shelf>()
        val favIds = Store.favs(kind)
        builtFavs = favIds
        if (favIds.isNotEmpty()) {
            val m = all.associateBy { it.id }
            list.add(Shelf(FAV, "المفضلة", favIds.mapNotNull { m[it] }))
        }
        list.add(Shelf(NEW, "أُضيف مؤخراً", all.sortedByDescending { it.added }.take(30)))
        cats.forEach { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { list.add(Shelf(c.id, c.name, it)) } }
        shelfAdapter.items = list.filter { it.items.isNotEmpty() }
        hero?.setFeatured(all.maxByOrNull { it.added })
        empty.text = "ما فيه $title"
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- شبكة "الكل" ----------
    private fun openGrid(shelf: Shelf) {
        gridTitle.text = shelf.title
        gridItems = when (shelf.id) {
            NEW -> all
            FAV -> shelf.items
            else -> all.filter { it.categoryId == shelf.id }
        }
        sort = if (shelf.id == NEW) "new" else sort
        if (a.tv) gridTitle.text = "${shelf.title}  ·  ${gridItems.size}"
        buildSort()
        applySort()
        gridBox.visibility = View.VISIBLE
        gridBox.fadeIn(a.dp(24))
        if (a.tv) {
            // الصفوف اللي ورا الصفحة تنخفي (قبل: التحديد كان ينزل لها وهي مغطّاة = تنقّل عشوائي)
            sortRow.findViewWithTag<View>(sort)?.requestFocus()
            mainBox?.visibility = View.INVISIBLE
            grid.postDelayed({ if (gridBox.visibility == View.VISIBLE) firstOf(grid) }, 80)
        } else grid.post { (grid.getChildAt(0) ?: grid).requestFocus() }
    }

    private fun buildSort() {
        sortRow.removeAllViews()
        listOf("new" to "الأحدث", "rating" to "الأعلى تقييم", "name" to "أ-ي").forEach { (k, t) ->
            val on = k == sort
            sortRow.addView(a.text(t, 12.5f, if (on) C.PRIMARY_TEXT else C.TEXT2, lines = 0).apply {
                gravity = Gravity.CENTER
                setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7))
                background = a.focusBg(normal = if (on) C.PRIMARY else C.SURFACE2, radius = 16, selected = if (on) C.PRIMARY else C.ACCENT_SOFT)
                isFocusable = true; isClickable = true
                tag = k
                // الترتيب: التحديد يبقى على نفس الزر بعد ما تنبني الأزرار من جديد (قبل: يطير لمكان ثاني)
                setOnClickListener { sort = k; buildSort(); applySort(); sortRow.findViewWithTag<View>(k)?.requestFocus() }
            }, lp(WRAP, WRAP).apply { marginEnd = a.dp(8) })
        }
    }

    private fun applySort() {
        gridPosters.items = when (sort) {
            "rating" -> gridItems.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            "name" -> gridItems.sortedBy { it.name.lowercase() }
            else -> gridItems.sortedByDescending { it.added }
        }
        grid.scrollToPosition(0)
    }

    private fun closeGrid() {
        if (a.tv) mainBox?.visibility = View.VISIBLE
        gridBox.fadeOut(a.dp(24)) { gridBox.visibility = View.GONE }
        if (a.tv) {
            // نرجع لخانة «المزيد» بنفس الصف
            rows.post {
                val vh = rows.findViewHolderForAdapterPosition(lastRowFocus) as? ShelfAdapter.VH
                if (vh != null) TvHero.focusItem(vh.strip, vh.posters.itemCount - 1) else rows.requestFocus()
            }
            return
        }
        rows.post { (rows.findViewHolderForAdapterPosition(lastRowFocus)?.itemView ?: rows).requestFocus() }
    }

    override fun onBack(): Boolean {
        if (gridBox.visibility == View.VISIBLE) { closeGrid(); return true }
        return false
    }

    /** نعيد بناء الصفوف بس لو المفضلة تغيّرت (غير كذا الصفحة تبقى زي ما هي: نفس المكان ونفس الصور). */
    private var builtFavs: List<String>? = null
    override fun onShow() {
        hero?.restore()
        if (all.isNotEmpty() && Store.favs(kind) != builtFavs) buildShelves()
    }

    override fun focusDefault() {
        // التلفزيون: من القائمة الجانبية = أول بوستر (مو زر «الكل»)، والصف يرجع لأوله
        if (gridBox.visibility == View.VISIBLE) { firstOf(grid); return }
        hero?.let { h -> if (h.hasFeatured) { h.setRows(false, mainBox, rows); rows.scrollToPosition(0); h.play.requestFocus(); return } }
        rows.scrollToPosition(0)
        rows.post {
            val h = rows.findViewHolderForAdapterPosition(0) as? ShelfAdapter.VH
            if (h == null) { rows.requestFocus(); return@post }
            rowScroll.remove(h.boundId)
            firstOf(h.strip)
        }
    }

    private fun firstOf(list: RecyclerView) {
        list.scrollToPosition(0)
        list.post { (list.findViewHolderForAdapterPosition(0)?.itemView ?: list.getChildAt(0) ?: list).requestFocus() }
    }

    // ---------- الصفوف ----------
    /** صف أفقي: RecyclerView أفقي داخل القائمة (يدعم RTL ويعيد استخدام الخانات). */
    private inner class ShelfAdapter : RecyclerView.Adapter<ShelfAdapter.VH>() {
        var items: List<Shelf> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(root: LinearLayout, val title: TextView, val more: TextView, val strip: RecyclerView, val posters: PosterAdapter) : RecyclerView.ViewHolder(root) {
            var boundId = ""
        }

        override fun onViewRecycled(h: VH) {
            saveRow(h)
            super.onViewRecycled(h)
        }

        fun saveRow(h: VH) {
            if (h.boundId.isEmpty()) return
            val lm = h.strip.layoutManager as LinearLayoutManager
            val p = lm.findFirstVisibleItemPosition()
            if (p >= 0) rowScroll[h.boundId] = p to (lm.findViewByPosition(p)?.let { v -> if (h.strip.layoutDirection == View.LAYOUT_DIRECTION_RTL) h.strip.width - h.strip.paddingRight - v.right else v.left - h.strip.paddingLeft } ?: 0)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                setPadding(0, a.dp(6), 0, a.dp(6))
            }
            val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(16), a.dp(4), a.dp(10), a.dp(2)) }
            val t = a.text("", 15.5f, C.TEXT, bold = true)
            head.addView(t, lp(0, WRAP, 1f))
            val more = a.text("الكل  ‹", 13f, C.ACCENT, lines = 0).apply {
                gravity = Gravity.CENTER
                setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6))
                background = a.focusBg(radius = 8)
                isFocusable = true; isClickable = true
            }
            if (a.tv) more.visibility = View.GONE        // التلفزيون (نتفلكس): الصف فيه كل التصنيف — بدون زر بين الصفوف يلخبط التنقل
            head.addView(more)
            root.addView(head)
            val posters = PosterAdapter(a, posterW) { a.openDetails(it) }
            // صف أفقي بـ RecyclerView (يدعم الاتجاه من اليمين لليسار تلقائياً ويعيد استخدام الخانات)
            val strip = RecyclerView(a).apply {
                layoutManager = SmoothLM(a, LinearLayoutManager.HORIZONTAL).apply { initialPrefetchItemCount = 5; alignStart = a.tv }
                setRecycledViewPool(posterPool(posterW))      // خانات مشتركة بين كل الصفوف
                adapter = posters
                setHasFixedSize(true)
                itemAnimator = null
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
                horizontalInVertical()
            }
            root.addView(strip, lp(MATCH, WRAP))
            return VH(root, t, more, strip, posters)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.title.text = s.title
            val list = s.items.take(SHELF_MAX)              // الباقي في "الكل" (صور أقل = أخف)
            if (h.posters.items != list) h.posters.items = list
            h.boundId = s.id
            // كل صف يتذكّر وين وقفت فيه (ما يرجع للبداية لما ترجع له)
            val (p, off) = rowScroll[s.id] ?: (0 to 0)
            (h.strip.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, off)
            h.more.setOnClickListener { lastRowFocus = h.bindingAdapterPosition; openGrid(s) }
            // التلفزيون: «المزيد» بآخر الصف = كل التصنيف بصفحة (نتفلكس) — بدل زر بين الصفوف
            if (a.tv) h.posters.onMore = { lastRowFocus = h.bindingAdapterPosition; openGrid(s) }
        }
    }

    companion object {
        const val FAV = "__fav"
        const val NEW = "__new"
        const val SHELF_MAX = 15
    }
}
