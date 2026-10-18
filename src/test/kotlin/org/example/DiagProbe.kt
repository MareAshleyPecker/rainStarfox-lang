package org.example

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile

/**
 * 阶段 32b 的诊断渲染对拍探针（Kotlin 侧 = oracle）。
 *
 * 每条用例打 `### case N` 再把 `RsfError.render()` 的原文打出来；
 * 自举侧 `rsfSrc/bootstrap/DiagProbe.rsf` 必须逐字节相同。
 */
private class Case(
    val phase: String,
    val detail: String,
    val file: String,
    val line: Int,
    val column: Int,
    val text: String,
)

private val LF = "fn main() {\n    var x : int = \n}\n"
private val CRLF = "a\r\nb\r\nc"
private val NO_TAIL = "one line no trailing newline"
private val TAB = "tab\there\nx"

private val CASES = listOf(
    Case("checker", "不能把 string 赋给 int 变量 'x'", "demo.rsf", 2, 9, LF),
    Case("parser", "这里需要一个表达式", "demo.rsf", 3, 1, LF),
    Case("checker", "没有位置", "demo.rsf", 0, 0, LF),
    Case("checker", "只有行号", "demo.rsf", 2, 0, LF),
    Case("checker", "行号越界", "demo.rsf", 9, 1, LF),
    Case("checker", "CRLF 源", "crlf.rsf", 2, 1, CRLF),
    Case("checker", "列在行尾后一格", "crlf.rsf", 1, 2, CRLF),
    Case("checker", "列超出行尾", "crlf.rsf", 1, 9, CRLF),
    Case("checker", "制表符按一个宽度", "tab.rsf", 1, 5, TAB),
    Case("checker", "无尾换行的最后一行", "last.rsf", 1, 20, NO_TAIL),
)

fun main() {
    for ((i, c) in CASES.withIndex()) {
        val err = RsfError(c.phase, c.detail, c.line, c.column, SourceFile(c.file, c.text))
        println("### case ${i + 1}")
        println(err.render())
    }
}
