package org.example

import org.example.complie.Rsf
import org.example.complie.diag.Diagnostic
import org.example.complie.diag.RsfError
import org.example.complie.diag.Severity
import org.example.complie.diag.SourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 阶段 78：`#NoWarning!!(类型)` —— 文件级压警告指令。
 *
 * 语义：只压**指定类型**的 warning，压不掉 error；粒度是文件；
 * 指令整行在词法之前被挖成空格（行号列号与原文完全对齐）。
 */
class WarningDirectiveTest {

    private fun warnings(text: String): List<Diagnostic> =
        Rsf.analyze(SourceFile.of("w.rsf", text)).diagnostics.filter { it.severity == Severity.WARNING }

    /** `rtn` 之后还有语句 —— 稳定产出一条 unreachable。 */
    private val deadCode = """
        fn main() -> void {
            rtn
            println("dead")
        }
    """.trimIndent()

    private fun withDirective(kinds: String): String = "#NoWarning!!($kinds)\n$deadCode"

    @Test
    fun `不可达代码默认会出警告，并且带类型短名`() {
        val list = warnings(deadCode)
        assertEquals(1, list.size, list.joinToString { it.message })
        assertEquals("unreachable", list.first().code)
    }

    @Test
    fun `写了 unreachable 之后这条警告消失`() {
        assertTrue(warnings(withDirective("unreachable")).isEmpty())
    }

    @Test
    fun `all 能把所有类型一起压掉`() {
        val plain = "stc fn helper() -> void { rtn }\n\n$deadCode"
        assertEquals(2, warnings(plain).size, "没写指令时应该有两条（stc 多余 + 不可达）")
        assertTrue(warnings("#NoWarning!!(all)\n$plain").isEmpty())
    }

    @Test
    fun `多写几个类型也认`() {
        val text = "#NoWarning!!(unreachable, redundant-static)\nstc fn helper() -> void { rtn }\n\n$deadCode"
        assertTrue(warnings(text).isEmpty())
    }

    @Test
    fun `类型名写错会报错，并列出可用类型`() {
        val e = assertThrows<RsfError> { warnings(withDirective("unreachible")) }
        val message = e.message!!
        assertTrue(message.contains("不认识的警告类型"), message)
        assertTrue(message.contains("unreachable"), message)
        assertTrue(message.contains("all"), message)
        assertEquals(1, e.line)
    }

    @Test
    fun `括号里什么都不写会报错`() {
        val e = assertThrows<RsfError> { warnings(withDirective("")) }
        assertTrue(e.message!!.contains("括号里要写类型"), e.message!!)
    }

    @Test
    fun `指令行被挖空，但行列号与原文一致`() {
        val text = """
            #NoWarning!!(all)
            fn main() -> void {
                unknownCall()
            }
        """.trimIndent()
        val e = assertThrows<RsfError> { Rsf.analyze(SourceFile.of("w.rsf", text)) }
        assertEquals(3, e.line)
        assertEquals("unknownCall", e.message!!.substringAfter("'").substringBefore("'"))
    }

    @Test
    fun `压不掉错误——错误照旧抛出来`() {
        val text = """
            #NoWarning!!(all)
            fn main() -> void {
                noSuchFunction()
            }
        """.trimIndent()
        val e = assertThrows<RsfError> { Rsf.analyze(SourceFile.of("w.rsf", text)) }
        assertTrue(e.message!!.contains("noSuchFunction"), e.message!!)
    }

    @Test
    fun `指令写在一行语句后面不算指令，会报不认识的字符`() {
        val text = """
            fn main() -> void {
                println("x") #NoWarning!!(all)
            }
        """.trimIndent()
        val e = assertThrows<RsfError> { Rsf.analyze(SourceFile.of("w.rsf", text)) }
        assertTrue(e.message!!.contains("不认识的字符 '#'"), e.message!!)
    }
}
