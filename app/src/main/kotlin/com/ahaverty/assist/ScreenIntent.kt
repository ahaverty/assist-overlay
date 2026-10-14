package com.ahaverty.assist

/**
 * Does a question clearly ask about what is on the screen? Decided on the phone, so the screen text
 * never leaves it just to find out. A lone "this" or "here" is not enough ("turn this light off",
 * "is Dana here yet"): it needs the screen named, "this" tied to something you read on a phone, or
 * a reading verb aimed at "this".
 */
object ScreenIntent {
    private const val THING = "page|article|email|e-mail|mail|message|messages|text|texts|post|tweet|thread|chat|" +
        "recipe|product|listing|ad|advert|review|reviews|comment|comments|link|website|site|webpage|app|story|" +
        "paragraph|list|photo|picture|pic|image|screenshot|document|doc|pdf|form|bill|receipt|invoice|notification|" +
        "word|words|sentence|menu|offer|deal|price|item|invite|invitation|address|" +
        "code|error|warning|quote|result|results|map|headline|graph|chart|table|letter|contract|ticket|booking"

    /** Things in the house: "turn off these lights" is about the lights, not the screen. */
    private const val HOME = "light|lights|lamp|lamps|door|doors|room|heating|alarm|timer|music|song|tv|volume|fan|plug|switch|blind|blinds"

    private val PATTERNS = listOf(
        // The screen named outright.
        """\b(my|the|this|phone'?s?) screen\b""",
        """\bwhat am i (looking at|reading|seeing)\b""",
        """\b(what|anything|something|everything|stuff|price|prices|cheapest|best)\b.*\bon here\b""",
        // "this article", "these reviews", "that email"
        """\b(this|these|that|those) ($THING)\b""",
        // A reading verb aimed at this / these / that
        """\b(read|summari[sz]e|summary of|tl;?dr|translate|explain|reply to|respond to|proofread|rewrite|""" +
            """reword|fact[- ]?check|look at|go through|copy|save|share|add|book|""" +
            """what do you (think|make) of|thoughts on|opinion on) (all )?(this|these|that|those)\b(?! ($HOME)\b)""",
        """\bwhat (does|do) (this|these|that|it) (say|mean|says)\b""",
        """\b(is|are) (this|these|that|it) (legit|real|genuine|true|fake|a scam|scam|spam|safe|correct|right|accurate|any good|good value|worth it)\b""",
        """\b(who|what) (is|'s|are) (this|these|that)( (person|guy|one|thing))?\s*\??$""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun pointsAtScreen(question: String): Boolean {
        val q = question.replace('’', '\'')
        return PATTERNS.any { it.containsMatchIn(q) }
    }
}
