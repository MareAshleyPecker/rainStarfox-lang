package org.example.complie.native

import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.Expr
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.ForStmt
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IfStmt
import org.example.complie.ast.IndexAssignStmt
import org.example.complie.ast.IndexExpr
import org.example.complie.ast.IntLit
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.Stmt
import org.example.complie.ast.StringLit
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TypeRef
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt
import org.example.complie.diag.RsfError
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import org.example.complie.sema.UserMember

/**
 * 阶段 52 的 **C 后端**（native 线的第一刀）。
 *
 * 口径：把已通过语义检查的程序**整份摊成一个 C99 文件**，由系统 C 编译器
 * （本机是 `D:\mingw64\bin\gcc`，也兼容 `zig cc`）编成原生可执行文件。
 * 为什么先出 C 而不是直接发机器码：C 是最短的一条「能跑」路径——寄存器分配、
 * 调用约定、PE 链接全交给编译器，我们只对轴『语义 → C』负责；指针那套
 * （DESIGN-23 的 L1/L2/L3）以后可以在这条线上按真地址落地。
 *
 * 支持范围（阶段 52c 起含类）：
 * int / long / bool / string / 一维数组 / 类（字段 + 实例方法与静态方法 + 构造器）/
 * 顶层函数（不重载）/ if / while / for / 递归 / print / println / 字符串 `+` 与 `==` /
 * `intToString` 等少数内置。
 * 类继承、接口、枚举、泛型、多维数组、浮点、错误返回都还没接，遇到**显式报错**。
 */
class CBackend(private val model: SemanticModel) {

    /** 换行符用码点拼，避免源码里的转义在工具链里被二次解析。 */
    private val NL: Char = 10.toChar()

    private val classes: Map<String, ClassDecl> = model.classModel.classes
    private val ctorNames = HashMap<FuncDecl, String>()
    private val methodNames = HashMap<FuncDecl, String>()
    private val defaultCtorNames = HashMap<ClassDecl, String>()
    private var currentClass: ClassDecl? = null

    fun emit(): String {
        checkProgram()
        nameEverything()
        val sb = StringBuilder()
        sb.append(RUNTIME)
        for (cls in classes.values) sb.append(struct(cls))
        for (fn in model.functions.sortedBy { it.name }) sb.append(protoTop(fn)).append(";\n")
        for (cls in classes.values) {
            for (fn in cls.methods) sb.append(protoMember(cls, fn)).append(";\n")
            val defaultCtor = defaultCtorNames[cls]
            if (defaultCtor != null) sb.append("rsf_cls_${cls.name}* $defaultCtor(void);"); sb.append(10.toChar())
        }
        sb.append('\n')
        for (fn in model.functions.sortedBy { it.name }) sb.append(definition(fn, null))
        for (cls in classes.values) {
            for (fn in cls.methods) sb.append(definition(fn, cls))
            if (defaultCtorNames.containsKey(cls)) sb.append(defaultConstructor(cls))
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

    private fun nameEverything() {
        for (cls in classes.values) {
            var ctorIndex = 0
            for (fn in cls.methods) {
                if (fn.isConstructor) {
                    ctorNames[fn] = "rsf_new_${cls.name}_$ctorIndex"
                    ctorIndex++
                } else if (fn.isStatic) {
                    methodNames[fn] = "rsf_st_${cls.name}_${fn.name}"
                } else {
                    methodNames[fn] = "rsf_m_${cls.name}_${fn.name}"
                }
            }
            if (ctorIndex == 0) defaultCtorNames[cls] = "rsf_new_${cls.name}_0"
        }
    }

    // ------------------------------------------------------------------ 前置检查

    private fun checkProgram() {
        if (model.enums.isNotEmpty()) fail("枚举")
        if (model.interfaces.isNotEmpty()) fail("接口")
        if (model.annotations.isNotEmpty()) fail("注解")
        if (model.superClasses.isNotEmpty()) fail("类继承")
        if (model.superInterfaces.isNotEmpty()) fail("接口实现")
        for (fn in model.functions) {
            if (model.functionTable[fn.name]?.size ?: 0 > 1) fail("重载函数（${fn.name}）")
        }
        for (cls in classes.values) {
            if (cls.typeParams.isNotEmpty()) fail("泛型类（${cls.name}）")
            if (cls.supertypes.isNotEmpty()) fail("类的父类型（${cls.name}）")
            for (fn in cls.methods) if (fn.body == null) fail("无方法体（${fn.name}）")
        }
    }

    private fun fail(what: String): Nothing =
        throw RsfError("native", "C 后端暂不支持：$what（阶段 52 的 C 后端只接了 int/long/bool/string/数组/类）")

    // ------------------------------------------------------------------ 类型

    private fun returnType(fn: FuncDecl): RsfType =
        model.returnTypes[fn] ?: model.classModel.methodReturns[fn] ?: RsfType.VOID

    /** 参数类型：顶层函数在 `model.paramTypes`，类方法/构造器在 `classModel.methodParams` 里。 */
    private fun paramTypesOf(fn: FuncDecl): List<RsfType> =
        model.paramTypes[fn] ?: model.classModel.methodParams[fn]?.map { it.type } ?: emptyList()

    private fun cType(type: RsfType): String {
        if (type.arrayDepth > 1) fail("多维数组")
        if (type.arrayDepth == 1) return "rsf_arr"
        return when (type.base) {
            RsfBase.VOID -> "void"
            RsfBase.INT -> "int32_t"
            RsfBase.LONG -> "int64_t"
            RsfBase.BOOL -> "bool"
            RsfBase.STRING -> "rsf_str"
            RsfBase.CHAR -> "uint16_t"
            RsfBase.BYTE -> "int8_t"
            RsfBase.EXTERNAL -> classPointer(type)
            else -> fail("类型 '${type.base.rsfName}'")
        }
    }

    private fun classPointer(type: RsfType): String {
        val name = type.externalName ?: fail("外部类型")
        if (classes.containsKey(name)) return "rsf_cls_$name*"
        fail("JVM 类型（$name）")
    }

    private fun elemCType(type: RsfType): String = when (type.base) {
        RsfBase.INT -> "int32_t"
        RsfBase.LONG -> "int64_t"
        RsfBase.BOOL -> "bool"
        RsfBase.STRING -> "rsf_str"
        RsfBase.CHAR -> "uint16_t"
        RsfBase.BYTE -> "int8_t"
        RsfBase.EXTERNAL -> classPointer(type)
        else -> fail("数组元素类型 '${type.base.rsfName}'")
    }

    private fun elementOf(type: RsfType): RsfType =
        if (type.arrayDepth > 0) type.copy(arrayDepth = type.arrayDepth - 1) else type

    // ------------------------------------------------------------------ 类

    private fun fieldTypes(cls: ClassDecl): List<RsfType> =
        model.classModel.fieldTypes[cls] ?: cls.fields.map { fail("字段类型（${it.name}）") }

    private fun struct(cls: ClassDecl): String {
        val sb = StringBuilder()
        sb.append("typedef struct rsf_cls_${cls.name} {\n")
        val types = fieldTypes(cls)
        cls.fields.forEachIndexed { i, f ->
            sb.append("    ").append(cType(types[i])).append(' ').append(f.name).append(";\n")
        }
        sb.append("} rsf_cls_${cls.name};\n\n")
        return sb.toString()
    }

    private fun protoTop(fn: FuncDecl): String {
        val params = paramTypesOf(fn)
        val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        return "${cType(returnType(fn))} ${fnName(fn)}(${list.joinToString(", ").ifEmpty { "void" }})"
    }

    private fun protoMember(cls: ClassDecl, fn: FuncDecl): String {
        val params = paramTypesOf(fn)
        val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }.toMutableList()
        if (fn.isConstructor) {
            val name = ctorNames[fn] ?: fail("构造器命名")
            return "rsf_cls_${cls.name}* $name(${list.joinToString(", ").ifEmpty { "void" }})"
        }
        val name = methodNames[fn] ?: fail("方法命名")
        if (!fn.isStatic) list.add(0, "rsf_cls_${cls.name}* self")
        return "${cType(returnType(fn))} $name(${list.joinToString(", ").ifEmpty { "void" }})"
    }

    private fun definition(fn: FuncDecl, cls: ClassDecl?): String {
        currentClass = cls
        val sb = StringBuilder()
        if (cls == null) {
            sb.append(protoTop(fn)).append(" {\n")
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("}\n\n")
            currentClass = null
            return sb.toString()
        }
        if (fn.isConstructor) {
            val name = ctorNames[fn] ?: fail("构造器命名")
            val params = paramTypesOf(fn)
            val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
            sb.append("rsf_cls_${cls.name}* $name(${list.joinToString(", ").ifEmpty { "void" }}) {\n")
            sb.append("    rsf_cls_${cls.name}* self = (rsf_cls_${cls.name}*)calloc(1, sizeof(rsf_cls_${cls.name}));\n")
            cls.fields.forEach { f ->
                sb.append("    self->").append(f.name).append(" = ").append(expr(f.initializer)).append(";\n")
            }
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("    return self;\n")
            sb.append("}\n\n")
            currentClass = null
            return sb.toString()
        }
        sb.append(protoMember(cls, fn)).append(" {\n")
        fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
        sb.append("}\n\n")
        currentClass = null
        return sb.toString()
    }

    /** 类没写构造器时，`new C()` 仍要能跑：补一个只填字段初值的默认构造器。 */
    private fun defaultConstructor(cls: ClassDecl): String {
        val name = defaultCtorNames[cls] ?: fail("默认构造器命名")
        currentClass = cls
        val sb = StringBuilder()
        sb.append("rsf_cls_${cls.name}* $name(void) {\n")
        sb.append("    rsf_cls_${cls.name}* self = (rsf_cls_${cls.name}*)calloc(1, sizeof(rsf_cls_${cls.name}));\n")
        cls.fields.forEach { f ->
            sb.append("    self->").append(f.name).append(" = ").append(expr(f.initializer)).append(";\n")
        }
        sb.append("    return self;\n}\n\n")
        currentClass = null
        return sb.toString()
    }

    // ------------------------------------------------------------------ 函数名

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    private fun paramName(name: String): String = "v_" + name

    // ------------------------------------------------------------------ 语句

    private fun stmt(s: Stmt, indent: Int): String = when (s) {
        is VarDeclStmt -> {
            val type = model.typeOf(s)
            pad(indent) + "${cType(type)} ${paramName(s.name)} = ${expr(s.initializer)};\n"
        }
        is AssignStmt -> {
            val field = model.classModel.bareFieldWrites[s]
            if (field != null) {
                pad(indent) + "self->" + s.name + " = " + expr(s.value) + ";\n"
            } else {
                pad(indent) + "${paramName(s.name)} = ${expr(s.value)};\n"
            }
        }
        is MemberAssignStmt -> {
            if (model.classModel.fieldWrites[s] == null) fail("成员赋值")
            pad(indent) + expr(s.target.receiver) + "->" + s.target.name + " = " + expr(s.value) + ";\n"
        }
        is IndexAssignStmt -> {
            val target = s.target
            val elem = elemCType(elementOf(model.typeOf(target.receiver)))
            pad(indent) + "(($elem*)" + expr(target.receiver) + ".data)[" + expr(target.index) + "] = " + expr(s.value) + ";" + NL
        }
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

    private fun forStmt(s: ForStmt, indent: Int): String {
        val step = when (val st = s.step) {
            null -> ""
            is AssignStmt -> if (model.classModel.bareFieldWrites[st] != null) {
                "self->" + st.name + " = " + expr(st.value)
            } else {
                "${paramName(st.name)} = ${expr(st.value)}"
            }
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
        is CharLit -> e.value.code.toString() + "u"
        is StringLit -> stringLit(e.value)
        is BoolLit -> if (e.value) "true" else "false"
        is VarExpr -> {
            val field = model.classModel.fieldReads[e]
            if (field != null) "self->${e.name}" else paramName(e.name)
        }
        is ThisExpr -> "self"
        is UnaryExpr -> unary(e)
        is BinaryExpr -> binary(e)
        is IfExpr -> "(${expr(e.condition)} ? ${expr(e.whenTrue)} : ${expr(e.whenFalse)})"
        is CallExpr -> call(e)
        is IndexExpr -> indexRead(e)
        is MemberExpr -> member(e)
        is NewArrayExpr -> newArray(e)
        is NewExpr -> newObj(e)
        else -> fail("表达式 ${e::class.simpleName}")
    }

    private fun newObj(e: NewExpr): String {
        val cls = model.classModel.newTargets[e] ?: fail("new 的类（${e.className}）")
        val ctor = model.classModel.newCtors[e]
        val name = if (ctor != null) ctorNames[ctor] ?: fail("构造器命名") else defaultCtorNames[cls] ?: fail("默认构造器命名")
        val args = e.arguments.joinToString(", ") { expr(it) }
        return "$name($args)"
    }

    private fun indexRead(e: IndexExpr): String {
        val arrayType = model.typeOf(e.receiver)
        val elem = elemCType(elementOf(arrayType))
        return "(($elem*)${expr(e.receiver)}.data)[${expr(e.index)}]"
    }

    private fun member(e: MemberExpr): String {
        if (e.name == "length" && e.arguments == null) return "(${expr(e.receiver)}.len)"
        val receiver = expr(e.receiver)
        return when (val resolved = model.classModel.members[e]) {
            is UserMember.Field -> "$receiver->${e.name}"
            is UserMember.Method -> {
                val name = methodNames[resolved.decl] ?: fail("方法命名")
                val args = e.arguments ?: emptyList()
                if (resolved.decl.isStatic) {
                    "$name(${args.joinToString(", ") { expr(it) }})"
                } else {
                    (listOf(receiver) + args.map { expr(it) }).joinToString(", ").let { "$name($it)" }
                }
            }
            else -> fail("成员访问 '${e.name}'")
        }
    }

    private fun newArray(e: NewArrayExpr): String {
        if (e.dimensions.size != 1) fail("多维数组")
        val type = RsfType(baseOf(e.elementType), arrayDepth = 0)
        val elemSize = elemCType(type)
        return "rsf_arr_new(${expr(e.dimensions[0])}, (int64_t)sizeof($elemSize))"
    }

    private fun baseOf(ref: TypeRef): RsfBase = when (ref.name) {
        "int" -> RsfBase.INT
        "long" -> RsfBase.LONG
        "bool" -> RsfBase.BOOL
        "string" -> RsfBase.STRING
        "char" -> RsfBase.CHAR
        "byte" -> RsfBase.BYTE
        else -> if (classes.containsKey(ref.name)) RsfBase.EXTERNAL else fail("数组元素类型 '${ref.name}'")
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
        val implicit = model.classModel.implicitCalls[e]
        if (implicit != null) {
            val name = methodNames[implicit.decl] ?: fail("方法命名")
            val args = e.arguments.joinToString(", ") { expr(it) }
            return if (implicit.decl.isStatic) {
                "$name($args)"
            } else {
                "$name(self${if (args.isEmpty()) "" else ", $args"})"
            }
        }
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
            typedef struct { int64_t len; void* data; } rsf_arr;

            static rsf_arr rsf_arr_new(int64_t len, int64_t elem) {
                rsf_arr a; a.len = len; a.data = calloc((size_t)(len > 0 ? len : 0), (size_t)elem); return a;
            }

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
