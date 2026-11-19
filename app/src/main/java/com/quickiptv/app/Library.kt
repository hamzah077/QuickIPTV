package com.quickiptv.app

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
    private val rows = RecyclerView(a)
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

    init {
        val root = FrameLayout(a)
        val main = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(16), a.dp(12), a.dp(8), a.dp(4)) }
        head.addView(a.text(title, 20f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        head.addView(a.iconButton(R.drawable.ic_search, "بحث", 42) { a.openExtra("search") })
        main.addView(head)
        rows.layoutManager = LinearLayoutManager(a)
        rows.adapter = shelfAdapter
        rows.setItemViewCacheSize(4)
        main.addView(rows, lp(MATCH, 0, 1f))
        root.addView(main)

        // شبكة "الكل"
        val gh = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(8), a.dp(10), a.dp(16), a.dp(4)) }
        gh.addView(a.iconButton(R.drawable.ic_chev_right, "رجوع", 42) { closeGrid() })
        gh.addView(gridTitle, lp(0, WRAP, 1f).apply { marginStart = a.dp(6) })
        gridBox.addView(gh)
        sortRow.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(8))
        gridBox.addView(sortRow)
        grid.adapter = gridPosters
        grid.setHasFixedSize(true)
        grid.setPadding(a.dp(8), 0, a.dp(8), 0)
        grid.layoutManager = GridLayoutManager(a, 3)
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
                val (c, items) = withContext(Dispatchers.IO) {
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
        if (favIds.isNotEmpty()) {
            val m = all.associateBy { it.id }
            list.add(Shelf(FAV, "المفضلة", favIds.mapNotNull { m[it] }))
        }
        list.add(Shelf(NEW, "أُضيف مؤخراً", all.sortedByDescending { it.added }.take(30)))
        cats.forEach { c -> byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { list.add(Shelf(c.id, c.name, it)) } }
        shelfAdapter.items = list.filter { it.items.isNotEmpty() }
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
        buildSort()
        applySort()
        gridBox.visibility = View.VISIBLE
        grid.post { (grid.getChildAt(0) ?: grid).requestFocus() }
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
                setOnClickListener { sort = k; buildSort(); applySort() }
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
        gridBox.visibility = View.GONE
        rows.post { (rows.findViewHolderForAdapterPosition(lastRowFocus)?.itemView ?: rows).requestFocus() }
    }

    override fun onBack(): Boolean {
        if (gridBox.visibility == View.VISIBLE) { closeGrid(); return true }
        return false
    }

    override fun onShow() { if (all.isNotEmpty()) buildShelves() }

    override fun focusDefault() {
        if (gridBox.visibility == View.VISIBLE) grid.post { (grid.getChildAt(0) ?: grid).requestFocus() }
        else rows.post { rows.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: rows.requestFocus() }
    }

    // ---------- الصفوف ----------
    /** صف أفقي: RecyclerView أفقي داخل القائمة (يدعم RTL ويعيد استخدام الخانات). */
    private inner class ShelfAdapter : RecyclerView.Adapter<ShelfAdapter.VH>() {
        var items: List<Shelf> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        inner class VH(root: LinearLayout, val title: TextView, val more: TextView, val strip: RecyclerView, val posters: PosterAdapter) : RecyclerView.ViewHolder(root)

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
            head.addView(more)
            root.addView(head)
            val posters = PosterAdapter(a, posterW) { a.openDetails(it) }
            // صف أفقي بـ RecyclerView (يدعم الاتجاه من اليمين لليسار تلقائياً ويعيد استخدام الخانات)
            val strip = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
                adapter = posters
                setHasFixedSize(true)
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(a.dp(10), 0, a.dp(10), 0)
            }
            root.addView(strip, lp(MATCH, WRAP))
            return VH(root, t, more, strip, posters)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.title.text = s.title
            h.posters.items = s.items.take(SHELF_MAX)          // الباقي في "الكل" (صور أقل = أخف)
            h.strip.scrollToPosition(0)
            h.more.setOnClickListener { lastRowFocus = h.bindingAdapterPosition; openGrid(s) }
        }
    }

    companion object {
        const val FAV = "__fav"
        const val NEW = "__new"
        const val SHELF_MAX = 15
    }
}
