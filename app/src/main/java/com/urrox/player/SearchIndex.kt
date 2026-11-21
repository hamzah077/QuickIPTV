package com.urrox.player

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * فهرس بحث خفيف: كل اسم ينحفظ مطبّع مرة وحدة (حروف صغيرة، بدون تشكيل/مسافات/رموز، أ=إ=آ=ا، ة=ه، ى=ي)،
 * وبعدها كل حرف تكتبه = مرور سريع على نصوص جاهزة بدل معالجة عشرات الآلاف من الأسماء كل مرة.
 */
object SearchIndex {
    class Idx(
        val api: Xtream,
        val live: List<Channel>, val liveN: Array<String>,
        val vods: List<Vod>, val vodsN: Array<String>,
    ) {
        /** القنوات (أقصى 300) والأفلام/المسلسلات (أقصى 80). q = نص البحث كما كتبه المستخدم. */
        suspend fun find(q: String): Pair<List<Channel>, List<Vod>> {
            val tokens = tokens(q)
            val ch = pick(liveN, tokens, 300).map { live[it] }
            val vs = pick(vodsN, tokens, 80).map { vods[it] }
            return ch to vs
        }

        private suspend fun pick(names: Array<String>, tokens: List<String>, max: Int): List<Int> =
            pickIn(names, tokens, max) { i -> if (i and 4095 == 0) coroutineContext.ensureActive() }   // حرف جديد = نوقف البحث القديم فوراً
    }

    /**
     * بحث بأي جزء من الاسم: كل كلمة تكتبها لازم تكون موجودة بأي مكان بالاسم وبأي ترتيب.
     * الترتيب: اللي يبدأ بالبحث أول، ثم اللي فيه البحث متصل زي ما كتبته، ثم الباقي.
     */
    inline fun pickIn(names: Array<String>, tokens: List<String>, max: Int, tick: (Int) -> Unit = {}): List<Int> {
        if (tokens.isEmpty()) return emptyList()
        val joined = tokens.joinToString("")
        val starts = ArrayList<Int>(); val exact = ArrayList<Int>(); val other = ArrayList<Int>()
        for (i in names.indices) {
            tick(i)
            val n = names[i]
            if (!tokens.all { n.contains(it) }) continue
            when {
                n.startsWith(joined) || n.startsWith(tokens[0]) -> { starts.add(i); if (starts.size >= max) break }
                n.contains(joined) -> if (exact.size < max) exact.add(i)
                else -> if (other.size < max) other.add(i)
            }
        }
        return (starts + exact + other).take(max)
    }

    /** كلمات البحث مطبّعة (الفواصل والرموز تفصل بين الكلمات). */
    fun tokens(q: String): List<String> =
        q.split(Regex("[^\\p{L}\\p{N}]+")).map { norm(it) }.filter { it.isNotEmpty() }

    /** الاسم يطابق كل كلمات البحث (بأي مكان وبأي ترتيب)؟ */
    fun matches(normName: String, tokens: List<String>) = tokens.all { normName.contains(it) }

    @Volatile private var idx: Idx? = null
    private val lock = Any()

    fun readyFor(api: Xtream) = idx?.api === api

    /** يبني الفهرس مرة وحدة لكل حساب (والقوائم نفسها من الكاش). */
    fun build(api: Xtream): Idx {
        idx?.takeIf { it.api === api }?.let { return it }
        synchronized(lock) {
            idx?.takeIf { it.api === api }?.let { return it }
            val live = runCatching { api.liveStreams() }.getOrDefault(emptyList())
            val vods = runCatching { api.vods(false) }.getOrDefault(emptyList()) + runCatching { api.vods(true) }.getOrDefault(emptyList())
            val built = Idx(api, live, Array(live.size) { norm(live[it].name) }, vods, Array(vods.size) { norm(vods[it].name) })
            idx = built
            return built
        }
    }

    fun clear() { idx = null }

    /** تطبيع سريع حرف حرف (بدون Regex). */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> '0' + (ch - '٠')      // أرقام عربية = إنجليزية
                else -> ch
            }
            if (c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ') continue      // تشكيل وتطويل
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c))
        }
        return sb.toString()
    }
}
