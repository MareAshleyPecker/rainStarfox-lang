package org.rainstarfox.idea

/**
 * 把服务器给的 markdown 转成 IDEA 文档弹窗认识的 HTML。
 *
 * 只做悬停会用到的四种东西：代码围栏、段落、分隔线、行内反引号。**不引第三方
 * markdown 库**——插件的依赖越少越好（打出来的包已经 1.8 MB 了）。
 */
object RsfMarkdown {

    private val fence = "`".repeat(3)
    private val inlineCode = Regex("`([^`]+)`")

    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        var inCode = false
        for (line in markdown.split("\n")) {
            if (line.startsWith(fence)) {
                out.append(if (inCode) "</code></pre>" else "<pre><code>")
                inCode = !inCode
                continue
            }
            if (inCode) {
                out.append(escape(line)).append("\n")
                continue
            }
            when {
                line.trim() == "---" -> out.append("<hr/>")
                line.isBlank() -> out.append("<br/>")
                else -> out.append("<p>").append(inline(line)).append("</p>")
            }
        }
        if (inCode) out.append("</code></pre>")
        return out.toString()
    }

    private fun inline(text: String): String =
        inlineCode.replace(escape(text)) { "<code>" + it.groupValues[1] + "</code>" }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
