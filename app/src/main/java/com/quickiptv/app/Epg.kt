package com.quickiptv.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * دليل برامج خارجي (XMLTV) + تعرّف تلقائي على القنوات.
 * نفس قواعد نسخة الكمبيوتر: يتجاهل - و _ والفراغات وحالة الأحرف والهمزات، والجودة (HD/4K)،
 * وبادئة الدولة (AR:)، و"Backup 1"، ويوحّد Sports/Sport و"بي ان"/beIN…
 * القراءة بالتدفق (XmlPullParser) وما نحفظ إلا برامج اليوم تقريباً → ذاكرة قليلة.
 */
object Epg {
    val DEFAULT_URLS = listOf(
        "https://al7omed.github.io/bein-epg/guide.xml",
        "https://raw.githubusercontent.com/Saudi23723/Unified-MENA-EPG/main/bein_sports_qatar_epg.xml",
    )
    private const val TTL = 12 * 3600 * 1000L

    @Volatile private var programs: Map<String, List<Program>> = emptyMap()
    @Volatile private var labels: Map<String, String> = emptyMap()
    @Volatile private var byId = HashMap<String, String>()
    @Volatile private var byFull = HashMap<String, String>()
    @Volatile private var byCore = HashMap<String, String>()
    @Volatile var loaded = false
        private set
    var status: String = ""
        private set

    // ---------- مفاتيح المطابقة ----------
    private val TAGS = "fhd|uhd|hd|sd|hq|4k|8k|hevc|h265|h264|1080p?|720p?|2160p?|raw|vip|plus|multi|ott|live|tv|ch|channel|قناة|قناه|arabic|عربي"
    private val rxPrefix = Regex("^\\s*[\\[(|]?\\s*(?:[a-z]{2}|ksa|uae|usa|ara|arb|gcc|mena)\\s*[\\])|:]+\\s*[-|:]*\\s*(?=\\S)")
    private val rxBackup = Regex("(?:\\(|\\[)?\\b(?:(?:backup|bkp|bk|alt)\\s*\\d*|b\\s*\\d+)(?:\\)|\\])?\\s*$")
    private val rxSep = Regex("[^\\p{L}\\p{N}]+")          // كل شي غير الحروف (عربي/إنجليزي) والأرقام
    private val rxTag = Regex("(?<![\\p{L}\\p{N}])(?:$TAGS)(?![\\p{L}\\p{N}])")
    private val rxTail = Regex("(?:fhd|uhd|hd|sd|4k|8k|hevc|tv)+$")
    private val syn = listOf(
        Regex("\\bsports\\b") to "sport", Regex("(?:بي|بى)\\s*(?:ان|إن|أن)\\s*") to "bein ",
        Regex("\\bbe\\s*in\\b") to "bein", Regex("سبورت(?:س)?") to "sport", Regex("ماكس") to "max",
        Regex("اكسترا|إكسترا") to "xtra", Regex("\\bextra\\b") to "xtra", Regex("نيوز") to "news",
    )

    fun norm(s: String): String {
        var n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        n = n.replace(Regex("[\\u064B-\\u0652\\u0640]"), "")              // التشكيل والتطويل
            .replace(Regex("[أإآ]"), "ا").replace('ة', 'ه').replace('ى', 'ي')
        return n
    }

    private fun base(s: String): String {
        var n = norm(s).replace(Regex("[_\\-.]+"), " ")
        n = rxPrefix.replace(n, "")
        n = rxBackup.replace(n, "")
        syn.forEach { (r, v) -> n = r.replace(n, v) }
        return n.replace(Regex("(?<![0-9])0+(?=[1-9])"), "")
    }

    /** (كامل، أساسي) */
    fun keys(s: String): Pair<String, String> {
        val n = base(s)
        val full = rxSep.replace(n, "")
        val core = rxTail.replace(rxSep.replace(rxTag.replace(n, " "), ""), "")
        return full to core.ifEmpty { full }
    }

    fun idKey(cid: String): String {
        var c = norm(cid).replace(Regex("@[a-z0-9]+$"), "").replace(Regex("\\.[a-z]{2,3}$"), "")
        c = c.replace(Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"), " ").replace("sports", "sport")
        val k = rxTail.replace(rxSep.replace(rxTag.replace(base(c), " "), ""), "")
        return k.ifEmpty { rxSep.replace(c, "") }
    }

    // ---------- التحميل ----------
    /** يحمّل كل الأدلة (من الجهاز لو جديدة، وإلا من النت). يرجّع رسالة حالة. */
    fun load(urls: List<String>, cacheDir: File, force: Boolean = false): String {
        val progs = HashMap<String, MutableList<Program>>()
        val names = HashMap<String, String>()
        val labs = HashMap<String, String>()
        var errors = 0
        var lastSeen = 0L
        for (u in urls) {
            try {
                val f = File(cacheDir, "epg/${u.hashCode().toUInt()}.xml")
                if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > TTL || f.length() < 100) {
                    val bytes = Xtream.http(u, 90000)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                }
                lastSeen = maxOf(lastSeen, parse(f.inputStream(), progs, names, labs))
            } catch (e: Exception) {
                errors++
            }
        }
        progs.values.forEach { it.sortBy { p -> p.start } }
        val ids = HashMap<String, String>(); val full = HashMap<String, String>(); val core = HashMap<String, String>()
        progs.keys.forEach { cid -> idKey(cid).takeIf { it.isNotEmpty() }?.let { ids.putIfAbsent(it, cid) } }
        names.forEach { (n, cid) ->
            if (cid !in progs) return@forEach
            val (f, c) = keys(n)
            if (f.isNotEmpty()) full.putIfAbsent(f, cid)
            if (c.isNotEmpty()) core.putIfAbsent(c, cid)
        }
        programs = progs; labels = labs; byId = ids; byFull = full; byCore = core
        loaded = true
        val now = System.currentTimeMillis() / 1000
        status = when {
            progs.isNotEmpty() -> "الدليل فيه ${progs.size} قناة"
            errors == urls.size && urls.isNotEmpty() -> "تعذر تحميل الدليل"
            lastSeen in 1 until now -> "الدليل قديم — جرّب رابط ثاني"
            else -> "الدليل فاضي"
        }
        return status
    }

    private val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        return try {
            val t = s.trim()
            (if (t.length >= 20) fmt.parse(t) else fmt.parse(t.take(14) + " +0000"))!!.time / 1000
        } catch (e: Exception) { 0 }
    }

    /** يقرأ ملف XMLTV (مضغوط أو لا). يرجّع آخر وقت انتهاء شافه (عشان نعرف لو الدليل قديم). */
    private fun parse(raw: InputStream, progs: HashMap<String, MutableList<Program>>,
                      names: HashMap<String, String>, labs: HashMap<String, String>): Long {
        val bin = BufferedInputStream(raw)
        bin.mark(2)
        val gz = bin.read() == 0x1f && bin.read() == 0x8b
        bin.reset()
        val input: InputStream = if (gz) GZIPInputStream(bin) else bin
        val now = System.currentTimeMillis() / 1000
        val lo = now - 6 * 3600
        val hi = now + 30 * 3600
        var last = 0L
        input.use { ins ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(ins, null)
            var ev = p.eventType
            var cid = ""; var start = 0L; var stop = 0L; var title = ""; var desc = ""
            var inProg = false; var inChan = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { inChan = true; cid = p.getAttributeValue(null, "id") ?: "" }
                        "display-name" -> if (inChan) {
                            val t = p.nextText().trim()
                            if (t.isNotEmpty() && !t.all { it.isDigit() }) {
                                names.putIfAbsent(norm(t), cid)
                                labs.putIfAbsent(cid, t)
                            }
                        }
                        "programme" -> {
                            inProg = true
                            cid = p.getAttributeValue(null, "channel") ?: ""
                            start = time(p.getAttributeValue(null, "start"))
                            stop = time(p.getAttributeValue(null, "stop"))
                            title = ""; desc = ""
                        }
                        "title" -> if (inProg && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (inProg && desc.isEmpty()) desc = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            if (cid !in labs) labs[cid] = cid.replace(Regex("(\\.[A-Za-z]{2,3})?(@\\w+)?$"), "")
                            inChan = false
                        }
                        "programme" -> {
                            inProg = false
                            if (stop > last) last = stop
                            if (start > 0 && stop > lo && start < hi) {
                                progs.getOrPut(cid) { ArrayList() }.add(Program(title, desc.take(300), start, stop))
                            }
                        }
                    }
                }
                ev = p.next()
            }
        }
        return last
    }

    // ---------- المطابقة ----------
    fun match(epgId: String, name: String): String? {
        if (epgId.isNotEmpty()) {
            if (epgId in programs) return epgId
            programs.keys.firstOrNull { it.equals(epgId, true) }?.let { return it }
            val k = idKey(epgId)
            (byId[k] ?: byFull[k])?.let { return it }
        }
        if (name.isEmpty()) return null
        val (full, core) = keys(name)
        byFull[full]?.let { return it }
        byId[full]?.let { return it }
        byCore[core]?.let { return it }
        byId[core]?.let { return it }
        // اسم الدليل أطول بكلمة (beIN Movies 1 ↔ beINMovies1Premiere) — بشرط تكون قناة وحدة بس
        if (core.length >= 6 && core.last().isDigit()) {
            val hits = HashSet<String>()
            for ((k, v) in byId) if (k.startsWith(core) && (k.length == core.length || !k[core.length].isDigit())) hits.add(v)
            for ((k, v) in byCore) if (k.startsWith(core) && (k.length == core.length || !k[core.length].isDigit())) hits.add(v)
            if (hits.size == 1) return hits.first()
        }
        return null
    }

    /** برامج القناة: الربط اليدوي أول، بعده التعرّف التلقائي. null = ما فيه (نستخدم دليل السيرفر). */
    fun programsFor(ch: Channel): List<Program>? {
        val manual = Store.epgMap()[ch.id]
        if (manual != null) return if (manual.isEmpty()) emptyList() else programs[manual]
        if (!loaded) return null
        val cid = match(ch.epgId, ch.name) ?: return null
        return programs[cid]
    }

    fun mode(ch: Channel): String {
        val manual = Store.epgMap()[ch.id]
        return when {
            manual == null -> "auto"
            manual.isEmpty() -> "none"
            else -> "manual"
        }
    }

    fun nowFor(ch: Channel): Program? = programsFor(ch)?.firstOrNull { it.isNow }

    fun channels(): List<Pair<String, String>> = programs.keys.map { it to (labels[it] ?: it) }.sortedBy { it.second.lowercase() }

    fun programsOf(cid: String): List<Program> = programs[cid] ?: emptyList()

    fun stats(chans: List<Channel>): Pair<Int, Int> = chans.count { programsFor(it)?.isNotEmpty() == true } to chans.size
}
