package com.ahaverty.assist

/**
 * Telling Alan talking over a reply from the reply's own voice coming back through the mic. The recognizer
 * rarely hears the TTS word for word: "dataset" comes back as "data set", "CamAPS FX" as "cam apsfx",
 * "96.7" as "ninety six point seven". Matching whole words alone counted those as new words, so the
 * echo stopped the reply and was sent as a question. Here a heard word also counts as the reply's own
 * when it is part of a reply word (or several run together), one letter off one, or a number word.
 */
object Echo {
    private val STARTERS = setOf("stop", "wait", "friday", "cancel", "hang", "hold")
    private val NUMBERS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "point", "percent", "and",
    )

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }

    /** True when [heard] (live words during the reply) is Alan, not [spoken] (the reply's speakable text). */
    fun isInterruption(heard: String, spoken: String): Boolean {
        val words = words(heard)
        if (words.isEmpty()) return false
        if (words.first() in STARTERS) return true
        val known = words(spoken).map { it.replace("'", "") }.toSet()
        val squashed = spoken.lowercase().filter { it.isLetterOrDigit() }
        val hasDigits = squashed.any { it.isDigit() }
        val unknown = words.map { it.replace("'", "") }.count { w ->
            !(w in known ||
                (w.length >= 3 && w in squashed) ||
                (hasDigits && w in NUMBERS) ||
                (w.length >= 5 && known.any { k -> nearlySame(w, k) }))
        }
        return unknown >= 2 && unknown * 2 >= words.size
    }

    /**
     * The whole of what was heard after talk-over stopped the reply was the reply's own voice: a few echoed
     * words can pass for Alan, the full sentence rarely does. A "stop" anywhere in it is still Alan.
     */
    fun isEcho(heard: String, spoken: String): Boolean =
        words(heard).none { it in STARTERS } && !isInterruption(heard, spoken)

    /** One letter added, dropped or changed ("adults" / "adult", "colour" / "color"). */
    private fun nearlySame(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
