package org.example

import org.example.build.ProjectBuildHost
import org.example.complie.Rsf
import org.example.complie.interp.RsfFailure
import org.example.complie.diag.SourceFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 81：构建脚本（`build.rsfs`）真的能编译东西。
 *
 * 这里不走命令行，直接拿 [ProjectBuildHost] 喂解释器——命令行那条路只是多一层参数解析。
 */
class BuildScriptTest {

    private fun project(): File {
        val root = Files.createTempDirectory("rsf-build").toFile()
        File(root, "src").mkdirs()
        File(root, "src/main.rsf").writeText(
            """
            fn main() -> void {
                println("hello, rsf")
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return root
    }

    private fun runScript(root: File, script: String, host: ProjectBuildHost?): String {
        val file = File(root, "build.rsfs")
        file.writeText(script.trimIndent(), Charsets.UTF_8)
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)
        val buffer = ByteArrayOutputStream()
        PrintStream(buffer, true, "UTF-8").use { out ->
            Rsf.interpret(model, out, host)
        }
        return buffer.toString("UTF-8")
    }

    @Test
    fun `构建脚本能真的编译 src 下的文件`() {
        val root = project()
        val host = ProjectBuildHost(root = root, srcDir = File("src"), outDir = File("build/out"))
        val text = runScript(
            root,
            """
            fn main() -> void {
                val n = compileAll("src")
                println("n=" + intToString(n))
                println("out=" + outDir())
            }
            """,
            host,
        )
        assertTrue(text.contains("n=1"), text)
        assertTrue(File(root, "build/out/main.class").isFile, "没有产出 main.class")
    }

    @Test
    fun `compile 只编一个文件并返回产物路径`() {
        val root = project()
        val host = ProjectBuildHost(root = root, srcDir = File("src"), outDir = File("build/out"))
        val text = runScript(
            root,
            """
            fn main() -> void {
                println(compile("src/main.rsf"))
            }
            """,
            host,
        )
        assertTrue(text.trim().endsWith("main.class"), text)
        assertTrue(File(root, "build/out/main.class").isFile)
    }

    @Test
    fun `普通程序里调 compile 明确报只有构建脚本能用`() {
        val program = """
            fn main() -> void {
                println(compile("src/main.rsf"))
            }
        """.trimIndent()
        val model = Rsf.analyze(SourceFile.of("plain.rsf", program))
        val failure = assertFailsWith<RsfFailure> { Rsf.interpret(model, PrintStream(ByteArrayOutputStream())) }
        assertTrue(failure.message!!.contains("只有构建脚本"), failure.message)
    }
}
