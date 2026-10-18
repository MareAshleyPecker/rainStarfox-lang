package org.rainstarfox.idea

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * 语言服务器客户端：URI 编码、响应解析，以及一条**真的连服务器**的集成用例。
 *
 * 集成用例需要仓库根已经 `gradlew jar` 过；没有 jar 就跳过（不把插件测试绑死在对
 * 另一个工程的构建产物上，否则单独 clone 插件仓库时一片红）。
 */
class RsfLspClientTest {

    // ------------------------------------------------------------------ URI

    @Test
    fun `路径转 URI 盘符不编码 空格要编码`() {
        assertEquals("file:///D:/java/rainStarfox/a.rsf", RsfLspClient.toUri("D:\\java\\rainStarfox\\a.rsf"))
        assertEquals("file:///D:/a%20b/c.rsf", RsfLspClient.toUri("D:/a b/c.rsf"))
        // Windows 上是大小写不敏感的，但编码结果必须稳定（同一路径两次调用一致）
        assertEquals(RsfLspClient.toUri("C:/x/y.rsf"), RsfLspClient.toUri("C:\\x\\y.rsf"))
    }

    @Test
    fun `JSON 字符串转义 控制字符与引号反斜杠`() {
        assertEquals("\"a\\\"b\"", RsfLspClient.quote("a\"b"))
        assertEquals("\"a\\\\b\"", RsfLspClient.quote("a\\b"))
        assertEquals("\"a\\nb\"", RsfLspClient.quote("a\nb"))
        assertEquals("\"中文\"", RsfLspClient.quote("中文"))
    }

    // ------------------------------------------------------------------ 对齐

    private fun diag(uri: String, line: Int, col: Int, endCol: Int, message: String) = RsfLspClient.Diagnostic(
        uri = uri, line = line, col = col, endCol = endCol, severity = "error", phase = "checker", message = message,
    )

    @Test
    fun `只留本文档的诊断 别的文件不画到本文档上`() {
        val mine = "file:///D:/p/a.rsf"
        val aligned = RsfDiagnostics.align(
            listOf(diag(mine, 3, 5, 8, "本文档"), diag("file:///D:/p/b.rsf", 7, 1, 4, "别的文件")),
            mine,
        )
        assertEquals(listOf("本文档"), aligned.map { it.message })
    }

    @Test
    fun `同位置同文案只留一条 不同位置都留`() {
        val uri = "file:///D:/p/a.rsf"
        val aligned = RsfDiagnostics.align(
            listOf(
                diag(uri, 1, 1, 2, "重复"),
                diag(uri, 1, 1, 2, "重复"),
                diag(uri, 1, 9, 10, "另一处"),
            ),
            uri,
        )
        assertEquals(listOf("重复", "另一处"), aligned.map { it.message })
    }

    @Test
    fun `URI 比较忽略大小写与百分号编码`() {
        assertTrue(RsfDiagnostics.sameDocument("file:///D:/P/a.rsf", "file:///d:/p/a.rsf"))
        assertTrue(RsfDiagnostics.sameDocument("file:///D:/P/a%20b.rsf", "file:///D:/P/a b.rsf"))
        assertTrue(!RsfDiagnostics.sameDocument("file:///D:/p/b.rsf", "file:///D:/p/a.rsf"))
    }

    // ------------------------------------------------------------------ 真服务器

    @Test
    fun `连真服务器 拿到中文诊断与带签名的补全`() {
        val jar = newestCompilerJar()
        assumeTrue(jar != null, "仓库根没有 build/libs/*.jar，跳过集成用例")
        // 编译器 jar 是 JDK 25 编译的（class major 69），IDEA 自带的 JBR 21 跑不了它：
        // 测试要挑一个能跑的 java，挑不到就跳过（这正是插件在 jar 模式下要面对的同一个坑）
        val java = javaThatCanRunCompilerJar()
        assumeTrue(java != null, "找不到能跑 JDK 25 产物的 java，跳过集成用例")

        val workDir = File(".").absoluteFile
        val command = RsfCommand(
            executable = java!!.absolutePath,
            prefix = listOf("-Dstdout.encoding=UTF-8", "-jar", jar!!.absolutePath),
            description = "测试用 jar",
        )
        val client = RsfLspClient.start(command, workDir)
        assumeTrue(client != null, "服务器没起来（编译器版本是否支持 lsp 子命令？）")
        try {
            val target = File(workDir, "build/tmp-lsp-test")
            target.mkdirs()
            val file = File(target, "sample.rsf")
            file.deleteOnExit()
            val text = "fn add(int a, int b) -> int {\n    rtn a + b\n}\n\nfn main() -> void {\n    nope()\n    rtn\n}\n"
            file.writeText(text, Charsets.UTF_8)
            val uri = RsfLspClient.toUri(file.path)

            val diagnostics = client!!.diagnose(uri, text)
            assertEquals(1, diagnostics.size, "遇错即停：只应报一条 error")
            assertEquals(6, diagnostics[0].line)
            assertTrue(diagnostics[0].message.contains("未定义的函数"), diagnostics[0].message)
            assertTrue(diagnostics[0].endCol > diagnostics[0].col, "划词范围应该比单字符宽")

            // 补全：光标停在 main 里，应该能补出 add（带签名）
            val completions = client.complete(uri, text, 5, 4)
            val add = completions.firstOrNull { it.label == "add" }
            assertTrue(add != null, "应该补出 add：${completions.map { it.label }}")
            // 签名形如 `add(a : int, b : int) -> int`：只要名字、参数和返回类型都在
            assertTrue(add!!.detail.startsWith("add("), add.detail)
            assertTrue(add.detail.contains("-> int"), add.detail)
            assertEquals("function", add.kind)
        } finally {
            client?.close()
        }
    }

    @Test
    fun `连真服务器 增量同步 找引用 重命名`() {
        val jar = newestCompilerJar()
        assumeTrue(jar != null, "仓库根没有 build/libs/*.jar，跳过集成用例")
        val java = javaThatCanRunCompilerJar()
        assumeTrue(java != null, "找不到能跑 JDK 25 产物的 java，跳过集成用例")

        val workDir = File(".").absoluteFile
        val command = RsfCommand(
            executable = java!!.absolutePath,
            prefix = listOf("-Dstdout.encoding=UTF-8", "-jar", jar!!.absolutePath),
            description = "测试用 jar",
        )
        val client = RsfLspClient.start(command, workDir)
        assumeTrue(client != null, "服务器没起来（编译器版本是否支持 lsp 子命令？）")
        try {
            val target = File(workDir, "build/tmp-lsp-refs")
            target.mkdirs()
            val file = File(target, "refs.rsf")
            file.deleteOnExit()
            val text = """fn add(a : int, b : int) -> int {
    rtn a + b
}

fn main() -> void {
    var x : int = add(1, 2)
    var y : int = add(x, 3)
    rtn
}
"""
            file.writeText(text, Charsets.UTF_8)
            val uri = RsfLspClient.toUri(file.path)

            assertEquals(0, client!!.diagnose(uri, text).size, "干净文件不该有诊断")

            // 增量：只改一个字符（客户端会发 range 补丁，不是整篇）
            val edited = text.replace("add(x, 3)", "add(x, 4)")
            assertEquals(0, client.diagnose(uri, edited).size, "增量补丁后的正文也应该没问题")

            val line = 5
            val character = edited.lines()[line].indexOf("add")
            val usages = client.references(uri, edited, line, character)
            assertEquals(3, usages.size, "声明 1 处 + 调用 2 处：${usages.map { it.path + ":" + it.line }}")

            val edits = client.rename(uri, edited, line, character, "plus")
            assertTrue(edits != null, "应该能重命名函数")
            val all = edits!!.values.flatten()
            assertEquals(3, all.size, "重命名应该改 3 处：$edits")
            assertTrue(all.all { it.newText == "plus" }, edits.toString())
        } finally {
            client?.close()
        }
    }

    /** 找一台能跑编译器 jar 的 java：JAVA_HOME 优先，其次 PATH；版本不够就当没有。 */
    private fun javaThatCanRunCompilerJar(): File? {
        val candidates = ArrayList<File>()
        System.getenv("JAVA_HOME")?.let { candidates.add(File(it, "bin/java.exe")); candidates.add(File(it, "bin/java")) }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            candidates.add(File(dir, "java.exe"))
            candidates.add(File(dir, "java"))
        }
        return candidates.firstOrNull { it.isFile && javaMajor(it) >= 25 }
    }

    private fun javaMajor(java: File): Int = try {
        val process = ProcessBuilder(java.absolutePath, "-version")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("""version "([0-9]+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    }

    /** 仓库根的胖 jar（`gradlew jar` 的产物），取最新一个。 */
    private fun newestCompilerJar(): File? =
        File("../build/libs")
            .listFiles { f -> f.isFile && f.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
}
