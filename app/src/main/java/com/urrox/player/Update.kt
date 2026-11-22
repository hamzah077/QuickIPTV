package com.urrox.player

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * تحديث القوائم بالترتيب: البث المباشر ← الأفلام ← المسلسلات، وكل خطوة بعدّادها.
 * - أول دخول بعد تسجيل الحساب: صفحة كاملة، ولما تخلص تدخل التطبيق.
 * - من الإعدادات: نافذة منبثقة (تقدر تخفيها والتحديث يكمل بالخلفية).
 */
object ListsUpdate {
    class Step(val title: String, val unit: String) {
        var state = 0          // 0 بالانتظار، 1 يتحدّث، 2 خلص، 3 تعذّر
        var frac = 0f
        var bytes = 0L
        var count = 0
    }

    val steps = listOf(Step("البث المباشر", "قناة"), Step("الأفلام", "فيلم"), Step("المسلسلات", "مسلسل"))
    var running = false
        private set
    private var listener: (() -> Unit)? = null
    private var job: Job? = null

    val total: Int get() = (steps.sumOf { (if (it.state >= 2) 1f else it.frac).toDouble() } / steps.size * 100).toInt()

    /** يبدأ التحديث (لو شغّال: يكمل نفسه). onDone ينادى بالخيط الرئيسي لما يخلص. */
    fun start(a: MainActivity, onDone: () -> Unit) {
        if (running) return
        val api = Session.api ?: return onDone()
        running = true
        steps.forEach { it.state = 0; it.frac = 0f; it.bytes = 0; it.count = 0 }
        notifyUi()
        job = a.scope.launch {
            run(steps[0]) { p -> api.liveCategories(true); api.liveStreams(true, p).size }
            run(steps[1]) { p -> api.vodCategories(true); api.vods(false, true, p).size }
            run(steps[2]) { p -> api.seriesCategories(true); api.vods(true, true, p).size }
            SearchIndex.clear()
            if (steps.any { it.state == 2 }) Store.currentId.takeIf { it.isNotEmpty() }?.let { Store.markListsUpdated(it) }
            running = false
            notifyUi()
            onDone()
        }
    }

    private suspend fun run(s: Step, load: ((Long, Long) -> Unit) -> Int) {
        s.state = 1; notifyUi()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            val n = withContext(Bg.io) {
                load { got, total ->
                    // التنزيل = 90% من الخطوة (الباقي قراءة القائمة). لو السيرفر ما قال الحجم: تقريب يقرب من 90% بالتدريج
                    val f = if (total > 0) got.toFloat() / total else 1f - kotlin.math.exp(-got / 4_000_000.0).toFloat()
                    main.post { s.bytes = got; s.frac = (f * 0.9f).coerceIn(0f, 0.9f); notifyUi() }
                }
            }
            s.count = n; s.frac = 1f; s.state = 2
        } catch (e: Exception) {
            s.state = 3
        }
        notifyUi()
    }

    fun listen(l: (() -> Unit)?) { listener = l; l?.invoke() }
    private fun notifyUi() { listener?.invoke() }
}

/** صفوف الخطوات الثلاث (تنستخدم بالصفحة الكاملة والنافذة المنبثقة). */
class UpdateRows(private val a: MainActivity, big: Boolean) {
    val view = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private class Row(val icon: TextView, val count: TextView, val bar: ProgressBar)
    private val rows = ListsUpdate.steps.map { s ->
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            background = rounded(0x0DFFFFFF, a.dpf(14f))
        }
        val ic = a.text("•", 14f, C.MUTED, bold = true).apply { gravity = Gravity.CENTER }
        r.addView(ic, lp(a.dp(30), a.dp(30)).apply { marginEnd = a.dp(10) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(a.text(s.title, if (big) 15f else 14f, C.TEXT, bold = true), lp(0, WRAP, 1f))
        val cnt = a.text("بالانتظار", 12.5f, C.MUTED)
        top.addView(cnt)
        col.addView(top)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(C.LIVE)
            progressBackgroundTintList = ColorStateList.valueOf(0x1AFFFFFF)
        }
        col.addView(bar, lp(MATCH, a.dp(4)).apply { topMargin = a.dp(6) })
        r.addView(col, lp(0, WRAP, 1f))
        view.addView(r, lp(MATCH, WRAP).apply { bottomMargin = a.dp(8) })
        Row(ic, cnt, bar)
    }

    fun refresh() {
        ListsUpdate.steps.forEachIndexed { i, s ->
            val r = rows[i]
            when (s.state) {
                2 -> { r.icon.text = "✓"; r.icon.setTextColor(0xFF6FCF97.toInt()); r.icon.background = rounded(0xFF1F4D33.toInt(), a.dpf(15f)) }
                1 -> { r.icon.text = "⟳"; r.icon.setTextColor(C.TEXT); r.icon.background = rounded(0x1FFFFFFF, a.dpf(15f)) }
                3 -> { r.icon.text = "!"; r.icon.setTextColor(C.LIVE); r.icon.background = rounded(0x33E5484D, a.dpf(15f)) }
                else -> { r.icon.text = "•"; r.icon.setTextColor(C.MUTED); r.icon.background = rounded(0x0DFFFFFF, a.dpf(15f)) }
            }
            r.count.text = when (s.state) {
                2 -> "%,d ${s.unit}".format(java.util.Locale.US, s.count)
                1 -> if (s.frac >= 0.9f) "نرتّب…" else if (s.bytes > 0) "%.1f م.ب".format(java.util.Locale.US, s.bytes / 1_048_576.0) else "نتصل…"
                3 -> "تعذّر — بنستخدم الموجود"
                else -> "بالانتظار"
            }
            r.bar.progress = ((if (s.state >= 2) 1f else s.frac) * 1000).toInt()
        }
    }
}

/** أول دخول: صفحة كاملة فيها النسبة والخطوات، ولما تخلص تدخل التطبيق. */
fun MainActivity.showFirstUpdate(acc: Account) {
    Session.open(this, acc)
    val pct = text("0٪", 40f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    val sub = text("نجهّز قوائمك…", 14f, C.MUTED).apply { gravity = Gravity.CENTER }
    val rows = UpdateRows(this, big = true)
    val root = FrameLayout(this).apply { setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    if (tv) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(60), 0, dp(60), 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        left.addView(pct); left.addView(sub)
        row.addView(left, lp(0, WRAP, 0.4f))
        row.addView(rows.view, lp(0, WRAP, 0.6f))
        root.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
    } else {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(70), dp(20), dp(20)) }
        col.addView(pct, lp(MATCH, WRAP)); col.addView(sub, lp(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(28) })
        col.addView(rows.view, lp(MATCH, WRAP))
        root.addView(col, FrameLayout.LayoutParams(minOf(dp(520), resources.displayMetrics.widthPixels), WRAP, Gravity.CENTER_HORIZONTAL))
        root.addView(text("أول مرة بس — بعدها يفتح على طول", 12.5f, C.MUTED).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(28) })
    }
    // السيرفر بطيء؟ تخطّي = تدخل التطبيق والتحديث يكمل بالخلفية
    var entered = false
    val skip = button("تخطّي — كمّل بالخلفية") {
        if (entered) return@button
        entered = true; ListsUpdate.listen(null); enter(acc, checkLogin = false)
    }
    root.addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(if (tv) 40 else 64) })
    root.padForSystemBars()
    setContentView(root)
    ListsUpdate.listen { pct.text = "${ListsUpdate.total}٪"; rows.refresh() }
    if (tv) skip.post { skip.requestFocus() }
    ListsUpdate.start(this) {
        if (entered) {             // دخلت قبل: نحدّث الأقسام بالقوائم الجديدة بس
            listOf("home", "live", "movie", "series").forEach { dropScreen(it) }
            toast("تحدّثت القوائم")
            return@start
        }
        ListsUpdate.listen(null)
        pct.text = "100٪"; sub.text = "جاهز"
        scope.launch { delay(500); if (!entered) { entered = true; enter(acc, checkLogin = false) } }
    }
}

/** التحديث التلقائي (حسب الإعدادات): بالخلفية بدون نافذة، وتنبيه صغير لما يخلص */
fun MainActivity.autoUpdateIfDue(acc: Account): Boolean {
    if (!Store.autoUpdateDue(acc.id)) return false
    ListsUpdate.start(this) {
        listOf("home", "live", "movie", "series").forEach { dropScreen(it) }
        toast("تحدّثت القوائم تلقائياً")
    }
    return true
}

/** من الإعدادات: نافذة منبثقة (إخفاء = يكمل بالخلفية، وتنبيه لما يخلص). */
fun MainActivity.showUpdatePopup(onDone: () -> Unit) {
    val d = Dialog(this)
    val pct = text("0٪", 13f, C.MUTED)
    val rows = UpdateRows(this, big = false)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(16), dp(16), dp(14))
        background = rounded(0xFF1C1F25.toInt(), dpf(20f))
    }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(text("تحديث القوائم", 16f, C.TEXT, bold = true), lp(0, WRAP, 1f))
    head.addView(pct)
    box.addView(head, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
    box.addView(rows.view, lp(MATCH, WRAP))
    val hide = button("إخفاء") { d.dismiss() }
    box.addView(hide, lp(MATCH, WRAP).apply { topMargin = dp(4) })
    d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    d.setContentView(box)
    d.window?.setBackgroundDrawable(ColorDrawable(0))
    d.window?.setLayout(minOf(dp(if (tv) 460 else 380), resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    d.setOnDismissListener { ListsUpdate.listen(null) }
    d.show()
    hide.requestFocus()
    ListsUpdate.listen { pct.text = "${ListsUpdate.total}٪"; rows.refresh() }
    ListsUpdate.start(this) {
        onDone()
        if (d.isShowing) { pct.text = "100٪"; scope.launch { delay(1200); if (d.isShowing) d.dismiss() } }
        toast("تحدّثت القوائم")
    }
}
