package com.urrox.player

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

fun MainActivity.field(hint: String, value: String = "", password: Boolean = false, rtl: Boolean = false): EditText = EditText(this).apply {
    typeface = Fonts.regular
    setText(value)
    this.hint = hint
    setHintTextColor(C.FAINT)
    setTextColor(C.TEXT)
    textSize = 15f
    isSingleLine = true
    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    textDirection = if (rtl) View.TEXT_DIRECTION_FIRST_STRONG else View.TEXT_DIRECTION_LTR
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = focusBg(normal = C.SURFACE, radius = 10)
    inputType = when {
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        rtl -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
}

fun MainActivity.sectionTitle(t: String): TextView = text(t, 17f, C.TEXT, bold = true).apply {
    setPadding(dp(4), dp(18), dp(4), dp(10))
}

/** صف أفقي من البوسترات (للرئيسية). */
fun MainActivity.posterRow(items: List<Vod>, posterW: Int, onClick: (Vod) -> Unit): RecyclerView =
    RecyclerView(this).apply {
        layoutManager = SmoothLM(this@posterRow, LinearLayoutManager.HORIZONTAL).apply { initialPrefetchItemCount = 5; alignStart = tv }
        setRecycledViewPool(posterPool(posterW))
        adapter = PosterAdapter(this@posterRow, posterW, onClick).also { it.items = items }
        setHasFixedSize(true)
        itemAnimator = null
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(if (tv) 24 else 12), dp(8), dp(if (tv) 24 else 12), dp(8))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        horizontalInVertical()
    }

fun MainActivity.openDetails(v: Vod) {
    startActivity(Intent(this, DetailsActivity::class.java)
        .putExtra("id", v.id).putExtra("series", v.isSeries).putExtra("name", v.name)
        .putExtra("icon", v.icon).putExtra("ext", v.ext))
}

fun MainActivity.playHistory(h: HistoryItem) {
    // خلّصت الحلقة اللي وصلت لها: نفتح المسلسل ونشغّل الحلقة التالية (عمودي = المشغّل الصغير، أفقي = ملء الشاشة)
    if (h.type == "episode" && h.finished && h.seriesId.isNotEmpty()) {
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra("id", h.seriesId).putExtra("series", true)
            .putExtra("name", h.title).putExtra("icon", h.image).putExtra("autoResume", true))
        return
    }
    // الجوال عمودي: يكمل في المشغّل الصغير عمودي (صفحة الفيلم/المسلسل). أفقي أو تلفزيون: ملء الشاشة أفقي.
    val portrait = !tv && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val detailsId = when (h.type) { "episode" -> h.seriesId; "movie" -> h.key.substringAfter("movie:", ""); else -> "" }
    if (portrait && detailsId.isNotEmpty() && h.url.isNotEmpty()) {
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra("id", detailsId).putExtra("series", h.type == "episode")
            .putExtra("name", h.title).putExtra("icon", h.image)
            .putExtra("ext", h.url.substringAfterLast('.', "mp4").substringBefore('?'))
            .putExtra("autoUrl", h.url).putExtra("autoKey", h.key).putExtra("autoSub", h.sub).putExtra("autoType", h.type))
        return
    }
    startActivity(Intent(this, PlayerActivity::class.java)
        .putExtra("url", h.url).putExtra("key", h.key).putExtra("title", h.title).putExtra("sub", h.sub)
        .putExtra("image", h.image).putExtra("type", h.type).putExtra("seriesId", h.seriesId)
        .putExtra("start", Store.resumePoint(h.key)))
}

// =====================================================================
/** إضافة/تعديل الحساب. يقبل لصق رابط الاشتراك كامل (get.php…). */
class LoginScreen(private val a: MainActivity, edit: Account?) : Screen {
    override val view: View
    // الترتيب: الاسم ← اسم المستخدم ← كلمة المرور ← عنوان السيرفر
    private val name = a.field("أي اسم تبيه (يظهر في الترحيب)", edit?.name ?: "", rtl = true)
    private val user = a.field("اسم المستخدم", edit?.username ?: "")
    private val pass = a.field("كلمة المرور", edit?.password ?: "", password = true)
    private val server = a.field("http://example.com:8080  أو الصق رابط الاشتراك كامل", edit?.server ?: "").apply {
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
    private val err = a.text("", 13f, C.LIVE, lines = 3)
    private val id = edit?.id ?: UUID.randomUUID().toString().take(10)

    init {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        col.addView(a.text("URROX PLAYER", 26f, C.TEXT, bold = true))
        val subtitle = a.text("أضف حساب Xtream Codes للبدء", 14f, C.MUTED).apply { setPadding(0, a.dp(6), 0, a.dp(20)) }
        col.addView(subtitle)
        // زر "التالي" بالكيبورد ينقلك للخانة اللي بعدها، وآخر خانة "دخول"
        listOf(name, user, pass).forEach { it.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        for ((label, f) in listOf("الاسم" to name, "اسم المستخدم" to user, "كلمة المرور" to pass, "عنوان السيرفر" to server)) {
            col.addView(a.text(label, 13f, C.MUTED).apply { setPadding(a.dp(4), 0, a.dp(4), a.dp(4)) })
            col.addView(f, lp(MATCH, WRAP).apply { bottomMargin = a.dp(12) })
        }
        server.setOnEditorActionListener { _, act, _ -> if (act == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        col.addView(err, lp(MATCH, WRAP).apply { bottomMargin = a.dp(8) })
        val ok = a.button("دخول", R.drawable.ic_play, primary = true) { submit() }
        col.addView(ok, lp(MATCH, WRAP))
        val others = Store.accounts.filter { it.id != id }
        if (others.isNotEmpty()) {
            col.addView(a.sectionTitle("حساباتك"))
            others.forEach { acc ->
                col.addView(a.button("${acc.name.ifEmpty { acc.username }}  ·  ${acc.server}") {
                    Store.currentId = acc.id; a.enter(acc, true)
                }, lp(MATCH, WRAP).apply { bottomMargin = a.dp(6) })
            }
        }
        val sc = ScrollView(a).apply { setBackgroundColor(C.BG); isFillViewport = true }
        val box = FrameLayout(a)
        box.addView(col, FrameLayout.LayoutParams(minOf(a.dp(520), a.resources.displayMetrics.widthPixels), WRAP, Gravity.CENTER))
        sc.addView(box)
        view = sc
        // الكيبورد: الصفحة ترتفع فوقه (نضيف مكانه تحت) والخانة اللي تكتب فيها تتمرّر لين تبان مع زر الدخول تحتها
        var kbOpen = false
        sc.setOnApplyWindowInsetsListener { v, ins ->
            val e = IntArray(5)       // يسار، فوق، يمين، تحت، الكيبورد
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                e[4] = ins.getInsets(android.view.WindowInsets.Type.ime()).bottom
                e[0] = bars.left; e[1] = bars.top; e[2] = bars.right; e[3] = maxOf(bars.bottom, e[4])
            } else {
                @Suppress("DEPRECATION") e[0] = ins.systemWindowInsetLeft
                @Suppress("DEPRECATION") e[1] = ins.systemWindowInsetTop
                @Suppress("DEPRECATION") e[2] = ins.systemWindowInsetRight
                @Suppress("DEPRECATION") e[3] = ins.systemWindowInsetBottom
                e[4] = if (e[3] > a.dp(120)) e[3] else 0
            }
            val ime = e[4]
            v.setPadding(e[0], e[1], e[2], e[3])
            val open = ime > a.dp(80)
            if (open != kbOpen) {
                kbOpen = open
                subtitle.visibility = if (open) View.GONE else View.VISIBLE      // مساحة أكثر وقت الكتابة
            }
            if (open) v.post { showFocused(sc) }
            ins
        }
        listOf(name, user, pass, server).forEach { f -> f.setOnFocusChangeListener { _, has -> if (has) sc.post { showFocused(sc) }; if (!has && f === server) splitLink() } }
    }

    /** الخانة اللي تكتب فيها (ومعها زر الدخول لو يتسع) تبان فوق الكيبورد */
    private fun showFocused(sc: ScrollView) {
        val f = sc.findFocus() ?: return
        val r = android.graphics.Rect(0, 0, f.width, f.height)
        sc.offsetDescendantRectToMyCoords(f, r)
        val visTop = sc.scrollY + sc.paddingTop
        val visBottom = sc.scrollY + sc.height - sc.paddingBottom
        val want = r.bottom + a.dp(if (f === server) 96 else 24)          // آخر خانة: نبيّن زر الدخول تحتها
        val dy = when {
            want > visBottom -> want - visBottom
            r.top < visTop -> r.top - visTop - a.dp(12)
            else -> 0
        }
        if (dy != 0) sc.smoothScrollBy(0, dy)
    }

    private fun splitLink() {
        Xtream.parseLink(server.text.toString())?.let { (s, u, p) ->
            server.setText(s); user.setText(u); pass.setText(p)
        }
    }

    private fun submit() {
        splitLink()
        val s = server.text.toString().trim()
        val u = user.text.toString().trim()
        val p = pass.text.toString()
        if (s.isEmpty() || u.isEmpty() || p.isEmpty()) { err.text = "أكمل رابط السيرفر واسم المستخدم وكلمة المرور"; return }
        val acc = Account(id, name.text.toString().trim().ifEmpty { u }, Xtream.normalizeServer(s), u, p)
        err.setTextColor(C.MUTED); err.text = "جاري التحقق…"
        a.scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Xtream(acc, a.cacheDir).login() }
                Store.saveAccount(acc)
                Session.userInfo = info.optJSONObject("user_info")
                Store.currentId = acc.id
                a.showFirstUpdate(acc)          // أول دخول: صفحة التحديث (المباشر ← الأفلام ← المسلسلات) ثم التطبيق
            } catch (e: Exception) {
                err.setTextColor(C.LIVE); err.text = e.message ?: "تعذر الدخول"
            }
        }
    }

    override fun focusDefault() { name.requestFocus() }
}

// =====================================================================
/** الرئيسية: متابعة المشاهدة، قنواتك المفضلة، أفلامك ومسلسلاتك المفضلة. بدون طلبات ثقيلة. */
class HomeScreen(private val a: MainActivity) : Screen, EpgAware {
    // التلفزيون: مسافة كبيرة تحت عشان آخر صف يقدر يطلع لفوق (كل صف تنزل له يصير أول الشاشة — نمط نتفلكس)
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(6), 0, a.dp(if (a.tv) 220 else 24)) }
    // التلفزيون: التمرير الرأسي كله منّا (الصف المحدد يطلع لأول الشاشة) — نوقف تمرير ScrollView التلقائي عشان ما يتعاركون
    private val scroll = object : ScrollView(a) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int =
            if (a.tv) 0 else super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }.apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false }
    private val tvRoot: LinearLayout? = if (a.tv) LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false; clipToPadding = false
    } else null
    override val view: View = tvRoot ?: scroll
    private var first: View? = null
    private val padH get() = a.dp(if (a.tv) 28 else 16)

    // القوائم (من الكاش) — نجيبها مرة وحدة
    private var live: List<Channel>? = null
    private var quickLive: List<Channel>? = null       // أول القنوات من الملخص الصغير (قبل ما تنقرا القائمة كاملة)
    private var loadingLists = false
    private var newMovies: List<Vod>? = null
    private var newSeries: List<Vod>? = null
    private var movieMap: Map<String, Vod> = emptyMap()
    private var seriesMap: Map<String, Vod> = emptyMap()
    private var liveSlot: LinearLayout? = null

    // الصفحة تنبني مرة وحدة، وكل قسم يتحدّث لحاله بس لو تغيّر محتواه (ما ترمش ولا تعيد تحميل الصور).
    private var shell = false
    private val sigs = HashMap<String, String>()
    private val contSlot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val movSlot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val serSlot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val favSlot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val spinner by lazy {
        android.widget.ProgressBar(a).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(C.ACCENT) }
    }

    override fun onShow() {
        heroBox?.restore()        // رجعت للرئيسية: صورة الخلفية ترجع
        refresh()
        if (live == null || movieMap.isEmpty() || seriesMap.isEmpty()) loadLists()
    }

    private fun refresh() {
        if (!shell) buildShell()
        val hist = Store.history()
        val cont = Store.continueWatching().ifEmpty { Store.displayHistory().filter { it.type != "live" } }.take(15)   // مسلسل = مدخل واحد
        slot("cont", cont.joinToString { "${it.key}:${it.pos / 30}" }, contSlot) { v ->
            if (cont.isNotEmpty()) {
                v.addView(header("متابعة المشاهدة", more = "السجل") { a.openExtra("history") })
                val (sc, row) = hRow()
                cont.forEach { row.addView(continueCard(it)) }
                v.addView(sc)
            }
        }
        slot("live", hist.filter { it.type == "live" }.take(16).joinToString { it.key } + "|" + Store.favs("live").joinToString() +
            "|" + (live?.size ?: -1) + "|" + (quickLive?.size ?: -1), liveSlot!!) { fillLive(it) }
        val w = a.dp(if (a.tv) 124 else 100)      // نفس عرض صفحة الأفلام = نفس الخانات المعاد استخدامها
        slot("mov", newMovies?.joinToString { it.id } ?: "", movSlot) { v ->
            newMovies?.takeIf { it.isNotEmpty() }?.let { l ->
                v.addView(header("أحدث الأفلام") { a.show("movie") }); v.addView(a.posterRow(l, w) { a.openDetails(it) })
            }
        }
        slot("ser", newSeries?.joinToString { it.id } ?: "", serSlot) { v ->
            newSeries?.takeIf { it.isNotEmpty() }?.let { l ->
                v.addView(header("أحدث المسلسلات") { a.show("series") }); v.addView(a.posterRow(l, w) { a.openDetails(it) })
            }
        }
        val favs = Store.favs("movie").mapNotNull { movieMap[it] } + Store.favs("series").mapNotNull { seriesMap[it] }
        slot("fav", favs.joinToString { it.id }, favSlot) { v ->
            if (favs.isNotEmpty()) {
                v.addView(header("مفضلتك", more = "الكل") { a.openExtra("favorites") }); v.addView(a.posterRow(favs.take(20), w) { a.openDetails(it) })
            }
        }
        spinner.visibility = if (newMovies == null && newSeries == null && quickLive == null && live == null) View.VISIBLE else View.GONE
        if (a.tv) pickFeatured(cont)
        if (a.current == "home" && a.currentFocus == null) focusDefault()
    }

    /** يعيد بناء قسم واحد بس لو تغيّرت بصمته. */
    private fun slot(name: String, sig: String, v: LinearLayout, fill: (LinearLayout) -> Unit) {
        if (sigs[name] == sig) return
        sigs[name] = sig
        v.removeAllViews()
        fill(v)
    }

    private fun buildShell() {
        shell = true
        col.removeAllViews()
        if (a.tv) {
            // التلفزيون (نتفلكس): بدون ترحيب — الإعلان الكبير فوق هو أول الشاشة
            val ls = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            liveSlot = ls
            for (v in listOf(contSlot, ls, movSlot, serSlot, favSlot)) col.addView(v, lp(MATCH, WRAP))
            col.addView(spinner, lp(a.dp(32), a.dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = a.dp(40) })
            return
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(padH, a.dp(8), a.dp(6), 0) }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val who = Store.current?.name?.trim().orEmpty()
        val hello = if (hour in 4..11) "صباح الخير" else "مساء الخير"
        head.addView(a.text(if (who.isEmpty()) hello else "$hello، $who", 21f, C.TEXT, bold = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(0, WRAP, 1f))
        if (!a.tv) head.addView(a.iconButton(R.drawable.ic_search, "بحث") { a.openExtra("search") })
        head.addView(a.iconButton(R.drawable.ic_download, "التحميلات") { a.openExtra("downloads") })
        col.addView(head)
        val ls = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        liveSlot = ls
        for (v in listOf(contSlot, ls, movSlot, serSlot, favSlot)) col.addView(v, lp(MATCH, WRAP))
        col.addView(spinner, lp(a.dp(32), a.dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = a.dp(40) })
    }

    override fun onEpgLoaded() { sigs.remove("live"); if (shell) refresh() }

    /**
     * 1) فوراً: الملخص الصغير (20 فيلم، 20 مسلسل، أول القنوات) — الرئيسية تفتح بدون ما نقرا القوائم الكاملة.
     * 2) بعدها بهدوء (بخيط منخفض الأولوية): القوائم الكاملة للمفضلة. أول مرة بس (ما فيه ملخص) نقراها على طول.
     */
    private fun loadLists() {
        val api = Session.api ?: return
        if (loadingLists) return
        loadingLists = true
        a.scope.launch {
            try {
                withContext(Bg.io) {
                    if (newMovies == null) newMovies = api.newest(false)
                    if (newSeries == null) newSeries = api.newest(true)
                    if (live == null && quickLive == null) quickLive = api.firstChannels()
                }
                if (a.current == "home") refresh()
                val firstRun = newMovies == null || newSeries == null || quickLive == null
                if (!firstRun) delay(4000)
                withContext(Bg.io) {
                    if (live == null) live = runCatching { api.liveStreams() }.getOrNull()
                    if (movieMap.isEmpty()) runCatching { api.vods(false) }.getOrNull()?.let { l ->
                        movieMap = l.associateBy { it.id }; newMovies = api.newest(false)
                    }
                    if (seriesMap.isEmpty()) runCatching { api.vods(true) }.getOrNull()?.let { l ->
                        seriesMap = l.associateBy { it.id }; newSeries = api.newest(true)
                    }
                }
                if (a.current == "home") refresh()
            } finally {
                loadingLists = false
            }
        }
    }

    private fun header(title: String, dot: Boolean = false, more: String = "الكل", onMore: (() -> Unit)? = null): View {
        val h = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(padH, a.dp(18), a.dp(8), a.dp(8)) }
        if (dot) h.addView(View(a).apply { background = rounded(C.LIVE, a.dpf(4f)) }, lp(a.dp(8), a.dp(8)).apply { marginEnd = a.dp(8) })
        h.addView(a.text(title, 16.5f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        if (onMore != null && !a.tv) h.addView(a.text("$more  ‹", 13f, C.ACCENT).apply {
            tag = "more"          // التلفزيون: الدخول من القائمة الجانبية يتخطّاه ويروح لأول عنصر
            setPadding(a.dp(10), a.dp(6), a.dp(10), a.dp(6)); background = a.focusBg(radius = 8)
            isFocusable = true; isClickable = true; setOnClickListener { onMore() }
        })
        return h
    }

    private fun hRow(): Pair<HorizontalScrollView, LinearLayout> {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(padH - a.dp(4), a.dp(6), padH - a.dp(4), a.dp(6)) }
        // التلفزيون (نتفلكس): المحدد يبقى أول الصف والصف يتحرك
        val sc = object : HorizontalScrollView(a) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect): Int {
                if (!a.tv) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
                return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) rect.right + a.dp(6) - (scrollX + width - paddingRight)
                else rect.left - a.dp(6) - (scrollX + paddingLeft)
            }
        }
        return sc.apply { addView(row); isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false } to row
    }

    /** المباشر: المفضلة ثم آخر القنوات اللي شاهدتها ثم أول قنوات القائمة. */
    private fun fillLive(slot: LinearLayout) {
        slot.removeAllViews()
        val all = live ?: quickLive ?: emptyList()
        val byId = HashMap<String, Channel>(all.size * 2).apply { all.forEach { put(it.id, it) } }
        // القنوات من السجل نعرفها حتى قبل ما تنقرا القائمة (الاسم والصورة محفوظة بالسجل)
        Store.history().filter { it.type == "live" }.forEach { h ->
            val cid = h.key.removePrefix("live:")
            if (cid !in byId) byId[cid] = Channel(cid, 0, h.title, h.image, "", "", false)
        }
        if (byId.isEmpty()) return
        val ids = LinkedHashSet<String>()
        Store.favs("live").forEach { ids.add(it) }
        Store.history().filter { it.type == "live" }.forEach { ids.add(it.key.removePrefix("live:")) }
        val picked = ids.mapNotNull { byId[it] }.toMutableList()
        if (picked.size < 12) all.asSequence().filter { it.id !in ids }.take(12 - picked.size).forEach { picked.add(it) }
        val list = picked.take(16)
        slot.addView(header("مباشر الحين", dot = true) { a.show("live") })
        val (sc, row) = hRow()
        list.forEachIndexed { i, ch -> row.addView(channelCard(ch, list, i)) }
        slot.addView(sc)
    }

    /** شريط تقدّم رفيع (وزن بدل حساب العرض = بدون إعادة رسم). */
    private fun progressLine(frac: Float, color: Int): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(C.SURFACE3, a.dpf(2f))
        val f = frac.coerceIn(0f, 1f)
        addView(View(a).apply { background = rounded(color, a.dpf(2f)) }, lp(0, MATCH, f))
        addView(View(a), lp(0, MATCH, 1f - f))
    }

    private fun continueCard(h: HistoryItem): View {
        val w = a.dp(if (a.tv) 230 else 172)
        val ih = w * 9 / 16
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(w + a.dp(8), WRAP)
            setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(6))
            background = a.focusBg(radius = 12)
            isFocusable = true; isClickable = true
            focusScale()
            setOnClickListener { a.playHistory(h) }
            setTag(R.id.hero_item, h)
        }
        if (first == null) first = card
        val box = FrameLayout(a).apply { background = rounded(C.SURFACE2, a.dpf(9f)); clipToOutline = true }
        box.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(h.image.ifEmpty { null }) { size(w, ih) }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(FrameLayout(a).apply {
            background = rounded(0xE6F1F3F7.toInt(), a.dpf(17f))
            addView(a.icon(R.drawable.ic_play, C.PRIMARY_TEXT, 16), FrameLayout.LayoutParams(a.dp(16), a.dp(16), Gravity.CENTER))
        }, FrameLayout.LayoutParams(a.dp(34), a.dp(34), Gravity.CENTER))
        if (h.dur > 0) box.addView(progressLine((h.pos.toFloat() / h.dur).coerceIn(0f, 1f), C.ACCENT),
            FrameLayout.LayoutParams(MATCH, a.dp(3), Gravity.BOTTOM))
        card.addView(box, lp(MATCH, ih))
        card.addView(a.text(h.title, 13.5f, C.TEXT, bold = true).apply { setPadding(a.dp(2), a.dp(7), a.dp(2), 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        val sub = listOfNotNull(
            h.sub.replace("الموسم ", "م").replace(" · الحلقة ", " ح").takeIf { it.isNotEmpty() },
            if (h.dur > 0 && h.pos < h.dur - 60) "متبقي ${fmtTime(h.dur - h.pos)}" else if (h.type == "episode" && h.finished) "الحلقة التالية" else null,
        ).joinToString(" · ")
        if (sub.isNotEmpty()) card.addView(a.text(sub, 11.5f, C.MUTED).apply { setPadding(a.dp(2), a.dp(2), a.dp(2), 0) })
        return card
    }

    private fun channelCard(ch: Channel, list: List<Channel>, i: Int): View {
        val w = a.dp(if (a.tv) 150 else 118)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(w, WRAP).apply { marginStart = a.dp(4); marginEnd = a.dp(4) }
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
            background = a.focusBg(normal = C.SURFACE, radius = 12)
            isFocusable = true; isClickable = true
            focusScale()
            setOnClickListener { PlayerActivity.playLive(a, list, i, "الرئيسية") }
            setTag(R.id.hero_item, ch)
        }
        if (first == null) first = card
        card.addView(FrameLayout(a).apply {
            background = rounded(C.SURFACE2, a.dpf(7f))
            addView(ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(ch.icon.ifEmpty { null }) { size(200, 120) } },
                FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(a.dp(8), a.dp(5), a.dp(8), a.dp(5)) })
        }, lp(MATCH, a.dp(46)))
        card.addView(a.text(ch.name, 12.5f, C.TEXT, bold = true).apply { setPadding(0, a.dp(7), 0, 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        val now = Epg.nowFor(ch) ?: Live.nowCache[ch.id]?.takeIf { it.isNow }
        card.addView(a.text(now?.title ?: "مباشر", 11.5f, C.MUTED).apply { setPadding(0, a.dp(2), 0, a.dp(6)) })
        card.addView(progressLine(now?.progress ?: 0f, C.LIVE), lp(MATCH, a.dp(2)))
        return card
    }

    override fun focusDefault() {
        heroBox?.let { h -> if (h.hasFeatured) { setRows(false); h.play.requestFocus(); return } }
        firstItem()?.requestFocus() ?: view.requestFocus()
    }

    private fun firstItem(): View? {
        // أول عنصر يتركّز عليه (للريموت)
        // التلفزيون: أول عنصر بأول قسم (نتخطّى «الكل/السجل ‹» اللي جنب العنوان)
        fun find(v: View): View? {
            if (v.visibility != View.VISIBLE || v.tag == "more") return null
            if (v.isFocusable && v !is ScrollView && v !is HorizontalScrollView && v !is androidx.recyclerview.widget.RecyclerView) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return listOf(contSlot, liveSlot, movSlot, serSlot, favSlot).firstNotNullOfOrNull { it?.let { s -> find(s) } }
    }

    // =================================================================================
    // التلفزيون — نمط نتفلكس: إعلان كبير فوق (TvHero) وخلفه صورة العمل بكامل الشاشة.
    // تنزل للصفوف: الإعلان يتحوّل لتفاصيل اللي واقف عليه والصف يطلع لأول الشاشة.
    // =================================================================================
    private val heroBox: TvHero? = if (a.tv) TvHero(a, "home") else null

    init {
        val h = heroBox
        tvRoot?.let { r ->
            r.addView(h!!.view, LinearLayout.LayoutParams(MATCH, WRAP))
            r.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            TvHero.watchFocus(r) { nv -> onTvFocus(nv) }
        }
    }

    private val lastPos = HashMap<View, Int>()       // آخر مكان بكل صف
    private fun slots(): List<LinearLayout> = listOfNotNull(contSlot, liveSlot, movSlot, serSlot, favSlot).filter { it.childCount > 0 }

    private fun onTvFocus(nv: View?) {
        val h = heroBox ?: return
        if (nv == null || a.current != "home" || tvRoot?.isAttachedToWindow != true) return
        if (h.contains(nv)) { setRows(false); return }
        if (!TvHero.isIn(scroll, nv)) return
        setRows(true)
        h.showFocused(nv)
        slots().firstOrNull { TvHero.isIn(it, nv) }?.let { sl -> TvHero.rowIn(sl)?.let { r -> TvHero.indexIn(r, nv)?.let { lastPos[sl] = it } } }
        // الصف اللي نزلت له يطلع لأول الشاشة
        slots().firstOrNull { TvHero.isIn(it, nv) }?.let { sl -> scroll.post { scroll.smoothScrollTo(0, sl.top) } }
    }

    private fun setRows(on: Boolean) {
        val h = heroBox ?: return
        if (h.rows == on) return
        h.setRows(on, tvRoot)
        if (!on) scroll.smoothScrollTo(0, 0)
    }

    override fun onKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val h = heroBox ?: return false
        if (!h.hasFeatured) return false
        val f = a.currentFocus ?: return false
        // فوق/تحت بين الصفوف: نروح للصف اللي فوق/تحت مباشرة، لأقرب عنصر لمكانك (بدون قفزات ولا تمرير بدل التنقل)
        if ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) && TvHero.isIn(scroll, f)) {
            val sl = slots()
            val i = sl.indexOfFirst { TvHero.isIn(it, f) }
            if (i < 0) return false
            val j = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) i - 1 else i + 1
            if (j < 0) { setRows(false); h.play.requestFocus(); return true }       // أول صف + فوق = الإعلان
            if (j >= sl.size) return true                                             // آخر صف: نبقى مكاننا
            // كل صف يتذكر وين وقفت فيه آخر مرة (نتفلكس)، وأول مرة = أول بوستر
            TvHero.rowIn(sl[j])?.let { TvHero.focusItem(it, lastPos[sl[j]] ?: 0) }
            return true
        }
        // من الإعلان تحت = أول عنصر بأول صف
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && h.contains(f)) {
            firstItem()?.let { it.requestFocus(); return true }
        }
        return false
    }

    /** العمل المميّز: آخر شي كنت تتابعه، وإلا أحدث فيلم. */
    private fun pickFeatured(cont: List<HistoryItem>) {
        val h = heroBox ?: return
        val k = h.featuredKey()
        if (k != null && (!k.startsWith("v:") || cont.isEmpty()) && (k.startsWith("v:") || cont.any { it.key == k })) return
        h.setFeatured(cont.firstOrNull() ?: newMovies?.firstOrNull() ?: newSeries?.firstOrNull())
    }
}

// =====================================================================
/** البحث في القنوات والأفلام والمسلسلات (محلياً من القوائم المحفوظة). */
class SearchScreen(private val a: MainActivity) : Screen {
    private val input = a.field("ابحث عن قناة أو فيلم أو مسلسل…").apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }
    private val chAdapter = ChannelAdapter(a, { ch, i -> PlayerActivity.playLive(a, chResults, i, "البحث") }, { _, _ -> })
    private var chResults: List<Channel> = emptyList()
    private val vodAdapter = PosterAdapter(a, a.dp(if (a.tv) 130 else 104)) { a.openDetails(it) }
    private val status = a.text("", 13f, C.MUTED)
    private var job: Job? = null
    override val view: View

    init {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(16), a.dp(16), 0) }
        col.addView(input, lp(MATCH, WRAP))
        col.addView(status.apply { setPadding(a.dp(4), a.dp(10), 0, a.dp(6)) })
        val chList = RecyclerView(a).apply { layoutManager = SmoothLM(a); adapter = chAdapter }
        val grid = RecyclerView(a).apply {
            layoutManager = SmoothLM(a, LinearLayoutManager.HORIZONTAL); adapter = vodAdapter
        }
        col.addView(a.text("الأفلام والمسلسلات", 15f, C.TEXT, bold = true).apply { setPadding(a.dp(4), a.dp(4), 0, a.dp(4)) })
        col.addView(grid, lp(MATCH, WRAP))
        col.addView(a.text("القنوات", 15f, C.TEXT, bold = true).apply { setPadding(a.dp(4), a.dp(10), 0, a.dp(4)) })
        col.addView(chList, lp(MATCH, 0, 1f))
        view = col
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = search(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
    }

    override fun onShow() {
        // نجهّز الفهرس أول ما تفتح الصفحة (بالخلفية) عشان أول حرف تكتبه يطلع نتيجته فوراً
        val api = Session.api ?: return
        if (SearchIndex.readyFor(api)) return
        status.text = "جاري تجهيز البحث…"
        a.scope.launch {
            withContext(Bg.io) { SearchIndex.build(api) }
            if (input.text.isNullOrBlank()) status.text = "" else search(input.text.toString())
        }
    }

    private fun search(q: String) {
        job?.cancel()
        val api = Session.api ?: return
        job = a.scope.launch {
            val nq = SearchIndex.tokens(q).joinToString("")
            if (nq.length < 2) { chResults = emptyList(); chAdapter.items = emptyList(); vodAdapter.items = emptyList(); status.text = ""; return@launch }
            delay(if (SearchIndex.readyFor(api)) 120 else 250)
            if (!SearchIndex.readyFor(api)) status.text = "جاري تجهيز البحث…"
            val idx = withContext(Bg.io) { SearchIndex.build(api) }
            val (ch, vods) = withContext(Dispatchers.Default) { idx.find(q) }
            chResults = ch
            chAdapter.items = ch
            vodAdapter.items = vods
            status.text = if (ch.isEmpty() && vods.isEmpty()) "ما فيه نتائج لـ \"$q\"" else "${ch.size} قناة · ${vods.size} فيلم ومسلسل"
        }
    }

    override fun focusDefault() { input.requestFocus() }
}

// =====================================================================
/** المفضلة: القنوات والأفلام والمسلسلات. */
class FavoritesScreen(private val a: MainActivity) : Screen {
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16)) }
    override val view: View = ScrollView(a).apply { addView(col) }

    override fun onShow() {
        col.removeAllViews()
        col.addView(a.text("المفضلة", 20f, C.TEXT, bold = true))
        val api = Session.api ?: return
        a.scope.launch {
            val (chans, vods) = withContext(Bg.io) {
                val f = Store.favs("live")
                val c = if (f.isEmpty()) emptyList() else api.liveStreams().associateBy { it.id }.let { m -> f.mapNotNull { m[it] } }
                val fm = Store.favs("movie"); val fs = Store.favs("series")
                val m = if (fm.isEmpty()) emptyList() else api.vods(false).associateBy { it.id }.let { mm -> fm.mapNotNull { mm[it] } }
                val s = if (fs.isEmpty()) emptyList() else api.vods(true).associateBy { it.id }.let { mm -> fs.mapNotNull { mm[it] } }
                c to (m + s)
            }
            if (chans.isEmpty() && vods.isEmpty()) {
                col.addView(a.text("ما فيه شي بعد. اضغط مطوّلاً على أي قناة، أو افتح فيلم واضغط المفضلة.", 14f, C.MUTED, lines = 3).apply { setPadding(0, a.dp(20), 0, 0) })
                return@launch
            }
            if (chans.isNotEmpty()) {
                col.addView(a.sectionTitle("القنوات"))
                val ad = ChannelAdapter(a, { _, i -> PlayerActivity.playLive(a, chans, i, "المفضلة") }, { _, _ -> })
                ad.items = chans
                col.addView(RecyclerView(a).apply { layoutManager = SmoothLM(a); adapter = ad; isNestedScrollingEnabled = false })
            }
            if (vods.isNotEmpty()) {
                col.addView(a.sectionTitle("الأفلام والمسلسلات"))
                val w = a.dp(if (a.tv) 130 else 104)
                val span = maxOf(2, (a.resources.displayMetrics.widthPixels - a.dp(if (a.tv) 110 else 32)) / (w + a.dp(10)))
                col.addView(RecyclerView(a).apply {
                    layoutManager = SmoothGridLM(a, span); isNestedScrollingEnabled = false
                    adapter = PosterAdapter(a, w) { a.openDetails(it) }.also { it.items = vods }
                })
            }
        }
    }
}

// =====================================================================
/** "المزيد" (الجوال) / الإعدادات (التلفزيون): قائمة مسطّحة بأسطر مرتبة. */
class SettingsScreen(private val a: MainActivity) : Screen {
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(8), 0, a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(col) }
    private var first: View? = null

    private fun add(v: View) { col.addView(v, lp(MATCH, WRAP)); col.addView(a.divider()); if (first == null && v.isFocusable) first = v }

    override fun onShow() {
        col.removeAllViews(); first = null
        col.addView(a.text(if (a.tv) "الإعدادات" else "المزيد", 22f, C.TEXT, bold = true).apply { setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(12)) })

        // الحساب: سطر واحد فيه الحرف الأول والاسم وتاريخ الانتهاء والاتصالات
        val acc = Store.current
        val ui = Session.userInfo
        val exp = ui?.optString("exp_date")?.toLongOrNull()?.let {
            java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(java.util.Date(it * 1000))
        } ?: "—"
        val accRow = a.listRow(0, acc?.name ?: "", subtitle = "ينتهي $exp · الاتصالات ${ui?.optString("active_cons") ?: "?"} من ${ui?.optString("max_connections") ?: "?"}") { a.showLogin(acc) }
        val avatar = a.text((acc?.name ?: "?").take(1).uppercase(), 16f, 0xFFA9C1FF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF2B3A5C.toInt(), a.dpf(22f))
        }
        accRow.addView(avatar, 0, lp(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(14) })
        add(accRow)

        if (!a.tv) {
            col.addView(a.sectionHeader("تصفّح"))
            add(a.listRow(R.drawable.ic_search, "بحث") { a.openExtra("search") })
            add(a.listRow(R.drawable.ic_heart, "المفضلة") { a.openExtra("favorites") })
        }
        val dls = Downloads.snapshot().filter { it.accId == Store.currentId }
        val act = dls.count { it.active }
        add(a.listRow(R.drawable.ic_download, "التحميلات",
            value = if (act > 0) "$act قيد التحميل" else if (dls.isNotEmpty()) "${dls.count { it.status == "completed" }}" else "",
            subtitle = "شاهد الأفلام والحلقات بدون إنترنت") { a.openExtra("downloads") })
        add(a.listRow(R.drawable.ic_history, "سجل المشاهدة") { a.openExtra("history") })

        col.addView(a.sectionHeader("الإعدادات"))
        add(a.listRow(R.drawable.ic_settings, "شكل الواجهة", when (Store.uiMode) { "tv" -> "تلفزيون"; "phone" -> "جوال"; else -> "تلقائي" },
            subtitle = "تلقائي: يعرف لحاله إذا الجهاز تلفزيون") { chooseUiMode() })
        if (a.tv) add(a.listRow(R.drawable.ic_live, "شكل صفحة المباشر", liveStyleName(Store.liveStyle)) { chooseLiveStyle() })
        add(a.listRow(R.drawable.ic_live, "صيغة البث المباشر", if (Store.liveFormat == "ts") "MPEG-TS" else "HLS") { chooseFormat() })
        add(a.listRow(R.drawable.ic_guide, "دليل البرامج", Epg.status.ifEmpty { "—" }) { a.openExtra("epg") })
        add(a.listRow(R.drawable.ic_refresh, "تحديث القوائم", subtitle = "يجيب آخر القنوات والأفلام من السيرفر") {
            // نافذة منبثقة بالعدّاد، ولما يخلص: الأقسام تنبني من جديد بالقوائم الجديدة
            a.showUpdatePopup { listOf("home", "live", "movie", "series").forEach { a.dropScreen(it) } }
        })
        add(a.listRow(R.drawable.ic_refresh, "التحديث التلقائي للقوائم", autoName(Store.autoUpdateDays)) { chooseAutoUpdate() })
        add(a.listRow(R.drawable.ic_user, "تعديل الحساب / حساب آخر") { a.showLogin(acc) })

        col.addView(a.sectionHeader("عن التطبيق"))
        add(a.listRow(R.drawable.ic_info, "URROX PLAYER", "1.0", chevron = false))
        add(a.listRow(R.drawable.ic_logout, "تسجيل خروج", chevron = false, danger = true) {
            AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("حذف الحساب من الجهاز؟")
                .setPositiveButton("حذف") { _, _ -> acc?.let { Store.removeAccount(it.id) }; Store.current?.let { a.enter(it, true) } ?: a.showLogin() }
                .setNegativeButton("إلغاء", null).show()
        })
    }

    private fun chooseUiMode() {
        val keys = arrayOf("auto", "phone", "tv")
        val opts = arrayOf("تلقائي (موصى به)", "جوال", "تلفزيون")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("شكل الواجهة")
            .setSingleChoiceItems(opts, keys.indexOf(Store.uiMode).coerceAtLeast(0)) { d, w ->
                d.dismiss()
                if (keys[w] != Store.uiMode) a.setUiMode(keys[w])
            }.show()
    }

    private fun autoName(d: Int) = when (d) { 0 -> "إيقاف"; 1 -> "كل يوم"; 2 -> "كل يومين"; 3 -> "كل 3 أيام"; 7 -> "كل أسبوع"; 30 -> "كل شهر"; else -> "كل $d أيام" }

    private fun chooseAutoUpdate() {
        val days = intArrayOf(0, 1, 2, 3, 7, 30)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("التحديث التلقائي للقوائم")
            .setSingleChoiceItems(days.map { autoName(it) }.toTypedArray(), days.indexOf(Store.autoUpdateDays).coerceAtLeast(0)) { d, w ->
                d.dismiss()
                Store.autoUpdateDays = days[w]
                onShow(); first?.requestFocus()
            }.show()
    }

    private fun liveStyleName(k: String) = when (k) { "c" -> "أعمدة"; "z" -> "مشغّل كبير + قائمة"; else -> "البث يملى الشاشة" }

    private fun chooseLiveStyle() {
        val keys = arrayOf("a", "c", "z")
        val opts = keys.map { liveStyleName(it) + if (it == "a") " (الافتراضي)" else "" }.toTypedArray()
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("شكل صفحة المباشر")
            .setSingleChoiceItems(opts, keys.indexOf(Store.liveStyle).coerceAtLeast(0)) { d, w ->
                d.dismiss()
                if (keys[w] != Store.liveStyle) { Store.liveStyle = keys[w]; a.dropScreen("live"); onShow(); first?.requestFocus() }
            }.show()
    }

    private fun chooseFormat() {
        val opts = arrayOf("HLS (موصى به)", "MPEG-TS")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("صيغة البث المباشر")
            .setSingleChoiceItems(opts, if (Store.liveFormat == "ts") 1 else 0) { d, w ->
                Store.liveFormat = if (w == 1) "ts" else "m3u8"; d.dismiss(); onShow()
            }.show()
    }

    override fun focusDefault() { (first ?: view).requestFocus() }
}

// =====================================================================
/** دليل البرامج: الحالة، روابط XMLTV (كل رابط سطر مع حذف)، إضافة رابط، تحديث. */
class EpgScreen(private val a: MainActivity) : Screen {
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(8), 0, a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(col) }
    private var first: View? = null

    override fun onShow() {
        col.removeAllViews(); first = null
        val head = a.listRow(R.drawable.ic_chev_right, "دليل البرامج", chevron = false) { a.show("settings") }
        (head.getChildAt(2) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.apply { textSize = 20f; typeface = Fonts.bold } }
        col.addView(head); first = head

        val chans = runCatching { Session.api?.let { if (it.hasLive()) it.liveStreams() else null } }.getOrNull() ?: emptyList()
        val (m, t) = Epg.stats(chans)
        col.addView(a.listRow(R.drawable.ic_info, "تعرّفنا على $m من $t قناة", subtitle = Epg.status, chevron = false))
        col.addView(a.divider())

        col.addView(a.sectionHeader("روابط الدليل (XMLTV)"))
        Store.epgUrls.forEach { u ->
            val row = a.listRow(R.drawable.ic_link, u.removePrefix("https://").removePrefix("http://"), chevron = false) {
                AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("حذف هذا الرابط؟\n$u")
                    .setPositiveButton("حذف") { _, _ -> Store.epgUrls = Store.epgUrls - u; onShow() }
                    .setNegativeButton("إلغاء", null).show()
            }
            ((row.getChildAt(2) as? LinearLayout)?.getChildAt(0) as? TextView)?.apply {
                textSize = 13f; textDirection = View.TEXT_DIRECTION_LTR
            }
            row.addView(a.icon(R.drawable.ic_close, C.MUTED, 18))
            col.addView(row); col.addView(a.divider())
        }
        col.addView(a.listRow(R.drawable.ic_plus, "إضافة رابط", chevron = false) { addUrl() }.apply {
            ((getChildAt(2) as? LinearLayout)?.getChildAt(0) as? TextView)?.setTextColor(C.ACCENT)
        })
        col.addView(a.divider())
        col.addView(a.button("تحديث الدليل الآن", R.drawable.ic_refresh, primary = true) { refresh() },
            lp(MATCH, WRAP).apply { setMargins(a.dp(16), a.dp(18), a.dp(16), a.dp(8)) })
        col.addView(a.text("للربط اليدوي: اضغط مطوّلاً على أي قناة ← ربط دليل البرامج.", 12.5f, C.MUTED, lines = 3)
            .apply { setPadding(a.dp(16), a.dp(4), a.dp(16), 0) })
    }

    private fun addUrl() {
        val input = a.field("https://…/epg.xml.gz")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("إضافة رابط دليل")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty() && u !in Store.epgUrls) { Store.epgUrls = Store.epgUrls + u; refresh() }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun refresh() {
        a.toast("جاري تحديث الدليل…")
        a.scope.launch {
            withContext(Bg.io) { Epg.load(Store.epgUrls, a.cacheDir, force = true) }
            if (a.current == "epg") onShow()
        }
    }

    override fun focusDefault() { (first ?: view).requestFocus() }
}

// =====================================================================
/** سجل المشاهدة: أسطر (صورة، الاسم، التفاصيل، المتبقي). */
class HistoryScreen(private val a: MainActivity) : Screen {
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(8), 0, a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(col) }
    private var first: View? = null

    override fun onShow() {
        col.removeAllViews(); first = null
        col.addView(a.text("سجل المشاهدة", 22f, C.TEXT, bold = true).apply { setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(8)) })
        val hist = Store.displayHistory()          // مسلسل = مدخل واحد (الحلقة اللي وصلت لها)
        if (hist.isEmpty()) {
            col.addView(a.text("ما شاهدت شي للحين.", 14f, C.MUTED).apply { setPadding(a.dp(16), a.dp(20), a.dp(16), 0) })
            return
        }
        val api = Session.api
        hist.forEach { h ->
            val sub = when {
                h.type == "live" -> "مباشر"
                h.dur > 0 && h.pos >= h.dur - 60 -> "✓ شاهدته"
                h.dur > 0 -> "متبقي ${fmtTime(h.dur - h.pos)}" + if (h.sub.isNotEmpty()) " · ${h.sub}" else ""
                else -> h.sub
            }
            val row = a.listRow(0, h.title, subtitle = sub) {
                if (h.type == "live") a.scope.launch {
                    val ch = api?.let { x -> withContext(Bg.io) { runCatching { x.liveStreams() }.getOrNull() } }
                        ?.firstOrNull { "live:${it.id}" == h.key }
                    if (ch != null) PlayerActivity.playLive(a, listOf(ch), 0, "السجل") else a.toast("القناة مو موجودة")
                } else a.playHistory(h)
            }
            val img = ImageView(a).apply {
                scaleType = if (h.type == "live") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                background = rounded(C.SURFACE2, a.dpf(6f)); clipToOutline = true
                load(h.image.ifEmpty { null }) { size(160, 100) }
            }
            row.addView(img, 0, lp(a.dp(64), a.dp(40)).apply { marginEnd = a.dp(12) })
            col.addView(row); col.addView(a.divider())
            if (first == null) first = row
        }
    }

    override fun focusDefault() { (first ?: view).requestFocus() }
}

/** قائمة اختيار مع بحث (للتصنيفات): ترجع المعرّف المختار. */
fun pickDialog(a: MainActivity, title: String, items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var shown = items
    val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(6), a.dp(16), 0) }
    val q = a.field("ابحث…").apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
    col.addView(q, lp(MATCH, WRAP))
    val ad = android.widget.ArrayAdapter(a, android.R.layout.simple_list_item_1,
        shown.map { (id, n) -> if (id == selected) "✓  $n" else n }.toMutableList())
    val list = android.widget.ListView(a).apply { adapter = ad }
    col.addView(list, lp(MATCH, a.dp(380)))
    val dlg = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle(title).setView(col)
        .setNegativeButton("إغلاق", null).create()
    list.setOnItemClickListener { _, _, pos, _ -> onPick(shown[pos].first); dlg.dismiss() }
    q.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) {
            val k = Epg.keys(s?.toString() ?: "").first
            shown = if (k.isEmpty()) items else items.filter { Epg.keys(it.second).first.contains(k) }
            ad.clear(); ad.addAll(shown.map { (id, n) -> if (id == selected) "✓  $n" else n }); ad.notifyDataSetChanged()
        }
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    })
    dlg.show()
}
