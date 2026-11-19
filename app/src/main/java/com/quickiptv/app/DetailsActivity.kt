package com.quickiptv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** صفحة الفيلم / المسلسل: صورة كبيرة، معلومات، زر تشغيل أو متابعة، المفضلة، والمواسم والحلقات. */
class DetailsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var col: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var episodesBox: LinearLayout
    private lateinit var seasonsRow: LinearLayout
    private var id = ""
    private var series = false
    private var name = ""
    private var icon = ""
    private var ext = "mp4"
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var season = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        id = intent.getStringExtra("id") ?: ""
        series = intent.getBooleanExtra("series", false)
        name = intent.getStringExtra("name") ?: ""
        icon = intent.getStringExtra("icon") ?: ""
        ext = intent.getStringExtra("ext") ?: "mp4"
        val tv = isTv()

        val sc = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(col)
        val heroH = if (tv) dp(300) else resources.displayMetrics.widthPixels * 10 / 16
        val hero = FrameLayout(this)
        hero.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f
            load(icon.ifEmpty { null }) { size(1000, 600) }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        hero.addView(iconButton(R.drawable.ic_chev_right, "رجوع") { finish() },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        col.addView(hero, lp(MATCH, heroH))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (tv) 32 else 16), dp(14), dp(if (tv) 32 else 16), dp(30)) }
        col.addView(body)
        body.addView(text(if (series) "مسلسل" else "فيلم", 12f, C.MUTED).apply { tag = "kicker" })
        body.addView(text(name, if (tv) 26f else 21f, C.TEXT, bold = true, lines = 3).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0, dp(4), 0, dp(8)) })
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(actions, lp(MATCH, WRAP))
        val plot = text("", 13.5f, C.TEXT2, lines = 6).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.3f) }
        body.addView(plot)
        seasonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(HorizontalScrollView(this).apply { addView(seasonsRow); isHorizontalScrollBarEnabled = false; setPadding(0, dp(14), 0, dp(6)) })
        episodesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(episodesBox)
        setContentView(sc)
        buildActions()

        if (Session.api == null) Store.current?.let { Session.open(this, it) }
        val api = Session.api ?: return finish()
        scope.launch {
            try {
                if (series) {
                    val (info, s) = withContext(Dispatchers.IO) { api.seriesInfo(id) }
                    plot.text = info.optString("plot")
                    meta(body, info.optString("releaseDate").take(4), info.optString("genre"), info.optString("rating"))
                    seasons = s
                    season = s.keys.firstOrNull() ?: 1
                    buildSeasons()
                } else {
                    val info = withContext(Dispatchers.IO) { api.vodInfo(id) }
                    plot.text = info.optString("plot").ifEmpty { info.optString("description") }
                    meta(body, info.optString("releasedate").take(4), info.optString("genre"), info.optString("rating"), info.optString("duration"))
                }
            } catch (e: Exception) {
                plot.text = e.message ?: "تعذر تحميل التفاصيل"
            }
        }
    }

    private fun meta(body: LinearLayout, year: String, genre: String, rating: String, dur: String = "") {
        val k = body.findViewWithTag<android.widget.TextView>("kicker") ?: return
        k.text = listOf(if (series) "مسلسل" else "فيلم", year, genre.split(",").firstOrNull()?.trim() ?: "",
            rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) } ?: "", dur.take(5))
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun buildActions() {
        actions.removeAllViews()
        if (!series) {
            val key = "movie:$id"
            val resume = Store.resumePoint(key)
            val play = button(if (resume > 0) "متابعة · ${fmtTime(resume)}" else "تشغيل", R.drawable.ic_play, primary = true) {
                play(Session.api!!.vodUrl(id, ext), key, name, "", resume, "movie")
            }
            actions.addView(play)
            actions.addView(View(this), lp(dp(8), 1))
            if (resume > 0) {
                actions.addView(button("من البداية") { play(Session.api!!.vodUrl(id, ext), key, name, "", 0, "movie") })
                actions.addView(View(this), lp(dp(8), 1))
            }
            play.post { play.requestFocus() }
        }
        val kind = if (series) "series" else "movie"
        val fav = Store.isFav(kind, id)
        actions.addView(button(if (fav) "في المفضلة" else "المفضلة", R.drawable.ic_heart) {
            Store.toggleFav(kind, id); buildActions()
        })
    }

    private fun buildSeasons() {
        seasonsRow.removeAllViews()
        if (seasons.size > 1) seasons.keys.forEach { s ->
            seasonsRow.addView(button("الموسم $s", primary = s == season) { season = s; buildSeasons() })
            seasonsRow.addView(View(this), lp(dp(8), 1))
        }
        episodesBox.removeAllViews()
        val list = seasons[season] ?: emptyList()
        var focus: View? = null
        list.forEach { e ->
            val key = "ep:${e.id}"
            val h = Store.history().firstOrNull { it.key == key }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = focusBg(normal = C.SURFACE, radius = 10)
                isFocusable = true; isClickable = true
                layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(6) }
                setOnClickListener {
                    play(Session.api!!.episodeUrl(e.id, e.ext), key, name, "الموسم ${e.season} · الحلقة ${e.num}", Store.resumePoint(key), "episode")
                }
            }
            row.addView(text("${e.num}", 16f, C.MUTED, bold = true).apply { layoutParams = lp(dp(36), WRAP) })
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(text(e.title.ifEmpty { "الحلقة ${e.num}" }, 14f, C.TEXT).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
            val sub = when {
                h != null && h.dur > 0 && h.pos >= h.dur - 60 -> "✓ شاهدتها"
                h != null && h.pos > 30 -> "متبقي ${fmtTime(h.dur - h.pos)}"
                else -> e.duration
            }
            if (sub.isNotEmpty()) c.addView(text(sub, 12f, C.MUTED).apply { setPadding(0, dp(3), 0, 0) })
            row.addView(c, lp(0, WRAP, 1f))
            row.addView(icon(R.drawable.ic_play, C.TEXT2, 18))
            episodesBox.addView(row)
            if (focus == null && (h == null || h.pos < h.dur - 60)) focus = row
        }
        (focus ?: episodesBox.getChildAt(0))?.let { v -> v.post { v.requestFocus() } }
    }

    private fun play(url: String, key: String, title: String, sub: String, start: Long, type: String) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", url).putExtra("key", key).putExtra("title", title).putExtra("sub", sub)
            .putExtra("image", icon).putExtra("type", type).putExtra("seriesId", if (series) id else "")
            .putExtra("start", start))
    }

    override fun onResume() {
        super.onResume()
        buildActions()
        if (series && seasons.isNotEmpty()) buildSeasons()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
