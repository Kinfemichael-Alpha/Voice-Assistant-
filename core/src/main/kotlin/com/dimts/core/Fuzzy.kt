package com.dimts.core

/** Tiny "did you mean ...?" helper shared by the command registry and the script validator. */
object Fuzzy {
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    fun soundKey(text: String): String {
        var s = text.lowercase().filter { it.isLetterOrDigit() }
        if (s.length > 3 && s.endsWith("e")) s = s.dropLast(1)
        s = s.replace("ph", "f").replace("ck", "k").replace("x", "ks")
            .replace('c', 'k').replace('q', 'k').replace('z', 's')
        val sb = StringBuilder()
        var lastVowel = false
        for (ch in s) {
            val vowel = ch in "aeiouy"
            if (vowel) {
                if (!lastVowel) sb.append('a')
            } else if (sb.isEmpty() || sb.last() != ch) {
                sb.append(ch)
            }
            lastVowel = vowel
        }
        return sb.toString()
    }

    fun rank(query: String, candidates: Collection<String>, limit: Int = 5): List<String> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        val qKey = soundKey(q)
        val scored = candidates.distinct().mapNotNull { c ->
            val l = c.lowercase()
            val gap = Math.abs(l.length - q.length)
            val d = minOf(distance(q, l), distance(qKey, soundKey(l)))
            val tolerance = maxOf(2, maxOf(q.length, l.length) / 3)
            val score = when {
                l == q -> 0
                l.startsWith(q) || q.startsWith(l) -> 10 + gap
                l.contains(q) || q.contains(l) -> 40 + gap
                d <= tolerance -> 100 + d * 10 + gap
                else -> return@mapNotNull null
            }
            Triple(c, score, l.length)
        }
        return scored.sortedWith(compareBy({ it.second }, { it.third })).take(limit).map { it.first }
    }

    fun closest(word: String, candidates: Collection<String>): String? {
        val w = word.lowercase()
        val limit = when {
            w.length <= 3 -> 1
            w.length <= 7 -> 2
            else -> 3
        }
        return candidates
            .map { it to distance(w, it.lowercase()) }
            .filter { it.second <= limit }
            .minByOrNull { it.second }
            ?.first
    }
}
