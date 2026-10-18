package org.example.complie.lsp

import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.FieldDecl
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.Node
import org.example.complie.ast.Block
import org.example.complie.ast.IfStmt
import org.example.complie.ast.WhileStmt
import org.example.complie.ast.ForStmt
import org.example.complie.ast.TryStmt
import org.example.complie.ast.SwitchStmt
import org.example.complie.diag.SourceFile
import org.example.complie.loading.LoadedProgram

/**
 * 符号表：让「Ctrl+点」「悬停看文档」有东西可指。
 *
 * 只做**声明位置**这一件事，不碰类型检查——语义层已经有一份权威实现，这里再判一遍
 * 类型只会两边走样。名字解析按「就近」排：局部变量 → 当前类的成员 → 本文件顶层 →
 * 别的文件顶层。找不到就老实回 null，让编辑器自己兜底。
 *
 * 文档取自声明**正上方连续的 `///` 行**（RDoc）。不认 `/** */`：rsf 的块注释常被拿来
 * 临时注释掉一段代码，把那种东西当文档会给出误导的悬停提示。
 */
object RsfSymbols {

    /** 一个可以跳转/悬停的声明。位置都是 **1 基**（和编译器的报错口径一致）。 */
    data class RsfSymbol(
        val name: String,
        /** `function` / `method` / `constructor` / `class` / `interface` / `enum` / `annotation` / `field` / `variable` */
        val kind: String,
        /** 一行签名，例如 `add(a : int, b : int) -> int`。 */
        val detail: String,
        /** RDoc 正文（没有就是空串）。 */
        val doc: String,
        /** 声明所在文件（绝对路径）。 */
        val file: String,
        val line: Int,
        val column: Int,
        val length: Int,
        /** 声明起点（`fn` / `class` 关键字所在行），用于「哪个声明包住了光标」。 */
        val declLine: Int,
    )

    // ------------------------------------------------------------------ 建表

    fun index(loaded: LoadedProgram): List<RsfSymbol> {
        val out = ArrayList<RsfSymbol>()

        for (fn in loaded.functions) {
            val src = loaded.sourceOfFunction[fn] ?: continue
            out.add(functionSymbol(fn, src, "function"))
        }

        for (cls in loaded.classes) {
            val src = loaded.sourceOfClass[cls] ?: continue
            out.add(typeSymbol(cls.name, classDetail(cls), docAt(src, cls.at.line), src, cls.at.line, cls.at.column, "class"))
            for (field in cls.fields) out.add(fieldSymbol(field, src))
            for (method in cls.methods) out.add(functionSymbol(method, src, if (method.isConstructor) "constructor" else "method"))
        }

        for (itf in loaded.interfaces) {
            val src = loaded.sourceOfInterface[itf] ?: continue
            out.add(typeSymbol(itf.name, "interface ${itf.name}", docAt(src, itf.at.line), src, itf.at.line, itf.at.column, "interface"))
            for (method in itf.methods) out.add(functionSymbol(method, src, "method"))
        }

        for (en in loaded.enums) {
            val src = loaded.sourceOfEnum[en] ?: continue
            out.add(typeSymbol(en.name, "enum ${en.name}", docAt(src, en.at.line), src, en.at.line, en.at.column, "enum"))
        }

        for (an in loaded.annotations) {
            val src = loaded.sourceOfAnnotation[an] ?: continue
            out.add(typeSymbol(an.name, "annotation ${an.name}", docAt(src, an.at.line), src, an.at.line, an.at.column, "annotation"))
        }

        return out
    }

    /**
     * 光标处（0 基）解析到哪个声明。
     *
     * 优先级就是人读代码时的直觉：局部变量 > 当前类成员 > 本文件顶层 > 其它文件。
     * 同一个名字有多处声明时，取**声明行不超过光标行**里最靠下的那一处（同名遮蔽的规则）。
     */
    fun resolveAt(loaded: LoadedProgram, file: SourceFile, line0: Int, character0: Int): RsfSymbol? {
        val word = wordAt(file, line0, character0) ?: return null
        val cursorLine = line0 + 1

        // 1) 局部变量：光标所在函数的函数体里，且声明在光标之前。
        //    局部变量**不在** index 里（index 只收声明级符号），所以这一步必须排在
        //    「有没有同名声明」的早退之前，否则 `msg = msg` 这种会解析不到。
        localsOf(loaded, file, cursorLine).lastOrNull { it.name == word.text && it.declLine <= cursorLine }?.let { return it }

        val all = index(loaded)
        val named = all.filter { it.name == word.text }
        if (named.isEmpty()) return null

        val before = named.filter { it.declLine <= cursorLine }

        // 2) 当前类的成员（字段/方法）
        val owner = enclosingType(loaded, file, cursorLine)
        if (owner != null) {
            named.firstOrNull { it.kind == "field" && it.declLine <= cursorLine && inType(loaded, owner, it) }?.let { return it }
            named.firstOrNull { it.kind == "method" && it.declLine <= cursorLine && inType(loaded, owner, it) }?.let { return it }
        }

        // 3) 本文件顶层 → 4) 别的文件
        before.firstOrNull { it.file == file.path && it.kind != "variable" }?.let { return it }
        before.firstOrNull { it.kind != "variable" }?.let { return it }
        return before.firstOrNull() ?: named.firstOrNull()
    }

    // ------------------------------------------------------------------ 文档

    /** 取声明上面连续的 `///` 行；空行会打断（不把上一段的注释认成这段的文档）。 */
    fun docAt(file: SourceFile, declLine: Int): String {
        val out = ArrayList<String>()
        var line = declLine - 1
        while (line >= 1) {
            val text = file.lineAt(line) ?: break
            val trimmed = text.trimStart()
            if (!trimmed.startsWith("///")) break
            out.add(trimmed.removePrefix("///").removePrefix(" "))
            line--
        }
        return out.asReversed().joinToString("\n").trim()
    }

    // ------------------------------------------------------------------ 内部

    private data class Word(val text: String, val start: Int, val end: Int)

    /** 光标处那个标识符。光标停在词尾后一位也算（编辑器里习惯把光标放在词后）。 */
    private fun wordAt(file: SourceFile, line0: Int, character0: Int): Word? {
        val line = file.lineAt(line0 + 1) ?: return null
        if (line.isEmpty()) return null
        var index = character0.coerceIn(0, line.length - 1)
        if (!isWordChar(line[index]) && index > 0 && isWordChar(line[index - 1])) index--

        if (!isWordChar(line[index])) return null
        var start = index
        while (start > 0 && isWordChar(line[start - 1])) start--
        var end = index + 1
        while (end < line.length && isWordChar(line[end])) end++
        return Word(line.substring(start, end), start, end)
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    /** 光标所在函数（按「声明行不超过光标、且最靠下」挑）里的局部变量，按声明顺序。 */
    private fun localsOf(loaded: LoadedProgram, file: SourceFile, cursorLine: Int): List<RsfSymbol> {
        val funcs = enclosingFunctions(loaded, file, cursorLine) ?: return emptyList()
        val out = ArrayList<RsfSymbol>()
        for (fn in funcs) {
            val body = fn.body ?: continue
            collectVars(body, file, out)
        }
        return out.sortedBy { it.declLine }
    }

    private fun enclosingFunctions(loaded: LoadedProgram, file: SourceFile, cursorLine: Int): List<FuncDecl>? {
        val candidates = ArrayList<FuncDecl>()
        for (fn in loaded.functions) if (loaded.sourceOfFunction[fn] === file) candidates.add(fn)
        for (cls in loaded.classes) {
            if (loaded.sourceOfClass[cls] !== file) continue
            candidates.addAll(cls.methods)
        }
        for (itf in loaded.interfaces) {
            if (loaded.sourceOfInterface[itf] !== file) continue
            candidates.addAll(itf.methods)
        }
        if (candidates.isEmpty()) return null
        // 光标可能落在类的字段区（不在任何函数体里）：这时取「开始行 <= 光标」里最靠下的那个，
        // 再把它的局部变量当作候选——多给几个变量比一个都不给强。
        return listOf(candidates.filter { it.at.line <= cursorLine }.maxByOrNull { it.at.line } ?: return null)
    }

    private fun enclosingType(loaded: LoadedProgram, file: SourceFile, cursorLine: Int): ClassDecl? {
        val classes = loaded.classes.filter { loaded.sourceOfClass[it] === file && it.at.line <= cursorLine }
        return classes.maxByOrNull { it.at.line }
    }

    private fun inType(loaded: LoadedProgram, owner: ClassDecl, symbol: RsfSymbol): Boolean {
        return owner.fields.any { it.name == symbol.name && it.at.line == symbol.declLine } ||
            owner.methods.any { it.name == symbol.name && it.at.line == symbol.declLine }
    }

    private fun collectVars(node: Node, file: SourceFile, out: MutableList<RsfSymbol>) {
        when (node) {
            is VarDeclStmt -> out.add(
                RsfSymbol(
                    name = node.name,
                    kind = "variable",
                    detail = "${if (node.mutable) "var" else "imutvar"} ${node.name} : ${node.declaredType?.name ?: "?"}",
                    doc = docAt(file, node.at.line),
                    file = file.path,
                    line = node.at.line,
                    column = node.at.column,
                    length = node.name.length,
                    declLine = node.at.line,
                ),
            )
            is Block -> for (stmt in node.statements) collectVars(stmt, file, out)
            is IfStmt -> {
                collectVars(node.body, file, out)
                node.elseBody?.let { collectVars(it, file, out) }
            }
            is WhileStmt -> collectVars(node.body, file, out)
            is ForStmt -> collectVars(node.body, file, out)
            is TryStmt -> {
                collectVars(node.body, file, out)
                collectVars(node.catchBody, file, out)
            }
            is SwitchStmt -> {
                for (case in node.cases) collectVars(case.body, file, out)
                node.elseBody?.let { collectVars(it, file, out) }
            }
            else -> Unit
        }
    }

    private fun functionSymbol(fn: FuncDecl, file: SourceFile, kind: String): RsfSymbol {
        val span = nameSpan(file, fn.at.line, fn.at.column, fn.name)
        return RsfSymbol(
            name = fn.name,
            kind = kind,
            detail = signature(fn),
            doc = docAt(file, fn.at.line),
            file = file.path,
            line = span.first,
            column = span.second,
            length = fn.name.length,
            declLine = fn.at.line,
        )
    }

    private fun fieldSymbol(field: FieldDecl, file: SourceFile): RsfSymbol {
        val span = nameSpan(file, field.at.line, field.at.column, field.name)
        return RsfSymbol(
            name = field.name,
            kind = "field",
            detail = "${if (field.mutable) "var" else "imutvar"} ${field.name} : ${field.type.name}",
            doc = docAt(file, field.at.line),
            file = file.path,
            line = span.first,
            column = span.second,
            length = field.name.length,
            declLine = field.at.line,
        )
    }

    private fun typeSymbol(
        name: String,
        detail: String,
        doc: String,
        file: SourceFile,
        line: Int,
        column: Int,
        kind: String,
    ): RsfSymbol {
        val span = nameSpan(file, line, column, name)
        return RsfSymbol(name, kind, detail, doc, file.path, span.first, span.second, name.length, line)
    }

    /** 名字在声明行里的列（1 基）。行里找不到就退回声明起点（总比指到 0 强）。 */
    private fun nameSpan(file: SourceFile, line: Int, column: Int, name: String): Pair<Int, Int> {
        val text = file.lineAt(line) ?: return line to column
        val from = (column - 1).coerceIn(0, text.length)
        val idx = text.indexOf(name, from).takeIf { it >= 0 } ?: text.indexOf(name)
        return line to (if (idx >= 0) idx + 1 else column)
    }

    private fun classDetail(cls: ClassDecl): String {
        val params = cls.typeParams.joinToString(", ")
        val generics = if (params.isEmpty()) "" else "&<$params>"
        val parent = cls.superClass?.let { " : ${it.name}" } ?: ""
        return "class ${cls.name}$generics$parent"
    }

    /** 签名渲染：`add(a : int, b : int) -> int`（和补全条目同一口径）。 */
    fun signature(fn: FuncDecl): String {
        val ps = fn.params.joinToString(", ") { "${it.name} : ${it.type.name}" }
        val mark = if (fn.ret.mayError) "!" else ""
        val keyword = if (fn.isConstructor) "constructor " else ""
        return "$keyword${fn.name}($ps) ${fn.ret.style.symbol} $mark${fn.ret.type.name}"
    }

    /** 悬停内容（markdown）：签名代码块 + RDoc + 定义位置。 */
    fun hoverMarkdown(symbol: RsfSymbol, baseDir: String?): String {
        val sb = StringBuilder()
        sb.append("```rsf\n").append(symbol.detail).append("\n```\n")
        if (symbol.doc.isNotEmpty()) sb.append("\n").append(symbol.doc).append("\n")
        val shown = baseDir?.let { dir ->
            if (symbol.file.startsWith(dir)) symbol.file.removePrefix(dir).trimStart('/', '\\') else symbol.file
        } ?: symbol.file
        sb.append("\n---\n`").append(shown.replace('\\', '/')).append(':').append(symbol.line).append("`\n")
        return sb.toString()
    }
}
