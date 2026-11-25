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
 * 三个后端都接了全局存储，但落点不同：
 * - 解释器：`Interpreter` 自己一张全局表，`runEntry()` 里按声明顺序初始化；
 * - JVM：程序类的静态字段（`const val` 加 `ACC_FINAL`），初值在 `<clinit>` 里；
 * - C：文件作用域的 `static`，初值在 `rsf_globals_init()` 里，`main` 开头调一次。
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

    /** 编成 .class 之后跑 main，返回打印出来的行（System.out 临时换掉）。 */
    private fun runCompiled(text: String, className: String): List<String> {
        val dir = Files.createTempDirectory("rsf-globals-jvm").toFile()
        val source = SourceFile.of("main.rsf", text)
        Rsf.build(Rsf.analyze(source), source, className, dir)
        val loader = java.net.URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
        val cls = loader.loadClass(className)
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true, "UTF-8"))
        try {
            cls.getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
        } finally {
            System.setOut(original)
        }
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
    fun `解释器与字节码跑全局变量结果一致`() {
        val text = """
            const val greeting : String = "hello"
            const var counter : Int = 0
            const val LIMIT : Int = 3

            fn bump() -> void { counter = counter + 1 }

            fn main() -> void {
                bump()
                bump()
                println(greeting + " " + intToString(counter) + "/" + intToString(LIMIT))
            }
        """.trimIndent()
        val expected = listOf("hello 2/3")
        assertEquals(expected, runInterpreted(text))
        assertEquals(expected, runCompiled(text, "RsfGlobals"))
    }

    @Test
    fun `C 后端把全局变量发成文件作用域的 static，并在 main 开头初始化`() {
        val text = """
            target native
            const val answer : Int = 42
            const var hits : Int = 0
            fn main() -> void {
                hits = hits + answer
                println(intToString(hits))
            }
        """.trimIndent()
        val c = CBackend(Rsf.analyze(SourceFile.of("main.rsf", text)), withEntry = true).emit()
        assertTrue(c.contains("static int32_t rsf_g_answer;"), c.take(600))
        assertTrue(c.contains("static int32_t rsf_g_hits;"), c.take(600))
        assertTrue(c.contains("void rsf_globals_init(void) {"), c.take(600))
        assertTrue(c.contains("rsf_g_answer = 42;"), c.take(1600))
        // 初始化必须发生在进用户 main 之前
        assertTrue(c.indexOf("rsf_globals_init();") < c.indexOf("rsf_fn_main();"), c.take(1600))
    }
}
