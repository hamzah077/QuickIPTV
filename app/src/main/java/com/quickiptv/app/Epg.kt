package com.quickiptv.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
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
        val dir = File(cacheDir, "epg").apply { mkdirs() }
        val files = urls.map { u -> u to File(dir, "${u.hashCode().toUInt()}.xml") }
        var errors = 0
        // 1) نحدّث الملفات القديمة من النت (تنزيل مباشر للملف — ما ينحمّل كامل بالذاكرة)
        for ((u, f) in files) {
            try {
                if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > TTL || f.length() < 100) Xtream.httpToFile(u, f, 120000)
            } catch (e: Exception) {
                if (!f.exists()) errors++
            }
        }
        // 2) لو قرأناها قبل ونفس الملفات → نسخة جاهزة (بدون ما نعيد قراءة XML)
        val sig = files.joinToString("|") { (u, f) -> "$u:${f.lastModified()}:${f.length()}" }
        val bin = File(dir, "parsed_${sig.hashCode().toUInt()}.v1.bin")
        if (!force && readParsed(bin)) {
            status = statusText(errors, urls.size, 0L)
            return status
        }
        // 3) نقرا XML (بطيء نسبياً) ونحفظ النتيجة للمرة الجاية
        val progs = HashMap<String, MutableList<Program>>()
        val names = HashMap<String, String>()
        val labs = HashMap<String, String>()
        var lastSeen = 0L
        for ((_, f) in files) {
            if (!f.exists()) continue
            try { lastSeen = maxOf(lastSeen, parse(f.inputStream(), progs, names, labs)) } catch (e: Exception) { errors++ }
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
        apply(progs, labs, ids, full, core)
        dir.listFiles()?.filter { it.name.startsWith("parsed_") && it != bin }?.forEach { it.delete() }
        writeParsed(bin, progs, labs, ids, full, core)
        status = statusText(errors, urls.size, lastSeen)
        return status
    }

    private fun statusText(errors: Int, n: Int, lastSeen: Long): String {
        val now = System.currentTimeMillis() / 1000
        return when {
            programs.isNotEmpty() -> "الدليل فيه ${programs.size} قناة"
            errors >= n && n > 0 -> "تعذر تحميل الدليل"
            lastSeen in 1 until now -> "الدليل قديم — جرّب رابط ثاني"
            else -> "الدليل فاضي"
        }
    }

    private fun apply(progs: Map<String, List<Program>>, labs: Map<String, String>, ids: HashMap<String, String>, full: HashMap<String, String>, core: HashMap<String, String>) {
        programs = progs; labels = labs; byId = ids; byFull = full; byCore = core
        matchCache.clear()
        loaded = true
    }

    // ---------- نسخة جاهزة على الجهاز (ثنائية) ----------
    private fun writeParsed(f: File, progs: Map<String, List<Program>>, labs: Map<String, String>, vararg maps: Map<String, String>) {
        runCatching {
            val tmp = File(f.path + ".tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered(64 * 1024)).use { o ->
                o.writeLong(System.currentTimeMillis())
                o.writeInt(progs.size)
                progs.forEach { (cid, list) ->
                    o.writeUTF(cid); o.writeInt(list.size)
                    list.forEach { p -> o.writeUTF(p.title.take(300)); o.writeUTF(p.desc); o.writeLong(p.start); o.writeLong(p.end) }
                }
                for (m in listOf(labs) + maps) { o.writeInt(m.size); m.forEach { (k, v) -> o.writeUTF(k.take(500)); o.writeUTF(v.take(500)) } }
            }
            tmp.renameTo(f)
        }
    }

    private fun readParsed(f: File): Boolean {
        if (!f.exists()) return false
        return runCatching {
            java.io.DataInputStream(f.inputStream().buffered(64 * 1024)).use { i ->
                val at = i.readLong()
                if (System.currentTimeMillis() - at > 8 * 3600 * 1000L) return false     // قديمة (برامج اليوم تغيّرت)
                val n = i.readInt()
                val progs = HashMap<String, List<Program>>(n * 2)
                repeat(n) {
                    val cid = i.readUTF(); val c = i.readInt()
                    progs[cid] = List(c) { Program(i.readUTF(), i.readUTF(), i.readLong(), i.readLong()) }
                }
                fun map(): HashMap<String, String> { val m = i.readInt(); return HashMap<String, String>(m * 2).apply { repeat(m) { put(i.readUTF(), i.readUTF()) } } }
                val labs = map(); val ids = map(); val full = map(); val core = map()
                apply(progs, labs, ids, full, core)
                true
            }
        }.getOrDefault(false)
    }

    /** وقت XMLTV مثل "20261119180000 +0300" → ثواني (أسرع بكثير من SimpleDateFormat). */
    private fun time(s: String?): Long {
        if (s == null || s.length < 14) return 0
        fun num(a: Int, b: Int): Int { var v = 0; for (k in a until b) { val c = s[k]; if (c !in '0'..'9') return -1; v = v * 10 + (c - '0') }; return v }
        val y = num(0, 4); val mo = num(4, 6); val d = num(6, 8); val h = num(8, 10); val mi = num(10, 12); val se = num(12, 14)
        if (y < 0 || mo < 1 || d < 1 || h < 0 || mi < 0 || se < 0) return 0
        // أيام من 1970 (خوارزمية days-from-civil)
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468
        var t = days * 86400 + h * 3600 + mi * 60 + se
        // المنطقة الزمنية (+0300)
        val z = s.indexOfAny(charArrayOf('+', '-'), 14)
        if (z > 0 && z + 5 <= s.length) {
            val zh = num(z + 1, z + 3); val zm = num(z + 3, z + 5)
            if (zh >= 0 && zm >= 0) { val off = zh * 3600 + zm * 60; t -= if (s[z] == '+') off else -off }
        }
        return t
    }

    private val rxLabel = Regex("(\\.[A-Za-z]{2,3})?(@\\w+)?$")

    private fun parse(raw: InputStream, progs: HashMap<String, MutableList<Program>>,
                      names: HashMap<String, String>, labs: HashMap<String, String>): Long {
        val bin = BufferedInputStream(raw, 64 * 1024)
        bin.mark(2)
        val gz = bin.read() == 0x1f && bin.read() == 0x8b
        bin.reset()
        val input: InputStream = if (gz) GZIPInputStream(bin, 64 * 1024) else bin
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
            var inProg = false; var inChan = false; var keep = false
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
                            if (stop > last) last = stop
                            keep = start > 0 && stop > lo && start < hi        // برامج خارج اليوم: ما نقرا عنوانها ولا وصفها
                            title = ""; desc = ""
                        }
                        "title" -> if (inProg && keep && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (inProg && keep && desc.isEmpty()) desc = p.nextText().trim().take(300)
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            if (cid !in labs) labs[cid] = cid.replace(rxLabel, "")
                            inChan = false
                        }
                        "programme" -> {
                            if (inProg && keep) progs.getOrPut(cid) { ArrayList() }.add(Program(title, desc, start, stop))
                            inProg = false
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
    /** نتيجة المطابقة لكل قناة (المطابقة ثقيلة — نحسبها مرة وحدة بس لكل قناة). "" = ما فيه. */
    private val matchCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun programsFor(ch: Channel): List<Program>? {
        val manual = Store.epgMap()[ch.id]
        if (manual != null) return if (manual.isEmpty()) emptyList() else programs[manual]
        if (!loaded) return null
        val cid = matchCache.getOrPut(ch.id) { match(ch.epgId, ch.name) ?: "" }
        return if (cid.isEmpty()) null else programs[cid]
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
