package org.example.complie.parsing

import org.example.complie.ast.*
//import org.example.complie.ast.BinaryExpr
//import org.example.complie.ast.Block
//import org.example.complie.ast.BoolLit
//import org.example.complie.ast.BreakStmt
//import org.example.complie.ast.CallExpr
//import org.example.complie.ast.CharLit
//import org.example.complie.ast.ClassDecl
//import org.example.complie.ast.ClassImport
//import org.example.complie.ast.ContinueStmt
//import org.example.complie.ast.ControlType
//import org.example.complie.ast.DoubleLit
//import org.example.complie.ast.ElvisExpr
//import org.example.complie.ast.EnumDecl
//import org.example.complie.ast.EnumValue
//import org.example.complie.ast.Expr
//import org.example.complie.ast.ExprStmt
//import org.example.complie.ast.FailStmt
//import org.example.complie.ast.FieldDecl
//import org.example.complie.ast.FloatLit
//import org.example.complie.ast.ForceExpr
//import org.example.complie.ast.ForStmt
//import org.example.complie.ast.FuncDecl
//import org.example.complie.ast.GotoStmt
//import org.example.complie.ast.IfExpr
//import org.example.complie.ast.IfStmt
//import org.example.complie.ast.ImportDecl
//import org.example.complie.ast.IndexAssignStmt
//import org.example.complie.ast.IndexExpr
//import org.example.complie.ast.IntLit
//import org.example.complie.ast.LabelStmt
//import org.example.complie.ast.LongLit
//import org.example.complie.ast.MemberAssignStmt
//import org.example.complie.ast.MemberExpr
//import org.example.complie.ast.ModuleImport
//import org.example.complie.ast.NewArrayExpr
//import org.example.complie.ast.NewExpr
//import org.example.complie.ast.NullLit
//import org.example.complie.ast.Param
//import org.example.complie.ast.Program
//import org.example.complie.ast.ReturnSpec
//import org.example.complie.ast.ReturnStmt
//import org.example.complie.ast.ReturnStyle
//import org.example.complie.ast.SourceImport
//import org.example.complie.ast.Stmt
//import org.example.complie.ast.StringLit
//import org.example.complie.ast.SwitchCase
//import org.example.complie.ast.SwitchStmt
//import org.example.complie.ast.ThisCallStmt
//import org.example.complie.ast.ThisExpr
//import org.example.complie.ast.TryStmt
//import org.example.complie.ast.TypeRef
//import org.example.complie.ast.UnaryExpr
//import org.example.complie.ast.VarDeclStmt
//import org.example.complie.ast.VarExpr
//import org.example.complie.ast.WhileStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.Token
import org.example.complie.lexing.TokenKind

/**
 * rainStarfox 语法分析器（递归下降 + 优先级爬升）。
 *
 * 函数声明：
 * ```
 * <ControlType> <static> fun <MainType> 名字(<参数表>) -> <ReturnType> { ... }
 * <ControlType> <static> fun <MainType> 名字(<参数表>) : <ReturnType> { ... }
 * ```
 * - `<ControlType>`：`public` / `protected` / `private`，写在最左，**省略就是 `public`**
 * - `<static>`：紧跟其后，控制「怎么调用」（类名.方法 / 对象.方法），可省略
 * - `<MainType>`：`fun` 之后、函数名之前的类型槽，缺省 `void`
 * - **返回类型子句只认 `->` 和 `:`**（2026-10-15 拍板：`::` 那条别名删掉，`::` 腾给
 *   `xxx::xx` 这种嵌套写法；`:` 与 `if c : 语句`、`var x : int` 各自在别的上下文，不冲突）；
 *   可省略，缺省 `void`；可带 `?`（可空）或 `!`（可能出错）前缀
 * - **参数表两种风格都收，可以混写**：Java 风格 `int a` / Kotlin 风格 `a : int`
 * - **变量与字段只收 Kotlin 后缀风格**：`var x : int = 0`（类型可省，靠推断）
 *
 * 语句：
 * ```
 * var x : int = 1        声明变量（Kotlin 后缀类型；类型可省）
 * imutvar name = "hi"      不可变变量
 * x = 2                    赋值
 * rtn / return expr        返回（两个关键字等价）
 * 名字:                    标签（goto 落点）
 * goto 名字                无条件跳转
 * if (c) { ... }           if：花括号形式
 * if c : 单条语句           if：冒号形式
 * if c ? a : b             三目（表达式）
 * while (c) { ... }        循环
 * break / continue         跳出 / 下一轮
 * fail "消息"              抛出一个错误（只能在 !T 函数里，或者 try 里）
 * try { ... } catch (e : string) { ... } 接住错误（也接受老写法 catch (e string)）
 * { ... }                  块（引入新作用域）
 * ```
 */
class Parser(
    private val tokens: List<Token>,
    private val source: SourceFile,
) {

    private var pos = 0

    /** 解糖时用来生成不重名的内部变量名。 */
    private var synthCounter = 0

    fun parseProgram(): Program {
        val imports = ArrayList<ImportDecl>()
        val functions = ArrayList<FuncDecl>()
        val classes = ArrayList<ClassDecl>()
        val enums = ArrayList<EnumDecl>()
        val interfaces = ArrayList<InterfaceDecl>()
        val annotations = ArrayList<AnnotationDecl>()

        // pkg 只能出现在最前面（在 import 之前），和 Java 的 package 一样
        var packageName: String? = null
        if (check(TokenKind.KW_PKG)) {
            packageName = parsePackageDecl()
        }

        while (!check(TokenKind.EOF)) {
            // 声明前的注解（阶段 21）：`@Marker public class C { }`。
            // 注解与 `public`/`static`/`override` **顺序任意、可连写多个**，所以先在
            // 这里收干净，再按「接下来是什么声明」分派；每一种声明都带一个
            // `annotations` 参数（缺省空表），既有调用点因此一行都不用改。
            val declAnnotations = parseAnnotations()
            if (check(TokenKind.KW_IMPORT) || check(TokenKind.KW_MODULE)) {
                imports.add(parseImport())
            } else if (check(TokenKind.KW_PKG)) {
                val t = peek()
                throw error(t, "'pkg' 只能写在文件最前面（在所有 import 之前）")
            } else if (check(TokenKind.KW_ENUM) || isEnumDeclAhead()) {
                // 枚举**不是** v1 的可标注位置（DESIGN-21.md §2.3 的可标注位置只有
                // 类 / 接口 / 注解声明 / 方法 / 字段）。这里明确拦下，别让 `@Marker`
                // 被悄悄丢掉——用户会以为标上了。
                if (declAnnotations.isNotEmpty()) {
                    val first = declAnnotations.first()
                    throw RsfError(
                        "parser",
                        "注解不能标在枚举声明上：v1 的可标注位置是类 / 接口 / 注解声明 / 方法 / 字段" +
                            "（枚举留到后续阶段）：把 '${first.display()}' 删掉，或者改标在别的声明上",
                        first.at.line,
                        first.at.column,
                        source,
                    )
                }
                enums.add(parseEnumDecl())
            } else if (check(TokenKind.KW_CLASS)) {
                classes.add(parseClassDecl(declAnnotations))
            } else if (isClassDeclAhead()) {
                classes.add(parseClassDecl(declAnnotations))
            } else if (check(TokenKind.KW_INTERFACE)) {
                interfaces.add(parseInterfaceDecl(declAnnotations))
            } else if (isInterfaceDeclAhead()) {
                interfaces.add(parseInterfaceDecl(declAnnotations))
            } else if (check(TokenKind.KW_ANNOTATION)) {
                annotations.add(parseAnnotationDecl(declAnnotations))
            } else if (isAnnotationDeclAhead()) {
                annotations.add(parseAnnotationDecl(declAnnotations))
            } else if (check(TokenKind.KW_MATHEMATIC)) {
                // 数学类的预留关键字（`mathematic` 在词法层已经收成关键字，但语法还没定；
                // 2026-10-15 用户把它移出计划，词本身保留）：在这里明确拦下，
                // 别让它落到 parseFuncDecl 里报一句关于 `fun` 的错。
                val t = peek()
                throw error(
                    t,
                    "'mathematic' 是保留关键字（数学类预留）：语法还没定，编译器还没实现它" +
                        "（现在还只能当关键字用，不能当名字）",
                )
            } else {
                functions.add(parseFuncDecl(annotations = declAnnotations, allowLeadingAnnotations = true))
            }
        }
        return Program(imports, functions, classes, packageName, enums, interfaces, annotations)
    }

    /**
     * 包声明：`pkg 名字`。
     *
     * 段之间可以用 `.`、`\`、`/` 任意混写，统一归一化成点分形式（`a.b`）：
     * `pkg util`、`pkg a.b`、`pkg a\b`、`pkg a/b` 说的是同一个包。
     * 包本身是个**目录**，以当前目录为准（`pkg a.b` → `./a/b/`）。
     */
    private fun parsePackageDecl(): String {
        val kw = advance() // pkg
        val parts = ArrayList<String>()

        fun readIdent(): String {
            val t = peek()
            if (t.kind != TokenKind.IDENT) {
                throw error(
                    t,
                    "包名需要标识符（例如 pkg util 或 pkg a.b），实际是 ${t.describe()}",
                )
            }
            advance()
            return t.lexeme
        }

        parts.add(readIdent())
        while (true) {
            // 三种分隔符都收，混写也行
            val separated = when {
                match(TokenKind.DOT) -> true
                match(TokenKind.SLASH) -> true
                match(TokenKind.BACKSLASH) -> true
                else -> false
            }
            if (!separated) break
            parts.add(readIdent())
        }
        return parts.joinToString(".")
    }

    /**
     * 顶层声明的前瞻：注解（`@Name` / `@Name(...)`）和修饰词（`public`/`protected`/
     * `private`/`static`/`override`）顺序任意、数量不限，所以跳着数一遍。
     */
    private fun declLookaheadOffset(): Int {
        var offset = 0
        while (true) {
            val kind = peek(offset).kind
            if (kind == TokenKind.AT) {
                offset++
                if (peek(offset).kind == TokenKind.IDENT) {
                    offset++
                } else {
                    return offset
                }
                while (peek(offset).kind == TokenKind.DOT || peek(offset).kind == TokenKind.DOUBLE_COLON) {
                    offset++
                    if (peek(offset).kind == TokenKind.IDENT) offset++ else return offset
                }
                if (peek(offset).kind == TokenKind.LPAREN) {
                    var depth = 1
                    offset++
                    while (true) {
                        val k = peek(offset).kind
                        if (k == TokenKind.EOF) return offset
                        if (k == TokenKind.LPAREN) depth++
                        if (k == TokenKind.RPAREN) {
                            depth--
                            if (depth == 0) {
                                offset++
                                break
                            }
                        }
                        offset++
                    }
                }
                continue
            }
            if (kind.isModifierKeyword) {
                offset++
                continue
            }
            return offset
        }
    }

    /** 顶层下一个声明是不是类：`[@注解]* [public|protected|private] class ...`。 */
    private fun isClassDeclAhead(): Boolean =
        peek(declLookaheadOffset()).kind == TokenKind.KW_CLASS

    /** 顶层下一个声明是不是枚举：`[@注解]* [public|protected|private] enum ...`。 */
    private fun isEnumDeclAhead(): Boolean =
        peek(declLookaheadOffset()).kind == TokenKind.KW_ENUM

    /** 顶层下一个声明是不是接口：`[@注解]* [public|protected|private] interface ...`。 */
    private fun isInterfaceDeclAhead(): Boolean =
        peek(declLookaheadOffset()).kind == TokenKind.KW_INTERFACE

    /** 顶层下一个声明是不是注解声明：`[@注解]* [public|protected|private] annotation ...`。 */
    private fun isAnnotationDeclAhead(): Boolean =
        peek(declLookaheadOffset()).kind == TokenKind.KW_ANNOTATION

    // ------------------------------------------------------------------
    // 注解：`@Marker` 使用点 / `annotation Marker(...) { companion entity ... }` 声明
    // （阶段 21 M1，定稿见 DESIGN-21.md §2）
    // ------------------------------------------------------------------

    /**
     * 收下声明前零个或多个注解：`@Marker`、`@Marker("hi")`、`@Marker(value = "hi", count = 3)`。
     *
     * 多个注解**可以连写**（`@A @B fun f()`），且与 `public`/`static`/`override` 顺序任意。
     * 调用方拿到这张表后传给对应声明的 `annotations` 参数；声明自己的解析函数里还会再收一轮，
     * 因为 `public @M fun` 这种顺序也合法。
     *
     * 这里**不做**「这个位置能不能标注解」的判断：参数和局部变量位置 v1 不开放注解，
     * 由各自的解析函数给出更精确的报错（它们会自然读到 `@` 这个记号）。
     */
    private fun parseAnnotations(): List<AnnotationUse> {
        if (!check(TokenKind.AT)) return emptyList()
        val result = ArrayList<AnnotationUse>()
        while (check(TokenKind.AT)) {
            result.add(parseAnnotationUse())
        }
        return result
    }

    /**
     * 注解使用点：`@名字` 或 `@名字(实参表)`。
     *
     * 表可以带 `.`/`::` 限定（`@a.b`、`@Outer::Inner`）。实参表**只有注解使用点**支持
     * 具名实参（`value = "hi"`），普通调用的实参语法一行没改。
     */
    private fun parseAnnotationUse(): AnnotationUse {
        val atTok = expect(TokenKind.AT, "注解要写在 '@' 后面")
        val nameTok = expect(TokenKind.IDENT, "'@' 后面缺少注解名（写法是 @Marker 或 @Marker(...)）")
        var name = nameTok.lexeme
        while (check(TokenKind.DOT) || check(TokenKind.DOUBLE_COLON)) {
            val sep = advance()
            val partTok = expect(
                TokenKind.IDENT,
                "注解名 '$name' 后面缺少名字：'${sep.lexeme}' 后面要跟一个名字（例如 @a.b 或 @Outer::Inner）",
            )
            name = "$name.${partTok.lexeme}"
        }
        val args = if (check(TokenKind.LPAREN)) parseAnnotationArgs() else emptyList()
        return AnnotationUse(name, args, atTok.at)
    }

    /** 注解使用点的实参表：位置式与具名式**可以混写**（`@M("hi", count = 3)` 也行）。 */
    private fun parseAnnotationArgs(): List<AnnotationArg> {
        expect(TokenKind.LPAREN, "注解实参表缺少 '('")
        val args = ArrayList<AnnotationArg>()
        if (!check(TokenKind.RPAREN)) {
            while (true) {
                args.add(parseAnnotationArg())
                if (!match(TokenKind.COMMA)) break
                if (check(TokenKind.RPAREN)) break // 尾逗号
            }
        }
        expect(TokenKind.RPAREN, "注解实参表没有闭合，缺少 ')'")

        // 语义上「位置式不能写到具名式后面」，但这里只做最省的语法检查：
        // 一旦出现过具名实参，后面再来位置式就没法确定它是哪个元素了。
        var seenNamed = false
        for (arg in args) {
            when (arg) {
                is NamedAnnotationArg -> seenNamed = true
                is PositionalAnnotationArg -> if (seenNamed) {
                    throw error(
                        previous(),
                        "注解实参里具名式要写在最后：'${exprText(arg.value)}' 前面已经出现了 '名字 = 值'，" +
                            "位置式实参没法确定对应哪个元素",
                    )
                }
            }
        }
        return args
    }

    /**
     * 一个注解实参。具名式的判据是「IDENT 后面紧跟 `=`」——前端记号表里没有 `+=`、`==` 这类
     * 复合记号（`=` 只有 `ASSIGN("=")` 一种），所以这个前瞻不会误伤。
     */
    private fun parseAnnotationArg(): AnnotationArg {
        if (check(TokenKind.IDENT) && peek(1).kind == TokenKind.ASSIGN) {
            val nameTok = advance()
            advance() // =
            val value = parseExpr()
            return NamedAnnotationArg(nameTok.lexeme, value, nameTok.at)
        }
        val at = peek().at
        return PositionalAnnotationArg(parseExpr(), at)
    }

    /**
     * 注解声明：
     * ```
     * [public|protected|private] annotation Marker(value : string = "x", count : int = 0) {
     *     companion entity Impl : Runnable { ... }
     * }
     * ```
     *
     * 元素表可省（`annotation Marker { }`）。注解体里**至多一个** `companion entity`，
     * 别的成员（`fun`/`var`/`imutvar`/`constructor`）是语法错误——在 parser 层就报，
     * 不留给语义层（DESIGN-21.md §4）。
     */
    private fun parseAnnotationDecl(annotations: List<AnnotationUse> = emptyList()): AnnotationDecl {
        var control = ControlType.DEFAULT
        if (peek().kind.isControlKeyword) {
            control = ControlType.of(advance().kind)
        }
        val annotationTok = expect(TokenKind.KW_ANNOTATION, "注解声明缺少 'annotation'")
        val nameTok = expect(TokenKind.IDENT, "'annotation' 后面缺少注解名")

        val elements = if (check(TokenKind.LPAREN)) parseAnnotationElementList() else emptyList()
        val companion: CompanionEntityDecl? = if (match(TokenKind.LBRACE)) {
            val entity = parseAnnotationBody(nameTok.lexeme)
            // 体里**至多一个**伴生实体（DESIGN-21 §2.1）：第二个要在这里说清楚，
            // 不然只会撞上一句「注解没有闭合，缺少 '}'」，看不出是数量的问题。
            if (check(TokenKind.KW_COMPANION)) {
                val t = peek()
                throw error(
                    t,
                    "注解 '${nameTok.lexeme}' 的体里只能写一个 companion entity（这里已经是第二个了）：" +
                        "把第二个的字段/方法合并进第一个伴生实体（实际读到 ${t.describe()}）",
                )
            }
            expect(TokenKind.RBRACE, "注解 '${nameTok.lexeme}' 没有闭合，缺少 '}'")
            entity
        } else {
            null
        }
        return AnnotationDecl(
            control = control,
            name = nameTok.lexeme,
            elements = elements,
            at = annotationTok.at,
            companion = companion,
            annotations = annotations,
        )
    }

    /** 注解的元素表：`(value : string = "x", count : int = 0)`。 */
    private fun parseAnnotationElementList(): List<AnnotationElement> {
        expect(TokenKind.LPAREN, "注解元素表缺少 '('")
        val elements = ArrayList<AnnotationElement>()
        if (!check(TokenKind.RPAREN)) {
            while (true) {
                elements.add(parseAnnotationElement())
                if (!match(TokenKind.COMMA)) break
                if (check(TokenKind.RPAREN)) break // 尾逗号
            }
        }
        expect(TokenKind.RPAREN, "注解元素表没有闭合，缺少 ')'")

        val seen = HashMap<String, SourceLine>()
        for (element in elements) {
            val previousAt = seen.put(element.name, element.at)
            if (previousAt != null) {
                throw RsfError(
                    "parser",
                    "注解元素 '${element.name}' 重复定义了（元素名是 '名字 : 类型' 里的那个名字）：" +
                        "上一个写在第 ${previousAt.line} 行",
                    element.at.line,
                    element.at.column,
                    source,
                )
            }
        }
        return elements
    }

    /**
     * 一个注解元素：`名字 : 类型` 或 `名字 : 类型 = 常量默认值`。
     *
     * 类型只收**后缀风格**（`value : string`），和字段/参数的后缀风格一致；
     * 写成 Java 风格（`string value`）会在这里报错。
     */
    private fun parseAnnotationElement(): AnnotationElement {
        val nameTok = expect(
            TokenKind.IDENT,
            "注解元素要写成「名字 : 类型」（可以有默认值「名字 : 类型 = 常量」），实际读到 ${peek().describe()}",
        )
        expect(
            TokenKind.COLON,
            "注解元素 '${nameTok.lexeme}' 缺少类型：要写成「名字 : 类型」" +
                "（例如 ${nameTok.lexeme} : string），当前读到 ${peek().describe()}",
        )
        val type = parseTypeRef()
        val default = if (match(TokenKind.ASSIGN)) parseAnnotationDefault(nameTok.lexeme) else null
        return AnnotationElement(nameTok.lexeme, type, default, nameTok.at)
    }

    /**
     * 注解元素的默认值：v1 只收**常量字面量**——int/long/float/double/string/char/bool、
     * `null`，以及数值字面量前面一个 `-`。
     *
     * 枚举常量、注解、数组默认值明确不在 v1 里（DESIGN-21.md §12.6），这里报「暂不支持」，
     * 并指向伴生实体（要现算的默认值写在伴生实体里）。
     */
    private fun parseAnnotationDefault(owner: String): Expr {
        val start = peek()
        val value = parseExpr()
        validateAnnotationDefault(owner, value, start)
        return value
    }

    /**
     * 默认值的形状检查：只让常量字面量过（V1_DEFAULT_KINDS），以及它们前面**一个** `-`。
     *
     * 用「先按普通表达式解析、再按形状校验收窄」而不是「写一个小的常量表达式解析器」：
     * 这样 `-1`、`- 1.5` 这类写法与程序里其它地方的解析结果**是同一个 AST**，
     * 也免得再维护一份字面量识别逻辑（漏一个记号就会静默收下一个奇怪的值）。
     */
    private fun validateAnnotationDefault(owner: String, value: Expr, start: Token) {
        if (value is UnaryExpr && value.op == TokenKind.MINUS) {
            val operand = value.operand
            if (operand is IntLit || operand is LongLit || operand is FloatLit || operand is DoubleLit) return
        }
        if (isDefaultConstantLiteral(value)) return
        throw error(
            start,
            "注解元素 '$owner' 的默认值只支持常量字面量（int/long/float/double/string/char/bool/null，" +
                "数值可以带前导 '-'），实际读到 ${start.describe()}。" +
                "枚举常量、注解、数组默认值 v1 都还不支持；要现算的默认值请写进伴生实体里",
        )
    }

    /** 注解默认值里允许出现的裸字面量。 */
    private fun isDefaultConstantLiteral(value: Expr): Boolean = when (value) {
        is IntLit, is LongLit, is FloatLit, is DoubleLit,
        is StringLit, is CharLit, is BoolLit, is NullLit,
        -> true

        else -> false
    }

    /**
     * 注解体：**至多一个** `companion entity [名字] [: 父接口 [, 父接口]*] { 普通类体 }`。
     *
     * 别的成员一律报错——注解体不是类体，这是 v1 的语法决定（DESIGN-21.md §2.1/§4）。
     * 返回 null 表示「体里没有伴生实体」（`annotation Marker { }` 或 `annotation Marker { ... }` 空体）。
     */
    private fun parseAnnotationBody(annotationName: String): CompanionEntityDecl? {
        parseAnnotations() // 伴生实体自己也能带注解（语义层忽略也没关系）
        if (check(TokenKind.KW_COMPANION)) {
            return parseCompanionEntity(annotationName)
        }
        if (check(TokenKind.RBRACE)) return null
        throw error(
            peek(),
            "注解 '$annotationName' 的体里只能写一个 companion entity（例如 " +
                "companion entity Impl : Runnable { ... }），实际读到 ${peek().describe()}。" +
                "注解不是类：字段/方法/构造器都要写进伴生实体里",
        )
    }

    /**
     * 伴生实体：
     * ```
     * companion entity Impl : Runnable {
     *     var hits : int = 0
     *     fun run() -> void { }
     * }
     * ```
     *
     * 名字可省（匿名：`companion entity : Runnable { }`），父类型列表可省。体是**普通类体**
     * （字段/方法/构造器），复用 parseFieldDecl / parseConstructor / parseFuncDecl。
     */
    private fun parseCompanionEntity(annotationName: String): CompanionEntityDecl {
        val companionTok = expect(TokenKind.KW_COMPANION, "伴生实体缺少 'companion'")
        expect(
            TokenKind.KW_ENTITY,
            "companion 后面缺少 'entity'（伴生实体要写成 companion entity [名字] [: 父接口] { ... }）",
        )
        val name: String? = if (check(TokenKind.IDENT)) advance().lexeme else null
        if (name != null && peek().kind.isTypeKeyword) {
            val t = peek()
            throw error(
                t,
                "伴生实体 '${name}' 缺少 '{'：名字后面的类型要写成父接口列表（companion entity $name : 父接口 { ... }），" +
                    "实际读到 ${t.describe()}",
            )
        }
        val supertypes = parseSupertypeList("注解 '${annotationName}' 的伴生实体")
        expect(
            TokenKind.LBRACE,
            "伴生实体${if (name == null) "" else " '${name}'"}缺少 '{'（伴生实体要有类体，写法是 companion entity 名字 : 父接口 { ... }）",
        )

        val fields = ArrayList<FieldDecl>()
        val methods = ArrayList<FuncDecl>()
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            val memberAnnotations = parseAnnotations()
            if (isFieldDeclAhead()) {
                fields.add(parseFieldDecl(memberAnnotations))
                continue
            }
            if (check(TokenKind.KW_CONSTRUCTOR)) {
                if (memberAnnotations.isNotEmpty()) {
                    throw error(
                        peek(),
                        "伴生实体的构造器上不能写注解（'@'）：注解只标在类/接口/注解声明/方法/字段上",
                    )
                }
                methods.add(parseConstructor(name ?: annotationName))
                continue
            }
            // 伴生实体的体就是普通类体：override 的**语义**检查留给 Checker
            // （类和伴生实体走同一套规矩，不在这里多加一条只有注解才有的限制）。
            val member = parseFuncDecl(
                annotations = ArrayList(memberAnnotations),
                allowOverride = true,
                allowLeadingAnnotations = true,
            )
            if (check(TokenKind.SEMI)) advance()
            methods.add(member)
        }
        expect(
            TokenKind.RBRACE,
            "伴生实体${if (name == null) "" else " '${name}'"}没有闭合，缺少 '}'",
        )
        return CompanionEntityDecl(name, fields, methods, supertypes, companionTok.at)
    }

    /**
     * 枚举声明（阶段 19c）：
     * ```
     * public enum TokenKind {
     *     IDENT,
     *     INT_LIT,
     *     STRING,
     *     EOF            // 尾逗号可写可不写
     * }
     * ```
     *
     * 只做**语法**上能定死的事：权限修饰词（缺省 public）、名字、至少一个值、
     * 逗号分隔、允许尾逗号。三件语义上的事交给 Checker——
     * 值名重复、枚举名与类名/函数名重名、以及「枚举名撞程序类名」（那是 codegen 的事）。
     * 这么分工是为了报错能带上**正确的文件**（多文件编译时 Checker 才知道每个声明来自哪）。
     *
     * 注解（阶段 21 M1）：枚举**不**收注解——可标注位置只有类 / 接口 / 注解声明 /
     * 方法 / 字段，写在 `enum` 前面的 `@` 由 [parseProgram] 在分派到这里之前就报错。
     */
    private fun parseEnumDecl(): EnumDecl {
        var control = ControlType.DEFAULT
        if (peek().kind.isControlKeyword) {
            control = ControlType.of(advance().kind)
        }
        val enumTok = expect(TokenKind.KW_ENUM, "enum 声明缺少 'enum'")
        val nameTok = expect(TokenKind.IDENT, "'enum' 后面缺少枚举名")

        expect(TokenKind.LBRACE, "枚举 '${nameTok.lexeme}' 缺少 '{'")

        val values = ArrayList<EnumValue>()
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            val valueTok = expect(
                TokenKind.IDENT,
                "枚举 '${nameTok.lexeme}' 里需要一个值名（写法是 IDENT, INT_LIT, ...），" +
                    "实际读到 ${peek().describe()}",
            )
            values.add(EnumValue(valueTok.lexeme, valueTok.at))
            // 逗号可省在**最后一个值**上（尾逗号），所以「没吃逗号」就说明值列完了
            if (!match(TokenKind.COMMA)) break
        }
        expect(TokenKind.RBRACE, "枚举 '${nameTok.lexeme}' 没有闭合，缺少 '}'")

        if (values.isEmpty()) {
            throw error(
                enumTok,
                "枚举 '${nameTok.lexeme}' 至少要有一个值（写法是 enum ${nameTok.lexeme} { A, B, C }）",
            )
        }
        return EnumDecl(control, nameTok.lexeme, values, enumTok.at)
    }

    /**
     * 类声明（阶段 18 起带主构造）：
     * ```
     * public class Point(x0 : int, y0 : int) {   主构造：参数写在类头，字段初值里能用
     *     var x : int = x0                     字段（类型 + 初值都要写；类型写在名字后面）
     *     public fun sum() -> int { }            普通方法
     *     constructor(v : int) { x = v }         次构造（可以写多个）
     * }
     * ```
     *
     * 主构造在**解析时合成**成一个 `FuncDecl`（名字 = 类名、`isPrimary`、体是空块），
     * 排进 [ClassDecl.methods] 的最前面。这样「构造器」在下游看来就是一个返回 void、
     * 有一组参数、有一个函数体的普通函数——槽位分配、降级、解释器、字节码后端
     * 全都不用为它分家。主构造的「体」就是字段初值（不需要 init 块）。
     */
    private fun parseClassDecl(annotations: List<AnnotationUse> = emptyList()): ClassDecl {
        // 阶段 25 K5a-fix：类头的「权限修饰词」与「继承修饰词」**任意穿插**——`public final class`
        // 与 `final public class` 等价（与 [parseFuncDecl] 的同一条口径，Kotlin 也这么收）。
        // 权限修饰词仍然只收一个：第二个（`public private class`）落到下面的 expect(KW_CLASS) 上，
        // 报错文案与修正前逐字一致，不在这里放松。
        var control = ControlType.DEFAULT
        var sawControl = false
        var inherit: InheritModifiers? = null
        var going = true
        while (going) {
            if (!sawControl && peek().kind.isControlKeyword) {
                control = ControlType.of(advance().kind)
                sawControl = true
            } else if (peek().kind.isInheritKeyword) {
                // 被权限修饰词隔开的几段继承修饰词要**合起来**判重与互斥（`final public abstract`），
                // 和 [parseFuncDecl] 前后收到两段时走的是同一个 [mergeInherit]。
                inherit = mergeInherit(inherit, kindWord = "类")
            } else {
                going = false
            }
        }
        val mods = inherit ?: InheritModifiers(isFinal = false, isAbstract = false)
        val classTok = expect(TokenKind.KW_CLASS, "class 声明缺少 'class'")
        val nameTok = expect(TokenKind.IDENT, "'class' 后面缺少类名")

        // 类型参数子句：`class Box&<T>` / `class Pair&<K, V>`。
        // 必须写 `&<`：单独的 `<` 是「小于」，不能当类型实参的开头。
        val typeParams = parseTypeParamList(nameTok, kindWord = "类", declWord = "class")

        // 主构造：类头那对括号。不写括号（`class P { ... }`）就没有主构造，
        // 与「只写 constructor(...)」是两回事——后者不会再有隐式的无参构造。
        val primary: FuncDecl? = if (check(TokenKind.LPAREN)) {
            val ctorParams = parseParamList(
                "类 '${nameTok.lexeme}' 的主构造参数表没有闭合，缺少 ')'",
            )
            FuncDecl(
                control = ControlType.DEFAULT,
                mainType = TypeRef("void", classTok.at),
                name = nameTok.lexeme,
                params = ctorParams,
                ret = ReturnSpec(TypeRef("void", classTok.at), ReturnStyle.OMITTED, false, classTok.at),
                body = Block(emptyList(), classTok.at),
                at = classTok.at,
                isStatic = false,
                isConstructor = true,
                isPrimary = true,
            )
        } else {
            null
        }

        // 父类 / 父接口子句（阶段 20 起是父接口，阶段 25 起最前面可以有一个父类）：
        // `class Dog(n : string) : Greeter, Runnable { ... }`、
        // `class D(y : int) : Base(y), Greeter { ... }`。
        // 顺序固定为「主构造括号 → ':' 子句 → '{'」（DESIGN-20 §2）。
        val sup = parseSuperClause(nameTok.lexeme)

        expect(TokenKind.LBRACE, "类 '${nameTok.lexeme}' 缺少 '{'")

        val fields = ArrayList<FieldDecl>()
        val methods = ArrayList<FuncDecl>()
        if (primary != null) methods.add(primary)
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            // 注解可以和修饰词混写（`@M public fun f()` / `public @M fun f()`），
            // 所以先收注解再分派；字段/方法各自带走自己的那份表。
            val memberAnnotations = parseAnnotations()
            // 继承修饰词也可以排在这一串里（`final fun f()` / `public final fun f()`）：
            // 先原样数出来，等看清后面是字段还是方法再决定谁去消费它们。
            val memberStart = peek(modifierRun()).kind
            when {
                isFieldDeclAhead() -> fields.add(parseFieldDecl(memberAnnotations))
                // 字段不吃继承修饰词（阶段 25 禁止字段遮蔽，DESIGN-25 §5）：给了准确报错，
                // 而不是让人掉进 parseFuncDecl 撞一句「这里需要一个函数声明」。
                // 只认 open/final/abstract 这三个词——`override var x` 照旧往下走到
                // parseFuncDecl 去撞那句专为它写的「override 只能写在方法前面」（见
                // [isFieldDeclAhead] 的说明）。
                isFieldDeclAheadAfterInherit() -> throw error(
                    peek(),
                    "字段不能写 'final' / 'abstract'：子类不能重复声明父类已有的字段" +
                        "（阶段 25 禁止字段遮蔽，DESIGN-25 §5），所以字段没有「可被覆盖」这一说，" +
                        "把修饰词删掉",
                )
                // `constructor` 以及「修饰词 + constructor」（后者交给 parseConstructor
                // 报「不能写权限修饰词 / 不能写 override」，别掉进 parseFuncDecl
                // 变成一句看不懂的通用错误）。修饰词可以排好几个，所以先把这一串数出来。
                memberStart == TokenKind.KW_CONSTRUCTOR -> {
                    if (memberAnnotations.isNotEmpty()) {
                        throw error(
                            peek(),
                            "构造器上不能写注解（'@'）：v1 的注解只标在类/接口/注解声明/方法/字段上",
                        )
                    }
                    if (peek().kind.isInheritKeyword) parseInheritModifiers(kindWord = "构造器")
                    methods.add(parseConstructor(nameTok.lexeme))
                }
                // 类里的方法：`override` / `open` / `final` / `abstract` 都在这里合法
                // （接口里的方法也能写 override、但 open/final/abstract 有各自的规矩，
                // 见 parseInterfaceDecl 与 parseFuncDecl 的 allowInheritModifiers）
                else -> methods.add(
                    parseFuncDecl(
                        annotations = memberAnnotations,
                        allowOverride = true,
                        allowLeadingAnnotations = true,
                        allowInheritModifiers = true,
                    ),
                )
            }
        }
        expect(TokenKind.RBRACE, "类 '${nameTok.lexeme}' 没有闭合，缺少 '}'")
        return ClassDecl(
            control = control,
            name = nameTok.lexeme,
            fields = fields,
            methods = methods,
            at = classTok.at,
            typeParams = typeParams,
            supertypes = sup.interfaces,
            annotations = annotations,
            superClass = sup.base,
            superArgs = sup.baseArgs,
            isFinal = mods.isFinal,
            isAbstract = mods.isAbstract,
        )
    }

    /**
     * 类声明里的继承修饰词（阶段 25；阶段 27 起只剩 `final` / `abstract`）。
     *
     * 两个词的**顺序不限**（`abstract final class` 写得出来，随后由互斥规则拒掉），但同一个词不能写两遍。
     * 判定放在 Parser 而不是 Checker，是因为这三个词只影响「能不能继承/覆盖」这一件事，
     * 语法上是纯修饰词；能在这里说清就不留到语义阶段兜一圈再报（报错位置也更准：
     * 指到写重了的那个词）。
     *
     * `final` 与 `abstract` 不能同时写（DESIGN-25 §6 / DESIGN-27）：
     * 抽象类本来就是要被继承的，写 `final` 是自相矛盾。
     */
    private fun parseInheritModifiers(kindWord: String): InheritModifiers {
        var final: Token? = null
        var abstract: Token? = null
        while (peek().kind.isInheritKeyword) {
            val tok = advance()
            when (tok.kind) {
                TokenKind.KW_FINAL -> if (final != null) {
                    throw error(tok, "$kindWord 上 'final' 写了两遍——写一次就够了")
                } else {
                    final = tok
                }

                TokenKind.KW_ABSTRACT -> if (abstract != null) {
                    throw error(tok, "$kindWord 上 'abstract' 写了两遍——写一次就够了")
                } else {
                    abstract = tok
                }

                else -> throw IllegalStateException("不是继承修饰词: ${tok.kind}")
            }
        }
        if (abstract != null && final != null) {
            throw error(
                abstract,
                "$kindWord 上 'abstract' 不能和 'final' 写在一起：" +
                    (if (kindWord == "类") "抽象类本来就是要被继承的" else "抽象成员本来就是要被子类实现的") +
                    "（DESIGN-25 §6），写 'abstract' 一个就够了",
            )
        }
        return InheritModifiers(isFinal = final != null, isAbstract = abstract != null)
    }

    /**
     * 类头 `:` 子句（阶段 25）：`[父类(实参) [, 接口]*]`。
     *
     * 三条规则在这里就定死，全部按 DESIGN-25 §2/§3：
     * - **父类必须写实参表**：`class D : Base` 报错，要求写成 `class D : Base(实参)`。
     *   父类可能没有无参构造器，允许省略就得在语义层猜，猜错就是错的字节码；
     * - **父类必须排最前**：`: Greeter, Base(x)` 报错。写在最前面这一条让
     *   「哪一项是父类」一眼可判；
     * - **父类只能有一个**：`: A(x), B(y)` 报错（不做多继承，DESIGN-25 §6）。
     *
     * 判据是**语法**的（「类型引用后面紧跟着 `(`」），最终「这个名字到底是不是一份可以
     * 继承的类」留给语义层（[parseSupertypeList] 与 Checker 会给出准确报错）。
     * 这样父类槽位不必反过来去查符号表。
     */
    private fun parseSuperClause(className: String): SuperClause {
        if (!check(TokenKind.COLON)) return SuperClause(null, emptyList(), emptyList())
        advance() // ':'

        // 第一个名字后面紧跟 '(' → 这是父类（实参表必须写，所以它一定是这个形状）
        val firstIsBase = isBaseAhead()
        if (firstIsBase) {
            val base = parseTypeRef()
            val args = parseSuperArgs(className)
            if (!check(TokenKind.COMMA)) return SuperClause(base, emptyList(), args)
            advance() // ','
            if (isBaseAhead()) {
                throw error(
                    peek(),
                    "类 '$className' 只能有一个父类：'${base.display()}(...)' 后面又出现了带实参表的 " +
                        "'${peek().lexeme}'。父类型列表里**最多一个**带实参的父类，其余都是接口" +
                        "（写法是 ': 父类名(实参), 接口名'），rsf 不做多继承（DESIGN-25 §6）",
                )
            }
            return SuperClause(base, parseSupertypeItems("类 '$className'"), args)
        }

        val interfaces = parseSupertypeItems("类 '$className'", stopAtBase = true)
        if (isBaseAhead()) {
            val baseStart = peek()
            val base = parseTypeRef()
            val args = parseSuperArgs(className)
            throw error(
                baseStart,
                "类 '$className' 的父类 '${base.display()}' 必须排在父类型列表的最前面：写法是 " +
                    "': 父类名(实参), 接口名'，把 '${base.display()}(...)' 挪到最前面",
            )
        }
        return SuperClause(null, interfaces, emptyList())
    }

    /**
     * 下一个记号开始的是不是一个**带了实参表的父类**（`: X(...)`）。
     *
     * 只看两项：IDENT 加上紧跟的 `(`——因为父类的实参表是**必须写的**（DESIGN-25 §2），
     * 「带括号的类型名」在 `:` 子句里只可能是父类。用来做两个判断：它是不是排在第一位，
     * 以及它有没有跑到接口后面去了。
     */
    private fun isBaseAhead(): Boolean {
        if (!check(TokenKind.IDENT)) return false
        var n = 1
        // 泛型父类：实参表写在 `&<类型实参>` **之后**（DESIGN-25 §2）——
        // `class IntBox : Box&<int>(1) { ... }`。所以带 `&<...>` 时要把尖括号对跳过去，
        // 再看后面是不是构造实参表的 '('。
        if (peek(n).kind == TokenKind.AMP) {
            n++
            if (peek(n).kind != TokenKind.LT) return false
            var depth = 0
            while (true) {
                when (peek(n).kind) {
                    TokenKind.EOF -> return false
                    TokenKind.LT -> depth++
                    TokenKind.GT -> {
                        depth--
                        if (depth == 0) {
                            n++
                            break
                        }
                    }
                    else -> {}
                }
                n++
            }
        }
        return peek(n).kind == TokenKind.LPAREN
    }

    /**
     * 父类构造实参表：`Base(实参)`。**必须写**——没有括号直接报错（DESIGN-25 §2）。
     */
    private fun parseSuperArgs(className: String): List<Expr> {
        if (!check(TokenKind.LPAREN)) {
            throw error(
                peek(),
                "类 '$className' 的父类后面必须写出构造实参：写法是 `: 父类名(实参)` 或 `: 父类名()`" +
                    "（父类可能没有无参构造器，所以这一对括号不能省），实际读到 ${peek().describe()}",
            )
        }
        advance() // '('
        val args = ArrayList<Expr>()
        if (!check(TokenKind.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "类 '$className' 的父类构造实参表没有闭合，缺少 ')'")
        return args
    }

    /** 类头 `:` 子句的解析结果：父类、父类实参、父接口。 */
    private class SuperClause(
        val base: TypeRef?,
        val interfaces: List<TypeRef>,
        val baseArgs: List<Expr>,
    )

    /** 继承修饰词的解析结果（[parseInheritModifiers] 的返回值）。 */
    private class InheritModifiers(
        val isFinal: Boolean,
        val isAbstract: Boolean,
    )

    /**
     * 类型参数子句：`class Box&<T>` / `interface Box&<T>`（阶段 20 起两处共用）。
     *
     * 必须写 `&<`：单独的 `<` 是「小于」，不能当类型实参的开头。[kindWord] 是报错文案里的
     * 类别词（"类" / "接口"），[declWord] 是写法示例里的关键字——两边的规则一模一样，
     * 只有这两个词不同，所以抽出来一份，免得将来只改了一边。
     */
    private fun parseTypeParamList(nameTok: Token, kindWord: String, declWord: String): List<String> {
        val typeParams = ArrayList<String>()
        if (check(TokenKind.AMP)) {
            advance() // &
            expect(TokenKind.LT, "'&' 后面缺少 '<'：类型参数要写成 $declWord ${nameTok.lexeme}&<T>")
            do {
                val paramTok = expect(
                    TokenKind.IDENT,
                    "类型参数需要一个名字，写法是 $declWord ${nameTok.lexeme}&<T>",
                )
                if (typeParams.contains(paramTok.lexeme)) {
                    throw error(paramTok, "$kindWord '${nameTok.lexeme}' 的类型参数 '${paramTok.lexeme}' 重名了")
                }
                if (paramTok.lexeme == nameTok.lexeme) {
                    throw error(paramTok, "类型参数不能和${kindWord}同名（都叫 '${nameTok.lexeme}'）")
                }
                typeParams.add(paramTok.lexeme)
            } while (match(TokenKind.COMMA))
            expect(TokenKind.GT, "$kindWord '${nameTok.lexeme}' 的类型参数表没有闭合，缺少 '>'")
        }
        return typeParams
    }

    /**
     * 父类型子句：`class Dog : Greeter, Runnable`、`interface A : Greeter`（阶段 20）。
     *
     * `:` 子句在类和接口两边**语法完全一样**，所以两边共用这一个函数；区别只在语义
     * （Checker 对每个名字查不同的表）。没写 `:` 就是空表——这正是「既有类声明一行都不用改」
     * 的原因（[ClassDecl.supertypes] 缺省空表）。
     *
     * 这里只做语法上能定死的事：至少一个名字、逗号分隔、不许可空、不许内置类型名。
     * 「它到底是接口还是类」「类继承不支持」都留给 Checker——多文件编译时只有 Checker
     * 知道每个声明来自哪个文件，报错才能带对文件名。
     */
    private fun parseSupertypeList(owner: String): List<TypeRef> {
        if (!match(TokenKind.COLON)) return emptyList()
        return parseSupertypeItems(owner)
    }

    /**
     * `:` 后面那一串名字本体（至少一个、逗号分隔）。
     *
     * 和 [parseSupertypeList] 拆开是为了 [parseSuperClause] 复用：类头那个函数自己吃掉了
     * `:`，剩下的一串（父类之后可能还有接口）要按同一套规矩解析——两处各写一遍的话，
     * 「不许内置类型」「不许数组」「不许可空」这几条迟早会走岔。
     */
    private fun parseSupertypeItems(owner: String, stopAtBase: Boolean = false): List<TypeRef> {
        val supertypes = ArrayList<TypeRef>()
        do {
            // 类继承（阶段 25）：父类后面**一定**跟着实参表 `(`，所以在接口列表里
            // 「IDENT 后面紧跟 '('」就是跑到接口后面去的父类——这里先停手，
            // 由 [parseSuperClause] 给出「父类必须排最前面」那句报错。
            if (stopAtBase && isBaseAhead()) break
            val t = peek()
            if (t.kind == TokenKind.QUESTION) {
                throw error(t, "$owner 的父类型不能可空：'?' 只写在变量/参数/返回的类型上")
            }
            if (!t.kind.isTypeKeyword && t.kind != TokenKind.IDENT) {
                throw error(
                    t,
                    "$owner 的父类型列表里需要一个接口名（写法是 '名字, 名字'），" +
                        "实际读到 ${t.describe()}",
                )
            }
            val superType = parseTypeRef()
            if (t.kind.isTypeKeyword) {
                throw error(
                    t,
                    "'${superType.name}' 是内置类型，不能当父类型——父类型只能是接口名" +
                        "（写法是 ': Greeter' 或者 ': Greeter, Runnable'）",
                )
            }
            if (superType.arrayDepth > 0) {
                throw error(t, "父类型不能是数组（'${superType.display()}'）：父类型只能是接口名")
            }
            supertypes.add(superType)
        } while (match(TokenKind.COMMA))
        return supertypes
    }

    /**
     * 接口声明（阶段 20）：
     * ```
     * public interface Greeter {
     *     fun greet(name : string) -> string                   // 抽象方法：只写签名
     *     fun shout(name : string) -> string { rtn ... }        // 默认方法：带方法体
     * }
     * public interface Loud : Greeter, Speaker { }             // 接口继承接口（D4）
     * ```
     *
     * 类头顺序：`[修饰词] interface 名字 [&<T,…>] [ : 父接口列表] { 成员 }`——
     * 接口**没有主构造括号**（不能 `new`，也就没有构造参数可写）。
     *
     * 接口体里有三条硬规则，都在这里拦住，别让它们掉到后面变成一句看不懂的通用报错：
     * - 只能写方法（D3）：`var` / `imutvar` 一律报「接口没有字段」；
     * - 不能写构造器：接口没有构造器，也没有字段要初始化；
     * - 不能写 `static` 方法：本批不支持（抽象静态方法没有调用点，默认静态方法也没有语义）。
     *
     * 方法**可以没有体**（抽象方法）——这是接口与类在语法上唯一的分叉，见
     * [parseFuncDecl] 的 `allowAbstractBody`。
     */
    private fun parseInterfaceDecl(annotations: List<AnnotationUse> = emptyList()): InterfaceDecl {
        var control = ControlType.DEFAULT
        if (peek().kind.isControlKeyword) {
            control = ControlType.of(advance().kind)
        }
        val ifaceTok = expect(TokenKind.KW_INTERFACE, "interface 声明缺少 'interface'")
        val nameTok = expect(TokenKind.IDENT, "'interface' 后面缺少接口名")

        val typeParams = parseTypeParamList(nameTok, kindWord = "接口", declWord = "interface")
        val supertypes = parseSupertypeList("接口 '${nameTok.lexeme}'")

        expect(TokenKind.LBRACE, "接口 '${nameTok.lexeme}' 缺少 '{'")

        val methods = ArrayList<FuncDecl>()
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            // 接口方法也能带注解（注解在**声明**上，不在方法体里），与修饰词顺序任意：
            // 先收注解，再让下面的修饰词数数循环照旧工作。
            val methodAnnotations = parseAnnotations()
            val t = peek()
            if (t.kind.isVarKeyword) {
                throw error(
                    t,
                    "接口里只能写方法：'${nameTok.lexeme}' 里的 '${t.lexeme}' 是字段/变量声明。" +
                        "接口没有字段——值要么由实现类自己存（在类里写 var），" +
                        "要么写成一个方法返回它（fun 名字() -> 类型）",
                )
            }
            if (t.kind == TokenKind.KW_CONSTRUCTOR) {
                throw error(
                    t,
                    "接口 '${nameTok.lexeme}' 里不能写构造器：接口没有构造器" +
                        "（它不会被 new，也没有字段要初始化）",
                )
            }
            // 修饰词可以排好几个、顺序随意（`override public fun` / `public override fun`），
            // 所以先把这一串数完：数的时候顺带做「接口里的方法只能是 public」「不支持 static」
            // 两条检查——它们要落在**真正写了那个词**的位置上（`override private fun` 也得拦住）。
            var offset = 0
            while (peek(offset).kind.isModifierKeyword) {
                val m = peek(offset)
                if (m.kind.isStaticKeyword) {
                    throw error(
                        m,
                        "接口里暂时不支持 static 方法：去掉 'static' 写成普通方法（默认方法或抽象方法）；" +
                            "要能用『类名.方法』直接调的函数，就写在顶层",
                    )
                }
                if (m.kind.isControlKeyword && m.kind != TokenKind.KW_PUBLIC) {
                    throw error(
                        m,
                        "接口里的方法只能是 public：接口就是『给外面看的约定』，" +
                            "'${m.lexeme}' 会让实现类没法实现它（不写修饰词就是 public）",
                    )
                }
                // 阶段 25：接口方法不写 open/final/abstract——接口的方法本来就是
                // 「实现类必须实现」或者「默认实现」两种，没有「能不能被覆盖」这一层。
                if (m.kind.isInheritKeyword) {
                    throw error(
                        m,
                        "接口的方法不能写 '${m.lexeme}'：'final' / 'abstract' 是**类**的规矩" +
                            "（类与方法默认 final，DESIGN-25 §4）。接口的方法只写签名就是抽象方法、" +
                            "写了体就是默认实现，把 '${m.lexeme}' 删掉",
                    )
                }
                offset++
            }
            if (peek(offset).kind != TokenKind.KW_FUN) {
                throw error(
                    t,
                    "接口 '${nameTok.lexeme}' 里只能写方法（格式是 'fun 名字(参数) 返回子句'，可以有方法体），" +
                        "实际读到 ${t.describe()}",
                )
            }
            // 接口里的方法也能写 override：意思是「它必须覆盖到继承来的接口方法」
            methods.add(
                parseFuncDecl(
                    annotations = methodAnnotations,
                    allowAbstractBody = true,
                    allowOverride = true,
                    allowLeadingAnnotations = true,
                ),
            )
            while (match(TokenKind.SEMI)) { /* 可选的分号，吃掉 */ }
        }
        expect(TokenKind.RBRACE, "接口 '${nameTok.lexeme}' 没有闭合，缺少 '}'")

        return InterfaceDecl(
            control = control,
            name = nameTok.lexeme,
            methods = methods,
            supertypes = supertypes,
            at = ifaceTok.at,
            typeParams = typeParams,
            annotations = annotations,
        )
    }

    /**
     * 参数表：`(int a, b : string)`。**两种风格都收，同一个表里可以混写**。
     *
     * - Java 风格：类型在前 —— `int a`、`Point p`、`Box&<int> b`、`int[] xs`、`?string s`
     * - Kotlin 风格：名字在前 —— `a : int`、`p : Point`、`b : Box&<int>`、`xs : int[]`
     *
     * 逐个参数靠**记号前瞻**分流，无歧义：
     * 1. `标识符` 后面跟 `:`  → Kotlin 风格；
     * 2. 类型关键字 / `?`     → Java 风格；
     * 3. `标识符` 后面还跟 `标识符` / `&` / `[` → Java 风格（类名当类型）；
     * 4. 光秃秃一个标识符后面就是 `,` / `)` → 漏了类型，报错里同时给出两种写法。
     *
     * 类型照样交给 [parseTypeRef]，所以泛型、数组、可空两种风格下都自动可用。
     *
     * 函数、主构造、次构造三处共用，省得三份循环各写一遍（参数多了以后
     * 只改一处，不容易出现「函数能写、构造器不能写」这种不一致）。
     */
    private fun parseParamList(missingParen: String): List<Param> {
        expect(TokenKind.LPAREN, missingParen)
        val params = ArrayList<Param>()
        if (!check(TokenKind.RPAREN)) {
            do {
                params.add(parseParam())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "参数表没有闭合，缺少 ')'")
        return params
    }

    /**
     * 单个参数。判定顺序见 [parseParamList] 的说明。
     *
     * [missingParen] 之外唯一的硬规则是「类型必须能看出来」：光写个名字
     * （`fun f(x) -> void`）不猜、不推断——参数类型是给调用方看的，
     * 猜错了要到运行期才发现。
     */
    private fun parseParam(): Param {
        val t = peek()

        // 0. 参数上的注解：v1 的可标位置是「类 / 接口 / 注解声明 / 方法 / 字段」，
        // 参数与局部变量都不在其中（DESIGN-21 §2.3）。这里给一条说清边界的报错，
        // 而不是掉到末尾那句「参数要写成「类型 名字」…」上。
        if (t.kind == TokenKind.AT) {
            throw error(
                t,
                "'@' 不能标在参数上：v1 的注解只标在类 / 接口 / 注解声明 / 方法 / 字段前面" +
                    "（参数与局部变量的注解留到后续阶段），把 '@...' 删掉",
            )
        }

        // 1. Kotlin 风格：名字 : 类型
        if (t.kind == TokenKind.IDENT && peek(1).kind == TokenKind.COLON) {
            val nameTok = advance()
            advance() // :
            val type = parseSuffixType(
                hint = "参数类型要写成 '${nameTok.lexeme} : 类型'（Kotlin 风格）" +
                    "或者 '类型 ${nameTok.lexeme}'（Java 风格）",
            )
            return Param(type, nameTok.lexeme, nameTok.at)
        }

        // 2/3. Java 风格：类型 名字
        val looksLikeJavaType = t.kind.isTypeKeyword || t.kind == TokenKind.QUESTION ||
            (t.kind == TokenKind.IDENT &&
                (peek(1).kind == TokenKind.IDENT ||
                    peek(1).kind == TokenKind.AMP ||
                    peek(1).kind == TokenKind.LBRACKET))
        if (looksLikeJavaType) {
            val typeRef = parseTypeRef()
            val paramName = expect(
                TokenKind.IDENT,
                "参数缺少名字。两种写法都可以：Java 风格 '${typeRef.display()} 名字'，" +
                    "或者 Kotlin 风格 '名字 : ${typeRef.display()}'",
            )
            return Param(typeRef, paramName.lexeme, paramName.at)
        }

        // 4. 光秃秃一个标识符：两种都不是（漏了类型）
        if (t.kind == TokenKind.IDENT &&
            (peek(1).kind == TokenKind.COMMA || peek(1).kind == TokenKind.RPAREN)
        ) {
            throw error(
                t,
                "参数 '${t.lexeme}' 缺少类型。两种写法都可以：Java 风格 'int ${t.lexeme}'，" +
                    "或者 Kotlin 风格 '${t.lexeme} : int'",
            )
        }

        throw error(
            t,
            "参数要写成「类型 名字」或者「名字 : 类型」：Java 风格 'int ${t.lexeme}'、" +
                "Kotlin 风格 '${t.lexeme} : int'，" +
                "实际读到 ${t.describe()}",
        )
    }

    /**
     * `名字 : 类型` 里冒号后面那个类型槽（局部变量、字段、Kotlin 风格参数共用）。
     *
     * 单独包一层是为了**报错说清楚这里是类型**：`var x : 5 = 1` 直接丢给
     * [parseTypeRef] 的话，用户看到的是一句泛泛的「这里需要一个类型」，
     * 不会联想到「类型标注写在名字后面」这条新语法。
     */
    private fun parseSuffixType(hint: String): TypeRef {
        val t = peek()
        if (!t.kind.isTypeKeyword && t.kind != TokenKind.IDENT && t.kind != TokenKind.QUESTION) {
            throw error(t, "$hint，实际读到 ${t.describe()}")
        }
        return parseTypeRef()
    }

    /**
     * 次构造：`constructor(参数) { 语句 }`（阶段 18）。
     *
     * 三条硬规则在这里就报错（不用等到语义阶段）：
     * - 没有返回类型——构造器没有返回值，写了 `->` `/` `::` `/` `:` 直接说清楚；
     * - 没有权限修饰词——构造器只能被 `new` 调用，写了也没有作用，不如不许写；
     * - 不能是 static——构造器一定要有 `this`。
     *
     * 阶段 20 追加：`override` 也不许写——构造器没有「父类型里的同名方法」可覆盖。
     * 体是覆盖不了的东西，写了只会让人以为构造器也在参与覆盖。
     *
     * 体的第一句可以是 `this(实参)`（委托给另一个构造器），由 Checker 校验。
     */
    private fun parseConstructor(className: String, annotations: List<AnnotationUse> = emptyList()): FuncDecl {
        if (annotations.isNotEmpty()) {
            throw error(
                peek(),
                "构造器上不能写注解（'@'）：v1 的注解只标在类/接口/注解声明/方法/字段上",
            )
        }
        // 修饰词要**先**看：它们排在 constructor 前面，所以这时游标还停在修饰词上
        if (peek().kind.isControlKeyword) {
            val m = peek()
            throw error(
                m,
                "constructor 不能写权限修饰词（'${m.lexeme}'）：构造器只能通过 new 调用，" +
                    "修饰词在这里没有作用。直接写 constructor(参数) { ... }",
            )
        }
        if (peek().kind == TokenKind.KW_OVERRIDE) {
            val m = peek()
            throw error(
                m,
                "构造器不能写 override（'${m.lexeme}'）：override 的意思是「覆盖父类型里的方法」，" +
                    "构造器没有父类型方法可覆盖。直接写 constructor(参数) { ... }",
            )
        }
        if (peek().kind.isStaticKeyword) {
            throw error(peek(), "constructor 不能是 static：构造器一定要有当前对象（this）")
        }
        // 阶段 25：构造器也不写 open/final/abstract——构造器不参与继承，
        // 「能不能被覆盖」对构造器没有意义（调用方永远写 new 类名(...)）。
        if (peek().kind.isInheritKeyword) {
            val m = peek()
            throw error(
                m,
                "constructor 不能写 '${m.lexeme}'：'final' / 'abstract' 说的是" +
                    "「能不能被继承/覆盖」，而构造器只能通过 new 类名(...) 调用、不参与覆盖。" +
                    "直接写 constructor(参数) { ... }",
            )
        }

        val tok = advance() // constructor

        val params = parseParamList(
            "constructor 后面缺少 '('，写法是 constructor(参数) { ... }",
        )

        val next = peek()
        if (next.kind == TokenKind.ARROW || next.kind == TokenKind.DOUBLE_COLON ||
            next.kind == TokenKind.COLON
        ) {
            throw error(
                next,
                "constructor 不写返回类型（构造器没有返回值）：去掉 '${next.lexeme}'，" +
                    "直接写 '{'。想让调用方拿到值就用字段或普通方法",
            )
        }

        val body = parseBlock()
        return FuncDecl(
            control = ControlType.DEFAULT,
            mainType = TypeRef("void", tok.at),
            name = className,
            params = params,
            ret = ReturnSpec(TypeRef("void", tok.at), ReturnStyle.OMITTED, false, tok.at),
            body = body,
            at = tok.at,
            isStatic = false,
            isConstructor = true,
            isPrimary = false,
        )
    }

    /**
     * 字段：`var 名字 : 类型 = 初值`（Kotlin 后缀风格，和局部变量一致；初值必填）。
     */
    private fun parseFieldDecl(annotations: List<AnnotationUse> = emptyList()): FieldDecl {
        val kwTok = advance() // var / imutvar
        val mutable = kwTok.kind == TokenKind.KW_VAR
        val nameTok = if (check(TokenKind.IDENT)) {
            advance()
        } else {
            val t = peek()
            val hint = if (t.kind == TokenKind.KW_MATHEMATIC) {
                // 数学类的预留关键字：字段名位置也一样要说清
                "；'mathematic' 是保留关键字（数学类预留），语法还没定，现在还不能当名字用"
            } else if (t.kind.isTypeKeyword) {
                // 老写法 `var int x = 0`：类型写在名字前面已经废弃。
                // 名字就在类型后面那个记号上，所以能把改写后的写法直接写出来。
                val name = if (peek(1).kind == TokenKind.IDENT) peek(1).lexeme else "名字"
                "；类型现在写在**名字后面**：'${kwTok.lexeme} ${t.lexeme} $name = 初值' " +
                    "要写成 '${kwTok.lexeme} $name : ${t.lexeme} = 初值'"
            } else {
                ""
            }
            throw error(t, "字段缺少名字：写法是 '${kwTok.lexeme} 名字 : 类型 = 初值'$hint")
        }
        if (!check(TokenKind.COLON)) {
            val t = peek()
            throw error(
                t,
                "字段必须写类型：'${kwTok.lexeme} ${nameTok.lexeme} : int = 初值'" +
                    "（字段的类型不能靠推断，不然各个方法里看到的类型可能不一致），" +
                    "实际读到 ${t.describe()}",
            )
        }
        advance() // :
        val type = parseSuffixType(
            hint = "字段 '${nameTok.lexeme}' 的类型要写成 '${kwTok.lexeme} ${nameTok.lexeme} : 类型 = 初值'",
        )
        expect(
            TokenKind.ASSIGN,
            "字段 '${nameTok.lexeme}' 必须给初值：" +
                "'${kwTok.lexeme} ${nameTok.lexeme} : ${type.name} = 初值'",
        )
        val init = parseExpr()
        return FieldDecl(
            name = nameTok.lexeme,
            type = type,
            initializer = init,
            mutable = mutable,
            at = kwTok.at,
            annotations = annotations,
        )
    }

    /**
     * 从当前位置往前看，接下来是不是一个字段声明。
     *
     * 类体/伴生实体体的调用方**已经先收过注解**（`@M var x` 里的 `@M`），所以这里只看
     * 落地的记号是不是 `var`/`imutvar`——**不跳修饰词**：字段不写权限修饰词，
     * `override var x` 要照旧掉进 parseFuncDecl 去撞那句「override 只能写在方法前面」，
     * 而不是被当成字段后报一句「字段缺少名字」。
     */
    private fun isFieldDeclAhead(): Boolean = peek().kind.isVarKeyword

    /** [isFieldDeclAhead] 的带继承修饰词版本：`final var x`（阶段 27 后只剩它会走到这里）。 */
    private fun isFieldDeclAheadAfterInherit(): Boolean = peek().kind.isInheritKeyword && run {
        var n = 0
        while (peek(n).kind.isInheritKeyword) n++
        peek(n).kind.isVarKeyword
    }

    // ------------------------------------------------------------------ import

    /**
     * 三种写法：
     * ```
     * import java.lang.Math      // JVM 类（点分路径）
     * import "lib/util.rsf"      // rsf 源文件（字符串路径，可以跨行写）
     * module "lib"               // 目录（该目录下所有 .rsf）
     * ```
     */
    private fun parseImport(): ImportDecl {
        val kw = advance() // import / module
        val isModule = kw.kind == TokenKind.KW_MODULE

        if (check(TokenKind.STRING_LIT)) {
            val t = advance()
            val path = normalizeImportPath(t.literal as String)
            if (path.isEmpty()) {
                throw error(t, "${kw.lexeme} 的路径是空的")
            }
            return if (isModule) ModuleImport(path, kw.at) else SourceImport(path, kw.at)
        }

        if (isModule) {
            throw error(
                peek(),
                "'module' 后面需要一个目录路径，写成 module \"文件夹\"（实际读到 ${peek().describe()}）",
            )
        }

        // 点分路径。最后一段可能是类名，也可能是类里的成员——留到解析阶段再判断
        val first = expect(
            TokenKind.IDENT,
            "${kw.lexeme} 后面需要一个类名（点分路径，例如 java.lang.Math）或者一个字符串路径",
        )
        val sb = StringBuilder(first.lexeme)
        while (match(TokenKind.DOT)) {
            sb.append('.').append(expect(TokenKind.IDENT, "'.' 后面需要标识符").lexeme)
        }
        return ClassImport(sb.toString(), kw.at)
    }

    /** import 路径允许跨行写：逐行去空白，把非空行接起来。 */
    private fun normalizeImportPath(raw: String): String =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("")

    // ------------------------------------------------------------------ 声明

    /**
     * 函数/方法声明：`[修饰词] [static] fun [类型] 名字(参数) 返回子句 函数体`。
     *
     * [allowAbstractBody] 只有**接口体**里传 true（阶段 20）：那时「没有 `{`」不再算错，
     * 而是解析成一个 [FuncDecl.body] == null 的抽象方法（只声明签名）。
     *
     * [allowOverride] 只有**类体/接口体**里传 true（阶段 20 追加）：`override` 的意思是
     * 「这个函数必须真的覆盖到父类型里的方法」，顶层函数没有父类型，所以在顶层直接报错。
     * 修饰词可以按**任意顺序**写（`override public fun` 与 `public override fun` 等价），
     * 所以这一串是一起收的。
     *
     * [allowInheritModifiers] 只有**类体**里传 true（阶段 25 追加，阶段 27 收窄）：那里才允许写
     * `final` / `abstract`（`final fun f()` 封住一个本来可以被覆盖的方法、`abstract fun f()` 只写签名）。
     *
     * 顶层与接口体都不传它，于是那两处的 `final` 会掉进下面 `!isFunKeyword` 那一支，
     * 由专门为「继承修饰词」写的那句提示说清「它只能写在类 / 类方法前面」。
     *
     * 抽象方法（[FuncDecl.isAbstract]）在这里有两处硬规则：
     * - 只有**抽象类**或接口能给「没有体」的方法：写了体就是普通方法，写了 `abstract` 又写体
     *   当场报错（`abstract fun f() { ... }` 自相矛盾，DESIGN-25 §7）；
     * - `abstract` 的签名照样要求写出**返回子句**（`-> void`）——「省略返回子句等于 void」
     *   只对接口默认方法与普通方法成立，抽象方法省了会分不清是哪种（见 body 前面那段）。
     */
    private fun parseFuncDecl(
        allowAbstractBody: Boolean = false,
        allowOverride: Boolean = false,
        annotations: List<AnnotationUse> = emptyList(),
        allowLeadingAnnotations: Boolean = false,
        allowInheritModifiers: Boolean = false,
    ): FuncDecl {
        val leading = ArrayList(annotations)
        // <注解> 与 <ControlType> 与 <override> 与 <static> 与 <open/final/abstract>：
        // 都排在 fun 前面，顺序随意，一起收。注解与修饰词可以混着出现
        // （`@M public fun` / `public @M fun` / `@A @B fun` / `public static @M fun`），
        // 所以每收完一段修饰词都要再看一眼有没有注解，而不是只在一个固定的位置收一轮。
        collectLeadingAnnotations(leading, allowLeadingAnnotations)
        var control = ControlType.DEFAULT
        var isOverride = false
        var inherit: InheritModifiers? = null
        while (true) {
            val m = peek()
            if (m.kind.isControlKeyword) {
                control = ControlType.of(advance().kind)
                continue
            }
            if (consumeOverride(allowOverride)) {
                isOverride = true
                continue
            }
            if (peek().kind.isInheritKeyword && allowInheritModifiers) {
                inherit = mergeInherit(inherit, kindWord = "方法")
                continue
            }
            // 修饰词后面又能接注解（`public @M fun`），所以这里再收一轮
            if (check(TokenKind.AT)) {
                collectLeadingAnnotations(leading, allowLeadingAnnotations)
                continue
            }
            break
        }

        // <static>：排在 <ControlType> 之后、fun 之前，控制「怎么调用」。
        // `static override fun` / `public static @M fun` 也收：修饰词顺序本身没有语义，
        // 没必要让用户因为换个顺序就撞一句看不懂的报错。
        var isStatic = false
        while (true) {
            if (peek().kind.isStaticKeyword) {
                advance()
                isStatic = true
                continue
            }
            if (consumeOverride(allowOverride)) {
                isOverride = true
                continue
            }
            if (peek().kind.isInheritKeyword && allowInheritModifiers) {
                inherit = mergeInherit(inherit, kindWord = "方法")
                continue
            }
            if (check(TokenKind.AT)) {
                collectLeadingAnnotations(leading, allowLeadingAnnotations)
                continue
            }
            break
        }
        if (inherit != null && isStatic) {
            throw error(
                peek(),
                "静态方法不能是 'final' / 'abstract'：静态方法只能写成 '类名.方法名' 调用" +
                    "（static 不参与继承，DESIGN-25 §3），把它上面的继承修饰词删掉",
            )
        }

        if (!peek().kind.isFunKeyword) {
            val t = peek()
            val hint = when {
                t.kind == TokenKind.KW_VOID -> "；注意 'void' 现在写在 fun 后面的 <MainType> 槽位里"
                // lang/example.rsf 那份草稿里到处是 <ControlType>、<MainType> 这种写法，
                // 有人会照着抄。这里直接说清楚：那是占位符，不是语法。
                t.lexeme == "<" ->
                    "；'<...>' 是 lang/example.rsf 草稿里的占位符，不是真的语法：" +
                        "权限修饰词直接写 public / protected / private（都不写就是默认权限），" +
                        "类型槽紧跟在 fun 后面（缺省 void）"
                t.lexeme == "[" ->
                    "；『[...]』是 lang/example.rsf 草稿里的占位符写法，不是声明语法" +
                        "（方括号现在的意思是数组：类型后缀 int[]、下标 a[0]、创建 new int[5]）"
                t.kind == TokenKind.KW_CONSTRUCTOR ->
                    "；'constructor' 是**次构造**，只能写在类体里：constructor(参数) { ... }。" +
                        "主构造写在类头括号里（class P(参数) { ... }）"
                else -> ""
            }
            // `override` 只对方法有意义。写错地方（`override var x`）时，上面那句通用文案
            // 看不出是哪个词的问题，这里补一句专说 override 的话。只有新关键字才会走到这一支，
            // 所以旧程序（一个 override 都没写）的任何报错文案都不受影响。
            val overrideHint = if (isOverride) {
                "；'override' 只能写在方法前面（fun 前）——字段/变量声明没有可覆盖的东西"
            } else {
                ""
            }
            // 阶段 25：`open` / `final` / `abstract` 写在这里只有两种可能——
            // 顶层函数，或者接口体里的方法。两处的规矩与报错都不同，分开说。
            val inheritHint = if (inherit != null || peek().kind.isInheritKeyword) {
                val word = if (inherit != null) {
                    when {
                        inherit.isFinal -> "final"
                        else -> "abstract"
                    }
                } else {
                    peek().lexeme
                }
                "；'$word' 只能写在**类**或者**类里的方法**前面（顶层函数没法被继承，" +
                    "接口的方法本来就是公开的默认实现，DESIGN-25 §4）：把 '$word' 删掉，" +
                    "或者把这段搬到类体里"
            } else {
                ""
            }
            throw error(
                t,
                "顶层只能写函数声明，格式是 '[public|protected|private] [static] fun [类型] 名字() 返回子句 函数体'，" +
                    "实际读到 ${t.describe()}$hint$overrideHint$inheritHint",
            )
        }
        val funTok = advance()

        // 曾经写反的修饰符位置，给个明确提示
        if (peek().kind.isModifierKeyword) {
            val m = peek()
            throw error(m, "修饰符 '${m.lexeme}' 要写在 fun 前面：改成 '${m.lexeme} fun ...'")
        }

        // <MainType>：fun 之后、函数名之前的类型槽，缺省 void
        var mainType = TypeRef("void", funTok.at)
        if (peek().kind.isTypeKeyword) {
            mainType = parseTypeRef()
        }

        val nameTok = if (check(TokenKind.IDENT)) {
            advance()
        } else {
            val t = peek()
            if (mainType.name != "void") {
                // 典型情况：fun double() —— double 被当成了 <MainType>
                throw error(
                    t,
                    "'${mainType.name}' 是类型关键字，不能当函数名（<MainType> 槽位在 fun 之后、" +
                        "函数名之前）。换个名字，例如 fun twice()" +
                        "；返回类型要写在参数表后面：fun 名字(...) -> ${mainType.name}" +
                        "（可能失败就写成 -> !${mainType.name}）",
                )
            }
            throw error(t, "缺少函数名（实际读到 ${t.describe()}）")
        }

        // 泛型**函数**不支持：类型参数只能写在 class 上。看见 `&` 就直说，
        // 否则用户只会收到一句「后面缺少 '('」，猜不到问题在哪。
        if (check(TokenKind.AMP)) {
            throw error(
                peek(),
                "泛型函数还不支持：类型参数只能写在 class 上（class Box&<T> { ... }），" +
                    "函数名后面不能跟 '&<...>'。想要「什么类型都能收」的函数，就为每种类型" +
                    "各写一个重载（阶段 19d 起同名函数按实参类型挑），" +
                    "或者把数据放进一个泛型类里、用它的方法",
            )
        }

        val params = parseParamList("函数名 '${nameTok.lexeme}' 后面缺少 '('")

        // 返回类型子句：`->` 与 `:` 等价（`::` 那条别名已删，见下面的报错）
        val ret: ReturnSpec = when {
            match(TokenKind.ARROW) -> parseReturnSpec(ReturnStyle.ARROW)
            match(TokenKind.COLON) -> parseReturnSpec(ReturnStyle.COLON)
            check(TokenKind.DOUBLE_COLON) -> throw error(
                peek(),
                "返回类型子句不再接受 '::'：请用 '->' 或者 ':'，" +
                    "例如 fun ${nameTok.lexeme}() -> int { ... }。" +
                    "（'::' 现在留给 xxx::xx 这种嵌套写法，例如 Marker::Impl）",
            )
            else -> ReturnSpec(TypeRef("void", funTok.at), ReturnStyle.OMITTED, false, funTok.at)
        }

        // 方法体：三种情况
        // - 接口（allowAbstractBody）：下一个记号不是 '{' 就直接收工，体是 null（阶段 20）；
        // - 类里的 `abstract fun`（阶段 25）：同理，只写签名。**但返回子句必须写出来**，
        //   否则「省略返回子句 = void」这条规则与抽象方法撞在一起就分不清是不是漏写了体；
        // - 其余任何地方都要求有体。
        val body: Block? = if (allowAbstractBody && !check(TokenKind.LBRACE)) {
            null
        } else if (inherit?.isAbstract == true) {
            if (check(TokenKind.LBRACE)) {
                val brace = peek()
                throw error(
                    brace,
                    "抽象方法不能写函数体：'abstract fun ${nameTok.lexeme}(...)' 只写签名，没有 '{ ... }'。" +
                        "要么把 'abstract' 删掉、把体留下，要么把体删掉",
                )
            }
            if (ret.style == ReturnStyle.OMITTED) {
                val t = peek()
                throw error(
                    t,
                    "抽象方法要写出返回子句：'abstract fun ${nameTok.lexeme}(...) -> 返回类型'。" +
                        "（普通方法省略返回子句等于 void，抽象方法省了就分不清「返回 void」" +
                        "与「忘了写体」）实际读到 ${t.describe()}",
                )
            }
            null
        } else {
            parseBlock()
        }
        return FuncDecl(
            control, mainType, nameTok.lexeme, params, ret, body, funTok.at, isStatic,
            isOverride = isOverride,
            isFinal = inherit?.isFinal == true,
            annotations = leading,
        )
    }

    /**
     * 把紧接着的一段继承修饰词收进已有的 [current]（阶段 25；阶段 27 起只剩 `final` / `abstract`）。
     *
     * `fun` 前面允许「修饰词 / 注解 / 修饰词」这么夹着写（`final @M final fun f()`），
     * 所以 [parseFuncDecl] 里的两个收集循环可能会各收一段。两段都合法、但要合起来看：
     * 同一个词出现两次、或者 `final` + `abstract` 这种组合，都必须在**这里**当场报错——
     * 报错位置指到第二个词，比指到整段修饰词有用。
     */
    private fun mergeInherit(current: InheritModifiers?, kindWord: String): InheritModifiers {
        val next = parseInheritModifiers(kindWord)
        if (current == null) return next
        if (current.isFinal && next.isFinal) {
            throw error(peek(), "$kindWord 上 'final' 写了两遍——写一次就够了")
        }
        if (current.isAbstract && next.isAbstract) {
            throw error(peek(), "$kindWord 上 'abstract' 写了两遍——写一次就够了")
        }
        val isFinal = current.isFinal || next.isFinal
        val isAbstract = current.isAbstract || next.isAbstract
        if (isAbstract && isFinal) {
            throw error(
                peek(),
                "$kindWord 上 'abstract' 不能和 'final' 写在一起（DESIGN-25 §6 / DESIGN-27），" +
                    "写 'abstract' 一个就够了",
            )
        }
        return InheritModifiers(isFinal = isFinal, isAbstract = isAbstract)
    }

    /**
     * `fun`/`constructor` 前面那串修饰词有几个（`public` / `private` / `protected` /
     * `static` / `override` 任意顺序都算）。只数不消费——调用方拿这个偏移去 `peek(n)`
     * 看后面跟着的是不是它要的那个声明（类体里要靠它把「修饰词 + constructor」认出来）。
     *
     * [extra] 是从当前位置再往前看的偏移量（阶段 21）：调用方**已经收过注解**时传 0 即可，
     * 只有「当前位置之前还夹着若干未消费的记号」才需要非 0。
     */
    private fun modifierRun(extra: Int = 0): Int {
        var n = extra
        while (peek(n).kind.isModifierKeyword) n++
        return n
    }

    /**
     * 收下一段声明前的注解（`@M` / `@M("hi")` / `@M(value = "hi")`）。
     *
     * [allowLeadingAnnotations] 为 false 的位置（参数、语句里）直接给出说清边界的报错。
     * 抽成一个函数是因为 `fun` 前面的注解与修饰词**顺序任意**：每收完一段修饰词都要再看一眼。
     */
    private fun collectLeadingAnnotations(leading: ArrayList<AnnotationUse>, allowLeadingAnnotations: Boolean) {
        while (check(TokenKind.AT)) {
            if (!allowLeadingAnnotations) {
                val t = peek()
                throw error(
                    t,
                    "注解要写在声明前面（v1 只支持标在类/接口/注解声明/方法/字段上）：" +
                        "这里不能出现 '@'（实际读到 ${t.describe()}）",
                )
            }
            leading.add(parseAnnotationUse())
        }
    }

    /**
     * 看见 `override` 就收掉一个，并回答「到底收没收到」。
     *
     * `override` **只对类/接口里的方法有意义**（意思是「必须真的覆盖到父类型里的方法」），
     * 所以顶层函数写了它要当场说清楚——否则会掉进下面那句「顶层只能写函数声明」，
     * 让人猜不到是哪个词出了问题。真正的覆盖检查在 Checker 里做（只有它看得见父类型）。
     */
    private fun consumeOverride(allowed: Boolean): Boolean {
        val m = peek()
        if (m.kind != TokenKind.KW_OVERRIDE) return false
        if (!allowed) {
            throw error(
                m,
                "只有类/接口里的方法能写 override：顶层函数没有父类型方法可覆盖，" +
                    "去掉 'override' 直接写 fun ...",
            )
        }
        advance()
        return true
    }

    private fun parseReturnSpec(style: ReturnStyle): ReturnSpec {
        val at = peek().at
        val mayError = match(TokenKind.BANG)
        if (check(TokenKind.KW_NULL)) {
            advance()
            return ReturnSpec(TypeRef("void", at), style, mayError, at)
        }
        // 可空性由 parseTypeRef 处理（`?int`）
        val type = parseTypeRef()
        return ReturnSpec(type, style, mayError, at)
    }

    /**
     * 类型引用，支持 `?` 前缀表示可空、**用户自定义类名**（阶段 12）、
     * 泛型实参 `X&<T>`（阶段 15），以及**数组后缀 `[]`**（阶段 13）。
     *
     * 类名是标识符，跟类型关键字长不到一块去，所以先认关键字、再认标识符即可，
     * 不会有歧义（`parseVarDecl` 那种「可选类型标注」的位置另有前瞻处理）。
     *
     * **类型实参必须写成 `&<...>`**：单独的 `<` 是「小于」比较，`[]` 是数组，
     * `<ControlType>` 那种尖括号是 lang/example.rsf 草稿里的类型槽占位符——
     * 带上 `&` 才能一眼跟它们区分开。
     *
     * 类型实参是**递归**解析的，所以 `Box&<Box&<int>>` 是嵌套（词法上是 `>` `>`，
     * 不是移位——rsf 里没有移位运算符，两个 `>` 各归各的类型实参表）。
     *
     * 数组后缀写在**最后**（C 风格）：`Box&<int>[]`、`int[][]`、`Point[]`。
     * 顺序是「先泛型实参、后 `[]`」——`Box&<int>[]` 意思是「装着 `int` 的 Box 的数组」，
     * 而不是「装着 `int[]` 的 Box」（那个要写 `Box&<int[]>`）。`[]` 只吃**紧挨着的**
     * 一对空括号：`new int[5]` 里的 `[5]` 是维度、不是类型后缀，所以这里要求
     * `[` 后面紧跟 `]`，`[5]` 会原样留给 `new` 那段去解析。
     */
    private fun parseTypeRef(): TypeRef {
        val nullable = match(TokenKind.QUESTION)
        val t = peek()
        val name = when {
            t.kind.isTypeKeyword -> {
                advance()
                canonicalTypeName(t.kind)
            }
            t.kind == TokenKind.IDENT -> {
                advance()
                t.lexeme
            }
            else -> throw error(
                t,
                "这里需要一个类型（void / int / long / float / double / string / bool / " +
                    "byte / char，或者一个类名），" +
                    "实际是 ${t.describe()}",
            )
        }
        var base = TypeRef(if (nullable) "?$name" else name, t.at)

        // 嵌套类型写法（阶段 21 M1）：`注解名 :: 伴生名`，例如 `Marker::Impl`。
        // v1 **只支持一层**（DESIGN-21.md §12.3）：写完一对再看一眼，还有 `::` 就报错。
        // name 留 **owner**（`Marker`），伴生名放 nested（`Impl`）——语义层拼内部名 `Marker$Impl`。
        if (check(TokenKind.DOUBLE_COLON)) {
            if (t.kind.isTypeKeyword) {
                throw error(
                    peek(),
                    "'$name' 是内置类型，没有嵌套类型：'::' 只能写成「注解名 :: 伴生名」" +
                        "（例如 Marker::Impl）",
                )
            }
            advance() // ::
            val innerTok = expect(
                TokenKind.IDENT,
                "'::' 后面缺少嵌套类型名：写法是「注解名 :: 伴生名」（例如 Marker::Impl）",
            )
            base = base.copy(nested = innerTok.lexeme)
            if (check(TokenKind.DOUBLE_COLON)) {
                val extra = peek()
                throw error(
                    extra,
                    "嵌套类型 v1 只支持一层：'${base.display()}::...' 里的第二个 '::' 不支持" +
                        "（写法是「注解名 :: 伴生名」，例如 Marker::Impl）",
                )
            }
        }

        if (check(TokenKind.AMP)) {
            if (t.kind.isTypeKeyword) {
                throw error(
                    peek(),
                    "'$name' 是内置类型，没有类型参数——'&<实参类型>' 只能跟在一个类名后面" +
                        "（例如 Box&<int>）",
                )
            }
            base = base.copy(typeArgs = parseTypeArgs())
        }

        // 数组后缀，可以叠多层：int[] / int[][] / Box&<int>[]
        var depth = 0
        while (check(TokenKind.LBRACKET) && peek(1).kind == TokenKind.RBRACKET) {
            advance() // [
            advance() // ]
            depth++
        }
        if (depth > 0 && base.name.startsWith("?")) {
            throw error(
                t,
                "数组类型不能写 '?'（'?${name}[]' 不支持）：数组本身就是引用，" +
                    "元素默认值天然是 0 / false / null，" +
                    "而「元素可空」还没有语义。去掉 '?' 写成 '$name[]'",
            )
        }
        return if (depth == 0) base else base.copy(arrayDepth = depth)
    }

    /**
     * 类型实参表：`&<int>` / `&<string, int>` / `&<Box&<int>>`。
     *
     * 开头的 `&` 和 `<` 必须挨着——这正是「不会跟比较运算打架」的原因。
     */
    private fun parseTypeArgs(): List<TypeRef> {
        advance() // &
        expect(TokenKind.LT, "'&' 后面缺少 '<'：类型实参要写成 '&<实参类型>'（单独的 '<' 是比较运算）")
        val args = ArrayList<TypeRef>()
        do {
            args.add(parseTypeRef())
        } while (match(TokenKind.COMMA))
        expect(TokenKind.GT, "类型实参表没有闭合，缺少 '>'")
        return args
    }

    /**
     * `new` 表达式的两种形态（阶段 13 起）：
     *
     * ```
     * new Point(1, 2)          对象：类名 + 实参表
     * new Box&<int>(0)        泛型对象
     * new int[5]               数组：类型 + 每一维的长度（**运行时求值**）
     * new Point[2]             引用类型数组
     * new int[3][4]            多维：按「数组的数组」处理
     * ```
     *
     * 两条路靠**类型后面跟的是 `(` 还是 `[`** 区分，没有歧义。
     * 这里直接把类型整段交给 [parseTypeRef]：`new int[5]` 里的 `[5]` 不是 `[]`
     * 类型后缀（那里要求 `[` 紧跟 `]`），所以会原样留下来，由下面那轮循环吃。
     */
    private fun parseNewExpr(at: SourceLine): Expr {
        val t = peek()
        if (!t.kind.isTypeKeyword && t.kind != TokenKind.IDENT) {
            throw error(t, "'new' 后面需要类名（new Point(1, 2)）或者数组类型（new int[5]）")
        }
        val typeRef = parseTypeRef()
        if (typeRef.name.startsWith("?")) {
            throw error(t, "'new' 后面的类型不能写 '?'（'?${typeRef.name.substring(1)}' 不是可 new 的类型）")
        }

        // 数组：一维或多维，每一维都必须给长度
        if (check(TokenKind.LBRACKET)) {
            val dims = ArrayList<Expr>()
            while (match(TokenKind.LBRACKET)) {
                if (check(TokenKind.RBRACKET)) {
                    throw error(
                        peek(),
                        "数组创建必须给出每一维的长度：new ${typeRef.display()}[n]" +
                            "（空的 '[]' 只能写在**类型**里；本阶段不做 new int[3][] 这种锯齿数组）",
                    )
                }
                dims.add(parseExpr())
                expect(TokenKind.RBRACKET, "'new ${typeRef.display()}[...]' 缺少 ']'")
            }
            if (typeRef.arrayDepth > 0) {
                throw error(t, "new 后面的数组类型不能再写 '[]'：长度写在方括号里，例如 new int[5]")
            }
            return NewArrayExpr(typeRef, dims, at)
        }

        if (t.kind.isTypeKeyword) {
            throw error(
                t,
                "'${typeRef.name}' 是内置类型，不能 new（没有构造器）。" +
                    "要造数组写成 new ${typeRef.name}[长度]，例如 new ${typeRef.name}[5]",
            )
        }
        if (typeRef.arrayDepth > 0) {
            throw error(
                t,
                "数组类型要用 'new ${typeRef.name}[长度]' 创建（长度写在方括号里）",
            )
        }
        expect(TokenKind.LPAREN, "'new ${typeRef.display()}' 后面缺少 '('（对象要写实参表，数组要写长度）")
        val args = ArrayList<Expr>()
        if (!check(TokenKind.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "'new ${typeRef.display()}' 的参数表没有闭合，缺少 ')'")
        return NewExpr(typeRef.name, args, at, typeRef.typeArgs)
    }

    private fun canonicalTypeName(kind: TokenKind): String = when (kind) {
        TokenKind.KW_VOID -> "void"
        TokenKind.KW_INT -> "int"
        TokenKind.KW_LONG -> "long"
        TokenKind.KW_FLOAT -> "float"
        TokenKind.KW_DOUBLE -> "double"
        TokenKind.KW_STRING -> "string"
        TokenKind.KW_BOOL -> "bool"
        TokenKind.KW_BYTE -> "byte"
        TokenKind.KW_CHAR -> "char"
        else -> throw IllegalStateException("不是类型关键字: $kind")
    }

    // ------------------------------------------------------------------ 语句

    private fun parseBlock(): Block {
        val brace = expect(TokenKind.LBRACE, "函数体需要以 '{' 开始")
        val statements = ArrayList<Stmt>()
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            statements.add(parseStmt())
            while (match(TokenKind.SEMI)) { /* 可选的分号，吃掉 */ }
        }
        expect(TokenKind.RBRACE, "函数体没有闭合，缺少 '}'")
        return Block(statements, brace.at)
    }

    private fun parseStmt(): Stmt {
        val t = peek()
        return when {
            t.kind == TokenKind.LBRACE -> parseBlock()

            t.kind.isVarKeyword -> parseVarDecl()

            t.kind == TokenKind.KW_LET -> throw error(
                t,
                "'let' 已经换成两个关键字了：'var'（可变）和 'imutvar'（不可变）",
            )

            t.kind.isReturnKeyword -> parseReturn()

            t.kind == TokenKind.KW_IF -> parseIfStmt()

            t.kind == TokenKind.KW_ELSE -> throw error(
                t,
                "'else' 要跟在 if 的分支后面，不能单独成句",
            )

            t.kind == TokenKind.KW_WHILE -> {
                val whileTok = advance()
                val condition = parseExpr()
                WhileStmt(condition, parseBody("while"), whileTok.at)
            }

            t.kind == TokenKind.KW_FOR -> parseFor()

            t.kind == TokenKind.KW_SWITCH -> parseSwitch()

            // `case` / 单独一个 else 跑到这里都是「位置不对」，给出的提示要说清它该待在哪
            t.kind == TokenKind.KW_CASE -> throw error(
                t,
                "'case' 只能写在 switch 的块里（而且从 0.9.0 起可以不写了）：" +
                    "写法是 switch (值) { 枚举名.值名 : 语句 ... }",
            )

            t.kind == TokenKind.KW_BREAK -> {
                advance()
                BreakStmt(t.at)
            }

            t.kind == TokenKind.KW_CONTINUE -> {
                advance()
                ContinueStmt(t.at)
            }

            t.kind == TokenKind.KW_GOTO -> {
                advance()
                val label = expect(TokenKind.IDENT, "'goto' 后面缺少标签名")
                GotoStmt(label.lexeme, t.at)
            }

            t.kind == TokenKind.KW_FAIL -> parseFail()

            t.kind == TokenKind.KW_TRY -> parseTry()

            // `this(实参)`：构造器委托（阶段 18）。必须排在成员访问前面判断——
            // `this(...)` 和 `this.字段` 都从 this 开头，靠后面跟的是 '(' 还是 '.' 区分。
            t.kind == TokenKind.KW_THIS && peek(1).kind == TokenKind.LPAREN -> parseThisCall()

            // `super(实参)`（阶段 25）：**父类构造器委托**，和 this(...) 是一对，
            // 也只能当构造器体的第一句（Checker 校验）。靠后面跟的是 '(' 而不是 '.'
            // 来区分它和 `super.方法()`——后者是表达式语句，走下面那一支。
            t.kind == TokenKind.KW_SUPER && peek(1).kind == TokenKind.LPAREN -> parseSuperCall()

            t.kind == TokenKind.KW_CONSTRUCTOR -> throw error(
                t,
                "'constructor' 只能写在类体里（它是次构造：constructor(参数) { ... }）",
            )

            t.kind == TokenKind.KW_CATCH -> throw error(
                t,
                "'catch' 只能跟在 try 块后面，写法是 try { ... } catch (e : string) { ... }" +
                    "（也接受老写法 catch (e string)）",
            )

            // 局部变量上的注解：v1 只标在声明（类 / 接口 / 注解声明 / 方法 / 字段）前面，
            // 语句里的 '@' 给一条说清边界的报错（DESIGN-21 §2.3）。
            t.kind == TokenKind.AT -> throw error(
                t,
                "'@' 不能标在语句里（局部变量）：v1 的注解只标在类 / 接口 / 注解声明 / 方法 / 字段前面" +
                    "（参数与局部变量的注解留到后续阶段），把 '@...' 删掉",
            )

            t.kind == TokenKind.IDENT && peek(1).kind == TokenKind.ASSIGN -> parseAssign()

            t.kind == TokenKind.IDENT && peek(1).kind == TokenKind.COLON -> {
                advance()
                advance() // ':'
                LabelStmt(t.lexeme, t.at)
            }

            else -> finishExprStmt(parseExpr())
        }
    }

    /**
     * 表达式语句，顺带认出**两种赋值目标**（它们在语法上都是后缀表达式）：
     *
     * - `p.x = 1` / `this.x = 1` —— 成员赋值（阶段 12）
     * - `a[i] = 1` / `p.items[0] = 1` —— 下标赋值（阶段 13）
     *
     * 做法是「先按表达式解析到底，再看后面跟不跟 `=`」：这样接收者可以任意复杂
     * （`a.b.c = 1`、`new P().x = 1`、`f()[0] = 1`），不用在语法里给左值单开一条产生式。
     * 其余情形就是普通表达式语句。
     */
    private fun finishExprStmt(e: Expr): Stmt {
        if (check(TokenKind.ASSIGN)) {
            when (e) {
                is MemberExpr -> {
                    advance() // '='
                    return MemberAssignStmt(e, parseExpr(), e.at)
                }
                is IndexExpr -> {
                    advance() // '='
                    return IndexAssignStmt(e, parseExpr(), e.at)
                }
                else -> {}
            }
        }
        return ExprStmt(e, e.at)
    }

    /**
     * `this(实参)` —— 构造器委托（阶段 18）。
     *
     * 只能当构造器体的**第一句**（Checker 校验），语义是「先跑另一个构造器，
     * 回来再跑我剩下的语句」，和 Java 的 alternate constructor invocation 一样。
     */
    private fun parseThisCall(): Stmt {
        val tok = advance() // this
        expect(TokenKind.LPAREN, "'this' 后面需要 '('（构造器委托写成 this(实参)）")
        val args = ArrayList<Expr>()
        if (!check(TokenKind.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "this(...) 的参数表没有闭合，缺少 ')'")
        return ThisCallStmt(args, tok.at)
    }

    /**
     * `super(实参)` —— **父类构造器委托**（阶段 25）。
     *
     * 和 [parseThisCall] 同形、同规矩：只能当构造器体的**第一句**，而且与 `this(...)`
     * 互斥（两个都由 Checker 校验，报错位置在那边才看得到「这是第几句」）。
     * 语法上只做一件事：把实参表收成一个 [SuperCallStmt]。
     */
    private fun parseSuperCall(): Stmt {
        val tok = advance() // super
        expect(TokenKind.LPAREN, "'super' 后面需要 '('（父类构造器委托写成 super(实参)）")
        val args = ArrayList<Expr>()
        if (!check(TokenKind.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "super(...) 的参数表没有闭合，缺少 ')'")
        return SuperCallStmt(args, tok.at)
    }

    /**
     * `if` 的三种写法共用一个入口；三种都能跟 `else`（三目本身已经带了 else 分支）。 */
    private fun parseIfStmt(): Stmt {
        val ifTok = advance()
        val condition = parseExpr()
        if (check(TokenKind.QUESTION)) {
            // 三目形式出现在语句位置：值被丢弃
            val expr = parseIfTail(condition, ifTok.at)
            return ExprStmt(expr, ifTok.at)
        }

        val body = parseBody("if")
        val elseBody = if (match(TokenKind.KW_ELSE)) parseElseBody() else null
        return IfStmt(condition, body, elseBody, ifTok.at)
    }

    /** `else` 后面：另一个 `if`（形成 else-if 链），或者一个普通 body。 */
    private fun parseElseBody(): Block {
        val ifTok = peek()
        if (ifTok.kind == TokenKind.KW_IF) {
            val nested = parseIfStmt()
            return Block(listOf(nested), nested.at)
        }
        return parseBody("else")
    }

    /** if / while 的循环体：花括号块，或者冒号后面的单条语句。 */
    private fun parseBody(what: String): Block = when {
        check(TokenKind.LBRACE) -> parseBlock()

        check(TokenKind.COLON) -> {
            advance()
            val single = parseStmt()
            Block(listOf(single), single.at)
        }

        else -> throw error(
            peek(),
            "'$what' 后面需要 '{' 开始一个块，或者 ':' 后面跟单条语句，实际读到 ${peek().describe()}",
        )
    }

    /**
     * 枚举分派（阶段 19c）：
     * ```
     * switch (kind) {
     *     case TokenKind.IDENT : println("标识符")
     *     case TokenKind.INT_LIT : {
     *         var n = parseInt(text) ?: 0
     *         println("数字 " + n)
     *     }
     *     else : println("其它")
     * }
     * ```
     *
     * 语法上的几个决定：
     * - 主体的括号**必须写**（`switch kind {` 不收）：跟 `if` / `while` 一致，
     *   而且没有括号的话 `switch a.b { ... }` 里的 `{` 到底属于谁要靠猜；
     * - 分支体复用 [parseBody]：`case X : 单条语句` 或者 `case X : { 多句 }`，
     *   和 `if c : 语句` 是同一套规则（单条语句在这里也被包成一个 [Block]，
     *   于是下游只有一种「分支体」形状）；
     * - `case` 的值只收 `枚举名.值名` 两个标识符加点号，**不吃表达式**：
     *   如果这里允许任意表达式，`case TokenKind.IDENT` 与 `case f(x)` 就得靠
     *   语义分析区分，而「只允许枚举常量」这条规则本来就该在语法上说清楚。
     *   写了别的形状（字面量、`default`、区间）都在这里直接报错并给出正确写法。
     */
    private fun parseSwitch(): Stmt {
        val switchTok = advance() // switch
        expect(TokenKind.LPAREN, "'switch' 后面需要 '('（写法是 switch (值) { ... }）")
        val subject = parseExpr()
        expect(TokenKind.RPAREN, "switch 的条件没有闭合，缺少 ')'")
        expect(TokenKind.LBRACE, "switch 缺少 '{'")

        val cases = ArrayList<SwitchCase>()
        var elseBody: Block? = null
        var sawElse = false

        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            // 可选的分号：多写几个空语句不算错
            if (match(TokenKind.SEMI)) continue

            if (check(TokenKind.KW_ELSE)) {
                val elseTok = advance()
                if (sawElse) throw error(elseTok, "switch 里只能有一个 'else' 分支")
                sawElse = true
                elseBody = parseBody("else")
                continue
            }

            if (check(TokenKind.IDENT) && peek().lexeme == "default") {
                throw error(
                    peek(),
                    "rsf 的默认分支写成 'else :'（不是 Java 的 'default :'）——" +
                        "语言里已经有一个「都不是就走这里」的关键字了，不用再记一个 'default'",
                )
            }

            // 阶段 21：`case` 弃用——分支直接写「枚举名.值名 : 语句」，省掉一个关键字。
            // 旧写法先照收（位置记进 AST），由 Checker 统一发一条弃用警告。
            val caseTok = if (check(TokenKind.KW_CASE)) advance() else null
            if (!check(TokenKind.IDENT)) {
                throw error(
                    peek(),
                    if (caseTok != null) {
                        "'case' 后面需要一个枚举名（现在可以不写 'case'，直接写「枚举名.值名 : 语句」）"
                    } else {
                        "switch 的块里只能写 '枚举名.值名 : 语句' 或者 'else : 语句'，" +
                            "实际读到 ${peek().describe()}"
                    },
                )
            }
            val enumTok = advance()
            expect(
                TokenKind.DOT,
                "'${enumTok.lexeme}' 后面需要 '.'——分支只接受枚举常量，" +
                    "写法是 ${enumTok.lexeme}.值名（不支持区间、比较、字面量）",
            )
            val valueTok = expect(
                TokenKind.IDENT,
                "'${enumTok.lexeme}.' 后面需要一个值名（写法是 ${enumTok.lexeme}.值名）",
            )
            val body = parseBody("case")
            cases.add(
                SwitchCase(
                    enumTok.lexeme,
                    valueTok.lexeme,
                    body,
                    (caseTok ?: enumTok).at,
                    caseTok?.at,
                ),
            )
        }
        expect(TokenKind.RBRACE, "switch 没有闭合，缺少 '}'")

        if (cases.isEmpty() && elseBody == null) {
            throw error(
                switchTok,
                "switch 里什么都没有：至少要有一个 '枚举名.值名 : 语句' 分支" +
                    "（想全部落到一个分支就写 else : ...）",
            )
        }
        return SwitchStmt(subject, cases, elseBody, switchTok.at)
    }

    /** 三目的后半截：`? 值1 : 值2`。 */
    private fun parseIfTail(condition: Expr, at: SourceLine): IfExpr {        expect(TokenKind.QUESTION, "三目运算符需要 '?'，写法是 'if 条件 ? 值1 : 值2'")
        val whenTrue = parseExpr()
        expect(TokenKind.COLON, "三目运算符缺少 ':'（写法是 'if 条件 ? 值1 : 值2'）")
        val whenFalse = parseExpr()
        return IfExpr(condition, whenTrue, whenFalse, at)
    }

    // ------------------------------------------------------------------ for

    /**
     * 两种写法：
     * ```
     * for (var i = 0; i < 9; i = i + 1) { ... }   // Java 式
     * for (i in 0..9 step 2) { ... }                // Kotlin 式
     * ```
     * 都在这里解糖成 `Block(初始化...) { ForStmt(条件, 更新, 循环体) }`。
     */
    private fun parseFor(): Stmt {
        val forTok = advance() // for
        expect(TokenKind.LPAREN, "'for' 后面需要 '('")

        if (peek().kind == TokenKind.IDENT && peek(1).kind == TokenKind.KW_IN) {
            return parseForIn(forTok)
        }
        return parseForClassic(forTok)
    }

    private fun parseForClassic(forTok: Token): Stmt {
        val init = ArrayList<Stmt>()
        if (!check(TokenKind.SEMI)) {
            do {
                init.add(parseForClauseStmt())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.SEMI, "for 的初始化部分后面需要 ';'")

        val condition = if (check(TokenKind.SEMI)) null else parseExpr()
        expect(TokenKind.SEMI, "for 的条件部分后面需要 ';'")

        val step = ArrayList<Stmt>()
        if (!check(TokenKind.RPAREN)) {
            do {
                step.add(parseForClauseStmt())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "for 的头部没有闭合，缺少 ')'")

        val body = parseBody("for")
        val stepStmt: Stmt? = when (step.size) {
            0 -> null
            1 -> step[0]
            else -> Block(step.toList(), forTok.at)
        }
        val loop = ForStmt(condition, stepStmt, body, forTok.at)
        // 初始化语句和循环本体一起放进块里，初始化变量就只在循环内可见
        return if (init.isEmpty()) loop else Block(init.toList() + loop, forTok.at)
    }

    /** for 头部里的一小节：变量声明、赋值、或者表达式。 */
    private fun parseForClauseStmt(): Stmt {
        val t = peek()
        return when {
            t.kind.isVarKeyword -> parseVarDecl()
            t.kind == TokenKind.KW_LET -> throw error(
                t, "'let' 已经换成两个关键字了：'var'（可变）和 'imutvar'（不可变）",
            )
            t.kind == TokenKind.IDENT && peek(1).kind == TokenKind.ASSIGN -> parseAssign()
            // `a[i] = x` / `p.x = 1` 这种后缀目标：交给和语句位置同一套收尾逻辑
            else -> finishExprStmt(parseExpr())
        }
    }

    /**
     * `for (i in 起点..终点)` 与 `for (i in 起点..终点 step 步长)`。
     *
     * 解糖（`k` 是保证不重名的计数器）：
     * ```
     * {
     *     imutvar __rsf_forEnd<k> = 终点
     *     imutvar __rsf_forStep<k> = 步长
     *     imutvar i = 起点
     *     for (if __rsf_forStep<k> >= 0 ? i <= __rsf_forEnd<k> : i >= __rsf_forEnd<k> ; i = i + __rsf_forStep<k>) 循环体
     * }
     * ```
     * 终点与步长都只求值一次（和 Java / Kotlin 一致）。步长是负数就自动变成递减，
     * 所以 `for (i in 10..0 step -1)` 也能用。
     */
    private fun parseForIn(forTok: Token): Stmt {
        val nameTok = advance() // IDENT
        advance() // in

        val start = parseExpr()
        // 数组**不能**这样遍历（阶段 13 明确不做 `for (x in arr)`），所以这句提示
        // 顺带把「数组要怎么写循环」说出来，省得用户对着区间语法猜
        expect(
            TokenKind.RANGE,
            "for-in 需要一个区间，写法是 'for (${nameTok.lexeme} in 起点..终点)'。" +
                "数组还不能用 for-in 遍历，按下标写：" +
                "for (var i = 0; i < 数组.length; i = i + 1)",
        )
        val end = parseExpr()

        // step 是上下文关键字：只有出现在这里才当关键字，别处照样能叫 step
        val stepExpr: Expr = if (peek().kind == TokenKind.IDENT && peek().lexeme == "step") {
            advance()
            parseExpr()
        } else {
            IntLit(1, forTok.at)
        }

        expect(TokenKind.RPAREN, "for 的头部没有闭合，缺少 ')'")
        val body = parseBody("for")

        val k = synthCounter++
        val endName = "__rsf_forEnd$k"
        val stepName = "__rsf_forStep$k"
        fun ref(n: String) = VarExpr(n, nameTok.at)

        val endDecl = VarDeclStmt(endName, end, mutable = false, at = forTok.at)
        val stepDecl = VarDeclStmt(stepName, stepExpr, mutable = false, at = forTok.at)
        val loopVar = VarDeclStmt(nameTok.lexeme, start, mutable = false, at = nameTok.at)

        // 条件：步长非负就 i <= 终点，否则 i >= 终点
        val nonNegative = BinaryExpr(
            TokenKind.GE, ref(stepName), IntLit(0, forTok.at), forTok.at,
        )
        val ascending = BinaryExpr(TokenKind.LE, ref(nameTok.lexeme), ref(endName), forTok.at)
        val descending = BinaryExpr(TokenKind.GE, ref(nameTok.lexeme), ref(endName), forTok.at)
        val condition = IfExpr(nonNegative, ascending, descending, forTok.at)

        val advanceStep = AssignStmt(
            nameTok.lexeme,
            BinaryExpr(TokenKind.PLUS, ref(nameTok.lexeme), ref(stepName), forTok.at),
            forTok.at,
            synthetic = true,
        )

        return Block(
            listOf(endDecl, stepDecl, loopVar, ForStmt(condition, advanceStep, body, forTok.at)),
            forTok.at,
        )
    }

    /**
     * 变量声明：`var 名字 : 类型 = 初值`（Kotlin 后缀风格）。
     *
     * 类型可以省（`var x = 0`，靠初值推断）；写了就写在**名字后面**。
     * 老写法 `var int x = 0` 不再接受，报错里直接给出新写法——
     * 这是纯语法迁移里最容易被漏掉的一处。
     */
    private fun parseVarDecl(): Stmt {
        val kwTok = advance() // var / imutvar
        val mutable = kwTok.kind == TokenKind.KW_VAR
        val nameTok = if (check(TokenKind.IDENT)) {
            advance()
        } else {
            val t = peek()
            val hint = if (t.kind == TokenKind.KW_MATHEMATIC) {
                // 数学类的预留关键字（语法还没定）：这里要说清它不是「少写了名字」
                "；'mathematic' 是保留关键字（数学类预留），语法还没定，现在还不能当名字用"
            } else if (t.kind.isTypeKeyword) {
                // 老写法 `var int x = 0`：类型写在名字前面已经废弃
                val name = if (peek(1).kind == TokenKind.IDENT) peek(1).lexeme else "名字"
                "；类型现在写在**名字后面**：'${kwTok.lexeme} ${t.lexeme} $name = 初值' " +
                    "要写成 '${kwTok.lexeme} $name : ${t.lexeme} = 初值'"
            } else {
                ""
            }
            throw error(t, "'${kwTok.lexeme}' 后面缺少变量名$hint")
        }
        // 可选的类型标注：`var x : int = 0` / `var s : ?string = null` /
        // `var b : Box&<int> = ...` / `var a : int[] = new int[3]`
        val declaredType = if (match(TokenKind.COLON)) {
            parseSuffixType(
                hint = "变量 '${nameTok.lexeme}' 的类型要写成 " +
                    "'${kwTok.lexeme} ${nameTok.lexeme} : 类型 = 初值'",
            )
        } else {
            null
        }
        expect(
            TokenKind.ASSIGN,
            "变量 '${nameTok.lexeme}' 声明时必须初始化，" +
                "写法是 ${varDeclShape(kwTok.lexeme, nameTok.lexeme, declaredType)}",
        )
        val init = parseExpr()
        return VarDeclStmt(nameTok.lexeme, init, mutable, kwTok.at, declaredType)
    }

    /** 报错里给的示例写法：`var x : int = 表达式`（类型省了就写 `var x = 表达式`）。 */
    private fun varDeclShape(kw: String, name: String, type: TypeRef?): String =
        if (type == null) "$kw $name = 表达式" else "$kw $name : ${type.name} = 表达式"

    private fun parseAssign(): Stmt {
        val nameTok = advance() // IDENT
        advance() // =
        val value = parseExpr()
        return AssignStmt(nameTok.lexeme, value, nameTok.at)
    }

    private fun parseReturn(): Stmt {
        val rtnTok = advance() // rtn / return
        // 后面可以没有值（void 函数）。判定规则：右大括号 / 分号 / 文件结束，
        // 或者**下一个记号换行了**——`rtn` 独占一行的写法太常见，而 rsf 不靠换行
        // 分词，所以这里按行号判一下，否则下一行的语句会被误当成返回值。
        val next = peek()
        if (next.kind == TokenKind.RBRACE ||
            next.kind == TokenKind.EOF ||
            next.kind == TokenKind.SEMI ||
            next.line != rtnTok.line
        ) {
            return ReturnStmt(null, rtnTok.at)
        }
        val value = parseExpr()
        return ReturnStmt(value, rtnTok.at)
    }

    /**
     * `fail <错误消息>`（阶段 14）。
     *
     * 消息必须是 `string` 表达式（比如 `fail "空字符串没法转数字"`）；
     * 后面不带东西时给一句清楚的话，而不是把下一行语句当成消息吃掉。
     */
    private fun parseFail(): Stmt {
        val failTok = advance() // fail
        val next = peek()
        if (next.kind == TokenKind.RBRACE ||
            next.kind == TokenKind.EOF ||
            next.kind == TokenKind.SEMI ||
            next.line != failTok.line
        ) {
            throw error(
                next,
                "'fail' 后面要跟错误消息，写法是 'fail \"出错了\"'（消息必须是 string 表达式）",
            )
        }
        val message = parseExpr()
        return FailStmt(message, failTok.at)
    }

    /**
     * `try { ... } catch (e : string) { ... }`（阶段 14；阶段 19b 起改用 Kotlin 风格）。
     *
     * catch 变量的类型写法**和函数参数一样两种都收**：
     *
     * - Kotlin 风格（推荐，和 `var x : int` 一致）：`catch (e : string)`
     * - Java 风格（老写法，不打死）：`catch (e string)`
     *
     * 三种写坏的方式都要有话说：没有 catch、catch 缺括号、catch 的类型不是 string。
     * 「漏了类型」（`catch (e)`）的报错里**两种写法都列出来**。catch 只接一个变量、
     * 类型固定 string——这一版不做多重 catch / finally。
     */
    private fun parseTry(): Stmt {
        val tryTok = advance() // try
        val body = parseBody("try")

        if (!check(TokenKind.KW_CATCH)) {
            val t = peek()
            throw error(
                t,
                "try 块后面必须跟一个 catch，写法是 try { ... } catch (e : string) { ... }" +
                    "（实际读到 ${t.describe()}）",
            )
        }
        advance() // catch

        expect(
            TokenKind.LPAREN,
            "catch 后面需要 '('，写法是 catch (错误变量 : string)，例如 catch (e : string)",
        )
        val nameTok = expect(
            TokenKind.IDENT,
            "catch 里需要错误变量名，写法是 catch (e : string)（也可以写老写法 catch (e string)）",
        )

        // 两种风格的分流只看名字后面那个记号，和 parseParam 一样无歧义：
        //   `:` 开头 → Kotlin 风格；直接就是 `string` → Java 风格。
        if (match(TokenKind.COLON)) {
            if (!check(TokenKind.KW_STRING)) {
                throw error(peek(), catchTypeError(nameTok.lexeme))
            }
            advance() // string
        } else if (check(TokenKind.KW_STRING)) {
            advance() // string（老写法 catch (e string)）
        } else {
            throw error(peek(), catchTypeError(nameTok.lexeme))
        }

        expect(
            TokenKind.RPAREN,
            "catch 的参数表没有闭合，缺少 ')'（写法是 catch (e : string)）",
        )
        val catchBody = parseBody("catch")
        return TryStmt(body, nameTok.lexeme, catchBody, tryTok.at)
    }

    /**
     * catch 变量的类型写坏了。两种成因（写了别的类型 / 干脆没写类型）共用一段文案，
     * 因为**两种写法都要给出来**：用户看到的是「这里该怎么写」，不是「你哪里错了」。
     */
    private fun catchTypeError(name: String): String {
        val t = peek()
        val what = if (t.kind.isTypeKeyword) {
            "catch 的错误变量类型只能是 string（错误消息本身就是字符串），实际写的是 '${t.lexeme}'"
        } else {
            "catch 里缺少错误变量的类型（实际读到 ${t.describe()}）"
        }
        return "$what。两种写法都可以：Kotlin 风格 'catch ($name : string)'，" +
            "或者 Java 风格 'catch ($name string)'"
    }

    // ------------------------------------------------------------------ 表达式

    /** 表达式：最外层是 `?:`（优先级最低，比逻辑或还松）。 */
    private fun parseExpr(): Expr = parseElvis()

    private fun parseElvis(): Expr {
        var left = parseOr()
        while (match(TokenKind.ELVIS)) {
            val right = parseOr()
            left = ElvisExpr(left, right, left.at)
        }
        return left
    }

    /**
     * `||`（阶段 19c）：**倒数第二松**的二元运算——只比 `?:` 紧。
     *
     * 优先级从松到紧的完整链条（和 Java / Kotlin 完全一致）：
     * ```
     * ?:  <  ||  <  &&  <  == !=  <  < > <= >=  <  + -  <  * / %  <  一元  <  后缀
     * ```
     * 这条链决定了两件必须对的事：
     * - `a == b && c == d` 是 `(a == b) && (c == d)`（`&&` 比 `==` 松，所以先把两个
     *   比较各自收成一个 bool，再与起来）——这是 C 家族最容易被写错的一处；
     * - `x || y && z` 是 `x || (y && z)`（`&&` 比 `||` 紧，`&&` 先结合）。
     *
     * 短路语义在两端后端里实现，语法这边只管结合方式（见
     * [org.example.complie.interp.Interpreter] / [org.example.complie.codegen.BytecodeCompiler]）。
     */
    private fun parseOr(): Expr {
        var left = parseAnd()
        while (match(TokenKind.OROR)) {
            val op = previous().kind
            val right = parseAnd()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    /** `&&`（阶段 19c）：比 `||` 紧、比相等运算松。 */
    private fun parseAnd(): Expr {
        var left = parseEquality()
        while (match(TokenKind.ANDAND)) {
            val op = previous().kind
            val right = parseEquality()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    private fun parseEquality(): Expr {
        var left = parseComparison()
        while (match(TokenKind.EQ, TokenKind.NE)) {
            val op = previous().kind
            val right = parseComparison()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    private fun parseComparison(): Expr {
        var left = parseTerm()
        while (match(TokenKind.LT, TokenKind.GT, TokenKind.LE, TokenKind.GE)) {
            val op = previous().kind
            val right = parseTerm()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    private fun parseTerm(): Expr {
        var left = parseFactor()
        while (match(TokenKind.PLUS, TokenKind.MINUS)) {
            val op = previous().kind
            val right = parseFactor()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    private fun parseFactor(): Expr {
        var left = parseUnary()
        while (match(TokenKind.STAR, TokenKind.SLASH, TokenKind.PERCENT)) {
            val op = previous().kind
            val right = parseUnary()
            left = BinaryExpr(op, left, right, left.at)
        }
        return left
    }

    private fun parseUnary(): Expr {
        if (match(TokenKind.MINUS, TokenKind.BANG)) {
            val opTok = previous()
            val operand = parseUnary()
            return UnaryExpr(opTok.kind, operand, opTok.at)
        }
        return parsePostfix()
    }

    /**
     * 后缀：`!!`（强制解包）、`.`（成员访问）、`[i]`（下标，阶段 13）。
     *
     * 放在前缀运算符**里面**一层，所以 `!x!!` 是 `!(x!!)` 而不是 `(!x)!!`。
     * 前缀的 `!!x`（双重取反）仍然走 parseUnary 的分支，两条路不会打架。
     *
     * 下标在**同一个循环**里，所以 `a[i][j]`、`p.items[0].name`、`f()[0]` 会自然
     * 一层层套起来，不需要任何额外的语法。
     */
    private fun parsePostfix(): Expr {
        var expr = parsePrimary()
        while (true) {
            if (check(TokenKind.BANG) && peek(1).kind == TokenKind.BANG) {
                advance()
                advance()
                expr = ForceExpr(expr, expr.at)
                continue
            }
            if (check(TokenKind.DOT)) {
                val dot = advance()
                val nameTok = expect(TokenKind.IDENT, "'.' 后面需要成员名")
                val args: List<Expr>? = if (match(TokenKind.LPAREN)) {
                    val list = ArrayList<Expr>()
                    if (!check(TokenKind.RPAREN)) {
                        do {
                            list.add(parseExpr())
                        } while (match(TokenKind.COMMA))
                    }
                    expect(TokenKind.RPAREN, "成员调用的参数表没有闭合，缺少 ')'")
                    list
                } else {
                    null
                }
                expr = MemberExpr(expr, nameTok.lexeme, args, dot.at)
                continue
            }
            if (check(TokenKind.LBRACKET)) {
                val bracket = advance()
                val index = parseExpr()
                expect(TokenKind.RBRACKET, "下标访问缺少 ']'（写法是 数组[下标]，例如 a[0]）")
                expr = IndexExpr(expr, index, bracket.at)
                continue
            }
            // 阶段 24：类型转换 / 类型判断。四种写法都在 postfix 这一层
            // （所以 `a as A` 的结果能直接接 `.成员` / `[下标]` / `!!`），
            // 并且**解析完立刻 return**——只允许写一层，见 [expectNoChainedCast]。
            if (check(TokenKind.KW_AS)) {
                val op = advance()
                val target = parseCastTarget("as")
                expectNoChainedCast("as")
                return AsExpr(expr, target, false, op.at)
            }
            if (check(TokenKind.QUESTION) && peek(1).kind == TokenKind.KW_AS) {
                // `?as` 的 '?' 在 `as` **前面**（Kotlin 是 `as?`）——DESIGN-24 §3 的 Q3 拍板。
                val question = advance()
                advance()
                val target = parseCastTarget("?as")
                expectNoChainedCast("?as")
                return AsExpr(expr, target, true, question.at)
            }
            if (check(TokenKind.KW_IS)) {
                val op = advance()
                val target = parseCastTarget("is")
                expectNoChainedCast("is")
                return IsExpr(expr, target, false, op.at)
            }
            if (check(TokenKind.BANG) && peek(1).kind == TokenKind.KW_IS) {
                val bang = advance()
                advance()
                val target = parseCastTarget("!is")
                expectNoChainedCast("!is")
                return IsExpr(expr, target, true, bang.at)
            }
            break
        }
        return expr
    }

    /**
     * `as` / `is` 后面的目标类型（阶段 24）。
     *
     * 复用 [parseTypeRef]，所以 `A`、`A[]`、`Box&<int>`、`A::Nested` 都能写；
     * 只多一条限制：**目标不许可空**。null 的三种行为已经被拍死
     * （`null is A` 恒 false、`null as A` 抛、`null ?as A` 得 null，
     * 见 DESIGN-24 §3 的 Q2），目标再写 `?` 不会带来任何区别，只会让人误会
     * 「有 '?' 才是安全的那一种」——所以直接在语法层拒掉。
     */
    private fun parseCastTarget(opText: String): TypeRef {
        val tok = peek()
        // `?` 单拎出来报：否则会落到下面「需要一个目标类型」那条，把人引向
        // 「该写什么类型」而不是「不许写 ?」，而 n1 的判据看的就是这条口径。
        if (tok.kind == TokenKind.QUESTION) {
            throw error(
                tok,
                "目标类型不许写可空：'... $opText ?...' 不合法——" +
                    "'null is A' 恒为 false、'null as A' 抛、'null ?as A' 得 null，" +
                    "行为由「写的是 as 还是 ?as」决定，跟目标带不带 '?' 无关；" +
                    "去掉 '?' 写成 '$opText ...'",
            )
        }
        if (!tok.kind.isTypeKeyword && tok.kind != TokenKind.IDENT) {
            throw error(
                tok,
                "'$opText' 后面需要一个目标类型（类名 / 接口名 / 数组类型），" +
                    "实际读到 ${tok.describe()}",
            )
        }
        val ref = parseTypeRef()
        if (ref.name.startsWith("?")) {
            throw error(
                tok,
                "目标类型不许写可空：'... $opText ${ref.display()}' 不合法——" +
                    "'null is A' 恒为 false、'null as A' 抛、'null ?as A' 得 null，" +
                    "行为由「写的是 as 还是 ?as」决定，跟目标带不带 '?' 无关；" +
                    "去掉 '?' 写成 '$opText ${ref.display().removePrefix("?")}'",
            )
        }
        return ref
    }

    /**
     * `as` / `is` **只允许写一层**（阶段 24，DESIGN-24 §3 的 Q4 拍板）。
     *
     * 写完一个转换/判型后立刻看一眼下一个记号：若是 `as` / `?as` / `is` / `!is` 就报错。
     * 理由：`a as A as B` 的结论等价于 `a as B`（判型只认最后那个类型），
     * 中间那层既不改变结果、也不做额外检查，链下去只让人误会它「逐层收窄」。
     */
    private fun expectNoChainedCast(opText: String) {
        val t = peek()
        val next = when {
            t.kind == TokenKind.KW_AS -> "as"
            t.kind == TokenKind.KW_IS -> "is"
            t.kind == TokenKind.QUESTION && peek(1).kind == TokenKind.KW_AS -> "?as"
            t.kind == TokenKind.BANG && peek(1).kind == TokenKind.KW_IS -> "!is"
            else -> return
        }
        throw error(
            t,
            "'as' / 'is' 只允许写一层：'$opText ... $next ...' 不合法——" +
                "判型只看最后一个目标类型，中间那层不改变结论；" +
                "确实需要两步就分开写（`var x = a as A` 再 `x as B`）",
        )
    }

    /**
     * `super` 的两种写法（阶段 25）：`super` 与 `super(接口名)`。
     *
     * 两者都只允许**紧接着一个 `.成员`**：`super.tag()` 调直接父类被自己覆盖的那个实现，
     * `super(Greeter).tag()` 指定父接口的默认实现（消歧用）。语法在这里就把形状定死，
     * 是为了让下游（Checker / 解释器 / 字节码）只要看到 [SuperExpr] 就知道
     * 「它后面一定跟着成员访问」，不用各自再防一手「单独一个 super 怎么办」。
     */
    private fun parseSuperExpr(): Expr {
        val tok = advance() // super
        if (check(TokenKind.LPAREN)) {
            advance() // '('
            val ifaceTok = peek()
            val hint = "super(接口名).方法名() 里的括号中要写一个**接口名**"
            if (ifaceTok.kind != TokenKind.IDENT) {
                throw error(
                    ifaceTok,
                    "$hint，例如 super(Greeter).tag()（这一步是消歧两个父接口的默认方法），" +
                        "实际读到 ${ifaceTok.describe()}",
                )
            }
            advance() // 接口名
            val typeArgs = if (check(TokenKind.AMP)) parseTypeArgs() else emptyList()
            val name = TypeRef(ifaceTok.lexeme, ifaceTok.at, typeArgs = typeArgs)
            expect(TokenKind.RPAREN, "$hint，缺一个 ')'")
            val sup = SuperExpr(name, tok.at)
            return parseSuperMember(sup, "super(${ifaceTok.lexeme})")
        }
        val sup = SuperExpr(null, tok.at)
        if (!check(TokenKind.DOT)) {
            throw error(
                peek(),
                "'super' 只能当方法调用的接收者：写法是 super.方法名(实参)，" +
                    "或者 super(接口名).方法名(实参)。单独一个 'super' 没有值可取，" +
                    "要访问父类的方法就写清楚方法名，实际读到 ${peek().describe()}",
            )
        }
        return parseSuperMember(sup, "super")
    }

    /**
     * `super` 后面那个 `.成员`（阶段 25）。
     *
     * 只收**方法调用**（`super.f(实参)`），不收字段：DESIGN-25 §6 明确不做
     * `super.字段`，而且字段本来也不许遮蔽，写 `super.x` 一定是有别的误解——
     * 这里直接说清，比让它掉进语义层报一句「父类没有成员 x」有用得多。
     */
    private fun parseSuperMember(sup: SuperExpr, who: String): Expr {
        val dot = expect(TokenKind.DOT, "'$who' 后面需要 '.'：写法是 $who.方法名(实参)")
        val nameTok = expect(TokenKind.IDENT, "'$who.' 后面需要成员名")
        if (!check(TokenKind.LPAREN)) {
            throw error(
                peek(),
                "'$who.${nameTok.lexeme}' 只能是方法调用：要写出实参表（$who.${nameTok.lexeme}(...)）。" +
                    "rsf 不支持 super.字段（字段本来也不许遮蔽，DESIGN-25 §5/§6），" +
                    "实际读到 ${peek().describe()}",
            )
        }
        advance() // '('
        val args = ArrayList<Expr>()
        if (!check(TokenKind.RPAREN)) {
            do {
                args.add(parseExpr())
            } while (match(TokenKind.COMMA))
        }
        expect(TokenKind.RPAREN, "'$who.${nameTok.lexeme}(...)' 的参数表没有闭合，缺少 ')'")
        return MemberExpr(sup, nameTok.lexeme, args, dot.at)
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        return when (t.kind) {
            TokenKind.INT_LIT -> { advance(); IntLit(t.literal as Int, t.at) }
            TokenKind.LONG_LIT -> { advance(); LongLit(t.literal as Long, t.at) }
            TokenKind.FLOAT_LIT -> { advance(); FloatLit(t.literal as Float, t.at) }
            TokenKind.DOUBLE_LIT -> { advance(); DoubleLit(t.literal as Double, t.at) }
            TokenKind.STRING_LIT -> { advance(); StringLit(t.literal as String, t.at) }
            TokenKind.CHAR_LIT -> { advance(); CharLit(t.literal as Char, t.at) }
            TokenKind.KW_TRUE -> { advance(); BoolLit(true, t.at) }
            TokenKind.KW_FALSE -> { advance(); BoolLit(false, t.at) }
            TokenKind.KW_NULL -> { advance(); NullLit(t.at) }

            TokenKind.KW_IF -> {
                advance()
                val condition = parseExpr()
                parseIfTail(condition, t.at)
            }

            TokenKind.KW_THIS -> { advance(); ThisExpr(t.at) }

            // `super`（阶段 25）：只允许当方法调用的接收者——
            // `super.f()` 走直接父类那一层的实现，`super(接口名).f()` 消歧两个父接口的
            // 默认方法（DESIGN-25 §3 的 Q1）。单独一个 `super` 没有值可取，所以这里
            // 只认这两种形状，别的写法当场报错说清它该长什么样。
            TokenKind.KW_SUPER -> parseSuperExpr()

            TokenKind.KW_NEW -> {
                advance()
                parseNewExpr(t.at)
            }

            TokenKind.IDENT -> {
                advance()
                // `Box&<int>(0)`：类型实参只可能出现在**类型**上，函数没有类型参数，
                // 所以「标识符 +&<...> + (」无歧义地是一次构造（等价于 `new Box&<int>(0)`）。
                // 省掉 new 的普通写法 `Point(1, 2)` 解析成 [CallExpr]，由 Checker
                // 做完名字解析之后决定它是构造还是函数调用。
                if (check(TokenKind.AMP)) {
                    val typeArgs = parseTypeArgs()
                    expect(
                        TokenKind.LPAREN,
                        "'${t.lexeme}&<...>' 后面缺少 '('：构造对象要写实参表，" +
                            "例如 new ${t.lexeme}&<int>(0)（new 可以省）",
                    )
                    val args = ArrayList<Expr>()
                    if (!check(TokenKind.RPAREN)) {
                        do {
                            args.add(parseExpr())
                        } while (match(TokenKind.COMMA))
                    }
                    expect(TokenKind.RPAREN, "构造 '${t.lexeme}' 的参数表没有闭合，缺少 ')'")
                    return NewExpr(t.lexeme, args, t.at, typeArgs)
                }
                if (match(TokenKind.LPAREN)) {
                    val args = ArrayList<Expr>()
                    if (!check(TokenKind.RPAREN)) {
                        do {
                            args.add(parseExpr())
                        } while (match(TokenKind.COMMA))
                    }
                    expect(TokenKind.RPAREN, "调用 '${t.lexeme}' 的参数表没有闭合，缺少 ')'")
                    CallExpr(t.lexeme, args, t.at)
                } else {
                    VarExpr(t.lexeme, t.at)
                }
            }

            TokenKind.LPAREN -> {
                advance()
                val inner = parseExpr()
                expect(TokenKind.RPAREN, "括号没有闭合，缺少 ')'")
                inner
            }

            else -> throw error(t, "这里需要一个表达式，实际是 ${t.describe()}")
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun peek(offset: Int = 0): Token =
        tokens[minOf(pos + offset, tokens.size - 1)]

    private fun previous(): Token = tokens[pos - 1]

    private fun check(kind: TokenKind): Boolean = peek().kind == kind

    private fun advance(): Token {
        val t = peek()
        if (t.kind != TokenKind.EOF) pos++
        return t
    }

    private fun match(vararg kinds: TokenKind): Boolean {
        for (k in kinds) {
            if (check(k)) {
                advance()
                return true
            }
        }
        return false
    }

    private fun expect(kind: TokenKind, message: String): Token {
        if (!check(kind)) {
            val t = peek()
            throw error(t, "$message（实际读到 ${t.describe()}）")
        }
        return advance()
    }

    private fun error(t: Token, message: String): RsfError =
        RsfError("parser", message, t.line, t.column, source)
}
