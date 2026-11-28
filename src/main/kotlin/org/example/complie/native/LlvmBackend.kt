package org.example.complie.native

import org.example.complie.ast.*
import org.example.complie.ir.*
import org.example.complie.lexing.SourceLine
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.Binding
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
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
    /** 当前在发哪条指令（分支需要知道“不跳”该落到哪个块）。 */
    private var currentIdx = 0
    /** 当前基本块有没有结束（LLVM 要求每个块以 br/ret 收尾）。 */
    private var blockOpen = true

    /** 内部标签带不可见前缀（lowering 用的标记），LLVM 里要换成合法字符。 */
    private fun blockLabel(name: String): String =
        "bb_" + name.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }.joinToString("")
    private fun nextBlock(instr: Instr): String = "bb_after_$currentIdx"

    /** 条件表达式 → i1。 */
    private fun cond(e: Expr, slotOf: (Binding) -> String): String {
        val t = fresh()
        if (e is BinaryExpr && model.typeOf(e.left).base == RsfBase.STRING) {
            // 字符串比较：== / != 走 strcmp（rsf 的字符串是值语义）
            if (e.op != TokenKind.EQ && e.op != TokenKind.NE) {
                fail(e.at, "--ll 的字符串比较只支持 == / !=")
            }
            val l = strValue(e.left, slotOf)
            val r = strValue(e.right, slotOf)
            val c = fresh()
            out("  $c = call i32 @strcmp(i8* $l, i8* $r)")
            out("  $t = icmp ${if (e.op == TokenKind.EQ) "eq" else "ne"} i32 $c, 0")
            return t
        }
        if (e is BinaryExpr) {
            val l = value(e.left, slotOf)
            val r = value(e.right, slotOf)
            val op = when (e.op) {
                TokenKind.LT -> "slt"
                TokenKind.GT -> "sgt"
                TokenKind.LE -> "sle"
                TokenKind.GE -> "sge"
                TokenKind.EQ -> "eq"
                TokenKind.NE -> "ne"
                else -> fail(e.at, "--ll 还不支持这种条件（'${e.op}'）")
            }
            out("  $t = icmp $op i32 $l, $r")
            return t
        }
        if (e is CallExpr) {
            val v = value(e, slotOf)
            out("  $t = icmp ne i32 $v, 0")
            return t
        }
        fail(e.at, "--ll 的条件暂时只支持比较与函数调用")
    }

    fun emit(): String {
        globals.setLength(0)
        body.setLength(0)
        strings.clear()
        tmp = 0
        checkSubset()

        body.append("; 由 rsf 的 LLVM 文本 IR 后端生成\n")
        body.append("declare i32 @printf(i8*, ...)\n")
        body.append("declare i32 @putchar(i32)\n")
        body.append("declare i8* @malloc(i64)\n")
        body.append("declare i8* @memcpy(i8*, i8*, i64)\n")
        body.append("declare i64 @strlen(i8*)\n")
        body.append("declare i32 @strcmp(i8*, i8*)\n")
        body.append("declare i32 @snprintf(i8*, i64, i8*, ...)\n\n")
        emitRuntime()

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
            if (ret.base != RsfBase.INT && ret.base != RsfBase.VOID && ret.base != RsfBase.STRING) {
                fail(fn.at, "--ll 现在只支持 Int / String / void（'${fn.name}' 返回 ${ret.rsfName}）")
            }
            for (t in model.paramTypes[fn].orEmpty()) {
                if (t.base != RsfBase.INT && t.base != RsfBase.STRING) {
                    fail(fn.at, "--ll 现在只支持 Int / String 参数（'${fn.name}' 里有 ${t.rsfName}）")
                }
            }
        }
    }

    private fun fail(at: SourceLine, message: String): Nothing =
        throw org.example.complie.diag.RsfError("codegen", "$message（--ll 只覆盖一个很小的子集）", at.line, at.column, null)

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    /** rsf 类型 → LLVM 值类型。字符串用 `i8*`（NUL 结尾，和 C 一致）。 */
    private fun llType(t: RsfType): String = when (t.base) {
        RsfBase.VOID -> "void"
        RsfBase.STRING -> "i8*"
        RsfBase.INT -> "i32"
        else -> fail(SourceLine(1, 1), "--ll 还不支持类型 ${t.rsfName}")
    }

    private fun slotType(b: Binding): String = llType(b.type)

    private fun fresh(prefix: String = "t"): String = "%${prefix}${tmp++}"

    // ------------------------------------------------------------------ 函数

    private fun emitFunction(fn: FuncDecl) {
        val bodyIr = model.loweredOf(fn)
        val retType = llType(model.returnTypeOf(fn))
        val params = model.paramBindings[fn].orEmpty()
        val paramsText = params.mapIndexed { i, b -> "${llType(b.type)} %p$i" }.joinToString(", ")
        out("define $retType @${fnName(fn)}($paramsText) {")
        out("entry:")

        // 每个绑定一个 alloca（v1 不做寄存器化，交给 LLVM 的 mem2reg）
        val slots = LinkedHashMap<Binding, String>()
        fun slotOf(b: Binding): String = slots.getOrPut(b) { "%slot${slots.size}" }
        for (b in params) out("  ${slotOf(b)} = alloca ${slotType(b)}")
        for (instr in bodyIr.instructions) {
            if (instr is IVarDecl) {
                val b = model.declBindings[instr.stmt] ?: fail(instr.stmt.at, "内部：变量声明没有绑定")
                if (b !in slots) out("  ${slotOf(b)} = alloca ${slotType(b)}")
            }
        }
        for ((i, b) in params.withIndex()) out("  store ${llType(b.type)} %p$i, ${slotType(b)}* ${slotOf(b)}")

        var previousWasBranch = false
        for ((idx, instr) in bodyIr.instructions.withIndex()) {
            currentIdx = idx
            val needsLabel = instr is ILabel || previousWasBranch
            if (previousWasBranch) blockOpen = false
            if (needsLabel && instr !is ILabel) {
                if (blockOpen) out("  br label %bb_after_${idx - 1}")
                out("bb_after_${idx - 1}:")
                blockOpen = true
            }
            previousWasBranch = instr is IGoto || instr is IBranch || instr is IReturn
            when (instr) {
                is IVarDecl -> {
                    val b = model.declBindings[instr.stmt] ?: fail(instr.stmt.at, "内部：没有绑定")
                    out("  store ${slotType(b)} ${anyValue(instr.stmt.initializer, ::slotOf)}, ${slotType(b)}* ${slotOf(b)}")
                }
                is IAssign -> {
                    val b = model.assignRefs[instr.stmt] ?: fail(instr.stmt.at, "内部：没有绑定")
                    out("  store ${slotType(b)} ${anyValue(instr.stmt.value, ::slotOf)}, ${slotType(b)}* ${slotOf(b)}")
                }
                is IExpr -> emitExprStmt(instr.stmt, ::slotOf)
                is IReturn -> {
                    val v = instr.stmt.value
                    out(
                        when {
                            v == null || retType == "void" -> "  ret void"
                            retType == "i8*" -> "  ret i8* ${strValue(v, ::slotOf)}"
                            else -> "  ret i32 ${value(v, ::slotOf)}"
                        },
                    )
                    blockOpen = false
                }
                is ILabel -> {
                    if (blockOpen) out("  br label %${blockLabel(instr.label)}")
                    out("${blockLabel(instr.label)}:")
                    blockOpen = true
                }
                is IGoto -> {
                    out("  br label %${blockLabel(instr.label)}")
                    blockOpen = false
                }
                is IBranch -> {
                    val c = cond(instr.cond, ::slotOf)
                    out("  br i1 $c, label %${blockLabel(instr.target)}, label %${nextBlock(instr)}")
                    blockOpen = false
                }
                else -> fail(instr.at, "--ll 还不支持这类语句")
            }
        }
        if (blockOpen) {
            out(
                when (retType) {
                    "void" -> "  ret void"
                    "i8*" -> "  ret i8* null"
                    else -> "  ret i32 0"
                },
            )
        }
        out("}")
        blockOpen = true
        out("")
    }

    private fun out(line: String) {
        body.append(line).append('\n')
    }

    /**
     * 字符串运行期：`intToString` 与 `+` 拼接。
     *
     * 用 libc 的 `snprintf` / `strlen` / `malloc` / `memcpy`——和 C 后端产物的语义一致
     * （NUL 结尾、堆分配、不判空）。以后自研运行时接上来时，只要替换这几个符号，
     * 上层代码生成不用动。
     */
    private fun emitRuntime() {
        out("define i8* @rsf_int_to_string(i32 %v) {")
        out("entry:")
        out("  %buf = call i8* @malloc(i64 16)")
        out("  %fmt = getelementptr inbounds [3 x i8], [3 x i8]* @.str.int, i64 0, i64 0")
        out("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 16, i8* %fmt, i32 %v)")
        out("  ret i8* %buf")
        out("}")
        out("")
        out("define i8* @rsf_str_concat(i8* %a, i8* %b) {")
        out("entry:")
        out("  %la = call i64 @strlen(i8* %a)")
        out("  %lb = call i64 @strlen(i8* %b)")
        out("  %len = add i64 %la, %lb")
        out("  %sz = add i64 %len, 1")
        out("  %out = call i8* @malloc(i64 %sz)")
        out("  %r1 = call i8* @memcpy(i8* %out, i8* %a, i64 %la)")
        out("  %pa = getelementptr inbounds i8, i8* %out, i64 %la")
        out("  %r2 = call i8* @memcpy(i8* %pa, i8* %b, i64 %lb)")
        out("  %end = getelementptr inbounds i8, i8* %out, i64 %len")
        out("  store i8 0, i8* %end")
        out("  ret i8* %out")
        out("}")
        out("")
        globals.append("@.str.int = private unnamed_addr constant [3 x i8] c\"%d\\00\"\n")
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
                    val data = strValue(arg, slotOf)
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

    private fun args(e: CallExpr, slotOf: (Binding) -> String): String = callArgs(e, slotOf)

    /** 按静态类型取值：字符串走 [strValue]，其余走 [value]。 */
    private fun anyValue(e: Expr, slotOf: (Binding) -> String): String {
        val t = model.typeOf(e)
        return if (t.base == RsfBase.STRING) strValue(e, slotOf) else value(e, slotOf)
    }

    /**
     * 字符串表达式 → `i8*`。
     *
     * 用 NUL 结尾的 C 字符串：`println` 直接交 `printf("%s")`，拼接交运行期助手
     * （`strlen` + `malloc` + `memcpy`）。这套表示和 C 后端一致，两个后端的
     * 字符串语义因此不会分家。
     */
    private fun strValue(e: Expr, slotOf: (Binding) -> String): String = when (e) {
        is StringLit -> strPtr(e.value)
        is VarExpr -> {
            val b = model.varRefs[e] ?: fail(e.at, "内部：变量读取没有绑定")
            val t = fresh()
            out("  $t = load i8*, i8** ${slotOf(b)}")
            t
        }
        is BinaryExpr -> {
            if (e.op != TokenKind.PLUS) fail(e.at, "--ll 的字符串只支持 '+' 拼接")
            val l = strValue(e.left, slotOf)
            val r = strValue(e.right, slotOf)
            val t = fresh()
            out("  $t = call i8* @rsf_str_concat(i8* $l, i8* $r)")
            t
        }
        is CallExpr -> {
            if (e.name == "intToString") {
                val t = fresh()
                out("  $t = call i8* @rsf_int_to_string(i32 ${value(e.arguments[0], slotOf)})")
                return t
            }
            val target = model.callTargets[e] ?: fail(e.at, "--ll 只支持调用用户函数")
            val t = fresh()
            out("  $t = call i8* @${fnName(target)}(${callArgs(e, slotOf)})")
            t
        }
        else -> fail(e.at, "--ll 还不支持这种字符串表达式")
    }

    /** 调用的实参：逐个按静态类型取，拼成 `i32 3, i8* %t` 这种形式。 */
    private fun callArgs(e: CallExpr, slotOf: (Binding) -> String): String =
        e.arguments.joinToString(", ") { arg ->
            val t = model.typeOf(arg)
            "${llType(t)} ${anyValue(arg, slotOf)}"
        }

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
            val target = targetOf(e)
            if (model.returnTypeOf(target).base == RsfBase.STRING) {
                fail(e.at, "内部：字符串返回的函数应该走 strValue")
            }
            val t = fresh()
            out("  $t = call i32 @${fnName(target)}(${args(e, slotOf)})")
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
