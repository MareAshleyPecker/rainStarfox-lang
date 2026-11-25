package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lsp.EditorSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * 脚本模式（`.rsfs`）：顶层可以直接写语句，编译器把它们裹进合成的 `main`。
 *
 * 与 `.rsf` 的**唯一**差别就是这一条——所以这里既钉「脚本能跑」，也钉「`.rsf` 仍然不能」，
 * 免得把严格的普通文件放松成脚本。
 */
class ScriptModeTest {

    private val scriptBody = """
        const val greeting : String = "hello"
        const var n : Int = 0
        n = n + 41
        println(greeting + " script, n=" + intToString(n + 1))
    """.trimIndent()

    private fun tempFile(name: String, text: String): File {
        val dir = Files.createTempDirectory("rsf-script").toFile()
        return File(dir, name).apply { writeText(text, Charsets.UTF_8) }
    }

    private fun run(model: org.example.complie.sema.SemanticModel): String {
        val buffer = ByteArrayOutputStream()
        PrintStream(buffer, true, "UTF-8").use { out -> Rsf.interpret(model, out) }
        return buffer.toString("UTF-8").replace("\r\n", "\n").trim()
    }

    @Test
    fun `rsfs 的顶层语句裹进合成的 main，能直接跑`() {
        val file = tempFile("Build.rsfs", scriptBody)
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)
        // 合成的 main 就是入口：没有手写 main 也能跑
        assertEquals("main", model.entry?.name)
        assertEquals("hello script, n=42", run(model))
    }

    @Test
    fun `普通 rsf 仍然不许写顶层语句`() {
        val file = tempFile("Plain.rsf", scriptBody)
        val e = assertThrows<RsfError> { Rsf.analyze(Rsf.load(file)) }
        assertTrue(e.message!!.contains("顶层只能写函数声明"), e.message)
    }

    @Test
    fun `同样的内容换个扩展名就按脚本走`() {
        val file = tempFile("Plain.rsf", scriptBody)
        val model = Rsf.analyze(Rsf.load(file, script = true))
        assertEquals("hello script, n=42", run(model))
    }

    @Test
    fun `脚本里已经有 fn main 时，顶层就不能再写语句`() {
        val file = tempFile(
            "Both.rsfs",
            """
            println("top-level")
            fn main() -> void { println("explicit") }
            """.trimIndent(),
        )
        val e = assertThrows<RsfError> { Rsf.analyze(Rsf.load(file)) }
        assertTrue(e.message!!.contains("已经有 fn main()"), e.message)
        assertEquals(1, e.line)
    }

    @Test
    fun `脚本里顶层语句不能带注解`() {
        val file = tempFile(
            "Anno.rsfs",
            """
            @Deprecated
            println("x")
            """.trimIndent(),
        )
        val e = assertThrows<RsfError> { Rsf.analyze(Rsf.load(file)) }
        assertTrue(e.message!!.contains("注解只能标在声明上"), e.message)
    }

    @Test
    fun `LSP 的 script 开关让同一份文件不再报顶层语句的错`() {
        val file = tempFile("Plain.rsf", scriptBody)
        val strict = EditorSupport.diagnostics(file, null, scriptBody)
        assertEquals(1, strict.size)
        assertTrue(strict[0].message.contains("顶层只能写函数声明"), strict[0].message)

        val asScript = EditorSupport.diagnostics(file, null, scriptBody, script = true)
        assertTrue(asScript.isEmpty(), asScript.joinToString { it.message })
    }

    @Test
    fun `脚本的补全仍然列出内置函数，能看到编译相关的三个`() {
        val file = tempFile("Build.rsfs", "compileAll(\"src\")\n")
        val labels = EditorSupport.completions(file, "compi", 1, 6, null, script = true).map { it.label }
        assertTrue(labels.contains("compileAll"), labels.joinToString())
        assertTrue(labels.contains("compile"), labels.joinToString())
        assertTrue(labels.contains("outDir"), labels.joinToString())
    }
}
