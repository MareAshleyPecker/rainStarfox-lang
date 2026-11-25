package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.native.CBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 85：全局变量 `const var` / `const val`。
 *
 * 这一版（v1）三个后端里只有**解释器**接了全局存储：
 * - `rsf run foo.rsf` 与 `rsf build`（构建脚本走解释器）可用；
 * - 字节码后端与 C 后端**明确报错**（错误消息里写清「下一步才接」），
 *   而不是静默编出一份把全局状态丢掉的产物。
 */
class GlobalVarTest {

    /** 跑一份单文件程序，返回它打印出来的行。 */
    private fun runInterpreted(text: String): List<String> {
        val source = SourceFile.of("main.rsf", text)
        val model = Rsf.analyze(source)
        val out = ByteArrayOutputStream()
        PrintStream(out, true, "UTF-8").use { stream -> Rsf.interpret(model, stream) }
        return out.toString("UTF-8").replace("\r\n", "\n").trimEnd('\n').lines()
    }

    /** 跑一个目录里的多文件程序（入口是 main.rsf）。 */
    private fun runProject(files: Map<String, String>): List<String> {
        val dir = Files.createTempDirectory("rsf-globals").toFile()
        for ((name, text) in files) File(dir, name).writeText(text, Charsets.UTF_8)
        val root = File(dir, "main.rsf")
        val model = Rsf.analyze(Rsf.load(root, dir, null))
        val out = ByteArrayOutputStream()
        PrintStream(out, true, "UTF-8").use { stream -> Rsf.interpret(model, stream) }
        return out.toString("UTF-8").replace("\r\n", "\n").trimEnd('\n').lines()
    }

    @Test
    fun `const val 与 const var 在解释器里能读写`() {
        val text = """
            const val greeting : String = "hello"
            const var counter : Int = 0

            fn bump() -> void { counter = counter + 1 }

            fn main() -> void {
                bump()
                bump()
                println(greeting + " " + intToString(counter))
            }
        """.trimIndent()
        assertEquals(listOf("hello 2"), runInterpreted(text))
    }

    @Test
    fun `全局变量对别的文件也可见`() {
        val lines = runProject(
            mapOf(
                "config.rsf" to """
                    const val LIMIT : Int = 3
                    const var hits : Int = 0
                """.trimIndent(),
                "main.rsf" to """
                    import "config.rsf"

                    fn main() -> void {
                        hits = hits + LIMIT
                        println(intToString(hits))
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("3"), lines)
    }

    @Test
    fun `没有声明的类型靠初值推断`() {
        val text = """
            const val baseline = 40 + 2
            fn main() -> void { println(intToString(baseline)) }
        """.trimIndent()
        assertEquals(listOf("42"), runInterpreted(text))
    }

    @Test
    fun `顶层裸 var 或 val 要提示补 const`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(SourceFile.of("main.rsf", "var x : Int = 1\nfn main() -> void { rtn }"))
        }
        assertTrue(e.message!!.contains("const"), e.message)
        assertEquals(1, e.line)
    }

    @Test
    fun `给 const val 赋值要报错`() {
        val text = """
            const val answer : Int = 42
            fn main() -> void { answer = 0 }
        """.trimIndent()
        val e = assertFailsWith<RsfError> { Rsf.analyze(SourceFile.of("main.rsf", text)) }
        assertTrue(e.message!!.contains("const val"), e.message)
        assertTrue(e.message!!.contains("answer"), e.message)
    }

    @Test
    fun `全局变量必须写初值`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(SourceFile.of("main.rsf", "const val x : Int\nfn main() -> void { rtn }"))
        }
        assertTrue(e.message!!.contains("初值"), e.message)
    }

    @Test
    fun `字节码后端明确拒绝全局变量`() {
        val text = """
            const val answer : Int = 42
            fn main() -> void { println(intToString(answer)) }
        """.trimIndent()
        val e = assertFailsWith<RsfError> {
            Rsf.build(Rsf.analyze(SourceFile.of("main.rsf", text)), SourceFile.of("main.rsf", text), "Main", Files.createTempDirectory("rsf-g").toFile())
        }
        assertTrue(e.message!!.contains("字节码后端还没接全局变量"), e.message)
    }

    @Test
    fun `C 后端明确拒绝全局变量`() {
        val text = """
            target native
            const val answer : Int = 42
            fn main() -> void { println(intToString(answer)) }
        """.trimIndent()
        val model = Rsf.analyze(SourceFile.of("main.rsf", text))
        val e = assertFailsWith<RsfError> { CBackend(model, withEntry = true).emit() }
        assertTrue(e.message!!.contains("全局变量"), e.message)
    }
}
