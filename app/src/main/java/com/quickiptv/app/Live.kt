package com.quickiptv.app

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** كاش البرنامج الحالي من دليل السيرفر (للقنوات اللي تشغّلت). */
object Live {
    val nowCache = HashMap<String, Program>()
    const val FAV = "__fav"
    const val ALL = "__all"
}

/**
 * البث المباشر.
 * تلفزيون: [التصنيفات] [القنوات] [الشاشة + الدليل].  جوال: الشاشة فوق، التصنيفات أفقية، القنوات تحت.
 * ضغطة على قناة = تشغيل في الشاشة الصغيرة، ضغطة ثانية = ملء الشاشة. الأرقام تقفز لرقم القناة.
 */
@OptIn(UnstableApi::class)
class LiveScreen(private val a: MainActivity) : Screen, EpgAware {
    override val view: View
    private val catAdapter = CategoryAdapter(a, horizontal = !a.tv) { selectCategory(it.id) }
    private val chAdapter = ChannelAdapter(a, { ch, i -> clicked(ch, i) }, { ch, _ -> channelMenu(ch) })
    private val catList = RecyclerView(a)
    // الجوال بالوضع الأفقي: التصنيفات قائمة جانبية ظاهرة دايماً (بدل زر التصنيف)
    private val catSideAdapter = CategoryAdapter(a, horizontal = false) { selectCategory(it.id) }
    private val catSide = RecyclerView(a)
    private val chList = RecyclerView(a)
    private val playerView = PlayerView(a)
    private val title = a.text("", 15f, C.TEXT, bold = true)
    private val nowText = a.text("", 12.5f, C.TEXT2, lines = 2)
    private val epgBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val status = a.text("", 14f, C.MUTED, lines = 3).apply { gravity = Gravity.CENTER }
    private val loading = ProgressBar(a).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }
    private val catBtn = a.text("", 13f, C.TEXT2)
    // شريط الأزرار الموحّد تحت الشاشة الصغيرة: تأخير/إيقاف/تقديم يسار، ومباشر + تكبير يمين (ظاهر دايماً)
    private val bar = PlayerBar(a, live = true, autoHide = false).apply {
        onLive = { goLive() }
        onFullscreen = { current?.let { openFull(it) } }
    }
    private val searchBox = a.field("ابحث في هذا التصنيف…").apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }
    private var inCat: List<Channel> = emptyList()
    private var player: ExoPlayer? = null
    private var all: List<Channel> = emptyList()
    private var visible: List<Channel> = emptyList()
    private var current: Channel? = null
    private var catId = ""
    private var triedTs = false
    private var digits = ""
    private var digitJob: Job? = null
    private var epgJob: Job? = null
    private val numBadge = a.text("", 30f, C.PRIMARY_TEXT, bold = true).apply {
        background = rounded(C.ACCENT, a.dpf(12f)); setPadding(a.dp(18), a.dp(6), a.dp(18), a.dp(6)); visibility = View.GONE
    }

    // ملاحظة: لازم تكون قبل init (كوتلن يهيّئ الخصائص بالترتيب)
    private val root = FrameLayout(a)
    private lateinit var screenBox: FrameLayout
    private val phoneBar = LinearLayout(a)
    private val epgScroll by lazy { ScrollView(a).apply { addView(epgBox); isFocusable = false } }
    private var arrangedWide: Boolean? = null

    init {
        catList.layoutManager = SmoothLM(a, if (a.tv) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL)
        catList.adapter = catAdapter
        catSide.layoutManager = SmoothLM(a)
        catSide.adapter = catSideAdapter
        catSide.setHasFixedSize(true)
        chList.layoutManager = SmoothLM(a)
        chList.adapter = chAdapter
        chList.setHasFixedSize(true)
        chList.setItemViewCacheSize(20)
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setBackgroundColor(0xFF000000.toInt())
        playerView.setShutterBackgroundColor(0xFF000000.toInt())
        playerView.isFocusable = false

        val screen = FrameLayout(a).apply { background = rounded(0xFF000000.toInt(), a.dpf(12f)); clipToOutline = true }
        screen.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.addView(bar, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.addView(loading, FrameLayout.LayoutParams(a.dp(36), a.dp(36), Gravity.CENTER))
        screen.addView(status, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(a.dp(16), 0, a.dp(16), 0) })

        screenBox = screen
        // سطر الجوال: "القنوات" + زر التصنيف (يفتح قائمة) + بحث داخل القسم
        phoneBar.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(4)) }
        phoneBar.addView(a.text("القنوات", 17f, C.TEXT, bold = true), lp(WRAP, WRAP))
        phoneBar.addView(View(a), lp(0, 1, 1f))
        catBtn.apply {
            background = a.focusBg(normal = C.SURFACE2, radius = 9)
            setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
            isFocusable = true; isClickable = true
            setOnClickListener { pickCategory() }
        }
        phoneBar.addView(catBtn, lp(WRAP, WRAP).apply { marginEnd = a.dp(6) })
        phoneBar.addView(a.iconButton(R.drawable.ic_search, "بحث في القنوات", 40) { toggleSearch() })
        searchBox.visibility = View.GONE
        arrange()
        view = root
        load()
    }


    /**
     * ترتيب الشاشة حسب الاتجاه:
     * - عمودي (جوال): الشاشة فوق والقنوات تحت.
     * - أفقي (جوال) والتلفزيون: عمودين — القنوات بجنب، والشاشة والدليل بجنب (القنوات تبقى ظاهرة دايماً).
     */
    private fun arrange() {
        val wide = a.tv || a.landscape
        if (arrangedWide == wide) return
        arrangedWide = wide
        listOf<View>(catList, catSide, chList, screenBox, title, nowText, phoneBar, searchBox, epgScroll, numBadge).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        root.removeAllViews()
        if (wide) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(12), a.dp(if (a.tv) 14 else 6), a.dp(12), a.dp(8)) }
            if (a.tv) row.addView(catList, lp(a.dp(200), MATCH).apply { marginEnd = a.dp(10) })
            else row.addView(catSide, lp(0, MATCH, 0.62f).apply { marginEnd = a.dp(8) })      // التصنيفات جنب القنوات
            val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            if (!a.tv) {
                catBtn.visibility = View.GONE          // التصنيفات ظاهرة بالقائمة الجانبية
                phoneBar.setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(4))
                mid.addView(phoneBar)
                mid.addView(searchBox, lp(MATCH, WRAP).apply { setMargins(a.dp(4), 0, a.dp(4), a.dp(6)) })
            }
            mid.addView(chList, lp(MATCH, 0, 1f))
            row.addView(mid, lp(0, MATCH, if (a.tv) 1.1f else 1f).apply { marginEnd = a.dp(12) })
            val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            right.addView(screenBox, lp(MATCH, a.dp(120)))
            right.addView(title.apply { setPadding(a.dp(2), a.dp(10), 0, a.dp(2)); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
            right.addView(nowText.apply { setPadding(a.dp(2), 0, 0, 0) })
            if (a.tv) {
                // برامج اليوم تحت المعاينة (التلفزيون). الجوال بالأفقي ما فيه مساحة تحت الشاشة — يكفي "الآن"
                right.addView(a.text("برامج اليوم", 12.5f, C.MUTED, bold = true).apply { setPadding(a.dp(2), a.dp(10), 0, a.dp(6)) })
                right.addView(epgScroll, lp(MATCH, 0, 1f))
            }
            row.addView(right, lp(0, MATCH, if (a.tv) 1.4f else 1.15f))
            root.addView(row)
            // الشاشة 16:9 حسب عرض العمود
            right.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val h = (r - l) * 9 / 16
                val p = screenBox.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
                if (p.height != h) { p.height = h; v.post { screenBox.layoutParams = p } }
            }
        } else {
            val colm = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val w = minOf(a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
            colm.addView(screenBox, lp(MATCH, w * 9 / 16))
            colm.addView(title.apply { setPadding(a.dp(12), a.dp(8), a.dp(12), 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
            colm.addView(nowText.apply { setPadding(a.dp(12), a.dp(2), a.dp(12), 0) })
            catBtn.visibility = View.VISIBLE
            phoneBar.setPadding(a.dp(12), a.dp(8), a.dp(8), a.dp(4))
            colm.addView(phoneBar)
            colm.addView(searchBox, lp(MATCH, WRAP).apply { setMargins(a.dp(12), a.dp(2), a.dp(12), a.dp(6)) })
            colm.addView(a.divider())
            colm.addView(chList, lp(MATCH, 0, 1f))
            root.addView(colm)
        }
        root.addView(numBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = a.dp(24) })
    }

    override fun onConfig() {
        arrange()
        current?.let { ch -> chList.post { visible.indexOfFirst { it.id == ch.id }.takeIf { it >= 0 }?.let { chList.scrollToPosition(it) } } }
    }

    override fun onBack(): Boolean {
        if (searchBox.visibility == View.VISIBLE) { toggleSearch(); return true }
        return false
    }

    // ---------- البيانات ----------
    private fun load() {
        val api = Session.api ?: return
        a.scope.launch {
            try {
                val (cats, chans) = withContext(Bg.io) { api.liveCategories() to api.liveStreams() }
                all = chans
                catAdapter.items = listOf(Category(Live.FAV, "المفضلة"), Category(Live.ALL, "كل القنوات")) + cats
                catSideAdapter.items = catAdapter.items
                val last = Store.lastCategory("live")
                selectCategory(if (last.isNotEmpty() && catAdapter.items.any { it.id == last }) last else cats.firstOrNull()?.id ?: Live.ALL)
            } catch (e: Exception) {
                showStatus(e.message ?: "تعذر تحميل القنوات")
            }
        }
    }

    private fun selectCategory(id: String) {
        catId = id
        catAdapter.selectedId = id
        catSideAdapter.selectedId = id
        Store.setLastCategory("live", id)
        inCat = when (id) {
            Live.ALL -> all
            Live.FAV -> { val m = all.associateBy { it.id }; Store.favs("live").mapNotNull { m[it] } }
            else -> all.filter { it.categoryId == id }
        }
        val name = catAdapter.items.firstOrNull { it.id == id }?.name ?: ""
        catBtn.text = "$name · ${inCat.size}  ⌄"
        applyFilter()
        chList.scrollToPosition(0)
        val i = catAdapter.items.indexOfFirst { it.id == id }
        if (i >= 0) { catList.scrollToPosition(i); catSide.scrollToPosition(i) }
    }

    private fun applyFilter() {
        val t = SearchIndex.tokens(searchBox.text?.toString() ?: "")
        visible = if (t.isEmpty()) inCat else inCat.filter { SearchIndex.matches(SearchIndex.norm(it.name), t) }
        chAdapter.items = visible
    }

    private fun toggleSearch() {
        if (searchBox.visibility == View.VISIBLE) { searchBox.setText(""); searchBox.visibility = View.GONE }
        else { searchBox.visibility = View.VISIBLE; searchBox.requestFocus() }
    }

    private fun pickCategory() {
        pickDialog(a, "التصنيفات", catAdapter.items.map { it.id to it.name }, catId) { selectCategory(it) }
    }

    override fun onEpgLoaded() { chAdapter.notifyDataSetChanged(); current?.let { showEpg(it) } }

    // ---------- التشغيل ----------
    private fun clicked(ch: Channel, index: Int) {
        if (current?.id == ch.id && player != null) openFull(ch) else preview(ch)
    }

    private fun preview(ch: Channel) {
        current = ch
        chAdapter.playingId = ch.id
        title.text = ch.name
        nowText.text = ""
        triedTs = false
        playUrl((Session.api ?: return).liveUrl(ch.id))
        showEpg(ch)
        Store.addHistory(HistoryItem("live:${ch.id}", "live", ch.name, "", ch.icon, ""))
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                status.visibility = View.GONE
                if (triedTs) Session.preferTs = true      // TS اشتغلت بعد فشل HLS: القنوات الجاية TS على طول
            }
            syncPause()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncPause()

        override fun onPlayerError(error: PlaybackException) {
            val ch = current ?: return
            if (!triedTs && Store.liveFormat == "m3u8" && !Session.preferTs) {     // HLS ما اشتغل؟ نجرب TS مرة وحدة
                triedTs = true
                Session.api?.let { playUrl(it.liveUrl(ch.id, "ts"), force = true) }
            } else showStatus("القناة ما اشتغلت (${error.errorCodeName}). جرّب مرة ثانية أو قناة ثانية.")
        }
    }

    /** نربط الشاشة الصغيرة بالمشغّل المشترك. */
    private fun ensurePlayer(): ExoPlayer {
        val p = LivePlayer.get(a)
        if (player !== p) {
            player?.removeListener(listener)
            player = p
            p.addListener(listener)
            playerView.attachSmooth(p)       // أول ما تجهز الشاشة الصغيرة (بدون ومضة سودا)
            bar.player = p
        }
        return p
    }

    private fun playUrl(url: String, force: Boolean = false) {
        ensurePlayer()
        status.visibility = View.GONE
        loading.visibility = View.VISIBLE
        LivePlayer.play(a, url, force)
        syncPause()
    }

    /** زر مباشر: يرجّع للحظة الحالية (لو البث متوقف أو متأخر). */
    private fun goLive() {
        val p = player ?: return
        if (p.isCurrentMediaItemLive && p.isCurrentMediaItemSeekable) p.seekToDefaultPosition()
        else current?.let { c -> Session.api?.let { playUrl(it.liveUrl(c.id), force = true) } }
        p.playWhenReady = true
        syncPause()
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        syncPause()
    }

    private fun seek(forward: Boolean) { if (player != null) bar.seek(forward) }

    private fun syncPause() = bar.refresh()

    /** ملء الشاشة بدون انقطاع: نفس المشغّل ينتقل للشاشة الكبيرة (ما يعيد الاتصال). */
    private fun openFull(ch: Channel) {
        LivePlayer.keepForPreview = true
        player?.removeListener(listener)      // الصورة تبقى هنا لين الشاشة الكبيرة تاخذها (بدون فراغ = بدون بافرينج)
        player = null
        bar.player = null
        val i = visible.indexOfFirst { it.id == ch.id }.coerceAtLeast(0)
        PlayerActivity.playLive(a, visible.ifEmpty { listOf(ch) }, i, catAdapter.items.firstOrNull { it.id == catId }?.name ?: "")
    }

    private fun detach() {
        player?.removeListener(listener)
        player = null
        playerView.detachPlayer()
        bar.player = null
    }

    private fun showStatus(t: String) {
        loading.visibility = View.GONE
        status.text = t
        status.visibility = View.VISIBLE
    }

    private fun stopPlayer() {
        if (LivePlayer.keepForPreview) { player?.removeListener(listener); player = null; bar.player = null; return }   // انتقل لملء الشاشة: لا نفك الصورة
        detach()
        LivePlayer.release()
    }

    // ---------- الدليل ----------
    private fun showEpg(ch: Channel) {
        epgJob?.cancel()
        epgJob = a.scope.launch {
            val progs = Epg.programsFor(ch) ?: withContext(Dispatchers.IO) {
                runCatching { Session.api?.shortEpg(ch.id, 8) }.getOrNull() ?: emptyList()
            }
            if (current?.id != ch.id) return@launch
            val now = progs.firstOrNull { it.isNow }
            now?.let { Live.nowCache[ch.id] = it; chAdapter.notifyItemChanged(visible.indexOfFirst { c -> c.id == ch.id }) }
            nowText.text = now?.let { "الآن: ${it.title}   ${hhmm(it.start)} – ${hhmm(it.end)}" } ?: "لا يتوفر دليل برامج"
            epgBox.removeAllViews()
            if (!a.tv) return@launch
            val upcoming = progs.filter { it.end > System.currentTimeMillis() / 1000 }.take(14)
            if (upcoming.isEmpty()) epgBox.addView(a.text("ما فيه برامج لهذي القناة بالدليل", 13f, C.MUTED).apply { setPadding(a.dp(4), a.dp(6), 0, 0) })
            upcoming.forEach { p ->
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
                    background = rounded(if (p.isNow) C.ACCENT_SOFT else C.SURFACE, a.dpf(8f))
                    layoutParams = lp(MATCH, WRAP).apply { bottomMargin = a.dp(4) }
                }
                row.addView(a.text("${hhmm(p.start)}", 12f, if (p.isNow) C.ACCENT else C.MUTED).apply { layoutParams = lp(a.dp(52), WRAP) })
                row.addView(a.text(p.title, 13f, C.TEXT).apply { layoutParams = lp(0, WRAP, 1f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
                if (p.isNow) row.addView(a.text("● الآن", 11f, C.LIVE, bold = true))
                epgBox.addView(row)
            }
            // تحديث كل دقيقة
            while (isActive) { delay(60_000); if (current?.id == ch.id) { showEpg(ch); break } }
        }
    }

    // ---------- قائمة القناة (ضغط مطوّل) ----------
    private fun channelMenu(ch: Channel) {
        val fav = Store.isFav("live", ch.id)
        val items = arrayOf("تشغيل ملء الشاشة", if (fav) "إزالة من المفضلة" else "إضافة للمفضلة", "ربط دليل البرامج…")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(ch.name)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> openFull(ch)
                    1 -> { Store.toggleFav("live", ch.id); chAdapter.notifyDataSetChanged(); if (catId == Live.FAV) selectCategory(Live.FAV) }
                    2 -> remapDialog(a, ch) { chAdapter.notifyDataSetChanged(); if (current?.id == ch.id) showEpg(ch) }
                }
            }.show()
    }

    // ---------- الأرقام ----------
    override fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digits = (digits + (keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
            numBadge.text = digits; numBadge.visibility = View.VISIBLE
            digitJob?.cancel()
            digitJob = a.scope.launch {
                delay(1300)
                val n = digits.toIntOrNull(); digits = ""; numBadge.visibility = View.GONE
                if (n != null && n in 1..visible.size) {
                    val ch = visible[n - 1]
                    chList.scrollToPosition(n - 1)
                    preview(ch)
                } else a.toast("ما فيه قناة رقم $n")
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) { current?.let { channelMenu(it) }; return true }
        // أزرار الميديا بالريموت
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player != null) { togglePause(); return true } }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (player != null) { seek(false); return true } }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (player != null) { seek(true); return true } }
        }
        return false
    }

    override fun onShow() {
        if (LivePlayer.keepForPreview && LivePlayer.player != null) {
            // رجعنا من ملء الشاشة: نكمل نفس البث بدون إعادة اتصال (والقناة ممكن تغيّرت هناك)
            LivePlayer.keepForPreview = false
            Session.playlist.getOrNull(Session.playIndex)?.let { ch ->
                current = ch; chAdapter.playingId = ch.id; title.text = ch.name; showEpg(ch)
            }
            player?.removeListener(listener); player = null
            playerView.detachPlayer()          // (ما يمسح شي: الصورة حالياً على الشاشة الكبيرة)
            ensurePlayer()                    // نرجع الصورة للشاشة الصغيرة بنفس المشغّل
            player?.playWhenReady = true
            loading.visibility = if (player?.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            syncPause()
            LivePlayer.healAfterSwitch(a, a.scope)      // لو الصورة علّقت بعد التصغير نعيد الاتصال
        } else if (current != null && player == null) preview(current!!)
    }

    override fun onHide() {
        stopPlayer()
        epgJob?.cancel()
    }

    override fun focusDefault() {
        chList.post {
            val i = visible.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            (chList.findViewHolderForAdapterPosition(i)?.itemView ?: chList.getChildAt(0) ?: chList).requestFocus()
        }
    }
}

/** ربط قناة بدليل البرامج يدوياً: بحث + قائمة، أو رجوع للتلقائي، أو بدون دليل. */
fun remapDialog(a: MainActivity, ch: Channel, done: () -> Unit) {
    val all = Epg.channels()
    if (all.isEmpty()) { a.toast("ما فيه دليل برامج محمّل. أضف رابط من الإعدادات"); return }
    val (_, core) = Epg.keys(ch.name)
    val sorted = all.sortedBy { (_, n) -> if (Epg.keys(n).second.startsWith(core.take(5))) 0 else 1 }
    var shown = sorted
    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
    val q = EditText(a).apply { hint = "ابحث في قنوات الدليل…"; setTextColor(C.TEXT); setHintTextColor(C.FAINT); isSingleLine = true }
    val mode = Epg.mode(ch)
    col.addView(TextView(a).apply {
        typeface = Fonts.regular
        text = when (mode) { "manual" -> "مربوطة يدوياً"; "none" -> "بدون دليل"; else -> "تلقائي" }
        setTextColor(C.MUTED)
    })
    col.addView(q)
    fun label(p: Pair<String, String>): String {
        val now = Epg.programsOf(p.first).firstOrNull { it.isNow }
        return "${p.second}\nالآن: ${now?.title ?: "—"}"
    }
    val ad = ArrayAdapter(a, android.R.layout.simple_list_item_1, shown.map { label(it) }.toMutableList())
    val list = ListView(a).apply { adapter = ad }
    col.addView(list, lp(MATCH, a.dp(320)))
    val dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("ربط دليل البرامج: ${ch.name}")
        .setView(col)
        .setPositiveButton("رجوع للتلقائي") { _, _ -> Store.setEpgMap(ch.id, null); done() }
        .setNeutralButton("بدون دليل") { _, _ -> Store.setEpgMap(ch.id, ""); done() }
        .setNegativeButton("إلغاء", null)
        .create()
    list.setOnItemClickListener { _, _, pos, _ ->
        Store.setEpgMap(ch.id, shown[pos].first)
        a.toast("تم ربط ${ch.name} بـ ${shown[pos].second}")
        dlg.dismiss(); done()
    }
    q.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) {
            val k = Epg.keys(s?.toString() ?: "").first
            shown = if (k.isEmpty()) sorted else sorted.filter { Epg.keys(it.second).first.contains(k) || it.first.lowercase().contains(k) }
            ad.clear(); ad.addAll(shown.map { label(it) }); ad.notifyDataSetChanged()
        }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    })
    dlg.show()
}
