package com.quickiptv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * صفحة الفيلم / المسلسل.
 * - عمودي: صورة كبيرة فوق (تمتد خلف شريط الساعة) ثم المعلومات والأزرار والحلقات.
 * - أفقي (والتلفزيون): عمودين — المعلومات والأزرار بجنب، والحلقات بجنب تتسحب لحالها.
 * الحلقات في RecyclerView: ما ينرسم إلا اللي ظاهر (سحب ناعم حتى مع مواسم طويلة).
 */
class DetailsActivity : Activity() {
    private val scope = MainScope()
    private var id = ""
    private var series = false
    private var name = ""
    private var icon = ""
    private var ext = "mp4"

    // الحالة (تبقى لما تقلب الجوال)
    private var kicker = ""
    private var plot = ""
    private var plotOpen = false
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var season = 1
    private var loaded = false
    private var hist: Map<String, HistoryItem> = emptyMap()

    // مسافات أشرطة النظام
    private var inL = 0; private var inT = 0; private var inR = 0; private var inB = 0

    private lateinit var root: FrameLayout
    private var dlBtn: LinearLayout? = null
    private var epList: RecyclerView? = null
    private val episodes = EpisodeAdapter()
    private val dlListener: (Dl?) -> Unit = { refreshDl() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        edgeToEdge()
        installFocusMotion()
        id = intent.getStringExtra("id") ?: ""
        series = intent.getBooleanExtra("series", false)
        name = intent.getStringExtra("name") ?: ""
        icon = intent.getStringExtra("icon") ?: ""
        ext = intent.getStringExtra("ext") ?: "mp4"
        kicker = if (series) "مسلسل" else "فيلم"

        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(root)
        root.onSystemInsets { l, t, r, b ->
            if (l != inL || t != inT || r != inR || b != inB) { inL = l; inT = t; inR = r; inB = b; render() }
        }
        hist = Store.history().associateBy { it.key }
        render()

        if (Session.api == null) Store.current?.let { Session.open(this, it) }
        val api = Session.api ?: return finish()
        scope.launch {
            try {
                if (series) {
                    val (info, s) = withContext(Dispatchers.IO) { api.seriesInfo(id) }
                    plot = info.optString("plot")
                    kicker = meta(info.optString("releaseDate").take(4), info.optString("genre"), info.optString("rating"))
                    seasons = s
                    season = seriesResume()?.first?.season ?: s.keys.firstOrNull() ?: 1
                } else {
                    val info = withContext(Dispatchers.IO) { api.vodInfo(id) }
                    plot = info.optString("plot").ifEmpty { info.optString("description") }
                    kicker = meta(info.optString("releasedate").take(4), info.optString("genre"), info.optString("rating"), info.optString("duration"))
                }
            } catch (e: Exception) {
                plot = e.message ?: "تعذر تحميل التفاصيل"
            }
            loaded = true
            render()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mini != null && wide && System.currentTimeMillis() - resumedAt > 3000) goFull()      // لفّيت الجوال أفقي وهو يشتغل = ملء الشاشة
        else if (!wide && backToMini != null && System.currentTimeMillis() - resumedAt < 3000) { render(); resumeMini() }
        else render()
    }

    private fun meta(year: String, genre: String, rating: String, dur: String = ""): String =
        listOf(if (series) "مسلسل" else "فيلم", year, genre.split(",").firstOrNull()?.trim() ?: "",
            rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(java.util.Locale.US, it) } ?: "", dur.take(5))
            .filter { it.isNotEmpty() }.joinToString(" · ")

    // =====================================================================
    // البناء
    private val wide get() = isTv() || landscape

    private fun render() {
        val focusTag = root.findFocus()?.tag as? String
        epList?.adapter = null          // القائمة القديمة تفك ارتباطها قبل ما نبني الجديدة
        root.removeAllViews()
        dlBtn = null; epList = null
        if (wide) renderWide() else renderTall()
        refreshDl()
        val target: View? = focusTag?.let { root.findViewWithTag<View?>(it) } ?: root.findViewWithTag<View?>("primary")
        target?.post { target.requestFocus() }
    }

    /** عمودي: صورة كبيرة فوق ثم كل شي تحت بعض. */
    private fun renderTall() {
        val heroH = resources.displayMetrics.widthPixels * 9 / 16 + inT
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hero = FrameLayout(this)
        val mv = miniView
        if (mv != null) {
            // المشغّل المصغّر مكان الصورة (تحت شريط الساعة)
            hero.setBackgroundColor(0xFF000000.toInt())
            (mv.parent as? ViewGroup)?.removeView(mv)
            hero.addView(mv, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = inT })
        } else hero.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f
            load(icon.ifEmpty { null }) { size(900, 520) }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        hero.addView(backButton(), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { topMargin = inT + dp(6); marginStart = dp(10) + maxOf(inL, inR) })
        head.addView(hero, lp(MATCH, heroH))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16) + inL, dp(14), dp(16) + inR, dp(6)) }
        info(body, big = true)
        head.addView(body)

        if (series) {
            // المسلسل: الرأس + المواسم + الحلقات كلها في قائمة وحدة (ينرسم الظاهر بس)
            val chips = seasonChips()
            if (chips != null) head.addView(chips, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })
            if (!loaded) head.addView(loadingView())
            episodes.header = head
            root.addView(episodeList().apply { setPadding(inL, 0, inR, inB + dp(20)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val sc = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
            head.setPadding(0, 0, 0, inB + dp(24))
            sc.addView(head)
            root.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    /** أفقي/تلفزيون: عمودين بدون ما تحتاج تسحب عشان توصل للأزرار. */
    private fun renderWide() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(inL + dp(12), inT + dp(10), inR + dp(12), 0)
        }
        // خلفية خفيفة من صورة العمل
        root.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.12f
            load(icon.ifEmpty { null }) { size(640, 360) }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))

        val screenH = resources.displayMetrics.heightPixels - inT - inB
        val side = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val sideCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, inB + dp(16)) }
        side.addView(sideCol)

        if (series) {
            // يمين: بطاقة المسلسل والأزرار — يسار: المواسم والحلقات
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            top.addView(backButton(), lp(dp(40), dp(40)))
            top.addView(poster(), lp(dp(if (isTv()) 96 else 72), dp(if (isTv()) 144 else 108)).apply { marginStart = dp(4); marginEnd = dp(12) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(kicker, 12f, C.MUTED, lines = 2))
            t.addView(text(name, if (isTv()) 22f else 17f, C.TEXT, bold = true, lines = 3).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0, dp(3), 0, 0) })
            top.addView(t, lp(0, WRAP, 1f))
            sideCol.addView(top)
            sideCol.addView(actions(compact = true), lp(MATCH, WRAP).apply { topMargin = dp(12) })
            sideCol.addView(plotView())
            row.addView(side, lp(0, MATCH, 0.42f))
            row.addView(View(this).apply { setBackgroundColor(C.BORDER) }, lp(1, MATCH).apply { setMargins(dp(12), 0, dp(4), 0) })

            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            seasonChips()?.let { right.addView(it, lp(MATCH, WRAP).apply { bottomMargin = dp(4) }) }
            if (!loaded) right.addView(loadingView())
            episodes.header = null
            right.addView(episodeList().apply { setPadding(0, 0, 0, inB + dp(16)) }, lp(MATCH, 0, 1f))
            row.addView(right, lp(0, MATCH, 0.58f))
        } else {
            // الفيلم: البوستر بجنب والمعلومات بجنب
            val posterH = (screenH - dp(30)).coerceAtMost(dp(if (isTv()) 360 else 300))
            val pc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pc.addView(backButton(), lp(dp(40), dp(40)))
            row.addView(pc)
            row.addView(poster(), lp(posterH * 2 / 3, posterH).apply { marginEnd = dp(18); topMargin = dp(4) })
            info(sideCol, big = isTv())
            row.addView(side, lp(0, MATCH, 1f))
        }
    }

    private fun backButton() = iconButton(R.drawable.ic_chev_right, "رجوع") { finish() }.apply {
        background = focusBg(normal = 0x66000000, radius = 22)
    }

    private fun poster() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(C.SURFACE2, dpf(10f)); clipToOutline = true
        load(icon.ifEmpty { null }) { size(360, 540) }
    }

    private fun loadingView() = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }
        .also { it.layoutParams = lp(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20) } }

    /** العنوان + المعلومات + الأزرار + القصة. */
    private fun info(col: LinearLayout, big: Boolean) {
        col.addView(text(kicker, 12.5f, C.MUTED, lines = 2))
        col.addView(text(name, if (big) 22f else 19f, C.TEXT, bold = true, lines = 3).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0, dp(4), 0, dp(12)) })
        col.addView(actions(compact = false), lp(MATCH, WRAP))
        col.addView(plotView())
    }

    private fun plotView(): View = text(if (loaded) plot else "", 13.5f, C.TEXT2, lines = if (plotOpen) 40 else 4).apply {
        setPadding(0, dp(12), 0, dp(4)); setLineSpacing(0f, 1.3f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (plot.length > 160) { isClickable = true; setOnClickListener { plotOpen = !plotOpen; maxLines = if (plotOpen) 40 else 4 } }
    }

    /**
     * الأزرار: الأساسي (تشغيل/متابعة) بعرض كامل، وتحته صف صغير (من البداية، تحميل، المفضلة).
     * كذا ما تتزاحم على الجوالات الصغيرة.
     */
    private fun actions(compact: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sec = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addSec(v: View, weight: Float = 1f) {
            if (sec.childCount > 0) sec.addView(View(this), lp(dp(8), 1))
            sec.addView(v, lp(0, WRAP, weight))
        }
        if (!series) {
            val key = "movie:$id"
            val resume = Store.resumePoint(key)
            val local = Downloads.localFile(key) != null
            box.addView(button(if (resume > 0) "متابعة · ${fmtTime(resume)}" else if (local) "تشغيل (محمّل)" else "تشغيل", R.drawable.ic_play, primary = true) {
                play(Session.api!!.vodUrl(id, ext), key, name, "", resume, "movie")
            }.apply { tag = "primary" }, lp(MATCH, WRAP))
            if (resume > 0) addSec(button("من البداية", R.drawable.ic_refresh) { play(Session.api!!.vodUrl(id, ext), key, name, "", 0, "movie") }.apply { tag = "restart" })
            addSec(button("تحميل", R.drawable.ic_download) { movieDlClick() }.apply { tag = "dl" }.also { dlBtn = it }, 1.3f)
        } else {
            val r = seriesResume()
            box.addView(button(r?.second ?: (if (loaded) "ما فيه حلقات" else "تشغيل"), R.drawable.ic_play, primary = true) {
                r?.first?.let { playEpisode(it) }
            }.apply { tag = "primary" }, lp(MATCH, WRAP))
            if (seasons.isNotEmpty()) addSec(button(if (compact) "الموسم" else "تحميل الموسم", R.drawable.ic_download) { downloadSeason() }.apply { tag = "dlseason" }, 1.4f)
        }
        val kind = if (series) "series" else "movie"
        val fav = Store.isFav(kind, id)
        addSec(button(if (fav) "مفضّل" else "المفضلة", R.drawable.ic_heart) { Store.toggleFav(kind, id); render() }.apply {
            tag = "fav"
            if (fav) (getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(C.HEART)
        })
        box.addView(sec, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        return box
    }

    private fun seasonChips(): View? {
        if (seasons.size <= 1) return null
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        seasons.keys.forEach { s ->
            val on = s == season
            row.addView(text("الموسم $s", 13f, if (on) C.PRIMARY_TEXT else C.TEXT2, lines = 0).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = focusBg(normal = if (on) C.PRIMARY else C.SURFACE2, radius = 16, selected = if (on) C.PRIMARY else C.ACCENT_SOFT)
                isFocusable = true; isClickable = true; tag = "season:$s"
                setOnClickListener { if (season != s) { season = s; render() } }
            }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row) }
    }

    private fun episodeList(): RecyclerView = RecyclerView(this).apply {
        layoutManager = SmoothLM(this@DetailsActivity)
        itemAnimator = null
        setHasFixedSize(true)
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        setItemViewCacheSize(6)
        episodes.items = seasons[season] ?: emptyList()
        adapter = episodes
        epList = this
        // نوصل لأول حلقة ما خلصتها
        val i = episodes.items.indexOfFirst { e -> hist["ep:${e.id}"].let { h -> h == null || h.dur == 0L || h.pos < h.dur - 60 } }
        if (i > 2) scrollToPosition(i + (if (episodes.header != null) 1 else 0) - 1)
    }

    // =====================================================================
    // الحلقات
    private inner class EpisodeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var header: View? = null
        var items: List<Episode> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }
        private val off get() = if (header != null) 1 else 0

        inner class VH(val row: LinearLayout, val thumb: ImageView, val title: TextView, val sub: TextView, val bar: ProgressBar, val dl: FrameLayout) : RecyclerView.ViewHolder(row)
        inner class HeadVH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = items.size + off
        override fun getItemViewType(position: Int) = if (position < off) 0 else 1
        fun epAt(position: Int) = items.getOrNull(position - off)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val h = header!!
                (h.parent as? ViewGroup)?.removeView(h)
                h.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                return HeadVH(h)
            }
            val c = this@DetailsActivity
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(6), dp(8))
                isFocusable = true; isClickable = true
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(if (wide) 0 else 8), 0, dp(if (wide) 0 else 8), dp(2)) }
                pressScale(0.985f)
            }
            val tw = dp(if (isTv()) 128 else 104)
            val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(C.SURFACE2, dpf(7f)); clipToOutline = true }
            row.addView(thumb, lp(tw, tw * 9 / 16).apply { marginEnd = dp(12) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            val title = text("", 14f, C.TEXT, lines = 2).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
            val sub = text("", 12f, C.MUTED).apply { setPadding(0, dp(4), 0, 0) }
            val bar = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(C.ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(C.SURFACE3)
            }
            col.addView(title); col.addView(sub); col.addView(bar, lp(MATCH, dp(3)).apply { topMargin = dp(6) })
            row.addView(col, lp(0, WRAP, 1f))
            val dl = FrameLayout(c).apply { background = focusBg(radius = 22); isFocusable = true; isClickable = true; contentDescription = "تحميل الحلقة" }
            row.addView(dl, lp(dp(46), dp(46)).apply { marginStart = dp(4) })
            return VH(row, thumb, title, sub, bar, dl)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder is VH && payloads.isNotEmpty()) epAt(position)?.let { bindDl(holder.dl, it) } ?: Unit
            else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is VH) return
            val e = epAt(position) ?: return
            val key = "ep:${e.id}"
            val h = hist[key]
            val watched = h != null && h.dur > 0 && h.pos >= h.dur - 60
            val partial = h != null && h.dur > 0 && h.pos > 30 && !watched
            holder.row.tag = "ep:${e.id}"
            holder.row.background = focusBg(radius = 10, normal = if (partial) C.ACCENT_SOFT else 0)
            holder.row.setOnClickListener { playEpisode(e) }
            holder.thumb.load((e.image.ifEmpty { icon }).ifEmpty { null }) { size(320, 180) }
            holder.title.text = "${e.num}. ${e.title.ifEmpty { "الحلقة ${e.num}" }}"
            holder.sub.text = when {
                watched -> "✓ شاهدتها"
                partial -> "متبقي ${fmtTime(h!!.dur - h.pos)}"
                else -> e.duration
            }
            holder.sub.visibility = if (holder.sub.text.isEmpty()) View.GONE else View.VISIBLE
            holder.sub.setTextColor(if (watched) 0xFF6FCF97.toInt() else C.MUTED)
            holder.bar.visibility = if (partial) View.VISIBLE else View.GONE
            if (partial) holder.bar.progress = (h!!.pos * 1000 / h.dur).toInt()
            holder.dl.tag = "epdl:${e.id}"
            holder.dl.setOnClickListener { episodeDlClick(e) }
            bindDl(holder.dl, e)
        }

        fun refreshDl() { if (items.isNotEmpty()) notifyItemRangeChanged(off, items.size, "dl") }
    }

    private fun bindDl(box: FrameLayout, e: Episode) {
        val d = Downloads.find("ep:${e.id}")
        val state = when {
            d == null -> "none"
            d.status == "completed" -> "done"
            d.status == "paused" -> "paused"
            else -> "run:" + (if (d.total > 0) (d.frac * 100).toInt() else -1)
        }
        if (box.getTag(R.id.dl_state) == state) return       // ما تغيّر شي = ما نلمس الواجهة
        box.setTag(R.id.dl_state, state)
        box.removeAllViews()
        when {
            d == null -> box.addView(icon(R.drawable.ic_download, C.TEXT2, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            d.status == "completed" -> box.addView(icon(R.drawable.ic_check, 0xFF6FCF97.toInt(), 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            d.status == "paused" -> box.addView(icon(R.drawable.ic_pause, C.MUTED, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            else -> box.addView(text(if (d.total > 0) "${(d.frac * 100).toInt()}%" else "…", 11.5f, C.ACCENT, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    /** الحلقة اللي نكمل منها: آخر حلقة شغّلتها (أو اللي بعدها لو خلصتها). */
    private fun seriesResume(): Pair<Episode, String>? {
        if (seasons.isEmpty()) return null
        val all = seasons.values.flatten()
        val last = Store.history().firstOrNull { it.type == "episode" && it.seriesId == id } ?: return all.firstOrNull()?.let { it to "تشغيل · م${it.season} ح${it.num}" }
        val i = all.indexOfFirst { "ep:${it.id}" == last.key }
        if (i < 0) return all.firstOrNull()?.let { it to "تشغيل · م${it.season} ح${it.num}" }
        val done = last.dur > 0 && last.pos >= last.dur - 60
        val e = if (done) all.getOrNull(i + 1) ?: return null else all[i]
        return e to (if (done) "الحلقة التالية · م${e.season} ح${e.num}" else "متابعة · م${e.season} ح${e.num}")
    }

    private fun playEpisode(e: Episode) {
        val key = "ep:${e.id}"
        play(Session.api!!.episodeUrl(e.id, e.ext), key, name, "الموسم ${e.season} · الحلقة ${e.num}", Store.resumePoint(key), "episode")
    }

    // =====================================================================
    // التحميل
    private fun askNotify() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
    }

    private fun movieDlClick() {
        val d = Downloads.find("movie:$id")
        if (d == null) {
            val api = Session.api ?: return
            Downloads.add("movie:$id", "movie", name, api.vodUrl(id, ext), ext, icon)?.let { toast(it) } ?: run {
                toast("انضاف للتحميلات"); askNotify()
            }
            refreshDl()
        } else openDownloads()
    }

    private fun episodeDlClick(e: Episode) {
        val d = Downloads.find("ep:${e.id}")
        when {
            d == null -> { addEpisode(e); askNotify() }
            d.status == "downloading" || d.status == "queued" -> Downloads.pause(d.id)
            d.status == "paused" -> Downloads.resume(d.id)
            d.status == "completed" -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("الحلقة محمّلة وتشتغل بدون إنترنت.\nحذفها من الجهاز؟")
                .setPositiveButton("حذف") { _, _ -> Downloads.remove(d.id) }
                .setNegativeButton("إلغاء", null).show()
        }
        refreshDl()
    }

    private fun addEpisode(e: Episode): Boolean {
        val api = Session.api ?: return false
        return Downloads.add("ep:${e.id}", "episode", e.title.ifEmpty { "الحلقة ${e.num}" }, api.episodeUrl(e.id, e.ext), e.ext,
            icon, group = name, seriesId = id, season = e.season, epNum = e.num) == null
    }

    private fun downloadSeason() {
        val list = seasons[season] ?: return
        val n = list.count { Downloads.find("ep:${it.id}") == null && addEpisode(it) }
        toast(if (n > 0) "انضافت $n حلقة للتحميلات" else "كل حلقات الموسم في التحميلات")
        if (n > 0) askNotify()
        refreshDl()
    }

    private fun openDownloads() {
        MainActivity.pendingOpen = "downloads"
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    /** يحدّث حالة أزرار التحميل بمكانها (بدون إعادة بناء الصفحة). */
    private fun refreshDl() {
        dlBtn?.let { b ->
            val d = Downloads.find("movie:$id")
            val (ic, label) = when {
                d == null -> R.drawable.ic_download to "تحميل"
                d.status == "completed" -> R.drawable.ic_check to "محمّل"
                d.status == "paused" -> R.drawable.ic_pause to "متوقف ${(d.frac * 100).toInt()}%"
                d.waiting -> R.drawable.ic_download to "بانتظار"
                else -> R.drawable.ic_download to (if (d.total > 0) "${(d.frac * 100).toInt()}%" else "جاري…")
            }
            (b.getChildAt(0) as? ImageView)?.setImageResource(ic)
            (b.getChildAt(b.childCount - 1) as? TextView)?.text = label
        }
        episodes.refreshDl()
    }

    /** الجوال عمودي: يشتغل فوق مكان الصورة. أفقي/تلفزيون: ملء الشاشة مباشرة. */
    private fun play(url: String, key: String, title: String, sub: String, start: Long, type: String) {
        val r = PlayReq(url, key, title, sub, type)
        if (wide) { stopMini(); openFull(r, start) } else startMini(r, start)
    }

    private fun openFull(r: PlayReq, start: Long) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", r.url).putExtra("key", r.key).putExtra("title", r.title).putExtra("sub", r.sub)
            .putExtra("image", icon).putExtra("type", r.type).putExtra("seriesId", if (series) id else "")
            .putExtra("start", start))
    }

    // ---------- المشغّل المصغّر (الجوال عمودي) ----------
    private data class PlayReq(val url: String, val key: String, val title: String, val sub: String, val type: String)
    private var mini: ExoPlayer? = null
    private var miniView: PlayerView? = null
    private var miniReq: PlayReq? = null
    private var miniJob: Job? = null
    private var miniWatching = false
    private var backToMini: PlayReq? = null      // رجعنا من ملء الشاشة: نكمل في المصغّر

    private fun startMini(r: PlayReq, start: Long) {
        stopMini()
        Store.addHistory(HistoryItem(r.key, r.type, r.title, r.sub, icon, r.url, if (series) id else ""))
        val local = Downloads.localFile(r.key)
        val src = if (local != null) android.net.Uri.fromFile(local).toString() else {
            Downloads.setWatching("vod", true); miniWatching = true; r.url
        }
        val p = buildPlayer(applicationContext, false)
        p.addListener(miniListener)
        p.setMediaItem(MediaItem.fromUri(src), start * 1000)
        p.playWhenReady = true
        mini = p; miniReq = r
        miniView = makeMiniView(p)
        miniJob = scope.launch {
            // المشاهدة تاخذ اتصال من حد الاشتراك؛ لو فيه تحميل ماسكه ننتظر لحظة لين يتنازل
            if (local == null && Downloads.mustYieldFor()) delay(1200)
            p.prepare()
            saveLoop()
        }
        hist = Store.history().associateBy { it.key }
        render()
    }

    private val miniListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) root.post { saveMini(); stopMini(); hist = Store.history().associateBy { it.key }; render() }
        }
    }

    private suspend fun saveLoop() { while (true) { delay(10_000); saveMini() } }

    private fun makeMiniView(p: ExoPlayer) = PlayerView(this).apply {
        player = p
        setShutterBackgroundColor(0xFF000000.toInt())
        setBackgroundColor(0xFF000000.toInt())
        layoutDirection = View.LAYOUT_DIRECTION_LTR          // شريط التقدّم من اليسار لليمين
        setShowNextButton(false); setShowPreviousButton(false)
        controllerShowTimeoutMs = 3000
        setFullscreenButtonClickListener { goFull() }       // زر التكبير = أفقي ملء الشاشة
    }

    private var handedView: PlayerView? = null      // الشاشة الصغيرة وقت ما المشغّل بملء الشاشة

    /** تكبير بدون انقطاع: نفس المشغّل (وهو شغّال) ينتقل لملء الشاشة — بدون إيقاف ولا إعادة تحميل. */
    private fun goFull() {
        val r = miniReq ?: return
        val p = mini ?: return
        saveMini()
        miniJob?.cancel(); miniJob = null
        p.removeListener(miniListener)
        VodHandoff.drop()                   // (أي بقايا قديمة)
        VodHandoff.player = p; VodHandoff.key = r.key
        VodHandoff.watching = miniWatching; miniWatching = false
        VodHandoff.ownerAlive = true
        handedView = miniView                // الصورة تبقى هنا لين ملء الشاشة ياخذها (بدون فراغ)
        mini = null; miniReq = null; miniView = null
        backToMini = r
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", r.url).putExtra("key", r.key).putExtra("title", r.title).putExtra("sub", r.sub)
            .putExtra("image", icon).putExtra("type", r.type).putExtra("seriesId", if (series) id else "")
            .putExtra("start", p.currentPosition / 1000).putExtra("handoff", true))
    }

    /** رجعنا من ملء الشاشة: ناخذ نفس المشغّل ونكمل في الصغير بدون أي توقف. */
    private fun adoptBack(r: PlayReq): Boolean {
        if (VodHandoff.key != r.key) return false
        val p = VodHandoff.take() ?: return false
        mini = p; miniReq = r
        miniWatching = VodHandoff.watching; VodHandoff.watching = false; VodHandoff.key = ""
        p.addListener(miniListener)
        val v = handedView ?: makeMiniView(p)
        handedView = null
        v.player = null; v.player = p       // نرجّع الصورة للشاشة الصغيرة
        miniView = v
        p.playWhenReady = true
        miniJob = scope.launch { saveLoop() }
        return true
    }

    private fun saveMini() {
        val p = mini ?: return; val r = miniReq ?: return
        val d = p.duration
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return
        Store.saveProgress(r.key, p.currentPosition / 1000, d / 1000)
    }

    private fun stopMini() {
        miniJob?.cancel(); miniJob = null
        miniView?.let { v -> v.player = null; (v.parent as? ViewGroup)?.removeView(v) }
        miniView = null
        mini?.release(); mini = null; miniReq = null
        if (miniWatching) { Downloads.setWatching("vod", false); miniWatching = false }
    }

    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
        Downloads.removeListener(dlListener)
        saveMini(); mini?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(dlListener)
        if (paused) {
            // رجعنا من المشغّل: نحدّث التقدّم والزر الأساسي
            paused = false
            hist = Store.history().associateBy { it.key }
            render()
        }
        // رجعنا من ملء الشاشة والجوال عمودي: نكمل من نفس المكان في المصغّر
        // (لو الشاشة لسا أفقي لحظة الرجوع، نكمل أول ما ترجع عمودي)
        resumedAt = System.currentTimeMillis()
        backToMini?.let { r ->
            if (adoptBack(r)) {
                backToMini = null
                hist = Store.history().associateBy { it.key }
                render()
                // رجعت والجوال أفقي فعلاً (مو بس تأخير لفّ الشاشة): نوقف المصغّر
                if (wide) scope.launch { delay(1500); if (wide && mini != null) { saveMini(); stopMini(); render() } }
                return
            }
        }
        if (!wide) resumeMini()
    }

    private var resumedAt = 0L

    private fun resumeMini() {
        val r = backToMini ?: return
        backToMini = null
        val h = Store.history().firstOrNull { it.key == r.key }
        if (mini == null && h != null && !(h.dur > 0 && h.pos >= h.dur - 10)) startMini(r, h.pos)
    }

    override fun onDestroy() {
        saveMini(); stopMini()
        VodHandoff.ownerAlive = false
        if (VodHandoff.player != null && VodHandoff.key == backToMini?.key) VodHandoff.drop()
        handedView?.player = null; handedView = null
        scope.cancel()
        super.onDestroy()
    }
}
