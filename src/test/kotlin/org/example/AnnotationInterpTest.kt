package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.sema.SemanticModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 21 M4：注解的**解释器**回归测试。
 *
 * 覆盖任务书里的 (1)~(5)：
 * (1) `new Marker("hi", 3)` 造出伴生对象、**元素字段已按构造器参数赋值**；
 * (2) `m.value` 读元素字段；
 * (3) `m.value()` 元素访问器（M4 的主要活：注解接口不进 `interfaces` 表，
 *     必须用 `SemanticModel.companionAnnotationOf` / `elementAccessorOf` 直接取值）；
 * (4) 伴生体里按**裸名**读元素 / 调元素访问器；
 * (5) 伴生体自己的行为方法（`describe` / `run`）走普通类方法。
 *
 * 每一条都同时断言**两条路**（解释器 `Interpreter` / 字节码 `Rsf.build` + 反射跑 `main`）
 * 的 stdout **逐字节相同** —— 这是这个项目里「两端一致」唯一的验证方式，只看一头会漏。
 *
 * 已知差异（不算 bug，DESIGN-21 §3 选的是运行时机 B）：`public static Marker()` /
 * `Marker(string member)` 那套方案 B 访问器与懒构造缓存、`annotationType()` 都是
 * **字节码独有**的（rsf 源码里看不见，Checker 也没这些成员），所以解释器不建缓存、
 * 不建模——本文件不测它们（它们由 `AnnotationCodegenTest` 在 Java 侧守着）。
 */
class AnnotationInterpTest {

    // ============================================================ (1) 元素字段

    /** (1) `new Marker("hi", 3)` 的元素字段就是构造器实参，不是合成初值的占位。 */
    @Test
    fun `new 伴生实例的元素字段按构造器实参赋值`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi", 3)
                println(m.value)
                println(m.count)
            }
            """,
            listOf("hi", "3"),
        )
    }

    /** (1)(3) 省略的实参走元素默认值，`m.value` 与 `m.value()` 同值。 */
    @Test
    fun `省略的实参取元素默认值`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void {
                var all : Marker = new Marker()
                println(all.value + "/" + all.count)
                var one : Marker = new Marker("q")
                println(one.value + "/" + one.count)
            }
            """,
            listOf("d/0", "q/0"),
        )
    }

    /** (2)(3) `m.value`（读元素字段）与 `m.value()`（元素访问器）必须同值。 */
    @Test
    fun `元素字段读取与元素访问器同值`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi", 3)
                println(m.value)
                println(m.value())
                println(m.count)
                println(m.count())
            }
            """,
            listOf("hi", "hi", "3", "3"),
        )
    }

    /** (3) 同一个对象上两种写法连续读两次也一致（访问器不是「读一次就变」的东西）。 */
    @Test
    fun `元素访问器可以重复调用且值稳定`() {
        parity(
            """
            annotation Marker(value : string = "d") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi")
                println(m.value() + m.value())
                println(m.value)
            }
            """,
            listOf("hihi", "hi"),
        )
    }

    /** (1)(3) 各类型元素都能赋值/读出：bool / long / double / byte / char / string[]。 */
    @Test
    fun `各类型元素在解释器里按构造器实参赋值`() {
        parity(
            """
            annotation Meta(tags : string[], flag : bool = true, big : long = 9L,
                            d : double = 1.5, b : byte = 2, ch : char = 'z') {
                companion entity Impl {
                    fn describe() -> string {
                        return "tags=" + tags[0] + "," + tags.length + " flag=" + flag +
                            " big=" + big + " d=" + d + " b=" + b + " ch=" + ch
                    }
                }
            }

            fn main() -> void {
                var ts : string[] = new string[2]
                ts[0] = "aa"
                var m : Meta = new Meta(ts, false)
                println(m.flag)
                println(m.big)
                println(m.d)
                println(m.b)
                println(m.ch)
                println(m.tags.length)
                println(m.tags[0])
                println(m.tags().length)
                println(m.tags()[0])
                var c : Meta::Impl = new Meta(ts, false)
                println(c.describe())
            }
            """,
            listOf(
                "false", "9", "1.5", "2", "z", "2", "aa", "2", "aa",
                "tags=aa,2 flag=false big=9 d=1.5 b=2 ch=z",
            ),
        )
    }

    // ============================================================ (5) 伴生行为方法

    /** (5) 伴生体自己的行为方法走普通类方法（静态类型写成伴生类）。 */
    @Test
    fun `伴生行为方法可调用且看得见元素`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string {
                        return value + "/" + count
                    }
                    fn bump() -> int {
                        hits = hits + 1
                        return hits
                    }
                }
            }

            fn main() -> void {
                var c : Marker::Impl = new Marker("hi", 3)
                println(c.describe())
                println(c.bump())
                println(c.bump())
                println(c.hits)
            }
            """,
            listOf("hi/3", "1", "2", "2"),
        )
    }

    /** (5) 伴生体实现接口时，接口方法照常虚分派到伴生类那一份。 */
    @Test
    fun `伴生体实现接口的方法照常虚分派`() {
        parity(
            """
            interface Namer {
                fn name() -> string
            }

            annotation Marker(value : string = "d") {
                companion entity Impl : Namer {
                    fn name() -> string {
                        return "n:" + value
                    }
                }
            }

            fn main() -> void {
                var c : Marker::Impl = new Marker("hi")
                var n : Namer = c
                println(n.name())
                println(c.name())
            }
            """,
            listOf("n:hi", "n:hi"),
        )
    }

    // ============================================================ (4) 伴生体里的裸名

    /** (4) 伴生体里按**裸名**读元素：Checker 已经把它解析成同一个字段（`fieldReads`）。 */
    @Test
    fun `伴生体里按裸名读元素`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    fn describe() -> string {
                        return value + "/" + count
                    }
                    fn onlyValue() -> string {
                        return value
                    }
                }
            }

            fn main() -> void {
                var c : Marker::Impl = new Marker("hi", 3)
                println(c.describe())
                println(c.onlyValue())
            }
            """,
            listOf("hi/3", "hi"),
        )
    }

    /** (4) 伴生体里按裸名**调**元素访问器（`value()`）走同一条元素访问器路。 */
    @Test
    fun `伴生体里按裸名调元素访问器`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    fn viaAccessor() -> string {
                        return value() + "/" + count()
                    }
                    fn mixed() -> string {
                        return value + "/" + count()
                    }
                }
            }

            fn main() -> void {
                var c : Marker::Impl = new Marker("hi", 3)
                println(c.viaAccessor())
                println(c.mixed())
            }
            """,
            listOf("hi/3", "hi/3"),
        )
    }

    // ============================================================ 构造写法

    /** `new Marker::Impl(...)`（写伴生类的名字）与 `new Marker(...)` 造出同一种对象。 */
    @Test
    fun `new Marker_Impl 与 new Marker 等价`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    fn describe() -> string {
                        return value + "/" + count
                    }
                }
            }

            fn main() -> void {
                var a : Marker::Impl = new Marker::Impl("hi", 3)
                var b : Marker::Impl = new Marker("hi", 3)
                println(a.describe())
                println(b.describe())
                var m : Marker = new Marker::Impl("yo", 5)
                println(m.value)
                println(m.count())
            }
            """,
            listOf("hi/3", "hi/3", "yo", "5"),
        )
    }

    /** 赋给 `Marker` 之后只剩元素方法可见：行为方法在编译期就被拦住。 */
    @Test
    fun `赋给注解类型后行为方法不可见`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    annotation Marker(value : string = "d") {
                        companion entity Impl {
                            fn describe() -> string {
                                return value
                            }
                        }
                    }

                    fn main() -> void {
                        var m : Marker = new Marker("hi")
                        println(m.describe())
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("describe"), e.detail)
        assertTrue(e.detail.contains("元素"), e.detail)
    }

    /** 没有 companion entity 的注解不能 `new`（这条 M2 已拦，M4 复验一遍）。 */
    @Test
    fun `无伴生实体的注解不能 new`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    annotation Plain(v : string = "d")

                    fn main() -> void {
                        var p : Plain = new Plain()
                        println(p.v)
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("companion entity"), e.detail)
    }

    /** 手写构造器（参数与元素同名同序）也走同一套元素赋值。 */
    @Test
    fun `手写构造器的伴生类元素字段照样赋值`() {
        parity(
            """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    constructor(value : string, count : int) {
                    }
                    fn describe() -> string {
                        return value + "/" + count
                    }
                }
            }

            fn main() -> void {
                var c : Marker::Impl = new Marker("hi", 3)
                println(c.describe())
                println(c.value)
                println(c.count())
            }
            """,
            listOf("hi/3", "hi", "3"),
        )
    }

    /** 数组元素：`m.tags()` 返回的就是同一个数组引用（元素访问器不是拷贝）。 */
    @Test
    fun `元素访问器返回数组元素本身`() {
        parity(
            """
            annotation Marker(tags : string[]) {
                companion entity Impl {
                    fn first() -> string {
                        return tags[0]
                    }
                }
            }

            fn main() -> void {
                var ts : string[] = new string[2]
                ts[0] = "aa"
                ts[1] = "bb"
                var m : Marker = new Marker(ts)
                println(m.tags().length)
                println(m.tags()[1])
                var c : Marker::Impl = new Marker(ts)
                println(c.first())
            }
            """,
            listOf("2", "bb", "aa"),
        )
    }

    /**
     * 具名实参只在**注解使用点**开放（`@Marker(value = "hi", count = 3)`）。
     * 解释器不解析注解本身，所以这里断言的是「带具名实参的注解不影响程序跑」，
     * 且与位置式写法逐字节同输出（M3 侧由 `AnnotationCodegenTest` 验值）。
     */
    @Test
    fun `注解使用点的具名实参与位置式同输出`() {
        val named = """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker(value = "hi", count = 3)
            pub class Tagged {
                @Marker("f")
                var name : string = "n"

                @Marker(count = 1)
                pub fn hello() -> string {
                    var m : Marker = new Marker("hi", 3)
                    return m.value() + "/" + m.count()
                }
            }

            fn main() -> void {
                var t : Tagged = new Tagged()
                println(t.hello())
                println(t.name)
            }
        """
        val positional = """
            annotation Marker(value : string = "d", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker("hi", 3)
            pub class Tagged {
                @Marker("f")
                var name : string = "n"

                @Marker("1")
                pub fn hello() -> string {
                    var m : Marker = new Marker("hi", 3)
                    return m.value() + "/" + m.count()
                }
            }

            fn main() -> void {
                var t : Tagged = new Tagged()
                println(t.hello())
                println(t.name)
            }
        """
        parity(named, listOf("hi/3", "n"))
        assertEquals(runInterpreted(named), runInterpreted(positional), "具名实参与位置式输出不一致")
        assertEquals(runCompiled(named), runCompiled(positional), "具名实参与位置式（字节码侧）输出不一致")
    }

    /**
     * `new Marker(value = "hi")` —— `new` 的实参表只收位置式（DESIGN-21 §1：
     * 具名实参只在注解位置开放）。这条把「解释器侧为什么不用管具名实参」钉住：
     * **前端**（Parser 的普通实参表）就把它拦下来，报「实参表没有闭合」。
     */
    @Test
    fun `new 的实参表不收具名实参`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    annotation Marker(value : string = "d") {
                        companion entity Impl {
                            var hits : int = 0
                        }
                    }

                    fn main() -> void {
                        var m : Marker = new Marker(value = "hi")
                        println(m.value)
                    }
                    """,
                ),
            )
        }
        assertTrue(e.detail.contains("没有闭合"), e.detail)
    }

    /** 无注解的程序一个字节都不变：不动点（解释器侧）。 */
    @Test
    fun `没有注解的程序解释器照常跑`() {
        parity(
            """
            interface Greeter {
                fn greet(n : string) -> string
            }

            class Dog : Greeter {
                var name : string = "d"
                fn greet(n : string) -> string {
                    return "woof:" + n
                }
            }

            fn main() -> void {
                var d : Dog = new Dog()
                println(d.greet("a"))
            }
            """,
            listOf("woof:a"),
        )
    }

    // ============================================================ 工具

    /** 两条路各跑一遍，断言 stdout 逐字节相同，并且等于 [expected]。 */
    private fun parity(text: String, expected: List<String>) {
        val interpreted = runInterpreted(text)
        val compiled = runCompiled(text)
        assertEquals(expected, lines(interpreted), "解释器侧输出不符")
        assertEquals(interpreted, compiled, "解释器与字节码后端的 stdout 不一致")
    }

    /** 解释器跑一遍（= `rsf run` 走的那条路），返回 stdout 原文。 */
    private fun runInterpreted(text: String): String {
        val model: SemanticModel = Rsf.analyze(source(text))
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        // 也要换掉 System.out —— 调用 Java 的 System.out.println 时写的是它
        val saved = System.out
        System.setOut(ps)
        try {
            Interpreter(model, ps).runEntry()
        } finally {
            System.setOut(saved)
        }
        ps.flush()
        return buf.toString("UTF-8")
    }

    /** `Rsf.build` 出所有类文件 + 按需 `findClass` + 反射跑 `main`，返回 stdout 原文。 */
    private fun runCompiled(text: String): String {
        val src = source(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, RUNNER, dir)
        val (loader, programName) = programLoader(out.classFiles, dir)
        return runMain(loader.loadClass(programName))
    }

    /**
     * 注解会编译出**多个**类文件（注解接口 `Marker`、伴生类 `Marker$Impl`、程序类……），
     * 所以不能像单类那样挨个 define——一次性 define 会踩未定义类的引用，交给 `findClass`
     * 按需加载就没有顺序问题。
     */
    private fun programLoader(files: List<File>, dir: File): Pair<ClassLoader, String> {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                val file = File(dir, name.replace('.', '/') + ".class")
                if (!file.isFile) throw ClassNotFoundException(name)
                val bytes = file.readBytes()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return loader to binaryName(files.first(), dir)
    }

    private fun runMain(cls: Class<*>): String {
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        val saved = System.out
        System.setOut(ps)
        try {
            cls.getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
        } finally {
            System.setOut(saved)
        }
        ps.flush()
        return buf.toString("UTF-8")
    }

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-ann-interp", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
            .replace('/', '.')

    private fun source(text: String): SourceFile =
        SourceFile.of("<annotation-interp>", text.trimIndent())

    private companion object {
        const val RUNNER = "RsfAnnInterpRunner"
    }
}
