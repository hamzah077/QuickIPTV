package com.quickiptv.app

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** الأفلام / المسلسلات: تصنيفات + شبكة بوسترات (يرسم الظاهر بس = سلس حتى مع آلاف العناصر). */
class LibraryScreen(private val a: MainActivity, private val series: Boolean) : Screen {
    private val kind = if (series) "series" else "movie"
    override val view: View
    private val catAdapter = CategoryAdapter(a, horizontal = !a.tv) { select(it.id) }
    private val posterW = a.dp(if (a.tv) 128 else 104)
    private val grid = RecyclerView(a)
    private val posters = PosterAdapter(a, posterW) { a.openDetails(it) }
    private val catList = RecyclerView(a)
    private val loading = ProgressBar(a).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }
    private val empty = a.text("", 14f, C.MUTED, lines = 3).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val header = a.text(if (series) "المسلسلات" else "الأفلام", 18f, C.TEXT, bold = true)
    private var all: List<Vod> = emptyList()
    private var catId = ""

    companion object {
        const val FAV = "__fav"
        const val ALL = "__all"
        const val NEW = "__new"
    }

    init {
        catList.layoutManager = LinearLayoutManager(a, if (a.tv) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL, false)
        catList.adapter = catAdapter
        grid.adapter = posters
        grid.setHasFixedSize(true)
        grid.setItemViewCacheSize(24)
        val root = FrameLayout(a)
        if (a.tv) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(12), a.dp(14), a.dp(12), 0) }
            row.addView(catList, lp(a.dp(210), MATCH).apply { marginEnd = a.dp(12) })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(header.apply { setPadding(a.dp(6), 0, 0, a.dp(8)) })
            col.addView(grid, lp(MATCH, 0, 1f))
            row.addView(col, lp(0, MATCH, 1f))
            root.addView(row)
        } else {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(12), 0, 0) }
            col.addView(header.apply { setPadding(a.dp(14), 0, a.dp(14), a.dp(4)) })
            col.addView(catList, lp(MATCH, a.dp(52)).apply { setMargins(a.dp(8), 0, a.dp(8), 0) })
            catList.setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(8)); catList.clipToPadding = false
            col.addView(grid, lp(MATCH, 0, 1f))
            grid.setPadding(a.dp(6), 0, a.dp(6), 0)
            root.addView(col)
        }
        root.addView(loading, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.CENTER))
        root.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(a.dp(30), 0, a.dp(30), 0) })
        view = root
        // عدد الأعمدة حسب العرض الفعلي
        grid.layoutManager = GridLayoutManager(a, 3)
        grid.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val span = maxOf(2, (r - l - v.paddingLeft - v.paddingRight) / (posterW + a.dp(10)))
            val lm = grid.layoutManager as GridLayoutManager
            if (lm.spanCount != span) v.post { lm.spanCount = span }
        }
        load()
    }

    private fun load() {
        val api = Session.api ?: return
        loading.visibility = View.VISIBLE
        a.scope.launch {
            try {
                val (cats, items) = withContext(Dispatchers.IO) {
                    (if (series) api.seriesCategories() else api.vodCategories()) to api.vods(series)
                }
                all = items
                catAdapter.items = listOf(Category(FAV, "المفضلة"), Category(NEW, "أُضيف مؤخراً"),
                    Category(ALL, if (series) "كل المسلسلات" else "كل الأفلام")) + cats
                val last = Store.lastCategory(kind)
                select(if (last.isNotEmpty() && catAdapter.items.any { it.id == last }) last else cats.firstOrNull()?.id ?: ALL)
            } catch (e: Exception) {
                empty.text = e.message ?: "تعذر التحميل"; empty.visibility = View.VISIBLE
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun select(id: String) {
        catId = id
        catAdapter.selectedId = id
        Store.setLastCategory(kind, id)
        val items = when (id) {
            ALL -> all
            NEW -> all.sortedByDescending { it.added }.take(200)
            FAV -> { val m = all.associateBy { it.id }; Store.favs(kind).mapNotNull { m[it] } }
            else -> all.filter { it.categoryId == id }
        }
        posters.items = items
        grid.scrollToPosition(0)
        header.text = catAdapter.items.firstOrNull { it.id == id }?.name ?: header.text
        empty.text = if (id == FAV) "ما فيه شي بالمفضلة بعد" else "ما فيه عناصر هنا"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onShow() { if (catId == FAV) select(FAV) }

    override fun focusDefault() {
        grid.post { (grid.getChildAt(0) ?: catList.getChildAt(0) ?: grid).requestFocus() }
    }
}
