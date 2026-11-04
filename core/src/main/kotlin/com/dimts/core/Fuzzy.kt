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

    /** Closest candidate within a small edit distance (scaled to the word length), or null. */
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
