package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.native.CBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `memBytes 与 memPeak 摊成运行时统计`() {
        val backend = nativeBackend(
            """
            target native
            fn main() -> void {
                println(memBytes())
                println(memPeak())
            }
            """.trimIndent(),
            withEntry = true,
        )
        val c = backend.emit()
        assertTrue(c.contains("rsf_mem_bytes()"), c)
        assertTrue(c.contains("rsf_mem_peak()"), c)
        // 计数器必须在分配路径上真的被加：只声明不累加的话 memBytes 永远是 0。
        // 运行时（含 arena）已拆到 rsf_runtime.h，所以这里查 runtimeLibSource()。
        val runtime = backend.runtimeLibSource()
        assertTrue(runtime.contains("static inline int64_t rsf_mem_bytes(void)"), runtime)
        assertTrue(runtime.contains("a->used += pad + need;"), runtime)
        assertTrue(runtime.contains("if (a->used > a->peak) a->peak = a->used;"), runtime)
    }

    @Test
    fun `memBytes 在 JVM 目标里被检查期挡住`() {
        val source = SourceFile.of("c.kt", "fn main() -> void { println(memBytes()) }")
        val e = assertThrows<RsfError> { Rsf.analyze(source) }
        assertTrue(e.message!!.contains("只有 native 侧"), e.message!!)
    }

    @Test
    fun `gcCollect 摊成运行时的 GC 插座`() {
        val backend = nativeBackend(
            """
            target native
            fn main() -> void {
                if (gcCollect()) { println("gc") }
            }
            """.trimIndent(),
            withEntry = true,
        )
        val c = backend.emit()
        assertTrue(c.contains("rsf_gc_collect()"), c)
        // 插座本身在运行时库里：C 侧宿主用它登记自己的 GC。
        val runtime = backend.runtimeLibSource()
        assertTrue(runtime.contains("typedef struct rsf_gc {"), runtime)
        assertTrue(runtime.contains("void   (*collect)(void* user);"), runtime)
        assertTrue(runtime.contains("size_t (*bytes)(void* user);"), runtime)
        assertTrue(runtime.contains("static inline void rsf_ctx_set_gc(rsf_ctx* c, rsf_gc g)"), runtime)
        assertTrue(runtime.contains("static inline bool rsf_gc_collect(void)"), runtime)
        // 没登记 GC 时必须返回 false，而不是悄悄成功。
        assertTrue(runtime.contains("if (c->gc.collect == NULL) return false;"), runtime)
    }

    @Test
    fun `gcCollect 在 JVM 目标里被检查期挡住`() {
        val source = SourceFile.of("c.kt", "fn main() -> void { println(gcCollect()) }")
        val e = assertThrows<RsfError> { Rsf.analyze(source) }
        assertTrue(e.message!!.contains("只有 native 侧"), e.message!!)
    }

    @Test
    fun `保守 GC 的栈边界靠锚点，不依赖平台 API`() {
        val backend = nativeBackend("target native\nfn main() -> void { rtn }", withEntry = true)
        val runtime = backend.runtimeLibSource()
        // 锚点用宏记「调用者自己」的帧，不能用函数（否则记到 attach 自己的帧，区间会反）。
        assertTrue(runtime.contains("#define rsf_gc_thread_attach()"), runtime)
        assertTrue(runtime.contains("rsf_gc_thread_attach_at((void*)&rsf_gc_anchor_local_)"), runtime)
        // 取边界和记锚点的实现都不能内联：内联进同一帧后「谁高谁低」归编译器管。
        assertTrue(runtime.contains("RSF_NOINLINE static void rsf_gc_thread_attach_at(void* anchor)"), runtime)
        assertTrue(runtime.contains("RSF_NOINLINE static bool rsf_gc_stack_bounds(void** lo, void** hi)"), runtime)
        // 交出去的边界要对齐到指针宽度，否则按 sizeof(void*) 步长扫会跳过紧挨着的槽。
        assertTrue(runtime.contains("low & ~(step - 1)"), runtime)
        // 分配点要发布 sp，否则「分配完再由上层帧引用」的对象会被漏扫。
        assertTrue(runtime.contains("rsf_gc_sp = (void*)&rsf_gc_here;"), runtime)
        // 一行平台 API 都不许有：Windows / Linux / Android / macOS 同一份代码。
        // （注释里会提到这些名字做对比，所以只盯「真的调用/真的 include」。）
        assertFalse(runtime.contains("#include <windows.h>"), "不许拉 Windows 头文件")
        assertFalse(runtime.contains("NtCurrentTeb()"), "不许调 Windows TIB")
        assertFalse(runtime.contains("pthread_getattr_np("), "不许拉 pthread 栈边界")
        assertFalse(runtime.contains("pthread_get_stackaddr_np("), "不许拉 macOS 栈边界")
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
        assertTrue(c.contains("#include \"rsf_runtime.h\""), "要包含运行时头（阶段 69 起运行时拆到 rsf_runtime.h）")
        val header = CBackend(Rsf.analyze(SourceFile.of("c.kt", "fn main() -> void { rtn }"))).runtimeLibSource()
        assertTrue(header.contains("#include <stdint.h>"), "运行时头要自带 stdint")
        assertTrue(header.contains("rsf_concat"), "运行时头要带字符串拼接")
        assertTrue(c.contains("int32_t rsf_fn_add(int32_t v_a, int32_t v_b)"), c)
        assertTrue(c.contains("rsf_println_i(rsf_fn_add(1, 2));"), c)
        assertTrue(c.contains("int main(void)"), "要有 C 的入口")
    }

    @Test
    fun `拼出来的字符串要以 0 结尾，宿主 shim 才敢交给 libc`() {
        // 阶段 72 定位到的坑：rsf_str 是「长度 + 指针」，拼接只按长度分配、不写结尾 0。
        // FILE_RUNTIME 的 java.io.File shim 要把它交给 stat/opendir/remove，这些都要 C 字符串，
        // 于是末尾的随机字节让 stat 报 ENOENT —— 表现为「读文件读不出来」而路径其实是对的。
        val header = CBackend(Rsf.analyze(SourceFile.of("c.kt", "fn main() -> void { rtn }"))).runtimeLibSource()
        assertTrue(header.contains("(size_t)(a.len + b.len + 1)"), "拼接要多留一个字节写结尾 0")
        assertTrue(header.contains("p[a.len + b.len] = 0;"), "拼接要显式写结尾 0")

        val c = emit(
            """
            import java.io.File

            fn main() -> void {
                var f = new File("no-such-file.txt")
                println(f.exists())
                rtn
            }
            """.trimIndent(),
        )
        assertTrue(c.contains("rsf_zstr(f->path)"), "宿主 shim 走 rsf_zstr 换成 C 字符串")
        assertTrue(!c.contains("stat(f->path.data"), "不能把非 0 结尾的缓冲区交给 libc")
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
        assertTrue(c.contains("""rsf_str_lit_u("\344\270\255", 3LL, 2LL)"""), c)
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
        assertTrue(c.contains("rsf_concat(rsf_str_lit_u(\"x\", 1LL, 2LL), rsf_str_lit_u(\"y\", 1LL, 2LL))"), c)
        assertTrue(c.contains("rsf_str_eq(v_a, rsf_str_lit_u(\"xy\", 2LL, 3LL))"), c)
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
        assertTrue(c.contains("rsf_str_starts(v_s, rsf_str_lit_u(\"ab\", 2LL, 3LL))"), c)
        assertTrue(c.contains("rsf_str_contains(v_s, rsf_str_lit_u(\"b\", 1LL, 2LL))"), c)
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
            target native
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
        assertTrue(c.contains("rsf_str_eq(sw_1, rsf_str_lit_u(\"ok\", 2LL, 3LL))"), c)
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
        // 字符串不许走 modified UTF-8（CESU-8）：emoji 会被编成两对代理项。
        assertTrue(jni.contains("rsf_jni_utf8_of_jstring(env, j0, &n0)"), jni)
        assertTrue(jni.contains("rsf_jni_jchar_of_utf8(r.data, r.len, &rn)"), jni)
        assertTrue(jni.contains("NewString(env, ru, rn)"), jni)
        assertFalse(jni.contains("GetStringUTFChars"), jni)
        assertFalse(jni.contains("NewStringUTF"), jni)
        // 入参缓冲要在交给 JVM 之后再释放（返回的 r 可能就是入参的切片）。
        assertTrue(jni.indexOf("NewString(env, ru, rn)") < jni.indexOf("free(c0);"), jni)
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

    @Test
    fun `字符串的长度与下标按 UTF-16 码元算，不按字节`() {
        val source = SourceFile.of("u.kt", "fn main() -> void { println(intToString(1)) }")
        val lib = CBackend(Rsf.analyze(source)).runtimeLibSource()
        // 码元视图的四个辅助：步长 / 码点 / 码元数 / 码元下标 -> 字节偏移
        assertTrue(lib.contains("static inline int64_t rsf_u16_step("), lib.take(200))
        assertTrue(lib.contains("static inline uint32_t rsf_u16_cp("), lib.take(200))
        assertTrue(lib.contains("static inline int64_t rsf_u16_len("), lib.take(200))
        assertTrue(lib.contains("static inline int64_t rsf_u16_to_byte("), lib.take(200))
        // 星面字符按代理对分两个码元（与 Java charAt 对齐）
        assertTrue(lib.contains("0xD800"), lib.take(200))
        assertTrue(lib.contains("0xDC00"), lib.take(200))
        // length 走码元数，substring 仍零拷贝（只算两次字节偏移再挪指针）
        assertTrue(lib.contains("static inline int32_t rsf_str_len(rsf_str s) { return (int32_t)rsf_u16_len_of(s); }"), lib.take(200))
        assertTrue(lib.contains("r.len = b - a; r.data = (char*)s.data + a;"), lib.take(200))
        // 码元数缓存：units1 = 码元数 + 1（0 = 还不知道），字面量与拼接在创建时就算好
        assertTrue(lib.contains("static inline int64_t rsf_u16_len_of(rsf_str s) {"), lib.take(200))
        assertTrue(lib.contains("if (s.units1 > 0) return s.units1 - 1;"), lib.take(200))
        // 非 ASCII 的长串走码元表缓存，charAt / length 才不会是 O(n)
        assertTrue(lib.contains("#define RSF_CP_MIN_LEN 256"), lib.take(200))
        assertTrue(lib.contains("static inline rsf_cp_entry* rsf_cp_build(rsf_str s) {"), lib.take(200))
        assertTrue(lib.contains("e->cps[u++] = 0xD800u + (v >> 10);"), lib.take(200))
        assertTrue(lib.contains("rsf_cp_clear();   /* 扫掉的串可能就在码元表里 */"), lib.take(200))
        assertTrue(lib.contains("static inline rsf_str rsf_str_lit_u(const char* data, int64_t len, int64_t units1) {"), lib.take(200))
        assertTrue(lib.contains("s.units1 = (a.units1 > 0 && b.units1 > 0) ? a.units1 + b.units1 - 1 : 0;"), lib.take(200))
        assertTrue(lib.contains("r.units1 = (s.units1 > 0 && s.units1 - 1 == s.len) ? (r.len + 1) : 0;"), lib.take(200))
        // 切在代理对中间时 native 外扩到码点（UTF-8 存不下孤立代理项），注释要留着
        assertTrue(lib.contains("native 侧的"), lib.take(200))
        assertTrue(lib.contains("只以码点边界收尾，永远不产生非法 UTF-8"), lib.take(200))
        // 字节数走 rsf_str.len（O(1)），不再数一遍 UTF-8
        assertTrue(
            lib.contains("static inline int32_t rsf_str_byte_len(rsf_str s) { return (int32_t)s.len; }"),
            lib.take(200),
        )
    }

    @Test
    fun `byteLength 摊成 rsf_str_byte_len`() {
        val c = emit(
            "fn main() -> void { println(intToString(byteLength(\"中文abc\"))) }",
        )
        assertTrue(c.contains("rsf_str_byte_len("), c.take(600))
    }

    @Test
    fun `切片摊成 rsf_str_slice，越界与倒序都在运行时钳住`() {
        val c = emit(
            "fn main() -> void { println(\"中文😀abc\"[1..3]) }",
        )
        assertTrue(c.contains("rsf_str_slice("), c.take(600))
        val rt = CBackend(Rsf.analyze(SourceFile.of("c.kt", "fn main() -> void { rtn }"))).runtimeLibSource()
        assertTrue(
            rt.contains("static inline rsf_str rsf_str_slice(rsf_str s, int32_t from, int32_t to)"),
            rt.take(600),
        )
        assertTrue(rt.contains("if (t < f) t = f;"), rt.take(600))
        assertTrue(rt.contains("return rsf_str_substr(s, (int32_t)f, (int32_t)t);"), rt.take(600))
    }

    @Test
    fun `gc on 才在 main 里装内置 GC`() {
        val on = emit(
            """
            gc on
            fn main() -> void { println(intToString(1)) }
            """.trimIndent(),
        )
        assertTrue(on.contains("if (!rsf_gc_thread_attached()) rsf_gc_thread_attach();"), on.take(600))
        assertTrue(on.contains("rsf_gc_heap_install();"), on.take(600))

        val off = emit("fn main() -> void { println(intToString(1)) }")
        assertFalse(off.contains("rsf_gc_heap_install();"), off.take(600))
        assertFalse(off.contains("rsf_gc_thread_attach();"), off.take(600))
    }

    @Test
    fun `内置 GC 的运行时也在头文件里，且只有 gc on 的产物会切分配器`() {
        val source = SourceFile.of("gc.kt", "fn main() -> void { println(intToString(1)) }")
        val backend = CBackend(Rsf.analyze(source))
        val lib = backend.runtimeLibSource()
        // 标记-清扫那套：对象头 + 链 + 标记 + 清扫 + install
        assertTrue(lib.contains("typedef struct rsf_gc_obj {"), lib.take(200))
        assertTrue(lib.contains("static inline void rsf_gc_heap_install(void)"), lib.take(200))
        assertTrue(lib.contains("static bool rsf_gc_cycle(void)"), lib.take(200))
        assertTrue(lib.contains("rsf_gc_live -= o->size;"), lib.take(200))
        // 没 attach 就一块都不回收（宁可漏，不能把活对象当垃圾）
        assertTrue(lib.contains("if (!rsf_gc_stack_bounds(&lo, &hi)) return false;"), lib.take(200))
        // 宿主自己的 GC 优先
        assertTrue(lib.contains("if (c->gc.collect != NULL) return;"), lib.take(200))
    }
}
