package org.example.complie.directives

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.diag.WarningDirectives

/** `#dep` 里一条依赖的种类。 */
enum class DepKind(val keyword: String) {
    JAVA("java"),
    C("c"),
    H("h"),
    RULE("rule"),
}

/**
 * `#dep` 块里的一条：`java("libs/foo.jar")` / `c("native/util.c")` / `h("native/util.h")` / `rule("依赖函数名")`。
 *
 * [target] 对前三种是**相对项目根目录**的路径，对 [DepKind.RULE] 是顶层函数名。
 */
data class DepEntry(val kind: DepKind, val target: String) {
    val line: Int = 0

    fun display(): String = "${kind.keyword}(\"$target\")"
}

/**
 * `#build` 块里的设置。全部可省；CLI 上的 `-o` / `--c` / `--meta` 优先于这里。
 */
data class BuildSettings(
    val outDir: String? = null,
    val target: String? = null,
    val meta: Boolean? = null,
    val name: String? = null,
)

/** 一份文件解析出来的指令。 */
class ParsedDirectives(
    val deps: List<DepEntry>,
    val build: BuildSettings?,
    val stripped: String,
)

/**
 * 文件级指令 `#dep { … }` 与 `#build { … }`。
 *
 * 和 `#NoWarning!!` 一样是**预处理**：在词法之前把整块挖成空格（长度不变、换行保留），
 * 所以行列号与原文完全对齐，AST 里看不到它们。
 *
 * ```rsf
 * #dep {
 *     java("libs/gson.jar")
 *     c("native/util.c")
 *     h("native/util.h")
 * }
 * #build {
 *     out("build/native")
 *     target("c")
 *     meta(true)
 * }
 * ```
 *
 * 规矩：
 * - 块必须**独立占行**（`#dep` 前面只允许空白），收尾的 `}` 也是独立一行；
 * - 一条一行，写法是 `关键字("字面量")`；
 * - `#build` 只许写在项目根目录的 `build.rsfs` 里（它描述的是整份产物的设置）；
 * - 同一文件写两次同样的键报错，跨文件的值不合并（`#dep` 是相加，`#build` 只看 build.rsfs）。
 */
object Directives {

    val DEP_KEYWORD: String = "#dep"
    val BUILD_KEYWORD: String = "#build"

    private val ENTRY: Regex = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*(.*?)\s*\)\s*$""")
    private val STRING_ARG: Regex = Regex("""^"((?:[^"\\]|\\.)*)"$""")

    private class Block(
        val keyword: String,
        val startLine: Int,
        val endLine: Int,
        val start: Int,
        val end: Int,
        val body: String,
        val bodyLine: Int,
    )

    /**
     * 解析并校验。任何写错都当场报错（`RsfError`），不会静默失效。
     */
    fun parse(source: SourceFile): ParsedDirectives {
        val text = source.text
        if (!text.contains(DEP_KEYWORD) && !text.contains(BUILD_KEYWORD)) {
            return ParsedDirectives(emptyList(), null, WarningDirectives.strip(text))
        }
        val blocks = scan(source, text)
        val deps = ArrayList<DepEntry>()
        var build: BuildSettings? = null
        for (block in blocks) {
            when (block.keyword) {
                DEP_KEYWORD -> deps.addAll(parseDeps(source, block))
                BUILD_KEYWORD -> {
                    if (build != null) {
                        throw err(source, "'#build' 一份文件只能写一次", block.startLine)
                    }
                    if (source.name != BUILD_SCRIPT) {
                        throw err(
                            source,
                            "'#build' 只能写在项目根目录的 $BUILD_SCRIPT 里：它描述的是整份产物的设置" +
                                "（产物目录、目标、要不要带元数据），写进普通源文件会让人猜不到最终用哪套",
                            block.startLine,
                        )
                    }
                    build = parseBuild(source, block)
                }
            }
        }
        return ParsedDirectives(deps, build, stripBlocks(text, blocks))
    }

    const val BUILD_SCRIPT: String = "build.rsfs"

    private fun parseDeps(source: SourceFile, block: Block): List<DepEntry> {
        val out = ArrayList<DepEntry>()
        // body 的第一段就在 `#dep {` 那一行上，所以行号从块首行开始、处理完再 +1。
        var line = block.bodyLine
        for (raw in block.body.split("\n")) {
            val text = raw.trim()
            if (text.isEmpty()) {
                line++
                continue
            }
            val match = ENTRY.matchEntire(text)
                ?: throw err(
                    source,
                    "'#dep' 里一行写一条，形式是 java(\"路径\") / c(\"路径\") / h(\"路径\") / rule(\"函数名\")，实际读到 '$text'",
                    line,
                )
            val key = match.groupValues[1]
            val kind = DepKind.entries.firstOrNull { it.keyword == key }
                ?: throw err(
                    source,
                    "不认识的依赖种类 '$key'。可用的：${DepKind.entries.joinToString(" / ") { it.keyword }}",
                    line,
                )
            val arg = match.groupValues[2]
            val literal = STRING_ARG.matchEntire(arg)
                ?: throw err(source, "'$key(...)' 的实参要写字符串字面量（例：$key(\"路径\")），实际给了 '$arg'", line)
            val value = literal.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            if (value.isEmpty()) throw err(source, "'$key(\"\")' 里的路径不能是空的", line)
            out.add(DepEntry(kind, value).also { entry ->
                if (entry.kind == DepKind.RULE && !entry.target.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    throw err(source, "'rule(...)' 里要写顶层函数名（标识符），实际给了 '${entry.target}'", line)
                }
            })
            line++
        }
        return out
    }

    private fun parseBuild(source: SourceFile, block: Block): BuildSettings {
        var outDir: String? = null
        var target: String? = null
        var meta: Boolean? = null
        var name: String? = null
        val seen = HashSet<String>()
        var line = block.bodyLine
        for (raw in block.body.split("\n")) {
            val text = raw.trim()
            if (text.isEmpty()) {
                line++
                continue
            }
            val match = ENTRY.matchEntire(text)
                ?: throw err(
                    source,
                    "'#build' 里一行写一条，形式是 out(\"目录\") / target(\"jvm\"|\"c\") / meta(true|false) / name(\"产物名\")，实际读到 '$text'",
                    line,
                )
            val key = match.groupValues[1]
            if (!seen.add(key)) throw err(source, "'#build' 里的 '$key' 写了两次", line)
            val arg = match.groupValues[2]
            when (key) {
                "out" -> outDir = stringArg(source, key, arg, line)
                "name" -> name = stringArg(source, key, arg, line)
                "target" -> {
                    val value = stringArg(source, key, arg, line)
                    if (value != "jvm" && value != "c" && value != "native" && value != "auto") {
                        throw err(
                            source,
                            "'target(...)' 只能写 \"jvm\" / \"c\"（\"native\" 是 \"c\" 的别名）/ \"auto\"，实际给了 \"$value\"",
                            line,
                        )
                    }
                    target = when (value) {
                        "native" -> "c"
                        else -> value
                    }
                }
                "meta" -> meta = when (arg) {
                    "true", "on" -> true
                    "false", "off" -> false
                    else -> throw err(source, "'meta(...)' 只能写 true / false（或 on / off），实际给了 '$arg'", line)
                }
                else -> throw err(
                    source,
                    "不认识的构建设置 '$key'。可用的：out / target / meta / name",
                    line,
                )
            }
        }
        return BuildSettings(outDir, target, meta, name)
    }

    private fun stringArg(source: SourceFile, key: String, arg: String, line: Int): String {
        val literal = STRING_ARG.matchEntire(arg)
            ?: throw err(source, "'$key(...)' 的实参要写字符串字面量（例：$key(\"值\")），实际给了 '$arg'", line)
        return literal.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    /** 从头到尾扫出所有 `#dep` / `#build` 块（块内允许字符串里出现 `}`）。 */
    private fun scan(source: SourceFile, text: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        var line = 1
        while (i < text.length) {
            val atLineStart = i == 0 || text[i - 1] == '\n'
            if (atLineStart) {
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                val keyword = when {
                    text.startsWith(DEP_KEYWORD, j) -> DEP_KEYWORD
                    text.startsWith(BUILD_KEYWORD, j) -> BUILD_KEYWORD
                    else -> null
                }
                if (keyword != null) {
                    val block = readBlock(source, text, j, keyword, line)
                    out.add(block)
                    line += text.substring(i, block.end).count { it == '\n' }
                    i = block.end
                    continue
                }
            }
            if (text[i] == '\n') line++
            i++
        }
        return out
    }

    private fun readBlock(source: SourceFile, text: String, start: Int, keyword: String, line: Int): Block {
        var i = start + keyword.length
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text[i] != '{') {
            throw err(source, "'$keyword' 后面要跟一个 '{'（例：$keyword { … }）", line)
        }
        val bodyStart = i + 1
        var j = bodyStart
        var inString = false
        var bodyLine = line
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') bodyLine++
            if (inString) {
                if (c == '\\') j++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '}' -> {
                        val closeLine = bodyLine
                        // 收尾的 } 之后这一行只允许空白
                        var k = j + 1
                        while (k < text.length && text[k] != '\n') {
                            if (!text[k].isWhitespace()) {
                                throw err(source, "'$keyword' 块的收尾 '}' 后面不能再写东西", closeLine)
                            }
                            k++
                        }
                        return Block(
                            keyword = keyword,
                            startLine = line,
                            endLine = closeLine,
                            start = start,
                            end = if (k < text.length) k + 1 else text.length,
                            body = text.substring(bodyStart, j),
                            bodyLine = line,
                        )
                    }
                }
            }
            j++
        }
        throw err(source, "'$keyword' 块没有收尾的 '}'", line)
    }

    /** 把块整体挖成空格（长度不变、换行保留）。 */
    private fun stripBlocks(text: String, blocks: List<Block>): String {
        if (blocks.isEmpty()) return WarningDirectives.strip(text)
        val sb = StringBuilder(text)
        for (block in blocks) {
            for (i in block.start until block.end) {
                if (sb[i] != '\n') sb.setCharAt(i, ' ')
            }
        }
        return WarningDirectives.strip(sb.toString())
    }

    private fun err(source: SourceFile, message: String, line: Int): RsfError =
        RsfError("dep", message, line, 1, source)
}
