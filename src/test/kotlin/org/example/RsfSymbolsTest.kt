package org.example

import org.example.complie.Rsf
import org.example.complie.diag.SourceFile
import org.example.complie.lsp.RsfSymbols
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 符号表：谁算「声明」、光标落在哪儿算「用到了谁」。
 *
 * 服务器那层（`LanguageServerTest`）只管把位置换成 JSON；这里测的是就近规则与 RDoc
 * 提取——那才是「Ctrl+点跳错地方」「悬停没内容」的真正来源。
 */
class RsfSymbolsTest {

    private fun resolver(source: String): (Int, Int) -> RsfSymbols.RsfSymbol? {
        val dir = File.createTempFile("rsf-sym", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "m.rsf")
        file.writeText(source, Charsets.UTF_8)
        val program = Rsf.load(file, dir)
        val root: SourceFile = program.root
        return { line: Int, character: Int -> RsfSymbols.resolveAt(program, root, line, character) }
    }

    @Test
    fun `RDoc 只取紧贴声明的那一段 空行会打断`() {
        val dir = File.createTempFile("rsf-doc", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "d.rsf")
        val text = """/// 这是上一段的注释

/// 真正的文档：返回一。
pub fn one() -> int {
    rtn 1
}

fn main() -> void {
    rtn
}
"""
        file.writeText(text, Charsets.UTF_8)
        val program = Rsf.load(file, dir)
        val one = RsfSymbols.index(program).first { it.name == "one" }
        assertTrue(one.doc.contains("真正的文档"), one.doc)
        assertTrue(!one.doc.contains("上一段"), one.doc)
        assertEquals(4, one.line) // `pub fn one` 那一行
    }

    @Test
    fun `类里的方法调用能解析到类成员而不是同名全局函数`() {
        val at = resolver("""fn value() -> int {
    rtn 0
}

pub class Box {
    pub fn value() -> int {
        rtn 1
    }

    pub fn use() -> int {
        rtn value()
    }
}
""")
        val line = 10 // `        rtn value()`
        val symbol = at(line, "        rtn value()".indexOf("value"))
        assertNotNull(symbol)
        assertEquals("method", symbol.kind)
        assertEquals(6, symbol.declLine)
    }

    @Test
    fun `悬停文本包含签名 RDoc 与定义位置`() {
        val dir = File.createTempFile("rsf-hover", "").let { it.delete(); it.mkdirs(); it }
        val file = File(dir, "h.rsf")
        file.writeText("""/// 加法。
pub fn add(a : int, b : int) -> int {
    rtn a + b
}
""", Charsets.UTF_8)
        val program = Rsf.load(file, dir)
        val add = RsfSymbols.index(program).first { it.name == "add" }
        val markdown = RsfSymbols.hoverMarkdown(add, dir.path)
        assertTrue(markdown.contains("add(a : int, b : int) -> int"), markdown)
        assertTrue(markdown.contains("加法。"), markdown)
        assertTrue(markdown.contains("h.rsf:2"), markdown)
    }
}
