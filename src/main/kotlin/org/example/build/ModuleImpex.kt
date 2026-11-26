package org.example.build

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import java.io.File

/** `module-impex.rsf` 里的一条。 */
enum class ImpexKind(val keyword: String) {
    IMPORT("import"),
    EXPORT("export"),
    EXPORT_JVM("exportjvm"),
    EXPORT_C("extern"),
    ;

    companion object {
        fun of(word: String): ImpexKind? = entries.firstOrNull { it.keyword == word }
        val KEYWORDS: String = entries.joinToString(" / ") { it.keyword }
    }
}

/** 一条清单项：[path] 相对项目根（`@/` 前缀可写可不写）。 */
data class ImpexEntry(val kind: ImpexKind, val path: String, val line: Int)

/**
 * `module-impex.rsf`：一个模块的**对外面貌**（阶段 84）。
 *
 * 它是清单，不是 rsf 源码——`export` 在语言里已经是函数修饰词，`export "a.rsf"`
 * 走普通语法会撞车，所以这里用专用读取器，一行一条：
 *
 * ```rsf
 * import    "a.rsf"           // 模块内部用到（等价于在别处写 import，v1 只校验存在）
 * export    "b.rsf"           // 导出给普通 rsf 用
 * exportjvm "Tool.rsf"        // 产出给 JVM 系语言（Java/Kotlin）调用的库 jar
 * extern    "lib.rsf"         // 产出 C ABI 的目标文件（.o），符号带 rsf_ 前缀
 * ```
 *
 * 三条出口对应三种消费者，命名也按「给谁用」来，而不是按动词：
 * `export`（rsf）/ `exportjvm`（JVM）/ `extern`（C）。
 *
 * **为什么单独一个文件**：导出面集中在一处，LSP 列公开 API、自举做符号表、
 * 构建决定产哪几个 jar/obj 都只读这一个文件；散在各文件里的 `export` 修饰词
 * 只能表达「这个函数是公开的」，表达不了「这个模块对外产出什么」。
 */
object ModuleImpex {

    const val FILE_NAME: String = "module-impex.rsf"

    /** 读清单；文件不存在返回空表（没有清单就当没有额外出口）。 */
    fun parse(file: File): List<ImpexEntry> {
        if (!file.isFile) return emptyList()
        val source = SourceFile.of(file)
        val out = ArrayList<ImpexEntry>()
        var line = 0
        for (raw in source.text.lines()) {
            line++
            val text = stripComment(raw).trim()
            if (text.isEmpty()) continue
            val space = text.indexOfFirst { it == ' ' || it == '\t' }
            if (space < 0) {
                throw err(file, source, line, "'$text' 少了参数：一行一条，写成 ${ImpexKind.KEYWORDS} \"路径\"")
            }
            val word = text.substring(0, space)
            val kind = ImpexKind.of(word)
                ?: throw err(
                    file, source, line,
                    "不认识的清单项 '$word'。可用的：${ImpexKind.KEYWORDS}（一行一条，写成 '$word \"路径\"'）",
                )
            val arg = text.substring(space).trim()
            val path = unquote(arg)
                ?: throw err(file, source, line, "$word 后面要写带引号的路径，实际读到 '$arg'")
            if (path.isEmpty()) throw err(file, source, line, "$word 后面的路径不能是空的")
            out.add(ImpexEntry(kind, path, line))
        }
        return out
    }

    /** `"a.rsf"` -> `a.rsf`；不是带引号的字符串就返回 null。 */
    private fun unquote(text: String): String? {
        if (text.length < 2 || text.first() != '"' || text.last() != '"') return null
        return text.substring(1, text.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** 去掉行尾 `//` 注释（引号里的 `//` 不算）。 */
    private fun stripComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                if (c == '"') inString = true
                if (c == '/' && i + 1 < line.length && line[i + 1] == '/') return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun err(file: File, source: SourceFile, line: Int, message: String): RsfError =
        RsfError("build", "$FILE_NAME 第 $line 行：$message", line, 1, source)
}
