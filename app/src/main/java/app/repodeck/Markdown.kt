package app.repodeck

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Item(val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val itemRe = Regex("^([-*+]|\\d+[.)])\\s+")
private val ruleRe = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val tableSepRe = Regex("^\\|?[\\s:|-]+\\|?$")

fun parseMarkdown(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val ls = md.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out += MdBlock.Para(para.toString().trim()); para.clear() } }
    var i = 0
    while (i < ls.size) {
        val l = ls[i]
        val t = l.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < ls.size && !ls[i].trim().startsWith("```")) { sb.appendLine(ls[i]); i++ }
                out += MdBlock.Code(lang, sb.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            t.startsWith("#") && t.dropWhile { it == '#' }.startsWith(" ") -> {
                flush()
                val lvl = t.takeWhile { it == '#' }.length.coerceAtMost(6)
                out += MdBlock.Heading(lvl, t.drop(lvl).trim())
            }
            ruleRe.matches(t) -> { flush(); out += MdBlock.Rule }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
            itemRe.containsMatchIn(t) -> {
                flush()
                val m = itemRe.find(t)!!
                val marker = m.groupValues[1]
                val indent = (l.length - l.trimStart().length) / 2
                out += MdBlock.Item(if (marker[0].isDigit()) marker else "\u2022", indent, t.substring(m.range.last + 1))
            }
            t.startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                while (i < ls.size && ls[i].trim().startsWith("|")) {
                    val row = ls[i].trim()
                    if (!tableSepRe.matches(row)) rows += row.trim('|').split("|").map { it.trim() }
                    i++
                }
                out += MdBlock.Table(rows)
                i--
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

fun mdInline(text: String, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("**", i) -> {
                val e = text.indexOf("**", i + 2)
                if (e > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, e)) }; i = e + 2 }
                else { append(c); i++ }
            }
            c == '`' -> {
                val e = text.indexOf('`', i + 1)
                if (e > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(text.substring(i + 1, e)) }; i = e + 1 }
                else { append(c); i++ }
            }
            (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) && i + 1 < text.length && text[i + 1] != ' ' -> {
                val e = text.indexOf(c, i + 1)
                if (e > 0) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, e)) }; i = e + 1 }
                else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && end > 0) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, end)
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    pop()
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
private fun MdText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    val uri = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    val ann = remember(text, cs) { mdInline(text, cs.primary, cs.surfaceVariant) }
    ClickableText(
        ann, modifier = modifier, style = style.copy(color = cs.onSurface),
        onClick = { off -> ann.getStringAnnotations("URL", off, off).firstOrNull()?.let { uri.openUri(it.item) } }
    )
}

@Composable
fun MarkdownView(md: String, modifier: Modifier = Modifier) {
    val blocks = remember(md) { parseMarkdown(md) }
    val cs = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> MdText(
                    b.text, style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                )
                is MdBlock.Para -> MdText(b.text)
                is MdBlock.Code -> Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(
                        b.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
                    )
                }
                is MdBlock.Item -> Row {
                    androidx.compose.material3.Text(b.marker, Modifier.padding(start = (8 + b.indent * 16).dp, end = 8.dp))
                    MdText(b.text)
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary))
                    MdText(b.text, Modifier.padding(start = 10.dp))
                }
                is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    b.rows.forEachIndexed { ri, row ->
                        Row {
                            row.forEach { cell ->
                                MdText(cell, Modifier.width(140.dp).padding(6.dp),
                                    if (ri == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}
