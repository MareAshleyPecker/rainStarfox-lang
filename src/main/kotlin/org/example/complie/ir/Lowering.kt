package org.example.complie.ir

import org.example.complie.diag.RsfError
import org.example.complie.ast.AsExpr
import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.DerefAssignStmt
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.ElvisExpr
import org.example.complie.ast.Expr
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.FailStmt
import org.example.complie.ast.FloatLit
import org.example.complie.ast.ForStmt
import org.example.complie.ast.ForceExpr
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.GotoStmt
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IfStmt
import org.example.complie.ast.IndexAssignStmt
import org.example.complie.ast.IndexExpr
import org.example.complie.ast.IntLit
import org.example.complie.ast.IsExpr
import org.example.complie.ast.LabelStmt
import org.example.complie.ast.LambdaExpr
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.NullLit
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.Stmt
import org.example.complie.ast.StringLit
import org.example.complie.ast.SuperCallStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.SwitchStmt
import org.example.complie.ast.ThisCallStmt
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TryStmt
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt
import org.example.complie.ast.VarDeclStmt
import org.example.complie.sema.Binding
import org.example.complie.sema.Builtins
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import org.example.complie.sema.fnSignatureOf
import org.example.complie.sema.fnTypeText
import org.example.complie.sema.isFunctionType
import org.example.complie.lexing.SourceLine
import java.util.IdentityHashMap

// ------------------------------------------------------------------ 指令

/**
 * 降级后的扁平指令。控制流一律用「标签 + 跳转」表达，不再有嵌套结构，
 * 于是 CFG 分析、解释器、字节码后端都能对着同一个线性列表工作。
 *
 * 表达式仍然是 AST 节点（表达式内部不会有 goto），只有语句被摊平。
 */
sealed interface Instr {
    val at: SourceLine
}

/** `var x = ...` / `imutvar x = ...`：声明 + 初始化。 */
class IVarDecl(val stmt: VarDeclStmt) : Instr {
    override val at get() = stmt.at
}

/** `x = ...` */
class IAssign(val stmt: AssignStmt) : Instr {
    override val at get() = stmt.at
}

/**
 * `对象.字段 = ...`（阶段 12）。
 *
 * 单独一条指令而不是复用 [IAssign]：那个写的是**局部变量槽**（有 `binding.slot`），
 * 这个要先求值接收者、再解析字段、最后 `PUTFIELD`——两端都按这条路径实现。
 */
class IFieldAssign(val stmt: MemberAssignStmt) : Instr {
    override val at get() = stmt.at
}

/**
 * `数组[下标] = 值`（阶段 13）。
 *
 * 和 [IFieldAssign] 一样单独一条：三种赋值的求值顺序与目标解析都不一样，
 * 而且这一条还要在**目标求完之后、右值求完之后**插一次越界检查
 * （解释器与字节码两端都按这个顺序做，副作用顺序才能一致）。
 *
 * 注意数组的**读**（`a[i]`）与**创建**（`new int[5]`）不需要新指令：它们都出现在
 * 表达式位置，而 IR 里的表达式本来就是 AST 节点（表达式内部没有 goto）。
 */
class IIndexAssign(val stmt: IndexAssignStmt) : Instr {
    override val at get() = stmt.at
}

/**
 * `*p = 值`（阶段 53）。
 *
 * 单独一条指令：目标不是槽、不是字段、不是下标，而是「指针指向的那格存储」。
 * 解释器直接写回 [RsfPointer] 的槽；C 后端发 `*p = 值;`；JVM 字节码显式报错。
 */
class IDerefAssign(val stmt: DerefAssignStmt) : Instr {
    override val at get() = stmt.at
}

/**
 * `this(实参)` —— 构造器委托（阶段 18），只会出现在构造器体的第一句。
 *
 * 单独一条指令的理由和 [IIndexAssign] 一样：它的语义既不是「求值一个表达式」
 * 也不是「普通调用」——解释器要**递归进目标构造器**、字节码要发
 * `ALOAD 0; 实参; INVOKESPECIAL <init>`（而且所在的那条 `<init>` 因此不再发
 * `super()` 与字段初值）。目标构造器不放在这里，由语义模型的
 * `ctorDelegations[当前构造器]` 提供——那是 Checker 校验过的同一条边，
 * 两端读同一份，不可能走偏。
 */
class ICtorDelegate(val stmt: ThisCallStmt) : Instr {
    override val at get() = stmt.at
}

/**
 * `super(实参)` —— **父类构造委托**（阶段 25 K1）。
 *
 * 和 [ICtorDelegate] 是两条不同的边，不能合并：
 *
 * - `this(实参)` 是「同一个类的另一个构造器接着跑」，这条边**不new 两个对象**，
 *   链根那次构造负责跑字段初值；
 * - `super(实参)` 是「先把父类那份对象状态建起来」，父类构造器跑完之后，
 *   才轮到**本类**的字段初值与构造体（DESIGN-25 §3 的构造顺序）。
 *
 * K1（前端 + 语义）只负责把这条边**显式**降到 IR 里：降级本身不猜语义，
 * 目标父类由语义模型的 `superDelegations[当前构造器]` 提供（Checker 解析过、
 * 两端读同一份）。真正的字节码 / 解释器实现是 K2 / K3 的活——那两端现在
 * 遇到这条指令**显式报未实现**，绝不静默按 `this(...)` 或空操作处理。
 */
class ISuperDelegate(val stmt: SuperCallStmt) : Instr {
    override val at get() = stmt.at
}

/** 表达式语句 */
class IExpr(val stmt: ExprStmt) : Instr {
    override val at get() = stmt.at
}

/** 返回 */
class IReturn(val stmt: ReturnStmt) : Instr {
    override val at get() = stmt.at
}

/** 无条件跳转。 */
class IGoto(val label: String, override val at: SourceLine) : Instr

/**
 * `fail <消息>`（阶段 14）：无条件抛出错误。
 *
 * 和 [IReturn] 一样是**终结指令**——它一定抛出去，没有「顺序执行下一条」这条路，
 * 所以 `fail` 后面的代码会被判成不可达（这正是想要的：跟 `rtn` 后面一样）。
 */
class IFail(val stmt: FailStmt) : Instr {
    override val at get() = stmt.at
}

/** 条件为真时跳转，否则顺序执行下一条。 */
class IBranch(val cond: Expr, val target: String, override val at: SourceLine) : Instr

/**
 * 枚举分派（阶段 19c）：`switch (值) { case 枚举名.值名 : ... }`。
 *
 * 为什么**不复用** [IBranch] 串成一串「比较 + 跳转」：那需要合成一串
 * `值 == 枚举常量` 的表达式节点，而 IR 里的表达式是**已经检查过的 AST 节点**
 * ——合成的节点不在 `SemanticModel.exprTypes` 里，两端后端一取类型就会抛
 * 「表达式缺少类型标注」。与其在检查阶段造一批假节点，不如让这条指令把
 * 「按常量分派」这件事整体表达出来：一条 [ISwitch] 进来、N 个目标标签出去。
 *
 * [targets] 与 `stmt.cases` **一一对应**（下标 i 就是第 i 个 case 的目标标签），
 * 所以后端不需要再按值名查一边——查表顺序、标签顺序、执行顺序三者不可能走偏。
 *
 * [defaultTarget] 是什么都没命中时去的地方：写了 `else` 就是 else 体的标签，
 * 没写就是 switch 结束标签（Checker 已保证此时 case 是穷尽的，所以那条边
 * 实际上到不了——但保留它让「所有路径」的分析与字节码的栈平衡都简单）。
 */
class ISwitch(
    val stmt: SwitchStmt,
    /** 每个 case 的目标标签（与 `stmt.cases` 同序同长）。 */
    val targets: List<String>,
    /**
     * 什么都没命中时去的地方：写了 `else` 就是 else 体的标签。
     *
     * **null = 没有这条边**，两种情况完全不同：
     *
     * 1. **控制流图上**没有这条边。没写 `else` ⇒ Checker 已保证 case 覆盖了枚举的
     *    每一个值，于是「都不命中」在类型正确的程序里不可能发生。把它算成一条边会
     *    让「所有路径都 return」的分析误报——而
     *    `fun nameOf(k : TokenKind) -> string { switch (k) { case A : rtn "a" ... } }`
     *    正是最自然的枚举分派写法，不能判它「可能掉出函数末尾」。
     * 2. **运行期仍然要有明确的落点**：旁路确实存在——引用类型数组的元素默认是 null，
     *    `switch (arr[1])` 里的值就可能是 null，而枚举常量都不是 null，于是哪一支都
     *    不匹配。两端在这里**抛同一条 rsf 运行期错误**
     *    （[org.example.complie.sema.EnumRuntime.noMatchFailure]），
     *    而不是「悄悄什么都不做」——后者会让 `catch` 接不到、输出也看不出问题，
     *    是最难查的那种行为。
     */
    val defaultTarget: String?,
) : Instr {
    override val at get() = stmt.at
}

/** 跳转落点。 */
class ILabel(val label: String, override val at: SourceLine) : Instr

// ------------------------------------------------------------------ 降级结果

class LoweredFunction(
    val function: FuncDecl,
    val instructions: List<Instr>,
    /** 标签名 → 指令下标。用户标签与编译器内部标签都在这里。 */
    val labelIndex: Map<String, Int>,
    /** 这个函数一共用掉多少个局部变量槽（含参数）。 */
    val slotCount: Int,
    /** `try` / `catch` 的处理器表；没有 try 就是空表。 */
    val handlers: List<TryHandler> = emptyList(),
) {
    /**
     * 这条 pc 上抛出来的错误由哪个处理器接：取**最内层**的那个（区间最窄的）。
     * 返回 null 表示本帧接不住——异常继续往外层函数帧传。
     */
    fun handlerAt(pc: Int): TryHandler? =
        handlers.filter { pc >= it.startPc && pc < it.endPc }.maxByOrNull { it.startPc }
}

/**
 * 一条 try/catch 的异常处理器，和 JVM 异常表里的一条记录一一对应。
 *
 * 为什么是「pc 区间 → handler」而不是一条 `ITry(body, catchBody)` 指令：
 * 扁平指令流里 `rtn` / `break` / `goto` 可以从任意位置跳出 try 块，那种写法就得在
 * **每一条出口路径**上补一次「注销处理器」，漏掉一条解释器的处理器栈就会串味。
 * 区间表没有这个问题——抛错误时按 pc 查表，跟控制流怎么走完全无关（JVM 就是这么做的），
 * 字节码那侧直接对应 `visitTryCatchBlock`。
 */
class TryHandler(
    val startLabel: String,
    val endLabel: String,
    val handlerLabel: String,
    /** catch 变量占的槽位；捕获时把错误消息写进去。 */
    val catchSlot: Int,
    /** 解析后的指令下标：保护范围 [startPc, endPc)，异常跳到 handlerPc。 */
    val startPc: Int,
    val endPc: Int,
    val handlerPc: Int,
)

// ------------------------------------------------------------------ 降级

/**
 * 把函数体摊平成一维指令序列。
 *
 * 槽位从 0 开始分配：先是参数（按签名顺序，long/double 占两格），然后是函数体里
 * 每个变量声明一个**永不复用**的槽位。理论上兄弟块里的变量可以共享槽位，
 * 但 goto 能让控制流从任意位置跳进任意块，两个不同类型却共用槽位的变量会在
 * 汇合点撞车（栈帧合并成冲突类型，JVM 直接拒绝）。不复用是拿一点点局部变量
 * 预算换「永远安全」——对玩具语言是划算的。
 *
 * 槽位直接写进 [Binding.slot]（参数与局部变量在检查之后是同一种东西）。
 */
class Lowering(
    private val fn: FuncDecl,
    private val declBindings: Map<VarDeclStmt, Binding>,
    private val params: List<Binding>,
    /**
     * 0 号槽是不是被占着（实例方法的 `this`）。
     *
     * 实例方法、构造器都是「有个 this」的，槽位从 1 开始排；JVM 那边本来就把 this 放 0 号槽，
     * 解释器那边由 [org.example.complie.interp.Interpreter] 自己填那个槽，两边正好对上。
     */
    private val firstSlot: Int = 0,
    /**
     * `try { ... } catch (e : string) { ... }` 里那个 catch 变量的绑定。
     *
     * 它跟普通局部变量一样要占一格槽位，但它没有 `VarDeclStmt` 可以挂，所以单开一张表。
     */
    private val catchBindings: Map<TryStmt, Binding> = emptyMap(),
    /**
     * 要降级的**函数体**；null 就是用 [fn] 自己的体。
     *
     * 阶段 30b 的 lambda 需要它：lambda 的体是 `fn` 里的一条表达式，却要当成一个独立的
     * 帧来铺槽位。给 [fn] 保持**外层函数**不动（`classModel.ownerOf(fn)`、`isInstance(fn)`、
     * 报错文案都还认得出它是哪个类的方法），只把「铺哪段语句」换掉。
     */
    private val bodyOverride: Block? = null,
) {

    private val out = ArrayList<Instr>()
    private var slotCount = firstSlot
    private var counter = 0
    private val loops = ArrayDeque<LoopCtx>()

    /** 已经铺好标签、等着换成指令下标的 try/catch 处理器。 */
    private val handlerSpecs = ArrayList<HandlerSpec>()

    private class LoopCtx(val continueLabel: String, val breakLabel: String)
    private class HandlerSpec(val start: String, val end: String, val handler: String, val catchSlot: Int)

    fun lower(): LoweredFunction {
        // 参数先占槽——JVM 的调用约定就是这么排的（调用方压栈，被调方从 0 开始收）
        for (p in params) {
            p.slot = slotCount
            slotCount += p.type.slots
        }

        // 抽象方法（接口里只写签名的那种）没有体，降级结果就是一个空函数——
        // 调用方（Checker）本来就不该给它铺槽位，这里只是让签名对得上。
        (bodyOverride ?: fn.body)?.let { lowerBlock(it) }

        val labelIndex = LinkedHashMap<String, Int>()
        for ((i, ins) in out.withIndex()) {
            if (ins is ILabel) labelIndex[ins.label] = i
        }

        val handlers = handlerSpecs.map { spec ->
            TryHandler(
                startLabel = spec.start,
                endLabel = spec.end,
                handlerLabel = spec.handler,
                catchSlot = spec.catchSlot,
                startPc = labelIndex[spec.start] ?: error("内部错误：try 的起始标签丢了"),
                endPc = labelIndex[spec.end] ?: error("内部错误：try 的结束标签丢了"),
                handlerPc = labelIndex[spec.handler] ?: error("内部错误：catch 的标签丢了"),
            )
        }

        return LoweredFunction(
            function = fn,
            instructions = out.toList(),
            labelIndex = labelIndex,
            slotCount = slotCount,
            handlers = handlers,
        )
    }

    /** 内部标签用 '\u0000' 开头——用户标签只能是标识符，永远撞不上。 */
    private fun fresh(kind: String): String = "\u0000$kind.${counter++}"

    private fun lowerBlock(block: Block) {
        for (stmt in block.statements) lowerStmt(stmt)
    }

    private fun lowerStmt(stmt: Stmt) {
        when (stmt) {
            is Block -> lowerBlock(stmt)

            is VarDeclStmt -> {
                val binding = declBindings[stmt]
                    ?: error("内部错误：变量 '${stmt.name}' 没有绑定")
                binding.slot = slotCount
                slotCount += binding.type.slots
                out.add(IVarDecl(stmt))
            }

            is AssignStmt -> out.add(IAssign(stmt))
            is MemberAssignStmt -> out.add(IFieldAssign(stmt))
            is IndexAssignStmt -> out.add(IIndexAssign(stmt))
            is DerefAssignStmt -> out.add(IDerefAssign(stmt))
            is ThisCallStmt -> out.add(ICtorDelegate(stmt))
            // 阶段 25：`super(实参)` 也是一条委托边，但它是**父类**那条（见 [ISuperDelegate]）
            is SuperCallStmt -> out.add(ISuperDelegate(stmt))
            is ExprStmt -> out.add(IExpr(stmt))
            is ReturnStmt -> out.add(IReturn(stmt))
            is FailStmt -> out.add(IFail(stmt))
            is LabelStmt -> out.add(ILabel(stmt.name, stmt.at))
            is GotoStmt -> out.add(IGoto(stmt.label, stmt.at))

            /*
             * `try { 保护体 } catch (e : string) { 处理体 }`：
             *
             * ```
             * try.start:      <- 保护范围从这里开始
             *     保护体
             * try.end:        <- 保护范围到这里为止（不含）
             *     goto try.done
             * try.handler:    <- 异常入口（栈上是异常对象／解释器的「刚接住的错误」）
             *     处理体
             * try.done:
             * ```
             *
             * catch 变量的槽位在这里分配，捕获时由两端各自写进去：字节码在 handler 开头
             * 发 `getMessage()` + ASTORE，解释器在查表命中时直接写槽位。
             */
            is TryStmt -> {
                val binding = catchBindings[stmt]
                    ?: error("内部错误：catch 变量 '${stmt.catchName}' 没有绑定")
                binding.slot = slotCount
                slotCount += binding.type.slots

                val lStart = fresh("try.start")
                val lEnd = fresh("try.end")
                val lHandler = fresh("try.handler")
                val lDone = fresh("try.done")

                out.add(ILabel(lStart, stmt.at))
                lowerBlock(stmt.body)
                out.add(ILabel(lEnd, stmt.at))
                out.add(IGoto(lDone, stmt.at))
                out.add(ILabel(lHandler, stmt.at))
                lowerBlock(stmt.catchBody)
                out.add(ILabel(lDone, stmt.at))

                handlerSpecs.add(HandlerSpec(lStart, lEnd, lHandler, binding.slot))
            }

            is BreakStmt -> {
                val loop = loops.firstOrNull()
                    ?: error("内部错误：break 不在循环里（Checker 应当先拦住）")
                out.add(IGoto(loop.breakLabel, stmt.at))
            }

            is ContinueStmt -> {
                val loop = loops.firstOrNull()
                    ?: error("内部错误：continue 不在循环里（Checker 应当先拦住）")
                out.add(IGoto(loop.continueLabel, stmt.at))
            }

            is IfStmt -> {
                val lBody = fresh("if.body")
                val lEnd = fresh("if.end")
                if (stmt.elseBody == null) {
                    out.add(IBranch(stmt.condition, lBody, stmt.at))
                    out.add(IGoto(lEnd, stmt.at))
                    out.add(ILabel(lBody, stmt.at))
                    lowerBlock(stmt.body)
                    out.add(ILabel(lEnd, stmt.at))
                } else {
                    val lElse = fresh("if.else")
                    out.add(IBranch(stmt.condition, lBody, stmt.at))
                    out.add(IGoto(lElse, stmt.at))
                    out.add(ILabel(lBody, stmt.at))
                    lowerBlock(stmt.body)
                    out.add(IGoto(lEnd, stmt.at))
                    out.add(ILabel(lElse, stmt.at))
                    lowerBlock(stmt.elseBody)
                    out.add(ILabel(lEnd, stmt.at))
                }
            }

            /*
             * `switch`（阶段 19c）——一条 ISwitch 加 N+1 个标签：
             *
             * ```
             *     ISwitch(stmt, [case0, case1, ...], default)   <- 求值一次、按常量分派
             * case0:
             *     分支体 0
             *     goto end
             * case1:
             *     分支体 1
             *     goto end
             * default:                                          <- 有 else 才是 else 体的落点
             *     else 体（没有 else 时这个标签就是 end，直接落到结束）
             * end:
             * ```
             *
             * 每个分支**自己**以 `goto end` 收尾（哪怕这一支已经 return 了也照发）：
             * 这正是「没有 fallthrough」的落地方式——分支之间不共享出口，
             * 后一支永远不会顺着往下掉进前一支的代码里。多余的 goto 会被
             * 可达性分析标成不可达、字节码那边整条跳过，不留死代码。
             */
            is SwitchStmt -> {
                val lEnd = fresh("switch.end")
                val lDefault = if (stmt.elseBody == null) null else fresh("switch.else")
                val targets = stmt.cases.map { fresh("switch.case") }

                out.add(ISwitch(stmt, targets, lDefault))
                for ((i, c) in stmt.cases.withIndex()) {
                    out.add(ILabel(targets[i], c.at))
                    lowerBlock(c.body)
                    out.add(IGoto(lEnd, c.at))
                }
                if (lDefault != null) {
                    out.add(ILabel(lDefault, stmt.at))
                    lowerBlock(stmt.elseBody!!)
                }
                out.add(ILabel(lEnd, stmt.at))
            }

            is ForStmt -> {
                val lTop = fresh("for.top")
                val lBody = fresh("for.body")
                val lStep = fresh("for.step")
                val lEnd = fresh("for.end")

                out.add(ILabel(lTop, stmt.at))
                if (stmt.condition != null) {
                    out.add(IBranch(stmt.condition, lBody, stmt.at))
                    out.add(IGoto(lEnd, stmt.at))
                }
                // 条件为 null（for(;;)）时直接掉进循环体；lEnd 只有 break 会跳到
                out.add(ILabel(lBody, stmt.at))

                // 关键差别：continue 落到**更新语句**，而不是条件——
                // 这正是 for 和 while 的语义差异
                loops.addFirst(LoopCtx(continueLabel = lStep, breakLabel = lEnd))
                lowerBlock(stmt.body)
                loops.removeFirst()

                out.add(ILabel(lStep, stmt.at))
                stmt.step?.let { lowerStmt(it) }
                out.add(IGoto(lTop, stmt.at))
                out.add(ILabel(lEnd, stmt.at))
            }

            is WhileStmt -> {                val lTop = fresh("while.top")
                val lBody = fresh("while.body")
                val lEnd = fresh("while.end")

                // `while (true)` 且循环体里没有能逃出本层的 break —— 这就是死循环，
                // 出口边不存在。不做这个特判的话，后面「所有路径都 return」的分析会
                // 认为函数可能掉出末尾，对 `fun f() -> int { while (true) { rtn 1 } }`
                // 这种完全合法的写法误报。
                val canExit = stmt.condition !is BoolLit ||
                    (stmt.condition as BoolLit).value.not() ||
                    containsBreak(stmt.body)

                out.add(ILabel(lTop, stmt.at))
                out.add(IBranch(stmt.condition, lBody, stmt.at))
                out.add(IGoto(if (canExit) lEnd else lTop, stmt.at))
                out.add(ILabel(lBody, stmt.at))
                loops.addFirst(LoopCtx(lTop, lEnd))
                lowerBlock(stmt.body)
                loops.removeFirst()
                out.add(IGoto(lTop, stmt.at))
                if (canExit) out.add(ILabel(lEnd, stmt.at))
            }
        }
    }

    /** 这个块里有没有会跳出**本层**循环的 break（内层循环的 break 不算）。 */
    private fun containsBreak(block: Block): Boolean {
        for (stmt in block.statements) {
            when (stmt) {
                is BreakStmt -> return true
                is Block -> if (containsBreak(stmt)) return true
                is IfStmt -> {
                    if (containsBreak(stmt.body)) return true
                    if (stmt.elseBody?.let { containsBreak(it) } == true) return true
                }
                is WhileStmt -> { /* 内层循环的 break 只跳出内层 */ }
                is ForStmt -> { /* 同上 */ }
                // switch 的**分支体**在本层：它里面写的 break 同样是跳出本层循环
                // （switch 自己不消费 break——没有 fallthrough 就不需要「跳出 switch」）。
                // 漏掉这一条的话，`while (true) { switch (k) { case A : break ... } }`
                // 会被当成死循环，后面的可达性分析与「所有路径 return」跟着一起错。
                is SwitchStmt -> {
                    if (stmt.cases.any { containsBreak(it.body) }) return true
                    if (stmt.elseBody?.let { containsBreak(it) } == true) return true
                }
                // try 的保护体和处理体都在本层：里面写的 break 就是跳出本层循环。
                // 漏掉这一条的话，`while (true) { try { break } catch (e : string) { } }`
                // 会被当成死循环，后面的可达性分析跟着一起错。
                is TryStmt -> {
                    if (containsBreak(stmt.body)) return true
                    if (containsBreak(stmt.catchBody)) return true
                }
                else -> {}
            }
        }
        return false
    }
}

// ------------------------------------------------------------------ 控制流图

/**
 * 指令级的 CFG。节点是指令下标，外加一个虚拟的 [exit] 节点表示「函数末尾掉出去」。
 *
 * 只有无条件/条件跳转，没有嵌套结构，所以 CFG 直接由后继关系定义，
 * 连基本块都不用显式划分——分析规模本来就很小。
 */
class ControlFlow(
    private val instrs: List<Instr>,
    private val labelIndex: Map<String, Int>,
    /**
     * try/catch 的处理器表。
     *
     * 异常边必须进 CFG：不进来的话 handler 会被判成「不可达」——既会误报不可达代码警告，
     * 也会让字节码后端把整个处理体跳过（那才是真的错）。顺带地，确定性赋值在这些边上
     * 交汇（handler 的入集 = 保护范围里每条指令入集的交集），'catch 里读到的外层变量
     * 一定在 try 之前就赋过值' 这条判断也因此自动成立。
     */
    private val handlers: List<TryHandler> = emptyList(),
) {

    /** 虚拟出口：走到这里意味着函数体执行完了却没 return。 */
    val exit: Int = instrs.size

    val size: Int get() = instrs.size

    fun instructionAt(i: Int): Instr = instrs[i]

    fun successors(i: Int): IntArray {
        val base = normalSuccessors(i)
        var extra: IntArray? = null
        for (h in handlers) {
            if (i >= h.startPc && i < h.endPc) {
                extra = if (extra == null) intArrayOf(h.handlerPc) else extra + h.handlerPc
            }
        }
        return if (extra == null) base else base + extra
    }

    /** 顺序/跳转意义上的后继；异常边由 [successors] 补上。 */
    private fun normalSuccessors(i: Int): IntArray {
        val next = i + 1
        return when (val ins = instrs[i]) {
            is IReturn -> IntArray(0)
            // fail 一定抛出去：后面没有「顺序执行下一条」这条路（异常边另算）
            is IFail -> IntArray(0)
            is IGoto -> intArrayOf(labelIndex[ins.label] ?: exit)
            is IBranch -> intArrayOf(next, labelIndex[ins.target] ?: exit)
            // switch：**每一条分支都是一条真实的边**（任何一支都可能被执行）。
            // default 那条边只在写了 `else` 时才加——见 [ISwitch.defaultTarget]：
            // 穷尽且没写 else 时把它算进来，会让「所有路径都 return」的分析对最自然的
            // 枚举分派写法误报。去重是因为 default 通常就是 case 后面那条指令（next）。
            is ISwitch -> {
                val targets = LinkedHashSet<Int>()
                targets.add(next)
                for (t in ins.targets) targets.add(labelIndex[t] ?: exit)
                ins.defaultTarget?.let { targets.add(labelIndex[it] ?: exit) }
                targets.toIntArray()
            }
            else -> intArrayOf(next)
        }
    }

    /** 从入口能到达哪些节点（下标 0..size，含 [exit]）。 */
    fun reachable(): BooleanArray {
        val seen = BooleanArray(size + 1)
        val stack = ArrayDeque<Int>()
        seen[0] = true
        stack.addLast(0)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i == exit) continue
            for (s in successors(i)) {
                if (!seen[s]) {
                    seen[s] = true
                    stack.addLast(s)
                }
            }
        }
        return seen
    }

    /**
     * 确定性赋值（definite assignment）：对每条指令给出「执行到它之前，一定已经
     * 被赋值过」的 let 集合。
     *
     * 这是经典的**必须分析**：交汇点取交集，从全集出发向下迭代到不动点。
     * 它同时替我们实现了 C++ 那条「不能跳过变量初始化跳进作用域」的规矩——
     * 向后跳到 `let` 之前再读那个变量，这里就会算出「没被赋值」，编译期报错，
     * 而不是等 JVM 校验器在类加载时甩一个 VerifyError。
     */
    fun definitelyAssigned(reach: BooleanArray): List<Set<VarDeclStmt>> {
        // 空函数体（阶段 18 的主构造就是这种：字段初值就是它的体，自己一条指令都没有）
        // 没有可分析的指令，直接给空结果——不特判的话 `inSet[0] = ...` 会越界。
        if (size == 0) return emptyList()

        val universe: Set<VarDeclStmt> = instrs.filterIsInstance<IVarDecl>().map { it.stmt }.toSet()

        val preds = Array(size) { ArrayList<Int>() }
        for (i in 0 until size) {
            if (!reach[i]) continue
            for (s in successors(i)) {
                if (s < size) preds[s].add(i)
            }
        }

        val inSet = Array(size) { HashSet(universe) }
        val outSet = Array(size) { HashSet(universe) }
        inSet[0] = HashSet()

        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until size) {
                if (!reach[i]) continue

                val newIn: HashSet<VarDeclStmt> = if (i == 0) {
                    HashSet()
                } else {
                    val ps = preds[i]
                    if (ps.isEmpty()) {
                        HashSet()
                    } else {
                        val acc = HashSet(outSet[ps[0]])
                        for (k in 1 until ps.size) acc.retainAll(outSet[ps[k]])
                        acc
                    }
                }

                val newOut = HashSet(newIn)
                val ins = instrs[i]
                if (ins is IVarDecl) newOut.add(ins.stmt)

                if (newIn != inSet[i] || newOut != outSet[i]) {
                    inSet[i] = newIn
                    outSet[i] = newOut
                    changed = true
                }
            }
        }

        return inSet.map { it as Set<VarDeclStmt> }
    }
}

// ------------------------------------------------------------------ 闭包（阶段 30b）

/**
 * lambda 的一个**捕获项**（值捕获，DESIGN-30 §4）。
 *
 * 捕获清单的顺序是硬性的：工单 §1 规定「按体里首次引用的顺序」，`this` 也算一项、
 * 而且排在最前。30c 的自举后端要逐字节复刻，所以顺序在两端只能是这一份算法。
 */
sealed interface Capture {
    val type: RsfType

    /** 捕获值在本帧里的槽位（从 0 开始按宽度累加）。 */
    val slot: Int

    /** 捕获的是外层的 `this`：JVM 上类型是 `L<包围类>;`，创建点传 `ALOAD 0`。 */
    data class This(override val type: RsfType, override val slot: Int) : Capture

    /** 捕获的是外层的一个局部变量/形参。 */
    data class Local(val binding: Binding, override val slot: Int) : Capture {
        override val type: RsfType get() = binding.type
    }
}

/**
 * 一个 lambda 的降级结果：捕获清单 + 自己的形参 + 体 + 帧槽位表。
 *
 * 帧布局（两端必须一模一样，30c 要对拍）：
 * ```
 * 槽 0 ..                捕获项（`this` 在前，然后按首次引用顺序，long/double 占两格）
 * 槽 captureSlotCount .. 形参（按签名顺序）
 * 槽 ...                 体里的局部变量（[Lowering] 分配，永不复用）
 * ```
 * 之所以让捕获项占**帧里最前面的槽**：JVM 那边被调方法的前几个局部变量槽就是实参
 * （捕获值），解释器这边照抄同一个号，两边就不用各自再翻译一次。
 */
class LambdaClosure(
    /** 包围这个方法的名字（`lambda$<包围方法名>$<k>` 里的那一段）。 */
    val encloser: FuncDecl,
    val expr: LambdaExpr,
    /** 在包围方法里的出现序号（从 0 开始）。 */
    val index: Int,
    val captures: List<Capture>,
    /** 自己的形参绑定；槽位已由 [body] 的 Lowering 分配好。 */
    val params: List<Binding>,
    val returnType: RsfType,
    val body: LoweredFunction,
    private val slots: Map<Binding, Int>,
) {
    /** 形参个数——也就是 SAM 接口的 arity。 */
    val arity: Int get() = params.size

    /** 捕获项一共占多少槽（long/double 各占两格）。 */
    val captureSlotCount: Int = captures.sumOf { it.type.slots }

    /** 合成方法的确定性名字（工单 §1）。 */
    val implName: String get() = "lambda\$${encloser.name}\$$index"

    /**
     * 本帧里某个绑定占哪一格。
     *
     * 为什么不能只看 `binding.slot`：捕获项的绑定属于**外层**帧，它的 `slot` 是外层
     * 的槽位号，在本帧里必须换成捕获槽。同一份表里还有一格「Checker 造的那份形参
     * 绑定 → 我们的形参槽」——Checker 在 `checkLambda` 里另造了一套 `Binding`，只活在
     * 它自己的作用域里，我们拿不到，只能在遇到读的时候按名字对上来。
     */
    fun slotOf(binding: Binding): Int = slots[binding] ?: binding.slot
}

/**
 * 整个函数（含嵌套 lambda）的闭包清单。
 *
 * 两端后端都从这里读，绝不各自再走一遍 AST——捕获顺序、槽位号、lambda 编号一旦不同，
 * 字节码与解释器就会给出不同的结果，而 30c 还要拿它对拍到字节。
 */
class ClosurePlan(
    val closures: List<LambdaClosure>,
    private val byExpr: IdentityHashMap<LambdaExpr, LambdaClosure>,
    private val fnCalls: IdentityHashMap<CallExpr, Binding>,
) {
    fun lambdaOf(expr: LambdaExpr): LambdaClosure? = byExpr[expr]

    /** 按名字调用的函数值（`f(1)`）解析到的绑定；不是函数值调用就是 null。 */
    fun fnValueCallee(call: CallExpr): Binding? = fnCalls[call]

    companion object {
        val EMPTY = ClosurePlan(emptyList(), IdentityHashMap(), IdentityHashMap())
    }
}

/**
 * 闭包分析的缓存：同一个 `FuncDecl` 只算一次。
 *
 * 两个后端都会问同一批函数，但真正要紧的是**两端读到同一份结果**（尤其是 lambda 编号
 * 和槽位号）。键用 `FuncDecl` 的**身份**：数据类的 `equals` 会把整个体走一遍，太贵。
 */
object ClosureScopes {
    private val cache = IdentityHashMap<FuncDecl, ClosurePlan>()

    fun of(model: SemanticModel, fn: FuncDecl): ClosurePlan =
        cache.getOrPut(fn) { ClosureAnalyser(model, fn).analyze() }
}

/**
 * 走一遍函数体，找出每个 lambda 的捕获清单，以及每个「函数值按名字调用」的绑定。
 *
 * 为什么要自己走：Checker 把捕获探测的结果留在它自己的 `captureProbe` 里就扔了，
 * `SemanticModel` 里没有「lambda → 捕获」这张表（阶段 30a 没存，30b 也不许改语义）。
 * 两边后端又都需要它，所以放在 IR 这一层算一次——这里已经是 AST 的下游，正好是
 * 「语义已经查完、还没开始生成」的位置。
 *
 * 归属规则（配合下面 [Level] 的说明）：
 * - 一个读命中的绑定如果**本帧自己声明**，那它不算捕获；
 * - 形参的 `Binding` 是 Checker 在 `checkLambda` 里新造的（`paramIndex >= 0`），
 *   按「下标 + 名字」认回它属于哪一层；
 * - catch 变量同理（Checker 也是在 catch 体里新造一个 `Binding`），按名字认回最近的
 *   `catch (名字 : string)`；
 * - 其余的一律是外层帧的值，从**读到它的那一层**一路标到函数帧：每一层都得捕获，
 *   因为每一层都要把值再传给下一层。
 *
 * 只认 `VarExpr` 读和 `this`：赋值的左边不是读（`x = ...` 改的是外层那个变量，
 * 而 `var` 的捕获本来就被 Checker 拒了）。
 */
private class ClosureAnalyser(
    private val model: SemanticModel,
    private val fn: FuncDecl,
) {
    /** 按**出现顺序**收集的 lambda（就是编号 `k` 的顺序）。 */
    private val allLevels = ArrayList<Level>()
    private val levels = ArrayList<Level>()
    private val scopeMaps = ArrayDeque<MutableMap<String, Binding>>()
    private val catchScopes = ArrayList<CatchScope>()
    private val catchBindings = IdentityHashMap<TryStmt, Binding>()
    private val fnCalls = IdentityHashMap<CallExpr, Binding>()

    private class CatchScope(val name: String, val fresh: Binding, val owner: Level?)

    /** 一层 lambda 的分析中间结果。 */
    private inner class Level(val expr: LambdaExpr, val params: List<Binding>, val ret: RsfType) {
        /** 体里用了 `this`（含嵌套 lambda 里用的）。 */
        var hasThis = false

        /** 捕获的**外层局部**，首次引用顺序（`this` 不在里面，它排在捕获清单最前）。 */
        val captured = ArrayList<Binding>()

        /** 本帧自己的绑定（形参/局部/catch），命中这些就不算捕获。 */
        val declared = HashSet<Binding>()

        /** Checker 造的 catch 绑定 → 我们这份帧内的 catch 绑定。 */
        val aliases = IdentityHashMap<Binding, Binding>()

        /** 形参在 Checker 眼里的绑定（读到时才对得上）。 */
        val checkerParams = arrayOfNulls<Binding>(params.size)
    }

    fun analyze(): ClosurePlan {
        scopeMaps.addLast(HashMap())
        for (p in model.paramsOf(fn)) scopeMaps.last()[p.name] = p
        fn.body?.let { walkBlock(it, pushScope = true) }
        scopeMaps.removeLast()

        val plan = ArrayList<LambdaClosure>(allLevels.size)
        val byExpr = IdentityHashMap<LambdaExpr, LambdaClosure>()
        for ((k, level) in allLevels.withIndex()) {
            val captures = ArrayList<Capture>()
            var slot = 0
            if (level.hasThis) {
                val ownerName = model.classModel.ownerOf(fn)?.name
                    ?: model.classModel.interfaceOwnerOf(fn)?.name
                    ?: throw IllegalStateException(
                        "内部错误：'${fn.name}' 里用了 this，但它不属于任何类或接口",
                    )
                captures += Capture.This(RsfType(RsfBase.EXTERNAL, false, ownerName), slot)
                slot += 1
            }
            for (b in level.captured) {
                captures += Capture.Local(b, slot)
                slot += b.type.slots
            }

            // lambda 的体铺成独立一帧：捕获项占最前面的槽，形参紧随其后。
            val body = Lowering(
                fn = fn,
                declBindings = model.declBindings,
                params = level.params,
                firstSlot = slot,
                catchBindings = catchBindings,
                bodyOverride = level.expr.body,
            ).lower()

            val slots = IdentityHashMap<Binding, Int>()
            for (c in captures) {
                if (c is Capture.Local) slots[c.binding] = c.slot
            }
            for ((i, fresh) in level.params.withIndex()) {
                slots[fresh] = fresh.slot
                level.checkerParams[i]?.let { slots[it] = fresh.slot }
            }
            for ((checker, fresh) in level.aliases) slots[checker] = fresh.slot

            val closure = LambdaClosure(
                encloser = fn,
                expr = level.expr,
                index = k,
                captures = captures,
                params = level.params,
                returnType = level.ret,
                body = body,
                slots = slots,
            )
            plan += closure
            byExpr[level.expr] = closure
        }
        return ClosurePlan(plan, byExpr, fnCalls)
    }

    // -------------------------------------------------------------- 遍历

    private fun walkBlock(block: Block, pushScope: Boolean) {
        if (pushScope) scopeMaps.addLast(HashMap())
        for (stmt in block.statements) walkStmt(stmt)
        if (pushScope) scopeMaps.removeLast()
    }

    private fun walkStmt(stmt: Stmt) {
        when (stmt) {
            is Block -> walkBlock(stmt, pushScope = true)

            is VarDeclStmt -> {
                val binding = model.bindingOf(stmt)
                levels.lastOrNull()?.declared?.add(binding)
                scopeMaps.last()[stmt.name] = binding
                walkExpr(stmt.initializer)
            }

            is AssignStmt -> {
                // 裸字段名赋值（`x = 1`）在两端都靠 `this`——它也是一次 this 使用
                if (model.classModel.bareFieldWrites[stmt] != null) markThis()
                walkExpr(stmt.value)
            }

            is DerefAssignStmt -> walkExpr(stmt.target.let { (it as UnaryExpr).operand }).also { walkExpr(stmt.value) }
            is MemberAssignStmt -> {
                walkExpr(stmt.target)
                walkExpr(stmt.value)
            }
            is IndexAssignStmt -> {
                walkExpr(stmt.target)
                walkExpr(stmt.value)
            }
            is ThisCallStmt -> stmt.arguments.forEach(::walkExpr)
            is SuperCallStmt -> stmt.arguments.forEach(::walkExpr)
            is ExprStmt -> walkExpr(stmt.expr)
            is ReturnStmt -> stmt.value?.let(::walkExpr)
            is FailStmt -> walkExpr(stmt.message)
            is LabelStmt -> {}
            is GotoStmt -> {}
            is BreakStmt -> {}
            is ContinueStmt -> {}

            is IfStmt -> {
                walkExpr(stmt.condition)
                walkBlock(stmt.body, pushScope = true)
                stmt.elseBody?.let { walkBlock(it, pushScope = true) }
            }

            is WhileStmt -> {
                walkExpr(stmt.condition)
                walkBlock(stmt.body, pushScope = true)
            }

            is ForStmt -> {
                stmt.condition?.let(::walkExpr)
                walkBlock(stmt.body, pushScope = true)
                stmt.step?.let { walkStmt(it) }
            }

            is SwitchStmt -> {
                walkExpr(stmt.subject)
                for (case in stmt.cases) walkBlock(case.body, pushScope = true)
                stmt.elseBody?.let { walkBlock(it, pushScope = true) }
            }

            is TryStmt -> {
                walkBlock(stmt.body, pushScope = true)
                val fresh = Binding(
                    name = stmt.catchName,
                    type = RsfType(RsfBase.STRING),
                    mutable = false,
                    paramIndex = -1,
                    at = stmt.at,
                    catchVar = true,
                )
                catchBindings[stmt] = fresh
                catchScopes += CatchScope(stmt.catchName, fresh, levels.lastOrNull())
                scopeMaps.addLast(HashMap())
                scopeMaps.last()[stmt.catchName] = fresh
                walkBlock(stmt.catchBody, pushScope = false)
                scopeMaps.removeLast()
                catchScopes.removeLast()
            }
        }
    }

    private fun walkExpr(expr: Expr) {
        when (expr) {
            is IntLit -> {}
            is LongLit -> {}
            is FloatLit -> {}
            is DoubleLit -> {}
            is StringLit -> {}
            is CharLit -> {}
            is BoolLit -> {}
            is NullLit -> {}

            is VarExpr -> {
                // 裸字段名（`rtn x`）不是局部变量读：它编译成 `this.x`，也算一次 this 使用
                val field = model.classModel.fieldReads[expr]
                if (field != null) {
                    markThis()
                } else {
                    // 既不是字段也不是局部变量的 VarExpr 是**类名/枚举名的前缀**
                    // （`Math.abs`、`TokenKind.Add`、`System.out`），它没有绑定，
                    // 和闭包捕获无关。`bindingOf` 对这种情况会抛，所以直接查表。
                    model.varRefs[expr]?.let { use(it) }
                }
            }

            is ThisExpr -> markThis()
            is SuperExpr -> markThis()

            is CallExpr -> {
                expr.callee?.let(::walkExpr)
                // 隐式 this 调用（`helper()`、继承来的 JDK 方法）的接收者也是本帧的 this
                val implicitDecl = model.classModel.implicitCalls[expr]?.decl
                if (implicitDecl != null && !implicitDecl.isStatic) markThis()
                val inherited = model.classModel.implicitJvmCallOf(expr)?.method
                if (inherited != null && !inherited.isStatic) markThis()
                if (expr.callee == null) recordFnValueCall(expr)
                expr.arguments.forEach(::walkExpr)
            }

            is UnaryExpr -> walkExpr(expr.operand)
            is BinaryExpr -> {
                walkExpr(expr.left)
                walkExpr(expr.right)
            }
            is IfExpr -> {
                walkExpr(expr.condition)
                walkExpr(expr.whenTrue)
                walkExpr(expr.whenFalse)
            }
            is ElvisExpr -> {
                walkExpr(expr.left)
                walkExpr(expr.right)
            }
            is ForceExpr -> walkExpr(expr.operand)
            is IsExpr -> walkExpr(expr.operand)
            is AsExpr -> walkExpr(expr.operand)
            is NewExpr -> expr.arguments.forEach(::walkExpr)
            is NewArrayExpr -> expr.dimensions.forEach(::walkExpr)
            is IndexExpr -> {
                walkExpr(expr.receiver)
                walkExpr(expr.index)
            }
            is MemberExpr -> {
                walkExpr(expr.receiver)
                expr.arguments?.forEach(::walkExpr)
            }

            is LambdaExpr -> walkLambda(expr)
        }
    }

    private fun walkLambda(expr: LambdaExpr) {
        val type = model.typeOf(expr)
        val text = type.fnTypeText ?: throw IllegalStateException(
            "内部错误：lambda 的类型不是函数类型（${type.rsfName}）",
        )
        val (paramTypes, ret) = fnSignatureOf(text) ?: throw IllegalStateException(
            "内部错误：函数类型解析失败（$text）",
        )
        val params = expr.params.mapIndexed { i, p ->
            Binding(p.name, paramTypes[i], mutable = false, paramIndex = i, at = p.at)
        }
        val level = Level(expr, params, ret)
        allLevels += level
        levels += level
        scopeMaps.addLast(HashMap())
        for ((i, b) in params.withIndex()) scopeMaps.last()[expr.params[i].name] = b
        walkBlock(expr.body, pushScope = false)
        scopeMaps.removeLast()
        levels.removeLast()
    }

    // -------------------------------------------------------------- 归属

    private fun markThis() {
        for (level in levels) level.hasThis = true
    }

    /**
     * 一次读：判断它是本帧的绑定还是外层的捕获。
     */
    private fun use(binding: Binding) {
        val top = levels.lastOrNull() ?: return // 函数顶层（不在任何 lambda 里）不需要捕获
        if (top.declared.contains(binding)) return

        // catch 变量：Checker 在 catch 体里造的那份 Binding，按名字认回我们的
        if (binding.catchVar) {
            val scope = catchScopes.lastOrNull { it.name == binding.name }
            if (scope == null) {
                top.declared.add(binding) // 兜底：认不出来就当作本帧的，别误捕
                return
            }
            val owner = scope.owner
            if (owner != null) {
                owner.aliases[binding] = scope.fresh
                owner.declared.add(binding)
            }
            captureUpTo(binding, owner)
            return
        }

        // 形参：按「下标 + 名字」认出属于哪一层（Checker 那份 Binding 只活在它作用域里）
        if (binding.paramIndex >= 0) {
            val owner = levels.lastOrNull {
                binding.paramIndex < it.params.size &&
                    it.expr.params[binding.paramIndex].name == binding.name
            }
            if (owner == null) {
                captureUpTo(binding, null)
                return
            }
            owner.declared.add(binding)
            if (owner.checkerParams[binding.paramIndex] == null) {
                owner.checkerParams[binding.paramIndex] = binding
            }
            if (owner !== top) captureUpTo(binding, owner)
            return
        }

        captureUpTo(binding, null)
    }

    /**
     * 从最内层一路标到 [owner]（**不含** owner 那一层；null 表示函数帧）：
     * 中间每一层都得把值攥在手里，才传得到最里面。
     */
    private fun captureUpTo(binding: Binding, owner: Level?) {
        val stop = if (owner == null) -1 else levels.indexOf(owner)
        var i = levels.lastIndex
        while (i > stop) {
            val level = levels[i]
            if (!level.declared.contains(binding) && !level.captured.contains(binding)) {
                level.captured += binding
            }
            i--
        }
    }

    /** 按名字调用的函数值：`f(1)` 里的 `f`（不是函数值就什么也不做）。 */
    private fun recordFnValueCall(call: CallExpr) {
        if (call.name == "print" || call.name == "println") return
        if (Builtins[call.name] != null) return
        if (model.isConstruction(call)) return
        if (model.callTargets[call] != null) return
        val binding = lookup(call.name) ?: return
        if (!binding.type.isFunctionType) return
        fnCalls[call] = binding
        use(binding)
    }

    private fun lookup(name: String): Binding? {
        for (i in scopeMaps.indices.reversed()) {
            scopeMaps[i][name]?.let { return it }
        }
        return null
    }
}
