package org.rainstarfox.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JSON 解析的测试。
 *
 * 这里的样本不是编出来的，是从 `rsf check --json` / `rsf complete` 的真实输出里
 * 抄下来的——插件和编译器之间就靠这层协议说话，形状变了必须有人喊。
 */
class RsfJsonTest {

    @Test
    fun `解析诊断输出`() {
        val raw = """
            {"diagnostics":[{"line":2,"col":13,"endCol":18,"severity":"error","phase":"checker","message":"未定义的函数 'greet'"}]}
        """.trimIndent()

        val root = RsfJson.parse(raw)
        val diagnostics = RsfJson.objectsAt(root, "diagnostics")
        assertEquals(1, diagnostics.size)

        val d = diagnostics[0]
        assertEquals(2, RsfJson.int(d, "line"))
        assertEquals(13, RsfJson.int(d, "col"))
        assertEquals(18, RsfJson.int(d, "endCol"))
        assertEquals("error", RsfJson.string(d, "severity"))
        assertEquals("checker", RsfJson.string(d, "phase"))
        assertEquals("未定义的函数 'greet'", RsfJson.string(d, "message"))
    }

    @Test
    fun `解析补全输出`() {
        val raw = """
            {"items":[
              {"label":"greet","kind":"function","detail":"greet(string who) -> string"},
              {"label":"add","kind":"function","detail":"add(int a, int b) -> int"}
            ]}
        """.trimIndent()

        val items = RsfJson.objectsAt(RsfJson.parse(raw), "items")
        assertEquals(2, items.size)
        assertEquals("greet", RsfJson.string(items[0], "label"))
        assertEquals("add(int a, int b) -> int", RsfJson.string(items[1], "detail"))
    }

    @Test
    fun `空结果与缺失字段都当空处理 不抛异常`() {
        assertEquals(0, RsfJson.objectsAt(RsfJson.parse("""{"diagnostics":[]}"""), "diagnostics").size)
        assertEquals(0, RsfJson.objectsAt(RsfJson.parse("""{"items":[]}"""), "items").size)
        assertEquals(0, RsfJson.objectsAt(RsfJson.parse("{}"), "diagnostics").size)
    }

    @Test
    fun `坏输入不会炸掉编辑器`() {
        // 压根不是 JSON：拿不到东西
        assertEquals(null, RsfJson.parse("这不是 json"))
        // 半截 JSON（编译器被杀、管道断了）： tolerate 成「能读多少读多少」，
        // 缺的字段一律给默认值，绝不能抛异常把编辑器带崩
        val partial = RsfJson.objectsAt(RsfJson.parse("{\"diagnostics\":[{\"line\":"), "diagnostics")
        assertTrue(partial.all { RsfJson.string(it, "message").isEmpty() })
        assertTrue(partial.all { RsfJson.int(it, "line", 1) == 1 })
        assertTrue(RsfJson.objectsAt(RsfJson.parse(""), "diagnostics").isEmpty())
        // 缺字段时给默认值，而不是让调用方拿到 null 后崩在别处
        assertEquals(1, RsfJson.int(emptyMap(), "line", 1))
        assertEquals("", RsfJson.string(emptyMap(), "message"))
    }

    @Test
    fun `转义字符正确还原`() {
        val raw = """{"diagnostics":[{"line":1,"col":1,"endCol":2,"severity":"error","phase":"lexer","message":"字符串没有闭合：\"abc\n\" 这里"}]}"""
        val d = RsfJson.objectsAt(RsfJson.parse(raw), "diagnostics")[0]
        val message = RsfJson.string(d, "message")
        assertTrue(message.contains("\"abc"), message)
        assertTrue(message.contains("\n"), "换行转义没还原：$message")
        assertTrue(message.contains("这里"), message)
    }
}
