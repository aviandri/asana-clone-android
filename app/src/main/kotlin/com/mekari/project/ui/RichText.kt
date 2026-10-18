package com.mekari.project.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.mekari.project.core.Mentions
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Basic inline markdown as in the web and Mac: **bold**, *italic* / _italic_, `code` and [text](url) links.
 * Line breaks are kept. No block syntax (headings, lists): it stays as typed. Unclosed markers stay literal.
 */
object InlineMarkdown {
    fun render(text: String, accent: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
        appendInline(text, accent)
    }

    /** A comment body: markdown text plus "@[Name](id)" mentions as accent tokens. */
    fun renderComment(body: String, accent: Color): AnnotatedString = buildAnnotatedString {
        for (segment in Mentions.parse(body)) {
            when (segment) {
                is Mentions.Segment.Text -> appendInline(segment.text, accent)
                is Mentions.Segment.Mention -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append("@${segment.name}")
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String, accent: Color) {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val match = when {
                rest.startsWith("**") -> delimited(text, i, "**")?.let { end ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, end), accent)
                    }
                    end + 2
                }
                rest.startsWith("`") -> delimited(text, i, "`")?.let { end ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    end + 1
                }
                rest.startsWith("*") || rest.startsWith("_") -> {
                    val marker = rest.substring(0, 1)
                    delimited(text, i, marker)?.let { end ->
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendInline(text.substring(i + 1, end), accent)
                        }
                        end + 1
                    }
                }
                rest.startsWith("[") -> link(text, i)?.let { (label, url, end) ->
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent)))) { append(label) }
                    end
                }
                else -> null
            }
            if (match != null) {
                i = match
            } else {
                append(text[i])
                i++
            }
        }
    }

    /** The index of the closing [marker] after an opening one at [start], on the same line, with content. */
    private fun delimited(text: String, start: Int, marker: String): Int? {
        val from = start + marker.length
        val end = text.indexOf(marker, from)
        if (end <= from) return null
        if (text.substring(from, end).contains('\n')) return null
        return end
    }

    private fun link(text: String, start: Int): Triple<String, String, Int>? {
        val close = text.indexOf("](", start)
        if (close < 0) return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val label = text.substring(start + 1, close)
        val url = text.substring(close + 2, end)
        if (label.isEmpty() ||
            label.contains('\n') ||
            !(url.startsWith("http://") || url.startsWith("https://"))
        ) {
            return null
        }
        return Triple(label, url, end + 1)
    }
}

/** "just now", "5m ago", "3h ago", "yesterday", "Mon", then "Mar 4" (story timestamps). */
object RelativeTime {
    fun format(
        at: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val elapsed = Duration.between(at, now)
        val days = java.time.temporal.ChronoUnit.DAYS.between(
            at.atZone(zone).toLocalDate(),
            now.atZone(zone).toLocalDate()
        )
        return when {
            elapsed.toMinutes() < 1 -> "just now"
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
            days == 0L -> "${elapsed.toHours()}h ago"
            days == 1L -> "yesterday"
            days < 7 -> at.atZone(zone).format(DateTimeFormatter.ofPattern("EEE", locale))
            else -> at.atZone(zone).format(DateTimeFormatter.ofPattern("MMM d", locale))
        }
    }
}
