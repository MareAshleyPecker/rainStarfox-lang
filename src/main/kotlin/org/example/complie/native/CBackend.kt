package org.example.complie.native

import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.Expr
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.ForStmt
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IfStmt
import org.example.complie.ast.IntLit
import org.example.complie.ast.LongLit
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.Stmt
import org.example.complie.ast.StringLit
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt
import org.example.complie.diag.RsfError
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel

/**
 * 阶段 52 的 **C 后端**（native 线的第一刀）。
 *
 * 口径：把已通过语义检查的程序**整份摊成一个 C99 文件**，由系统 C 编译器
 * （本机是 `D:\mingw64\bin\gcc`，也兼容 `zig cc`）编成原生可执行文件。
 * 为什么先出 C 而不是直接发机器码：C 是最短的一条「能跑」路径——寄存器分配、
 * 调用约定、PE 链接全交给编译器，我们只对轴『语义 → C』负责；指针那套
 * （DESIGN-23 的 L1/L2/L3）以后可以在这条线上按真地址落地。
 *
 * v1 只收一个子集，遇到不收的构造**显式报错**（不静默糊过去）：
 * int / long / bool / string、顶层函数（不重载）、if / while / for、递归、
 * `print` / `println`、字符串 `+` 与 `==` / `!=`。类、枚举、接口、错误返回、
 * 浮点、数组、内置函数都还没接。
 */
class CBackend(private val model: SemanticModel) {

    fun emit(): String {
        val sb = StringBuilder()
        sb.append(RUNTIME)
        checkProgram()
        for (fn in model.functions.sortedBy { it.name }) sb.append(prototype(fn)).append(";\n")
        sb.append('\n')
        for (fn in model.functions.sortedBy { it.name }) {
            sb.append(prototype(fn)).append(" {\n")
            body(fn)?.let { sb.append(it) }
            sb.append("}\n\n")
        }
        val entry = model.entry ?: throw RsfError("native", "没有 main 函数，C 后端不知道从哪开始跑")
        sb.append("int main(void) {\n")
        if (returnType(entry).base == RsfBase.VOID) {
            sb.append("    ").append(fnName(entry)).append("();\n")
            sb.append("    return 0;\n")
        } else {
            sb.append("    return (int)").append(fnName(entry)).append("();\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 前置检查

    private fun checkProgram() {
        if (model.classModel.classes.isNotEmpty()) fail("类")
        if (model.enums.isNotEmpty()) fail("枚举")
        if (model.interfaces.isNotEmpty()) fail("接口")
        if (model.annotations.isNotEmpty()) fail("注解")
        for (fn in model.functions) {
            if (model.functionTable[fn.name]?.size ?: 0 > 1) fail("重载函数（${fn.name}）")
            if (fn.isConstructor) fail("构造器")
        }
    }

    private fun fail(what: String): Nothing =
        throw RsfError("native", "C 后端暂不支持：$what（阶段 52 只接了 int/long/bool/string 与顶层函数）")

    // ------------------------------------------------------------------ 类型

    private fun returnType(fn: FuncDecl): RsfType = model.returnTypes[fn] ?: RsfType.VOID

    private fun cType(type: RsfType, at: Expr? = null): String {
        if (type.arrayDepth > 0) fail("数组类型")
        return when (type.base) {
            RsfBase.VOID -> "void"
            RsfBase.INT -> "int32_t"
            RsfBase.LONG -> "int64_t"
            RsfBase.BOOL -> "bool"
            RsfBase.STRING -> "rsf_str"
            RsfBase.CHAR -> "uint16_t"
            RsfBase.BYTE -> "int8_t"
            else -> fail("类型 '${type.base.rsfName}'")
        }
    }

    // ------------------------------------------------------------------ 函数

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    private fun paramName(name: String): String = "v_" + name

    private fun prototype(fn: FuncDecl): String {
        val params = model.paramTypes[fn] ?: emptyList()
        val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        return "${cType(returnType(fn))} ${fnName(fn)}(${list.joinToString(", ").ifEmpty { "void" }})"
    }

    private fun body(fn: FuncDecl): String? {
        val block = fn.body ?: return null
        val sb = StringBuilder()
        for (stmt in block.statements) sb.append(stmt(stmt, 1))
        return sb.toString()
    }

    // ------------------------------------------------------------------ 语句

    private fun stmt(s: Stmt, indent: Int): String = when (s) {
        is VarDeclStmt -> {
            val type = model.typeOf(s)
            pad(indent) + "${cType(type)} ${paramName(s.name)} = ${expr(s.initializer)};\n"
        }
        is AssignStmt -> pad(indent) + "${paramName(s.name)} = ${expr(s.value)};\n"
        is ExprStmt -> pad(indent) + expr(s.expr) + ";\n"
        is ReturnStmt -> {
            val value = s.value
            if (value == null) pad(indent) + "return;\n" else pad(indent) + "return ${expr(value)};\n"
        }
        is IfStmt -> {
            val sb = StringBuilder()
            sb.append(pad(indent)).append("if (").append(expr(s.condition)).append(") {\n")
            for (inner in s.body.statements) sb.append(stmt(inner, indent + 1))
            sb.append(pad(indent)).append("}")
            val elseBody = s.elseBody
            if (elseBody != null) {
                sb.append(" else {\n")
                for (inner in elseBody.statements) sb.append(stmt(inner, indent + 1))
                sb.append(pad(indent)).append("}")
            }
            sb.append('\n')
            sb.toString()
        }
        is WhileStmt -> {
            val sb = StringBuilder()
            sb.append(pad(indent)).append("while (").append(expr(s.condition)).append(") {\n")
            for (inner in s.body.statements) sb.append(stmt(inner, indent + 1))
            sb.append(pad(indent)).append("}\n")
            sb.toString()
        }
        is ForStmt -> forStmt(s, indent)
        is BreakStmt -> pad(indent) + "break;\n"
        is ContinueStmt -> pad(indent) + "continue;\n"
        is Block -> {
            val sb = StringBuilder()
            sb.append(pad(indent)).append("{\n")
            for (inner in s.statements) sb.append(stmt(inner, indent + 1))
            sb.append(pad(indent)).append("}\n")
            sb.toString()
        }
        else -> fail("语句 ${s::class.simpleName}")
    }

    /**
     * `for` 在 AST 里已经是解糖后的「块 + ForStmt」，所以这里直接映射成 C 的 for。
     * 更新语句必须是赋值（rsf 的 for 只可能是 `i = i + 1` 这类），C 的第三个槽
     * 正好收表达式——这样 `continue` 自动落到步进上，与 rsf 语义一致。
     */
    private fun forStmt(s: ForStmt, indent: Int): String {
        val step = when (val st = s.step) {
            null -> ""
            is AssignStmt -> "${paramName(st.name)} = ${expr(st.value)}"
            else -> fail("for 的更新语句（${st::class.simpleName}）")
        }
        val cond = s.condition?.let { expr(it) } ?: ""
        val sb = StringBuilder()
        sb.append(pad(indent)).append("for (").append("; ").append(cond).append("; ").append(step).append(") {\n")
        for (inner in s.body.statements) sb.append(stmt(inner, indent + 1))
        sb.append(pad(indent)).append("}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 表达式

    private fun expr(e: Expr): String = when (e) {
        is IntLit -> e.value.toString()
        is LongLit -> "${e.value}LL"
        is StringLit -> stringLit(e.value)
        is BoolLit -> if (e.value) "true" else "false"
        is VarExpr -> paramName(e.name)
        is UnaryExpr -> unary(e)
        is BinaryExpr -> binary(e)
        is IfExpr -> "(${expr(e.condition)} ? ${expr(e.whenTrue)} : ${expr(e.whenFalse)})"
        is CallExpr -> call(e)
        else -> fail("表达式 ${e::class.simpleName}")
    }

    private fun unary(e: UnaryExpr): String {
        val operand = expr(e.operand)
        return when (e.op) {
            TokenKind.MINUS -> "(-$operand)"
            TokenKind.PLUS -> "($operand)"
            TokenKind.BANG -> "(!$operand)"
            else -> fail("一元运算 ${e.op}")
        }
    }

    private fun binary(e: BinaryExpr): String {
        val type = model.typeOf(e)
        val stringy = model.typeOf(e.left).base == RsfBase.STRING ||
            model.typeOf(e.right).base == RsfBase.STRING
        val left = expr(e.left)
        val right = expr(e.right)
        return when (e.op) {
            TokenKind.PLUS -> if (stringy) "rsf_concat($left, $right)" else "($left + $right)"
            TokenKind.MINUS -> "($left - $right)"
            TokenKind.STAR -> "($left * $right)"
            TokenKind.SLASH -> "($left / $right)"
            TokenKind.PERCENT -> "($left % $right)"
            TokenKind.LT -> "($left < $right)"
            TokenKind.GT -> "($left > $right)"
            TokenKind.LE -> "($left <= $right)"
            TokenKind.GE -> "($left >= $right)"
            TokenKind.EQ -> if (stringy) "rsf_str_eq($left, $right)" else "($left == $right)"
            TokenKind.NE -> if (stringy) "(!rsf_str_eq($left, $right))" else "($left != $right)"
            TokenKind.ANDAND -> "($left && $right)"
            TokenKind.OROR -> "($left || $right)"
            else -> fail("二元运算 ${e.op}（结果类型 ${type.base.rsfName}）")
        }
    }

    private fun call(e: CallExpr): String {
        if (e.name == "print" || e.name == "println") return printCall(e)
        // 内置函数只接 v1 用得到的那几个；其余（parseInt 等）显式报未支持
        when (e.name) {
            "intToString" -> return "rsf_int_to_str(${expr(e.arguments[0])})"
            "charToInt" -> return "((int32_t)${expr(e.arguments[0])})"
            "toChar" -> return "((uint16_t)${expr(e.arguments[0])})"
            "toByte" -> return "((int8_t)${expr(e.arguments[0])})"
        }
        val target = model.callTargets[e] ?: fail("调用 '${e.name}'")
        val args = e.arguments.joinToString(", ") { expr(it) }
        return "${fnName(target)}($args)"
    }

    private fun printCall(e: CallExpr): String {
        val newline = e.name == "println"
        if (e.arguments.isEmpty()) return if (newline) "rsf_println_empty()" else "0"
        val arg = e.arguments[0]
        val type = model.typeOf(arg)
        val value = expr(arg)
        return when (type.base) {
            RsfBase.STRING -> if (newline) "rsf_println_s($value)" else "rsf_print_s($value)"
            RsfBase.LONG -> if (newline) "rsf_println_l($value)" else "rsf_print_l($value)"
            RsfBase.BOOL -> if (newline) "rsf_println_b($value)" else "rsf_print_b($value)"
            RsfBase.INT -> if (newline) "rsf_println_i($value)" else "rsf_print_i($value)"
            else -> fail("print 的参数类型 '${type.base.rsfName}'")
        }
    }

    /** 字符串字面量按 UTF-8 逐字节写成八进制转义，长度也按字节算（与 JVM/解释器一致）。 */
    private fun stringLit(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("rsf_str_lit(\"")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == '"'.code || v == '\\'.code) {
                sb.append('\\').append(v.toChar())
            } else if (v in 0x20..0x7E) {
                sb.append(v.toChar())
            } else {
                sb.append('\\').append(String.format("%03o", v))
            }
        }
        sb.append("\", ").append(bytes.size).append("LL)")
        return sb.toString()
    }

    private fun pad(indent: Int): String = "    ".repeat(indent)

    companion object {
        /** 单文件运行时：程序与这些辅助函数编在一起，不依赖任何 rsf 运行时库。 */
        private val RUNTIME = """
            /* 由 rsf build --c 生成（阶段 52 的 C 后端，native 线第一刀） */
            #include <stdint.h>
            #include <stdbool.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>

            typedef struct { int64_t len; const char* data; } rsf_str;

            static rsf_str rsf_str_lit(const char* data, int64_t len) {
                rsf_str s; s.len = len; s.data = data; return s;
            }

            static rsf_str rsf_concat(rsf_str a, rsf_str b) {
                char* p = (char*)malloc((size_t)(a.len + b.len));
                if (a.len > 0) memcpy(p, a.data, (size_t)a.len);
                if (b.len > 0) memcpy(p + a.len, b.data, (size_t)b.len);
                rsf_str s; s.len = a.len + b.len; s.data = p; return s;
            }

            static bool rsf_str_eq(rsf_str a, rsf_str b) {
                return a.len == b.len && memcmp(a.data, b.data, (size_t)a.len) == 0;
            }

            static void rsf_print_s(rsf_str s) { fwrite(s.data, 1, (size_t)s.len, stdout); }
            static void rsf_print_i(int32_t v)  { printf("%d", (int)v); }
            static void rsf_print_l(int64_t v)  { printf("%lld", (long long)v); }
            static void rsf_print_b(bool v)     { fputs(v ? "true" : "false", stdout); }
            static void rsf_println_s(rsf_str s) { rsf_print_s(s); putchar('\n'); }
            static void rsf_println_i(int32_t v)  { rsf_print_i(v); putchar('\n'); }
            static void rsf_println_l(int64_t v)  { rsf_print_l(v); putchar('\n'); }
            static void rsf_println_b(bool v)     { rsf_print_b(v); putchar('\n'); }
            static void rsf_println_empty(void)   { putchar('\n'); }

            static rsf_str rsf_int_to_str(int32_t v) {
                char* buf = (char*)malloc(16);
                int n = snprintf(buf, 16, "%d", (int)v);
                rsf_str s; s.len = n; s.data = buf; return s;
            }

        """.trimIndent()
    }
}
