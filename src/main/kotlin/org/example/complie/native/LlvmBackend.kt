package org.example.complie.native

import org.example.complie.ast.*
import org.example.complie.ir.*
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.Binding
import org.example.complie.sema.RsfBase
import org.example.complie.sema.SemanticModel

/**
 * LLVM 文本 IR 后端（阶段 84，native 线按 Zig 的路子：先交给 LLVM）。
 *
 * 产出 `.ll` 文本，交给 `clang` 汇编链接——多目标（aarch64 / amd64 / PE / Mach-O）
 * 与优化立刻就有，代价是编译期需要 clang。等以后自己的编码器/ELF 写出器成熟了，
 * 这条线可以逐步换成自研后端，接口不用改。
 *
 * 覆盖面与 [AsmBackend] 一样刻意划得很窄（Int/void 函数、整数运算、调用、
 * println(Int|字符串字面量)），不支持的构造**显式报错**。
 *
 * 为什么走文本 IR 而不是 JNI 包 libLLVM：rsf 跑在 JVM 上，文本 IR 一个字符串就能交出去，
 * 不用维护 FFI 绑定，也不用按平台分发 libLLVM；代价是多一次 `clang` 调用。
 */
class LlvmBackend(private val model: SemanticModel) {

    private val globals = StringBuilder()
    private val body = StringBuilder()
    private var tmp = 0
    /** 字符串常量：内容 → 全局符号名。 */
    private val strings = LinkedHashMap<String, String>()

    fun emit(): String {
        globals.setLength(0)
        body.setLength(0)
        strings.clear()
        tmp = 0
        checkSubset()

        body.append("; 由 rsf 的 LLVM 文本 IR 后端生成\n")
        body.append("declare i32 @printf(i8*, ...)\n")
        body.append("declare i32 @putchar(i32)\n\n")

        // C 入口：调 rsf_fn_main 后返回 0
        body.append("define i32 @main() {\n")
        body.append("entry:\n")
        if (model.entry != null) body.append("  call void @${fnName(model.entry!!)}()\n")
        body.append("  ret i32 0\n}\n\n")

        for (fn in model.functions) {
            if (fn.body == null) continue
            emitFunction(fn)
        }

        // 字符串常量放最后（LLVM IR 里顺序无关）
        for ((text, label) in strings) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            globals.append("$label = private unnamed_addr constant [${bytes.size + 1} x i8] c\"")
            for (b in bytes) globals.append("\\${"%02X".format(b)}")
            globals.append("\\00\"\n")
        }
        return body.toString() + "\n" + globals.toString()
    }

    private fun checkSubset() {
        for (fn in model.functions) {
            if (fn.body == null) continue
            val ret = model.returnTypeOf(fn)
            if (ret.base != RsfBase.INT && ret.base != RsfBase.VOID) {
                fail(fn.at, "--ll 现在只支持 Int / void 返回（'${fn.name}' 是 ${ret.rsfName}）")
            }
            for (t in model.paramTypes[fn].orEmpty()) {
                if (t.base != RsfBase.INT) fail(fn.at, "--ll 现在只支持 Int 参数（'${fn.name}' 里有 ${t.rsfName}）")
            }
        }
    }

    private fun fail(at: SourceLine, message: String): Nothing =
        throw org.example.complie.diag.RsfError("codegen", "$message（--ll 只覆盖一个很小的子集）", at.line, at.column, null)

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    private fun fresh(prefix: String = "t"): String = "%${prefix}${tmp++}"

    // ------------------------------------------------------------------ 函数

    private fun emitFunction(fn: FuncDecl) {
        val bodyIr = model.loweredOf(fn)
        val retType = if (model.returnTypeOf(fn).base == RsfBase.VOID) "void" else "i32"
        val params = model.paramBindings[fn].orEmpty()
        val paramsText = params.mapIndexed { i, b -> "i32 %p$i" }.joinToString(", ")
        out("define $retType @${fnName(fn)}($paramsText) {")
        out("entry:")

        // 每个绑定一个 alloca（v1 不做寄存器化，交给 LLVM 的 mem2reg）
        val slots = LinkedHashMap<Binding, String>()
        fun slotOf(b: Binding): String = slots.getOrPut(b) { "%slot${slots.size}" }
        for (b in params) out("  ${slotOf(b)} = alloca i32")
        for (instr in bodyIr.instructions) {
            if (instr is IVarDecl) {
                val b = model.declBindings[instr.stmt] ?: fail(instr.stmt.at, "内部：变量声明没有绑定")
                if (b !in slots) out("  ${slotOf(b)} = alloca i32")
            }
        }
        for ((i, b) in params.withIndex()) out("  store i32 %p$i, i32* ${slotOf(b)}")

        for (instr in bodyIr.instructions) {
            when (instr) {
                is IVarDecl -> {
                    val b = model.declBindings[instr.stmt] ?: fail(instr.stmt.at, "内部：没有绑定")
                    out("  store i32 ${value(instr.stmt.initializer, ::slotOf)}, i32* ${slotOf(b)}")
                }
                is IAssign -> {
                    val b = model.assignRefs[instr.stmt] ?: fail(instr.stmt.at, "内部：没有绑定")
                    out("  store i32 ${value(instr.stmt.value, ::slotOf)}, i32* ${slotOf(b)}")
                }
                is IExpr -> emitExprStmt(instr.stmt, ::slotOf)
                is IReturn -> {
                    val v = instr.stmt.value
                    if (v == null || retType == "void") out("  ret $retType${if (retType == "void") "" else " 0"}")
                    else out("  ret i32 ${value(v, ::slotOf)}")
                }
                else -> fail(instr.at, "--ll 还不支持这类语句")
            }
        }
        out("  ret $retType${if (retType == "void") "" else " 0"}")
        out("}")
        out("")
    }

    private fun out(line: String) {
        body.append(line).append('\n')
    }

    private fun emitExprStmt(stmt: ExprStmt, slotOf: (Binding) -> String) {
        val e = stmt.expr
        if (e is CallExpr && e.name == "println") {
            if (e.arguments.isEmpty()) {
                out("  ${fresh()} = call i32 @putchar(i32 10)")
                return
            }
            val arg = e.arguments[0]
            when (model.typeOf(arg).base) {
                RsfBase.INT -> {
                    val v = value(arg, slotOf)
                    val fmt = strPtr("%d\n")
                    out("  ${fresh()} = call i32 (i8*, ...) @printf(i8* $fmt, i32 $v)")
                }
                RsfBase.STRING -> {
                    val lit = arg as? StringLit ?: fail(arg.at, "println 的字符串暂时只支持字面量")
                    val data = strPtr(lit.value)
                    val fmt = strPtr("%s\n")
                    out("  ${fresh()} = call i32 (i8*, ...) @printf(i8* $fmt, i8* $data)")
                }
                else -> fail(arg.at, "println 只支持 Int 与 String")
            }
            return
        }
        if (e is CallExpr) {
            out("  ${fresh()} = call i32 @${fnName(targetOf(e))}(${args(e, slotOf)})")
            return
        }
        fail(stmt.at, "--ll 不支持这种表达式语句")
    }

    private fun targetOf(e: CallExpr): FuncDecl = model.callTargets[e] ?: fail(e.at, "--ll 只支持调用用户函数")

    private fun args(e: CallExpr, slotOf: (Binding) -> String): String =
        e.arguments.joinToString(", ") { "i32 ${value(it, slotOf)}" }

    /** 整数表达式 → SSA 值（常量或临时寄存器名）。 */
    private fun value(e: Expr, slotOf: (Binding) -> String): String = when (e) {
        is IntLit -> e.value.toString()
        is VarExpr -> {
            val b = model.varRefs[e] ?: fail(e.at, "内部：变量读取没有绑定")
            val t = fresh()
            out("  $t = load i32, i32* ${slotOf(b)}")
            t
        }
        is UnaryExpr -> {
            val v = value(e.operand, slotOf)
            val t = fresh()
            out("  $t = sub i32 0, $v")
            t
        }
        is BinaryExpr -> {
            val l = value(e.left, slotOf)
            val r = value(e.right, slotOf)
            val t = fresh()
            val op = when (e.op) {
                TokenKind.PLUS -> "add"
                TokenKind.MINUS -> "sub"
                TokenKind.STAR -> "mul"
                else -> fail(e.at, "--ll 还不支持运算符 '${e.op}'")
            }
            out("  $t = $op i32 $l, $r")
            t
        }
        is CallExpr -> {
            val t = fresh()
            out("  $t = call i32 @${fnName(targetOf(e))}(${args(e, slotOf)})")
            t
        }
        else -> fail(e.at, "--ll 还不支持这种整数表达式")
    }

    /** 字符串常量的指针（getelementptr 到首字节）。 */
    private fun strPtr(text: String): String {
        val label = strings.getOrPut(text) { "@.str.${strings.size}" }
        val n = text.toByteArray(Charsets.UTF_8).size + 1
        val t = fresh()
        out("  $t = getelementptr inbounds [$n x i8], [$n x i8]* $label, i64 0, i64 0")
        return t
    }
}
