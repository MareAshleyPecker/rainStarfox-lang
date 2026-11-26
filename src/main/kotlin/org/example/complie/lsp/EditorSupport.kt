package org.example.complie.lsp

import org.example.build.ProjectJars
import org.example.complie.Rsf
import org.example.complie.ast.*
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
 * 给编辑器用的查询接口（编辑器插件调它，走 `rsf check --json` / `rsf complete`）。
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
        val sb = StringBuilder()
        sb.append("{\"diagnostics\":[")
        for ((i, d) in diagnostics(file, baseDir).withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"file\":\"").append(Json.escape(d.file)).append('"')
            sb.append(",\"line\":").append(d.line)
            sb.append(",\"col\":").append(d.col)
            sb.append(",\"endCol\":").append(d.endCol)
            sb.append(",\"severity\":\"").append(d.severity).append('"')
            sb.append(",\"phase\":\"").append(Json.escape(d.phase)).append('"')
            sb.append(",\"code\":\"").append(Json.escape(d.code)).append('"')
            sb.append(",\"message\":\"").append(Json.escape(d.message)).append('"')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 一次检查的**类型化**结果（编辑器守护进程直接用它，不用再过一遍 JSON）。
     *
     * 前端是「遇错即停」，所以 error 最多一条；warning 全部收集。
     * `file` 是绝对路径——import 展开后别的文件里的错也在这里，调用方按它分流。
     */
    fun diagnostics(file: File, baseDir: File?, text: String? = null, script: Boolean = false): List<Located> {
        val out = ArrayList<Located>()
        try {
            // 阶段 84：先按项目根判定「编辑器该看见哪些 jar」，再分析
            ProjectJars.installFor(file)
            val loaded = Rsf.load(file, baseDir, text, script = script)
            val model = Rsf.analyze(loaded)
            for (d in model.diagnostics) {
                val src = loaded.files.firstOrNull { it.name == d.file } ?: loaded.root
                out.add(Located(src.name, d.line, d.col, underlineEnd(src, d.line, d.col), d.severity.json, d.phase, d.message, d.code))
            }
        } catch (e: RsfError) {
            val src = e.source ?: SourceFile.of(file)
            out.add(
                Located(
                    src.name,
                    maxOf(e.line, 1),
                    maxOf(e.column, 1),
                    underlineEnd(src, e.line, e.column),
                    "error",
                    e.phase,
                    e.detail,
                ),
            )
        }
        return out
    }

    /**
     * 一条带位置的诊断。[severity] 与编译器 JSON 的取值一致：`error` / `warning` / `info`。
     *
     * [code] 是警告的类型短名（`unreachable` 之类，见 `WarningKind`），error 为空串；
     * 编辑器可以按它做「这类警告静音」的快速修复（生成 `#NoWarning!!(类型)`）。
     */
    data class Located(
        val file: String,
        val line: Int,
        val col: Int,
        val endCol: Int,
        val severity: String,
        val phase: String,
        val message: String,
        val code: String = "",
    )

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
        val items = completions(file, text, line, col, baseDir)
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

    /** 补全的**类型化**结果（守护进程用；顺序与去重规则都在 [Completer] 里）。 */
    fun completions(file: File, text: String?, line: Int, col: Int, baseDir: File?, script: Boolean = false): List<CompletionItem> {
        val source = if (text != null) SourceFile.of(file.path, text, script) else SourceFile.of(file, script)

        // 被 import 进来的函数也要能补出来。带上「当前文件」与「整个程序」两份函数表：
        // 前者用来判断光标在哪个函数里（要按当前文件的坐标算），后者用来列候选。
        var ownFunctions: List<FuncDecl> = emptyList()
        var allFunctions: List<FuncDecl> = emptyList()
        runCatching {
            // 阶段 84：先按项目根判定「编辑器该看见哪些 jar」，再分析
            ProjectJars.installFor(file)
            val loaded = Rsf.load(file, baseDir, text, script = script)
            allFunctions = loaded.functions
        }
        runCatching {
            ownFunctions = org.example.complie.parsing.Parser(
                Lexer(source).tokenize(), source,
            ).parseProgram().functions
        }

        return Completer(source, line, col, ownFunctions, allFunctions).items()
    }
}

/** 一条补全建议。kind 会映射成编辑器的 CompletionItemKind。 */
data class CompletionItem(val label: String, val kind: String, val detail: String)

/**
 * 在「第 line 行第 col 列」这个位置算出可以补什么。
 *
 * 两条路：
 * 1. **能解析**（文件当前是合法的）→ 用 AST 给出带签名的建议：函数（含签名）、
 *    当前函数的参数、光标之前声明过的变量（含类型）、成员访问。
 * 2. **解析不了**（正在打字，很常见）→ 退化成 token 扫描：把 `fun 名字`、
 *    `var/val 名字` 扫出来。粗糙但胜在「任何时候都有东西可补」。
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
        // `import ` 这一行单列：依赖 jar 里的类只有这里补得出来
        importItems()?.let { return it }

        val out = ArrayList<CompletionItem>()
        for (k in KEYWORDS) {
            // `let` 已经是废弃的关键字（写了会报「已经换成 var 与 val」），
            // 但词法器仍把它当保留字，所以候选里留着它、只是把话说清楚
            out.add(
                CompletionItem(
                    k,
                    "keyword",
                    if (k == "let") "保留字（已废弃：改用 var / val）" else "关键字",
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
    // ------------------------------------------------------------------ import

    /**
     * 光标停在 `import 前缀` 上时，给出**依赖 jar 里的类名**（阶段 84）。
     *
     * 只认「行首 import + 一个还没写完的点分名字」，所以别的位置不受影响；
     * 没有任何依赖 jar 时返回 null，让调用方回到通用的候选列表。
     *
     * 为什么只补 jar 里的类、不补 JDK：JDK 那几千个类塞进来只会淹没候选，
     * 而 `import java.lang.Math` 这种用户基本是自己写（或用 IDE 的 JDK 索引）。
     */
    private fun importItems(): List<CompletionItem>? {
        val lineText = source.lineAt(line) ?: return null
        val upto = lineText.take((col - 1).coerceIn(0, lineText.length))
        val typed = IMPORT_TAIL.find(upto)?.groupValues?.get(1) ?: return null
        val out = ArrayList<CompletionItem>()
        for (name in ProjectJars.installedClassNames()) {
            if (typed.isNotEmpty() && !name.startsWith(typed)) continue
            out.add(CompletionItem(name, "class", "依赖 jar 里的类"))
        }
        return out.ifEmpty { null }
    }

    // ------------------------------------------------------------------ 成员

    /** 光标前是 `xxx.` 或 `xxx.部分名字` 时，列出 xxx 的成员；不是则返回 null。 */
    private fun memberItems(): List<CompletionItem>? {
        val lineText = source.lineAt(line) ?: return null
        val upto = lineText.take((col - 1).coerceIn(0, lineText.length))
        val m = MEMBER_TAIL.find(upto) ?: return null
        val base = m.groupValues[1]

        val resolver = MemberResolver(importedClasses())
        val program = fileProgram()

        // `base` 有两种可能：**类型名**（`Math.`、`Box.`）或**变量名**（`b.`、`this.`）。
        // 阶段 87 之前只按类型名解析，于是 `b.` 什么都补不出来（只剩扩展函数）——
        // 这里先看是不是变量，把它的静态类型（写出来的类型，或者 `new X(...)` / 字面量推出来的）找出来。
        val isTypeName = resolver.resolveClass(base) != null || isUserType(base, program)
        val typeName = if (isTypeName) base else typeNameOf(base, program)
        val cls = resolver.resolveClass(typeName ?: base)

        val out = ArrayList<CompletionItem>()
        if (cls == null && typeName != null && program != null) {
            // 用户自己的类/接口：按声明顺序列字段与方法，再顺着父类型往上走
            userTypeItems(typeName, program, out)
        }
        if (cls != null) for (c in ClassPath.hierarchy(cls.internalName)) {
            // 只列 public：`s.` 以前会把 `COMPACT_STRINGS`、`newStringUTF8NoRepl` 这类
            // JDK 内部成员也列出来（81 条），既吵又调不到——编译器那边也一样拒绝。
            for (f in c.fields) {
                if (!f.isPublic) continue
                if (!out.any { it.label == f.name }) {
                    out.add(CompletionItem(f.name, "field", "${f.type.rsfName}（${if (f.isStatic) "静态" else "实例"}字段）"))
                }
            }
            for (mm in c.methods) {
                if (!mm.isPublic) continue
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
        // 阶段 80：扩展函数也列进来。能确定接收者类型时只列匹配的（`Box.` → `fn Box.foo`）；
        // 变量类型推不出来时就全列，详情里写清接收者——用户自己看得出哪个能用。
        val receiverName0 = typeName
        for (fn in program?.functions.orEmpty()) {
            val receiverName = fn.receiverType?.name ?: continue
            if (receiverName0 != null && receiverName != receiverName0) continue
            if (out.any { it.label == fn.name && it.kind == "method" }) continue
            out.add(
                CompletionItem(
                    fn.name,
                    "method",
                    "扩展函数（接收者 $receiverName）" + if (fn.isStatic) "（静态）" else "",
                ),
            )
        }
        return out.ifEmpty { null }
    }

    /** 名字本身就是本文件（或 import 进来的）类 / 接口 / 枚举名时算类型名。 */
    private fun isUserType(name: String, program: Program?): Boolean =
        program != null && (
            program.classes.any { it.name == name } ||
                program.interfaces.any { it.name == name } ||
                program.enums.any { it.name == name }
            )

    /**
     * 把 `名字.` 里的名字解析成**类型名**：`this` → 所在类；参数 / 局部变量 / 字段 → 它们的静态类型。
     *
     * 类型写出来就用它；没写（`val b = new Base("s")`）就按初值推一个最粗的类型。
     * 推不出来返回 null——那就只列扩展函数，不瞎猜。
     */
    private fun typeNameOf(name: String, program: Program?): String? {
        if (program == null) return null
        if (name == "this") {
            val owner = program.classes.lastOrNull { cls ->
                cls.methods.any { it.at.line <= line } && cls.at.line <= line
            }
            return owner?.name
        }
        val decls = program.functions +
            program.classes.flatMap { it.methods } +
            program.interfaces.flatMap { it.methods }
        // 形参
        for (fn in decls) {
            if (fn.at.line > line) continue
            for (p in fn.params) if (p.name == name) return p.type.name
        }
        // 局部变量（光标之前声明过的）
        for (fn in decls) {
            val body = fn.body ?: continue
            val hit = localsBefore(body).lastOrNull { it.name == name } ?: continue
            if (hit.at.line > line) continue
            return hit.declaredType?.name ?: inferredTypeName(hit.initializer)
        }
        // 字段（`this.xxx` 之外，直接写字段名的地方也能推）
        for (cls in program.classes) for (f in cls.fields) if (f.name == name) return f.type.name
        return null
    }

    /** 按初值猜类型：只认最粗的那几种，猜不出返回 null。 */
    private fun inferredTypeName(expr: Expr?): String? = when (expr) {
        is NewExpr -> expr.className
        is StringLit -> "String"
        is IntLit -> "Int"
        is LongLit -> "Long"
        is DoubleLit -> "Double"
        is FloatLit -> "Float"
        is BoolLit -> "Bool"
        is CharLit -> "Char"
        else -> null
    }

    /** 用户自己声明的类型：字段 + 方法，再顺着父类 / 父接口往上走。 */
    private fun userTypeItems(typeName: String, program: Program, out: MutableList<CompletionItem>) {
        val seen = HashSet<String>()

        fun walk(current: String, depth: Int) {
            if (depth > 6) return
            val cls = program.classes.firstOrNull { it.name == current }
            if (cls != null) {
                for (f in cls.fields) {
                    if (seen.add("f:" + f.name)) {
                        out.add(
                            CompletionItem(
                                f.name,
                                "field",
                                f.type.name + if (f.mutable) "（字段）" else "（只读字段）",
                            ),
                        )
                    }
                }
                for (m in cls.methods) {
                    if (m.isConstructor) continue
                    if (seen.add("m:" + m.name)) {
                        out.add(CompletionItem(m.name, "method", signature(m) + if (m.isStatic) "（静态）" else ""))
                    }
                }
                cls.superClass?.let { walk(it.name, depth + 1) }
                for (s in cls.supertypes) walk(s.name, depth + 1)
            }
            val iface = program.interfaces.firstOrNull { it.name == current }
            if (iface != null) {
                for (m in iface.methods) {
                    if (seen.add("m:" + m.name)) out.add(CompletionItem(m.name, "method", signature(m)))
                }
                for (s in iface.supertypes) walk(s.name, depth + 1)
            }
        }

        walk(typeName, 0)
    }

    /**
     * 把当前这篇源码解析一次（解析不了就是 null，正在打字时很常见）。
     *
     * 补全这一层只看当前文件：跨文件的声明要靠 import，而它不做跨文件语义分析
     * （与 `importedClasses` 用的是同一份「文件内 + import 的类」视野）。
     */
    /**
     * 解析当前文件（只用来出补全，不要求能过检查）。
     *
     * 用户打 `b.` 的那一瞬间文件是**语法不完整**的（`b.` 后面什么都没有），整份解析会直接抛错；
     * 但类 / 函数 / 字段 / 形参都在别的行上，把光标那一行挖空再解析一次，补全照样有答案。
     * 阶段 87 之前这里只解析一次，于是 `this.` / `b.` 全都退化成关键字表——那正是用户报的问题。
     */
    private fun fileProgram(): Program? {
        parseText(source.text)?.let { return it }
        val lines = source.text.split("\n").toMutableList()
        val at = line - 1
        if (at in lines.indices) lines[at] = ""
        // 别的行也可能是半行（`p.`、`this.`）——凡是**以点结尾**的行都不可能合法，一并挖空。
        for (i in lines.indices) {
            if (i != at && lines[i].trimEnd().endsWith(".")) lines[i] = ""
        }
        return parseText(lines.joinToString("\n"))
    }

    private fun parseText(text: String): Program? = runCatching {
        val file = SourceFile.of(source.path, text, source.script)
        org.example.complie.parsing.Parser(Lexer(file).tokenize(), file).parseProgram()
    }.getOrNull()

    /** 显式 import 的类（简单名 → 内部名）。java.lang 由 MemberResolver 兜底。 */
    private fun importedClasses(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val program = fileProgram() ?: return out
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

    /** 补全条目里显示的签名，参数渲染成 Kotlin 后缀风格：`add(a : Int, b : Int) -> Int`。 */
    private fun signature(fn: FuncDecl): String {
        val ps = fn.params.joinToString(", ") { "${it.name} : ${it.type.name}" }
        val mark = if (fn.ret.mayError) "!" else ""
        return "${fn.name}($ps) ${fn.ret.style.symbol} $mark${fn.ret.type.name}"
    }

    private companion object {
        val KEYWORDS = listOf(
            "fn", "rtn", "return", "var", "val", "if", "else", "while", "for",
            "in", "step", "break", "continue", "goto", "true", "false", "null",
            // pkg（阶段 6a）一直漏在补全表外：关键字表要同步——
            // 这里和 idea 插件的 RsfKeywords 一起改（由 EditorKeywordParityTest 守着）。
            "import", "module", "pkg", "pub", "prot", "prv", "stc",
            // 阶段 60：构建目标（声明级 native/jvm，文件级 target）
            "native", "jvm", "target",
            // 阶段 25：类继承的声明级修饰词（类与方法都能写）；阶段 27 起 `open` 已删，
            // 默认可继承/覆盖，只剩这两个收窄手段。
            "final", "abstract",
            "class", "new", "this",
            // 阶段 25：`super` 不是修饰词（它在表达式位置上），和 `this` 摆一起
            "super",
            // 阶段 14 的错误处理关键字也漏过一阵
            "fail", "try", "catch",
            // 阶段 18：次构造
            "constructor",
            // 阶段 19c：枚举声明 + 枚举分派（`case` 是关键字，`else` 已经在上面）
            "enum", "switch", "case",
            // 阶段 20：接口声明（`@interface` 是阶段 21 的事，各表先都不列）
            "interface",
            // 阶段 20 追加：可选的 override（类/接口方法都能写），两处关键字表照旧同步
            "override",
            // 阶段 21：注解声明与它的伴生实体。三个都是硬关键字——写进补全表，
            // 同时也是「标在声明前面的 @ 连同名字一起高亮」那件事的词汇面。
            "annotation", "companion", "entity",
            // 阶段 24 的转换/判断（`x as Foo` / `x is Foo`）——两个都是硬关键字，
            // 补全表一直漏着，写的时候只能靠记忆
            "as", "is",
            // 阶段 30 的 lambda 字面量：`lam(x : Int) -> Int { … }`
            "lam",
            // 阶段 80（上游 f127053）：顶层函数的导出修饰词 `export fn f()`
            "export",
            // `let` 在词法器里仍然是保留字（写了会得到「let 已经换成 var 与 val」
            // 的提示），所以补全表也留着它，但 detail 会标成已废弃
            "let",
        )
        // 阶段 16：Byte / Char 也要在候选里（类型关键字表两处同步：这里、
        // idea 插件的 RsfKeywords.TYPES）。
        // `Str` / `Boolean` 是 `String` / `Bool` 的别名（Lexer.KEYWORDS 里也认），
        // 一并列出来，免得两张表对不上。
        val TYPES = listOf(
            "void", "Int", "Long", "Float", "Double", "String", "Str",
            "Bool", "Boolean", "Byte", "Char",
        )

        /** 光标前是不是 `名字.` 或 `名字.部分`。 */
        val MEMBER_TAIL = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)?$")

        /** `import ` 之后那半个点分名字（可以为空）：`import demo.Ut` -> `demo.Ut`。 */
        val IMPORT_TAIL = Regex("""^\s*import\s+([A-Za-z0-9_.]*)$""")
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
