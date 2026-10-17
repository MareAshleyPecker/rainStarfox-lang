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
 * 阶段 24 `as` / `?as` / `is` / `!is` 的测试（刀 24a）。
 *
 * 口径与全仓一致：**同一份源码，解释器与 `Rsf.build` 出来的 .class 的 stdout 必须逐字节相同**，
 * 所以每个运行时用例都跑两端（helper 与 `InterfaceRuntimeTest.kt` / `InteropTest.kt` 同一套）。
 * 失败文案是**判据的一部分**（工单 §0 的 Q1）：`rsf: 不能把 X 当成 Y`，X / Y 都是
 * `Class.getName()` 那种点分全名（数组是 `[LB;` 这种描述符、null 印 `null`），
 * 两端逐字一致才算过——把消息 `println` 出来比 stdout 就是在比文案。
 *
 * 静态检查只查四件事（Q5）：目标引用类型/数组、目标不可空、不许链式、非泛型不许写实参；
 * 「两个类型不可能相关」**故意不做静态报错**，交给运行期（见 `不相关的引用类型只到运行期才失败`）。
 */
class CastTest {

    // ================================================================== is / !is

    @Test
    fun `is 与 !is 判类、父类和接口`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }
            interface Named { fun name() -> string }
            class N : A(), Named { override fun name() -> string { rtn "n" } }

            fun main() -> void {
                var b : B = new B()
                var a : A = b
                var n : Named = new N()
                println("" + (a is B))
                println("" + (a is A))
                println("" + (b is A))
                println("" + (b is B))
                println("" + (n is A))
                println("" + (a !is B))
                println("" + (b !is B))
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(
            listOf("true", "true", "true", "true", "true", "false", "false"),
            out,
        )
    }

    @Test
    fun `is 的结果就是 bool`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }

            fun main() -> void {
                var b : B = new B()
                var a : A = b
                var t : bool = a is B
                var f : bool = a !is A
                println("" + t)
                println("" + f)
                if (t) { println("t") } else { println("else") }
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("true", "false", "t"), out)
    }

    // ================================================================== as

    @Test
    fun `as 上转型是空操作`() {
        val out = bothEnds(
            """
            class A { fun who() -> string { rtn "A" } }
            class B : A() { }

            fun main() -> void {
                var b : B = new B()
                var b2 : B = b as B
                var a : A = b2 as A
                println(b2.who())
                println(a.who())
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("A", "A"), out)
    }

    @Test
    fun `as 下转型之后能调子类成员`() {
        val out = bothEnds(
            """
            class A { fun who() -> string { rtn "A" } }
            class B : A() {
                fun only() -> string { rtn "only-B" }
                override fun who() -> string { rtn "B" }
            }

            fun main() -> void {
                var b : B = new B()
                var a : A = b
                var b2 : B = a as B
                println(b2.only())
                println(b2.who())
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("only-B", "B"), out)
    }

    @Test
    fun `as 失败时抛出运行期全名文案`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }
            class C : A() { }

            fun main() -> void {
                var c : C = new C()
                var a : A = c
                try {
                    var b : B = a as B
                    println("不该到这里")
                } catch (e : string) {
                    println(e)
                }
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("rsf: 不能把 C 当成 B"), out)
    }

    @Test
    fun `不相关的引用类型只到运行期才失败`() {
        // Q5：静态只查四件事，不报「这两个类型不可能相关」。`string as A` 前端放行，
        // 运行期按 `Class.getName()` 打 `java.lang.String`。
        val out = bothEnds(
            """
            class A { }

            fun main() -> void {
                try {
                    var a : A = "hi" as A
                    println("不该到这里")
                } catch (e : string) {
                    println(e)
                }
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("rsf: 不能把 java.lang.String 当成 A"), out)
    }

    @Test
    fun `null 的三种行为`() {
        val out = bothEnds(
            """
            class A { }

            fun main() -> void {
                var a : ?A = null
                println("" + (a is A))
                println("" + (a !is A))
                var b : ?A = a ?as A
                println("" + (b is A))
                try {
                    var c : A = a as A
                    println("不该到这里")
                } catch (e : string) {
                    println(e)
                }
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(
            listOf("false", "true", "false", "rsf: 不能把 null 当成 A"),
            out,
        )
    }

    @Test
    fun `安全转换 as 的三种情形`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }
            class C : A() { }

            fun main() -> void {
                var b : B = new B()
                var a : A = b
                var ok : ?B = a ?as B
                println("" + (ok is B))
                var c : C = new C()
                var a2 : A = c
                var bad : ?B = a2 ?as B
                println("" + (bad is B))
                println("" + (bad == null))
                var n : ?A = null
                var none : ?B = n ?as B
                println("" + (none == null))
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("true", "false", "true", "true"), out)
    }

    // ================================================================== 接口

    @Test
    fun `接口判型与转型`() {
        val out = bothEnds(
            """
            interface Greeter {
                fun greet() -> string { rtn "hi" }
            }
            class Impl : Greeter { }
            class Base { }
            class Both : Base(), Greeter { }

            fun main() -> void {
                var g : Greeter = new Impl()
                println("" + (g is Impl))
                println("" + (g is Greeter))
                var i : Impl = g as Impl
                println(i.greet())
                var g2 : Greeter = new Both()
                println("" + (g2 is Base))
                var b : Base = g2 as Base
                println("" + (b is Greeter))
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("true", "true", "hi", "true", "true"), out)
    }

    // ================================================================== 数组

    @Test
    fun `数组按协变判型`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }
            class C : A() { }

            fun main() -> void {
                var bs : B[] = new B[2]
                var as1 : A[] = bs
                println("" + (bs is A[]))
                println("" + (as1 is B[]))
                println("" + (as1 is A[]))
                var as2 : A[] = new C[1]
                println("" + (as2 is B[]))
                var bs2 : B[] = as1 as B[]
                println("" + (bs2 is B[]))
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("true", "true", "true", "false", "true"), out)
    }

    @Test
    fun `数组转型失败文案印数组描述符`() {
        val out = bothEnds(
            """
            class A { }
            class B : A() { }
            class C : A() { }

            fun main() -> void {
                var as1 : A[] = new C[1]
                try {
                    var bs : B[] = as1 as B[]
                    println("不该到这里")
                } catch (e : string) {
                    println(e)
                }
                rtn
            }
            """.trimIndent(),
            "Probe",
        )
        assertEquals(listOf("rsf: 不能把 [LC; 当成 [LB;"), out)
    }

    // ================================================================== 泛型（擦除）

    @Test
    fun `泛型实参允许写但按擦除判并发警告`() {
        val program = """
            class Box&<T>(v : T) {
                var value : T = v
                fun get() -> T { rtn value }
            }

            fun main() -> void {
                var b : Box&<int> = new Box&<int>(7)
                var o : Box&<int> = b as Box&<int>
                var n : int = o.get()
                println("" + n)
                println("" + (o is Box&<int>))
                println("" + (o is Box&<string>))
                rtn
            }
        """.trimIndent()
        val out = bothEnds(program, "Probe")
        assertEquals(listOf("7", "true", "true"), out)

        val warnings = Rsf.analyze(source(program)).warnings
        assertTrue(
            warnings.any { it.contains("泛型实参只用于报错") },
            "写了 &<int> 就要发一条 stderr 警告，实际：$warnings",
        )
        assertTrue(
            warnings.any { it.contains("Box&<int>") },
            "警告里要带上用户写的那个类型，实际：$warnings",
        )
    }

    @Test
    fun `没写泛型实参就不警告`() {
        // 泛型类的类型实参在 rsf 里是必写的（`Box` 单独写会报「类型实参个数不对」），
        // 所以「不写实参」这条只能拿非泛型目标来验：非泛型目标不该冒擦除警告。
        val program = """
            class A { }
            class B : A() { }

            fun main() -> void {
                var b : B = new B()
                var a : A = b
                var a2 : A = a as A
                println("" + (a2 is A))
                rtn
            }
        """.trimIndent()
        bothEnds(program, "Probe")
        assertTrue(
            Rsf.analyze(source(program)).warnings.none { it.contains("泛型实参") },
            "非泛型目标不该发擦除警告",
        )
    }

    // ================================================================== 静态拒绝

    @Test
    fun `目标类型不许写可空`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var b : A = a as ?A
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("目标类型不许写可空"), e.detail)
    }    @Test
    fun `数值类型不许参与 as 与 is`() {
        val asError = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fun main() -> void {
                        var n : int = 1
                        var x : int = n as int
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("checker", asError.phase)
        assertTrue(asError.detail.contains("'as' 只用于引用类型和数组"), asError.detail)
        assertTrue(asError.detail.contains("toByte"), asError.detail)

        val isError = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    fun main() -> void {
                        var n : int = 1
                        println("" + (n is int))
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("checker", isError.phase)
        assertTrue(isError.detail.contains("'is' 只用于引用类型和数组"), isError.detail)

        val targetError = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var x : A = a as int
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertTrue(targetError.detail.contains("目标类型必须是引用类型或数组"), targetError.detail)
    }

    @Test
    fun `as 与 is 不许链式`() {
        val asChain = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var b : A = a as A as A
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("parser", asChain.phase)
        assertTrue(asChain.detail.contains("只允许写一层"), asChain.detail)

        val isChain = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        println("" + (a is A is A))
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("parser", isChain.phase)
        assertTrue(isChain.detail.contains("只允许写一层"), isChain.detail)
    }

    @Test
    fun `安全转换的结果可空不能赋给非空`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : ?A = new A()
                        var b : A = a ?as A
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("?A"), e.detail)
    }

    @Test
    fun `未知目标类型被拒`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var b : A = a as Nope
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("未知类型"), e.detail)
    }

    @Test
    fun `非泛型类型不许写类型实参`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var b : A = a as A&<int>
                        rtn
                    }
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("非泛型类型不许写类型实参"), e.detail)
    }

    // ================================================================== AST dump

    @Test
    fun `AST dump 露出 as 与 is 的形状`() {
        val dump = AstDump.dump(
            Rsf.parse(
                source(
                    """
                    class A { }
                    fun main() -> void {
                        var a : A = new A()
                        var b : A = a as A
                        var c : ?A = a ?as A
                        println("" + (a is A))
                        println("" + (a !is A))
                        rtn
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertTrue(dump.contains("AS safe=false"), dump)
        assertTrue(dump.contains("AS safe=true"), dump)
        assertTrue(dump.contains("IS negated=false"), dump)
        assertTrue(dump.contains("IS negated=true"), dump)
        assertTrue(dump.contains("TARGET type=A"), dump)
    }

    @Test
    fun `AST dump 的目标类型带泛型实参与数组后缀`() {
        val dump = AstDump.dump(
            Rsf.parse(
                source(
                    """
                    class Box&<T>(v : T) {
                        var value : T = v
                    }
                    class A { }
                    fun main() -> void {
                        var b : Box&<int> = new Box&<int>(1)
                        println("" + (b is Box&<int>))
                        var a : A[] = new A[0]
                        println("" + (a is A[]))
                        rtn
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertTrue(dump.contains("TARGET type=Box&<int>"), dump)
        assertTrue(dump.contains("TARGET type=A[]"), dump)
    }

    // ================================================================== helper

    /** 解释器跑一遍、编译产物再跑一遍，断言 stdout 逐字节相同，返回行列表。 */
    private fun bothEnds(text: String, className: String): List<String> {
        val interpreted = runInterpreted(text)
        val compiled = runCompiledClasses(text, className)
        assertEquals(
            normalize(interpreted),
            normalize(compiled),
            "解释器与字节码对 as / is 必须逐字节一致",
        )
        return lines(interpreted)
    }

    private fun source(text: String): SourceFile = SourceFile.of("<cast>", text)

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-cast", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')

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

    private fun runCompiledClasses(text: String, className: String): String {
        val src = source(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, className, dir)
        val (loader, programName) = programLoader(out.classFiles, dir)
        return runMain(loader.loadClass(programName))
    }

    private fun runInterpreted(text: String): String {
        val model: SemanticModel = Rsf.analyze(source(text))
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
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
}
