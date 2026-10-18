package org.example

import org.example.complie.Rsf
import org.example.complie.interp.Interpreter
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 31a：泛型函数的**前端与语义**（`fn id&<T>(x : T) -> T`）。
 *
 * 覆盖：语法与 dump 形状、四种结构化统一（`T` / `T[]` / `?T` / `Box&<T>`）、
 * 实例/静态方法、非泛型重载优先，以及 DESIGN-31 §5 的逐字文案。
 */
class GenericFnTest {

    private fun source(text: String): SourceFile =
        SourceFile.of("<generic-fn>", text.trimIndent())

    // ------------------------------------------------------------ 语法与 dump

    @Test
    fun `顶层泛型函数 能解析 类型参数进 AST`() {
        val program = Rsf.parse(
            source(
                """
                fn id&<T>(x : T) -> T {
                    rtn x
                }
                """,
            ),
        )
        assertEquals(listOf("T"), program.functions.single { it.name == "id" }.typeParams)
    }

    @Test
    fun `两个类型参数 按声明顺序记录`() {
        val program = Rsf.parse(
            source(
                """
                fn pair&<T, U>(a : T, b : U) -> T {
                    rtn a
                }
                """,
            ),
        )
        assertEquals(
            listOf("T", "U"),
            program.functions.single { it.name == "pair" }.typeParams,
        )
    }

    @Test
    fun `dump 在 FUNC 下发 TYPE_PARAMS`() {
        val dump = AstDump.dump(
            Rsf.parse(
                source(
                    """
                    fn id&<T>(x : T) -> T {
                        rtn x
                    }
                    """,
                ),
            ),
        )
        assertTrue(dump.contains("TYPE_PARAMS"), dump)
        val at = dump.indexOf("TYPE_PARAMS")
        val block = dump.substring(at, minOf(dump.length, at + 200))
        assertTrue(block.contains("count=1"), block)
        assertTrue(block.contains("T"), block)
    }

    @Test
    fun `没有类型参数时 dump 不带 TYPE_PARAMS`() {
        val dump = AstDump.dump(
            Rsf.parse(
                source(
                    """
                    fn plain(x : int) -> int {
                        rtn x
                    }
                    """,
                ),
            ),
        )
        assertTrue(!dump.contains("TYPE_PARAMS"), dump)
    }

    // ---------------------------------------------------------------- 推导

    @Test
    fun `T 按实参绑定 调用结果类型正确`() {
        Rsf.analyze(
            source(
                """
                fn id&<T>(x : T) -> T {
                    rtn x
                }
                fn main() -> void {
                    var a : int = id(1)
                    var b : string = id("s")
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `两个类型参数各自绑定`() {
        Rsf.analyze(
            source(
                """
                fn firstOf&<T, U>(a : T, b : U) -> T {
                    rtn a
                }
                fn main() -> void {
                    var a : string = firstOf("x", 2)
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `T 数组形参 绑定元素类型`() {
        Rsf.analyze(
            source(
                """
                fn head&<T>(xs : T[]) -> T {
                    rtn xs[0]
                }
                fn main() -> void {
                    var xs : string[] = new string[2]
                    var a : string = head(xs)
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `可空形参 只绑定里面的类型`() {
        Rsf.analyze(
            source(
                """
                fn orElse&<T>(v : ?T, d : T) -> T {
                    if (v == null) {
                        rtn d
                    }
                    rtn v!!
                }
                fn main() -> void {
                    var s : ?string = null
                    var a : string = orElse(s, "x")
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `泛型类作为返回类型 绑定后可用`() {
        Rsf.analyze(
            source(
                """
                class Box&<T> {
                    var value : T = 0
                    constructor(v0 : T) {
                        value = v0
                    }
                }
                fn wrap&<T>(v : T) -> Box&<T> {
                    rtn new Box&<T>(v)
                }
                fn main() -> void {
                    var b : Box&<int> = wrap(9)
                    var a : int = b.value
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `实例方法与静态方法都能泛型`() {
        Rsf.analyze(
            source(
                """
                class Calc {
                    var base : int = 0
                    constructor(b : int) {
                        base = b
                    }
                    public fn same&<T>(x : T) -> T {
                        rtn x
                    }
                    public static fn pick&<T>(x : T, y : T) -> T {
                        rtn x
                    }
                }
                fn main() -> void {
                    var c : Calc = new Calc(3)
                    var a : int = c.same(11)
                    var b : string = Calc.pick("x", "y")
                    rtn
                }
                """,
            ),
        )
    }

    @Test
    fun `非泛型重载优先`() {
        // 若挑了泛型版本，返回值是 int，赋给 string 会报错
        Rsf.analyze(
            source(
                """
                fn f(x : int) -> string {
                    rtn "fixed"
                }
                fn f&<T>(x : T) -> int {
                    rtn 0
                }
                fn main() -> void {
                    var s : string = f(1)
                    rtn
                }
                """,
            ),
        )
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `只出现在返回类型里 推不出来`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fn make&<T>() -> ?T {
                        rtn null
                    }
                    fn main() -> void {
                        var x : ?int = make()
                        rtn
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("推不出来"), e.detail)
    }

    @Test
    fun `同一个 T 绑到两个类型 报冲突`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fn same&<T>(a : T, b : T) -> T {
                        rtn a
                    }
                    fn main() -> void {
                        var x : int = same(1, "s")
                        rtn
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("同时被要求是"), e.detail)
    }

    @Test
    fun `类型参数写了两遍 报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fn f&<T, T>(x : T) -> T {
                        rtn x
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("写了两遍"), e.detail)
    }

    @Test
    fun `空类型参数表 报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fn f&<>(x : int) -> int {
                        rtn x
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("类型参数表是空的"), e.detail)
    }

    @Test
    fun `构造器不能泛型`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class C {
                        var x : int = 0
                        constructor&<T>(v : T) {
                        }
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("构造器不能是泛型的"), e.detail)
    }

    @Test
    fun `泛型方法不能 override`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class Base {
                        public fn tag(x : int) -> int {
                            rtn x
                        }
                    }
                    class Kid : Base() {
                        public override fn tag&<T>(x : T) -> T {
                            rtn x
                        }
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("泛型方法不能写 'override'"), e.detail)
    }

    @Test
    fun `泛型方法不能 abstract`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    interface Shape {
                        fn area&<T>(x : T) -> T
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("泛型方法不能写 'abstract'"), e.detail)
    }
    // ------------------------------------------------------ 运行时（两端一致）

    @Test
    fun `运行时 顶层泛型函数 两端一致`() {
        val (interp, jvm) = runBoth(
            """
            fn id&<T>(x : T) -> T {
                rtn x
            }
            fn main() {
                println("" + id(5))
                println(id("hi"))
                println("" + id(true))
            }
            """,
        )
        assertEquals(listOf("5", "hi", "true"), interp)
        assertEquals(interp, jvm)
    }

    @Test
    fun `运行时 两个类型参数 两端一致`() {
        val (interp, jvm) = runBoth(
            """
            fn firstOf&<T, U>(a : T, b : U) -> T {
                rtn a
            }
            fn main() {
                println("" + firstOf(7, "ignored"))
                println(firstOf("kept", 2))
            }
            """,
        )
        assertEquals(listOf("7", "kept"), interp)
        assertEquals(interp, jvm)
    }

    @Test
    fun `运行时 可空与引用数组 两端一致`() {
        val (interp, jvm) = runBoth(
            """
            fn orElse&<T>(v : ?T, d : T) -> T {
                if (v == null) {
                    rtn d
                }
                rtn v!!
            }
            fn first&<T>(xs : T[]) -> T {
                rtn xs[0]
            }
            fn main() {
                var s : ?string = null
                println(orElse(s, "fallback"))
                var t : ?string = "given"
                println(orElse(t, "fallback"))
                var ss : string[] = new string[2]
                ss[0] = "hey"
                println(first(ss))
            }
            """,
        )
        assertEquals(listOf("fallback", "given", "hey"), interp)
        assertEquals(interp, jvm)
    }

    @Test
    fun `运行时 泛型方法 两端一致`() {
        val (interp, jvm) = runBoth(
            """
            class Calc {
                var base : int = 0
                constructor(b : int) {
                    base = b
                }
                public fn same&<T>(x : T) -> T {
                    rtn x
                }
                public static fn pick&<T>(x : T, y : T) -> T {
                    rtn x
                }
            }
            fn main() {
                var c : Calc = new Calc(3)
                println("" + c.same(11))
                println(c.same("m"))
                println("" + Calc.pick(1, 2))
            }
            """,
        )
        assertEquals(listOf("11", "m", "1"), interp)
        assertEquals(interp, jvm)
    }

    @Test
    fun `基本类型数组 绑不进 T 数组`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fn first&<T>(xs : T[]) -> T {
                        rtn xs[0]
                    }
                    fn main() {
                        var xs : int[] = new int[3]
                        println("" + first(xs))
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("绑到了基本类型"), e.detail)
    }

    // ------------------------------------------------------------ 运行时工具

    /** 同一份源码跑两遍：解释器一遍、字节码加载后一遍，都只收集 stdout 的行。 */
    private fun runBoth(text: String): Pair<List<String>, List<String>> {
        val src = SourceFile.of("<generic-fn-rt>", text.trimIndent())
        val model = Rsf.analyze(src)

        val ibuf = java.io.ByteArrayOutputStream()
        Interpreter(model, java.io.PrintStream(ibuf, true, "UTF-8")).runEntry()
        val interp = lines(ibuf)

        val dir = java.io.File.createTempFile("rsf-generic", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        try {
            val className = "GmMain"
            Rsf.build(model, src, className, dir)
            val loader = java.net.URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
            val saved = System.out
            val jbuf = java.io.ByteArrayOutputStream()
            val ps = java.io.PrintStream(jbuf, true, "UTF-8")
            System.setOut(ps)
            try {
                loader.loadClass(className)
                    .getMethod("main", Array<String>::class.java)
                    .invoke(null, arrayOf<String>())
            } finally {
                System.setOut(saved)
                ps.flush()
                loader.close()
            }
            return interp to lines(jbuf)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun lines(buf: java.io.ByteArrayOutputStream): List<String> =
        buf.toString("UTF-8").lines().filter { it.isNotEmpty() }

}
