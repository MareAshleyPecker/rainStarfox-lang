package org.example.complie.ast

import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind

/** 所有 AST 节点的公共接口。 */
sealed interface Node {
    val at: SourceLine
}

// ------------------------------------------------------------------ 类型引用

/**
 * 源码里写出来的类型名。`null` 作为返回类型时归一化成 `void`。
 *
 * [typeArgs] 是泛型的实参：`Box&<int>` → name = `Box`、typeArgs = [`int`]。
 * 不写 `&<...>` 的类型（`int`、`Point`）typeArgs 为空，所以老代码不用改。
 *
 * [arrayDepth] 是数组后缀的层数（阶段 13）：`int[]` → 1、`Box&<int>[]` → 1、
 * `int[][]` → 2。**不并进 [name]**：`name` 在几十处被当成「基础类型名 / 类名」直接比对
 * （`ret.type.name == "void"`、`fromName(ref.name)`…），把 `[]` 拼进去会让那些地方
 * 全部静默失效；维度单独一个字段，只有真正关心数组的地方才读它。
 */
data class TypeRef(
    val name: String,
    override val at: SourceLine,
    val typeArgs: List<TypeRef> = emptyList(),
    /** 数组维度：`int[]` → 1，不是数组 → 0。 */
    val arrayDepth: Int = 0,
    /**
     * 嵌套类型写的**内层名字**（阶段 21）：`Marker::Impl` → name = `Marker`、nested = `Impl`；
     * 普通类型（`int`、`Point`）是 `null`，所以既有代码一行都不用改。
     *
     * **v1 只支持一层**（`A::B::C` 在 Parser 就报错），所以这里是一个名字而不是列表。
     * 内部名（`Marker$Impl`）由语义层自己拼——前端只管把 owner（[name]）与内层名字
     * 都留住，`display()` 原样写成 `Marker::Impl` 给人和报错看。
     */
    val nested: String? = null,
) : Node {
    /** 写给人看的完整类型名：`Box&<int>`、`Box&<Box&<int>>`、`int[][]`、`Marker::Impl`。 */
    fun display(): String {
        val base = if (typeArgs.isEmpty()) name else "$name&<" + typeArgs.joinToString(", ") { it.display() } + ">"
        val whole = if (nested == null) base else "$base::$nested"
        return whole + "[]".repeat(arrayDepth)
    }
}

// ------------------------------------------------------------------ 注解（阶段 21）

/**
 * 注解声明（阶段 21）：
 * ```
 * public annotation Marker(value : string = "x", count : int = 0) {
 *     companion entity Impl : Runnable {
 *         var hits : int = 0
 *         fun describe() -> string { rtn "Marker(" + value + "," + count + ")" }
 *         fun run() -> void { println("run " + value) }
 *     }
 * }
 * ```
 *
 * ## 形状
 * - `[public|protected|private] annotation 名字(元素表) { 伴生实体? }`，**缺省 public**；
 * - 元素表**可以整个省掉**（`annotation M { }`）也可以空着（`annotation M() { }`）；
 * - 注解体里**至多一个** [companion]——除它之外写任何东西（`fun` / `var` / `constructor`…）
 *   在 Parser 层就报错，见 [CompanionEntityDecl]。
 *
 * ## 为什么元素与行为要分家
 * [elements] 是**常量**（编译进 class 文件的那种，默认值只能是字面量），
 * 行为写在 [companion] 里——那是一个普通类体（字段/方法/构造器）。
 * 两边的规矩完全不同，所以用两个字段而不是一张混在一起的成员表。
 *
 * ## 编译产物（codegen 在后续里程碑做，这里先把口径写死）
 * 两个 class：注解自己（`ACC_ANNOTATION|ACC_INTERFACE`）与伴生类
 * （`注解名$内层名`，匿名时 `注解名$companion`）。
 */
data class AnnotationDecl(
    val control: ControlType,
    val name: String,
    /** 元素表：`(value : string = "x", count : int = 0)`。空表 = 没写括号或括号里空的。 */
    val elements: List<AnnotationElement>,
    override val at: SourceLine,
    /**
     * 伴生实体；没写就是 `null`。
     *
     * 「至多一个」在 Parser 强制（第二个 `companion` 当场报错），所以这里不是列表。
     */
    val companion: CompanionEntityDecl? = null,
    /** 写在注解声明自己前面的注解（元注解）：`@Meta annotation M { }`，v1 只解析不做语义。 */
    val annotations: List<AnnotationUse> = emptyList(),
) : Node

/**
 * 注解元素：`value : string = "x"`（名字 + 类型 + 可选的常量默认值）。
 *
 * **不复用 [Param]**：那个没有默认值槽，而默认值是注解元素的一半价值
 * （使用点不写实参时取它）。硬塞一个可空字段进去会让所有普通参数都多背一个
 * 「这里不可能是默认值」的状态。
 *
 * 元素类型只写类型，**默认值限定为字面量常量**（int / long / float / double / bool /
 * string / char / 它们的 `-` 前缀 / `null`）——这是 Parser 强制的，见
 * `Parser.parseAnnotationElementDefault`。枚举常量、注解、数组默认值 v1 明确不支持。
 */
data class AnnotationElement(
    val name: String,
    val type: TypeRef,
    /** 常量默认值表达式的**源码文本**（`"x"` / `3` / `-1.5` / `true` / `null`）。 */
    val default: Expr?,
    override val at: SourceLine,
) : Node

/**
 * 伴生实体（阶段 21）：`companion entity [名字] [: 父接口, 父接口] { 类体 }`。
 *
 * - 名字**可以省**（匿名）：那就只在源码里占位，内部名由语义层拼成 `注解名$companion`；
 * - 父类型表与类/接口的 `:` 子句**同一套语法**（[ClassDecl.supertypes]），
 *   所以直接复用 [parseSupertypeList] 的结果；
 * - 体就是普通类体：字段 / 方法 / 构造器，和类体用同一批解析函数，
 *   所以 `override` / `static` / 权限修饰词在这里的规矩与类里完全一致。
 *
 * [methods] 与 [ClassDecl.methods] 同一约定：**构造器也装在里面**（靠
 * [FuncDecl.isConstructor] 区分），顺序按源码。
 */
data class CompanionEntityDecl(
    val name: String?,
    val fields: List<FieldDecl>,
    val methods: List<FuncDecl>,
    val supertypes: List<TypeRef>,
    override val at: SourceLine,
) : Node {
    /** 所有构造器（按源码顺序）。 */
    val constructors: List<FuncDecl> get() = methods.filter { it.isConstructor }

    /** 除构造器之外的普通方法。 */
    val plainMethods: List<FuncDecl> get() = methods.filter { !it.isConstructor }

    /** 内部名：匿名伴生给 `$companion`，具名给 `$名字`（语义层拼全名时用）。 */
    val internalSuffix: String get() = "\$" + (name ?: "companion")
}

/**
 * 一个注解使用点：`@Marker(value = "hi", count = 3)`。
 *
 * - [name] 是注解名（v1 只认一个标识符，不认 `a.b.C` 这种路径写法）；
 * - [arguments] 区分**位置式**与**具名式**（[AnnotationArg]）——具名实参
 *   **只在注解使用点开放**，普通调用的语法一行都没动；
 * - 位置与具名可以混写，顺序原样保留（`@M("x", count = 1)`）。
 */
data class AnnotationUse(
    val name: String,
    val arguments: List<AnnotationArg>,
    override val at: SourceLine,
) : Node {
    /** 写给人看的写法，报错文案里用。 */
    fun display(): String =
        if (arguments.isEmpty()) "@$name"
        else "@$name(" + arguments.joinToString(", ") { it.display() } + ")"
}

/**
 * 把一个表达式还原成**源码文本**（只给注解那一小撮地方用：报错文案、以及
 * `AstDump` 之外的自查）。
 *
 * 为什么不给 [Expr] 加一个通用 `display()`：AST 是**解析结果**，不是语法树的可逆表示——
 * 写成 `a  +  b` 的表达式还原出来只能是 `a + b`，别处没人需要这种“看着像源码”的字符串，
 * 加了只会让人以为它和源文件逐字节对得上。覆盖范围就按注解里真会出现的表达式来：
 * 字面量、`-` 前缀、以及 `AnnotationUse` 里被原样解析出来的少量形式；其余给个 `?` 兜底
 * （它只可能出现在报错文案里，不会参与任何判等）。
 */
internal fun exprText(e: Expr): String = when (e) {
    is IntLit -> e.value.toString()
    is LongLit -> e.value.toString()
    is FloatLit -> e.value.toString()
    is DoubleLit -> e.value.toString()
    is StringLit -> "\"" + e.value + "\""
    is CharLit -> "'" + e.value + "'"
    is BoolLit -> e.value.toString()
    is NullLit -> "null"
    is VarExpr -> e.name
    is UnaryExpr -> (if (e.op == TokenKind.MINUS || e.op == TokenKind.BANG) e.op.text else e.op.text + " ") +
        exprText(e.operand)
    is BinaryExpr -> exprText(e.left) + " " + e.op.text + " " + exprText(e.right)
    is CallExpr -> e.name + "(" + e.arguments.joinToString(", ") { exprText(it) } + ")"
    else -> "?"
}

/**
 * 注解使用点的一个实参：位置式 `"hi"` 或具名式 `value = "hi"`。
 *
 * 用**两个节点**（而不是一个带可空名字的节点）表达：下游读具名实参时不用先判空，
 * 读位置式实参时也不会看到「名字可能是个幌子」的状态。
 */
sealed interface AnnotationArg : Node {
    /** 实参表达式。 */
    val value: Expr

    /** 写给人看的写法（位置式就是值本身，具名式带 `名字 = ` 前缀）。 */
    fun display(): String
}

/** 位置式实参：`@Marker("hi")` 里的 `"hi"`。 */
data class PositionalAnnotationArg(
    override val value: Expr,
    override val at: SourceLine,
) : AnnotationArg {
    override fun display(): String = exprText(value)
}

/** 具名实参：`@Marker(value = "hi")` 里的 `value = "hi"`。 */
data class NamedAnnotationArg(
    val name: String,
    override val value: Expr,
    override val at: SourceLine,
) : AnnotationArg {
    override fun display(): String = "$name = ${exprText(value)}"
}

// ------------------------------------------------------------------ 声明

/**
 * 函数签名最左边的访问控制槽 `<ControlType>`：`public` / `protected` / `private` / 缺省。
 *
 * **缺省就是 `public`**（不是 protected、也不是「模块内可见」）：什么都不写 =
 * 谁都能调，编译成 `ACC_PUBLIC`，字节码层面和显式写 `public` 一模一样。
 * 这一条在 `rsf check` 的清单里也会**直接显示成 `public`**（见 [label]），
 * 免得空白让人误以为「没有权限」。
 *
 * 各档在 rsf 里的含义（Checker 强制，和 JVM 访问标志一一对应）：
 *
 * - `public`    —— 谁都能调，包括外部的 Java/Kotlin 代码（编译成 `ACC_PUBLIC`）
 * - `protected` —— 本文件 + 同一次编译展开进来的其他文件（编译成 `ACC_PROTECTED`；
 *                  rsf 生成的类都在同一个默认包里，所以效果就是「同一批代码之间可见」）
 * - 缺省        —— **同 `public`**（编译成 `ACC_PUBLIC`）
 * - `private`   —— **只有声明它的那个 .rsf 文件**能调（编译成 `ACC_PRIVATE`）
 *
 * 注意：这里**不含** `void`。`void` 归 `<MainType>`（`fun` 之后、函数名之前的类型槽）。
 */
enum class ControlType(val keyword: String) {
    DEFAULT(""),
    PUBLIC("public"),
    PROTECTED("protected"),
    PRIVATE("private"),
    ;

    /**
     * 列表 / 提示里显示用的名字。
     *
     * 缺省那一档显示成 `public`——缺省就是 public，显示成空白会让用户以为
     * 「这个函数没有权限」。想区分「写没写」看源码，别在清单里猜。
     */
    val label: String get() = if (this == DEFAULT) PUBLIC.keyword else keyword

    /** 是不是对外可见（`public` 或什么都没写）。 */
    val isPublic: Boolean get() = this == DEFAULT || this == PUBLIC

    companion object {
        fun of(kind: TokenKind): ControlType = when (kind) {
            TokenKind.KW_PUBLIC -> PUBLIC
            TokenKind.KW_PROTECTED -> PROTECTED
            TokenKind.KW_PRIVATE -> PRIVATE
            else -> DEFAULT
        }
    }
}

/**
 * 返回类型子句的两种等价写法（`-> T` / `: T`），外加省略。
 *
 * 2026-10-15 拍板：`:: T` 那条别名**已删除**——`::` 腾出来给 `xxx::xx` 这种嵌套写法
 * （伴生类写作 `Marker::Impl`）。`:` 加回来当返回标记（2026-10-08 曾经删过它，
 * 因为当时三种写法并存容易看混；现在只剩 `->` 与 `:` 两种，且有别的符号可用）。
 * 写了 `:: T` 由 `Parser.parseFuncDecl` 直接报错，并提示改用 `->` 或 `:`。
 */
enum class ReturnStyle(val symbol: String) {
    ARROW("->"),
    COLON(":"),
    OMITTED("(缺省)"),
}

/**
 * 返回类型子句：`-> T` / `: T`，可带 `!`（可能出错）前缀。
 *
 * 可空性写在类型名里（`?int`），不再单列一个字段。
 *
 * `!T`（[mayError]）的意思是「这个函数可能失败」：正常路径上它的返回值就是 `T`，
 * 出错时走**异常**这条路（JVM 上就是 RuntimeException）。调用方必须显式处理，
 * 三种写法：包进 `try` / 用 `?:` 给兜底值 / 用 `!!` 往上抛（阶段 14）。
 */
data class ReturnSpec(
    val type: TypeRef,
    val style: ReturnStyle,
    val mayError: Boolean,
    override val at: SourceLine,
) : Node {
    val nullable: Boolean get() = type.name.startsWith("?")

    /** 写给人看的完整写法，例如 `: ?int`。 */
    fun display(): String {
        val mark = if (mayError) "!" else ""
        return "${style.symbol} $mark${type.display()}"
    }
}

data class Param(
    val type: TypeRef,
    val name: String,
    override val at: SourceLine,
) : Node

/**
 * 函数声明：`<ControlType> <static> fun <MainType> 名字() <返回子句> 函数体`
 *
 * - [mainType] 是 `fun` 之后、函数名之前的类型槽，缺省 `void`（目前只接受 void）。
 * - [isStatic] 对应 `<static>` 槽：控制**怎么调用**——写了就是「类名.方法」，
 *   不写就是「对象.方法」。顶层函数本来就是静态的，所以顶层的 `static` 只会有个多余警告。
 *
 * **构造器也复用这个节点**（阶段 18）：`ClassDecl` 的方法表里同时装着普通方法和构造器，
 * 靠 [isConstructor] / [isPrimary] 区分。这样下游（绑定与槽位分配、降级、
 * 解释器、字节码后端）一行都不用为构造器分家——构造器本来就"长得像"一个返回 void 的方法：
 *
 * - 主构造（[isPrimary]）：`class P(int x0, int y0) { ... }` 里类头那对括号，**解析时合成**，
 *   [name] 就是类名，[body] 是**空块**（主构造的"体"就是字段初值）。
 * - 次构造：`constructor(参数) { ... }`，[isPrimary] 为 false。
 */
data class FuncDecl(
    val control: ControlType,
    val mainType: TypeRef,
    val name: String,
    val params: List<Param>,
    val ret: ReturnSpec,
    /**
     * 函数体。**只有接口里允许为 `null`**（阶段 20）——那表示「抽象方法」：
     * 只声明签名，实现交给实现类/子接口。
     *
     * 其它任何地方（顶层函数、类方法、构造器）都必须有体，Parser 层面就强制了；
     * 所以下游拿到的 `null` 一定是接口里的抽象方法，`checkFunction` / 降级 /
     * 两个后端都应当**跳过**它（没有体就没有代码可生成）。
     */
    val body: Block?,
    override val at: SourceLine,
    val isStatic: Boolean = false,
    /** 是不是构造器（主构造或 `constructor`）。构造器不能 static、没有返回类型、只能被 `new` 调用。 */
    val isConstructor: Boolean = false,
    /** 是不是**主**构造（类头那对括号里声明的那个，解析时合成，排在同名方法的最前面）。 */
    val isPrimary: Boolean = false,
    /**
     * 写没写 `override` 修饰词（阶段 20 的追加件）。
     *
     * 语义照 Java 的 `@Override`：**只是写给编译器的一句断言**——写了就必须真的覆盖到
     * 父类型（类这里是父接口）里的某个方法，否则报错；**不写也照样能覆盖**（旧代码
     * 一行都不用改，所有既有诊断文案也保持原样）。所以它**不进** [control]：那是可见性，
     * `ControlType` 里只有 public / private / protected。
     */
    val isOverride: Boolean = false,
    /**
     * 写在方法前面的注解（阶段 21）：`@Marker fun f() -> void { }`。
     *
     * 加在**构造参数末尾**且带默认值（空表），所以既有的 `FuncDecl(...)` 调用点
     * 一行都不用改。注解和修饰词一样排在 `fun` 前面，**顺序任意**（`@M public static fun`
     * 与 `public @M fun` 等价）。主构造与次构造同样可以带注解——它们本来就复用这个节点。
     */
    val annotations: List<AnnotationUse> = emptyList(),
) : Node {
    /**
     * 抽象方法：只有签名没有体（[body] == null）。**只可能出现在接口里**——
     * 类的成员方法 Parser 就要求写出 `{ ... }`。
     */
    val isAbstract: Boolean get() = body == null
}

/**
 * 顶层 import。三种写法对应三种来源：
 *
 * - `import java.lang.Math`  —— JVM 类（点分路径，Java/Kotlin 风格）
 * - `import "lib/util.rsf"`  —— 另一个 rsf 源文件（字符串路径，Go 风格；路径可以跨行写）
 * - `module "lib"`           —— 一个目录下的所有 `.rsf`（模块导入）
 */
sealed interface ImportDecl : Node {
    val path: String
}

/** JVM 类导入，例如 `import java.lang.Math`。 */
data class ClassImport(
    override val path: String,
    override val at: SourceLine,
) : ImportDecl

/** 单个 rsf 源文件导入，例如 `import "lib/util.rsf"`。 */
data class SourceImport(
    override val path: String,
    override val at: SourceLine,
) : ImportDecl

/** 目录（模块）导入：该目录下所有 `.rsf` 都并进来。 */
data class ModuleImport(
    override val path: String,
    override val at: SourceLine,
) : ImportDecl

data class Program(
    val imports: List<ImportDecl>,
    val functions: List<FuncDecl>,
    /** 用户自定义类（阶段 12）。顶层函数和类是同一个命名空间里的两种声明。 */
    val classes: List<ClassDecl> = emptyList(),
    /**
     * 包名（点分形式，如 `a.b`），文件开头写了 `pkg a.b` 才有。
     *
     * 包就是一个目录：`pkg a.b` 意味着这个文件在 `./a/b/` 下（以当前目录为准），
     * 生成 .class 时它同时是 JVM 的包名。
     */
    val packageName: String? = null,
    /**
     * 枚举声明（阶段 19c）。
     *
     * 和类**共用同一个类型命名空间**（都占一个名字，重名要报错），所以单独一张表
     * 只是为了让后端能一眼看出「哪些名字是枚举」——类型解析、成员访问、赋值、
     * 数组、可空全都走类那一套（枚举的静态类型就是 `RsfType.external(枚举名)`）。
     */
    val enums: List<EnumDecl> = emptyList(),
    /**
     * 接口声明（阶段 20）。
     *
     * 和类**共用同一个类型命名空间**（重名要报错），单独一张表的原因和 [enums] 一样：
     * 让下游一眼看出「哪些名字是接口」——它们的成员规则、`new` 规则、调用指令
     * 都和类不一样。静态类型仍然是 `RsfType.external(接口名)`（引用类型那一套）。
     */
    val interfaces: List<InterfaceDecl> = emptyList(),
    /**
     * 注解声明（阶段 21）。和类/接口**共用同一个类型命名空间**（重名要报错），
     * 单独一张表的原因和 [enums] / [interfaces] 一样：让下游一眼看出「哪些名字是注解」。
     *
     * 语义层本阶段**忽略**注解（不认识就是不认识），所以这张表当下只有前端与
     * 编辑器在消费；加它也是为了让 AST 里「注解声明真的被解析出来了」有据可查。
     */
    val annotations: List<AnnotationDecl> = emptyList(),
) : Node {
    override val at: SourceLine get() = SourceLine(1, 1)
}

// ------------------------------------------------------------------ 枚举

/**
 * 枚举声明（阶段 19c）：
 * ```
 * public enum TokenKind {
 *     IDENT,
 *     INT_LIT,
 *     STRING,
 *     EOF
 * }
 * ```
 *
 * 语法规则（parser 强制）：
 * - `<ControlType>` 可选，写在最左，**缺省 public**（和 `fun` / `class` 一样）；
 * - 值用逗号分隔，**允许尾逗号**（`IDENT, INT_LIT,`——写多行时不用为了最后一行删逗号）；
 * - **至少要有一个值**（空枚举没有任何值可取，留到真需要时再说）；
 * - 值名重复、枚举名与类名/函数名重名都在 Checker 里报错。
 *
 * ## 编译口径：枚举 = 普通类，**不是** JVM 的 enum
 *
 * 生成出来的类（字节码后端 [org.example.complie.codegen.BytecodeCompiler]）长这样：
 * ```
 * public final class TokenKind extends java.lang.Object {
 *     public static final TokenKind IDENT;      // 每个值一个单例（引用相等即可判等）
 *     ...
 *     private final String name;                // 私有的名字字段
 *     private TokenKind(String name);           // 私有构造器：外面造不出第二个实例
 *     public String toString() { rtn name; }    // 打印出来就是值的名字
 *     static { IDENT = new TokenKind("IDENT"); ... }   // <clinit> 里构造全部单例
 * }
 * ```
 *
 * 为什么**不用** JVM 的 `enum`（`ACC_ENUM` + 继承 `java.lang.Enum`）：那需要
 * `java.lang.Enum` 的构造器约定、`values()` / `valueOf()` 合成方法、以及
 * `Class.getEnumConstants` 那一整套，而我们真正要的只有两件事——
 * **一组具名单例**、**打印出来是名字**。用普通类表达，解释器（一个带名字的
 * 单例对象）与字节码（`GETSTATIC` 取单例 + `toString()` 返回名字）两端都是
 * 最直接的那条路，不需要任何特殊指令。代价是它在 JVM 层面不是一个真的 enum
 * （外部 Java 代码不能 `switch` 它），这一点在 README 里写明了。
 */
data class EnumDecl(
    val control: ControlType,
    val name: String,
    val values: List<EnumValue>,
    override val at: SourceLine,
) : Node

/** 枚举里的一个值：`IDENT`。 */
data class EnumValue(
    val name: String,
    override val at: SourceLine,
) : Node

// ------------------------------------------------------------------ 类

/**
 * 字段声明：`var 名字 : 类型 = 0` / `imutvar 名字 : string = "?"`。
 *
 * 类型和初值都必须写：类型用 **Kotlin 后缀风格**（跟局部变量 `var 名字 : 类型 = 初值`
 * 一致，类型写在名字后面），初值必填是为了避开「字段没初始化」这种 JVM 上
 * 要靠默认值兜底的灰色地带。
 */
data class FieldDecl(
    val name: String,
    val type: TypeRef,
    val initializer: Expr,
    val mutable: Boolean,
    override val at: SourceLine,
    /**
     * 写在字段声明前面的注解（阶段 21）：`@Marker var hits : int = 0`。
     *
     * 加在**构造参数末尾**并带缺省值：既有构造点（解释器、后端、测试里的手写 AST）
     * 一行都不用改，语义层也照旧忽略它。
     */
    val annotations: List<AnnotationUse> = emptyList(),
) : Node

/**
 * 类声明：`<ControlType> class 类名 <&<T>> (<主构造参数>) { 字段... 方法... 构造器... }`（阶段 18 起）。
 *
 * ```
 * public class Point(int x0, int y0) {      // 主构造：参数写在类头，字段初值里能用
 *     var x : int = x0
 *     var y : int = y0
 *     public fun sum() -> int { rtn x + y }
 * }
 *
 * public class Box&<T> {                   // 泛型：类体里 T 可以当类型用
 *     var value : T = 0
 *     public fun get() -> T { rtn value }
 * }
 *
 * public class Q {                          // 次构造（可以写多个 → 构造器重载）
 *     var v : int = 0
 *     constructor() { v = 0 }
 *     constructor(n : int) { v = n }
 * }
 * ```
 *
 * [methods] 里**同时**装着普通方法和构造器：主构造（如果写了类头括号）排在最前面，
 * 后面按源码顺序。用 [constructors] / [plainMethods] 分开取。
 */
data class ClassDecl(
    val control: ControlType,
    val name: String,
    val fields: List<FieldDecl>,
    val methods: List<FuncDecl>,
    override val at: SourceLine,
    /**
     * 类型参数表：`class Box&<T>` → `["T"]`，`class Pair&<K, V>` → `["K", "V"]`。
     * 没有 `&<...>` 子句的类是空表，所以非泛型类的代码一行都不用改。
     */
    val typeParams: List<String> = emptyList(),
    /**
     * 父类型列表（阶段 20）：`class Dog(name : string) : Greeter, Runnable { ... }`。
     *
     * 没写 `:` 子句就是空表（**既有代码因此一行都不用改**）。
     *
     * 本批只支持**接口**：Checker 对每一项查表——查到接口就登记为实现，
     * 查到类就报「暂不支持类继承」，查不到就报未知类型（D2：绝不静默忽略）。
     * 类型实参写在名字后面（`class C : Box&<int>`，擦除后 `LBox;`）。
     */
    val supertypes: List<TypeRef> = emptyList(),
    /**
     * 写在 `class` 前面的注解（阶段 21）：`@Marker public class C { }`。
     *
     * 缺省空表——既有 `ClassDecl(...)` 调用点一行都不用改。顺序任意：
     * `@M public class C` 与 `public @M class C` 解析结果一样。
     */
    val annotations: List<AnnotationUse> = emptyList(),
) : Node {
    /**
     * 所有构造器：主构造（写了类头括号才有）在前，次构造（`constructor`）按源码顺序在后。
     *
     * 空表有明确含义：**这个类没写任何构造器** ⇒ 等价于一个隐式的无参主构造
     * （`new 类名()` 照样能用，字段初值照跑）。写了任何一个构造器之后就不会再有隐式的了——
     * 和 Java / Kotlin 一样。
     */
    val constructors: List<FuncDecl> get() = methods.filter { it.isConstructor }

    /** 主构造；没写类头括号就是 null。 */
    val primaryConstructor: FuncDecl? get() = methods.firstOrNull { it.isPrimary }

    /** 除构造器之外的普通方法。 */
    val plainMethods: List<FuncDecl> get() = methods.filter { !it.isConstructor }
}

// ------------------------------------------------------------------ 接口

/**
 * 接口声明（阶段 20）：
 * ```
 * public interface Greeter {
 *     fun greet(name : string) -> string                       // 抽象方法：只写签名
 *     fun shout(name : string) -> string {                     // 默认方法：带体
 *         rtn toUpper(greet(name))
 *     }
 * }
 *
 * class Dog(n : string) : Greeter {                            // 实现：沿用继承的 ':'
 *     public fun greet(name : string) -> string { rtn "woof " + name }
 * }
 *
 * interface Loud : Greeter { }                                 // 接口继承接口（D4）
 * ```
 *
 * ## 为什么是**独立节点**（D1），不给 [ClassDecl] 加个标志
 *
 * 接口和类的成员规则根本不同：接口**没有构造器、没有字段**（写字段直接报错）、
 * 成员天然 public、方法可以**没有体**。这些差异如果靠一个 `isInterface: Boolean`
 * 表达，[ClassDecl] 里那四个字段（[ClassDecl.fields]、[ClassDecl.methods]、
 * [ClassDecl.typeParams]…）就各自多出一条「接口时无意义」的分支，下游每读一次
 * 都要先问一句「这是不是接口」。独立节点把差异摊在类型上：读 [ClassDecl] 的代码
 * 天然不用管接口，读 [InterfaceDecl] 的代码天然不用管构造器。
 *
 * ## 编译产物（codegen 在 M3 做，这里先把口径写死）
 *
 * JVM 接口：`ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT`，超类是 `java/lang/Object`，
 * **没有 `<init>`**（接口不可实例化）；抽象方法写 `ACC_PUBLIC | ACC_ABSTRACT`
 * 且**不写 Code 属性**，默认方法照普通方法写 Code。父接口列表进 `interfaces` 数组。
 *
 * ## 成员
 *
 * [methods] 按源码顺序装全部方法：抽象方法（[FuncDecl.body] == null）与默认方法混在
 * 一张表里，靠 [FuncDecl.isAbstract] 区分——调用点只关心「有没有默认实现可以去」，
 * 不关心它来自接口还是类。
 */
data class InterfaceDecl(
    val control: ControlType,
    val name: String,
    /** 接口体里声明的方法，按源码顺序（**只有方法**，字段在 Parser 就被拦下）。 */
    val methods: List<FuncDecl>,
    /**
     * 父接口列表（D4）：`public interface A : B, C { ... }`，空表 = 没有 `: ...` 子句。
     *
     * 和 [ClassDecl.supertypes] 用同一个表示（[TypeRef]）：`:` 子句在类和接口两边
     * 语法完全一样，Checker 只是对每个名字**查不同的表**——接口表命中就是父接口，
     * 类表命中就报「接口不能继承类」。
     */
    val supertypes: List<TypeRef> = emptyList(),
    override val at: SourceLine,
    /**
     * 类型参数表：`interface Box&<T>` → `["T"]`，语义与 [ClassDecl.typeParams] 相同。
     *
     * 本批只要求「能声明、能实现、能调用」（DESIGN-20 §3 的泛型一行）：方法体里
     * 不做类型实参推导增强，擦除后一律 `L接口名;`。
     */
    val typeParams: List<String> = emptyList(),
    /**
     * 写在 `interface` 前面的注解（阶段 21）：`@Marker interface I { }`。缺省空表。
     */
    val annotations: List<AnnotationUse> = emptyList(),
) : Node {
    /** 抽象方法：只声明签名，实现类/子接口必须提供（多接口默认方法冲突也看这里）。 */
    val abstractMethods: List<FuncDecl> get() = methods.filter { it.isAbstract }

    /** 默认方法：带体的方法，实现类不写也能调到。 */
    val defaultMethods: List<FuncDecl> get() = methods.filter { !it.isAbstract }
}

// ------------------------------------------------------------------ 语句

sealed interface Stmt : Node

/** `{ ... }` —— 引入一个新的块作用域。 */
data class Block(
    val statements: List<Stmt>,
    override val at: SourceLine,
) : Stmt

/**
 * 变量声明。
 *
 * - `var x = 1`   → [mutable] = true，之后可以 `x = 2`
 * - `imutvar x = 1` → [mutable] = false，重新赋值会被 Checker 拦住
 *
 * [declaredType] 是可选的类型标注，**Kotlin 后缀风格**：`var s : ?string = null`、
 * `var b : Box&<int> = ...`、`var a : int[] = new int[3]`。
 * 不写就从初始化式推断；写 `= null` 时必须写标注，否则没有类型信息可用。
 */
data class VarDeclStmt(
    val name: String,
    val initializer: Expr,
    val mutable: Boolean,
    override val at: SourceLine,
    val declaredType: TypeRef? = null,
) : Stmt

/** `x = expr` */
data class AssignStmt(    val name: String,
    val value: Expr,
    override val at: SourceLine,
    /**
     * 编译器解糖生成的赋值（例如 `for (i in a..b)` 的步进）。
     * 这类赋值跳过 `imutvar` 的可变性检查——循环变量对用户是不可变的，
     * 但循环本身得能推进它。
     */
    val synthetic: Boolean = false,
) : Stmt

/**
 * `接收者.字段 = expr`（阶段 12）。
 *
 * 单独一个节点而不是复用 [AssignStmt]：那个只管局部变量/参数的槽位，赋值目标是一个
 * 绑定；这个的赋值目标是一次成员解析（字段），两者的语义检查和降级路径都不一样。
 */
data class MemberAssignStmt(
    /** 一定是 [MemberExpr]，且 `arguments` 为 null（不能给方法赋值）。 */
    val target: MemberExpr,
    val value: Expr,
    override val at: SourceLine,
) : Stmt

/**
 * `数组[下标] = expr`（阶段 13）。
 *
 * 又一个独立的赋值节点，理由和 [MemberAssignStmt] 一样：三种赋值目标的**求值顺序、
 * 语义检查和降级路径**都不一样。
 *
 * - [AssignStmt]：写局部变量槽（目标是绑定）；
 * - [MemberAssignStmt]：先求接收者、再 `PUTFIELD`（目标是字段）；
 * - 这个：先求数组引用、再求下标、**做越界检查**、最后求右值再 `*ASTORE`。
 *
 * 目标是一个 [IndexExpr] 而不是「数组 + 下标」两个字段：这样 `p.items[0] = 1`
 * （字段数组的元素）不需要任何额外语法处理——[IndexExpr.receiver] 本来就是任意表达式。
 */
data class IndexAssignStmt(
    /** 一定是 [IndexExpr]。 */
    val target: IndexExpr,
    val value: Expr,
    override val at: SourceLine,
) : Stmt

/** `rtn` / `rtn expr` / `return` / `return expr` */
data class ReturnStmt(
    val value: Expr?,
    override val at: SourceLine,
) : Stmt

/** 表达式语句，主要用来调用函数。 */
data class ExprStmt(
    val expr: Expr,
    override val at: SourceLine,
) : Stmt

/** `名字:` —— goto 的落点。 */
data class LabelStmt(
    val name: String,
    override val at: SourceLine,
) : Stmt

/**
 * `this(实参)` —— 构造器委托（阶段 18）。
 *
 * 和 Java 的「alternate constructor invocation」一个意思：写在**构造器体的第一句**，
 * 表示「先跑另一个构造器，再回来跑我自己剩下的语句」。校验规则（Checker 强制）：
 *
 * - 只能出现在构造器体里，而且必须是**第一条语句**（Java 也是这条规矩——它保证
 *   `this` 在被使用之前一定已经被初始化过一次）；
 * - 目标必须是同一个类的另一个构造器（按实参个数与类型选，和 `new` 同一套规则）；
 * - 有主构造的类里，每个次构造都必须委托（链的根因此必然是主构造，字段初值里
 *   引用的主构造参数一定已经有值——这正是 Kotlin 那条「次构造必须委托」的来历）；
 * - 委托不能成环（`A -> B -> A` 报错）。
 *
 * 为什么不写成一个表达式节点：它不是表达式，没有值，位置也被限定死了；
 * 而且两端后端拿到的必须是**同一条经过校验的委托边**（解释器据此递归、
 * 字节码据此发 `INVOKESPECIAL <init>`），一个语句节点最省事。
 */
data class ThisCallStmt(
    val arguments: List<Expr>,
    override val at: SourceLine,
) : Stmt

/**
 * `switch (枚举值) { case 枚举名.值名 : 语句 ... else : 语句 }`（阶段 19c）。
 *
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
 * ## 四条定死的规矩
 *
 * - **`switch` 是语句，不是表达式**：没有「用 switch 取一个值」的写法（三目
 *   已经能覆盖那种需求；把 switch 做成表达式要引入「所有分支类型必须一致」
 *   以及「必须穷尽」两套新规则，本阶段不做）。
 * - **`case` 后面只接受枚举常量**（`枚举名.值名`）：没有区间、没有比较、
 *   没有字面量。理由见 [org.example.complie.ast.EnumDecl]——既然只匹配枚举常量，
 *   「缺分支」就能在**编译期**查出来（[SwitchStmt] 没有 else 时必须穷尽），
 *   而那正是这类分派最容易出的错。
 * - **没有 fallthrough**：每个分支执行完就跳出 switch（不需要 `break`，
 *   也因此不会写出「忘了写 break」那种 bug）。
 * - **默认分支写 `else`**，不是 `default`。
 */
data class SwitchStmt(
    /** 被分派的值。类型必须是某个枚举（Checker 强制）。 */
    val subject: Expr,
    val cases: List<SwitchCase>,
    /** `else : ...`；没写就是 null（这时必须穷尽）。 */
    val elseBody: Block?,
    override val at: SourceLine,
) : Stmt

/**
 * 一个 `case 枚举名.值名 : 体`。
 *
 * [body] 是一个 [Block]：单条语句的写法（`case X : rtn 1`）在 parser 里也被
 * 包成只含一条语句的块，于是「分支体」在两端只有一种形状（和 `if c : 语句` 同一套做法）。
 */
data class SwitchCase(
    /** `TokenKind`——写出来的枚举名；Checker 会核对它是不是 `switch` 主体的那个枚举。 */
    val enumName: String,
    /** `IDENT`——值名；Checker 会核对它在这个枚举里存在。 */
    val valueName: String,
    val body: Block,
    override val at: SourceLine,
    /**
     * 旧写法里 `case` 关键字的位置；写的是新写法（直接 `枚举名.值名 :`）时是 null。
     *
     * 阶段 21 起 `case` 弃用：留这个位置只是为了让 Checker 发一条带行号的弃用警告。
     */
    val deprecatedCaseAt: SourceLine? = null,
) : Node

/** `goto 名字` —— C/C++ 意义上的无条件跳转。 */
data class GotoStmt(
    val label: String,
    override val at: SourceLine,
) : Stmt

/** `if (条件) { ... } else { ... }` —— else 可省略，也可以接另一个 if 形成 else-if 链。 */
data class IfStmt(
    val condition: Expr,
    val body: Block,
    val elseBody: Block?,
    override val at: SourceLine,
) : Stmt

/** `while (条件) { ... }` */
data class WhileStmt(
    val condition: Expr,
    val body: Block,
    override val at: SourceLine,
) : Stmt

/**
 * `for` 循环的**解糖后形态**：`{ 初始化... }` 包着 `for (条件; 更新) 循环体`。
 *
 * 两种写法都在 parser 里解糖成这个节点：
 * - `for (var i = 0; i < 9; i = i + 1) { }` → 初始化就是用户写的那几条
 * - `for (i in 0..9 step 2) { }` → 初始化是合成的三个声明（终点、步长、循环变量），
 *   条件化成 `if 步长 >= 0 ? i <= 终点 : i >= 终点`
 *
 * 之所以解糖成「块 + for」，是为了让 `continue` 直接落到**更新语句**上——
 * 这正是 for 和 while 的语义差别（while 的 continue 回条件，for 的 continue 回步进）。
 */
data class ForStmt(
    /** null 表示条件恒真（`for (;;)`）。 */
    val condition: Expr?,
    /** null 表示没有更新语句。 */
    val step: Stmt?,
    val body: Block,
    override val at: SourceLine,
) : Stmt

/** `break` —— 跳出最内层 while。 */
data class BreakStmt(override val at: SourceLine) : Stmt

/** `continue` —— 直接进入最内层 while 的下一轮。 */
data class ContinueStmt(override val at: SourceLine) : Stmt

/**
 * `fail "消息"` —— 抛出一个错误（阶段 14）。
 *
 * 只能出现在两种地方（Checker 强制）：
 * - 返回类型写成 `!T`（可能失败）的函数里；
 * - 或者 `try` 块里（由这个 try 的 catch 接住）。
 *
 * JVM 上的表示就是 `throw new RuntimeException(消息)`，所以不需要新造类型。
 */
data class FailStmt(
    /** 错误消息，必须是 `string` 表达式。 */
    val message: Expr,
    override val at: SourceLine,
) : Stmt

/**
 * `try { ... } catch (名字 : string) { ... }` —— 接住 `fail` 抛出的错误（阶段 14；
 * 阶段 19b 起推荐 Kotlin 风格，老写法 `catch (名字 string)` 也接受）。
 *
 * 约定：catch 只接一个变量、类型固定是 `string`（就是错误消息本身），
 * 没有 finally、没有多重 catch——这一版只要能把错误接住就够了。
 *
 * [body] 里的 `!T` 调用视为**已处理**（异常被这个 try 接走），
 * [catchBody] 不在 try 的保护范围内，所以那里再调 `!T` 函数仍要显式处理。
 */
data class TryStmt(
    val body: Block,
    /** catch 的变量名，类型固定 `string`。 */
    val catchName: String,
    val catchBody: Block,
    override val at: SourceLine,
) : Stmt

// ------------------------------------------------------------------ 表达式

sealed interface Expr : Node

data class IntLit(val value: Int, override val at: SourceLine) : Expr
data class LongLit(val value: Long, override val at: SourceLine) : Expr
data class FloatLit(val value: Float, override val at: SourceLine) : Expr
data class DoubleLit(val value: Double, override val at: SourceLine) : Expr
data class StringLit(val value: String, override val at: SourceLine) : Expr

/**
 * 字符字面量（阶段 16）：`'a'`、`'\n'`、`'\u0041'`。
 *
 * [value] 就是那个字符本身（转义已经在词法阶段解开了），类型是 `char`。
 * 它和 `IntLit` 是两个不同的类型：`'a'` 不能当 int 用，`97` 也不能当 char 用，
 * 想互相转就用内置函数 `charToInt` / `toChar`（跟 Java 的显式转换一个意思）。
 */
data class CharLit(val value: Char, override val at: SourceLine) : Expr

data class BoolLit(val value: Boolean, override val at: SourceLine) : Expr

/** `rtn null` 里单独出现的 null —— 只在 void 函数里合法。 */
data class NullLit(override val at: SourceLine) : Expr

data class VarExpr(val name: String, override val at: SourceLine) : Expr

/** `foo(a, b)`；v0.2 仍只允许零参数调用。 */
data class CallExpr(
    val name: String,
    val arguments: List<Expr>,
    override val at: SourceLine,
) : Expr

data class UnaryExpr(
    val op: TokenKind,
    val operand: Expr,
    override val at: SourceLine,
) : Expr

data class BinaryExpr(
    val op: TokenKind,
    val left: Expr,
    val right: Expr,
    override val at: SourceLine,
) : Expr

/** 三元：`if 条件 ? 值1 : 值2` —— 两个分支都是表达式。 */
data class IfExpr(
    val condition: Expr,
    val whenTrue: Expr,
    val whenFalse: Expr,
    override val at: SourceLine,
) : Expr

/** `a ?: b` —— 左边是 null 就取右边。左边可空、右边任意，结果是非空版。 */
data class ElvisExpr(
    val left: Expr,
    val right: Expr,
    override val at: SourceLine,
) : Expr

/** `x!!` —— 强制解包；是 null 就抛 NullPointerException。 */
data class ForceExpr(
    val operand: Expr,
    override val at: SourceLine,
) : Expr

/**
 * 成员访问：`接收者.名字` 或 `接收者.名字(实参)`。
 *
 * [arguments] 为 null 表示字段访问，非 null 表示方法调用。接收者可以是
 * 变量（实例调用）、也可以是导入的类名（静态调用 / 静态字段）。
 */
data class MemberExpr(
    val receiver: Expr,
    val name: String,
    val arguments: List<Expr>?,
    override val at: SourceLine,
) : Expr

/** `new 类名(实参)` —— 分配一个对象并跑它的构造器。泛型类写成 `new Box&<int>(0)`。 */
data class NewExpr(
    val className: String,
    val arguments: List<Expr>,
    override val at: SourceLine,
    /** `&<...>` 里的实参类型；非泛型是空表。 */
    val typeArgs: List<TypeRef> = emptyList(),
) : Expr

/**
 * `new T[n]` / `new T[n][m]`（阶段 13）—— 创建数组。
 *
 * 单独一个节点而不是往 [NewExpr] 里塞字段：`new Point(1, 2)` 是「调构造器」，
 * `new Point[2]` 是「分配一块连续空间」——JVM 上是完全不同的指令（`NEW`+`INVOKESPECIAL`
 * vs `ANEWARRAY`/`NEWARRAY`/`MULTIANEWARRAY`），语义上一个跑代码、一个只填默认值。
 * 混在一个节点里，两处后端都得靠 `arguments.isEmpty()` 猜，迟早猜错。
 *
 * [elementType] 是**元素**的类型（`new int[5]` → `int`、`new int[3][4]` → `int`），
 * [dimensions] 是每一维的长度表达式（**运行时求值**，可以任意 int 表达式）。
 * 整个表达式的类型 = 元素类型 + `dimensions.size` 层 `[]`：
 * `new int[3][4]` 是 `int[][]`、`new Point[2]` 是 `Point[]`。
 *
 * 多维按「数组的数组」处理：`new int[3][4]` 在 JVM 上就是 `[[I`
 * （字节码 `MULTIANEWARRAY`），r0[i] 的类型是 `int[]`——不做「二维一块内存」的特殊语义。
 * **每一维都要写长度**：`new int[3][]`（锯齿数组）本阶段不做，parser 直接报错。
 */
data class NewArrayExpr(
    val elementType: TypeRef,
    val dimensions: List<Expr>,
    override val at: SourceLine,
) : Expr

/**
 * 下标读：`a[i]`（阶段 13）。
 *
 * 后缀运算符（见 Parser.parsePostfix），所以 `a[i][j]`、`p.items[0]`、`f()[0]`
 * 都是「一层层套」出来的，不需要任何特殊语法。
 */
data class IndexExpr(
    /** 数组表达式；类型必须是数组。 */
    val receiver: Expr,
    /** 下标；类型必须是 `int`（不做 byte/char 的隐式提升）。 */
    val index: Expr,
    override val at: SourceLine,
) : Expr

/**
 * `this`：当前对象。
 *
 * 字段可以直接按名字访问；只有在「字段和参数/局部变量重名」时才必须写 `this.x`。
 */
data class ThisExpr(
    override val at: SourceLine,
) : Expr
