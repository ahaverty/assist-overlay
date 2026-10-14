package com.ahaverty.assist

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** [name](https://...)  |  bare https://...  |  **bold** */
private val TOKEN = Regex("""\[([^\]\n]+)]\((https?://[^\s)]+)\)|(https?://[^\s<>()\]]+[^\s<>()\].,;:!?'"])|\*\*([^*\n]+)\*\*""")

/** "https://www.bbc.co.uk/news/x" -> "bbc.co.uk" */
fun siteName(url: String): String = (Uri.parse(url).host ?: url).removePrefix("www.")

/** Reply text for the screen: links show their name (or the site) and are tappable; **bold** is bold. */
fun richText(text: String, linkColor: Color, onOpen: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
    var at = 0
    for (m in TOKEN.findAll(text)) {
        append(text, at, m.range.first)
        val (name, url, bare, bold) = m.destructured
        when {
            url.isNotEmpty() -> withLink(LinkAnnotation.Clickable(url, styles) { onOpen(url) }) { append(name) }
            bare.isNotEmpty() -> withLink(LinkAnnotation.Clickable(bare, styles) { onOpen(bare) }) { append(siteName(bare)) }
            else -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
        }
        at = m.range.last + 1
    }
    append(text, at, text.length)
}

/** Reply text for the voice: links become their name, bare URLs "a link", markdown marks dropped. */
fun speakable(text: String): String = text.lines().filterNot { HA_EMBED.matches(it.trim()) }.joinToString("\n").let { TOKEN.replace(it) { m ->
    val (name, _, bare, bold) = m.destructured
    when {
        name.isNotEmpty() -> name
        bare.isNotEmpty() -> "a link on ${siteName(bare)}"
        else -> bold
    }
} }
    .replace(Regex("""(?m)^\s*(#{1,6}|[-*•]|\d+\.)\s+"""), "")
    .replace(Regex("""[*_`]"""), "")
    .trim()

/**
 * Cuts streamed reply text into pieces worth sending to TTS as soon as they are complete: paragraphs,
 * or sentences once there is a little text. Links never contain ". " so they are never split.
 */
class SentenceChunker {
    private val buf = StringBuilder()

    fun add(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val para = buf.indexOf("\n\n")
            val sentence = Regex("""[.!?…](\s)""").findAll(buf).firstOrNull { it.range.first >= 30 }?.range?.first
            val cut = listOfNotNull(para.takeIf { it >= 0 }, sentence?.plus(1)).minOrNull() ?: break
            val piece = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (piece.isNotEmpty()) out += piece
        }
        return out
    }

    fun flush(): String? = buf.toString().trim().also { buf.clear() }.takeIf { it.isNotEmpty() }
}
