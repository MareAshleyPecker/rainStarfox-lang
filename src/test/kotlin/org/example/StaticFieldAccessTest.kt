package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 修复用户报的 `<source>: error[interp]: 内部错误：这里没有 this`：
 *
 * 静态方法（`stc fn`）里写裸字段名时，Checker 照样把它记成 `fieldReads`，于是
 * - 解释器走到 `thisObject(slots)` 里抛「内部错误：这里没有 this」（slots[0] 不是对象）；
 * - 字节码后端发 `aload 0`（静态方法里那是第一个形参或压根越界）；
 * - C 后端发 `self.f_x`（静态函数没有 `self`）。
 *
 * 正确做法是**检查期**就报人话：静态方法没有 this，读不到实例字段。
 */
class StaticFieldAccessTest {

    private fun analyze(text: String): RsfError =
        assertFailsWith<RsfError> { Rsf.analyze(SourceFile.of("s.rsf", text)) }

    @Test
    fun `静态方法里读实例字段在检查期报错，不是运行期内部错误`() {
        val text = """
            class C() {
                val name : String = "x"
                stc fn get() -> String { rtn name }
            }
            fn main() -> void { println(C.get()) }
        """.trimIndent()
        val e = analyze(text)
        assertTrue(e.message!!.contains("静态方法里读不到实例字段"), e.message)
        assertTrue(e.message!!.contains("name"), e.message)
        assertEquals(3, e.line)
        // 关键：不能再是那句「内部错误：这里没有 this」
        assertTrue(!e.message!!.contains("内部错误"), e.message)
    }

    @Test
    fun `静态方法里改实例字段也在检查期报错`() {
        val text = """
            class C() {
                var n : Int = 0
                stc fn reset() -> void { n = 0 }
            }
            fn main() -> void { C.reset() }
        """.trimIndent()
        val e = analyze(text)
        assertTrue(e.message!!.contains("静态方法里改不到实例字段"), e.message)
        assertTrue(e.message!!.contains("n"), e.message)
    }

    @Test
    fun `非静态方法照旧能裸写字段`() {
        val text = """
            class C() {
                val name : String = "x"
                fn get() -> String { rtn name }
            }
            fn main() -> void { println(new C().get()) }
        """.trimIndent()
        // 不抛就行
        Rsf.analyze(SourceFile.of("s.rsf", text))
    }
}
