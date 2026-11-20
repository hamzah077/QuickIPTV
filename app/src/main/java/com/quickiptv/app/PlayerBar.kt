package com.quickiptv.app

import android.app.Activity
import android.app.AlertDialog
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C as MC
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi

/** التنقّل بين الحلقات (المسلسلات). */
interface EpisodeNav {
    val seriesId: String           // لتعلّم المقدمة والتتر لكل مسلسل
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
        set(v) {
            field?.removeListener(learnListener)
            field = v
            v?.addListener(learnListener)
            resetItem()
            refresh()
        }

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
    private val playSize = act.dp(48)
    private val gap = act.dp(6)

    /** تحديد رمادي خفيف (الريموت) + ضغطة خفيفة (اللمس). عادي: شفاف — أيقونات بيضاء بدون دوائر. */
    private fun grayFocus(radiusDp: Float): android.graphics.drawable.StateListDrawable {
        fun g(fill: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = act.dpf(radiusDp); setColor(fill)
            if (stroke != 0) setStroke(act.dp(1) + act.dp(1) / 2, stroke)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            setEnterFadeDuration(120); setExitFadeDuration(120)
            addState(intArrayOf(android.R.attr.state_focused), g(0x47C8CCD4, 0x8CD6DAE2.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), g(0x33FFFFFF, 0))
            addState(intArrayOf(), g(0x00000000, 0))
        }
    }

    /** حركة التحديد (يكبر شوي) + اسم الزر فوقه بالتلفزيون. */
    private fun focusable(v: View) {
        v.isFocusable = true; v.isClickable = true
        v.setTag(R.id.own_focus_anim, true)          // حركتنا الخاصة بدل الحركة العامة
        v.setOnFocusChangeListener { _, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(140).start()
            if (has) showTip(v) else if (tipFor === v) hideTip()
            if (has) poke()
        }
    }

    private fun roundBtn(res: Int, desc: String, size: Int = 40, iconDp: Int = 24, onClick: () -> Unit): Pair<FrameLayout, android.widget.ImageView> {
        val ic = act.icon(res, 0xFFFFFFFF.toInt(), iconDp).apply {
            layoutParams = LayoutParams(act.dp(iconDp), act.dp(iconDp), Gravity.CENTER)
        }
        val b = FrameLayout(act).apply {
            background = grayFocus(size / 2f)
            contentDescription = desc
            addView(ic)
            setOnClickListener { onClick(); poke() }
        }
        focusable(b)
        return b to ic
    }

    /** تأخير/تقديم 10 ثواني: سهم دائري و"10" بالنص بالضبط. */
    private fun seekBtn(forward: Boolean): FrameLayout = FrameLayout(act).apply {
        background = grayFocus(20f)
        contentDescription = if (forward) "تقديم 10 ثواني" else "تأخير 10 ثواني"
        addView(act.icon(if (forward) R.drawable.ic_seek_fwd else R.drawable.ic_seek_back, 0xFFFFFFFF.toInt(), 28).apply {
            layoutParams = LayoutParams(act.dp(28), act.dp(28), Gravity.CENTER)
        })
        addView(act.text("10", 9f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 1
            layoutParams = LayoutParams(act.dp(28), act.dp(28), Gravity.CENTER)
        })
        setOnClickListener { seek(forward); poke() }
        focusable(this)
    }

    private fun pill(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(act.dp(12), 0, act.dp(12), 0)
        minimumWidth = btnSize
        background = grayFocus(10f)
        focusable(this)
    }

    private val btnBack = seekBtn(false)
    private val playPair = roundBtn(R.drawable.ic_pause, "إيقاف مؤقت", 48, 32) { togglePlay() }
    private val btnPlay = playPair.first
    private val playIcon = playPair.second
    private val btnFwd = seekBtn(true)
    private val btnPrev = roundBtn(R.drawable.ic_skip_prev, "الحلقة السابقة") { nav?.let { if (it.hasPrev) it.prev() } }.first
    private val btnNext = roundBtn(R.drawable.ic_skip_next, "الحلقة التالية") { nav?.let { if (it.hasNext) { learnManualNext(); it.next() } } }.first
    private val btnSettings = roundBtn(R.drawable.ic_settings, "الإعدادات") { settings() }.first
    private val fullPair = roundBtn(R.drawable.ic_fullscreen, "تكبير") { onFullscreen?.invoke() }
    private val btnFull = fullPair.first.apply { visibility = View.GONE }
    private val fullIcon = fullPair.second

    // "مباشر": نص أحمر بدون كبسولة (رمادي "↻ مباشر" لو متأخر)
    private val liveText = act.text("●  مباشر", 13f, C.LIVE, bold = true).apply { maxLines = 1; includeFontPadding = false }
    private val btnLive = pill().apply {
        contentDescription = "مباشر"
        addView(liveText)
        setOnClickListener { onLive?.invoke(); poke() }
    }
    private val btnChannels = pill().apply {
        contentDescription = "القنوات"
        addView(act.icon(R.drawable.ic_list, 0xFFFFFFFF.toInt(), 22), LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
        addView(act.text("القنوات", 13f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; includeFontPadding = false; setPadding(act.dp(6), 0, 0, 0) })
        setOnClickListener { onChannels?.invoke() }
        visibility = View.GONE
    }

    // شريط التقدّم الرفيع (أحمر) + الوقت + فقاعة الوقت فوق المؤشّر
    private val tPos = act.text("0:00", 12f, 0xFFE6E8EB.toInt()).apply { maxLines = 1 }
    private val tDur = act.text("0:00", 12f, 0xFFE6E8EB.toInt()).apply { maxLines = 1 }
    private val seekLine = SeekLine(act).apply {
        onScrub = { v -> val d = duration(); if (d > 0) { tPos.text = fmtTime(d * v / 1000 / 1000); showBubble(d * v / 1000) }; poke() }
        onCommit = { v -> val d = duration(); if (d > 0) player?.seekTo(d * v / 1000); hideBubble(); poke() }
        onActive = { a -> if (a) poke() else hideBubble() }
        isFocusable = act.isTv()                     // الريموت: سهم فوق = الشريط، يمين/يسار = ±10 ثواني
        setTag(R.id.own_focus_anim, true)
    }
    private val dragging get() = seekLine.scrubbing
    private val bubble = act.text("", 12f, 0xFF111214.toInt(), bold = true).apply {
        maxLines = 1; includeFontPadding = false; gravity = Gravity.CENTER
        setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
        background = rounded(0xFFE9EBEF.toInt(), act.dpf(6f))
        visibility = View.INVISIBLE
    }
    private val progRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tPos, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(seekLine, LinearLayout.LayoutParams(0, act.dp(30), 1f).apply { setMargins(act.dp(10), 0, act.dp(10), 0) })
        addView(tDur, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private fun showBubble(ms: Long) {
        bubble.text = fmtTime(ms / 1000)
        bubble.visibility = View.VISIBLE
        bubble.post {
            val loc = IntArray(2); val me = IntArray(2)
            seekLine.getLocationInWindow(loc); getLocationInWindow(me)
            val x = loc[0] - me[0] + seekLine.thumbX() - bubble.width / 2f
            bubble.translationX = x.coerceIn(0f, (width - bubble.width).toFloat().coerceAtLeast(0f))
            bubble.translationY = (loc[1] - me[1] - bubble.height - act.dp(4)).toFloat()
        }
    }

    private fun hideBubble() { if (!seekLine.isFocused) bubble.visibility = View.INVISIBLE }

    // اسم الزر فوقه (التلفزيون)
    private val tip = act.text("", 11.5f, 0xFFFFFFFF.toInt()).apply {
        maxLines = 1; includeFontPadding = false
        setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
        background = rounded(0xEB14161C.toInt(), act.dpf(6f))
        visibility = View.INVISIBLE
    }
    private var tipFor: View? = null

    private fun showTip(v: View) {
        if (!act.isTv()) return
        val d = v.contentDescription?.toString().orEmpty()
        if (d.isEmpty() || v === seekLine) { hideTip(); return }
        tipFor = v
        tip.text = d
        tip.visibility = View.VISIBLE
        tip.post {
            val loc = IntArray(2); val me = IntArray(2)
            v.getLocationInWindow(loc); getLocationInWindow(me)
            val x = loc[0] - me[0] + v.width / 2f - tip.width / 2f
            tip.translationX = x.coerceIn(0f, (width - tip.width).toFloat().coerceAtLeast(0f))
            tip.translationY = (loc[1] - me[1] - tip.height - act.dp(8)).toFloat()
        }
    }

    private fun hideTip() { tipFor = null; tip.visibility = View.INVISIBLE }

    private val leftGroup = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val rightGroup = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val spacer = View(act)
    private val btnRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val panel = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        // تدرّج سينمائي من تحت (غامق) لفوق (شفاف) بدل صندوق أسود
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x99000000.toInt(), 0x00000000))
        setPadding(act.dp(12), act.dp(28), act.dp(12), act.dp(8))
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
        setOnClickListener { nav?.let { if (it.hasNext) { learnManualNext(); it.next() } } }
        visibility = View.GONE
    }
    private var nextShownOnce = false

    // زر "تخطي المقدمة" المنفصل (نفس المكان والشكل)
    private val introFloat = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isFocusable = true; isClickable = true
        contentDescription = "تخطي المقدمة"
        setPadding(act.dp(14), 0, act.dp(14), 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(0xFFFFFFFF.toInt(), act.dpf(12f), C.ACCENT, act.dp(3)))
            addState(intArrayOf(), rounded(0xFFF2F3F5.toInt(), act.dpf(12f)))
        }
        addView(act.text("تخطي المقدمة", 14f, C.PRIMARY_TEXT, bold = true).apply { maxLines = 1; includeFontPadding = false; setPadding(0, 0, act.dp(8), 0) })
        addView(act.icon(R.drawable.ic_skip_next, C.PRIMARY_TEXT, 20), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        setOnClickListener { skipIntro() }
        visibility = View.GONE
    }
    private var introShownOnce = false
    private var introSkipped = false

    // ---------- التعلّم: من تقديمك أنت (سحب الشريط، +10، ضغطتين، الريموت) ----------
    private var internalSeek = false          // قفزة سوّيناها نحن (تخطي المقدمة): ما نتعلّم منها
    private var burstFrom = -1L
    private var burstTo = -1L
    private val burstEnd = Runnable { finishBurst() }

    private val learnListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPos: Player.PositionInfo, newPos: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) onUserSeek(oldPos.positionMs, newPos.positionMs)
        }
        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) { resetItem() }
    }

    private fun onUserSeek(from: Long, to: Long) {
        if (internalSeek) { internalSeek = false; return }
        if (live || nav == null) return
        if (to < from) { burstFrom = -1; removeCallbacks(burstEnd); return }     // رجوع = مو تخطي
        if (burstFrom < 0) burstFrom = from                                    // ضغطات +10 ورا بعض = قفزة وحدة
        burstTo = to
        removeCallbacks(burstEnd)
        postDelayed(burstEnd, 4000)
    }

    private fun finishBurst() {
        val sid = nav?.seriesId ?: return
        val f = burstFrom; val t = burstTo
        burstFrom = -1
        if (f < 0) return
        val jump = t - f
        val d = duration()
        if (f <= 8 * 60_000 && t <= 10 * 60_000 && jump in 20_000..180_000) {
            Store.learnIntro(sid, f, t)                          // تخطّى المقدمة
        } else if (d > 0) {
            val left = d - f
            if (left in 10_000..6 * 60_000 && t >= d - 20_000) Store.learnCredits(sid, left)   // قدّم للنهاية من داخل التتر
        }
    }

    /** ضغطت "الحلقة التالية" بنفسك قبل النهاية = بداية التتر تقريباً. */
    private fun learnManualNext() {
        val sid = nav?.seriesId ?: return
        val p = player ?: return
        val d = duration()
        if (d <= 0) return
        val left = d - p.currentPosition
        if (left in 10_000..6 * 60_000) Store.learnCredits(sid, left)
    }

    private fun skipIntro() {
        val sid = nav?.seriesId ?: return
        val p = player ?: return
        val (_, e) = Store.intro(sid) ?: return
        introSkipped = true
        internalSeek = true
        p.seekTo(e)
        introFloat.visibility = View.GONE
        poke()
    }

    // عدّاد الحلقة التالية (لما نعرف بداية التتر)
    private var countdownEnd = 0L
    private var autoCancelled = false
    private var autoFired = false

    /** حلقة جديدة: نصفّر كل شي. */
    private fun resetItem() {
        countdownEnd = 0L; autoCancelled = false; autoFired = false
        introSkipped = false; introShownOnce = false; nextShownOnce = false
        burstFrom = -1; removeCallbacks(burstEnd)
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR           // أزرار الميديا وشريط التقدّم من اليسار لليمين
        // يسار: تأخير ← تشغيل/إيقاف (أكبر) ← تقديم (يمين التشغيل)
        leftGroup.addView(btnBack, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = gap * 2 })
        leftGroup.addView(btnPlay, LinearLayout.LayoutParams(playSize, playSize).apply { marginEnd = gap * 2 })
        leftGroup.addView(btnFwd, LinearLayout.LayoutParams(btnSize, btnSize))
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
        addView(introFloat, LayoutParams(WRAP, act.dp(44), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, act.dp(12), act.dp(16)) })
        addView(bubble, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        addView(tip, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        arrange(false)
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
        // التصميم (أ): شريط التقدّم الرفيع فوق، والأزرار تحته (يسار التحكم، يمين الباقي)
        if (!live) panel.addView(progRow, 0, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dp(if (c) 2 else 6) })
        btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        btnRow.addView(rightGroup, LinearLayout.LayoutParams(WRAP, WRAP))
        val h = act.dp(if (c) 10 else 20)
        panel.setPadding(h, act.dp(if (c) 22 else 36), h, act.dp(if (c) 6 else 12))
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
        val minProg = 0
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
        hideTip(); bubble.visibility = View.INVISIBLE
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

    fun hasFocusInside() = panel.hasFocus() || nextFloat.hasFocus() || introFloat.hasFocus()

    /** الريموت: رجوع وقت العدّاد = نلغي التشغيل التلقائي (يرجّع true لو استهلكها). */
    fun cancelAutoNext(): Boolean {
        if (nextFloat.visibility == View.VISIBLE && countdownEnd > 0 && !autoCancelled) { autoCancelled = true; refresh(); return true }
        return false
    }

    // ---------- اللمس: ضغطة = إظهار/إخفاء، ضغطتين يمين/يسار = ±10 ثواني ----------
    private val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (onTap?.invoke() == true) return true
            if (nextFloat.visibility == View.VISIBLE && countdownEnd > 0 && !autoCancelled) { autoCancelled = true; refresh() }   // تبي تكمل التتر
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
            liveText.setTextColor(if (behind) 0xFFC9CDD3.toInt() else C.LIVE)
        } else {
            val d = duration()
            if (p != null && d > 0) {
                if (!dragging) {
                    seekLine.progress = (p.currentPosition * 1000 / d).toInt()
                    tPos.text = fmtTime(p.currentPosition / 1000)
                }
                seekLine.secondary = (p.bufferedPosition * 1000 / d).toInt()
                tDur.text = fmtTime(d / 1000)
            } else { tPos.text = "--:--"; tDur.text = "--:--" }
            nav?.let { btnPrev.alpha = if (it.hasPrev) 1f else 0.35f; btnNext.alpha = if (it.hasNext) 1f else 0.35f }
            updateIntro(p)
            updateNext(p, d)
        }
    }

    /** "تخطي المقدمة": يطلع من بداية المقدمة المتعلّمة لين نهايتها. */
    private fun updateIntro(p: Player?) {
        val sid = nav?.seriesId
        val iv = if (sid != null) Store.intro(sid) else null
        val pos = p?.currentPosition ?: -1
        val show = iv != null && p != null && !introSkipped && pos >= iv.first && pos < iv.second - 3000
        if (show) {
            if (introFloat.visibility != View.VISIBLE) {
                introFloat.visibility = View.VISIBLE
                if (!introShownOnce && act.isTv()) introFloat.requestFocus()      // الريموت: OK = تخطي
                introShownOnce = true
            }
        } else {
            if (introFloat.visibility == View.VISIBLE && introFloat.hasFocus()) btnPlay.requestFocus()
            introFloat.visibility = View.GONE
        }
    }

    /**
     * "الحلقة التالية": لو نعرف بداية التتر لهالمسلسل يطلع أول ما يبدأ التتر، مع عدّاد 10 ثواني وبعدها
     * تشتغل الحلقة التالية. لو ما نعرفه: آخر 30 ثانية والعدّاد لين النهاية.
     */
    private fun updateNext(p: Player?, d: Long) {
        val n = nav
        val left = if (p != null && d > 0) d - p.currentPosition else Long.MAX_VALUE
        val ended = p?.playbackState == Player.STATE_ENDED
        val learned = n?.seriesId?.let { Store.credits(it) }?.takeIf { d > 0 && it < d / 3 }
        val threshold = learned ?: 30_000L
        val show = n != null && n.hasNext && (ended || left <= threshold)
        if (show) {
            if (learned != null && !ended) {
                if (countdownEnd == 0L) countdownEnd = System.currentTimeMillis() + 10_000
                val secs = (countdownEnd - System.currentTimeMillis() + 999) / 1000
                if (autoCancelled) nextCount.visibility = View.GONE
                else {
                    nextCount.visibility = View.VISIBLE
                    nextCount.text = secs.coerceAtLeast(0).toString()
                    if (secs <= 0 && !autoFired && p?.playWhenReady == true) { autoFired = true; n.next(); return }
                }
            } else {
                nextCount.visibility = View.VISIBLE
                nextCount.text = if (ended) "0" else ((left + 999) / 1000).coerceIn(0, 30).toString()
            }
            if (nextFloat.visibility != View.VISIBLE) {
                nextFloat.visibility = View.VISIBLE
                if (!nextShownOnce && act.isTv()) nextFloat.requestFocus()      // الريموت: OK = الحلقة التالية
                nextShownOnce = true
            }
        } else {
            if (nextFloat.visibility == View.VISIBLE && nextFloat.hasFocus()) btnPlay.requestFocus()
            nextFloat.visibility = View.GONE
            if (left > threshold + 5_000) { countdownEnd = 0L; autoCancelled = false; autoFired = false; nextShownOnce = false }
        }
        placeNext()
    }

    /** الزر المنفصل فوق شريط الأزرار (أو تحت لو الشريط مخفي). */
    private fun placeNext() {
        val b = if (barVisible && panel.height > 0) panel.height + act.dp(10) else act.dp(16)
        for (v in listOf<View>(nextFloat, introFloat)) {
            val lp = v.layoutParams as? LayoutParams ?: continue
            if (lp.bottomMargin != b) { lp.bottomMargin = b; v.layoutParams = lp }
        }
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

    // ---------- الريموت على شريط التقدّم: يمين/يسار = ±10 ثواني (ضغط مطوّل أسرع) ----------
    private var pendingSeek = -1L
    private val commitSeek = Runnable {
        if (pendingSeek >= 0) player?.seekTo(pendingSeek)
        pendingSeek = -1
        seekLine.scrubbing = false
        refresh()
    }

    init {
        seekLine.setOnKeyListener { _, code, ev ->
            if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT && code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            val p = player ?: return@setOnKeyListener true
            val d = duration()
            if (d <= 0) return@setOnKeyListener true
            val step = if (ev.repeatCount > 6) 60_000L else if (ev.repeatCount > 0) 30_000L else 10_000L
            val base = if (pendingSeek >= 0) pendingSeek else p.currentPosition
            pendingSeek = (base + if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0, d)
            seekLine.scrubbing = true
            seekLine.progress = (pendingSeek * 1000 / d).toInt()
            tPos.text = fmtTime(pendingSeek / 1000)
            showBubble(pendingSeek)
            removeCallbacks(commitSeek); postDelayed(commitSeek, 700)
            poke()
            true
        }
        seekLine.setOnFocusChangeListener { _, has ->
            seekLine.active = has
            if (has) { hideTip(); player?.let { p -> val d = duration(); if (d > 0) showBubble(p.currentPosition) } } else hideBubble()
            // الأزرار تبهت شوي وقت الشريط محدّد
            btnRow.animate().alpha(if (has) 0.55f else 1f).setDuration(140).start()
            poke()
        }
    }
}

/**
 * شريط تقدّم رفيع مرسوم بنفسه: خط أحمر رفيع ومؤشّر دائري، يتضخّم لما تسحبه أو يكون محدّد بالريموت
 * (وحول المؤشّر هالة رمادية).
 */
class SeekLine(ctx: android.content.Context) : View(ctx) {
    var progress = 0; set(v) { field = v.coerceIn(0, 1000); invalidate() }
    var secondary = 0; set(v) { field = v.coerceIn(0, 1000); invalidate() }
    var scrubbing = false; set(v) { field = v; invalidate() }
    var active = false; set(v) { field = v; invalidate() }
    var onScrub: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    var onActive: ((Boolean) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()
    private val pad get() = 10 * d               // مكان للمؤشّر على الأطراف

    fun thumbX(): Float = pad + (width - 2 * pad) * progress / 1000f

    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        val big = active || scrubbing
        val th = (if (big) 6f else 3f) * d
        val cy = height / 2f
        val l = pad; val w = width - 2 * pad
        fun bar(to: Float, color: Int) {
            paint.color = color
            r.set(l, cy - th / 2, l + w * to, cy + th / 2)
            c.drawRoundRect(r, th / 2, th / 2, paint)
        }
        bar(1f, 0x40FFFFFF)
        bar(secondary / 1000f, 0x66FFFFFF)
        bar(progress / 1000f, 0xFFE5484D.toInt())
        val x = l + w * progress / 1000f
        if (isFocused) { paint.color = 0x73C8CCD4; c.drawCircle(x, cy, 13 * d, paint) }      // هالة رمادية (الريموت)
        paint.color = 0xFFE5484D.toInt()
        c.drawCircle(x, cy, (if (big) 9f else 6.5f) * d, paint)
    }

    private fun valueAt(x: Float) = (((x - pad) / (width - 2 * pad).coerceAtLeast(1f)) * 1000).toInt().coerceIn(0, 1000)

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true; onActive?.invoke(true)
                progress = valueAt(e.x); onScrub?.invoke(progress)
            }
            android.view.MotionEvent.ACTION_MOVE -> { progress = valueAt(e.x); onScrub?.invoke(progress) }
            android.view.MotionEvent.ACTION_UP -> {
                progress = valueAt(e.x)
                scrubbing = false; onCommit?.invoke(progress); onActive?.invoke(false)
            }
            android.view.MotionEvent.ACTION_CANCEL -> { scrubbing = false; onActive?.invoke(false) }
        }
        return true
    }
}
