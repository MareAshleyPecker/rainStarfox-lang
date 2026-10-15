package org.example.complie.interp

import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.BoolLit
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ControlType
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.ElvisExpr
import org.example.complie.ast.Expr
import org.example.complie.ast.FloatLit
import org.example.complie.ast.ForceExpr
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IndexExpr
import org.example.complie.ast.IntLit
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.NullLit
import org.example.complie.ast.StringLit
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarExpr
import org.example.complie.diag.RsfError
import org.example.complie.interop.JvmMethod
import org.example.complie.interop.ResolvedField
import org.example.complie.interop.ResolvedMethod
import org.example.complie.ir.IAssign
import org.example.complie.ir.IBranch
import org.example.complie.ir.ICtorDelegate
import org.example.complie.ir.IExpr
import org.example.complie.ir.IFail
import org.example.complie.ir.IFieldAssign
import org.example.complie.ir.IGoto
import org.example.complie.ir.IIndexAssign
import org.example.complie.ir.ILabel
import org.example.complie.ir.IReturn
import org.example.complie.ir.ISuperDelegate
import org.example.complie.ir.ISwitch
import org.example.complie.ir.IVarDecl
import org.example.complie.ir.LoweredFunction
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.ArrayRuntime
import org.example.complie.sema.Binding
import org.example.complie.sema.Builtin
import org.example.complie.sema.Builtins
import org.example.complie.sema.EnumRuntime
import org.example.complie.sema.FileRuntime
import org.example.complie.sema.ObjectFormat
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import org.example.complie.sema.SuperCall
import org.example.complie.sema.SuperDelegation
import org.example.complie.sema.UserMember
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.PrintStream
import java.lang.invoke.MethodHandles
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.IdentityHashMap

/**
 * rsf 的运行期错误：`fail` 抛出来的东西（阶段 14）。
 *
 * 为什么解释器内部不用 JVM 现成的异常当载体：解释器得能分清「rsf 自己 fail 出来的错误」
 * 和「JVM 互操作抛出来的 RuntimeException」。内部异常类就是一个标记。
 *
 * **边界（照实说清楚）**：解释器里的 `try` / `?:` **只**接 [RsfFailure]。
 * Java 互操作抛出来的异常（比如 `Integer.parseInt("abc")` 的 NumberFormatException）
 * 不会被解释器的 try 接住；而编译产物那边 `catch (RuntimeException)` 会接住它——
 * 这种跨语言异常的差异本版**不做**（要做就得把 JVM 的异常表整张搬进解释器，
 * 等于把 Java 异常也纳入 rsf 的语义）。本版只保证 rsf 自己的 `fail` 两端一致。
 */
class RsfFailure(val msg: String) : RuntimeException(msg) {

    /**
     * 这个错误一路穿过的函数帧，**最外层在最前**（[Interpreter.call] / [callMethod] 记的）。
     *
     * `?:` 靠它区分两种情况：失败是**目标调用自己**造成的（取兜底值），
     * 还是求实参时别的调用造成的（继续往外传）。字节码那边实参本来就在合成方法
     * 外面求值、不在保护范围内，所以这个区分让两端的表现严格一致。
     */
    val frames = ArrayList<FuncDecl>()

    /**
     * 接口分派（阶段 20）：**实现类的那个 FuncDecl → 它替的接口方法**。
     *
     * 抽象接口方法没有体，真正执行的是实现类里的那份声明，[frames] 里记的也是它；
     * 而调用点手里攥着的身份是接口的那个 FuncDecl（`?:` 比的就是这个身份）。
     * [Interpreter.callInterfaceMethod] 在失败时把这条对应关系捎上，
     * [Interpreter.failedIn] 才能认出「这次失败就是目标调用自己抛的」。
     */
    val dispatched = IdentityHashMap<FuncDecl, FuncDecl>()
}

/**
 * 解释器 —— `rsf run xxx.rsf` 走这条路，不落地任何 .class。
 *
 * 它跑的不是 AST，而是 [LoweredFunction] 那串扁平指令，用一个程序计数器推进、
 * 一个槽位数组存局部变量。这样控制流（if / while / goto）就退化成了改 pc，
 * 而且**执行模型和 JVM 完全同构**：同一套槽位编号、同一套跳转目标。
 * 语义上刻意对齐的还有：整型截断除法、溢出回绕，以及输出统一按静态类型格式化。
 */
class Interpreter(
    private val model: SemanticModel,
    private val out: PrintStream = System.out,
) {

    private var depth = 0

    /**
     * 枚举常量的单例表（阶段 19c）：`枚举名.值名` → 那个实例。
     *
     * 见 [enumValue]：这张表是「枚举值是单例」这条语义的载体，`==` 的引用相等
     * 全靠它。一次运行一张（Interpreter 一个实例），两端因此都只可能看到一个实例。
     */
    private val enumCache = HashMap<String, RsfEnumValue>()

    /**
     * rsf 对象 → 交给 JDK 用的那个代理（阶段 20 M4），按**同一性**索引。
     *
     * 一个对象只造一个：它实现哪些 JDK 接口在类声明上就定死了，形参要哪几个同一个
     * 代理都满足；多次跨边界也必须是**同一个**代理，否则 JDK 那边的引用相等就散了。
     * 反向表 [proxiedObjects] 让回调再进来的代理能被认回去（见 [unwrapProxy]）。
     */
    private val javaProxyCache = java.util.IdentityHashMap<RsfObject, Any>()

    /** 代理 → 它代表的 rsf 对象（[javaProxyCache] 的反向表）。 */
    private val proxiedObjects = java.util.IdentityHashMap<Any, RsfObject>()

    /** 静默模式：重放 REPL 会话前缀时不产生输出（用户函数里的输出也一并静音）。 */
    private var silent = false

    /** 从 main 开始执行。 */
    fun runEntry(): Any? {
        // 阶段 25 K3 起类继承在解释器里**跑得动了**，K1 那道解释器守卫就撤了。
        // 注意另一道守卫还在（`codegen/BytecodeCompiler.kt`，K2 的活）：`rsf build`
        // 遇到带父类的程序仍然显式报「字节码后端还没实现」，那条由 K2 拆。
        // 阶段 19d：`main(a : int)` 是合法重载但不是入口——「有 main 却带参数」要说清
        // 是参数的问题，而不是「没有 main」（见 SemanticModel.entryProblem）。
        val problem = model.entryProblem
        if (problem != null) {
            val at = model.functionsNamed("main").firstOrNull()?.at
            throw RsfError("interp", problem, at?.line ?: 0, at?.column ?: 0, null)
        }
        return call(model.entry!!, emptyList())
    }

    /**
     * 调用一个函数。[args] 按签名顺序给出；个数与类型已经由 Checker 保证，
     * 这里只负责按参数类型做一次收敛（比如 int 实参传给 long 形参）。
     */
    fun call(fn: FuncDecl, args: List<Any?>): Any? {
        enter(fn.at, fn.name)
        try {
            val body = model.loweredOf(fn)
            val slots = arrayOfNulls<Any>(body.slotCount)
            val params = model.paramsOf(fn)
            for (i in params.indices) {
                val binding = params[i]
                slots[binding.slot] = coerce(args[i], binding.type)
            }
            return execute(body, slots, 0)
        } catch (failure: RsfFailure) {
            // 记一笔「这个错误穿过了谁」——`?:` 要拿它判断失败是不是目标调用自己造成的
            failure.frames.add(0, fn)
            throw failure
        } finally {
            depth--
        }
    }

    /**
     * REPL 专用入口：指令 `[0, startPc)` 是已经执行过的会话前缀，静默重放一遍把
     * 变量状态重新建起来；`[startPc, end)` 才是用户刚敲的那一句，带输出执行。
     *
     * 这样 `while` / `if` 这类复合语句对变量的修改也能跨行保留，
     * 而不会被重复打印一遍。
     */
    fun runSnippet(body: LoweredFunction, startPc: Int): Any? {
        enter(body.function.at, body.function.name)
        try {
            val slots = arrayOfNulls<Any>(body.slotCount)
            if (startPc <= 0) return execute(body, slots, 0)
            // 前缀只跑到标记处为止 —— 跑过头就会把刚敲的这句也静默执行一遍，
            // 变量状态提前被改掉，正式执行时结果就不对了。
            silent = true
            try {
                execute(body, slots, 0, startPc)
            } finally {
                silent = false
            }
            return execute(body, slots, startPc)
        } finally {
            depth--
        }
    }

    private fun enter(at: SourceLine, name: String) {
        if (depth >= MAX_DEPTH) {
            throw RsfError(
                "interp",
                "调用层级超过 $MAX_DEPTH 层，多半是无限递归",
                at.line, at.column, null,
            )
        }
        depth++
    }

    // ------------------------------------------------------------------ 指令循环

    /**
     * 指令循环。
     *
     * 阶段 14 起整个循环被一个 `try` 包着：`fail` / 未处理的 `!T` 调用抛出 [RsfFailure]
     * 之后，按**当前 pc** 去 [LoweredFunction.handlerAt] 查有没有 try 接得住。
     * 用「pc 区间表」而不是显式的处理器栈，是为了和 JVM 的异常表同构：
     * 从 try 块里 `goto` / `rtn` 出去也不会留下没注销的处理器（那会把后面
     * 别的异常接到错误的 catch 上去）。
     */
    private fun execute(
        body: LoweredFunction,
        slots: Array<Any?>,
        startPc: Int,
        endPc: Int = Int.MAX_VALUE,
    ): Any? {
        val instrs = body.instructions
        var pc = startPc

        try {
            while (pc < instrs.size && pc < endPc) {
                when (val instr = instrs[pc]) {
                    is IVarDecl -> {
                        val binding = model.bindingOf(instr.stmt)
                        slots[binding.slot] =
                            coerce(eval(instr.stmt.initializer, slots, body), binding.type)
                        pc++
                    }

                    is IAssign -> {
                        // 裸字段名赋值（`x = 1`）：目标是字段而不是局部槽
                        val field = model.classModel.bareFieldWrites[instr.stmt]
                        if (field != null) {
                            val value = eval(instr.stmt.value, slots, body)
                            thisObject(slots).fields[field.index] = coerce(value, field.type)
                            pc++
                        } else {
                            val binding = model.bindingOf(instr.stmt)
                            slots[binding.slot] =
                                coerce(eval(instr.stmt.value, slots, body), binding.type)
                            pc++
                        }
                    }

                    // `对象.字段 = ...`
                    is IFieldAssign -> {
                        val field = model.classModel.fieldWrites[instr.stmt]
                            ?: throw RsfError("interp", "内部错误：字段赋值没有解析到字段", instr.at.line, instr.at.column, null)
                        val target = instr.stmt.target
                        val obj = if (target.receiver is ThisExpr) {
                            thisObject(slots)
                        } else {
                            eval(target.receiver, slots, body) as RsfObject
                        }
                        val value = eval(instr.stmt.value, slots, body)
                        obj.fields[field.index] = coerce(value, field.type)
                        pc++
                    }

                    is IExpr -> {
                        // 表达式语句：值丢掉，所以不做泛型结果收敛（见 [eval] 的 discard）
                        eval(instr.stmt.expr, slots, body, discard = true)
                        pc++
                    }

                    // `this(实参)` —— 构造器委托（阶段 18）。
                    // 实参在**本帧**里求值（Java 也是这样），然后递归进目标构造器；
                    // 它返回之后继续跑本构造器委托之后的语句（所以只是 pc++）。
                    is ICtorDelegate -> {
                        val delegation = model.classModel.delegationOf(body.function)
                            ?: throw RsfError(
                                "interp", "内部错误：构造器委托没有解析到目标",
                                instr.at.line, instr.at.column, null,
                            )
                        val cls = model.classModel.ownerOf(body.function)
                            ?: throw RsfError(
                                "interp", "内部错误：构造器不在类里",
                                instr.at.line, instr.at.column, null,
                            )
                        val obj = thisObject(slots)
                        val innerArgs = delegation.stmt.arguments.map { eval(it, slots, body) }
                        runConstructor(cls, delegation.target, obj, innerArgs)
                        pc++
                    }

                    // `super(实参)` —— **父类**构造委托（阶段 25 K3）。
                    //
                    // 这条指令自己**不做事**：父类那一串构造器由 [runConstructor] 在本
                    // 构造器体开跑**之前**就铺完了。必须是那个位置——父类构造要排在字段初值
                    // 前面，而字段初值不是一条指令、是 runConstructor 直接跑的，指令流里
                    // 没有它的位置。Checker 保证 `super(...)` 是构造器体的**第一句**，
                    // 于是「体开跑之前」与「这条指令到达之前」正好是同一时刻。
                    // 这里只推进 pc，免得父类构造器被跑两遍。
                    is ISuperDelegate -> pc++

                    // `数组[下标] = 值`（阶段 13）。
                    //
                    // 求值顺序和编译产物严格对齐：接收者 → 下标 → **越界检查** → 右值。
                    // 右值放在检查**之后**求：越界时右值里的副作用（函数调用、打印）
                    // 一次都不能发生，否则两端输出会分叉（JVM 上 a[i]=f() 越界时
                    // 也是先抛异常、f 根本不跑）。
                    is IIndexAssign -> {
                        val target = instr.stmt.target
                        val array = eval(target.receiver, slots, body)
                        val index = eval(target.index, slots, body) as Int
                        checkArrayIndex(array, index)
                        val value = eval(instr.stmt.value, slots, body)
                        java.lang.reflect.Array.set(array, index, coerceElement(value, model.typeOf(target)))
                        pc++
                    }

                    is IReturn -> {
                        val value = instr.stmt.value
                        if (value == null || value is NullLit) return null
                        return coerce(eval(value, slots, body), model.returnTypeOf(body.function))
                    }

                    // `fail <消息>`：抛出去（编译产物那头是 `throw new RuntimeException(msg)`）
                    is IFail -> {
                        val message = eval(instr.stmt.message, slots, body) as? String
                        throw RsfFailure(message ?: "")
                    }

                    is IGoto -> pc = target(body, instr.label)

                    is IBranch -> {
                        val taken = eval(instr.cond, slots, body) as Boolean
                        pc = if (taken) target(body, instr.target) else pc + 1
                    }

                    // `switch`（阶段 19c）：被分派的值**只求一次**，然后按声明顺序
                    // 逐个和枚举常量比。命中就跳到那一支的标签；一个都没命中就跳 default
                    // （没写 else 时 default 就是 switch 结束标签，Checker 已保证穷尽）。
                    //
                    // 比较用 `===`（**引用相等**）而不是 equals / 名字字符串比：
                    // 枚举值是单例（见 [enumValue]），引用相等就是「同一个常量」的
                    // 精确含义，而且和编译产物那条 `IF_ACMPEQ` 是同一种比较——
                    // 谁都不会因为「名字恰好相同」而误命中。
                    is ISwitch -> {
                        val subject = eval(instr.stmt.subject, slots, body)
                        var chosen: String? = null
                        for ((i, c) in instr.stmt.cases.withIndex()) {
                            if (subject === enumValue(c.enumName, c.valueName)) {
                                chosen = instr.targets[i]
                                break
                            }
                        }
                        val fallback = instr.defaultTarget
                        pc = when {
                            chosen != null -> target(body, chosen)
                            fallback != null -> target(body, fallback)
                            // 没写 else 且 case 穷尽：到这里说明值是 null（枚举常量都不是 null）。
                            // 抛的消息和编译产物那条 `throw new RuntimeException(...)` 一字不差。
                            else -> throw RsfFailure(
                                EnumRuntime.noMatchFailure(
                                    model.typeOf(instr.stmt.subject).simpleExternalName,
                                ),
                            )
                        }
                    }

                    is ILabel -> pc++
                }
            }
        } catch (failure: RsfFailure) {
            // pc 还停在抛错的那条指令上（pc 只在一条指令跑完之后才推进），所以查表是准的
            val handler = body.handlerAt(pc) ?: throw failure
            slots[handler.catchSlot] = failure.msg
            // 从 handler 接着跑；嵌套的 catch 也走这条路，所以 endPc 要一起带过去
            return execute(body, slots, handler.handlerPc, endPc)
        }
        // 走到函数末尾：void 函数正常结束
        return null
    }

    private fun target(body: LoweredFunction, label: String): Int =
        body.labelIndex[label]
            ?: throw IllegalStateException("内部错误：未解析的标签 '$label'")

    // ------------------------------------------------------------------ 表达式

    /**
     * 这个表达式是不是一次「可能失败」的调用（`!T` 函数 / `!T` 方法 / `parseInt`）；
     * 是的话返回被调的函数（内置函数返回 null，单独由 [errorBuiltinOf] 处理）。
     *
     * 判据来自语义模型里已经解析好的那几张表，和字节码后端用的是同一份——
     * 两端对「这里要不要按错误处理」的判断因此不可能走偏。
     */
    private fun errorCallTarget(expr: Expr): FuncDecl? {
        val fn = when (expr) {
            is CallExpr -> model.callTargets[expr] ?: model.classModel.implicitCalls[expr]?.decl
            is MemberExpr -> (model.classModel.members[expr] as? UserMember.Method)?.decl
            else -> null
        } ?: return null
        return if (fn.ret.mayError) fn else null
    }

    /**
     * 这次失败是不是 [target] 这次调用**自己**抛出来的（而不是求实参时别的调用抛的）。
     *
     * 一般比的是 [RsfFailure.frames] 第一项的身份。接口分派（阶段 20）多一层：
     * 抽象接口方法没有体，跑的是实现类的那个 FuncDecl，帧里记的也是它——
     * 所以还要顺着 [RsfFailure.dispatched] 把「它替的是哪个接口方法」翻出来再比一次。
     */
    private fun failedIn(failure: RsfFailure, target: FuncDecl): Boolean {
        val first = failure.frames.firstOrNull() ?: return false
        return first === target || failure.dispatched[first] === target
    }

    /**
     * `?:` 左边是不是一个**可能失败的内置函数**调用（阶段 16：目前只有 `parseInt`）。
     *
     * 内置函数没有 `FuncDecl`，所以 [errorCallTarget] 认不出它；这里按名字查 [Builtins]。
     * 名字查得准是因为用户函数不允许重定义内置函数名（Checker.declareFunctions 拦着）。
     */
    private fun errorBuiltinOf(expr: Expr): Builtin? {
        val call = expr as? CallExpr ?: return null
        return Builtins[call.name]?.takeIf { it.mayError }
    }

    /**
     * 求值一个表达式。
     *
     * [discard] = 这个值马上就被丢掉（表达式语句），调用方**不要**做泛型互操作的
     * 结果收敛：`m.put(k, v)` 的返回值（旧值）可能是 null，收敛时会当成 int 拆箱炸掉，
     * 而 Java 里把它当语句写根本不看那个值。字节码后端的 `emitExpr(discard = true)`
     * 是同一件事的另一半。
     */
    private fun eval(
        expr: Expr,
        slots: Array<Any?>,
        body: LoweredFunction,
        discard: Boolean = false,
    ): Any? =
        when (expr) {
            is IntLit -> expr.value
            is LongLit -> expr.value
            is FloatLit -> expr.value
            is DoubleLit -> expr.value
            is StringLit -> expr.value
            // 字符字面量：解释器里 char 就用 Kotlin 的 Char（JVM 上就是 java.lang.Character）
            is CharLit -> expr.value
            is BoolLit -> expr.value
            is NullLit -> null

            // `super` / `super(接口)`（阶段 25 K1）：不是能取值的表达式，只当
            // `super.方法(...)` 的接收者用，那种调用由 Checker 记进 `superCalls`
            // 并走成员调用那条路。这里是裸 super 的兜底。
            is SuperExpr -> throw RsfError(
                "interp", "'super' 只能当方法调用的接收者（super.方法(...)）",
                expr.at.line, expr.at.column, null,
            )

            is VarExpr -> {
                // 方法体里的裸字段名（`rtn x`）：先看类解析结果，其次才是局部变量槽
                val field = model.classModel.fieldReads[expr]
                if (field != null) {
                    thisObject(slots).fields[field.index]
                } else {
                    val binding = model.bindingOf(expr)
                    slots[binding.slot]
                }
            }

            // 阶段 12：用户自定义类；`Point(1, 2)` 这种**省掉 new** 的写法也是构造，
            // 判据是 Checker 写下的 newTargets（两种写法在语义模型里是同一件事）
            is NewExpr -> construct(expr, slots, body)

            // 阶段 13：数组
            is NewArrayExpr -> newArray(expr, slots, body)

            is IndexExpr -> {
                val array = eval(expr.receiver, slots, body)
                val index = eval(expr.index, slots, body) as Int
                checkArrayIndex(array, index)
                // 引用类型数组的元素可能是 null（默认值就是 null），
                // 所以这里用「容忍 null」的收敛，而不是 coerce
                coerceElement(java.lang.reflect.Array.get(array, index), model.typeOf(expr))
            }

            is ThisExpr -> thisObject(slots)

            // 自动 new：省掉 new 的 `Point(1, 2)` 在 AST 里是 CallExpr，
            // 但 Checker 已经判定它是构造，所以走构造那条路（和显式 new 完全同一条代码）
            is CallExpr -> if (model.isConstruction(expr)) {
                construct(expr, slots, body)
            } else {
                evalCall(expr, slots, body)
            }
            is UnaryExpr -> evalUnary(expr, slots, body)
            is BinaryExpr -> evalBinary(expr, slots, body)

            is IfExpr -> {
                val taken = eval(expr.condition, slots, body) as Boolean
                val branch = if (taken) expr.whenTrue else expr.whenFalse
                // 两个分支可能宽度不同（int vs long），按表达式的静态类型收敛
                coerce(eval(branch, slots, body), model.typeOf(expr))
            }

            is ElvisExpr -> {
                val resultType = model.typeOf(expr)

                // 阶段 16：`parseInt(...) ?: 兜底`——内置函数里可能失败的目前只有 parseInt。
                // 实参**先求值、且不在保护范围内**：实参自己出错不算这次调用失败。
                // 编译产物里实参也是在合成方法外面求值的（emitOrElseCall），两端必须一致。
                val errorBuiltin = errorBuiltinOf(expr.left)
                if (errorBuiltin != null) {
                    val call = expr.left as CallExpr
                    val argValues = call.arguments.map { eval(it, slots, body) }
                    val value = try {
                        callBuiltin(errorBuiltin, argValues)
                    } catch (failure: RsfFailure) {
                        CAUGHT
                    }
                    return if (value === CAUGHT) {
                        coerce(eval(expr.right, slots, body), resultType)
                    } else {
                        coerce(value, resultType)
                    }
                }

                val target = errorCallTarget(expr.left)
                if (target != null) {
                    // 阶段 14：`f() ?: 兜底`——f 是 !T 函数，出错就取右边。
                    //
                    // 只有**目标调用自己**失败才算「出错」：实参求值时的失败要继续往外传，
                    // 因为字节码那边实参是在合成方法外面求值的、不在保护范围内（两端一致）。
                    // 正常路径不可能是 null（可空 + !T 的组合已经在 Checker 里拦下了），
                    // 所以这里不做 null 判断。
                    val value = try {
                        eval(expr.left, slots, body)
                    } catch (failure: RsfFailure) {
                        if (failedIn(failure, target)) CAUGHT else throw failure
                    }
                    if (value === CAUGHT) {
                        coerce(eval(expr.right, slots, body), resultType)
                    } else {
                        coerce(value, resultType)
                    }
                } else {
                    val left = eval(expr.left, slots, body)
                    if (left != null) {
                        coerce(left, resultType)
                    } else {
                        coerce(eval(expr.right, slots, body), resultType)
                    }
                }
            }

            is ForceExpr -> {
                val value = eval(expr.operand, slots, body) ?: // 和编译产物保持一致：都是 NullPointerException、同样的消息
                throw NullPointerException("rsf: 对 null 使用了 '!!'")
                coerce(value, model.typeOf(expr))
            }

            is MemberExpr -> evalMember(expr, slots, body, discard)
        }

    /**
     * 成员访问用**反射**执行。
     *
     * 关键点：解析出来的方法带描述符，按「声明类 + 精确参数类型」去取那一个方法，
     * 而不是按名字重载。否则解释器会按运行期类型重新选一次重载，和字节码后端
     * （在编译期就定死了 owner/name/descriptor）选到不同的方法。
     */
    private fun evalMember(
        expr: MemberExpr,
        slots: Array<Any?>,
        body: LoweredFunction,
        discard: Boolean = false,
    ): Any? {
        // 阶段 19c：枚举常量 `TokenKind.IDENT`。
        // 放在**最前面**：Checker 已经把它和「JVM 静态字段访问」彻底分开了
        // （枚举名不是 classpath 上的类），所以不存在选错路的问题，
        // 而放在前面能让「取常量」这条最常用的路一眼看到。
        model.enumValueOf(expr)?.let { return enumValue(it.enumName, it.valueName) }

        // 阶段 13：数组的 `.length`。
        // 用**静态类型**判断（而不是「反射找不到成员就猜」）：两端读的是同一份
        // 语义模型，所以判断不可能走偏。Checker 已经保证这里只有 `.length`（不带括号）。
        // 这里查表而不是 model.typeOf：静态成员访问（`Math.max(...)`）的接收者是类名，
        // Checker 从不给它标注类型，typeOf 会在那儿抛「表达式缺少类型标注」。
        if (expr.arguments == null && expr.name == "length" &&
            model.exprTypes[expr.receiver]?.isArray == true
        ) {
            return java.lang.reflect.Array.getLength(eval(expr.receiver, slots, body))
        }

        // 阶段 25 K3：`super.f()` / `super(接口).f()`——**从直接父类起**取那一份实现，
        // 不做运行期虚分派（这正是 `super` 与 `this` 的区别）。Checker 已经把选中的
        // 是哪一份声明记在 superCalls 里，这里只负责用它去调，**绝不重新查一遍**：
        // 重查会命中本类刚写的那个 override，变成自我递归。
        //
        // 放在 `members` 之前：`super.f()` 走的是 Checker 里另一条解析路径（父链 / 接口
        // 默认方法），members 表里没有它——落下去会到 `memberOf` 那里抛「没有解析到目标」。
        model.classModel.superCallOf(expr)?.let { superCall ->
            // 接收者恒是 `this`（`super` 只能当调用的接收者，源码里没有别的写法）
            val self = thisObject(slots)
            val args = expr.arguments.orEmpty().map { eval(it, slots, body) }
            return when (superCall) {
                // 父类（或接口默认方法）那一份是 **rsf 代码**，直接跑它那一份体。
                // 这里不能走 [callInterfaceMethod]——那是虚分派，会去找运行期实现，
                // `super` 的意思就没了。
                is SuperCall.Rsf -> callMethod(superCall.className, superCall.decl, self, args)
                // 阶段 25 K4（缺口 C）：父类那一份在 **classpath** 上（`super.getMessage()`
                // 声明于 `java/lang/Throwable`）。必须**非虚**调用——打到声明类那一份，
                // 不许按运行期类再查一遍（DESIGN-25 §7）。
                is SuperCall.Jvm -> callSuperJvmMethod(superCall, self, args)
            }
        }

        // 用户自定义类的成员（阶段 12）：先看这张表，再走 JVM 反射那条路
        when (val member = model.classModel.members[expr]) {
            is UserMember.Field -> {
                val obj = eval(expr.receiver, slots, body) as RsfObject
                return obj.fields[member.index]
            }
            is UserMember.Method -> {
                val method = member.decl
                val args = expr.arguments.orEmpty().map { eval(it, slots, body) }
                // 阶段 20：接收者的**静态**类型是接口（Checker 记的 interfaceOwner）。
                // 接口方法一律虚分派（`INVOKEINTERFACE` 的语义）：默认方法跑它自己那份体，
                // 抽象方法去运行期真实类里找实现。见 [callInterfaceMethod]。
                if (member.interfaceOwner && !method.isStatic) {
                    val self = eval(expr.receiver, slots, body) as RsfObject
                    // 阶段 21：静态类型是**注解接口**（`Marker`）——伴生对象的元素访问器
                    // 走这条路，不能交给 callInterfaceMethod（注解接口不在 interfaces 表里）
                    annotationElementCall(self, method.name, args)?.let { return it }
                    return callInterfaceMethod(self, method, args)
                }
                val self = if (method.isStatic) null else eval(expr.receiver, slots, body) as RsfObject
                // 阶段 25 K3：静态类型是**类**的实例方法同样要虚分派（`INVOKEVIRTUAL`）——
                // `var a : Animal = new Dog() ; a.sound()` 必须跑到 Dog 那份覆盖。
                return invokeUserMethod(member.className, method, self, args)
            }
            null -> {}
        }

        return when (val target = model.memberOf(expr)) {
            is ResolvedField -> {
                val owner = Class.forName(target.field.owner.replace('/', '.'))
                val field = owner.getDeclaredField(target.field.name).also { it.isAccessible = true }
                if (target.field.isStatic) {
                    field.get(null)
                } else {
                    // 阶段 25 K3：字段是从**父类**继承来的（`java.lang.Exception` 之类），
                    // 那就作用在配对出来的 JDK 父类实例上（见 [RsfObject.jdkParent]）
                    field.get(jdkReceiver(eval(expr.receiver, slots, body)))
                }
            }

            is ResolvedMethod -> {
                val owner = Class.forName(target.method.owner.replace('/', '.'))
                val paramClasses = target.method.paramTypes.map { toJavaClass(it) }.toTypedArray()
                val method = publicMethod(owner, target.method.name, paramClasses)
                val receiver = if (target.method.isStatic) null else eval(expr.receiver, slots, body)
                // 阶段 20：静态类型是 **JDK 接口**（`Runnable` / `Comparable`…），运行期拿到的
                // 却是 rsf 的实现类对象——它只是个 [RsfObject]，**并不是真的**实现了那个接口，
                // 反射 invoke 会抛 IllegalArgumentException（object of type ... is not an
                // instance of java.lang.Runnable）。改成在 rsf 侧按真实类虚分派，走和 rsf
                // 接口同一个入口。只有 receiver **真的是** JDK 对象时（`xs.size()`）才继续反射。
                if (target.interfaceOwner && receiver is RsfObject) {
                    val args = expr.arguments.orEmpty().map { eval(it, slots, body) }
                    return callJdkInterfaceMethod(
                        receiver, target.method.name, target.method.paramTypes.size, args,
                    )
                }
                val rawArgs = expr.arguments.orEmpty().map { eval(it, slots, body) }
                // 阶段 25 K4b：`java/lang/Object` 的 `toString` / `equals` / `hashCode`
                // 在 rsf 对象上按 rsf 的语义算，不反射打到配对出来的 JDK 父类实例上
                val family = rsfObjectFamily(receiver, target.method.name, rawArgs)
                if (family !== NOT_OBJECT_FAMILY) return family
                val args = rawArgs.mapIndexed { i, v ->
                    adaptJdkArg(v, target.method.paramTypes.getOrNull(i))
                }.toTypedArray()
                val result = method.invoke(jdkReceiver(receiver), *args)
                // 泛型互操作：JVM 给回来的是擦除后的 Object，按静态类型还原成 int/string/…
                // 结果被丢弃时（表达式语句）不还原——`m.put(k, v)` 的旧值可能是 null
                if (discard || model.typeOf(expr) == target.method.returnType) result
                else coerceGenericResult(result, model.typeOf(expr))
            }
        }
    }

    /**
     * 泛型互操作的结果收敛：`xs.get(0)` 反射回来的是 `Integer`/`Object`，
     * 静态类型却是 `int`，这里按静态类型收一次。
     *
     * 引用类型不用做任何事——JVM 上的对象本来就是对的类型，字节码那边插的
     * CHECKCAST 在解释器里是恒等操作。
     */
    private fun coerceGenericResult(v: Any?, want: RsfType): Any? = when {
        want.isVoid || want.isNullLiteral -> null
        want.nullable -> if (v == null) null else coerceGenericResult(v, want.asNonNull())
        want.base.isPrimitive -> coerce(v, want)
        else -> v
    }

    /**
     * 一个 rainStarfox 对象。
     *
     * 字段按**声明顺序**放在数组里（下标由 Checker 算好记在 model 里），
     * 所以这里只要一个类名 + 一个数组就够，不需要动态的字段表。
     *
     * 阶段 25 起字段数组是**整条父链拼起来的那一条**（DESIGN-25 §5）：父类字段在前、
     * 本类字段在后，下标就是 Checker 记下的**绝对下标**（`fieldBase + 段内下标`）。
     * 所以子类对象读父类字段和读自己的字段走的是同一句 `fields[绝对下标]`，
     * 换个说法——**对象只有一条字段数组**，父类那一段在子类视角下没有额外包装。
     *
     * [fieldNames] / [fieldTypes] 是生成 `toString()` 用的：格式规则在
     * [org.example.complie.sema.ObjectFormat]，**和字节码后端生成的 `toString()`
     * 读的是同一段规则**，这样 `"p = " + p` 两端才会逐字节一致。
     * 拿不到（不该发生）时退化成空表，渲染出来就是 `Point()`。
     * 继承下这两张表同样是**整条父链**（父类字段在前），与数组下标一一对应。
     */
    class RsfObject(
        val className: String,
        val fields: Array<Any?>,
        private val fieldNames: List<String> = emptyList(),
        private val fieldTypes: List<RsfType> = emptyList(),
        /**
         * 「这个类型是不是枚举」——渲染 `toString()` 时要认出枚举字段
         * （它的值名可以直接显示，普通对象字段才写成 `...`）。
         * 由 [Interpreter] 在造对象时把语义模型的判断传进来，和字节码后端
         * 生成 `toString()` 时用的是**同一条判断**（[org.example.complie.sema.ObjectFormat]）。
         */
        private val isEnum: (RsfType) -> Boolean = { false },
    ) {
        /**
         * 阶段 25 K3：这个 rsf 对象的 **JDK 父类实例**（`class MyError(m : string) : Exception(m)`）。
         *
         * rsf 的类在解释器里没有真实 `.class`（一个字节码都不落地），所以「父类是 JDK 类」
         * 这一跳只能靠**配一个真的 JDK 实例**：跑 `super(实参)` 时反射造一个父类实例挂在这里，
         * 从父类继承来的 JDK 方法（`getMessage()`）与字段就反射作用到它身上。
         * 父类是 rsf 类、或者根本没有父类时恒为 null——那些情况字段全在 [fields] 里。
         */
        var jdkParent: Any? = null

        override fun toString(): String =
            ObjectFormat.render(className, fieldNames, fieldTypes, fields, isEnum)
    }

    /**
     * 一个枚举值（阶段 19c）：**有名字的单例对象**。
     *
     * 这是「枚举 = 一组具名单例」这个口径在解释器这一侧的落地：
     *
     * - **单例**：[enumValue] 按 `枚举名.值名` 缓存，同一个常量永远是**同一个对象**。
     *   于是 `==` 用引用相等（Kotlin 的 `==` 对普通类就是引用相等，因为这里
     *   刻意**不写** data class）就和编译产物那条 `Objects.equals`/`IF_ACMPEQ`
     *   完全等价——两端的判等结果不可能不同。
     * - **有名字**：[toString] 返回值名，`println(k)` 打成 `IDENT`，
     *   字节码那边靠生成的 `toString()` 返回同一个字符串。
     *
     * 不继承任何东西、也没有字段可读：rsf 的枚举里没有字段与方法，值就是一个名字。
     */
    class RsfEnumValue(
        val enumName: String,
        val valueName: String,
    ) {
        override fun toString(): String = valueName
    }

    /**
     * `枚举名.值名` → 那个单例（同一次运行内恒等）。
     *
     * 缓存是**必须**的，不是优化：没有缓存的话每次取常量都造一个新对象，
     * `TokenKind.IDENT == TokenKind.IDENT` 就会是假——而编译产物那边
     * `GETSTATIC` 拿到的是同一个实例、比较恒为真。两端立刻分叉。
     */
    private fun enumValue(enumName: String, valueName: String): RsfEnumValue =
        enumCache.getOrPut("$enumName.$valueName") { RsfEnumValue(enumName, valueName) }

    /** 调一个用户类的方法/构造器。[self] 是接收者（static 方法为 null）。 */
    private fun callMethod(className: String, method: FuncDecl, self: RsfObject?, args: List<Any?>): Any? {
        enter(method.at, method.name)
        try {
            val body = model.loweredOf(method)
            val slots = arrayOfNulls<Any>(body.slotCount)
            // 0 号槽：实例方法放 this（Lowering 给实例方法留了 1 号起的局部槽）
            if (model.classModel.isInstance(method)) slots[0] = self
            val params = model.paramsOf(method)
            for (i in params.indices) {
                val binding = params[i]
                slots[binding.slot] = coerce(args[i], binding.type)
            }
            return execute(body, slots, 0)
        } catch (failure: RsfFailure) {
            failure.frames.add(0, method)
            throw failure
        } finally {
            depth--
        }
    }

    /**
     * 用户类实例方法的**运行期虚分派**（阶段 25 K3）——`INVOKEVIRTUAL` 的语义。
     *
     * 静态类型是个类、运行期对象却可能是子类：`var a : Animal = new Dog() ; a.sound()`
     * 必须跑到 **Dog 那份覆盖**。Checker 记在 `members` 里的只是「按静态类型选中的那一份
     * 声明」，拿它直接调就成了静态分派（跑父类那份），两端立刻分叉。
     *
     * 分派沿 [RsfObject.className] 的类链找**同名同签名**的方法，最近的类优先。两条
     * 不参与分派的路和 JVM 一致：
     * - `static` 方法没有接收者，谈不上覆盖（Checker 也不许覆盖 static）；
     * - `private` 方法**不继承**（DESIGN-25 §3）：子类里同名同形的那个是另一个方法。
     *
     * 链上没有覆盖时跑静态类型那一份——本来就只有那一份，回退不算特例。
     */
    private fun invokeUserMethod(
        owner: String,
        method: FuncDecl,
        self: RsfObject?,
        args: List<Any?>,
    ): Any? {
        if (self == null || method.isStatic || method.control == ControlType.PRIVATE) {
            return callMethod(owner, method, self, args)
        }
        val toCall = classImplCache.getOrPut(self.className to method) {
            findClassImplementation(self.className, method) ?: method
        }
        return callMethod(self.className, toCall, self, args)
    }

    /**
     * 虚分派结果的缓存：`(运行期类名, 静态类型选中的那份声明)` → 实际要跑的那一份
     * （链上没有覆盖时就是静态那份本身）。
     *
     * 纯属省掉每次调用都重扫一遍类链（[paramsMatch] 要现算描述符），**语义上是恒等的**：
     * 类表与方法表在一次运行里只读，同一个键的答案不会变。答案带「回退成静态那份」的
     * 情形，所以存的一定是**非空**的 FuncDecl，`getOrPut` 才既缓存命中又缓存落空。
     */
    private val classImplCache = HashMap<Pair<String, FuncDecl>, FuncDecl>()

    /** 沿类链（最近的类优先）找同名同签名的实例方法——虚分派的目标。 */
    private fun findClassImplementation(runtimeClass: String, method: FuncDecl): FuncDecl? {
        val wanted = model.paramTypesOf(method)
        for (cls in model.classChainOf(runtimeClass)) {
            cls.plainMethods.firstOrNull {
                it.name == method.name && !it.isStatic && it.control != ControlType.PRIVATE &&
                    paramsMatch(model.paramTypesOf(it), wanted)
            }?.let { return it }
        }
        return null
    }

    /**
     * 反射要作用在**哪个真身**上（阶段 25 K3）。
     *
     * 成员是从 **JDK 父类**继承来的时候（`class MyError(m : string) : Exception(m)` 的
     * `getMessage()`），能在 JVM 上被反射调用的不是这个 rsf 对象，而是它配对出来的
     * JDK 父类实例（见 [RsfObject.jdkParent]）。其余情况原样返回，一刀不改。
     */
    private fun jdkReceiver(v: Any?): Any? = (v as? RsfObject)?.jdkParent ?: v

    /** [rsfObjectFamily] 的「不归我管」标记——调用方照旧走反射。 */
    private val NOT_OBJECT_FAMILY: Any = Any()

    /**
     * **`java/lang/Object` 的方法除了 rsf 自己定义语义的 `toString` / `equals` /
     * `hashCode`，不得经 JDK 父链漏进用户类**（阶段 25 K4b）。
     *
     * 字节码侧这三个调用发的是 `invokevirtual <声明类>.<名字>`，运行期接收者是 rsf
     * 生成的类：它**总是**带着 rsf 生成的 `toString()`（ObjectFormat 渲染，
     * 和裸 `println(对象)` 同一个字符串），`equals` / `hashCode` 则落在
     * `java/lang/Object` 的同一性实现上。解释器里的 rsf 对象没有对应的 JVM 类，
     * 反射打到 [RsfObject.jdkParent]（配对出来的 JDK 父类实例）上会答成父类那一份
     * ——`class E2 : Exception` 的 `toString()` 会印出 `java.lang.Exception: x`，
     * `a.equals(a)` 会答 `false`。所以在这一层补上同一套语义
     * （[javaHandler] 里给接口代理写的是同一套）。
     *
     * 只有这三个名字走这里：`wait` / `notify` / `clone` / `getClass` 之类在 Checker
     * 的 `objectMemberGate` 里就被拦住了，根本到不了解释器。
     *
     * @return [NOT_OBJECT_FAMILY] 表示不归这条路管（接收者不是 rsf 对象，或者名字
     *   不在这一族里），调用方继续反射。
     */
    private fun rsfObjectFamily(receiver: Any?, name: String, args: List<Any?>): Any? {
        if (receiver !is RsfObject) return NOT_OBJECT_FAMILY
        return when {
            name == "toString" && args.isEmpty() -> receiver.toString()
            name == "hashCode" && args.isEmpty() -> System.identityHashCode(receiver)
            name == "equals" && args.size == 1 -> receiver === args[0]
            // 静态类型是 JDK 类、运行期却拿到 rsf 对象时（`var e : Exception = new E2("x")`），
            // 编译期的 objectMemberGate 管不到——这里把「两端拿不到同一个 Class」明着抛出来，
            // 不让它悄悄答成父类的类。（真 JDK 对象走不到这里，上面第一行就弹回去了。）
            name == "getClass" && args.isEmpty() -> throw RsfError(
                "interp",
                "rsf 的类在解释器里没有真实的 Class 对象：'${receiver.className}' 就是一个 RsfObject" +
                    "（类名 + 字段平数组），从 JDK 父类配出来的也只是那个父类的实例——" +
                    "两端拿不到同一个 Class（阶段 25 K4b）",
                -1, -1, null,
            )
            else -> NOT_OBJECT_FAMILY
        }
    }

    /**
     * 反射调一个**从 JDK 父类继承来的**实例方法（阶段 25 K3）。
     *
     * 用在隐式 this 调用那条路上（[SemanticModel.ClassModel.implicitJvmCalls]）：rsf 类里
     * 没写这个方法的声明，只有 Checker 选中的 JvmMethod，以及构造时反射造出来、挂在
     * [RsfObject.jdkParent] 上的那个 JDK 实例——`getMessage()` 打在它身上才有东西。
     *
     * 返回值不做泛型收敛：Checker 记的返回类型就是这个 `JvmMethod.returnType`，调用点的
     * 静态类型与它一致。
     */
    private fun callInheritedJvmMethod(
        self: RsfObject,
        target: ResolvedMethod,
        args: List<Any?>,
    ): Any? {
        // 阶段 25 K4b：Object 方法族先按 rsf 语义答（`class E2 : Exception` 的
        // `getMessage()` 仍然落到下面反射父类实例那条路）
        val family = rsfObjectFamily(self, target.method.name, args)
        if (family !== NOT_OBJECT_FAMILY) return family
        val receiver = self.jdkParent
            ?: throw RsfError(
                "interp",
                "内部错误：'${self.className}' 调继承来的 JVM 方法 '${target.method.name}' 时，" +
                    "还没有建立它的 JDK 父类实例",
                -1, -1, null,
            )
        val owner = Class.forName(target.method.owner.replace('/', '.'))
        val paramClasses = target.method.paramTypes.map { toJavaClass(it) }.toTypedArray()
        val method = publicMethod(owner, target.method.name, paramClasses)
        val adapted = args.mapIndexed { i, v ->
            adaptJdkArg(v, target.method.paramTypes.getOrNull(i))
        }.toTypedArray()
        return method.invoke(receiver, *adapted)
    }

    /**
     * 一次接口方法调用（阶段 20）——语义就是 JVM 的 `INVOKEINTERFACE`：
     * **静态类型是接口，调哪一份实现看运行期对象是谁**。
     *
     * **先解析、后回退**：去运行期真实类（[RsfObject.className]）的方法表里找同名同形的实现，
     * 找不到再沿接口链找带体的默认方法；两处都没有，才用**静态类型那一份**默认方法（就是
     * [decl] 自己）。`this` 始终是实现类的对象（[callMethod] 把 0 号槽填成 [self]）。
     *
     * 默认方法**同样要解析**，不能直接跑 [decl] 那一份：接口继承里子接口把父接口的默认方法
     * 覆盖掉时（`interface Person : Named { override fun name() ... }`），静态类型写 `Named`
     * 的调用在 JVM 上照样跑子接口那份——只按 [decl] 跑就成了静态分派，解释器打父接口的、
     * 字节码打子接口的，两端结果不一样。
     *
     * 两处都没有就是内部错误——Checker 的 `checkImplementations` 已经保证实现存在，
     * 走到这里说明语义模型和运行期对不上。
     */
    private fun callInterfaceMethod(self: RsfObject, decl: FuncDecl, args: List<Any?>): Any? {
        val impl = findInterfaceImplementation(self.className, decl.name, model.paramTypesOf(decl))
        if (impl == null || impl === decl) {
            if (decl.body != null) return callMethod(decl.name, decl, self, args)
            throw RsfError(
                "interp",
                "内部错误：'${self.className}' 没有实现接口方法 '${decl.name}'",
                decl.at.line, decl.at.column, null,
            )
        }
        return try {
            callMethod(self.className, impl, self, args)
        } catch (failure: RsfFailure) {
            // 帧里记的是实现类的那份声明，调用点等着的却是接口的 decl：
            // 把这条对应关系捎在异常上（`?:` 的身份判断要看它，见 [failedIn]）。
            failure.dispatched[impl] = decl
            throw failure
        }
    }

    /**
     * 静态类型是 **JDK 接口**、运行期对象却是 rsf 实现类的那条路（阶段 20）。
     *
     * 参数类型用「个数个 Object」[wanted] 表示：JVM 的接口方法签名是**擦除后**的形态
     * （`Comparable&<T>.compareTo(T)` 在 rsf 侧写成 `compareTo(other : Box)`），两边
     * 的描述符天然对不上，所以这里只按**名字 + 个数**配——和 Checker 的
     * `checkClassCovers` 配 JDK 接口用的尺子一样。
     */
    private fun callJdkInterfaceMethod(
        self: RsfObject,
        name: String,
        arity: Int,
        args: List<Any?>,
    ): Any? {
        val impl = findInterfaceImplementation(self.className, name, List(arity) { RsfType.OBJECT })
            ?: throw RsfError(
                "interp",
                "内部错误：'${self.className}' 没有实现接口方法 '$name'",
                -1, -1, null,
            )
        return callMethod(self.className, impl, self, args)
    }

    /**
     * `super.f(...)` 打在 **classpath 上的父类方法**身上（阶段 25 K4，缺口 C）。
     *
     * 为什么不能像 [memberOf] 那条路一样 `owner.getDeclaredMethod(...)` 然后 `invoke`：
     * 反射 `invoke` 永远是**虚**的。`super.getMessage()` 的语义是「打到
     * `java/lang/Throwable` 那一份」，而反射会按运行期类再查一遍、可能命中子类
     * 覆盖（rsf 覆盖 JDK 方法暂不支持，但这条路一旦写成虚的，将来就成了隐蔽的错）。
     * 所以用 `MethodHandles.findSpecial`：`specialCaller` 恰好是 **rsf 生成的子类**，
     * JVM 解析规则下它只能绑到父类的声明上——与字节码侧的 `INVOKESPECIAL` 是同一条指令语义。
     *
     * 接收者是**真的** JDK 实例（挂在 [RsfObject.jdkParent] 上，K3 起就如此），
     * 所以不需要任何拆箱/包装转换：实参由 [adaptJdkArg] 按声明类型对齐即可。
     */
    /**
     * `super.f(实参)` 落到 **JDK 父类**那一份实现上（阶段 25 K4，缺口 C）。
     *
     * 语义是 `INVOKESPECIAL`：**打到声明类那一份**，绝不按运行时类重新解析——所以方法
     * 对象是从 [JvmMethod.owner]（Checker 按父类链选出来的**最近声明类**，例如
     * `java/lang/Throwable.getMessage`）上取的，而不是从接收者的运行时类上取的。
     *
     * 解释器里发不出真正的 `invokespecial` 指令：`java.base` 没有向无名模块 opens
     * （`MethodHandles.privateLookupIn` 抛 `IllegalAccessException`，`publicLookup` 的
     * `findSpecial` 报 `no private access for invokespecial`），这里是反射 `invoke`。
     * 它**不会**在这里分叉成虚分派，一条链式事实就能定死：
     * 1. 接收者是 [RsfObject.jdkParent]，它的运行时类就是**直接父类**本身（或者缺口 E
     *    那种只多带一个构造器的桥类）——rsf 类在解释器里没有 `.class`，覆盖不了 JDK
     *    方法（n16 明确拒绝），所以运行时类之下没有任何覆盖点；
     * 2. [JvmMethod.owner] 是父类链上**最近**的声明类（[MemberResolver] 沿
     *    `ClassPath.hierarchy` 从直接父类往上找的第一个匹配）；
     * 3. 于是「从接收者运行时类往上找第一个声明类」的结果必然是 [JvmMethod.owner] 本身。
     *
     * 也就是说这里与字节码侧发的 `INVOKESPECIAL <declaringClass>` 等价：都不看运行时类
     * 有没有更近的覆盖，差别只在于 JVM 是拿指令保证的、这里是拿上面那条事实保证的。
     */
    private fun callSuperJvmMethod(
        call: SuperCall.Jvm,
        self: RsfObject,
        args: List<Any?>,
    ): Any? {
        val m = call.member.method
        val receiver = jdkReceiver(self)
        val owner = try {
            Class.forName(m.owner.replace('/', '.'))
        } catch (e: ClassNotFoundException) {
            throw RsfError(
                "interp",
                "内部错误：找不到父类 '${m.owner.replace('/', '.')}'",
                -1, -1, null,
            )
        }
        val paramClasses = m.paramTypes.map { toJavaClass(it) }.toTypedArray()
        val target = try {
            publicMethod(owner, m.name, paramClasses)
        } catch (e: NoSuchMethodException) {
            throw RsfError(
                "interp",
                "内部错误：父类 '${m.owner.replace('/', '.')}' 里没有方法 '${m.name}'",
                -1, -1, null,
            )
        }
        val actual = args.mapIndexed { i, v ->
            adaptJdkArg(v, m.paramTypes.getOrNull(i))
        }.toTypedArray()
        return try {
            target.invoke(receiver, *actual)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 父类方法自己抛的异常原样往外扔（和直接调 Java 保持一致）
            throw e.cause ?: e
        } catch (e: ReflectiveOperationException) {
            throw RsfError(
                "interp",
                "内部错误：调不到父类 '${m.owner.replace('/', '.')}' 的 '${m.name}'：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        }
    }

    /** 先在**真实类的类链**（自己 → 直接父类 → …）的方法表里找实现，再沿接口链找带体的默认方法（[callInterfaceMethod]）。 */    private fun findInterfaceImplementation(
        runtimeClass: String,
        name: String,
        wanted: List<RsfType>,
    ): FuncDecl? {
        // 阶段 25 K3：第一条路要沿**类链**走，不能只看运行期类自己。接口实现在父类里写、
        // 子类不重复写是常规做法（`open class Base : Greeter { override fun greet() … }`
        // 配 `class D : Base()`），只看自己会找不到实现。顺序是最近的类优先——和 JVM 的
        // `INVOKEINTERFACE` / `INVOKEVIRTUAL` 解析同一套。
        for (cls in model.classChainOf(runtimeClass)) {
            cls.plainMethods
                .firstOrNull { it.name == name && paramsMatch(model.paramTypesOf(it), wanted) }
                ?.let { return it }
        }
        return findInterfaceDefault(runtimeClass, name, wanted)
    }

    /**
     * 沿 [runtimeClass] 的接口链找带体的默认方法（穿过父接口，菱形只走一次）。
     *
     * JDK 接口的默认方法**不在这里**（它们的实现不在语义模型里，得靠反射或代理）——
     * 跳过是明确的口径，不是漏写。静态类型就是 JDK 接口时也不需要它：Java 侧的默认
     * 方法由 JVM 自己解析。
     */
    private fun findInterfaceDefault(
        runtimeClass: String,
        name: String,
        wanted: List<RsfType>,
    ): FuncDecl? {
        val visited = HashSet<String>()
        fun walk(owner: String): FuncDecl? {
            for (sup in model.superInterfacesOf(owner)) {
                if (sup.fromClassPath || !visited.add(sup.name)) continue
                val iface = model.interfaceOf(sup.name) ?: continue
                iface.defaultMethods
                    .firstOrNull { it.name == name && paramsMatch(model.paramTypesOf(it), wanted) }
                    ?.let { return it }
                walk(sup.name)?.let { return it }
            }
            return null
        }
        return walk(runtimeClass)
    }

    /**
     * 接口方法的形参与实现类的形参是不是同一份签名。
     *
     * 两处**不参与比对**：接口签名里的类型变量（`T`）和 `Object`——它们擦除后都是
     * `Ljava/lang/Object;`，任何实参类型都对得上（JVM 解析 `INVOKEINTERFACE` 也正是
     * 按擦除后的描述符来）。其余位置要求描述符一字不差，这样同名重载不会被选错。
     */
    private fun paramsMatch(own: List<RsfType>, wanted: List<RsfType>): Boolean =
        own.size == wanted.size && wanted.indices.all { i ->
            val w = wanted[i]
            w.isTypeParam || w.descriptor == "Ljava/lang/Object;" || own[i].descriptor == w.descriptor
        }

    // ------------------------------------------- 把 rsf 对象交给 JDK 代码（阶段 20 M4）

    /**
     * 交给 JDK 反射调用的实参：**必须真的是那个 JDK 类型**。
     *
     * `Collections.sort(xs, new ByValue())` 里 `ByValue` 是按 `Comparator&<string>` 检查通过的
     * rsf 类，可它在解释器里只是个 [RsfObject]——反射 `invoke` 会抛
     * `IllegalArgumentException: argument type mismatch`。这里按形参类型补一层
     * [java.lang.reflect.Proxy]（见 [javaProxyOf]），让 JDK 的回调能进 rsf 实现。
     *
     * 三条边界：
     * 1. **只对接口形参套**：Proxy 只能实现接口；`Object` 形参更不该套——`xs.add(o)` 必须
     *    存原对象，存进去一个代理、rsf 侧再取出来就不是同一个东西了；
     * 2. 形参类型在 rsf 侧是**源码类型**时 `toJavaClass` 会抛，原样返回，让后面的报错照旧；
     * 3. 这个类没有任何 JDK 接口时原样返回（说不清要哪个接口，交给反射抛）。
     */
    private fun adaptJdkArg(value: Any?, want: RsfType?): Any? {
        if (value !is RsfObject || want == null) return value
        val wantClass = try {
            toJavaClass(want)
        } catch (_: Throwable) {
            return value
        }
        if (!wantClass.isInterface || wantClass.isInstance(value)) return value
        val proxy = javaProxyOf(value) ?: return value
        return if (wantClass.isInstance(proxy)) proxy else value
    }

    /** 回调再进来的代理认回它代表的 rsf 对象；不是我们造的代理就原样返回。 */
    private fun unwrapProxy(value: Any?): Any? = proxiedObjects[value] ?: value

    /**
     * 造（并缓存）一个实现 rsf 类身上那些 JDK 接口的代理。
     *
     * 回调按「名字 + 个数」找 rsf 实现——尺子和 [callJdkInterfaceMethod] 一致：JVM 的接口
     * 签名是**擦除后**的形态，rsf 侧写不出 `compare(Object, Object)`。
     * 找不到实现时退给 JDK 接口自己的 `default` 方法（`Comparator.reversed()` 这种）。
     */
    private fun javaProxyOf(obj: RsfObject): Any? {
        javaProxyCache[obj]?.let { return it }
        val interfaces = javaInterfacesOf(obj.className)
        if (interfaces.isEmpty()) return null
        val loader = interfaces.first().classLoader ?: Interpreter::class.java.classLoader
        val proxy = java.lang.reflect.Proxy.newProxyInstance(loader, interfaces.toTypedArray(), javaHandler(obj))
        javaProxyCache[obj] = proxy
        proxiedObjects[proxy] = obj
        return proxy
    }

    /**
     * rsf 类身上那些 JDK 接口的 [Class]（穿过 rsf 侧接口继承链，菱形只收一次）。
     *
     * 只收 classpath 上的：rsf 自己声明的接口不是 Java 类型，Proxy 实现不了。
     */
    private fun javaInterfacesOf(className: String): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        fun walk(owner: String) {
            for (sup in model.superInterfacesOf(owner)) {
                if (!sup.fromClassPath) {
                    walk(sup.name)
                    continue
                }
                val cls = try {
                    Class.forName(sup.internalName.replace('/', '.'))
                } catch (_: Throwable) {
                    continue
                }
                if (!cls.isInterface || !out.add(cls)) continue
                out.addAll(cls.interfaces)
            }
        }
        walk(className)
        return out.toList()
    }

    /**
     * 代理收到一次 JDK 回调：翻回 rsf 侧执行。
     *
     * `equals`/`hashCode`/`toString` 三条按**代理自己**的语义办（和 rsf 对象的同一性对齐），
     * 不去 rsf 方法表里找——rsf 类不声明它们，找了必然落空。
     */
    private fun javaHandler(obj: RsfObject): java.lang.reflect.InvocationHandler =
        java.lang.reflect.InvocationHandler { proxy, method, rawArgs ->
            val args = rawArgs?.toList().orEmpty()
            when (method.name) {
                "equals" if args.size == 1 -> proxy === args[0]
                "hashCode" if args.isEmpty() -> System.identityHashCode(obj)
                "toString" if args.isEmpty() -> obj.toString()
                else -> {
                    val impl = findInterfaceImplementation(
                        obj.className, method.name, List(args.size) { RsfType.OBJECT },
                    )
                    if (impl != null) {
                        val params = model.paramTypesOf(impl)
                        val values = args.mapIndexed { i, a ->
                            coerce(unwrapProxy(a), params.getOrElse(i) { RsfType.OBJECT })
                        }
                        callMethod(obj.className, impl, obj, values)
                    } else if (method.isDefault) {
                        java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, rawArgs)
                    } else {
                        throw RsfError(
                            "interp",
                            "内部错误：'${obj.className}' 没有实现接口方法 '${method.name}'",
                            -1, -1, null,
                        )
                    }
                }
            }
        }

    /**
     * 造一个对象（阶段 18 起：主构造 / 多个次构造 / `this(...)` 委托）。
     *
     * [expr] 是**构造点**：显式 `new C(...)` 是 [NewExpr]，省掉 `new` 的 `C(...)`
     * 是 [CallExpr]——两者在语义模型里共用同一张 `newTargets` 表，所以这里
     * 只有一处取实参 / 取类声明的地方（用 `model.constructionArgs` / `constructionName`）。
     *
     * 次序严格照 JVM：
     * 1. **实参在调用方的帧里**求值（`new C(f())` 先跑 f 再进构造器）；
     * 2. 从 Checker 选中的那个构造器出发，沿 `this(...)` 走到链的根；
     * 3. **链的根**跑字段初值（用的是根构造器自己的帧，所以字段初值里能引用主构造参数）；
     * 4. 委托语句由指令循环执行：它递归进目标构造器，返回之后继续跑本构造器剩下的语句。
     *
     * 选中的构造器是 `null` 时表示这个类一个构造器都没写 —— 隐式无参构造器：
     * 没有构造器体，只有字段初值。
     */
    private fun construct(expr: Expr, slots: Array<Any?>, body: LoweredFunction): Any? {
        // 导入的 JVM 类：`new ArrayList&<int>()`（或者 `ArrayList&<int>()`）走反射
        model.externalNewTargets[expr]?.let { return constructExternal(expr, it.method, slots, body) }

        val decl = model.classModel.newTargets[expr]
            ?: throw RsfError(
                "interp",
                "内部错误：构造没有解析到类 '${model.constructionName(expr)}'",
                expr.at.line, expr.at.column, null,
            )

        val fields = chainFieldsOf(decl.name)
        val obj = RsfObject(
            decl.name,
            arrayOfNulls(fields.size),
            fields.map { it.first },
            fields.map { it.second },
            // 枚举字段要能打出值名（否则 `Holder(kind=...)` 白丢信息）；
            // 判断与字节码后端生成 toString() 时读的是同一份语义模型
            isEnum = { t -> model.isEnumType(t) },
        )
        val args = model.constructionArgs(expr).map { eval(it, slots, body) }

        val ctor = model.classModel.ctorOf(expr)
        if (ctor == null) {
            // 隐式无参构造器：先补齐**父类那一跳**，再跑字段初值。给一个只装着 this 的帧——
            // 字段初值里的 `this.字段` / 裸字段名都从这个槽拿对象。
            val frame = arrayOfNulls<Any?>(1)
            frame[0] = obj
            model.classModel.implicitSuperDelegationOf(decl)?.let { runSuperDelegation(it, obj, frame, body, decl) }
            runFieldInitializers(decl, obj, frame, body)
        } else {
            runConstructor(decl, ctor, obj, args)
        }
        return obj
    }

    /**
     * 跑一个构造器（含它委托出去的那条链）。[args] 已经求好值，按签名顺序。
     *
     * 字段初值只在**不委托**的那个构造器（链的根）里跑一次 —— JVM 上是同一个道理：
     * 只有调 `super()` 的那条 `<init>` 才带字段初值，调 `this(...)` 的那些不带。
     */
    private fun runConstructor(decl: ClassDecl, ctor: FuncDecl, obj: RsfObject, args: List<Any?>) {
        enter(ctor.at, decl.name)
        try {
            val lowered = model.loweredOf(ctor)
            val frame = arrayOfNulls<Any?>(lowered.slotCount)
            // 0 号槽：this（Lowering 给实例方法留的局部槽从 1 开始）
            frame[0] = obj
            val params = model.paramsOf(ctor)
            for (i in params.indices) {
                // 阶段 21：伴生类的构造器允许**少给尾部实参**（省略的元素走默认值，
                // 由 [fillElementDefaults] 补）。普通构造器的个数由 Checker 保证一致，
                // 这里的越界判断只对那条路生效，行为不变。
                if (i >= args.size) break
                frame[params[i].slot] = coerce(args[i], params[i].type)
            }
            // 不委托 = 链的根：先补齐**父类那一跳**，再跑自己的字段初值，最后跑自己的体。
            if (model.classModel.delegationOf(ctor) == null) {
                // 阶段 25 K3：父类的那一串构造器在这里跑，**排在字段初值前面**（DESIGN-25 §6：
                // 父类构造器跑完之前，子类那份状态一个字都还没有）。父类委托有两种写法，
                // 这里一次都覆盖：
                // - 类头 `: Base(实参)`（主构造）与隐式 `super()` 在指令流里**没有对应指令**；
                // - 次构造体第一句的 `super(实参)` 有 [ISuperDelegate]，但那条指令是**空**的
                //   （见指令循环那一段），真正干活的就是这一句。
                // 实参在本构造器的帧里求值——和 Java 一样（`super(实参)` 的实参先算）。
                model.classModel.superDelegationOf(ctor)?.let { runSuperDelegation(it, obj, frame, lowered, decl) }
                // 阶段 21：伴生类的元素字段值来自构造器形参槽，实测参数个数少于元素表的
                // （`new Marker("hi")`）时要先把缺的元素补上——语义层不补齐，补齐是后端的事
                val annotation = model.companionAnnotationOf(decl.name)
                if (annotation != null) {
                    fillElementDefaults(companionElementCount(decl), decl, ctor, frame, lowered, args)
                }
                runFieldInitializers(decl, obj, frame, lowered, ctor, args)
            }
            execute(lowered, frame, 0)
        } finally {
            depth--
        }
    }

    /**
     * 字段初值。跑在**链的根构造器的帧**里：`class P(x0 : int) { var x : int = x0 }`
     * 的 `x0` 就在这个帧的参数槽上（字节码那边也是同一个槽位，见 emitUserConstructor）。
     */
    private fun runFieldInitializers(
        decl: ClassDecl,
        obj: RsfObject,
        frame: Array<Any?>,
        owner: LoweredFunction,
        ctor: FuncDecl? = null,
        args: List<Any?> = emptyList(),
    ) {
        val fieldTypes = model.classModel.fieldTypes[decl].orEmpty()
        // 阶段 25 K3：本类这一段字段在对象数组里的**起始下标**（父链字段总数）。
        // 没有父类时恒为 0，写盘位置和以前逐字节一致。
        val base = model.fieldBaseOf(decl.name)
        // 阶段 21：伴生类的前 n 个字段是**合成的元素字段**，它们的 `initializer` 只是
        // 占位（默认值字面量，或与类型相配的零值）——有实参的取构造器形参槽（见
        // [fillElementDefaults]），省略的才落回这个占位。
        val elementCount = companionElementCount(decl)
        val params = ctor?.let { model.paramsOf(it) }.orEmpty()
        for ((index, field) in decl.fields.withIndex()) {
            val value = if (index < elementCount && index < args.size && index < params.size) {
                frame[params[index].slot]
            } else {
                // 字段初值都是纯表达式（没有 goto / 标签），所以这里的 [owner] 只用来
                // 传参——求值只用 [frame]（`this` 在 0 号槽）
                eval(field.initializer, frame, owner)
            }
            // 绝对下标 = 本类起始下标 + 段内下标（和 Checker 烘进 UserMember.Field.index
            // 的是同一条公式）——父类字段与子类字段因此共用一条扁平数组
            obj.fields[base + index] = coerce(value, fieldTypes.getOrElse(index) { RsfType.INT })
        }
    }

    /**
     * 类 [name] 的**整条字段链**（父类在前、自己在后），每项是 `(字段名, 字段类型)`。
     *
     * 列表的第 i 项就落在对象字段数组的第 i 个格子上——[RsfObject] 的数组长度、字段名表、
     * 字段类型表（`toString()` 用）都从这一份推出来，三张表不可能对不齐。顺序与语义层的
     * [SemanticModel.fieldChainOf] 完全一致（那边多带一个声明类，这里只要渲染要的两样）。
     *
     * 泛型写法（`class Wrap(v : string) : Holder&<string>(v)`）下父类字段的类型取的是
     * **声明处那一份**（`T` 本身），不做实参代换——与 `toString()` 对泛型字段的现有处理
     * 一致；字段的**读取**走的是 `UserMember.Field.type`（Checker 已经代换过），不受影响。
     */
    private fun chainFieldsOf(name: String): List<Pair<String, RsfType>> {
        val types = model.classModel.fieldTypes
        val out = ArrayList<Pair<String, RsfType>>()
        for (decl in model.fieldChainOf(name).map { it.first }.distinct()) {
            val own = types[decl].orEmpty()
            for ((index, field) in decl.fields.withIndex()) {
                out.add(field.name to own.getOrElse(index) { RsfType.INT })
            }
        }
        return out
    }

    /**
     * 跑一次**父类构造委托**（阶段 25 K3）：`super(实参)` / 类头 `: Base(实参)` / 隐式 `super()`。
     *
     * 三种目标只在 [SuperDelegation] 的两个字段上有区别：
     * - `target != null`——父类是 rsf 类且选中了它的某个构造器，递归进 [runConstructor]；
     * - `target == null` 而父类有声明——父类一个构造器都没写（等价于隐式无参构造器），那就把
     *   父类那条「隐式构造」照 [construct] 里 `ctor == null` 的路再走一遍；
     * - 父类没有声明——**JDK 父类**：rsf 类在解释器里没有真实 `.class`，这一跳靠反射造一个
     *   真的父类实例，挂在 [RsfObject.jdkParent] 上（从它继承来的成员都作用在它身上）。
     *
     * 三种情况都在**同一个对象**上继续：rsf 只有一条字段数组、一个对象身份，[owner] 与
     * [frame] 是**发出委托的那个构造器**的（实参在那儿求值，和 Java 一样）。
     */
    private fun runSuperDelegation(
        sup: SuperDelegation,
        obj: RsfObject,
        frame: Array<Any?>,
        owner: LoweredFunction,
        decl: ClassDecl,
    ) {
        val args = sup.arguments.map { eval(it, frame, owner) }
        val parentDecl = sup.superClass.declaration
        if (sup.target != null && parentDecl != null) {
            runConstructor(parentDecl, sup.target, obj, args)
            return
        }
        if (parentDecl != null) {
            // 父类是 rsf 类，但它一个构造器都没写：父类那一跳只剩它自己的父类 + 它自己的字段初值
            model.classModel.implicitSuperDelegationOf(parentDecl)?.let {
                runSuperDelegation(it, obj, frame, owner, parentDecl)
            }
            runFieldInitializers(parentDecl, obj, frame, owner)
            return
        }
        obj.jdkParent = instantiateJdkParent(sup, args)
    }

    /**
     * 造一个 **JDK 父类实例**（阶段 25 K3）：`class MyError(m : string) : Exception(m)` 的那个
     * `java.lang.Exception`。
     *
     * 构造器**不再在这里挑**（阶段 25 K4，缺口 D）：Checker 已经按同一套打分选好了一条并挂在
     * **这条委托边**上（[org.example.complie.sema.SuperDelegation.jvmCtor]），字节码侧也读同一份。
     * 从前这里（`ctorScore`）与 `BytecodeCompiler.jdkCtorScore` 是两份副本，平局规则稍有出入，
     * 同一份源码两端就会建出**不同的父类状态**。
     */
    private fun instantiateJdkParent(sup: SuperDelegation, args: List<Any?>): Any {
        val internal = sup.superClass.internalName
        val chosen = sup.jvmCtor
            ?: throw RsfError(
                "interp",
                "内部错误：${sup.superClass.display} '${sup.superClass.name}' 里没有匹配的构造器",
                -1, -1, null,
            )
        val owner = Class.forName(internal.replace('/', '.'))
        val paramClasses = chosen.paramTypes.map { toJavaClass(it) }.toTypedArray()
        val javaCtor = owner.getDeclaredConstructor(*paramClasses)
        val adapted = args.mapIndexed { i, v ->
            adaptJdkArg(v, chosen.paramTypes.getOrNull(i))
        }.toTypedArray()
        // 构造器能反射调用就直接调；`protected` 而 `java.base` 又没 opens 的
        // （`java.lang.ClassLoader()`，见缺口 E）`trySetAccessible()` 会返回 false，
        // 这时改走子类桥——父类构造器**真的跑**，不是绕过它。
        if (!javaCtor.trySetAccessible()) {
            return JdkParentBridge.newInstance(internal, chosen.descriptor, paramClasses, adapted, chosen.paramTypes)
        }
        return try {
            javaCtor.newInstance(*adapted)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 父类构造器自己抛的异常原样往外扔（和直接调 Java 保持一致）
            throw e.cause ?: e
        } catch (e: NoSuchMethodException) {
            throw RsfError(
                "interp",
                "内部错误：调不到 JDK 父类 '$internal' 的构造器 ${chosen.descriptor}：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        } catch (e: IllegalAccessException) {
            throw RsfError(
                "interp",
                "内部错误：JDK 父类 '$internal' 的构造器 ${chosen.descriptor} 访问不了：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        } catch (e: InstantiationException) {
            throw RsfError(
                "interp",
                "内部错误：JDK 父类 '$internal' 建不出实例：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        }
    }

    /**
     * 注解元素字段的**省略实参**（阶段 21）：把没给实参的元素形参槽填成元素默认值。
     *
     * 为什么需要这一步：`new Marker("hi")` 少给的实参在语义层**不做补齐**（Checker 只保证
     * 「省略的元素有默认值」），补齐是后端的事——M3 在 `emitNew` 里补，解释器就得在这儿补，
     * 而且必须补在 `execute` **之前**：伴生体自己的行为方法（`describe`）要读这些形参槽。
     *
     * 补的是 `field.initializer`——对元素字段它就是元素默认值（或与类型相配的零值，
     * 见 M2 的 `companionElementField`），基本类型因此不会拿到 null。
     */
    private fun fillElementDefaults(
        elementCount: Int,
        decl: ClassDecl,
        ctor: FuncDecl?,
        frame: Array<Any?>,
        owner: LoweredFunction,
        args: List<Any?>,
    ) {
        val params = ctor?.let { model.paramsOf(it) }.orEmpty()
        val fieldTypes = model.classModel.fieldTypes[decl].orEmpty()
        for (index in 0 until elementCount) {
            val slot = params.getOrNull(index)?.slot ?: continue
            if (index < args.size && frame[slot] != null) continue
            val field = decl.fields[index]
            frame[slot] = coerce(
                eval(field.initializer, frame, owner),
                fieldTypes.getOrElse(index) { RsfType.INT },
            )
        }
    }

    // ------------------------------------------------------------------ 注解（阶段 21）

    /**
     * 伴生类的元素字段个数（[ClassDecl.fields] 的前 n 个）；不是伴生类就是 0。
     *
     * 这是「元素字段的值来自构造器参数」唯一需要知道的那点形状信息，M2 把它
     * 登记在 [SemanticModel.companionAnnotations] 里，M3 的 `emitUserConstructor` 读的
     * 也是这一份（`elementCount`）——两端同一把尺子，不各自对着 AST 数。
     */
    private fun companionElementCount(decl: ClassDecl): Int =
        model.companionAnnotationOf(decl.name)?.companion?.elementFields?.size ?: 0

    /**
     * 注解元素访问器的实现（阶段 21）：接收者运行期是个**伴生类的对象**，
     * 静态类型却是**注解接口**（`Marker`），[member] 是元素名。
     *
     * 为什么不能交给 [callInterfaceMethod]：合成注解接口**不进 `interfaces` 表**
     * （`@interface` 装不进 `InterfaceDecl`），所以 `findInterfaceImplementation` 沿
     * 接口链一路走空——访问器一旦无体就会抛「内部错误」，有体也只能靠回退跑到
     * `decl` 那一份上（`className` 还不是伴生类）。元素访问器是**非虚**的：伴生类
     * 自己就带一份（[SemanticModel.elementAccessorOf]），查到了直接当普通实例方法调。
     *
     * 查不到（不是伴生对象，或者元素名不对）返回 null，让调用方保持原来的报错路径。
     */
    private fun annotationElementCall(self: RsfObject, member: String, args: List<Any?>): Any? {
        val annotation = model.companionAnnotationOf(self.className) ?: return null
        val accessor = model.elementAccessorOf(annotation.name, member) ?: return null
        return callMethod(self.className, accessor, self, args)
    }

    /**
     * 构造**导入的 JVM 类**（`new ArrayList&<int>()`，或者省掉 `new` 的
     * `ArrayList&<int>()`）。[expr] 是构造点（[NewExpr] 或 [CallExpr]）。
     *
     * 走反射 `newInstance`。实参的装箱/拆箱由 JVM 自己按构造器签名处理——
     * 解释器里基本类型本来就是装箱的，所以和字节码那边发的
     * `INVOKESPECIAL <init>` 是同一件事。
     */
    private fun constructExternal(
        expr: Expr,
        ctor: org.example.complie.interop.JvmMethod,
        slots: Array<Any?>,
        body: LoweredFunction,
    ): Any {
        val owner = Class.forName(ctor.owner.replace('/', '.'))
        val paramClasses = ctor.paramTypes.map { toJavaClass(it) }.toTypedArray()
        // try 版而不是 `isAccessible = true`：JDK 内部那些没 opens 出来的包
        // （`java.lang.AbstractStringBuilder` 之类）会让强制版直接抛异常
        val javaCtor = owner.getDeclaredConstructor(*paramClasses).also { it.trySetAccessible() }
        val rawArgs = model.constructionArgs(expr).map { eval(it, slots, body) }
        val args = rawArgs.mapIndexed { i, v -> adaptJdkArg(v, ctor.paramTypes.getOrNull(i)) }.toTypedArray()
        return try {
            javaCtor.newInstance(*args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 构造器自己抛的异常原样往外扔（和直接调 Java 保持一致）
            throw e.cause ?: e
        }
    }

    /**
     * 按「声明类 + 精确参数类型」取一个 JVM 方法。
     *
     * 两条路，顺序不能反：
     * 1. `getMethod` —— **public + 沿继承链**，这正是 `INVOKEVIRTUAL` 的解析语义，
     *    而且不需要 `setAccessible`。`StringBuilder.length()` 这种从包私有的
     *    `java.lang.AbstractStringBuilder` 继承来的方法只能走这条：
     *    `getDeclaredMethod` + `setAccessible(true)` 会抛
     *    `InaccessibleObjectException`（java.base 没有 opens java.lang）。
     * 2. `getDeclaredMethod`（protected / 包私有成员）—— 这时才需要 `trySetAccessible`。
     *    用 try 版：进不去就保持原样，让调用方拿到和 javac 一致的 `IllegalAccessError`，
     *    而不是在取方法这一步就抛一个只有解释器才有的异常。
     */
    private fun publicMethod(
        owner: Class<*>,
        name: String,
        paramClasses: Array<Class<*>>,
    ): java.lang.reflect.Method {
        try {
            return owner.getMethod(name, *paramClasses)
        } catch (e: NoSuchMethodException) {
            val declared = owner.getDeclaredMethod(name, *paramClasses)
            declared.trySetAccessible()
            return declared
        }
    }

    /**
     * 当前对象：实例方法/构造器的 0 号槽就是 `this`。
     *
     * 阶段 18 起字段初值也跑在**根构造器的帧**里（那时 0 号槽同样是 this），
     * 所以不再需要「临时存一份待用对象」那种兜底状态。
     */
    private fun thisObject(slots: Array<Any?>): RsfObject =
        slots.getOrNull(0) as? RsfObject
            ?: throw RsfError("interp", "内部错误：这里没有 this", 0, 0, null)

    // ------------------------------------------------------------------ 数组（阶段 13）

    /**
     * `new T[n]` / `new T[n][m]`。
     *
     * **直接用 JVM 自己的数组**（`java.lang.reflect.Array.newInstance`），不自己造一个
     * 「rsf 数组」类。这是本阶段最重要的一个决定，理由：
     *
     * 1. **默认值天然一致**：JVM 的数组一定被填成 `0 / false / null`，不用手写一遍；
     * 2. **引用语义天然一致**：数组就是对象引用，传参、返回、赋给另一个变量、
     *    存进字段都是同一个引用——「传进去改元素，外面看得见」这件事两端自动对齐；
     * 3. **两边真的能对上**：编译产物那边发的是 `NEWARRAY` / `ANEWARRAY` /
     *    `MULTIANEWARRAY`，得到的就是同一种东西，而不是「看起来像」的两种实现。
     *
     * 多维照 JVM 的规则一次分配（`java.lang.reflect.Array.newInstance(组件类型, 各维长度...)`），
     * 和字节码的 `MULTIANEWARRAY` 一一对应。
     */
    private fun newArray(expr: NewArrayExpr, slots: Array<Any?>, body: LoweredFunction): Any {
        val resultType = model.typeOf(expr)
        val dims = IntArray(expr.dimensions.size) { i ->
            val n = eval(expr.dimensions[i], slots, body) as Int
            // 负数长度：JVM 会抛 NegativeArraySizeException，而解释器的 try
            // **只接 rsf 自己的错误**——不在这里拦住，两端就会出现
            // 「一边被 catch 住、一边直接穿出去」的分歧。所以两端都显式检查。
            if (n < 0) throw RsfFailure(ArrayRuntime.lengthFailure(n))
            n
        }
        // 多维：传**最内层**的元素类型 + 每一维的长度。
        //
        // 这里有个坑：`Array.newInstance` 的规则是「componentType 是数组类时，
        // 新数组的总维数 = componentType 的维数 + dimensions.length」。
        // 传 `int[].class` 配 (2, 3) 建出来的是 `[[[I`（三维！），
        // 而 `new int[2][3]` 要的 `[[I` 必须用 `int.class` + (2, 3)。
        return java.lang.reflect.Array.newInstance(
            toJavaClass(resultType.copy(arrayDepth = 0)),
            *dims,
        )
    }

    /**
     * 越界检查：**和编译产物同一套消息**（`数组下标越界：长度 3，下标 5`）。
     *
     * 为什么不让 `java.lang.reflect.Array` 自己抛 `ArrayIndexOutOfBoundsException`：
     * 那个异常的类型和消息都跟字节码那边对不上（JVM 的消息是
     * `Index 5 out of bounds for length 3`），而且解释器的 `try` 根本接不住它。
     * 显式检查 → 两端都是「同一条 rsf 运行期错误」。
     */
    private fun checkArrayIndex(array: Any?, index: Int) {
        val length = java.lang.reflect.Array.getLength(array)
        if (index !in 0..<length) {
            throw RsfFailure(ArrayRuntime.indexFailure(length, index))
        }
    }

    /**
     * 数组元素的收敛：**null 要原样放行**。
     *
     * 引用类型数组的元素默认值就是 null（`var names : string[] = new string[3]` 里
     * 每个元素都是 null），而 [coerce] 对非空 `string` 会做 `v as String` ——
     * 一个默认值就把解释器炸了，编译产物却好好地打印 "null"。所以读元素走这一条。
     */
    private fun coerceElement(v: Any?, element: RsfType): Any? =
        if (v == null) null else coerce(v, element)

    /** rsf 类型 → 反射要用的 Class。 */
    private fun toJavaClass(type: RsfType): Class<*> = when {
        // 阶段 13：数组类型 → JVM 的数组 Class（`int[]` → `[I`、`Point[]` → `[LPoint;`）。
        // 用「造一个长度为 0 的数组再问它是什么类」拿到，是因为组件类型本身可能是
        // 数组（`int[][]` 的组件是 `int[]`）——递归下去自然就对了。
        type.isArray -> java.lang.reflect.Array.newInstance(toJavaClass(type.elementType), 0).javaClass
        // 用户自定义类在解释器里就是 RsfObject（它没有对应的 .class 可以去加载，
        // Class.forName("Point") 会直接 ClassNotFoundException）。所以 `Point[]` /
        // `Box&<int>[]` 的元素类型按 RsfObject 建数组——这是解释器**内部**的载体，
        // 对 rsf 语义没有影响（元素读写仍然只经 rsf 的类型检查）。
        type.isExternal && model.classModel.classes.containsKey(type.externalName) ->
            RsfObject::class.java
        // 阶段 19c：枚举同理——枚举的「类」是编译期才生成出来的（解释器根本不生成 .class），
        // `Class.forName("TokenKind")` 必然 ClassNotFoundException。解释器里枚举值的载体
        // 是 [RsfEnumValue] 单例，所以 `TokenKind[]` 按它建数组。
        // 这个 `.class` 是 JVM 上的真实类（编译器自己编译出来的），不是生成的产物，
        // 所以反射建数组没有问题；元素读写照旧只经 rsf 的类型检查。
        type.isExternal && model.isEnumType(type) -> RsfEnumValue::class.java
        type.isExternal -> Class.forName(type.externalName!!.replace('/', '.'))
        // 类型参数擦除到 Object：反射调用时按 Object 找方法
        type.isTypeParam -> java.lang.Object::class.java
        type.base == RsfBase.STRING -> String::class.java
        type.nullable && type.base.isPrimitive -> when (type.base) {
            RsfBase.BOOL -> java.lang.Boolean::class.java
            RsfBase.BYTE -> java.lang.Byte::class.java
            RsfBase.CHAR -> java.lang.Character::class.java
            RsfBase.INT -> java.lang.Integer::class.java
            RsfBase.LONG -> java.lang.Long::class.java
            RsfBase.FLOAT -> java.lang.Float::class.java
            RsfBase.DOUBLE -> java.lang.Double::class.java
            else -> throw IllegalStateException("不是基本类型: $type")
        }
        else -> when (type.base) {
            RsfBase.BOOL -> Boolean::class.javaPrimitiveType!!
            // byte.class / char.class：反射找 `String.charAt(int) -> char` 这类方法要用
            RsfBase.BYTE -> Byte::class.javaPrimitiveType!!
            RsfBase.CHAR -> Character::class.javaPrimitiveType!!
            RsfBase.INT -> Int::class.javaPrimitiveType!!
            RsfBase.LONG -> Long::class.javaPrimitiveType!!
            RsfBase.FLOAT -> Float::class.javaPrimitiveType!!
            RsfBase.DOUBLE -> Double::class.javaPrimitiveType!!
            RsfBase.VOID -> Void.TYPE
            else -> throw IllegalStateException("不能反射调用: $type")
        }
    }

    private fun evalCall(expr: CallExpr, slots: Array<Any?>, body: LoweredFunction): Any? {
        if (expr.name == "print" || expr.name == "println") {
            if (expr.arguments.isEmpty()) {
                if (expr.name == "println" && !silent) out.println()
                return null
            }
            val arg = expr.arguments[0]
            // 参数照常求值（可能有副作用），只是静默重放时不输出
            val text = render(eval(arg, slots, body), model.typeOf(arg))
            if (!silent) {
                if (expr.name == "println") out.println(text) else out.print(text)
            }
            return null
        }

        // 阶段 16：带签名的内置函数（toByte / toChar / charToInt / parseInt / isDigit…）
        Builtins[expr.name]?.let { return evalBuiltin(expr, it, slots, body) }

        // 类里按名字直接调本类方法（隐式 this / 隐式类名）
        model.classModel.implicitCalls[expr]?.let { member ->
            val method = member.decl
            val args = expr.arguments.map { eval(it, slots, body) }
            // 阶段 20：默认方法体里写 `greet(name)` 这种**隐式 this 调用**时，目标可能是
            // 同一个接口里的另一个接口方法（Checker 记的 interfaceOwner）——那时 `this`
            // 是实现类的对象，得和显式 `this.greet(...)` 走同一条虚分派路。
            if (member.interfaceOwner && !method.isStatic) {
                val self = thisObject(slots)
                    ?: throw RsfError(
                        "interp", "内部错误：接口方法 '${method.name}' 的调用点没有 this",
                        expr.at.line, expr.at.column, null,
                    )
                // 阶段 21：伴生体里按裸名调元素访问器（`value()`）与显式 `m.value()` 同一条路
                annotationElementCall(self, method.name, args)?.let { return it }
                return callInterfaceMethod(self, method, args)
            }
            val self = if (method.isStatic) null else thisObject(slots)
            return invokeUserMethod(member.className, method, self, args)
        }

        // 阶段 25 K3：隐式 this 调**从 JDK 父类继承来的方法**——`class MyError(m : string) :
        // Exception(m)` 里的 `getMessage()`。它在 rsf 侧没有任何声明，Checker 把选中的那个
        // `java.lang.Throwable.getMessage` 记在 implicitJvmCalls 里，反射作用在对象配对出来的
        // JDK 父类实例上（和显式 `e.getMessage()` 走同一条路）。
        model.classModel.implicitJvmCallOf(expr)?.let { target ->
            val args = expr.arguments.map { eval(it, slots, body) }
            return callInheritedJvmMethod(thisObject(slots), target, args)
        }

        val target = model.callTargets[expr]
            ?: throw RsfError(
                "interp", "内部错误：未解析的函数调用 '${expr.name}'",
                expr.at.line, expr.at.column, null,
            )
        val args = expr.arguments.map { eval(it, slots, body) }
        return call(target, args)
    }

    /**
     * 内置函数求值（阶段 16）。
     *
     * 实现原则：**能和编译产物对齐就和它对齐**。
     *
     * - 分类与大小写直接调 `java.lang.Character`（编译产物那边调的就是它），
     *   免得自己写一套判断在偏僻字符上跟 JVM 分家；
     * - `parseInt` 失败时抛 [RsfFailure]，消息用 [Builtins.parseIntFailure] 拼
     *   ——编译产物里生成的 `__rsf_parseInt$rt` 用的是同一个格式，两端
     *   `catch (e : string) { println(e) }` 打出来的东西逐字节相同；
     * - `toByte` / `toChar` 在这里就截断（`Byte` / `Char` 存的就是截断后的值），
     *   编译产物那边对应 `I2B` / `I2C`；
     * - 四个文件函数（阶段 16b）调的是和编译产物**完全相同**的 `java.nio.file` 方法，
     *   失败消息由 [FileRuntime.failure] 拼（格式只定义在那一处，见 [fileCall]）。
     */
    private fun evalBuiltin(
        expr: CallExpr,
        builtin: Builtin,
        slots: Array<Any?>,
        body: LoweredFunction,
    ): Any? = callBuiltin(builtin, expr.arguments.map { eval(it, slots, body) })

    /**
     * 内置函数体（实参已经求好值）。
     *
     * 和「实参求值」拆开是为了 `?:`：那边要保证实参在保护范围**之外**求值
     * （编译产物就是这么排的），只把调用本身包进 try。
     */
    private fun callBuiltin(builtin: Builtin, args: List<Any?>): Any? {
        val arg = args.firstOrNull()
        return when (builtin.name) {
            "toByte" -> asNumber(arg).toByte()
            "toChar" -> asNumber(arg).toInt().toChar()
            "charToInt" -> (arg as Char).code
            "intToString" -> asNumber(arg).toString()
            "parseInt" -> {
                val text = arg as String
                text.toIntOrNull() ?: throw RsfFailure(Builtins.parseIntFailure(text))
            }
            // 分类与大小写一律直接问 java.lang.Character：编译产物那边调的就是它，
            // 两端在偏僻字符上的行为因此不会分家
            "isDigit" -> Character.isDigit(arg as Char)
            "isLetter" -> Character.isLetter(arg as Char)
            "isWhitespace" -> Character.isWhitespace(arg as Char)
            "toUpper" -> Character.toUpperCase(arg as Char)
            "toLower" -> Character.toLowerCase(arg as Char)

            // 文件读写（阶段 16b）：调的是**和编译产物完全相同的那几个 java.nio.file 方法**
            // （编译产物那边是程序类上的 __rsf_*$rt 辅助方法发同样的调用），
            // 所以同一个失败在两边的异常消息天然相同；这里只负责把异常换成 RsfFailure。
            "readText" -> fileCall(builtin.name, args[0] as String) {
                Files.readString(Paths.get(it))
            }
            "writeText" -> fileCall(builtin.name, args[0] as String) {
                Files.write(Paths.get(it), (args[1] as String).toByteArray(StandardCharsets.UTF_8))
                null
            }
            "readBytes" -> fileCall(builtin.name, args[0] as String) {
                Files.readAllBytes(Paths.get(it))
            }
            "writeBytes" -> fileCall(builtin.name, args[0] as String) {
                Files.write(Paths.get(it), args[1] as ByteArray)
                null
            }

            else -> throw IllegalStateException("内部错误：没实现的内置函数 '${builtin.name}'")
        }
    }

    /**
     * 一次文件操作，失败换成 rsf 自己的错误（阶段 16b）。
     *
     * **接 `Exception` 而不是 `IOException`**：路径本身就非法时（Windows 下的
     * `Paths.get("a<b")`）抛的是 `InvalidPathException`，它不是 IOException。
     * 编译产物那边的辅助方法 catch 的上限也是 `java/lang/Exception`，
     * 两边必须是同一个范围，否则会出现「解释器接住、编译产物漏出去」的分歧。
     *
     * 消息用 [FileRuntime.failure] 拼——**格式只定义在那一处**，编译产物用前后缀
     * ldc 拼出同一个串（和 `parseInt` 的做法一样）。
     */
    private fun fileCall(name: String, path: String, block: (String) -> Any?): Any? = try {
        block(path)
    } catch (e: Exception) {
        throw RsfFailure(FileRuntime.failure(name, path, e.message))
    }

    private fun evalUnary(expr: UnaryExpr, slots: Array<Any?>, body: LoweredFunction): Any? {
        val v = eval(expr.operand, slots, body)
        return when (expr.op) {
            // 结果类型由 Checker 定好了：byte / char 取负之后是 int，所以这里按**结果**分派
            // （编译产物也是这个思路：栈上的 byte/char 就是 int，直接 INEG）
            TokenKind.MINUS -> when (model.typeOf(expr)) {
                RsfType.INT -> -asNumber(v).toInt()
                RsfType.LONG -> -asNumber(v).toLong()
                RsfType.FLOAT -> -asNumber(v).toFloat()
                RsfType.DOUBLE -> -asNumber(v).toDouble()
                else -> throw IllegalStateException("一元减号的操作数类型不对")
            }
            TokenKind.BANG -> !(v as Boolean)
            else -> throw IllegalStateException("不认识的一元运算符 ${expr.op}")
        }
    }

    private fun evalBinary(expr: BinaryExpr, slots: Array<Any?>, body: LoweredFunction): Any? {
        val op = expr.op
        val leftType = model.typeOf(expr.left)
        val rightType = model.typeOf(expr.right)
        val resultType = model.typeOf(expr)

        // `&&` / `||`（阶段 19c）：**短路**。必须在「两边都求值」那句之前返回，
        // 否则右边的副作用（函数调用、打印）会多跑一次，而编译产物那边
        // 右边在跳转之后才求值——两端输出立刻分叉。
        //
        // 形状刻意写得和字节码那三条指令一一对应：
        //   算左 → 能定结果就不算右 → 否则结果就是右
        // 注意右边在**两个运算符下都原样返回**（bool 已经在 Checker 里查过），
        // 不需要再收敛：`&&` 的右值就是结果本身。
        if (op == TokenKind.ANDAND) {
            val left = eval(expr.left, slots, body) as Boolean
            return if (!left) false else eval(expr.right, slots, body) as Boolean
        }
        if (op == TokenKind.OROR) {
            val left = eval(expr.left, slots, body) as Boolean
            return if (left) true else eval(expr.right, slots, body) as Boolean
        }

        if (op == TokenKind.PLUS && resultType == RsfType.STRING) {
            val a = render(eval(expr.left, slots, body), leftType)
            val b = render(eval(expr.right, slots, body), rightType)
            return a + b
        }

        val a = eval(expr.left, slots, body)
        val b = eval(expr.right, slots, body)

        if (op in COMPARISONS) {
            // 非数值一律走「引用语义」：字符串按内容、可空装箱值按 equals、null 按引用。
            // 这和编译产物里统一调 Objects.equals 是一回事。
            // byte / char 也算「数值」——它们先提升成 int 再比（见 RsfType.promote）
            if (!(leftType.isArithmetic && rightType.isArithmetic)) {
                if (op != TokenKind.EQ && op != TokenKind.NE) {
                    throw IllegalStateException("'${op.text}' 不能用在非数值上")
                }
                val eq = if (a == null || b == null) (a == null && b == null) else a == b
                return if (op == TokenKind.EQ) eq else !eq
            }
            val common = RsfType.promote(leftType, rightType)
                ?: throw IllegalStateException("比较运算的类型不对: $leftType / $rightType")
            // byte / char 先提升成 int 再比：栈上的表示和编译产物那条 IF_ICMPxx 一致
            val cmpType = RsfType(common.base.promoted())
            val cmp = compare(coerce(a, cmpType), coerce(b, cmpType), cmpType)
            return when (op) {
                TokenKind.EQ -> cmp == 0
                TokenKind.NE -> cmp != 0
                TokenKind.LT -> cmp < 0
                TokenKind.GT -> cmp > 0
                TokenKind.LE -> cmp <= 0
                TokenKind.GE -> cmp >= 0
                else -> throw IllegalStateException("不是比较运算符: $op")
            }
        }

        val x = coerce(a, resultType)
        val y = coerce(b, resultType)
        return when (resultType) {
            RsfType.INT -> intOp(op, x as Int, y as Int)
            RsfType.LONG -> longOp(op, x as Long, y as Long)
            RsfType.FLOAT -> floatOp(op, x as Float, y as Float)
            RsfType.DOUBLE -> doubleOp(op, x as Double, y as Double)
            else -> throw IllegalStateException("算术运算的结果类型不对: $resultType")
        }
    }

    private fun intOp(op: TokenKind, a: Int, b: Int): Int = when (op) {
        TokenKind.PLUS -> a + b
        TokenKind.MINUS -> a - b
        TokenKind.STAR -> a * b
        TokenKind.SLASH -> a / b
        TokenKind.PERCENT -> a % b
        else -> throw IllegalStateException("不是算术运算符: $op")
    }

    private fun longOp(op: TokenKind, a: Long, b: Long): Long = when (op) {
        TokenKind.PLUS -> a + b
        TokenKind.MINUS -> a - b
        TokenKind.STAR -> a * b
        TokenKind.SLASH -> a / b
        TokenKind.PERCENT -> a % b
        else -> throw IllegalStateException("不是算术运算符: $op")
    }

    private fun floatOp(op: TokenKind, a: Float, b: Float): Float = when (op) {
        TokenKind.PLUS -> a + b
        TokenKind.MINUS -> a - b
        TokenKind.STAR -> a * b
        TokenKind.SLASH -> a / b
        TokenKind.PERCENT -> a % b
        else -> throw IllegalStateException("不是算术运算符: $op")
    }

    private fun doubleOp(op: TokenKind, a: Double, b: Double): Double = when (op) {
        TokenKind.PLUS -> a + b
        TokenKind.MINUS -> a - b
        TokenKind.STAR -> a * b
        TokenKind.SLASH -> a / b
        TokenKind.PERCENT -> a % b
        else -> throw IllegalStateException("不是算术运算符: $op")
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 解释器里的值收敛到静态类型。
     *
     * 各类型的**运行时载体**（两端必须一致，所以这里写清楚）：
     * `byte` 是 `kotlin.Byte`（= java.lang.Byte）、`char` 是 `kotlin.Char`
     * （= java.lang.Character），int/long/float/double 是各自的包装类，
     * 可空类型就是「同一个值或者 null」（不额外装箱）。
     */
    private fun coerce(v: Any?, t: RsfType): Any? {
        if (t.isNullLiteral || t.isVoid) return null
        // 阶段 13：数组是 JVM 上真实的数组对象，原样带着——元素类型由静态类型管，
        // 而「元素类型对不对」Checker 已经查过了（这里再装箱/拆箱只会引入分歧）。
        if (t.isArray) return v
        // 可空类型在解释器里就是「值可能是 null」，不需要装箱
        if (t.nullable) return if (v == null) null else coerce(v, t.asNonNull())
        return when (t.base) {
            RsfBase.BOOL -> v as Boolean
            // byte 存成 Byte：toByte(300) 这种要在这里就截断成 44，
            // 否则解释器留着 300、编译产物是 44，两端就不一致了
            RsfBase.BYTE -> asNumber(v).toByte()
            // char 不是 Number（Kotlin 的 Char 不继承 Number），所以单独来一支：
            // 反射回来的是 java.lang.Character、算术提升过来的是 Int，两种都要收
            RsfBase.CHAR -> when (v) {
                is Char -> v
                is Number -> v.toInt().toChar()
                else -> throw IllegalStateException("不能把 $v 当 char 用")
            }
            RsfBase.INT -> asNumber(v).toInt()
            RsfBase.LONG -> asNumber(v).toLong()
            RsfBase.FLOAT -> asNumber(v).toFloat()
            RsfBase.DOUBLE -> asNumber(v).toDouble()
            RsfBase.STRING -> v as String
            RsfBase.EXTERNAL -> v // JVM 对象原样带着
            RsfBase.TYPE_PARAM -> v // 类型参数占位（类体内部）：JVM 上就是 Object
            RsfBase.VOID, RsfBase.NULL -> null
        }
    }

    /**
     * 把解释器里的值看成 JVM 意义上的整数。
     *
     * `char` 在 JVM 栈上就是 int（`charToInt` 在字节码里一条指令都不用发），
     * 所以 `'a' + 1` 在编译产物里是 int 加法；解释器这边就得自己把 Char 翻译成码点，
     * 否则 `coerce('a', int)` 会直接 ClassCastException。
     */
    private fun asNumber(v: Any?): Number = when (v) {
        is Char -> v.code
        is Number -> v
        else -> throw IllegalStateException("不是数值: $v")
    }

    private fun compare(a: Any?, b: Any?, t: RsfType): Int = when (t) {
        RsfType.INT -> asNumber(a).toInt().compareTo(asNumber(b).toInt())
        RsfType.LONG -> asNumber(a).toLong().compareTo(asNumber(b).toLong())
        RsfType.FLOAT -> asNumber(a).toFloat().compareTo(asNumber(b).toFloat())
        RsfType.DOUBLE -> asNumber(a).toDouble().compareTo(asNumber(b).toDouble())
        else -> throw IllegalStateException("不能比较 $t")
    }

    /** 与 Java 的 String.valueOf / PrintStream.println 对齐的字符串化。 */
    private fun render(v: Any?, t: RsfType): String = when {
        t.isVoid -> ""
        v == null -> "null"
        t.base == RsfBase.STRING -> v as String
        else -> v.toString()
    }

    companion object {
        const val MAX_DEPTH = 2000

        /**
         * `?:` 的哨兵：区分「左边出错了（取右边）」和「左边的值恰好是 null」。
         * 两者在解释器里都是「没有可用值」，但语义完全不同，不能混成一个 null。
         */
        private val CAUGHT = Any()

        private val COMPARISONS = setOf(
            TokenKind.EQ, TokenKind.NE, TokenKind.LT, TokenKind.GT, TokenKind.LE, TokenKind.GE,
        )
    }
}

/**
 * JDK 父类构造器的**子类桥**（阶段 25 K4，缺口 E）。
 *
 * 场景：`class MyLoader : ClassLoader()` 里的 `ClassLoader()` 是 `protected` 的，而
 * `java.base` 没有向无名模块 opens `java.lang`——`Constructor.trySetAccessible()` 返回
 * `false`，接着 `newInstance` 会抛 `IllegalAccessException`。从前这一条会以裸 Java 异常的
 * 形式逃出解释器（用户看到一句没有文件:行:列 的 `IllegalAccessException`），而字节码产物
 * 那边是能跑的：rsf 类在 JVM 上**真的**是 `ClassLoader` 的子类，`INVOKESPECIAL` 调
 * `protected` 构造器本来就合法。
 *
 * 变通办法就是把这层子类关系补出来：现场生成一个**子类**，它在自己的构造器里调父类那条
 * `protected` 构造器（子类做这件事是合法的 `invokespecial`），子类自己的构造器是 `public`、
 * 反射调得动。语义上父类构造器**真的跑了**——不是绕过它、也不是 `allocateInstance` 那种
 * 空壳，所以解释器与字节码两侧的状态一致。
 *
 * 生成的类必须落在**调用者自己的包**里（`Lookup.defineClass` 的硬性要求），所以它挂在
 * 解释器的包下，一个 (父类, 构造器描述符) 只生成一次并缓存。父类构造器是 `private` 的
 * （`Runtime` 那种）时子类同样调不到，那时按 rsf 错误报出来，消息里带文件:行:列。
 */
private object JdkParentBridge {
    private val seq = java.util.concurrent.atomic.AtomicInteger()
    private val cache = HashMap<String, Class<*>>()

    fun newInstance(
        parentInternal: String,
        descriptor: String,
        paramClasses: Array<Class<*>>,
        args: Array<Any?>,
        paramTypes: List<RsfType>,
    ): Any {
        val cls = synchronized(cache) {
            cache.getOrPut("$parentInternal#$descriptor") {
                define(parentInternal, descriptor, paramTypes)
            }
        }
        val ctor = try {
            cls.getDeclaredConstructor(*paramClasses)
        } catch (e: NoSuchMethodException) {
            throw RsfError(
                "interp",
                "内部错误：为 JDK 父类 '$parentInternal' 造的桥类里没有构造器 $descriptor：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        }
        return try {
            ctor.newInstance(*args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 父类构造器自己抛的异常原样往外扔（和直接调 Java 保持一致）
            throw e.cause ?: e
        } catch (e: ReflectiveOperationException) {
            throw RsfError(
                "interp",
                "内部错误：JDK 父类 '$parentInternal' 的构造器 $descriptor 调不动（模块系统不放行，" +
                    "子类桥也够不着——父类构造器可能是 private 的）：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        } catch (e: IllegalAccessError) {
            throw RsfError(
                "interp",
                "内部错误：JDK 父类 '$parentInternal' 的构造器 $descriptor 对子类桥也不可见：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        }
    }

    /** 造出 `extends <parentInternal>` 且只带一条构造器的类。 */
    private fun define(parentInternal: String, descriptor: String, paramTypes: List<RsfType>): Class<*> {
        val pkg = Interpreter::class.java.name.substringBeforeLast('.')
        val internal = pkg.replace('.', '/') + "/RsfJdkParentBridge$" + seq.incrementAndGet()
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internal,
            null,
            parentInternal,
            null,
        )
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        var slot = 1
        for (t in paramTypes) {
            val d = t.descriptor
            mv.visitVarInsn(loadOpcode(d), slot)
            slot += if (d.first() == 'J' || d.first() == 'D') 2 else 1
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parentInternal, "<init>", descriptor, false)
        mv.visitInsn(Opcodes.RETURN)
        // 方法里没有分支，不需要栈映射帧；最大值交给 ASM 算
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return try {
            MethodHandles.lookup().defineClass(cw.toByteArray())
        } catch (e: Throwable) {
            throw RsfError(
                "interp",
                "内部错误：为 JDK 父类 '$parentInternal' 造子类桥失败：" +
                    (e.message ?: e.javaClass.simpleName),
                -1, -1, null,
            )
        }
    }

    private fun loadOpcode(descriptor: String): Int = when (descriptor.first()) {
        'J' -> Opcodes.LLOAD
        'F' -> Opcodes.FLOAD
        'D' -> Opcodes.DLOAD
        'L', '[' -> Opcodes.ALOAD
        else -> Opcodes.ILOAD
    }
}
