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
    /** `export pkg std`：把**一个包**（目录）导出给普通 rsf（Java/Kotlin 的 import 那样用）。 */
    PACKAGE_EXPORT("export pkg"),
    /** `import pkg classfile`：包只在仓库内部用（旧写法，保留）。 */
    PACKAGE_IMPORT("import pkg"),
    /** `module "<目录>" to <别名> [{ 成员… }]`：按别名成包（推荐写法）。 */
    MODULE_DECL("module"),
    ;

    companion object {
        fun of(word: String): ImpexKind? = entries.firstOrNull { it.keyword == word }

        /** 先按两段关键字（`export pkg` / `import pkg`）匹配，再按单段匹配。 */
        fun ofLine(text: String): ImpexKind? =
            entries.filter { it.keyword.contains(' ') }.firstOrNull { text.startsWith(it.keyword + " ") }
                ?: entries.filter { !it.keyword.contains(' ') }
                    .firstOrNull { text.startsWith(it.keyword + " ") }

        val KEYWORDS: String = entries.joinToString(" / ") { it.keyword }
    }
}

/**
 * 一条清单项：[path] 对文件形态是相对项目根的路径，对 `module "…" to alias` 是目录。
 *
 * [alias] / [members] / [internal] 只有模块形态才有：
 * - `module "stdlib/std" to std` —— 整包对外，源码里 `import std.Vec`；
 * - `module "stdlib/std" to std { Vec Text }` —— 只开放这两个成员；
 * - `internal module "stdlib/classfile" to classfile` —— 只在仓库内部可用。
 */
data class ImpexEntry(
    val kind: ImpexKind,
    val path: String,
    val line: Int,
    val alias: String? = null,
    /** null = 整包；非空 = 白名单。 */
    val members: Set<String>? = null,
    val internal: Boolean = false,
)

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
            var text = stripComment(raw).trim()
            if (text.isEmpty()) continue
            // `internal module "…" to alias`：前缀决定可见性
            var isInternal = false
            if (text.startsWith("internal ")) {
                isInternal = true
                text = text.removePrefix("internal ").trim()
            }
            if (text.startsWith("module ")) {
                out.add(parseModuleDecl(file, source, text, line, isInternal))
                continue
            }
            val kind = ImpexKind.ofLine(text)
                ?: throw err(
                    file, source, line,
                    "不认识的清单项 '$text'。可用的：${ImpexKind.KEYWORDS}" +
                        "（文件形态写带引号的路径，包形态写包名，例：export pkg std）",
                )
            val arg = text.substring(kind.keyword.length).trim()
            // 包形态：参数是不带引号的包名
            if (kind == ImpexKind.PACKAGE_EXPORT || kind == ImpexKind.PACKAGE_IMPORT) {
                if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(arg)) {
                    throw err(file, source, line, "包名要写标识符（例：${kind.keyword} std），实际读到 '$arg'")
                }
                out.add(ImpexEntry(kind, arg, line))
                continue
            }
            val path = unquote(arg)
                ?: throw err(file, source, line, "'${kind.keyword}' 后面要写带引号的路径，实际读到 '$arg'")
            if (path.isEmpty()) throw err(file, source, line, "'${kind.keyword}' 后面的路径不能是空的")
            out.add(ImpexEntry(kind, path, line))
        }
        return out
    }

    /**
     * `module "stdlib/std" to std { Vec Text }`
     *
     * 语法正好是「目录 → 别名」再加上可选的白名单：`to` 读起来就是"把左边这个目录
     * 叫作右边这个名字"，和源码里的 `std.Vec` 对得上。
     */
    private fun parseModuleDecl(
        file: File,
        source: SourceFile,
        text: String,
        line: Int,
        isInternal: Boolean,
    ): ImpexEntry {
        val rest = text.removePrefix("module").trim()
        if (!rest.startsWith("\"")) {
            throw err(file, source, line, "module 后面要写带引号的目录，例：module \"stdlib/std\" to std")
        }
        val end = rest.indexOf('"', 1)
        if (end < 0) throw err(file, source, line, "module 的目录少了收尾引号")
        val dir = rest.substring(1, end)
        var tail = rest.substring(end + 1).trim()
        if (!tail.startsWith("to ")) {
            throw err(file, source, line, "module \"$dir\" 后面要写 'to 别名'，例：module \"$dir\" to std")
        }
        tail = tail.removePrefix("to ").trim()
        val brace = tail.indexOf('{')
        val alias = (if (brace >= 0) tail.substring(0, brace) else tail).trim()
        if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(alias)) {
            throw err(file, source, line, "别名要写标识符（例：to std），实际读到 '$alias'")
        }
        var members: Set<String>? = null
        if (brace >= 0) {
            val close = tail.lastIndexOf('}')
            if (close < brace) throw err(file, source, line, "module 的成员列表缺少收尾的 '}'")
            members = tail.substring(brace + 1, close)
                .split(',', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (members.isEmpty()) {
                throw err(file, source, line, "module 的成员列表是空的：整包开放就别写 '{}'")
            }
        }
        return ImpexEntry(ImpexKind.MODULE_DECL, dir, line, alias, members, isInternal)
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
