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
 * <ControlType> <static> fun <MainType> 名字(<参数表>) :: <ReturnType> { ... }
 * ```
 * - `<ControlType>`：`public` / `protected` / `private`，写在最左，**省略就是 `public`**
 * - `<static>`：紧跟其后，控制「怎么调用」（类名.方法 / 对象.方法），可省略
 * - `<MainType>`：`fun` 之后、函数名之前的类型槽，缺省 `void`
 * - **返回类型子句只认 `->` 和 `::`**（`:` 那条别名已删掉：它和 `if c : 语句`、
 *   变量类型标注 `var x : int` 太容易看混）；可省略，缺省 `void`；
 *   可带 `?`（可空）或 `!`（可能出错）前缀
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

        // pkg 只能出现在最前面（在 import 之前），和 Java 的 package 一样
        var packageName: String? = null
        if (check(TokenKind.KW_PKG)) {
            packageName = parsePackageDecl()
        }

        while (!check(TokenKind.EOF)) {
            if (check(TokenKind.KW_IMPORT) || check(TokenKind.KW_MODULE)) {
                imports.add(parseImport())
            } else if (check(TokenKind.KW_PKG)) {
                val t = peek()
                throw error(t, "'pkg' 只能写在文件最前面（在所有 import 之前）")
            } else if (check(TokenKind.KW_ENUM) || isEnumDeclAhead()) {
                enums.add(parseEnumDecl())
            } else if (check(TokenKind.KW_CLASS) || isClassDeclAhead()) {
                classes.add(parseClassDecl())
            } else if (check(TokenKind.KW_INTERFACE) || isInterfaceDeclAhead()) {
                interfaces.add(parseInterfaceDecl())
            } else {
                functions.add(parseFuncDecl())
            }
        }
        return Program(imports, functions, classes, packageName, enums, interfaces)
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

    /** 顶层下一个声明是不是类：`[public|protected|private] class ...`。 */
    private fun isClassDeclAhead(): Boolean {
        val offset = if (peek().kind.isControlKeyword) 1 else 0
        return peek(offset).kind == TokenKind.KW_CLASS
    }

    /** 顶层下一个声明是不是枚举：`[public|protected|private] enum ...`。 */
    private fun isEnumDeclAhead(): Boolean {
        val offset = if (peek().kind.isControlKeyword) 1 else 0
        return peek(offset).kind == TokenKind.KW_ENUM
    }

    /** 顶层下一个声明是不是接口：`[public|protected|private] interface ...`。 */
    private fun isInterfaceDeclAhead(): Boolean {
        val offset = if (peek().kind.isControlKeyword) 1 else 0
        return peek(offset).kind == TokenKind.KW_INTERFACE
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
    private fun parseClassDecl(): ClassDecl {
        var control = ControlType.DEFAULT
        if (peek().kind.isControlKeyword) {
            control = ControlType.of(advance().kind)
        }
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

        // 父接口子句（阶段 20）：`class Dog(n : string) : Greeter, Runnable { ... }`。
        // 顺序固定为「主构造括号 → ':' 子句 → '{'」（DESIGN-20 §2）。
        val supertypes = parseSupertypeList("类 '${nameTok.lexeme}'")

        expect(TokenKind.LBRACE, "类 '${nameTok.lexeme}' 缺少 '{'")

        val fields = ArrayList<FieldDecl>()
        val methods = ArrayList<FuncDecl>()
        if (primary != null) methods.add(primary)
        while (!check(TokenKind.RBRACE) && !check(TokenKind.EOF)) {
            when {
                peek().kind.isVarKeyword -> fields.add(parseFieldDecl())
                // `constructor` 以及「修饰词 + constructor」（后者交给 parseConstructor
                // 报「不能写权限修饰词 / 不能写 override」，别掉进 parseFuncDecl
                // 变成一句看不懂的通用错误）。修饰词可以排好几个，所以先把这一串数出来。
                peek(modifierRun()).kind == TokenKind.KW_CONSTRUCTOR ->
                    methods.add(parseConstructor(nameTok.lexeme))
                // 类里的方法：`override` 在这里是合法的（接口里的方法也能写，见 parseInterfaceDecl）
                else -> methods.add(parseFuncDecl(allowOverride = true))
            }
        }
        expect(TokenKind.RBRACE, "类 '${nameTok.lexeme}' 没有闭合，缺少 '}'")
        return ClassDecl(control, nameTok.lexeme, fields, methods, classTok.at, typeParams, supertypes)
    }

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
        val supertypes = ArrayList<TypeRef>()
        do {
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
    private fun parseInterfaceDecl(): InterfaceDecl {
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
            methods.add(parseFuncDecl(allowAbstractBody = true, allowOverride = true))
            while (match(TokenKind.SEMI)) { /* 可选的分号，吃掉 */ }
        }
        expect(TokenKind.RBRACE, "接口 '${nameTok.lexeme}' 没有闭合，缺少 '}'")

        return InterfaceDecl(control, nameTok.lexeme, methods, supertypes, ifaceTok.at, typeParams)
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
    private fun parseConstructor(className: String): FuncDecl {
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
    private fun parseFieldDecl(): FieldDecl {
        val kwTok = advance() // var / imutvar
        val mutable = kwTok.kind == TokenKind.KW_VAR
        val nameTok = if (check(TokenKind.IDENT)) {
            advance()
        } else {
            val t = peek()
            val hint = if (t.kind.isTypeKeyword) {
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
        return FieldDecl(nameTok.lexeme, type, init, mutable, kwTok.at)
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
     * 类和顶层函数一律要求写出方法体——所以「没有体」这件事在下游是接口专属信号，
     * 不用担心某个类方法偷偷变成抽象的。
     *
     * [allowOverride] 只有**类体/接口体**里传 true（阶段 20 追加）：`override` 的意思是
     * 「这个函数必须真的覆盖到父类型里的方法」，顶层函数没有父类型，所以在顶层直接报错。
     * `override` 与访问控制修饰词可以按**任意顺序**写（`override public fun` 与
     * `public override fun` 等价），所以这两个先一起收掉；`static` 照旧排在这一串之后。
     */
    private fun parseFuncDecl(
        allowAbstractBody: Boolean = false,
        allowOverride: Boolean = false,
    ): FuncDecl {
        // <ControlType> 与 <override>：都排在 fun 前面，顺序随意，一起收
        var control = ControlType.DEFAULT
        var isOverride = false
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
            break
        }

        // <static>：紧跟 <ControlType> 之后、fun 之前，控制「怎么调用」
        var isStatic = false
        if (peek().kind.isStaticKeyword) {
            advance()
            isStatic = true
        }

        // `static override fun` 也收：`override` 是新关键字，收它不会影响任何旧程序，
        // 而修饰词顺序本身没有语义——没必要让用户因为换个顺序就撞一句看不懂的报错。
        if (consumeOverride(allowOverride)) {
            isOverride = true
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
            throw error(
                t,
                "顶层只能写函数声明，格式是 '[public|protected|private] [static] fun [类型] 名字() 返回子句 函数体'，" +
                    "实际读到 ${t.describe()}$hint$overrideHint",
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

        // 返回类型子句：只认 `->` 和 `::`（`:` 那条别名已删，见下面的报错）
        val ret: ReturnSpec = when {
            match(TokenKind.ARROW) -> parseReturnSpec(ReturnStyle.ARROW)
            match(TokenKind.DOUBLE_COLON) -> parseReturnSpec(ReturnStyle.DOUBLE_COLON)
            check(TokenKind.COLON) -> throw error(
                peek(),
                "返回类型子句不再接受 ':'：请用 '->' 或者 '::'，" +
                    "例如 fun ${nameTok.lexeme}() -> int { ... }。" +
                    "（':' 现在专门留给「名字 : 类型」和「if c : 语句」，" +
                    "三种写法挤在一起太容易看混）",
            )
            else -> ReturnSpec(TypeRef("void", funTok.at), ReturnStyle.OMITTED, false, funTok.at)
        }

        // 接口里的抽象方法：只写签名，下一个记号不是 '{' 就直接收工（体是 null）。
        // 其余任何地方都要求有体，所以这里必须显式传 allowAbstractBody。
        val body: Block? = if (allowAbstractBody && !check(TokenKind.LBRACE)) null else parseBlock()
        return FuncDecl(
            control, mainType, nameTok.lexeme, params, ret, body, funTok.at, isStatic,
            isOverride = isOverride,
        )
    }

    /**
     * `fun` 前面那串修饰词有几个（`public` / `private` / `protected` / `static` / `override`
     * 任意顺序都算）。只数不消费——调用方拿这个偏移去 `peek(n)` 看 `fun` 后面跟着的是不是
     * 它要的那个声明（类体里要靠它把「修饰词 + constructor」认出来）。
     */
    private fun modifierRun(): Int {
        var n = 0
        while (peek(n).kind.isModifierKeyword) n++
        return n
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
                "'case' 只能写在 switch 的块里，写法是 switch (值) { case 枚举名.值名 : 语句 ... }",
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

            t.kind == TokenKind.KW_CONSTRUCTOR -> throw error(
                t,
                "'constructor' 只能写在类体里（它是次构造：constructor(参数) { ... }）",
            )

            t.kind == TokenKind.KW_CATCH -> throw error(
                t,
                "'catch' 只能跟在 try 块后面，写法是 try { ... } catch (e : string) { ... }" +
                    "（也接受老写法 catch (e string)）",
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

            val caseTok = expect(
                TokenKind.KW_CASE,
                "switch 的块里只能写 'case 枚举名.值名 : 语句' 或者 'else : 语句'，" +
                    "实际读到 ${peek().describe()}",
            )
            val enumTok = expect(
                TokenKind.IDENT,
                "'case' 后面需要一个枚举名（写法是 case 枚举名.值名 : 语句）",
            )
            expect(
                TokenKind.DOT,
                "'case ${enumTok.lexeme}' 后面需要 '.'——case 只接受枚举常量，" +
                    "写法是 case ${enumTok.lexeme}.值名（不支持区间、比较、字面量）",
            )
            val valueTok = expect(
                TokenKind.IDENT,
                "'case ${enumTok.lexeme}.' 后面需要一个值名（写法是 case ${enumTok.lexeme}.值名）",
            )
            val body = parseBody("case")
            cases.add(SwitchCase(enumTok.lexeme, valueTok.lexeme, body, caseTok.at))
        }
        expect(TokenKind.RBRACE, "switch 没有闭合，缺少 '}'")

        if (cases.isEmpty() && elseBody == null) {
            throw error(
                switchTok,
                "switch 里什么都没有：至少要有一个 'case 枚举名.值名 : 语句' 分支" +
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
            val hint = if (t.kind.isTypeKeyword) {
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
            break
        }
        return expr
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
