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
                    season = seriesResume()?.first?.season ?: s.keys.firstOrNull() ?: 1
                    buildSeasons()
                    buildActions()
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
        if (series) seriesResume()?.let { (e, label) ->
            val b = button(label, R.drawable.ic_play, primary = true) { playEpisode(e) }
            actions.addView(b)
            actions.addView(View(this), lp(dp(8), 1))
            b.post { b.requestFocus() }
        }
        val kind = if (series) "series" else "movie"
        val fav = Store.isFav(kind, id)
        actions.addView(button(if (fav) "في المفضلة" else "المفضلة", R.drawable.ic_heart) {
            Store.toggleFav(kind, id); buildActions()
        })
    }

    /** الحلقة اللي نكمل منها: آخر حلقة شغّلتها (أو اللي بعدها لو خلصتها). */
    private fun seriesResume(): Pair<Episode, String>? {
        if (seasons.isEmpty()) return null
        val all = seasons.values.flatten()
        val last = Store.history().firstOrNull { it.type == "episode" && it.seriesId == id } ?: return all.firstOrNull()?.let { it to "تشغيل · م${it.season} ح${it.num}" }
        val i = all.indexOfFirst { "ep:${it.id}" == last.key }
        if (i < 0) return null
        val done = last.dur > 0 && last.pos >= last.dur - 60
        val e = if (done) all.getOrNull(i + 1) ?: return null else all[i]
        return e to (if (done) "الحلقة التالية · م${e.season} ح${e.num}" else "متابعة · م${e.season} ح${e.num}")
    }

    private fun playEpisode(e: Episode) {
        val key = "ep:${e.id}"
        play(Session.api!!.episodeUrl(e.id, e.ext), key, name, "الموسم ${e.season} · الحلقة ${e.num}", Store.resumePoint(key), "episode")
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
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = focusBg(radius = 10, normal = if (h != null && h.pos > 30 && h.pos < h.dur - 60) C.ACCENT_SOFT else 0)
                isFocusable = true; isClickable = true
                layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(2) }
                setOnClickListener { playEpisode(e) }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(C.SURFACE2, dpf(7f)); clipToOutline = true
                load((e.image.ifEmpty { icon }).ifEmpty { null }) { size(320, 180) }
            }
            row.addView(thumb, lp(dp(112), dp(63)).apply { marginEnd = dp(12) })
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(text("${e.num}. ${e.title.ifEmpty { "الحلقة ${e.num}" }}", 14f, C.TEXT, lines = 2))
            val watched = h != null && h.dur > 0 && h.pos >= h.dur - 60
            val sub = when {
                watched -> "✓ شاهدتها"
                h != null && h.pos > 30 -> "متبقي ${fmtTime(h.dur - h.pos)}"
                else -> e.duration
            }
            if (sub.isNotEmpty()) c.addView(text(sub, 12f, if (watched) 0xFF6FCF97.toInt() else C.MUTED).apply { setPadding(0, dp(4), 0, 0) })
            if (h != null && h.dur > 0 && h.pos > 30 && !watched) c.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (h.pos * 1000 / h.dur).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(C.SURFACE3)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }, lp(MATCH, dp(3)).apply { topMargin = dp(6) })
            row.addView(c, lp(0, WRAP, 1f))
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
