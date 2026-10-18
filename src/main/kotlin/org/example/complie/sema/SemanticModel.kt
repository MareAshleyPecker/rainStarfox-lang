@file:Suppress("unused")

package org.example.complie.sema

import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.AnnotationElement
import org.example.complie.ast.AnnotationUse
import org.example.complie.ast.AssignStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.Expr
import org.example.complie.ast.FieldDecl
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.SuperCallStmt
import org.example.complie.ast.ThisCallStmt
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.VarExpr
import org.example.complie.diag.Diagnostic
import org.example.complie.diag.Severity
import org.example.complie.interop.JvmMethod
import org.example.complie.interop.ResolvedMember
import org.example.complie.interop.ResolvedMethod
import org.example.complie.ir.LoweredFunction
import org.example.complie.lexing.SourceLine
import java.util.IdentityHashMap

// ---------------------------------------------------------------- 函数类型（阶段 30）
//
// `RsfType` 本身没有「函数」这一档（阶段 30 不许改 RsfType.kt），函数类型借用
// `EXTERNAL` + 内部名 `rsf/fn/<形状>` 表示：`simpleExternalName` 取最后一个 '/' 之后
// 那段，正好是 `(int) -> int`，所以报错文案里读起来跟源码写法一样。

/** 函数类型内部名的前缀。 */
const val FN_TYPE_PREFIX = "rsf/fn/"

/** 函数类型的形状文本：`(int, string) -> bool`、`() -> void`。 */
fun fnTypeText(params: List<RsfType>, ret: RsfType): String =
    "(" + params.joinToString(", ") { it.rsfName } + ") -> " + ret.rsfName

/** 用形状文本造一个函数类型（[nullable] 就是 `?fn` 的那个 `?`）。 */
fun functionTypeOf(text: String, nullable: Boolean): RsfType =
    RsfType(RsfBase.EXTERNAL, nullable, FN_TYPE_PREFIX + text)

/** 函数类型的形状文本；不是函数类型就是 null。 */
val RsfType.fnTypeText: String?
    get() {
        if (base != RsfBase.EXTERNAL || arrayDepth != 0) return null
        val name = externalName ?: return null
        if (!name.startsWith(FN_TYPE_PREFIX)) return null
        return name.substring(FN_TYPE_PREFIX.length)
    }

/** 是不是函数类型（`(int) -> int` 及其可空形式）。 */
val RsfType.isFunctionType: Boolean get() = fnTypeText != null

/**
 * 把函数类型的形状文本解析回 `(形参类型表, 返回类型)`；不是函数类型就返回 null。
 *
 * 为什么需要**反向**解析：形状文本是阶段 30a 存进 `externalName` 的唯一信息
 * （`RsfType` 本身没有函数这一档），而后端要拿形参个数去挑 SAM 接口
 * （`__RsfFn<arity>`）、拿形参/返回类型去拼描述符。所以这里把 [fnTypeText] 写出去
 * 的文本再读回来，**只有这一份**解析实现，两个后端共用。
 *
 * 文本是我们自己按 `(", ")` 拼的（见 [fnTypeText]），所以按顶层逗号切分是安全的：
 * 嵌套的函数类型自己带着括号（`((int) -> int) -> void`），递归下降能认出来。
 * 读不懂就返回 null（调用方按「不是函数类型」处理），不在这里报错。
 */
fun fnSignatureOf(text: String): Pair<List<RsfType>, RsfType>? {
    val parser = FnTextParser(text)
    return parser.parseFunction()?.takeIf { parser.atEnd() }
}

/** [fnSignatureOf] 的递归下降实现：形如 `('?'? 类型 (', ' 类型)*)? ' -> ' 类型`。 */
private class FnTextParser(private val text: String) {
    private var i = 0

    fun atEnd(): Boolean = i >= text.length

    fun parseFunction(): Pair<List<RsfType>, RsfType>? {
        if (text.getOrNull(i) != '(') return null
        i++
        val params = ArrayList<RsfType>()
        if (text.getOrNull(i) == ')') {
            i++
        } else {
            while (true) {
                params += parseType() ?: return null
                when (text.getOrNull(i)) {
                    ',' -> i += 2 // ", "
                    ')' -> { i++; break }
                    else -> return null
                }
            }
        }
        // [fnTypeText] 拼出来的是 `(...) -> ret`：右括号和箭头之间有一个空格
        while (text.getOrNull(i) == ' ') i++
        if (!text.startsWith("-> ", i)) return null
        i += 3
        val ret = parseType() ?: return null
        return params to ret
    }

    private fun parseType(): RsfType? {
        var nullable = false
        if (text.getOrNull(i) == '?') {
            nullable = true
            i++
        }
        // 形参/返回位置上的函数类型：整段再递归一次
        if (text.getOrNull(i) == '(') {
            val (ps, ret) = parseFunction() ?: return null
            return functionTypeOf(fnTypeText(ps, ret), nullable)
        }
        val start = i
        while (i < text.length && text[i] != ',' && text[i] != ')' && text[i] != '[' && text[i] != '&') i++
        val name = text.substring(start, i)
        if (name.isEmpty()) return null
        // 泛型实参（`Pair&<string, int>`）对描述符没有影响，跳过 `<...>`
        if (text.getOrNull(i) == '&') {
            i++
            if (text.getOrNull(i) != '<') return null
            var level = 0
            while (i < text.length) {
                when (text[i++]) {
                    '<' -> level++
                    '>' -> if (--level == 0) break
                }
            }
            if (level != 0) return null
        }
        var depth = 0
        while (text.startsWith("[]", i)) {
            depth++
            i += 2
        }
        val base = when (name) {
            "void" -> RsfBase.VOID
            "bool" -> RsfBase.BOOL
            "byte" -> RsfBase.BYTE
            "char" -> RsfBase.CHAR
            "int" -> RsfBase.INT
            "long" -> RsfBase.LONG
            "float" -> RsfBase.FLOAT
            "double" -> RsfBase.DOUBLE
            "string" -> RsfBase.STRING
            else -> RsfBase.EXTERNAL
        }
        return if (base == RsfBase.EXTERNAL) {
            RsfType(RsfBase.EXTERNAL, nullable, name, arrayDepth = depth)
        } else {
            RsfType(base, nullable, null, arrayDepth = depth)
        }
    }
}

/** 用户自定义类的成员，解析完的样子。 */
sealed interface UserMember {
    /** 字段：[index] 是它在对象字段数组里的下标（按声明顺序）。 */
    data class Field(val className: String, val index: Int, val type: RsfType) : UserMember

    /** 方法（含构造器）。 */
    data class Method(
        val className: String,
        val decl: FuncDecl,
        /**
         * 解析出来的这个方法是不是**接口**声明的（阶段 20）。
         *
         * 后端据此决定发什么指令：`INVOKEINTERFACE` 还是 `INVOKEVIRTUAL`。
         * 判定的依据是**接收者的静态类型**（`g : Greeter` ⇒ true），而不是运行期类型——
         * 这正是 DESIGN-20 风险 R1 的缓解方式：判定只在 Checker 里做一次并记下来，
         * 两个后端都只读这一份，不会各自再猜一遍。
         *
         * 注意：接口方法通过**类**类型的接收者调用时是 false（`dog.greet(x)` 发
         * `INVOKEVIRTUAL Dog.greet`），哪怕 Dog 实现了 Greeter，也仍然如此。
         */
        val interfaceOwner: Boolean = false,
    ) : UserMember
}

/**
 * 一个类/接口的**已解析**父接口（阶段 20）。
 *
 * 后端拼 `ClassWriter.visit(..., interfaces = ...)` 时只该抄这里，不要自己拿
 * AST 上的名字再解析一遍：`class Dog : Greeter, Runnable` 里哪个是这份程序声明的接口、
 * 哪个是 classpath 上的 JDK 接口、实参又代换成了什么（`class IntBox : Box&<int>`），
 * 全都是 Checker 在 declareClasses / declareInterfaceBodies 里定下来的
 * （D2 的「父类型只能是接口」也是在那一刻判的）。
 */
data class SuperInterface(
    /** 源码里写的名字（rsf 接口是它的名字，JDK 接口是简名如 `Runnable`）。报错用。 */
    val name: String,
    /**
     * JVM 内部名：rsf 接口就是名字本身（rsf 的类都在默认包），
     * JDK 接口是 `java/lang/Runnable` 这种全名。字节码要的是这个。
     */
    val internalName: String,
    /** `: Box&<int>` 里那串实参（代换后的）；JDK 接口恒为空表（JVM 擦除）。 */
    val args: List<RsfType> = emptyList(),
    /** true = classpath 上的接口（JDK）；false = 这份程序里声明的接口。 */
    val fromClassPath: Boolean = false,
)

/**
 * 一条构造器委托边（阶段 18）：`constructor(...) { this(实参) ... }` 里的那句 `this(...)`。
 *
 * 存下来是为了让**两个后端拿到同一条已经校验过的边**：解释器据此递归进目标构造器，
 * 字节码据此发 `ALOAD 0; 实参; INVOKESPECIAL <init>`。实参挂在 [stmt] 上，
 * 两端都在**委托方自己的帧**里求值（和 Java 一样）。
 */
data class CtorDelegation(
    /** 委托的目标构造器（在 [ClassModel.ctorDelegations] 里按**委托方**索引）。 */
    val target: FuncDecl,
    val stmt: ThisCallStmt,
)

/**
 * 一个类的**已解析父类**（阶段 25 K1）。
 *
 * 和 [SuperInterface] 分开一张表、分开一个类型，而不是把父类塞进 `supertypes` 列表：
 * 两者的规矩不一样——父类**必须有且只有一个**、必须写构造实参、必须排在父类型列表最前面；
 * 接口可以有很多个、不许写实参。合在一个列表里，每个读它的地方都要再问一遍
 * 「这一项是类还是接口」，那正是 D2 想避免的含糊。
 *
 * 后端（K2/K3）拼 `extends` 时只该抄这里，不要自己拿 AST 上的名字再解析一遍。
 */
data class SuperClass(
    /** 源码里写的名字（rsf 类就是它的名字，JDK 类是简名如 `Thread`）。报错用。 */
    val name: String,
    /**
     * JVM 内部名：rsf 类就是名字本身（rsf 的类都在默认包），JDK 类是
     * `java/lang/Object` 这种全名。字节码要的是这个。
     */
    val internalName: String,
    /**
     * 父类的**类型实参**（`class D : Box&<int>(1)` 里的 `int`；没写泛型就是空表）。
     *
     * 和 [SuperInterface.args] 一个意思：继承来的成员里出现 `T` 时要拿它替换
     * （[SuperDelegation.arguments] 才是**构造实参**，那是求值用的表达式，不是类型）。
     */
    val args: List<RsfType> = emptyList(),
    /** true = classpath 上的类（JDK）；false = 这份程序里声明的类。 */
    val fromClassPath: Boolean = false,
    /** rsf 类的声明；JDK 父类为 null（那时看 [fromClassPath]）。 */
    val declaration: ClassDecl? = null,
) {
    /** 报错文案里的说法。 */
    val display: String get() = if (fromClassPath) "classpath 上的类 '$name'" else "类 '$name'"
}

/**
 * `super.f(实参)` 解析到的**调用目标**（阶段 25 K1；K4 缺口 C 扩成两支）。
 *
 * 为什么不是 [UserMember.Method]：`super.getMessage()` 这种调用打的是 **JDK 父类**上
 * 的方法，它没有 rsf 的 `FuncDecl` 可指——只有一条从 classpath 读出来的
 * [ResolvedMethod]。
 *
 * 两支的**共同语义**（DESIGN-25 §7，缺口 C）：`super.` 一律是**非虚调用**——
 * 打到**声明类那一份**实现，不许按运行期类再查一遍。rsf 父类上那条
 * `INVOKESPECIAL` 本来就是非虚的；JDK 父类这条只能靠 [Jvm.owner] 是**声明类**
 * 来保证（解释器侧对应 `Class.getDeclaredMethod` + `invoke`，不是按运行期类找）。
 *
 * `f` 若是 rsf 接口的默认方法（`super.<接口>.f()`），[Rsf.interfaceOwner] /
 * [Jvm.interfaceOwner] 为 true，后端据此发 `INVOKEINTERFACE`（仍带非虚语义）。
 */
sealed interface SuperCall {
    /** 目标是 rsf 父类（或 rsf 接口默认方法）上的方法：有 [FuncDecl] 可指。 */
    data class Rsf(
        /** 声明这个方法的类/接口（INVOKESPECIAL/INVOKEINTERFACE 的 owner）。 */
        val className: String,
        val decl: FuncDecl,
        /** 声明者是**接口**（默认方法）⇒ 发 `INVOKEINTERFACE`。 */
        val interfaceOwner: Boolean = false,
    ) : SuperCall

    /**
     * 目标是 classpath 上那个 JDK 类里的方法（阶段 25 K4，缺口 C）。
     *
     * [member] 的 `owner` 必须是**声明类**（`java/lang/Throwable.getMessage`），
     * 不是接收者的运行时类——这正是非虚调用的落点。
     */
    data class Jvm(val member: ResolvedMethod) : SuperCall
}

/**
 * 一条**父类构造委托**边（阶段 25 K1）：`constructor(...) { super(实参) ... }` 里那句。
 *
 * 和 [CtorDelegation]（`this(...)`）是一对，但语义不同：`this(...)` 是同类另一个构造器
 * 接着跑，`super(...)` 是先把父类那份状态建起来。两端读的必须是**同一条已经校验过的边**
 * （实参挂在 [stmt] 上，在委托方自己的帧里求值）。
 *
 * [target] 为 null 只在一种情况下出现：父类来自 classpath（JDK 类）——它的构造器
 * 没有 rsf 的 `FuncDecl` 可指，那时选中的是 [jvmCtor]（一个 `JvmMethod`），
 * K2/K3 都只读它、自己不再挑一遍（阶段 25 K4 缺口 D）。rsf 父类的构造器**永远非 null**。
 */
data class SuperDelegation(
    /** 解析出来的父类。 */
    val superClass: SuperClass,
    /** 父类那个被选中的构造器；JDK 父类为 null（那时看 [jvmCtor]）。 */
    val target: FuncDecl?,
    /**
     * 实参表达式，**在委托方自己的帧里求值**。
     *
     * 两个来源都能落到这里：[stmt] 非空就是次构造里写的 `super(实参)`；
     * [stmt] 为空就是主构造——实参来自类头的 `class D(y : int) : Base(y)`，
     * 那条没有对应的语句节点，但语义完全一样。
     */
    val arguments: List<Expr>,
    /** 写 `super(...)` 的那条语句；主构造（类头实参）与隐式 `super()` 时为 null。 */
    val stmt: SuperCallStmt?,
    /**
     * JDK 父类那一趟选中的 `<init>`（阶段 25 K4，缺口 D）；rsf 父类时为 null。
     *
     * 选择放在**这一条边**上而不是「类 → 构造器」的一张表里：同一个类里两个构造器
     * 可以委托给父类**不同的**构造器（`class E : Exception() { constructor(m : string)
     * { super(m) } }` 那一支上），按类存会互相覆盖。挑一次、两个后端都只读这一份。
     */
    val jvmCtor: JvmMethod? = null,
)

/**
 * 阶段 12 的类解析产物。
 *
 * 为什么要单独一张表：顶层函数与方法**各是一套表**。两边的表都按 [FuncDecl] 索引
 * （阶段 19d 起顶层函数也支持重载，同名会有好几个 `FuncDecl`，按名字索引会互相覆盖），
 * 只是分开放，方便后端问「这个 `FuncDecl` 是不是类里的方法」。
 */
class ClassModel(
    /** 类名 → 声明。 */
    val classes: Map<String, ClassDecl>,
    /** 方法/构造器 → 它属于哪个类。解释器靠它知道 `this` 是什么。 */
    val owner: Map<FuncDecl, ClassDecl>,
    /** 方法/构造器 → 参数绑定（按签名顺序）。 */
    val methodParams: Map<FuncDecl, List<Binding>>,
    /** 方法/构造器 → 返回类型。 */
    val methodReturns: Map<FuncDecl, RsfType>,
    /** 方法/构造器 → 降级后的指令。 */
    val methodLowered: Map<FuncDecl, LoweredFunction>,
    /** 类 → 按声明顺序排好的字段类型（解释器造对象时用）。 */
    val fieldTypes: Map<ClassDecl, List<RsfType>>,
    /** `对象.成员` → 解析结果。 */
    val members: IdentityHashMap<MemberExpr, UserMember>,
    /** 方法体里裸写的字段名（读）→ 字段。 */
    val fieldReads: IdentityHashMap<VarExpr, UserMember.Field>,
    /** 方法体里裸写的字段名（写）→ 字段。 */
    val bareFieldWrites: IdentityHashMap<AssignStmt, UserMember.Field>,
    /** `对象.字段 = ...` → 字段。 */
    val fieldWrites: IdentityHashMap<MemberAssignStmt, UserMember.Field>,
    /**
     * 构造点 → 类声明。
     *
     * 键是 [Expr] 而不是 [NewExpr]：**省掉 `new` 的写法**（`Point(1, 2)`）在 AST 里
     * 是一个 [CallExpr]，只有 Checker 做完名字解析才知道它是构造还是函数调用。
     * 显式 `new Point(1, 2)`（[NewExpr]）与省略写法共用这一个表，两个后端也就
     * 不需要各写一份「这到底是不是构造」的判断。
     */
    val newTargets: IdentityHashMap<Expr, ClassDecl>,
    /**
     * 构造点 → **选中的那个构造器**（阶段 18）。
     *
     * 查不到（或没有这一项）表示这个类一个构造器都没写 ⇒ 隐式无参构造器：
     * 只有字段初值要跑，没有构造器体。理由是「选中的构造器」永远非空，
     * 所以不需要额外区分「没选」和「隐式无参」。
     */
    val newCtors: IdentityHashMap<Expr, FuncDecl> = IdentityHashMap(),
    /** 构造器 → 它的委托边（`this(...)`）；不委托的构造器不在表里。 */
    val ctorDelegations: IdentityHashMap<FuncDecl, CtorDelegation> = IdentityHashMap(),
    /**
     * 类里「按名字直接调本类方法」的调用（隐式 this / 隐式类名）。
     *
     * `shift(x, y)` 这种写法在方法体里很自然，但它不是顶层函数调用，
     * 解释器得知道该带上哪个 this。
     */
    val implicitCalls: IdentityHashMap<CallExpr, UserMember.Method>,
    /**
     * 接口方法 → 它属于哪个接口（阶段 20）。
     *
     * 只装**接口里声明的方法**（抽象方法 + 默认方法都装）：抽象方法没有体、不进
     * [methodLowered]，但它仍是接口的成员，后端问「这个方法声明在哪个接口上」时
     * 得答得出来（抽象方法要写 `ACC_ABSTRACT` 且不写 Code，默认方法要写 Code）。
     * 和 [owner] 分开两张表，而不是把 [owner] 的值类型放宽成 `Node`：读 [owner]
     * 的后端代码（解释器取 `this` 的类）一行都不用动。
     */
    val interfaceOwner: Map<FuncDecl, InterfaceDecl> = emptyMap(),
    /**
     * `super.方法(...)` / `super(接口).方法(...)` → 解析结果（阶段 25 K1）。
     *
     * **单独一张表**，而不是复用 [members]：`super.f()` 的调用目标与 `this.f()` 不是
     * 一回事（`super.` 走父类那一份实现、跳过本类的覆盖），而且它的接收者不是一个
     * 能单独求值的表达式。后端只要从这张表里拿「哪个类/接口的哪个方法」就够，
     * 不必再看 AST 上的 `SuperExpr`。
     */
    val superCalls: IdentityHashMap<MemberExpr, SuperCall> = IdentityHashMap(),
    /** 构造器 → 它的**父类构造委托**边（`super(...)`）；不写的构造器不在表里。 */
    val superDelegations: IdentityHashMap<FuncDecl, SuperDelegation> = IdentityHashMap(),
    /**
     * 类 → **隐式** `super()` 那条父类委托（阶段 25 K1）。
     *
     * 只在两种情况下有：类没有主构造、并且一个 `constructor` 都没写（那是编译器合成的
     * 无参构造器，没有 [FuncDecl] 可以当键）；或者某个次构造没写 `this(...)`/`super(...)`，
     * 编译器替它补一句无参 `super()`。所有构造器都在 [superDelegations] 里时这张表是空的。
     */
    val implicitSuperDelegations: IdentityHashMap<ClassDecl, SuperDelegation> = IdentityHashMap(),
    /**
     * 类里「按名字直接调**父类继承来的 JDK 方法**」的调用（阶段 25 K1，G5）。
     *
     * `class MyError(msg : string) : Exception(msg) { fun describe() -> string { rtn
     * getMessage() } }`：`getMessage` 声明在 `java/lang/Throwable` 上、接收者是隐式的
     * this，所以它既不是 [implicitCalls]（那里的值是 rsf 的 [FuncDecl]）、也不是
     * [memberTargets]（那里挂的是 `接收者.成员` 这种 [MemberExpr]）。
     *
     * 单独一张表把「隐式 this + JDK 继承来的成员」这条边记下来，后端（K2/K3）问
     * [implicitJvmCallOf] 就能拿到「哪个 JDK 类上的哪个方法」。
     */
    val implicitJvmCalls: IdentityHashMap<CallExpr, ResolvedMethod> = IdentityHashMap(),
) {
    val isEmpty: Boolean get() = classes.isEmpty()

    fun ownerOf(fn: FuncDecl): ClassDecl? = owner[fn]

    /** 这个方法声明在哪个接口上；不是接口方法就是 null。 */
    fun interfaceOwnerOf(fn: FuncDecl): InterfaceDecl? = interfaceOwner[fn]

    /** 一个类的构造器，按声明顺序（主构造在前）。没写任何构造器就是空表（隐式无参）。 */
    fun constructorsOf(decl: ClassDecl): List<FuncDecl> = decl.constructors

    /** 构造点选中的构造器；null = 隐式无参构造器。 */
    fun ctorOf(expr: Expr): FuncDecl? = newCtors[expr]

    /** 构造器 [ctor] 的委托边；不委托就是 null。 */
    fun delegationOf(ctor: FuncDecl): CtorDelegation? = ctorDelegations[ctor]

    /** 构造器 [ctor] 的**父类**委托边（`super(...)`）；没写就是 null。 */
    fun superDelegationOf(ctor: FuncDecl): SuperDelegation? = superDelegations[ctor]

    /** 类 [decl] 的**隐式**父类委托（合成的无参 `super()`）；没有就是 null。 */
    fun implicitSuperDelegationOf(decl: ClassDecl): SuperDelegation? = implicitSuperDelegations[decl]

    /** `super.f()` 这条调用解析到的目标；不是 super 接收者就是 null。 */
    fun superCallOf(expr: MemberExpr): SuperCall? = superCalls[expr]

    /** 类里隐式 this 调用**JDK 父类继承来的方法**的解析结果；不是那种调用就是 null。 */
    fun implicitJvmCallOf(expr: CallExpr): ResolvedMethod? = implicitJvmCalls[expr]

    /**
     * 这个方法是不是**实例**方法（要 `this`）。
     *
     * 构造器算实例方法；类里的 `static` 方法与顶层函数都不算。
     * 实例方法的 0 号槽固定留给 `this`（JVM 本来也是这么放的），所以 [Lowering]
     * 给实例方法分配局部槽时从 1 开始。
     *
     * 阶段 20 起**接口的默认方法**也算实例方法：它照样要 `this` 才能调到接口的
     * 其它方法（`fun shout(...) { rtn toUpper(greet(name)) }` 里的 `greet` 就是 this 上的）。
     */
    fun isInstance(fn: FuncDecl): Boolean =
        (owner.containsKey(fn) || interfaceOwner.containsKey(fn)) && !fn.isStatic
}

/**
 * 一个枚举常量引用（阶段 19c）：`TokenKind.IDENT` 解析成「哪个枚举的哪个值」。
 *
 * 两个后端都只要这两个名字：字节码发 `GETSTATIC TokenKind.IDENT`，解释器取
 * 常量表里同一个键上的那个单例。**不存 EnumDecl 对象本身**——后端要的只是
 * 「哪个类、哪个字段」，存字符串能让「这个常量属于谁」一眼看出来，
 * 也免得后端不小心去读 AST 上的别的信息。
 */
data class EnumValueRef(
    /** 枚举类型名（也是生成出来的那个类的内部名），例如 `TokenKind`。 */
    val enumName: String,
    /** 值名（也是那个类的 `public static final` 字段名），例如 `IDENT`。 */
    val valueName: String,
)

// ---------------------------------------------------------------------- 注解（阶段 21）

/**
 * 一个注解元素的**已解析**形状（阶段 21，M2）。
 *
 * [type] 已经过白名单校验（string / 基本类型 / 枚举 / 注解 / 这些的一维数组，且非可空），
 * 后端可以直接照它走「元素值要发什么字节码」的分支，不必再看 AST 上的 [AnnotationElement.type]。
 * 默认值的**字面量表达式**仍留在 [decl] 上（`AnnotationDefault` 要的是那个常量）。
 */
data class AnnotationElementSpec(
    val decl: AnnotationElement,
    /** 解析后的类型（非可空、非 void，数组最多一维）。 */
    val type: RsfType,
    /** 写了 `= 常量` 就是 true。缺省值的实参可以省略。 */
    val hasDefault: Boolean,
) {
    val name: String get() = decl.name
}

/**
 * 注解的 **companion entity**（阶段 21，M2）——语义层里它就是**一个类**。
 *
 * 这是「设计约束」里那条的落点：伴生实体不另起一套成员检查，而是被包装成一个
 * **合成的 [ClassDecl]**（[decl]，名字是 JVM 内部名 `Marker$Impl`），走的是用户类
 * 那条一模一样的检查路径（字段重名、`checkSignature`、擦除冲突、方法体检查、
 * 构造器链、实现检查……全部复用）。M3 的 `BytecodeCompiler` 因此可以把它当成
 * 普通类发射：父接口 = 注解接口 + 伴生实体自己写的接口。
 *
 * [elementFields] 是**合成出来的**元素字段（每个元素一个），[accessors] 是它们的
 * 公开访问器（每个元素一个，无参）。两者都按元素声明顺序排列。
 *
 * ⚠️ 给 M3 的约定：[elementFields] 里的字段**不是**靠 [ClassDecl.fields] 上的
 * `initializer` 拿到值的——那个初值只是**占位**（默认值字面量，或与类型相配的零值），
 * 真正的赋值要在伴生类 `<init>` 里按构造器参数生成（DESIGN-21 §5）。这样占位
 * 即使被照抄进 `<init>` 也不会产生非法字节码（基本类型字段不会拿到 null）。
 */
data class CompanionSpec(
    /** 它属于哪个注解。 */
    val annotation: String,
    /** 伴生类的 JVM 内部名：`Marker$Impl`（没写名字时 `Marker$companion`）。 */
    val jvmName: String,
    /** **合成**出来的类声明（源码里没有这个类；[ClassDecl.at] 指向 `companion entity` 那一行）。 */
    val decl: ClassDecl,
    /** 伴生类唯一的那个构造器：手写的那个，或者元素表自动生成的主构造。 */
    val constructor: FuncDecl,
    /** 元素访问器（每个元素一个，无参，名字 = 元素名）。 */
    val accessors: List<FuncDecl>,
    /** 元素字段（合成的，按元素声明顺序）。 */
    val elementFields: List<FieldDecl>,
)

/** 一个注解声明解析完的样子（阶段 21，M2）。 */
data class AnnotationSpec(
    val decl: AnnotationDecl,
    /** 元素表，按声明顺序。 */
    val elements: List<AnnotationElementSpec>,
    /** companion entity；没写就是 null（那就是一个纯标注用的注解）。 */
    val companion: CompanionSpec?,
) {
    val name: String get() = decl.name

    fun elementOf(name: String): AnnotationElementSpec? = elements.firstOrNull { it.name == name }

    /** 报错文案里的元素名提示：`（Marker 的元素有：value、count）`。 */
    fun elementHint(): String =
        if (elements.isEmpty()) "（'$name' 没有元素）"
        else "（'$name' 的元素有：${elements.joinToString("、") { it.name }}）"
}

/** 注解使用点落在什么东西上（阶段 21 v1 开放的位置）。 */
enum class AnnotationTargetKind(val label: String) {
    CLASS("类"),
    INTERFACE("接口"),
    ANNOTATION("注解"),
    METHOD("方法"),
    FIELD("字段"),
}

/**
 * 一个注解使用点的**目标**（阶段 21，M2）。
 *
 * [hostName] 是「方案 B 要挂访问器的那个类型」（类型级 = 被标的类型自己，成员级 = 它所属的
 * 类型）；顶层函数是 null（没有类型可以挂）。[memberName] 是成员级注解的成员名。
 */
sealed interface AnnotationTarget {
    val at: SourceLine
    /** 这是个什么位置（类 / 接口 / 注解 / 方法 / 字段）——后端按它决定生成哪一种标注。 */
    val kind: AnnotationTargetKind
    /** 报错落款，如 `类 'Point'`、`方法 'Point.shift'`。 */
    val label: String
    val hostName: String?
    val memberName: String?

    data class OnClass(val decl: ClassDecl) : AnnotationTarget {
        override val at: SourceLine get() = decl.at
        override val kind: AnnotationTargetKind get() = AnnotationTargetKind.CLASS
        override val label: String get() = "${AnnotationTargetKind.CLASS.label} '${decl.name}'"
        override val hostName: String get() = decl.name
        override val memberName: String? get() = null
    }

    data class OnInterface(val decl: InterfaceDecl) : AnnotationTarget {
        override val at: SourceLine get() = decl.at
        override val kind: AnnotationTargetKind get() = AnnotationTargetKind.INTERFACE
        override val label: String get() = "${AnnotationTargetKind.INTERFACE.label} '${decl.name}'"
        override val hostName: String get() = decl.name
        override val memberName: String? get() = null
    }

    data class OnAnnotation(val decl: AnnotationDecl) : AnnotationTarget {
        override val at: SourceLine get() = decl.at
        override val kind: AnnotationTargetKind get() = AnnotationTargetKind.ANNOTATION
        override val label: String get() = "${AnnotationTargetKind.ANNOTATION.label} '${decl.name}'"
        override val hostName: String get() = decl.name
        override val memberName: String? get() = null
    }

    data class OnMethod(val decl: FuncDecl, val ownerName: String?) : AnnotationTarget {
        override val at: SourceLine get() = decl.at
        override val kind: AnnotationTargetKind get() = AnnotationTargetKind.METHOD
        override val label: String get() = if (ownerName == null) "方法 '${decl.name}'" else "方法 '$ownerName.${decl.name}'"
        override val hostName: String? get() = ownerName
        override val memberName: String get() = decl.name
    }

    data class OnField(val decl: FieldDecl, val ownerName: String?) : AnnotationTarget {
        override val at: SourceLine get() = decl.at
        override val kind: AnnotationTargetKind get() = AnnotationTargetKind.FIELD
        override val label: String get() = if (ownerName == null) "字段 '${decl.name}'" else "字段 '$ownerName.${decl.name}'"
        override val hostName: String? get() = ownerName
        override val memberName: String get() = decl.name
    }
}

/**
 * 一个**注解使用点**与它的目标的配对（阶段 21，M2）。
 *
 * 使用点的实参**已经**按元素表校验过（位置式按顺序、具名按名字、缺省值兜底、重复标注查过）。
 * 后端要多一个「哪个 `@Marker(...)` 落在哪个声明上」的权威表，就只读这一份，
 * 不要自己再拿 AST 对一遍。
 */
data class AnnotationUseSite(
    val annotation: AnnotationSpec,
    val use: AnnotationUse,
    val target: AnnotationTarget,
)

/**
 * 方案 B 要在某个类型上生成的**注解访问器**规格（阶段 21，M2 只出规格，M3 才生成）。
 *
 * - 类型级（[memberName] 为 null）：`public static <伴生类型> <注解名>()`
 * - 成员级：[`public static <伴生类型> <注解名>(string member)`]，取不到返回 null
 */
data class AnnotationAccessor(
    val annotation: AnnotationSpec,
    val use: AnnotationUse,
    val target: AnnotationTarget,
    /** 访问器挂到哪个类型上（被标的类/接口/注解；方案 B 只挂静态成员）。 */
    val hostName: String,
    /** 成员级访问器的 `string member` 实参值；类型级是 null。 */
    val memberName: String?,
    /** 返回类型：伴生类的 JVM 内部名。 */
    val companionName: String,
) {
    /** 给人看的签名，如 `public static Marker$Impl Marker()`。 */
    fun signature(): String =
        "pub static $companionName ${annotation.name}(" +
            (memberName?.let { "string member" } ?: "") + ")"
}

/**
 * **一处泛型调用**推导出来的类型实参绑定（阶段 31）。
 *
 * 例：`fn id&<T>(x : T) -> T` 的调用点 `id("hi")` → [typeParams] = `["T"]`、
 * [bindings] = `{T: string}`、[returnType] = `string`。
 *
 * [bindings] 的键一定是 [typeParams] 里的名字，且**每个都有值**（推不出来的类型参数
 * 在检查阶段就报错了，走不到这里）。31a 只把它记在 [SemanticModel.genericCalls] 里，
 * 31b 的字节码后端按它在调用点插 `CHECKCAST`（擦除之后形参/返回值都是 `Object`）。
 */
data class GenericCallSite(
    /** 被调用函数自己的类型参数名（声明顺序）。 */
    val typeParams: List<String>,
    /** 每个类型参数推导出的具体类型（`T` → `int`）。 */
    val bindings: Map<String, RsfType>,
    /** 把返回类型里出现的类型参数代换掉之后的结果（= 调用表达式的类型）。 */
    val returnType: RsfType,
)

/**
 * 语义检查的产物：函数表、类型标注、名字解析结果、降级后的指令，以及分析告警。
 *
 * AST 本身保持不可变，一切标注放在这里（用 IdentityHashMap 按引用索引，
 * 避免结构相同的节点互相串味）。解释器和字节码后端都靠它工作。
 */
class SemanticModel(
    val functions: List<FuncDecl>,

    /**
     * 顶层函数按**名字**索引 → 这个名字下的全部重载（声明顺序）。
     *
     * 阶段 19d 起一个名字可以有多个 `FuncDecl`（重载）。名字到候选的这张表只用来
     * 「按名字找候选」；**每一次调用具体选中了哪一个**记在 [callTargets] 里，
     * 两个后端都只读那一份，绝不会各自再解析一遍。
     */
    val functionTable: Map<String, List<FuncDecl>>,
    val exprTypes: IdentityHashMap<Expr, RsfType>,

    /**
     * `as` / `?as` / `is` / `!is` 的**目标类型**（阶段 24，按**解析后**的 [RsfType] 记）。
     *
     * 为什么不复用 [exprTypes]：`is` / `!is` 的表达式类型是 `bool`，目标类型在那里没有位置；
     * `?as` 的结果是 `?T`，把 `?` 剥回去也是一种重复知识。目标类型只解析一次，
     * 记在这里，两个后端直接读——INSTANCEOF / CHECKCAST 用的内部名和错误消息里印的
     * JVM 名都由它算出来，两端才不会各算一套。
     */
    val castTargets: IdentityHashMap<Expr, RsfType>,

    /** 变量读取 → 它绑定的那个变量（参数或局部声明）。 */
    val varRefs: IdentityHashMap<VarExpr, Binding>,
    /** 赋值语句 → 它写的那个变量。 */
    val assignRefs: IdentityHashMap<AssignStmt, Binding>,
    /** 局部声明语句 → 它的绑定。Lowering 靠它找到该分配的槽位。 */
    val declBindings: IdentityHashMap<VarDeclStmt, Binding>,
    /** 顶层函数 → 参数绑定（按签名顺序）。阶段 19d 起按 [FuncDecl] 索引（重载需要）。 */
    val paramBindings: Map<FuncDecl, List<Binding>>,
    /** 顶层函数 → 参数类型（按签名顺序）。 */
    val paramTypes: Map<FuncDecl, List<RsfType>>,

    /**
     * **每一次调用选中的那个 `FuncDecl`**（阶段 19d）。
     *
     * 这是重载在两端唯一的分派依据：解释器据此决定调哪个函数的哪段指令，
     * 字节码据此决定发哪个描述符的 `INVOKESTATIC`/`INVOKEVIRTUAL`。
     * 选择只发生在 Checker 里一次，两端不再各自解析——这正是「两端逐字节一致」
     * 在重载上的保证方式。
     */
    val callTargets: IdentityHashMap<CallExpr, FuncDecl>,
    /** 成员访问 → 解析到的 JVM 成员。 */
    val memberTargets: IdentityHashMap<MemberExpr, ResolvedMember>,
    /** 顶层函数 → 返回类型。 */
    val returnTypes: Map<FuncDecl, RsfType>,
    /** 顶层函数 → 降级后的指令序列。 */
    val lowered: Map<FuncDecl, LoweredFunction>,
    /** 结构化诊断（目前只有 warning；error 是抛异常走的）。 */
    val diagnostics: List<Diagnostic>,

    /** 程序所属的包（`pkg a.b`）；没写就是 null。生成 .class 时它是 JVM 的包。 */
    val packageName: String? = null,

    /** 阶段 12：用户自定义类的一切解析结果。 */
    val classModel: ClassModel = ClassModel(
        classes = emptyMap(),
        owner = emptyMap(),
        methodParams = emptyMap(),
        methodReturns = emptyMap(),
        methodLowered = emptyMap(),
        fieldTypes = emptyMap(),
        members = IdentityHashMap(),
        fieldReads = IdentityHashMap(),
        bareFieldWrites = IdentityHashMap(),
        fieldWrites = IdentityHashMap(),
        newTargets = IdentityHashMap(),
        implicitCalls = IdentityHashMap(),
    ),

    /**
     * 阶段 15：构造**导入的 JVM 类**（`new ArrayList&<int>()`，或者省掉 `new` 的
     * `ArrayList&<int>()`）→ 解析到的构造器。
     *
     * 用户自定义类的构造记在 [ClassModel.newTargets] 里（那边要类声明），
     * 这里记的是 JVM 类的构造器：解释器靠它反射 newInstance，字节码后端靠它发
     * `INVOKESPECIAL <init>`。
     */
    val externalNewTargets: IdentityHashMap<Expr, ResolvedMethod> = IdentityHashMap(),

    /**
     * 阶段 19c：枚举声明，按名字索引。
     *
     * 枚举的类型在检查器里和用户类**完全一样**（`RsfType.external(枚举名)`），
     * 这张表的用处是让后端问得出「这个名字是不是枚举」——
     * 字节码后端据此生成专门的枚举类（`GETSTATIC` + `<clinit>`），
     * 解释器据此把值表示成具名单例对象。
     */
    val enums: Map<String, EnumDecl> = emptyMap(),

    /** `枚举名.值名`（[MemberExpr]）→ 解析结果。 */
    val enumValues: IdentityHashMap<MemberExpr, EnumValueRef> = IdentityHashMap(),

    /**
     * 阶段 20：接口声明，按名字索引。
     *
     * 接口的类型在检查器里和用户类是**同一个形状**（`RsfType.external(接口名)`），
     * 这张表的用处是让后端问得出「这个名字是不是接口」——字节码要按接口的规矩
     * 生成类头（`ACC_INTERFACE|ACC_ABSTRACT`、无 `<init>`）与抽象方法（无 Code），
     * 解释器要知道哪些方法只有声明没有体。缺了它，后端只能对着一个
     * `RsfType.external` 猜——而猜错就是「生成一个没有 `<init>` 却被人 new 的类」
     * 这种运行期才炸的产物。
     */
    val interfaces: Map<String, InterfaceDecl> = emptyMap(),

    /**
     * 阶段 20：**类名/接口名 → 它的父接口**（已解析：JVM 内部名 + 代换后的实参）。
     *
     * 没写 `:` 子句的类不在表里（读的时候用空表兜底）。后端靠它填 `ClassWriter.visit`
     * 的 `interfaces` 数组，不必也不该自己再解析一遍 AST 上的名字。
     */
    val superInterfaces: Map<String, List<SuperInterface>> = emptyMap(),

    /**
     * 阶段 25：**类名 → 它的父类**（已解析：JVM 内部名 + 已校验的构造实参）。
     *
     * 没写父类的类不在表里。这张表同时是两件事的**唯一判据**：
     *
     * - 后端填 `ClassWriter.visit` 的 `superName`（K2/K3）；
     * - 「这份程序里有没有类继承」——两个后端（字节码 / 解释器）在 K1 阶段靠
     *   `superClasses.isEmpty()` 判断要不要**显式报未实现**，绝不静默产出错产物。
     *
     * 注意它和 [superInterfaces] 是两张表：父类只有一个、要写实参，接口可以有很多个、
     * 不许写实参（DESIGN-25 §2/§3），合在一起只会让每个读它的地方都要再判一次类型。
     */
    val superClasses: Map<String, SuperClass> = emptyMap(),

    /**
     * 阶段 21：注解声明，按**名字**索引。
     *
     * 注解的类型在检查器里和用户类/接口是**同一个形状**（`RsfType.external(注解名)`，
     * 那个名字就是注解接口的类名），这张表的用处是让后端问得出「这个名字是不是注解」——
     * 字节码后端据此生成注解接口（`ACC_ANNOTATION` + 元素方法），
     * 解释器据此知道它没有对象可以 `new`（要带行为就得用 [AnnotationSpec.companion]）。
     *
     * 顺带说清楚：**`@interface` 本身不进 [interfaces]**——[InterfaceDecl] 装不下注解
     * （元素表、伴生实体都在 [AnnotationDecl] 上），硬塞进去只会让后端到处做类型判断。
     */
    val annotations: Map<String, AnnotationSpec> = emptyMap(),

    /**
     * 阶段 21：**每一个注解使用点**（`@Marker(...)`）落在哪个目标上的配对表。
     *
     * 两端后端写 `RuntimeVisibleAnnotations` 时只该读这一份：目标是谁、实参是什么、
     * 缺省值补了没有，全在 Checker 里定过一次了。
     */
    val annotationUses: List<AnnotationUseSite> = emptyList(),

    /**
     * 阶段 21：`new Marker(...)` / `Marker(...)` 这种**构造伴生实例**的点 → 注解。
     *
     * 和 [ClassModel.newTargets] 同时命中（伴生类也是一个普通的类），这一张额外回答
     * 「它其实是哪个注解的伴生实例」——元素访问器要按注解的元素表配对，报错也要说注解的话。
     */
    val annotationNewTargets: IdentityHashMap<Expr, AnnotationSpec> = IdentityHashMap(),

    /**
     * 阶段 21：**伴生类的 JVM 内部名** → 它属于哪个注解（`Marker$Impl` → `Marker`）。
     *
     * [ClassModel.classes] 里也有那个合成的 `ClassDecl`（键就是这个名字），后端据此
     * 认出「这个类不是用户写的类，而是某个注解的伴生实体」。
     */
    val companionAnnotations: Map<String, String> = emptyMap(),

    /**
     * 阶段 21：方案 B 要在哪些类型上生成哪些注解访问器（M2 只出规格，M3 生成字节码）。
     *
     * 一个使用点一条；同一个使用点只有一个注解（同一个目标上同一个注解重复标会报错）。
     */
    val annotationAccessors: List<AnnotationAccessor> = emptyList(),

    /**
     * 阶段 31：**每一处泛型调用**推导出来的类型实参绑定（`T` → `int`）。
     *
     * 键是调用点那个表达式：顶层函数调用是 [CallExpr]，方法调用是 [MemberExpr]
     * （rsf 的成员调用就是「成员访问 + 实参表」，两者都可能是泛型函数的目标）。
     * 值见 [GenericCallSite]。
     *
     * 31a 只**记录**、不使用：这一刀不发码（两个后端对泛型函数一律报 17d）。
     * 31b 的字节码后端要按这张表在调用点插 `CHECKCAST`——擦除之后
     * `fn id&<T>(x : T) -> T` 编出来是 `id(Object)Object`，`var s : string = id("x")`
     * 拿回来的静态类型却是 `Object`，不 cast 就过不了 JVM 的校验。
     */
    val genericCalls: IdentityHashMap<Expr, GenericCallSite> = IdentityHashMap(),
) {
    /** 给命令行用的文本形式。 */
    val warnings: List<String>
        get() = diagnostics
            .filter { it.severity == Severity.WARNING }
            .map { "${it.file}:${it.line}: ${it.message}" }

    /**
     * 名为 main 的**无参**顶层函数，用作入口；没有则返回 null。
     *
     * 阶段 19d 起 `main(int)` 是合法的重载但**不是入口**（JVM 只认 `main(String[])`）。
     * 两个无参 `main()` 会在 Checker 里以「入口歧义 + 擦除冲突」报错，所以
     * 这里取第一个无参候选是安全的。
     */
    val entry: FuncDecl? get() = functionsNamed("main").firstOrNull { it.params.isEmpty() }

    /** 这个名字下的全部顶层函数重载（声明顺序）；没有就是空表。 */
    fun functionsNamed(name: String): List<FuncDecl> = functionTable[name].orEmpty()

    /**
     * 入口为什么跑不起来；能跑就是 null。
     *
     * 单独有这一条是因为阶段 19d 起 `main(a : int)` 也是**合法的重载**，只是当不了入口。
     * 那种情况下说「没有 main」会把人带偏，要说清是**参数**的问题。
     */
    val entryProblem: String?
        get() {
            if (entry != null) return null
            val named = functionsNamed("main")
            if (named.isEmpty()) return "找不到入口函数：需要 fn main() -> void { ... }"
            return "入口函数 main 不能有参数（实际有 ${named.first().params.size} 个）：" +
                "JVM 只认 main(String[])，rsf 里也没有地方给它传参。" +
                "入口要写成 fn main() -> void { ... }；带参数的 main 仍然是一个普通函数，" +
                "在 rsf 内部可以照常调用（阶段 19d 起它是重载，只是当不了入口）"
        }

    fun typeOf(expr: Expr): RsfType =
        exprTypes[expr] ?: throw IllegalStateException("表达式缺少类型标注: $expr")

    /** `as` / `is` 的目标类型（阶段 24）。 */
    fun castTargetOf(expr: Expr): RsfType =
        castTargets[expr] ?: throw IllegalStateException("类型转换/判断缺少目标类型: $expr")

    fun bindingOf(decl: VarDeclStmt): Binding =
        declBindings[decl] ?: throw IllegalStateException("变量声明没有绑定: $decl")

    fun bindingOf(ref: VarExpr): Binding =
        varRefs[ref] ?: throw IllegalStateException("变量读取没有解析到绑定: $ref")

    fun bindingOf(assign: AssignStmt): Binding =
        assignRefs[assign] ?: throw IllegalStateException("赋值没有解析到绑定: $assign")

    fun typeOf(decl: VarDeclStmt): RsfType = bindingOf(decl).type

    fun memberOf(expr: MemberExpr): ResolvedMember =
        memberTargets[expr] ?: throw IllegalStateException("成员访问没有解析到目标: $expr")

    /**
     * 顶层函数 [fn] 的参数绑定。
     *
     * 方法优先按 [FuncDecl] 索引（[ClassModel.methodParams]），其次才是顶层函数那张表
     * ——两套表不混用，谁也不覆盖谁。
     */
    fun paramsOf(fn: FuncDecl): List<Binding> =
        classModel.methodParams[fn] ?: paramBindings[fn] ?: emptyList()

    fun paramTypesOf(fn: FuncDecl): List<RsfType> =
        classModel.methodParams[fn]?.map { it.type } ?: paramTypes[fn] ?: emptyList()

    fun returnTypeOf(fn: FuncDecl): RsfType =
        classModel.methodReturns[fn] ?: returnTypes[fn] ?: RsfType.VOID

    fun loweredOf(fn: FuncDecl): LoweredFunction =
        classModel.methodLowered[fn]
            ?: lowered[fn]
            ?: throw IllegalStateException("函数没有降级结果: ${fn.name}")

    // ------------------------------------------------------------------ 构造点（自动 new）

    /**
     * 这个表达式是不是一次**构造**：显式 `new X(...)`（[NewExpr]），
     * 或者省掉 `new` 的 `X(...)`（[CallExpr]，由 Checker 判定）。
     *
     * 两个后端的表达式分派都先问这一句：是构造就走构造那条路，否则才当函数调用。
     * 判断依据只有 Checker 写下的 [ClassModel.newTargets] / [externalNewTargets]，
     * 所以「省不省 `new`」在两端是同一件事。
     */
    fun isConstruction(expr: Expr): Boolean =
        classModel.newTargets.containsKey(expr) || externalNewTargets.containsKey(expr)

    /**
     * 构造点的实参表。显式 `new X(...)` 与省略写法是两个不同的节点类型，
     * 构造函数里要取实参的地方统一走这里，省得两处各写一个 `when`。
     */
    fun constructionArgs(expr: Expr): List<Expr> = when (expr) {
        is NewExpr -> expr.arguments
        is CallExpr -> expr.arguments
        else -> emptyList()
    }

    /** 构造点的类名（报错与查表用；不是构造点时是空串）。 */
    fun constructionName(expr: Expr): String = when (expr) {
        is NewExpr -> expr.className
        is CallExpr -> expr.name
        else -> ""
    }

    // ------------------------------------------------------------------ 枚举（阶段 19c）

    /**
     * 这个类型是不是某个枚举。
     *
     * 判据只有「EXTERNAL + 名字在 [enums] 里」——枚举的静态类型和用户类是同一个
     * 形状，区别只在于名字登记在哪张表里。两端后端都靠这一句分流。
     */
    fun isEnumType(t: RsfType): Boolean =
        t.isExternal && t.externalName != null && enums.containsKey(t.externalName)

    /** 某个枚举的全部值名，按声明顺序；不是枚举就是空表。 */
    fun enumValueNames(enumName: String): List<String> =
        enums[enumName]?.values?.map { it.name } ?: emptyList()

    /** 一个枚举常量的解析结果；不是常量引用就是 null。 */
    fun enumValueOf(expr: Expr): EnumValueRef? = enumValues[expr as? MemberExpr]

    /** 一个枚举常量在 JVM 上的描述符：`TokenKind.IDENT` → `LTokenKind;`。 */
    fun enumDescriptor(enumName: String): String = "L$enumName;"

    // ------------------------------------------------------------------ 类型转换/判断的运行期名字（阶段 24）

    /**
     * 一个**用户写的名字**在运行期对应的类文件内部名。
     *
     * 这是「名字 → 生成的类名」的**唯一实现**：用户类 / 枚举跟着程序类的包走
     * （`pkg a.b` + `P` → `a/b/P`），rsf 接口 / 注解接口只在默认包里生成，
     * classpath 上的 JVM 类名本身就是绝对的。字节码后端生成类文件、两个后端
     * 拼 `as` 的报错文案，都从这里取，免得三处各写一条规则而悄悄分叉。
     */
    fun classInternalName(simpleName: String): String {
        if (
            !classModel.classes.containsKey(simpleName) &&
            !enums.containsKey(simpleName)
        ) {
            return simpleName
        }
        return packageName
            ?.takeIf { it.isNotEmpty() }
            ?.let { it.replace('.', '/') + "/" + simpleName }
            ?: simpleName
    }

    /**
     * [classInternalName] 的点分写法——就是 `Class.getName()` 会印出来的那个字符串。
     */
    fun jvmRuntimeNameOf(simpleName: String): String =
        classInternalName(simpleName).replace('/', '.')

    /**
     * 一个 [RsfType] 在运行期的**类名**（`Class.getName()` 的写法）。
     *
     * 数组按 JVM 的老规矩写描述符形式：`B[]` → `[LB;`、`int[][]` → `[[I`；
     * 泛型的类型参数按擦除算 `java.lang.Object`；可空基本类型是它的包装类。
     * `as` 失败时的文案里印的就是这个字符串（`rsf: 不能把 [LC; 当成 [LB;`）。
     */
    fun jvmRuntimeNameOf(type: RsfType): String =
        if (type.isArray) {
            "[" + arrayElementNameOf(type.elementType)
        } else {
            jvmRuntimeClassNameOf(type)
        }

    /**
     * 数组**一层**元素在 `Class.getName()` 里的写法：引用类型是 `L全名;`、基本类型是
     * 单字母描述符、多维再套一层 `[`。`B[]` → `[LB;`、`int[][]` → `[[I`。
     */
    private fun arrayElementNameOf(element: RsfType): String = when {
        element.isArray -> "[" + arrayElementNameOf(element.elementType)
        element.isPrimitive -> element.descriptor
        // 引用元素（类 / 接口 / string / 擦除后的 Object）要带 L...;
        else -> "L" + jvmRuntimeClassNameOf(element) + ";"
    }

    private fun jvmRuntimeClassNameOf(type: RsfType): String = when {
        type.base == RsfBase.EXTERNAL ->
            jvmRuntimeNameOf(type.externalName ?: "java/lang/Object")
        // 其余（基本类型 / string / 类型参数 / 可空基本类型的包装类）描述符里
        // 已经写全了：`I`、`Ljava/lang/String;`、`Ljava/lang/Object;`
        else -> type.descriptor.let {
            if (it.startsWith("L")) it.substring(1, it.length - 1).replace('/', '.') else it
        }
    }

    // ------------------------------------------------------------------ 接口（阶段 20）

    /**
     * 这个类型是不是某个 **rsf 接口**。
     *
     * 判据和 [isEnumType] 同型：`EXTERNAL + 名字在 [interfaces] 里`。注意
     * **JDK 接口**（`Runnable`、`Comparator`）不在这张表里——它们由
     * `ClassPath.find(...)?.isInterface` 回答，两套判据不能混用：一个是「源码里
     * 声明过的接口」，一个是「classpath 上的接口」，后端要的答案通常只有其中一个。
     */
    fun isInterfaceType(t: RsfType): Boolean =
        t.isExternal && t.externalName != null && interfaces.containsKey(t.externalName)

    /** 这个名字的接口声明；不是 rsf 接口就是 null。 */
    fun interfaceOf(name: String): InterfaceDecl? = interfaces[name]

    /**
     * 这个类/接口的父接口（已解析）；没写 `:` 子句就是空表。
     *
     * 参数写类名或接口名都行——两者共用命名空间，键也就是同一个。
     */
    fun superInterfacesOf(name: String): List<SuperInterface> = superInterfaces[name].orEmpty()

    // ------------------------------------------------------------------ 类继承（阶段 25）

    /** 这个类的父类（已解析）；没写父类就是 null。 */
    fun superClassOf(name: String): SuperClass? = superClasses[name]

    /**
     * 阶段 25 K3：类 [name] 的**父类链**，从根祖先到直接父类（**不含自己**）。
     *
     * 与 Checker 算字段绝对下标时用的那条链是同一条（`ancestorsOf`），所以
     * `[父链][自己]` 的字段拼起来，顺序恰好就是 Checker 烘进 `UserMember.Field.index`
     * 的那套下标——解释器造对象、跑字段初值都只读这一条链，不再自己数一遍。
     *
     * **JDK 父类不算在内**：它的字段在 JVM 那个真实实例里（见解释器的
     * `RsfObject.jdkParent`），不占 rsf 这条扁平字段数组的位置。
     */
    fun classAncestorsOf(name: String): List<ClassDecl> {
        val out = ArrayList<ClassDecl>()
        var cur = superClassOf(name)?.declaration
        // 环（`class A : B` / `class B : A`）在 Checker 里已经报错，这里再去一次重
        // 只是为了不把死循环留给运行期：链一旦回到见过的类就停。
        while (cur != null && out.none { it === cur }) {
            out.add(0, cur)
            cur = superClassOf(cur.name)?.declaration
        }
        return out
    }

    /**
     * 阶段 25 K3：类 [name] **自己那一段**字段在对象字段数组里的起始下标
     * （= 父链上所有类的字段总数；没有父类就是 0）。
     *
     * 与 `Checker.fieldBase` 是同一条公式：绝对下标 = 本方法的返回值 + 段内下标。
     */
    fun fieldBaseOf(name: String): Int = classAncestorsOf(name).sumOf { it.fields.size }

    /**
     * 阶段 25 K3：类 [name] 的对象字段数组**总长度**（父链的 + 自己的）。
     *
     * 解释器 `new` 一个对象时按它分配数组：父类字段在前，本类字段在后。
     */
    fun fieldCountOf(name: String): Int = fieldBaseOf(name) + (classModel.classes[name]?.fields?.size ?: 0)

    /**
     * 阶段 25 K3：类 [name] 的整条字段链（**父类在前、自己在后**）。
     *
     * 返回的列表与对象字段数组**逐位对应**：第 i 项的声明类就是绝对下标 i 的归属。
     */
    fun fieldChainOf(name: String): List<Pair<ClassDecl, FieldDecl>> {
        val out = ArrayList<Pair<ClassDecl, FieldDecl>>()
        for (decl in classAncestorsOf(name)) {
            for (f in decl.fields) out.add(decl to f)
        }
        classModel.classes[name]?.let { decl -> for (f in decl.fields) out.add(decl to f) }
        return out
    }

    /**
     * 阶段 25 K3：类 [name] 的**方法查找链**——从自己开始、到根祖先为止（最近的优先）。
     *
     * 虚分派（`INVOKEVIRTUAL` / `INVOKEINTERFACE`）按这个顺序找实现：先看运行期类自己，
     * 再往父类走，第一个同名同签名的实例方法就是目标。和 [classAncestorsOf] 是同一条链，
     * 只是反了个方向、并把自己放在最前面。
     */
    fun classChainOf(name: String): List<ClassDecl> =
        classAncestorsOf(name).asReversed().let { parents ->
            classModel.classes[name]?.let { listOf(it) + parents } ?: parents
        }

    /** 这份程序里有没有类写了父类——两个后端的「阶段 25 后端未实现」守卫读它。 */
    val hasClassInheritance: Boolean get() = superClasses.isNotEmpty()

    // ------------------------------------------------------------------ 注解（阶段 21）

    /** 这个名字的注解声明；不是注解就是 null。 */
    fun annotationOf(name: String): AnnotationSpec? = annotations[name]

    /** 这个注解的伴生类；没有 companion entity 就是 null。 */
    fun companionOf(annotation: String): CompanionSpec? = annotations[annotation]?.companion

    /**
     * 这个类型是不是某个 **rsf 注解**（判据和 [isEnumType] / [isInterfaceType] 同型）。
     *
     * 注意「注解接口类型」（`Marker`）和「伴生类类型」（`Marker::Impl`）是**两个**类型：
     * 前者只看得见元素访问器，后者是完整的那个类。
     */
    fun isAnnotationType(t: RsfType): Boolean =
        t.isExternal && t.externalName != null && annotations.containsKey(t.externalName)

    /** 这个名字是不是某个注解的**伴生类**（`Marker$Impl`）。 */
    fun isCompanionClass(name: String): Boolean = companionAnnotations.containsKey(name)

    /** 这个伴生类属于哪个注解；不是伴生类就是 null。 */
    fun companionAnnotationOf(name: String): AnnotationSpec? =
        companionAnnotations[name]?.let { annotations[it] }

    /** 这个使用点（AST 节点）的配对结果；没经过检查（或不是使用点）就是 null。 */
    fun useSiteOf(use: AnnotationUse): AnnotationUseSite? = annotationUses.firstOrNull { it.use === use }

    /** 这个表达式是不是「构造某个注解的伴生实例」。 */
    fun isAnnotationConstruction(expr: Expr): Boolean = annotationNewTargets.containsKey(expr)

    /** `new Marker(...)` 构造的是哪个注解的伴生实例；不是就是 null。 */
    fun annotationConstructionOf(expr: Expr): AnnotationSpec? = annotationNewTargets[expr]

    /** 挂在某个类型上的注解访问器（方案 B，M3 生成用），按注解在源码里的顺序。 */
    fun accessorsOn(hostName: String): List<AnnotationAccessor> =
        annotationAccessors.filter { it.hostName == hostName }

    /**
     * 注解 [annotation] 的**元素访问器**：元素名 → 它在伴生类上的那个无参方法。
     *
     * 「赋给 `Marker` 类型的变量只剩元素方法可见」（DESIGN-21 §1）就是靠它实现的：
     * 接收者静态类型是注解接口时，成员解析只查这张表，行为方法（`describe`/`run`）
     * 自然照不进来。查不到就是 null。
     */
    fun elementAccessorOf(annotation: String, member: String): FuncDecl? =
        companionOf(annotation)?.accessors?.firstOrNull { it.name == member }

    /** 注解 [annotation] 的元素**字段**（伴生类上的那个字段，按元素名）；没有就是 null。 */
    fun elementFieldOf(annotation: String, member: String): FieldDecl? =
        companionOf(annotation)?.elementFields?.firstOrNull { it.name == member }
}
