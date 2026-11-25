package org.example

import java.io.File
import org.example.complie.lsp.EditorSupport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 阶段 80：`x.` 之后的补全里要能看到**扩展函数**。
 *
 * 编辑器这层只做语法索引、不做类型推断（`memberItems` 拿 `xxx.` 里的 `xxx` 当类名去查），
 * 所以规则是：
 *   - `Box.` 这种类名，只列接收者同名的扩展（`fn Box.foo`）；
 *   - `someVar.` 这种变量，本文件里的扩展全列出来，详情里写清接收者，由用户自己挑。
 */
class ExtensionCompletionTest {

    private val source = """
        fn Int.twice() -> Int { rtn this * 2 }
        fn Box.wrap() -> Int { rtn 1 }
        class Box() { var n : Int = 1 }
        fn main() -> void {
            val x : Int = 3
            println(intToString(x.twice()))
        }
    """.trimIndent()

    private fun labels(line: Int, col: Int, text: String = source): List<String> {
        val file = File("ExtComplete.rsf").absoluteFile
        // EditorSupport 的 line/col 都是 1 基（LanguageServer 里 +1 之后传进来）
        return EditorSupport.completions(file, text, line + 1, col, null).map { it.label }
    }

    @Test
    fun `变量后面的点要列出本文件的扩展函数`() {
        // 第 6 行（0 基）`    println(intToString(x.twice()))` 里 x 后面那个点
        val line = 5
        val text = source.lines()[line]
        val dot = text.indexOf("x.") + 1
        val labels = labels(line, dot + 2)
        assertTrue(labels.contains("twice"), "补全里应该有扩展函数 twice，实际：$labels")
        // 走的是成员补全（不是关键字兜底列表）
        assertTrue(!labels.contains("while"), "成员补全不该混进关键字，实际：$labels")
    }

    @Test
    fun `类名后面的点只列接收者同名的扩展`() {
        val text = source + "\n// Box."
        val line = text.lines().size - 1
        val labels = labels(line, "// Box.".length + 1, text)
        assertTrue(labels.contains("wrap"), "类名补全里应该有 fn Box.wrap，实际：$labels")
        assertTrue(!labels.contains("twice"), "接收者不是 Box 的扩展不该出现在 Box. 下，实际：$labels")
    }
}
