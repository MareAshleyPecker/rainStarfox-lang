package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
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

    /** native 函数在 JVM 构建里只有签名，所以 C 侧要用 native 目标加载才有函数体。 */
    private fun nativeBackend(text: String, withEntry: Boolean = false): CBackend {
        val file = java.io.File.createTempFile("rsf-jni", ".rsf")
        file.writeText(text, Charsets.UTF_8)
        file.deleteOnExit()
        return CBackend(Rsf.analyze(Rsf.load(file, target = BuildTarget.NATIVE)), withEntry)
    }

    @Test
    fun `库模式不生成 main`() {
        val source = SourceFile.of(
            "lib.kt",
            """
            fn twice(x : Int) -> Int {
                rtn x * 2
            }
            """.trimIndent(),
        )
        val c = CBackend(Rsf.analyze(source), withEntry = false).emit()
        assertTrue(c.contains("rsf_fn_twice"), "函数要照常摊出来")
        assertTrue(!c.contains("int main("), "库模式不生成 main")
    }

    @Test
    fun `基础程序摊出运行时与函数`() {
        val c = emit(
            """
            fn add(a : Int, b : Int) -> Int {
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
                var a : String = "x" + "y"
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
                fn f(a : Int[][]) -> void {
                }

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
    fun `String 成员方法摊成 rsf_str_ 系列`() {
        val c = emit(
            """
            fn main() -> void {
                var s = "abc"
                println(intToString(s.length()))
                println(s.substring(1))
                println(s.toUpperCase())
                println(s.startsWith("ab") + " " + s.contains("b"))
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("rsf_str_len(v_s)"), c)
        assertTrue(c.contains("rsf_str_substr(v_s, 1, rsf_str_len(v_s))"), c)
        assertTrue(c.contains("rsf_str_upper(v_s)"), c)
        assertTrue(c.contains("rsf_str_starts(v_s, rsf_str_lit(\"ab\", 2LL))"), c)
        assertTrue(c.contains("rsf_str_contains(v_s, rsf_str_lit(\"b\", 1LL))"), c)
    }

    @Test
    fun `接口数组摊成 void* 数组`() {
        val c = emit(
            """
            interface Shape {
                fn area() -> Int
            }

            class Sq(s : Int) : Shape {
                var side : Int = s
                fn area() -> Int { rtn side * side }
            }

            fn main() -> void {
                var xs : Shape[] = new Shape[2]
                xs[0] = Sq(3)
                xs[1] = Sq(4)
                println(intToString(xs[0].area()))
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("sizeof(void*)"), c)
    }

    @Test
    fun `接口摊成 void 指针、默认方法走虚表`() {
        val c = emit(
            """
            interface Greeter {
                fn greet(name : String) -> String
                fn shout(name : String) -> String {
                    rtn this.greet(name) + "!"
                }
            }

            class Person(who : String) : Greeter {
                var who : String = who

                fn greet(name : String) -> String {
                    rtn name + who
                }
            }

            fn main() -> void {
                var g : Greeter = Person("ann")
                println(g.shout("bob"))
                rtn
            }
            """.trimIndent(),
        )
        // 接口方法有实体（默认方法），类实现的那份也在
        assertTrue(c.contains("rsf_str rsf_m_Greeter_shout(void* self, rsf_str v_name)"), c)
        assertTrue(c.contains("rsf_str rsf_m_Person_greet(void* self, rsf_str v_name)"), c)
        // 虚表里 shout / greet 都要有槽位；接口默认方法进 greet 的实现链
        assertTrue(c.contains("static void* rsf_vtbl_Person["), c)
        assertTrue(c.contains("(void*)rsf_m_Person_greet"), c)
        // 接口类型的参数就是 void*
        assertTrue(c.contains("void* v_g"), c)
    }

    @Test
    fun `类摊成 struct 与 self 方法`() {
        val c = emit(
            """
            class Point(x0 : Int) {
                var x : Int = x0

                fn get() -> Int { rtn x }

                stc fn zero() -> Int { rtn 0 }
            }

            fn main() -> void {
                var p : Point = new Point(3)
                println(p.get())
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("typedef struct rsf_cls_Point {"), c)
        assertTrue(c.contains("void** rsf_vtbl;"), c)
        assertTrue(c.contains("int32_t f_Point_x;"), c)
        assertTrue(c.contains("int32_t rsf_m_Point_get(void* self)"), c)
        assertTrue(c.contains("int32_t rsf_st_Point_zero(void)"), c)
        assertTrue(c.contains("rsf_new_Point_0(3)"), c)
        assertTrue(c.contains("((rsf_cls_Point*)self)->f_Point_x"), c)
    }

    @Test
    fun `构造器里字段初值先于构造体`() {
        val c = emit(
            """
            class Q {
                var v : Int = 1

                constructor(n : Int) { v = n }
            }

            fn main() -> void {
                var q : Q = new Q(5)
                println(q.v)
                rtn
            }
            """.trimIndent(),
        )
        val body = c.substringAfter("void rsf_init_Q_0(void* self, int32_t v_n) {")
        assertTrue(body.contains("((rsf_cls_Q*)self)->f_Q_v = 1;"), body.take(200))
        assertTrue(body.contains("((rsf_cls_Q*)self)->f_Q_v = v_n;"), body.take(200))
    }


    @Test
    fun `指针摊成 C 指针`() {
        val c = emit(
            """
            fn main() -> void {
                var x : Int = 7
                var p : Int* = &x
                *p = 9
                println(*p)
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("int32_t* v_p"), c)
        assertTrue(c.contains("(&v_x)"), c)
        assertTrue(c.contains("*(v_p) = 9;"), c)
        assertTrue(c.contains("(*v_p)"), c)
    }


    @Test
    fun `switch 摊成先存临时值的 if 链`() {
        val c = emit(
            """
            fn pick(n : Int, s : String) -> Int {
                switch (n) {
                    1, 2 : { rtn 10 }
                    else : { }
                }
                switch (s) {
                    "ok" : { rtn 1 }
                }
                rtn 0
            }

            fn main() -> void {
                println(pick(2, "ok"))
                rtn
            }
            """.trimIndent(),
        )
        // 主体只求值一次：先落临时变量，再逐标签比较
        assertTrue(c.contains("int32_t sw_0 = v_n;"), c)
        assertTrue(c.contains("if ((sw_0 == 1) || (sw_0 == 2)) {"), c)
        assertTrue(c.contains("rsf_str_eq(sw_1, rsf_str_lit(\"ok\", 2LL))"), c)
    }


    @Test
    fun `JNI 桥把 native 函数摊成 Java_ 符号`() {
        val backend = nativeBackend(
            """
            native fn add(a : Int, b : Int) -> Int { rtn a + b }
            native fn greet(name : String) -> String { rtn "hi " + name }
            """.trimIndent(),
        )
        val jni = backend.emitJni("Main")
        assertTrue(jni.contains("extern int32_t rsf_fn_add(int32_t, int32_t);"), jni)
        assertTrue(jni.contains("JNIEXPORT jint JNICALL Java_Main_add(JNIEnv* env, jclass cls, jint j0, jint j1)"), jni)
        assertTrue(jni.contains("GetStringUTFChars(env, j0, 0)"), jni)
        assertTrue(jni.contains("ReleaseStringUTFChars(env, j0, c0)"), jni)
        assertTrue(jni.contains("NewStringUTF(env, t)"), jni)
    }

    @Test
    fun `JNI 桥拒绝过不了边界的类型`() {
        val backend = nativeBackend(
            """
            native fn take(xs : Int[]) -> Int { rtn xs.length }
            """.trimIndent(),
        )
        val e = assertThrows<RsfError> { backend.emitJni("Main") }
        assertTrue(e.message!!.contains("JNI 桥只支持"), e.message!!)
    }


    @Test
    fun `jvmCall 才把 JVM 运行时编进来`() {
        val withCall = emit(
            """
                fn main() -> void {
                    println(jvmCall("Echo", "hi", "bob"))
                }
            """.trimIndent(),
        )
        assertTrue(withCall.contains("static rsf_str rsf_jvm_call("))
        assertTrue(withCall.contains("rsf_jvm_call("))
        assertTrue(withCall.contains("JNI_CreateJavaVM"))

        val without = emit("fn main() -> void { println(intToString(1)) }")
        assertTrue(!without.contains("JNI_CreateJavaVM"))
    }

    @Test
    fun `虚分派的接收者只求值一次`() {
        val code = emit(
            """
            class A {
                pub fn m() -> Int { rtn 1 }
            }
            fn mk() -> A { rtn new A() }
            fn main() -> void { println(mk().m()) }
            """.trimIndent(),
        )
        // 接收者是函数调用：产物里必须先存临时变量，mk 只能出现一次
        assertEquals(1, Regex("""\(rsf_fn_mk\(\)\)""").findAll(code).count(), code)
        assertTrue(code.contains("void* __rsf_recv"), code)
    }

}
