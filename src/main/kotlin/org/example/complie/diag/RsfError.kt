package org.example.complie.diag

/**
 * 编译期统一异常。四个阶段（lexer / parser / checker / codegen）都抛它，
 * CLI 顶层捕获后用 [render] 打出带源码片段的错误信息。
 */
class RsfError(
    val phase: String,
    val detail: String,
    val line: Int = -1,
    val column: Int = -1,
    val source: SourceFile? = null,
) : RuntimeException(detail) {

    fun render(): String {
        val sb = StringBuilder()
        val file = source?.name ?: "<source>"
        if (line > 0 && column > 0) {
            sb.append("$file:$line:$column: error[$phase]: $detail")
        } else if (line > 0) {
            sb.append("$file:$line: error[$phase]: $detail")
        } else {
            sb.append("$file: error[$phase]: $detail")
        }
        val text = source?.lineAt(line)
        if (text != null) {
            val gutter = line.toString()
            val pad = " ".repeat(gutter.length)
            sb.append('\n').append("  ").append(gutter).append(" | ").append(text)
            // 制表符按一个字符宽度对齐即可，不追求完美
            if (column in 1..(text.length + 1)) {
                sb.append('\n').append("  ").append(pad).append(" | ")
                    .append(" ".repeat(column - 1)).append('^')
            }
        }
        return sb.toString()
    }

    override val message: String get() = detail
}
