package com.urrox.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
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
import coil.load

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
        set(v) { field = v; btnFull.visibility = if (v != null) View.VISIBLE else View.GONE; fit(); relayoutYt() }

    /** true = الأيقونة "تصغير" (ملء الشاشة). */
    var fullscreenMode = false
        set(v) { field = v; fullIcon.setImageResource(if (v) R.drawable.ic_minimize else R.drawable.ic_fullscreen); btnFull.contentDescription = if (v) "تصغير" else "تكبير"; relayoutYt() }

    /** زر القنوات (المباشر بملء الشاشة). */
    var onChannels: (() -> Unit)? = null
        set(v) { field = v; btnChannels.visibility = if (v != null) View.VISIBLE else View.GONE; fit(); relayoutYt() }

    /** زر "مباشر": الرجوع للحظة الحالية. */
    var onLive: (() -> Unit)? = null

    /** ضغطة على الشاشة: لو رجّعت true ما نبدّل ظهور الأزرار (مثلاً تقفل قائمة القنوات). */
    var onTap: (() -> Boolean)? = null

    /** يتنادى لما الشريط يظهر/يختفي (عشان العنوان فوق يمشي معه). */
    var onVisibility: ((Boolean) -> Unit)? = null

    /** الحلقات (null = فيلم/مباشر: أزرار السابقة والتالية مخفية). */
    var nav: EpisodeNav? = null
        set(v) { val changed = (field == null) != (v == null); field = v; fit(); if (changed) relayoutYt(); refresh() }

    /** العنوان فوق (نمط الجوال). */
    var titleProvider: (() -> Pair<String, String>)? = null

    /** صف تحت (التلفزيون): حلقات الموسم / قنوات التصنيف. */
    class RowItem(val title: String, val image: String?, val current: Boolean, val onClick: () -> Unit)
    var rowProvider: (() -> Pair<String, List<RowItem>>?)? = null

    /** نمط نتفلكس (التلفزيون): قائمة "الحلقات" بالمواسم، وقصة العمل لشاشة "أنت تشاهد". */
    class EpCard(val title: String, val sub: String, val plot: String, val image: String?, val progress: Float, val current: Boolean, val onClick: () -> Unit)
    class EpData(val seasons: List<Int>, val current: Int, val bySeason: (Int) -> List<EpCard>)
    var episodesProvider: (() -> EpData?)? = null
    var plotProvider: (() -> String)? = null

    /** الجوال: نمط يوتيوب (أزرار بالوسط، خط أحمر، ضغطتين = ±10 بقوس). التلفزيون: التصميم (أ). */
    private val yt = !act.isTv()

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
            if (tvBig && v in tvStyled) tvTintChildren(v, has)          // دائرة بيضاء = الأيقونة داكنة (ما تختفي)
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
        if (!yt && !autoHide) arrange(false)          // (التلفزيون بملء الشاشة يترتّب بآخر init)
    }

    // ---------- الترتيب حسب حجم المشغّل ----------
    private var compact: Boolean? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = w < act.dp(600)
        if (yt) for (a in listOf(arcL, arcR)) (a.layoutParams as? LayoutParams)?.let { it.width = (w * 0.4f).toInt(); a.layoutParams = it }
        if (c != compact) post { arrange(c) } else post { fit() }
    }

    private fun arrange(c: Boolean) {
        if (yt) { arrangeYt(c); return }
        if (!c && autoHide) { arrangeTv(); return }       // التلفزيون بملء الشاشة: نمط يوتيوب تي في
        tvBig = false
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
        if (yt || tvBig) return
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
    val barVisible get() = panel.visibility == View.VISIBLE && !hiding
    private val hideRun = Runnable { if (autoHide && !dragging && !epsOpen && player?.playWhenReady == true) hide() else if (!epsOpen) poke() }

    /** يظهر الشريط ويعيد مؤقّت الإخفاء. */
    /** الشريط قاعد يختفي (حركة التلاشي): نعتبره مخفي. */
    private var hiding = false

    fun show() {
        if (panel.visibility != View.VISIBLE || hiding) {
            // كان يختفي؟ نلغي الاختفاء (قبل: تكمل حركة الإخفاء وتخفيه بعد ما طلبنا إظهاره = ضغطة ضايعة)
            val wasHidden = panel.visibility != View.VISIBLE
            hiding = false
            panel.animate().cancel()
            panel.visibility = View.VISIBLE
            if (wasHidden) panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(150).start()
            onVisibility?.invoke(true)
        }
        refresh()
        poke()
    }

    fun hide() {
        if (!autoHide || panel.visibility != View.VISIBLE || hiding) return
        // التحديد يروح لنقطة مخفية (مو لأول زر بالشريط اللي بيختفي — كان يعلق فيه وتصير الأسهم تنقل لتحت)
        if (panel.hasFocus()) parkFocus()
        hideTip(); bubble.visibility = View.INVISIBLE
        rowBox.visibility = View.GONE
        endPeek()
        hiding = true
        panel.animate().cancel()
        panel.animate().alpha(0f).setDuration(150).withEndAction { if (hiding) { hiding = false; panel.visibility = View.GONE; placeNext() } }.start()
        onVisibility?.invoke(false)
    }

    fun toggle() { if (barVisible) hide() else show() }

    /** أي تفاعل: نأجّل الإخفاء. */
    fun poke() {
        if (pausedAt > 0) pausedAt = System.currentTimeMillis()
        removeCallbacks(hideRun)
        if (autoHide) postDelayed(hideRun, 3500)
    }

    /** يحط التركيز على زر التشغيل (الريموت). */
    /**
     * requestFocusFromTouch: لو الجهاز بوضع اللمس (ماوس/ريموت هوائي — والوضع ينتقل من الصفحة اللي قبل)
     * requestFocus العادي يفشل بصمت = أول ما تشغّل الحلقة ما تقدر تتحكم لين تختفي الأزرار وترجع.
     */
    fun focusPlay() { endPeek(); show(); btnPlay.requestFocusFromTouch() }

    /** التحديد على أزرار الشريط (الظاهر). أزرار «تخطي المقدمة/الحلقة التالية» لحالها: floatFocused. */
    fun hasFocusInside() = panel.visibility == View.VISIBLE && !hiding && panel.hasFocus()

    /** نمط نتفلكس: سهم تحت من صف الأزرار (المسلسل) = قائمة الحلقات على طول. */
    fun downFromButtons(): Boolean {
        if (!netflix || !nfBtns.hasFocus() || nav == null || episodesProvider == null) return false
        openEpisodes(); return true
    }
    fun floatFocused() = (nextFloat.visibility == View.VISIBLE && nextFloat.hasFocus()) || (introFloat.visibility == View.VISIBLE && introFloat.hasFocus())

    /** نقطة تحديد مخفية: لما الأزرار تختفي التحديد يجلس هنا والأسهم ترجع للمشغّل (تقديم/ترجيع). */
    private val focusSink = View(act).apply {
        isFocusable = false; setTag(R.id.own_focus_anim, true)
        setOnFocusChangeListener { v, has -> if (!has) v.isFocusable = false }     // ما ينوصل له بالأسهم (بس وقت نركنه)
    }
    private fun parkFocus() {
        if (focusSink.parent == null) addView(focusSink, LayoutParams(1, 1))
        focusSink.isFocusable = true; focusSink.requestFocusFromTouch()
    }

    /** الريموت: رجوع وقت العدّاد = نلغي التشغيل التلقائي (يرجّع true لو استهلكها). */
    fun cancelAutoNext(): Boolean {
        if (nextFloat.visibility == View.VISIBLE && countdownEnd > 0 && !autoCancelled) { autoCancelled = true; refresh(); return true }
        return false
    }

    // ---------- اللمس: ضغطة = إظهار/إخفاء، ضغطتين يمين/يسار = ±10 ثواني ----------
    private val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // نمط يوتيوب: ضغطات ورا بعض على نفس الجهة بعد ضغطتين = +10 كل ضغطة
            if (yt && !live) {
                val fwd = e.x >= width / 2f
                val now = System.currentTimeMillis()
                if (now - lastSeekAt in 120..700 && fwd == lastSeekFwd) { tapSeek(fwd); tapConsumed = true }
            }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapConsumed) { tapConsumed = false; return true }
            if (onTap?.invoke() == true) return true
            if (nextFloat.visibility == View.VISIBLE && countdownEnd > 0 && !autoCancelled) { autoCancelled = true; refresh() }   // تبي تكمل التتر
            if (autoHide) toggle() else show()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (live) { if (onTap?.invoke() != true && autoHide) toggle(); return true }
            if (yt) { if (System.currentTimeMillis() - lastSeekAt > 120) tapSeek(e.x >= width / 2f); return true }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); post(tick); if (autoHide) poke() else show()
        // التلفزيون: نطلع من وضع اللمس من أول لحظة عشان أول ضغطة بالريموت تشتغل على طول
        if (act.isTv() && autoHide) post { if (!hasFocus()) parkFocus() }
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); removeCallbacks(hideRun); super.onDetachedFromWindow() }

    fun refresh() {
        if (netflix) refreshNetflix()
        if (tvBig) titleProvider?.invoke()?.let { (t, sub) ->
            if (tvTitle.text.toString() != t) tvTitle.text = t
            if (tvSub.text.toString() != sub) { tvSub.text = sub; tvSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE }
        }
        if (yt) {
            titleProvider?.invoke()?.let { (t, s) ->
                val v = listOf(t, s.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                if (ytTitle.text.toString() != v) ytTitle.text = v
            }
            edge.visibility = if (!live && !barVisible && duration() > 0) View.VISIBLE else View.GONE
        }
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
                tDur.text = if (yt) " / " + fmtTime(d / 1000) else fmtTime(d / 1000)
                edgeFill.scaleX = (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
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
                if (!introShownOnce && act.isTv()) introFloat.requestFocusFromTouch()      // الريموت: OK = تخطي
                introShownOnce = true
            }
        } else {
            if (introFloat.visibility == View.VISIBLE && introFloat.hasFocus()) { if (barVisible) btnPlay.requestFocus() else parkFocus() }
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
                if (!nextShownOnce && act.isTv()) nextFloat.requestFocusFromTouch()      // الريموت: OK = الحلقة التالية
                nextShownOnce = true
            }
        } else {
            if (nextFloat.visibility == View.VISIBLE && nextFloat.hasFocus()) { if (barVisible) btnPlay.requestFocus() else parkFocus() }
            nextFloat.visibility = View.GONE
            if (left > threshold + 5_000) { countdownEnd = 0L; autoCancelled = false; autoFired = false; nextShownOnce = false }
        }
        placeNext()
    }

    /** الزر المنفصل فوق شريط الأزرار (أو تحت لو الشريط مخفي). */
    private fun placeNext() {
        val b = if (yt) act.dp(if (barVisible) 78 else 16)
            else if (tvBig) (if (barVisible && panel.height > 0) panel.height + act.dp(12) else act.dp(28))
            else if (barVisible && panel.height > 0) panel.height + act.dp(10) else act.dp(16)
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

    // ---------- نمط يوتيوب تي في (التلفزيون بملء الشاشة) ----------
    private var tvBig = false
    private val tvTitle = act.text("", 22f, 0xFFFFFFFF.toInt(), bold = true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private val tvSub = act.text("", 13.5f, 0xFFC9CDD3.toInt()).apply { maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
    private val tvTitleBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val tvTimeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tvTimeSpacer = View(act)
    private val rowTitle = act.text("", 14f, 0xFFFFFFFF.toInt(), bold = true).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_RTL }
    private val rowList = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val rowScroll = object : android.widget.HorizontalScrollView(act) {
        // السكرول الافتراضي يلصق الحلقة المحددة بطرف الصف فينقص تكبيرها وإطارها — نخلي هامش حولها
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
            val r = android.graphics.Rect(rect); r.inset(-act.dp(24), 0)
            return super.computeScrollDeltaToGetChildRectOnScreen(r)
        }
    }.apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; layoutDirection = View.LAYOUT_DIRECTION_RTL
        // مساحة حول الصف من البداية تكفي لتكبير الحلقة المحددة وإطارها (ما ينقص من الأطراف ولا يتزحزح)
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        setTag(R.id.focus_room, true)
        addView(rowList)
    }
    private val rowBox = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        clipChildren = false; clipToPadding = false
        addView(rowTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dp(8) })
        addView(rowScroll, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** التحديد: دائرة بيضاء والأيقونة سودا (واضح من بعيد). */
    private fun tvFocusBg(radiusDp: Float) = if (Glass.on) Glass.button(this, act.dpf(radiusDp), backdrop = false) else android.graphics.drawable.StateListDrawable().apply {
        setEnterFadeDuration(110); setExitFadeDuration(110)
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { cornerRadius = act.dpf(radiusDp); setColor(C.FOCUS) })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { cornerRadius = act.dpf(radiusDp); setColor(0x14FFFFFF) })   // عادي: دائرة رمادي شفاف خفيف (نفس القائمة الجانبية)
    }

    /** أزرار التلفزيون: دائرة بيضاء لما تتحدد والأيقونة داكنة جواها (نلوّنها بأنفسنا مع كل تحديد —
     *  الطريقة القديمة ما كانت توصل للأيقونة فكانت تختفي أبيض على أبيض). */
    private val tvStyled = HashSet<View>()
    private fun tvStyle(b: View, radiusDp: Float) {
        b.background = tvFocusBg(radiusDp)
        tvStyled.add(b)
        tvTintChildren(b, b.isFocused)
    }
    private fun tvTintChildren(b: View, focused: Boolean) {
        val col = if (focused) 0xFF111214.toInt() else 0xFFFFFFFF.toInt()
        if (b is android.view.ViewGroup) for (i in 0 until b.childCount) {
            val c = b.getChildAt(i)
            if (c is android.widget.ImageView) c.imageTintList = ColorStateList.valueOf(col)
            else if (c is TextView && c !== liveText) c.setTextColor(col)
            else if (c is android.view.ViewGroup) tvTintChildren(c, focused)
        }
    }

    private fun arrangeTv() {
        tvBig = true
        compact = false
        val parts = listOf<View>(btnBack, btnPlay, btnFwd, btnPrev, btnNext, btnSettings, btnFull, btnLive, btnChannels, btnCC,
            tPos, tDur, seekLine, progRow, leftGroup, rightGroup, btnRow, spacer, tvTitle, tvSub, tvTitleBox, tvTimeRow, tvTimeSpacer, rowBox)
        for (v in parts) (v.parent as? android.view.ViewGroup)?.removeView(v)
        for (g in listOf(leftGroup, rightGroup, btnRow, tvTitleBox, tvTimeRow)) g.removeAllViews()
        panel.removeAllViews()
        panel.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xF0000000.toInt(), 0xB3000000.toInt(), 0x00000000))
        panel.setPadding(act.dp(48), act.dp(90), act.dp(48), act.dp(30))
        panel.clipChildren = false; panel.clipToPadding = false
        if (!live) { arrangeNetflix(); return }
        // العنوان كبير
        tvTitleBox.addView(tvTitle); tvTitleBox.addView(tvSub)
        panel.addView(tvTitleBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dp(14) })
        // الشريط بعرض الشاشة والوقت تحته على الطرفين
        if (!live) {
            panel.addView(seekLine, LinearLayout.LayoutParams(MATCH, act.dp(26)).apply { setMargins(-act.dp(10), 0, -act.dp(10), 0) })
            tvTimeRow.addView(tPos); tvTimeRow.addView(tvTimeSpacer, LinearLayout.LayoutParams(0, 1, 1f)); tvTimeRow.addView(tDur)
            panel.addView(tvTimeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = act.dp(10) })
        }
        // الأزرار: تشغيل · السابقة · التالية (المباشر: تشغيل · مباشر)  …  ترجمة · إعدادات (المباشر: القنوات)
        val s = act.dp(44)
        val g = act.dp(10)
        leftGroup.addView(btnPlay, LinearLayout.LayoutParams(s, s).apply { marginEnd = g })
        if (live) leftGroup.addView(btnLive, LinearLayout.LayoutParams(WRAP, s))
        else if (nav != null) {
            leftGroup.addView(btnPrev, LinearLayout.LayoutParams(s, s).apply { marginEnd = g })
            leftGroup.addView(btnNext, LinearLayout.LayoutParams(s, s))
        }
        if (live) { if (onChannels != null) rightGroup.addView(btnChannels, LinearLayout.LayoutParams(WRAP, s)) }
        else {
            rightGroup.addView(btnCC, LinearLayout.LayoutParams(s, s).apply { marginEnd = g })
            rightGroup.addView(btnSettings, LinearLayout.LayoutParams(s, s))
        }
        for (b in listOf(btnPlay, btnPrev, btnNext, btnCC, btnSettings)) { tvStyle(b, 22f); b.visibility = View.VISIBLE; b.alpha = 1f }
        tvStyle(btnLive, 22f); tvStyle(btnChannels, 22f)
        btnRow.addView(leftGroup, LinearLayout.LayoutParams(WRAP, WRAP))
        btnRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        btnRow.addView(rightGroup, LinearLayout.LayoutParams(WRAP, WRAP))
        panel.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(rowBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(18) })
        rowBox.visibility = View.GONE
        refresh()
    }

    // ---------- صف الحلقات/القنوات (سهم تحت) ----------
    fun rowHasFocus() = rowBox.visibility == View.VISIBLE && rowBox.hasFocus()
    fun seekFocused() = seekLine.isFocused

    /** يطلّع الصف ويحدّد العنصر الحالي. false = ما فيه صف. */
    fun showRow(): Boolean {
        if (!tvBig || netflix) return false
        val (title, items) = rowProvider?.invoke() ?: return false
        if (items.isEmpty()) return false
        rowTitle.text = title
        rowList.removeAllViews()
        var cur: View? = null
        val w = act.dp(168); val h = act.dp(94)
        for (it in items) {
            val img = android.widget.ImageView(act).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFF2A2D33.toInt(), act.dpf(8f)); clipToOutline = true
                load(it.image?.ifEmpty { null }) { size(320, 180) }
            }
            val frame = FrameLayout(act).apply {
                setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0x00000000, act.dpf(11f), 0xFFCDD2DA.toInt(), act.dp(3)))     // نفس لون التحديد بباقي التطبيق
                    addState(intArrayOf(), rounded(0x00000000, act.dpf(11f)))
                }
                addView(img, LayoutParams(MATCH, MATCH))
                if (it.current) addView(View(act).apply { setBackgroundColor(0xFFE5484D.toInt()) }, LayoutParams(MATCH, act.dp(4), Gravity.BOTTOM))
            }
            val label = act.text(if (it.current) "▶ تعرض الآن" else it.title, 12.5f, if (it.current) 0xFFFFFFFF.toInt() else 0xFFC9CDD3.toInt(), bold = it.current).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(act.dp(3), act.dp(6), act.dp(3), 0)
            }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setTag(R.id.own_focus_anim, true)
                contentDescription = it.title
                frame.isDuplicateParentStateEnabled = true
                addView(frame, LinearLayout.LayoutParams(w, h))
                addView(label, LinearLayout.LayoutParams(w, WRAP))
                val click = it.onClick
                setOnClickListener { hideRow(); click(); poke() }
                setOnFocusChangeListener { v, has ->
                    v.raiseOnFocus(has)
                    v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(140).start()
                    if (has) poke()
                }
            }
            rowList.addView(cell, LinearLayout.LayoutParams(w, WRAP).apply { marginEnd = act.dp(16) })
            if (it.current) cur = cell
        }
        rowBox.visibility = View.VISIBLE
        val target = cur ?: rowList.getChildAt(0)
        target?.post { target.requestFocus() }
        placeNext(); poke()
        return true
    }

    fun hideRow() {
        if (rowBox.visibility != View.VISIBLE) return
        rowBox.visibility = View.GONE
        placeNext()
    }

    // ---------- نمط يوتيوب (الجوال) ----------
    private val ytTop = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val ytCenter = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val ytMid = FrameLayout(act)
    private val ytBottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val ytSpacer = View(act)
    private val ytChevron = roundBtn(R.drawable.ic_chev_down, "تصغير") { onFullscreen?.invoke() }.first
    private val btnCC = roundBtn(R.drawable.ic_cc, "الترجمة") { tracksDialog(MC.TRACK_TYPE_TEXT) }.first
    private val ytTitle = act.text("", 14f, 0xFFFFFFFF.toInt(), bold = true).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(act.dp(6), 0, act.dp(6), 0)
    }
    private val timeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // الخط الأحمر الرفيع على حافة الفيديو لما الأزرار مخفية
    private val edgeFill = View(act).apply { setBackgroundColor(0xFFE5484D.toInt()); pivotX = 0f; scaleX = 0f }
    private val edge = FrameLayout(act).apply {
        setBackgroundColor(0x4DFFFFFF)
        addView(edgeFill, LayoutParams(MATCH, MATCH))
        visibility = View.GONE
    }

    // قوس الضغطتين (±10 ثواني) على الجهة
    private fun arc(fwd: Boolean) = act.text("", 12.5f, 0xFFFFFFFF.toInt(), bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 2
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x2EFFFFFF)
            val r = act.dpf(400f)
            cornerRadii = if (fwd) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        alpha = 0f; visibility = View.GONE
    }
    private val arcL = arc(false)
    private val arcR = arc(true)
    private var lastSeekAt = 0L
    private var lastSeekFwd = true
    private var seekAccum = 0
    private var tapConsumed = false
    private val arcHide = Runnable {
        for (a in listOf(arcL, arcR)) a.animate().alpha(0f).setDuration(200).withEndAction { a.visibility = View.GONE }.start()
        seekAccum = 0
    }

    /** ضغطة تقديم/تأخير (نمط يوتيوب): قوس على الجهة + مجموع الثواني. */
    private fun tapSeek(fwd: Boolean) {
        val p = player ?: return
        if (fwd != lastSeekFwd) seekAccum = 0
        lastSeekFwd = fwd; lastSeekAt = System.currentTimeMillis()
        if (fwd) p.seekForward() else p.seekBack()
        seekAccum += 10
        val a = if (fwd) arcR else arcL
        (if (fwd) arcL else arcR).visibility = View.GONE
        a.text = (if (fwd) "▶▶\n" else "◀◀\n") + "$seekAccum ثانية"
        a.visibility = View.VISIBLE; a.animate().cancel(); a.alpha = 1f
        removeCallbacks(arcHide); postDelayed(arcHide, 700)
        refresh()
    }

    private fun ytCircle(): android.graphics.drawable.StateListDrawable {
        fun g(c: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
            if (stroke != 0) setStroke(act.dp(1) + act.dp(1) / 2, stroke)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), g(0x80000000.toInt(), 0x8CD6DAE2.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), g(0x99000000.toInt(), 0))
            addState(intArrayOf(), g(0x59000000, 0))
        }
    }

    private fun relayoutYt() { if (yt && compact != null) post { arrange(compact ?: true) } }

    private fun arrangeYt(c: Boolean) {
        compact = c
        val parts = listOf<View>(btnBack, btnPlay, btnFwd, btnPrev, btnNext, btnSettings, btnFull, btnLive, btnChannels, btnCC,
            ytChevron, ytTitle, ytSpacer, tPos, tDur, seekLine, progRow, ytTop, ytMid, ytCenter, ytBottom, timeRow, leftGroup, rightGroup, btnRow, spacer)
        for (v in parts) (v.parent as? android.view.ViewGroup)?.removeView(v)
        for (g in listOf(ytTop, ytCenter, ytBottom, timeRow, leftGroup, rightGroup, btnRow)) g.removeAllViews()
        panel.removeAllViews()
        panel.background = android.graphics.drawable.ColorDrawable(0x5C000000)        // يغمّق الفيديو شوي وقت الأزرار
        val h = act.dp(if (c) 6 else 16)
        panel.setPadding(h, act.dp(if (c) 2 else 8), h, 0)
        (panel.layoutParams as? LayoutParams)?.let { it.height = MATCH; it.gravity = Gravity.FILL; panel.layoutParams = it }
        val s = act.dp(40)
        // فوق: سهم التصغير (ملء الشاشة) + العنوان … الترجمة + الإعدادات
        if (fullscreenMode && onFullscreen != null) ytTop.addView(ytChevron, LinearLayout.LayoutParams(s, s))
        ytTop.addView(ytTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!live) {
            ytTop.addView(btnCC, LinearLayout.LayoutParams(s, s).apply { marginStart = act.dp(4) })
            ytTop.addView(btnSettings, LinearLayout.LayoutParams(s, s).apply { marginStart = act.dp(4) })
        }
        // الوسط: السابقة · تشغيل كبير · التالية (فيلم/مباشر: تأخير · تشغيل · تقديم)
        val (l, r) = if (!live && nav != null) btnPrev to btnNext else btnBack to btnFwd
        val side = act.dp(if (c) 44 else 52); val big = act.dp(if (c) 60 else 72); val gp = act.dp(if (c) 30 else 52)
        for (b in listOf(l, btnPlay, r)) { b.background = ytCircle(); b.visibility = View.VISIBLE; b.alpha = 1f }
        ytCenter.addView(l, LinearLayout.LayoutParams(side, side).apply { marginEnd = gp })
        ytCenter.addView(btnPlay, LinearLayout.LayoutParams(big, big).apply { marginEnd = gp })
        ytCenter.addView(r, LinearLayout.LayoutParams(side, side))
        ytMid.addView(ytCenter, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        // تحت: الوقت (أو مباشر) … القنوات + تكبير/تصغير
        if (live) ytBottom.addView(btnLive, LinearLayout.LayoutParams(WRAP, s))
        else {
            timeRow.addView(tPos); timeRow.addView(tDur)
            ytBottom.addView(timeRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
        }
        ytBottom.addView(ytSpacer, LinearLayout.LayoutParams(0, 1, 1f))
        if (onChannels != null) ytBottom.addView(btnChannels, LinearLayout.LayoutParams(WRAP, s).apply { marginEnd = act.dp(4) })
        if (onFullscreen != null) ytBottom.addView(btnFull, LinearLayout.LayoutParams(s, s))
        panel.addView(ytTop, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(ytMid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        panel.addView(ytBottom, LinearLayout.LayoutParams(MATCH, WRAP))
        // الخط الأحمر: على حافة الفيديو بالمشغّل الصغير، ومرفوع شوي بملء الشاشة
        if (!live) panel.addView(seekLine, LinearLayout.LayoutParams(MATCH, act.dp(22)).apply {
            bottomMargin = if (c) -act.dp(9) else act.dp(8)
            if (c) { marginStart = -h - act.dp(8); marginEnd = -h - act.dp(8) }
        })
        placeNext()
    }

    // ---------- الريموت على شريط التقدّم: يمين/يسار = ±10 ثواني (ضغط مطوّل أسرع) ----------
    private var noSeekToast = 0L
    private fun remoteSeek(forward: Boolean, repeat: Int) {
        val p = player ?: return
        val d = duration()
        if (d <= 0) {
            // المدة مو معروفة (بعض الحلقات من السيرفر): قبل كان ما يصير شي أبداً — نقدّم مباشرة لو الملف يسمح
            if (p.isCurrentMediaItemSeekable) {
                val step = if (repeat > 6) 60_000L else if (repeat > 0) 30_000L else 10_000L
                p.seekTo((p.currentPosition + if (forward) step else -step).coerceAtLeast(0))
            } else if (System.currentTimeMillis() - noSeekToast > 4000) {
                noSeekToast = System.currentTimeMillis()
                android.widget.Toast.makeText(act, "هذا الملف ما يدعم التقديم من السيرفر", android.widget.Toast.LENGTH_SHORT).show()
            }
            poke(); return
        }
        val step = if (repeat > 6) 60_000L else if (repeat > 0) 30_000L else 10_000L
        val base = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        pendingSeek = (base + if (forward) step else -step).coerceIn(0, d)
        seekLine.scrubbing = true
        seekLine.progress = (pendingSeek * 1000 / d).toInt()
        tPos.text = fmtTime(pendingSeek / 1000)
        if (netflix) tRemain.text = fmtTime((d - pendingSeek) / 1000)
        showBubble(pendingSeek)
        removeCallbacks(commitSeek); postDelayed(commitSeek, 700)
        poke()
    }

    private var pendingSeek = -1L
    private val commitSeek = Runnable {
        if (pendingSeek >= 0) player?.seekTo(pendingSeek)
        pendingSeek = -1
        seekLine.scrubbing = false
        refresh()
    }

    init {
        if (yt) {
            // (هنا لأن عناصر نمط يوتيوب تنعرّف بعد أول init)
            addView(arcL, 0, LayoutParams(0, MATCH, Gravity.START))
            addView(arcR, 0, LayoutParams(0, MATCH, Gravity.END))
            addView(edge, LayoutParams(MATCH, act.dp(3), Gravity.BOTTOM))
            panel.clipChildren = false
            arrange(true)
        }
        seekLine.setOnKeyListener { _, code, ev ->
            if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT && code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            remoteSeek(code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT, ev.repeatCount)
            true
        }
        seekLine.setOnFocusChangeListener { _, has ->
            seekLine.active = has
            if (has) { hideTip(); player?.let { p -> val d = duration(); if (d > 0) showBubble(p.currentPosition) } } else hideBubble()
            // الأزرار تبهت شوي وقت الشريط محدّد
            (if (netflix) nfBtns else btnRow).animate().alpha(if (has) 0.55f else 1f).setDuration(140).start()
            poke()
        }
    }

    // =====================================================================================
    // ---------- نمط نتفلكس (التلفزيون: الأفلام والمسلسلات) ----------
    // الشريط تحت: تشغيل · خط التقدّم بعرض الشاشة · الوقت المتبقي، تحته اسم العمل بالنص،
    // وتحته أزرار بالكلام: التالي · الحلقات · الصوت والترجمة · السرعة (المحدد أبيض).
    // =====================================================================================
    private var netflix = false
    val isNetflix get() = netflix
    private val tRemain = act.text("", 15f, 0xFFFFFFFF.toInt()).apply { maxLines = 1; includeFontPadding = false }
    private val nfProg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val nfTitle = act.text("", 17f, 0xFFFFFFFF.toInt(), bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    private val nfBtns = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
    }

    private fun nfButton(icon: Int, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val tv = act.text(label, 14.5f, 0xFFFFFFFF.toInt(), bold = true).apply {
            maxLines = 1; includeFontPadding = false; setPaddingRelative(act.dp(8), 0, 0, 0)
        }
        val b = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(act.dp(16), 0, act.dp(16), 0)
            contentDescription = label
            isFocusable = true; isClickable = true
            setTag(R.id.own_focus_anim, true)
            addView(act.icon(icon, 0xFFFFFFFF.toInt(), 20), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
            addView(tv)
            setOnClickListener { onClick(); poke() }
        }
        tvStyle(b, 8f)
        b.setOnFocusChangeListener { _, has -> tvTintChildren(b, has); if (has) { hideTip(); poke() } }
        return b to tv
    }

    private val nfNext = nfButton(R.drawable.ic_skip_next, "التالي") { nav?.let { if (it.hasNext) { learnManualNext(); it.next() } } }.first
    private val nfEps = nfButton(R.drawable.ic_episodes, "الحلقات") { openEpisodes() }.first
    private val nfAudio = nfButton(R.drawable.ic_cc, "الصوت والترجمة") { audioSubs() }.first
    private val nfSpeedPair = nfButton(R.drawable.ic_speed, "السرعة") { speedDialog() }
    private val nfSpeed = nfSpeedPair.first

    private fun audioSubs() {
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle("الصوت والترجمة")
            .setItems(arrayOf("الصوت", "الترجمة")) { _, w -> tracksDialog(if (w == 0) MC.TRACK_TYPE_AUDIO else MC.TRACK_TYPE_TEXT) }
            .show()
    }

    private fun arrangeNetflix() {
        netflix = true
        for (v in listOf<View>(nfProg, nfTitle, nfBtns)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        for (g in listOf(nfProg, nfBtns)) g.removeAllViews()
        for (v in listOf<View>(nfNext, nfEps, nfAudio, nfSpeed)) (v.parent as? android.view.ViewGroup)?.removeView(v)
        panel.setPadding(act.dp(48), act.dp(120), act.dp(48), act.dp(30))
        val s = act.dp(44)
        tvStyle(btnPlay, 22f); btnPlay.visibility = View.VISIBLE; btnPlay.alpha = 1f
        nfProg.addView(btnPlay, LinearLayout.LayoutParams(s, s).apply { marginEnd = act.dp(12) })
        nfProg.addView(seekLine, LinearLayout.LayoutParams(0, act.dp(28), 1f))
        nfProg.addView(tRemain, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(12) })
        panel.addView(nfProg, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(nfTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(8) })
        val h = act.dp(42)
        for (b in listOf<View>(nfNext, nfEps, nfAudio, nfSpeed))
            nfBtns.addView(b, LinearLayout.LayoutParams(WRAP, h).apply { marginStart = act.dp(6); marginEnd = act.dp(6) })
        if (Glass.on) {
            // ليكود جلاس: كل الأزرار والشريط داخل كبسولة زجاج داكنة عائمة تحت، والأزرار كبسولات زجاج
            panel.background = Glass.Pane(panel, act.dpf(34f), dark = true)
            panel.setPadding(act.dp(26), act.dp(16), act.dp(26), act.dp(16))
            (panel.layoutParams as? LayoutParams)?.let { it.setMargins(act.dp(40), 0, act.dp(40), act.dp(22)); panel.layoutParams = it }
            for (b in listOf<View>(nfNext, nfEps, nfAudio, nfSpeed)) b.background = Glass.button(b, act.dpf(21f), backdrop = false)
        }
        panel.addView(nfBtns, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(16); gravity = Gravity.CENTER_HORIZONTAL })
        if (epsView.parent == null) addView(epsView, LayoutParams(MATCH, MATCH))
        if (pauseView.parent == null) addView(pauseView, LayoutParams(MATCH, MATCH))
        if (Glass.on) {
            // ليكود جلاس: قائمة الحلقات و«أنت تشاهد» داخل ألواح زجاج داكنة
            epsView.setBackgroundColor(0x99000000.toInt())
            (epsView.getChildAt(0) as? View)?.let { c ->
                c.background = Glass.Pane(c, act.dpf(32f), dark = true)
                (c.layoutParams as? LayoutParams)?.let { it.setMargins(act.dp(36), act.dp(28), act.dp(36), act.dp(28)); c.layoutParams = it }
            }
            pauseView.background = android.graphics.drawable.ColorDrawable(0x66000000)
            (pauseView.getChildAt(0) as? View)?.let { c ->
                c.background = Glass.Pane(c, act.dpf(30f), dark = true)
                c.setPaddingRelative(act.dp(30), act.dp(26), act.dp(30), act.dp(26))
                (c.layoutParams as? LayoutParams)?.let { it.marginEnd = act.dp(48); c.layoutParams = it }
            }
        }
        refresh()
    }

    private fun refreshNetflix() {
        val p = player
        val d = duration()
        if (p != null && d > 0 && pendingSeek < 0) tRemain.text = fmtTime((d - p.currentPosition).coerceAtLeast(0) / 1000)
        else if (d <= 0) tRemain.text = "--:--"
        titleProvider?.invoke()?.let { (t, sub) ->
            val v = listOf(t, sub.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString("   ·   ")
            if (nfTitle.text.toString() != v) nfTitle.text = v
        }
        val n = nav
        nfNext.visibility = if (n != null && n.hasNext) View.VISIBLE else View.GONE
        nfEps.visibility = if (n != null && episodesProvider != null) View.VISIBLE else View.GONE
        val sp = p?.playbackParameters?.speed ?: 1f
        val lbl = if (sp == 1f) "السرعة (1x)" else "السرعة (${sp}x)"
        if (nfSpeedPair.second.text.toString() != lbl) nfSpeedPair.second.text = lbl
        updatePause(p)
    }

    // ---------- تقديم/ترجيع والأزرار مخفية: الشريط بس + الوقت فوق المؤشّر ----------
    private var peeking = false
    private val peekEnd = Runnable { endPeek() }
    private fun endPeek() {
        removeCallbacks(peekEnd)
        if (!peeking) return
        peeking = false
        nfTitle.visibility = View.VISIBLE; nfBtns.visibility = View.VISIBLE; btnPlay.visibility = View.VISIBLE
        if (!seekLine.isFocused) bubble.visibility = View.INVISIBLE
    }

    /** الريموت يمين/يسار والفيديو بدون أزرار (نمط نتفلكس). ضغط مطوّل = أسرع. */
    fun peekSeek(forward: Boolean, repeat: Int) {
        if (!netflix) { seek(forward); show(); return }
        if (!barVisible || peeking) {
            peeking = true
            nfTitle.visibility = View.INVISIBLE; nfBtns.visibility = View.INVISIBLE; btnPlay.visibility = View.INVISIBLE
            show()
        }
        remoteSeek(forward, repeat)
        removeCallbacks(peekEnd); postDelayed(peekEnd, 2200)
    }

    // ---------- قائمة "الحلقات" ----------
    private var epsOpen = false
    fun overlayOpen() = epsOpen || pauseShown
    private val epsTitle = act.text("الحلقات", 26f, 0xFFFFFFFF.toInt(), bold = true)
    private val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val cardRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
    }
    private fun edgeScroll(child: View) = object : android.widget.HorizontalScrollView(act) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
            val r = android.graphics.Rect(rect); r.inset(-act.dp(40), 0)
            return super.computeScrollDeltaToGetChildRectOnScreen(r)
        }
    }.apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setTag(R.id.focus_room, true)
        addView(child)
    }
    private val chipScroll = edgeScroll(chipRow).apply { setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6)) }
    private val cardScroll = edgeScroll(cardRow).apply { setPadding(act.dp(16), act.dp(16), act.dp(16), act.dp(16)) }
    private val epsView = FrameLayout(act).apply {
        setBackgroundColor(0xE0000000.toInt())
        visibility = View.GONE
        isClickable = true
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            clipChildren = false; clipToPadding = false
            setPadding(act.dp(40), act.dp(36), act.dp(40), act.dp(20))
            addView(epsTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = act.dp(8) })
            addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(10) })
            addView(cardScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(10) })
            addView(act.text("◀ ▶ تتنقل بين الحلقات  ·  ▲ المواسم  ·  رجوع = يقفل القائمة", 12.5f, 0xFF8A8F98.toInt()).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6) })
        }
        addView(col, LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
    }
    private var epsData: EpData? = null
    private var epsSeason = -1

    private fun openEpisodes() {
        val data = episodesProvider?.invoke() ?: return
        epsData = data
        epsOpen = true
        removeCallbacks(hideRun)
        panel.animate().cancel(); hiding = false; panel.visibility = View.GONE; panel.alpha = 1f
        hideTip(); bubble.visibility = View.INVISIBLE
        onVisibility?.invoke(false)
        chipRow.removeAllViews()
        for (sn in data.seasons) {
            val chip = act.text("الموسم $sn", 14f, 0xFFFFFFFF.toInt(), bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 1
                setPadding(act.dp(18), 0, act.dp(18), 0)
                isFocusable = true; isClickable = true
                setTag(R.id.own_focus_anim, true)
                tag = sn
                setOnFocusChangeListener { _, has -> styleChips(); if (has && epsSeason != sn) fillSeason(sn, focusCard = false) }
                setOnClickListener { fillSeason(sn, focusCard = true) }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(WRAP, act.dp(38)).apply { marginEnd = act.dp(10) })
        }
        chipScroll.visibility = if (data.seasons.size > 1) View.VISIBLE else View.GONE
        epsView.visibility = View.VISIBLE
        epsView.alpha = 0f; epsView.animate().alpha(1f).setDuration(160).start()
        fillSeason(data.current, focusCard = true)
    }

    private fun styleChips() {
        for (i in 0 until chipRow.childCount) {
            val c = chipRow.getChildAt(i) as TextView
            val sel = c.tag == epsSeason
            val f = c.isFocused
            c.background = rounded(if (f) 0xFFFFFFFF.toInt() else if (sel) 0x40FFFFFF else 0x14FFFFFF, act.dpf(19f))
            c.setTextColor(if (f) 0xFF111214.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun fillSeason(sn: Int, focusCard: Boolean) {
        val data = epsData ?: return
        epsSeason = sn
        styleChips()
        cardRow.removeAllViews()
        val w = act.dp(250); val ih = w * 9 / 16
        var cur: View? = null
        for (e in data.bySeason(sn)) {
            val img = android.widget.ImageView(act).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFF2A2D33.toInt(), act.dpf(6f)); clipToOutline = true
                load(e.image?.ifEmpty { null }) { size(400, 225) }
            }
            val frame = FrameLayout(act).apply {
                setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(0x00000000, act.dpf(9f), 0xFFFFFFFF.toInt(), act.dp(3)))
                    addState(intArrayOf(), rounded(0x00000000, act.dpf(9f)))
                }
                isDuplicateParentStateEnabled = true
                addView(img, LayoutParams(MATCH, MATCH))
                addView(FrameLayout(act).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000) }
                    addView(act.icon(R.drawable.ic_play, 0xFFFFFFFF.toInt(), 18), LayoutParams(act.dp(18), act.dp(18), Gravity.CENTER))
                }, LayoutParams(act.dp(40), act.dp(40), Gravity.CENTER))
                if (e.progress > 0.01f) addView(FrameLayout(act).apply {
                    setBackgroundColor(0x4DFFFFFF)
                    addView(View(act).apply { setBackgroundColor(0xFFE50914.toInt()) },
                        LayoutParams((w * e.progress.coerceIn(0f, 1f)).toInt(), MATCH, Gravity.START))
                }, LayoutParams(MATCH, act.dp(4), Gravity.BOTTOM).apply { setMargins(act.dp(3), 0, act.dp(3), act.dp(3)) })
            }
            val title = act.text(e.title, 15f, 0xFFFFFFFF.toInt(), bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(act.dp(3), act.dp(8), act.dp(3), 0)
            }
            val sub = act.text(e.sub, 12.5f, if (e.current) 0xFFE5484D.toInt() else 0xFFA8ADB5.toInt(), bold = e.current).apply {
                maxLines = 1; setPadding(act.dp(3), act.dp(2), act.dp(3), 0)
            }
            val plot = act.text(e.plot, 12.5f, 0xFFC9CDD3.toInt(), lines = 3).apply {
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(act.dp(3), act.dp(6), act.dp(3), 0)
                alpha = 0f; minLines = 3
            }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setTag(R.id.own_focus_anim, true)
                contentDescription = e.title
                addView(frame, LinearLayout.LayoutParams(w, ih))
                addView(title, LinearLayout.LayoutParams(w, WRAP))
                addView(sub, LinearLayout.LayoutParams(w, WRAP))
                addView(plot, LinearLayout.LayoutParams(w, WRAP))
                val click = e.onClick
                setOnClickListener { closeEpisodes(false); click() }
                setOnFocusChangeListener { v, has ->
                    v.raiseOnFocus(has)
                    v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(140).start()
                    plot.animate().alpha(if (has && e.plot.isNotBlank()) 1f else 0f).setDuration(160).start()
                }
            }
            cardRow.addView(cell, LinearLayout.LayoutParams(w, WRAP).apply { marginEnd = act.dp(18) })
            if (e.current) cur = cell
        }
        if (focusCard) (cur ?: cardRow.getChildAt(0))?.let { t -> t.post { t.requestFocusFromTouch() } }
    }

    /** يقفل قائمة الحلقات (رجوع). */
    fun closeEpisodes(showBar: Boolean = true) {
        if (!epsOpen) return
        epsOpen = false
        epsView.animate().cancel()
        epsView.visibility = View.GONE
        if (showBar) { show(); nfEps.requestFocusFromTouch() } else poke()
    }

    // ---------- "أنت تشاهد": وقفت الفيديو وتركته شوي ----------
    private var pausedAt = 0L
    private var pauseShown = false
    private val pWatch = act.text("أنت تشاهد", 15f, 0xFFB4B9C1.toInt())
    private val pTitle = act.text("", 34f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
    private val pSub = act.text("", 17f, 0xFFFFFFFF.toInt(), bold = true).apply { maxLines = 1; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
    private val pPlot = act.text("", 14.5f, 0xFFC9CDD3.toInt(), lines = 4).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setLineSpacing(0f, 1.3f) }
    private val pauseView = FrameLayout(act).apply {
        visibility = View.GONE
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0xEB000000.toInt(), 0xB3000000.toInt(), 0x59000000))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPaddingRelative(act.dp(64), 0, act.dp(24), 0)
            addView(pWatch)
            addView(pTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6) })
            addView(pSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6) })
            addView(pPlot, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(12) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(act.dp(20), 0, act.dp(20), 0)
                background = rounded(0xFFFFFFFF.toInt(), act.dpf(6f))
                addView(act.icon(R.drawable.ic_play, 0xFF111214.toInt(), 20), LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
                addView(act.text("كمّل", 16f, 0xFF111214.toInt(), bold = true).apply { setPaddingRelative(act.dp(8), 0, 0, 0) })
            }, LinearLayout.LayoutParams(WRAP, act.dp(44)).apply { topMargin = act.dp(22) })
            addView(act.text("متوقف مؤقتاً · أي زر يرجعك", 12.5f, 0xFF8A8F98.toInt()), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(12) })
        }
        addView(col, LayoutParams((act.resources.displayMetrics.widthPixels * 0.5f).toInt(), WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
    }

    fun isPauseShown() = pauseShown

    private fun updatePause(p: Player?) {
        val paused = p != null && !p.playWhenReady && p.playbackState == Player.STATE_READY
        if (!paused) { pausedAt = 0L; if (pauseShown) hidePause(); return }
        if (pauseShown || epsOpen || dragging) return
        val now = System.currentTimeMillis()
        if (pausedAt == 0L) { pausedAt = now; return }
        if (now - pausedAt >= 8000) showPause()
    }

    private fun showPause() {
        val (t, sub) = titleProvider?.invoke() ?: ("" to "")
        pTitle.text = t
        pSub.text = sub.lineSequence().firstOrNull().orEmpty(); pSub.visibility = if (pSub.text.isBlank()) View.GONE else View.VISIBLE
        val plot = plotProvider?.invoke().orEmpty()
        pPlot.text = plot; pPlot.visibility = if (plot.isBlank()) View.GONE else View.VISIBLE
        pauseShown = true
        removeCallbacks(hideRun)
        panel.animate().cancel(); hiding = false; panel.visibility = View.GONE; panel.alpha = 1f
        hideTip(); bubble.visibility = View.INVISIBLE
        onVisibility?.invoke(false)
        if (panel.hasFocus()) parkFocus()
        pauseView.visibility = View.VISIBLE
        pauseView.alpha = 0f; pauseView.animate().alpha(1f).setDuration(400).start()
    }

    /** يخفي شاشة "أنت تشاهد" (أي زر). */
    fun hidePause() {
        if (!pauseShown) return
        pauseShown = false
        pausedAt = if (player?.playWhenReady == false) System.currentTimeMillis() else 0L
        pauseView.animate().cancel()
        pauseView.visibility = View.GONE
    }

    // التلفزيون بملء الشاشة: يترتّب هنا بالآخر (بعد ما كل العناصر فوق تنعرّف)
    init { if (!yt && autoHide) arrange(false) }
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
