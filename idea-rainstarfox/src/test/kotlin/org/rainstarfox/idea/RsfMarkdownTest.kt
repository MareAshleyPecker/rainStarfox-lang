package org.rainstarfox.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 悬停文档的渲染（纯字符串，不需要 IDE 环境）。
 *
 * 这里只钉住「服务器给的 markdown 长什么样 → 弹窗里该出现什么标签」；真正的
 * markdown 由编译器那侧生成，格式由 [RsfSymbolsTest] 之类的根工程用例守着。
 */
class RsfMarkdownTest {

    @Test
    fun `代码围栏变成 pre-code`() {
        val html = RsfMarkdown.toHtml("```rsf\nadd(a : int) -> int\n```\n")
        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("add(a : int) -&gt; int"), html)
        assertTrue(html.contains("</code></pre>"), html)
    }

    @Test
    fun `正文里的尖括号被转义而不是当成标签`() {
        val html = RsfMarkdown.toHtml("```rsf\nBox&<int>\n```\n")
        assertTrue(html.contains("Box&amp;&lt;int&gt;"), html)
    }

    @Test
    fun `行内反引号与分隔线`() {
        val html = RsfMarkdown.toHtml("说明 `x` 的用法\n---\n")
        assertTrue(html.contains("<code>x</code>"), html)
        assertTrue(html.contains("<hr/>"), html)
    }

    @Test
    fun `未闭合的围栏也要收尾 不留下悬空标签`() {
        val html = RsfMarkdown.toHtml("```rsf\nfn f() -> int\n")
        assertEquals(1, Regex("<pre><code>").findAll(html).count())
        assertEquals(1, Regex("</code></pre>").findAll(html).count())
    }
}
