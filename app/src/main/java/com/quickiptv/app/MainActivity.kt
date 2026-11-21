package com.quickiptv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
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
        setContentView(LoginScreen(this, edit).view.apply { padForSystemBars() })
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
        scope.launch {
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
            val root = FrameLayout(this).apply { setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
            nav.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            // حافة الشاشة (يمين): مسافة أمان أكبر لأن بعض التلفزيونات تقص الأطراف — التحديد ما ينقص
            nav.setPaddingRelative(dp(26), dp(16), dp(10), dp(16))
            nav.clipToPadding = false
            // حواف آمنة: بعض التلفزيونات تقص أطراف الصورة
            content.setPadding(0, dp(18), dp(28), dp(14))
            root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH).apply { marginStart = dp(RAIL_W) })
            root.addView(nav, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.START))
            root.padForSystemBars()
            for ((key, label, ic) in sections) {
                val b = railButton(key, label, ic)
                navButtons[key] = b
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
        root.addView(buildDock(), lp(MATCH, WRAP))           // المشغّل الصغير (مخفي لين تسحب مشغّل لتحت)
        root.addView(nav, lp(MATCH, WRAP))
        // مكان الشريط السفلي: صفحة التشغيل تنزل لين فوقه بالضبط (مكان المشغّل الصغير)
        nav.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2); v.getLocationOnScreen(loc); MiniDock.navTop = loc[1]
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
        setContentView(root)
    }

    // ---------- المشغّل الصغير تحت (الجوال — زي يوتيوب) ----------
    private var dockBox: FrameLayout? = null
    private var dockPv: androidx.media3.ui.PlayerView? = null
    private var dockTitle: android.widget.TextView? = null
    private var dockSub: android.widget.TextView? = null
    private var dockPlay: android.widget.ImageView? = null
    private var dockProg: View? = null
    private var dockJob: kotlinx.coroutines.Job? = null

    private fun buildDock(): View {
        val pv = (layoutInflater.inflate(R.layout.mini_player, null) as androidx.media3.ui.PlayerView).apply {
            useController = false; isClickable = false; isFocusable = false
            setShutterBackgroundColor(0xFF000000.toInt())
        }
        val vidBox = FrameLayout(this).apply {
            background = rounded(0xFF000000.toInt(), dpf(8f)); clipToOutline = true
            addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val t = text("", 13.5f, C.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        val st = text("", 11.5f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
            addView(t); addView(st)
        }
        val playBtn = iconButton(R.drawable.ic_pause, "إيقاف مؤقت", 44) {
            val p = MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null)
            p?.let { it.playWhenReady = !it.playWhenReady; syncDock() }
        }
        val closeBtn = iconButton(R.drawable.ic_close, "إغلاق", 44) { MiniDock.stop() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))
            addView(vidBox, LinearLayout.LayoutParams(dp(100), dp(56)))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(playBtn); addView(closeBtn)
        }
        val prog = View(this).apply { setBackgroundColor(C.LIVE) }
        val box = FrameLayout(this).apply {
            setBackgroundColor(C.PANEL)
            visibility = View.GONE
            addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(View(this@MainActivity).apply { setBackgroundColor(C.BORDER) }, FrameLayout.LayoutParams(MATCH, dp(1), Gravity.TOP))
            addView(prog, FrameLayout.LayoutParams(0, dp(2), Gravity.BOTTOM or Gravity.START))
            isClickable = true
            // ضغطة = يكبر. سحب لفوق = يكبر.
            var downY = 0f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; v.isPressed = true }
                    android.view.MotionEvent.ACTION_MOVE -> v.translationY = minOf(0f, e.rawY - downY) * 0.4f
                    android.view.MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        val dy = e.rawY - downY
                        v.animate().translationY(0f).setDuration(150).start()
                        if (dy < -dp(30) || kotlin.math.abs(dy) < dp(8)) expandDock()
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.animate().translationY(0f).setDuration(150).start() }
                }
                true
            }
        }
        dockBox = box; dockPv = pv; dockTitle = t; dockSub = st; dockPlay = playBtn.getChildAt(0) as? android.widget.ImageView; dockProg = prog
        MiniDock.onChange = { refreshDock() }
        return box
    }

    /** يطلع/يخفي المشغّل الصغير حسب اللي شغّال فيه. */
    private fun refreshDock() {
        val box = dockBox ?: return
        val p = if (tv) null else (MiniDock.player ?: (if (MiniDock.live) LivePlayer.player else null))
        if (p == null) {
            dockJob?.cancel(); dockJob = null
            dockPv?.player = null
            if (box.visibility == View.VISIBLE) {
                box.animate().translationY(box.height.toFloat()).alpha(0f).setDuration(180)
                    .withEndAction { box.visibility = View.GONE; box.translationY = 0f; box.alpha = 1f }.start()
            }
            return
        }
        dockTitle?.text = MiniDock.title
        dockSub?.text = MiniDock.sub
        dockSub?.visibility = if (MiniDock.sub.isBlank()) View.GONE else View.VISIBLE
        if (dockPv?.player !== p) { dockPv?.player = null; dockPv?.attachSmooth(p) }
        if (MiniDock.instant) {
            // الصفحة نزلت لين صارت هي المربع: يظهر بمكانه على طول (بدون حركة ثانية)
            MiniDock.instant = false
            box.animate().cancel(); box.alpha = 1f; box.translationY = 0f; box.visibility = View.VISIBLE
        } else if (box.visibility != View.VISIBLE) {
            box.visibility = View.VISIBLE
            box.alpha = 0f; box.translationY = dp(40).toFloat()
            box.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
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

    /** تكبير المشغّل الصغير: القناة ترجع لصفحة المباشر، والفيلم/الحلقة ترجع لصفحته بنفس المشغّل. */
    private fun expandDock() {
        if (MiniDock.live) {
            if (current == "live") { MiniDock.undockLive(); screens["live"]?.onShow() } else show("live")
            return
        }
        val i = MiniDock.reopen ?: return
        dockPv?.player = null
        dockBox?.visibility = View.INVISIBLE        // صفحة التشغيل تبدأ من مكانه بالضبط وتكبر
        startActivity(Intent(i).putExtra("fromDock", true))
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

    /** القائمة الجانبية: تتوسّع بأسماء الأقسام لما يوقف عليها الريموت، وترجع أيقونات لما يطلع منها. */
    private fun railExpand(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
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
        nav.setBackgroundColor(if (open) 0xF20C0E11.toInt() else C.RAIL)
        nav.elevation = if (open) dp(12).toFloat() else 0f
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
            addState(intArrayOf(android.R.attr.state_focused), rounded(C.FOCUS, dpf(24f)))
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
        val col = when { focused -> C.PRIMARY_TEXT; key == current -> C.TEXT; else -> C.MUTED }
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

    /** يرجّع شاشة (مثل البحث أو المفضلة) من شاشة "المزيد" على الجوال. */
    fun openExtra(key: String) {
        leaving(current, key)
        screens[current]?.onHide()
        current = key
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
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("واجهة التلفزيون")
                .setMessage("شكلك تستخدم ريموت. تبغى واجهة التلفزيون؟ (خط أكبر وتنقّل بالأسهم)\nتقدر تغيّرها بعدين من الإعدادات ← شكل الواجهة.")
                .setPositiveButton("نعم") { _, _ -> setUiMode("tv") }
                .setNegativeButton("لا", null).show()
        }
        if (event.action == KeyEvent.ACTION_DOWN && screens[current]?.onKey(event.keyCode, event) == true) return true
        return super.dispatchKeyEvent(event)
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
        if (MiniDock.live) LivePlayer.player?.playWhenReady = true     // رجعت للتطبيق: البث الصغير يكمل
        refreshDock()
        val p = pendingOpen
        if (p != null && current != "") { pendingOpen = null; if (p != current) { openExtra(p); return } }
        screens[current]?.onShow()
    }

    override fun onPause() {
        super.onPause()
        // صورة للصفحة الحالية: تبان ورا صفحة التشغيل وهي تنسحب لتحت
        if (!tv) PageSnapshot.take(window.decorView)
        screens[current]?.onHide()
        // طلعت من التطبيق: المشغّل الصغير يوقف (ونحفظ وين وقفت)
        MiniDock.save()
        if (MiniDock.live) LivePlayer.player?.playWhenReady = false
        MiniDock.player?.playWhenReady = false
        dockJob?.cancel(); dockJob = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

/** شاشات تتحدّث لما يخلص تحميل دليل البرامج. */
interface EpgAware {
    fun onEpgLoaded()
}
