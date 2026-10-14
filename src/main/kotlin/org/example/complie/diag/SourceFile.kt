package org.example.complie.diag

import java.io.File

/**
 * 一份被编译的 .rsf 源码。保留原文是为了在报错时能打印出出错那一行和插入符。
 */
class SourceFile(val path: String, val text: String) {

    val lines: List<String> = text.split("\n")

    /** 取第 [line] 行（1 基）的原文，越界返回 null。 */
    fun lineAt(line: Int): String? =
        if (line in 1..lines.size) lines[line - 1].trimEnd('\r') else null

    /** 只取文件名部分，用于报错与生成类名。 */
    val name: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    /** 不含扩展名的文件名。 */
    val baseName: String
        get() = name.substringBeforeLast('.')

    companion object {
        fun of(file: File): SourceFile = SourceFile(file.path, file.readText())
        fun of(path: String, text: String): SourceFile = SourceFile(path, text)
    }
}
