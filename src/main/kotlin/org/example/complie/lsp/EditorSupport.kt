package org.example.complie.lsp

import org.example.complie.Rsf
import org.example.complie.ast.ClassImport
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.Program
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.Block
import org.example.complie.ast.ForStmt
import org.example.complie.ast.IfStmt
import org.example.complie.ast.Stmt
import org.example.complie.ast.WhileStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interop.ClassPath
import org.example.complie.interop.MemberResolver
import org.example.complie.lexing.Lexer
import org.example.complie.lexing.Token
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.Builtins
import org.example.complie.sema.SemanticModel
import java.io.File

/**
 * 给编辑器用的查询接口（VS Code 扩展调它）。
 *
 * 输出是 JSON 文本，一行一个对象，直接打到 stdout。之所以不做成常驻的 LSP 服务，
 * 是想让扩展保持"零依赖 + 不需要常驻进程"：每次请求起一次 JVM，靠编辑器侧防抖
 * 把频率压下来。等以后要增量分析或跳转定义，再升级成常驻服务也不亏——
 * 这层接口的形状不用改。
 */
object EditorSupport {

    // ------------------------------------------------------------------ 诊断

    /**
     * 检查一个文件，返回诊断 JSON。
     *
     * 注意：前端的词法/语法/语义检查都是**遇错即停**，所以一次只会报出一条 error；
     * 修掉之后再跑就能看到下一条。warnings（比如不可达代码）是全部收集的。
     */
    fun diagnosticsJson(file: File, baseDir: File?): String {
        val source = SourceFile.of(file)
        val sb = StringBuilder()
        sb.append("{\"diagnostics\":[")

        var first = true
        fun emit(
            line: Int,
            col: Int,
            endCol: Int,
            severity: String,
            phase: String,
            message: String,
        ) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"line\":").append(line)
            sb.append(",\"col\":").append(col)
            sb.append(",\"endCol\":").append(endCol)
            sb.append(",\"severity\":\"").append(severity).append('"')
            sb.append(",\"phase\":\"").append(Json.escape(phase)).append('"')
            sb.append(",\"message\":\"").append(Json.escape(message)).append('"')
            sb.append('}')
        }

        try {
            val loaded = Rsf.load(file, baseDir)
            val model = Rsf.analyze(loaded)
            for (d in model.diagnostics) {
                val src = loaded.files.firstOrNull { it.name == d.file } ?: loaded.root
                emit(d.line, d.col, underlineEnd(src, d.line, d.col), d.severity.json, d.phase, d.message)
            }
        } catch (e: RsfError) {
            val src = e.source ?: source
            emit(
                maxOf(e.line, 1),
                maxOf(e.column, 1),
                underlineEnd(src, e.line, e.column),
                "error",
                e.phase,
                e.detail,
            )
        }
        sb.append("]}")
        return sb.toString()
    }

    /** 插入符该有多长：把出错位置那个「词」整体划出来，比只划一个字符好看得多。 */
    private fun underlineEnd(source: SourceFile, line: Int, col: Int): Int {
        val text = source.lineAt(line) ?: return col + 1
        if (col < 1 || col > text.length) return col + 1
        var n = 0
        while (col - 1 + n < text.length) {
            val c = text[col - 1 + n]
            if (!(c.isLetterOrDigit() || c == '_')) break
            n++
        }
        return col + maxOf(n, 1)
    }

    // ------------------------------------------------------------------ 补全

    fun completionJson(file: File, text: String?, line: Int, col: Int, baseDir: File?): String {
        val source = if (text != null) SourceFile.of(file.path, text) else SourceFile.of(file)

        // 被 import 进来的函数也要能补出来。带上「当前文件」与「整个程序」两份函数表：
        // 前者用来判断光标在哪个函数里（要按当前文件的坐标算），后者用来列候选。
        var ownFunctions: List<FuncDecl> = emptyList()
        var allFunctions: List<FuncDecl> = emptyList()
        runCatching {
            val loaded = Rsf.load(file, baseDir)
            allFunctions = loaded.functions
        }
        runCatching {
            ownFunctions = org.example.complie.parsing.Parser(
                Lexer(source).tokenize(), source,
            ).parseProgram().functions
        }

        val items = Completer(source, line, col, ownFunctions, allFunctions).items()
        val sb = StringBuilder("{\"items\":[")
        for ((i, item) in items.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"label\":\"").append(Json.escape(item.label)).append('"')
            sb.append(",\"kind\":\"").append(item.kind).append('"')
            sb.append(",\"detail\":\"").append(Json.escape(item.detail)).append('"')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }
}

/** 一条补全建议。kind 会映射成 VS Code 的 CompletionItemKind。 */
data class CompletionItem(val label: String, val kind: String, val detail: String)

/**
 * 在「第 line 行第 col 列」这个位置算出可以补什么。
 *
 * 两条路：
 * 1. **能解析**（文件当前是合法的）→ 用 AST 给出带签名的建议：函数（含签名）、
 *    当前函数的参数、光标之前声明过的变量（含类型）、成员访问。
 * 2. **解析不了**（正在打字，很常见）→ 退化成 token 扫描：把 `fun 名字`、
 *    `var/imutvar 名字` 扫出来。粗糙但胜在「任何时候都有东西可补」。
 */
class Completer(
    private val source: SourceFile,
    private val line: Int,
    private val col: Int,
    /** 当前文件自己的函数（用来判断光标在哪个函数里）。 */
    private val ownFunctions: List<FuncDecl> = emptyList(),
    /** 整个程序（含被 import 的文件）的函数——候选里要带上它们。 */
    private val allFunctions: List<FuncDecl> = emptyList(),
) {

    private fun isBefore(l: Int, c: Int): Boolean = l < line || (l == line && c < col)

    private val beforeAt = { at: org.example.complie.lexing.SourceLine -> isBefore(at.line, at.column) }

    fun items(): List<CompletionItem> {
        // 成员访问优先：`Math.` 这种要先看接收者
        memberItems()?.let { return it }

        val out = ArrayList<CompletionItem>()
        for (k in KEYWORDS) {
            // `let` 已经是废弃的关键字（写了会报「已经换成 var 与 imutvar」），
            // 但词法器仍把它当保留字，所以候选里留着它、只是把话说清楚
            out.add(
                CompletionItem(
                    k,
                    "keyword",
                    if (k == "let") "保留字（已废弃：改用 var / imutvar）" else "关键字",
                ),
            )
        }
        for (t in TYPES) out.add(CompletionItem(t, "type", "类型"))
        for (b in Builtins.ALL) out.add(CompletionItem(b.name, "function", "内置：${b.display()}"))
        out.add(CompletionItem("print", "function", "内置：print(值)"))
        out.add(CompletionItem("println", "function", "内置：println(值)"))

        val program = runCatching {
            org.example.complie.parsing.Parser(Lexer(source).tokenize(), source).parseProgram()
        }.getOrNull()

        if (program != null) {
            richItems(program, out)
        } else {
            fallbackItems(out)
        }
        return out.distinctBy { it.label + it.kind }
    }
    // ------------------------------------------------------------------ 成员

    /** 光标前是 `xxx.` 或 `xxx.部分名字` 时，列出 xxx 的成员；不是则返回 null。 */
    private fun memberItems(): List<CompletionItem>? {
        val lineText = source.lineAt(line) ?: return null
        val upto = lineText.take((col - 1).coerceIn(0, lineText.length))
        val m = MEMBER_TAIL.find(upto) ?: return null
        val base = m.groupValues[1]

        val resolver = MemberResolver(importedClasses())
        val cls = resolver.resolveClass(base) ?: return null

        val out = ArrayList<CompletionItem>()
        for (c in ClassPath.hierarchy(cls.internalName)) {
            for (f in c.fields) {
                if (!out.any { it.label == f.name }) {
                    out.add(CompletionItem(f.name, "field", "${f.type.rsfName}（${if (f.isStatic) "静态" else "实例"}字段）"))
                }
            }
            for (mm in c.methods) {
                if (out.any { it.label == mm.name && it.kind == "method" }) continue
                out.add(
                    CompletionItem(
                        mm.name,
                        "method",
                        mm.display(cls.simpleName) + if (mm.isStatic) "（静态）" else "",
                    ),
                )
            }
        }
        return out
    }

    /** 显式 import 的类（简单名 → 内部名）。java.lang 由 MemberResolver 兜底。 */
    private fun importedClasses(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val program = runCatching {
            org.example.complie.parsing.Parser(Lexer(source).tokenize(), source).parseProgram()
        }.getOrNull() ?: return out
        for (imp in program.imports) {
            if (imp is ClassImport) {
                val full = imp.path.replace('.', '/')
                if (ClassPath.find(full) != null) {
                    out[imp.path.substringAfterLast('.')] = full
                } else {
                    val owner = imp.path.substringBeforeLast('.', "")
                    if (owner.isNotEmpty()) {
                        out[owner.substringAfterLast('.')] = owner.replace('.', '/')
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 走 AST

    private fun richItems(program: Program, out: MutableList<CompletionItem>) {
        val own = ownFunctions.ifEmpty { program.functions }
        // 候选：本文件的函数 + 被 import / module 带进来的函数。
        // 阶段 19d 起同名函数可以是重载，所以去重键用**完整签名**（原来只用
        // 「名字 + 参数个数」，同个数不同类型的重载会只剩一个条目）。
        // `own + allFunctions` 里同一个 FuncDecl 可能出现两次，签名相同会一并去掉。
        for (fn in (own + allFunctions).distinctBy { signature(it) }) {
            out.add(CompletionItem(fn.name, "function", signature(fn)))
        }

        val enclosing = own.lastOrNull { it.at.line <= line } ?: return
        for (p in enclosing.params) {
            out.add(CompletionItem(p.name, "variable", "${p.type.name}（参数）"))
        }
        // 顶层函数一定有体（只有接口里的抽象方法才是 null），这里的 `?: return` 只是
        // 让类型对得上：真取到 null 就没有「光标之前的局部变量」可言。
        for (v in localsBefore(enclosing.body ?: return)) {
            out.add(CompletionItem(v.name, "variable", (v.declaredType?.name ?: "推断") + "（局部变量）"))
        }
    }

    /** 光标之前声明过的局部变量。不做块作用域过滤——宁可多给几个，也别一个都不给。 */
    private fun localsBefore(block: Block): List<VarDeclStmt> {
        val out = ArrayList<VarDeclStmt>()
        fun walk(stmt: Stmt) {
            if (!beforeAt(stmt.at)) return
            when (stmt) {
                is VarDeclStmt -> out.add(stmt)
                is Block -> stmt.statements.forEach { walk(it) }
                is IfStmt -> {
                    stmt.body.statements.forEach { walk(it) }
                    stmt.elseBody?.statements?.forEach { walk(it) }
                }
                is WhileStmt -> stmt.body.statements.forEach { walk(it) }
                is ForStmt -> stmt.body.statements.forEach { walk(it) }
                else -> {}
            }
        }
        block.statements.forEach { walk(it) }
        return out
    }

    // ------------------------------------------------------------------ token 扫描兜底

    private fun fallbackItems(out: MutableList<CompletionItem>) {
        val tokens = runCatching { Lexer(source).tokenize() }.getOrNull() ?: return
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!isBefore(t.line, t.column)) break
            when {
                t.kind == TokenKind.KW_FUN -> {
                    // fun [类型] 名字
                    var j = i + 1
                    if (j < tokens.size && tokens[j].kind.isTypeKeyword) j++
                    if (j < tokens.size && tokens[j].kind == TokenKind.IDENT) {
                        out.add(CompletionItem(tokens[j].lexeme, "function", "函数（未完成解析）"))
                    }
                }
                t.kind.isVarKeyword -> {
                    if (i + 1 < tokens.size && tokens[i + 1].kind == TokenKind.IDENT) {
                        out.add(CompletionItem(tokens[i + 1].lexeme, "variable", "变量（未完成解析）"))
                    }
                }
            }
            i++
        }
    }

    /** 补全条目里显示的签名，参数渲染成 Kotlin 后缀风格：`add(a : int, b : int) -> int`。 */
    private fun signature(fn: FuncDecl): String {
        val ps = fn.params.joinToString(", ") { "${it.name} : ${it.type.name}" }
        val mark = if (fn.ret.mayError) "!" else ""
        return "${fn.name}($ps) ${fn.ret.style.symbol} $mark${fn.ret.type.name}"
    }

    private companion object {
        val KEYWORDS = listOf(
            "fun", "rtn", "return", "var", "imutvar", "if", "else", "while", "for",
            "in", "step", "break", "continue", "goto", "true", "false", "null",
            // pkg（阶段 6a）一直漏在补全表外：三处关键字表要同步——
            // 这里、idea 插件的 RsfKeywords、vscode 的 tmLanguage
            "import", "module", "pkg", "public", "protected", "private", "static",
            "class", "new", "this",
            // 阶段 14 的错误处理关键字也漏过一阵
            "fail", "try", "catch",
            // 阶段 18：次构造
            "constructor",
            // 阶段 19c：枚举声明 + 枚举分派（`case` 是关键字，`else` 已经在上面）
            "enum", "switch", "case",
            // 阶段 20：接口声明（`@interface` 是阶段 21 的事，各表先都不列）
            "interface",
            // 阶段 20 追加：可选的 override（类/接口方法都能写），三处关键字表照旧同步
            "override",
            // `let` 在词法器里仍然是保留字（写了会得到「let 已经换成 var 与 imutvar」
            // 的提示），所以补全表也留着它，但 detail 会标成已废弃
            "let",
        )
        // 阶段 16：byte / char 也要在候选里（类型关键字表三处同步：这里、
        // idea 插件的 RsfKeywords.TYPES、vscode 的 tmLanguage）。
        // `str` / `boolean` 是 `string` / `bool` 的别名（Lexer.KEYWORDS 里也认），
        // 一并列出来，免得三张表对不上。
        val TYPES = listOf(
            "void", "int", "long", "float", "double", "string", "str",
            "bool", "boolean", "byte", "char",
        )

        /** 光标前是不是 `名字.` 或 `名字.部分`。 */
        val MEMBER_TAIL = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)?$")
    }
}

/** 只写不读的极简 JSON 转义。 */
object Json {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
