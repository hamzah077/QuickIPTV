package com.quickiptv.app

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

fun MainActivity.field(hint: String, value: String = "", password: Boolean = false): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    setHintTextColor(C.FAINT)
    setTextColor(C.TEXT)
    textSize = 15f
    isSingleLine = true
    layoutDirection = View.LAYOUT_DIRECTION_LTR
    textDirection = View.TEXT_DIRECTION_LTR
    setPadding(dp(14), dp(12), dp(14), dp(12))
    background = focusBg(normal = C.SURFACE, radius = 10)
    inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
}

fun MainActivity.sectionTitle(t: String): TextView = text(t, 17f, C.TEXT, bold = true).apply {
    setPadding(dp(4), dp(18), dp(4), dp(10))
}

/** صف أفقي من البوسترات (للرئيسية). */
fun MainActivity.posterRow(items: List<Vod>, posterW: Int, onClick: (Vod) -> Unit): RecyclerView =
    RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@posterRow, LinearLayoutManager.HORIZONTAL, false)
        adapter = PosterAdapter(this@posterRow, posterW, onClick).also { it.items = items }
        setHasFixedSize(true)
        clipToPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

fun MainActivity.openDetails(v: Vod) {
    startActivity(Intent(this, DetailsActivity::class.java)
        .putExtra("id", v.id).putExtra("series", v.isSeries).putExtra("name", v.name)
        .putExtra("icon", v.icon).putExtra("ext", v.ext))
}

fun MainActivity.playHistory(h: HistoryItem) {
    startActivity(Intent(this, PlayerActivity::class.java)
        .putExtra("url", h.url).putExtra("key", h.key).putExtra("title", h.title).putExtra("sub", h.sub)
        .putExtra("image", h.image).putExtra("type", h.type).putExtra("seriesId", h.seriesId)
        .putExtra("start", Store.resumePoint(h.key)))
}

// =====================================================================
/** إضافة/تعديل الحساب. يقبل لصق رابط الاشتراك كامل (get.php…). */
class LoginScreen(private val a: MainActivity, edit: Account?) : Screen {
    override val view: View
    private val server = a.field("http://example.com:8080  أو الصق رابط الاشتراك كامل", edit?.server ?: "")
    private val user = a.field("اسم المستخدم", edit?.username ?: "")
    private val pass = a.field("كلمة المرور", edit?.password ?: "", password = true)
    private val name = a.field("اسم الحساب (اختياري)", edit?.name ?: "")
    private val err = a.text("", 13f, C.LIVE, lines = 3)
    private val id = edit?.id ?: UUID.randomUUID().toString().take(10)

    init {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        col.addView(a.text("QuickIPTV", 26f, C.TEXT, bold = true))
        col.addView(a.text("أضف حساب Xtream Codes للبدء", 14f, C.MUTED).apply { setPadding(0, a.dp(6), 0, a.dp(20)) })
        for (f in listOf(server, user, pass, name)) {
            col.addView(f, lp(MATCH, WRAP).apply { bottomMargin = a.dp(10) })
        }
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
        server.setOnFocusChangeListener { _, has -> if (!has) splitLink() }
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
                a.enter(acc, checkLogin = false)
            } catch (e: Exception) {
                err.setTextColor(C.LIVE); err.text = e.message ?: "تعذر الدخول"
            }
        }
    }

    override fun focusDefault() { server.requestFocus() }
}

// =====================================================================
/** الرئيسية: متابعة المشاهدة، قنواتك المفضلة، أفلامك ومسلسلاتك المفضلة. بدون طلبات ثقيلة. */
class HomeScreen(private val a: MainActivity) : Screen, EpgAware {
    private val col = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(if (a.tv) 28 else 14), a.dp(16), a.dp(if (a.tv) 28 else 14), a.dp(24))
    }
    override val view: View = ScrollView(a).apply { addView(col); isFillViewport = true }
    private var first: View? = null

    override fun onShow() = build()
    override fun onEpgLoaded() { if (a.current == "home") build() }

    private fun build() {
        col.removeAllViews(); first = null
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(a.text("QuickIPTV", 20f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        if (!a.tv) head.addView(a.iconButton(R.drawable.ic_search, "بحث") { a.openExtra("search") })
        col.addView(head)

        val cont = Store.continueWatching()
        cont.firstOrNull()?.let { h -> col.addView(hero(h)) }
        if (cont.size > 1) {
            col.addView(a.sectionTitle("متابعة المشاهدة"))
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            cont.drop(1).take(10).forEach { h -> row.addView(continueCard(h)) }
            col.addView(HorizontalScrollView(a).apply { addView(row); isHorizontalScrollBarEnabled = false })
        }
        val api = Session.api ?: return
        a.scope.launch {
            val favLive = Store.favs("live")
            val favMovie = Store.favs("movie")
            val favSeries = Store.favs("series")
            val (chans, movies, series) = withContext(Dispatchers.IO) {
                Triple(
                    if (favLive.isEmpty()) emptyList() else runCatching { api.liveStreams() }.getOrDefault(emptyList()).let { all -> val m = all.associateBy { it.id }; favLive.mapNotNull { m[it] } },
                    if (favMovie.isEmpty()) emptyList() else runCatching { api.vods(false) }.getOrDefault(emptyList()).let { all -> val m = all.associateBy { it.id }; favMovie.mapNotNull { m[it] } },
                    if (favSeries.isEmpty()) emptyList() else runCatching { api.vods(true) }.getOrDefault(emptyList()).let { all -> val m = all.associateBy { it.id }; favSeries.mapNotNull { m[it] } },
                )
            }
            if (chans.isNotEmpty()) {
                col.addView(a.sectionTitle("قنواتك المفضلة"))
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                chans.take(20).forEachIndexed { i, ch -> row.addView(channelCard(ch, chans, i)) }
                col.addView(HorizontalScrollView(a).apply { addView(row); isHorizontalScrollBarEnabled = false })
            }
            val w = a.dp(if (a.tv) 130 else 108)
            if (movies.isNotEmpty()) { col.addView(a.sectionTitle("أفلامك المفضلة")); col.addView(a.posterRow(movies, w) { a.openDetails(it) }) }
            if (series.isNotEmpty()) { col.addView(a.sectionTitle("مسلسلاتك المفضلة")); col.addView(a.posterRow(series, w) { a.openDetails(it) }) }
            if (cont.isEmpty() && chans.isEmpty() && movies.isEmpty() && series.isEmpty()) {
                col.addView(a.text("ابدأ من البث المباشر أو الأفلام. اللي تشاهده وتفضّله بيطلع هنا.", 15f, C.MUTED, lines = 3)
                    .apply { setPadding(0, a.dp(30), 0, a.dp(16)) })
                col.addView(a.button("البث المباشر", R.drawable.ic_live, primary = true) { a.show("live") }.also { first = it }, lp(WRAP, WRAP))
            }
            if (a.current == "home" && a.currentFocus == null) focusDefault()
        }
    }

    private fun hero(h: HistoryItem): View {
        val box = FrameLayout(a).apply {
            layoutParams = lp(MATCH, a.dp(if (a.tv) 230 else 190)).apply { topMargin = a.dp(14) }
            background = rounded(C.SURFACE2, a.dpf(16f))
            clipToOutline = true
        }
        box.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.45f
            load(h.image.ifEmpty { null }) { size(900, 500) }
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(16)) }
        c.addView(a.text("متابعة المشاهدة", 12f, C.TEXT2))
        c.addView(a.text(h.title, 22f, C.TEXT, bold = true, lines = 2).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        if (h.sub.isNotEmpty()) c.addView(a.text(h.sub, 12.5f, C.TEXT2))
        c.addView(a.text("متبقي ${fmtTime(h.dur - h.pos)}", 12f, C.MUTED).apply { setPadding(0, a.dp(4), 0, a.dp(10)) })
        val b = a.button("متابعة", R.drawable.ic_play, primary = true) { a.playHistory(h) }
        first = b
        c.addView(b, lp(WRAP, WRAP))
        box.addView(c, FrameLayout.LayoutParams(MATCH, MATCH))
        return box
    }

    private fun continueCard(h: HistoryItem): View {
        val w = a.dp(if (a.tv) 220 else 180)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(w, WRAP).apply { marginEnd = a.dp(10) }
            setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(6))
            background = a.focusBg(radius = 12)
            isFocusable = true; isClickable = true
            focusScale()
            setOnClickListener { a.playHistory(h) }
        }
        card.addView(ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(C.SURFACE2, a.dpf(9f)); clipToOutline = true
            load(h.image.ifEmpty { null }) { size(w, w * 9 / 16) }
        }, lp(MATCH, w * 9 / 16))
        card.addView(a.text(h.title, 13f, C.TEXT, bold = true).apply { setPadding(a.dp(2), a.dp(6), a.dp(2), 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        card.addView(a.text("متبقي ${fmtTime(h.dur - h.pos)}", 11.5f, C.MUTED).apply { setPadding(a.dp(2), a.dp(2), a.dp(2), 0) })
        return card
    }

    private fun channelCard(ch: Channel, list: List<Channel>, i: Int): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(a.dp(170), WRAP).apply { marginEnd = a.dp(10) }
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
            background = a.focusBg(normal = C.SURFACE, radius = 12)
            isFocusable = true; isClickable = true
            focusScale()
            setOnClickListener { PlayerActivity.playLive(a, list, i, "المفضلة") }
        }
        card.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(ch.icon.ifEmpty { null }) { size(160, 100) } }, lp(a.dp(60), a.dp(38)))
        card.addView(a.text(ch.name, 13f, C.TEXT, bold = true).apply { setPadding(0, a.dp(6), 0, 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        val now = Epg.nowFor(ch) ?: Live.nowCache[ch.id]?.takeIf { it.isNow }
        card.addView(a.text(now?.title ?: "مباشر", 11.5f, C.MUTED).apply { setPadding(0, a.dp(2), 0, 0) })
        return card
    }

    override fun focusDefault() { (first ?: view).requestFocus() }
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
        val chList = RecyclerView(a).apply { layoutManager = LinearLayoutManager(a); adapter = chAdapter }
        val grid = RecyclerView(a).apply {
            layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false); adapter = vodAdapter
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

    private fun search(q: String) {
        job?.cancel()
        val api = Session.api ?: return
        job = a.scope.launch {
            delay(300)
            if (q.trim().length < 2) { chAdapter.items = emptyList(); vodAdapter.items = emptyList(); status.text = ""; return@launch }
            status.text = "جاري البحث…"
            val nq = Epg.keys(q).first
            val (ch, vods) = withContext(Dispatchers.Default) {
                val chs = runCatching { withContext(Dispatchers.IO) { api.liveStreams() } }.getOrDefault(emptyList())
                    .filter { Epg.keys(it.name).first.contains(nq) }.take(200)
                val vs = (runCatching { withContext(Dispatchers.IO) { api.vods(false) } }.getOrDefault(emptyList()) +
                        runCatching { withContext(Dispatchers.IO) { api.vods(true) } }.getOrDefault(emptyList()))
                    .filter { Epg.keys(it.name).first.contains(nq) }.take(60)
                chs to vs
            }
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
            val (chans, vods) = withContext(Dispatchers.IO) {
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
                col.addView(RecyclerView(a).apply { layoutManager = LinearLayoutManager(a); adapter = ad; isNestedScrollingEnabled = false })
            }
            if (vods.isNotEmpty()) {
                col.addView(a.sectionTitle("الأفلام والمسلسلات"))
                val w = a.dp(if (a.tv) 130 else 104)
                val span = maxOf(2, (a.resources.displayMetrics.widthPixels - a.dp(if (a.tv) 110 else 32)) / (w + a.dp(10)))
                col.addView(RecyclerView(a).apply {
                    layoutManager = GridLayoutManager(a, span); isNestedScrollingEnabled = false
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
        add(a.listRow(R.drawable.ic_live, "صيغة البث المباشر", if (Store.liveFormat == "ts") "MPEG-TS" else "HLS") { chooseFormat() })
        add(a.listRow(R.drawable.ic_guide, "دليل البرامج", Epg.status.ifEmpty { "—" }) { a.openExtra("epg") })
        add(a.listRow(R.drawable.ic_refresh, "تحديث القوائم", subtitle = "يجيب آخر القنوات والأفلام من السيرفر") {
            Session.api?.clearCache(); a.toast("بتتحدّث القوائم أول ما تفتح كل قسم")
        })
        add(a.listRow(R.drawable.ic_user, "تعديل الحساب / حساب آخر") { a.showLogin(acc) })

        col.addView(a.sectionHeader("عن التطبيق"))
        add(a.listRow(R.drawable.ic_info, "QuickIPTV", "1.0", chevron = false))
        add(a.listRow(R.drawable.ic_logout, "تسجيل خروج", chevron = false, danger = true) {
            AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setMessage("حذف الحساب من الجهاز؟")
                .setPositiveButton("حذف") { _, _ -> acc?.let { Store.removeAccount(it.id) }; Store.current?.let { a.enter(it, true) } ?: a.showLogin() }
                .setNegativeButton("إلغاء", null).show()
        })
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
        (head.getChildAt(2) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) } }
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
            withContext(Dispatchers.IO) { Epg.load(Store.epgUrls, a.cacheDir, force = true) }
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
        val hist = Store.history()
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
                    val ch = api?.let { x -> withContext(Dispatchers.IO) { runCatching { x.liveStreams() }.getOrNull() } }
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
