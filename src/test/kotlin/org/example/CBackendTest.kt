package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.native.CBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 阶段 52：C 后端（native 线第一刀）的生成器单测。
 *
 * 这里**不**调 gcc —— 编译/链接/运行在 `build/verify52/run-probes.sh` 里跑；
 * 单测只钉「摊出来的 C 长什么样」和「不收的构造要显式报错」。
 */
class CBackendTest {

    private fun emit(text: String): String {
        val source = SourceFile.of("c.kt", text)
        return CBackend(Rsf.analyze(source)).emit()
    }

    @Test
    fun `基础程序摊出运行时与函数`() {
        val c = emit(
            """
            fn add(a : int, b : int) -> int {
                rtn a + b
            }

            fn main() -> void {
                println(add(1, 2))
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("#include <stdint.h>"), "要自带运行时头")
        assertTrue(c.contains("static rsf_str rsf_concat"), "要带字符串拼接")
        assertTrue(c.contains("int32_t rsf_fn_add(int32_t v_a, int32_t v_b)"), c)
        assertTrue(c.contains("rsf_println_i(rsf_fn_add(1, 2));"), c)
        assertTrue(c.contains("int main(void)"), "要有 C 的入口")
    }

    @Test
    fun `字符串字面量按 UTF-8 字节算长度`() {
        val c = emit(
            """
            fn main() -> void {
                println("中")
                rtn
            }
            """.trimIndent(),
        )
        // "中" 的 UTF-8 是 E4 B8 AD（三个字节，八进制转义）
        assertTrue(c.contains("""rsf_str_lit("\344\270\255", 3LL)"""), c)
    }

    @Test
    fun `字符串拼接与相等走运行时`() {
        val c = emit(
            """
            fn main() -> void {
                var a : string = "x" + "y"
                if (a == "xy") { println("ok") }
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("rsf_concat(rsf_str_lit(\"x\", 1LL), rsf_str_lit(\"y\", 1LL))"), c)
        assertTrue(c.contains("rsf_str_eq(v_a, rsf_str_lit(\"xy\", 2LL))"), c)
    }

    @Test
    fun `还没接的构造要显式报错`() {
        val error = assertThrows<RsfError> {
            emit(
                """
                enum Color { Red, Green }

                fn main() -> void {
                    rtn
                }
                """.trimIndent(),
            )
        }
        assertEquals("native", error.phase)
        assertTrue(error.detail.contains("暂不支持"), error.detail)
    }

    @Test
    fun `类摊成 struct 与 self 方法`() {
        val c = emit(
            """
            class Point(x0 : int) {
                var x : int = x0

                fn get() -> int { rtn x }

                stc fn zero() -> int { rtn 0 }
            }

            fn main() -> void {
                var p : Point = new Point(3)
                println(p.get())
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("typedef struct rsf_cls_Point {"), c)
        assertTrue(c.contains("int32_t x;"), c)
        assertTrue(c.contains("int32_t rsf_m_Point_get(rsf_cls_Point* self)"), c)
        assertTrue(c.contains("int32_t rsf_st_Point_zero(void)"), c)
        assertTrue(c.contains("rsf_new_Point_0(3)"), c)
        assertTrue(c.contains("self->x"), c)
    }

    @Test
    fun `构造器里字段初值先于构造体`() {
        val c = emit(
            """
            class Q {
                var v : int = 1

                constructor(n : int) { v = n }
            }

            fn main() -> void {
                var q : Q = new Q(5)
                println(q.v)
                rtn
            }
            """.trimIndent(),
        )
        val body = c.substringAfter("rsf_cls_Q* rsf_new_Q_0(int32_t v_n) {")
        assertTrue(body.contains("self->v = 1;"), body.take(200))
        assertTrue(body.contains("self->v = v_n;"), body.take(200))
    }

}
