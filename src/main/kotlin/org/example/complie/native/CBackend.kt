package org.example.complie.native

import org.example.complie.ast.AssignStmt
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.BuildTarget
import org.example.complie.ast.BreakStmt
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.ContinueStmt
import org.example.complie.ast.DerefAssignStmt
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.Expr
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.FieldDecl
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
import org.example.complie.ast.SuperCallStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.SwitchLabel
import org.example.complie.ast.SwitchStmt
import org.example.complie.ast.ThisCallStmt
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
import org.example.complie.sema.SuperCall
import org.example.complie.sema.SuperDelegation
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
class CBackend(private val model: SemanticModel, private val withEntry: Boolean = true) {

    /** 换行符用码点拼，避免源码里的转义在工具链里被二次解析。 */
    private val NL: Char = 10.toChar()

    private val classes: Map<String, ClassDecl> = model.classModel.classes
    private val ctorNames = HashMap<FuncDecl, String>()
    private val ctorInitNames = HashMap<FuncDecl, String>()
    private val methodNames = HashMap<FuncDecl, String>()
    private val defaultCtorNames = HashMap<ClassDecl, String>()
    private val defaultInitNames = HashMap<ClassDecl, String>()
    private var currentClass: ClassDecl? = null

    /** 虚方法槽位：`名字/参数个数` → 下标。整份程序只有这一张表，类与父类共用。 */
    private val slots = LinkedHashMap<String, Int>()

    /** `switch` 的被分派值落在临时变量里，编号保证同一函数内不重名。 */
    private var switchSeq = 0

    fun emit(): String {
        checkProgram()
        nameEverything()
        val sb = StringBuilder()
        sb.append(RUNTIME)
        for (e in model.enums.values) sb.append(enumBlock(e))
        for (cls in classes.values) sb.append(struct(cls))
        for (fn in model.functions.sortedBy { it.name }) sb.append(protoTop(fn)).append(";\n")
        for (cls in classes.values) {
            for (fn in cls.methods) {
                sb.append(protoMember(cls, fn)).append(";\n")
                if (fn.isConstructor) sb.append(protoConstructor(cls, fn)).append(";\n")
            }
            val defaultCtor = defaultCtorNames[cls]
            if (defaultCtor != null) {
                sb.append("void ${defaultInitNames[cls]}(void* self);").append(NL)
                sb.append("void* $defaultCtor(void);").append(NL)
            }
        }
        sb.append('\n')
        for (iface in model.interfaces.values) {
            for (fn in iface.methods) {
                if (fn.body == null) continue
                sb.append(ifaceProto(iface, fn)).append(";").append(NL)
            }
        }
        for (cls in classes.values) {
            val table = vtable(cls)
            if (table != null) sb.append(table)
        }
        sb.append('\n')
        for (fn in model.functions.sortedBy { it.name }) sb.append(definition(fn, null))
        for (cls in classes.values) {
            for (fn in cls.methods) sb.append(definition(fn, cls))
            if (defaultCtorNames.containsKey(cls)) sb.append(defaultConstructor(cls))
        }
        for (iface in model.interfaces.values) {
            for (fn in iface.methods) {
                if (fn.body == null) continue
                sb.append(definition(fn, null, iface)).append(NL)
            }
        }
        if (!withEntry) {
            // --c --lib：不发 main，宿主自己声明/调用导出的 rsf_fn_* 函数
            return sb.toString()
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
                    ctorInitNames[fn] = "rsf_init_${cls.name}_$ctorIndex"
                    ctorNames[fn] = "rsf_new_${cls.name}_$ctorIndex"
                    ctorIndex++
                } else if (fn.isStatic) {
                    methodNames[fn] = "rsf_st_${cls.name}_${fn.name}"
                } else {
                    methodNames[fn] = "rsf_m_${cls.name}_${fn.name}"
                }
            }
            if (ctorIndex == 0) {
                defaultInitNames[cls] = "rsf_init_${cls.name}_def"
                defaultCtorNames[cls] = "rsf_new_${cls.name}_0"
            }
        }
        // 接口方法：默认方法要发实体，抽象方法只进槽位表（实现由类提供）
        for (iface in model.interfaces.values) {
            for (fn in iface.methods) {
                methodNames[fn] = if (fn.isStatic) {
                    "rsf_st_${iface.name}_${fn.name}"
                } else {
                    "rsf_m_${iface.name}_${fn.name}"
                }
            }
        }
        // 虚方法槽位按名字 + 参数个数排序后编号：不依赖 Map 的迭代顺序，产物可复现
        val signatures = ArrayList<String>()
        for (cls in classes.values) {
            for (fn in cls.methods) {
                if (fn.isConstructor || fn.isStatic) continue
                val key = fn.name + "/" + fn.params.size
                if (key !in signatures) signatures.add(key)
            }
        }
        for (iface in model.interfaces.values) {
            for (fn in iface.methods) {
                if (fn.isConstructor || fn.isStatic) continue
                val key = fn.name + "/" + fn.params.size
                if (key !in signatures) signatures.add(key)
            }
        }
        signatures.sort()
        for ((index, key) in signatures.withIndex()) slots[key] = index
    }

    // ------------------------------------------------------------------ 前置检查

    private fun checkProgram() {
        if (model.annotations.isNotEmpty()) fail("注解")
        for (sup in model.superClasses.values) {
            if (sup.fromClassPath) fail("继承 JDK 类（${sup.name}）")
        }
        for (iface in model.interfaces.values) {
            if (iface.typeParams.isNotEmpty()) fail("泛型接口（${iface.name}）")
        }
        for (list in model.superInterfaces.values) {
            for (sup in list) {
                if (sup.fromClassPath) fail("实现 JDK 接口（${sup.name}）")
            }
        }
        for (fn in model.functions) {
            if (model.functionTable[fn.name]?.size ?: 0 > 1) fail("重载函数（${fn.name}）")
        }
        for (cls in classes.values) {
            if (cls.typeParams.isNotEmpty()) fail("泛型类（${cls.name}）")
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
        if (type.arrayDepth == 1) {
            if (type.ptrDepth > 0) fail("指针数组")
            return "rsf_arr"
        }
        // 阶段 53：指针就是 C 的指针——基础类型直接加 `*`，
        // 类本来就已经是 `rsf_cls_X*`（引用），指针再叠一层就是 `rsf_cls_X**`。
        if (type.ptrDepth > 0) return cType(type.copy(ptrDepth = 0)) + "*".repeat(type.ptrDepth)
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

    /**
     * 类引用一律摊成 `void*`：继承下子类对象要能直接传给「父类参数」，
     * 用 `rsf_cls_父*` 会触发不兼容指针告警；字段访问时再按**声明类**转回去
     * （`((rsf_cls_Owner*)p)->f_Owner_x`，父类字段在子类结构里是同一段前缀）。
     */
    private fun classPointer(type: RsfType): String {
        val name = type.externalName ?: fail("外部类型")
        if (classes.containsKey(name)) return "void*"
        if (model.interfaces.containsKey(name)) return "void*"
        if (model.enums.containsKey(name)) return "rsf_enum_$name"
        fail("JVM 类型（$name）")
    }

    /** 枚举就是 int 常量：typedef int32_t rsf_enum_Color; 加一列 rsf_val_Color_Red = 0。 */
    private fun enumBlock(decl: EnumDecl): String {
        val sb = StringBuilder()
        sb.append("typedef int32_t rsf_enum_${decl.name};").append(NL)
        sb.append("enum {")
        decl.values.forEachIndexed { i, v ->
            if (i > 0) sb.append(", ")
            sb.append("rsf_val_${decl.name}_${v.name} = ").append(i)
        }
        sb.append("};").append(NL).append(NL)
        return sb.toString()
    }

    /** 数组元素（或指针指向的值）在 C 里的类型——交给 [cType] 统一算，含指针层。 */
    private fun elemCType(type: RsfType): String = cType(type.copy(arrayDepth = 0))

    private fun elementOf(type: RsfType): RsfType =
        if (type.arrayDepth > 0) type.copy(arrayDepth = type.arrayDepth - 1) else type

    // ------------------------------------------------------------------ 类

    private fun fieldTypeOf(decl: ClassDecl, f: FieldDecl): RsfType {
        val types = model.classModel.fieldTypes[decl] ?: return fail("字段类型（${f.name}）")
        val index = decl.fields.indexOf(f)
        return types.getOrNull(index) ?: fail("字段类型（${f.name}）")
    }

    private fun fieldName(decl: ClassDecl, f: FieldDecl): String = "f_${decl.name}_${f.name}"

    /** 字段访问：按**声明类**转回结构体指针，再用限定名取（父类字段在子类结构里是同一段前缀）。 */
    private fun fieldAccess(receiver: String, decl: ClassDecl, f: FieldDecl): String =
        "((rsf_cls_${decl.name}*)$receiver)->${fieldName(decl, f)}"

    private fun fieldRef(receiver: String, className: String, name: String): String =
        "((rsf_cls_$className*)$receiver)->f_${className}_$name"

    /**
     * 结构体 = 虚表指针 + **整条父链的字段**（父类在前、本类在后，与解释器的扁平字段数组同序）。
     *
     * 子类结构的前缀因此与父类结构逐字节一致：父类方法拿到 `void*` 转成
     * `rsf_cls_父*` 后读到的就是同一批字段。
     */
    private fun struct(cls: ClassDecl): String {
        val sb = StringBuilder()
        sb.append("typedef struct rsf_cls_${cls.name} {").append(NL)
        sb.append("    void** rsf_vtbl;").append(NL)
        for ((decl, f) in model.fieldChainOf(cls.name)) {
            sb.append("    ").append(cType(fieldTypeOf(decl, f))).append(' ')
                .append(fieldName(decl, f)).append(';').append(NL)
        }
        sb.append("} rsf_cls_${cls.name};").append(NL).append(NL)
        return sb.toString()
    }

    /**
     * 每个类一张虚表：槽位下标与 [slots] 对齐，内容是这个类（沿父链往上找）最靠前的实现。
     *
     * 覆盖方法因此自动生效：子类虚表里同一个槽位填的是自己那份，父类指针调用也走虚表。
     * 没有实现（理论上 Checker 已挡住）填空指针。
     */
    private fun vtable(cls: ClassDecl): String {
        if (slots.isEmpty()) return "static void* rsf_vtbl_${cls.name}[1] = { 0 };$NL$NL"
        val chain = ArrayList<ClassDecl>()
        chain.add(cls)
        chain.addAll(model.classAncestorsOf(cls.name).reversed())
        val entries = ArrayList<String>()
        for (key in slots.keys) {
            val parts = key.split("/")
            val name = parts[0]
            val arity = parts[1].toInt()
            var found: String? = null
            for (c in chain) {
                val fn = c.methods.firstOrNull {
                    !it.isConstructor && !it.isStatic && it.name == name && it.params.size == arity
                }
                if (fn != null) {
                    found = methodNames[fn]
                    break
                }
            }
            if (found == null) {
                // 类链里没有 → 找接口默认方法（抽象方法没有实体，留在 0 上）
                for (iface in interfacesOf(cls.name)) {
                    val fn = iface.methods.firstOrNull {
                        !it.isConstructor && !it.isStatic && it.body != null &&
                            it.name == name && it.params.size == arity
                    }
                    if (fn != null) {
                        found = methodNames[fn]
                        break
                    }
                }
            }
            entries.add(if (found != null) "(void*)$found" else "0")
        }
        return "static void* rsf_vtbl_${cls.name}[${slots.size}] = { ${entries.joinToString(", ")} };$NL$NL"
    }

    /** 类实现的所有 rsf 接口（含接口继承来的父接口），按声明顺序。 */
    private fun interfacesOf(className: String): List<InterfaceDecl> {
        val out = ArrayList<InterfaceDecl>()
        val seen = HashSet<String>()
        fun visit(name: String) {
            val iface = model.interfaces[name] ?: return
            if (!seen.add(name)) return
            out.add(iface)
            for (sup in iface.supertypes) visit(sup.name)
        }
        for (sup in model.superInterfaces[className].orEmpty()) visit(sup.name)
        return out
    }

    /** 接口里的默认方法/静态方法：一行 C 原型（self 就是 `void*`）。 */
    private fun ifaceProto(iface: InterfaceDecl, fn: FuncDecl): String {
        val params = paramTypesOf(fn)
        val first = if (fn.isStatic) emptyList() else listOf("void* self")
        val rest = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        val list = (first + rest).joinToString(", ").ifEmpty { "void" }
        return "${cType(returnType(fn))} ${methodNames[fn]}($list)"
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
            // 构造器拆成两半：`rsf_init_*` 在**已有对象**上跑（父类构造器要复用它），
            // `rsf_new_*` 分配 + 装虚表 + 调 init。
            val name = ctorInitNames[fn] ?: fail("构造器命名")
            list.add(0, "void* self")
            return "void $name(${list.joinToString(", ")})"
        }
        val name = methodNames[fn] ?: fail("方法命名")
        if (!fn.isStatic) list.add(0, "void* self")
        return "${cType(returnType(fn))} $name(${list.joinToString(", ").ifEmpty { "void" }})"
    }

    private fun protoConstructor(cls: ClassDecl, fn: FuncDecl): String {
        val name = ctorNames[fn] ?: fail("构造器命名")
        val params = paramTypesOf(fn)
        val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        return "void* $name(${list.joinToString(", ").ifEmpty { "void" }})"
    }

    private fun definition(fn: FuncDecl, cls: ClassDecl?, iface: InterfaceDecl? = null): String {
        currentClass = cls
        val sb = StringBuilder()
        if (iface != null) {
            sb.append(ifaceProto(iface, fn)).append(" {").append(NL)
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("}").append(NL).append(NL)
            currentClass = null
            return sb.toString()
        }
        if (cls == null) {
            sb.append(protoTop(fn)).append(" {\n")
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("}\n\n")
            currentClass = null
            return sb.toString()
        }
        if (fn.isConstructor) {
            sb.append(ctorInitDefinition(cls, fn))
            sb.append(ctorNewDefinition(cls, fn))
            currentClass = null
            return sb.toString()
        }
        sb.append(protoMember(cls, fn)).append(" {\n")
        fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
        sb.append("}\n\n")
        currentClass = null
        return sb.toString()
    }

    /** 构造器的「在已有对象上跑」那一半：先父类那一跳（或 `this(...)`），再字段初值，最后体。 */
    private fun ctorInitDefinition(cls: ClassDecl, fn: FuncDecl): String {
        val name = ctorInitNames[fn] ?: fail("构造器命名")
        val params = paramTypesOf(fn)
        val list = listOf("void* self") +
            fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        val sb = StringBuilder()
        sb.append("void $name(${list.joinToString(", ")}) {").append(NL)
        val thisCall = model.classModel.delegationOf(fn)
        if (thisCall != null) {
            // this(...)：转给同类另一个构造器；字段初值只在那条链的根跑
            val target = ctorInitNames[thisCall.target] ?: fail("构造器命名")
            sb.append("    ").append(target).append("(self")
            thisCall.stmt.arguments.forEach { sb.append(", ").append(expr(it)) }
            sb.append(");").append(NL)
        } else {
            appendSuperInit(sb, cls, model.classModel.superDelegationOf(fn) ?: model.classModel.implicitSuperDelegationOf(cls))
            for (f in cls.fields) {
                sb.append("    ").append(fieldAccess("self", cls, f)).append(" = ")
                    .append(expr(f.initializer)).append(';').append(NL)
            }
        }
        fn.body?.statements?.forEach {
            if (it !is SuperCallStmt && it !is ThisCallStmt) sb.append(stmt(it, 1))
        }
        sb.append("}").append(NL).append(NL)
        return sb.toString()
    }

    /** 父类构造委托：`super(实参)` 或隐式 `super()`；目标可能是父类的某个构造器或它的默认 init。 */
    private fun appendSuperInit(sb: StringBuilder, cls: ClassDecl, delegation: SuperDelegation?) {
        if (delegation == null) return
        val target = delegation.target
        val call = if (target != null) {
            ctorInitNames[target] ?: fail("父类构造器命名")
        } else {
            val parent = delegation.superClass.declaration?.name?.let { classes[it] }
                ?: fail("父类构造器（父类是 JDK 类）")
            defaultInitNames[parent] ?: fail("父类默认构造器")
        }
        sb.append("    ").append(call).append("(self")
        delegation.arguments.forEach { sb.append(", ").append(expr(it)) }
        sb.append(");").append(NL)
    }

    /** 构造器的「分配 + 装虚表 + 调 init」那一半。 */
    private fun ctorNewDefinition(cls: ClassDecl, fn: FuncDecl): String {
        val name = ctorNames[fn] ?: fail("构造器命名")
        val init = ctorInitNames[fn] ?: fail("构造器命名")
        val params = paramTypesOf(fn)
        val list = fn.params.mapIndexed { i, p -> "${cType(params[i])} ${paramName(p.name)}" }
        val args = fn.params.joinToString(", ") { paramName(it.name) }
        val sb = StringBuilder()
        sb.append("void* $name(${list.joinToString(", ").ifEmpty { "void" }}) {").append(NL)
        sb.append("    void* self = calloc(1, sizeof(rsf_cls_${cls.name}));").append(NL)
        sb.append("    *(void***)self = rsf_vtbl_${cls.name};").append(NL)
        sb.append("    $init(self").append(if (args.isEmpty()) "" else ", $args").append(");").append(NL)
        sb.append("    return self;").append(NL).append("}").append(NL).append(NL)
        return sb.toString()
    }

    /** 类没写构造器时，`new C()` 仍要能跑：补一个只跑父类那一跳与字段初值的默认 init。 */
    private fun defaultConstructor(cls: ClassDecl): String {
        val name = defaultCtorNames[cls] ?: fail("默认构造器命名")
        val init = defaultInitNames[cls] ?: fail("默认构造器命名")
        currentClass = cls
        val sb = StringBuilder()
        sb.append("void $init(void* self) {").append(NL)
        appendSuperInit(sb, cls, model.classModel.implicitSuperDelegationOf(cls))
        for (f in cls.fields) {
            sb.append("    ").append(fieldAccess("self", cls, f)).append(" = ")
                .append(expr(f.initializer)).append(';').append(NL)
        }
        sb.append("}").append(NL).append(NL)
        sb.append("void* $name(void) {").append(NL)
        sb.append("    void* self = calloc(1, sizeof(rsf_cls_${cls.name}));").append(NL)
        sb.append("    *(void***)self = rsf_vtbl_${cls.name};").append(NL)
        sb.append("    $init(self);").append(NL)
        sb.append("    return self;").append(NL).append("}").append(NL).append(NL)
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
                pad(indent) + fieldRef("self", field.className, s.name) + " = " + expr(s.value) + ";\n"
            } else {
                pad(indent) + "${paramName(s.name)} = ${expr(s.value)};\n"
            }
        }
        is DerefAssignStmt -> {
            val target = s.target as? UnaryExpr ?: fail("解引用赋值")
            pad(indent) + "*(" + expr(target.operand) + ") = " + expr(s.value) + ";" + NL
        }

        is MemberAssignStmt -> {
            val field = model.classModel.fieldWrites[s] ?: fail("成员赋值")
            pad(indent) + fieldRef(expr(s.target.receiver), field.className, s.target.name) +
                " = " + expr(s.value) + ";\n"
        }
        is SuperCallStmt, is ThisCallStmt -> ""
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
        is SwitchStmt -> switchStmt(s, indent)
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

    /**
     * `switch` 没有 fallthrough ⇒ 摊成 if / else if 链（阶段 56 起支持枚举、Int/Long、
     * String 三种主体）。被分派的值先落进一个临时变量，保证 `switch (f())` 只求值一次。
     */
    private fun switchStmt(s: SwitchStmt, indent: Int): String {
        val sb = StringBuilder()
        val subjectType = model.typeOf(s.subject)
        val tmp = "sw_" + (switchSeq++)
        val isLong = subjectType.base == RsfBase.LONG
        sb.append(pad(indent)).append("{\n")
        sb.append(pad(indent + 1)).append(cType(subjectType)).append(' ').append(tmp)
            .append(" = ").append(expr(s.subject)).append(";\n")
        s.cases.forEachIndexed { i, c ->
            val cond = c.labels.joinToString(" || ") { labelCond(it, tmp, isLong) }
            sb.append(pad(indent + 1)).append(if (i == 0) "if" else "else if").append(" (").append(cond).append(") {\n")
            for (inner in c.body.statements) sb.append(stmt(inner, indent + 2))
            sb.append(pad(indent + 1)).append("}\n")
        }
        val elseBody = s.elseBody
        if (elseBody != null) {
            sb.append(pad(indent + 1)).append(if (s.cases.isEmpty()) "{" else "else {").append("\n")
            for (inner in elseBody.statements) sb.append(stmt(inner, indent + 2))
            sb.append(pad(indent + 1)).append("}\n")
        }
        sb.append(pad(indent)).append("}\n")
        return sb.toString()
    }

    /** 一个 `switch` 标签的比较表达式（`tmp` 是保存被分派值的临时变量）。 */
    private fun labelCond(label: SwitchLabel, tmp: String, isLong: Boolean): String =
        when (label) {
            is SwitchLabel.EnumConst -> "$tmp == rsf_val_${label.enumName}_${label.valueName}"
            is SwitchLabel.IntConst -> if (isLong) "($tmp == ${label.value}LL)" else "($tmp == ${label.value})"
            is SwitchLabel.StringConst -> "rsf_str_eq($tmp, ${stringLit(label.value)})"
        }

    private fun expr(e: Expr): String = when (e) {
        is IntLit -> e.value.toString()
        is LongLit -> "${e.value}LL"
        is CharLit -> e.value.code.toString() + "u"
        is StringLit -> stringLit(e.value)
        is BoolLit -> if (e.value) "true" else "false"
        is VarExpr -> {
            val field = model.classModel.fieldReads[e]
            if (field != null) fieldRef("self", field.className, e.name) else paramName(e.name)
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
        // super.f(...)：**非虚**调用，直接打声明类那一份实现
        if (e.receiver is SuperExpr) {
            return when (val call = model.classModel.superCalls[e]) {
                is SuperCall.Rsf -> {
                    val name = methodNames[call.decl] ?: fail("方法命名")
                    val args = (e.arguments ?: emptyList()).joinToString(", ") { expr(it) }
                    "$name(self${if (args.isEmpty()) "" else ", $args"})"
                }
                is SuperCall.Jvm -> fail("super 调 JDK 方法")
                null -> fail("super 成员访问 '${e.name}'")
            }
        }
        if (e.name == "length" && e.arguments == null) return "(${expr(e.receiver)}.len)"
        // 阶段 58a：String 的成员方法（UI 的每个 label 都要量宽度）
        // 枚举常量/类型名一类的接收者没有类型标注（`Color.Green` 的 `Color`），
        // 所以这里只做「能问出类型才问」的探测。
        val recvType = runCatching { model.typeOf(e.receiver) }.getOrNull()
        if (recvType != null && recvType.base == RsfBase.STRING && e.arguments != null) {
            val recv = expr(e.receiver)
            val args = e.arguments!!.map { expr(it) }
            when (e.name) {
                "length" -> return "rsf_str_len($recv)"
                "isEmpty" -> return "rsf_str_empty($recv)"
                "charAt" -> return "rsf_str_char_at($recv, ${args[0]})"
                "substring" -> return if (args.size == 1) {
                    "rsf_str_substr($recv, ${args[0]}, rsf_str_len($recv))"
                } else {
                    "rsf_str_substr($recv, ${args[0]}, ${args[1]})"
                }
                "equals" -> return "rsf_str_eq($recv, ${args[0]})"
                "startsWith" -> return "rsf_str_starts($recv, ${args[0]})"
                "endsWith" -> return "rsf_str_ends($recv, ${args[0]})"
                "contains" -> return "rsf_str_contains($recv, ${args[0]})"
                "toUpperCase" -> return "rsf_str_upper($recv)"
                "toLowerCase" -> return "rsf_str_lower($recv)"
            }
        }
        val enumValue = model.enumValues[e]
        if (enumValue != null) return "rsf_val_${enumValue.enumName}_${enumValue.valueName}"
        val receiver = expr(e.receiver)
        return when (val resolved = model.classModel.members[e]) {
            is UserMember.Field -> fieldRef(receiver, resolved.className, e.name)
            is UserMember.Method -> {
                val name = methodNames[resolved.decl] ?: fail("方法命名")
                val args = e.arguments ?: emptyList()
                if (resolved.decl.isStatic) {
                    "$name(${args.joinToString(", ") { expr(it) }})"
                } else {
                    dispatchCall(receiver, resolved.decl, args.map { expr(it) })
                }
            }
            else -> fail("成员访问 '${e.name}'")
        }
    }

    /** 虚分派：按「名字 + 参数个数」查槽位，从对象的虚表里取实现再调。 */
    private fun dispatchCall(receiver: String, decl: FuncDecl, args: List<String>): String {
        val slot = slotOf(decl.name, args.size)
        val params = (listOf("void*") + paramTypesOf(decl).map { cType(it) }).joinToString(", ")
        val ret = cType(returnType(decl))
        val call = (listOf(receiver) + args).joinToString(", ")
        return "(($ret (*)($params))(*(void***)($receiver))[$slot])($call)"
    }

    private fun slotOf(name: String, arity: Int): Int =
        slots[name + "/" + arity] ?: fail("虚方法槽位（$name/$arity）")

    private fun newArray(e: NewArrayExpr): String {
        if (e.dimensions.size != 1) fail("多维数组")
        val base = baseOf(e.elementType)
        val type = if (base == RsfBase.EXTERNAL) {
            RsfType(base, externalName = e.elementType.name, ptrDepth = e.elementType.ptrDepth)
        } else {
            RsfType(base, ptrDepth = e.elementType.ptrDepth)
        }
        val elemSize = elemCType(type)
        return "rsf_arr_new(${expr(e.dimensions[0])}, (int64_t)sizeof($elemSize))"
    }

    private fun baseOf(ref: TypeRef): RsfBase = when (ref.name) {
        "Int" -> RsfBase.INT
        "Long" -> RsfBase.LONG
        "Bool" -> RsfBase.BOOL
        "String" -> RsfBase.STRING
        "Char" -> RsfBase.CHAR
        "Byte" -> RsfBase.BYTE
        else -> {
            if (classes.containsKey(ref.name) || model.enums.containsKey(ref.name) ||
                model.interfaces.containsKey(ref.name)
            ) RsfBase.EXTERNAL
            else fail("数组元素类型 '${ref.name}'")
        }
    }

    private fun unary(e: UnaryExpr): String {
        val operand = expr(e.operand)
        return when (e.op) {
            // 阶段 53：取地址 / 解引用——C 里就是同形写法，零成本
            TokenKind.AMP -> "(&$operand)"
            TokenKind.STAR -> "(*$operand)"
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
            // 阶段 58a：拼接时非字符串侧要转成字符串（`bool + " "`、`Int + "x"` 在解释器里都成立）
            TokenKind.PLUS -> if (stringy) {
                "rsf_concat(${asStr(e.left, left)}, ${asStr(e.right, right)})"
            } else {
                "($left + $right)"
            }
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

    /** 拼接里的一个操作数：字符串原样，数值/布尔走对应的 `rsf_*_to_str`。 */
    private fun asStr(operand: org.example.complie.ast.Expr, code: String): String =
        when (model.typeOf(operand).base) {
            RsfBase.STRING -> code
            RsfBase.BOOL -> "rsf_bool_to_str($code)"
            RsfBase.LONG -> "rsf_long_to_str($code)"
            RsfBase.INT -> "rsf_int_to_str($code)"
            else -> fail("字符串拼接里的 ${model.typeOf(operand).base.rsfName}")
        }

    private fun call(e: CallExpr): String {
        if (e.name == "print" || e.name == "println") return printCall(e)
        val implicit = model.classModel.implicitCalls[e]
        if (implicit != null) {
            val name = methodNames[implicit.decl] ?: fail("方法命名")
            return if (implicit.decl.isStatic) {
                "$name(${e.arguments.joinToString(", ") { expr(it) }})"
            } else {
                dispatchCall("self", implicit.decl, e.arguments.map { expr(it) })
            }
        }
        when (e.name) {
            "intToString" -> return "rsf_int_to_str(${expr(e.arguments[0])})"
            "charToInt" -> return "((int32_t)${expr(e.arguments[0])})"
            "toChar" -> return "((uint16_t)${expr(e.arguments[0])})"
            "toByte" -> return "((int8_t)${expr(e.arguments[0])})"
        }
        // 省掉 `new` 的构造（`Point(1, 2)`）在语义模型里和 NewExpr 是同一件事，
        // 这里按 newTargets 走构造器命名，否则会掉到「调用 'Point'」去报错。
        val ctorClass = model.classModel.newTargets[e]
        if (ctorClass != null) {
            val ctor = model.classModel.newCtors[e]
            val ctorName = if (ctor != null) {
                ctorNames[ctor] ?: fail("构造器命名")
            } else {
                defaultCtorNames[ctorClass] ?: fail("默认构造器命名")
            }
            return "$ctorName(${e.arguments.joinToString(", ") { expr(it) }})"
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

    // ------------------------------------------------------------------ JNI 桥（阶段 60b）

    /**
     * 生成 `Java_<类>_<方法>` 形式的 JNI 桥：JVM 侧的 `native` 方法调到这里，
     * 这里再调 `--c --lib` 出的 `rsf_fn_*`。
     *
     * 只支持基本类型与 `String`：对象跨边界要用句柄，v1 不开放。
     */
    fun emitJni(internalName: String): String {
        val sb = StringBuilder()
        sb.append("/* 由 rsf build --c --lib --jni 生成：JVM -> native 的 JNI 桥，别手改 */").append(NL)
        sb.append("#include <jni.h>").append(NL)
        sb.append("#include <stdint.h>").append(NL)
        sb.append("#include <stdbool.h>").append(NL)
        sb.append("#include <stdlib.h>").append(NL)
        sb.append("#include <string.h>").append(NL).append(NL)
        sb.append("#ifndef RSF_STR_DEFINED").append(NL)
        sb.append("#define RSF_STR_DEFINED").append(NL)
        sb.append("typedef struct { int64_t len; const char* data; } rsf_str;").append(NL)
        sb.append("#endif").append(NL).append(NL)

        val natives = model.functions.filter { it.target == BuildTarget.NATIVE }
        for (fn in natives) {
            sb.append("extern ").append(cType(model.returnTypeOf(fn)))
                .append(" rsf_fn_").append(fn.name).append("(")
            val ps = model.paramTypesOf(fn)
            sb.append(ps.joinToString(", ") { cType(it) })
            sb.append(");").append(NL)
        }
        sb.append(NL)

        // JNI 符号名不是「把分隔符换成下划线」：类名里本来就有 `_` 时要转义成 `_1`，
        // 否则 `jni_basic` 会去找 `Java_jni_basic_add`，而 JVM 实际要的是 `Java_jni_1basic_add`。
        val cls = internalName.replace('.', '/').replace("_", "_1").replace('/', '_')
        for (fn in natives) {
            val ps = model.paramTypesOf(fn)
            val ret = model.returnTypeOf(fn)
            sb.append("JNIEXPORT ").append(jniType(ret)).append(" JNICALL Java_")
                .append(cls).append('_').append(fn.name).append("(JNIEnv* env, jclass cls")
            for (i in ps.indices) sb.append(", ").append(jniType(ps[i])).append(" j").append(i)
            sb.append(") {").append(NL)
            sb.append("    (void)env; (void)cls;").append(NL)
            for ((i, t) in ps.withIndex()) {
                when (t.base) {
                    RsfBase.STRING -> {
                        sb.append("    const char* c").append(i)
                            .append(" = (*env)->GetStringUTFChars(env, j").append(i).append(", 0);").append(NL)
                        sb.append("    rsf_str a").append(i)
                            .append("; a").append(i).append(".len = (int64_t)strlen(c").append(i)
                            .append("); a").append(i).append(".data = c").append(i).append(";").append(NL)
                    }
                    else -> sb.append("    ").append(cType(t)).append(" a").append(i)
                        .append(" = (").append(cType(t)).append(")j").append(i).append(";").append(NL)
                }
            }
            val args = ps.indices.joinToString(", ") { "a" + it }
            when (ret.base) {
                RsfBase.VOID -> sb.append("    rsf_fn_").append(fn.name).append("(").append(args).append(");").append(NL)
                RsfBase.STRING -> {
                    sb.append("    rsf_str r = rsf_fn_").append(fn.name).append("(").append(args).append(");").append(NL)
                    sb.append("    char* t = (char*)malloc((size_t)r.len + 1);").append(NL)
                    sb.append("    if (r.len > 0) memcpy(t, r.data, (size_t)r.len);").append(NL)
                    sb.append("    t[r.len] = 0;").append(NL)
                    sb.append("    jstring out = (*env)->NewStringUTF(env, t);").append(NL)
                    sb.append("    free(t);").append(NL)
                }
                else -> sb.append("    ").append(cType(ret)).append(" r = rsf_fn_").append(fn.name)
                    .append("(").append(args).append(");").append(NL)
            }
            for ((i, t) in ps.withIndex()) {
                if (t.base == RsfBase.STRING) {
                    sb.append("    (*env)->ReleaseStringUTFChars(env, j").append(i).append(", c").append(i).append(");").append(NL)
                }
            }
            when (ret.base) {
                RsfBase.VOID -> sb.append("    return;").append(NL)
                RsfBase.STRING -> sb.append("    return out;").append(NL)
                else -> sb.append("    return (").append(jniType(ret)).append(")r;").append(NL)
            }
            sb.append("}").append(NL).append(NL)
        }
        if (natives.isEmpty()) {
            sb.append("/* 这个程序里没有 native fn，桥是空的 */").append(NL)
        }
        return sb.toString()
    }

    /** JNI 头的参数/返回类型（对象只开放 String）。 */
    private fun jniType(t: RsfType): String {
        if (t.arrayDepth != 0 || t.ptrDepth != 0) {
            throw RsfError("native", "JNI 桥只支持基本类型与 String，不支持 ${t.rsfName}（数组/指针要用句柄传，v1 没做）")
        }
        return jniTypeOf(t)
    }

    private fun jniTypeOf(t: RsfType): String = when (t.base) {
        RsfBase.VOID -> "void"
        RsfBase.INT -> "jint"
        RsfBase.LONG -> "jlong"
        RsfBase.BOOL -> "jboolean"
        RsfBase.BYTE -> "jbyte"
        RsfBase.CHAR -> "jchar"
        RsfBase.STRING -> "jstring"
        else -> throw RsfError(
            "native",
            "JNI 桥只支持 Int / Long / Bool / Byte / Char / String 与 void，不支持 ${t.rsfName}",
        )
    }

    companion object {
        /** 单文件运行时：程序与这些辅助函数编在一起，不依赖任何 rsf 运行时库。 */
        private val RUNTIME = """
            /* 由 rsf build --c 生成（阶段 52 的 C 后端，native 线第一刀） */
            #include <stdint.h>
            #include <stdbool.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>

            #ifndef RSF_STR_DEFINED
            #define RSF_STR_DEFINED
            typedef struct { int64_t len; const char* data; } rsf_str;
            #endif
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

            // 阶段 58a：String 的成员方法（按 UTF-8 字节算，ASCII 之外一个汉字算 3 个）。
            static int32_t rsf_str_len(rsf_str s) { return (int32_t)s.len; }

            static uint16_t rsf_str_char_at(rsf_str s, int32_t i) {
                if (i < 0 || (int64_t)i >= s.len) return 0;
                return (uint16_t)((const unsigned char*)s.data)[i];
            }

            static rsf_str rsf_str_substr(rsf_str s, int32_t from, int32_t to) {
                if (from < 0) from = 0;
                if (to > s.len) to = (int32_t)s.len;
                if (to < from) to = from;
                rsf_str r; r.len = to - from; r.data = (char*)s.data + from; return r;
            }

            static bool rsf_str_starts(rsf_str s, rsf_str p) {
                return p.len <= s.len && memcmp(s.data, p.data, (size_t)p.len) == 0;
            }

            static bool rsf_str_ends(rsf_str s, rsf_str p) {
                return p.len <= s.len && memcmp((char*)s.data + (s.len - p.len), p.data, (size_t)p.len) == 0;
            }

            static bool rsf_str_contains(rsf_str s, rsf_str p) {
                if (p.len == 0) return true;
                if (p.len > s.len) return false;
                for (int64_t i = 0; i + p.len <= s.len; i++) {
                    if (memcmp((char*)s.data + i, p.data, (size_t)p.len) == 0) return true;
                }
                return false;
            }

            static rsf_str rsf_str_upper(rsf_str s) {
                char* p = (char*)malloc((size_t)(s.len > 0 ? s.len : 1));
                for (int64_t i = 0; i < s.len; i++) {
                    char c = ((const char*)s.data)[i];
                    p[i] = (c >= 'a' && c <= 'z') ? (char)(c - 32) : c;
                }
                rsf_str r; r.len = s.len; r.data = p; return r;
            }

            static rsf_str rsf_str_lower(rsf_str s) {
                char* p = (char*)malloc((size_t)(s.len > 0 ? s.len : 1));
                for (int64_t i = 0; i < s.len; i++) {
                    char c = ((const char*)s.data)[i];
                    p[i] = (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c;
                }
                rsf_str r; r.len = s.len; r.data = p; return r;
            }

            static bool rsf_str_empty(rsf_str s) { return s.len == 0; }

            static rsf_str rsf_bool_to_str(bool v) {
                return v ? rsf_str_lit("true", 4) : rsf_str_lit("false", 5);
            }

            static rsf_str rsf_long_to_str(int64_t v) {
                char* buf = (char*)malloc(32);
                int n = snprintf(buf, 32, "%lld", (long long)v);
                rsf_str s; s.len = n; s.data = buf; return s;
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
