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
                Session.userInfo = withContext(Dispatchers.IO) { Session.api!!.login() }.optJSONObject("user_info")
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
            nav.setPadding(dp(12), dp(16), dp(12), dp(16))
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
        root.addView(nav, lp(MATCH, WRAP))
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

    private val RAIL_W = 76
    private var railOpen = false

    /** القائمة الجانبية: تتوسّع بأسماء الأقسام لما يوقف عليها الريموت، وترجع أيقونات لما يطلع منها. */
    private fun railExpand(open: Boolean) {
        if (open == railOpen) return
        railOpen = open
        navButtons.values.forEach { b -> b.findViewWithTag<View?>("tx")?.visibility = if (open) View.VISIBLE else View.GONE }
        nav.setBackgroundColor(if (open) 0xF20C0E11.toInt() else C.RAIL)
        nav.elevation = if (open) dp(12).toFloat() else 0f
    }

    private fun railButton(key: String, label: String, ic: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(48)).apply { bottomMargin = dp(6) }
        setPadding(dp(14), 0, dp(14), 0)
        minimumWidth = dp(52)
        background = focusBg(radius = 12)
        isFocusable = true
        isClickable = true
        contentDescription = label
        addView(icon(ic, C.MUTED, 24).apply { tag = "ic" })
        addView(text(label, 15f, C.MUTED, bold = true).apply { tag = "tx"; setPadding(dp(12), 0, dp(6), 0); visibility = View.GONE })
        setOnClickListener { show(key) }
        setOnFocusChangeListener { _, has ->
            if (has) railExpand(true) else nav.post { if (!nav.hasFocus()) railExpand(false) }
        }
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
        screens[current]?.onHide()
        current = key
        val s = screen(key)
        content.removeAllViews()
        (s.view.parent as? FrameLayout)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        s.view.fadeIn(dp(10))       // انتقال خفيف بين الأقسام
        navButtons.forEach { (k, b) ->
            val on = k == key
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
        val p = pendingOpen
        if (p != null && current != "") { pendingOpen = null; if (p != current) { openExtra(p); return } }
        screens[current]?.onShow()
    }

    override fun onPause() {
        super.onPause()
        screens[current]?.onHide()
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
