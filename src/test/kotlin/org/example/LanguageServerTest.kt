package org.example

import org.example.complie.lsp.JsonParser
import org.example.complie.lsp.JsonRpc
import org.example.complie.lsp.JsonValue
import org.example.complie.lsp.LanguageServer
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LSP 服务器：协议层（分帧/JSON）与核心请求（初始化、诊断、补全、跳转、悬停）。
 *
 * 这些用例不走子进程——[LanguageServer.dispatch] 是纯函数式的，输入 params、输出
 * 结果对象，直接调就能测；只有分帧那条要过一遍真的字节流。
 */
class LanguageServerTest {

    private fun server(out: ByteArrayOutputStream = ByteArrayOutputStream()) =
        LanguageServer(ByteArrayInputStream(ByteArray(0)), out, PrintStream(out, true, "UTF-8"))

    @Test
    fun `JSON 解析与渲染往返`() {
        val text = """{"a":1,"b":["x",true,null],"c":{"d":"e\nf"}}"""
        val v = JsonParser.parse(text)
        assertEquals(1, v.int("a"))
        assertEquals("x", v.arr("b")?.first()?.let { (it as JsonValue.Str).value })
        assertEquals("e\nf", v.str("c", "d"))
        // 渲染出来还得能被自己解析回去
        assertEquals(v, JsonParser.parse(v.render()))
    }

    @Test
    fun `LSP 帧按字节长度写 读回是同一条消息`() {
        val out = ByteArrayOutputStream()
        val payload = """{"method":"初始化","id":1}"""
        JsonRpc.writeMessage(out, payload)

        val header = out.toString("US-ASCII").lineSequence().first()
        assertTrue(header.startsWith("Content-Length: "), header)
        // 长度按 UTF-8 字节算：中文一个字 3 字节，按字符数算会短一截，读端就会卡死
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, header.removePrefix("Content-Length: ").toInt())

        assertEquals(payload, JsonRpc.readMessage(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `initialize 声明全量同步与补全能力`() {
        val result = server().dispatch("initialize", JsonValue.Null)!!
        assertEquals(1, result.int("capabilities", "textDocumentSync"))
        assertTrue(result.obj("capabilities")?.obj("completionProvider") != null)
        assertEquals("rainStarfox", result.str("serverInfo", "name"))
    }

    @Test
    fun `打开文档后拉取诊断 报出中文错误并映射成 0 基位置`() {
        val dir = File.createTempFile("rsf-lsp", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "bad.rsf")
        file.writeText("fn main() -> void {\n    rtn\n}\n", Charsets.UTF_8)
        val uri = LanguageServer.pathToUri(file.path)

        val s = server()
        s.dispatch(
            "textDocument/didOpen",
            JsonParser.parse(
                """{"textDocument":{"uri":"${uri.replace("\\", "\\\\")}","languageId":"rsf","version":1,"text":"fn main() -> void {\n    nope()\n    rtn\n}\n"}}""",
            ),
        )

        val report = s.dispatch("textDocument/diagnostic", JsonParser.parse("""{"textDocument":{"uri":"$uri"}}"""))!!
        assertEquals("full", report.str("kind"))
        val items = report.arr("items")!!
        assertTrue(items.isNotEmpty(), "应该有诊断")
        val first = items.first()
        assertEquals(1, first.int("severity")) // 1 = Error
        assertEquals(1, first.int("range", "start", "line"))
        assertTrue(first.str("message")!!.contains("未定义的函数"), first.str("message")!!)
        assertEquals("rainStarfox", first.str("source"))
    }

    @Test
    fun `补全返回带签名与 kind 编号的条目`() {
        val dir = File.createTempFile("rsf-lsp-c", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "c.rsf")
        file.writeText("fn helper() -> int {\n    rtn 1\n}\n", Charsets.UTF_8)
        val uri = LanguageServer.pathToUri(file.path)

        val s = server()
        s.dispatch(
            "textDocument/didOpen",
            JsonParser.parse(
                """{"textDocument":{"uri":"$uri","languageId":"rsf","version":1,"text":"fn helper() -> int {\n    rtn 1\n}\n\nfn main() -> void {\n    rtn\n}\n"}}""",
            ),
        )
        val result = s.dispatch(
            "textDocument/completion",
            JsonParser.parse("""{"textDocument":{"uri":"$uri"},"position":{"line":5,"character":4}}"""),
        )!!
        val items = result.arr("items")!!
        val helper = items.firstOrNull { it.str("label") == "helper" }
        assertTrue(helper != null, "应该补出 helper")
        assertEquals(3, helper.int("kind")) // 3 = Function
        assertTrue(helper.str("detail")!!.contains("helper("), helper.str("detail")!!)
    }

    @Test
    fun `路径与 URI 互转 兼容 Windows 盘符`() {
        assertEquals("D:/java/rainStarfox/a.rsf", LanguageServer.uriToPath("file:///D:/java/rainStarfox/a.rsf"))
        assertEquals("file:///D:/java/rainStarfox/a.rsf", LanguageServer.pathToUri("D:\\java\\rainStarfox\\a.rsf"))
        // 空格要编码，解码要能还原
        val withSpace = "D:/a b/c.rsf"
        assertEquals(withSpace, LanguageServer.uriToPath(LanguageServer.pathToUri(withSpace)))
    }

    // ------------------------------------------------------------------ 跳转与 RDoc

    /** 带 RDoc 的被引用文件。 */
    private val greetSource = """/// 打个招呼。
/// 第二个参数没用，留着占位。
pub fn greet(name : string) -> string {
    rtn "hi " + name
}
"""

    /** 根文件：import 上面那份，并在第 4 行调用它。 */
    private val mainSource = """import "lib/greet.rsf"

fn main() -> void {
    var msg : string = greet("x")
    rtn
}
"""

    private fun project(): Pair<File, String> {
        val dir = File.createTempFile("rsf-lsp-def", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "lib").mkdirs()
        File(dir, "lib/greet.rsf").writeText(greetSource, Charsets.UTF_8)
        val main = File(dir, "main.rsf")
        main.writeText(mainSource, Charsets.UTF_8)
        return main to LanguageServer.pathToUri(main.path)
    }

    private fun openText(server: LanguageServer, uri: String, text: String) {
        server.dispatch(
            "textDocument/didOpen",
            JsonParser.parse(
                """{"textDocument":{"uri":"$uri","languageId":"rsf","version":1,"text":${JsonValue.Str(text).render()}}}""",
            ),
        )
    }

    @Test
    fun `Ctrl 点跳转 跨文件跳到带 RDoc 的声明`() {
        val (_, uri) = project()
        val s = server()
        openText(s, uri, mainSource)
        val line = 3
        val character = mainSource.lines()[line].indexOf("greet")
        val result = s.dispatch(
            "textDocument/definition",
            JsonParser.parse("""{"textDocument":{"uri":"$uri"},"position":{"line":$line,"character":$character}}"""),
        )!!
        assertTrue(result.str("uri")!!.endsWith("lib/greet.rsf"), result.str("uri")!!)
        assertEquals(2, result.int("range", "start", "line")) // 声明在第 3 行（0 基 2）
        assertEquals(greetSource.lines()[2].indexOf("greet"), result.int("range", "start", "character"))
    }

    @Test
    fun `悬停显示 RDoc 与签名`() {
        val (_, uri) = project()
        val s = server()
        openText(s, uri, mainSource)
        val line = 3
        val character = mainSource.lines()[line].indexOf("greet")
        val result = s.dispatch(
            "textDocument/hover",
            JsonParser.parse("""{"textDocument":{"uri":"$uri"},"position":{"line":$line,"character":$character}}"""),
        )!!
        assertEquals("markdown", result.str("contents", "kind"))
        val value = result.str("contents", "value")!!
        assertTrue(value.contains("greet(name : string)"), value)
        assertTrue(value.contains("打个招呼"), value)
        assertTrue(value.contains("greet.rsf:"), value)
    }

    @Test
    fun `局部变量跳到声明 光标落在使用处不会串到同名函数`() {
        val dir = File.createTempFile("rsf-lsp-var", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "v.rsf")
        val text = """fn main() -> void {
    var msg : string = "x"
    var other : string = msg
    rtn
}
"""
        file.writeText(text, Charsets.UTF_8)
        val uri = LanguageServer.pathToUri(file.path)
        val s = server()
        openText(s, uri, text)
        val line = 2
        val character = text.lines()[line].lastIndexOf("msg")
        val result = s.dispatch(
            "textDocument/definition",
            JsonParser.parse("""{"textDocument":{"uri":"$uri"},"position":{"line":$line,"character":$character}}"""),
        )!!
        assertEquals(1, result.int("range", "start", "line"))
    }

    @Test
    fun `找不到声明就老实回 null`() {
        val dir = File.createTempFile("rsf-lsp-none", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "n.rsf")
        val text = """fn main() -> void {
    nope()
}
"""
        file.writeText(text, Charsets.UTF_8)
        val uri = LanguageServer.pathToUri(file.path)
        val s = server()
        openText(s, uri, text)
        val result = s.dispatch(
            "textDocument/definition",
            JsonParser.parse("""{"textDocument":{"uri":"$uri"},"position":{"line":1,"character":5}}"""),
        )
        assertEquals(JsonValue.Null, result)
    }

    @Test
    fun `包声明按真实文件位置校验 未保存的缓冲区不会被误判`() {
        // 编辑器里的正文只存在于内存：服务器以前会把它写到 Temp 下的临时文件再检查，
        // 于是 `pkg testbag` 被报成「文件不在 testbag/ 下」。这里钉住回归。
        val dir = File.createTempFile("rsf-lsp-pkg", "").let { it.delete(); it.mkdirs(); it }
        val box = File(dir, "testbag").apply { mkdirs() }
        val file = File(box, "testmain.rsf")
        val text = """pkg testbag

fn main() -> void {
    println("hi")
}
"""
        file.writeText(text, Charsets.UTF_8)
        val uri = LanguageServer.pathToUri(file.path)

        val s = server()
        openText(s, uri, text)
        val report = s.dispatch("textDocument/diagnostic", JsonParser.parse("""{"textDocument":{"uri":"$uri"}}"""))!!
        assertEquals(0, report.arr("items")!!.size, report.render())
    }

    @Test
    fun `未保存的改动立刻反映在诊断里 不必先保存`() {
        val dir = File.createTempFile("rsf-lsp-dirty", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "d.rsf")
        file.writeText(
            """fn main() -> void {
    rtn
}
""",
            Charsets.UTF_8,
        )
        val uri = LanguageServer.pathToUri(file.path)

        val s = server()
        openText(
            s,
            uri,
            """fn main() -> void {
    nope()
    rtn
}
""",
        )
        val report = s.dispatch("textDocument/diagnostic", JsonParser.parse("""{"textDocument":{"uri":"$uri"}}"""))!!
        val items = report.arr("items")!!
        assertTrue(items.isNotEmpty(), "内存里的正文应该被检查")
        assertTrue(items.first().str("message")!!.contains("nope"), items.first().render())
    }
}
