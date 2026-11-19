package com.quickiptv.app

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
        /** القنوات (أقصى 300) والأفلام/المسلسلات (أقصى 80)، اللي تبدأ بالكلمة أول. */
        suspend fun find(q: String): Pair<List<Channel>, List<Vod>> {
            val ch = pick(liveN, q, 300).map { live[it] }
            val vs = pick(vodsN, q, 80).map { vods[it] }
            return ch to vs
        }

        private suspend fun pick(names: Array<String>, q: String, max: Int): List<Int> {
            val starts = ArrayList<Int>()
            val other = ArrayList<Int>()
            for (i in names.indices) {
                if (i and 4095 == 0) coroutineContext.ensureActive()     // حرف جديد = نوقف البحث القديم فوراً
                val n = names[i]
                if (n.startsWith(q)) { starts.add(i); if (starts.size >= max) break }
                else if (other.size < max && n.contains(q)) other.add(i)
            }
            return (starts + other).take(max)
        }
    }

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
