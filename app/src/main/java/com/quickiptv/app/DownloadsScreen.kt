package com.quickiptv.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import coil.load

/**
 * صفحة التحميلات (مثل نسخة الكمبيوتر):
 * فلاتر (الكل / قيد التحميل / المكتملة / أفلام / حلقات)، الحلقات مجمّعة حسب المسلسل والموسم
 * مع أزرار للموسم كامل، وتقدّم مباشر يتحدّث بمكانه بدون ما تنبني الصفحة كل مرة.
 */
class DownloadsScreen(private val a: MainActivity) : Screen {
    private val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(8), 0, a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(col); isFillViewport = true }
    private var filter = "all"
    private var first: View? = null
    private val expanded = HashMap<String, Boolean>()

    private class RowRefs(val bar: ProgressBar, val status: TextView, val size: TextView, val btns: LinearLayout, var shown: String)
    private class GroupRefs(val key: String, val bar: ProgressBar, val meta: TextView, val status: TextView, val btns: LinearLayout, var shown: String)
    private val rows = HashMap<String, RowRefs>()
    private val groups = HashMap<String, GroupRefs>()

    private val listener: (Dl?) -> Unit = { d -> if (d == null) rebuild() else update(d) }

    override fun onShow() {
        Downloads.addListener(listener)
        rebuild()
    }

    override fun onHide() { Downloads.removeListener(listener) }

    override fun focusDefault() { (first ?: view).requestFocus() }

    private fun items(): List<Dl> = Downloads.snapshot().filter { it.accId == Store.currentId }

    private fun match(d: Dl) = when (filter) {
        "active" -> d.status != "completed"
        "completed" -> d.status == "completed"
        "movie" -> d.kind == "movie"
        "episode" -> d.kind == "episode"
        else -> true
    }

    private fun groupKey(d: Dl) = "${d.seriesId.ifEmpty { d.group }}#${d.season}"

    // ---------- البناء ----------
    private fun rebuild() {
        val hadFocus = view.findFocus()?.tag as? String
        col.removeAllViews(); rows.clear(); groups.clear(); first = null
        val head = a.listRow(R.drawable.ic_chev_right, "التحميلات", chevron = false) { a.show("settings") }
        (head.getChildAt(2) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.apply { textSize = 20f; typeface = Fonts.bold } }
        col.addView(head); first = head

        val all = items()
        val active = all.count { it.active }
        col.addView(a.listRow(R.drawable.ic_download, "المساحة الفاضية ${fmtBytes(Downloads.freeSpace())}",
            value = "${Downloads.maxParallel} بنفس الوقت",
            subtitle = "المحمّل ${fmtBytes(Downloads.usedSpace())}" + (if (active > 0) " · $active قيد التحميل" else "") +
                "\nالملفات داخل التطبيق: تنحذف لو حذفت التطبيق") { chooseParallel() })
        col.addView(a.divider())
        val limit = Session.userInfo?.optString("max_connections")?.toIntOrNull() ?: 0
        col.addView(a.listRow(R.drawable.ic_refresh, "مسرّع التحميل",
            value = if (Downloads.connections > 1) "${Downloads.connections} اتصالات" else "متوقف",
            subtitle = "يقسم الملف ويحمّله بأكثر من اتصال بنفس الوقت" +
                (if (limit in 1..2) "\nاشتراكك يسمح بـ $limit اتصال — المسرّع ينحد تلقائياً عشان ما ينحظر" else "")) { chooseConnections() })
        col.addView(a.divider())

        // الفلاتر
        val chips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(6)) }
        for ((k, t) in listOf("all" to "الكل", "active" to "قيد التحميل", "completed" to "المكتملة", "movie" to "أفلام", "episode" to "حلقات")) {
            val b = a.button(t, primary = k == filter) { filter = k; rebuild() }.apply { tag = "chip:$k" }
            chips.addView(b, lp(WRAP, WRAP).apply { marginEnd = a.dp(8) })
        }
        col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        if (all.any { it.status == "failed" }) {
            col.addView(a.button("مسح الفاشلة", R.drawable.ic_trash) { Downloads.clearFailed() }.apply { tag = "clear" },
                lp(WRAP, WRAP).apply { setMargins(a.dp(12), a.dp(6), a.dp(12), 0) })
        }

        val list = all.filter { match(it) }
        if (list.isEmpty()) {
            empty(all.isEmpty())
        } else {
            // أفلام لحالها، والحلقات مجمّعة (مسلسل + موسم)؛ الأحدث فوق
            val blocks = LinkedHashMap<String, MutableList<Dl>>()
            list.sortedByDescending { it.addedAt }.forEach { d ->
                blocks.getOrPut(if (d.kind == "episode") "g:" + groupKey(d) else "m:" + d.id) { ArrayList() }.add(d)
            }
            blocks.forEach { (k, ds) -> if (k.startsWith("g:")) addGroup(k.substring(2), ds.sortedBy { it.epNum }) else col.addView(row(ds[0], true)) }
        }
        hadFocus?.let { t -> col.findViewWithTag<View?>(t)?.let { v -> v.post { v.requestFocus() } } }
    }

    private fun empty(nothing: Boolean) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(24), a.dp(60), a.dp(24), a.dp(24)) }
        box.addView(a.icon(R.drawable.ic_download, C.FAINT, 44))
        box.addView(a.text(if (nothing) "ما فيه تحميلات بعد" else "ما فيه عناصر هنا", 17f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, a.dp(14), 0, a.dp(6)) }, lp(MATCH, WRAP))
        box.addView(a.text(if (nothing) "افتح أي فيلم أو مسلسل واضغط \"تحميل\" عشان تشاهده بدون إنترنت." else "جرّب فلتر ثاني من فوق.", 13.5f, C.MUTED, lines = 3).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        if (nothing) box.addView(a.button("تصفح الأفلام", R.drawable.ic_movie, primary = true) { a.show("movie") }, lp(WRAP, WRAP).apply { topMargin = a.dp(18) })
        col.addView(box, lp(MATCH, WRAP))
    }

    private fun bar(): ProgressBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(C.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(C.SURFACE3)
        layoutDirection = View.LAYOUT_DIRECTION_RTL     // يتعبّى من اليمين
    }

    private fun thumb(url: String, w: Int, h: Int) = ImageView(a).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(C.SURFACE2, a.dpf(7f)); clipToOutline = true
        load(url.ifEmpty { null }) { size(a.dp(w) * 2, a.dp(h) * 2) }
    }

    /** سطر فيلم أو حلقة. */
    private fun row(d: Dl, withThumb: Boolean): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(14), a.dp(10), a.dp(10), a.dp(10))
            if (d.status == "completed") { isClickable = true; isFocusable = true; background = a.focusBg(radius = 0); tag = "row:${d.id}"; setOnClickListener { play(d) } }
        }
        if (withThumb) r.addView(thumb(d.poster, 54, 78), lp(a.dp(54), a.dp(78)).apply { marginEnd = a.dp(12) })
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val name = if (d.kind == "episode") (if (withThumb) "${d.group} · " else "") + "ح${d.epNum}. ${d.title}" else d.title
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(a.text(name, 14.5f, C.TEXT, lines = 2).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG }, lp(0, WRAP, 1f))
        if (withThumb) top.addView(a.text(if (d.kind == "episode") "حلقة" else "فيلم", 11f, C.MUTED).apply {
            background = rounded(C.SURFACE2, a.dpf(6f)); setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2))
        })
        c.addView(top)
        val b = bar()
        c.addView(b, lp(MATCH, a.dp(4)).apply { topMargin = a.dp(8); bottomMargin = a.dp(6) })
        val info = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        // سطرين: الحالة (عربي) ثم الحجم والنسبة — بدل ما يتزاحمون بسطر واحد
        val st = a.text("", 12f, C.MUTED, lines = 2)
        val sz = a.text("", 12f, C.MUTED).apply { setPadding(0, a.dp(2), 0, 0) }
        info.orientation = LinearLayout.VERTICAL
        info.addView(st, lp(MATCH, WRAP)); info.addView(sz, lp(MATCH, WRAP))
        c.addView(info)
        r.addView(c, lp(0, WRAP, 1f))
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(btns, lp(WRAP, WRAP).apply { marginStart = a.dp(6) })
        rows[d.id] = RowRefs(b, st, sz, btns, "")
        fill(d)
        val wrap = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(r, lp(MATCH, WRAP)); wrap.addView(a.divider())
        return wrap
    }

    private fun fill(d: Dl) {
        val r = rows[d.id] ?: return
        r.bar.progress = (d.frac * 1000).toInt()
        r.bar.visibility = if (d.status == "completed") View.GONE else View.VISIBLE
        r.bar.progressTintList = ColorStateList.valueOf(when (d.status) { "failed" -> C.LIVE; "paused", "queued" -> C.MUTED; else -> C.ACCENT })
        r.status.setTextColor(if (d.status == "failed") C.LIVE else if (d.status == "completed") 0xFF6FCF97.toInt() else C.MUTED)
        r.status.text = when {
            d.status == "downloading" && d.waiting -> "متوقف أثناء المشاهدة — يكمّل تلقائياً بعدها"
            d.status == "downloading" && d.error.isNotEmpty() -> d.error
            d.status == "downloading" -> (if (d.conns > 1) "⚡ ${d.conns} اتصالات · " else "") + if (d.speed > 0) "السرعة ${fmtBytes(d.speed)}" + (if (d.total > 0) " · متبقي \u200E${fmtTime((d.total - d.received) / d.speed.coerceAtLeast(1))}\u200E" else "") else "جاري الاتصال…"
            d.status == "queued" -> "في الانتظار"
            d.status == "paused" -> "متوقف مؤقتاً"
            d.status == "completed" -> "✓ مكتمل · يشتغل بدون إنترنت"
            else -> "فشل: ${d.error}"
        }
        r.size.text = when {
            d.status == "completed" -> fmtBytes(d.total)
            d.total > 0 -> "${fmtBytes(d.received)} من ${fmtBytes(d.total)} · \u200E${(d.frac * 100).toInt()}%"
            d.received > 0 -> fmtBytes(d.received)
            else -> ""
        }
        val kind = when (d.status) { "downloading", "queued" -> "run"; else -> d.status }
        if (kind != r.shown) {
            r.shown = kind
            r.btns.removeAllViews()
            fun add(ic: Int, desc: String, act: () -> Unit) {
                r.btns.addView(a.iconButton(ic, desc) { act() }.apply { tag = "btn:${d.id}:$desc" }, lp(a.dp(44), a.dp(44)))
            }
            when (kind) {
                "run" -> { add(R.drawable.ic_pause, "إيقاف مؤقت") { Downloads.pause(d.id) }; add(R.drawable.ic_close, "إلغاء") { confirm("إلغاء التحميل وحذف الجزء المحمّل؟") { Downloads.remove(d.id) } } }
                "paused" -> { add(R.drawable.ic_play, "استئناف") { Downloads.resume(d.id) }; add(R.drawable.ic_close, "إلغاء") { confirm("إلغاء التحميل وحذف الجزء المحمّل؟") { Downloads.remove(d.id) } } }
                "completed" -> { add(R.drawable.ic_play, "تشغيل") { play(d) }; add(R.drawable.ic_trash, "حذف") { confirm("حذف \"${d.title}\" من الجهاز؟") { Downloads.remove(d.id) } } }
                else -> { add(R.drawable.ic_refresh, "إعادة المحاولة") { Downloads.resume(d.id) }; add(R.drawable.ic_trash, "إزالة") { Downloads.remove(d.id) } }
            }
        }
    }

    /** مجموعة حلقات موسم واحد: تقدّم إجمالي وأزرار للموسم كامل، وتوسيع/طي. */
    private fun addGroup(key: String, ds: List<Dl>) {
        val d0 = ds[0]
        val open = expanded[key] ?: ds.any { it.status != "completed" }
        val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.PANEL) }
        val h = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(14), a.dp(12), a.dp(10), a.dp(12))
            isFocusable = true; isClickable = true; background = a.focusBg(radius = 0); tag = "group:$key"
            setOnClickListener { expanded[key] = !open; rebuild() }
        }
        h.addView(thumb(d0.poster, 54, 78), lp(a.dp(54), a.dp(78)).apply { marginEnd = a.dp(12) })
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        c.addView(a.text("${d0.group} — الموسم ${d0.season}", 15f, C.TEXT, bold = true, lines = 2).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG })
        val meta = a.text("", 12f, C.MUTED).apply { setPadding(0, a.dp(3), 0, 0) }
        c.addView(meta)
        val b = bar()
        c.addView(b, lp(MATCH, a.dp(4)).apply { topMargin = a.dp(8); bottomMargin = a.dp(6) })
        val st = a.text("", 12f, C.MUTED)
        c.addView(st)
        h.addView(c, lp(0, WRAP, 1f))
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(btns, lp(WRAP, WRAP).apply { marginStart = a.dp(6) })
        h.addView(a.icon(if (open) R.drawable.ic_chev_left else R.drawable.ic_chev_right, C.FAINT, 18).apply { rotation = if (open) -90f else 0f })
        card.addView(h)
        groups[key] = GroupRefs(key, b, meta, st, btns, "")
        fillGroup(key)
        if (open) ds.forEach { card.addView(row(it, false).apply { setPadding(a.dp(18), 0, 0, 0) }) }
        else card.addView(a.divider())
        col.addView(card, lp(MATCH, WRAP).apply { topMargin = a.dp(8) })
    }

    private fun groupItems(key: String) = items().filter { it.kind == "episode" && groupKey(it) == key }

    private fun fillGroup(key: String) {
        val g = groups[key] ?: return
        val ds = groupItems(key)
        if (ds.isEmpty()) return
        val n = ds.size
        val done = ds.count { it.status == "completed" }
        val failed = ds.count { it.status == "failed" }
        val running = ds.filter { it.status == "downloading" }
        val total = ds.sumOf { it.total }
        val got = ds.sumOf { if (it.status == "completed") it.total else it.received }
        g.bar.progress = if (total > 0) (got * 1000 / total).toInt() else done * 1000 / n
        g.bar.visibility = if (done == n) View.GONE else View.VISIBLE
        g.meta.text = "$done من $n حلقات مكتملة" + (if (total > 0 && done < n) " · ${fmtBytes(got)} من ${fmtBytes(total)}" else if (done == n) " · ${fmtBytes(total)}" else "")
        val speed = running.sumOf { it.speed }
        g.status.setTextColor(if (failed > 0) C.LIVE else if (done == n) 0xFF6FCF97.toInt() else C.MUTED)
        g.status.text = when {
            done == n -> "✓ اكتمل الموسم — يشتغل بدون إنترنت"
            running.any { it.waiting } -> "متوقف أثناء المشاهدة"
            running.isNotEmpty() -> if (speed > 0) "جاري التحميل · السرعة ${fmtBytes(speed)}" else "جاري التحميل…"
            failed > 0 -> "$failed حلقة فشل تحميلها"
            ds.any { it.status == "queued" } -> "في الانتظار"
            else -> "متوقف مؤقتاً"
        }
        val anyRun = ds.any { it.active }
        val anyPaused = ds.any { it.status == "paused" || it.status == "failed" }
        val shown = "$anyRun/$anyPaused/${done == n}"
        if (shown != g.shown) {
            g.shown = shown
            g.btns.removeAllViews()
            fun add(ic: Int, desc: String, act: () -> Unit) {
                g.btns.addView(a.iconButton(ic, desc) { act() }.apply { tag = "gbtn:$key:$desc" }, lp(a.dp(44), a.dp(44)))
            }
            if (anyRun) add(R.drawable.ic_pause, "إيقاف الموسم") { groupItems(key).filter { it.active }.forEach { Downloads.pause(it.id) } }
            else if (anyPaused) add(R.drawable.ic_play, "استئناف الموسم") { groupItems(key).filter { it.status == "paused" || it.status == "failed" }.forEach { Downloads.resume(it.id) } }
            add(R.drawable.ic_trash, if (done == n) "حذف الموسم" else "إلغاء الموسم") {
                confirm(if (done == n) "حذف كل حلقات الموسم من الجهاز؟" else "إلغاء الحلقات اللي ما اكتملت؟ (المكتملة تبقى)") {
                    groupItems(key).filter { done == n || it.status != "completed" }.forEach { Downloads.remove(it.id) }
                }
            }
        }
    }

    private fun update(d: Dl) {
        if (rows.containsKey(d.id)) fill(d)
        if (d.kind == "episode") fillGroup(groupKey(d))
    }

    // ---------- إجراءات ----------
    private fun play(d: Dl) {
        val ep = d.kind == "episode"
        a.startActivity(Intent(a, PlayerActivity::class.java)
            .putExtra("url", d.url).putExtra("key", d.key)
            .putExtra("title", if (ep) d.group else d.title)
            .putExtra("sub", if (ep) "الموسم ${d.season} · الحلقة ${d.epNum}" else "")
            .putExtra("image", d.poster).putExtra("type", if (ep) "episode" else "movie")
            .putExtra("seriesId", d.seriesId).putExtra("start", Store.resumePoint(d.key)))
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(msg).setPositiveButton("نعم") { _, _ -> ok() }.setNegativeButton("لا", null).show()
    }

    private fun chooseConnections() {
        val opts = arrayOf(1, 2, 4, 6, 8)
        val labels = opts.map { if (it == 1) "متوقف (اتصال واحد)" else "$it اتصالات" }.toTypedArray()
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("مسرّع التحميل")
            .setSingleChoiceItems(labels, opts.indexOf(Downloads.connections).coerceAtLeast(0)) { dlg, w -> Downloads.connections = opts[w]; dlg.dismiss(); rebuild() }
            .show()
    }

    private fun chooseParallel() {
        val opts = arrayOf("1", "2", "3", "4")
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("كم ملف يتحمّل بنفس الوقت؟")
            .setSingleChoiceItems(opts, Downloads.maxParallel - 1) { dlg, w -> Downloads.maxParallel = w + 1; dlg.dismiss(); rebuild() }
            .show()
    }
}
