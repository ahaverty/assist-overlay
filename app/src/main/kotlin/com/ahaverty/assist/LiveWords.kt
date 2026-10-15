package com.ahaverty.assist

/**
 * Joining live transcripts without showing anything twice. Recognizers revise and repeat themselves: a
 * final result can repeat the partials before it, and Google lags Whisper by a word or two. Words compare
 * loosely here (case and punctuation ignored), because Whisper and Google write the same words differently.
 */
object LiveWords {
    private fun words(s: String) = s.split(' ').filter { it.isNotBlank() }
    private fun key(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

    /** [base] then [next], dropping the words at the start of [next] that [base] already ends with. */
    fun merge(base: String, next: String): String {
        val a = words(base)
        val b = words(next)
        if (a.isEmpty() || b.isEmpty()) return (a + b).joinToString(" ")
        val ka = a.map(::key)
        val kb = b.map(::key)
        for (k in minOf(a.size, b.size) downTo 1) {
            if (ka.subList(a.size - k, a.size) == kb.subList(0, k)) return (a + b.drop(k)).joinToString(" ")
        }
        // [next] can start a word or two before the end of [base] (Google a little behind a Whisper draft), and
        // the draft, cut at a gap, can end in a clipped word ("as if I" for "as if I'm"). Match up to two words
        // back; a base word that the next new word starts with was clipped, so the whole word stands instead.
        for (back in 1..2) {
            val e = a.size - back
            for (k in minOf(e, b.size) downTo 1) {
                if (k == 1 && b.size > 1) continue // one loose word is too weak a match mid-sentence
                if (ka.subList(e - k, e) != kb.subList(0, k)) continue
                val clipped = b.size > k && kb[k].startsWith(ka[e]) && ka[e].isNotEmpty()
                return ((if (clipped) a.take(e) else a) + b.drop(k)).joinToString(" ")
            }
        }
        return (a + b).joinToString(" ")
    }

    /**
     * The words of [now] said after [then], an earlier copy of the same live text. Usually [now] just grows
     * from [then], but the recognizer may have revised it since, so [then]'s last words are looked for
     * near where they were rather than counted.
     */
    fun after(now: String, then: String): String {
        val a = words(then).map(::key)
        val b = words(now)
        if (a.isEmpty()) return b.joinToString(" ")
        val kb = b.map(::key)
        for (k in minOf(3, a.size) downTo 1) {
            val tail = a.takeLast(k)
            val end = (k..kb.size).filter { kb.subList(it - k, it) == tail }.minByOrNull { kotlin.math.abs(it - a.size) }
            if (end != null) return b.drop(end).joinToString(" ")
        }
        return b.drop(a.size).joinToString(" ")
    }

    /** True when [text] starts with the same first words as [other]: a recognizer repeating, not moving on. */
    fun startsLike(text: String, other: String): Boolean {
        val a = words(text).map(::key)
        val b = words(other).map(::key)
        val n = minOf(2, b.size)
        return n > 0 && a.size >= n && a.take(n) == b.take(n)
    }
}
