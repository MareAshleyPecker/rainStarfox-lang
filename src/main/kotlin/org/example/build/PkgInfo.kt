package org.example.build

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import java.io.File

/**
 * `pkg-info.rsf`：**一个包目录的元信息**（阶段 84）。
 *
 * 类比 Java 的 `package-info.java`：包里只写一次包名与元数据，目录下的源码不必
 * 每份都写 `pkg a.b`。它是**元信息文件**，不参与编译（没有函数/类，也不进 AST）。
 *
 * ```rsf
 * // src/demo/pkg-info.rsf
 * pkg demo
 * version "1.2.0"
 * author "rsf"
 * ```
 *
 * 规矩：
 * - 一行一条：`pkg a.b` 是包名；其余 `键 "值"` 进元数据表（暂时只记录，供打包/文档用）；
 * - **一个目录最多一个**；同一目录下源码自己再写 `pkg` 时，必须和这里一致，
 *   不一致就报错（两份声明说不同的包，没法猜哪份算数）；
 * - 它自己不会被当成源码编译，也不会被当作模块入口。
 */
data class PkgInfo(
    /** 包名（点分）；没写 `pkg` 就是 null。 */
    val packageName: String?,
    /** 其它键值：`version` / `author` / 任意。 */
    val metadata: Map<String, String>,
) {
    companion object {

        const val FILE_NAME: String = "pkg-info.rsf"

        private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")

        /** 读一个目录的包信息；没有这个文件返回 null。 */
        fun readIn(dir: File): PkgInfo? {
            val file = File(dir, FILE_NAME)
            if (!file.isFile) return null
            return parse(file)
        }

        fun parse(file: File): PkgInfo {
            val source = SourceFile.of(file)
            var pkg: String? = null
            val meta = LinkedHashMap<String, String>()
            var line = 0
            for (raw in source.text.lines()) {
                line++
                val text = stripComment(raw).trim()
                if (text.isEmpty()) continue
                val space = text.indexOfFirst { it == ' ' || it == '\t' }
                if (space < 0) {
                    throw err(source, line, "'$text' 少了参数：写成 'pkg a.b' 或 '键 \"值\"'")
                }
                val key = text.substring(0, space)
                val arg = text.substring(space).trim()
                if (key == "pkg") {
                    if (pkg != null) throw err(source, line, "'pkg' 写了两遍")
                    if (!IDENT.matches(arg)) {
                        throw err(source, line, "包名要写点分标识符（例：pkg a.b），实际读到 '$arg'")
                    }
                    pkg = arg
                } else {
                    val value = unquote(arg)
                        ?: throw err(source, line, "'$key' 的值要写带引号的字符串，实际读到 '$arg'")
                    meta[key] = value
                }
            }
            return PkgInfo(pkg, meta)
        }

        private fun unquote(text: String): String? {
            if (text.length < 2 || text.first() != '"' || text.last() != '"') return null
            return text.substring(1, text.length - 1)
        }

        private fun stripComment(line: String): String {
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else {
                    if (c == '"') inString = true
                    if (c == '/' && i + 1 < line.length && line[i + 1] == '/') return line.substring(0, i)
                }
                i++
            }
            return line
        }

        private fun err(source: SourceFile, line: Int, message: String): RsfError =
            RsfError("loader", "$FILE_NAME 第 $line 行：$message", line, 1, source)
    }
}
