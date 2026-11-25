package org.example

import java.io.File
import org.example.complie.lsp.EditorSupport
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 阶段 87：光标停在半行（`b.` / `this.`）时成员补全也要有答案。
 *
 * 用户报的问题是：编辑器里打 `b.` 之后 Ctrl+Space 只给一堆关键字——
 * 因为那一瞬间文件语法不完整，整份解析失败，成员解析拿不到程序。
 */
class CompletionMemberTest {

    private val text = """
        class Point(x0 : Int, y0 : Int) {
            var x : Int = x0
            var y : Int = y0
            fn bump() -> Point {
                this.
                rtn this
            }
        }

        class Holder(p : Point) {
            var point : Point = p
            fn test() -> void {
                val local : Point = this.point
                local.
                this.
                println("h")
            }
        }

        fn main() -> void {
            val p = new Point(1, 2)
            p.
            val s : String = "abc"
            s.
            val n = 3
            n.
            rtn
        }
    """.trimIndent()

    private fun labels(line: Int, col: Int): List<String> =
        EditorSupport.completions(File("m.rsf"), text, line, col, null).map { it.label }

    private fun lineOf(snippet: String): Int {
        val lines = text.split("\n")
        for ((i, l) in lines.withIndex()) if (l.trim() == snippet) return i + 1
        throw AssertionError("找不到行：$snippet")
    }

    @Test
    fun `变量后面点一下就能列出成员，不是关键字表`() {
        val ln = lineOf("p.")
        val got = labels(ln, text.split("\n")[ln - 1].length + 1)
        assertTrue(got.contains("x"), "p. 应该列出字段 x，实际：$got")
        assertTrue(got.contains("y"), "p. 应该列出字段 y，实际：$got")
        assertTrue(got.contains("bump"), "p. 应该列出方法 bump，实际：$got")
    }

    @Test
    fun `this 后面点一下列出本类的成员`() {
        val ln = lineOf("this.")
        val got = labels(ln, text.split("\n")[ln - 1].length + 1)
        assertTrue(got.contains("x") || got.contains("point"), "this. 应该有成员，实际：$got")
    }

    @Test
    fun `JDK 类型的变量也能列出成员`() {
        val lines = text.split("\n")
        val ln = lineOf("s.")
        val got = labels(ln, lines[ln - 1].length + 1)
        assertTrue(got.contains("length"), "s. 应该列出 String.length，实际：$got")
        assertTrue(got.contains("substring"), "s. 应该列出 String.substring，实际：$got")
    }
}
