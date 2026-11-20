package com.quickiptv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C as MC
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi

/** التنقّل بين الحلقات (المسلسلات). */
interface EpisodeNav {
    val hasPrev: Boolean
    val hasNext: Boolean
    fun prev()
    fun next()
}

/**
 * شريط تحكّم موحّد لكل المشغّلات (الأفلام/المسلسلات والبث المباشر، الصغير وملء الشاشة، الجوال والتلفزيون).
 *
 * الترتيب (من اليسار): تأخير 10 · تشغيل/إيقاف · تقديم 10 … [شريط التقدّم] … ثم الأزرار الثانية:
 *  - أفلام/مسلسلات: الحلقة السابقة · الحلقة التالية · الإعدادات · تكبير/تصغير
 *  - مباشر: مباشر · القنوات · تكبير/تصغير
 * المشغّل الصغير (أضيق من 600dp): شريط التقدّم فوق والأزرار تحته. الكبير: كل شي بسطر واحد.
 * كل الأزرار بنفس المقاس (40dp) والأيقونات (22dp) في وسطها، والكتابة سطر واحد داخل حدود الزر.
 */
@OptIn(UnstableApi::class)
class PlayerBar(private val act: Activity, private val live: Boolean, private val autoHide: Boolean) : FrameLayout(act) {

    var player: Player? = null
        set(v) { field = v; refresh() }

    /** زر التكبير/التصغير (null = مخفي). */
    var onFullscreen: (() -> Unit)? = null
        set(v) { field = v; btnFull.visibility = if (v != null) View.VISIBLE else View.GONE; fit() }

    /** true = الأيقونة "تصغير" (ملء الشاشة). */
    var fullscreenMode = false
        set(v) { field = v; fullIcon.setImageResource(if (v) R.drawable.ic_minimize else R.drawable.ic_fullscreen); btnFull.contentDescription = if (v) "تصغير" else "تكبير" }

    /** زر القنوات (المباشر بملء الشاشة). */
    var onChannels: (() -> Unit)? = null
        set(v) { field = v; btnChannels.visibility = if (v != null) View.VISIBLE else View.GONE; fit() }

    /** زر "مباشر": الرجوع للحظة الحالية. */
    var onLive: (() -> Unit)? = null

    /** ضغطة على الشاشة: لو رجّعت true ما نبدّل ظهور الأزرار (مثلاً تقفل قائمة القنوات). */
    var onTap: (() -> Boolean)? = null

    /** يتنادى لما الشريط يظهر/يختفي (عشان العنوان فوق يمشي معه). */
    var onVisibility: ((Boolean) -> Unit)? = null

    /** الحلقات (null = فيلم/مباشر: أزرار السابقة والتالية مخفية). */
    var nav: EpisodeNav? = null
        set(v) { field = v; fit(); refresh() }

    private val btnSize = act.dp(40)
    private val gap = act.dp(6)

    private fun roundBtn(res: Int, desc: String, onClick: () -> Unit): Pair<FrameLayout, android.widget.ImageView> {
        val ic = act.icon(res, 0xFFFFFFFF.toInt(), 22).apply {
            layoutParams = LayoutParams(act.dp(22), act.dp(22), Gravity.CENTER)
        }
        val b = FrameLayout(act).apply {
            background = act.focusBg(normal = 0x33FFFFFF, radius = 20)
            isFocusable = true; isClickable = true
            contentDescription = desc
            addView(ic)
            setOnClickListener { onClick(); poke() }
        }
        return b to ic
    }

    private fun pill(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        setPadding(act.dp(12), 0, act.dp(12), 0)
        minimumWidth = btnSize
    }

    private val btnBack = act.seekButton(false, 40, 0x33FFFFFF) { seek(false) }
    private val playPair = roundBtn(R.drawable.ic_pause, "إيقاف مؤقت") { togglePlay() }
    private val btnPlay = playPair.first
    private val playIcon = playPair.second
    private val btnFwd = act.seekButton(true, 40, 0x33FFFFFF) { seek(true) }
    private val btnPrev = roundBtn(R.drawable.ic_skip_prev, "الحلقة السابقة") { nav?.let { if (it.hasPrev) it.prev() } }.first
    private val btnNext = roundBtn(R.drawable.ic_skip_next, "الحلقة التالية") { nav?.let { if (it.hasNext) it.next() } }.first
    private val btnSettings = roundBtn(R.drawable.ic_settings, "الإعدادات") { settings() }.first
    private val fullPair = roundBtn(R.drawable.ic_fullscreen, "تكبير") { onFullscreen?.invoke() }
    private val btnFull = fullPair.first.apply { visibility = View.GONE }
    private val fullIcon = fullPair.second

    private val liveText = act.text("●  مباشر", 12.5f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; includeFontPadding = false }
    private val btnLive = pill().apply {
        contentDescription = "مباشر"
        addView(liveText)
        setOnClickListener { onLive?.invoke(); poke() }
    }
    private val btnChannels = pill().apply {
        contentDescription = "القنوات"
        background = act.focusBg(normal = 0x33FFFFFF, radius = 20)
        addView(act.icon(R.drawable.ic_list, 0xFFFFFFFF.toInt(), 20), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        addView(act.text("القنوات", 12.5f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; includeFontPadding = false; setPadding(act.dp(6), 0, 0, 0) })
        setOnClickListener { onChannels?.invoke() }
        visibility = View.GONE
    }

    // شريط التقدّم + الوقت
    private val tPos = act.text("0:00", 11.5f, 0xFFE6E8EB.toInt()).apply { maxLines = 1 }
    private val tDur = act.text("0:00", 11.5f, 0xFFE6E8EB.toInt()).apply { maxLines = 1 }
    private var dragging = false
    private val seekBar = SeekBar(act).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(C.ACCENT)
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
        secondaryProgressTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
        setPadding(act.dp(10), 0, act.dp(10), 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val d = duration()
                if (d > 0) tPos.text = fmtTime(d * p / 1000 / 1000)
                poke()
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true; poke() }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val d = duration()
                if (d > 0) player?.seekTo(d * sb.progress / 1000)
                poke()
            }
        })
    }
    private val progRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tPos, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(seekBar, LinearLayout.LayoutParams(0, act.dp(28), 1f))
        addView(tDur, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private val leftGroup = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val rightGroup = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val spacer = View(act)
    private val btnRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val panel = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xB3000000.toInt())
        setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(8))
        isClickable = true            // اللمس على الشريط نفسه ما يخفيه
    }

    // زر "الحلقة التالية" المنفصل (يطلع قرب نهاية الحلقة)
    private val nextCount = act.text("", 11f, C.PRIMARY_TEXT, bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(act.dp(2), C.PRIMARY_TEXT) }
    }
    private val nextFloat = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isFocusable = true; isClickable = true
        contentDescription = "الحلقة التالية"
        setPadding(act.dp(12), 0, act.dp(12), 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFFFFFFFF.toInt(), act.dpf(12f), C.ACCENT, act.dp(3)))
            addState(intArrayOf(), rounded(0xFFF2F3F5.toInt(), act.dpf(12f)))
        }
        addView(nextCount, LinearLayout.LayoutParams(act.dp(26), act.dp(26)))
        addView(act.text("الحلقة التالية", 14f, C.PRIMARY_TEXT, bold = true).apply { maxLines = 1; includeFontPadding = false; setPadding(act.dp(8), 0, act.dp(8), 0) })
        addView(act.icon(R.drawable.ic_skip_next, C.PRIMARY_TEXT, 20), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        setOnClickListener { nav?.let { if (it.hasNext) it.next() } }
        visibility = View.GONE
    }
    private var nextShownOnce = false

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR           // أزرار الميديا وشريط التقدّم من اليسار لليمين
        fun lpBtn(v: View, w: Int = btnSize) = LinearLayout.LayoutParams(w, btnSize).apply { marginEnd = gap }
        leftGroup.addView(btnBack, lpBtn(btnBack))
        leftGroup.addView(btnPlay, lpBtn(btnPlay))
        leftGroup.addView(btnFwd, lpBtn(btnFwd).apply { marginEnd = 0 })
        if (live) {
            rightGroup.addView(btnLive, LinearLayout.LayoutParams(WRAP, btnSize).apply { marginStart = gap })
            rightGroup.addView(btnChannels, LinearLayout.LayoutParams(WRAP, btnSize).apply { marginStart = gap })
        } else {
            rightGroup.addView(btnPrev, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = gap })
            rightGroup.addView(btnNext, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = gap })
            rightGroup.addView(btnSettings, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = gap })
        }
        rightGroup.addView(btnFull, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = gap })
        panel.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(panel, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        addView(nextFloat, LayoutParams(WRAP, act.dp(44), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, act.dp(12), act.dp(16)) })
        arrange(false)
        seekBar.isFocusable = false            // الريموت: يمين/يسار = ±10 ثواني (والشريط باللمس)
    }

    // ---------- الترتيب حسب حجم المشغّل ----------
    private var compact: Boolean? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = w < act.dp(600)
        if (c != compact) post { arrange(c) } else post { fit() }
    }

    private fun arrange(c: Boolean) {
        compact = c
        for (v in listOf<View>(leftGroup, rightGroup, spacer, progRow)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        btnRow.removeAllViews()
        btnRow.addView(leftGroup, LinearLayout.LayoutParams(WRAP, WRAP))
        if (live) {
            btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        } else if (c) {
            // صغير: شريط التقدّم فوق بعرض المشغّل، والأزرار تحته
            panel.addView(progRow, 0, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dp(4) })
            btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        } else {
            // كبير: كل شي بسطر واحد
            btnRow.addView(progRow, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(act.dp(12), 0, act.dp(6), 0) })
        }
        btnRow.addView(rightGroup, LinearLayout.LayoutParams(WRAP, WRAP))
        fit()
    }

    /** لو المساحة ما تكفي: نخفي الأقل أهمية أول (ولا شي يطلع برا حدوده). */
    private fun fit() {
        val w = width - panel.paddingLeft - panel.paddingRight
        if (w <= 0) return
        val hasNav = nav != null
        btnPrev.visibility = if (hasNav) View.VISIBLE else View.GONE
        btnNext.visibility = btnPrev.visibility
        btnSettings.visibility = if (live) View.GONE else View.VISIBLE
        btnBack.visibility = View.VISIBLE; btnFwd.visibility = View.VISIBLE
        val minProg = if (!live && compact == false) act.dp(160) else 0
        fun need(): Int {
            var n = minProg
            for (g in listOf(leftGroup, rightGroup)) for (i in 0 until g.childCount) {
                val v = g.getChildAt(i)
                if (v.visibility != View.VISIBLE) continue
                v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(btnSize, MeasureSpec.EXACTLY))
                n += maxOf(v.measuredWidth, btnSize) + gap
            }
            return n
        }
        // ترتيب الإخفاء: الإعدادات ← السابقة/التالية ← ±10 (التشغيل والتكبير ما ينخفون أبداً)
        val steps = if (live) listOf(listOf<View>(btnBack, btnFwd))
            else listOf(listOf<View>(btnSettings), listOf<View>(btnPrev, btnNext), listOf<View>(btnBack, btnFwd))
        for (step in steps) {
            if (need() <= w) break
            step.forEach { it.visibility = View.GONE }
        }
    }

    // ---------- الإظهار والإخفاء ----------
    val barVisible get() = panel.visibility == View.VISIBLE
    private val hideRun = Runnable { if (autoHide && !dragging && player?.playWhenReady == true) hide() else poke() }

    /** يظهر الشريط ويعيد مؤقّت الإخفاء. */
    fun show() {
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(150).start()
            onVisibility?.invoke(true)
        }
        refresh()
        poke()
    }

    fun hide() {
        if (!autoHide || panel.visibility != View.VISIBLE) return
        if (panel.hasFocus()) act.window?.decorView?.requestFocus()
        panel.animate().alpha(0f).setDuration(150).withEndAction { panel.visibility = View.GONE; placeNext() }.start()
        onVisibility?.invoke(false)
    }

    fun toggle() { if (barVisible) hide() else show() }

    /** أي تفاعل: نأجّل الإخفاء. */
    fun poke() {
        removeCallbacks(hideRun)
        if (autoHide) postDelayed(hideRun, 3500)
    }

    /** يحط التركيز على زر التشغيل (الريموت). */
    fun focusPlay() { show(); btnPlay.requestFocus() }

    fun hasFocusInside() = panel.hasFocus() || nextFloat.hasFocus()

    // ---------- اللمس: ضغطة = إظهار/إخفاء، ضغطتين يمين/يسار = ±10 ثواني ----------
    private val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (onTap?.invoke() == true) return true
            if (autoHide) toggle() else show()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (live) { if (onTap?.invoke() != true && autoHide) toggle(); return true }
            seek(e.x >= width / 2f)
            show()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean { gd.onTouchEvent(event); return true }

    // ---------- الأفعال ----------
    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.playWhenReady = true }
        else p.playWhenReady = !p.playWhenReady
        refresh()
    }

    fun seek(forward: Boolean) {
        val p = player ?: return
        if (live) {
            if (!p.liveSeek(forward)) android.widget.Toast.makeText(act, "هذا البث ما يدعم التقديم والتأخير", android.widget.Toast.LENGTH_SHORT).show()
            p.playWhenReady = true
        } else if (forward) p.seekForward() else p.seekBack()
        refresh(); poke()
    }

    private fun duration(): Long {
        val d = player?.duration ?: return 0
        return if (d == MC.TIME_UNSET || d < 0) 0 else d
    }

    // ---------- التحديث الدوري ----------
    private val tick = object : Runnable {
        override fun run() { refresh(); postDelayed(this, 500) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick); if (autoHide) poke() else show() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); removeCallbacks(hideRun); super.onDetachedFromWindow() }

    fun refresh() {
        val p = player
        val playing = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        playIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (playing) "إيقاف مؤقت" else "تشغيل"
        if (live) {
            val canSeek = p?.isCurrentMediaItemSeekable == true
            btnBack.alpha = if (canSeek) 1f else 0.4f; btnFwd.alpha = btnBack.alpha
            val behind = p != null && (!p.isPlaying || (p.isCurrentMediaItemLive && p.currentLiveOffset.let { it != MC.TIME_UNSET && it > 20_000 }))
            liveText.text = if (behind) "↻  مباشر" else "●  مباشر"
            btnLive.background = act.focusBg(normal = if (behind) 0xFF3A404A.toInt() else C.LIVE, radius = 20)
        } else {
            val d = duration()
            if (p != null && d > 0) {
                if (!dragging) {
                    seekBar.progress = (p.currentPosition * 1000 / d).toInt()
                    tPos.text = fmtTime(p.currentPosition / 1000)
                }
                seekBar.secondaryProgress = (p.bufferedPosition * 1000 / d).toInt()
                tDur.text = fmtTime(d / 1000)
            } else { tPos.text = "--:--"; tDur.text = "--:--" }
            nav?.let { btnPrev.alpha = if (it.hasPrev) 1f else 0.35f; btnNext.alpha = if (it.hasNext) 1f else 0.35f }
            updateNext(p, d)
        }
    }

    private fun updateNext(p: Player?, d: Long) {
        val n = nav
        val left = if (p != null && d > 0) d - p.currentPosition else Long.MAX_VALUE
        val ended = p?.playbackState == Player.STATE_ENDED
        val show = n != null && n.hasNext && (ended || left <= 30_000)
        if (show) {
            nextCount.text = if (ended) "0" else ((left + 999) / 1000).coerceIn(0, 30).toString()
            if (nextFloat.visibility != View.VISIBLE) {
                nextFloat.visibility = View.VISIBLE
                if (!nextShownOnce && act.isTv()) nextFloat.requestFocus()      // الريموت: OK = الحلقة التالية
                nextShownOnce = true
            }
        } else {
            if (nextFloat.visibility == View.VISIBLE && nextFloat.hasFocus()) btnPlay.requestFocus()
            nextFloat.visibility = View.GONE
            if (left > 30_000) nextShownOnce = false
        }
        placeNext()
    }

    /** الزر المنفصل فوق شريط الأزرار (أو تحت لو الشريط مخفي). */
    private fun placeNext() {
        val lp = nextFloat.layoutParams as? LayoutParams ?: return
        val b = if (barVisible && panel.height > 0) panel.height + act.dp(10) else act.dp(16)
        if (lp.bottomMargin != b) { lp.bottomMargin = b; nextFloat.layoutParams = lp }
    }

    // ---------- الإعدادات: السرعة، الصوت، الترجمة ----------
    private fun settings() {
        val p = player ?: return
        val speed = p.playbackParameters.speed
        val items = arrayOf("سرعة التشغيل  (${fmtSpeed(speed)})", "الصوت", "الترجمة")
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle("الإعدادات").setItems(items) { _, w ->
            when (w) { 0 -> speedDialog(); 1 -> tracksDialog(MC.TRACK_TYPE_AUDIO); 2 -> tracksDialog(MC.TRACK_TYPE_TEXT) }
        }.show()
    }

    private fun fmtSpeed(s: Float) = if (s == 1f) "عادي" else "${s}x"

    private fun speedDialog() {
        val p = player ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val cur = speeds.indexOfFirst { it == p.playbackParameters.speed }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle("سرعة التشغيل")
            .setSingleChoiceItems(speeds.map { fmtSpeed(it) }.toTypedArray(), cur) { d, i -> p.setPlaybackSpeed(speeds[i]); d.dismiss() }
            .show()
    }

    private fun tracksDialog(type: Int) {
        val p = player ?: return
        val names = ArrayList<String>()
        val picks = ArrayList<Pair<androidx.media3.common.Tracks.Group, Int>?>()
        var checked = -1
        if (type == MC.TRACK_TYPE_TEXT) {
            names.add("إيقاف"); picks.add(null)
            if (p.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT)) checked = 0
        }
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage.ifEmpty { it } }).firstOrNull()
                    ?: (if (type == MC.TRACK_TYPE_AUDIO) "صوت ${names.size + 1}" else "ترجمة ${names.size}")
                if (g.isTrackSelected(i) && checked < 0) checked = names.size
                names.add(label); picks.add(g to i)
            }
        }
        if (names.isEmpty() || (type == MC.TRACK_TYPE_TEXT && names.size == 1)) {
            android.widget.Toast.makeText(act, if (type == MC.TRACK_TYPE_AUDIO) "ما فيه أكثر من صوت" else "ما فيه ترجمة", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle(if (type == MC.TRACK_TYPE_AUDIO) "الصوت" else "الترجمة")
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, i ->
                val pick = picks[i]
                val b = p.trackSelectionParameters.buildUpon()
                if (pick == null) b.setTrackTypeDisabled(type, true)
                else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
                p.trackSelectionParameters = b.build()
                d.dismiss()
            }.show()
    }
}
