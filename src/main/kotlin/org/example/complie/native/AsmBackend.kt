package org.example.complie.native

import org.example.complie.ast.*
import org.example.complie.ir.*
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.Binding
import org.example.complie.sema.RsfBase
import org.example.complie.sema.SemanticModel

/**
 * aarch64 直出汇编后端（阶段 84，自举线的第一块砖）。
 *
 * 和 C 后端的关系：C 后端是「把程序写成 C，再让系统编译器干活」；这条路不经过 C，
 * 直接产出 `.s`，由 `as` / `gcc` 汇编链接。它现在的覆盖面很小——**只为了证明
 * 「rsf → 机器码」这条路是通的**，不是替代 C 后端。
 *
 * 当前支持（刻意划得很窄）：
 * - 顶层函数，参数与返回类型只有 `Int` / `void`；
 * - 语句：`var`/`val` 声明（初值是 Int 表达式）、赋值、`println(Int)`、
 *   `println("字面量")`、`rtn`；
 * - 表达式：整数字面量、变量、`+ - *`、一元负号、调用用户函数（Int 实参）。
 *
 * 不支持的构造**显式报错**（而不是生成错的代码）——这一点比覆盖面重要：
 * 自举是拿它编译自己的，静默错编会浪费几天调试。
 *
 * 约定：函数符号加 `rsf_fn_` 前缀（和 C 后端一致）；参数按 AAPCS64 走 w0..w7，
 * 内部用 w 寄存器（rsf 的 Int 是 32 位）。局部变量都在栈帧里，不分配寄存器——
 * 先正确，再快。
 */
class AsmBackend(private val model: SemanticModel) {

    private val out = StringBuilder()
    /** 字符串字面量池：内容 → 标签名。 */
    private val strings = LinkedHashMap<String, String>()

    fun emit(): String {
        out.setLength(0)
        strings.clear()
        checkSubset()
        out.append(".arch armv8-a\n")
        out.append(".text\n\n")
        // 入口：C ABI 的 main，调 rsf_fn_main 后退出 0
        out.append(".globl main\n")
        out.append("main:\n")
        out.append("    stp x29, x30, [sp, #-16]!\n")
        out.append("    mov x29, sp\n")
        if (model.entry != null) out.append("    bl ${fnName(model.entry!!)}\n")
        out.append("    mov w0, #0\n")
        out.append("    ldp x29, x30, [sp], #16\n")
        out.append("    ret\n\n")
        for (fn in model.functions) {
            if (fn.body == null) continue
            emitFunction(fn)
        }
        emitHelpers()
        emitStrings()
        return out.toString()
    }

    // ------------------------------------------------------------------ 子集检查

    private fun checkSubset() {
        for (fn in model.functions) {
            if (fn.body == null) continue
            val ret = model.returnTypeOf(fn)
            if (ret.base != RsfBase.INT && ret.base != RsfBase.VOID) fail(fn.at, "--asm 现在只支持 Int / void 返回（'${fn.name}' 是 ${ret.rsfName}）")
            for (t in model.paramTypes[fn].orEmpty()) {
                if (t.base != RsfBase.INT) fail(fn.at, "--asm 现在只支持 Int 参数（'${fn.name}' 里有 ${t.rsfName}）")
            }
        }
    }

    private fun fail(at: SourceLine, message: String): Nothing =
        throw org.example.complie.diag.RsfError("codegen", "$message（--asm 只覆盖了一个很小的子集，见 AsmBackend 的说明）", at.line, at.column, null)

    // ------------------------------------------------------------------ 函数

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    private fun emitFunction(fn: FuncDecl) {
        val body = model.loweredOf(fn)
        // 帧：每个绑定 8 字节；参数额外占位。先收集要用的绑定，再定帧大小。
        val slots = LinkedHashMap<Binding, Int>()
        fun slotOf(b: Binding): Int = slots.getOrPut(b) { slots.size }
        model.paramBindings[fn]?.forEach { slotOf(it) }

        val ctx = FnCtx(fn, body, ::slotOf)
        out.append(".globl ${fnName(fn)}\n")
        out.append("${fnName(fn)}:\n")
        out.append("    stp x29, x30, [sp, #-16]!\n")
        out.append("    mov x29, sp\n")
        // 参数先进临时寄存器，再按帧大小落栈
        val params = model.paramBindings[fn].orEmpty()
        val frame = ((slots.size + 1) * 8 + 15) / 16 * 16
        if (frame > 0) out.append("    sub sp, sp, #$frame\n")
        for ((i, b) in params.withIndex()) {
            if (i >= 8) fail(fn.at, "--asm 最多支持 8 个参数")
            out.append("    str w$i, [sp, #${slotOf(b) * 8}]\n")
        }
        emitInstrs(ctx, body.instructions)
        // 兜底返回（void 函数走到底）
        out.append("    mov w0, #0\n")
        out.append("    b ${ctx.epilogue}\n")
        out.append("${ctx.epilogue}:\n")
        if (frame > 0) out.append("    add sp, sp, #$frame\n")
        out.append("    ldp x29, x30, [sp], #16\n")
        out.append("    ret\n\n")
    }

    private class FnCtx(
        val fn: FuncDecl,
        val body: LoweredFunction,
        val slotOf: (Binding) -> Int,
    ) {
        val epilogue = ".Lret_${fn.name}"
    }

    private fun emitInstrs(ctx: FnCtx, instrs: List<Instr>) {
        for (instr in instrs) {
            when (instr) {
                is IVarDecl -> {
                    val b = model.declBindings[instr.stmt]
                        ?: fail(instr.stmt.at, "内部：变量声明没有绑定")
                    emitInt(instr.stmt.initializer, ctx)
                    store(ctx, b)
                }
                is IAssign -> {
                    val b = model.assignRefs[instr.stmt]
                        ?: fail(instr.stmt.at, "内部：赋值没有绑定")
                    emitInt(instr.stmt.value, ctx)
                    store(ctx, b)
                }
                is IExpr -> emitExprStmt(instr.stmt, ctx)
                is IReturn -> {
                    val value = instr.stmt.value
                    if (value != null) emitInt(value, ctx) else out.append("    mov w0, #0\n")
                    out.append("    b ${ctx.epilogue}\n")
                }
                else -> fail(instr.at, "--asm 还不支持这类语句")
            }
        }
    }

    private fun store(ctx: FnCtx, b: Binding) {
        out.append("    str w0, [sp, #${ctx.slotOf(b) * 8}]\n")
    }

    private fun emitExprStmt(stmt: ExprStmt, ctx: FnCtx) {
        val e = stmt.expr
        if (e is CallExpr && e.name == "println") {
            if (e.arguments.isEmpty()) {
                out.append("    bl rsf_print_nl\n")
                return
            }
            val arg = e.arguments[0]
            when (model.typeOf(arg).base) {
                RsfBase.INT -> {
                    emitInt(arg, ctx)
                    out.append("    bl rsf_print_int\n")
                }
                RsfBase.STRING -> {
                    val lit = arg as? StringLit ?: fail(arg.at, "println 的字符串暂时只支持字面量")
                    out.append("    adrp x0, ${strLabel(lit.value)}\n")
                    out.append("    add x0, x0, :lo12:${strLabel(lit.value)}\n")
                    out.append("    bl rsf_print_str\n")
                }
                else -> fail(arg.at, "println 只支持 Int 与 String")
            }
            out.append("    bl rsf_print_nl\n")
            return
        }
        if (e is CallExpr) {
            emitCall(e, ctx)
            return
        }
        fail(stmt.at, "--asm 不支持这种表达式语句")
    }

    /** 调用户函数：实参按 AAPCS 走 w0..w7，多于一层的实参先压栈暂存。 */
    private fun emitCall(e: CallExpr, ctx: FnCtx): String {
        val target = model.callTargets[e] ?: fail(e.at, "--asm 只支持调用用户函数")
        if (e.arguments.size > 8) fail(e.at, "--asm 最多支持 8 个实参")
        val values = ArrayList<String>()
        for (arg in e.arguments) {
            emitInt(arg, ctx)
            // 先压到栈顶暂存，全部算完再搬进参数寄存器（避免下一个实参覆盖 w0..）
            out.append("    str w0, [sp, #-16]!\n")
        }
        for (i in e.arguments.indices.reversed()) {
            out.append("    ldr w$i, [sp], #16\n")
        }
        out.append("    bl ${fnName(target)}\n")
        return "w0"
    }

    /** 整数表达式 → w0。 */
    private fun emitInt(e: Expr, ctx: FnCtx) {
        when (e) {
            is IntLit -> {
                val v = e.value.toLong()
                if (v in -4096..4095) {
                    out.append("    mov w0, #$v\n")
                } else {
                    out.append("    mov w0, #${v and 0xFFFF}\n")
                    out.append("    movk w0, #${(v shr 16) and 0xFFFF}, lsl #16\n")
                }
            }
            is VarExpr -> {
                val b = model.varRefs[e] ?: fail(e.at, "内部：变量读取没有绑定")
                out.append("    ldr w0, [sp, #${ctx.slotOf(b) * 8}]\n")
            }
            is UnaryExpr -> {
                emitInt(e.operand, ctx)
                out.append("    neg w0, w0\n")
            }
            is BinaryExpr -> {
                // 先算右边、压栈，再算左边，最后弹回来做运算（求值顺序：左先右后）
                emitInt(e.left, ctx)
                out.append("    str w0, [sp, #-16]!\n")
                emitInt(e.right, ctx)
                out.append("    mov w1, w0\n")
                out.append("    ldr w0, [sp], #16\n")
                when (e.op) {
                    TokenKind.PLUS -> out.append("    add w0, w0, w1\n")
                    TokenKind.MINUS -> out.append("    sub w0, w0, w1\n")
                    TokenKind.STAR -> out.append("    mul w0, w0, w1\n")
                    else -> fail(e.at, "--asm 还不支持运算符 '${e.op}'")
                }
            }
            is CallExpr -> emitCall(e, ctx)
            else -> fail(e.at, "--asm 还不支持这种整数表达式")
        }
    }

    // ------------------------------------------------------------------ 运行期小工具

    /** 直接走 `write(2)` 的系统调用，不依赖 libc——自举之后要能完全脱离 C 运行时。 */
    private fun emitHelpers() {
        out.append("rsf_print_int:\n")
        out.append("    stp x29, x30, [sp, #-16]!\n")
        out.append("    mov x29, sp\n")
        out.append("    sub sp, sp, #32\n")
        out.append("    mov w2, w0\n")              // 保存数值
        out.append("    add x1, sp, #31\n")          // 从缓冲区尾部往前写
        out.append("    mov w3, #0\n")               // 是否已经写过字符
        out.append("    mov w4, #10\n")
        out.append("    cmp w2, #0\n")
        out.append("    b.ge .Lpi_abs\n")
        out.append("    neg w2, w2\n")
        out.append("    mov w5, #1\n")               // 负数标记
        out.append("    b .Lpi_digits\n")
        out.append(".Lpi_abs:\n")
        out.append("    mov w5, #0\n")
        out.append(".Lpi_digits:\n")
        out.append("    mov w6, w2\n")
        out.append(".Lpi_loop:\n")
        out.append("    udiv w7, w6, w4\n")
        out.append("    msub w8, w7, w4, w6\n")      // w8 = 余数
        out.append("    add w8, w8, #48\n")
        out.append("    strb w8, [x1, #-1]!\n")
        out.append("    mov w3, #1\n")
        out.append("    mov w6, w7\n")
        out.append("    cbnz w6, .Lpi_loop\n")
        out.append("    cbz w5, .Lpi_write\n")
        out.append("    mov w8, #45\n")               // '-'
        out.append("    strb w8, [x1, #-1]!\n")
        out.append(".Lpi_write:\n")
        out.append("    add x1, x1, #0\n")
        out.append("    sub x2, x29, #0\n")
        out.append("    mov x2, sp\n")
        out.append("    add x2, x2, #32\n")
        out.append("    sub x2, x2, x1\n")
        out.append("    mov x0, #1\n")
        out.append("    mov x8, #64\n")               // __NR_write
        out.append("    svc #0\n")
        out.append("    add sp, sp, #32\n")
        out.append("    ldp x29, x30, [sp], #16\n")
        out.append("    ret\n\n")

        out.append("rsf_print_nl:\n")
        out.append("    stp x29, x30, [sp, #-16]!\n")
        out.append("    mov x29, sp\n")
        out.append("    sub sp, sp, #16\n")
        out.append("    mov w8, #10\n")
        out.append("    strb w8, [sp]\n")
        out.append("    mov x0, #1\n")
        out.append("    mov x1, sp\n")
        out.append("    mov x2, #1\n")
        out.append("    mov x8, #64\n")
        out.append("    svc #0\n")
        out.append("    add sp, sp, #16\n")
        out.append("    ldp x29, x30, [sp], #16\n")
        out.append("    ret\n\n")

        out.append("rsf_print_str:\n")
        out.append("    stp x29, x30, [sp, #-16]!\n")
        out.append("    mov x29, sp\n")
        out.append("    mov x1, x0\n")
        out.append("    mov x2, #0\n")
        out.append(".Lps_len:\n")
        out.append("    add x3, x1, x2\n")
        out.append("    ldrb w4, [x3]\n")
        out.append("    cbz w4, .Lps_write\n")
        out.append("    add x2, x2, #1\n")
        out.append("    b .Lps_len\n")
        out.append(".Lps_write:\n")
        out.append("    mov x0, #1\n")
        out.append("    mov x8, #64\n")
        out.append("    svc #0\n")
        out.append("    ldp x29, x30, [sp], #16\n")
        out.append("    ret\n\n")
    }

    private fun strLabel(text: String): String =
        strings.getOrPut(text) { ".Lstr_${strings.size}" }

    private fun emitStrings() {
        out.append(".section .rodata\n")
        for ((text, label) in strings) {
            out.append("$label:\n")
            out.append("    .asciz \"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\"\n")
        }
    }
}
