package org.example

import org.example.complie.Rsf
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
                    var xs : int[] = new int[2]
                    var a : int = head(xs)
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
}
