package com.droidperf.update

import android.text.Html
import android.text.Spanned

object MarkdownFormatter {

    fun format(markdown: String?): Spanned {
        if (markdown.isNullOrBlank()) {
            return Html.fromHtml("<i>No release notes provided.</i>", Html.FROM_HTML_MODE_COMPACT)
        }

        val sb = StringBuilder()
        val lines = markdown.lines()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> {
                    sb.append("<br/><b><font color='#00E699'>")
                        .append(escape(trimmed.removePrefix("### ").trim()))
                        .append("</font></b><br/>")
                }
                trimmed.startsWith("## ") -> {
                    sb.append("<br/><b><font color='#00E699'>")
                        .append(escape(trimmed.removePrefix("## ").trim()))
                        .append("</font></b><br/>")
                }
                trimmed.startsWith("# ") -> {
                    sb.append("<br/><b><font color='#00E699'>")
                        .append(escape(trimmed.removePrefix("# ").trim()))
                        .append("</font></b><br/>")
                }
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                    val content = trimmed.substring(2).trim()
                    sb.append("&bull;&nbsp;&nbsp;")
                        .append(formatInline(content))
                        .append("<br/>")
                }
                trimmed.isBlank() -> {
                    sb.append("<br/>")
                }
                else -> {
                    sb.append(formatInline(trimmed))
                        .append("<br/>")
                }
            }
        }

        return Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT)
    }

    private fun formatInline(text: String): String {
        var str = escape(text)
        str = str.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        str = str.replace(Regex("__(.+?)__"), "<b>$1</b>")
        str = str.replace(Regex("`(.+?)`"), "<font color='#00D2FF'><tt>$1</tt></font>")
        return str
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
