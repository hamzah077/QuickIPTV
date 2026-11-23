package com.urrox.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.load
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** شاشة داخل التطبيق (أخف من Fragments). */
interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
    fun onKey(keyCode: Int, event: KeyEvent): Boolean = false
    /** رجوع داخل الشاشة نفسها (مثل الرجوع من "الكل" لصفوف الأفلام). true = تعامل معه. */
    fun onBack(): Boolean = false
    fun focusDefault() { view.requestFocus() }
    /** الجوال انقلب (عمودي ↔ أفقي). */
    fun onConfig() {}
}

/**
 * الواجهة الرئيسية: على التلفزيون قائمة أيقونات جانبية، على الجوال شريط تحت.
 * الشاشات تنبني مرة وحدة وتبقى بالذاكرة (التنقل بينها فوري).
 */
class MainActivity : Activity() {
    val scope: CoroutineScope = MainScope()
    val tv by lazy { isTv() }
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val screens = HashMap<String, Screen>()
    private val navButtons = HashMap<String, View>()
    var current: String = ""
        private set

    private val sections get() = if (tv) listOf(
        Triple("home", "الرئيسية", R.drawable.ic_home),
        Triple("search", "بحث", R.drawable.ic_search),
        Triple("favorites", "المفضلة", R.drawable.ic_heart),
        Triple("live", "مباشر", R.drawable.ic_live),
        Triple("movie", "أفلام", R.drawable.ic_movie),
        Triple("series", "مسلسلات", R.drawable.ic_series),
        Triple("settings", "الإعدادات", R.drawable.ic_settings),
    ) else listOf(
        Triple("home", "الرئيسية", R.drawable.ic_home),
        Triple("live", "مباشر", R.drawable.ic_live),
        Triple("movie", "أفلام", R.drawable.ic_movie),
        Triple("series", "مسلسلات", R.drawable.ic_series),
        Triple("settings", "المزيد", R.drawable.ic_menu),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Glass.tv = isTv()
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        edgeToEdge()
        installFocusMotion()
        val accSaved = Store.current
        intent?.getStringExtra("open")?.let { pendingOpen = it }
        // إضافة حساب بالتشغيل (بدون كتابة بالريموت): am start ... -e server URL -e user U -e pass P
        val qs = intent?.getStringExtra("server"); val qu = intent?.getStringExtra("user"); val qp = intent?.getStringExtra("pass")
        val acc = if (!qs.isNullOrBlank() && !qu.isNullOrBlank() && !qp.isNullOrBlank()) {
            val srv = Xtream.normalizeServer(qs)
            val id = Store.accounts.firstOrNull { it.server == srv && it.username == qu }?.id ?: java.util.UUID.randomUUID().toString().take(10)
            Account(id, qu, srv, qu, qp).also { Store.saveAccount(it) }
        } else accSaved
        if (acc == null) showLogin() else enter(acc, checkLogin = true)
        Downloads.pump()        // يكمل التحميلات اللي ما خلصت
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("open")?.let { pendingOpen = it }
    }

    /** يغيّر شكل الواجهة (تلقائي/جوال/تلفزيون) ويعيد فتح الشاشة بالشكل الجديد. */
    fun setUiMode(mode: String) {
        Store.uiMode = mode
        Device.reset()
        recreate()
    }

    companion object {
        private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)

        /** شاشة نفتحها أول ما نرجع (مثلاً التحميلات من صفحة الفيلم أو الإشعار). */
        var pendingOpen: String? = null
    }

    fun showLogin(edit: Account? = null) {
        screens.values.forEach { it.onHide() }
        screens.clear(); navButtons.clear(); current = ""
        setContentView(LoginScreen(this, edit).view)        // الحواف والكيبورد تنحسب داخل الشاشة نفسها
    }

    /** يفتح الحساب فوراً من الكاش، ويتحقق من الاشتراك بالخلفية. */
    fun enter(acc: Account, checkLogin: Boolean) {
        Session.open(this, acc)
        buildShell()
        show("home")
        scope.launch {
            delay(2500)      // نخلي الواجهة تفتح وتستقر أول
            withContext(Bg.io) { Epg.load(Store.epgUrls, cacheDir) }
            screens.values.forEach { (it as? EpgAware)?.onEpgLoaded() }
        }
        // تجهيز مسبق: نقرا القوائم بالخلفية (بأولوية منخفضة) عشان المباشر والأفلام والمسلسلات تفتح فوراً
        // التحديث التلقائي (لو جا موعده) — وإلا نجهّز القوائم المحفوظة بالخلفية
        if (autoUpdateIfDue(acc)) Unit else scope.launch {
            delay(1500)
            val api = Session.api ?: return@launch
            withContext(Bg.io) {
                runCatching { api.liveCategories(); api.liveStreams() }
                runCatching { api.vodCategories(); api.vods(false) }
                runCatching { api.seriesCategories(); api.vods(true) }
            }
        }
        if (checkLogin) scope.launch {
            try {
                Session.userInfo = withContext(Dispatchers.IO) { (Session.api ?: error("لا يوجد حساب")).login() }.optJSONObject("user_info")
            } catch (e: Exception) {
                toast(e.message ?: "تعذر التحقق من الاشتراك")
            }
        }
    }

    private fun buildShell() {
        screens.clear(); navButtons.clear()
        content = FrameLayout(this)
        nav = LinearLayout(this).apply {
            orientation = if (tv) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(C.RAIL)
            gravity = Gravity.CENTER
        }
        if (tv) {
            // التلفزيون: قائمة جانبية فوق المحتوى — أيقونات بس، وتتوسّع بأسمائها لما توقف عليها (مثل Google TV)
            val root = FrameLayout(this).apply {
                setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL
                if (Glass.on) background = Glass.ambient()        // ليكود جلاس: ألوان ناعمة ورا الزجاج بكل الصفحات
            }
            nav.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // حافة الشاشة (يمين): مسافة أمان أكبر لأن بعض التلفزيونات تقص الأطراف — التحديد ما ينقص
            nav.setPaddingRelative(dp(26), dp(16), dp(10), dp(16))
            nav.clipToPadding = false
            // حواف آمنة: بعض التلفزيونات تقص أطراف الصورة
            // حواف آمنة (دليل Google: ~48 من الجنب و27 من فوق وتحت) — بعض التلفزيونات تقص أطراف الصورة
            content.setPadding(0, dp(27), dp(48), dp(27))
            // نمط نتفلكس: صورة كبيرة خلف كل شي (حتى خلف القائمة الجانبية) للعمل المحدد بالرئيسية، مع تظليل
            val bd = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
            backdrop = bd
            root.addView(bd, FrameLayout.LayoutParams(MATCH, MATCH))
            if (Glass.on) {
                // ليكود جلاس: نسخة ممَوّهة من الصورة فوقها (تطلع بتلاشي) — Blur خفيف بدون ما يثقّل
                val bl = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
                blurView = bl
                root.addView(bl, FrameLayout.LayoutParams(MATCH, MATCH))
                val cb: (android.graphics.Bitmap?) -> Unit = { b -> bl.setImageBitmap(b) }
                blurCb = cb; Glass.onBlurred = cb
                Glass.setBackdrop(Glass.ambient())       // من أول لحظة: الزجاج يعكس الألوان اللي ورا
            }
            val bg = C.BG and 0x00FFFFFF
            backdropScrim = FrameLayout(this).apply {
                alpha = 0f
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
                        intArrayOf(bg or 0xF5000000.toInt(), bg or 0xB3000000.toInt(), bg or 0x26000000))
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(bg or 0xFF000000.toInt(), bg or 0xE0000000.toInt(), bg))
                }, FrameLayout.LayoutParams(MATCH, (resources.displayMetrics.heightPixels * 0.62f).toInt(), Gravity.BOTTOM))
            }
            root.addView(backdropScrim, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH).apply { marginStart = dp(RAIL_W) })
            // القائمة مفتوحة: المحتوى يغمق وراها (نمط نتفلكس)
            railScrim = View(this).apply { setBackgroundColor(0x8C000000.toInt()); alpha = 0f; visibility = View.GONE }
            root.addView(railScrim, FrameLayout.LayoutParams(MATCH, MATCH))
            nav.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (Glass.on) {
                // ليكود جلاس: القائمة كبسولة زجاج عائمة بالنص
                // زجاج «مثلّج»: صورة ممَوّهة + طبقة غامقة كثيفة = ما يبان اللي وراها
                // (أ) شريط جانبي زي iOS 27: زجاج يبان وراه المحتوى ممَوّه، والحساب فوق
                nav.background = Glass.Pane(nav, dpf(30f), tint = 0x1FFFFFFF, frost = 0xA614161C.toInt())
                nav.setPaddingRelative(dp(8), dp(14), dp(8), dp(14))
                root.addView(nav, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
            } else root.addView(nav, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.START))
            root.padForSystemBars()
            if (Glass.on) {
                // فوق: دائرة بأول حرف من الحساب + اسمه (الاسم يطلع لما تنفتح القائمة)، وتحته خط
                val accName = Store.current?.name.orEmpty()
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(2), dp(6), dp(10))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                head.addView(text(accName.take(1).uppercase().ifEmpty { "؟" }, 16f, 0xFFA9C1FF.toInt(), bold = true).apply {
                    gravity = Gravity.CENTER; background = rounded(0xFF2B3A5C.toInt(), dpf(20f))
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                val names = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(12), 0, dp(6), 0)
                    addView(text(accName, 14.5f, C.TEXT, bold = true).apply { maxLines = 1 })
                    addView(text("الحساب", 11.5f, 0xFFC4C8D0.toInt()))
                }
                railHeadText = names
                head.addView(names)
                nav.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
                nav.addView(railDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
            }
            for ((key, label, ic) in sections) {
                val b = railButton(key, label, ic)
                navButtons[key] = b
                // الإعدادات منفصلة بخط فوقها (زي الشريط الجانبي في iOS 27)
                if (Glass.on && key == "settings") nav.addView(railDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })
                nav.addView(b)
            }
            setContentView(root)
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(content, lp(MATCH, 0, 1f))
        root.addView(nav, lp(MATCH, WRAP))
        // مكان الشريط السفلي: النافذة العائمة تكون فوقه
        nav.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2); v.getLocationOnScreen(loc); MiniDock.navTop = loc[1]
            placeDock(false)
        }
        // الشريط السفلي يمتد خلف أزرار الجوال (بنفس لونه) والأزرار نفسها فوقها
        nav.onSystemInsets { _, _, _, b -> nav.setPadding(0, 0, 0, b) }
        // المحتوى ما يدخل تحت الساعة ولا تحت نتوء الكاميرا
        root.padForSystemBars(bottom = false)
        for ((key, label, ic) in sections) {
            val b = tabButton(key, label, ic)
            navButtons[key] = b
            nav.addView(b)
        }
        // النافذة العائمة (زي يوتيوب) فوق كل شي
        val shell = FrameLayout(this)
        shell.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        buildDock(shell)
        setContentView(shell)
    }

    // ---------- المشغّل العائم (الجوال — زي يوتيوب الجديد) ----------
    // نافذة فيديو صغيرة بزوايا مدوّرة: ضغطة = زر إيقاف وإغلاق فوقها، ضغطة ثانية = تكبير للصفحة.
    // تسحبها لأي مكان وتلتصق بأقرب زاوية، وترميها على الحافة = تنخبى بلسان ويوقف الفيديو.
    private var dockBox: FrameLayout? = null
    private var dockPv: androidx.media3.ui.PlayerView? = null
    private var dockBtns: View? = null
    private var dockPlay: android.widget.ImageView? = null
    private var dockProg: View? = null
    private var dockHandle: FrameLayout? = null
    private var dockJob: kotlinx.coroutines.Job? = null
    private var btnsJob: kotlinx.coroutines.Job? = null
    private var dockCorner = 3            // 0 فوق يسار، 1 فوق يمين، 2 تحت يسار، 3 تحت يمين
    private var dockHidden = 0            // -1 يسار، 1 يمين
    private var pausedByHide = false

    private fun floatW(): Int = ((dockBox?.parent as? View)?.width ?: resources.displayMetrics.widthPixels) / 2
    private fun floatH(): Int = floatW() * 9 / 16

    /** مكان زاوية النافذة بالشاشة */
    private fun cornerXY(c: Int): Pair<Float, Float> {
        val w = (dockBox?.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        val x = if (c % 2 == 0) dp(10).toFloat() else (w - floatW() - dp(10)).toFloat()
        val navTop = if (MiniDock.navTop > 0) MiniDock.navTop else resources.displayMetrics.heightPixels - dp(70)
        val top = if (android.os.Build.VERSION.SDK_INT >= 23) window.decorView.rootWindowInsets?.systemWindowInsetTop ?: dp(24) else dp(24)
        val y = if (c < 2) (top + dp(8)).toFloat() else (navTop - dp(10) - floatH()).toFloat()
        return x to y
    }

    /** نضع النافذة بزاويتها (أو على الحافة لو مخبّية) — ونخبّر صفحة التشغيل وين تنزل بالضبط */
    private fun placeDock(animate: Boolean) {
        val box = dockBox ?: return
        val fw = floatW(); val fh = floatH()
        (box.layoutParams as FrameLayout.LayoutParams).let { lp -> if (lp.width != fw || lp.height != fh) { lp.width = fw; lp.height = fh; box.layoutParams = lp } }
        val (cx, cy) = cornerXY(dockCorner)
        MiniDock.floatX = cx; MiniDock.floatY = cy; MiniDock.floatW = fw.toFloat()
        val w = (box.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        val x = when (dockHidden) { -1 -> -fw - dp(6).toFloat(); 1 -> w + dp(6).toFloat(); else -> cx }
        if (animate) box.animate().translationX(x).translationY(cy).setDuration(340)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        else { box.translationX = x; box.translationY = cy }
        dockHandle?.let { hnd ->
            hnd.visibility = if (dockHidden != 0 && box.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            hnd.translationX = if (dockHidden < 0) 0f else (w - dp(24)).toFloat()
            hnd.translationY = cy + fh / 2f - dp(30)
            hnd.background = rounded(0xFF2A2D33.toInt(), 0f).also { d ->
                val r = dpf(12f)
                d.cornerRadii = if (dockHidden < 0) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            (hnd.getChildAt(0) as? android.widget.ImageView)?.setImageResource(if (dockHidden < 0) R.drawable.ic_chev_left else R.drawable.ic_chev_right)
        }
    }

    private fun buildDock(shell: FrameLayout) {
        val pv = (layoutInflater.inflate(R.layout.mini_player, null) as androidx.media3.ui.PlayerView).apply {
            useController = false; isClickable = false; isFocusable = false
            setShutterBackgroundColor(0xFF000000.toInt())
        }
        val playBtn = iconButton(R.drawable.ic_pause, "إيقاف مؤقت", 40) {
            val p = MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null)
            p?.let { it.playWhenReady = !it.playWhenReady; syncDock(); flashBtns() }
        }.apply { background = rounded(0x80000000.toInt(), dpf(20f)) }
        val closeBtn = iconButton(R.drawable.ic_close, "إغلاق", 40) { MiniDock.stop() }
            .apply { background = rounded(0x80000000.toInt(), dpf(20f)) }
        // الزرين ثابتين فوق الفيديو (زي يوتيوب)، والتظليل وراهم يختفي بعد شوي
        val scrim = View(this).apply { setBackgroundColor(0x52000000); alpha = 0f }
        val btns = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(playBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.LEFT).apply { setMargins(dp(4), dp(4), 0, 0) })
            addView(closeBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(4), dp(4), 0) })
        }
        val prog = View(this).apply { setBackgroundColor(C.LIVE) }
        val box = FrameLayout(this).apply {
            background = rounded(0xFF000000.toInt(), dpf(12f)); clipToOutline = true
            elevation = dp(10).toFloat()
            visibility = View.GONE
            addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(btns, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(prog, FrameLayout.LayoutParams(0, dp(2), Gravity.BOTTOM or Gravity.LEFT))
            // ضغطة = الأزرار (والثانية = تكبير). سحب = تحرّكها، وتلتصق بأقرب زاوية أو تنخبى على الحافة
            var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
            var lastX = 0f; var lastAt = 0L; var velX = 0f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = v.translationX; startY = v.translationY; moved = false
                        lastX = e.rawX; lastAt = e.eventTime; velX = 0f; v.animate().cancel()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (!moved && kotlin.math.hypot(dx, dy) > dp(6)) moved = true
                        if (moved) {
                            val dt = (e.eventTime - lastAt).coerceAtLeast(1L)
                            velX = (e.rawX - lastX) / dt * 1000f; lastX = e.rawX; lastAt = e.eventTime
                            v.translationX = startX + dx; v.translationY = startY + dy
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (!moved) { if (e.actionMasked == android.view.MotionEvent.ACTION_UP) expandDock() }      // ضغطة = تكبير
                        else dropDock(v.translationX, v.translationY, velX)
                    }
                }
                true
            }
        }
        val handle = FrameLayout(this).apply {
            visibility = View.GONE; elevation = dp(10).toFloat(); isClickable = true
            addView(icon(R.drawable.ic_chev_left, C.TEXT, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { unhideDock() }
        }
        shell.addView(box, FrameLayout.LayoutParams(dp(180), dp(101), Gravity.TOP or Gravity.LEFT))
        shell.addView(handle, FrameLayout.LayoutParams(dp(24), dp(60), Gravity.TOP or Gravity.LEFT))
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        dockBox = box; dockPv = pv; dockBtns = scrim; dockPlay = playBtn.getChildAt(0) as? android.widget.ImageView; dockProg = prog; dockHandle = handle
        MiniDock.onChange = { refreshDock() }
    }

    /** التظليل يطلع لحظة (لما تظهر النافذة أو تضغط إيقاف) ويختفي بهدوء — الزرين يبقون */
    private fun flashBtns() {
        val sc = dockBtns ?: return
        sc.animate().cancel(); sc.alpha = 1f
        btnsJob?.cancel()
        btnsJob = scope.launch { delay(2500); sc.animate().alpha(0f).setDuration(450).start() }
    }

    /** تركت النافذة: رمية على الحافة = تنخبى، وإلا تلتصق بأقرب زاوية */
    private fun dropDock(x: Float, y: Float, velX: Float) {
        val fw = floatW()
        val w = (dockBox?.parent as? View)?.width ?: resources.displayMetrics.widthPixels
        when {
            x < -fw * 0.35f || velX < -dp(1200) -> hideDock(-1, y)
            x > w - fw * 0.65f || velX > dp(1200) -> hideDock(1, y)
            else -> {
                if (dockHidden != 0) unhideDock(false)
                dockCorner = (0..3).minByOrNull { c -> val (cx, cy) = cornerXY(c); kotlin.math.hypot(cx - x, cy - y) } ?: dockCorner
                placeDock(true)
            }
        }
    }

    private fun hideDock(side: Int, y: Float) {
        val top = y < (cornerXY(0).second + cornerXY(2).second) / 2
        dockCorner = if (side < 0) (if (top) 0 else 2) else (if (top) 1 else 3)
        dockHidden = side
        val p = MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null)
        if (p?.playWhenReady == true) { p.playWhenReady = false; pausedByHide = true }   // انخبت = يوقف (زي يوتيوب)
        placeDock(true)
    }

    private fun unhideDock(place: Boolean = true) {
        if (dockHidden == 0) return
        dockHidden = 0
        if (pausedByHide) { pausedByHide = false; (MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null))?.playWhenReady = true }
        if (place) placeDock(true)
    }

    /** يطلع/يخفي المشغّل العائم حسب اللي شغّال فيه. */
    private fun refreshDock() {
        val box = dockBox ?: return
        val p = if (tv) null else (MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null))
        if (p == null) {
            dockJob?.cancel(); dockJob = null
            dockPv?.player = null
            dockHandle?.visibility = View.GONE
            dockHidden = 0; pausedByHide = false
            if (box.visibility == View.VISIBLE) {
                box.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(160)
                    .withEndAction { box.visibility = View.GONE; box.scaleX = 1f; box.scaleY = 1f; box.alpha = 1f }.start()
            }
            return
        }
        if (dockPv?.player !== p) { dockPv?.player = null; dockPv?.attachSmooth(p) }
        if (MiniDock.instant) {
            // صفحة التشغيل صغرت لين صارت النافذة نفسها: تظهر بمكانها على طول (بدون حركة ثانية)
            MiniDock.instant = false
            box.animate().cancel(); box.alpha = 1f; box.scaleX = 1f; box.scaleY = 1f
            box.visibility = View.VISIBLE; placeDock(false)
        } else if (box.visibility != View.VISIBLE) {
            box.visibility = View.VISIBLE; placeDock(false)
            box.alpha = 0f; box.scaleX = 0.85f; box.scaleY = 0.85f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else placeDock(false)
        if (box.visibility == View.VISIBLE && (dockBtns?.alpha ?: 0f) == 0f && btnsJob == null) flashBtns()
        syncDock()
        dockJob?.cancel()
        dockJob = scope.launch {
            var n = 0
            while (true) { delay(500); syncDock(); if (++n % 20 == 0) MiniDock.save() }
        }
    }

    private fun syncDock() {
        val p = (MiniDock.player ?: if (MiniDock.live) LivePlayer.player else null) ?: return
        dockPlay?.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        val box = dockBox ?: return
        val d = p.duration
        val frac = if (!MiniDock.live && d > 0 && d != androidx.media3.common.C.TIME_UNSET) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
        dockProg?.let { v -> (v.layoutParams as FrameLayout.LayoutParams).let { lp -> val w = (box.width * frac).toInt(); if (lp.width != w) { lp.width = w; v.layoutParams = lp } } }
    }

    private var snapshotFresh = false

    /** تكبير النافذة: القناة ترجع لصفحة المباشر، والفيلم/الحلقة ترجع لصفحته بنفس المشغّل (تكبر من مكان النافذة). */
    private fun expandDock() {
        if (MiniDock.live) {
            if (current == "live") { MiniDock.undockLive(); screens["live"]?.onShow() } else show("live")
            return
        }
        val i = MiniDock.reopen ?: return
        startActivity(Intent(i).putExtra("fromDock", true).putExtra("fromT", 1f))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** طالع من صفحة المباشر (لقسم ثاني) والقناة شغّالة؟ تكمل في المشغّل الصغير. */
    private fun leaving(old: String, new: String) {
        if (tv || old != "live" || new == "live") return
        (screens["live"] as? LiveScreen)?.dockIfPlaying()
    }

    private val RAIL_W = 92
    private var railOpen = false
    private var backdrop: ImageView? = null
    private var backdropScrim: View? = null
    private var railScrim: View? = null
    private var backdropUrl: String? = null
    private var blurView: ImageView? = null
    private var railHeadText: View? = null
    /** خط فاصل بالقائمة: ما يفرض عرضه (View عادي ياخذ عرض الشاشة كلها = القائمة كانت تتمدّد بكامل الصفحة). */
    private fun railDivider() = object : View(this) {
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) MeasureSpec.getSize(w) else 0, dp(1))
        }
    }.apply { setBackgroundColor(0x2EFFFFFF) }
    private var blurCb: ((android.graphics.Bitmap?) -> Unit)? = null
    private var blurLevel = 0f

    /** ليكود جلاس: مقدار تمويه الخلفية (0 واضحة … 1 ممَوّهة). القائمة الجانبية المفتوحة = ممَوّهة كاملة. */
    fun setBlur(level: Float) {
        blurLevel = level
        val bl = blurView ?: return
        val v = if (railOpen) 1f else level
        bl.animate().cancel(); bl.animate().alpha(v * 0.85f).setDuration(260).start()
    }
    private var lastContentFocus: java.lang.ref.WeakReference<View>? = null
    private var bleed = false

    /**
     * التلفزيون: الصفحة تملى الشاشة كاملة (بدون القائمة الجانبية ولا الهوامش) — مثل المباشر «البث يملى الشاشة»
     * لما تخفي اللوحة. false = ترجع طبيعية.
     */
    fun setBleed(on: Boolean) {
        if (!tv || bleed == on) return
        bleed = on
        nav.visibility = if (on) View.GONE else View.VISIBLE
        if (on) railScrim?.visibility = View.GONE
        content.setPadding(0, if (on) 0 else dp(27), if (on) 0 else dp(48), if (on) 0 else dp(27))
        (content.layoutParams as? FrameLayout.LayoutParams)?.let { it.marginStart = if (on) 0 else dp(RAIL_W); content.layoutParams = it }
    }

    /** التلفزيون: الصورة الكبيرة خلف الشاشة (null = تختفي). تتبدّل بتلاشي ناعم. */
    fun setBackdrop(url: String?) {
        val bd = backdrop ?: return
        val u = url?.takeIf { it.isNotBlank() }
        if (u == backdropUrl) return
        backdropUrl = u
        if (u == null) {
            if (Glass.on) Glass.setBackdrop(Glass.ambient())      // بدون صورة: الزجاج يعكس الألوان الناعمة اللي ورا
            bd.animate().alpha(0f).setDuration(250).start()
            backdropScrim?.animate()?.alpha(0f)?.setDuration(250)?.start()
            return
        }
        val old = bd.drawable
        bd.load(u) {
            size(1280, 720); crossfade(350)
            placeholder(old); error(old)
            if (Glass.on) listener(onSuccess = { _, r -> Glass.setBackdrop(r.drawable) })
        }
        bd.animate().alpha(0.85f).setDuration(300).start()
        backdropScrim?.animate()?.alpha(1f)?.setDuration(300)?.start()
    }

    /** القائمة الجانبية: تتوسّع بأسماء الأقسام لما يوقف عليها الريموت، وترجع أيقونات لما يطلع منها. */
    private fun railExpand(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
        railHeadText?.let { h -> h.animate().cancel(); h.visibility = if (open) View.VISIBLE else View.GONE; h.alpha = if (open) 0f else 1f; if (open) h.animate().alpha(1f).setDuration(160).start() }
        navButtons.values.forEach { b ->
            val tx = b.findViewWithTag<View?>("tx") ?: return@forEach
            tx.animate().cancel()
            if (open) {
                tx.visibility = View.VISIBLE
                tx.alpha = 0f; tx.translationX = dp(12).toFloat()
                tx.animate().alpha(1f).translationX(0f).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                tx.visibility = View.GONE; tx.alpha = 1f; tx.translationX = 0f
            }
        }
        if (!Glass.on) nav.setBackgroundColor(if (open) 0xF20C0E11.toInt() else android.graphics.Color.TRANSPARENT)
        nav.elevation = if (open) dp(12).toFloat() else 0f
        if (Glass.on) {
            setBlur(blurLevel)
            // أندرويد 12+: المحتوى نفسه يتموّه وراء القائمة المفتوحة (Blur حقيقي)
            if (android.os.Build.VERSION.SDK_INT >= 31) content.setRenderEffect(
                if (open) android.graphics.RenderEffect.createBlurEffect(dpf(14f), dpf(14f), android.graphics.Shader.TileMode.CLAMP) else null)
        }
        railScrim?.let { sc ->
            sc.animate().cancel()
            if (open) { sc.visibility = View.VISIBLE; sc.animate().alpha(1f).setDuration(180).start() }
            else sc.animate().alpha(0f).setDuration(150).withEndAction { sc.visibility = View.GONE }.start()
        }
    }

    private fun railButton(key: String, label: String, ic: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // عرض القائمة (على قد أطول اسم) = كل الكبسولات نفس العرض
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(6) }
        setPadding(dp(14), 0, dp(14), 0)
        minimumWidth = dp(52)
        // التركيز: كبسولة بيضاء والأيقونة والكتابة داكنة (مثل Google TV) — بدون أزرق
        background = android.graphics.drawable.StateListDrawable().apply {
            setEnterFadeDuration(120); setExitFadeDuration(100)
            // ليكود جلاس (iOS 27): المحدد زجاج ساطع بحافة لامعة (مو أبيض)، والضغط يخففه لحظة
            // ليكود جلاس: مثل قوائم الآيفون — المحدد رمادي غامق شفاف (بدون أبيض)، والضغط أغمق لحظة
            if (Glass.on) addState(intArrayOf(android.R.attr.state_pressed), rounded(0x2EFFFFFF, dpf(24f)))
            addState(intArrayOf(android.R.attr.state_focused), if (Glass.on) rounded(0x47FFFFFF, dpf(24f)) else rounded(C.FOCUS, dpf(24f)))      // التنقل: أبيض شفاف خفيف
            // ليكود جلاس: القسم اللي أنت فيه كبسولة زجاج أوضح (بدل الكلام الأحمر)
            if (Glass.on) addState(intArrayOf(android.R.attr.state_selected), rounded(0x4D5A5A64, dpf(24f)))       // القسم الحالي: رمادي أخف
            addState(intArrayOf(), rounded(android.graphics.Color.TRANSPARENT, dpf(24f)))
        }
        isFocusable = true
        isClickable = true
        contentDescription = label
        addView(icon(ic, C.MUTED, 24).apply { tag = "ic" })
        addView(text(label, 15f, C.MUTED, bold = true).apply { tag = "tx"; setPadding(dp(12), 0, dp(6), 0); visibility = View.GONE })
        setOnClickListener { show(key) }
        setOnFocusChangeListener { v, has ->
            railTint(v, key, has)
            if (has) {
                // دخلت القائمة من المحتوى؟ نوقّفك على القسم اللي أنت فيه (مثل Google TV)
                if (!railOpen && key != current) navButtons[current]?.let { cur -> post { cur.requestFocus() } }
                railExpand(true)
            } else nav.post { if (!nav.hasFocus()) railExpand(false) }
        }
    }

    /** ألوان زر القائمة: مركّز = داكن على أبيض، القسم الحالي = أبيض، الباقي = رمادي. */
    private fun railTint(b: View, key: String, focused: Boolean = b.hasFocus()) {
        // القسم اللي أنت فيه: أحمر (نمط نتفلكس)، والمحدد داكن على أبيض
        val glass = Glass.on
        b.isSelected = glass && key == current
        val col = when {
            focused -> if (glass) 0xFFFFFFFF.toInt() else C.PRIMARY_TEXT
            key == current -> if (glass) C.TEXT else 0xFFE50914.toInt()
            else -> if (glass) 0xFFD3D7DF.toInt() else C.MUTED
        }
        b.findViewWithTag<android.widget.ImageView>("ic")?.imageTintList = android.content.res.ColorStateList.valueOf(col)
        b.findViewWithTag<android.widget.TextView>("tx")?.setTextColor(col)
    }

    private fun tabButton(key: String, label: String, ic: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = lp(0, dp(if (landscape) 50 else 58), 1f)
        isClickable = true
        background = focusBg(radius = 0, selected = 0)
        addView(icon(ic, C.MUTED, 22).apply { tag = "ic" })
        // اسم القسم: بعرض الخانة كامل وفي النص (بدون قص على الأطراف)
        addView(text(label, 10f, C.MUTED, lines = 0).apply {
            tag = "tx"; maxLines = 1; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
        }, lp(MATCH, WRAP))
        setOnClickListener { show(key) }
    }

    private fun screen(key: String): Screen = screens.getOrPut(key) {
        when (key) {
            "home" -> HomeScreen(this)
            "live" -> LiveScreen(this)
            "movie" -> LibraryScreen(this, series = false)
            "series" -> LibraryScreen(this, series = true)
            "search" -> SearchScreen(this)
            "favorites" -> FavoritesScreen(this)
            "history" -> HistoryScreen(this)
            "epg" -> EpgScreen(this)
            "downloads" -> DownloadsScreen(this)
            else -> SettingsScreen(this)
        }
    }

    fun show(key: String) {
        if (key == current) { screens[key]?.focusDefault(); return }
        leaving(current, key)
        screens[current]?.onHide()
        if (key == "live") MiniDock.undockLive()         // رجعت للمباشر: البث يرجع لشاشته
        current = key
        if (key != "home") setBackdrop(null)
        setBlur(0f)
        setBleed(false)
        val s = screen(key)
        content.removeAllViews()
        (s.view.parent as? FrameLayout)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        s.view.fadeIn(dp(10))       // انتقال خفيف بين الأقسام
        navButtons.forEach { (k, b) ->
            val on = k == key
            if (tv) { railTint(b, k); return@forEach }
            b.isSelected = on
            val col = if (on) C.ACCENT else C.MUTED
            b.findViewWithTag<android.widget.ImageView>("ic")?.imageTintList = android.content.res.ColorStateList.valueOf(col)
            b.findViewWithTag<android.widget.TextView>("tx")?.setTextColor(col)
        }
        s.onShow()
        s.view.post { s.focusDefault() }
    }

    /** تنبني الشاشة من جديد أول ما تفتحها (مثلاً تغيّر شكل صفحة المباشر من الإعدادات). */
    fun dropScreen(key: String) {
        if (key == current) return
        screens.remove(key)?.onHide()
    }

    /** يرجّع شاشة (مثل البحث أو المفضلة) من شاشة "المزيد" على الجوال. */
    fun openExtra(key: String) {
        leaving(current, key)
        screens[current]?.onHide()
        current = key
        if (key != "home") setBackdrop(null)
        setBlur(0f)
        setBleed(false)
        val s = screen(key)
        content.removeAllViews()
        (s.view.parent as? FrameLayout)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        s.view.fadeIn(dp(10))       // انتقال خفيف بين الأقسام
        s.onShow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !tv && Store.uiMode == "auto" && !Store.tvAsked && event.keyCode in DPAD_KEYS) {
            Store.tvAsked = true
            android.app.AlertDialog.Builder(this, dialogTheme)
                .setTitle("واجهة التلفزيون")
                .setMessage("شكلك تستخدم ريموت. تبغى واجهة التلفزيون؟ (خط أكبر وتنقّل بالأسهم)\nتقدر تغيّرها بعدين من الإعدادات ← شكل الواجهة.")
                .setPositiveButton("نعم") { _, _ -> setUiMode("tv") }
                .setNegativeButton("لا", null).show()
        }
        if (event.action == KeyEvent.ACTION_DOWN && screens[current]?.onKey(event.keyCode, event) == true) return true
        // التلفزيون: سهم يمين من آخر شي على اليمين بالصفحة = القائمة الجانبية على طول (مو ينزل تحت)
        if (tv && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !nav.hasFocus()) {
            val f = currentFocus
            if (f != null && TvHero.isIn(content, f) && !canGoRight(f)) { lastContentFocus = java.lang.ref.WeakReference(f); (navButtons[current] ?: nav).requestFocus(); return true }
        }
        // التلفزيون: من القائمة الجانبية يسار = تدخل القسم من أوله (أو من اللي اخترته فيه)
        if (tv && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && nav.hasFocus()) {
            // يرجعك لنفس المكان اللي طلعت منه للقائمة (لو لسه بنفس القسم)، وإلا أول القسم
            val back = lastContentFocus?.get()
            lastContentFocus = null
            if (back != null && back.isAttachedToWindow && back.isShown && TvHero.isIn(content, back) && back.requestFocus()) return true
            screens[current]?.focusDefault(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** فيه شي على يمين العنصر المحدد داخل الصفحة؟ (صف يقدر يتمرّر لليمين، أو عنصر فعلاً على يمينه) */
    private fun canGoRight(f: View): Boolean {
        var p: View? = f.parent as? View
        while (p != null && p !== content) {
            if ((p is androidx.recyclerview.widget.RecyclerView || p is android.widget.HorizontalScrollView) && p.canScrollHorizontally(1)) return true
            p = p.parent as? View
        }
        val next = android.view.FocusFinder.getInstance().findNextFocus(content, f, View.FOCUS_RIGHT) ?: return false
        val a = IntArray(2); val b = IntArray(2)
        f.getLocationOnScreen(a); next.getLocationOnScreen(b)
        return b[0] >= a[0] + f.width / 2
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            current == "" -> super.onBackPressed()
            current == "epg" || current == "history" || current == "downloads" || (!tv && (current == "search" || current == "favorites")) -> show("settings")
            screens[current]?.onBack() == true -> {}
            current != "home" -> show("home")
            else -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        screens.values.forEach { it.onConfig() }
        // ارتفاع الشريط السفلي يتغيّر مع الاتجاه
        if (!tv) navButtons.values.forEach { b -> (b.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = dp(if (landscape) 50 else 58); b.layoutParams = it } }
    }

    override fun onResume() {
        super.onResume()
        // ليكود جلاس: صفحة الفيلم غيّرت الصورة الممَوّهة — نرجّع صورة هالشاشة
        if (Glass.on) {
            val d = if (backdropUrl != null) backdrop?.drawable else null
            Glass.setBackdrop(d ?: Glass.ambient())
        }
        if (MiniDock.live) LivePlayer.player?.playWhenReady = true     // رجعت للتطبيق: البث الصغير يكمل
        refreshDock()
        val p = pendingOpen
        if (p != null && current != "") { pendingOpen = null; if (p != current) { openExtra(p); return } }
        screens[current]?.onShow()
    }

    override fun onPause() {
        super.onPause()
        // صورة للصفحة الحالية: تبان ورا صفحة التشغيل وهي تنسحب لتحت (لو أخذناها قبل السحب لفوق، نخليها)
        // (أندرويد 8.0 بس: صفحة الفيلم ما تقدر تكون شفافة — نحتاج صورة للصفحة اللي تحت)
        if (!tv && !snapshotFresh && android.os.Build.VERSION.SDK_INT == 26) PageSnapshot.take(this)
        snapshotFresh = false
        screens[current]?.onHide()
        // طلعت من التطبيق: المشغّل الصغير يوقف (ونحفظ وين وقفت)
        MiniDock.save()
        if (MiniDock.live) LivePlayer.player?.playWhenReady = false
        MiniDock.player?.playWhenReady = false
        dockJob?.cancel(); dockJob = null
    }

    override fun onDestroy() {
        // الصفحة انقفلت (مثلاً تغيير الثيم): ما نخلي الزجاج ماسك فيها (تسريب ذاكرة/رسم على صفحة ميتة)
        if (Glass.onBlurred === blurCb) Glass.onBlurred = null
        scope.cancel()
        super.onDestroy()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/** شاشات تتحدّث لما يخلص تحميل دليل البرامج. */
interface EpgAware {
    fun onEpgLoaded()
}
