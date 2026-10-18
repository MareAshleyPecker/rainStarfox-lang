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
 * LSP 服务器：协议层（分帧/JSON）与三个核心请求（初始化、诊断、补全）。
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
}
