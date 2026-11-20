package com.quickiptv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * المشغّل بملء الشاشة.
 * المباشر: زر "مباشر" يرجّعك للحظة الحالية، فوق/تحت (أو CH+/CH-) يبدّل القناة، والأرقام تقفز لرقم القناة.
 * الأفلام والحلقات: يكمل من حيث وقفت، ويحفظ مكانك كل 10 ثواني.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val scope = MainScope()
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var title: android.widget.TextView
    private lateinit var sub: android.widget.TextView
    private lateinit var status: android.widget.TextView
    private lateinit var loading: ProgressBar
    private lateinit var numBadge: android.widget.TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bar: PlayerBar                 // شريط الأزرار الموحّد (تحت)
    private var live = false
    private var returnsToPreview = false      // انفتح من الشاشة الصغيرة وبيرجع لها (لا نطفي المشغّل)
    private var handedOff = false             // المشغّل جاي من المصغّر وبيرجع له لما نرجع
    private var key = ""
    private var type = ""
    private var triedTs = false
    private var digits = ""
    private var digitJob: Job? = null
    private var curUrl = ""                    // رابط الفيلم/الحلقة الحالي
    private var subBase = ""                   // سطر الموسم/الحلقة (بدون "من التحميلات")

    // قائمة القنوات الجانبية (المشغّل التفاعلي للمباشر)
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: android.widget.TextView
    private lateinit var panelList: androidx.recyclerview.widget.RecyclerView
    private val panelAdapter by lazy {
        ChannelAdapter(this, { _, i -> playChannel(i); closePanel() }, { _, _ -> })
    }
    private val panelOpen get() = ::panel.isInitialized && panel.visibility == View.VISIBLE

    companion object {
        fun playLive(ctx: Context, list: List<Channel>, index: Int, category: String) {
            Session.playlist = list
            Session.playIndex = index
            Session.playCategory = category
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("live", true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installFocusMotion()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        immersive()
        if (Session.api == null) Store.current?.let { Session.open(this, it) }
        live = intent.getBooleanExtra("live", false)
        returnsToPreview = live && LivePlayer.keepForPreview

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        pv = PlayerView(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF000000.toInt())
            useController = false                                  // أزرارنا الموحّدة (PlayerBar) بدل أزرار المكتبة
        }
        root.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

        // شريط الأزرار تحت: يمين/يسار مقسّم (نفس الشكل بالجوال والتلفزيون)
        bar = PlayerBar(this, live = live, autoHide = true).apply {
            fullscreenMode = true
            if (!isTv()) onFullscreen = {
                // زر التصغير: الأفلام ترجع للمشغّل الصغير عمودي، والمباشر للشاشة الصغيرة (بدون انقطاع)
                if (!live) VodHandoff.toPortrait = true
                finish()
            }
            if (live) {
                onLive = { goLive() }
                onChannels = { openPanel() }
                onTap = { if (panelOpen) { closePanel(); true } else false }
            }
            // العنوان داخل شريط الأزرار (الجوال: جنب سهم التصغير، التلفزيون: كبير فوق الشريط)
            titleProvider = { title.text.toString() to sub.text.toString() }
            // التلفزيون: سهم تحت = صف حلقات الموسم / قنوات التصنيف
            rowProvider = { rowItems() }
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH, MATCH))

        // فوق: الاسم والبرنامج/الحلقة بس (بدون أزرار)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(0x99000000.toInt())
        }
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = text("", 17f, 0xFFFFFFFF.toInt(), bold = true).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        sub = text("", 13f, 0xFFC9CDD3.toInt(), lines = 2)
        tcol.addView(title); tcol.addView(sub)
        topBar.addView(tcol, lp(0, WRAP, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        topBar.visibility = View.GONE              // العنوان صار داخل شريط الأزرار

        if (live) {
            // لوحة القنوات: تطلع من الجنب فوق البث (البث يكمل وراها)
            panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xF2111317.toInt())
                visibility = View.GONE
                isClickable = true          // اللمس داخل اللوحة ما يوصل للبث
            }
            val ph = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(6), dp(8)) }
            panelTitle = text("", 15f, C.TEXT, bold = true)
            ph.addView(panelTitle, lp(0, WRAP, 1f))
            ph.addView(iconButton(R.drawable.ic_close, "إغلاق") { closePanel() })
            panel.addView(ph)
            panelList = androidx.recyclerview.widget.RecyclerView(this).apply {
                layoutManager = SmoothLM(this@PlayerActivity)
                adapter = panelAdapter
                setHasFixedSize(true)
                itemAnimator = null
                setItemViewCacheSize(12)
                setPadding(dp(6), 0, dp(6), dp(10)); clipToPadding = false
            }
            panel.addView(panelList, lp(MATCH, 0, 1f))
            val pw = minOf(dp(if (isTv()) 420 else 360), resources.displayMetrics.widthPixels * 55 / 100)
            root.addView(panel, FrameLayout.LayoutParams(pw, MATCH, Gravity.START))
        }

        loading = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(C.ACCENT) }
        root.addView(loading, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        status = text("", 15f, 0xFFFFFFFF.toInt(), lines = 4).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(20), dp(14), dp(20), dp(14)); background = rounded(0xCC111317.toInt(), dpf(12f))
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        numBadge = text("", 34f, C.PRIMARY_TEXT, bold = true).apply {
            background = rounded(C.ACCENT, dpf(12f)); setPadding(dp(20), dp(6), dp(20), dp(6)); visibility = View.GONE
        }
        root.addView(numBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(80) })
        setContentView(root)

        // المباشر: نفس مشغّل الشاشة الصغيرة (ما ينقطع ولا يعيد التحميل). الأفلام: مشغّل خاص.
        // فيلم/حلقة جاية من المشغّل المصغّر: ناخذ نفس المشغّل (يكمل بدون توقف)
        handedOff = !live && intent.getBooleanExtra("handoff", false) && VodHandoff.player != null && VodHandoff.key == (intent.getStringExtra("key") ?: "")
        // جاي من المشغّل الصغير بحركتنا (يكبر ويلف): لا نخلي أندرويد يضيف لفّة/حركة فوقها
        if (handedOff) window.attributes = window.attributes.also { it.rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT }
        player = (if (live) LivePlayer.get(this) else if (handedOff) VodHandoff.take()!! else buildPlayer(this, false)).also { p ->
            pv.attachSmooth(p)        // الصورة تنتقل أول ما تجهز الشاشة الكبيرة (بدون ومضة سودا)
            p.addListener(listener)
            bar.player = p
        }
        if (live) playChannel(Session.playIndex, reuse = true) else playVod()
        scope.launch {           // حفظ المكان كل 10 ثواني
            while (isActive) {
                delay(1000)
                if (!live && (player?.currentPosition ?: 0) / 1000 % 10 == 0L) saveProgress()
            }
        }
    }

    private val listener: Player.Listener by lazy {
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        status.visibility = View.GONE
                        if (live && triedTs) Session.preferTs = true     // TS اشتغلت بعد فشل HLS
                    }
                    if (state == Player.STATE_ENDED && !live) {
                        saveProgress()
                        // مسلسل: الحلقة التالية تشتغل لحالها
                        val i = SeriesQueue.indexOfKey(key)
                        if (i >= 0 && i < SeriesQueue.list.size - 1) pv.post { switchEpisode(i + 1) } else finish()
                    }
                    syncPause()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) { syncPause() }

                override fun onPlayerError(error: PlaybackException) {
                    if (live && !triedTs && Store.liveFormat == "m3u8" && !Session.preferTs) {
                        triedTs = true
                        Session.playlist.getOrNull(Session.playIndex)?.let { c -> Session.api?.let { LivePlayer.play(this@PlayerActivity, it.liveUrl(c.id, "ts"), force = true) } }
                        return
                    }
                    loading.visibility = View.GONE
                    status.text = "تعذر التشغيل (${error.errorCodeName}).\n${if (live) "جرّب قناة ثانية بالأسهم فوق/تحت" else "تأكد من الاتصال وجرّب مرة ثانية"}"
                    status.visibility = View.VISIBLE
                }
            }
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun start(url: String, startSec: Long) {
        val p = player ?: return
        status.visibility = View.GONE
        loading.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(url), startSec * 1000)
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- المباشر ----------
    private var zapJob: Job? = null

    /** zap = تقليب سريع بالريموت (فوق/تحت): الاسم يتغيّر فوراً والبث يبدأ لما توقف (بدون اتصالات ضايعة). */
    private fun playChannel(i: Int, reuse: Boolean = false, zap: Boolean = false) {
        val list = Session.playlist
        if (list.isEmpty()) { finish(); return }
        val idx = ((i % list.size) + list.size) % list.size
        Session.playIndex = idx
        val ch = list[idx]
        if (::panel.isInitialized) panelAdapter.playingId = ch.id
        triedTs = false
        title.text = "${idx + 1}. ${ch.name}"
        sub.text = Session.playCategory
        // نفس القناة الشغّالة في الشاشة الصغيرة؟ نكمل عليها مباشرة بدون إعادة اتصال
        loading.visibility = View.VISIBLE
        val shared = LivePlayer.player
        val api = Session.api ?: run { finish(); return }
        zapJob?.cancel()
        if (reuse && shared != null && shared.playbackState != Player.STATE_IDLE) shared.playWhenReady = true   // نفس البث يكمل (حتى لو صيغته TS)
        else if (zap) {
            shared?.stop()                         // نقفل اتصال القناة السابقة فوراً (السيرفرات تسمح باتصال واحد غالباً)
            zapJob = scope.launch { delay(300); LivePlayer.play(this@PlayerActivity, api.liveUrl(ch.id), force = true) }
        } else LivePlayer.play(this, api.liveUrl(ch.id), force = !reuse)
        if (reuse) LivePlayer.healAfterSwitch(this, scope)      // لو الصورة علّقت بعد التكبير نعيد الاتصال
        if (player?.playbackState == Player.STATE_READY) loading.visibility = View.GONE
        Store.addHistory(HistoryItem("live:${ch.id}", "live", ch.name, Session.playCategory, ch.icon, ""))
        showBar()
        scope.launch {
            val progs = Epg.programsFor(ch) ?: withContext(Dispatchers.IO) {
                runCatching { Session.api?.shortEpg(ch.id, 3) }.getOrNull() ?: emptyList()
            }
            val now = progs.firstOrNull { it.isNow } ?: return@launch
            Live.nowCache[ch.id] = now
            if (Session.playIndex == idx) {
                val next = progs.firstOrNull { it.start >= now.end }
                sub.text = "الآن: ${now.title}  ${hhmm(now.start)}–${hhmm(now.end)}" + (next?.let { "\nبعده: ${it.title}  ${hhmm(it.start)}" } ?: "")
            }
        }
    }

    /** يفتح قائمة القنوات فوق البث (نفس التصنيف اللي جيت منه) ويحدد القناة الحالية. */
    private fun openPanel() {
        if (!live || !::panel.isInitialized) return
        panelTitle.text = Session.playCategory.ifEmpty { "القنوات" } + "  ·  ${Session.playlist.size}"
        if (panelAdapter.items !== Session.playlist) panelAdapter.items = Session.playlist
        val cur = Session.playlist.getOrNull(Session.playIndex)
        panelAdapter.playingId = cur?.id ?: ""
        panel.visibility = View.VISIBLE
        panel.animate().cancel()
        panel.translationX = panel.layoutParams.width.toFloat()          // تطلع من الجنب
        panel.animate().translationX(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).withLayer().start()
        bar.hide()
        val i = Session.playIndex
        (panelList.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(i, dp(120))
        panelList.post { (panelList.findViewHolderForAdapterPosition(i)?.itemView ?: panelList).requestFocus() }
    }

    private fun closePanel() {
        if (!panelOpen) return
        panel.animate().cancel()
        panel.animate().translationX(panel.layoutParams.width.toFloat()).setDuration(160).withLayer()
            .withEndAction { panel.visibility = View.GONE; panel.translationX = 0f }.start()
        showBar()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // رجوع: يقفل قائمة القنوات أول، وبعدها يرجّعك لصفحة المباشر
        if (panelOpen) { closePanel(); return }
        if (!live && bar.cancelAutoNext()) return        // وقت عدّاد الحلقة التالية: رجوع = أكمل التتر
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** زر "مباشر": لو البث متوقف أو راجع لورا يرجّعك للحظة الحالية. */
    private fun goLive() {
        val p = player ?: return
        if (p.isCurrentMediaItemLive && p.isCurrentMediaItemSeekable) p.seekToDefaultPosition()
        else playChannel(Session.playIndex)             // بث بدون إمكانية رجوع (TS): نعيد الاتصال على الحالي
        p.playWhenReady = true
        syncPause()
        showBar()
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        syncPause()
    }

    private fun syncPause() { if (::bar.isInitialized) bar.refresh() }

    private fun showBar() { bar.show() }

    // ---------- الأفلام والحلقات ----------
    private fun playVod() {
        key = intent.getStringExtra("key") ?: ""
        type = intent.getStringExtra("type") ?: "movie"
        title.text = intent.getStringExtra("title") ?: ""
        subBase = intent.getStringExtra("sub") ?: ""
        sub.text = subBase
        val url = intent.getStringExtra("url") ?: return finish()
        curUrl = url
        bar.nav = SeriesQueue.nav(key) { i -> switchEpisode(i) }
        Store.addHistory(HistoryItem(key, type, title.text.toString(), sub.text.toString(),
            intent.getStringExtra("image") ?: "", url, intent.getStringExtra("seriesId") ?: ""))
        val local = Downloads.localFile(key)
        val startAt = intent.getLongExtra("start", 0)
        if (handedOff) {
            // نفس المشغّل شغّال من المصغّر: ما نعيد شي
            if (VodHandoff.watching) { watchingVod = true; VodHandoff.watching = false }
            if (local != null) sub.text = listOf(sub.text.toString(), "من التحميلات").filter { it.isNotEmpty() }.joinToString(" · ")
            loading.visibility = if (player?.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            player?.playWhenReady = true
        } else if (local != null) {
            sub.text = listOf(sub.text.toString(), "من التحميلات").filter { it.isNotEmpty() }.joinToString(" · ")
            start(android.net.Uri.fromFile(local).toString(), startAt)
        } else {
            // المشاهدة تاخذ اتصال من حد الاشتراك؛ لو فيه تحميل ماسكه ننتظر لحظة لين يتنازل
            Downloads.setWatching("vod", true); watchingVod = true
            if (Downloads.mustYieldFor()) { loading.visibility = View.VISIBLE; scope.launch { delay(1200); start(url, startAt) } }
            else start(url, startAt)
        }
    }

    /** الحلقة السابقة/التالية بنفس المشغّل (بدون ما نطلع من ملء الشاشة). */
    private fun switchEpisode(i: Int) {
        val r = SeriesQueue.req(i) ?: return
        val p = player ?: return
        saveProgress()
        SeriesQueue.index = i
        key = r.key; type = r.type; curUrl = r.url; subBase = r.sub
        title.text = r.title; sub.text = r.sub
        Store.addHistory(HistoryItem(r.key, r.type, r.title, r.sub, intent.getStringExtra("image") ?: "", r.url, intent.getStringExtra("seriesId") ?: ""))
        val local = Downloads.localFile(r.key)
        if (local != null) sub.text = listOf(r.sub, "من التحميلات").joinToString(" · ")
        else if (!watchingVod) { Downloads.setWatching("vod", true); watchingVod = true }
        status.visibility = View.GONE
        loading.visibility = View.VISIBLE
        p.setMediaItem(MediaItem.fromUri(local?.let { android.net.Uri.fromFile(it).toString() } ?: r.url), Store.resumePoint(r.key) * 1000)
        p.prepare()
        p.playWhenReady = true
        bar.nav = SeriesQueue.nav(key) { j -> switchEpisode(j) }
        bar.show()
    }

    private var watchingVod = false

    private fun saveProgress() {
        val p = player ?: return
        if (live || key.isEmpty()) return
        val d = p.duration
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return
        Store.saveProgress(key, p.currentPosition / 1000, d / 1000)
    }

    // ---------- الريموت ----------
    /** زر تعاملنا مع ضغطته: نبلع رفعته بعد (وإلا الرفعة توصل للعنصر المحدد وتعتبر ضغطة عليه). */
    private var swallowUp = -1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) { swallowUp = -1; return true }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val handled = handleKey(event)
        if (handled) swallowUp = event.keyCode
        return handled || super.dispatchKeyEvent(event)
    }

    /** صف تحت بالتلفزيون: حلقات نفس الموسم (مسلسل) أو قنوات حول القناة الحالية (مباشر). */
    private fun rowItems(): Pair<String, List<PlayerBar.RowItem>>? {
        if (live) {
            val list = Session.playlist
            if (list.isEmpty()) return null
            val cur = Session.playIndex
            val from = (cur - 25).coerceAtLeast(0); val to = (cur + 25).coerceAtMost(list.size - 1)
            return Session.playCategory.ifEmpty { "القنوات" } to (from..to).map { i ->
                val ch = list[i]
                PlayerBar.RowItem("${i + 1}. ${ch.name}", ch.icon, i == cur) { playChannel(i) }
            }
        }
        val i = SeriesQueue.indexOfKey(key)
        if (i < 0) return null
        val season = SeriesQueue.list[i].season
        val img = intent.getStringExtra("image") ?: ""
        return "حلقات الموسم $season" to SeriesQueue.list.withIndex().filter { it.value.season == season }.map { (j, e) ->
            PlayerBar.RowItem("الحلقة ${e.num}" + (e.title.takeIf { it.isNotBlank() && !it.contains("S0") }?.let { " · $it" } ?: ""),
                e.image.ifEmpty { img }, j == i) { switchEpisode(j) }
        }
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val k = event.keyCode
        if (live && panelOpen) {
            // القائمة مفتوحة: الأسهم والاختيار للقائمة نفسها (الرجوع يقفلها)
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) { closePanel(); return true }
            return false
        }
        // الأزرار ظاهرة والتحديد داخلها: تحت = صف الحلقات/القنوات، فوق من الصف = رجوع للأزرار
        if (isTv() && bar.barVisible && bar.hasFocusInside()) {
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && bar.seekFocused()) { bar.poke(); return false }      // من الشريط ← للأزرار
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (!bar.rowHasFocus()) bar.showRow(); bar.poke(); return true }
            if (k == KeyEvent.KEYCODE_DPAD_UP && bar.rowHasFocus()) { bar.hideRow(); bar.focusPlay(); return true }
            if (k == KeyEvent.KEYCODE_DPAD_UP && live) { bar.poke(); return true }
        }
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (bar.hasFocusInside()) { bar.poke(); return false }      // زر بالشريط (أو "الحلقة التالية"): ضغطته العادية
                if (live) openPanel() else bar.focusPlay()                   // المباشر: OK = قائمة القنوات (زي الرسيفر)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (bar.barVisible && bar.hasFocusInside()) { bar.poke(); return false }   // تنقّل بين الأزرار
                if (live) { bar.focusPlay(); return true }
                // الأفلام والأزرار مخفية: يمين = تقديم 10 ثواني، يسار = تأخير 10 ثواني
                bar.seek(k == KeyEvent.KEYCODE_DPAD_RIGHT)
                bar.show()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                togglePause(); showBar(); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { bar.seek(false); showBar(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { bar.seek(true); showBar(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { showBar(); return true }
        }
        if (live) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel(Session.playIndex - 1, zap = true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel(Session.playIndex + 1, zap = true); return true }
            }
            if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digit(k - KeyEvent.KEYCODE_0); return true }
        } else if ((k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) && !bar.barVisible) {
            bar.focusPlay(); return true
        }
        return false
    }

    private fun digit(d: Int) {
        digits = (digits + d).takeLast(4)
        numBadge.text = digits; numBadge.visibility = View.VISIBLE
        digitJob?.cancel()
        digitJob = scope.launch {
            delay(1300)
            val n = digits.toIntOrNull(); digits = ""; numBadge.visibility = View.GONE
            if (n != null && n in 1..Session.playlist.size) playChannel(n - 1)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun finish() {
        super.finish()
        if (handedOff) overridePendingTransition(0, 0)     // الرجوع للمصغّر بحركتنا (يلف ويصغر) بدل حركة أندرويد
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        // رجوع للمشغّل المصغّر: نسلّمه نفس المشغّل وهو شغّال (قبل ما تكمل الصفحة اللي تحت)
        if (handedOff && isFinishing && VodHandoff.ownerAlive && player?.playbackState != Player.STATE_ENDED) {
            val p = player!!
            p.removeListener(listener)
            VodHandoff.player = p; VodHandoff.key = key
            VodHandoff.req = VodReq(curUrl, key, title.text.toString(), subBase, type)
            VodHandoff.watching = watchingVod; watchingVod = false
            player = null
            return
        }
        // الأفلام: وقف مؤقت. المباشر لو راجع للشاشة الصغيرة يكمل بدون انقطاع.
        if (!(live && returnsToPreview && isFinishing)) player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
        syncPause()
    }

    override fun onDestroy() {
        saveProgress()
        if (watchingVod) Downloads.setWatching("vod", false)
        scope.cancel()
        player?.removeListener(listener)
        pv.detachPlayer()
        if (live) { if (!returnsToPreview) LivePlayer.release() } else player?.release()
        player = null
        super.onDestroy()
    }
}

/** خلفية زر بلون معيّن مع إطار أبيض لما يتركز عليه بالريموت. */
object StateListFocus {
    fun make(ctx: Context, color: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.StateListDrawable().apply {
            val r = ctx.dpf(9f)
            addState(intArrayOf(android.R.attr.state_focused), rounded(color, r, 0xFFFFFFFF.toInt(), ctx.dp(2)))
            addState(intArrayOf(), rounded(color, r))
        }
}
