package org.example

import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.AnnotationUse
import org.example.complie.ast.AsExpr
import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ClassImport
import org.example.complie.ast.CompanionEntityDecl
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.ElvisExpr
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.Expr
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.FailStmt
import org.example.complie.ast.FieldDecl
import org.example.complie.ast.FloatLit
import org.example.complie.ast.ForceExpr
import org.example.complie.ast.ForStmt
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.GotoStmt
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IfStmt
import org.example.complie.ast.IndexAssignStmt
import org.example.complie.ast.IndexExpr
import org.example.complie.ast.IntLit
import org.example.complie.ast.IsExpr
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.LabelStmt
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.ModuleImport
import org.example.complie.ast.NamedAnnotationArg
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.NullLit
import org.example.complie.ast.PositionalAnnotationArg
import org.example.complie.ast.Program
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.SourceImport
import org.example.complie.ast.Stmt
import org.example.complie.ast.StringLit
import org.example.complie.ast.SuperCallStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.SwitchCase
import org.example.complie.ast.SwitchStmt
import org.example.complie.ast.ThisCallStmt
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TryStmt
import org.example.complie.ast.TypeRef
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt

/**
 * 阶段 17b：把 Kotlin 版 AST 打成**规范化文本**，用来和 rsf 版语法分析器的胖节点树对拍。
 *
 * 放在**测试源集**里是刻意的：`rsf check` / `rsf lex` / `rsf run` 这些命令的输出格式
 * 有测试依赖，一行都不能动，所以这份 dump 不挂在任何 CLI 入口上——只有测试会调它。
 *
 * ## 格式（两端必须**逐字节**一致，rsf 侧见 `rsfSrc/bootstrap/Parser.rsf`）
 *
 * 缩进树，**一行一个节点**，行尾统一 `\n`（绝不出 CRLF），整份输出以 `\n` 结尾：
 *
 * ```
 * <缩进><KIND> [<名字>=<值>]...
 * ```
 *
 * - 缩进 = 2 个空格 × 层数，根节点的层数是 0；
 * - 字段之间正好一个空格；字段顺序**按下面每个 dump 函数里写的顺序**，不排序、不省略；
 * - 值里**不允许出现空格与换行**（都转义掉了），所以「按空格切字段」是无歧义的；
 * - 列表用**显式的列表节点**包起来（`IMPORTS` / `FUNCS` / `PARAMS` / `ARGS` / `BLOCK` …），
 *   带一个 `count=N`，孩子就是元素。这样「这个节点的孩子到哪儿为止」不用靠缩进猜，
 *   列表空不空也能一眼看出来（空列表仍然打一行 `count=0`）。
 *
 * ## 确定性从哪儿来
 *
 * - **不打对象地址、不打 hashCode**：所有字段都是字面量值、名字、类型文本或枚举名；
 * - **顺序固定**：AST 里全是 `List`（`Program.functions` 等），本来就按源码顺序，
 *   这里原样按索引遍历，不做任何集合重排（不碰 `HashSet` / `Map`）；
 * - **浮点/长整型值**统一走 `toString()`（JVM 的 `Double.toString` 等），
 *   不用 `"%.2f"` 那种依赖 locale / 精度的写法；rsf 侧用同样的 JVM 方法算，见
 *   `Parser.rsf` 的 `literalValueText`；
 * - **转义表固定**：`\` → `\\`、换行 → `\n`、回车 → `\r`、制表 → `\t`、空格 → `\s`、
 *   其余 `< 0x20` 的控制字符 → `\xNN`（小写两位十六进制）。中文字符原样输出（两端都按 UTF-8 处理）。
 *
 * ## 规范化掉的东西（写在报告里，别当成漏了）
 *
 * - `SourceLine`（行列）**不进 dump**：rsf 版也记行号，但「哪个节点记的是哪个位置」在
 *   两端是各写各的，逼它一致等于在比实现细节而不是比语法结构。语法结构一致才是本阶段的目标；
 * - `TypeRef` / `ReturnSpec` 是**内联**成字段的（`type=int[]`、`ret=ARROW` + `mayError=false`
 *   + `retType=!int` 这种拆法），不单独成节点——它们没有孩子，拆成节点只会让 dump 变长；
 * - `Program` 固定打 6 个子节点（`PKG` 缺省时**整行不打**、其余五个永远打，空表也打 `count=0`）。
 *
 * 用法（测试里）：
 * ```
 * AstDump.dump(Rsf.parse(SourceFile.of("<x>", text)))
 * ```
 */
object AstDump {

    /** 整份程序。行尾 `\n`，结尾也有一个 `\n`。 */
    fun dump(program: Program): String {
        val out = StringBuilder()
        line(out, 0, "PROGRAM")

        program.packageName?.let { line(out, 1, "PKG", "name" to esc(it)) }

        line(out, 1, "IMPORTS", "count" to program.imports.size.toString())
        for (imp in program.imports) {
            val kind = when (imp) {
                is ClassImport -> "CLASS_IMPORT"
                is SourceImport -> "SOURCE_IMPORT"
                is ModuleImport -> "MODULE_IMPORT"
            }
            line(out, 2, kind, "path" to esc(imp.path))
        }

        line(out, 1, "FUNCS", "count" to program.functions.size.toString())
        for (f in program.functions) func(out, 2, f)

        line(out, 1, "CLASSES", "count" to program.classes.size.toString())
        for (c in program.classes) classDecl(out, 2, c)

        line(out, 1, "ENUMS", "count" to program.enums.size.toString())
        for (e in program.enums) enumDecl(out, 2, e)

        // 阶段 20：接口单独一桶，排在 ENUMS 之后（rsf 侧 Parser.rsf 的 PROGRAM 孩子顺序同此）
        line(out, 1, "INTERFACES", "count" to program.interfaces.size.toString())
        for (i in program.interfaces) interfaceDecl(out, 2, i)

        // 阶段 21：注解声明单独一桶，排在 INTERFACES **之后**（PROGRAM 的最后一个孩子；
        // rsf 侧 Parser.rsf 的 PROGRAM 孩子顺序同此，同样追加在最后）
        line(out, 1, "ANNOTATIONS", "count" to program.annotations.size.toString())
        for (a in program.annotations) annotationDecl(out, 2, a)

        return out.toString()
    }

    // ------------------------------------------------------------------ 声明

    private fun func(out: StringBuilder, depth: Int, f: FuncDecl) {
        line(
            out, depth, "FUNC",
            "control" to f.control.name,
            "static" to bool(f.isStatic),
            "main" to typeText(f.mainType),
            "name" to esc(f.name),
            "ret" to f.ret.style.name,
            "mayError" to bool(f.ret.mayError),
            "retType" to typeText(f.ret.type),
            "ctor" to bool(f.isConstructor),
            "primary" to bool(f.isPrimary),
            "override" to bool(f.isOverride),
        )
        // 阶段 25：open / final **只在写了的时候**才打一行——没写的成员一个字都不多打。
        // 理由很实在：这份 dump 在 RsfTest 里要和 rsfSrc/bootstrap 的解析器逐字对拍，
        // 而自举那一侧还不认识这两个修饰词（K5 才同步）。默认形态保持原样是硬约束。
        if (f.isOpen) line(out, depth + 1, "OPEN")
        if (f.isFinal) line(out, depth + 1, "FINAL")
        line(out, depth + 1, "PARAMS", "count" to f.params.size.toString())
        for (p in f.params) {
            line(out, depth + 2, "PARAM", "type" to typeText(p.type), "name" to esc(p.name))
        }
        // 抽象方法（接口里只声明签名的那种）没有体，阶段 20 起 body 是可空的
        f.body?.let { block(out, depth + 1, it) }

        // 阶段 21：注解使用点是**最后一个**孩子（位置索引铁律：Interp/Backend 用位置读孩子）
        annotationUses(out, depth + 1, f.annotations)
    }

    private fun classDecl(out: StringBuilder, depth: Int, c: ClassDecl) {
        line(out, depth, "CLASS", "control" to c.control.name, "name" to esc(c.name))

        // 阶段 20：`:` 父类型子句。空表也打一行 `count=0`（和别的列表节点一个规矩）。
        supers(out, depth + 1, c.supertypes)

        // 阶段 25：父**类**（`class D : Base(实参)`）。没写父类时一行都不多打——
        // 与自举解析器逐字对拍的语料里不会有类继承（K5 之前）。
        if (c.superClass != null) {
            line(out, depth + 1, "SUPER_CLASS", "name" to esc(c.superClass.name), "display" to typeText(c.superClass))
            args(out, depth + 2, c.superArgs)
        }
        if (c.isOpen) line(out, depth + 1, "OPEN")
        if (c.isFinal) line(out, depth + 1, "FINAL")
        if (c.isAbstract) line(out, depth + 1, "ABSTRACT")

        line(out, depth + 1, "TYPE_PARAMS", "count" to c.typeParams.size.toString())
        for (tp in c.typeParams) line(out, depth + 2, "TYPE_PARAM", "name" to esc(tp))

        line(out, depth + 1, "FIELDS", "count" to c.fields.size.toString())
        for (field in c.fields) fieldDecl(out, depth + 2, field)

        // methods 里**同时**装着主构造与普通方法（主构造排最前），原样按顺序打——
        // 顺序本身就是 AST 的一部分，重排会让两边的「主构造在第几个」对不上。
        line(out, depth + 1, "METHODS", "count" to c.methods.size.toString())
        for (m in c.methods) func(out, depth + 2, m)

        annotationUses(out, depth + 1, c.annotations)
    }

    /**
     * `:` 父类型子句（类与接口**共用**同一种表示：`ClassDecl.supertypes` / `InterfaceDecl.supertypes`）。
     *
     * `display` 用的是本文件的 [typeText]（逗号后**不空格**），不是 `TypeRef.display()`
     * （那个带空格）：值里不允许出现空格。rsf 侧对应 `TypeInfo.text()`。
     */
    private fun supers(out: StringBuilder, depth: Int, list: List<TypeRef>) {
        line(out, depth, "SUPERS", "count" to list.size.toString())
        for (t in list) {
            line(
                out, depth + 1, "SUPER",
                "name" to esc(t.name),
                "display" to typeText(t),
                "typeArgs" to bool(t.typeArgs.isNotEmpty()),
            )
        }
    }

    /** 接口声明：头一行 + `SUPERS` / `TYPE_PARAMS` / `METHODS`，与 [classDecl] 同形（少 FIELDS）。 */
    private fun interfaceDecl(out: StringBuilder, depth: Int, i: InterfaceDecl) {
        line(out, depth, "INTERFACE", "control" to i.control.name, "name" to esc(i.name))

        supers(out, depth + 1, i.supertypes)

        line(out, depth + 1, "TYPE_PARAMS", "count" to i.typeParams.size.toString())
        for (tp in i.typeParams) line(out, depth + 2, "TYPE_PARAM", "name" to esc(tp))

        // 接口体里只有方法（字段在 Parser 就被拦下），抽象方法没有 BLOCK 子节点（见 [func]）
        line(out, depth + 1, "METHODS", "count" to i.methods.size.toString())
        for (m in i.methods) func(out, depth + 2, m)

        annotationUses(out, depth + 1, i.annotations)
    }

    private fun fieldDecl(out: StringBuilder, depth: Int, f: FieldDecl) {
        line(
            out, depth, "FIELD",
            "name" to esc(f.name),
            "mutable" to bool(f.mutable),
            "type" to typeText(f.type),
        )
        expr(out, depth + 1, f.initializer)

        annotationUses(out, depth + 1, f.annotations)
    }

    private fun enumDecl(out: StringBuilder, depth: Int, e: EnumDecl) {
        line(out, depth, "ENUM", "control" to e.control.name, "name" to esc(e.name))
        line(out, depth + 1, "VALUES", "count" to e.values.size.toString())
        for (v in e.values) line(out, depth + 2, "ENUM_VALUE", "name" to esc(v.name))
    }

    // ------------------------------------------------------------------ 注解（阶段 21）

    /**
     * 注解声明：`[public] annotation 名字(元素表) { companion entity ... }`。
     *
     * 孩子顺序与 rsf 侧 `Parser.rsf` 的 `parseAnnotationDecl` 一一对应：
     * `ANNOTATION_ELEMENTS` → [`COMPANION`] → `ANNOTATION_USES`（元注解，**永远打**）。
     */
    private fun annotationDecl(out: StringBuilder, depth: Int, a: AnnotationDecl) {
        line(out, depth, "ANNOTATION", "control" to a.control.name, "name" to esc(a.name))

        line(out, depth + 1, "ANNOTATION_ELEMENTS", "count" to a.elements.size.toString())
        for (e in a.elements) {
            line(
                out, depth + 2, "ANNOTATION_ELEMENT",
                "name" to esc(e.name),
                "type" to typeText(e.type),
                "hasDefault" to bool(e.default != null),
            )
            // 有默认值才紧跟一棵表达式子树（照 FIELD 的 initializer 那样打）
            e.default?.let { expr(out, depth + 3, it) }
        }

        // 没写伴生实体时整棵不打（`annotation M { }`）
        a.companion?.let { companion(out, depth + 1, it) }

        annotationUses(out, depth + 1, a.annotations)
    }

    /**
     * 伴生实体：与 [classDecl] 同形（`SUPERS` / `FIELDS` / `METHODS`），但**没有** `TYPE_PARAMS`。
     *
     * 名字可省：匿名打占位 `name=-`（`-` 与 `VAR_DECL type=-` 是同款占位写法）。
     * 伴生实体自己写的注解在语法层就被丢弃（`Parser.kt:522`），AST 里没有对应字段，
     * 所以这里没有 `ANNOTATION_USES` 这一行。
     */
    private fun companion(out: StringBuilder, depth: Int, c: CompanionEntityDecl) {
        line(out, depth, "COMPANION", "name" to esc(c.name ?: "-"))

        supers(out, depth + 1, c.supertypes)

        line(out, depth + 1, "FIELDS", "count" to c.fields.size.toString())
        for (field in c.fields) fieldDecl(out, depth + 2, field)

        line(out, depth + 1, "METHODS", "count" to c.methods.size.toString())
        for (m in c.methods) func(out, depth + 2, m)
    }

    /**
     * 挂在可标注声明（类/接口/注解声明/方法/字段）上的注解使用点列表，**永远是最后一个孩子**。
     *
     * 空表也打一行 `count=0`；`@M` 与 `@M()` 在这里打出来一样（`AnnotationUse.arguments`
     * 分不出这两者，照实打空表，不发明区分）。实参两种用**两个节点名**区分。
     */
    private fun annotationUses(out: StringBuilder, depth: Int, list: List<AnnotationUse>) {
        line(out, depth, "ANNOTATION_USES", "count" to list.size.toString())
        for (u in list) {
            line(out, depth + 1, "ANNOTATION_USE", "name" to esc(u.name))
            line(out, depth + 2, "ANNOTATION_ARGS", "count" to u.arguments.size.toString())
            for (arg in u.arguments) {
                when (arg) {
                    is PositionalAnnotationArg -> line(out, depth + 3, "POSITIONAL_ARG")
                    is NamedAnnotationArg -> line(out, depth + 3, "NAMED_ARG", "name" to esc(arg.name))
                }
                expr(out, depth + 4, arg.value)
            }
        }
    }

    // ------------------------------------------------------------------ 语句

    private fun block(out: StringBuilder, depth: Int, b: Block) {
        line(out, depth, "BLOCK", "count" to b.statements.size.toString())
        for (s in b.statements) stmt(out, depth + 1, s)
    }

    private fun stmt(out: StringBuilder, depth: Int, s: Stmt) {
        when (s) {
            is Block -> block(out, depth, s)

            is VarDeclStmt -> {
                val t = s.declaredType
                line(
                    out, depth, "VAR_DECL",
                    "name" to esc(s.name),
                    "mutable" to bool(s.mutable),
                    "typed" to bool(t != null),
                    "type" to (t?.let { typeText(it) } ?: "-"),
                )
                expr(out, depth + 1, s.initializer)
            }

            is AssignStmt -> {
                line(out, depth, "ASSIGN", "name" to esc(s.name), "synthetic" to bool(s.synthetic))
                expr(out, depth + 1, s.value)
            }

            is MemberAssignStmt -> {
                line(out, depth, "MEMBER_ASSIGN")
                expr(out, depth + 1, s.target)
                expr(out, depth + 1, s.value)
            }

            is IndexAssignStmt -> {
                line(out, depth, "INDEX_ASSIGN")
                expr(out, depth + 1, s.target)
                expr(out, depth + 1, s.value)
            }

            is ReturnStmt -> {
                line(out, depth, "RETURN", "hasValue" to bool(s.value != null))
                s.value?.let { expr(out, depth + 1, it) }
            }

            is ExprStmt -> {
                line(out, depth, "EXPR_STMT")
                expr(out, depth + 1, s.expr)
            }

            is LabelStmt -> line(out, depth, "LABEL", "name" to esc(s.name))

            is ThisCallStmt -> {
                line(out, depth, "THIS_CALL", "argc" to s.arguments.size.toString())
                args(out, depth + 1, s.arguments)
            }

            // 阶段 25：父类构造委托 `super(实参)`（构造器体第一句）
            is SuperCallStmt -> {
                line(out, depth, "SUPER_CALL", "argc" to s.arguments.size.toString())
                args(out, depth + 1, s.arguments)
            }

            is SwitchStmt -> {
                line(
                    out, depth, "SWITCH",
                    "caseCount" to s.cases.size.toString(),
                    "hasElse" to bool(s.elseBody != null),
                )
                expr(out, depth + 1, s.subject)
                line(out, depth + 1, "CASES", "count" to s.cases.size.toString())
                for (c in s.cases) switchCase(out, depth + 2, c)
                s.elseBody?.let { block(out, depth + 1, it) }
            }

            is GotoStmt -> line(out, depth, "GOTO", "label" to esc(s.label))

            is IfStmt -> {
                line(out, depth, "IF", "hasElse" to bool(s.elseBody != null))
                expr(out, depth + 1, s.condition)
                block(out, depth + 1, s.body)
                s.elseBody?.let { block(out, depth + 1, it) }
            }

            is WhileStmt -> {
                line(out, depth, "WHILE")
                expr(out, depth + 1, s.condition)
                block(out, depth + 1, s.body)
            }

            is ForStmt -> {
                line(
                    out, depth, "FOR",
                    "hasCond" to bool(s.condition != null),
                    "hasStep" to bool(s.step != null),
                )
                s.condition?.let { expr(out, depth + 1, it) }
                s.step?.let { stmt(out, depth + 1, it) }
                block(out, depth + 1, s.body)
            }

            is BreakStmt -> line(out, depth, "BREAK")

            is ContinueStmt -> line(out, depth, "CONTINUE")

            is FailStmt -> {
                line(out, depth, "FAIL")
                expr(out, depth + 1, s.message)
            }

            is TryStmt -> {
                line(out, depth, "TRY", "catch" to esc(s.catchName))
                block(out, depth + 1, s.body)
                block(out, depth + 1, s.catchBody)
            }
        }
    }

    private fun switchCase(out: StringBuilder, depth: Int, c: SwitchCase) {
        line(out, depth, "CASE", "enum" to esc(c.enumName), "value" to esc(c.valueName))
        block(out, depth + 1, c.body)
    }

    // ------------------------------------------------------------------ 表达式

    private fun args(out: StringBuilder, depth: Int, list: List<Expr>) {
        line(out, depth, "ARGS", "count" to list.size.toString())
        for (e in list) expr(out, depth + 1, e)
    }

    private fun expr(out: StringBuilder, depth: Int, e: Expr) {
        when (e) {
            // 字面量：值一律走 JVM 的 toString（Int / Long / Float / Double / String / Char / Boolean），
            // rsf 侧用同一批 JVM 方法算，所以像 `3.141592653589793238` 这种
            // 「源码文本 ≠ 值的十进制表示」的写法两端也能对上。
            is IntLit -> line(out, depth, "INT_LIT", "value" to e.value.toString())
            is LongLit -> line(out, depth, "LONG_LIT", "value" to e.value.toString())
            is FloatLit -> line(out, depth, "FLOAT_LIT", "value" to e.value.toString())
            is DoubleLit -> line(out, depth, "DOUBLE_LIT", "value" to e.value.toString())
            is StringLit -> line(out, depth, "STRING_LIT", "value" to esc(e.value))
            is CharLit -> line(out, depth, "CHAR_LIT", "value" to esc(e.value.toString()))
            is BoolLit -> line(out, depth, "BOOL_LIT", "value" to bool(e.value))
            is NullLit -> line(out, depth, "NULL_LIT")
            is ThisExpr -> line(out, depth, "THIS")

            // 阶段 25：`super` 接收者。`target` 是 `super(接口名)` 里那个接口名，裸 `super` 打 `-`。
            is SuperExpr -> line(
                out, depth, "SUPER",
                "target" to (e.target?.let { typeText(it) } ?: "-"),
            )

            is VarExpr -> line(out, depth, "VAR", "name" to esc(e.name))

            is CallExpr -> {
                line(out, depth, "CALL", "name" to esc(e.name))
                args(out, depth + 1, e.arguments)
            }

            is NewExpr -> {
                val ta = if (e.typeArgs.isEmpty()) "-" else e.typeArgs.joinToString(",") { typeText(it) }
                line(out, depth, "NEW", "class" to esc(e.className), "typeArgs" to ta)
                args(out, depth + 1, e.arguments)
            }

            is NewArrayExpr -> {
                line(out, depth, "NEW_ARRAY", "elem" to typeText(e.elementType))
                line(out, depth + 1, "DIMS", "count" to e.dimensions.size.toString())
                for (d in e.dimensions) expr(out, depth + 2, d)
            }

            is MemberExpr -> {
                line(out, depth, "MEMBER", "name" to esc(e.name), "call" to bool(e.arguments != null))
                expr(out, depth + 1, e.receiver)
                e.arguments?.let { args(out, depth + 1, it) }
            }

            is IndexExpr -> {
                line(out, depth, "INDEX")
                expr(out, depth + 1, e.receiver)
                expr(out, depth + 1, e.index)
            }

            is UnaryExpr -> {
                line(out, depth, "UNARY", "op" to e.op.name)
                expr(out, depth + 1, e.operand)
            }

            is BinaryExpr -> {
                line(out, depth, "BINARY", "op" to e.op.name)
                expr(out, depth + 1, e.left)
                expr(out, depth + 1, e.right)
            }

            is IfExpr -> {
                line(out, depth, "IF_EXPR")
                expr(out, depth + 1, e.condition)
                expr(out, depth + 1, e.whenTrue)
                expr(out, depth + 1, e.whenFalse)
            }

            is ElvisExpr -> {
                line(out, depth, "ELVIS")
                expr(out, depth + 1, e.left)
                expr(out, depth + 1, e.right)
            }

            is ForceExpr -> {
                line(out, depth, "FORCE")
                expr(out, depth + 1, e.operand)
            }

            is AsExpr -> {
                line(out, depth, "AS", "safe" to bool(e.safe))
                expr(out, depth + 1, e.operand)
                line(out, depth + 1, "TARGET", "type" to typeText(e.type))
            }

            is IsExpr -> {
                line(out, depth, "IS", "negated" to bool(e.negated))
                expr(out, depth + 1, e.operand)
                line(out, depth + 1, "TARGET", "type" to typeText(e.type))
            }
        }
    }

    // ------------------------------------------------------------------ 类型与工具

    /**
     * 类型的规范写法：`int`、`?string`、`Box&<int>`、`Box&<Box&<int>>`、`int[][]`、`Marker::Impl`。
     *
     * 与 `TypeRef.display()` **故意不同**：那个是打给人看的（`Box&<int>`，逗号后面带空格），
     * 这里的值里**不能有空格**，所以逗号后面不空格。两端都按这条规则拼，所以是一致的。
     *
     * 嵌套类型（`注解名::伴生名`，例如 `Marker::Impl`）打**完整**写法：类型实参只可能写在第一段
     * （`Outer&<int>::Inner` 里的 `&<int>` 属于 `Outer`），所以 `::` 部分不打类型实参。
     */
    private fun typeText(t: TypeRef): String {
        val head = if (t.typeArgs.isEmpty()) {
            t.name
        } else {
            t.name + "&<" + t.typeArgs.joinToString(",") { typeText(it) } + ">"
        }
        val nested = t.nested?.let { "::$it" } ?: ""
        return head + nested + "[]".repeat(t.arrayDepth)
    }

    private fun bool(b: Boolean): String = if (b) "true" else "false"

    private fun line(out: StringBuilder, depth: Int, kind: String, vararg fields: Pair<String, String>) {
        repeat(depth) { out.append("  ") }
        out.append(kind)
        for ((k, v) in fields) {
            out.append(' ').append(k).append('=').append(v)
        }
        out.append('\n')
    }

    /**
     * 值里的空格 / 换行 / 反斜杠都要转义掉（值本身不许含分隔符）。
     *
     * `rsfSrc/bootstrap/Parser.rsf` 的 `escapeValue` 是同一个表的复刻，改一处必须改两处。
     */
    private fun esc(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                ' ' -> out.append("\\s")
                else -> if (c.code < 0x20) {
                    out.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        return out.toString()
    }
}
