package org.example.complie.directives

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.diag.WarningDirectives

/** `#dep` 里一条依赖的种类。 */
enum class DepKind(val keyword: String) {
    JAVA("javaInclude"),
    C("cInclude"),
    H("hInclude"),
    RULE("rule"),

    /**
     * 整个目录：`dirInclude("res")`（阶段 83）。
     *
     * 和别的种类不一样，它指的是**目录里的全部文件**（复制、打包资源时用），
     * 所以存在性校验看的是 `isDirectory`，锁文件里也只记路径不记哈希
     * （目录的哈希要遍历子树，v1 不值得）。
     */
    DIR("dirInclude"),
}

/**
 * `#dep` 块里的一条：`java("libs/foo.jar")` / `cInclude("native/util.c")` / `hInclude("native/util.h")` / `rule("依赖函数名")`。
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

/**
 * `#def` 声明的一条构建规则：`#def extraDeps { … }`。
 *
 * 块里是**普通 rsf 语句**，解糖后就是 `fn extraDeps() -> void { … }`。
 * 规则与普通函数共用命名空间——两条同名声明会被 Checker 当成重复声明挡掉。
 */
data class DirectiveRule(val name: String, val line: Int)

/** 一份文件解析出来的指令。 */
class ParsedDirectives(
    val deps: List<DepEntry>,
    val build: BuildSettings?,
    /** `#def` 声明的构建规则（跨文件合并成一张表，重名由 Checker 报重复声明）。 */
    val rules: List<DirectiveRule>,
    val stripped: String,
)

/**
 * 文件级指令 `#dep { … }`、`#build { … }` 与 `#target { … }`。
 *
 * 和 `#NoWarning!!` 一样是**预处理**：在词法之前把整块挖成空格（长度不变、换行保留），
 * 所以行列号与原文完全对齐，AST 里看不到它们。
 *
 * ```rsf
 * #dep {
 *     javaInclude("libs/gson.jar")
 *     cInclude("native/util.c")
 *     hInclude("native/util.h")
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
    val DEF_KEYWORD: String = "#def"

    private val ENTRY: Regex = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*(.*?)\s*\)\s*$""")
    private val STRING_ARG: Regex = Regex("""^"((?:[^"\\]|\\.)*)"$""")

    private class Block(
        val keyword: String,
        val startLine: Int,
        val endLine: Int,
        val start: Int,
        val end: Int,
        /** 开块那个 `{` 的下标（`#def` 解糖时要从这里往后原样保留）。 */
        val brace: Int,
        val body: String,
        val bodyLine: Int,
        /** 只有 `#def` 有：规则名。 */
        val name: String? = null,
    )

    /**
     * 解析并校验。任何写错都当场报错（`RsfError`），不会静默失效。
     */
    /** 全部内置指令（阶段 84：`#` 是**封闭**的，用户不能自己发明）。 */
    val KNOWN: List<String> = listOf(DEP_KEYWORD, BUILD_KEYWORD, DEF_KEYWORD, NONE_WARNING)

    /** 曾经是 `#` 指令、现在改成块语句的东西：报错直接给出新写法。 */
    private val MOVED_TO_BLOCK: Map<String, String> = mapOf(
        "#target" to "target { exe(\"@/src/main.rsf\") }",
    )

    fun parse(source: SourceFile): ParsedDirectives {
        val text = source.text
        // 阶段 84：`#` 只留给内置指令。写了不认识的 `#xxx` 就当场报错并列出可用的——
        // 静默当成普通字符的话，报错会落在词法器里（「不认识的字符 '#'」），
        // 用户根本不知道这里本该是一条指令。
        rejectUnknownDirective(source, text)
        if (!text.contains(DEP_KEYWORD) && !text.contains(BUILD_KEYWORD) &&
            !text.contains(DEF_KEYWORD)
        ) {
            return ParsedDirectives(emptyList(), null, emptyList(), WarningDirectives.strip(text))
        }
        val blocks = scan(source, text)
        val deps = ArrayList<DepEntry>()
        val rules = ArrayList<DirectiveRule>()
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
                DEF_KEYWORD -> rules.add(DirectiveRule(block.name!!, block.startLine))
            }
        }
        return ParsedDirectives(deps, build, rules, stripBlocks(text, blocks))
    }

    const val BUILD_SCRIPT: String = "build.rsfs"

    /** `#NoWarning!!` 归 WarningDirectives 管，这里只当它「是已知指令」。 */
    private const val NONE_WARNING = "#NoWarning!!"

    /**
     * 行首（只允许空白在前）的 `#名字`：不是内置指令就报错。
     *
     * 只认**行首**：正文里出现的 `#`（注释、字符串）不受影响，
     * 和指令块「必须独立占行」的规矩一致。
     */
    private fun rejectUnknownDirective(source: SourceFile, text: String) {
        var i = 0
        var line = 1
        while (i < text.length) {
            val atLineStart = i == 0 || text[i - 1] == '\n'
            if (atLineStart) {
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                if (j < text.length && text[j] == '#') {
                    val start = j
                    j++ // 跳过 '#' 本身
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '!' || text[j] == '-')) j++
                    val word = text.substring(start, j)
                    val moved = MOVED_TO_BLOCK[word]
                    if (moved != null) {
                        throw err(
                            source,
                            "'$word' 已经改成块语句了：写成 $moved（块语句不带 '#'，和 project { … } 一样在顶层）",
                            line,
                        )
                    }
                    if (word !in KNOWN && word != NONE_WARNING) {
                        throw err(
                            source,
                            "未知的指令 '$word'：'#' 只留给内置指令（${KNOWN.joinToString(" / ")}）。" +
                                "自己定义的东西不要用 '#'——项目配置写配置块（project { … }），" +
                                "构建规则写 #def 然后 #dep 里引用",
                            line,
                        )
                    }
                }
            }
            if (text[i] == '\n') line++
            i++
        }
    }

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
                    text.startsWith(DEF_KEYWORD, j) -> DEF_KEYWORD
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
        // `#def` 比别的块多一个规则名：`#def extraDeps { … }`。
        var name: String? = null
        if (keyword == DEF_KEYWORD) {
            val nameStart = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            name = text.substring(nameStart, i)
            if (name.isEmpty() || !name.first().let { it.isLetter() || it == '_' }) {
                throw err(source, "'#def' 后面要跟规则名（标识符），例：#def extraDeps { … }", line)
            }
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        }
        if (i >= text.length || text[i] != '{') {
            val shape = if (keyword == DEF_KEYWORD) "$keyword 名字 { … }" else "$keyword { … }"
            throw err(source, "'$keyword' 后面要跟一个 '{'（例：$shape）", line)
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
                            brace = i,
                            body = text.substring(bodyStart, j),
                            bodyLine = line,
                            name = name,
                        )
                    }
                }
            }
            j++
        }
        throw err(source, "'$keyword' 块没有收尾的 '}'", line)
    }

    /**
     * 把块挖掉，交给词法器。
     *
     * 两种处理：
     * - `#dep` / `#build` / `#target`：整块挖成空格（长度不变、换行保留），
     *   所以行列号与原文完全对齐，AST 里看不到它们；
     * - `#def`：**解糖**成 `fn 名字() -> void { … }`。只改开头那一段、`{` 之后原样保留，
     *   于是规则体里每个字符的行列都不动——体里报的错仍然指回用户写的那一行。
     *   （换行数也不变：开头段和原文的 `#def 名字 ` 都只有一行。）
     */
    private fun stripBlocks(text: String, blocks: List<Block>): String {
        if (blocks.isEmpty()) return WarningDirectives.strip(text)
        val sb = StringBuilder(text.length + 64)
        var at = 0
        for (block in blocks.sortedBy { it.start }) {
            if (block.start < at) continue
            sb.append(text, at, block.start)
            if (block.keyword == DEF_KEYWORD) {
                sb.append("fn ").append(block.name).append("() -> void ")
                sb.append(text, block.brace, block.end)
            } else {
                for (i in block.start until block.end) {
                    sb.append(if (text[i] == '\n') '\n' else ' ')
                }
            }
            at = block.end
        }
        sb.append(text, at, text.length)
        return WarningDirectives.strip(sb.toString())
    }

    private fun err(source: SourceFile, message: String, line: Int): RsfError =
        RsfError("dep", message, line, 1, source)
}
