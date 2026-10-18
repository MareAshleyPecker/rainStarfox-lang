package org.example.complie.sema

import org.example.complie.ast.AnnotationArg
import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.AnnotationElement
import org.example.complie.ast.AnnotationUse
import org.example.complie.ast.AsExpr
import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.DerefAssignStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ClassImport
import org.example.complie.ast.CompanionEntityDecl
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.ControlType
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.ElvisExpr
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.EnumValue
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
import org.example.complie.ast.LambdaExpr
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NamedAnnotationArg
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.NullLit
import org.example.complie.ast.Param
import org.example.complie.ast.PositionalAnnotationArg
import org.example.complie.ast.Program
import org.example.complie.ast.ReturnSpec
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.ReturnStyle
import org.example.complie.ast.Stmt
import org.example.complie.ast.StringLit
import org.example.complie.ast.SuperCallStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.SwitchStmt
import org.example.complie.ast.ThisCallStmt
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TryStmt
import org.example.complie.ast.TypeRef
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt
import org.example.complie.ast.exprText
import org.example.complie.Rsf
import org.example.complie.diag.Diagnostic
import org.example.complie.diag.RsfError
import org.example.complie.diag.Severity
import org.example.complie.diag.SourceFile
import org.example.complie.interop.ClassPath
import org.example.complie.interop.JvmClass
import org.example.complie.interop.JvmMethod
import org.example.complie.interop.MemberResolver
import org.example.complie.interop.JvmField
import org.example.complie.interop.ResolvedField
import org.example.complie.interop.ResolvedMember
import org.example.complie.interop.ResolvedMethod
import org.example.complie.ir.ControlFlow
import org.example.complie.ir.IAssign
import org.example.complie.ir.IBranch
import org.example.complie.ir.ICtorDelegate
import org.example.complie.ir.IExpr
import org.example.complie.ir.IFail
import org.example.complie.ir.IFieldAssign
import org.example.complie.ir.IGoto
import org.example.complie.ir.IDerefAssign
import org.example.complie.ir.IIndexAssign
import org.example.complie.ir.ILabel
import org.example.complie.ir.IReturn
import org.example.complie.ir.ISuperDelegate
import org.example.complie.ir.ISwitch
import org.example.complie.ir.IVarDecl
import org.example.complie.ir.Instr
import org.example.complie.ir.LoweredFunction
import org.example.complie.ir.Lowering
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind
import java.util.IdentityHashMap

/**
 * 语义检查，分两趟：
 *
 * **第一趟（名字与类型）**：块作用域栈、把每个变量读取解析到具体的 `let`、
 * 推断表达式类型。`!T` / `?T` / 函数参数等尚未实现的东西在这里明确报错。
 *
 * **第二趟（控制流分析）**：把函数体降级成指令序列，建 CFG，做
 * 可达性、确定性赋值、所有路径 return 检查。这一趟是 goto / while / if 能安全
 * 落到 JVM 上的前提——不做的话，错误会推迟到类加载时才以 VerifyError 爆出来。
 */
class Checker(
    private val program: Program,
    private val source: SourceFile,
    /** 多文件时：每个函数来自哪个文件，报错才能指对文件。单文件时为空。 */
    private val sourceOfFunction: Map<FuncDecl, SourceFile> = emptyMap(),

    /** 每个类来自哪个文件（类相关的报错要指对文件）。
*/
    private val sourceOfClass: Map<ClassDecl, SourceFile> = emptyMap(),

    /** 每个枚举来自哪个文件（枚举相关的报错要指对文件）。
*/
    private val sourceOfEnum: Map<EnumDecl, SourceFile> = emptyMap(),

    /** 每个接口来自哪个文件（接口相关的报错要指对文件，和 [sourceOfClass] 同一个理由）。
*/
    private val sourceOfInterface: Map<InterfaceDecl, SourceFile> = emptyMap(),

    /** 每个注解来自哪个文件（注解相关的报错要指对文件，阶段 21，和上面几个同一个理由）。
*/
    private val sourceOfAnnotation: Map<AnnotationDecl, SourceFile> = emptyMap(),
) {

    /**
     * 顶层函数：**名字 → 候选列表**（阶段 19d 起支持重载，声明顺序）。
     *
     * 名字一样、参数表不同的函数共存于同一张表；具体选中哪一个由每次调用的
     * 重载解析决定，结果记进 [callTargets]。
     */
    private val functionTable = LinkedHashMap<String, MutableList<FuncDecl>>()
    private val exprTypes = IdentityHashMap<Expr, RsfType>()

    /** `as` / `is` 的目标类型（阶段 24），两个后端都从这里读，不各自再解析一遍。 */
    private val castTargets = IdentityHashMap<Expr, RsfType>()
    private val varRefs = IdentityHashMap<VarExpr, Binding>()
    private val assignRefs = IdentityHashMap<AssignStmt, Binding>()
    private val declBindings = IdentityHashMap<VarDeclStmt, Binding>()
    private val paramBindings = IdentityHashMap<FuncDecl, List<Binding>>()
    private val paramTypes = IdentityHashMap<FuncDecl, List<RsfType>>()
    private val callTargets = IdentityHashMap<CallExpr, FuncDecl>()

    /**
     * 阶段 31：每一处**泛型调用**推导出来的类型实参绑定（键是调用点表达式）。
     *
     * 只记不用：31a 不发码，31b 的字节码后端按它在调用点插 `CHECKCAST`
     * （擦除之后 `fn id&<T>(x : T) -> T` 编出来是 `id(Object)Object`）。
     */
    private val genericCalls = IdentityHashMap<Expr, GenericCallSite>()
    private val returnTypes = IdentityHashMap<FuncDecl, RsfType>()
    private val lowered = IdentityHashMap<FuncDecl, LoweredFunction>()
    private val diagnostics = ArrayList<Diagnostic>()

    // ---------------------------------------------------------------- 函数类型（阶段 30）

    /**
     * 函数类型的**签名表**：`"(int) -> int"` → 形参类型 + 返回类型。
     *
     * 函数类型本身在 [RsfType] 里只是一个 `EXTERNAL` 类型（internal name 是
     * `rsf/fn/(int) -> int`），报错时正好印成 `(int) -> int`；可空性不参与键
     * （`?(int) -> int` 与非空版共用同一条签名）。调用函数值、拿期望类型补 `lam`
     * 的形参类型，都要把「形参表 / 返回类型」再取回来，所以另存一张表，
     * 键就是那段显示文本（见 `RsfType.fnTypeText`）。
     */
    private val fnSignatures = HashMap<String, FnSignature>()

    /** 一个函数类型的形状（阶段 30）：形参类型表 + 返回类型。 */
    private class FnSignature(val params: List<RsfType>, val ret: RsfType)

    /**
     * 「期望类型」：`lam` 的形参类型 / 返回类型靠它补（DESIGN-30 §4 第 3 条）。
     *
     * 目前三个传播点是变量声明的初始化表达式、`rtn` 的值、以及只有一个候选的重载实参。
     * 用 [withExpectation] 成对设置 / 还原，嵌套时取最内层。
     */
    private var expectation: RsfType? = null

    /**
     * 正在检查的 `lam` 体里**读到过**的外层绑定（DESIGN-30 §4 第 4 条「捕获」）。
     *
     * 不绕弯子地做了个探针：形参 / 局部变量的读取都要经过 [computeType] 的 `VarExpr`
     * 分支，那里顺手记一笔，[checkLambda] 事后拿「外层可见的绑定集合」一减，就知道
     * 谁被捕获、是不是 `var`。比事后遍历一遍 lambda 体的 AST 少写一套 walker，
     * 也不会漏掉藏在深层的读。
     */
    private var captureProbe: MutableList<Pair<VarExpr, Binding>>? = null

    // 逐函数状态
    private lateinit var currentFn: FuncDecl
    private var currentReturn: RsfType = RsfType.VOID
    private var scope = Scope(null)
    private var loopDepth = 0
    private val labels = HashSet<String>()

    /**
     * 当前是不是在 `try` 块里（> 0 是，按嵌套层数计数）。
     *
     * 它放行两件事：块里的 `fail`（错误由这个 try 接住）、块里未做处理的 `!T` 调用。
     * `catch` 的处理体**不在**保护范围内，所以进处理体前会把它减回去。
     */
    private var tryDepth = 0

    /**
     * 「这个调用上的错误已经被显式处理」——就是 `?:` 的左边 / `!!` 的操作数**那一个**调用。
     *
     * 用节点身份而不是布尔开关：`f(parse("x")) ?: "d"` 里 `?:` 处理的是 `f(...)`
     * （它不是 !T 调用，走的是「null 就取右边」），藏在实参里的 `parse` 仍然要自己处理。
     * 用开关的话这种调用会被误放过去。
     */
    private var handledNode: Expr? = null

    /** `try { ... } catch (e : string) { ... }` 的 catch 变量绑定；Lowering 用它分配槽位。 */
    private val catchBindings = IdentityHashMap<TryStmt, Binding>()

    /** 当前正在检查的语句属于哪个文件（多文件时用）。
*/
    private var currentSource: SourceFile = source

    fun check(): SemanticModel {
        collectImports()
        // 注解名最先占住（阶段 21）：注解和类/枚举/接口共用类型命名空间，先占住名字，
        // 后面三趟才能报出「重名」——反过来的顺序只能报一半（谁先谁后报谁）。
        declareAnnotationNames()
        // 枚举最先声明：它们不引用任何别的类型，而且**类名/函数名重名检查要看得见它们**
        // （枚举和类共用类型命名空间，先占住名字，后面两趟才好报「重名」）。
        declareEnums()
        // 接口名要先占住（阶段 20）：`class Dog : Greeter` 和 `fun f(g : Greeter)` 都得
        // 看得见它，而这两件事分别发生在类声明的第二遍和函数声明的第一遍里。
        declareInterfaceNames()
        // 类要先声明：顶层函数的签名里可能用到类名（`fun f(Point p) -> int`），
        // 反过来的顺序会让 resolveType 看不到类表。
        declareClasses()
        // 类继承的**声明级**规矩（阶段 25）：父类得是 open、字段不许遮蔽、覆盖要写
        // override 且父类那个方法得 open、abstract 成员得配上 abstract 类。放在这里
        // 而不是 declareClassBody 里，是因为 `class D : Base` 完全可能写在 Base 前面——
        // 这一趟才保证每个类的字段表/方法表都已经填好（declareClasses 的第二遍）。
        checkClassParents()
        // 接口的方法与父接口放在类声明**之后**：`interface A : B` 里的 B 得先能分清
        // 「这是接口」还是「这是类（D2：暂不支持继承类）」——那要靠类表已经填满。
        declareInterfaceBodies()
        // 注解的**体**（阶段 21）：元素类型白名单 + companion entity 合成类。
        // 放在类声明与接口体之后：元素类型可能是枚举/注解（要那两张表已经填满），
        // 伴生实体自己写的父接口可能是这份程序里的接口（要接口表已经填满）。
        declareAnnotationBodies()
        declareFunctions()
        // 实现配对只看签名，不看方法体，所以放在查任何体之前：先把「你没实现 greet」
        // 这种错报出来，用户改完再看体里的错。
        checkImplementations()
        // `override` 只是「这句覆盖是真的」的断言，判据同样是签名，所以和实现配对挨着跑：
        // 两处的报错文案会一起出现在用户改代码的第一轮里。
        checkOverrides()
        for (fn in program.functions) {
            checkFunction(fn)
        }
        checkInterfaceBodies()
        checkClassBodies()
        // 使用点（`@Marker(...)`）放在最后：它要按元素的**已解析**类型表配对，
        // 也可能引用伴生类（`new Marker(...)` 的实参），所以等所有声明都查完再配对。
        checkAnnotationUses()

        // 阶段 12 分三步：语法 ✅ → 语义 ✅ → 后端 ✅（解释器与字节码都通了）
        // 类的 .class 用 BytecodeCompiler 按类逐个生成）。
        return SemanticModel(
            functions = program.functions.toList(),
            functionTable = functionTable,
            exprTypes = exprTypes,
            castTargets = castTargets,
            varRefs = varRefs,
            assignRefs = assignRefs,
            declBindings = declBindings,
            paramBindings = paramBindings,
            paramTypes = paramTypes,
            callTargets = callTargets,
            memberTargets = memberTargets,
            returnTypes = returnTypes,
            lowered = lowered,
            diagnostics = diagnostics.toList(),
            packageName = program.packageName,
            classModel = ClassModel(
                classes = classTable.mapValues { it.value.decl },
                owner = methodOwner,
                methodParams = methodParams,
                methodReturns = methodReturns,
                methodLowered = methodLowered,
                fieldTypes = classTable.values.associate { it.decl to (classFieldTypes[it.decl] ?: emptyList()) },
                members = userMembers,
                fieldReads = fieldReads,
                bareFieldWrites = bareFieldWrites,
                fieldWrites = fieldWrites,
                newTargets = newTargets,
                newCtors = newCtors,
                ctorDelegations = ctorDelegations,
                implicitCalls = implicitCalls,
                interfaceOwner = interfaceOwnerTable,
                superCalls = superCalls,
                superDelegations = superDelegations,
                implicitSuperDelegations = implicitSuperDelegations,
                implicitJvmCalls = implicitJvmCalls,
            ),
            externalNewTargets = externalNewTargets,
            enums = enumTable.toMap(),
            enumValues = enumValueRefs,
            interfaces = interfaceTable.mapValues { it.value.decl },
            superInterfaces = collectSuperInterfaces(),
            superClasses = classParents.entries.associate { (decl, parent) -> decl.name to parent },
            annotations = annotationTable.mapValues { it.value.spec ?: throw IllegalStateException("注解 '${it.key}' 没有解析结果") },
            annotationUses = annotationUses.toList(),
            annotationNewTargets = annotationNewTargets,
            companionAnnotations = companionAnnotations,
            annotationAccessors = annotationAccessors.toList(),
            genericCalls = genericCalls,
        )
    }

    // ------------------------------------------------------------------ 作用域
    private class Scope(private val parent: Scope?) {
        private val vars = HashMap<String, Binding>()

        fun declares(name: String): Boolean = vars.containsKey(name)
        fun declare(name: String, binding: Binding) { vars[name] = binding }
        fun lookup(name: String): Binding? = vars[name] ?: parent?.lookup(name)

        /** 把这个作用域链上**所有**绑定收进 [into]（阶段 30：判断 `lam` 捕获的是外层谁）。 */
        fun collectAll(into: MutableSet<Binding>) {
            into.addAll(vars.values)
            parent?.collectAll(into)
        }
    }
    // ------------------------------------------------------------------ 函数表
    private fun declareFunctions() {
        for (fn in program.functions) {
            currentSource = sourceOfFunction[fn] ?: source
            if (fn.name in BUILTINS) {
                throw err("'${fn.name}' 是内置函数，不能重定义", fn.at)
            }
            if (classTable.containsKey(fn.name)) {
                throw err("'${fn.name}' 已经是一个类名了，函数名和类名不能重名", fn.at)
            }
            if (enumTable.containsKey(fn.name)) {
                throw err(
                    "'${fn.name}' 已经是一个枚举名了，函数名和枚举名不能重名" +
                        enumValueList(fn.name),
                    fn.at,
                )
            }
            if (interfaceTable.containsKey(fn.name)) {
                throw err("'${fn.name}' 已经是一个接口名了，函数名和接口名不能重名", fn.at)
            }
            if (annotationTable.containsKey(fn.name)) {
                throw err("'${fn.name}' 已经是一个注解名了，函数名和注解名不能重名", fn.at)
            }
            // 阶段 19d：同名函数不再一律报错——先收进候选表，最后统一做
            // 「擦除后签名是否撞车」的检查（见 checkErasureConflicts）。
            functionTable.getOrPut(fn.name) { ArrayList() }.add(fn)

            // 顶层函数编译出来本来就是静态方法，写了 static 是多余的——提醒一下就好，不拦
            if (fn.isStatic) {
                diagnostics.add(
                    Diagnostic(
                        Severity.WARNING, "checker",
                        "顶层函数本来就是静态的（按类名调用），这里的 'stc' 是多余的",
                        currentSource.name, fn.at.line, fn.at.column,
                    ),
                )
            }

            // 阶段 31：泛型函数自己的类型参数要先并进解析环境（`x : T` 才认得出 T 是占位）
            val savedTypeParams = pushTypeParams(fn)

            // 参数：解析类型、建绑定、查量
            val types = ArrayList<RsfType>(fn.params.size)
            val bindings = ArrayList<Binding>(fn.params.size)
            val seenNames = HashSet<String>()
            for ((index, p) in fn.params.withIndex()) {
                val paramType = resolveType(p.type)
                if (paramType == RsfType.VOID) {
                    throw err("参数 '${p.name}' 的类型不能是 void", p.at)
                }
                if (!seenNames.add(p.name)) {
                    throw err("函数 '${fn.name}' 的参数 '${p.name}' 重名了", p.at)
                }
                types.add(paramType)
                // 参数按 C/C++/Java 的惯例是可变的：语法里没有给参数写 var/imutvar 的位置，
                // 设成不可变就再也表达不出「我要改这个参数」了（只能先拷一份到局部变量）
                bindings.add(
                    Binding(p.name, paramType, mutable = true, paramIndex = index, at = p.at),
                )
            }
            paramTypes[fn] = types
            paramBindings[fn] = bindings

            if (fn.mainType.name != "void") {
                throw err(
                    "<MainType> 目前只支持 void（实际是 '${fn.mainType.name}'）。" +
                        "它是 fn 之后、函数名之前的类型槽，留给将来的接收者类型/类作用域用。",
                    fn.mainType.at,
                )
            }

            // 阶段 14：`!T` 的语义是「正常路径返回 T，出错时走异常」——返回类型本身不用额外
            // 表示（returnTypes 里存的就是那个 T）。真正的约束在**调用方**：调用 !T 函数必须
            // 显式处理错误，见 requireErrorHandled。
            returnTypes[fn] = resolveType(fn.ret.type)
            typeParams = savedTypeParams
        }

        // 重载的合法性：同名函数必须在 JVM 上是不同的方法（擦除后的描述符不能撞车）。
        // 顶层函数全部编进同一个程序类的静态方法，所以顶层之间也一样适用。
        checkErasureConflicts(null, functionTable)
    }

    // ------------------------------------------------------------------ 类（阶段 12）
    /**
     * 一个用户自定义类的全部信息。
     *
     * 类的类型直接用 `RsfType.external(类名)` 表示：rsf 生成的类都在默认包里，
     * 所以「类名」本身就是合法的内部名，JVM 描述符也正好是 `LPoint;`——
     * 不用再往 RsfBase 里加一个新枚举值（少动一处，就少一处 when 要跟着改）。
     */
    private class ClassInfo(
        val decl: ClassDecl,
        val source: SourceFile,
        val fields: LinkedHashMap<String, FieldDecl>,
        /**
         * 这个方法名下的全部重载（阶段 19d），**按声明顺序**。
         *
         * 名字 → 候选列表；具体调用选中哪一个由重载解析决定，结果记进
         * [Checker.callTargets]（隐式调用记 [Checker.implicitCalls]，
         * 显式成员调用记 [Checker.userMembers]），两个后端都只读那一份。
         */
        val methods: LinkedHashMap<String, MutableList<FuncDecl>>,
        /**
         * 这个类的构造器，**按声明顺序**：主构造（类头括号，解析时合成）在前，
         * 次构造（`constructor(...)`）按源码顺序在后。
         *
         * 空表有明确含义：这个类一个构造器都没写 ⇒ 等价于一个隐式的无参构造器
         * （`new 类名()` 能用、字段初值照跑）。写了任何一个之后就没有隐式的了。
         */
        val constructors: List<FuncDecl>,
    ) {
        val type: RsfType get() = RsfType.external(decl.name)

        /** 主构造；没写类头括号就是 null。有主构造时，次构造必须委托（见 checkClassBodies 的链校验）。 */
        val primary: FuncDecl? get() = constructors.firstOrNull { it.isPrimary }
    }

    private val classTable = LinkedHashMap<String, ClassInfo>()

    // ------------------------------------------------------------------ 接口（阶段 20）

    /**
     * `:` 子句里的一项**解析结果**（阶段 20）。
     *
     * 只可能是接口：类继承本批不支持（D2），所以这里没有「父类」那一支——
     * 用户写 `class A : SomeClass` 时 Checker 立刻报错，不会静默变成一个没生效的项。
     *
     * 两种来源要分清（后端的处理完全不同）：
     * - [Rsf]：源码里声明的接口，方法体在 rsf 这一侧，按 [InterfaceInfo.methods] 查；
     * - [Jvm]：classpath 上的接口（`Runnable`、`Comparator`），方法签名只能从
     *   class 文件里读（[JvmClass.methods]），抽象方法必须被实现类覆盖。
     */
    private sealed interface SuperRef {
        /** 报错里的可读名字（rsf 接口名 / JVM 内部名）。 */
        val display: String

        data class Rsf(
            val info: InterfaceInfo,
            /**
             * `: Box&<int>` 里那串类型实参（解析后的）。空表 = 没写实参。
             *
             * 实现配对与调用点都要用它把接口方法签名里的 `T` 换成 `int`——
             * 不换的话 `interface Box&<T> { fun get() -> T }` 会被判成「类里的
             * `get() -> int` 返回类型和 `T` 不一致」而误报。
             */
            val args: List<RsfType> = emptyList(),
        ) : SuperRef {
            override val display: String get() = info.decl.name
        }

        data class Jvm(val cls: JvmClass) : SuperRef {
            override val display: String get() = cls.internalName
        }
    }

    /**
     * 一个接口的解析产物（阶段 20）。
     *
     * 和 [ClassInfo] 平级，但少了字段与构造器：接口**只有方法**
     * （D3：写字段在 Parser 就被拦下，Checker 这一侧根本不存在字段表）。
     * [supertypes] 在 [declareInterfaceBodies] 里解析一次就定下来——继承链是静态的，
     * 两个后端都不该自己再沿 AST 走一遍。
     */
    private class InterfaceInfo(
        val decl: InterfaceDecl,
        val source: SourceFile,
        /** 这个方法名下的全部重载（阶段 19d 的规矩照旧），按声明顺序。 */
        val methods: LinkedHashMap<String, MutableList<FuncDecl>>,
        /**
         * 直接父接口（`: A, B` 里那一串），已经解析成 [SuperRef]。
         *
         * 是 `var` 有实际原因：类头里的 `: Greeter` 必须在**类声明那一趟**就解析完
         * （那时才分得清父类型是接口还是类，见 D2），而接口自己的方法表要晚一趟
         * （[declareInterfaceBodies]）才填。也就是说，`SuperRef.Rsf` 会先一步抓住
         * 这个 info 对象——**两趟必须填同一个对象**，否则第一个引用它的地方看到的
         * 是一个空壳（方法表空、父接口空），实现配对会静默地一个要求都提不出来。
         */
        var supertypes: List<SuperRef>,
    ) {
        /** 接口类型和用户类是同一个形状：`RsfType.external(接口名)`。 */
        val type: RsfType get() = RsfType.external(decl.name)
    }

    /**
     * 接口声明，按名字索引。
     *
     * 接口、类、枚举**共用类型命名空间**：重名要报错，所以三张表之间互相查重
     * （见 [declareInterfaceNames] / [declareClasses] 的第一遍）。
     */
    private val interfaceTable = LinkedHashMap<String, InterfaceInfo>()

    /** 接口方法（抽象 + 默认）→ 它属于哪个接口。默认方法要靠它分配 `this` 槽位。 */
    private val interfaceOwnerTable = IdentityHashMap<FuncDecl, InterfaceDecl>()

    /** 每个类解析后的父类型列表（`: A, B`）；没写 `:` 的类不在表里，读的时候用空表兜底。 */
    private val classSuperRefs = IdentityHashMap<ClassDecl, List<SuperRef>>()

    // ------------------------------------------------------------------ 类继承（阶段 25 K1）

    /**
     * 类声明 → 已解析的**父类**（`class D(y : int) : Base(y)` 里的 `Base`）。
     *
     * 父类不塞进 [classSuperRefs]：那张表是「父**接口**列表」，下游每一处都默认
     * 里面是接口（实现配对、`INVOKEVIRTUAL` 的 owner…）。父类的规矩完全不同
     * （唯一、必须有构造实参、必须排最前），混在一张表里每次都要再问一遍
     * 「这项是类还是接口」——那正是 D2 要避免的含糊（见 [SuperClass] 的说明）。
     */
    private val classParents = IdentityHashMap<ClassDecl, SuperClass>()

    /** 类声明 → 父类的 JVM 类；父类来自 classpath 时才有（rsf 父类不在表里）。 */
    private val classParentJvm = IdentityHashMap<ClassDecl, JvmClass>()

    /** 构造器 → 它的**父类构造委托**（次构造里那句 `super(实参)`，或主构造的类头实参）。 */
    private val superDelegations = IdentityHashMap<FuncDecl, SuperDelegation>()

    /** 一个构造器都没写的类 → 隐式无参构造器的父类调用（那时没有 [FuncDecl] 可以当键）。 */
    private val implicitSuperDelegations = IdentityHashMap<ClassDecl, SuperDelegation>()

    /** `super.方法(...)` / `super(接口).方法(...)` → 解析到的方法（两个后端只读这一份）。 */
    private val superCalls = IdentityHashMap<MemberExpr, SuperCall>()

    /** 隐式 this 调用**JDK 父类继承来**的方法（G5，阶段 25 K1）。 */
    private val implicitJvmCalls = IdentityHashMap<CallExpr, ResolvedMethod>()

    /** 这个类的父类（没写 `:` 里的类就是 null）。 */
    private fun parentRecord(info: ClassInfo): SuperClass? = classParents[info.decl]

    /** 这个类的父**类**信息；父类来自 classpath（JDK 类）时是 null。 */
    private fun parentOf(info: ClassInfo): ClassInfo? =
        parentRecord(info)?.declaration?.let { classTable[it.name] }

    /** 父类链，从**根祖先**到直接父类（不含自己）。 */
    private fun ancestorsOf(info: ClassInfo): List<ClassInfo> {
        val out = ArrayList<ClassInfo>()
        var cur = parentOf(info)
        while (cur != null && out.none { it === cur }) {
            out.add(0, cur)
            cur = parentOf(cur)
        }
        return out
    }

    /**
     * 本类字段在对象字段数组里的**起始下标**：父类字段排在前面（DESIGN-25 §5）。
     *
     * 于是「绝对下标 = 父链字段总数 + 本类段内下标」，这份布局**只在这里算一次**，
     * 结果烘进 [UserMember.Field]，两个后端都只读那一份（R1）。
     */
    private fun fieldBase(info: ClassInfo): Int = ancestorsOf(info).sumOf { it.decl.fields.size }

    /** 沿父类链找**声明**这个字段的类（含自己，最近的类优先）。 */
    private fun fieldOwnerOf(info: ClassInfo, name: String): ClassInfo? {
        if (info.fields.containsKey(name)) return info
        for (anc in ancestorsOf(info).reversed()) if (anc.fields.containsKey(name)) return anc
        return null
    }

    /** 找字段（含父类链）：返回「声明它的类」+ 那个字段；都没有就是 null。 */
    private fun lookupField(info: ClassInfo, name: String): Pair<ClassInfo, FieldDecl>? {
        fieldOwnerOf(info, name)?.let { owner -> owner.fields[name]?.let { return owner to it } }
        return null
    }

    /**
     * 在**类链**上找第一个满足 [pick] 的方法（含自己，最近的类优先）。
     *
     * 静态方法与 private 方法**不继承**（DESIGN-25 §3）：调用的解析顺序里根本
     * 不该看见它们，所以这里先滤掉，让「类 'D' 里没有方法 'f'」这类报错如实发生。
     */
    private fun methodOnChain(info: ClassInfo, name: String, pick: (FuncDecl) -> Boolean): FuncDecl? {
        info.methods[name]?.firstOrNull(pick)?.let { return it }
        for (anc in ancestorsOf(info).reversed()) {
            anc.methods[name]?.firstOrNull { pick(it) && !it.isStatic && it.control != ControlType.PRIVATE }
                ?.let { return it }
        }
        return null
    }

    /** [methodOnChain] 的「顺便告诉我它声明在哪个类」版本。 */
    private fun declaredMethodOnChain(
        info: ClassInfo,
        name: String,
        pick: (FuncDecl) -> Boolean,
    ): Pair<ClassInfo, FuncDecl>? = declaredMethodOnChain(info, name, includeSelf = true, pick = pick)

    /**
     * [declaredMethodOnChain] 的可选「含不含自己」版本。
     *
     * `super.方法(...)` 必须传 `includeSelf = false`：它要的是**父类那一份**实现，
     * 把自己也搜进去的话，同名方法会命中「本类刚写的那个 override」，选中自己——
     * 后端会发出一句自我递归调用（阶段 25，DESIGN-25 §3）。
     */
    private fun declaredMethodOnChain(
        info: ClassInfo,
        name: String,
        includeSelf: Boolean,
        pick: (FuncDecl) -> Boolean,
    ): Pair<ClassInfo, FuncDecl>? {
        if (includeSelf) {
            info.methods[name]?.firstOrNull(pick)?.let { return info to it }
        }
        for (anc in ancestorsOf(info).reversed()) {
            anc.methods[name]?.firstOrNull { pick(it) && !it.isStatic && it.control != ControlType.PRIVATE }
                ?.let { return anc to it }
        }
        return null
    }

    /** 子类里这个方法同签名的**父类**方法（父链上最近的）；没有就是 null。 */
    private fun inheritedMethodSameSignature(info: ClassInfo, m: FuncDecl): Pair<ClassInfo, FuncDecl>? {
        val key = signatureKey(m.name, paramTypesOfDecl(m))
        for (anc in ancestorsOf(info).reversed()) {
            for (list in anc.methods.values) {
                for (p in list) {
                    if (p.isConstructor || p.isStatic) continue
                    if (signatureKey(p.name, paramTypesOfDecl(p)) == key) return anc to p
                }
            }
        }
        return null
    }

    /**
     * 把接收者的类型实参**一路上溯代换**到 [owner] 那一层（阶段 25 K1，G4）。
     *
     * `class Holder&<T>(v0 : T) { fun get() -> T }` 配 `class IntHolder(v0 : int) :
     * Holder&<int>(v0)` 时，`h.get()` 拿到的声明在 Holder 上、返回类型写的是 `T`：
     * 只看 IntHolder 自己的类型实参（一个都没有）永远代不出 int，必须在每一跳
     * 把父类实参按当前代换算出来，一层层传到**声明它的那个类**。
     *
     * [startSubst] 是 [start] 那一层的代换表（`自己的类型参数 → 实际类型`）。
     */
    private fun substitutionAlongChain(
        start: ClassInfo,
        startSubst: Map<String, RsfType>,
        owner: ClassInfo,
    ): Map<String, RsfType> {
        var subst = startSubst
        var cur = start
        while (cur !== owner) {
            val next = parentOf(cur) ?: return subst
            val rec = parentRecord(cur) ?: return subst
            subst = substitutionFor(next.decl.typeParams, rec.args.map { it.substitute(subst) })
            cur = next
        }
        return subst
    }

    /**
     * [substitutionAlongChain] 的「从类体内部出发」版本（阶段 25 K1）。
     *
     * 类体里裸写 `v` / `bump()` 时接收者是隐式的 this，当前类自己的类型参数
     * 保持原样（它们在类体里就是类型变量），只把祖先那一层代换成具体类型。
     */
    private fun substitutionFromInside(info: ClassInfo, owner: ClassInfo): Map<String, RsfType> =
        substitutionAlongChain(info, info.decl.typeParams.associateWith { RsfType.typeParam(it) }, owner)

    /**
     * 解析类头 `: Base(实参)` 里的**父类名字**（阶段 25 K1），在 [declareClassBody] 里调用。
     *
     * 这一趟只认名字：实参表达式要等主构造的参数进作用域之后才算类型（[checkParentCalls]），
     * 声明级的规矩（final / 遮蔽 / override / abstract）留给 [checkClassParents]
     * ——因为那时所有类的签名才都看得见（`class D : Base` 写在 Base 前面也要能查）。
     */
    private fun registerParentClass(cls: ClassDecl, ref: TypeRef) {
        val where = "类 '${cls.name}'"
        val display = ref.display()
        if (ref.arrayDepth > 0) {
            throw err("$where 的父类 '$display' 不能是数组——父类要是一个 class", ref.at)
        }
        if (RsfType.fromName(ref.name) != null) {
            throw err(
                "'$display' 是内置类型，不能当父类——父类要是一个 class（rsf 的或 JDK 的）",
                ref.at,
            )
        }
        val args = ref.typeArgs.map { resolveType(it) }
        enumTable[ref.name]?.let {
            throw err(
                "$where 不能继承 '$display'：'$display' 是个枚举。" +
                    "枚举不能被继承，也不能继承别的类（DESIGN-25 §6）",
                ref.at,
            )
        }
        annotationTable[ref.name]?.let {
            throw err("$where 不能继承 '$display'：'$display' 是个注解，注解不能当父类", ref.at)
        }
        interfaceTable[ref.name]?.let {
            throw err(
                "$where 不能继承 '$display'：'$display' 是个接口。接口写在父类型列表里、" +
                    "而且不写实参（`: $display`）；要继承类就写 ': 父类(实参)'",
                ref.at,
            )
        }
        val parentInfo = classTable[ref.name]
        if (parentInfo == null) {
            val jvm = resolveJvmClass(ref.name)
                ?: throw err(
                    "$where 的父类 '$display' 找不到：它既不是这份程序里声明的类，" +
                        "也不是 classpath 上的类型（要继承 Java/Kotlin 的类得先 import 它）",
                    ref.at,
                )
            if (jvm.isInterface) {
                throw err(
                    "$where 不能继承 '$display'：它是 classpath 上的**接口**。" +
                        "接口写在父类型列表里、而且不写实参（`: $display`）",
                    ref.at,
                )
            }
            // final 的 JDK 类不能当父类（阶段 25 K4，缺口 A）。判据只能来自 classpath：
            // `class SB : StringBuilder(...)` 解释器侧跑得通（rsf 的父类只是一条模型边），
            // 字节码侧却是**真的** extends——JVM 装载时抛
            // `IncompatibleClassChangeError: class SB cannot inherit from final class`。
            if (jvm.isFinal) {
                throw err(
                    "$where 不能继承 '$display'：它在 JDK 里是 **final** 的（不可继承）。" +
                        "两端都会崩——解释器现在放行，字节码产物一到 JVM 装载就抛 " +
                        "IncompatibleClassChangeError（DESIGN-25 §4）。换一个非 final 的父类，" +
                        "或者把这层关系写成**组合**（持有一个 '$display' 字段）",
                    ref.at,
                )
            }
            // 抽象 JDK 类（`Thread` 之类）照样可以继承：抽象方法由子类实现（DESIGN-25 §3）
            jvm.typeParamCount?.let { checkArity(jvm.simpleName, it, args, ref.at) }
            classParentJvm[cls] = jvm
            classParents[cls] = SuperClass(
                name = ref.name,
                internalName = jvm.internalName,
                args = args,
                fromClassPath = true,
                declaration = null,
            )
            return
        }
        checkArity(ref.name, parentInfo.decl.typeParams.size, args, ref.at, parentInfo.decl.typeParams)
        classParents[cls] = SuperClass(
            name = ref.name,
            internalName = ref.name,
            args = args,
            fromClassPath = false,
            declaration = parentInfo.decl,
        )
    }

    /**
     * 类继承的**声明级**检查（阶段 25 K1），在 [declareClasses] 之后、实现配对之前跑。
     *
     * 为什么单独一趟：`class D : Base` 完全可能写在 Base 前面，那时 [declareClassBody]
     * 还没走到 Base；而这一趟里所有类的字段表、方法表都已经填好了。
     */
    private fun checkClassParents() {
        // 先把**所有**类的父类名字解析完。为什么不在 declareClassBody 里顺手解析：
        // 那一趟是按源码顺序走第二遍的，`class D : Base` 写在 Base 前面时还看不见 Base
        // （classTable 里还没有它）。这一趟在 declareClasses() 之后，所有类名/签名都已就位。
        for (cls in program.classes) {
            currentSource = sourceOfClass[cls] ?: source
            // 父类的泛型实参要用**这个类**的类型参数来解析（`class D&<T> : Base&<T>(...)`）
            typeParams = cls.typeParams.associateWith { RsfType.typeParam(it) }
            cls.superClass?.let { registerParentClass(cls, it) }
        }
        typeParams = emptyMap()
        for (info in classTable.values) {
            if (parentRecord(info) == null) continue
            currentClass = info
            currentSource = info.source
            checkParentCycle(info)
            checkParentModifiers(info)
            checkFieldShadowing(info)
            checkInheritedOverrides(info)
            checkAbstractMembers(info)
        }
        currentClass = null
    }

    /** 父类成环（`class A : B` + `class B : A`）永远算不完，就地报掉。 */
    private fun checkParentCycle(info: ClassInfo) {
        val seen = LinkedHashSet<String>()
        var cur: ClassInfo? = info
        while (cur != null) {
            if (!seen.add(cur.decl.name)) {
                throw err(
                    "类继承成了环：${seen.joinToString(" -> ")} -> ${cur.decl.name}。" +
                        "把其中一条 ': 父类(实参)' 去掉",
                    cur.decl.at,
                )
            }
            cur = parentOf(cur)
        }
    }

    /** 父类不能是 `final`：rsf 的类默认开放，只有写了 `final` 才封（DESIGN-27）。 */
    private fun checkParentModifiers(info: ClassInfo) {
        val parent = parentRecord(info) ?: return
        val decl = parent.declaration ?: return
        if (!decl.isFinal) return
        throw err(
            "类 '${info.decl.name}' 不能继承 '${decl.name}'：'${decl.name}' 是 final 的（DESIGN-27）。" +
                "rsf 的类默认可以被继承，只有写了 'final' 才禁止——把父类那行的 'final' 去掉才能继承：\n" +
                "  class ${decl.name}(...) { ... }",
            info.decl.at,
        )
    }

    /** 字段遮蔽一律禁止（DESIGN-25 §5）：子类看到的同名会分不清是谁那一份。 */
    private fun checkFieldShadowing(info: ClassInfo) {
        for (f in info.decl.fields) {
            val owner = ancestorsOf(info).firstOrNull { it.fields.containsKey(f.name) } ?: continue
            throw err(
                "类 '${info.decl.name}' 的字段 '${f.name}' 和父类 '${owner.decl.name}' 的字段重名了：" +
                    "rsf 不许字段遮蔽（DESIGN-25 §5）——名字一分不清是谁的，两端逐字节对拍就做不了。" +
                    "把子类这个字段换个名字，或者直接复用父类那个",
                f.at,
            )
        }
    }

    /**
     * 子类方法与父类同名同签名时的两条规矩（DESIGN-25 §4/§5，阶段 27 反转了第二条）：
     *
     * 1. **必须写 `override`**：不写的同名方法一律报错（rsf 与 Kotlin 的故意偏离——
     *    静默覆盖会让两端对拍失去意义）；
     * 2. 父类那个方法**默认可覆盖**（DESIGN-27）：只有写了 `final` 才不允许；
     *    抽象方法不算「可覆盖节点」，但子类的义务是实现它，所以照样放行。
     *
     * 「写了 override 却覆盖不到任何东西」不在这里报——那是 [checkOverrides] 的活，
     * 它同时还要看接口，两处报同一件事只会让用户改两遍。
     */
    private fun checkInheritedOverrides(info: ClassInfo) {
        for (list in info.methods.values) {
            for (m in list) {
                if (m.isConstructor) continue
                val found = inheritedMethodSameSignature(info, m) ?: continue
                val (owner, parentMethod) = found
                if (!m.isOverride) {
                    throw err(
                        "类 '${info.decl.name}' 的 '${m.name}' 和父类 '${owner.decl.name}' 的 " +
                            "${describeSignature(parentMethod)} 同签名了，但没写 override。" +
                            "rsf 不许遮蔽（DESIGN-25 §5）——要覆盖它就写成 'override fn ${m.name}(...)'，" +
                            "只是重名就换个名字",
                        m.at,
                    )
                }
                if (parentMethod.isOverridable || parentMethod.isAbstract) continue
                throw err(
                    "类 '${info.decl.name}' 的 '${m.name}' 不能覆盖父类 '${owner.decl.name}' 的 " +
                        "${describeSignature(parentMethod)}：父方法写了 'final'（DESIGN-27）。" +
                        "rsf 的方法默认可覆盖，'final' 是端点——去掉父方法上的 'final'，或者换个名字写一个新方法",
                    m.at,
                )
            }
        }
    }

    /** `abstract` 成员的类内规矩（DESIGN-25 §6）：类必须也是 abstract，而且不能是 private/static。 */
    private fun checkAbstractMembers(info: ClassInfo) {
        for (m in info.decl.methods) {
            if (!m.isAbstract) continue
            if (m.control == ControlType.PRIVATE) {
                throw err(
                    "类 '${info.decl.name}' 的 '${m.name}' 不能既是 abstract 又是 prv：" +
                        "prv 方法子类根本看不到，没有人能实现它",
                    m.at,
                )
            }
            if (info.decl.isAbstract) continue
            throw err(
                "类 '${info.decl.name}' 里的 '${m.name}' 是 abstract 方法，但类没有写 'abstract'：" +
                    "把类改成 'abstract class ${info.decl.name}(...)'，或者给 '${m.name}' 写上函数体",
                m.at,
            )
        }
        checkUnimplementedInheritedAbstract(info)
    }

    /**
     * 具体（非 abstract）子类必须把**父类链上**的抽象方法都实现（DESIGN-25 §7）。
     *
     * 只报父**类**那一支：接口那一支由 [checkClassCovers] 报（它连返回类型、可见性
     * 一起管，两处都报会让用户改两遍）。父类的抽象方法则不在它的视界里（接口要求的
     * 是「接口方法」，父类自己的 abstract 方法属于类链）。
     */
    private fun checkUnimplementedInheritedAbstract(info: ClassInfo) {
        // 抽象类不查：它本来就不该被 `new`，JDK 父类那几条抽象方法留给它这一支上的
        // 具体子类去实现——那条路会在子类上再查一遍（[unimplementedJvmParentAbstract]
        // 沿 rsf 父链找 JDK 父类，子类照样绕不过去）。
        if (info.decl.isAbstract) return
        val missing = unimplementedAbstractMembers(info, includeInterfaces = false) +
            unimplementedJvmParentAbstract(info)
        if (missing.isNotEmpty()) {
            throw err(
                "类 '${info.decl.name}' 还没有实现父类继承来的全部抽象方法，还缺 ${missing.size} 个：\n  - " +
                    missing.joinToString("\n  - ") +
                    "\n（在类 '${info.decl.name}' 里写一个同名、同参数类型的方法把它实现掉，" +
                    "或者把本类也写成 'abstract class ${info.decl.name}(...)'）",
                info.decl.at,
            )
        }
    }

    /**
     * JDK 父**类**链上还没有被实现的抽象方法（阶段 25 K4，缺口 B）。
     *
     * 接口那一支由 [checkClassCovers] 报，rsf 父类那一支由 [unimplementedAbstractMembers]
     * 报——这一支谁都没管：`class L : AbstractList<int>()` 会一路编到 JVM 装载之后才
     * 抛 `AbstractMethodError`，而解释器侧因为父类实例是**真实**的 JDK 对象、
     * 拿不到抽象方法体，表现完全不同。所以在这里一次性补齐。
     *
     * 判据与 [unimplementedAbstractMembers] 里的 JVM 口径一致：**名字 + 参数个数**
     * （JDK 方法的签名是擦除形态，参数类型逐个比会假阳性）。`java/lang/Object`
     * 自己的方法（[isObjectMethod]）天然跳过，`hashCode` / `equals` 不会被误报。
     */
    private fun unimplementedJvmParentAbstract(info: ClassInfo): List<String> {
        val parent = jvmParentOf(info) ?: return emptyList()
        val out = LinkedHashMap<String, String>()
        // 从**直接**父类往上整条 JDK 链：`AbstractList` 的抽象方法可能是从
        // `AbstractCollection` 继承来的，只认一层会漏。
        for (jc in ClassPath.hierarchy(parent.internalName)) {
            for (m in jc.methods) {
                if (!m.isAbstract || m.isStatic) continue
                if (isObjectMethod(m)) continue
                val key = signatureKey(m.name, m.paramTypes)
                if (out.containsKey(key)) continue
                val implemented = methodOnChain(info, m.name) { own ->
                    !own.isConstructor && !own.isStatic && !own.isAbstract &&
                        own.params.size == m.paramTypes.size
                } != null
                if (!implemented) out[key] = "${m.name}(${m.paramTypes.joinToString(", ") { it.rsfName }})"
            }
        }
        return out.values.toList()
    }

    /**
     * 这个类**还欠着**的抽象成员，一行一条可读描述（报错与「抽象类不能 new」共用）。
     *
     * 三个来源：自己声明的 abstract 方法、父类链上还没有被实现的 abstract 方法、
     * （[includeInterfaces] 时）父接口要求而类里没给的抽象方法——默认方法顶掉的规则
     * 与 [checkClassCovers] 保持同一口径，两处不会给出互相矛盾的结论。
     */
    private fun unimplementedAbstractMembers(info: ClassInfo, includeInterfaces: Boolean): List<String> {
        val out = LinkedHashMap<String, String>()
        for (m in info.decl.methods) {
            if (m.isAbstract) {
                out[signatureKey(m.name, paramTypesOfDecl(m))] = "${describeSignature(m)}（本类声明的）"
            }
        }
        for (anc in ancestorsOf(info)) {
            for (list in anc.methods.values) {
                for (p in list) {
                    if (!p.isAbstract || p.control == ControlType.PRIVATE) continue
                    val key = signatureKey(p.name, paramTypesOfDecl(p))
                    if (out.containsKey(key)) continue
                    val implemented = methodOnChain(info, p.name) { m ->
                        !m.isConstructor && !m.isStatic && !m.isAbstract &&
                            signatureKey(m.name, paramTypesOfDecl(m)) == key
                    } != null
                    if (!implemented) out[key] = "${describeSignature(p)}（父类 '${anc.decl.name}' 的）"
                }
            }
        }
        if (!includeInterfaces) return out.values.toList()
        val required = LinkedHashMap<String, MutableList<RequiredMethod>>()
        val seen = HashSet<String>()
        for (s in classSuperRefs[info.decl].orEmpty() + inheritedInterfaceRefs(info)) {
            when (s) {
                is SuperRef.Rsf -> collectRsfRequired(s.info, s.args, required, seen)
                is SuperRef.Jvm -> collectJvmRequired(s.cls, required, seen)
            }
        }
        for ((key, reqs) in required) {
            if (out.containsKey(key) || reqs.any { it.concrete }) continue
            val wanted = reqs.first()
            val fromJvm = wanted.decl == null
            val implemented = methodOnChain(info, wanted.name) { m ->
                !m.isConstructor && !m.isStatic && !m.isAbstract &&
                    (m.control == ControlType.PUBLIC || m.control == ControlType.DEFAULT) &&
                    if (fromJvm) {
                        m.params.size == wanted.params.size
                    } else {
                        signatureKey(m.name, paramTypesOfDecl(m)) == key
                    }
            } != null
            if (!implemented) out[key] = "${wanted.display()}（来自 ${wanted.ownerLabel}）"
        }
        return out.values.toList()
    }

    /**
     * 父类构造实参的检查与**选中**（阶段 25 K4，缺口 D）。
     *
     * 返回值是「rsf 父类那个构造器 + JDK 父类那个选中 `<init>`」这一对——调用方立刻
     * 把它们挂到**那条委托边**（[SuperDelegation]）上，两个后端都只读那一份，不再各自挑一遍。
     * 这正是 K4 要收的口：`BytecodeCompiler.jdkCtorScore` 与 `Interpreter.ctorScore`
     * 是同一套打分的**两份副本**，两边各有各的平局规则（`minByOrNull` 的先后、
     * `descriptor == descriptor` 的快捷路径），一旦对不上就是「两端产物不一致」这种最坏的分叉。
     */
    private fun checkedParentCall(
        info: ClassInfo,
        args: List<Expr>,
        at: SourceLine,
        what: String,
    ): Pair<FuncDecl?, JvmMethod?> {
        val before = selectedParentCtor
        selectedParentCtor = null
        val decl = checkParentArguments(info, args, at, what)
        val picked = selectedParentCtor
        selectedParentCtor = before
        return decl to picked
    }

    /**
     * 上一次 [checkParentArguments] 在 JDK 父类上挑中的那个 `<init>`；rsf 父类或没跑过时是
     * null。它只是 [`checkedParentCall`] 的**临时寄存器**（Kotlin 里没有多返回值），
     * 出了那一趟就交回给调用方、挂到那条委托边上。
     */
    private var selectedParentCtor: JvmMethod? = null
    /**
     * 父类构造实参的检查与**选中**（阶段 25 K1）。
     *
     * [args] 在当前作用域里求类型（主构造那条用的是主构造的参数作用域）；rsf 父类走
     * [resolveCtor] 挑构造器（和 `new` 同一套打分），JDK 父类用 `resolver` 挑 `<init>`。
     * 选中结果由调用方记进 [superDelegations]。
     */
    private fun checkParentArguments(
        info: ClassInfo,
        args: List<Expr>,
        at: SourceLine,
        what: String,
    ): FuncDecl? {
        val parent = parentRecord(info) ?: return null
        val argTypes = ArrayList<RsfType>(args.size)
        for ((i, arg) in args.withIndex()) {
            val t = type(arg)
            if (t.isVoid) {
                throw err("$what 的第 ${i + 1} 个实参是 void 表达式，不能当参数传", arg.at)
            }
            argTypes.add(t)
        }
        val decl = parent.declaration
        if (decl == null) {
            val jvm = classParentJvm[info.decl] ?: return null
            val ctor = resolver.findConstructor(jvm, argTypes)
                ?: throw err(
                    "${parent.display} '${parent.name}' 里没有匹配的构造器（实参：" +
                        argTypes.joinToString(", ") { it.rsfName } + "）" +
                        if (jvm.constructors.isNotEmpty()) {
                            "。可用的有：" + resolver.constructorsOf(jvm).joinToString("；")
                        } else {
                            ""
                        },
                    at,
                )
            for ((i, arg) in args.withIndex()) {
                val declared = ctor.paramTypes.getOrNull(i) ?: continue
                if (!acceptsValue(declared, arg, argTypes[i])) {
                    throw err(
                        "$what 的第 ${i + 1} 个实参类型不对：需要 ${declared.rsfName}，" +
                            "实际给人 ${argTypes[i].rsfName}",
                        arg.at,
                    )
                }
            }
            selectedParentCtor = ctor
            return null
        }
        val parentInfo = classTable[decl.name]
            ?: throw err("内部错误：父类 '${decl.name}' 不在类表里", at)
        if (parentInfo.constructors.isEmpty()) {
            // 父类一个构造器都没写 ⇒ 等价于一个隐式无参构造器
            selectedParentCtor = null
            if (args.isEmpty()) return null
            throw err(
                "父类 '${decl.name}' 没写构造器，只能 super()（或者 ${decl.name}()），" +
                    "实际给了 ${args.size} 个实参",
                at,
            )
        }
        selectedParentCtor = null
        val subst = substitutionFor(parentInfo, parent.args)
        return resolveCtor(parentInfo, args, argTypes, subst, at)
    }

    /**
     * 父类构造实参不许碰**自己的状态**（DESIGN-25 §5/§6）。
     *
     * 理由：父类构造器跑完之前，子类那份状态还一个字都没写（字段初值排在
     * `super(...)` **之后**）——实参里读自己的字段只会读到默认值。顺带把 `this`
     * 与 `super` 本身也挡掉：它们在那时都还没有意义。
     */
    private fun checkParentArgPurity(info: ClassInfo, args: List<Expr>, what: String) {
        for (arg in args) checkParentArgExpr(info, arg, what)
    }

    private fun checkParentArgExpr(info: ClassInfo, expr: Expr, what: String) {
        when (expr) {
            is ThisExpr -> throw err(
                "$what 里不能写 this：父类构造器跑完之前，子类那份状态还没有值（DESIGN-25 §6）。" +
                    "要用值就从主构造的参数传进去",
                expr.at,
            )
            is SuperExpr -> throw err("$what 里不能再写 super：父类的构造还没跑完", expr.at)
            is VarExpr -> {
                // 参数/局部变量**遮蔽**同名字段时是合法的（那时读的是参数，不是字段）
                if (scope.lookup(expr.name) == null && lookupField(info, expr.name) != null) {
                    throw err(
                        "$what 里不能读自己的字段 '${expr.name}'：父类构造器先跑，" +
                            "那时这个字段还没有值（DESIGN-25 §6）。" +
                            "要用这个值就从主构造的参数传进来",
                        expr.at,
                    )
                }
            }
            is MemberExpr -> {
                if (expr.receiver is ThisExpr || expr.receiver is SuperExpr) {
                    throw err(
                        "$what 里不能读 this 的成员：父类构造器先跑，" +
                            "那时子类那份状态还没有值（DESIGN-25 §6）",
                        expr.at,
                    )
                }
                checkParentArgExpr(info, expr.receiver, what)
                expr.arguments?.forEach { checkParentArgExpr(info, it, what) }
            }
            is CallExpr -> expr.arguments.forEach { checkParentArgExpr(info, it, what) }
            is UnaryExpr -> checkParentArgExpr(info, expr.operand, what)
            is BinaryExpr -> {
                checkParentArgExpr(info, expr.left, what)
                checkParentArgExpr(info, expr.right, what)
            }
            is IfExpr -> {
                checkParentArgExpr(info, expr.condition, what)
                checkParentArgExpr(info, expr.whenTrue, what)
                checkParentArgExpr(info, expr.whenFalse, what)
            }
            is ElvisExpr -> {
                checkParentArgExpr(info, expr.left, what)
                checkParentArgExpr(info, expr.right, what)
            }
            is ForceExpr -> checkParentArgExpr(info, expr.operand, what)
            is NewExpr -> expr.arguments.forEach { checkParentArgExpr(info, it, what) }
            is NewArrayExpr -> expr.dimensions.forEach { checkParentArgExpr(info, it, what) }
            is IndexExpr -> {
                checkParentArgExpr(info, expr.receiver, what)
                checkParentArgExpr(info, expr.index, what)
            }
            // 字面量、Nothing 之类没有子表达式
            else -> Unit
        }
    }

    /**
     * 主构造的父类调用（阶段 25 K1）：类头 `: Base(实参)` 就是它。
     *
     * 没有主构造时类头不许写实参（那时没有参数作用域可算）：要么给类写主构造，
     * 要么在每个 `constructor(...)` 的第一句写 `super(实参)`——报错里两条路都给出。
     */
    private fun checkParentCalls(info: ClassInfo) {
        val parent = parentRecord(info) ?: return
        val primary = info.primary
        if (primary != null) {
            val headerArgs = info.decl.superArgs
            val what = "父类 '${parent.name}' 的构造实参"
            checkParentArgPurity(info, headerArgs, what)
            val (target, jvmCtor) = checkedParentCall(info, headerArgs, primary.at, what)
            superDelegations[primary] = SuperDelegation(parent, target, headerArgs, null, jvmCtor)
            return
        }
        if (info.decl.superArgs.isNotEmpty()) {
            // 一个构造器都没写的类，本来就会有一个**隐式的无参主构造**：类头上的实参
            // 就是它转交给父类的那一份（DESIGN-25 §2：`: Base(实参)` 里的实参必须写）。
            if (info.constructors.isEmpty()) {
                val headerArgs = info.decl.superArgs
                val what = "父类 '${parent.name}' 的构造实参"
                checkParentArgPurity(info, headerArgs, what)
                val (target, jvmCtor) = checkedParentCall(info, headerArgs, info.decl.at, what)
                implicitSuperDelegations[info.decl] =
                    SuperDelegation(parent, target, headerArgs, null, jvmCtor)
                return
            }
            throw err(
                "类 '${info.decl.name}' 没有主构造（类名后面那对括号），父类的构造实参没地方写：" +
                    "要么给类写主构造（class ${info.decl.name}(参数) : ${parent.name}(实参)），" +
                    "要么在每个 constructor(...) 的第一句写 super(实参)",
                info.decl.superArgs.first().at,
            )
        }
    }

    /**
     * 没有主构造（或一个构造器都没写）时，父类调用是隐式的 `super()`。
     *
     * 放在 [checkConstructorChain] 里跑：那时每个次构造体的第一句已经查过，
     * 「有没有写 this(...) / super(...)」才答得准。
     */
    private fun checkImplicitParentCalls(info: ClassInfo) {
        val parent = parentRecord(info) ?: return
        if (info.primary != null) return
        for (ctor in info.constructors) {
            if (ctor.isPrimary) continue
            if (ctorDelegations[ctor] != null || superDelegations[ctor] != null) continue
            val (target, jvmCtor) = checkedParentCall(
                info, emptyList(), ctor.at, "父类 '${parent.name}' 的隐式 super()",
            )
            superDelegations[ctor] = SuperDelegation(parent, target, emptyList(), null, jvmCtor)
        }
        if (info.constructors.isEmpty() && implicitSuperDelegations[info.decl] == null) {
            val (target, jvmCtor) = checkedParentCall(
                info, emptyList(), info.decl.at, "父类 '${parent.name}' 的隐式 super()",
            )
            implicitSuperDelegations[info.decl] =
                SuperDelegation(parent, target, emptyList(), null, jvmCtor)
        }
    }

    /**
     * `super(实参)`（阶段 25 K1）——次构造里的**父类**构造委托。
     *
     * 规矩与 [checkThisCall] 一一对应（只能在构造器里、必须是体第一句），
     * 再加一条互斥：同一个构造器里 `this(...)` 与 `super(...)` 只能有一个
     * （DESIGN-25 §6：它们都必须是第一句）。
     */
    private fun checkSuperCall(stmt: SuperCallStmt) {
        val fn = currentFn
        if (!fn.isConstructor) {
            val where = if (methodOwner.containsKey(fn)) {
                "方法 '${fn.name}'"
            } else {
                "顶层函数 '${fn.name}'"
            }
            throw err(
                "'super(实参)' 是**父类构造委托**，只能写在构造器体里（当前在 $where 里）。" +
                    "想调用父类的方法写 super.方法名(实参)",
                stmt.at,
            )
        }
        if (fn.body?.statements?.firstOrNull() !== stmt) {
            throw err(
                "'super(...)' 必须是构造器体的**第一句**：父类那份状态要在碰 this/字段之前建好。" +
                    "写法是 constructor(参数) { super(父类实参) ...剩下的语句 }",
                stmt.at,
            )
        }
        val info = currentClass
            ?: throw err("内部错误：构造器不在任何类里", stmt.at)
        val parent = parentRecord(info)
            ?: throw err(
                "类 '${info.decl.name}' 没有父类，写不了 super(...)。" +
                    "super(...) 是委托给父类构造器的（同一个类的另一个构造器要用 this(...)）",
                stmt.at,
            )
        if (ctorDelegations[fn] != null) {
            throw err(
                "同一个构造器里 this(...) 和 super(...) 只能写一个：它们都是「先把对象建起来」的" +
                    "第一句，写两句就说不清先跑谁",
                stmt.at,
            )
        }
        val (target, jvmCtor) = checkedParentCall(info, stmt.arguments, stmt.at, "super(...) 的")
        checkParentArgPurity(info, stmt.arguments, "super(...) 的实参")
        superDelegations[fn] = SuperDelegation(parent, target, stmt.arguments, stmt, jvmCtor)
    }

    /**
     * `super.方法(...)` / `super(接口名).方法(...)`（阶段 25 K1）。
     *
     * 两支的分工（DESIGN-25 §2）：
     * - 没有目标（`super.f()`）→ 沿**父类链**找（最近的祖先优先），目标记成
     *   **声明它的那个类**——JVM 的 `INVOKESPECIAL` 用的就是这个 owner；
     * - 有目标（`super(Greeter).f()`）→ 在指定接口的**默认方法**里找，
     *   用于两个父接口有同签名默认方法时的消歧（Q1：不用 `super<A>`，因为 `<` 已被泛型实参表占用）。
     */
    private fun checkSuperMember(expr: MemberExpr, sup: SuperExpr): RsfType {
        val info = currentClass
        val inStatic = currentFn.isStatic
        if (inStatic) {
            throw err(
                "'super' 不能用在静态方法里：静态方法没有当前对象，也就没有父类的那一份状态。" +
                    "父类的静态成员要写 类名.成员（stc 不继承，DESIGN-25 §3）",
                expr.at,
            )
        }
        if (info == null) {
            throw err("'super' 只能用在类的方法里（这里不在任何类中）", expr.at)
        }
        val args = expr.arguments
            ?: throw err(
                "'super.${expr.name}' 只能是方法调用：rsf 不支持 super.字段" +
                    "（字段本来也不许遮蔽，DESIGN-25 §5/§6）。写 super.${expr.name}(实参)",
                expr.at,
            )

        val target = sup.target
        if (target != null) {
            return checkSuperInterfaceDefault(info, target, expr, args, sup)
        }

        val argTypes = args.map { type(it) }
        // 阶段 25 K1（G4）+ K4（缺口 C）：查找顺序是 **rsf 父类链在前、JDK 父类链在后**。
        // 顺序不是随口定的——rsf 父链上有一份实现时，`super.f()` 就该打到那一份
        // （`class C : Base` 里 Base 自己写了 f，不能越过它去打 JDK 祖父的那一份）；
        // 只有 rsf 链上根本没这个名字，才落到 JDK 父类链
        // （`class MyErr : Exception` 里的 `super.getMessage()` 声明在 `java/lang/Throwable`）。
        // 两端都按「非虚调用」打：目标是**声明类**那一份实现（DESIGN-25 §7）。
        val found = declaredMethodOnChain(info, expr.name, includeSelf = false) { !it.isConstructor }
        if (found == null) {
            inheritedJvmMethod(info, expr.name, argTypes, expr.at)?.let { jvm ->
                superCalls[expr] = SuperCall.Jvm(
                    ResolvedMethod(jvm, ClassPath.find(jvm.owner)?.isInterface == true),
                )
                return if (jvm.returnType.isVoid) RsfType.VOID else jvm.returnType
            }
            throw err(
                "类 '${info.decl.name}' 的父类链上没有方法 '${expr.name}'：" +
                    "super.${expr.name}(...) 走的是父类那一份实现" +
                    (if (info.methods[expr.name] != null) {
                        "（'${expr.name}' 只在 '${info.decl.name}' 自己这里，直接写 ${expr.name}(...) 就行）"
                    } else if (jvmParentOf(info) != null) {
                        "（JDK 父类 '${jvmParentOf(info)!!.dottedName}' 里也没有这个名字；" +
                            "stc 方法不继承，只能写 类名.方法(...)，DESIGN-25 §3）"
                    } else {
                        ""
                    }),
                expr.at,
            )
        }
        val (owner, method) = found
        // 阶段 25 K1（G4）：父类方法的签名要按**它的类型参数**代换成子类里的具体类型
        // （`class IntHolder : Holder&<int>` 的 `super.get()` 给回 int）。
        val subst = substitutionFromInside(info, owner)
        val candidates = listOf(
            OverloadCandidate(method, memberParamTypes(method).map { it.substitute(memberSubstFor(method, subst)) }),
        )
        val sameArity = candidates.filter { it.params.size == args.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用父类 '${owner.decl.name}' 的 '${expr.name}' 参数个数不对：实际给了 ${args.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = args,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }

        val picked = chosen.decl
        if (picked.control == ControlType.PRIVATE) {
            throw err(
                "父类 '${owner.decl.name}' 的 '${expr.name}' 是 prv 的，子类调不到它：" +
                    "prv 成员不参与继承（DESIGN-25 §3）",
                expr.at,
            )
        }
        superCalls[expr] = SuperCall.Rsf(owner.decl.name, picked)
        if (picked.ret.mayError) {
            requireErrorHandled(picked.name, "!${picked.ret.type.display()}", expr, expr.at)
        }
        // 泛型方法：返回类型里的 T 按这一处的绑定代换（31a 只记录，31b 才用）
        return instantiateReturn(expr, chosen, memberReturnType(picked).substitute(memberSubstFor(picked, subst)))
    }

    /**
     * `super(接口名).方法(...)`：在指定接口（含它继承来的）里找**默认方法**。
     *
     * 只说「默认方法」：抽象方法没有实现可走，`super(I).f()` 若 f 是抽象的，
     * 用户其实想表达的是「我要 I 那一份」——那份不存在，报错要他显式覆盖。
     */
    private fun checkSuperInterfaceDefault(
        info: ClassInfo,
        target: TypeRef,
        expr: MemberExpr,
        args: List<Expr>,
        sup: SuperExpr,
    ): RsfType {
        val iface = interfaceTable[target.name]
            ?: throw err(
                "'${target.display()}' 不是这份程序里的接口——super(接口名) 里只能写**接口**" +
                    "（要用父类那一份实现就写 super.${expr.name}(实参)，不要括号）",
                target.at,
            )
        val declaredHere = classSuperRefs[info.decl].orEmpty().any {
            it is SuperRef.Rsf && it.info === iface
        }
        if (!declaredHere) {
            throw err(
                "类 '${info.decl.name}' 没有实现接口 '${iface.decl.name}'，" +
                    "super(${iface.decl.name}).${expr.name}(...) 说不清是哪一份实现",
                target.at,
            )
        }
        val args2 = target.typeArgs.map { resolveType(it) }
        checkArity(iface.decl.name, iface.decl.typeParams.size, args2, target.at, iface.decl.typeParams)
        val subst = substitutionFor(iface.decl.typeParams, args2)
        val required = LinkedHashMap<String, MutableList<RequiredMethod>>()
        collectRsfRequired(iface, args2, required, HashSet())
        val defaults = required.values.flatten().filter {
            it.decl != null && it.concrete && it.name == expr.name
        }
        if (defaults.isEmpty()) {
            throw err(
                "接口 '${iface.decl.name}' 里没有带实现（默认方法）的 '${expr.name}'：" +
                    "super(${iface.decl.name}).${expr.name}(...) 只能用来指明「要哪个接口那一份默认实现」。" +
                    "抽象方法必须由类自己实现，就在类里写一个 'override fn ${expr.name}(...)'",
                expr.at,
            )
        }
        val pickable = defaults.map { rm ->
            rm to OverloadCandidate(rm.decl!!, rm.params.map { p -> p.substitute(memberSubstFor(rm.decl!!, subst)) })
        }
        val sameArity = pickable.filter { it.second.params.size == args.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用接口 '${iface.decl.name}' 的 '${expr.name}' 参数个数不对：" +
                    "实际给了 ${args.size} 个，" + describeCandidates(pickable.map { it.second }),
                expr.at,
            )
        }
        val argTypes = args.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity.map { it.second }, args = args,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }
        val wanted = pickable.first { it.second === chosen.original() }.first
        val method = wanted.decl!!
        superCalls[expr] = SuperCall.Rsf(
            className = interfaceOwnerTable[method]?.name ?: wanted.ownerName,
            decl = method,
            interfaceOwner = true,
        )
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        return instantiateReturn(expr, chosen, wanted.ret.substitute(memberSubstFor(method, subst)))
    }


    // ------------------------------------------------------------------ 注解（阶段 21）

    /**
     * 一个注解声明的解析产物（阶段 21）。
     *
     * 与 [ClassInfo] / [InterfaceInfo] 平级，但注解**不是**两者中的任何一个：
     * 它既有「接口」那一面（元素是抽象访问器，两个后端要生成注解接口），
     * 又有「类」那一面（companion entity 是一个实实在在的类）。
     */
    private class AnnotationInfo(
        val decl: AnnotationDecl,
        val source: SourceFile,
        /** 伴生类的 JVM 内部名（`Marker$Impl`）；没有 companion entity 就是 null。 */
        val companionJvmName: String?,
        /**
         * 合成出来的**注解接口**信息（元素访问器都在里面）。
         *
         * 它**不进 [interfaceTable]**：`@interface` 不是 rsf 接口（没有 `new`、
         * 不能当父类型、方法都是抽象的元素访问器），塞进去会让「这个名字是不是接口」
         * 在后端各处都答错。它只用来复用接口那一套成员检查（[checkInterfaceMember]、
         * 实现要求收集）。
         */
        var iface: InterfaceInfo? = null,
        var spec: AnnotationSpec? = null,
    ) {
        /** 注解接口的类型：和用户类/接口同一个形状（`RsfType.external(注解名)`）。 */
        val type: RsfType get() = RsfType.external(decl.name)
    }

    /**
     * 注解声明，按名字索引。
     *
     * 注解、类、枚举、接口**共用类型命名空间**：重名要报错。注解名最先占住
     * （见 [check]），所以另外三张表的查重都能看见它。
     */
    private val annotationTable = LinkedHashMap<String, AnnotationInfo>()

    /** 伴生类 → 合成它的那个 [CompanionEntityDecl]（与方法体检查的上下文还原有关）。 */
    private val companionDecls = IdentityHashMap<ClassDecl, CompanionEntityDecl>()

    /** 伴生类的 JVM 内部名 → 注解名。 */
    private val companionAnnotations = LinkedHashMap<String, String>()

    /** 合成出来的元素字段 → 它的元素（[checkClassBodies] 跳过它们的初值检查）。 */
    private val elementFields = IdentityHashMap<FieldDecl, AnnotationElementSpec>()

    /** 使用点与目标的配对（[checkAnnotationUses] 填，进 [SemanticModel.annotationUses]）。 */
    private val annotationUses = ArrayList<AnnotationUseSite>()

    /** 方案 B 要生成的访问器规格（进 [SemanticModel.annotationAccessors]）。 */
    private val annotationAccessors = ArrayList<AnnotationAccessor>()

    /** `new Marker(...)` 这种构造伴生实例的点 → 注解。 */
    private val annotationNewTargets = IdentityHashMap<Expr, AnnotationSpec>()

    // ------------------------------------------------------------------ 枚举（阶段 19c）

    /**
     * 枚举声明，按名字索引。
     *
     * 枚举和类**共用类型命名空间**（重名要报错），但单独一张表：后端要能问
     * 「这个名字是不是枚举」——字节码要专门为它生成 `GETSTATIC` 取值、
     * 解释器要专门为它生成单例对象，两条路都不是普通类那条。类型系统这一侧
     * 两者完全一样（都是 `RsfType.external(名字)`），所以 `resolveType` 只需多查一张表。
     */
    private val enumTable = LinkedHashMap<String, EnumDecl>()

    /** `枚举名.值名` → 解析结果，供两个后端取常量。 */
    private val enumValueRefs = IdentityHashMap<MemberExpr, EnumValueRef>()

    /**
     * 收集枚举声明：查重名、查空枚举（parser 已经拦过，这里兜底）、
     * 查值名重复，并把值名登记进一张 `枚举名 → 值名集合` 的表里（成员访问与穷尽性检查要用）。
     */
    private fun declareEnums() {
        for (decl in program.enums) {
            currentSource = sourceOfEnum[decl] ?: source
            if (classTable.containsKey(decl.name)) {
                throw err(
                    "枚举 '${decl.name}' 和同名的类冲突：枚举名和类名在同一个命名空间里",
                    decl.at,
                )
            }
            if (annotationTable.containsKey(decl.name)) {
                throw err("枚举 '${decl.name}' 和同名的注解冲突：$annotationNamespaceNote", decl.at)
            }
            if (enumTable.putIfAbsent(decl.name, decl) != null) {
                throw err("枚举 '${decl.name}' 重复定义", decl.at)
            }
            val seen = HashSet<String>()
            for (v in decl.values) {
                if (!seen.add(v.name)) {
                    throw err(
                        "枚举 '${decl.name}' 里的值 '${v.name}' 重复了——" +
                            "每个值只能出现一次（值名重复的话 case 就分不清该匹配谁）",
                        v.at,
                    )
                }
            }
        }
    }

    /** `枚举名 → 值名列表`（声明顺序）。 */
    private fun enumValuesOf(enumName: String): List<EnumValue> =
        enumTable[enumName]?.values ?: emptyList()

    /** 这个类型是不是某个枚举。 */
    private fun isEnumType(t: RsfType): Boolean =
        t.isExternal && t.externalName != null && enumTable.containsKey(t.externalName)

    /** 报错里列出某个枚举有哪些值——用户最常见的错就是值名写错或漏分支。 */
    private fun enumValueList(enumName: String): String {
        val values = enumValuesOf(enumName)
        return if (values.isEmpty()) "" else "（${enumName} 的值有：${values.joinToString(", ") { it.name }}）"
    }

    // ------------------------------------------------------------------ 接口声明（阶段 20）

    /** 接口、类、枚举共用类型命名空间时，报错里统一这么说。 */
    private val namespaceNote = "接口名、类名、枚举名在同一个命名空间里，不能重名"

    /** 阶段 21：注解也占类型命名空间，多这一句把话说全。 */
    private val annotationNamespaceNote = "接口名、类名、枚举名、注解名在同一个命名空间里，不能重名"

    /**
     * 第一遍（注解，阶段 21）：只把注解名占上。
     *
     * **必须最先跑**（[check] 里排在 [declareEnums] 之前）：另外三张表（枚举/接口/类）
     * 的占名趟都要查「这个名字是不是注解」，注解名不先占住就只能报一半冲突
     * （谁先占谁赢，报错取决于声明顺序，不稳）。元素与伴生实体这一趟都不碰——
     * 元素类型里可能出现注解/枚举（那要等 [declareEnums] 与 [declareClasses] 填完表），
     * 见 [declareAnnotationBodies]。
     */
    private fun declareAnnotationNames() {
        for (decl in program.annotations) {
            currentSource = sourceOfAnnotation[decl] ?: source
            if (classTable.containsKey(decl.name)) {
                throw err("注解 '${decl.name}' 和同名的类冲突：$annotationNamespaceNote", decl.at)
            }
            if (enumTable.containsKey(decl.name)) {
                throw err("注解 '${decl.name}' 和同名的枚举冲突：$annotationNamespaceNote", decl.at)
            }
            if (interfaceTable.containsKey(decl.name)) {
                throw err("注解 '${decl.name}' 和同名的接口冲突：$annotationNamespaceNote", decl.at)
            }
            val companionName = decl.companion?.let { decl.name + it.internalSuffix }
            val info = AnnotationInfo(decl, currentSource, companionName)
            if (annotationTable.putIfAbsent(decl.name, info) != null) {
                throw err("注解 '${decl.name}' 重复定义", decl.at)
            }
            if (companionName != null) {
                companionAnnotations[companionName] = decl.name
            }
        }
    }

    /**
     * 第二遍（注解，阶段 21）：元素表 + companion entity 的**合成类**。
     *
     * 必须排在 [declareClasses] 之后：元素的类型可以是类/枚举/注解（`元素 : Dog`、
     * `元素 : TokenKind`、`元素 : Marker2`），三张表都填完才判得出「这个类型合不合法」。
     *
     * 一趟做三件事，顺序不能换：
     * 1. **元素校验**（DESIGN-21 §6）：类型白名单、不能可空、默认值类型对得上；
     * 2. **注解接口**（合成的 [InterfaceInfo]，**不进 [interfaceTable]**）：每个元素一个
     *    无参抽象方法，这样「伴生类实现了注解接口」可以直接走接口那一套实现检查；
     * 3. **companion entity → 合成的 [ClassDecl]**（走用户类那条一模一样的检查路径），
     *    见 [declareCompanionEntity]。
     */
    private fun declareAnnotationBodies() {
        for (decl in program.annotations) {
            val info = annotationTable.getValue(decl.name)
            currentSource = info.source
            val elements = annotationElementsOf(info)
            info.iface = annotationInterfaceOf(info, elements)
            info.spec = AnnotationSpec(decl, elements, declareCompanionEntity(info, elements))
        }
    }

    /**
     * 注解元素表：解析类型 + 白名单校验（DESIGN-21 §6）。
     *
     * 元素的**默认值**在这里也顺便查类型（`int` 的元素写 `= "x"` 要报错）——
     * Parser 只保证「默认值是个常量字面量」，字面量的类型和声明的类型对不对得上只有
     * 这里知道。默认值本身留在 `AnnotationElement.default` 上，两个后端要的
     * `AnnotationDefault` 就是那个常量。
     */
    private fun annotationElementsOf(info: AnnotationInfo): List<AnnotationElementSpec> {
        val decl = info.decl
        val out = ArrayList<AnnotationElementSpec>(decl.elements.size)
        for (el in decl.elements) {
            val type = resolveType(el.type)
            // 可空：数组元素也可空（`int[]?` / `?int[]`）——两者在 JVM 上都是 null 装不进注解
            if (type.nullable || (type.isArray && type.elementType.nullable)) {
                throw err(
                    "注解元素 '${el.name}' 不能是可空类型：注解元素在 JVM 里不能是 null",
                    el.type.at,
                )
            }
            if (!isAnnotationElementType(type)) {
                throw err(
                    "注解元素 '${el.name}' 的类型 '${el.type.display()}' 不合法：" +
                        "注解元素只能是 string、基本类型、枚举、注解，或它们的数组",
                    el.type.at,
                )
            }
            el.default?.let { checkAnnotationDefaultValue(el, type, it) }
            out.add(AnnotationElementSpec(el, type, el.default != null))
        }
        return out
    }

    /**
     * 这个类型能不能当注解元素（DESIGN-21 §6 的白名单）。
     *
     * 判据：`string` / 基本类型 / 枚举 / 注解 / **这些的一维数组**。void、null 类型、
     * 类型参数、普通类/接口、二维数组都在白名单外（二维数组要写成 `int[][]`，
     * 注解元素只到一维）。
     */
    private fun isAnnotationElementType(type: RsfType): Boolean {
        if (type.arrayDepth > 1) return false
        val scalar = if (type.isArray) type.elementType else type
        if (scalar.isArray || scalar.isVoid || scalar.isNullLiteral) return false
        if (scalar.isExternal) {
            val name = scalar.externalName ?: return false
            // 枚举与注解是**引用类型**里的例外：它们能进注解（普通类/接口不能）
            return enumTable.containsKey(name) || annotationTable.containsKey(name)
        }
        return when (scalar.base) {
            RsfBase.BOOL, RsfBase.BYTE, RsfBase.CHAR, RsfBase.INT,
            RsfBase.LONG, RsfBase.FLOAT, RsfBase.DOUBLE, RsfBase.STRING -> true
            else -> false
        }
    }

    /** 元素的默认值：只要类型对得上就行（Parser 已经保证它是常量字面量）。 */
    private fun checkAnnotationDefaultValue(el: AnnotationElement, want: RsfType, value: Expr) {
        val got = type(value)
        if (got == RsfType.VOID) {
            throw err("注解元素 '${el.name}' 的默认值不能是 void 表达式", value.at)
        }
        if (!acceptsValue(want, value, got)) {
            throw err(
                "注解元素 '${el.name}' 的默认值 ${exprText(value)} 的类型是 ${got.rsfName}，" +
                    "和声明的 ${want.rsfName} 对不上",
                value.at,
            )
        }
    }

    /**
     * 注解接口（合成）：元素 → 无参抽象方法。
     *
     * 这个 [InterfaceInfo] **不进 [interfaceTable]**（`@interface` 不是 rsf 接口：不能 new、
     * 不能当父类型），它只被两处用到：伴生类的额外父接口（[declareCompanionEntity]），
     * 以及「赋给 `Marker` 的变量只剩元素方法可见」（[checkAnnotationMember]）。
     * 这样接口那一套实现要求收集（[collectRsfRequired]）、子类型判定（[isSubtypeName]）
     * 全都不用改一行。
     */
    private fun annotationInterfaceOf(
        info: AnnotationInfo,
        elements: List<AnnotationElementSpec>,
    ): InterfaceInfo {
        val decl = info.decl
        val accessors = elements.map { elementAccessorOf(it, abstract = true) }
        val synthetic = InterfaceDecl(
            control = ControlType.PUBLIC,
            name = decl.name,
            methods = accessors,
            supertypes = emptyList(),
            at = decl.at,
        )
        val methods = LinkedHashMap<String, MutableList<FuncDecl>>()
        for ((index, m) in accessors.withIndex()) {
            methods.getOrPut(m.name) { ArrayList() }.add(m)
            methodParams[m] = emptyList()
            methodReturns[m] = elements[index].type
        }
        return InterfaceInfo(synthetic, info.source, methods, emptyList())
    }

    /**
     * 一个元素的访问器：`fun value() -> string`。
     *
     * [abstract] = true 时没有体（注解接口里的元素方法，DESIGN-21 §5 的
     * `ACC_PUBLIC|ACC_ABSTRACT`）；false 时体是 `rtn 元素名`（伴生类上的实现，
     * 在类体里那个裸名解析成**元素字段**的读取）。
     */
    private fun elementAccessorOf(el: AnnotationElementSpec, abstract: Boolean): FuncDecl {
        val at = el.decl.at
        val body = if (abstract) {
            null
        } else {
            Block(listOf(ReturnStmt(VarExpr(el.name, at), at)), at)
        }
        return FuncDecl(
            control = ControlType.PUBLIC,
            mainType = TypeRef("void", at),
            name = el.name,
            params = emptyList(),
            ret = ReturnSpec(el.decl.type, ReturnStyle.ARROW, mayError = false, at = at),
            body = body,
            at = at,
        )
    }

    /**
     * companion entity → 合成的 [ClassDecl]（阶段 21 的「设计约束」那一条的落点）。
     *
     * 为什么是合成一个**类**、走 [declareClassBody]：伴生实体本来就是一个类
     * （有字段、有方法体、继承接口、要进 `classTable`），M3 的 `BytecodeCompiler`
     * 也要把它当普通类发射。另起一套平行的成员检查只会把「字段重名 / 签名 /
     * 擦除冲突 / 方法体 / 构造器链 / 实现检查」抄一遍，两处迟早不一致。
     * 复用的代价只有 [declareClassBody] 的两个参数：额外的父接口（注解接口）与
     * 「继承类时报错指向类继承那一阶段」。
     *
     * 合成类的组成（顺序就是成员顺序）：构造器（手写的或自动生成的）、元素访问器、
     * 元素字段（**摆在自己写的字段前面**，位置要和构造器参数对齐）、用户自己写的字段与方法。
     */
    private fun declareCompanionEntity(
        info: AnnotationInfo,
        elements: List<AnnotationElementSpec>,
    ): CompanionSpec? {
        val entity = info.decl.companion ?: return null
        val annotationName = info.decl.name
        val jvmName = info.companionJvmName
            ?: throw IllegalStateException("注解 '$annotationName' 没有伴生类名")
        currentSource = info.source

        // 名字不能和元素重名（DESIGN-21 §6）：`companion entity value` 会和一个元素
        // 的访问器/字段重名，怎么生成都是坏的，在这里就拦下
        if (entity.name != null && elements.any { it.name == entity.name }) {
            throw err(
                "companion entity 的名字 '${entity.name}' 和注解元素重名了：换一个名字",
                entity.at,
            )
        }

        val constructor = entity.constructors.firstOrNull()?.let { checkCompanionConstructor(it, elements) }
            ?: companionPrimaryConstructor(elements, entity.at, jvmName)

        val elementDecls = elements.map { companionElementField(it) }
        for (f in elementDecls) elementFields[f] = elements.first { it.name == f.name }
        val fields = elementDecls + entity.fields
        val accessors = elements.map { elementAccessorOf(it, abstract = false) }
        val methods = listOf(constructor) + accessors + entity.plainMethods

        val decl = ClassDecl(
            control = ControlType.PUBLIC,
            name = jvmName,
            fields = fields,
            methods = methods,
            at = entity.at,
            supertypes = entity.supertypes,
        )
        companionDecls[decl] = entity
        // 伴生类 = 一个普通的类：父接口 = 注解接口（摆在最前面）+ 自己写的那些接口
        declareClassBody(
            decl,
            info.source,
            extraSupers = listOf(SuperRef.Rsf(info.iface!!)),
            companionEntity = true,
        )
        return CompanionSpec(
            annotation = annotationName,
            jvmName = jvmName,
            decl = decl,
            constructor = constructor,
            accessors = accessors,
            elementFields = elementDecls,
        )
    }

    /**
     * 用户手写的 `constructor(...)`：参数必须与注解元素**同名同序**（DESIGN-21 §6）。
     *
     * 参数类型也要对得上——只比名字的话 `constructor(value : int)` 配 `value : string`
     * 会生成一个字段是 string、参数是 int 的类，M3 写 `<init>` 时才发现。
     */
    private fun checkCompanionConstructor(
        ctor: FuncDecl,
        elements: List<AnnotationElementSpec>,
    ): FuncDecl {
        for ((index, el) in elements.withIndex()) {
            val p = ctor.params.getOrNull(index)
                ?: throw err(
                    "companion entity 的构造器参数必须和注解元素一致：缺少 '${el.name}'",
                    ctor.at,
                )
            if (p.name != el.name) {
                throw err(
                    "companion entity 的构造器参数必须和注解元素一致：" +
                        "第 ${index + 1} 个参数应该是 '${el.name}'，写的是 '${p.name}'" +
                        "（名字和顺序都要和元素表一样：" +
                        elements.joinToString("、") { it.name } + "）",
                    p.at,
                )
            }
            val declared = resolveType(p.type)
            if (declared != el.type) {
                throw err(
                    "companion entity 的构造器参数 '${p.name}' 的类型应该是 ${el.type.rsfName}，" +
                        "写的是 ${declared.rsfName}",
                    p.type.at,
                )
            }
        }
        if (ctor.params.size > elements.size) {
            val extra = ctor.params[elements.size]
            throw err(
                "companion entity 的构造器参数必须和注解元素一致：多出了 '${extra.name}'" +
                    "（元素表只有：" + elements.joinToString("、") { it.name } + "）",
                extra.at,
            )
        }
        // 伴生类只有这一个构造器，它就是主构造：元素字段的初值（它们按裸名写在
        // 方法体里）靠它拿值。次构造还要求 `this(...)` 委托，对伴生实体没有意义。
        return ctor.copy(isPrimary = true)
    }

    /**
     * 没写 `constructor` 时自动生成的主构造：参数表 = 元素表（DESIGN-21 §1）。
     *
     * 体是空的：**元素字段的赋值由 M3 的 `<init>` 生成**（见 [CompanionSpec] 的注释），
     * 语义层只用它把参数绑进作用域，让访问器和用户方法里的裸名 `value` 解析得到。
     */
    private fun companionPrimaryConstructor(
        elements: List<AnnotationElementSpec>,
        at: SourceLine,
        jvmName: String,
    ): FuncDecl = FuncDecl(
        control = ControlType.PUBLIC,
        mainType = TypeRef("void", at),
        name = jvmName,
        params = elements.map { Param(it.decl.type, it.name, it.decl.at) },
        ret = ReturnSpec(TypeRef("void", at), ReturnStyle.OMITTED, mayError = false, at = at),
        body = Block(emptyList(), at),
        at = at,
        isConstructor = true,
        isPrimary = true,
    )

    /**
     * 合成的元素字段（`var value : string`）。
     *
     * 初值只是**占位**：默认值字面量（有默认值时），否则与类型相配的零值——两者都必须
     * 是合法表达式，否则字节码后端按「普通字段初值」抄进 `<init>` 时会编出非法指令
     * （`int` 字段拿到 null）。真正的赋值在 M3 的 `<init>` 里按构造器参数生成
     * （见 [CompanionSpec]），M2 的初值类型检查对它用 [elementFields] 跳过。
     */
    private fun companionElementField(el: AnnotationElementSpec): FieldDecl = FieldDecl(
        name = el.name,
        type = el.decl.type,
        initializer = el.decl.default ?: zeroLiteralOf(el.type, el.decl.at),
        mutable = false,
        at = el.decl.at,
    )

    /** 与类型相配的零值字面量（合成元素字段的占位初值）。 */
    private fun zeroLiteralOf(type: RsfType, at: SourceLine): Expr = when (type.base) {
        RsfBase.BOOL -> BoolLit(false, at)
        RsfBase.CHAR -> CharLit('\u0000', at)
        RsfBase.BYTE, RsfBase.INT -> IntLit(0, at)
        RsfBase.LONG -> LongLit(0L, at)
        RsfBase.FLOAT -> FloatLit(0f, at)
        RsfBase.DOUBLE -> DoubleLit(0.0, at)
        RsfBase.STRING -> StringLit("", at)
        // 枚举 / 注解 / 数组：null 是唯一和任何引用类型都相配的占位
        else -> NullLit(at)
    }

    /** 报错文案里的「已定义的注解」清单。 */
    private fun annotationListHint(): String =
        if (annotationTable.isEmpty()) "这份程序里还没有任何 annotation 声明"
        else "已定义的注解：${annotationTable.keys.joinToString(", ")}"

    /**
     * 第三遍（注解，阶段 21）：**使用点**（`@Marker(...)`）。
     *
     * 放在最后跑：实参的类型要查「未声明的变量」之类的老报错，得等所有表都填好；
     * 访问器同名冲突要看得见目标类型自己的成员，也要等类体查完。
     *
     * v1 能标的位置（DESIGN-21 §1）：类、接口、注解声明、方法、字段。
     * 参数与局部变量上的注解 Parser 就不收；**构造器上的注解** Parser 也报掉了
     * （`构造器上不能写注解`）——companion entity 自己的注解由 Parser 丢弃
     * （这条记在阶段 21 的已知差异里）。顶层函数能写注解，但方案 B 的访问器要挂在**类型**上，
     * 所以带 companion entity 的注解标在顶层函数上会报错（见 [checkAnnotationList]）。
     */
    private fun checkAnnotationUses() {
        for (decl in program.annotations) {
            checkAnnotationList(decl.annotations, AnnotationTarget.OnAnnotation(decl))
            val entity = decl.companion ?: continue
            val owner = annotationTable.getValue(decl.name).companionJvmName
            for (f in entity.fields) {
                checkAnnotationList(f.annotations, AnnotationTarget.OnField(f, owner))
            }
            for (m in entity.plainMethods) {
                checkAnnotationList(m.annotations, AnnotationTarget.OnMethod(m, owner))
            }
        }
        for (decl in program.interfaces) {
            checkAnnotationList(decl.annotations, AnnotationTarget.OnInterface(decl))
            for (m in decl.methods) {
                checkAnnotationList(m.annotations, AnnotationTarget.OnMethod(m, decl.name))
            }
        }
        for (decl in program.classes) {
            checkAnnotationList(decl.annotations, AnnotationTarget.OnClass(decl))
            for (f in decl.fields) {
                checkAnnotationList(f.annotations, AnnotationTarget.OnField(f, decl.name))
            }
            // 类里的方法是普通方法（构造器上的注解 Parser 已经报过）
            for (m in decl.methods.filter { !it.isConstructor }) {
                checkAnnotationList(m.annotations, AnnotationTarget.OnMethod(m, decl.name))
            }
        }
        for (fn in program.functions) {
            checkAnnotationList(fn.annotations, AnnotationTarget.OnMethod(fn, null))
        }
    }

    /**
     * 同一个目标上的一串注解：名字存在吗、重复标了吗、实参配得上元素表吗。
     *
     * [target] 同时决定两件事：报错落款（`类 'Point'`），以及方案 B 的访问器挂到哪
     * （[AnnotationTarget.hostName]）。
     */
    private fun checkAnnotationList(uses: List<AnnotationUse>, target: AnnotationTarget) {
        val seen = HashSet<String>()
        for (use in uses) {
            val info = annotationTable[use.name]
                ?: throw err(
                    "没有这个注解：'@${use.name}'（${annotationListHint()}）",
                    use.at,
                )
            val spec = info.spec ?: throw IllegalStateException("注解 '${use.name}' 没有解析结果")
            if (!seen.add(use.name)) {
                throw err(
                    "${target.label} 上重复标了 '@${use.name}'：同一个地方同一个注解只能标一次",
                    use.at,
                )
            }
            checkAnnotationArguments(spec, use)
            // 方案 B：带 companion entity 的注解要生成访问器，而访问器只能挂在类型上
            if (spec.companion != null && target.hostName == null) {
                throw err(
                    "顶层函数 '${target.memberName}' 上不能标带 companion entity 的注解 '@${spec.name}'：" +
                        "方案 B 的访问器要挂在类型上（把注解标在类/接口/它们的方法或字段上）",
                    use.at,
                )
            }
            checkAnnotationAccessorClash(spec, use, target)
            annotationUses.add(AnnotationUseSite(spec, use, target))
            spec.companion?.let { addAnnotationAccessor(spec, it, use, target) }
        }
    }

    /**
     * 实参与元素表的配对（DESIGN-21 §6）：位置式按顺序、具名按名字、缺省值兜底。
     *
     * 具名实参写在位置式实参**后面**是 Parser 的规矩（它已经报过混写顺序）；
     * 这里把「位置式写在具名后面」再兜一次，因为声明里元素顺序是权威，
     * 真混起来时按 index 配对会给出莫名其妙的错。
     */
    private fun checkAnnotationArguments(spec: AnnotationSpec, use: AnnotationUse) {
        val values = arrayOfNulls<Expr>(spec.elements.size)
        var namedSeen = false
        for ((index, arg) in use.arguments.withIndex()) {
            when (arg) {
                is PositionalAnnotationArg -> {
                    if (namedSeen) {
                        throw err(
                            "注解 '${spec.name}' 的实参顺序不对：位置式实参（${arg.display()}）" +
                                "不能写在具名实参后面${spec.elementHint()}",
                            arg.at,
                        )
                    }
                    val element = spec.elements.getOrNull(index)
                        ?: throw err(
                            "注解 '${spec.name}' 的实参个数不对：给了 ${use.arguments.size} 个，" +
                                "但它的元素只有 ${spec.elements.size} 个${spec.elementHint()}",
                            arg.at,
                        )
                    checkAnnotationArgValue(spec, element, arg.value)
                    values[index] = arg.value
                }
                is NamedAnnotationArg -> {
                    namedSeen = true
                    val element = spec.elementOf(arg.name)
                        ?: throw err(
                            "注解 '${spec.name}' 没有元素 '${arg.name}'${spec.elementHint()}",
                            arg.at,
                        )
                    val slot = spec.elements.indexOf(element)
                    if (values[slot] != null) {
                        throw err(
                            "注解 '${spec.name}' 的元素 '${arg.name}' 给了两次值" +
                                "（同名具名写两次，或者位置式与具名各给了一次）",
                            arg.at,
                        )
                    }
                    checkAnnotationArgValue(spec, element, arg.value)
                    values[slot] = arg.value
                }
            }
        }
        for ((index, element) in spec.elements.withIndex()) {
            if (values[index] == null && !element.hasDefault) {
                throw err(
                    "注解 '${spec.name}' 的元素 '${element.name}' 没有默认值：" +
                        "写 @${spec.name} 时必须给出 ${element.name}",
                    use.at,
                )
            }
        }
    }

    /** 一个注解实参的类型：要和元素的类型配得上（枚举、数组也走这一条）。 */
    private fun checkAnnotationArgValue(spec: AnnotationSpec, element: AnnotationElementSpec, value: Expr) {
        val got = type(value)
        if (got == RsfType.VOID) {
            throw err(
                "注解 '${spec.name}' 的元素 '${element.name}' 的实参是 void 表达式，不能当值传",
                value.at,
            )
        }
        if (!acceptsValue(element.type, value, got)) {
            throw err(
                "注解 '${spec.name}' 的元素 '${element.name}' 需要 ${element.type.rsfName}，" +
                    "实际给的是 ${got.rsfName}（${exprText(value)}）",
                value.at,
            )
        }
    }

    /**
     * 方案 B 的访问器会不会和用户自己写的成员撞名（DESIGN-21 §3）。
     *
     * 只有带 companion entity 的注解才要生成访问器，也只有这时候才可能撞。
     * 判据取**同名**（不是同签名）：访问器是 `public static`，用户写个实例的
     * `Marker()` 虽然描述符不同，但源码里读起来仍然是一团乱麻——报出来让人改名字最省事。
     */
    private fun checkAnnotationAccessorClash(spec: AnnotationSpec, use: AnnotationUse, target: AnnotationTarget) {
        if (spec.companion == null) return
        val host = target.hostName ?: return
        val names = memberNamesOfType(host) ?: return
        if (names.contains(spec.name)) {
            throw err(
                "${target.label} 里已经有了成员 '${spec.name}'：方案 B 要在它上面生成注解访问器 " +
                    "'${spec.name}(...)'，名字撞上了——把已有的那个成员改个名",
                use.at,
            )
        }
    }

    /**
     * 一个类型自己的成员名（字段 + 方法），用于访问器同名冲突。
     *
     * 注解自己的成员是**元素**，元素名和访问器名（= 注解名）不可能一样
     * （Parser 已经保证元素名不与注解名相同——元素名是标识符，注解名也是，
     * 而 `annotation Marker(Marker : int)` 在 Parser 里就重名报错了），所以返回空集即可。
     */
    private fun memberNamesOfType(host: String): Set<String>? {
        classTable[host]?.let { info ->
            return info.decl.fields.map { it.name }.toSet() + info.decl.methods.map { it.name }.toSet()
        }
        interfaceTable[host]?.let { info -> return info.decl.methods.map { it.name }.toSet() }
        if (annotationTable.containsKey(host)) return emptySet()
        return null
    }

    /**
     * 记下方案 B 要生成的访问器规格（M2 只出规格，M3 生成字节码）。
     *
     * 类型级（标在类/接口/注解本身上）：`public static <伴生类型> Marker()`；
     * 成员级（标在方法/字段上）：`public static <伴生类型> Marker(string member)`。
     * 挂在哪：就是被标的那个类型（[AnnotationTarget.hostName]）。
     */
    private fun addAnnotationAccessor(
        spec: AnnotationSpec,
        companion: CompanionSpec,
        use: AnnotationUse,
        target: AnnotationTarget,
    ) {
        val host = target.hostName ?: return
        annotationAccessors.add(
            AnnotationAccessor(
                annotation = spec,
                use = use,
                target = target,
                hostName = host,
                memberName = target.memberName,
                companionName = companion.jvmName,
            ),
        )
    }

    /**
     * 静态类型是**注解接口**时的成员访问（阶段 21，DESIGN-21 §1 的
     * 「赋给 `Marker` 后只剩元素方法可见」）。
     *
     * 只放行元素：`x.value`（字段式）与 `x.value()`（方法式）都行，前者落到伴生类的
     * 元素字段上（GETFIELD），后者落到伴生类那个访问器上（接口调用，`interfaceOwner = true`）。
     * **行为方法**（companion entity 里自己写的 `describe`/`run`）看不见——它们不在注解
     * 接口里，这就是「行为方法不能进注解接口」在类型层面的落地。
     */
    private fun checkAnnotationMember(
        info: AnnotationInfo,
        expr: MemberExpr,
    ): RsfType {
        val spec = info.spec ?: throw IllegalStateException("注解 '${info.decl.name}' 没有解析结果")
        val annotation = info.decl.name
        val element = spec.elementOf(expr.name)
        if (element == null) {
            throw err(
                "注解 '$annotation' 上没有元素 '${expr.name}'${annotationElementHint(spec, annotation, expr.name)}",
                expr.at,
            )
        }
        val companion = spec.companion
            ?: throw err(
                "注解 '$annotation' 的元素 '${element.name}' 只有伴生实例才有：" +
                    "给注解加一个 companion entity 块才能 `new $annotation(...)`",
                expr.at,
            )
        val accessor = companion.accessors.first { it.name == element.name }
        if (expr.arguments != null) {
            if (expr.arguments.isNotEmpty()) {
                throw err(
                    "注解 '$annotation' 的元素 '${element.name}' 是个无参访问器：" +
                        "要取值写 `${expr.name}`（或 `${expr.name}()`），不要传实参",
                    expr.at,
                )
            }
            userMembers[expr] = UserMember.Method(annotation, accessor, interfaceOwner = true)
        } else {
            val index = companion.elementFields.indexOfFirst { it.name == element.name }
            userMembers[expr] = UserMember.Field(companion.jvmName, index, element.type)
        }
        return element.type
    }

    /** 「这个注解有哪些元素/为什么看不见这个成员」的报错补语。 */
    private fun annotationElementHint(spec: AnnotationSpec, annotation: String, member: String): String {
        val companion = spec.companion
        if (companion == null) {
            return "（'$annotation' 没有 companion entity，所以只有元素看得见：${spec.elementHint()}）"
        }
        if (companion.accessors.any { it.name == member }) {
            return "（'$member' 是 companion entity 的行为方法，只有伴生类的对象看得见：" +
                "先 `new $annotation(...)` 再用）"
        }
        if (companion.decl.fields.any { it.name == member } || companion.decl.methods.any { it.name == member }) {
            return "（'$member' 是 companion entity 的成员，只有伴生类的对象看得见：" +
                "先 `new $annotation(...)` 再用）"
        }
        return spec.elementHint()
    }

    /**
     * 第一遍（接口）：只把接口名占上。
     *
     * 和 [declareClasses] 的第一遍同一个理由：接口与类、接口与接口之间可以互相引用
     * （`class Dog : Greeter` 里的 Greeter 可能写在文件末尾），所以名字必须一次性齐了
     * 再去解析任何签名——否则 resolveType 会把「还没读到的接口」当成未知类型。
     */
    private fun declareInterfaceNames() {
        for (decl in program.interfaces) {
            currentSource = sourceOfInterface[decl] ?: source
            if (classTable.containsKey(decl.name)) {
                throw err("接口 '${decl.name}' 和同名的类冲突：$namespaceNote", decl.at)
            }
            if (enumTable.containsKey(decl.name)) {
                throw err("接口 '${decl.name}' 和同名的枚举冲突：$namespaceNote", decl.at)
            }
            if (annotationTable.containsKey(decl.name)) {
                throw err("接口 '${decl.name}' 和同名的注解冲突：$annotationNamespaceNote", decl.at)
            }
            val placeholder = InterfaceInfo(decl, currentSource, LinkedHashMap(), emptyList())
            if (interfaceTable.putIfAbsent(decl.name, placeholder) != null) {
                throw err("接口 '${decl.name}' 重复定义", decl.at)
            }
        }
    }

    /**
     * 第二遍（接口）：方法签名 + 父接口（阶段 20）。
     *
     * 必须等 [declareClasses] 的第一遍跑完：只有那时才能分清 `: X` 里的 X 是接口
     * （正常）还是类（D2：报「还不支持类继承」），而不是笼统地报「未知类型」。
     *
     * 接口成员规则在这一趟全部落地（D3）：**只能有方法**。字段/变量声明在 Parser
     * 就被拦下了，这里兜住剩下三条只能在有名字环境时才判得出来的规则
     * （修饰词、static、与接口同名）。抽象方法（没写体）与默认方法（写了体）
     * 一起进 [InterfaceInfo.methods]，靠 [FuncDecl.isAbstract] 区分。
     */
    private fun declareInterfaceBodies() {
        for (decl in program.interfaces) {
            currentSource = sourceOfInterface[decl] ?: source
            // 这个接口的类型参数只在它自己的签名里有意义
            typeParams = decl.typeParams.associateWith { RsfType.typeParam(it) }

            val methods = interfaceTable.getValue(decl.name).methods
            for (m in decl.methods) {
                if (m.isConstructor) {
                    throw err(
                        "接口 '${decl.name}' 里不能写构造器——接口没有对象可以构造（`new 接口名()` 根本写不出来）",
                        m.at,
                    )
                }
                if (m.control == ControlType.PRIVATE || m.control == ControlType.PROTECTED) {
                    throw err(
                        "接口 '${decl.name}' 里的方法只能是 pub（这里写的是 '${m.control.keyword}'）：" +
                            "实现类/子接口必须能覆盖它，prv 与 prot 的接口方法没法被覆盖",
                        m.at,
                    )
                }
                if (m.isStatic) {
                    throw err(
                        "接口 '${decl.name}' 里的方法不能是 stc 的——接口方法都是实例方法，" +
                            "要靠实现类的对象调。写成 fn ${m.name}(...) 就行",
                        m.at,
                    )
                }
                if (m.name == decl.name) {
                    throw err(
                        "接口 '${decl.name}' 里的方法不能和接口同名（写法上像构造器，但接口没有构造器）",
                        m.at,
                    )
                }
                checkSignature(m)
                methods.getOrPut(m.name) { ArrayList() }.add(m)
            }

            // 参数绑定与返回类型：和类的方法同型，按 FuncDecl 索引（重载不会互相覆盖）
            for (m in decl.methods) {
                interfaceOwnerTable[m] = decl
                val what = "接口 '${decl.name}' 里的方法 '${m.name}'"
                val bindings = ArrayList<Binding>(m.params.size)
                val seen = HashSet<String>()
                for ((index, p) in m.params.withIndex()) {
                    val paramType = resolveType(p.type)
                    if (paramType == RsfType.VOID) {
                        throw err("$what 的参数 '${p.name}' 的类型不能是 void", p.at)
                    }
                    if (!seen.add(p.name)) {
                        throw err("$what 的参数 '${p.name}' 重名了", p.at)
                    }
                    bindings.add(Binding(p.name, paramType, mutable = true, paramIndex = index, at = p.at))
                }
                methodParams[m] = bindings
                methodReturns[m] =
                    if (m.ret.type.name == "void") RsfType.VOID else resolveType(m.ret.type)
            }

            // 和类同一套判据：同一个名字下的两个重载在 JVM 上必须真的是两个方法
            checkErasureConflicts(decl.name, methods, ownerWord = "接口")

            val supers = decl.supertypes.map {
                resolveSuperTypeRef(it, ownerKind = "接口", ownerName = decl.name)
            }
            // 填第一遍占下的那个对象，**不能换成新的**（见 [InterfaceInfo.supertypes] 的说明）
            interfaceTable.getValue(decl.name).supertypes = supers
        }
        typeParams = emptyMap()
    }

    /**
     * 解析 `:` 子句里的一项（阶段 20）。**只接受接口**。
     *
     * 四种结局，每一种都有明确报错，没有「静默忽略」这一支（D2）：
     * - 这份程序里声明的接口 → [SuperRef.Rsf]；
     * - classpath 上的接口（`Runnable`、`Comparator`）→ [SuperRef.Jvm]；
     * - 类（rsf 的或 JVM 的）→ 报「父**类**要写在最前面并带实参表」
     *   （阶段 25 起类继承是真支持的，只是父类走 [parseSuperClause] 单独那个槽位，
     *   落到这里的说明用户写错了位置）；
     * - 认不出来的名字 → 未知父类型。
     *
     * 类型实参照收（`class C : Box&<int>`）：对 rsf 接口，实参要用来把方法签名里的
     * `T` 换成 `int`（实现配对与调用点都靠它）；对 JVM 接口则一律忽略——
     * JVM 的擦除把 `Comparator&<string>` 变成 `Comparator`，两端都只看擦除后的描述符。
     */
    private fun resolveSuperTypeRef(
        ref: TypeRef,
        ownerKind: String,
        ownerName: String,
        /**
         * true = 调用方是 `companion entity`（阶段 21）。
         *
         * 只影响继承**类**时的文案：伴生实体的规矩是死的（DESIGN-21 §6），
         * 用户写的是 `companion entity Impl : Base` 而不是类头，报「类的父类型」
         * 会把人带偏；而且 v1 的类继承本来就没做，指向那个阶段最有用。
         */
        companionEntity: Boolean = false,
    ): SuperRef {
        val where = "$ownerKind '$ownerName'"
        val display = ref.display()
        if (ref.arrayDepth > 0) {
            throw err("$where 的父类型 '$display' 不能是数组——父类型只能是接口名", ref.at)
        }
        if (RsfType.fromName(ref.name) != null) {
            throw err("'$display' 是内置类型，不能当父类型——父类型只能是接口名", ref.at)
        }
        if (annotationTable.containsKey(ref.name)) {
            throw err(
                "'$display' 是注解，不能当父类型：注解只能用来标注（@$display）或 new 出伴生实例",
                ref.at,
            )
        }
        interfaceTable[ref.name]?.let { info ->
            val args = ref.typeArgs.map { resolveType(it) }
            checkArity(ref.name, info.decl.typeParams.size, args, ref.at, info.decl.typeParams)
            return SuperRef.Rsf(info, args)
        }
        if (classTable.containsKey(ref.name) || enumTable.containsKey(ref.name)) {
            if (companionEntity) {
                throw err(
                    "companion entity 暂时只能继承接口：rsf 还没有类继承" +
                        "（继承 '$display' 这类写法留到类继承那一阶段）",
                    ref.at,
                )
            }
            if (enumTable.containsKey(ref.name)) {
                throw err(
                    "$where 不能继承 '$display'：'$display' 是个枚举。" +
                        "枚举不能被继承，也不能继承别的类（DESIGN-25 §6）" +
                        "——父类型列表里只能写接口名",
                    ref.at,
                )
            }
            throw err(
                "$where 想把 '$display' 当父类型，但 '$display' 是个类。" +
                    "rsf 支持类继承，只是父**类**要写在父类型列表的**最前面**、" +
                    "而且必须带实参表：'$ownerName' : $display(实参), 接口名…（DESIGN-25 §2）",
                ref.at,
            )
        }
        val cls = resolveJvmClass(ref.name)
            ?: throw err(
                "$where 的父类型 '$display' 找不到：它既不是这份程序里声明的接口，" +
                    "也不是 classpath 上的类型（要继承 Java/Kotlin 的接口得先 import 它）",
                ref.at,
            )
        if (!cls.isInterface) {
            if (companionEntity) {
                throw err(
                    "companion entity 暂时只能继承接口：rsf 还没有类继承" +
                        "（继承 '$display' 这类写法留到类继承那一阶段）",
                    ref.at,
                )
            }
            throw err(
                "$where 想把 '$display' 当父类型，但它是 classpath 上的类（不是接口）。" +
                    "要继承它就得写在父类型列表**最前面**并带上实参表：" +
                    "'$ownerName' : $display(实参), 接口名…（DESIGN-25 §2）",
                ref.at,
            )
        }
        return SuperRef.Jvm(cls)
    }

    // ------------------------------------------------------------------ 接口：成员与子类型（阶段 20）

    /**
     * 类/接口的父接口，翻译成后端能直接抄的形状（阶段 20）。
     *
     * 后端（字节码）拼 `ClassWriter.visit(..., interfaces = ...)` 时要的是 JVM 内部名，
     * 而源码里那是简名、还可能带类型实参、也分不清「rsf 接口」与「JDK 接口」——
     * 这些判断都在 Checker 里做过了，这里只是把结论换个形状交出去，
     * 免得后端自己再解析一遍（R1：同一个判定不做两遍，就不会两处不一致）。
     */
    private fun collectSuperInterfaces(): Map<String, List<SuperInterface>> {
        val out = LinkedHashMap<String, List<SuperInterface>>()
        for ((name, info) in classTable) {
            val supers = classSuperRefs[info.decl].orEmpty()
            if (supers.isNotEmpty()) out[name] = supers.map { toSuperInterface(it) }
        }
        for ((name, info) in interfaceTable) {
            if (info.supertypes.isNotEmpty()) out[name] = info.supertypes.map { toSuperInterface(it) }
        }
        return out
    }

    private fun toSuperInterface(s: SuperRef): SuperInterface = when (s) {
        // rsf 的类/接口都在默认包，内部名就是名字本身
        is SuperRef.Rsf -> SuperInterface(
            name = s.info.decl.name,
            internalName = s.info.decl.name,
            args = s.args,
            fromClassPath = false,
        )

        is SuperRef.Jvm -> SuperInterface(
            name = s.cls.simpleName,
            internalName = s.cls.internalName,
            args = emptyList(),
            fromClassPath = true,
        )
    }

    /** `this` 在当前上下文里的类型：类的方法里是那个类，接口的默认方法里是那个接口。 */
    private fun thisTypeOrNull(): RsfType? =
        currentClass?.type ?: currentInterface?.let { RsfType.external(it.decl.name) }

    /** 静态类型是**用户接口**时给出它的表项；类、JVM 类型、数组都是 null。 */
    private fun interfaceOf(type: RsfType): InterfaceInfo? =
        if (type.isExternal) type.externalName?.let { interfaceTable[it] } else null

    /**
     * 一个接口连同它继承来的全部方法，按名字分组（阶段 20）。
     *
     * 用「已经走过的接口名」去重：菱形继承（`interface C : A, B`，而 A、B 都继承 Base）
     * 只会把 Base 的方法收一次——不然同一份签名在候选里出现两次，重载挑选会看到
     * 两个一模一样的候选而报「说不清用哪个」，那是假的歧义。
     */
    private fun interfaceMethodsByName(info: InterfaceInfo): LinkedHashMap<String, MutableList<FuncDecl>> {
        val out = LinkedHashMap<String, MutableList<FuncDecl>>()
        val seen = HashSet<String>()
        fun collect(from: InterfaceInfo) {
            if (!seen.add(from.decl.name)) return
            for ((name, list) in from.methods) out.getOrPut(name) { ArrayList() }.addAll(list)
            for (s in from.supertypes) if (s is SuperRef.Rsf) collect(s.info)
        }
        collect(info)
        return out
    }

    /** 报错里附一句「这个接口有哪些方法」，用户不用翻回去数。 */
    private fun interfaceMethodHint(info: InterfaceInfo): String {
        val names = interfaceMethodsByName(info).keys
        return if (names.isEmpty()) "" else "（有：${names.joinToString(", ")}）"
    }

    /**
     * 接口类型的成员访问（阶段 20）：`g.greet(name)`。
     *
     * 接口**只有方法**（D3），所以不带括号那条路直接报错——不报的话用户会以为
     * 自己写了个字段；方法这条路则和类的方法共用 [resolveOverload] 那一套挑选规则
     * （重载、实参打分、错误文案全都天然一致，不用两处维护）。
     *
     * [subst] 把接口的类型实参替进方法签名（`Box&<int> b` 的 `b.get()` 是 int）。
     * 注意方法可能**声明在父接口**里：那时签名里的类型参数名不一定和自己的一样，
     * 本阶段按「同名即同参」处理（泛型接口只要求能声明/实现/调用，见 DESIGN §3）。
     */
    private fun checkInterfaceMember(
        info: InterfaceInfo,
        expr: MemberExpr,
        subst: Map<String, RsfType>,
    ): RsfType {
        val who = "接口 '${info.decl.name}'"
        if (expr.arguments == null) {
            throw err(
                "$who 里没有字段 '${expr.name}'——接口只有方法，没有字段可读。" +
                    "值要么由实现类自己存（在类里写 var），要么写成一个方法返回它" +
                    interfaceMethodHint(info),
                expr.at,
            )
        }

        val named = interfaceMethodsByName(info)[expr.name]
            ?: throw err("$who 里没有方法 '${expr.name}'" + interfaceMethodHint(info), expr.at)

        val candidates = named.map {
            OverloadCandidate(it, memberParamTypes(it).map { t -> t.substitute(memberSubstFor(it, subst)) })
        }
        val sameArity = candidates.filter { it.params.size == expr.arguments.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用 '${expr.name}' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }
        val argTypes = expr.arguments.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = expr.arguments,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }

        val method = chosen.decl
        // 记下「这是接口方法，声明在哪个接口」——后端要靠它选 invokeinterface，
        // 而 invokeinterface 的 owner 必须是**声明它的那个接口**（JVM 的解析规则）。
        userMembers[expr] = UserMember.Method(
            className = interfaceOwnerTable[method]?.name ?: info.decl.name,
            decl = method,
            interfaceOwner = true,
        )
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        return instantiateReturn(expr, chosen, memberReturnType(method).substitute(memberSubstFor(method, subst)))
    }

    /**
     * 接口默认方法体里的**隐式**调用（`shout` 里写 `greet(name)`，阶段 20）。
     *
     * 和类里「隐式带 this」同型：默认方法的 `this` 就是实现类的那个对象，
     * 所以 `greet(name)` 等价于 `this.greet(name)`。这里挑出方法、记下归属接口，
     * 剩下的（invokeinterface）交给后端。
     */
    private fun checkImplicitInterfaceCall(info: InterfaceInfo, expr: CallExpr): RsfType {
        val named = interfaceMethodsByName(info)[expr.name].orEmpty()
        val candidates = named.map { OverloadCandidate(it, memberParamTypes(it)) }
        val sameArity = candidates.filter { it.params.size == expr.arguments.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用 '${expr.name}' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }
        val argTypes = expr.arguments.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = expr.arguments,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }
        val method = chosen.decl
        implicitCalls[expr] = UserMember.Method(
            className = interfaceOwnerTable[method]?.name ?: info.decl.name,
            decl = method,
            interfaceOwner = true,
        )
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        return instantiateReturn(expr, chosen, memberReturnType(method))
    }

    /**
     * 源码层面的名义子类型判定（阶段 20）：名字 [sub] 能不能当 [sup] 用。
     *
     * 只在 [RsfType.accepts] 说「不行」之后才走这里——它管的是 rsf 自己的子类型关系
     * （实现类 → 接口、子接口 → 父接口），而 [RsfType.accepts] 是个纯函数，摸不到
     * 类表/接口表，所以这类判定只能落在 Checker 里（D4）。
     *
     * 判据是**名字**（`RsfType.external(名字)` 里那个名字，类、枚举、接口都在这套表示里），
     * 从 [sub] 沿父类型一层层向上找；走到 classpath 上的类型时交给 [ClassPath.isSubtype]
     * （它自己会沿父类链和父接口链接着找）。**类型实参不参与判定**：和 JVM 一样按擦除看。
     */
    private fun isSubtypeName(sub: String, sup: String): Boolean {
        if (sub == sup) return true
        // 任何引用类型的祖先都是 Object（口径与 RsfType.accepts 里那条一致）
        if (sup == "java/lang/Object") return true
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(sub)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            if (cur == sup) return true
            classTable[cur]?.let { info ->
                // 阶段 25：**类继承也是子类型关系**（`class C : B()` ⇒ C is-a B）。
                // 父类走独立槽位（classParents）而不是 classSuperRefs，所以这里
                // 必须单独把它排进链里——漏了这一句，`var b : B = c` 就会被判成
                // 「不能把 C 赋给 B」（G1 探针 p2/p5/p7 正是死在这里）。
                parentRecord(info)?.let { queue.add(it.internalName) }
                for (s in classSuperRefs[info.decl].orEmpty()) queue.add(s.display)
            }
            interfaceTable[cur]?.let { info ->
                for (s in info.supertypes) queue.add(s.display)
            }
            // 名字属于 classpath（要么它本来就是 JVM 类型，要么继承链走到某个 JVM 接口）
            if (ClassPath.isSubtype(cur, sup)) return true
        }
        return false
    }

    /**
     * rsf 自己的子类型能不能接住（[acceptsValue] 的补充，阶段 20）。
     *
     * 只处理引用类型（数组、基本类型、类型变量都不归它管），判据是「沿着父类型链，
     * 能不能从 [valueType] 走到 [want]」：
     * - `I x = new Dog(...)`：Dog 实现 I，走名字链就够（[isSubtypeName]）；
     * - `Box&<int> b = new IntBox()`：泛型接口要实现链上**逐跳代换**后才能比
     *   （`class IntBox : Box&<int>` 把 T 兑现成 int），所以要带着类型实参走
     *   （[isSubtypeWithArgs]）——这是「泛型接口能声明/实现/调用」必须的那一步。
     *
     * 判不出来时**返回 false**（用户看到一条正常的「不能把 X 赋给 Y」）：
     * 放一个错类型进后端比报错糟得多。
     */
    private fun isSourceSubtype(want: RsfType, valueType: RsfType): Boolean {
        // 阶段 24：数组是**协变**的（`B[]` 能当 `A[]` 用），和 JVM 的
        // `CHECKCAST`/`INSTANCEOF` 一致——`var as1 : A[] = bs` 这种写法必须能过，
        // 否则 `as B[]` 那类探针连第一行都写不出来。基本类型数组不协变。
        if (want.isArray || valueType.isArray) return isArraySubtype(want, valueType)
        if (!want.isExternal || !valueType.isExternal) return false
        // 可空只能往更宽的地方走：`I x = 可空Dog` 照引用类型的老规矩报错
        if (valueType.nullable && !want.nullable) return false
        val sub = valueType.externalName ?: return false
        val sup = want.externalName ?: return false
        // 目标不带实参（`I x`）：只看名字链，够用也最省事
        if (want.typeArgs.isEmpty()) return isSubtypeName(sub, sup)
        return isSubtypeWithArgs(sub, valueType.typeArgs, sup, want.typeArgs)
    }

    /**
     * 数组之间的赋值兼容（阶段 24）：JVM 的数组是**协变**的，`B[]` 能当 `A[]` 用。
     *
     * 逐层拆 `[]` 比元素类型，规则就和运行期那条 `INSTANCEOF` 完全一致：
     * - 基本类型数组**不**协变：`int[]` 不是 `long[]`（JVM 也不让）；
     * - 引用元素按 [isSourceSubtype] 走类链/接口链（`C[]` 不是 `B[]`）；
     * - 类型参数擦除到 Object：`T[]` 与 `Object[]` 互相放得下。
     *
     * 二维以上（`int[][]`、`B[][]`）递归处理，维度不同直接不兼容。
     */
    private fun isArraySubtype(want: RsfType, valueType: RsfType): Boolean {
        if (!want.isArray || !valueType.isArray) return false
        if (want.arrayDepth != valueType.arrayDepth) return false
        // 可空只能往更宽的地方走，和引用类型的老规矩一致
        if (valueType.nullable && !want.nullable) return false
        val wantElement = want.elementType
        val valueElement = valueType.elementType
        if (wantElement == valueElement) return true
        if (wantElement.isArray && valueElement.isArray) return isArraySubtype(wantElement, valueElement)
        if (wantElement.isPrimitive || valueElement.isPrimitive) return false
        if (valueElement.isTypeParam || wantElement.isTypeParam) {
            // 擦除后都是 Object[]：只在两边都擦除到 Object 时才互通
            return valueElement.isTypeParam && wantElement.isTypeParam
        }
        return isSourceSubtype(wantElement, valueElement)
    }

    /**
     * 带类型实参的子类型判定（阶段 20）：从 `[sub]&<[subArgs]>` 沿父类型链走到
     * `[sup]&<[supArgs]>` 了吗？
     *
     * 每一跳都做一次代换：`class IntBox : Box&<int>` 里 IntBox 没有类型参数，
     * 走到父类型时父类型的实参就是 `[int]`；若是 `class C&<T> : Box&<T>`，
     * 则用 `T -> 实际实参` 把它代换出来。名字对上之后才比实参——`Box&<string>`
     * 接不住 `Box&<int>` 的实现，这正是静态类型该拦住的东西。
     *
     * 走到 classpath 上的类型时实参就没了（JVM 擦除），此时退回 [ClassPath.isSubtype]
     * 按名字判——那条路本来就是擦除后的口径（`Comparator&<string>` → `Comparator`）。
     */
    private fun isSubtypeWithArgs(
        sub: String,
        subArgs: List<RsfType>,
        sup: String,
        supArgs: List<RsfType>,
    ): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<String, List<RsfType>>>()
        queue.add(sub to subArgs)
        while (queue.isNotEmpty()) {
            val (cur, args) = queue.removeFirst()
            val key = cur + "(" + args.joinToString(",") { it.descriptor } + ")"
            if (!seen.add(key)) continue
            if (cur == sup) {
                // 目标没写实参 → 名字对上就算；写了实参 → 必须逐个对上
                if (supArgs.isEmpty() || args == supArgs) return true
                // 名字对上了、实参对不上：**这条分支彻底不成立**（`Box&<string>` 接不住
                // `Box&<int>` 的实现），不能再掉到下面那条擦除判据上去——`ClassPath.isSubtype`
                // 在 sub == sup 时恒真，会把刚刚判死的结论又放行。
                // args 为空表 = 走到这里时实参已经不可考（JVM 擦除），那才交给 ClassPath。
                if (args.isNotEmpty()) continue
            }
            for ((n, a) in superTypesWithArgs(cur, args)) queue.add(n to a)
            if (ClassPath.isSubtype(cur, sup)) return true
        }
        return false
    }

    /** [name] 的直接父类型，带上**代换后**的类型实参（阶段 20）。 */
    private fun superTypesWithArgs(
        name: String,
        args: List<RsfType>,
    ): List<Pair<String, List<RsfType>>> {
        val out = ArrayList<Pair<String, List<RsfType>>>()
        classTable[name]?.let { info ->
            val subst = substitutionFor(info.decl.typeParams, args)
            // 阶段 25：父**类**那一跳（G1 的泛型版）：`class IntHolder : Holder&<int>`
            // 要能从 IntHolder 走到 `Holder&<int>`，`Holder&<T>` 那侧的实参按本类
            // 类型参数代换（`class D&<U> : Base&<U>`）。
            parentRecord(info)?.let { p -> out.add(p.internalName to p.args.map { it.substitute(subst) }) }
            for (s in classSuperRefs[info.decl].orEmpty()) {
                when (s) {
                    is SuperRef.Rsf -> out.add(s.info.decl.name to s.args.map { it.substitute(subst) })
                    is SuperRef.Jvm -> out.add(s.cls.internalName to emptyList())
                }
            }
        }
        interfaceTable[name]?.let { info ->
            val subst = substitutionFor(info.decl.typeParams, args)
            for (s in info.supertypes) {
                when (s) {
                    is SuperRef.Rsf -> out.add(s.info.decl.name to s.args.map { it.substitute(subst) })
                    is SuperRef.Jvm -> out.add(s.cls.internalName to emptyList())
                }
            }
        }
        return out
    }

    /**
     * 一个「接口要求实现的方法」（阶段 20）。
     *
     * 把 rsf 接口与 JVM 接口的要求统一成同一个形状，后面的配对逻辑就只用写一遍；
     * 两者的差别只有「签名能不能严格比」——JVM 那边的方法描述符是**擦除后**的
     * （`Comparator.compare(T,T)` 是 `(Object,Object)`），rsf 里写不出那个签名，
     * 所以对 JVM 接口只在名字与个数上配对（这也是本阶段与 M4 的交接边界）。
     */
    private inner class RequiredMethod(
        val name: String,
        val params: List<RsfType>,
        val ret: RsfType,
        /** rsf 接口里的声明；来自 JVM 接口的是 null。 */
        val decl: FuncDecl?,
        /** 声明它的接口名（rsf 用简单名，JVM 用内部名）——判「谁的默认方法更具体」要用。 */
        val ownerName: String,
        /** 报错里的称呼，如 `接口 'Greeter'` / `接口 'java.util.Comparator'`。 */
        val ownerLabel: String,
        /** 接口给了默认实现（有体）。false = 实现类必须自己给出这个签名。 */
        val concrete: Boolean,
    ) {
        /** 参数表的擦除签名——配对判据（与 [checkErasureConflicts] 同一把尺子）。 */
        val key: String get() = signatureKey(name, params)

        fun display(): String = "$name(${params.joinToString(", ") { it.rsfName }}) -> ${ret.rsfName}"
    }

    /** 一个方法「名字 + 擦除后的参数表」的签名键。返回类型**不**参与（重载不靠它区分）。 */
    private fun signatureKey(name: String, params: List<RsfType>): String =
        name + "(" + params.joinToString(",") { it.descriptor } + ")"

    /**
     * 收集一个（可能带类型实参的）rsf 接口要求的全部方法，含它继承来的。
     *
     * [args] 是这一层的类型实参（`class C : Box&<int>` 里的 `int`）：方法签名里的
     * `T` 要换成它——不换的话 `fun get() -> T` 会和类里的 `fun get() -> int`
     * 判成「返回类型不一致」而误报。
     */
    private fun collectRsfRequired(
        info: InterfaceInfo,
        args: List<RsfType>,
        out: LinkedHashMap<String, MutableList<RequiredMethod>>,
        seen: MutableSet<String>,
    ) {
        if (!seen.add(info.decl.name)) return
        val subst = substitutionFor(info.decl.typeParams, args)
        for (list in info.methods.values) {
            for (m in list) {
                val req = RequiredMethod(
                    name = m.name,
                    params = paramTypesOfDecl(m).map { it.substitute(subst) },
                    ret = returnTypeOfDecl(m).substitute(subst),
                    decl = m,
                    ownerName = info.decl.name,
                    ownerLabel = "接口 '${info.decl.name}'",
                    concrete = !m.isAbstract,
                )
                out.getOrPut(req.key) { ArrayList() }.add(req)
            }
        }
        for (s in info.supertypes) {
            if (s is SuperRef.Rsf) {
                collectRsfRequired(s.info, s.args.map { it.substitute(subst) }, out, seen)
            }
        }
    }

    /** 收集一个 JVM 接口（含它继承的）要求的全部方法。只收**抽象**方法（默认方法不用实现）。 */
    private fun collectJvmRequired(
        cls: JvmClass,
        out: LinkedHashMap<String, MutableList<RequiredMethod>>,
        seen: MutableSet<String>,
    ) {
        if (!seen.add(cls.internalName)) return
        for (m in cls.methods) {
            if (m.isStatic || !m.isAbstract) continue
            // equals/hashCode/toString 在接口里也会被标成抽象，但它们由 Object 顶着
            if (isObjectMethod(m)) continue
            val req = RequiredMethod(
                name = m.name,
                params = m.paramTypes,
                ret = m.returnType,
                decl = null,
                ownerName = cls.internalName,
                ownerLabel = "接口 '${cls.dottedName}'",
                concrete = false,
            )
            out.getOrPut(req.key) { ArrayList() }.add(req)
        }
        for (name in cls.interfaces) {
            ClassPath.find(name)?.let { collectJvmRequired(it, out, seen) }
        }
    }

    /** `equals(Object)` / `hashCode()` / `toString()`——它们由 `java.lang.Object` 提供。 */
    private fun isObjectMethod(m: JvmMethod): Boolean = when (m.name) {
        "hashCode", "toString" -> m.paramTypes.isEmpty()
        "equals" -> m.paramTypes.size == 1 && isErasedObject(m.paramTypes[0])
        else -> false
    }

    /**
     * 实现配对（阶段 20）：每个类都要把父接口要求的抽象方法**全部覆盖**。
     *
     * 判据是「名字 + 参数（擦除后）+ 返回类型」：
     * - 三个都对上 → 算实现；返回类型不一致**报错**（返回类型不参与重载挑选，
     *   但它参与覆盖——`-> int` 顶不住 `-> string`）；
     * - 名字在、参数表对不上 → 报「签名不符」，并给出两边各自的写法；
     * - 名字都不在 → 收进「还缺哪些方法」的清单，一次全报出来。
     *
     * 接口提供的**默认方法**可以顶掉另一个接口的抽象要求（Java 的规矩），
     * 而两个互不相干的接口都给了同签名默认方法、类又没覆盖时，必须报错（D5）。
     */
    private fun checkImplementations() {
        checkInterfaceCycles()
        for (info in classTable.values) {
            // 阶段 25：父类实现的接口**也继承下来**——父类没实现的抽象方法，具体子类
            // 照样得实现（JVM 的规矩：`class C : Shape` 而 Shape 实现了 Greeter，
            // C 也得把 greet 实现出来）。所以要求要从整条父类链上收集。
            val supers = classSuperRefs[info.decl].orEmpty() + inheritedInterfaceRefs(info)
            if (supers.isEmpty()) continue
            currentSource = info.source
            val required = LinkedHashMap<String, MutableList<RequiredMethod>>()
            val seenIface = HashSet<String>()
            for (s in supers) {
                when (s) {
                    is SuperRef.Rsf -> collectRsfRequired(s.info, s.args, required, seenIface)
                    is SuperRef.Jvm -> collectJvmRequired(s.cls, required, seenIface)
                }
            }
            checkClassCovers(info, required)
        }
    }

    /**
     * 父类链上（不含自己）实现的全部接口（阶段 25）。
     *
     * 接口是**跟着类一起继承**的：`class C : Shape` 而 `abstract class Shape : Greeter`
     * 时，C 也欠 Greeter 一个 `greet`——不把这份要求带下来的话，C 会「编得过但跑起来
     * AbstractMethodError」，正是两端不许背离的那种情形。
     */
    private fun inheritedInterfaceRefs(info: ClassInfo): List<SuperRef> =
        ancestorsOf(info).flatMap { classSuperRefs[it.decl].orEmpty() }

    /**
     * `override` 修饰词的检查（阶段 20 追加件）。
     *
     * `override` 的语义照 Java 的 `@Override`：**写不写都能覆盖**，写了就要求它真的覆盖到了
     * 某个父类型方法——类里写的沿父接口链找（rsf 接口 + JDK 接口，含接口继承链与默认方法），
     * 接口里写的沿它继承的接口找。找不到就报错：一句空断言比不写更坏，它会让人以为
     * 「改这个名字不影响别处」。
     *
     * 看不见父**类**：D2 不支持类继承，所以这里没有那一支；`java/lang/Object` 的方法在 rsf 里
     * 也不参与覆盖（用户对象上根本没有可见的 Object 成员，`toString()` 是后端自己合成的）。
     * 于是「一个接口都没实现的类」写了 `override` 同样报错——它确实覆盖不到任何东西。
     *
     * 判据是「名字 + 参数表」（[signatureKey]，和实现配对同一把尺子）——返回类型不参与，
     * 那是 [checkClassCovers] 按「接口要求什么」比的事。JDK 接口的签名是擦除形态，
     * 只能按名字 + 参数个数配（见 [jvmChainCovers]，与 [checkClassCovers] 对 JVM 的口径一致）。
     */
    private fun checkOverrides() {
        for (info in classTable.values) {
            // 这里**不**跳过「一个接口都没实现」的类：rsf 看不见父类（D2），也看不见
            // java/lang/Object 的方法，所以这种类里的 override 一定落空，该报错而不是放行。
            // 阶段 27：类默认可以被继承，只有父类写了 'final' 才禁止；方法默认可以被覆盖，
            // 只有父方法写了 'final' 才封住（DESIGN-27）。
            val supers = classSuperRefs[info.decl].orEmpty() + inheritedInterfaceRefs(info)
            for (list in info.methods.values) {
                for (m in list) {
                    if (!m.isOverride) continue
                    currentSource = info.source
                    if (supers.any { covers(it, m) }) continue
                    if (inheritedMethodSameSignature(info, m) != null) continue
                    throw err(
                        "类 '${info.decl.name}' 的 '${m.name}' 写了 override，但父类型里没有可覆盖的方法：" +
                            "接口里没有人要求 ${describeSignature(m)}，父类里也没有同签名的方法" +
                            "（父类里若写了 'final' 也不能覆盖，DESIGN-27）",
                        m.at,
                    )
                }
            }
        }
        for (info in interfaceTable.values) {
            for (list in info.methods.values) {
                for (m in list) {
                    if (!m.isOverride) continue
                    currentSource = info.source
                    if (info.supertypes.any { covers(it, m) }) continue
                    throw err(
                        "接口 '${info.decl.name}' 的 '${m.name}' 写了 override，" +
                            "但它继承的接口里没有 ${describeSignature(m)}",
                        m.at,
                    )
                }
            }
        }
    }

    /** [m] 有没有覆盖到父类型 [s]：rsf 接口与 JDK 接口的尺子不同，分两支判。 */
    private fun covers(s: SuperRef, m: FuncDecl): Boolean = when (s) {
        is SuperRef.Rsf -> rsfChainCovers(s, m)
        is SuperRef.Jvm -> jvmChainCovers(s.cls, m, HashSet())
    }

    /** rsf 接口 [s]（含它**继承来的**）有没有签名与 [m] 相同的方法——抽象、默认都算。 */
    private fun rsfChainCovers(s: SuperRef.Rsf, m: FuncDecl): Boolean {
        val required = LinkedHashMap<String, MutableList<RequiredMethod>>()
        collectRsfRequired(s.info, s.args, required, HashSet())
        return required.containsKey(signatureKey(m.name, paramTypesOfDecl(m)))
    }

    /**
     * JVM 接口 [cls]（含它**继承来的**）有没有签名与 [m] 对得上的方法。
     *
     * 判据只有「名字 + 参数个数」：JDK 方法的签名是擦除形态，rsf 侧写不出来
     * （`run()` 这种恰好能对上，`compare(T, T)` 就只能按个数配）——这与 [checkClassCovers]
     * 对 JVM 接口的尺子一致。**默认方法也算可覆盖**（有体、非 static），
     * `override fun toString()` 这种覆盖 JDK 默认方法的写法同样合法。
     */
    private fun jvmChainCovers(cls: JvmClass, m: FuncDecl, seen: MutableSet<String>): Boolean {
        if (!seen.add(cls.internalName)) return false
        val hit = cls.methods.any { !it.isStatic && it.name == m.name && it.paramTypes.size == m.params.size }
        if (hit) return true
        for (sup in cls.interfaces) {
            val next = ClassPath.find(sup) ?: continue
            if (jvmChainCovers(next, m, seen)) return true
        }
        return false
    }

    /** 报错文案里的签名形态，如 `greet(string)`。 */
    private fun describeSignature(m: FuncDecl): String =
        m.name + "(" + paramTypesOfDecl(m).joinToString(", ") { it.rsfName } + ")"

    private fun checkClassCovers(
        info: ClassInfo,
        required: Map<String, List<RequiredMethod>>,
    ) {
        val cls = info.decl
        val missing = ArrayList<String>()
        for ((key, reqs) in required) {
            val wanted = reqs.first()
            val fromJvm = wanted.decl == null
            // 形参对得上（JVM 接口只按个数配，rsf 接口按完整签名配）
            val shapeOk: (FuncDecl) -> Boolean = { m ->
                !m.isConstructor &&
                    if (fromJvm) {
                        // JVM 接口的签名是擦除后的形态，rsf 侧写不出来：只按名字 + 个数配
                        m.params.size == wanted.params.size
                    } else {
                        signatureKey(m.name, paramTypesOfDecl(m)) == key
                    }
            }
            // 还要有资格当实现：public（缺省就是 public）的实例方法。
            // private / protected / static 在 JVM 眼里都**没有**实现接口方法——不看这层的话，
            // `rsf run` 跑得过去，字节码一调就是 AbstractMethodError（两端背离）。
            val shapeMatches: (FuncDecl) -> Boolean = { m ->
                shapeOk(m) && !m.isStatic &&
                    (m.control == ControlType.PUBLIC || m.control == ControlType.DEFAULT)
            }
            // 阶段 25：沿**父类链**找实现——父类里已经实现好的方法，子类不用再写一遍
            // （那正是继承的意义）。static 与 private 不继承，[methodOnChain] 已滤掉。
            val own = methodOnChain(info, wanted.name, shapeMatches)
            if (own != null) {
                // 返回类型：rsf 接口严格比；JVM 接口只在两边都不是擦除 Object/类型变量时比
                val wantRet = reqs.firstOrNull { it.decl != null }?.ret
                    ?: reqs.first { !it.concrete }.ret
                val actualRet = returnTypeOfDecl(own)
                if (!fromJvm && !retCovers(wantRet, actualRet)) {
                    throw err(
                        "类 '${cls.name}' 的 '${own.name}' 返回类型和接口对不上：" +
                            "${reqs.first { !it.concrete }.ownerLabel} 要求 -> ${wantRet.rsfName}，" +
                            "类里写的是 -> ${actualRet.rsfName}",
                        own.at,
                    )
                }
                continue
            }
            // 接口自己给了默认实现 → 这个签名已经有着落了
            if (reqs.any { it.concrete }) continue
            // 名字和形参都对得上，只是 private/protected/static：单独报一句能照着改的错，
            // 比落到下面笼统的「签名对不上」有用得多。
            val barred = info.methods[wanted.name].orEmpty().firstOrNull { shapeOk(it) && !shapeMatches(it) }
            if (barred != null) {
                val why = when {
                    barred.isStatic -> "stc 方法"
                    barred.control == ControlType.PRIVATE -> "prv 方法"
                    else -> "prot 方法"
                }
                throw err(
                    "类 '${cls.name}' 里的 '${barred.name}' 是 $why，实现不了接口要求的 " +
                        "${wanted.display()}（来自 ${wanted.ownerLabel}）：" +
                        "接口方法必须是 pub 的实例方法（不写修饰词就是 pub）",
                    barred.at,
                )
            }
            // 名字对得上、参数表对不上：报「签名不符」，比笼统的「没实现」有用得多
            val sameName = info.methods[wanted.name].orEmpty().filter { !it.isConstructor }
            if (sameName.isNotEmpty()) {
                val got = sameName.first()
                throw err(
                    "类 '${cls.name}' 里的 '${got.name}' 和接口签名对不上：${wanted.ownerLabel} 要求 " +
                        "${wanted.display()}，类里写的是 " +
                        "${got.name}(${paramTypesOfDecl(got).joinToString(", ") { it.rsfName }}) -> " +
                        "${returnTypeOfDecl(got).rsfName}。" +
                        "覆盖接口方法要名字、参数类型、返回类型三者都对上",
                    got.at,
                )
            }
            // 抽象类允许先欠着：这份义务顺着父类链传给具体子类（[checkImplementations]
            // 已经把父类实现的接口也算到子类头上，[checkAbstractMembers] 负责盯具体子类），
            // 所以抽象类这里不报「还缺」。
            if (cls.isAbstract) continue
            val ab = reqs.first { !it.concrete }
            missing.add("${ab.display()}（来自 ${ab.ownerLabel}）")
        }

        if (missing.isNotEmpty()) {
            throw err(
                "类 '${cls.name}' 没有实现接口要求的全部方法，还缺 ${missing.size} 个：\n  - " +
                    missing.joinToString("\n  - ") +
                    "\n（在类里写一个同名、同参数类型、同返回类型的方法就补上了；" +
                    "接口里写了方法体（默认方法）的不用实现）",
                cls.at,
            )
        }

        checkDefaultConflicts(info, required)
    }

    /**
     * D5：两个（互不继承的）父接口都给了同签名的默认方法，而类自己没有覆盖它。
     *
     * 为什么必须报错：那份字节码里到底调哪一份是**说不清**的——选一份就是替用户猜。
     * 报错要求他显式写一个同签名的方法（写出来的那一刻就表达了「我要哪一份」的意图）。
     * `A : B` 这种有继承关系的两个默认方法不算冲突：A 的那份本来就覆盖 B 的。
     */
    private fun checkDefaultConflicts(
        info: ClassInfo,
        required: Map<String, List<RequiredMethod>>,
    ) {
        for ((key, reqs) in required) {
            val defaults = reqs.filter { it.concrete && it.decl != null }
            if (defaults.size < 2) continue
            // 「覆盖」了这份冲突的必须是**有资格当实现的**方法：private/protected/static
            // 方法在 JVM 眼里不算数，用它来「拍板」等于没拍（字节码里依旧 AbstractMethodError）。
            val overridden = info.methods[defaults.first().name].orEmpty().any {
                !it.isConstructor && !it.isStatic &&
                    (it.control == ControlType.PUBLIC || it.control == ControlType.DEFAULT) &&
                    signatureKey(it.name, paramTypesOfDecl(it)) == key
            }
            if (overridden) continue
            // 只留下「没有别的默认方法比它更具体」的那些；剩下的还多于一个就是真冲突
            val owners = defaults.map { it.ownerName }.distinct()
            val winners = owners.filter { p ->
                owners.none { q -> q != p && isSubtypeName(q, p) }
            }
            if (winners.size >= 2) {
                val labels = defaults.filter { it.ownerName in winners }.map { it.ownerLabel }.distinct()
                throw err(
                    "类 '${info.decl.name}' 从两个接口继承到同签名的默认方法，说不清该用哪一份：" +
                        labels.joinToString(" 和 ") + " 都有 ${defaults.first().display()}。" +
                        "在类 '${info.decl.name}' 里显式写一个同签名的方法覆盖它（写出来就表示你要哪一份）",
                    info.decl.at,
                )
            }
        }
    }

    /**
     * 覆盖时返回类型的兼容判定：**必须一致**（D2 的返回类型口径）。
     *
     * 只放宽一条：接口写 `T`（类型参数）而实现类写具体类型时，接口那一侧换成
     * 实现类给的类型——那正是「泛型接口的实参兑现」的地方，比较顺序不能反。
     */
    private fun retCovers(want: RsfType, actual: RsfType): Boolean {
        if (want.isTypeParam) return true
        // `-> void` 与 `-> void` 必须放行：`RsfType.accepts` 对 VOID 一律 false（void 不是值，
        // 不能参与赋值/加宽），但「接口要求 void，类里也写 void」是唯一合法的兑现方式。
        if (want.base == RsfBase.VOID && actual.base == RsfBase.VOID) return true
        if (want.accepts(actual)) return true
        return isSourceSubtype(want, actual)
    }

    /**
     * 父接口不能成环（`interface A : B` 与 `interface B : A`）。
     *
     * 继承链是静态的，成环会让「这个接口要求实现哪些方法」永远算不完
     * （收方法时有去重集合兜着，不至于卡死，但那条链本身已经没有意义），
     * 所以在动手查实现之前就报掉。
     */
    private fun checkInterfaceCycles() {
        val state = HashMap<String, Int>() // 1 = 正在这条链上，2 = 已经查完
        fun walk(info: InterfaceInfo, path: List<String>) {
            when (state[info.decl.name]) {
                2 -> return
                1 -> {
                    val cycle = path.dropWhile { it != info.decl.name } + info.decl.name
                    currentSource = info.source
                    throw err(
                        "接口继承成环了：${cycle.joinToString(" -> ")}——" +
                            "把这些 ': 父接口' 里多余的那一条去掉",
                        info.decl.at,
                    )
                }
            }
            state[info.decl.name] = 1
            for (s in info.supertypes) if (s is SuperRef.Rsf) walk(s.info, path + info.decl.name)
            state[info.decl.name] = 2
        }
        for (info in interfaceTable.values) walk(info, emptyList())
    }

    /**
     * 接口的方法体（阶段 20）：只有**默认方法**有体，抽象方法（`body == null`）
     * 在 [checkFunction] 里直接收工。
     *
     * 和 [checkClassBodies] 同型：先把「现在在哪个接口里、类型参数是什么」摆好，
     * 再逐个过方法体——`this`、隐式方法调用、`T` 这些都要靠这两个状态。
     */
    private fun checkInterfaceBodies() {
        for (info in interfaceTable.values) {
            currentSource = info.source
            currentInterface = info
            currentClass = null
            scope = Scope(null)
            typeParams = info.decl.typeParams.associateWith { RsfType.typeParam(it) }
            for (m in info.decl.methods) {
                if (!m.isAbstract) checkFunction(m)
            }
        }
        currentInterface = null
        typeParams = emptyMap()
    }

    // 类解析产物（阶段 12-C）：方法一律按 FuncDecl 索引，不跟顶层函数抢名字
    private val methodOwner = IdentityHashMap<FuncDecl, ClassDecl>()
    private val methodParams = IdentityHashMap<FuncDecl, List<Binding>>()
    private val methodReturns = IdentityHashMap<FuncDecl, RsfType>()
    private val methodLowered = IdentityHashMap<FuncDecl, LoweredFunction>()
    private val userMembers = IdentityHashMap<MemberExpr, UserMember>()
    private val fieldReads = IdentityHashMap<VarExpr, UserMember.Field>()
    private val bareFieldWrites = IdentityHashMap<AssignStmt, UserMember.Field>()
    private val fieldWrites = IdentityHashMap<MemberAssignStmt, UserMember.Field>()
    /**
     * 构造点 → 类声明。键是 [Expr]：显式 `new Point(1, 2)` 是 [NewExpr]，
     * **省掉 `new` 的 `Point(1, 2)`** 是 [CallExpr]，两者共用同一张表——
     * 这样后端的表达式分派只要问一句「是不是构造」就够。
     */
    private val newTargets = IdentityHashMap<Expr, ClassDecl>()

    /** 构造点 → 选中的构造器（阶段 18）；类没写构造器时表里没有这一项。 */
    private val newCtors = IdentityHashMap<Expr, FuncDecl>()

    /** 构造器 → 它的委托边 `this(...)`（阶段 18）。 */
    private val ctorDelegations = IdentityHashMap<FuncDecl, CtorDelegation>()

    private val implicitCalls = IdentityHashMap<CallExpr, UserMember.Method>()

    /**
     * 每个类的字段类型（按声明顺序）。
     *
     * 之所以**存下来**而不是每次 `resolveType(field.type)`：泛型类的字段类型里可能有
     * 它自己的类型参数（`class Box&<T>` 的 `var value : T`），而 `T` 的含义取决于
     * 「在哪个类里」。解析一次、按 ClassDecl 索引，别处（成员访问、字段赋值、
     * 字节码后端）一律读这份结果，就不会拿别的类的环境去解释 T。
     */
    private val classFieldTypes = IdentityHashMap<ClassDecl, List<RsfType>>()

    /**
     * 构造**导入的 JVM 类**（`new ArrayList&<int>()`，或者省掉 `new` 的
     * `ArrayList&<int>()`）→ 解析到的构造器。
     */
    private val externalNewTargets = IdentityHashMap<Expr, ResolvedMethod>()

    /**
     * 当前正在检查的类体内的类型参数环境：`T` → 占位类型。
     *
     * 只在**声明这个类型参数的类**里非空——出了类体（顶层函数、别的类）
     * 它就不是一个类型名了，这跟 Java 的规矩一样。
     */
    private var typeParams: Map<String, RsfType> = emptyMap()

    /**
     * 阶段 31：进入一个（可能带类型参数的）函数时，把**它的**类型参数并进 [typeParams]。
     *
     * 函数级类型参数**遮蔽**同名的类级类型参数（DESIGN-31 §3）：`class Box&<T>` 里写
     * `fn first&<T>(xs : T[])`，方法体里的 `T` 是方法的那个。返回旧环境，
     * 调用方用完负责还原（`typeParams = saved`）。
     */
    private fun pushTypeParams(fn: FuncDecl): Map<String, RsfType> {
        val saved = typeParams
        if (fn.typeParams.isNotEmpty()) {
            typeParams = saved + fn.typeParams.associateWith { RsfType.typeParam(it) }
        }
        return saved
    }

    /**
     * 字段成员。[subst] 是泛型实参的替换表：`Box&<int>` 的 `value` 从 `T` 变成 `int`；
     * 在类体内部（`this.value` / 裸写 `value`）传空表，`T` 就还是占位。
     *
     * **阶段 25：字段布局是「父类字段在前」**（DESIGN-25 §5）。[info] 是**访问发生的类**，
     * 字段可能声明在父类上，所以：
     *
     * - `owner` 取**声明这个字段的类**，下标 = `fieldBase(owner)`（那条父链上的字段总数）
     *   + 它在 owner 自己声明序里的位置；
     * - [UserMember.Field.className] 记的是**声明类**——JVM 的 `PUTFIELD`/`GETFIELD`
     *   按「声明类」解析字段引用，两端（字节码 / 解释器）读的就是这一份 owner。
     *
     * 这份绝对下标是 K1 一次性算好的：后端不需要再自己拼父链（R1：只有一份布局来源）。
     * 没有父类时 `fieldBase` 是 0，行为与阶段 24 逐字节相同。
     */
    private fun fieldMember(
        info: ClassInfo,
        field: FieldDecl,
        subst: Map<String, RsfType> = emptyMap(),
    ): UserMember.Field {
        val owner = fieldOwnerOf(info, field.name) ?: info
        val index = owner.decl.fields.indexOfFirst { it.name == field.name }
        val declared = classFieldTypes[owner.decl]?.getOrNull(index) ?: resolveType(field.type)
        return UserMember.Field(owner.decl.name, fieldBase(owner) + index, declared.substitute(subst))
    }

    /** 当前正在检查的类；不在类里的时候是 null。`this` 与「裸字段名」都靠它。
*/
    private var currentClass: ClassInfo? = null

    /**
     * 当前正在检查的接口；不在接口里的时候是 null（阶段 20）。
     *
     * 和 [currentClass] 同时存在但**不会同时非空**：接口的默认方法体里 `this` 是接口类型，
     * 裸名字的方法调用（`greet(name)`）要在接口的方法表里找。分开两个变量而不是
     * 合成一个「所在类型」，是因为两边能做的事完全不同（接口没有字段、没有构造器）。
     */
    private var currentInterface: InterfaceInfo? = null

    private fun declareClasses() {
        // 第一遍：只把类名占上。类之间允许互相引用（`class A` 的字段可以是 `class B`，
        // 哪怕 B 写在 A 后面），所以字段/方法的类型解析必须等到名字都齐了再做。
        for (cls in program.classes) {
            currentSource = sourceOfClass[cls] ?: source
            if (classTable.containsKey(cls.name)) {
                throw err("类 '${cls.name}' 重复定义", cls.at)
            }
            if (enumTable.containsKey(cls.name)) {
                throw err(
                    "类 '${cls.name}' 和同名的枚举冲突：枚举名和类名在同一个命名空间里" +
                        enumValueList(cls.name),
                    cls.at,
                )
            }
            if (interfaceTable.containsKey(cls.name)) {
                throw err("类 '${cls.name}' 和同名的接口冲突：$namespaceNote", cls.at)
            }
            if (annotationTable.containsKey(cls.name)) {
                throw err("类 '${cls.name}' 和同名的注解冲突：$annotationNamespaceNote", cls.at)
            }
            classTable[cls.name] = ClassInfo(cls, currentSource, LinkedHashMap(), LinkedHashMap(), emptyList())
        }

        // 第二遍：字段、方法、构造器（这时所有类名都已经可见）
        for (cls in program.classes) {
            declareClassBody(cls, sourceOfClass[cls] ?: source)
        }
        // 出了类的签名范围，类型参数就不再是类型名
        typeParams = emptyMap()
    }

    /**
     * 一个类声明的**第二遍**：字段、方法、构造器、父类型，全部解析并登记。
     *
     * 从 [declareClasses] 里拆出来（阶段 21）是为了让注解的 **companion entity 复用同一条路径**：
     * 伴生实体在语义层里就是一个类（合成的 [ClassDecl]），字段重名、`checkSignature`、
     * 擦除冲突、参数绑定、父接口解析这些规矩一条都不该少，也不该另写一份。
     * 复用的代价只有两个参数：[extraSupers]（伴生类额外实现的注解接口）与
     * [companionEntity]（继承类时的报错要指向「类继承那一阶段」）。
     */
    private fun declareClassBody(
        cls: ClassDecl,
        src: SourceFile,
        extraSupers: List<SuperRef> = emptyList(),
        companionEntity: Boolean = false,
    ) {
        currentSource = src
        // 这个类的类型参数只在它自己的签名与类体里有意义
        typeParams = cls.typeParams.associateWith { RsfType.typeParam(it) }

        val fields = LinkedHashMap<String, FieldDecl>()
        for (f in cls.fields) {
            if (fields.put(f.name, f) != null) {
                throw err("类 '${cls.name}' 里的字段 '${f.name}' 重复定义", f.at)
            }
        }
        classFieldTypes[cls] = cls.fields.map { resolveType(it.type) }

        val methods = LinkedHashMap<String, MutableList<FuncDecl>>()
        val constructors = ArrayList<FuncDecl>()
        for (m in cls.methods) {
            if (m.isConstructor) {
                // 主构造是解析时合成的（体是空块），次构造来自 constructor(...)；
                // 两者的返回类型都固定 void、都不能 static——parser 已经拦过，
                // 这里再确认一次，因为签名合成的入口不止一处
                if (m.isStatic) throw err("构造器不能是 stc 的（它一定要有当前对象 this）", m.at)
                if (m.ret.mayError || m.ret.type.name != "void") {
                    throw err("构造器没有返回值，也不可能是可能失败的（!T）", m.at)
                }
                constructors.add(m)
                continue
            }
            if (m.name == cls.name) {
                throw err(
                    "类 '${cls.name}' 里的方法不能和类同名。构造器现在有两种写法：" +
                        "主构造写在类头括号里（class ${cls.name}(参数) { ... }），" +
                        "次构造写成 constructor(参数) { ... }",
                    m.at,
                )
            }
            checkSignature(m)
            // 阶段 19d：同一个名字可以有多个方法（重载）。合法性在下面统一查
            // ——两个重载在 JVM 上必须是不同的方法（擦除后的描述符不能撞车）。
            methods.getOrPut(m.name) { ArrayList() }.add(m)
        }

        // 方法/构造器的参数绑定与返回类型：按 FuncDecl 记，不写进按名字索引的那两张表
        for (m in cls.methods) {
            methodOwner[m] = cls
            // 阶段 31：泛型方法自己的类型参数要并进解析环境（否则 `xs : T[]` 里的 T 是未知类型）
            val savedTypeParams = pushTypeParams(m)
            // 报错时的称呼：构造器就说「构造器 '类名'」，方法就说「方法 '名字'」
            val what = if (m.isConstructor) "构造器 '${cls.name}'" else "方法 '${m.name}'"
            val bindings = ArrayList<Binding>(m.params.size)
            val seen = HashSet<String>()
            for ((index, p) in m.params.withIndex()) {
                val paramType = resolveType(p.type)
                if (paramType == RsfType.VOID) {
                    throw err("$what 的参数 '${p.name}' 的类型不能是 void", p.at)
                }
                if (!seen.add(p.name)) {
                    throw err("$what 的参数 '${p.name}' 重名了", p.at)
                }
                bindings.add(Binding(p.name, paramType, mutable = true, paramIndex = index, at = p.at))
            }
            methodParams[m] = bindings
            methodReturns[m] =
                if (m.ret.type.name == "void") RsfType.VOID else resolveType(m.ret.type)
            typeParams = savedTypeParams
        }

        // 阶段 19d：同一个方法名下的多个重载必须在 JVM 上是**不同的方法**
        // （擦除后的参数描述符不能撞车）。构造器不适用——它保留老规矩（见 resolveCtor）。
        checkErasureConflicts(cls.name, methods)

        // 构造器签名不能重复：`constructor(int)` 与主构造 `(int)` 同时存在就说不清 new 哪个。
        // 判据用**解析后的参数类型**（不是源码写法）：`int` 和 `int`、`Box&<int>` 和
        // `Box&<int>` 才算同一个签名——和 JVM 一样按擦除后的描述符比。
        val seenSignatures = HashMap<String, FuncDecl>()
        for (c in constructors) {
            val key = methodParams[c].orEmpty().joinToString(",") { it.type.rsfName }
            val prev = seenSignatures.put(key, c)
            if (prev != null) {
                throw err(
                    "类 '${cls.name}' 有两个参数表完全一样的构造器（都是 ($key)）——" +
                        "new ${cls.name}(...) 时没法判断该用哪一个",
                    c.at,
                )
            }
        }

        // 父类型（阶段 20：`class Dog : Greeter, Runnable`）。
        // **只收接口，而且收不下就报错**（D2）：静默忽略的话 `class A : B` 会编出一个
        // 悄悄丢掉 B 的类，错误要拖到运行期才炸——那正是「不许产出半成品」要避免的。
        // [extraSupers] 是注解伴生类额外实现的注解接口（摆在最前面：它才是这个类的"根"）。
        classSuperRefs[cls] = extraSupers + cls.supertypes.map {
            resolveSuperTypeRef(
                it,
                ownerKind = "类",
                ownerName = cls.name,
                companionEntity = companionEntity,
            )
        }

        classTable[cls.name] = ClassInfo(cls, src, fields, methods, constructors.toList())
        // 出了这个类的签名范围，它的类型参数就不再是类型名（调用方自己也会清一次）
        typeParams = emptyMap()
    }

    /** `<MainType>` 这个"还没实现"的槽位，函数和方法用同一套说法（`!T` 阶段 14 已经实现）。 */
    private fun checkSignature(m: FuncDecl) {
        if (m.mainType.name != "void") {
            throw err(
                "<MainType> 目前只支持 void（实际是 '${m.mainType.name}'（",
                m.mainType.at,
            )
        }
    }

    /**
     * 一个 `FuncDecl` 的形参类型（解析后的）。
     *
     * 方法/构造器读 [methodParams]（那里存的是带类型参数占位的解析结果），
     * 顶层函数读 [paramTypes]；两张表都按 `FuncDecl` 索引，所以同名重载不会互相覆盖。
     */
    private fun paramTypesOfDecl(fn: FuncDecl): List<RsfType> =
        methodParams[fn]?.map { it.type }
            ?: paramTypes[fn]
            ?: fn.params.map { resolveType(it.type) }

    /** 一个 `FuncDecl` 的返回类型（解析后的）；方法读 [methodReturns]，顶层函数读 [returnTypes]。 */
    private fun returnTypeOfDecl(fn: FuncDecl): RsfType =
        methodReturns[fn]
            ?: returnTypes[fn]
            ?: if (fn.ret.type.name == "void") RsfType.VOID else resolveType(fn.ret.type)

    /**
     * 同一个名字下的多个重载，在 JVM 上必须是**不同的方法**。
     *
     * 判据是**擦除后的参数描述符**（[RsfType.descriptor]，它天然不带泛型实参、
     * 把类里的类型参数擦成 `Object`）：`f(Box&<int>)` 与 `f(Box&<string>)`
     * 的描述符都是 `(LBox;)V`，JVM 里就是同一个方法，两个一起编出来只会有一个活下来
     * （后声明的把先声明的挡掉），所以必须在编译期拒绝，而不是产出一份自己都对不上的字节码。
     *
     * **返回类型不参与判据**：重载只能靠参数区分，`f(int) -> int` 与 `f(int) -> string`
     * 在调用点 `f(1)` 上一样说不清该返回什么。
     *
     * [owner] 为 null 表示顶层函数——它们全都编进同一个程序类的静态方法，判据相同。
     * **构造器不走这里**：构造器的重复签名检查是另一套（保留阶段 18 的老规矩）。
     */
    private fun checkErasureConflicts(
        owner: String?,
        byName: Map<String, MutableList<FuncDecl>>,
        /** 报错里的称呼：类就说「类」，接口就说「接口」（阶段 20 起接口也用这套判据）。 */
        ownerWord: String = "类",
    ) {
        for ((name, decls) in byName) {
            if (decls.size < 2) continue
            val seen = HashMap<String, FuncDecl>()
            for (d in decls) {
                val params = paramTypesOfDecl(d)
                val key = params.joinToString(", ") { it.descriptor }
                val prev = seen.put(key, d) ?: continue
                // 两个无参 main：说「入口歧义」比说「描述符相同」更贴近用户的意图
                if (owner == null && name == "main" && d.params.isEmpty()) {
                    throw err(
                        "入口函数 main 只能有一个：这里声明了两个无参的 fn main()" +
                            "（前一个在第 ${prev.at.line} 行）。JVM 只认一个入口，" +
                            "rsf 也不知道该从哪一个开始跑——把其中一个改名，或者写成 " +
                            "main(参数)（带参数的 main 是合法的普通函数，只是不能当入口）",
                        d.at,
                    )
                }
                val where = if (owner == null) "函数 '$name'" else "$ownerWord '$owner' 里的方法 '$name'"
                // 源码写法也完全一样 ⇒ 就是我们平时说的「重复定义」，沿用老文案
                val source = params.joinToString(", ") { it.rsfName }
                val prevSource = paramTypesOfDecl(prev).joinToString(", ") { it.rsfName }
                if (source == prevSource) {
                    val retPrev = returnTypeOfDecl(prev)
                    val retNow = returnTypeOfDecl(d)
                    val why = if (retPrev != retNow) {
                        "两处的参数一模一样，只有返回类型不同（${retPrev.rsfName} 与 ${retNow.rsfName}）" +
                            "——重载**不看返回类型**：调用点 ${name}(...) 说不清该返回哪一种，所以不能这么写"
                    } else {
                        "两处的签名（参数与返回类型）都一样，说不清该调哪一个"
                    }
                    throw err("$where 重复定义（上一次在第 ${prev.at.line} 行）：$why", d.at)
                }
                // 源码写法不同、擦除后却撞车 ⇒ 擦除冲突（泛型实参是编译期的，JVM 看不见）
                throw err(
                    "$where 有两个重载在 JVM 上是同一个方法：参数擦除后的描述符都是 ($key)。" +
                        "第 ${prev.at.line} 行的 ${signatureOf(prev)} 与这里的 ${signatureOf(d)} " +
                        "编译出来同名同描述符，一个类里只能留一个。" +
                        "泛型实参不参与 JVM 的方法描述符（`Box&<int>` 与 `Box&<string>` " +
                        "擦除后都是 `LBox;`），重载也**不看返回类型**——" +
                        "把其中一个改名，或者让它多收一个参数",
                    d.at,
                )
            }
        }
    }

    /** 字段初值 + 每个方法的方法体（在「这个类的作用域」里查）。
*/
    private fun checkClassBodies() {
        for (info in classTable.values) {
            currentClass = info
            currentSource = info.source
            scope = Scope(null)
            // 类体里 `T` 就是类型：字段、参数、返回、局部变量都能写
            typeParams = info.decl.typeParams.associateWith { RsfType.typeParam(it) }

            // 主构造的参数在**字段初值**里可见（`class P(x0 : int) { var x : int = x0 }`）：
            // 主构造的「体」就是字段初值，这段作用域等价于主构造的函数体。
            // 次构造的参数不在这里——它们的体是自己的 constructor(...) 块。
            for (b in info.primary?.let { methodParams[it] }.orEmpty()) {
                scope.declare(b.name, b)
            }

            // 父类的构造实参（阶段 25）：就在主构造的参数作用域里求类型——类头那句
            // `: Base(y)` 里的 y 就是主构造的参数。排在字段初值之前查，用户先看到
            // 「父类那个构造器对不上」，再看到字段初值里的错。
            checkParentCalls(info)

            val declaredFields = classFieldTypes[info.decl].orEmpty()
            for ((index, f) in info.decl.fields.withIndex()) {
                val declared = declaredFields.getOrElse(index) { resolveType(f.type) }
                if (declared.isVoid) throw err("字段 '${f.name}' 不能是 void", f.at)
                // 阶段 21：注解伴生类里的**元素字段**是合成出来的，初值只是个占位
                // （真正的值由 M3 在 `<init>` 里按构造器参数写进去），不参与初值检查。
                if (elementFields.containsKey(f)) continue
                val initType = type(f.initializer)
                if (!acceptsValue(declared, f.initializer, initType)) {
                    throw err(
                        "字段 '${f.name}' 声明是 ${declared.rsfName}，初值却是 ${initType.rsfName}",
                        f.at,
                    )
                }
            }

            for (m in info.decl.methods) checkFunction(m)

            // 构造器委托链的规则：链的根（不委托的那个）负责跑字段初值。
            // 这一条是**保证字段初值里的主构造参数一定有值**的关键——所以有主构造时，
            // 次构造必须委托（和 Kotlin 同一条规矩）。
            checkConstructorChain(info)
            // 父类的**隐式** super()（阶段 25）：没有主构造、又没写 super(...) 的
            // 构造器由编译器补一句无参 super()。放在委托链检查之后：「有没有写」那时才答得准。
            checkImplicitParentCalls(info)
        }
        currentClass = null
        typeParams = emptyMap()
    }

    /**
     * 校验一个类的构造器委托链（阶段 18）。
     *
     * 三条规则：
     * 1. **有主构造 ⇒ 每个次构造必须以 `this(...)` 或 `super(...)` 开头**。这样链的根必然是
     *    主构造，字段初值里引用的主构造参数一定绑好值了。（Java 允许不委托的"根"次构造，
     *    但那样字段初值就可能读到不存在的主构造参数——Kotlin 用「必须委托」堵住了，
     *    我们照抄。阶段 25 起 `super(...)` 也算「委托」：它把父类那份状态先建起来，
     *    之后照样跑本类字段初值，所以主构造参数一样是有值的。）
     * 2. 委托**不能成环**（`A -> B -> A` 永远跑不完）。
     * 3. 目标必须是同一个类的另一个构造器（在 [checkThisCall] 里按实参选）。
     */
    private fun checkConstructorChain(info: ClassInfo) {
        val cls = info.decl.name
        val primary = info.primary
        for (ctor in info.constructors) {
            if (!ctor.isPrimary && primary != null && ctorDelegations[ctor] == null &&
                superDelegations[ctor] == null
            ) {
                throw err(
                    "类 '$cls' 有主构造（写在类头括号里），所以次构造必须委托给它：" +
                        "在 constructor(...) 体的**第一句**写 this(实参)（阶段 25 起也可以写 " +
                        "super(父类实参) 委托给父类）。" +
                        "否则字段初值里用到的主构造参数就没有值了",
                    ctor.at,
                )
            }
        }

        // 成环检测：从每个构造器沿委托边走，重复遇到同一个就说明有环
        for (start in info.constructors) {
            val seen = LinkedHashSet<FuncDecl>()
            var cur: FuncDecl? = start
            while (cur != null) {
                if (!seen.add(cur)) {
                    throw err(
                        "类 '$cls' 的构造器委托成环了：" +
                            seen.joinToString(" -> ") { describeCtor(info, it) } +
                            " -> ${describeCtor(info, cur)}",
                        cur.at,
                    )
                }
                cur = ctorDelegations[cur]?.target
            }
        }
    }

    /** 构造器的可读签名，报错用：`P(int x0, string s)`。 */
    private fun describeCtor(info: ClassInfo, ctor: FuncDecl): String {
        val types = methodParams[ctor].orEmpty()
        val parts = ctor.params.mapIndexed { i, p ->
            "${types.getOrNull(i)?.type?.rsfName ?: p.type.display()} ${p.name}"
        }
        return "${info.decl.name}(${parts.joinToString(", ")})"
    }

    /** 一个类所有构造器的可读签名，报错时列出来省得用户猜。`new` 可以省，所以只写 `类名(...)`。 */
    private fun describeCtors(info: ClassInfo): String =
        if (info.constructors.isEmpty()) {
            "${info.decl.name}()（这个类没写构造器，等价于一个无参主构造）"
        } else {
            info.constructors.joinToString("；") { describeCtor(info, it) }
        }

    /** 接收者的类型如果是一个用户自定义类，就返回它的信息（不是则 null）。 */
    private fun userClassOf(type: RsfType): ClassInfo? =
        // 数组要排除在外：`Point[]` 的 base/externalName 和 `Point` 一模一样，
        // 只比那两项的话 `ps.sum()` 会被当成「在 Point 上找方法」而放过去
        if (type.isExternal) classTable[type.externalName] else null

    /**
     * 检查一个构造点的构造器实参，并**挑出用哪个构造器**（阶段 18）。
     *
     * 返回选中的构造器；返回 null 表示这个类一个构造器都没写（隐式无参构造器，
     * 只有字段初值要跑）。挑的结果记进 [newCtors]，两个后端都读这一份——
     * 「选哪个构造器」在编译期就定死，否则解释器按运行期类型选、字节码按描述符发，
     * 两端可能选到不同的重载。
     *
     * [key] 是构造 AST 节点（[NewExpr] 或省掉 `new` 的 [CallExpr]）。
     */
    private fun checkConstructorArgs(
        info: ClassInfo,
        key: Expr,
        arguments: List<Expr>,
        subst: Map<String, RsfType>,
    ): FuncDecl? {
        val args = arguments
        val at = key.at

        // 没写任何构造器 ⇒ 隐式无参构造器
        if (info.constructors.isEmpty()) {
            if (args.isNotEmpty()) {
                throw err(
                    "类 '${info.decl.name}' 没写构造器，只能 ${info.decl.name}()，" +
                        "实际给了 ${args.size} 个实参。" +
                        "要有参数的构造器就写主构造（class ${info.decl.name}(参数) { ... }）" +
                        "或者次构造（constructor(参数) { ... }）；" +
                        "构造对象时 new 可以省，${info.decl.name}(...) 和 new ${info.decl.name}(...) 一样",
                    at,
                )
            }
            return null
        }

        val argTypes = ArrayList<RsfType>(args.size)
        for ((i, arg) in args.withIndex()) {
            val t = type(arg)
            if (t.isVoid) {
                throw err(
                    "构造 ${info.decl.name}(...) 的第 ${i + 1} 个实参是 void 表达式，不能当参数传",
                    arg.at,
                )
            }
            argTypes.add(t)
        }

        val chosen = resolveCtor(info, args, argTypes, subst, at)
        newCtors[key] = chosen
        return chosen
    }

    /**
     * 这个名字能不能当「类型」来构造：**用户自定义类**，或者导入（含 `java.lang.*`
     * 隐式可用）的 **JVM 类**。
     *
     * 这就是**自动 new**（`Point(1, 2)` 等价于 `new Point(1, 2)`）的全部判定依据：
     * 一个纯粹的名字解析问题，没有前瞻、没有猜。内置类型名（`int` / `string`…）
     * 不是类，`int(5)` 不会被当成构造。
     */
    private fun isConstructionName(name: String): Boolean =
        classTable.containsKey(name) || interfaceTable.containsKey(name) ||
            annotationTable.containsKey(name) || resolver.resolveClass(name) != null

    /**
     * **构造点**的公共实现：显式 `new X(...)` 与省掉 `new` 的 `X(...)` 都走这里。
     *
     * 两种写法因此共用同一段代码——类型、泛型元数检查、构造器挑选、实参检查、
     * 记进 [newTargets] / [externalNewTargets] 的结果全都一模一样，
     * 「两种写法完全等价」是**结构上**保证的，不靠两份代码对齐。
     *
     * 名字的解析顺序：**用户自定义类优先**（`classTable`），否则当成导入的
     * JVM 类（`resolver`，`java.lang.<名字>` 隐式可用）。两者都没有就是「没有这个类」。
     * 「类名与函数名不能重名」在 [check] 开头就报掉了，所以这里不用担心函数抢名字。
     */
    private fun checkConstruction(
        className: String,
        typeArgRefs: List<TypeRef>,
        arguments: List<Expr>,
        key: Expr,
    ): RsfType {
        val args = typeArgRefs.map { resolveType(it) }
        // 阶段 21：`new Marker(...)` 构造的是**注解的伴生实例**（方案 B 的使用面）——
        // 注解接口自己没有构造器，能构造的是它的 companion entity 合成的那个类。
        annotationTable[className]?.let { return checkCompanionConstruction(it, args, arguments, key) }
        // 接口不能 new（阶段 20，D3）：接口没有构造器，也就没有对象。
        // 和 JVM 接口那条拒绝（checkExternalConstructor）用同一句话，用户不用分辨
        // 「这个接口是我写的还是 Java 的」。
        if (interfaceTable.containsKey(className)) {
            throw err("'$className' 是接口，不能 new——接口没有构造器", key.at)
        }
        val info = classTable[className]
        if (info != null) {
            // 抽象类不能 new（阶段 25，DESIGN-25 §6）：它有没实现的抽象成员，
            // 造出来的对象连该跑哪个方法都不知道。
            if (info.decl.isAbstract) {
                val missing = unimplementedAbstractMembers(info, includeInterfaces = true)
                throw err(
                    "'$className' 是抽象类，不能 new——抽象类只能当父类，" +
                        "要用一个把抽象成员都实现的子类（abstract class $className）" +
                        if (missing.isEmpty()) {
                            ""
                        } else {
                            "。它还没实现的有：${missing.joinToString("、")}"
                        },
                    key.at,
                )
            }
            // 泛型用户类：先查元数，再按实参替换 T 之后检查构造器实参
            checkArity(className, info.decl.typeParams.size, args, key.at, info.decl.typeParams)
            val subst = substitutionFor(info, args)
            // 挑构造器（阶段 18：可能有好几个）；选中的那个记进 newCtors，
            // 两个后端都读这一份，选重载的结果不可能分叉
            checkConstructorArgs(info, key, arguments, subst)
            newTargets[key] = info.decl
            return info.type.withArgs(args)
        }

        val cls = resolver.resolveClass(className)
            ?: throw err(
                "没有这个类：'$className'" +
                    if (classTable.isNotEmpty()) {
                        "（已定义的类（${classTable.keys.joinToString(", ")}）"
                    } else {
                        "（这份程序里还没有任何 class 声明）"
                    },
                key.at,
            )
        cls.typeParamCount?.let { checkArity(cls.simpleName, it, args, key.at) }
        val ctor = checkExternalConstructor(cls, key, arguments, args)
        externalNewTargets[key] = ResolvedMethod(ctor)
        // 阶段 16b：`String(byte[])` 是**唯一**会落到这里的特例——
        // `java/lang/String` 在内建类型里就叫 `string`（它有专门的拼接/输出处理，
        // ClassPath.typeFromDescriptor 也是这么映射的）。要是这里原样给外部类类型，
        // `"转回来 = " + String(bytes)` 会走「外部类 → String.valueOf(...)」，
        // 生成 `String.valueOf(Ljava/lang/String;)Ljava/lang/String;` 这种不存在的重载，
        // 编译能过、运行期 NoSuchMethodError。归一成 string 之后两端行为一致
        // （解释器拿到的本来就是 java.lang.String）。
        return if (cls.internalName == "java/lang/String") {
            RsfType.STRING
        } else {
            RsfType.external(cls.internalName).withArgs(args)
        }
    }

    /**
     * `new Marker(实参…)`：构造注解的**伴生实例**（方案 B 的使用面，DESIGN-21 §1/§6）。
     *
     * 不走 [resolveCtor]：配对规则由**元素表**定（位置式按顺序、缺省值兜底），
     * 和「按类型打分选重载」不是一回事；逐个元素配对给出的报错也能精确到
     * 「第几个实参、是哪个元素、要什么类型」。
     *
     * 记进模型的是三样东西：`newTargets` = 合成伴生类（M3 按普通类 `new` 发射）、
     * `newCtors` = 那个构造器、`annotationNewTargets` = 注解规格（M4 的解释器要按
     * 元素的默认值补齐省略的实参）。
     */
    private fun checkCompanionConstruction(
        info: AnnotationInfo,
        args: List<RsfType>,
        arguments: List<Expr>,
        key: Expr,
    ): RsfType {
        val spec = info.spec ?: throw IllegalStateException("注解 '${info.decl.name}' 没有解析结果")
        checkArity(spec.name, 0, args, key.at)
        val companion = spec.companion
            ?: throw err(
                "注解 '${spec.name}' 没有 companion entity，不能 new：" +
                    "要带行为就给它加一个 companion entity 块",
                key.at,
            )
        // 具名实参只在**注解**位置开放（Parser 的收法），构造调用里只有位置式：
        // 一个实参对一个元素，多出来的、少了的都要说清是哪一个
        if (arguments.size > spec.elements.size) {
            throw err(
                "new ${spec.name}(...) 的实参个数不对：给了 ${arguments.size} 个，" +
                    "但它的元素只有 ${spec.elements.size} 个${spec.elementHint()}",
                arguments[spec.elements.size].at,
            )
        }
        for ((index, arg) in arguments.withIndex()) {
            val element = spec.elements[index]
            val got = type(arg)
            if (got == RsfType.VOID) {
                throw err(
                    "new ${spec.name}(...) 的第 ${index + 1} 个实参是 void 表达式，不能当参数传",
                    arg.at,
                )
            }
            if (!acceptsValue(element.type, arg, got)) {
                throw err(
                    "new ${spec.name}(...) 的第 ${index + 1} 个实参类型不对：" +
                        "元素 '${element.name}' 需要 ${element.type.rsfName}，实际给的是 ${got.rsfName}" +
                        "（${exprText(arg)}）",
                    arg.at,
                )
            }
        }
        for ((index, element) in spec.elements.withIndex()) {
            if (index >= arguments.size && !element.hasDefault) {
                throw err(
                    "new ${spec.name}(...) 少了元素 '${element.name}'：它没有默认值${spec.elementHint()}",
                    key.at,
                )
            }
        }
        newTargets[key] = companion.decl
        newCtors[key] = companion.constructor
        annotationNewTargets[key] = spec
        return RsfType.external(companion.jvmName)
    }

    /**
     * 按实参挑一个构造器（构造器重载）。
     *
     * 规则和 JVM 方法的重载选择一致：**先按个数筛**，再按类型打分（精确 0 分、
     * 能隐式转换 1 分、不兼容出局），总分最低者胜；同分取**声明在前**的那个
     * （主构造排在最前，所以同分时主构造优先——这是确定的，两端因此不会分叉）。
     */
    private fun resolveCtor(
        info: ClassInfo,
        args: List<Expr>,
        argTypes: List<RsfType>,
        subst: Map<String, RsfType>,
        at: SourceLine,
    ): FuncDecl {
        val sameArity = info.constructors.filter { it.params.size == argTypes.size }
        if (sameArity.isEmpty()) {
            throw err(
                "${info.decl.name}(...) 的参数个数不对：实际给了 ${args.size} 个，" +
                    "但可用的构造器是 —— " + describeCtors(info) +
                    "（构造对象 new 可省：${info.decl.name}(...) 与 new ${info.decl.name}(...) 等价）",
                at,
            )
        }

        var best: FuncDecl? = null
        var bestScore = Int.MAX_VALUE
        // 每个候选的「第一个对不上的实参」，用于只有一个候选时给出精确报错
        val firstFailure = HashMap<FuncDecl, String>()
        for (ctor in sameArity) {
            val declared = methodParams[ctor]?.map { it.type.substitute(subst) } ?: continue
            var score = 0
            var ok = true
            for (i in declared.indices) {
                val cost = ctorArgCost(declared[i], args[i], argTypes[i])
                if (cost == null) {
                    ok = false
                    firstFailure[ctor] =
                        "第 ${i + 1} 个实参类型不对：需要 ${declared[i].rsfName}，实际给人 ${argTypes[i].rsfName}"
                    break
                }
                score += cost
            }
            if (ok && score < bestScore) {
                bestScore = score
                best = ctor
            }
        }
        best?.let { return it }

        // 只有一个参数个数相同的候选时，说清是**哪一个实参**不对（这是最常见的情形，
        // 报「参数类型对不上，候选有…」反而绕）；有多个候选才列出来让用户自己挑。
        if (sameArity.size == 1) {
            firstFailure[sameArity[0]]?.let { throw err("${info.decl.name}(...) 的 $it", at) }
        }
        throw err(
            "${info.decl.name}(...) 的实参类型对不上任何构造器：" +
                "实际给的是 (" + argTypes.joinToString(", ") { it.rsfName } + ")，" +
                "可用的构造器是 —— " + describeCtors(info),
            at,
        )
    }

    /**
     * 一个实参对一个构造器形参的代价；null 表示不兼容（这个候选出局）。
     *
     * 和 [MemberResolver] 给 JVM 方法打分的口径一致：精确匹配 0 分，
     * 可隐式转换（加宽 / 字面量 / null 给引用类型）1 分。
     */
    private fun ctorArgCost(param: RsfType, arg: Expr, argType: RsfType): Int? {
        if (param == argType) return 0
        if (argType.isNullLiteral) return if (!param.isPrimitive) 1 else null
        if (acceptsValue(param, arg, argType)) return 1
        return null
    }

    // ------------------------------------------------------------------ 重载解析（阶段 19d）

    /**
     * 一个重载候选：选中的 `FuncDecl` + **这一处**的形参类型。
     *
     * 形参类型单独存：成员调用要先按接收者的类型实参把 `T` 换成实际类型
     * （`Box&<int>` 的 `set(T)` 期望的是 `int`），打分与报错都必须用换过之后的。
     */
    private class OverloadCandidate(val decl: FuncDecl, val params: List<RsfType>) {
        /** 报错里的可读签名：`f(a : int, b : string)`，与 [signatureOf] 同一个风格。 */
        val signature: String = decl.params.mapIndexed { i, p ->
            "${p.name} : ${params.getOrNull(i)?.rsfName ?: p.type.name}"
        }.let { "${decl.name}(${it.joinToString(", ")})" }

        /**
         * 阶段 31：泛型候选**按这一处实参推导出来的**类型实参绑定（`T` → `int`）。
         *
         * 非泛型候选永远是空表。泛型候选被选中时由 [resolveOverload] 填上：
         * 调用点要拿它把返回类型里的 `T` 换成实际类型，并记进
         * [SemanticModel.genericCalls] 给 31b 的后端插 cast。
         */
        var bindings: Map<String, RsfType> = emptyMap()

        /**
         * 泛型候选会以「实例化后的副本」参与打分（形参里的 `T` 已经换成具体类型），
         * 这个字段指回原来那个候选——调用点要按原件去找配对信息
         * （super 调用、接口默认方法的 `RequiredMethod` 都是按**引用**配对的）。
         */
        var origin: OverloadCandidate? = null
    }

    /** 泛型候选的实例化副本 → 原件；非泛型候选就是自己。 */
    private fun OverloadCandidate.original(): OverloadCandidate = origin ?: this

    /** 把候选签名列成一句人话；空表就什么都不说。 */
    private fun describeCandidates(candidates: List<OverloadCandidate>): String =
        if (candidates.isEmpty()) {
            ""
        } else {
            "候选有 ${candidates.size} 个 —— " + candidates.joinToString("；") { it.signature }
        }

    /**
     * 重载解析（顶层函数与类方法共用同一套机器）。
     *
     * 口径（用户拍板）：
     * 1. **先按实参个数筛**：个数不同的候选直接出局；
     * 2. 再按类型打分：**精确 0 分**；隐式加宽按**档位距离**计分
     *    （`byte → int` 1 分、`byte → long` 2 分、`int → double` 3 分…，
     *    所以「窄加宽」压过「宽加宽」）；`null` 字面量给引用类型、
     *    `0..127` 的整数字面量给 `byte` 各记 1 分；任何一项对不上，整个候选出局；
     * 3. 总分**最低**者胜；**最低分并列就报错**并列出候选签名，绝不静默取
     *    「声明在前」的那个。
     *
     * **泛型候选（阶段 31）**：非泛型候选优先——先照上面 1-3 在**非泛型**候选里挑，
     * 一个都没接住才轮到泛型候选（[bindGeneric] 按实参推导类型参数，再用实例化后的
     * 形参重新打分，还是同一把尺子）。所以 `f(int)` 永远赢过 `f&<T>(T)`，
     * 而 `f(1, 2)` 在有 `f&<T>(T, T)` 时也不必要求写类型实参（DESIGN-31 §5）。
     *
     * [candidates] 必须已经按实参个数筛过（调用方负责，这样「个数不对」的报错
     * 不必先给实参算类型，保持了老行为）。[mismatch] 在「只有一个同个数候选」时
     * 给出精确报错——顶层函数与成员方法的落款老文案不同，由调用方给。
     */
    private fun resolveOverload(
        what: String,
        candidates: List<OverloadCandidate>,
        args: List<Expr>,
        argTypes: List<RsfType>,
        at: SourceLine,
        mismatch: (OverloadCandidate, Int) -> String,
    ): OverloadCandidate {
        // 没有泛型候选就是老路——打分 → 并列报错 → 没接住报错，行为一个字都不变。
        val genericCandidates = candidates.filter { it.decl.typeParams.isNotEmpty() }
        if (genericCandidates.isEmpty()) {
            return decideOverload(
                what, candidates, scoreOverloads(candidates, args, argTypes), argTypes, at, mismatch,
            )
        }

        // 非泛型候选优先：只要有一个接住就直接用它，泛型候选连参与的机会都没有。
        // 非泛型之间并列也照旧报「无法确定用哪个重载」，不让泛型候选来"化解"。
        val plain = candidates.filter { it.decl.typeParams.isEmpty() }
        if (plain.isNotEmpty()) {
            val scoredPlain = scoreOverloads(plain, args, argTypes)
            if (scoredPlain.best != null) {
                return decideOverload(what, plain, scoredPlain, argTypes, at, mismatch)
            }
        }

        // 非泛型一个都没接住 → 泛型候选上场：先按实参推导类型参数，再用
        // **实例化之后**的形参正常打分（`T` 已经变成 `int`，加宽/空值规则照旧）。
        val attempts = genericCandidates.map { bindGeneric(it, argTypes, at) }
        val instantiated = ArrayList<OverloadCandidate>()
        for (b in attempts) {
            if (b !is GenericAttempt.Bound) continue
            val inst = OverloadCandidate(b.cand.decl, b.params)
            inst.bindings = b.bindings
            inst.origin = b.cand
            instantiated.add(inst)
        }
        var fallbackIndex = 0
        if (instantiated.isNotEmpty()) {
            val scored = scoreOverloads(instantiated, args, argTypes)
            if (scored.best != null) {
                return decideOverload(what, instantiated, scored, argTypes, at, mismatch)
            }
            instantiated.forEach { c -> scored.firstFailure[c]?.let { fallbackIndex = it } }
        }
        attempts.filterIsInstance<GenericAttempt.Mismatch>().firstOrNull()?.let { fallbackIndex = it.at }

        // 一个都没接住：**冲突**和**推不出来**都比「类型对不上」更接近真正的原因，
        // 优先报它们（同一种里按类型参数的声明顺序取第一个）。
        attempts.filterIsInstance<GenericAttempt.Conflict>().minByOrNull { it.order }?.let {
            throw err(
                "类型参数 '${it.name}' 同时被要求是 '${it.first.rsfName}' 和 '${it.second.rsfName}'" +
                    "（来自第 ${it.firstArg} 个和第 ${it.secondArg} 个实参）",
                at,
            )
        }
        attempts.filterIsInstance<GenericAttempt.Unbound>().minByOrNull { it.order }?.let {
            throw err(
                "类型参数 '${it.name}' 推不出来：它只出现在返回类型里，" +
                    "让它出现在某个参数类型里（例如 'fn f(x : T) -> T'）",
                at,
            )
        }

        // 推是推出来了（或者根本推不动），但实参对不上：按今天的老口径报类型不匹配。
        val single = candidates.singleOrNull()
        if (single != null) {
            throw err(mismatch(instantiated.firstOrNull() ?: single, fallbackIndex), at)
        }
        throw err(
            "$what 的实参类型对不上任何候选：" +
                "实际给的是 (${argTypes.joinToString(", ") { it.rsfName }})，" +
                describeCandidates(candidates),
            at,
        )
    }

    /**
     * 把**函数级**类型参数从类的替换表里摘掉（阶段 31）。
     *
     * 函数级类型参数遮蔽同名的类级类型参数（DESIGN-31 §3）：`class Box&<T>` 里的
     * `fn first&<T>(xs : T[])` —— 这里的 `T` 是**方法的**，不是类的。候选形参如果先被
     * 类级替换表换成 `int`，推导就再也看不到占位符了，所以先摘掉再代换。
     */
    private fun memberSubstFor(m: FuncDecl, subst: Map<String, RsfType>): Map<String, RsfType> =
        if (m.typeParams.isEmpty()) subst else subst.filterKeys { it !in m.typeParams }

    /**
     * 选中候选之后算调用表达式的类型：返回类型里的类型参数按**绑定表**代换，
     * 并顺手把这一处的绑定记进 [genericCalls]（31b 的后端要在调用点插 cast）。
     *
     * [declared] 是调用点已经按类级实参代换过的返回类型；非泛型候选原样返回。
     */
    private fun instantiateReturn(expr: Expr, chosen: OverloadCandidate, declared: RsfType): RsfType {
        val out = if (chosen.bindings.isEmpty()) declared else declared.substitute(chosen.bindings)
        if (chosen.decl.typeParams.isNotEmpty()) {
            genericCalls[expr] = GenericCallSite(chosen.decl.typeParams, chosen.bindings, out)
        }
        return if (out.isVoid) RsfType.VOID else out
    }

    /** 一次泛型推导的结果（阶段 31，DESIGN-31 §4）。 */
    private sealed interface GenericAttempt {
        /** 推成功了：[bindings] 每个类型参数都有值，[params] 是代换之后的形参类型。 */
        class Bound(
            val cand: OverloadCandidate,
            val bindings: Map<String, RsfType>,
            val params: List<RsfType>,
        ) : GenericAttempt

        /** 同一个类型参数被两个实参要求成两个不同的类型。 */
        class Conflict(
            /** 在 `decl.typeParams` 里的下标：多个冲突时按声明顺序报第一个。 */
            val order: Int,
            val name: String,
            val first: RsfType,
            val second: RsfType,
            val firstArg: Int,
            val secondArg: Int,
        ) : GenericAttempt

        /** 走完实参表还有类型参数没被绑上（只出现在返回类型里）。 */
        class Unbound(val order: Int, val name: String) : GenericAttempt

        /** 推出来了（或压根不用推），但具体形参对不上实参——交给普通的重载报错。 */
        class Mismatch(val cand: OverloadCandidate, val at: Int) : GenericAttempt
    }

    /** 推导的一步：对齐一对「形参 / 实参类型」。 */
    private sealed interface BindStep {
        /** 绑上了，或者这一对里没有要绑的东西。 */
        object Ok : BindStep

        /** 实参是 `null` 字面量，提供不了类型信息。 */
        object Skip : BindStep

        /** 结构对不上（`T[]` 收了非数组、`Box&<T>` 收了 `List&<int>`…）。 */
        object Mismatch : BindStep

        class Conflict(
            val name: String,
            val first: RsfType,
            val second: RsfType,
            val firstArg: Int,
            val secondArg: Int,
        ) : BindStep
    }

    /**
     * 按实参推导一个泛型候选的类型参数（DESIGN-31 §4：**只看实参**、按声明顺序）。
     *
     * 冲突与「推不出来」都只**记下来**、不立刻抛：多个泛型候选里可能有一个是好的，
     * 只有全都没接住时才把最贴近原因的报出去（见 [resolveOverload]）。
     */
    private fun bindGeneric(
        c: OverloadCandidate,
        argTypes: List<RsfType>,
        at: SourceLine,
    ): GenericAttempt {
        val params = c.params
        if (params.size != argTypes.size) return GenericAttempt.Mismatch(c, 0)
        val bindings = LinkedHashMap<String, RsfType>()
        val origin = HashMap<String, Int>()
        var nullAt = -1
        for (i in params.indices) {
            when (val step = bindTypeParamPattern(params[i], argTypes[i], bindings, origin, i + 1, at)) {
                BindStep.Ok -> Unit
                BindStep.Skip -> if (nullAt < 0) nullAt = i
                BindStep.Mismatch -> return GenericAttempt.Mismatch(c, i)
                is BindStep.Conflict -> return GenericAttempt.Conflict(
                    c.decl.typeParams.indexOf(step.name), step.name,
                    step.first, step.second, step.firstArg, step.secondArg,
                )
            }
        }
        for ((order, name) in c.decl.typeParams.withIndex()) {
            if (name in bindings) continue
            // 之所以没绑上是被 `null` 字面量挡住的——那属于「实参类型对不上」，
            // 报「推不出来（它只出现在返回类型里）」会误导人。
            return if (nullAt >= 0) {
                GenericAttempt.Mismatch(c, nullAt)
            } else {
                GenericAttempt.Unbound(order, name)
            }
        }
        return GenericAttempt.Bound(c, bindings, params.map { it.substitute(bindings) })
    }

    /**
     * 递归对齐「形参里的类型参数」与「实参类型」，只负责**绑定**；
     * 能不能真传（可空、加宽）由实例化之后的 [argCost] 说了算。
     */
    private fun bindTypeParamPattern(
        param: RsfType,
        arg: RsfType,
        bindings: MutableMap<String, RsfType>,
        origin: MutableMap<String, Int>,
        argIndex: Int,
        at: SourceLine,
    ): BindStep {
        // `null` 提供不了任何类型信息（它给任何可空类型都成立）：不绑、也不算冲突。
        if (arg.isNullLiteral) return BindStep.Skip

        // `T[]` ↔ `X[]`：数组维度逐层剥离。可空性属于「这一层数组」，不带给元素
        // （`?int[]` 的元素是 `int`，不是 `?int`）。
        if (param.arrayDepth > 0) {
            if (arg.arrayDepth <= 0) return BindStep.Mismatch
            // 阶段 31b：`T[]` 在 JVM 上擦成 `[Ljava/lang/Object;`，而 `int[]` 是 `[I`，
            // 两者不是子类型关系（`int[]` 装不进 `Object[]`）——真按这个绑定发码，类加载
            // 就会 VerifyError。解释器那边能跑，两端就分叉了，所以在这里直接拒。
            val elemParam = param.elementType.asNonNull()
            val elemArg = arg.elementType.asNonNull()
            val name = elemParam.typeParamName
            if (name != null && elemArg.isPrimitive) {
                throw err(
                    "泛型参数 '$name' 绑到了基本类型 '${elemArg.rsfName}'：" +
                        "'$name[]' 在 JVM 上擦成 Object[]，装不下基本类型数组（DESIGN-31 §8）" +
                        "——把实参换成引用元素数组（例如 string[] 或类数组）",
                    at,
                )
            }
            return bindTypeParamPattern(
                elemParam, elemArg, bindings, origin, argIndex, at,
            )
        }

        // `T` / `?T`：`?T` 连非空实参也收，绑的是脱掉可空之后的类型（DESIGN-31 §4）。
        val name = param.typeParamName
        if (name != null) {
            val actual = if (param.nullable) arg.asNonNull() else arg
            val prev = bindings[name]
            if (prev == null) {
                bindings[name] = actual
                origin[name] = argIndex
                return BindStep.Ok
            }
            return if (prev == actual) {
                BindStep.Ok
            } else {
                BindStep.Conflict(name, prev, actual, origin[name] ?: argIndex, argIndex)
            }
        }

        // `Box&<T>` ↔ `Box&<int>`：同基名、类型实参数目一致、逐个递归。
        if (param.typeArgs.isNotEmpty()) {
            if (param.base != arg.base || param.externalName != arg.externalName) return BindStep.Mismatch
            if (param.typeArgs.size != arg.typeArgs.size) return BindStep.Mismatch
            for (k in param.typeArgs.indices) {
                val step = bindTypeParamPattern(param.typeArgs[k], arg.typeArgs[k], bindings, origin, argIndex, at)
                if (step != BindStep.Ok) return step
            }
        }
        // 具体类型（`int`、`string`、`Box&<int>` 里的 int）：这里没有要绑的东西。
        return BindStep.Ok
    }

    /** [scoreOverloads] 的结果：谁最低分、谁并列、谁在第几个实参上最先出局。 */
    private class OverloadScore(
        val best: OverloadCandidate?,
        val bestScore: Int,
        /** 并列最低分的候选（含 [best] 自己）；`size > 1` 就是并列。 */
        val tied: List<OverloadCandidate>,
        val firstFailure: Map<OverloadCandidate, Int>,
    )

    /**
     * 按实参给候选打分（DESIGN-19d §2 的档位表），只算不抛错。
     *
     * 抽出来是为了让泛型候选能拿**实例化之后**的形参再来一遍同一套打分：
     * 「先推导、再打分」两步用的必须是同一把尺子。
     */
    private fun scoreOverloads(
        candidates: List<OverloadCandidate>,
        args: List<Expr>,
        argTypes: List<RsfType>,
    ): OverloadScore {
        var best: OverloadCandidate? = null
        var bestScore = Int.MAX_VALUE
        val tied = ArrayList<OverloadCandidate>()
        // 每个出局候选「第一个对不上的实参」下标，用于只有一个候选时给出精确报错
        val firstFailure = HashMap<OverloadCandidate, Int>()

        for (c in candidates) {
            var score = 0
            var failedAt = -1
            for (i in c.params.indices) {
                val cost = argCost(c.params[i], args[i], argTypes[i])
                if (cost == null) {
                    failedAt = i
                    break
                }
                score += cost
            }
            if (failedAt >= 0) {
                firstFailure[c] = failedAt
                continue
            }
            if (score < bestScore) {
                bestScore = score
                best = c
                tied.clear()
                tied.add(c)
            } else if (score == bestScore) {
                tied.add(c)
            }
        }

        return OverloadScore(best, bestScore, tied, firstFailure)
    }

    /** 打分结果落地：最低分者胜、并列报错、一个都没接住时报「类型不匹配」。 */
    private fun decideOverload(
        what: String,
        candidates: List<OverloadCandidate>,
        scored: OverloadScore,
        argTypes: List<RsfType>,
        at: SourceLine,
        mismatch: (OverloadCandidate, Int) -> String,
    ): OverloadCandidate {
        val chosen = scored.best
        if (chosen != null) {
            if (scored.tied.size > 1) {
                throw err(
                    "$what 无法确定用哪个重载：实参类型（${argTypes.joinToString(", ") { it.rsfName }}）" +
                        "下有 ${scored.tied.size} 个候选并列最低分（${scored.bestScore} 分）—— " +
                        describeCandidates(scored.tied) +
                        "。rsf 不替你按声明顺序挑一个：把实参写成与其中一个候选**完全一致**的类型" +
                        "（必要时先赋给一个显式标注了类型的局部变量）再调一次",
                    at,
                )
            }
            return chosen
        }

        // 一个候选都没接住
        if (candidates.size == 1) {
            scored.firstFailure[candidates[0]]?.let { i ->
                throw err(mismatch(candidates[0], i), at)
            }
        }
        throw err(
            "$what 的实参类型对不上任何候选：" +
                "实际给的是 (${argTypes.joinToString(", ") { it.rsfName }})，" +
                describeCandidates(candidates),
            at,
        )
    }

    /**
     * 一个实参对一个形参的代价；null 表示不兼容（这个候选出局）。
     *
     * 与构造器的 [ctorArgCost] **故意不同**：那里加宽一律记 1 分（同分取声明在前），
     * 这里按**档位距离**计分，好让「窄加宽」压过「宽加宽」：
     * `f(int)` 与 `f(long)` 同时存在时，`byte` 实参选 `f(int)`（1 分 < 2 分）。
     *
     * 档位表：`byte` 0 < `int` 1 < `long` 2 < `float` 3 < `double` 4。
     * `char` **不在表里**——它跟谁都不能隐式转（见 RsfType.kt 的加宽表）。
     */
    private fun argCost(param: RsfType, arg: Expr, argType: RsfType): Int? {
        if (param == argType) return 0
        if (argType.isNullLiteral) return if (!param.isPrimitive) 1 else null
        // 数值之间只有「加宽」这一条路（窄化、byte ↔ char 一律不给）
        if (param.isArithmetic && argType.isArithmetic) {
            widenDistance(argType.base, param.base)?.let { return it }
            // 字面量特例：0..127 的整数字面量可以直接给 byte（`var b : byte = 5` 的同一条规矩）
            return if (param.base == RsfBase.BYTE && byteRangeLiteral(arg) != null) 1 else null
        }
        return if (acceptsValue(param, arg, argType)) 1 else null
    }

    /** 加宽的档位距离：`int → long` 1 分、`int → double` 3 分。不能加宽（含 char）返回 null。 */
    private fun widenDistance(from: RsfBase, to: RsfBase): Int? {
        val a = widenLadder(from) ?: return null
        val b = widenLadder(to) ?: return null
        return if (b > a) b - a else null
    }

    /** 隐式加宽阶梯上的档位；`char` 与 `bool` 不在阶梯上（null）。 */
    private fun widenLadder(base: RsfBase): Int? = when (base) {
        RsfBase.BYTE -> 0
        RsfBase.INT -> 1
        RsfBase.LONG -> 2
        RsfBase.FLOAT -> 3
        RsfBase.DOUBLE -> 4
        else -> null
    }

    /**
     * 构造**导入的 JVM 类**（`new ArrayList&<int>()`，或者省掉 `new` 的
     * `ArrayList&<int>()`）。
     *
     * 构造器的签名从 class 文件里读（`<init>`），选重载的规则和普通方法一样。
     * 抽象类 / 接口不能构造——解释器会在反射时抛 InstantiationException、
     * 字节码那边会生成一个 INVOKESPECIAL 到没有实现的 `<init>`，两边都不好看，
     * 所以在这里就拦住。
     */
    private fun checkExternalConstructor(
        cls: JvmClass,
        key: Expr,
        arguments: List<Expr>,
        typeArgs: List<RsfType>,
    ): JvmMethod {
        if (cls.isInterface) {
            throw err("'${cls.simpleName}' 是接口，不能 new——接口没有构造器", key.at)
        }
        if (cls.isAbstract) {
            throw err("'${cls.simpleName}' 是抽象类，不能 new——得用它具体的子类", key.at)
        }

        val argTypes = arguments.map { type(it) }
        val element = singleTypeArg(typeArgs)
        // 泛型实参不参与描述符（JVM 是擦除的），但基本类型实参要按装箱去匹配
        // Java 的 `add(Object)` / `<init>(Object)` 这类形参。**只要写了类型实参就装箱**：
        // 单实参（`ArrayList&<int>`）与多实参（`HashMap&<string, int>`）一样。
        // 先按**原类型**试一遍（`new ArrayList&<string>(16)` 要挑到 `<init>(int)`，
        // Java 也是这么挑的），挑不到再按装箱后的形态试（那时形参是擦除后的 Object）。
        val lookup = if (typeArgs.isNotEmpty()) argTypes.map { boxForInterop(it) } else argTypes
        val ctor = (resolver.findConstructor(cls, argTypes) ?: resolver.findConstructor(cls, lookup))
            ?: throw err(
                "'${cls.simpleName}' 里没有匹配的构造器（实参：${argTypes.joinToString(", ") { it.rsfName }}）" +
                    if (cls.constructors.isNotEmpty()) {
                        "。可用的有：" + resolver.constructorsOf(cls).joinToString("；")
                    } else {
                        ""
                    },
                key.at,
            )

        val declared = ctor.paramTypes.mapIndexed { i, p ->
            erasedActual(p, ctor.paramTypeVars.getOrNull(i), typeArgs) ?: substituteElement(p, element)
        }
        for ((i, arg) in arguments.withIndex()) {
            val argType = argTypes[i]
            if (!acceptsValue(declared[i], arg, argType)) {
                throw err(
                    "new ${cls.simpleName}(...) 的第 ${i + 1} 个实参类型不对：" +
                        "需要 ${declared[i].rsfName}，实际给人 ${argType.rsfName}",
                    arg.at,
                )
            }
        }
        return ctor
    }

    /**
     * 给字段赋值（`p.x = ...`、`this.x = ...`，以及方法体里直接写 `x = ...`）。
     *
     * 三条规则：字段得存在、得是可变的、类型得对得上。
     */
    private fun checkFieldWrite(
        info: ClassInfo,
        field: FieldDecl,
        value: Expr,
        at: SourceLine,
        subst: Map<String, RsfType> = emptyMap(),
    ) {
        if (!field.mutable) {
            throw err(
                "字段 '${field.name}' 是 imutvar（不可变），声明时就定了值，不能重新赋值",
                at,
            )
        }
        val declared = fieldMember(info, field, subst).type
        // 阶段 30：字段是函数类型时，`this.f = lam(x) …` 也能借到形参类型
        val valueType = withExpectation(declared.takeIf { it.isFunctionType }) { type(value) }
        if (!acceptsValue(declared, value, valueType)) {
            throw err(
                "不能把 ${valueType.rsfName} 赋给 ${declared.rsfName} 字段 '${field.name}'",
                at,
            )
        }
    }

    /**
     * 用户类的成员访问（字段或方法）。
     *
     * [subst] 是泛型实参替换表：`b.set(7)` 里 `b` 是 `Box&<int>`，所以方法签名里的
     * `T` 要换成 `int`——实参检查、返回类型都必须按换过之后的算。
     */
    /**
     * 用户类沿父类链找到的**第一个 JDK 父类**（阶段 25 K1，G5）。
     *
     * `class MyError(msg : string) : Exception(msg)` 的这条链是 MyError →（rsf 层没了）
     * → `java/lang/Exception`；也可能隔着几层 rsf 父类才碰到 JDK 类。
     */
    private fun jvmParentOf(info: ClassInfo): JvmClass? {
        var cur: ClassInfo? = info
        while (cur != null) {
            val here: ClassInfo = cur
            classParentJvm[here.decl]?.let { return it }
            cur = parentOf(here)
        }
        return null
    }

    /**
     * 在**JDK 父类链**上找实例方法（阶段 25 K1，G5）。
     *
     * `class MyError : Exception` 里写 `getMessage()`：它声明在 `java/lang/Throwable`
     * 上，rsf 的类型表里根本没有这个名字，只能交给 [MemberResolver]（它自己会沿
     * JVM 的父类链走，`JvmMethod.owner` 就是声明它的那个类）。
     *
     * 找的是**实例方法**：JDK 的 static 方法一样不继承（DESIGN-25 §3，只能写
     * `类名.方法(...)`）。
     */
    private fun inheritedJvmMethod(
        info: ClassInfo,
        name: String,
        argTypes: List<RsfType>,
        at: SourceLine,
    ): JvmMethod? {
        val jvm = jvmParentOf(info) ?: return null
        val found = resolver.findMethod(jvm.internalName, name, argTypes, wantStatic = false)
            ?: if (argTypes.isNotEmpty()) {
                // 和 `接收者.成员` 那条路一致：基本类型实参再按装箱形态试一次
                resolver.findMethod(jvm.internalName, name, argTypes.map { boxForInterop(it) }, wantStatic = false)
            } else {
                null
            }
        return found?.let { objectMemberGate(info, it, at) }
    }

    /**
     * **`java/lang/Object` 的方法除了 rsf 自己定义语义的 `toString` / `equals` /
     * `hashCode`，不得经 JDK 父链漏进用户类**（阶段 25 K4b）。
     *
     * 这三个名字两端能一致：字节码侧发的是 `invokevirtual <声明类>.<名字>`，运行期接收者
     * 是 rsf 生成的类——它总是带着 rsf 生成的 `toString()`（ObjectFormat 渲染），
     * `equals` 与 `hashCode` 落在 `java/lang/Object` 的同一性实现上；解释器侧由
     * `Interpreter.rsfObjectFamily` 按同一套语义算。
     *
     * 其余的一律拦住：
     * - `wait` / `notify` / `notifyAll` / `clone` / `finalize`：解释器手上只有配对出来的
     *   JDK 父类实例，反射调它们要么打错真身、要么因 protected 直接抛 Java 异常，
     *   索性按「查不到」处理（写 `xs.wait()` 会照旧报「没有方法」）。
     * - `getClass()`：**两端拿不到同一个 `Class` 对象**——解释器里的对象是
     *   `RsfObject`（类名 + 字段平数组），配出来的只有 JDK 父类实例，答出来是
     *   `class java.lang.Exception`；字节码那边答的是 rsf 自己生成的类。这种分歧必须
     *   在编译期拦住，而不是让它悄悄漏过去（`build/verify25/probes/n17`）。
     */
    private fun objectMemberGate(info: ClassInfo, m: JvmMethod, at: SourceLine): JvmMethod? {
        if (m.owner != "java/lang/Object") return m
        when (m.name) {
            "toString" -> if (m.paramTypes.isEmpty()) return m
            "equals" -> if (m.paramTypes.size == 1) return m
            "hashCode" -> if (m.paramTypes.isEmpty()) return m
            "getClass" -> throw err(
                "类 '${info.decl.name}' 上调不到 'getClass()'：rsf 的类在解释器里没有真实的 Class 对象" +
                    "（一个对象就是「类名 + 字段平数组」，从 JDK 父类配出来的也只是那个父类的实例），" +
                    "字节码那边 a.getClass() 拿到的是它自己那个生成的类——两端拿不到同一个 Class。" +
                    "要类名就在类里写一个返回它的方法（阶段 25 K4b）",
                at,
            )
        }
        return null
    }

    /** JDK 父类链上的实例字段（G5）；没有就是 null。 */
    private fun inheritedJvmField(info: ClassInfo, name: String): JvmField? {
        val jvm = jvmParentOf(info) ?: return null
        return resolver.findField(jvm.internalName, name, wantStatic = false)
    }

    /**
     * 类链上叫 [name] 的**静态**方法声明在哪里（自己优先，然后沿 rsf 父链由近到远）。
     *
     * 阶段 25 K4（缺口 F）：static 不继承，子类里查不到它——这正是
     * `class Child : Base` 写 `Child.make()` 时要拦住的那种调用。
     * 光报「没有方法 'make'」会跟成员提示（提示里正好列着 `make`）自相矛盾，
     * 所以这里把声明处找出来，好把话说清楚（DESIGN-25 §3）。
     */
    private fun staticOnChain(info: ClassInfo, name: String): Pair<ClassInfo, FuncDecl>? {
        info.methods[name]?.firstOrNull { it.isStatic }?.let { return info to it }
        for (anc in ancestorsOf(info).reversed()) {
            anc.methods[name]?.firstOrNull { it.isStatic }?.let { return anc to it }
        }
        return null
    }

    private fun checkUserMember(
        info: ClassInfo,
        expr: MemberExpr,
        wantStatic: Boolean,
        subst: Map<String, RsfType> = emptyMap(),
    ): RsfType {
        val who = "类 '${info.decl.name}'"

        if (expr.arguments == null) {
            // 阶段 25：字段沿**父类链**找（字段是继承来的）。记的 owner 是**声明它的类**，
            // 下标是烘好的绝对下标（见 fieldMember）——两端只认这一份布局。
            val found = lookupField(info, expr.name)
            if (found == null) {
                // 阶段 25 K1（G5）：字段可能是**JDK 父类**继承来的
                // （`class MyError : Exception` 里的 `detailMessage` 那类）。
                val inherited = inheritedJvmField(info, expr.name)
                if (inherited != null) {
                    memberTargets[expr] = ResolvedField(inherited)
                    return inherited.type
                }
                throw err(
                    "$who 里没有字段 '${expr.name}'" + memberNamesHint(info, fields = true),
                    expr.at,
                )
            }
            val (owner, field) = found
            checkMemberVisible(owner, expr.name, expr.at)
            // 字段声明在祖先上时，要把接收者的实参一路代换到**声明类**那一层（G4）：
            // `class IntHolder : Holder&<int>` 的 `h.v` 是 int，不是 Holder 的 T。
            val member = fieldMember(info, field, substitutionAlongChain(info, subst, owner))
            userMembers[expr] = member
            return member.type
        }

        // 阶段 25：方法也沿**父类链**找。合并同一个签名时**近的类优先**——那就是 JVM
        // 虚分派的结果。父类的 static / private 方法**不继承**（DESIGN-25 §3：
        // static 只能写「类名.成员」），所以在父类那一层先滤掉。
        val chainNamed = LinkedHashMap<String, Pair<ClassInfo, FuncDecl>>()
        info.methods[expr.name]?.forEach { m ->
            chainNamed.putIfAbsent(signatureKey(m.name, paramTypesOfDecl(m)), info to m)
        }
        for (anc in ancestorsOf(info).reversed()) {
            anc.methods[expr.name]?.forEach { m ->
                if (m.isStatic || m.control == ControlType.PRIVATE) return@forEach
                chainNamed.putIfAbsent(signatureKey(m.name, paramTypesOfDecl(m)), anc to m)
            }
        }
        if (chainNamed.isEmpty()) {
            // 阶段 25 K1（G5）：类链上没有这个名字，但**JDK 父类**上可能有
            // （`class MyError : Exception` 里的 `getMessage()`）。JVM 的规矩是
            // 父类的方法压过接口的默认方法，所以这一支排在默认方法前面。
            if (!wantStatic) {
                val jvm = inheritedJvmMethod(info, expr.name, expr.arguments.map { type(it) }, expr.at)
                if (jvm != null) {
                    memberTargets[expr] = ResolvedMethod(jvm, ClassPath.find(jvm.owner)?.isInterface == true)
                    return jvm.returnType
                }
            }
            // 类链上没有这个名字，**不代表没有**：接口的默认方法是被实现类
            // 继承的（`var d : Dog = new Dog(); d.shout()`，shout 写在 Greeter 里带体）。
            // 静态调用不走这条路——接口方法都是实例方法。
            if (!wantStatic) checkInheritedInterfaceDefault(info, expr, subst)?.let { return it }
            // 阶段 25 K4（缺口 F）：名字是类链上某个**静态**方法时，别报「没有方法」+
            // 成员提示（提示里正好列着这个名字，读起来自相矛盾）——static 不继承，
            // 只能写在**声明它的那个类**上（DESIGN-25 §3）。
            staticOnChain(info, expr.name)?.let { (owner, _) ->
                val where = if (owner === info) "本类" else "类 '${owner.decl.name}'"
                throw err(
                    "$who 里没有方法 '${expr.name}'：'${expr.name}' 是${where}的**静态**方法，" +
                        "stc 不继承——子类里没有它，要写 '${owner.decl.name}.${expr.name}(...)'" +
                        "（DESIGN-25 §3）",
                    expr.at,
                )
            }
            throw err(
                "$who 里没有方法 '${expr.name}'" + memberNamesHint(info, fields = false),
                expr.at,
            )
        }

        // 候选先按**可见性**过滤，再按 static/实例的一致性过滤，最后才打分。
        // 顺序是有意的：一个名字下既有 private 又有 public 重载时，外面调得动的
        // 只有 public 那个；全都调不动才报 private（沿用老文案）。
        val visible = chainNamed.values.filter { (owner, m) -> isMemberVisible(owner, m) }
        if (visible.isEmpty()) {
            // 走到这里只有一种情况：候选全是**父类**的 private 方法（自己的 private
            // 方法在类内部是可见的，那是上一支）。沿用老文案，再点明「不继承」。
            checkMemberVisible(info, expr.name, expr.at)
            val owner = chainNamed.values.first { (_, m) -> m.control == ControlType.PRIVATE }.first
            throw err(
                "'${expr.name}' 是 '${owner.decl.name}' 的 prv 方法，子类继承不到它" +
                    "（prv 不参与继承，DESIGN-25 §3）",
                expr.at,
            )
        }
        val usable = visible.filter { (_, m) -> m.isStatic == wantStatic }
        if (usable.isEmpty()) {
            val (owner, first) = visible[0]
            val fix = if (first.isStatic) {
                "它是 stc 的，要用类名调：${owner.decl.name}.${expr.name}(...)"
            } else {
                "它不是 stc 的，要先有对象：对象.${expr.name}(...)"
            }
            throw err("'${expr.name}' 的调用方式不对——$fix", expr.at)
        }

        // 形参类型也要按**声明它的那个类**那一层代换：继承来的 `get() -> T`
        // 在 `class IntHolder : Holder&<int>` 上必须变成 `get() -> int`（G4）。
        val candidates = usable.map { (owner, m) ->
            val ownerSubst = substitutionAlongChain(info, subst, owner)
            OverloadCandidate(m, memberParamTypes(m).map { t -> t.substitute(memberSubstFor(m, ownerSubst)) })
        }
        val sameArity = candidates.filter { it.params.size == expr.arguments.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用 '${expr.name}' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }
        val argTypes = expr.arguments.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = expr.arguments,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }

        val method = chosen.decl
        // owner 取**声明它的类**：继承来的方法记父类名，后端据此发 invokevirtual
        val declarer = usable.first { (_, m) -> m === method }.first
        userMembers[expr] = UserMember.Method(declarer.decl.name, method)
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        // 返回类型同样要按声明类那一层代换（G4）；泛型方法再叠一层这一处的绑定。
        return instantiateReturn(
            expr, chosen,
            memberReturnType(method).substitute(memberSubstFor(method, substitutionAlongChain(info, subst, declarer))),
        )
    }

    /**
     * 类自己的方法表里没有这个名字时，沿**接口链**找继承来的默认方法（阶段 20）。
     *
     * `var d : Dog = new Dog(); d.shout()` 里 `shout` 可以只写在 `Greeter` 里（带体）：
     * JVM 上默认方法是被实现类**继承**的，所以类类型的接收者也调得到它。找不到就返回
     * null，让 [checkUserMember] 保持原来那句「类 'X' 里没有方法 'y'」的文案与行为。
     *
     * 记的归属和 [checkInterfaceMember] 完全一样（`interfaceOwner = true`、className 取
     * **声明它的那个接口**）——后端据此发 `invokeinterface`，而 `invokeinterface` 的
     * owner 必须是声明它的接口（JVM 的解析规则）。接收者静态类型写的是类还是接口，
     * 对这条记录没有影响：运行期都是按真实类虚分派。
     *
     * 只找**有体**的：抽象方法要求实现类自己给，类里没写就是真没有（那是 [checkClassCovers]
     * 已经报过的错），在这里兜住反而会把「没实现」这种错误的报错地点挪走。
     *
     * [subst] 是**接收者**的类型实参替换表（`Box&<int>` 的 `T`）：接口那一层的实参
     * 由 [collectRsfRequired] 换过一遍，这里再换一次是因为类自己也可能是泛型的
     * （`class C&<T> : Box&<T>` 而接收者是 `C&<int>`）。
     */
    private fun checkInheritedInterfaceDefault(
        info: ClassInfo,
        expr: MemberExpr,
        subst: Map<String, RsfType>,
    ): RsfType? {
        val args = expr.arguments ?: return null
        val supers = classSuperRefs[info.decl].orEmpty()
        if (supers.isEmpty()) return null

        // 复用实现配对那套收集：它已经处理了「沿父接口传递」与「把接口的类型实参
        // 替进签名」，而且是按**签名键**去重的（菱形继承不会看到两份一模一样的候选）。
        val required = LinkedHashMap<String, MutableList<RequiredMethod>>()
        val seenIface = HashSet<String>()
        for (s in supers) {
            if (s is SuperRef.Rsf) collectRsfRequired(s.info, s.args, required, seenIface)
        }

        val defaults = required.values.flatten().filter {
            it.decl != null && it.concrete && it.name == expr.name
        }
        val pickable = defaults.map { rm ->
            rm to OverloadCandidate(rm.decl!!, rm.params.map { p -> p.substitute(memberSubstFor(rm.decl!!, subst)) })
        }
        val sameArity = pickable.filter { it.second.params.size == args.size }
        if (sameArity.isEmpty()) return null

        val argTypes = args.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity.map { it.second },
            args = args, argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }

        val wanted = pickable.first { it.second === chosen.original() }.first
        val method = wanted.decl!!
        userMembers[expr] = UserMember.Method(
            className = interfaceOwnerTable[method]?.name ?: wanted.ownerName,
            decl = method,
            interfaceOwner = true,
        )
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        // 返回类型取**替换过接口实参**的那一份（`fun get() -> T` 在 `Box&<int>` 上是 int）
        return instantiateReturn(expr, chosen, wanted.ret.substitute(memberSubstFor(method, subst)))
    }

    /** 「第 N 个实参类型不匹配」的成员版落款（沿用阶段 18 的老文案，只是形参类型可能是替换过的）。 */
    private fun memberMismatch(
        name: String,
        c: OverloadCandidate,
        i: Int,
        got: RsfType,
    ): String =
        "第 ${i + 1} 个实参类型不匹配：'$name' 需要 ${c.params[i].rsfName}，实际给了 ${got.rsfName}"

    /** 这个方法在**当前上下文**里可见吗（private 只在声明它的类内部可见）。 */
    private fun isMemberVisible(info: ClassInfo, m: FuncDecl): Boolean =
        m.control != ControlType.PRIVATE || currentClass === info

    /**
     * 「（有：a, b, c）」这句提示（报错里列出现成的名字）。
     *
     * 阶段 25 起**把继承来的成员也算进去**：父类的字段/方法在子类里是能直接用的，
     * 报「没有这个成员」时把它们藏起来只会让用户多猜一轮。自己声明的排在前面。
     */
    private fun memberNamesHint(info: ClassInfo, fields: Boolean): String {
        val own = if (fields) info.fields.keys else info.methods.keys
        val inherited = ancestorsOf(info).reversed().flatMap { if (fields) it.fields.keys else it.methods.keys }
        val all = (own + inherited).distinct()
        return if (all.isEmpty()) "" else "（有：${all.joinToString(", ")}）"
    }
    /** 方法/构造器的形参类型：读在 [declareClasses] 里算好的那份（带类型参数占位）。
*/
    /** 这个类型里还有类型参数占位吗（`T`、`T[]`、`Box&<T>`）——阶段 31 用。 */
    private fun RsfType.mentionsTypeParam(): Boolean =
        isTypeParam || typeArgs.any { it.mentionsTypeParam() }

    private fun memberParamTypes(m: FuncDecl): List<RsfType> = paramTypesOfDecl(m)

    private fun memberReturnType(m: FuncDecl): RsfType =
        methodReturns[m] ?: if (m.ret.type.name == "void") RsfType.VOID else resolveType(m.ret.type)

    // ------------------------------------------------------------------ 泛型与 JVM 互操作
    /**
     * 类型实参里那个「元素类型」。
     *
     * 只有**恰好一个**实参时才有意义：`ArrayList&<int>` 的元素是 int，
     * 所以 `add(x)` 收的 x 是 int、`get()` 给回来的也是 int。`Map&<K, V>` 这种
     * 多个实参的，光看擦除后的描述符分不清哪个方法形参对应哪一个，所以不做替换
     * （形参/返回值就还是 `Object`）——这是本阶段的已知边界。
     */
    private fun singleTypeArg(args: List<RsfType>): RsfType? =
        if (args.size == 1) args[0] else null

    /**
     * 「形参 / 返回值是**裸的类类型变量**」时，它实际该是什么类型（多类型实参的擦除互操作）。
     *
     * [varIndex] 来自**方法签名**（`HashMap.put` 的 `(TK;TV;)TV;` → 形参 `[0, 1]`、返回 `1`，
     * 见 [org.example.complie.interop.ClassPath.methodTypeVars]）。有了它，
     * `HashMap&<string, int>` 的 `put` 才期望 `(string, int)`、`get()` 才给回 `int`——
     * 光看擦除后的描述符（两个都是 `Object`）永远分不清。
     *
     * 返回 null = 这个方法位置跟类型变量无关（或签名缺失），调用方退回
     * [substituteElement] 那条「恰好一个类型实参」的老路。
     *
     * **已知边界**：只用**下标**对齐，所以要求子类与父类的形式类型参数顺序一致
     * （`LinkedHashMap<K,V> extends HashMap<K,V>`、`TreeMap<K,V> extends AbstractMap<K,V>`
     * 都满足；JDK 集合全族都是这个形状）。若将来碰到 `Foo<A,B> extends Bar<B,A>` 这种
     * 换序继承，这里要改成沿类签名的父子映射走。
     */
    private fun erasedActual(type: RsfType, varIndex: Int?, typeArgs: List<RsfType>): RsfType? {
        if (varIndex == null) return null
        if (type.isArray || type.isTypeParam) return null
        return typeArgs.getOrNull(varIndex)
    }

    /** 这个方法签名里的类型是不是擦除后的 `Object`（泛型实参的落点）。 */
    private fun isErasedObject(type: RsfType): Boolean =
        type.isExternal && type.externalName == "java/lang/Object" && type.typeArgs.isEmpty()

    /** 擦除后是 `Object` 的形参/返回值，按元素类型还原。 */
    private fun substituteElement(type: RsfType, element: RsfType?): RsfType =
        if (element != null && isErasedObject(type)) element else type

    /**
     * 按 JVM 的
*装箱**形态去看一个实参：`xs.add(3)` 里的 `3` 到了 Java 侧是 `Integer`）
     * 匹配的形参是 `Object`。只影响「选哪个重载」，类型检查仍然用原类型。
     */
    private fun boxForInterop(type: RsfType): RsfType =
        if (type.isPrimitive) type.copy(nullable = true) else type

    /**
     * private 成员只能在声明它的那个类里用（跟 JVM 的 ACC_PRIVATE 一致）。
     *
     * 只对**方法**生效：字段的写法是 `var x : int = 0`，槽位里没有 `<ControlType>` 的位置，
     * 所以字段一律「类可见」。等字段也支持修饰词了，这里一起管。
     */
    private fun checkMemberVisible(info: ClassInfo, name: String, at: SourceLine) {
        val methods = info.methods[name].orEmpty()
        if (methods.none { it.control == ControlType.PRIVATE }) return
        if (currentClass === info) return
        throw err(
            "'$name' 是 ${info.decl.name} 的 prv 方法，只能在这个类内部使用。" +
                "要让外面也能用，把它改成 prot 或 pub",
            at,
        )
    }

    // ------------------------------------------------------------------ 函数体
    private fun checkFunction(fn: FuncDecl) {
        // 阶段 31：泛型函数的类型参数并进解析环境（函数级遮蔽类级），查完就还原，
        // 否则 `fn id&<T>(x : T) -> T` 的 T 会漏给下一个被检查的函数。
        val savedTypeParams = pushTypeParams(fn)
        checkFunctionBody(fn)
        typeParams = savedTypeParams
    }

    private fun checkFunctionBody(fn: FuncDecl) {
        currentFn = fn
        currentSource = sourceOfFunction[fn] ?: currentClass?.source ?: source
        // 返回类型直接从签名算，不查 returnTypes 表：那张表只装了顶层函数，
        // 类的方法不在里面（同名方法还会互相打架）
        currentReturn = if (fn.ret.type.name == "void") RsfType.VOID else resolveType(fn.ret.type)
        scope = Scope(null)
        loopDepth = 0
        // 逐函数状态：try 深度是**词法**上的，出了这个函数就归零
        tryDepth = 0
        handledNode = null

        // 参数与函数体顶层**共用**一个作用域：body 里再声明同名变量会报重复声明
        val params = paramsForCheck(fn)
        for (binding in params) scope.declare(binding.name, binding)

        // 抽象方法（接口里只写签名的那种）没有体可查、也没有代码可降级——
        // 调用方（checkInterfaceBodies / checkClassBodies）本来就不会把抽象方法送到这里，
        // 这一行是防御性的：真送到这里就直接收工，别去碰 fn.body。
        val fnBody = fn.body ?: return

        // 标签先全收一遍，goto 才能向前引用
        labels.clear()
        collectLabels(fnBody)

        checkBlock(fnBody, pushScope = false)

        // 第二趟：降级 + 控制流分析。实例方法的 0 号槽留给 this，局部槽从 1 开始。
        // 接口的默认方法也是实例方法（阶段 20）：它照样要 this 才能调到接口的其它方法。
        val isInstanceMethod = methodOwner.containsKey(fn) || interfaceOwnerTable.containsKey(fn)
        val firstSlot = if (isInstanceMethod && !fn.isStatic) 1 else 0
        val loweredFn = Lowering(fn, declBindings, params, firstSlot, catchBindings).lower()
        if (isInstanceMethod) {
            methodLowered[fn] = loweredFn
        } else {
            lowered[fn] = loweredFn
        }
        analyzeFlow(fn, loweredFn)
    }

    /**
     * 这个方法/函数在检查期间用哪套参数绑定。
     *
     * 方法按 [FuncDecl] 取，顶层函数也按 [FuncDecl] 取（阶段 19d 起两张表都按
     * `FuncDecl` 索引）——同名重载各有各的绑定，不会互相覆盖。
     */
    private fun paramsForCheck(fn: FuncDecl): List<Binding> =
        methodParams[fn] ?: paramBindings[fn].orEmpty()

    private fun collectLabels(block: Block, seen: HashSet<String> = labels) {
        for (stmt in block.statements) {
            when (stmt) {
                is LabelStmt -> {
                    if (!seen.add(stmt.name)) {
                        throw err("标签 '${stmt.name}' 在函数 '${currentFn.name}' 里重复定义", stmt.at)
                    }
                }
                is Block -> collectLabels(stmt, seen)
                is IfStmt -> {
                    collectLabels(stmt.body, seen)
                    stmt.elseBody?.let { collectLabels(it, seen) }
                }
                is WhileStmt -> collectLabels(stmt.body, seen)
                is ForStmt -> collectLabels(stmt.body, seen)
                // try / catch 的两个块各是一个作用域，但标签是**函数作用域**的：
                // 漏掉这里的话，try 体里写的 `goto` 会找不到同一个 try 体里的标签。
                is TryStmt -> {
                    collectLabels(stmt.body, seen)
                    collectLabels(stmt.catchBody, seen)
                }
                else -> {}
            }
        }
    }

    private fun checkBlock(block: Block, pushScope: Boolean) {
        val saved = scope
        if (pushScope) scope = Scope(saved)
        for (stmt in block.statements) checkStmt(stmt)
        scope = saved
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Block -> checkBlock(stmt, pushScope = true)

            is VarDeclStmt -> {
                val annotated = stmt.declaredType?.let { resolveType(it) }
                // 阶段 30：声明类型当**期望类型**，`lam` 里没写的形参类型 / 返回类型
                // 就指望它（`var f : (int) -> int = lam(x) …`）。
                val initType = withExpectation(annotated) { type(stmt.initializer) }

                if (initType.isVoid) {
                    throw err("void 不能作为变量的值", stmt.at)
                }
                val declared = annotated ?: initType
                if (declared.isVoid) {
                    throw err("变量不能是 void 类型", stmt.at)
                }
                if (declared.isNullLiteral) {
                    throw err(
                        "没法从 null 推断变量类型。写成 'var ${stmt.name} : ?类型 = null' 指明类型，例如 " +
                            "var ${stmt.name} : ?string = null",
                        stmt.at,
                    )
                }
                if (annotated != null && !acceptsValue(annotated, stmt.initializer, initType)) {
                    // 阶段 30：函数类型对不上时给一句点名「类型不匹配」的话——
                    // 形状差一个类型就整段不可赋（结构相等，没有子类型），
                    // 老文案「不能把 X 赋给 Y 变量」在 `(int) -> int` 上读不出差在哪。
                    if (annotated.isFunctionType || initType.isFunctionType) {
                        throw err(
                            "类型不匹配：变量 '${stmt.name}' 声明的是 ${annotated.rsfName}，" +
                                "实际给的是 ${initType.rsfName}（函数类型要求形状完全一样）",
                            stmt.at,
                        )
                    }
                    throw err(
                        "不能把 ${initType.rsfName} 赋给 ${annotated.rsfName} 变量 '${stmt.name}'",
                        stmt.at,
                    )
                }
                if (scope.declares(stmt.name)) {
                    throw err("变量 '${stmt.name}' 在同一个作用域里重复声明", stmt.at)
                }
                val binding = Binding(stmt.name, declared, stmt.mutable, -1, stmt.at)
                scope.declare(stmt.name, binding)
                declBindings[stmt] = binding
            }

            is DerefAssignStmt -> {
                // `*p = v`：target 已经过 type()，所以它的类型就是「指针指向的东西」。
                val pointee = type(stmt.target)
                val valueType = type(stmt.value)
                if (pointee.isVoid) {
                    throw err("不能通过 void* 写值：指针指向的东西没有类型", stmt.at)
                }
                if (!acceptsValue(pointee, stmt.value, valueType)) {
                    throw err(
                        "不能把 ${valueType.rsfName} 写进 ${pointee.rsfName} 指针指向的位置",
                        stmt.at,
                    )
                }
            }

            is MemberAssignStmt -> {
                val target = stmt.target
                if (target.arguments != null) {
                    throw err("不能给方法赋值（'${target.name}' 是方法调用）", stmt.at)
                }
                val receiverType = if (target.receiver is ThisExpr) {
                    (thisTypeOrNull() ?: throw err("'this' 只能用在类的方法里", stmt.at))
                } else {
                    type(target.receiver)
                }
                val info = userClassOf(receiverType)
                    ?: throw err("${receiverType.rsfName} 上没有字段 '${target.name}'", stmt.at)
                // 阶段 25 K1（G3）：字段**写**也要沿父类链找（读路径 checkUserMember 早已如此）。
                // owner 是**声明它的类**——PUTFIELD 的 owner 必须是声明类（JVM 解析规则）。
                val (owner, field) = lookupField(info, target.name)
                    ?: throw err(
                        "类 '${info.decl.name}' 里没有字段 '${target.name}'" +
                            memberNamesHint(info, fields = true),
                        stmt.at,
                    )
                checkMemberVisible(owner, target.name, stmt.at)
                if (!field.mutable) {
                    throw err(
                        "字段 '${target.name}' 是 imutvar（不可变），声明时就定了值，不能重新赋值",
                        stmt.at,
                    )
                }
                // 字段类型要按**接收者的类型实参**替换之后再比：
                // `var Box&<int> b` 的 `b.value = 9` 里，字段声明是 `T`、写进去的是 `int`。
                // 以前这里直接 `resolveType(field.type)`：既丢掉了替换（拿 `T` 去比），
                // 又是在类体之外解析 `T`——`typeParams` 早清空了，于是报「未知类型 'T'」。
                // 读路径（checkUserMember）本来就是传 subst 的，这里跟它对齐。
                // 字段声明在祖先上时还要把实参一路代换到**声明类**那一层（G4/G3）。
                val subst = substitutionAlongChain(info, substitutionFor(info, receiverType.typeArgs), owner)
                val declared = fieldMember(info, field, subst).type
                val valueType = type(stmt.value)
                if (!acceptsValue(declared, stmt.value, valueType)) {
                    throw err(
                        "不能把 ${valueType.rsfName} 赋给 ${declared.rsfName} 字段 '${target.name}'",
                        stmt.at,
                    )
                }
                fieldWrites[stmt] = fieldMember(info, field, subst)
            }

            is AssignStmt -> {
                val binding = scope.lookup(stmt.name)
                if (binding == null) {
                    // 不是局部变量 参数——在类的方法里就可能是在写字段（`v = v + 1`）
                    // 阶段 25 K1（G3）：裸字段写同样沿父类链找；被写的 owner 是声明类，
                    // 类型也要按**声明类**那一层代换（`class IntHolder : Holder&<int>` 里
                    // 裸写 `v` 必须是 int，G4）。
                    val info = currentClass
                    val found = info?.let { lookupField(it, stmt.name) }
                    if (found != null && info != null) {
                        val (owner, field) = found
                        checkMemberVisible(owner, stmt.name, stmt.at)
                        val subst = substitutionFromInside(info, owner)
                        checkFieldWrite(owner, field, stmt.value, stmt.at, subst)
                        bareFieldWrites[stmt] = fieldMember(info, field, subst)
                        return
                    }
                }
                val bound = binding
                    ?: throw err("未声明的变量 '${stmt.name}'", stmt.at)
                // 解糖生成的赋值（for-in 的步进）不受 imutvar 约束
                if (!stmt.synthetic && !bound.mutable) {
                    if (bound.catchVar) {
                        throw err(
                            "catch 的错误变量 '${stmt.name}' 不能重新赋值——它就是这次接住的那条消息",
                            stmt.at,
                        )
                    }
                    throw err(
                        "变量 '${stmt.name}' 是 'imutvar' 声明的，不能重新赋值。" +
                            "想改值就把它声明成 'var'。",
                        stmt.at,
                    )
                }
                // 阶段 30：`f = lam(x) …` 里 `f` 的类型就是期望类型
                val valueType = withExpectation(bound.type.takeIf { it.isFunctionType }) { type(stmt.value) }
                if (!acceptsValue(bound.type, stmt.value, valueType)) {
                    if (valueType.isNullLiteral) {
                        throw err(
                            "变量 '${stmt.name}' 是 ${bound.type.rsfName}（非空），不能赋 null。" +
                                "想要可空就声明成 ?${bound.type.rsfName}",
                            stmt.at,
                        )
                    }
                    throw err(
                        "不能把 ${valueType.rsfName} 赋给 ${bound.type.rsfName} 变量 '${stmt.name}'",
                        stmt.at,
                    )
                }
                assignRefs[stmt] = bound
            }

            is IndexAssignStmt -> checkIndexAssign(stmt)

            is ThisCallStmt -> checkThisCall(stmt)

            // 阶段 25：父类构造委托 `super(实参)`（只能当构造器体第一句）
            is SuperCallStmt -> checkSuperCall(stmt)

            is ReturnStmt -> checkReturn(stmt)

            is FailStmt -> checkFail(stmt)

            is TryStmt -> checkTry(stmt)

            is ExprStmt -> type(stmt.expr)

            is LabelStmt -> { /* 已经圀 collectLabels 里查过重名
*/ }

            is GotoStmt -> {
                if (stmt.label !in labels) {
                    throw err(
                        "goto 的标签 '${stmt.label}' 在函数 '${currentFn.name}' 里没有定义" +
                            "（标签是函数作用域，不能跨函数跳）",
                        stmt.at,
                    )
                }
            }

            is IfStmt -> {
                val condType = type(stmt.condition)
                if (condType != RsfType.BOOL) {
                    throw err("if 的条件必须是 bool，实际是 ${condType.rsfName}", stmt.condition.at)
                }
                checkBlock(stmt.body, pushScope = true)
                stmt.elseBody?.let { checkBlock(it, pushScope = true) }
            }

            is WhileStmt -> {
                val condType = type(stmt.condition)
                if (condType != RsfType.BOOL) {
                    throw err("while 的条件必须是 bool，实际是 ${condType.rsfName}", stmt.condition.at)
                }
                loopDepth++
                checkBlock(stmt.body, pushScope = true)
                loopDepth--
            }

            is ForStmt -> {
                stmt.condition?.let {
                    val condType = type(it)
                    if (condType != RsfType.BOOL) {
                        throw err("for 的条件必须是 bool，实际是 ${condType.rsfName}", it.at)
                    }
                }
                loopDepth++
                // 更新语句在循环体**外面**的作用域里检查，它要能看见
                // for 的初始化变量
                stmt.step?.let { checkStmt(it) }
                checkBlock(stmt.body, pushScope = true)
                loopDepth--
            }

            is SwitchStmt -> checkSwitch(stmt)

            is BreakStmt -> {
                if (loopDepth == 0) throw err("'break' 只能用在 while 循环里", stmt.at)
            }

            is ContinueStmt -> {
                if (loopDepth == 0) throw err("'continue' 只能用在 while 循环里", stmt.at)
            }
        }
    }

    // ------------------------------------------------------------------ 枚举分派（阶段 19c）

    /**
     * `switch (枚举值) { case 枚举名.值名 : ... else : ... }` 的检查。
     *
     * 五条规则，每条都带一句「为什么」：
     *
     * 1. **被分派的值必须是枚举**（[isEnumType]）。`case` 只接受枚举常量，所以
     *    主体也只能是枚举；要按数值 / 字符串分派请写 if / else-if 链。
     * 2. **不能是可空枚举**：枚举常量（单例）都不是 null，`case` 表达不出
     *    「null 走哪」，于是可空主体要么漏掉 null、要么得靠 else，两条都不干净。
     *    要分派就先 `?:` 给兜底值或用 `!!` 解包。
     * 3. **case 必须是同一个枚举的常量**。写别的枚举的常量、写不存在的值名，
     *    都在这里报错，并**列出这个枚举有哪些值**（值名写错是这类代码最常见的错，
     *    而「有哪些值」这句话本身就是答案）。
     * 4. **同一个值不能有两个分支**：两个 case 匹配同一个常量时先后顺序没有定义，
     *    与其定一个「取前面那个」的规矩，不如直接拦住。
     * 5. **没有 `else` 时必须穷尽**：把 case 覆盖的值和枚举的全部值一比，缺哪个
     *    就报哪个。既然只匹配枚举常量，穷尽性是**编译期就能算准**的，而
     *    「漏了分支却静默什么都不做」是最难查的一类 bug——宁可不让它编译过。
     *
     * `break` / `continue` 在分支体里**不改变含义**：switch 不消费它们
     * （`loopDepth` 不在这里加），所以循环里的 switch 分支里写 `break` 跳出的
     * 是那层循环——这正是「没有 fallthrough 就不需要 break 跳出 switch」的由来。
     */
    private fun checkSwitch(stmt: SwitchStmt) {
        val subjectType = type(stmt.subject)
        if (!isEnumType(subjectType)) {
            throw err(
                "switch 只能对枚举做（实际是 ${subjectType.rsfName}）：case 只接受枚举常量，" +
                    "所以被分派的值也必须是一个枚举。" +
                    if (subjectType.isArithmetic || subjectType.base == RsfBase.STRING ||
                        subjectType.base == RsfBase.BOOL
                    ) {
                        "要按${if (subjectType.base == RsfBase.STRING) "字符串" else "值"}分派就写 if / else if 链"
                    } else {
                        "要按对象的性质分派就先把它取成枚举字段再 switch"
                    },
                stmt.subject.at,
            )
        }
        if (subjectType.nullable) {
            throw err(
                "switch 的值不能是可空枚举（${subjectType.rsfName}）：枚举常量都不是 null，" +
                    "而 case 表达不出「null 走哪一支」。先用 '?:' 给个兜底值，或者用 '!!' 解包",
                stmt.subject.at,
            )
        }
        val enumName = subjectType.externalName
            ?: throw err("内部错误：枚举类型没有名字（${subjectType.rsfName}）", stmt.at)
        val allValues = enumValuesOf(enumName)

        val seen = LinkedHashMap<String, SourceLine>()
        for (c in stmt.cases) {
            // 阶段 21：`case` 弃用——旧写法照收，但每个分支提醒一次（位置在 AST 里记着）
            val deprecatedCaseAt = c.deprecatedCaseAt
            if (deprecatedCaseAt != null) {
                diagnostics.add(
                    Diagnostic(
                        Severity.WARNING,
                        "parser",
                        "'case' 已弃用（is deprecated since ${Rsf.VERSION}）：" +
                            "switch 的分支直接写 '枚举名.值名 : 语句'，不用再写 'case'",
                        currentSource.name,
                        deprecatedCaseAt.line,
                        deprecatedCaseAt.column,
                    ),
                )
            }
            if (c.enumName != enumName) {
                throw err(
                    if (enumTable.containsKey(c.enumName)) {
                        "case 写的是 ${c.enumName} 的常量，但 switch 的值是 ${enumName}——" +
                            "case 只能匹配同一个枚举的常量（跨枚举的值永远不相等，" +
                            "编译器直接拦住比运行期悄悄不命中好）"
                    } else {
                        "case 里的 '${c.enumName}' 不是一个枚举类型" +
                            "（switch 的值是 ${enumName}）" + enumValueList(enumName)
                    },
                    c.at,
                )
            }
            if (allValues.none { it.name == c.valueName }) {
                throw err(
                    "枚举 '${enumName}' 里没有值 '${c.valueName}'" + enumValueList(enumName),
                    c.at,
                )
            }
            val prev = seen.put(c.valueName, c.at)
            if (prev != null) {
                throw err(
                    "case ${enumName}.${c.valueName} 重复了（第 ${prev.line} 行已经匹配过这个值）。" +
                        "同一个值有两个分支时该走哪个没有定义——合并成一支，" +
                        "或者把两个值的处理放进同一个分支的 if 里",
                    c.at,
                )
            }
        }

        if (stmt.elseBody == null) {
            val missing = allValues.filter { v -> !seen.containsKey(v.name) }
            if (missing.isNotEmpty()) {
                throw err(
                    "switch 没有 else 分支，就必须覆盖 ${enumName} 的每一个值，缺了：" +
                        missing.joinToString(", ") { it.name } +
                        "。补上这几个 case，或者写一支 'else : ...' 兜底",
                    stmt.at,
                )
            }
        }

        for (c in stmt.cases) checkBlock(c.body, pushScope = true)
        stmt.elseBody?.let { checkBlock(it, pushScope = true) }
    }

    // ------------------------------------------------------------------ 数组（阶段 13）

    /**
     * `数组[下标] = 值`（阶段 13）。
     *
     * 检查顺序和两端的**求值顺序**对齐：接收者 → 下标 → 右值
     * （JVM 的 `*ASTORE` 就是这个次序，解释器照抄）。越界是运行期的事，
     * 编译期只管类型：接收者必须是数组、下标必须是 `int`、右值必须能赋给元素类型。
     */
    private fun checkIndexAssign(stmt: IndexAssignStmt) {
        // 用 type() 而不是直接 checkIndexTarget：它会**记下**这个下标表达式的类型，
        // 两端后端都要靠 model.typeOf(目标) 知道元素的类型（少了这一步，
        // 解释器会在 `a[i] = v` 上报「表达式缺少类型标注」）
        val arrayType = type(stmt.target)
        val element = arrayType.elementType
        val valueType = type(stmt.value)
        if (acceptsValue(element, stmt.value, valueType)) return
        throw err(
            if (valueType.isNullLiteral) {
                "数组 '${arrayType.rsfName}' 的元素类型是 ${element.rsfName}（非空），不能赋 null。" +
                    "元素的可空性本阶段还不支持；引用类型数组的元素**默认**就是 null，" +
                    "不需要显式写 null"
            } else {
                "不能把 ${valueType.rsfName} 赋给 ${arrayType.rsfName} 的元素：元素类型是 ${element.rsfName}"
            },
            stmt.at,
        )
    }

    /**
     * 下标表达式的公共检查（读、写都用得上）：接收者是不是数组、下标是不是 `int`。
     * 返回**数组**的类型（调用方自己取 [RsfType.elementType]）。
     */
    private fun checkIndexTarget(expr: IndexExpr): RsfType {
        val receiverType = type(expr.receiver)
        if (receiverType.isNullLiteral) {
            throw err("null 上没有下标", expr.at)
        }
        if (!receiverType.isArray) {
            throw err(
                "${receiverType.rsfName} 不是数组，不能写 值[下标]。" +
                    if (receiverType.base == RsfBase.STRING) {
                        "要取字符串里的字符用 s.charAt(下标)（数组才用 a[下标]）"
                    } else {
                        "数组要先声明成 T[]，例如 var a : int[] = new int[3]"
                    },
                expr.at,
            )
        }
        val indexType = type(expr.index)
        if (indexType != RsfType.INT) {
            throw err(
                "数组下标必须是 int，实际是 ${indexType.rsfName}" + indexHint(indexType),
                expr.index.at,
            )
        }
        return receiverType
    }

    /**
     * 下标 / 长度类型不对时补一句「该怎么转」。
     *
     * rsf 的隐式转换比 Java 严（`byte` / `char` 不自己往 int 上贴），
     * 而这两个位置**不做** byte/char 的隐式提升：下标是纯整数位置，混进字符只会写错。
     */
    private fun indexHint(got: RsfType): String = when (got.base) {
        RsfBase.CHAR -> "；char 是字符不是整数，要取码点写 charToInt(c)"
        RsfBase.BYTE -> "；byte 可以隐式加宽成 int，先 var i : int = b 再当下标"
        RsfBase.LONG, RsfBase.FLOAT, RsfBase.DOUBLE -> "；长度和下标的类型都固定是 int"
        else -> ""
    }

    /**
     * `new T[n]` / `new T[n][m]`（阶段 13）。
     *
     * 长度是**运行时求值**的 int 表达式（可以在循环里按需分配），所以编译期只查类型、
     * 不查值；负数长度是运行期错误（两端做同一套检查，见 `ArrayRuntime`）。
     *
     * 结果的类型 = 元素类型 + 维度数层 `[]`：`new int[3][4]` 是 `int[][]`。
     * 多维就是「数组的数组」（JVM 的 `MULTIANEWARRAY`），没有「一块二维内存」这种特殊语义。
     */
    private fun checkNewArray(expr: NewArrayExpr): RsfType {
        val element = resolveType(expr.elementType)
        if (element.isVoid) {
            throw err("数组的元素类型不能是 void——void 没有值，装不进数组", expr.at)
        }
        if (element.isArray) {
            throw err(
                "数组类型不能写在 new 的类型位置：每一维的长度都要写在方括号里（new int[3][4]）",
                expr.at,
            )
        }
        for ((i, dim) in expr.dimensions.withIndex()) {
            val dimType = type(dim)
            if (dimType != RsfType.INT) {
                throw err(
                    "new ${element.rsfName}[...] 的第 ${i + 1} 个长度必须是 int，实际是 ${dimType.rsfName}" +
                        indexHint(dimType),
                    dim.at,
                )
            }
        }
        return element.arrayOf(expr.dimensions.size)
    }

    // ------------------------------------------------------------------ 构造器委托（阶段 18）

    /**
     * `this(实参)` —— 构造器委托。
     *
     * 三条规矩：只能在构造器里、必须是**体的第一句**、目标必须是同一个类的另一个
     * 构造器（按实参个数与类型挑，和 `new` 用同一套打分）。挑中的边记进
     * [ctorDelegations]，两个后端据此递归 / 发 `INVOKESPECIAL <init>`。
     *
     * 「必须第一句」不是为了好看：它保证 `this` 在被使用之前一定已经被初始化过一次
     * （JVM 的校验器也是这么要求的——不先调 `<init>` 就碰字段，类加载就炸）。
     */
    private fun checkThisCall(stmt: ThisCallStmt) {
        val fn = currentFn
        if (!fn.isConstructor) {
            throw err(
                "'this(实参)' 是**构造器委托**，只能写在构造器体里（当前在 " +
                    if (methodOwner.containsKey(fn)) "方法 '${fn.name}'" else "顶层函数 '${fn.name}'" +
                    " 里）。想调用当前对象的方法直接写方法名，想访问字段写字段名",
                stmt.at,
            )
        }
        // 构造器一定有体（抽象方法进不来：接口里没有构造器），`?.` 只为类型安全
        if (fn.body?.statements?.firstOrNull() !== stmt) {
            throw err(
                "'this(...)' 必须是构造器体的**第一句**：委托要在碰 this/字段之前发生。" +
                    "写法是 constructor(参数) { this(另一组实参) ...剩下的语句 }",
                stmt.at,
            )
        }
        val info = currentClass
            ?: throw err("内部错误：构造器不在任何类里", stmt.at)

        val argTypes = ArrayList<RsfType>(stmt.arguments.size)
        for ((i, arg) in stmt.arguments.withIndex()) {
            val t = type(arg)
            if (t.isVoid) {
                throw err("this(...) 的第 ${i + 1} 个实参是 void 表达式，不能当参数传", arg.at)
            }
            argTypes.add(t)
        }

        val target = resolveCtor(info, stmt.arguments, argTypes, emptyMap(), stmt.at)
        if (target === fn) {
            throw err(
                "构造器不能委托给自己（this(...) 指的就是当前这个构造器，" +
                    "换个实参表让目标变成另一个构造器，或者去掉这句）",
                stmt.at,
            )
        }
        ctorDelegations[fn] = CtorDelegation(target, stmt)
    }

    /**
     * `fail <消息>`（阶段 14）。
     *
     * 两条规矩：
     * 1. 消息必须是 `string`（JVM 上 RuntimeException 的消息就是字符串）；
     * 2. 只能出现在**可能失败的函数**（返回类型 `!T`）里，或者 `try` 块里——
     *    否则这个错误没人接得住，跑起来只会一路穿到 JVM 外面变成栈回溯。
     */
    private fun checkFail(stmt: FailStmt) {
        val msgType = type(stmt.message)
        if (msgType != RsfType.STRING) {
            throw err(
                "'fail' 后面要跟一个 string 表达式当错误消息，实际是 ${msgType.rsfName}。" +
                    "例如：fail \"空字符串没法转数字\"",
                stmt.message.at,
            )
        }
        if (tryDepth > 0 || currentFn.ret.mayError) return
        throw err(
            "'fail' 要用在 !T 函数里，或者包进 try 块：当前函数 '${currentFn.name}' 的返回类型是 " +
                "${currentFn.ret.display()}（不是 !T），这里也不在任何 try 块内，所以这个错误没人接得住。" +
                "办法一：把返回类型写成 '!${currentFn.ret.type.display()}'；" +
                "办法二：try { fail \"...\" } catch (e : string) { ... }",
            stmt.at,
        )
    }

    /**
     * `try { 保护体 } catch (e : string) { 处理体 }`（阶段 14；阶段 19b 起推荐 Kotlin 风格）。
     *
     * - 保护体里的 `fail` 与未显式处理的 `!T` 调用都算「已处理」：异常被这个 try 接走。
     * - 处理体**不在**保护范围内（它自己抛的错不会又被自己接住），所以那里再写 `fail`、
     *   再调 `!T` 函数，仍要按规矩来。
     * - catch 变量固定是 `string`、不可变，作用域只覆盖处理体。
     */
    private fun checkTry(stmt: TryStmt) {
        tryDepth++
        checkBlock(stmt.body, pushScope = true)
        tryDepth--

        val saved = scope
        scope = Scope(saved)
        val binding = Binding(
            name = stmt.catchName,
            type = RsfType.STRING,
            mutable = false,
            paramIndex = -1,
            at = stmt.at,
            catchVar = true,
        )
        scope.declare(stmt.catchName, binding)
        catchBindings[stmt] = binding
        for (s in stmt.catchBody.statements) checkStmt(s)
        scope = saved
    }

    private fun checkReturn(stmt: ReturnStmt) {
        val value = stmt.value
        if (value == null) {
            if (!currentReturn.isVoid) {
                throw err(
                    "函数 '${currentFn.name}' 声明返回 ${currentReturn.rsfName}）" +
                        "'rtn' 后面必须跟一个 ${currentReturn.rsfName} 的值",
                    stmt.at,
                )
            }
            return
        }
        if (value is NullLit) {
            if (!currentReturn.isVoid && !currentReturn.isNullable) {
                throw err(
                    "函数 '${currentFn.name}' 声明返回 ${currentReturn.rsfName}（非空），不能 'rtn null'。" +
                        "想让它能返回 null，把返回类型写成 ?${currentReturn.rsfName}",
                    stmt.at,
                )
            }
            exprTypes[value] = RsfType.NULL
            return
        }
        // 阶段 30：`rtn lam(x) …` 里的期望类型就是本函数的返回类型
        val valueType = withExpectation(currentReturn.takeIf { it.isFunctionType }) { type(value) }
        if (currentReturn.isVoid) {
            throw err(
                "函数 '${currentFn.name}' 是 void 函数，不能 rtn 一个 ${valueType.rsfName} 值" +
                    "（写成 'rtn' 或 'rtn null'）",
                stmt.at,
            )
        }
        if (!acceptsValue(currentReturn, value, valueType)) {
            if (valueType.isNullLiteral) {
                throw err(
                    "函数 '${currentFn.name}' 声明返回 ${currentReturn.rsfName}（非空），不能 'rtn null'。" +
                        "想让它能返回 null，把返回类型写成 ?${currentReturn.rsfName}",
                    stmt.at,
                )
            }
            throw err(
                "返回类型不匹配：'${currentFn.name}' 声明返回 ${currentReturn.rsfName}）" +
                    "实际 rtn 的是 ${valueType.rsfName}",
                stmt.at,
            )
        }
    }

    // ------------------------------------------------------------------ 控制流分析
    private fun analyzeFlow(fn: FuncDecl, loweredFn: LoweredFunction) {
        val cf = ControlFlow(loweredFn.instructions, loweredFn.labelIndex)
        val reach = cf.reachable()

        for (i in 0 until cf.size) {
            // 只对用户写下的语句报警；goto / 标签是降级时铺的控制流脚手架）
            // 它们不可达是正常现象（比如 while(true) 的回边）
            val instr = cf.instructionAt(i)
            if (!reach[i] && instr !is ILabel && instr !is IGoto) {
                val at = instr.at
                diagnostics.add(
                    Diagnostic(
                        Severity.WARNING, "checker", "不可达代码（永远执行不到）",
                        currentSource.name, at.line, at.column,
                    ),
                )
            }
        }

        if (reach[cf.exit] && currentReturn != RsfType.VOID) {
            throw err(
                "函数 '${fn.name}' 声明返回 ${currentReturn.rsfName}）" +
                    "但存在一条没写 rtn 就走到函数末尾的路径",
                fn.at,
            )
        }

        val definite = cf.definitelyAssigned(reach)
        for (i in 0 until cf.size) {
            if (!reach[i]) continue

            // 把「执行到这条指令之前一定已经赋过值的声明」换算成绑定
            val assignedHere = HashSet<Binding>()
            for (decl in definite[i]) {
                declBindings[decl]?.let { assignedHere.add(it) }
            }

            val reads = ArrayList<VarExpr>()
            for (expr in expressionsOf(cf.instructionAt(i))) collectVarReads(expr, reads)
            for (read in reads) {
                val binding = varRefs[read] ?: continue
                // 参数在函数入口就有值，不参与确定性赋值分析；
                // catch 变量也一样——它是「进处理体时刚写进去的」，不属于任何 VarDeclStmt
                if (binding.isParam || binding.catchVar) continue
                if (binding !in assignedHere) {
                    throw err(
                        "读到变量 '${read.name}' 时它可能还没被赋值：存在一条绕过了它前面那句声明的路径" +
                            "（向后跳的 goto 或循环回边）。" +
                            "这就是 C++ 里「不能跳过初始化跳进作用域」那条规矩。",
                        read.at,
                    )
                }
            }
        }
    }

    private fun expressionsOf(instr: Instr): List<Expr> = when (instr) {
        is IVarDecl -> listOf(instr.stmt.initializer)
        is IAssign -> listOf(instr.stmt.value)
        is IFieldAssign -> listOf(instr.stmt.target.receiver, instr.stmt.value)
        // 下标赋值：接收者、下标、右值三处都可能读到变量，一个都不能漏
        // （漏了下标里的变量就绕过了「确定性赋值」检查）
        is IIndexAssign -> listOf(
            instr.stmt.target.receiver,
            instr.stmt.target.index,
            instr.stmt.value,
        )
        // 解引用赋值（阶段 53）：指针表达式与右值都可能读到变量
        is IDerefAssign -> listOf(instr.stmt.target, instr.stmt.value)
        // 构造器委托的实参也要走同一套变量读取分析
        is ICtorDelegate -> instr.stmt.arguments
        // 父类构造委托（阶段 25）的实参同理
        is ISuperDelegate -> instr.stmt.arguments
        is IExpr -> listOf(instr.stmt.expr)
        is IReturn -> listOfNotNull(instr.stmt.value)
        is IFail -> listOf(instr.stmt.message)
        is IBranch -> listOf(instr.cond)
        // switch 的**被分派表达式**也是这里读的变量：漏了它，
        // `switch (k)` 里的 k 就绕过了「确定性赋值」检查（还没赋值的 k 能被 switch）
        is ISwitch -> listOf(instr.stmt.subject)
        is IGoto, is ILabel -> emptyList()
    }

    private fun collectVarReads(expr: Expr, out: MutableList<VarExpr>) {
        when (expr) {
            is VarExpr -> out.add(expr)
            is UnaryExpr -> collectVarReads(expr.operand, out)
            is BinaryExpr -> {
                collectVarReads(expr.left, out)
                collectVarReads(expr.right, out)
            }
            is CallExpr -> expr.arguments.forEach { collectVarReads(it, out) }
            is IfExpr -> {
                collectVarReads(expr.condition, out)
                collectVarReads(expr.whenTrue, out)
                collectVarReads(expr.whenFalse, out)
            }
            is ElvisExpr -> {
                collectVarReads(expr.left, out)
                collectVarReads(expr.right, out)
            }
            is ForceExpr -> collectVarReads(expr.operand, out)
            is MemberExpr -> {
                collectVarReads(expr.receiver, out)
                expr.arguments?.forEach { collectVarReads(it, out) }
            }
            // 阶段 13：数组
            is IndexExpr -> {
                collectVarReads(expr.receiver, out)
                collectVarReads(expr.index, out)
            }
            is NewArrayExpr -> expr.dimensions.forEach { collectVarReads(it, out) }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ 表达式
    private fun type(expr: Expr): RsfType {
        val t = computeType(expr)
        exprTypes[expr] = t
        return t
    }

    // ------------------------------------------------------- 函数类型（阶段 30）

    /**
     * 造一个函数类型，并把形状记进 [fnSignatures]。
     *
     * [RsfType] 这边只留一个 `EXTERNAL` + 内部名 `rsf/fn/(int) -> int`：`rsfName`
     * （= `simpleExternalName`）因此正好印成 `(int) -> int` / `?(int) -> int`，
     * 相等性靠内部名字符串，跟结构相等等价（形参逐个 + 返回类型都进了那段文本）。
     */
    private fun fnType(params: List<RsfType>, ret: RsfType, nullable: Boolean): RsfType {
        val text = fnTypeText(params, ret)
        fnSignatures[text] = FnSignature(params, ret)
        return functionTypeOf(text, nullable)
    }

    /** 取回函数类型的形状；不是函数类型就是 null。 */
    private fun fnSignature(t: RsfType): FnSignature? = t.fnTypeText?.let { fnSignatures[it] }

    /** 在「期望类型 = [t]」的上下文里跑 [block]，跑完还原（嵌套取最内层）。 */
    private inline fun <T> withExpectation(t: RsfType?, block: () -> T): T {
        val saved = expectation
        expectation = t
        try {
            return block()
        } finally {
            expectation = saved
        }
    }

    private fun computeType(expr: Expr): RsfType =
        when (expr) {
            is IntLit -> RsfType.INT
            is LongLit -> RsfType.LONG
            is FloatLit -> RsfType.FLOAT
            is DoubleLit -> RsfType.DOUBLE
            is StringLit -> RsfType.STRING
            is CharLit -> RsfType.CHAR
            is BoolLit -> RsfType.BOOL
            is NullLit -> RsfType.NULL

            is NewExpr -> checkConstruction(expr.className, expr.typeArgs, expr.arguments, expr)

            is ThisExpr -> {
                val thisType = thisTypeOrNull()
                    ?: throw err("'this' 只能用在类的方法里（这里不在任何类中）", expr.at)
                thisType
            }

            // 阶段 25：`super` 不是能单独取值的表达式——它只能当方法调用的接收者
            // （`super.f(...)` 那条在 checkMember 里走掉了，走到这里说明用户把它当值用了）
            is SuperExpr -> {
                val info = currentClass
                val hint = if (info != null && parentRecord(info) != null) {
                    "写法是 super.方法名(实参)，或者 super(接口名).方法名(实参)"
                } else if (info != null) {
                    "而且类 '${info.decl.name}' 也没有父类"
                } else {
                    ""
                }
                throw err(
                    "'super' 只能当方法调用的接收者，不能当值用" +
                        (if (hint.isNotEmpty()) "——$hint" else "") +
                        "（DESIGN-25 §3：rsf 不支持 super.字段）",
                    expr.at,
                )
            }

            // 阶段 30：lambda 字面量。形参类型 / 返回类型都能省，靠「期望类型」补，
            // 补不出来就报 DESIGN-30 §5 的第 2 条。
            is LambdaExpr -> checkLambda(expr)

            // 阶段 13：数组的创建与下标读
            is NewArrayExpr -> checkNewArray(expr)

            is IndexExpr -> checkIndexTarget(expr).elementType

            is VarExpr -> {
                val binding = scope.lookup(expr.name)
                if (binding != null) {
                    varRefs[expr] = binding
                    // 阶段 30：`lam` 体里的读先记一笔，checkLambda 事后判「捕获的是不是 var」
                    captureProbe?.add(expr to binding)
                    binding.type
                } else {
                    // 不是局部变量 参数——在类里就再看看字段（裸字段名写法：`rtn x`）。
                    // 阶段 25 K1：沿**父类链**找（继承来的字段也能裸写），并且
                    // 类型要按**声明它的那个类**那一层代换（G4）。
                    val info = currentClass
                    val found = info?.let { lookupField(it, expr.name) }
                    if (found != null && info != null) {
                        val (owner, field) = found
                        checkMemberVisible(owner, expr.name, expr.at)
                        val member = fieldMember(info, field, substitutionFromInside(info, owner))
                        fieldReads[expr] = member
                        member.type
                    } else {
                        throw err(
                            "未声明的变量 '${expr.name}'" +
                                if (info != null &&
                                    info.primary?.params?.any { it.name == expr.name } == true
                                ) {
                                    // 主构造参数**不是字段**（阶段 18 起就是这样）：它只在类头的
                                    // 父类实参表与字段初值里可见。这里点明怎么把它留住，
                                    // 免得用户对着「未声明的变量」猜。
                                    "。'${expr.name}' 是类 '${info.decl.name}' 的**主构造参数**，" +
                                        "它不在方法的作用域里（主构造参数只在类头的父类实参表和字段初值里可见）" +
                                        "——要让方法读得到它，就在类体里存一个字段：" +
                                        "var ${expr.name} : 类型 = ${expr.name}"
                                } else if (info != null && memberNamesHint(info, fields = true).isNotEmpty()) {
                                    "（${info.decl.name} 的字段有：" +
                                        (info.fields.keys + ancestorsOf(info).reversed().flatMap { it.fields.keys })
                                            .distinct().joinToString(", ") + "）"
                                } else if (currentInterface != null) {
                                    // 阶段 20：接口默认方法里没有字段可读（D3），
                                    // 说清楚这一点，用户才不会去翻「我是不是少写了个 var」
                                    "（接口 '${currentInterface!!.decl.name}' 里没有字段可读——" +
                                        "接口只有方法；要用实现类存的值，就在接口上写一个方法把它取出来）"
                                } else {
                                    ""
                                },
                            expr.at,
                        )
                    }
                }
            }

            is CallExpr -> checkCall(expr)

            is UnaryExpr -> {
                val operand = type(expr.operand)
                when (expr.op) {
                    TokenKind.MINUS -> {
                        if (!operand.isArithmetic) {
                            throw err(
                                "一元 '-' 只能用在数值上（byte / char / int / long / float / double），" +
                                    "实际是 ${operand.rsfName}",
                                expr.at,
                            )
                        }
                        // byte / char 取负之后按 int 算（`-'a'` 是 -97，不是 char），
                        // 跟加法的提升规则一致，也和编译产物里那条 INEG 对得上
                        RsfType(operand.base.promoted())
                    }
                    TokenKind.BANG -> {
                        if (operand != RsfType.BOOL) {
                            throw err("'!' 只能用在 bool 上，实际是 ${operand.rsfName}", expr.at)
                        }
                        RsfType.BOOL
                    }
                    // 阶段 53：`&x` 取地址（结果多一层指针），`*p` 解引用（少一层）
                    TokenKind.AMP -> {
                        if (!isAddressable(expr.operand)) {
                            throw err(
                                "只能对变量、字段、数组元素或指针目标取地址（'&'）：" +
                                    "${operand.rsfName} 这个表达式没有存储位置",
                                expr.at,
                            )
                        }
                        operand.pointerOf(1)
                    }

                    TokenKind.STAR -> {
                        if (!operand.isPointer) {
                            throw err(
                                "不能解引用 ${operand.rsfName}：'*' 只能用在指针上（例如 int* 或 Point*）",
                                expr.at,
                            )
                        }
                        operand.pointee
                    }

                    else -> throw err("不认识的一元运算符 '${expr.op.text}'", expr.at)
                }
            }

            is BinaryExpr -> checkBinary(expr)

            is IfExpr -> checkIfExpr(expr)

            is ElvisExpr -> checkElvis(expr)

            is ForceExpr -> {
                // 操作数要在「错误已处理」的上下文里看：`f()!!` 里的 f 是 !T 调用，
                // 它的错误正是由这个 `!!` 往外抛的，不该再报「没处理」。
                val operand = handling(expr.operand) { type(expr.operand) }
                val errFn = errorCallOf(expr.operand)
                when {
                    // `f()!!` —— 把一个 !T 调用的错误往上抛
                    errFn != null -> {
                        if (operand.isVoid) {
                            throw err(
                                "'!!' 不能用在 '!void' 调用上：'$errFn' 没有值可以取。" +
                                    "要处理它的错误，用 try 包起来，或者直接把它当语句调用",
                                expr.at,
                            )
                        }
                        if (operand.isNullable) {
                            throw err(
                                "暂不支持在同一处同时用可空与错误返回：'$errFn' 既可能失败、" +
                                    "返回的又是 ${operand.rsfName}，'!!' 到底是解包还是抛错说不清。" +
                                    "先用 '?:' 给个兜底值，或者拆成两句（先 var 接住，再单独解包）",
                                expr.at,
                            )
                        }
                        if (!currentFn.ret.mayError && tryDepth == 0) {
                            throw err(
                                "'!!' 是把错误往上抛，所以要么本函数自己也声明可能失败（返回类型写成 !T，" +
                                    "例如把 '${currentFn.name}' 改成 '!${currentReturn.rsfName}'），" +
                                    "要么把它包进 try 块里。现在两样都不是：" +
                                    "返回类型是 ${currentFn.ret.display()}，这里也不在任何 try 块内。" +
                                    "不想改就用 '?:' 给个兜底值",
                                expr.at,
                            )
                        }
                        operand
                    }

                    operand.isNullLiteral -> {
                        throw err("'!!' 不能用在 null 字面量上（它一定是 null）", expr.at)
                    }

                    !operand.isNullable -> {
                        throw err(
                            "'!!' 只能用在可空值上，${operand.rsfName} 本来就是非空的",
                            expr.at,
                        )
                    }

                    else -> operand.asNonNull()
                }
            }

            is AsExpr -> checkAs(expr)

            is IsExpr -> checkIs(expr)

            is MemberExpr -> checkMember(expr)
        }

    // ------------------------------------------------------- lambda 字面量（阶段 30）

    /**
     * `lam` 字面量（DESIGN-30 §2）。
     *
     * 形参类型和 `-> 返回类型` 都能省：省了就借**期望类型**（`var f : (int) -> int = lam(x) …`
     * 里那个 `(int) -> int`）——借不到就是 §5 第 2 条。捕获一律**按值**：
     * 体里读到外层的 `var` 就是 §5 第 1 条（`imutvar` / 形参 / `this` 都行）。
     */
    private fun checkLambda(expr: LambdaExpr): RsfType {
        val expected = expectation?.takeIf { it.isFunctionType }
        val expectedSig = expected?.let { fnSignature(it) }

        // 1) 形参类型：写了就解析，没写就借期望类型的（借不到报 §5 第 2 条）
        val paramTypes = ArrayList<RsfType>(expr.params.size)
        for ((i, p) in expr.params.withIndex()) {
            if (!p.type.isMissing) {
                paramTypes.add(resolveType(p.type))
                continue
            }
            val borrowed = if (expectedSig != null && i < expectedSig.params.size) {
                expectedSig.params[i]
            } else {
                throw err(
                    "lambda 的参数类型推不出来：'${p.name}' 没写类型，这里也没有期望的函数类型" +
                        "（DESIGN-30）——把参数类型写上，或者先赋给一个有类型的变量",
                    p.at,
                )
            }
            paramTypes.add(borrowed)
        }
        if (expectedSig != null && expectedSig.params.size != expr.params.size) {
            throw err(
                "类型不匹配：这个 lambda 有 ${expr.params.size} 个形参，期望的函数类型" +
                    " ${fnTypeText(expectedSig.params, expectedSig.ret)} 要 ${expectedSig.params.size} 个",
                expr.at,
            )
        }

        // 2) 开一层新作用域：形参名只在 lambda 体里可见（也遮住外面的同名变量）
        val outer = LinkedHashSet<Binding>()
        scope.collectAll(outer)

        val savedScope = scope
        val savedReturn = currentReturn
        val savedProbe = captureProbe
        scope = Scope(savedScope)
        for ((i, p) in expr.params.withIndex()) {
            if (scope.declares(p.name)) {
                throw err("lambda 的形参 '${p.name}' 重名了", p.at)
            }
            // 形参不能赋值，所以一律按 imutvar 记——lambda 体里再读它就是普通参数
            scope.declare(p.name, Binding(p.name, paramTypes[i], false, i, p.at))
        }

        // 3) 返回类型：写的 > 期望类型的 > 从体里的 `rtn 表达式` 推的
        val ret = when {
            expr.declaredReturn != null -> resolveType(expr.declaredReturn)
            expectedSig != null -> expectedSig.ret
            else -> inferLambdaReturn(expr)
        }

        val probe = ArrayList<Pair<VarExpr, Binding>>()
        currentReturn = ret
        captureProbe = probe
        try {
            checkBlock(expr.body, pushScope = false)
        } finally {
            captureProbe = savedProbe
            currentReturn = savedReturn
            scope = savedScope
        }

        // 4) 捕获判定：读到外层绑定的才叫捕获；是 var 就不能按值抓（§5 第 1 条）
        for ((use, binding) in probe) {
            if (!outer.contains(binding)) continue
            if (binding.mutable) {
                throw err(
                    "lambda 只能值捕获：'${binding.name}' 是 var（DESIGN-30）——" +
                        "改成 imutvar，或者把它当参数传进来",
                    use.at,
                )
            }
        }

        return fnType(paramTypes, ret, nullable = false)
    }

    /**
     * lambda 既没写 `-> 类型`、也没有期望类型时，从体里的 `rtn 表达式` 推返回类型。
     *
     * 一个 `rtn 值` 都没有 ⇒ `void`；多个就取第一个（返回类型不一致由 `rtn` 自己的
     * 检查兜住，因为这里把推出来的那个当成了声明的返回类型）。
     */
    private fun inferLambdaReturn(expr: LambdaExpr): RsfType {
        val found = ArrayList<RsfType>()
        collectLambdaReturns(expr.body, found)
        return found.firstOrNull() ?: RsfType.VOID
    }

    private fun collectLambdaReturns(block: Block, out: MutableList<RsfType>) {
        for (stmt in block.statements) {
            when (stmt) {
                is ReturnStmt -> stmt.value?.let { out.add(type(it)) }
                is Block -> collectLambdaReturns(stmt, out)
                is IfStmt -> {
                    collectLambdaReturns(stmt.body, out)
                    stmt.elseBody?.let { collectLambdaReturns(it, out) }
                }
                is WhileStmt -> collectLambdaReturns(stmt.body, out)
                is ForStmt -> collectLambdaReturns(stmt.body, out)
                is TryStmt -> {
                    collectLambdaReturns(stmt.body, out)
                    collectLambdaReturns(stmt.catchBody, out)
                }
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------- 类型转换 / 类型判断（阶段 24）

    /**
     * `操作数 as 目标` / `操作数 ?as 目标`（阶段 24）。
     *
     * 结果类型：`as` → 目标的非空版，`?as` → 目标的可空版（判型失败取 null）。
     */
    private fun checkAs(expr: AsExpr): RsfType {
        val opText = if (expr.safe) "?as" else "as"
        castOperand(expr.operand, opText, expr.at)
        val target = castTarget(expr.type, opText, expr, expr.at)
        return if (expr.safe) target.asNullable() else target.asNonNull()
    }

    /** `操作数 is 目标` / `操作数 !is 目标`（阶段 24）：结果恒为 `bool`，v1 不做智能转换。 */
    private fun checkIs(expr: IsExpr): RsfType {
        val opText = if (expr.negated) "!is" else "is"
        castOperand(expr.operand, opText, expr.at)
        castTarget(expr.type, opText, expr, expr.at)
        return RsfType.BOOL
    }

    /**
     * `as` / `is` 的操作数：必须是**引用类型或数组**。
     *
     * 数值参与不了 JVM 的 `instanceof` / `checkcast`（它们只认引用），rsf 也不打算
     * 在判型里偷偷装箱——要数值转换就走那套显式的函数，见 [castTarget] 的说明。
     * `null` 字面量放行：`null is A` / `null as A` / `null ?as A` 的三种行为已经拍定
     * （DESIGN-24 §3 的 Q2），它们是运行期的事，静态不拦。
     */
    private fun castOperand(operand: Expr, opText: String, at: SourceLine): RsfType {
        val operandType = type(operand)
        if (operandType.isNullLiteral) return operandType
        if (operandType.isVoid) {
            throw err(
                "'$opText' 的操作数必须有值：这里是一个 void 调用，没有东西可以转型 / 判型；" +
                    "先把它改成有返回值的函数",
                at,
            )
        }
        if (operandType.isPrimitive) {
            throw err(
                if (opText.endsWith("is")) {
                    "'$opText' 只用于引用类型和数组，${operandType.rsfName} 是数值类型、" +
                        "不可能是某个类的实例；比较数值直接用 == / < / >"
                } else {
                    "'$opText' 只用于引用类型和数组，${operandType.rsfName} 是数值类型；" +
                        "数值转换用 toByte / toChar / charToInt / intToString / parseInt"
                },
                at,
            )
        }
        return operandType
    }

    /**
     * `as` / `is` 的目标类型（阶段 24）。
     *
     * 四条静态检查里跟目标有关的三条都在这里：
     * - 目标必须是**引用类型或数组**（数值转换有专门函数，不走 `as`）；
     * - 目标**不许可空**（`?A` 由 Parser 拒，这里再兜一层——null 的三种行为由
     *   「写的是 as 还是 ?as」决定，跟目标带不带 `?` 无关）；
     * - **非泛型类型不许写类型实参**（`A&<int>` 里 A 没有类型参数）。
     *
     * 泛型实参写了就是**允许但按擦除判**：目标类型本身照 [resolveType] 解析
     * （所以能查出未知类型、实参个数不对），运行期只看擦除后的那个类。
     * 每次写都发一条 stderr 警告，免得用户以为 `Box&<int>` 和 `Box&<string>` 判得不一样。
     */
    private fun castTarget(ref: TypeRef, opText: String, expr: Expr, at: SourceLine): RsfType {
        // 阶段 30：v1 不许把函数类型当 `as` / `is` 的目标（DESIGN-30 §3 最后一条）。
        // 函数类型在 JVM 上还没定型（30b 才决定 SAM 名字），现在放过去只会让
        // 后端在更远的地方炸，不如在这里直接说清楚。
        if (ref.isFunctionType) {
            throw err("'$opText' 的目标不能是函数类型（DESIGN-30）", at)
        }
        // 先查「非泛型不许写实参」：解析阶段那条 checkArity 的报错说的是
        // 「类型实参个数不对」，这里的口径要直白一点，先拦下来说清楚。
        if (ref.typeArgs.isNotEmpty()) {
            val declared = declaredTypeParams(ref.name)
            if (declared != null && declared.isEmpty()) {
                throw err(
                    "非泛型类型不许写类型实参：'${ref.display()}' 里的 '${ref.name}' " +
                        "没有类型参数，去掉 '&<...>' 写成 '${ref.name}'",
                    at,
                )
            }
        }
        val target = resolveType(ref)
        if (target.isNullable) {
            throw err(
                "目标类型不许写可空：'... $opText ${ref.display()}' 不合法——" +
                    "'null is A' 恒为 false、'null as A' 抛、'null ?as A' 得 null，" +
                    "行为由「写的是 as 还是 ?as」决定，跟目标带不带 '?' 无关；" +
                    "去掉 '?' 写成 '$opText ${ref.display().removePrefix("?")}'",
                at,
            )
        }
        if (target.isVoid) {
            throw err(
                "'$opText' 的目标类型不能是 void：void 没有任何值可以转型 / 判型",
                at,
            )
        }
        if (!target.isArray && target.isPrimitive) {
            throw err(
                if (opText.endsWith("is")) {
                    "'$opText' 的目标类型必须是引用类型或数组，'${ref.display()}' 是数值类型、" +
                        "不可能是某个类的实例；比较数值直接用 == / < / >"
                } else {
                    "'$opText' 的目标类型必须是引用类型或数组，'${ref.display()}' 是数值类型；" +
                        "数值转换用 toByte / toChar / charToInt / intToString / parseInt"
                },
                at,
            )
        }
        castTargets[expr] = target
        if (ref.typeArgs.isNotEmpty()) {
            // 同一个节点只警告一次（type() 可能被多处重复调用）。
            if (castErasureWarned.put(expr, true) == null) {
                diagnostics.add(
                    Diagnostic(
                        Severity.WARNING,
                        "checker",
                        "'$opText' 的泛型实参只用于报错：'${ref.display()}' 在运行期按擦除后的 " +
                            "'${ref.name}' 判（与 JVM 一致），'&<...>' 里的类型换一个结果也一样",
                        currentSource.name,
                        at.line,
                        at.column,
                    ),
                )
            }
        }
        return target
    }

    /** 一个**声明**的类型参数表；名字不是本批声明的类/接口时返回 null（交给 resolveType 报未知类型）。 */
    private fun declaredTypeParams(bare: String): List<String>? {
        classTable[bare]?.let { return it.decl.typeParams }
        interfaceTable[bare]?.let { return it.decl.typeParams }
        return null
    }

    /** 泛型擦除警告的记账本：同一个 `as` / `is` 节点只发一条。 */
    private val castErasureWarned = IdentityHashMap<Expr, Boolean>()

    // ------------------------------------------------------------------ 成员访问

    /** 简单类名 → 内部名。来自
import 语句；java.lang 里的名字甀 MemberResolver 兜底、
*/
    private val classImports = LinkedHashMap<String, String>()
    private val memberTargets = IdentityHashMap<MemberExpr, ResolvedMember>()
    private val resolver = MemberResolver(classImports, ::isSourceSubtype)

    private fun collectImports() {
        for (imp in program.imports) {
            if (imp !is ClassImport) continue // 源文件 目录导入已经由 SourceLoader 处理掉了
            val full = imp.path.replace('.', '/')
            if (ClassPath.find(full) != null) {
                classImports[imp.path.substringAfterLast('.')] = full
                continue
            }
            // 最后一段可能是成员：import java.lang.Math.max
            val owner = imp.path.substringBeforeLast('.', "")
            if (owner.isNotEmpty() && ClassPath.find(owner.replace('.', '/')) != null) {
                classImports[owner.substringAfterLast('.')] = owner.replace('.', '/')
                continue
            }
            throw err(
                "classpath 上找不到 JVM 类 '${imp.path}'：点分路径按 JVM 的类名解析" +
                    "（`a.b.C` 要能对应上 classpath 里的 a/b/C.class）。" +
                    "如果它是个 Java/Kotlin 写的伴生类，那它**不参与 rsf 编译**——" +
                    "先用 `javac -d <目录> X.java` 编出来，再把 <目录> 挂到 classpath 上" +
                    "（`java -cp \"<rsf 的 jar>;<目录>\" ...`，或者让构建脚本把它加进 run 的 classpath）；" +
                    "如果它其实是 rsf 自己写的，改用 `import \"文件.rsf\"` 或 `module \"目录\"`",
                imp.at,
            )
        }
    }

    private fun checkMember(expr: MemberExpr): RsfType {
        val receiver = expr.receiver

        // 阶段 25：`super.方法(...)` / `super(接口).方法(...)`。
        // 必须排在情况 A/B **之前**：`super` 既不是一个变量、也不是能单独求值的表达式
        // （走到 `type(receiver)` 会先撞上「'super' 只能当方法调用的接收者」）。
        if (receiver is SuperExpr) return checkSuperMember(expr, receiver)

        // 情况 A：接收者是类名（而且不是一个变量）→ 静态成员
        if (receiver is VarExpr && scope.lookup(receiver.name) == null) {
            // 枚举常量（阶段 19c）：`TokenKind.IDENT`
            enumTable[receiver.name]?.let { return checkEnumMember(it, expr) }
            // 用户自定义类的静态方法：`Point.origin()`
            classTable[receiver.name]?.let { return checkUserMember(it, expr, wantStatic = true) }
            val cls = resolver.resolveClass(receiver.name)
            if (cls != null) return checkStaticMember(cls, expr)
        }

        // 情况 B：实例成员
        val receiverType = type(receiver)
        if (receiverType.isNullLiteral) {
            throw err("null 上没有成员", expr.at)
        }
        // 枚举值是**类型上的常量**，不是实例上的成员：`k.IDENT` 想表达的其实是
        // 「k 是不是 IDENT」，那是 `==` 的活。这里单列一条，免得用户对着
        // 「既不是 JVM 类，也不是 string」那句猜。
        if (isEnumType(receiverType)) {
            throw err(
                "${receiverType.rsfName} 是枚举类型，枚举值要从**类型**上取：写成 " +
                    "${receiverType.simpleExternalName}.${expr.name}，不要拿变量当接收者。" +
                    "想判断某个值是不是某个常量就写 == 比较（if k == " +
                    "${receiverType.simpleExternalName}.${expr.name}）",
                expr.at,
            )
        }
        if (receiverType.nullable) {
            throw err(
                "可空值上不能直接访问成员（${receiverType.rsfName}）：先用 '?:' 给默认值，" +
                    "或者用 '!!' 解包（'?.' 安全调用还没实现）",
                expr.at,
            )
        }
        // 阶段 13：数组只有 .length 这一个成员，而且它是**属性**（不带括号）
        if (receiverType.isArray) return checkArrayMember(receiverType, expr)
        // 阶段 21：静态类型是**注解接口**（`Marker`）时只剩元素可见。
        // 这条必须排在 userClassOf / interfaceOf 前面：注解伴生类的那个类型
        // （`Marker::Impl`）走的是普通类那条路，而注解接口自己不在 classTable 里。
        if (receiverType.isExternal) {
            annotationTable[receiverType.externalName]?.let { return checkAnnotationMember(it, expr) }
        }
        // 用户自定义类的实例：`p.x` / `p.sum()`
        // 泛型实参要替进成员签名里：`b` 是 `Box&<int>` 时 `b.get()` 才是 int
        userClassOf(receiverType)?.let {
            return checkUserMember(it, expr, wantStatic = false, subst = substitutionFor(it, receiverType.typeArgs))
        }
        // 用户接口的实例（阶段 20）：静态类型是接口时，成员要在**接口及其父接口**里找。
        // 这条分支必须排在 resolver.classOf 前面——rsf 接口的 RsfType 也是
        // `external(名字)`，JVM 那一侧不认识这个名字，会一路报到「既不是 JVM 类」。
        interfaceOf(receiverType)?.let {
            return checkInterfaceMember(it, expr, subst = substitutionFor(it.decl.typeParams, receiverType.typeArgs))
        }
        val className = resolver.classOf(receiverType)
            ?: throw err(
                "${receiverType.rsfName} 上没有成员——它既不是 JVM 类，也不是 string",
                expr.at,
            )

        val args = expr.arguments
        if (args == null) {
            val field = resolver.findField(className, expr.name, wantStatic = false)
                ?: throw err(
                    "'${receiverType.simpleExternalName}' 里没有字段 '${expr.name}'",
                    expr.at,
                )
            memberTargets[expr] = ResolvedField(field)
            return field.type
        }

        // JVM 泛型的擦除互操作：`ArrayList&<int>` 的 `add(Object)` / `get(): Object`
        // 其实是「元素类型」的位置，按实参替换回来。
        // **多个类型实参**（`HashMap&<string, int>`）靠方法签名里的 `TK;` / `TV;` 对齐，
        // 见 [erasedActual]。
        val typeArgs = receiverType.typeArgs
        val element = singleTypeArg(typeArgs)
        val argTypes = args.map { type(it) }
        // 选重载时先按原类型试（`get(int)` 这种），失败再按装箱后的形态试一次
        // （`add(3)` / `put("a", 7)` 到 Java 侧是 `add(Integer)` / `put(String, Integer)`，
        // 而形参是擦除后的 Object）。**只要写了类型实参就试**——多类型实参的
        // `HashMap&<string, int>` 同样靠这一趟才接得住基本类型。
        val method = resolver.findMethod(className, expr.name, argTypes, wantStatic = false)
            ?: if (typeArgs.isNotEmpty()) {
                resolver.findMethod(
                    className, expr.name, argTypes.map { boxForInterop(it) }, wantStatic = false,
                )
            } else {
                null
            }
            ?: throw err(
                "'${receiverType.simpleExternalName}' 里没有匹配的方法 '${expr.name}'" +
                    describeArgs(argTypes) + describeCloseMatches(className, expr.name),
                expr.at,
            )
        // owner 是接口就必须走 INVOKEINTERFACE（`Iterator.hasNext()` 那种）——判定只在这里做一次
        memberTargets[expr] = ResolvedMethod(method, ClassPath.find(className)?.isInterface == true)

        // 形参/返回值里擦除成 Object 的那些，按元素类型/类型实参还原
        val declared = method.paramTypes.mapIndexed { i, p ->
            erasedActual(p, method.paramTypeVars.getOrNull(i), typeArgs) ?: substituteElement(p, element)
        }
        for ((i, arg) in args.withIndex()) {
            val argType = argTypes[i]
            // 只在**确实替换过**的位置上再查一遍：没替换的位置 findMethod 的打分已经保证过
            // 兼容（它的规则比 accepts 宽——`String → CharSequence` 那种靠类层次匹配）。
            // 单类型实参时保持老口径（所有位置都查），免得动到既有行为。
            if (element == null && declared[i] === method.paramTypes[i]) continue
            if (!declared[i].accepts(argType)) {
                throw err(
                    "第 ${i + 1} 个实参类型不匹配：'${expr.name}' 需要 ${declared[i].rsfName}，" +
                        "实际给了 ${argType.rsfName}" + erasedHint(receiverType, element),
                    arg.at,
                )
            }
        }
        return erasedActual(method.returnType, method.returnTypeVar, typeArgs)
            ?: substituteElement(method.returnType, element)
    }

    /** 类型实参还原失败时的补充说明（单实参说元素类型，多实参列出来）。 */
    private fun erasedHint(receiver: RsfType, element: RsfType?): String =
        if (element != null) {
            "（${receiver.rsfName} 的元素类型是 ${element.rsfName}）"
        } else {
            "（${receiver.rsfName} 的类型实参是 " +
                receiver.typeArgs.joinToString(", ") { it.rsfName } + "）"
        }

    /**
     * 数组不能直接打印 / 拼进字符串（阶段 13）。
     *
     * 不是为了难为用户，而是为了守住「两端逐字节一致」：JVM 上数组的 `toString()`
     * 就是身份哈希（`[I@1b6d3586`），**每次运行都不一样**——这种输出没法断言、
     * 也没有意义。要看清内容就按下标逐个取（`a[0]` / `a.length`）。
     */
    private fun arrayPrintHint(arrayType: RsfType): String =
        "数组不能直接打印或拼进字符串（${arrayType.rsfName}）：JVM 上打出来的是身份哈希" +
            "（像 [I@1b6d3586），每次运行都不同，两端没法保证一致。" +
            "要看清内容就按下标逐个取，比如 a[0]、a.length，或者用循环"

    /**
     * 数组的成员访问（阶段 13）：只认 `.length`，而且它是**属性**不是方法。
     *
     * `a.length` 而不是 `length(a)` 或 `a.length()`：数组的长度是它自己的性质，
     * 不是一个可以对别的对象调用的函数——`a.length()` 这种写法在这里就直接给出
     * 「不要括号」的提示，省得用户对着 `no method 'length'` 猜。
     */
    private fun checkArrayMember(arrayType: RsfType, expr: MemberExpr): RsfType {
        if (expr.name == "length") {
            if (expr.arguments == null) return RsfType.INT
            throw err(
                "数组的 length 是**属性**不是方法：写成 数组名.length，不要括号" +
                    "（要长度就是 a.length，要取元素才是 a[下标]）",
                expr.at,
            )
        }
        throw err(
            "${arrayType.rsfName} 是数组，只有 .length 这一个成员（元素类型是 " +
                "${arrayType.elementType.rsfName}），实际写的是 '.${expr.name}'。" +
                "元素要按下标取：a[0]；字符串那种才用成员方法（s.length()）",
            expr.at,
        )
    }

    /**
     * 枚举常量访问：`TokenKind.IDENT`（阶段 19c）。
     *
     * 三件事：
     * - 名字必须是这个枚举里的一个值（**不是就报错并列出全部值**——写错值名是
     *   这类代码最常见的错，而「有哪些值」这句话本身就把答案给了）；
     * - 不能带实参表（`TokenKind.IDENT()` 是在把常量当方法调）；
     * - 结果类型就是这个枚举（不是「某个子类型」）——枚举值之间可以 `==` 比较。
     *
     * 解析结果记进 [enumValueRefs]：两个后端都靠它知道该取哪个单例
     * （字节码 `GETSTATIC TokenKind.IDENT`，解释器取同一张常量表里的实例）。
     */
    private fun checkEnumMember(decl: EnumDecl, expr: MemberExpr): RsfType {
        val value = decl.values.firstOrNull { it.name == expr.name }
            ?: throw err(
                "枚举 '${decl.name}' 里没有值 '${expr.name}'" + enumValueList(decl.name),
                expr.at,
            )
        if (expr.arguments != null) {
            throw err(
                "'${decl.name}.${expr.name}' 是枚举常量，不是方法——取它不用写括号",
                expr.at,
            )
        }
        enumValueRefs[expr] = EnumValueRef(decl.name, value.name)
        return RsfType.external(decl.name)
    }

    private fun checkStaticMember(cls: JvmClass, expr: MemberExpr): RsfType {
        val args = expr.arguments
        if (args == null) {
            val field = resolver.findField(cls.internalName, expr.name, wantStatic = true)
                ?: throw err(
                    "'${cls.simpleName}' 里没有静态字段 '${expr.name}'" +
                        if (resolver.findField(cls.internalName, expr.name, wantStatic = false) != null) {
                            "（有同名的实例字段，需要先有对象）"
                        } else "",
                    expr.at,
                )
            memberTargets[expr] = ResolvedField(field)
            return field.type
        }

        val argTypes = args.map { type(it) }
        val method = resolver.findMethod(cls.internalName, expr.name, argTypes, wantStatic = true)
            ?: throw err(
                "'${cls.simpleName}' 里没有匹配的静态方法 '${expr.name}'" +
                    describeArgs(argTypes) + describeCloseMatches(cls.internalName, expr.name),
                expr.at,
            )
        memberTargets[expr] = ResolvedMethod(method, cls.isInterface)
        return method.returnType
    }

    private fun describeArgs(argTypes: List<RsfType>): String =
        "（实参：${argTypes.joinToString(", ") { it.rsfName }}）"

    /** 名字对但签名不对时，把候选签名列出来，省得用户猜。
*/
    private fun describeCloseMatches(className: String, name: String): String {
        val candidates = ClassPath.hierarchy(className)
            .flatMap { it.methods }
            .filter { it.name == name }
        if (candidates.isEmpty()) return ""
        // 分隔符用「；」而不是「（」：`display()` 自己不带括号，原来那句会把候选串成
        // 「…（Collections.sort(List, Comparator) -> void」——少一个右括号的残句。
        return "。同名的有：" + candidates.take(6).joinToString("；") {
            it.display(className.substringAfterLast('/'))
        }
    }

    /**
     * 三目 / `?:` 两边的**公共类型**（阶段 20 起带源码子类型收敛）。
     *
     * 先走 [RsfType.promote]，只有当它把两个**不同的引用类型**都退成 `Object` 时，
     * 才回头按源码子类型关系取父的那一边。
     *
     * 为什么要补这一刀：[RsfType.promote] 的注释说「两边都是引用类型时退到 Object
     * （和 javac 的合流规则一致）」，但 javac 给的是最近的公共父类型。语言里有了接口
     * 之后这条会咬人——`var g : Greeter = must() ?: new Dog()` 里 `!Greeter` 与 `Dog`
     * 的公共类型本该是 `Greeter`，退成 `Object` 就报「不能把 Object 赋给 Greeter 变量」。
     */
    private fun promoteCommon(a: RsfType, b: RsfType): RsfType? {
        val merged = RsfType.promote(a, b) ?: return null
        // 基本类型、null 字面量，以及 promote 已经合出具体类型的情形，一律照旧
        if (a.isPrimitive || b.isPrimitive) return merged
        if (a.isNullLiteral || b.isNullLiteral) return merged
        if (!merged.isExternal || merged.externalName != "java/lang/Object") return merged
        val nullable = a.nullable || b.nullable
        return when {
            isSourceSubtype(a, b) -> a.copy(nullable = nullable)
            isSourceSubtype(b, a) -> b.copy(nullable = nullable)
            else -> merged
        }
    }

    /**
     * `a ?: b`：左边可空、右边兜底，结果是非空版。
     *
     * 阶段 14 起它还有第二个用法：**左边是一个 `!T` 调用**时，出错就取右边的值
     * （`parseInt("") ?: -1`）。两种情况互相排斥，所以先用 `errorCallOf` 分流。
     */
    private fun checkElvis(expr: ElvisExpr): RsfType {
        // 左边的错误由这个 `?:` 兜住，所以这里不再要求调用点处理
        val left = handling(expr.left) { type(expr.left) }
        val right = type(expr.right)
        if (right.isVoid) {
            throw err("'?:' 右边不能是 void 表达式", expr.at)
        }

        val errFn = errorCallOf(expr.left)
        if (errFn != null) {
            if (left.isVoid) {
                throw err(
                    "'?:' 左边是 '!void' 调用（'$errFn' 没有值可以取），没有东西可以兜底",
                    expr.at,
                )
            }
            if (left.isNullable) {
                throw err(
                    "暂不支持在同一处同时用可空与错误返回：'$errFn' 既可能失败、" +
                        "返回的又是 ${left.rsfName}。拆成两句写：先用 try 或 '!!' 把错误处理掉，" +
                        "再用 '?:' 处理 null",
                    expr.at,
                )
            }
            // 正常路径上左边给的就是 T，所以结果类型跟普通 `?:` 一样两头求共同类型
            return promoteCommon(left, right)
                ?: throw err(
                    "'?:' 两边类型对不上：左边 ${left.rsfName}，右边 ${right.rsfName}",
                    expr.at,
                )
        }

        if (!left.isNullable && !left.isNullLiteral) {
            // 左边本来就不为空，右边永远用不上；Kotlin 这里只是警告，我们也放行
            return if (RsfType.promote(left, right) != null) left else left
        }
        return promoteCommon(left.asNonNull(), right)
            ?: throw err(
                "'?:' 两边类型对不上：左边 ${left.rsfName}，右边 ${right.rsfName}",
                expr.at,
            )
    }

    private fun checkIfExpr(expr: IfExpr): RsfType {
        val condType = type(expr.condition)
        if (condType != RsfType.BOOL) {
            throw err(
                "三目运算符 'if 条件 ? 值1 : 值2' 的条件必须是 bool，实际是 ${condType.rsfName}",
                expr.condition.at,
            )
        }
        val t = type(expr.whenTrue)
        val f = type(expr.whenFalse)
        if (t.isVoid || f.isVoid) {
            throw err("三目运算符的两个分支不能是 void", expr.at)
        }
        return promoteCommon(t, f)
            ?: throw err(
                "三目运算符两个分支的类型不一致：${t.rsfName} 和 ${f.rsfName}",
                expr.at,
            )
    }

    // ------------------------------------------------------------------ 错误返回 !T（阶段 14）

    /**
     * 在这个上下文里检查表达式时，只有 [node] 这**一个**调用算「错误已处理」
     * （`?:` 的左边、`!!` 的操作数——错误由它们自己兜住或往外抛）。
     *
     * 嵌套的 `?:` / `!!` 会临时换掉 [handledNode]，所以这里存一份、出来再还回去。
     */
    private inline fun <T> handling(node: Expr, block: () -> T): T {
        val saved = handledNode
        handledNode = node
        try {
            return block()
        } finally {
            handledNode = saved
        }
    }

    /**
     * 这个表达式是不是一次「可能失败」的调用（`!T` 函数 / `!T` 方法 / `parseInt`），
     * 是的话返回**名字**（报错用），否则返回 null。
     *
     * 必须在表达式已经 [type] 过之后调用——解析结果（[callTargets] / [implicitCalls] /
     * [userMembers]）就是判断依据，不用再重新解析一遍名字。内置函数没有 `FuncDecl`
     * 可以查，所以按名字查 [Builtins]（内置函数名不允许被用户函数占用，查名字是安全的）。
     */
    private fun errorCallOf(expr: Expr): String? {
        val fn = when (expr) {
            is CallExpr -> callTargets[expr] ?: implicitCalls[expr]?.decl
            is MemberExpr -> (userMembers[expr] as? UserMember.Method)?.decl
            else -> null
        }
        if (fn != null) return if (fn.ret.mayError) fn.name else null
        if (expr is CallExpr) return Builtins[expr.name]?.takeIf { it.mayError }?.name
        return null
    }

    /**
     * 调用一个「可能失败」的东西（`!T` 函数 / `!T` 方法 / `parseInt`）必须显式处理错误，
     * 否则编译期拦住。
     *
     * [retDisplay] 是返回类型的**源码写法**（`!int` 里那个 `int`），内置函数没有源码写法，
     * 直接给它的类型名。三种处理方式都写进报错里——用户看到这条错就该知道下一步改哪里。
     */
    private fun requireErrorHandled(name: String, retDisplay: String, node: Expr, at: SourceLine) {
        if (tryDepth > 0) return
        if (node === handledNode) return
        throw err(
            "'$name' 的返回类型是 '$retDisplay'（可能失败），" +
                "调用它必须显式处理错误。三种写法任选一种：" +
                "(a) 包进 try { ... } catch (e : string) { ... }；" +
                "(b) 给兜底值 ${name}(...) ?: 默认值；" +
                "(c) 往上抛 ${name}(...)!!（这样本函数的返回类型也得写成 !T）",
            at,
        )
    }

    private fun checkCall(expr: CallExpr): RsfType {
        if (expr.name in Builtins.PRINT_LIKE) {
            if (expr.arguments.size > 1) {
                throw err(
                    "'${expr.name}' 只接受 0 或 1 个参数，实际给了 ${expr.arguments.size} 个",
                    expr.at,
                )
            }
            if (expr.arguments.isEmpty() && expr.name == "print") {
                throw err("print() 至少需要一个参数（只想换行请用 println()）", expr.at)
            }
            if (expr.arguments.size == 1) {
                val argType = type(expr.arguments[0])
                if (argType == RsfType.VOID) {
                    throw err("'${expr.name}' 的参数不能是 void 表达式", expr.at)
                }
                if (argType.isArray) throw err(arrayPrintHint(argType), expr.at)
            }
            return RsfType.VOID
        }

        // 阶段 16：带签名的内置函数（toByte / toChar / charToInt / parseInt / isDigit…）
        Builtins[expr.name]?.let { return checkBuiltinCall(expr, it) }

        // **自动 new**：名字解析到「可以构造的类型」就当构造处理，
        // `Point(1, 2)` 与 `new Point(1, 2)` 走的是同一段 [checkConstruction]。
        // 判据只有「这个名字是不是用户类 / 导入的 JVM 类」——无歧义，因为
        // 「类名与函数名不能重名」在 check() 开头就报掉了（见 declareFunctions）。
        if (isConstructionName(expr.name)) {
            return checkConstruction(expr.name, emptyList(), expr.arguments, expr)
        }

        // 阶段 30：调的是**函数值**而不是函数名。
        // ① `f!!(1)`：Parser 已经把 callee 挂在 CallExpr 上；
        // ② `f(1)`：`f` 是局部变量 / 形参，类型是函数类型——局部变量遮蔽同名函数
        //    （跟普通变量遮蔽一个道理，写起来也最符合直觉）。
        if (expr.callee != null) {
            return checkFunctionValueCall(expr, null, expr.callee)
        }
        scope.lookup(expr.name)?.let { b ->
            if (b.type.isFunctionType) return checkFunctionValueCall(expr, b, null)
        }

        val named = functionTable[expr.name]
        if (named.isNullOrEmpty()) {
            // 类里可以直接按名字调本类方法：`shift(x, y)`（实例方法隐式带 this）
            // static 方法隐式带类名）。这在方法体里是最自然的写法，不能只认顶层函数。
            val info = currentClass
            if (info != null) {
                // 阶段 25 K1（G2）：**隐式 this 的成员查找也要沿父类链走**——
                // `base()` 写在父类里、子类方法体里不带接收者照样调得到。
                if (declaredMethodOnChain(info, expr.name) { !it.isConstructor } != null) {
                    return checkImplicitCall(info, expr)
                }
                // 名字在父类链上、但那是个 static：static 不继承（DESIGN-25 §3），
                // 给一句照着改就能过的报错，而不是「未定义的函数」。
                inheritedStaticHint(info, expr.name)?.let { throw err(it, expr.at) }
                // 阶段 25 K1（G5）：JDK 父类继承来的实例方法（`getMessage()`）。
                checkImplicitJvmCall(info, expr)?.let { return it }
            }
            // 接口的默认方法体里同理：`shout` 里写 `greet(name)`（隐式带 this，阶段 20）
            val iface = currentInterface
            if (iface != null && !interfaceMethodsByName(iface)[expr.name].isNullOrEmpty()) {
                return checkImplicitInterfaceCall(iface, expr)
            }
            throw err("未定义的函数 '${expr.name}'", expr.at)
        }

        // 候选先按**可见性**过滤：private 顶层函数只有它自己那个文件能调。
        // 全都调不动时按老规矩报 private 的错（而不是「没有这个函数」）。
        val visible = named.filter { isVisible(it) }
        if (visible.isEmpty()) checkVisible(named[0], expr.at)

        val candidates = visible.map { OverloadCandidate(it, paramTypesOfDecl(it)) }
        val sameArity = candidates.filter { it.params.size == expr.arguments.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用 '${expr.name}' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }

        // 实参先求类型：void 与「不能当参数传」的老报错保持原样。
        // 阶段 30：某个位置上的候选形参类型**完全一致**时，把它当期望类型喂给实参，
        // 这样 `each(xs, lam(n) { ... })` 的 `lam` 才知道形参类型与返回类型。
        val expectAt = (0 until expr.arguments.size).map { i ->
            val distinct = sameArity.mapNotNull { it.params.getOrNull(i) }.distinct()
            // 阶段 31：形参里还有类型参数占位（`T`）时**不当**期望类型——`T` 是不透明的，
            // 拿它去推 `lam` 的形参只会把「未知类型」传给用户。
            if (distinct.size == 1 && !distinct.single().mentionsTypeParam()) distinct.single() else null
        }
        val argTypes = ArrayList<RsfType>(expr.arguments.size)
        for ((i, arg) in expr.arguments.withIndex()) {
            val argType = withExpectation(expectAt[i]) { type(arg) }
            if (argType == RsfType.VOID) {
                throw err("第 ${i + 1} 个实参是 void 表达式，不能当参数传", arg.at)
            }
            argTypes.add(argType)
        }

        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = expr.arguments,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> functionMismatch(expr.name, c, i, argTypes[i]) }

        val target = chosen.decl
        callTargets[expr] = target
        if (target.ret.mayError) {
            requireErrorHandled(target.name, "!${target.ret.type.display()}", expr, expr.at)
        }
        return instantiateReturn(expr, chosen, returnTypes[target] ?: RsfType.VOID)
    }

    /** 「参数类型不匹配」的顶层函数版落款（沿用阶段 10 的老文案，带上形参名）。 */
    private fun functionMismatch(
        name: String,
        c: OverloadCandidate,
        i: Int,
        got: RsfType,
    ): String =
        "参数类型不匹配：'$name' 的第 ${i + 1} 个参数 " +
            "'${c.decl.params[i].name}' 需要 ${c.params[i].rsfName}，实际给人 ${got.rsfName}"

    /**
     * 类里直接按名字调本类方法：`shift(x, y)`。
     *
     * 实例方法隐式带上当前对象（`this.shift(...)` 的简写），static 方法隐式带上类名。
     * 结果记进 [implicitCalls]，解释器才知道要不要塞 this。
     * 类体内部 private 一律可见，static / 实例两种都能这么调，所以这里不做这两项过滤。
     */
    private fun checkImplicitCall(info: ClassInfo, expr: CallExpr): RsfType {
        // 阶段 25 K1（G2）：候选要沿**父类链**收集，父类那一层滤掉 static / private
        // （它们不继承，DESIGN-25 §3）；同一个签名「近的类优先」——那就是虚分派的结果。
        val chain = LinkedHashMap<String, Pair<ClassInfo, FuncDecl>>()
        info.methods[expr.name]?.forEach { m ->
            if (m.isConstructor) return@forEach
            chain.putIfAbsent(signatureKey(m.name, paramTypesOfDecl(m)), info to m)
        }
        for (anc in ancestorsOf(info).reversed()) {
            anc.methods[expr.name]?.forEach { m ->
                if (m.isConstructor || m.isStatic || m.control == ControlType.PRIVATE) return@forEach
                chain.putIfAbsent(signatureKey(m.name, paramTypesOfDecl(m)), anc to m)
            }
        }
        val named = chain.values.toList()
        // 形参按**声明它的那个类**那一层代换（G4：`get() -> T` 在
        // `class IntHolder : Holder&<int>` 上要变成 `get() -> int`）。
        val candidates = named.map { (owner, m) ->
            OverloadCandidate(
                m,
                memberParamTypes(m).map { t -> t.substitute(memberSubstFor(m, substitutionFromInside(info, owner))) },
            )
        }
        val sameArity = candidates.filter { it.params.size == expr.arguments.size }
        if (sameArity.isEmpty()) {
            throw err(
                "调用 '${expr.name}' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    describeCandidates(candidates),
                expr.at,
            )
        }
        val argTypes = expr.arguments.map { type(it) }
        val chosen = resolveOverload(
            what = "调用 '${expr.name}'", candidates = sameArity, args = expr.arguments,
            argTypes = argTypes, at = expr.at,
        ) { c, i -> memberMismatch(expr.name, c, i, argTypes[i]) }

        val method = chosen.decl
        // owner 取**声明它的类**：继承来的方法记父类名，后端据此发 invokevirtual
        val declarer = named.first { (_, m) -> m === method }.first
        implicitCalls[expr] = UserMember.Method(declarer.decl.name, method)
        if (method.ret.mayError) {
            requireErrorHandled(method.name, "!${method.ret.type.display()}", expr, expr.at)
        }
        return instantiateReturn(
            expr, chosen,
            memberReturnType(method).substitute(memberSubstFor(method, substitutionFromInside(info, declarer))),
        )
    }

    /**
     * 名字在父类链上、但只有 **static** 版本时给的可操作报错（阶段 25 K1，G2）。
     *
     * static 不继承（DESIGN-25 §3）：只能写 `父类名.方法(...)`。没这一支的话，
     * 用户看到的是「未定义的函数 'make'」，而真正的问题是他把 static 方法当实例方法用了。
     */
    private fun inheritedStaticHint(info: ClassInfo, name: String): String? {
        val owner = declaredMethodOnChain(info, name) { it.isStatic }?.first ?: return null
        return "'$name' 是 '${owner.decl.name}' 的 stc 方法，不继承：" +
            "静态方法只能写「类名.方法(...)」——写成 ${owner.decl.name}.$name(...)（DESIGN-25 §3）"
    }

    /**
     * 隐式 this 调用 **JDK 父类继承来**的方法（阶段 25 K1，G5）。
     *
     * `class MyError(msg : string) : Exception(msg) { fun describe() -> string { rtn
     * getMessage() } }`：`getMessage` 不在任何 rsf 类型上，只有 JDK 的类层次知道它。
     * 解析结果记进 [implicitJvmCalls]（那里的值是 [ResolvedMethod]，rsf 的
     * [FuncDecl] 表达不了 JDK 方法）；找不到就返回 null，让 [checkCall] 照旧报
     * 「未定义的函数」。
     */
    private fun checkImplicitJvmCall(info: ClassInfo, expr: CallExpr): RsfType? {
        if (jvmParentOf(info) == null) return null
        val argTypes = expr.arguments.map { type(it) }
        val method = inheritedJvmMethod(info, expr.name, argTypes, expr.at) ?: return null
        implicitJvmCalls[expr] = ResolvedMethod(method, ClassPath.find(method.owner)?.isInterface == true)
        return if (method.returnType.isVoid) RsfType.VOID else method.returnType
    }

    /**
     * 可见性检查（`<ControlType>` 的语义）。
     *
     * - `private`：只有**声明它的那个 .rsf 文件**能调。它编译出来是 `ACC_PRIVATE`，
     *   跨文件调用在解释器里跑得通、在字节码里会抛 `IllegalAccessError`——这种分歧必须在
     *   编译期就拦住，否则「两端输出逐字节一致」这条承诺就破了。
     * - `protected` / `public` / 缺省：同一次编译展开进来的文件都能调。rsf 生成的类都在同一个
     *   默认包里，所以 `ACC_PROTECTED` 的效果正好等于「同一批代码之间可见」。
     */
    private fun checkVisible(target: FuncDecl, at: SourceLine) {
        if (target.control != ControlType.PRIVATE) return
        val declSource = sourceOfFunction[target] ?: source
        if (declSource === currentSource) return
        throw err(
            "'${target.name}' 是 prv 的，只能在它自己的文件（${declSource.name}）里调用。" +
                "要让别的文件也能用，把它改成 prot 或 pub",
            at,
        )
    }

    /**
     * 这个顶层函数在当前文件里调得动吗（private 只在自己那个文件里可见）。
     *
     * 重载解析**先按可见性过滤候选再打分**：同名重载里若既有 private 又有 public，
     * 从别的文件调用时 private 那个根本不参与打分，不会出现「看不见的重载把
     * 看得见的挤掉」这种结果。
     */
    private fun isVisible(target: FuncDecl): Boolean {
        if (target.control != ControlType.PRIVATE) return true
        return (sourceOfFunction[target] ?: source) === currentSource
    }

    /**
     * 带签名的内置函数调用（阶段 16）。
     *
     * 签名在 [Builtins] 那张表里写死，这里只做参数个数与类型检查，再加上
     * 「可能失败的内置函数也要显式处理错误」——`parseInt` 和 rsf 自己写的 `!T`
     * 函数走的是同一套规矩（try / `?:` / `!!`），用户不用记两套。
     */
    // ------------------------------------------------------- 调用函数值（阶段 30）

    /**
     * 调用一个**函数值**：`f(1, 2)`（`f` 是函数类型的变量 / 形参）或 `f!!(1)`。
     *
     * 可空函数值必须先 `!!`：`?fn` 不可调用是 DESIGN-30 §5 第 3 条（跟 JVM 上
     * 「引用可能是 null」是同一件事，这里在**编译期**就拦住）。
     */
    private fun checkFunctionValueCall(expr: CallExpr, binding: Binding?, callee: Expr?): RsfType {
        val calleeType = when {
            callee != null -> type(callee)
            binding != null -> binding.type
            else -> throw err("未定义的函数 '${expr.name}'", expr.at)
        }
        val label = expr.name.ifEmpty { calleeType.rsfName }

        if (!calleeType.isFunctionType || calleeType.arrayDepth > 0) {
            throw err("'$label' 不是函数，不能调用（${calleeType.rsfName}）", expr.at)
        }
        if (calleeType.isNullable) {
            throw err(
                "'$label' 是可空的函数值，先断言非空（f!!）再调用（DESIGN-30）",
                expr.at,
            )
        }
        val sig = fnSignature(calleeType)
            ?: throw err("'$label' 不是函数，不能调用（${calleeType.rsfName}）", expr.at)
        if (sig.params.size != expr.arguments.size) {
            throw err(
                "调用 '$label' 的参数个数不对：实际给了 ${expr.arguments.size} 个，" +
                    "函数类型 ${calleeType.rsfName} 要 ${sig.params.size} 个",
                expr.at,
            )
        }
        for ((i, arg) in expr.arguments.withIndex()) {
            val want = sig.params[i]
            val argType = withExpectation(want) { type(arg) }
            if (argType == RsfType.VOID) {
                throw err("第 ${i + 1} 个实参是 void 表达式，不能当参数传", arg.at)
            }
            if (!want.accepts(argType)) {
                throw err(
                    "第 ${i + 1} 个实参类型不匹配：'$label' 的第 ${i + 1} 个参数要 ${want.rsfName}，" +
                        "实际给了 ${argType.rsfName}",
                    arg.at,
                )
            }
        }
        return sig.ret
    }

    private fun checkBuiltinCall(expr: CallExpr, builtin: Builtin): RsfType {
        if (expr.arguments.size != builtin.params.size) {
            throw err(
                "'${expr.name}' 的参数个数不对：需要 ${builtin.params.size} 个" +
                    "（${builtin.display()}），实际给了 ${expr.arguments.size} 个",
                expr.at,
            )
        }
        for ((i, arg) in expr.arguments.withIndex()) {
            val argType = type(arg)
            if (argType == RsfType.VOID) {
                throw err("第 ${i + 1} 个实参是 void 表达式，不能当参数传", arg.at)
            }
            val want = builtin.params[i]
            if (!acceptsValue(want, arg, argType)) {
                throw err(
                    "参数类型不匹配：'${builtin.name}' 的第 ${i + 1} 个参数需要 ${want.rsfName}，" +
                        "实际给人 ${argType.rsfName}" + builtinHint(builtin.name, argType),
                    arg.at,
                )
            }
        }
        if (builtin.mayError) {
            requireErrorHandled(builtin.name, "!${builtin.ret.rsfName}", expr, expr.at)
        }
        return builtin.ret
    }

    /**
     * 参数类型对不上时补一句「该用什么转」。
     *
     * byte / char 与 int 之间没有隐式转换（见 RsfType.kt 的加宽表），而 rsf 又没有
     * `(char) x` 这种强制转换语法——踩坑的人只会看到「需要 char，实际给人 int」，
     * 所以这里把该写的函数名直接说出来。
     */
    private fun builtinHint(name: String, got: RsfType): String = when {
        // 「要 char、给了 int」：除了 toChar 自己（它要的就是 int）
        got.base == RsfBase.INT && name != "toChar" && wantsChar(name) ->
            "；'$name' 要的是 char，int 先用 toChar(...) 转过来"

        // 「要 int、给了 char」
        got.base == RsfBase.CHAR && name == "toByte" ->
            "；char 要先 charToInt(...) 取码点，再 toByte(...)"

        got.base == RsfBase.CHAR && name == "intToString" ->
            "；char 要先 charToInt(...) 取码点，再 intToString(...)"

        got.base == RsfBase.CHAR && name == "toChar" ->
            "；它本来就是 char，不用再转"

        else -> ""
    }

    /** 这个内置函数的第一个参数是不是 `char`。 */
    private fun wantsChar(name: String): Boolean {
        val builtin = Builtins[name] ?: return false
        return builtin.params.firstOrNull()?.base == RsfBase.CHAR
    }

    /**
     * `add(a : int, b : long)` 这样的可读签名，用于报错。
     *
     * 渲染成 **Kotlin 后缀风格**（参数两种风格都能写，但示例与文档统一用这一种，
     * 报错里跟着走省得用户以为漏了什么）。
     *
     * [params] 允许显式给出：成员调用要先按接收者的类型实参把 `T` 换掉，
     * 报错里就该说换过之后的类型，而不是类体内的 `T`。
     */
    private fun signatureOf(fn: FuncDecl, params: List<RsfType> = paramTypesOfDecl(fn)): String {
        val parts = fn.params.mapIndexed { i, p ->
            "${p.name} : ${params.getOrNull(i)?.rsfName ?: p.type.name}"
        }
        return "${fn.name}(${parts.joinToString(", ")})"
    }

    private fun checkBinary(expr: BinaryExpr): RsfType {
        val left = type(expr.left)
        val right = type(expr.right)
        val op = expr.op

        if (left.isVoid || right.isVoid) {
            throw err("void 表达式不能参与运算", expr.at)
        }

        // `&&` / `||`（阶段 19c）排在最前面：它们**只**收 bool，别的什么都不收，
        // 所以 `null && x`、`"a" && b` 应该报「两边必须是 bool」，而不是掉进下面
        // 那条「null 只能用来做 == / !=」——后者的提示对这种写法没有指导性。
        if (op == TokenKind.ANDAND || op == TokenKind.OROR) {
            return checkLogical(expr, left, right, op)
        }

        if ((left.isNullLiteral || right.isNullLiteral) && op != TokenKind.EQ && op != TokenKind.NE) {
            throw err("null 只能用来做 '==' / '!=' 比较", expr.at)
        }

        return when (op) {
            TokenKind.PLUS, TokenKind.MINUS, TokenKind.STAR, TokenKind.SLASH, TokenKind.PERCENT -> {
                // 字符串拼接：任意一边是 string 就按拼接走（可空也行，null 会拼成 "null"）
                if (op == TokenKind.PLUS &&
                    (left.base == RsfBase.STRING || right.base == RsfBase.STRING)
                ) {
                    // 数组那边先拦住：JVM 上数组 toString 是身份哈希，两端没法一致。
                    // 注意 `string[]` 的 base 也是 string，所以这里只问 isArray
                    if (left.isArray) throw err(arrayPrintHint(left), expr.at)
                    if (right.isArray) throw err(arrayPrintHint(right), expr.at)
                    RsfType.STRING
                } else {
                    if (left.isNullable || right.isNullable) {
                        throw err(
                            "可空值不能直接参与运算（${left.rsfName} ${op.text} ${right.rsfName}）：" +
                                "先用 '?:' 给个默认值，或者用 '!!' 解包",
                            expr.at,
                        )
                    }
                    // 算术结果的类型用 RsfType.arithmetic（byte/char 一律提升成 int），
                    // 而不是 promote——promote 里 `a == b` 那条捷径会让 `b + b` 也变成 byte，
                    // 那就不符合 Java 的 binary numeric promotion 了（100 + 100 应当是 200 这个 int）
                    RsfType.arithmetic(left, right)
                        ?: throw err(
                            "运算符 '${op.text}' 需要数值（byte / char / int / long / float / double），" +
                                "实际是 ${left.rsfName} 和 ${right.rsfName}" +
                                if (op == TokenKind.PLUS) "（字符串拼接只支持 '+'）" else "",
                            expr.at,
                        )
                }
            }

            TokenKind.EQ, TokenKind.NE -> {
                when {
                    left.isNullLiteral || right.isNullLiteral -> {
                        val other = if (left.isNullLiteral) right else left
                        if (!other.isNullLiteral && !other.isNullable) {
                            val always = if (op == TokenKind.EQ) "假" else "真"
                            throw err(
                                "${other.rsfName} 是非空类型，跟 null 比较恒为$always。" +
                                    "真想让它可为空就把类型写成 ?${other.rsfName}",
                                expr.at,
                            )
                        }
                        RsfType.BOOL
                    }

                    // 阶段 19c：两个**不同的枚举**之间比较没有意义（永远不等），
                    // 单列一条是为了给出比「两边类型不一致」更有指导性的提示——
                    // 枚举值唯一合法的比较对象就是同一个枚举的值。同一个枚举的两个值
                    // 不在这里拦，它们走下面那条 `left == right` 正常判等。
                    isEnumType(left) && isEnumType(right) &&
                        left.externalName != right.externalName -> throw err(
                        "'${op.text}' 两边是不同的枚举：${left.rsfName} 和 ${right.rsfName}。" +
                            "枚举值只能和同一个枚举的值比较（它们本来就是一组互不相等的单例）",
                        expr.at,
                    )

                    // byte / char / int / long / float / double 之间可以互相比较：
                    // 它们会先提升到共同宽度（byte/char → int），见 RsfType.promote
                    left.isArithmetic && right.isArithmetic -> RsfType.BOOL
                    left == right -> RsfType.BOOL
                    else -> throw err(
                        "'${op.text}' 两边的类型不一致：${left.rsfName} 和 ${right.rsfName}",
                        expr.at,
                    )
                }
            }

            TokenKind.LT, TokenKind.GT, TokenKind.LE, TokenKind.GE -> {
                if (!left.isArithmetic || !right.isArithmetic) {
                    throw err(
                        "'${op.text}' 只能比较非空数值（byte / char / int / long / float / double），" +
                            "实际是 ${left.rsfName} 和 ${right.rsfName}" +
                            "（字符串只能用 == / !=；可空值要先解包）",
                        expr.at,
                    )
                }
                RsfType.BOOL
            }

            else -> throw err("不认识的二元运算符 '${op.text}'", expr.at)
        }
    }

    /**
     * `&&` / `||`（阶段 19c）的类型检查：**两边都必须是 `bool`**，结果也是 `bool`。
     *
     * 三条决定：
     * - **不收可空 bool**（`?bool`）：`a && b` 里 a 是 null 时结果该是什么？没有好答案，
     *   所以要求先解包（`!!`）或给兜底（`?:`），跟算术、比较那边的规矩一致。
     * - **两边分开报**（左边 / 右边），而不是笼统一句「两边必须是 bool」：
     *   `n > 0 && flag()` 里到底哪边错了，只有分开说才一眼看得出来（题目要求
     *   报错文案里带类型名）。
     * - **不求值顺序相关的类型**：这里只做类型检查；「右边到底跑不跑」是运行期的事，
     *   由两端的短路实现负责（解释器不 eval 右边、字节码把右边放在跳转之后）。
     */
    private fun checkLogical(
        expr: BinaryExpr,
        left: RsfType,
        right: RsfType,
        op: TokenKind,
    ): RsfType {
        if (left != RsfType.BOOL) {
            throw err(
                "'${op.text}' 两边必须是 bool，左边是 ${left.rsfName}。" +
                    logicalHint(left),
                expr.left.at,
            )
        }
        if (right != RsfType.BOOL) {
            throw err(
                "'${op.text}' 两边必须是 bool，右边是 ${right.rsfName}。" +
                    logicalHint(right),
                expr.right.at,
            )
        }
        return RsfType.BOOL
    }

    /** `&&` / `||` 收错类型时补一句「该怎么改」。 */
    private fun logicalHint(t: RsfType): String = when {
        t.isNullable -> "先用 '?:' 给个兜底值，或者用 '!!' 解包"
        t.isArray -> "数组不能当条件，要么按下标取元素、要么用 .length 比大小"
        t.base == RsfBase.STRING -> "字符串不能当条件，要判空/判等请写 == \"\" 这种比较"
        t.isArithmetic -> "数值不能当条件，要判非零请写 n != 0 这种比较"
        else -> "（枚举、对象没有真假之分，要先和某个值比较）"
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 类型名 → 类型。
     *
     * 顺序：类型参数（只在 `class X&<T>` 的类体里）→ 内置类型 → 用户自定义类 → 导入的 JVM 类 → 报错。
     * 用户类的类型就是 `RsfType.external(类名)`（rsf 的类都在默认包里，类名本身就是合法的内部名）。
     *
     * [env] 是当前的类型参数环境（`T` → 占位类型）。默认取 [typeParams]——那是
     * 「现在正在检查哪个类的类体」的那个环境，见 [declareClasses] / [checkClassBodies]。
     */
    private fun resolveType(ref: TypeRef, env: Map<String, RsfType> = typeParams): RsfType {
        val base = resolveBaseType(ref, env)
        if (ref.ptrDepth == 0) return base
        if (base.isArray) {
            throw err(
                "指针不能指向数组（'${ref.display()}'）：L1/L2 只做「指向单个值」，" +
                    "数组指针与偏移留到 L3",
                ref.at,
            )
        }
        if (base.isVoid) {
            throw err("指针不能指向 void（'${ref.display()}'）——void 没有值，取不到它的地址", ref.at)
        }
        return base.pointerOf(ref.ptrDepth)
    }

    private fun resolveBaseType(ref: TypeRef, env: Map<String, RsfType> = typeParams): RsfType {
        val nullable = ref.name.startsWith("?")

        // 阶段 30：函数类型。形参 / 返回类型逐个解析，再拼成一个 EXTERNAL 形状。
        // 函数类型没有泛型、也不参与数组维度（`((int) -> int)[]` 的元素类型由
        // Parser 在括号外面那一层已经处理掉了，这里只管非数组的那一层）。
        if (ref.isFunctionType) {
            val params = ref.fnParams.map { resolveType(it, env) }
            val ret = ref.fnRet?.let { resolveType(it, env) } ?: RsfType.VOID
            return fnType(params, ret, nullable = nullable)
        }

        val bare = if (nullable) ref.name.substring(1) else ref.name
        val args = ref.typeArgs.map { resolveType(it, env) }
        val depth = ref.arrayDepth

        /**
         * 套上数组维度（阶段 13）。`T[]` 与 `T` 除了「套了几层 []」之外是同一个类型，
         * 所以数组维度在**最后**统一叠上去，四条解析路径（类型参数 / 内置 / 用户类 /
         * JVM 类）一行都不用各写一遍。
         */
        fun finish(t: RsfType): RsfType {
            if (depth == 0) return t
            if (t.isVoid) {
                throw err("数组的元素类型不能是 void（'${ref.display()}'）——void 没有值，装不进数组", ref.at)
            }
            return t.arrayOf(depth)
        }

        // 1) 类型参数占位：`class Box&<T>` 的类体里 T 当类型用
        env[bare]?.let { placeholder ->
            if (args.isNotEmpty()) {
                throw err(
                    "'$bare' 是类型参数，不是泛型类——它自己不能再带类型实参" +
                        "（期望 0 个，实际给了 ${args.size} 个）",
                    ref.at,
                )
            }
            return finish(if (nullable) placeholder.asNullable() else placeholder)
        }

        // 2) 内置类型
        RsfType.fromName(ref.name)?.let { builtin ->
            if (args.isNotEmpty()) {
                throw err(
                    "'$bare' 是内置类型，没有类型参数）" +
                        "期望 0 个，实际给了 ${args.size} 个（'&<...>' 只能跟在一个类名后面）",
                    ref.at,
                )
            }
            return finish(builtin)
        }

        // 3d-1) 嵌套类型（阶段 21）：源码里只有 `注解名 :: 伴生名` 这一种写法。
        // 必须排在「用户类」之前：`Dog::Impl` 里的 Dog 是个普通类，不能悄悄按
        // `Dog` 解析然后把 `::Impl` 吞掉——那样写错名字反而编过。
        if (ref.nested != null) {
            val nested = ref.nested
            val info = annotationTable[bare]
                ?: throw err(
                    "'$bare' 不是注解，没有 '${ref.display()}' 这种嵌套类型：" +
                        "'::' 只能写成「注解名 :: 伴生名」（例如 Marker::Impl）",
                    ref.at,
                )
            val entity = info.decl.companion
                ?: throw err(
                    "注解 '$bare' 没有 companion entity，所以没有 '${ref.display()}' 这个类型：" +
                        "要给注解加一个 companion entity 块才能写 $bare::伴生名",
                    ref.at,
                )
            val declared = entity.name
                ?: throw err(
                    "注解 '$bare' 的 companion entity 没有名字，写不出它的类型：" +
                        "给它起个名字（companion entity Impl : ...）才能写 $bare::Impl",
                    ref.at,
                )
            if (declared != nested) {
                throw err(
                    "注解 '$bare' 的 companion entity 叫 '$declared'，不是 '$nested'：" +
                        "要写就写 $bare::$declared",
                    ref.at,
                )
            }
            checkArity(bare, 0, args, ref.at)
            return finish(RsfType.external(info.companionJvmName!!).let { if (nullable) it.asNullable() else it })
        }

        // 3) 用户自定义类：元数必须和 class 声明的类型参数个数一致
        if (classTable.containsKey(bare)) {
            val info = classTable.getValue(bare)
            val want = info.decl.typeParams.size
            checkArity(bare, want, args, ref.at, info.decl.typeParams)
            return finish(RsfType.external(bare).withArgs(args).let { if (nullable) it.asNullable() else it })
        }

        // 3b) 枚举（阶段 19c）：和用户类**完全一样**地参与类型系统。
        //
        // 这是「枚举编译成普通类」这个口径的兑现处：`TokenKind` 的静态类型就是
        // `RsfType.external("TokenKind")`，描述符 `LTokenKind;`——于是字段、参数、
        // 返回、局部变量、数组、可空、泛型实参位置一行代码都不用为枚举特判。
        // 枚举不是泛型的，所以类型实参必须是 0 个。
        if (enumTable.containsKey(bare)) {
            checkArity(bare, 0, args, ref.at)
            return finish(
                RsfType.external(bare).let { if (nullable) it.asNullable() else it },
            )
        }

        // 3c) 接口（阶段 20）：和用户类**完全一样**地参与类型系统。接口类型就是
        // `RsfType.external(接口名)`——编译产物是 JVM 接口，描述符同样是 `L名字;`，
        // 于是变量、参数、返回、字段、数组、可空、泛型实参这些位置一行都不用为接口
        // 特判（D6）。类与接口的差别只在「能不能 new」和「谁覆盖谁的抽象方法」上，
        // 那是 checkConstruction / checkImplementations 的事。
        if (interfaceTable.containsKey(bare)) {
            val info = interfaceTable.getValue(bare)
            checkArity(bare, info.decl.typeParams.size, args, ref.at, info.decl.typeParams)
            return finish(RsfType.external(bare).withArgs(args).let { if (nullable) it.asNullable() else it })
        }

        // 3d-2) 注解接口类型（阶段 21）：`Marker` 和接口**同形**
        // （`RsfType.external(注解名)`），于是「注解类型的变量/字段/参数/数组/可空」
        // 这些位置一行都不用特判。它与接口的差别只有两处，都在别处落地：
        // 成员可见性（checkMember 只放行元素访问器）与「不能 new 注解接口本身」。
        if (annotationTable.containsKey(bare)) {
            checkArity(bare, 0, args, ref.at)
            return finish(RsfType.external(bare).let { if (nullable) it.asNullable() else it })
        }

        // 4) 导入的 JVM 类（`ArrayList&<int>` 靠这条）。元数从 class 文件的
        //    泛型签名里数出来；数不出来（签名畸形）就不拦。
        val jvm = resolveJvmClass(bare)
        if (jvm != null) {
            jvm.typeParamCount?.let { checkArity(jvm.simpleName, it, args, ref.at) }
            return finish(
                RsfType.external(jvm.internalName).withArgs(args)
                    .let { if (nullable) it.asNullable() else it },
            )
        }

        if (ref.name == "?void") {
            throw err("void 不能是可空的——void 本来就没有值可空", ref.at)
        }
        throw err(
            "未知类型 '${ref.display()}'" +
                if (classTable.isNotEmpty()) "（已知的类：${classTable.keys.joinToString(", ")}）" else "",
            ref.at,
        )
    }

    /** 泛型实参个数不对。报错必须说清**期望几个、实际几个**，否则用户只能猜。 */
    private fun checkArity(
        name: String,
        want: Int,
        args: List<RsfType>,
        at: SourceLine,
        declaredNames: List<String> = emptyList(),
    ) {
        if (args.size == want) return
        val detail = when {
            want == 0 -> "（'$name' 不是泛型类）"
            declaredNames.isNotEmpty() -> "（声明是 class $name&<${declaredNames.joinToString(", ")}>）"
            else -> "（这个类声明了 $want 个类型参数）"
        }
        throw err(
            "类型实参个数不对：'$name' 期望 $want 个类型实参，实际给了 ${args.size} 个$detail",
            at,
        )
    }

    /** 简单类名 → 导入的 JVM 类（显式 import 优先，其次按 Java 的规矩找 java.lang）。
*/
    private fun resolveJvmClass(simpleName: String): JvmClass? =
        classImports[simpleName]?.let { ClassPath.find(it) }
            ?: ClassPath.find("java/lang/$simpleName")

    /** 类型实参 → `T` 的替换表（`Box&<int>` 的成员里 `T` 换成 `int`）。
*/
    private fun substitutionFor(info: ClassInfo, actual: List<RsfType>): Map<String, RsfType> {
        val params = info.decl.typeParams
        if (params.isEmpty() || actual.isEmpty()) return emptyMap()
        return params.zip(actual).toMap()
    }

    /** 同上，只是换成了接口的类型参数（阶段 20：`Box&<int>` 的接口版）。 */
    private fun substitutionFor(params: List<String>, actual: List<RsfType>): Map<String, RsfType> {
        if (params.isEmpty() || actual.isEmpty()) return emptyMap()
        return params.zip(actual).toMap()
    }

    private fun err(message: String, at: SourceLine): RsfError =
        RsfError("checker", message, at.line, at.column, currentSource)

    /**
     * 「声明类型 [want] 能不能接住表达式 [value]（它的类型是 [valueType]）」。
     *
     * 比 [RsfType.accepts] 只多一条**字面量特例**：`byte` 可以接住 `0..127` 的整数字面量。
     *
     * 为什么非要这条特例：rsf 里**没有强制类型转换语法**（没有 `(byte) x`），
     * 不给特例的话 `var b : byte = 5` 根本写不出来——只能用 `toByte(...)` 从别的
     * byte 算，而第一个 byte 又无从产生。Java 的规矩（常量窄化）正是这么定的：
     * 编译期已知的值落在目标类型范围内就可以直接赋。
     *
     * 边界划得很清楚：
     * - 只认**直接写出来的**整数字面量（可以带一个一元负号，`-1` 算），
     *   变量、函数返回值、`1 + 2` 这类表达式一律不认——那些值的范围编译期推不出来，
     *   要转就写 `toByte(...)`；
     * - 只对 `byte` 开这个口子。`char` **不接** int 字面量（`var c : char = 65` 报错）：
     *   字符字面量 `'A'` 才是它的字面量形态，混着来只会让人分不清 char 和 int。
     */
    private fun acceptsValue(want: RsfType, value: Expr, valueType: RsfType): Boolean {
        if (want.accepts(valueType)) return true
        if (want.base != RsfBase.BYTE) {
            // 「实现类的对象能放进去」这一条在**这张表之外**：[RsfType.accepts] 看不到类表，
            // 接口子类型（`var g : Greeter = new Dog()`）只有这里知道。所以赋值、传参、
            // 返回、重载打分全都要经过本函数，不允许别处再写一遍 `want.accepts(...)`。
            return isSourceSubtype(want, valueType)
        }
        return byteRangeLiteral(value) != null
    }

    /** 阶段 53：能取地址的表达式——变量、字段、数组元素、指针目标（`**pp`）。 */
    private fun isAddressable(e: Expr): Boolean = when (e) {
        is VarExpr -> true
        is MemberExpr -> e.arguments == null
        is IndexExpr -> true
        is UnaryExpr -> e.op == TokenKind.STAR
        else -> false
    }

    /** 字面量在 byte 范围内就返回它的值，否则 null（不是字面量也是 null）。 */
    private fun byteRangeLiteral(expr: Expr): Int? {
        val n = when (expr) {
            is IntLit -> expr.value
            is UnaryExpr -> {
                val operand = expr.operand
                if (expr.op == TokenKind.MINUS && operand is IntLit) -operand.value else null
            }
            else -> null
        } ?: return null
        return if (n in Byte.MIN_VALUE..Byte.MAX_VALUE) n else null
    }

    companion object {
        /**
         * 内置函数名。
         *
         * `print` / `println` 是单参数特例（见 [checkCall]），签名写不进 [Builtins] 那张表；
         * 其余（阶段 16 的 `toByte` / `charToInt` / `parseInt` …）都在 [Builtins] 里。
         * 这张表管两件事：**用户不能重定义内置函数**，以及补全列表里能认出它们。
         */
        val BUILTINS: Set<String> = Builtins.PRINT_LIKE + Builtins.NAMES
    }
}

