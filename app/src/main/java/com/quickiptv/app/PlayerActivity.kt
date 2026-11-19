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
    private lateinit var liveBtn: android.widget.TextView
    private lateinit var status: android.widget.TextView
    private lateinit var loading: ProgressBar
    private lateinit var numBadge: android.widget.TextView
    private lateinit var topBar: LinearLayout
    private var live = false
    private var key = ""
    private var type = ""
    private var triedTs = false
    private var digits = ""
    private var digitJob: Job? = null
    private var hideJob: Job? = null

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        immersive()
        if (Session.api == null) Store.current?.let { Session.open(this, it) }
        live = intent.getBooleanExtra("live", false)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        pv = PlayerView(this).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(0xFF000000.toInt())
            controllerShowTimeoutMs = 4000
            setShowNextButton(false); setShowPreviousButton(false)
            layoutDirection = View.LAYOUT_DIRECTION_LTR          // شريط التقدّم من اليسار لليمين
            useController = !live                                  // المباشر: شريطنا الخاص
        }
        root.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

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
        liveBtn = text("●  مباشر", 13f, 0xFFFFFFFF.toInt(), bold = true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true; isClickable = true
            setOnClickListener { goLive() }
            visibility = if (live) View.VISIBLE else View.GONE
        }
        topBar.addView(liveBtn)
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

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
        root.setOnClickListener { showBar() }
        setContentView(root)

        player = buildPlayer(this, live).also { p ->
            pv.player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) status.visibility = View.GONE
                    if (state == Player.STATE_ENDED && !live) { saveProgress(); finish() }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) = updateLiveBtn()

                override fun onPlayerError(error: PlaybackException) {
                    if (live && !triedTs && Store.liveFormat == "m3u8") {
                        triedTs = true
                        Session.playlist.getOrNull(Session.playIndex)?.let { start(Session.api!!.liveUrl(it.id, "ts"), 0) }
                        return
                    }
                    loading.visibility = View.GONE
                    status.text = "تعذر التشغيل (${error.errorCodeName}).\n${if (live) "جرّب قناة ثانية بالأسهم فوق/تحت" else "تأكد من الاتصال وجرّب مرة ثانية"}"
                    status.visibility = View.VISIBLE
                }
            })
        }
        if (live) playChannel(Session.playIndex) else playVod()
        scope.launch {           // حفظ المكان + حالة زر المباشر
            while (isActive) {
                delay(1000)
                updateLiveBtn()
                if (!live && (player?.currentPosition ?: 0) / 1000 % 10 == 0L) saveProgress()
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
    private fun playChannel(i: Int) {
        val list = Session.playlist
        if (list.isEmpty()) { finish(); return }
        val idx = ((i % list.size) + list.size) % list.size
        Session.playIndex = idx
        val ch = list[idx]
        triedTs = false
        title.text = "${idx + 1}. ${ch.name}"
        sub.text = Session.playCategory
        start(Session.api!!.liveUrl(ch.id), 0)
        Store.addHistory(HistoryItem("live:${ch.id}", "live", ch.name, Session.playCategory, ch.icon, ""))
        showBar()
        scope.launch {
            val progs = Epg.programsFor(ch) ?: withContext(Dispatchers.IO) {
                runCatching { Session.api!!.shortEpg(ch.id, 3) }.getOrDefault(emptyList())
            }
            val now = progs.firstOrNull { it.isNow } ?: return@launch
            Live.nowCache[ch.id] = now
            if (Session.playIndex == idx) {
                val next = progs.firstOrNull { it.start >= now.end }
                sub.text = "الآن: ${now.title}  ${hhmm(now.start)}–${hhmm(now.end)}" + (next?.let { "\nبعده: ${it.title}  ${hhmm(it.start)}" } ?: "")
            }
        }
    }

    /** زر "مباشر": لو البث متوقف أو راجع لورا يرجّعك للحظة الحالية. */
    private fun goLive() {
        val p = player ?: return
        if (p.isCurrentMediaItemLive && p.isCurrentMediaItemSeekable) p.seekToDefaultPosition()
        else playChannel(Session.playIndex)             // بث بدون إمكانية رجوع (TS): نعيد الاتصال على الحالي
        p.playWhenReady = true
        showBar()
    }

    private fun behindLive(): Boolean {
        val p = player ?: return false
        if (!p.isPlaying) return true
        if (!p.isCurrentMediaItemLive) return false
        val off = p.currentLiveOffset
        return off != androidx.media3.common.C.TIME_UNSET && off > 20_000
    }

    private fun updateLiveBtn() {
        if (!live) return
        val behind = behindLive()
        liveBtn.text = if (behind) "↻  العودة للمباشر" else "●  مباشر"
        liveBtn.background = StateListFocus.make(this, if (behind) 0xFF3A404A.toInt() else C.LIVE)
    }

    private fun showBar() {
        topBar.visibility = View.VISIBLE
        hideJob?.cancel()
        hideJob = scope.launch { delay(5000); if (live) topBar.visibility = View.GONE }
    }

    // ---------- الأفلام والحلقات ----------
    private fun playVod() {
        key = intent.getStringExtra("key") ?: ""
        type = intent.getStringExtra("type") ?: "movie"
        title.text = intent.getStringExtra("title") ?: ""
        sub.text = intent.getStringExtra("sub") ?: ""
        val url = intent.getStringExtra("url") ?: return finish()
        Store.addHistory(HistoryItem(key, type, title.text.toString(), sub.text.toString(),
            intent.getStringExtra("image") ?: "", url, intent.getStringExtra("seriesId") ?: ""))
        start(url, intent.getLongExtra("start", 0))
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v })
    }

    private fun saveProgress() {
        val p = player ?: return
        if (live || key.isEmpty()) return
        val d = p.duration
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return
        Store.saveProgress(key, p.currentPosition / 1000, d / 1000)
    }

    // ---------- الريموت ----------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val k = event.keyCode
        if (live) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { playChannel(Session.playIndex - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { playChannel(Session.playIndex + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (topBar.visibility == View.VISIBLE && liveBtn.isFocused) goLive() else { showBar(); liveBtn.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    player?.let { it.playWhenReady = !it.playWhenReady }; showBar(); return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> { showBar(); return true }
            }
            if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digit(k - KeyEvent.KEYCODE_0); return true }
        }
        return super.dispatchKeyEvent(event)
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

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        saveProgress()
        scope.cancel()
        player?.release()
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
