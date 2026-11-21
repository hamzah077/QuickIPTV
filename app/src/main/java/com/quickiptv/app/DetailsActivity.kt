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
        // الجوال: ورا الصفحة صورة الصفحة اللي قبل — تبان وأنت تسحب الصفحة لتحت (زي يوتيوب)
        if (!isTv()) {
            // الصفحة شفافة من ورا (DetailsTheme): الصفحة الحقيقية اللي تحت تبان وأنت تسحب — إلا أندرويد 8.0 (صورة لها)
            stage = FrameLayout(this).apply { if (!seeThrough) setBackgroundColor(C.BG) }
            snapView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
            shade = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; visibility = View.GONE }
            stage!!.addView(snapView, FrameLayout.LayoutParams(MATCH, MATCH))
            stage!!.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
            stage!!.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            morphTitle = text("", 13.5f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
            morphSub = text("", 11.5f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
            morphInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f; visibility = View.GONE
                addView(morphTitle); addView(morphSub)
            }
            stage!!.addView(morphInfo, FrameLayout.LayoutParams(MATCH, dp(68)))
            setContentView(stage)
        } else setContentView(root)
        root.onSystemInsets { l, t, r, b ->
            if (l != inL || t != inT || r != inR || b != inB) { inL = l; inT = t; inR = r; inB = b; render() }
        }
        hist = Store.history().associateBy { it.key }
        render()
        // جاي من المشغّل الصغير (كبّرته): نفس المشغّل يرجع هنا ويكمل بدون إعادة تحميل
        if (intent.getBooleanExtra("fromDock", false)) {
            adoptDock()
            // تبدأ من مكان المشغّل الصغير وتكبر لصفحة كاملة (نفس الحركة بالعكس)
            if (stage != null && mini != null) {
                // تكمل من النقطة اللي وصل لها إصبعك وأنت تسحب المربع لفوق (أو من المربع نفسه لو ضغطته)
                val from = intent.getFloatExtra("fromT", 1f).coerceIn(0f, 1f)
                val dragging = intent.getBooleanExtra("dragging", false)
                // أول رسمة للصفحة تكون بنفس شكل المربع وهو مكبّر (بدون شاشة سودا ولا قفزة)، وبعدها تكمل
                root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    var tries = 0
                    override fun onPreDraw(): Boolean {
                        if ((root.width <= 0 || (miniBox?.width ?: 0) <= 0) && ++tries < 30) return false      // لسه ما انرسمت: ننتظر (ما نرسم الصفحة كاملة لحظة)
                        root.viewTreeObserver.removeOnPreDrawListener(this)
                        morphInfoText()
                        if (!dragging) { setMorph(from); root.post { animateMorph(0f) }; return true }
                        // إصبعك لسه على الشاشة: الصفحة تتبعه خطوة بخطوة، ولما تتركه تكمل
                        setMorph(MiniDock.dragT)
                        MiniDock.onDragMove = { t -> morphAnim?.cancel(); setMorph(t) }
                        MiniDock.onDragEnd = { t, v -> releaseDockDrag(t, v) }
                        MiniDock.pendingEnd?.let { (t, v) -> MiniDock.pendingEnd = null; releaseDockDrag(t, v) }
                        return true
                    }
                })
            }
        }

        if (Session.api == null) Store.current?.let { Session.open(this, it) }
        val api = Session.api ?: return finish()
        // "متابعة المشاهدة" من الرئيسية: يشتغل على طول من نفس المكان (عمودي = المشغّل الصغير)
        val autoKey = intent.getStringExtra("autoKey")
        intent.getStringExtra("autoUrl")?.let { u ->
            if (autoKey != null) play(u, autoKey, name, intent.getStringExtra("autoSub") ?: "", Store.resumePoint(autoKey), intent.getStringExtra("autoType") ?: "movie")
        }
        scope.launch {
            try {
                if (series) {
                    val (info, s) = withContext(Dispatchers.IO) { api.seriesInfo(id) }
                    plot = info.optString("plot")
                    kicker = meta(info.optString("releaseDate").take(4), info.optString("genre"), info.optString("rating"))
                    seasons = s
                    season = seriesResume()?.first?.season ?: s.keys.firstOrNull() ?: 1
                    // شغّال من "متابعة المشاهدة": نجهّز الحلقة السابقة/التالية وتعلّم المقدمة والتتر
                    if (autoKey != null) s.values.flatten().firstOrNull { "ep:${it.id}" == autoKey }?.let { e ->
                        SeriesQueue.set(id, name, s, e); season = e.season; updateMiniNav()
                    }
                    // "متابعة المشاهدة" وخلّصت الحلقة: نشغّل التالية على طول
                    if (intent.getBooleanExtra("autoResume", false) && mini == null) seriesResume()?.first?.let { e -> season = e.season; pendingPlay = e }
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
            pendingPlay?.let { pendingPlay = null; playEpisode(it) }
        }
    }

    private var pendingPlay: Episode? = null

    /** تبديل حلقة: نحدّث القائمة بس (التحديد والتقدّم) بدل ما نبني الصفحة كلها من جديد. */
    private fun refreshEpisodes(oldSeason: Int) {
        if (wide || epList == null || season != oldSeason) { render(); return }
        episodes.refreshRows()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ملء الشاشة بنفس الصفحة (زر التكبير/التصغير)
        if (fsPending == 1 && landscape) { fsEntered(); return }
        if (fsPending == -1 && !landscape) { fsExited(); return }
        // لفّيت الجوال بيدك وهو يشتغل: يدخل/يطلع من ملء الشاشة بدون حركتنا (أندرويد يلف)
        if (!isTv() && mini != null && landscape && !fs && !portraitLocked) {
            fs = true; immersive(true); miniBar?.fullscreenMode = true; render(); slotBack?.visibility = View.GONE; return
        }
        if (fs && !landscape) { fs = false; immersive(false); miniBar?.fullscreenMode = false; render(); return }
        if (mini != null && wide && System.currentTimeMillis() - resumedAt > 3000) goFull()      // (احتياط)
        else if (!wide && backToMini != null && System.currentTimeMillis() - resumedAt < 3000) { render(); resumeMini() }
        else render()
    }

    private fun meta(year: String, genre: String, rating: String, dur: String = ""): String =
        listOf(if (series) "مسلسل" else "فيلم", year, genre.split(",").firstOrNull()?.trim() ?: "",
            rating.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(java.util.Locale.US, it) } ?: "", dur.take(5))
            .filter { it.isNotEmpty() }.joinToString(" · ")

    // =====================================================================
    // البناء
    private val wide get() = isTv() || (landscape && !fs)

    // ---------- ملء الشاشة بنفس الصفحة (زي يوتيوب): نفس المشغّل ونفس الصورة، بدون صفحة جديدة ----------
    private var fs = false
    private var fsPending = 0            // 1 = داخل لملء الشاشة، -1 = طالع منه (ننتظر لفّ الشاشة)
    private var exitSign = 1f            // اتجاه اللفّة بالرجوع (حسب اتجاه الجوال الأفقي)

    private fun immersive(on: Boolean) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    /** يكبر ويلف (الحركة أ) ثم الشاشة تلف فوراً — والصورة أصلاً بمكانها النهائي = بدون قفزة. */
    private fun enterFs() {
        if (fs || animating || mini == null || isTv()) return
        val box = miniBox; val slot = topSlot
        fsPending = 1
        setRotationAnim(rotationJumpcut)
        if (box == null || slot == null || root.width == 0 || box.width == 0) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; return
        }
        animating = true
        val W = root.width.toFloat(); val H = root.height.toFloat()
        val bw = box.width.toFloat(); val bh = box.height.toFloat()
        val bl = IntArray(2); val rl = IntArray(2)
        box.getLocationInWindow(bl); root.getLocationInWindow(rl)
        val cy = bl[1] - rl[1] + bh / 2f
        val scale = minOf(H / bw, W / bh)        // بعد اللف: عرض الفيديو = عرض الشاشة الأفقي بالضبط
        root.clipChildren = false; slot.clipChildren = false
        val d = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        root.addView(d, FrameLayout.LayoutParams(MATCH, MATCH)); dim = d
        slot.bringToFront()
        miniBar?.visibility = View.INVISIBLE; slotBack?.visibility = View.INVISIBLE
        d.animate().alpha(1f).setDuration(320).start()
        box.animate().translationY(H / 2f - cy).rotation(90f).scaleX(scale).scaleY(scale)
            .setDuration(420).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
            .withEndAction {
                animating = false
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                fsTimeout()
            }.start()
    }

    /** يرجع عمودي: الشاشة تلف فوراً والفيديو يبدأ بنفس شكله الأفقي ثم يلف ويصغر لمكانه. */
    private fun exitFs() {
        if (!fs || animating) return
        @Suppress("DEPRECATION")
        exitSign = if (windowManager.defaultDisplay.rotation == android.view.Surface.ROTATION_270) -1f else 1f
        fsPending = -1
        setRotationAnim(rotationJumpcut)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        portraitLocked = true          // يبقى عمودي (حتى لو الجوال بيدك أفقي) لين تكبّر مرة ثانية أو تطلع
        fsTimeout()
    }

    /** لو لفّ الشاشة ما وصل (نادر): نكمل على كل حال. */
    private fun fsTimeout() {
        val want = fsPending
        root.postDelayed({ if (fsPending == want && want != 0) onConfigurationChanged(resources.configuration) }, 1500)
    }

    /** بعد ما الشاشة لفّت أفقي: الفيديو يملى الشاشة بدون حركة (هو أصلاً بنفس الشكل). */
    private fun fsEntered() {
        fsPending = 0
        fs = true
        immersive(true)
        miniBar?.fullscreenMode = true
        render()
        val box = miniBox ?: return
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                resetBox(box); slotBack?.visibility = View.GONE
                setRotationAnim(rotationJumpcut)
                return true
            }
        })
        // بعد شوي: نسمح يقلب للأفقي الثاني حسب مسكة الجوال
        root.postDelayed({ if (fs) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }, 1500)
    }

    /** بعد ما الشاشة لفّت عمودي: نبدأ الفيديو بشكله الأفقي ثم يلف ويصغر لمكانه (عكس الحركة). */
    private fun fsExited() {
        fsPending = 0
        fs = false
        immersive(false)
        miniBar?.fullscreenMode = false
        render()
        val box = miniBox ?: return
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                val slot = topSlot ?: return true
                val W = root.width.toFloat(); val H = root.height.toFloat()
                val bw = box.width.toFloat(); val bh = box.height.toFloat()
                if (W == 0f || bw == 0f) return true
                val bl = IntArray(2); val rl = IntArray(2)
                box.getLocationInWindow(bl); root.getLocationInWindow(rl)
                val cy = bl[1] - rl[1] - box.translationY + bh / 2f
                val scale = minOf(H / bw, W / bh)
                root.clipChildren = false; slot.clipChildren = false
                val d = dim?.takeIf { it.parent === root } ?: View(this@DetailsActivity).apply { setBackgroundColor(0xFF000000.toInt()) }.also {
                    root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)); dim = it
                }
                d.alpha = 1f
                slot.bringToFront()
                box.translationY = H / 2f - cy; box.rotation = 90f * exitSign; box.scaleX = scale; box.scaleY = scale
                miniBar?.visibility = View.INVISIBLE
                box.post { animateBack(box) }
                return true
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fs) immersive(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fs) { exitFs(); return }           // رجوع بملء الشاشة = تصغير (زي يوتيوب)
        if (!isTv() && !wide && mini != null) { if (morphAnim == null) collapseToDock(); return }     // رجوع وهو شغّال (الجوال) = الصفحة تنزل وتصير المشغّل الصغير
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // عمودي ووقت التشغيل: مكان ثابت فوق للمشغّل. ما ينبني من جديد ولا ينتقل (= بدون ومضة سودا)،
    // والقائمة تحته بس اللي تتحرك.
    private var topSlot: FrameLayout? = null
    private var slotBack: View? = null

    private fun render() {
        val focusTag = root.findFocus()?.tag as? String
        epList?.adapter = null          // القائمة القديمة تفك ارتباطها قبل ما نبني الجديدة
        val keep = if (!wide && (miniBox ?: handedBox) != null) topSlot else null
        for (i in root.childCount - 1 downTo 0) if (root.getChildAt(i) !== keep) root.removeViewAt(i)
        if (keep == null) { topSlot = null; slotBack = null }
        dlBtn = null; epList = null
        if (wide) renderWide() else renderTall()
        if (!wide) (miniBox ?: handedBox)?.let { b -> if (miniBox != null && b.rotation != 0f && !animating) b.post { animateBack(b) } }
        refreshDl()
        val target: View? = focusTag?.let { root.findViewWithTag<View?>(it) } ?: root.findViewWithTag<View?>("primary")
        target?.post { target.requestFocus() }
    }

    /** عمودي: صورة كبيرة فوق ثم كل شي تحت بعض. */
    private fun renderTall() {
        val heroH = resources.displayMetrics.widthPixels * 9 / 16 + inT
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mv = miniBox ?: handedBox
        val fixed = mv != null
        if (mv != null) {
            // المشغّل ثابت فوق (خارج القائمة): نفس المكان، ما نفكّه ولا ننقله
            val slot = topSlot ?: FrameLayout(this).also { s ->
                s.setBackgroundColor(0xFF000000.toInt())
                topSlot = s
                root.addView(s, 0, FrameLayout.LayoutParams(MATCH, heroH))
            }
            // ملء الشاشة: نفس المكان يكبر لكل الشاشة (بدون ما ننقل الصورة)
            val slotH = if (fs) MATCH else heroH
            val top = if (fs) 0 else inT
            (slot.layoutParams as FrameLayout.LayoutParams).let { if (it.height != slotH) { it.height = slotH; slot.layoutParams = it } }
            if (mv.parent !== slot) {
                (mv.parent as? ViewGroup)?.removeView(mv)
                slot.addView(mv, 0, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = top })
            } else (mv.layoutParams as FrameLayout.LayoutParams).let { if (it.topMargin != top) { it.topMargin = top; mv.layoutParams = it } }
            if (fs) slot.bringToFront()
            if (fs) slotBack?.visibility = View.GONE
            if (slotBack?.parent !== slot && !fs) {
                slotBack = backButton()
                slot.addView(slotBack, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { topMargin = inT + dp(6); marginStart = dp(10) + maxOf(inL, inR) })
            }
        } else {
            val hero = FrameLayout(this)
            hero.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f
                load(icon.ifEmpty { null }) { size(900, 520) }
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            hero.addView(backButton(), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { topMargin = inT + dp(6); marginStart = dp(10) + maxOf(inL, inR) })
            head.addView(hero, lp(MATCH, heroH))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16) + inL, dp(14), dp(16) + inR, dp(6)) }
        info(body, big = true)
        head.addView(body)

        if (series) {
            // المسلسل: الرأس + المواسم + الحلقات كلها في قائمة وحدة (ينرسم الظاهر بس)
            val chips = seasonChips()
            if (chips != null) head.addView(chips, lp(MATCH, WRAP).apply { setMargins(dp(16), dp(10), dp(16), dp(4)) })
            if (!loaded) head.addView(loadingView())
            episodes.header = head
            root.addView(episodeList().apply { setPadding(inL, 0, inR, inB + dp(20)) },
                FrameLayout.LayoutParams(MATCH, MATCH).apply { if (fixed) topMargin = heroH })
        } else {
            val sc = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
            head.setPadding(0, 0, 0, inB + dp(24))
            sc.addView(head)
            root.addView(sc, FrameLayout.LayoutParams(MATCH, MATCH).apply { if (fixed) topMargin = heroH })
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

    @Suppress("DEPRECATION")
    private fun backButton() = iconButton(R.drawable.ic_chev_right, "رجوع") { onBackPressed() }.apply {
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
            val playing = miniReq?.key == key
            val current = playing || (miniReq == null && key == lastEpKey)
            holder.row.tag = "ep:${e.id}"
            // الحلقة الحالية (شغّالة الحين أو آخر وحدة شفتها): خلفية مميّزة وإطار
            // الحلقة الحالية: رمادي خفيف بنفس ستايل التطبيق (والتحديد بالريموت يبقى تحديد التطبيق العادي)
            holder.row.background = if (current) android.graphics.drawable.LayerDrawable(arrayOf(
                focusBg(radius = 10, normal = 0x1FFFFFFF),
                rounded(0x00000000, dpf(10f), 0x38FFFFFF, dp(1))))
            else focusBg(radius = 10, normal = if (partial) C.ACCENT_SOFT else 0)
            holder.row.setOnClickListener { playEpisode(e) }
            holder.thumb.load((e.image.ifEmpty { icon }).ifEmpty { null }) { size(320, 180) }
            holder.title.text = "${e.num}. ${e.title.ifEmpty { "الحلقة ${e.num}" }}"
            holder.title.setTextColor(C.TEXT)
            holder.sub.text = when {
                playing -> "▶ تعرض الآن"
                current && partial -> "آخر حلقة شاهدتها · متبقي ${fmtTime(h!!.dur - h.pos)}"
                current -> "آخر حلقة شاهدتها"
                watched -> "✓ شاهدتها"
                partial -> "متبقي ${fmtTime(h!!.dur - h.pos)}"
                else -> e.duration
            }
            holder.sub.visibility = if (holder.sub.text.isEmpty()) View.GONE else View.VISIBLE
            holder.sub.setTextColor(if (current) 0xFFC9CDD3.toInt() else if (watched) 0xFF6FCF97.toInt() else C.MUTED)
            holder.bar.visibility = if (partial) View.VISIBLE else View.GONE
            if (partial) holder.bar.progress = (h!!.pos * 1000 / h.dur).toInt()
            holder.dl.tag = "epdl:${e.id}"
            holder.dl.setOnClickListener { episodeDlClick(e) }
            bindDl(holder.dl, e)
        }

        fun refreshDl() { if (items.isNotEmpty()) notifyItemRangeChanged(off, items.size, "dl") }
        fun refreshRows() { if (items.isNotEmpty()) notifyItemRangeChanged(off, items.size) }
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
        // وين وصلت (أبعد حلقة شاهدتها فعلاً) — مو آخر حلقة فتحتها بالغلط
        val last = Store.seriesFrontier(id) ?: return all.firstOrNull()?.let { it to "تشغيل · م${it.season} ح${it.num}" }
        val i = all.indexOfFirst { "ep:${it.id}" == last.key }
        if (i < 0) return all.firstOrNull()?.let { it to "تشغيل · م${it.season} ح${it.num}" }
        val done = last.dur > 0 && last.pos >= last.dur - 60
        val e = if (done) all.getOrNull(i + 1) ?: return null else all[i]
        return e to (if (done) "الحلقة التالية · م${e.season} ح${e.num}" else "متابعة · م${e.season} ح${e.num}")
    }

    private fun playEpisode(e: Episode) {
        SeriesQueue.set(id, name, seasons, e)          // للحلقة السابقة/التالية
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
        val r = VodReq(url, key, title, sub, type)
        clearBackJob?.cancel()
        if (MiniDock.active) MiniDock.stop()        // شي ثاني شغّال بالمشغّل الصغير؟ نقفله (السيرفر يسمح باتصال واحد غالباً)
        if (wide) { stopMini(); backToMini = r; openFull(r, start) } else startMini(r, start)
    }

    private fun openFull(r: VodReq, start: Long, handoff: Boolean = false) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", r.url).putExtra("key", r.key).putExtra("title", r.title).putExtra("sub", r.sub)
            .putExtra("image", icon).putExtra("type", r.type).putExtra("seriesId", if (series) id else "")
            .putExtra("start", start).putExtra("handoff", handoff))
    }

    // ---------- المشغّل المصغّر (الجوال عمودي) ----------
    private var mini: ExoPlayer? = null
    private var miniBox: FrameLayout? = null        // الصورة + شريط الأزرار
    private var miniPv: PlayerView? = null
    private var miniBar: PlayerBar? = null
    private var miniReq: VodReq? = null
    private var miniJob: Job? = null
    private var miniWatching = false
    private var backToMini: VodReq? = null      // رجعنا من ملء الشاشة: نكمل في المصغّر

    private fun startMini(r: VodReq, start: Long) {
        // المشغّل الصغير شغّال: نفس المشغّل يبدّل الحلقة على طول (بدون ما نقفله ونبني واحد جديد)
        mini?.let { p ->
            saveMini()
            Store.addHistory(HistoryItem(r.key, r.type, r.title, r.sub, icon, r.url, if (series) id else ""))
            val local = Downloads.localFile(r.key)
            if (local == null && !miniWatching) { Downloads.setWatching("vod", true); miniWatching = true }
            p.setMediaItem(MediaItem.fromUri(local?.let { android.net.Uri.fromFile(it).toString() } ?: r.url), start * 1000)
            p.prepare(); p.playWhenReady = true
            miniReq = r
            updateMiniNav()
            hist = Store.history().associateBy { it.key }
            val old = season
            SeriesQueue.list.getOrNull(SeriesQueue.indexOfKey(r.key))?.let { season = it.season }
            if (series) refreshEpisodes(old)
            return
        }
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        makeMini(p)
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
            if (state != Player.STATE_ENDED) return
            root.post {
                saveMini()
                // مسلسل: الحلقة التالية تشتغل لحالها
                val i = miniReq?.let { SeriesQueue.indexOfKey(it.key) } ?: -1
                if (i >= 0 && i < SeriesQueue.list.size - 1) { switchMini(i + 1); return@post }
                stopMini(); unlockPortrait(); hist = Store.history().associateBy { it.key }; render()
            }
        }
    }

    private suspend fun saveLoop() { while (true) { delay(10_000); saveMini() } }

    /** يبني الشاشة الصغيرة: الصورة + شريط الأزرار الموحّد. */
    private fun makeMini(p: ExoPlayer) {
        // TextureView: الصورة نفسها تكبر وتلف مع الحركة (SurfaceView ما يلف = تقطيع)
        val pv = (layoutInflater.inflate(R.layout.mini_player, root, false) as PlayerView).apply {
            useController = false                         // أزرارنا (PlayerBar) بدل أزرار المكتبة
            player = p
        }
        val bar = PlayerBar(this, live = false, autoHide = true).apply {
            player = p
            fullscreenMode = false
            onFullscreen = { if (fs) exitFs() else enterFs() }   // تكبير/تصغير بنفس الصفحة ونفس المشغّل (زي يوتيوب)
        }
        miniBox = DragDownFrame(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF000000.toInt()) }   // (زواياه تتقعّر وأنت تسحب)
            // الجوال عمودي: اسحب المشغّل لتحت = يكمل في المشغّل الصغير تحت وترجع للصفحة اللي قبل (زي يوتيوب)
            canDrag = { !isTv() && !fs && !wide && !animating && fsPending == 0 }
            onDrag = { dy, ended -> onPageDrag(dy, ended) }
            addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(bar, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        miniPv = pv; miniBar = bar
        updateMiniNav()
    }

    private fun updateMiniNav() {
        miniBar?.nav = miniReq?.let { r -> SeriesQueue.nav(r.key) { i -> switchMini(i) } }
    }

    /** الحلقة السابقة/التالية بنفس المشغّل الصغير. */
    private fun switchMini(i: Int) {
        val r = SeriesQueue.req(i) ?: return
        val p = mini ?: return
        saveMini()
        SeriesQueue.index = i
        Store.addHistory(HistoryItem(r.key, r.type, r.title, r.sub, icon, r.url, if (series) id else ""))
        val local = Downloads.localFile(r.key)
        if (local == null && !miniWatching) { Downloads.setWatching("vod", true); miniWatching = true }
        p.setMediaItem(MediaItem.fromUri(local?.let { android.net.Uri.fromFile(it).toString() } ?: r.url), Store.resumePoint(r.key) * 1000)
        p.prepare()
        p.playWhenReady = true
        miniReq = r
        updateMiniNav()
        val old = season
        SeriesQueue.list.getOrNull(i)?.let { season = it.season }
        hist = Store.history().associateBy { it.key }
        refreshEpisodes(old)
    }

    /** آخر حلقة شفتها من هالمسلسل (للتحديد لما ما فيه شي شغّال). */
    private val lastEpKey: String?
        get() = Store.seriesFrontier(id)?.key

    private var handedBox: FrameLayout? = null      // الشاشة الصغيرة وقت ما المشغّل بملء الشاشة
    private var handedPv: PlayerView? = null
    private var handedBar: PlayerBar? = null

    /** تكبير بدون انقطاع: نفس المشغّل (وهو شغّال) ينتقل لملء الشاشة — بدون إيقاف ولا إعادة تحميل. */
    // ---------- حركة التكبير/التصغير (أ): الفيديو يطلع من مكانه، يكبر ويلف ربع لفّة لين يملى الشاشة ----------
    private var animating = false
    private var dim: View? = null
    private val rotationJumpcut get() = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT

    private fun setRotationAnim(v: Int) {
        window.attributes = window.attributes.also { it.rotationAnimation = v }
    }

    private fun goFull(animate: Boolean = false) {
        if (animating) return
        val box = miniBox; val slot = topSlot
        if (!animate || wide || box == null || slot == null || root.width == 0 || box.width == 0) { doGoFull(); return }
        animating = true
        val W = root.width.toFloat(); val H = root.height.toFloat()
        val bw = box.width.toFloat(); val bh = box.height.toFloat()
        val bl = IntArray(2); val rl = IntArray(2)
        box.getLocationInWindow(bl); root.getLocationInWindow(rl)
        val cy = bl[1] - rl[1] + bh / 2f
        // بعد اللف: عرض الفيديو = عرض الشاشة الأفقي بالضبط (نفس شكله بملء الشاشة = بدون قفزة)
        val scale = minOf(H / bw, W / bh)
        root.clipChildren = false; slot.clipChildren = false
        val d = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        root.addView(d, FrameLayout.LayoutParams(MATCH, MATCH)); dim = d
        slot.bringToFront()
        miniBar?.visibility = View.INVISIBLE; slotBack?.visibility = View.INVISIBLE
        d.animate().alpha(1f).setDuration(380).start()
        box.animate().translationY(H / 2f - cy).rotation(90f).scaleX(scale).scaleY(scale)
            .setDuration(480).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
            .withEndAction { animating = false; doGoFull(); overridePendingTransition(0, 0) }
            .start()
        setRotationAnim(rotationJumpcut)       // لفّة أندرويد الافتراضية ما تطلع فوق حركتنا
    }

    /** رجعنا من ملء الشاشة: نفس الحركة بالعكس (يلف ويصغر لمكانه). */
    private fun animateBack(box: View) {
        if (animating) return
        val slot = topSlot ?: run { resetBox(box); return }
        animating = true
        root.clipChildren = false; slot.clipChildren = false
        val d = dim?.takeIf { it.parent === root } ?: View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 1f }.also {
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)); dim = it
        }
        slot.bringToFront()
        miniBar?.visibility = View.INVISIBLE; slotBack?.visibility = View.INVISIBLE
        d.animate().alpha(0f).setDuration(380).start()
        box.animate().translationY(0f).rotation(0f).scaleX(1f).scaleY(1f)
            .setDuration(440).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
            .withEndAction {
                animating = false
                resetBox(box)
            }.start()
    }

    private fun resetBox(box: View) {
        box.animate().cancel()
        box.translationY = 0f; box.rotation = 0f; box.scaleX = 1f; box.scaleY = 1f
        dim?.let { (it.parent as? ViewGroup)?.removeView(it) }; dim = null
        miniBar?.visibility = View.VISIBLE; slotBack?.visibility = View.VISIBLE
        root.clipChildren = true; topSlot?.clipChildren = true
        setRotationAnim(android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE)
    }

    private fun doGoFull() {
        val r = miniReq ?: return
        val p = mini ?: return
        clearBackJob?.cancel()
        unlockPortrait()
        saveMini()
        miniJob?.cancel(); miniJob = null
        p.removeListener(miniListener)
        VodHandoff.drop()                   // (أي بقايا قديمة)
        VodHandoff.player = p; VodHandoff.key = r.key; VodHandoff.req = r
        VodHandoff.watching = miniWatching; miniWatching = false
        VodHandoff.ownerAlive = true
        // الصورة تبقى هنا لين ملء الشاشة ياخذها (بدون فراغ)
        handedBox = miniBox; handedPv = miniPv; handedBar = miniBar
        miniBar?.player = null
        mini = null; miniReq = null; miniBox = null; miniPv = null; miniBar = null
        backToMini = r
        openFull(r, p.currentPosition / 1000, handoff = true)
    }

    /** رجعنا من ملء الشاشة: ناخذ نفس المشغّل ونكمل في الصغير بدون أي توقف (حتى لو تغيّرت الحلقة هناك). */
    private fun adoptBack(r: VodReq): Boolean {
        val req = VodHandoff.req ?: r
        val p = VodHandoff.take() ?: return false
        mini = p; miniReq = req
        miniWatching = VodHandoff.watching; VodHandoff.watching = false; VodHandoff.key = ""; VodHandoff.req = null
        p.addListener(miniListener)
        val box = handedBox; val pv = handedPv; val bar = handedBar
        handedBox = null; handedPv = null; handedBar = null
        if (box != null && pv != null && bar != null) {
            pv.detachPlayer(); pv.attachSmooth(p)  // نرجّع الصورة للشاشة الصغيرة (أول ما تجهز = بدون ومضة)
            bar.player = p
            miniBox = box; miniPv = pv; miniBar = bar
            updateMiniNav()
        } else makeMini(p)
        SeriesQueue.indexOfKey(req.key).takeIf { it >= 0 }?.let { SeriesQueue.index = it; season = SeriesQueue.list[it].season }
        p.playWhenReady = true
        miniJob = scope.launch { saveLoop() }
        return true
    }

    // ---------- سحب الصفحة كاملة لتحت ← تصير المشغّل الصغير (الجوال) ----------
    private var stage: FrameLayout? = null
    private var snapView: ImageView? = null
    private var shade: View? = null
    private var morphInfo: LinearLayout? = null
    private var morphTitle: android.widget.TextView? = null
    private var morphSub: android.widget.TextView? = null
    private var morphT = 0f
    /** النافذة شفافة (الصفحة اللي تحت تبان حقيقية) — كل الأجهزة إلا أندرويد 8.0 */
    private val seeThrough = android.os.Build.VERSION.SDK_INT != 26
    private var morphAnim: android.animation.ValueAnimator? = null

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun boxH(): Float = (if (MiniDock.dockH > 0) MiniDock.dockH else dp(68)).toFloat()

    /** أعلى المشغّل الصغير (فوق الشريط السفلي مباشرة) — بإحداثيات الصفحة نفسها */
    private fun miniTop(): Float {
        val h = root.height.toFloat()
        val loc = IntArray(2); stage?.getLocationOnScreen(loc)
        val navTop = if (MiniDock.navTop > 0) MiniDock.navTop.toFloat() - loc[1] else h - dp(58) - inB
        return navTop - boxH()
    }

    /** مكان النافذة العائمة بإحداثيات الصفحة: (x, y, العرض) */
    private fun floatTarget(): Triple<Float, Float, Float> {
        val w = root.width.toFloat()
        val loc = IntArray(2); stage?.getLocationOnScreen(loc)
        val fw = if (MiniDock.floatW > 0f) MiniDock.floatW else w / 2f
        val fh = fw * 9f / 16f
        val x = if (MiniDock.floatX >= 0f) MiniDock.floatX - loc[0] else w - fw - dp(10)
        val navTop = if (MiniDock.navTop > 0) MiniDock.navTop.toFloat() - loc[1] else root.height - dp(58f.toInt()) - inB.toFloat()
        val y = if (MiniDock.floatY >= 0f) MiniDock.floatY - loc[1] else navTop - dp(10) - fh
        return Triple(x, y, fw)
    }

    /** t = 0 صفحة كاملة … 1 النافذة العائمة (زي يوتيوب): الفيديو يصغر لمكان النافذة، والصفحة تنزل وتتلاشى. */
    private fun setMorph(t0: Float) {
        val t = t0.coerceIn(0f, 1f)
        morphT = t
        val w = root.width.toFloat()
        if (w <= 0f) return
        val (tx, ty, fw) = floatTarget()
        miniBox?.let { b ->
            val vw = b.width.toFloat()
            if (vw > 0f) {
                val s = lerp(1f, fw / vw, t)
                b.pivotX = 0f; b.pivotY = 0f; b.scaleX = s; b.scaleY = s
                b.translationX = lerp(0f, tx, t)
                b.translationY = lerp(0f, ty - inT, t)
                // الزوايا تتقعّر من أول ما تسحب (مقسومة على التصغير عشان تطلع بنفس المقاس على الشاشة)
                val r = minOf(dp(14).toFloat(), t * dp(120)) / s
                (b.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = r
                b.clipToOutline = r > 0f
                b.invalidateOutline()
                b.elevation = if (t >= 0.999f) dp(10).toFloat() else 0f        // الظل بس آخر الحركة (المتحرك يثقّل)
            }
        }
        miniBar?.alpha = (1f - t * 4f).coerceAtLeast(0f)
        slotBack?.alpha = (1f - t * 4f).coerceAtLeast(0f)
        topSlot?.setBackgroundColor(if (t > 0f) 0 else 0xFF000000.toInt())
        topSlot?.clipChildren = t == 0f
        root.clipChildren = t == 0f
        // باقي الصفحة (التفاصيل والحلقات): تنزل وتتلاشى — كطبقة جاهزة على كرت الشاشة (أخف وأنعم)
        val fade = (1f - t * 1.4f).coerceIn(0f, 1f)
        val layer = if (t > 0f && t < 1f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c === topSlot) continue
            if (c.layerType != layer) c.setLayerType(layer, null)
            c.alpha = fade; c.translationY = t * dp(220)
        }
        rootBg.alpha = (fade * 255).toInt()
        if (root.background !== rootBg) root.background = rootBg
        val on = t > 0f
        if (!seeThrough) snapView?.let { sv ->
            if (on && sv.visibility != View.VISIBLE) { sv.setImageBitmap(PageSnapshot.bitmap); sv.visibility = View.VISIBLE }
            if (!on) sv.visibility = View.GONE
        }
        shade?.let { it.visibility = if (on) View.VISIBLE else View.GONE; it.alpha = (1f - t) * 0.7f }
        morphInfo?.visibility = View.GONE
    }

    private val rootBg = android.graphics.drawable.ColorDrawable(C.BG)

    private fun animateMorph(to: Float, end: (() -> Unit)? = null) {
        morphAnim?.cancel()
        morphAnim = android.animation.ValueAnimator.ofFloat(morphT, to).apply {
            // منحنى ناعم (يبدأ بسرعة ويهدى بالتدريج) ومدة كافية عشان تبان سلسة
            duration = (300 + 180 * kotlin.math.abs(to - morphT)).toLong()
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { setMorph(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { morphAnim = null; end?.invoke() }
            })
            start()
        }
    }

    /** تركت إصبعك وأنت تسحب المربع لفوق: كفاية (أو سحبة سريعة) = صفحة كاملة، وإلا يرجع مربع صغير. */
    private fun releaseDockDrag(t: Float, vel: Float) {
        MiniDock.onDragMove = null; MiniDock.onDragEnd = null
        setMorph(t)
        if (t < 0.7f || vel < -dp(900)) animateMorph(0f) else collapseToDock()
    }

    /** سحب من المشغّل: الصفحة تتبع إصبعك، ولما تترك: ثلث المسافة يكفي عشان تصير مشغّل صغير. */
    private fun onPageDrag(dy: Float, ended: Boolean) {
        val range = (floatTarget().second - inT).coerceAtLeast(dp(200).toFloat())
        val t = (dy / range).coerceIn(0f, 1f)
        if (!ended) { morphAnim?.cancel(); morphInfoText(); setMorph(t); return }
        if (t > 0.3f) collapseToDock() else animateMorph(0f)
    }

    private fun morphInfoText() {
        val r = miniReq ?: return
        morphTitle?.text = SeriesQueue.name.takeIf { r.type == "episode" && it.isNotEmpty() } ?: r.title
        morphSub?.text = r.sub
        morphSub?.visibility = if (r.sub.isBlank()) View.GONE else View.VISIBLE
    }

    /** الصفحة كلها تنزل وتصير المربع الصغير، وبعدها المشغّل نفسه ينتقل له (بدون توقف). */
    private fun collapseToDock() {
        if (stage == null || mini == null) { dock(); return }
        morphInfoText()
        animateMorph(1f) { dock(instant = true) }
    }

    /** يسلّم المشغّل للمشغّل الصغير تحت (الجوال) ويرجع للصفحة اللي قبل — الفيديو يكمل بدون توقف. */
    private fun dock(instant: Boolean = false) {
        val p = mini ?: return
        val r = miniReq ?: return
        saveMini()
        miniJob?.cancel(); miniJob = null
        p.removeListener(miniListener)
        miniBar?.player = null
        miniPv?.detachPlayer()
        val back = Intent(intent).apply {
            listOf("autoUrl", "autoKey", "autoSub", "autoType", "autoResume", "fromDock", "fromT", "dragging").forEach { removeExtra(it) }
        }
        MiniDock.handVod(p, r, miniWatching, back)
        miniWatching = false
        mini = null; miniReq = null
        (miniBox?.parent as? ViewGroup)?.removeView(miniBox)
        miniBox = null; miniPv = null; miniBar = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unlockPortrait()
        MiniDock.instant = instant
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, if (instant) 0 else R.anim.slide_down_out)
    }

    /** كبّرت المشغّل الصغير: ناخذ نفس المشغّل ونكمل هنا. */
    private fun adoptDock() {
        val (p, r, w) = MiniDock.takeVod() ?: return
        mini = p; miniReq = r; miniWatching = w
        p.addListener(miniListener)
        makeMini(p)
        SeriesQueue.indexOfKey(r.key).takeIf { it >= 0 }?.let { SeriesQueue.index = it; season = SeriesQueue.list[it].season }
        p.playWhenReady = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        miniJob = scope.launch { saveLoop() }
        hist = Store.history().associateBy { it.key }
        render()
    }

    private fun saveMini() {
        val p = mini ?: return; val r = miniReq ?: return
        val d = p.duration
        if (d <= 0 || d == androidx.media3.common.C.TIME_UNSET) return
        Store.saveProgress(r.key, p.currentPosition / 1000, d / 1000)
    }

    private fun stopMini() {
        miniBox?.let { if (it.rotation != 0f || dim != null) resetBox(it) }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (fs || fsPending != 0) {
            fs = false; fsPending = 0; immersive(false)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // خلص وهو بملء الشاشة: نرجع عادي
        }
        miniJob?.cancel(); miniJob = null
        miniBar?.player = null
        miniPv?.detachPlayer()
        miniBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        miniBox = null; miniPv = null; miniBar = null
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
        val wasPaused = paused
        paused = false
        // رجعنا من ملء الشاشة والجوال عمودي: نكمل من نفس المكان في المصغّر
        // (لو الشاشة لسا أفقي لحظة الرجوع، نكمل أول ما ترجع عمودي)
        resumedAt = System.currentTimeMillis()
        // زر التصغير: نثبّت الصفحة عمودي (حتى لو الجوال أفقي) لين يخلص المشغّل المصغّر أو تطلع
        if (VodHandoff.toPortrait) {
            VodHandoff.toPortrait = false
            if (!isTv()) { requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; portraitLocked = true }
        }
        backToMini?.let { r ->
            if (adoptBack(r)) {
                backToMini = null
                hist = Store.history().associateBy { it.key }
                render()
                // رجعت والجوال أفقي فعلاً (مو بس تأخير لفّ الشاشة): نوقف المصغّر
                if (wide && !portraitLocked) scope.launch { delay(1500); if (wide && mini != null) { saveMini(); stopMini(); render() } }
                return
            }
        }
        // الشاشة الصغيرة اللي كانت تنتظر المشغّل (ما رجع: خلص أو انقفل): نشيلها
        handedBox?.let {
            handedPv?.detachPlayer(); handedBar?.player = null; handedBox = null; handedPv = null; handedBar = null
            dim = null; root.clipChildren = true
            setRotationAnim(android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE)
        }
        if (wasPaused) {
            // رجعنا من المشغّل: نحدّث التقدّم والزر الأساسي
            hist = Store.history().associateBy { it.key }
            render()
        }
        if (!wide) resumeMini()
        // ما قدرنا ناخذ المشغّل (الصفحة ما تعرضه): لا نخليه شغّال بالخلفية
        if (VodHandoff.player != null && mini == null && backToMini == null) VodHandoff.drop()
        // الرجوع ينتظر لفّ الشاشة 3 ثواني بس؛ بعدها ننساه (عشان ما يشتغل لحاله بعدين)
        clearBackJob?.cancel()
        if (backToMini != null) clearBackJob = scope.launch {
            delay(3000)
            backToMini = null
            if (mini == null && VodHandoff.player != null) VodHandoff.drop()
        }
    }

    private var clearBackJob: Job? = null

    private var resumedAt = 0L
    private var portraitLocked = false

    private fun unlockPortrait() {
        if (!portraitLocked) return
        portraitLocked = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // يرجع يلف مع الجوال
    }

    private fun resumeMini() {
        val r = backToMini ?: return
        backToMini = null
        val h = Store.history().firstOrNull { it.key == r.key }
        if (mini == null && h != null && !(h.dur > 0 && h.pos >= h.dur - 10)) startMini(r, h.pos)
    }

    override fun onDestroy() {
        MiniDock.onDragMove = null; MiniDock.onDragEnd = null
        saveMini(); stopMini()
        VodHandoff.ownerAlive = false
        if (VodHandoff.player != null && backToMini != null) VodHandoff.drop()
        handedPv?.detachPlayer(); handedBar?.player = null
        handedBox = null; handedPv = null; handedBar = null
        scope.cancel()
        super.onDestroy()
    }
}
