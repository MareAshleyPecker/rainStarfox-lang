package org.example.complie.native

import org.example.complie.ast.*
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
class CBackend(
    private val model: SemanticModel,
    private val withEntry: Boolean = true,
    // fix: 多文件 build 时，全程序恰好一个实例传 true，由它 #define RSF_RUNTIME_IMPL
    // 提供共享状态（错误旗标/内存登记簿）的唯一定义；其余实例保持 false。
    private val emitImpl: Boolean = false,
) {

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
    /** try 帧的编号。 */
    private var trySeq = 0
    /** 当前函数里有 try：局部变量要加 volatile（longjmp 之后寄存器值不可信）。 */
    private var volatileLocals = false
    /** 正在发射的 try 嵌套深度：return 时要把 handler 栈弹回去。 */
    private var tryDepth = 0
    private var tempSeq = 0

    /** 用到 `jvmCall` 才把「起 JVM」那段运行时编进来（不用的人不背 windows.h/jni.h）。 */
    private var usesJvmCall = false
    private var usesFile = false

    fun emit(): String {
        checkProgram()
        nameEverything()
        val sb = StringBuilder()
        // fix: 运行时拆到 rsf_runtime.h，不再随每份生成代码携带 RUNTIME + RuntimeErase 全文。
        // 多文件一起 build 只出一个 lib.h（实际文件名 rsf_runtime.h），多个 .c 共同包含它；
        // 共享状态走 extern + 单一定义，全程序恰好一个编译单元 define RSF_RUNTIME_IMPL。
        // 头文件由构建侧调 ensureRuntimeLib(outDir) 写出（内容不同才重写）；
        // emitImpl=true 交给其中**一个**实例。RuntimeErase 仍被 emitJni 使用，故常量保留。
        // gcc -std=c11 会把 __STRICT_ANSI__ 打开，dirent/stat 里的 S_IFREG、readdir 等
        // 都被藏起来。生成的代码要用它们（File shim），所以在任何 include 之前把扩展打开。
        // 只对非 Windows 生效；_GNU_SOURCE 在 glibc / musl 上都等价于「全开」。
        sb.append("#ifndef _WIN32\n")
        sb.append("#  ifndef _GNU_SOURCE\n")
        sb.append("#    define _GNU_SOURCE 1\n")
        sb.append("#  endif\n")
        sb.append("#endif\n")
        if (emitImpl) sb.append("#define RSF_RUNTIME_IMPL\n")
        sb.append("#include \"rsf_runtime.h\"\n")
        val runtimeEnd = sb.length
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
        if (usesJvmCall) sb.insert(runtimeEnd, JVM_RUNTIME)
        if (usesFile) sb.insert(runtimeEnd, FILE_RUNTIME)
        if (!withEntry) {
            // --c --lib：不发 main，宿主自己声明/调用导出的 rsf_fn_* 函数
            return sb.toString()
        }
        val entry = model.entry ?: throw RsfError("native", "没有 main 函数，C 后端不知道从哪开始跑")
        sb.append("int main(void) {\n")
        if (model.gcEnabled) {
            // 先发布根区间（锚点必须落在 main 自己的帧上），再换分配器：顺序反了的话
            // install 之后的第一次分配就已经在 GC 管理的堆上了。
            sb.append("    if (!rsf_gc_thread_attached()) rsf_gc_thread_attach();\n")
            sb.append("    rsf_gc_heap_install();\n")
        }
        if (returnType(entry).base == RsfBase.VOID) {
            sb.append("    ").append(fnName(entry)).append("();\n")
            sb.append("    return 0;\n")
        } else {
            sb.append("    return (int)").append(fnName(entry)).append("();\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * fix: 运行时头文件 rsf_runtime.h 的唯一来源（RUNTIME + RuntimeErase 仍是单一事实源）。
     * 机械变换四步：
     *   1) 去掉两行文件级 error 变量（改由 ctx 持有，extern + RSF_RUNTIME_IMPL 提供唯一份）；
     *   2) 顶层 static 函数 -> static inline（多 TU 包含安全、消 unused-function 告警）；
     *   3) malloc/calloc -> rsf_malloc/rsf_calloc（走分配器；realloc 已全部改成 rsf_grow）；
     *   4) 在 rsf_arr_new 前插入「分配器 + 上下文 + arena」实现。
     * 第 3 步之后有一道守卫：body 里若还残留裸 malloc/calloc/realloc/free 就直接抛错。
     * C 侧的改动可用 build/verify69/gen_runtime.py 反推同一份头文件，只靠 gcc 验证。
     */
    fun runtimeLibSource(): String {
        var body = RUNTIME + RuntimeErase
        body = body.replace("static bool rsf_err = false;\n", "")
        body = body.replace("static rsf_str rsf_err_msg;\n", "")
        body = body.replace(Regex("(?m)^static "), "static inline ")
        body = body.replace("malloc(", "rsf_malloc(")
        body = body.replace("calloc(", "rsf_calloc(")
        // 分配点必须全部走分配器：漏一个（例如又冒出 realloc）就会绕过 ctx 的 arena，
        // 在 --lib / 嵌入模式下变成「宿主的分配器看不见的块」。宁可在这里炸。
        // 注意用词边界：rsf_malloc( 里的 malloc( 不算漏。
        val raw = Regex("(?<![A-Za-z0-9_])(malloc|calloc|realloc|free)\\(")
        val hit = raw.find(body)
        check(hit == null) {
            "运行时里还有没走分配器的内存调用：'${hit?.value}'（偏移 ${hit?.range?.first}）"
        }
        val marker = "static inline rsf_arr rsf_arr_new"
        val idx = body.indexOf(marker)
        check(idx >= 0) { "运行时里找不到 rsf_arr_new，无法定位共享状态插入点" }
        val head = body.substring(0, idx)
        val tail = body.substring(idx)
        return LIB_PREFIX + head + SHARED_STATE + FIX_FUNCS + tail + "#endif /* RSF_RUNTIME_LIB_H */\n"
    }

    /**
     * 把 rsf_runtime.h 写进 out 目录（多文件 build 只出一个 lib.h）。
     *
     * 判定标准从「存在就跳过」改成「**内容不同就重写**」：运行时是编译器的一部分，
     * 升级编译器却沿用 out 里的旧头文件，会得到最难查的一类失败（生成代码与运行时
     * 不同源、链接期才炸或运行期静默错）。内容相同才跳过，这样多文件 build 依然只写一次。
     *
     * 返回本次是否真正写出。注意这与「哪个 .c 定义 RSF_RUNTIME_IMPL」无关——
     * 后者每次 build 仍须恰好一个（构建侧给其中一个 CBackend 传 emitImpl=true）。
     */
    fun ensureRuntimeLib(outDir: java.io.File): Boolean {
        val f = java.io.File(outDir, "rsf_runtime.h")
        val want = runtimeLibSource()
        if (f.exists() && f.readText() == want) return false
        outDir.mkdirs()
        f.writeText(want)
        return true
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
        // 泛型接口/类一律擦除（类型参数当成 void* 的盒子，见 cType 的 TYPE_PARAM 分支）。
        for (list in model.superInterfaces.values) {
            for (sup in list) {
                if (sup.fromClassPath) fail("实现 JDK 接口（${sup.name}）")
            }
        }
        for (fn in model.functions) {
            if (model.functionTable[fn.name]?.size ?: 0 > 1) fail("重载函数（${fn.name}）")
        }
        for (cls in classes.values) {
            for (fn in cls.methods) if (fn.body == null) fail("无方法体（${fn.name}）")
        }
    }

    /** `fail` 提前返回时给一个零值：类型不匹配会编译失败，所以按当前函数返回类型挑一个。 */
    private fun returnZero(type: RsfType?): String {
        if (type == null) return "return;"
        return when (type.base) {
            RsfBase.VOID -> "return;"
            RsfBase.INT, RsfBase.LONG, RsfBase.CHAR, RsfBase.BYTE -> "return 0;"
            RsfBase.BOOL -> "return false;"
            RsfBase.FLOAT -> "return 0.0f;"
            RsfBase.DOUBLE -> "return 0.0;"
            RsfBase.STRING -> "return rsf_str_null();"
            RsfBase.TYPE_PARAM -> "return rsf_val_null();"
            else -> "return NULL;"
        }
    }

    private fun fail(what: String): Nothing =
        throw RsfError("native", "C 后端暂不支持：$what（阶段 52 的 C 后端只接了 int/long/bool/string/数组/类）")

    /** 语句树里有没有 try（含嵌套块）。决定局部变量要不要 volatile。 */
    private fun containsTry(st: Stmt?): Boolean = when (st) {
        null -> false
        is TryStmt -> true
        is Block -> st.statements.any { containsTry(it) }
        is IfStmt -> containsTry(st.body) || containsTry(st.elseBody)
        is WhileStmt -> containsTry(st.body)
        is ForStmt -> containsTry(st.body) || containsTry(st.step)
        is SwitchStmt -> st.cases.any { containsTry(it.body) } || containsTry(st.elseBody)
        else -> false
    }

    private fun hasTry(fn: FuncDecl): Boolean = fn.body?.statements?.any { containsTry(it) } == true

    // ------------------------------------------------------------------ 类型

    /** 「现在这个位置期望的类型」：只用于把 `null` 字面量落成对的 C 表示。 */
    private var expectedType: RsfType? = null

    private val JDK_CLASSES = setOf("Integer", "Long", "Float", "Double", "Math", "String", "System")
    private var currentReturn: RsfType? = null

    /**
     * 阶段 80：正在发**扩展函数**的体（`fn Int.twice()`）。
     *
     * 那种函数里 `this` 是 0 号形参（接收者），C 侧名字是 `v_this`，不是类方法的 `self`。
     */
    private var extensionFn = false

    private fun returnType(fn: FuncDecl): RsfType =
        model.returnTypes[fn] ?: model.classModel.methodReturns[fn] ?: RsfType.VOID

    /** 参数类型：顶层函数在 `model.paramTypes`，类方法/构造器在 `classModel.methodParams` 里。 */
    private fun paramTypesOf(fn: FuncDecl): List<RsfType> =
        model.paramTypes[fn] ?: model.classModel.methodParams[fn]?.map { it.type } ?: emptyList()

    /** 实参按形参类型给期望（目前只为 `null` 字面量服务）。 */
    private fun argsWithExpected(args: List<Expr>, types: List<RsfType>): String =
        args.mapIndexed { i, a ->
            val saved = expectedType
            expectedType = types.getOrNull(i)
            val code = expr(a)
            expectedType = saved
            val want = types.getOrNull(i) ?: return@mapIndexed code
            adapt(want, physicalType(a), code)
        }.joinToString(", ")

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
            RsfBase.FLOAT -> "float"
            RsfBase.DOUBLE -> "double"
            RsfBase.CHAR -> "uint16_t"
            RsfBase.BYTE -> "int8_t"
            RsfBase.EXTERNAL -> classPointer(type)
            RsfBase.TYPE_PARAM -> "rsf_val"
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
        if (name == "java/io/File" || name == "File") return "void*"
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
        sb.append("};").append(NL)
        // 枚举是 int：拼接/打印时要还原成源码里的名字，和解释器、JVM 两端一致。
        sb.append("static const char* rsf_enum_name_${decl.name}(int32_t v) {").append(NL)
        sb.append("    switch (v) {").append(NL)
        decl.values.forEachIndexed { i, v ->
            sb.append("        case ").append(i).append(": return \"").append(v.name).append("\";").append(NL)
        }
        sb.append("        default: return \"?\";").append(NL)
        sb.append("    }").append(NL)
        sb.append("}").append(NL).append(NL)
        return sb.toString()
    }

    /** 表达式的静态类型如果是枚举，返回那个枚举声明。 */
    private fun enumOf(t: RsfType): EnumDecl? {
        val n = t.rsfName.ifEmpty { t.externalName ?: "" }
        return model.enums[n] ?: model.enums[n.substringAfterLast('/')]
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
        val v = if (volatileLocals) "volatile " else ""
        val list = fn.params.mapIndexed { i, p -> "$v${cType(params[i])} ${paramName(p.name)}" }
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
        val withTry = hasTry(fn)
        volatileLocals = withTry
        tryDepth = 0
        val h0 = if (withTry) "    rsf_handler* _rsf_h0 = rsf_ctx_get()->handler;\n" else ""
        val savedReturn = currentReturn
        currentReturn = returnType(fn)
        val sb = StringBuilder()
        if (iface != null) {
            sb.append(ifaceProto(iface, fn)).append(" {").append(NL).append(h0)
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("}").append(NL).append(NL)
            currentClass = null
            return sb.toString()
        }
        if (cls == null) {
            // 阶段 80：扩展函数（`fn Int.twice()`）里 `this` 是 0 号形参 `v_this`
            val savedExtension = extensionFn
            extensionFn = fn.receiverType != null
            sb.append(protoTop(fn)).append(" {\n").append(h0)
            fn.body?.statements?.forEach { sb.append(stmt(it, 1)) }
            sb.append("}\n\n")
            extensionFn = savedExtension
            currentClass = null
            return sb.toString()
        }
        if (fn.isConstructor) {
            sb.append(ctorInitDefinition(cls, fn))
            sb.append(ctorNewDefinition(cls, fn))
            currentClass = null
            return sb.toString()
        }
        sb.append(protoMember(cls, fn)).append(" {\n").append(h0)
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
        if (hasTry(fn)) sb.append("    rsf_handler* _rsf_h0 = rsf_ctx_get()->handler;").append(NL)
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
                    .append(adapt(fieldTypeOf(cls, f), physicalType(f.initializer), expr(f.initializer))).append(';').append(NL)
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
        // fix: 对象分配走 rsf_calloc，纳入共享内存登记簿（退出集中回收）
        sb.append("    void* self = rsf_calloc(1, sizeof(rsf_cls_${cls.name}));").append(NL)
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
                .append(adapt(fieldTypeOf(cls, f), physicalType(f.initializer), expr(f.initializer))).append(';').append(NL)
        }
        sb.append("}").append(NL).append(NL)
        sb.append("void* $name(void) {").append(NL)
        // fix: 对象分配走 rsf_calloc，纳入共享内存登记簿（退出集中回收）
        sb.append("    void* self = rsf_calloc(1, sizeof(rsf_cls_${cls.name}));").append(NL)
        sb.append("    *(void***)self = rsf_vtbl_${cls.name};").append(NL)
        sb.append("    $init(self);").append(NL)
        sb.append("    return self;").append(NL).append("}").append(NL).append(NL)
        currentClass = null
        return sb.toString()
    }

    // ------------------------------------------------------------------ 函数名

    private fun fnName(fn: FuncDecl): String = "rsf_fn_" + fn.name

    private fun paramName(name: String): String = "v_" + name

    // ------------------------------------------------------------------ 泛型擦除
    // 类型参数 T 在 C 里是 rsf_val（带标签的盒子）：写进泛型字段/数组/参数时装箱，
    // 读出来落到具体类型时拆箱。只在本文件做，前端与自举侧不受影响。

    private val RuntimeErase = """
/* ---- 泛型擦除：类型参数的值盒子 ---- */
typedef struct { int64_t tag; int32_t i; int64_t l; void* p; rsf_str s; double d; } rsf_val;
static rsf_val rsf_box_int(int32_t v) { rsf_val b; b.tag = 1; b.i = v; b.l = 0; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; return b; }
static rsf_val rsf_box_long(int64_t v) { rsf_val b; b.tag = 2; b.i = 0; b.l = v; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; return b; }
static rsf_val rsf_box_bool(bool v) { rsf_val b; b.tag = 3; b.i = v ? 1 : 0; b.l = 0; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; return b; }
static rsf_val rsf_box_double(double v) { rsf_val b; b.tag = 7; b.i = 0; b.l = 0; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; b.d = v; b.l = 0; return b; }
static rsf_val rsf_box_char(uint16_t v) { rsf_val b; b.tag = 4; b.i = v; b.l = 0; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; return b; }
static rsf_val rsf_val_null(void) { rsf_val b; b.tag = 0; b.i = 0; b.l = 0; b.p = 0; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; b.d = 0; return b; }
static rsf_val rsf_box_str(rsf_str v) { rsf_val b; b.tag = 5; b.i = 0; b.l = 0; b.p = 0; b.s = v; return b; }
static rsf_val rsf_box_ptr(void* v) { rsf_val b; b.tag = 6; b.i = 0; b.l = 0; b.p = v; b.s.len = 0; b.s.units1 = 0; b.s.data = 0; return b; }
static int32_t rsf_unbox_int(rsf_val b) { return b.i; }
static int64_t rsf_unbox_long(rsf_val b) { return b.l; }
static bool rsf_unbox_bool(rsf_val b) { return b.i != 0; }
static double rsf_unbox_double(rsf_val b) { return b.d; }
static uint16_t rsf_unbox_char(rsf_val b) { return (uint16_t)b.i; }
static rsf_str rsf_unbox_str(rsf_val b) { return b.s; }
static void* rsf_unbox_ptr(rsf_val b) { return b.p; }
"""

    private fun boxed(type: RsfType, code: String): String =
        if (type.nullable && type.base != RsfBase.STRING && type.base != RsfBase.EXTERNAL) code else
        when (type.base) {
            RsfBase.INT -> "rsf_box_int($code)"
            RsfBase.LONG -> "rsf_box_long($code)"
            RsfBase.BOOL -> "rsf_box_bool($code)"
            RsfBase.FLOAT -> "rsf_box_double((double)$code)"
            RsfBase.DOUBLE -> "rsf_box_double($code)"
            RsfBase.CHAR -> "rsf_box_char($code)"
            RsfBase.BYTE -> "rsf_box_int($code)"
            RsfBase.STRING -> "rsf_box_str($code)"
            RsfBase.EXTERNAL -> "rsf_box_ptr($code)"
            else -> code
        }

    private fun unboxed(type: RsfType, code: String): String = when (type.base) {
        RsfBase.INT -> "rsf_unbox_int($code)"
        RsfBase.LONG -> "rsf_unbox_long($code)"
        RsfBase.BOOL -> "rsf_unbox_bool($code)"
        RsfBase.FLOAT -> "(float)rsf_unbox_double($code)"
        RsfBase.DOUBLE -> "rsf_unbox_double($code)"
        RsfBase.CHAR -> "rsf_unbox_char($code)"
        RsfBase.BYTE -> "rsf_unbox_int($code)"
        RsfBase.STRING -> "rsf_unbox_str($code)"
        RsfBase.EXTERNAL -> "rsf_unbox_ptr($code)"
        else -> code
    }

    /** 表达式在 C 里的**物理**类型：泛型方法/字段的返回与读取是盒子，即使前端已把它替换成 Int。 */
    private fun physicalType(e: Expr): RsfType = when (e) {
        is CallExpr -> model.callTargets[e]?.let { returnType(it) } ?: model.typeOf(e)
        is MemberExpr -> when (val m = model.classModel.members[e]) {
            is UserMember.Field -> m.type
            // 方法调用的物理类型看**声明**（泛型方法的 T 在 C 里是 rsf_val 盒子）
            is UserMember.Method -> returnType(m.decl)
            else -> model.typeOf(e)
        }
        else -> model.typeOf(e)
    }

    /** 期望是具体类型、拿到的是盒子 ⇒ 拆箱；期望是类型参数、拿到具体值 ⇒ 装箱。 */
    private fun adapt(want: RsfType, have: RsfType, code: String): String = when {
        // null 实参：按形参类型落成对应表示（String 是结构体，不能直接塞 NULL）
        want.base == RsfBase.STRING && (have.base == RsfBase.NULL || have.isPointer || have.base == RsfBase.EXTERNAL) && code == "NULL" -> "rsf_str_null()"
        want.base == RsfBase.TYPE_PARAM && have.base != RsfBase.TYPE_PARAM -> boxed(have, code)
        want.base != RsfBase.TYPE_PARAM && have.base == RsfBase.TYPE_PARAM -> unboxed(want, code)
        else -> code
    }

    // ------------------------------------------------------------------ 语句

    private fun stmt(s: Stmt, indent: Int): String = when (s) {
        is VarDeclStmt -> {
            val type = model.typeOf(s)
            val v = if (volatileLocals) "volatile " else ""
            pad(indent) + "$v${cType(type)} ${paramName(s.name)} = ${expr(s.initializer)};\n"
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
            // 阶段 79 v2：字段写了 `{ set }` 时 `p.hp = v` 摊成 `p.setHp(v)`
            val accessor = model.propertyWrites[s]
            if (accessor != null) {
                val name = methodNames[accessor.decl] ?: fail("属性 setter 命名")
                val want = paramTypesOf(accessor.decl).firstOrNull() ?: RsfType.INT
                pad(indent) + name + "(" + expr(s.target.receiver) + ", " +
                    adapt(want, physicalType(s.value), expr(s.value)) + ");\n"
            } else {
                val field = model.classModel.fieldWrites[s] ?: fail("成员赋值")
                pad(indent) + fieldRef(expr(s.target.receiver), field.className, s.target.name) +
                    " = " + expr(s.value) + ";\n"
            }
        }
        is SuperCallStmt, is ThisCallStmt -> ""
        // 阶段 66：fail "..." → 置错误旗标并立刻从当前函数返回（错误由 try/catch 或顶层兜底）
        is FailStmt -> pad(indent) + "rsf_set_err(" + expr(s.message) + "); rsf_throw(); " +
            returnZero(currentReturn) + NL
        is IndexAssignStmt -> {
            val target = s.target
            val elem = elemCType(elementOf(model.typeOf(target.receiver)))
            val want = elementOf(model.typeOf(target.receiver))
            pad(indent) + "(($elem*)" + expr(target.receiver) + ".data)[" + expr(target.index) + "] = " +
                adapt(want, physicalType(s.value), expr(s.value)) + ";" + NL
        }
        is ExprStmt -> pad(indent) + expr(s.expr) + ";\n"
        is ReturnStmt -> {
            val pop = if (tryDepth > 0) "rsf_ctx_get()->handler = _rsf_h0; " else ""
            val value = s.value
            if (value == null) {
                pad(indent) + pop + "return;\n"
            } else {
                expectedType = currentReturn
                val code = expr(value)
                pad(indent) + pop + "return $code;\n"
            }
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
        is BreakStmt -> {
            if (tryDepth > 0) fail("try 块里的 break（第 ${s.at.line} 行）")
            pad(indent) + "break;\n"
        }
        is ContinueStmt -> {
            if (tryDepth > 0) fail("try 块里的 continue（第 ${s.at.line} 行）")
            pad(indent) + "continue;\n"
        }
        is TryStmt -> {
            val sb = StringBuilder()
            val h = "h_${trySeq++}"
            sb.append(pad(indent)).append("rsf_handler ").append(h).append(";\n")
            sb.append(pad(indent)).append(h).append(".prev = rsf_ctx_get()->handler;\n")
            sb.append(pad(indent)).append("rsf_ctx_get()->handler = &").append(h).append(";\n")
            sb.append(pad(indent)).append("if (setjmp(").append(h).append(".jb) == 0) {\n")
            tryDepth++
            for (inner in s.body.statements) {
                sb.append(stmt(inner, indent + 1))
                // 内置函数的失败不会自己跳：走到这里还挂着 rsf_err 就补一次 throw
                sb.append(pad(indent + 1)).append("if (rsf_err) rsf_throw();\n")
            }
            tryDepth--
            sb.append(pad(indent + 1)).append("rsf_ctx_get()->handler = ").append(h).append(".prev;\n")
            sb.append(pad(indent)).append("} else {\n")
            // longjmp 落到这里：错误已经置好，帧也已经弹回 prev（下面这行）
            sb.append(pad(indent + 1)).append("rsf_ctx_get()->handler = ").append(h).append(".prev;\n")
            sb.append(pad(indent + 1)).append("rsf_str v_").append(s.catchName).append(" = rsf_err_msg;\n")
            sb.append(pad(indent + 1)).append("rsf_err = false;\n")
            for (inner in s.catchBody.statements) sb.append(stmt(inner, indent + 1))
            sb.append(pad(indent)).append("}\n")
            sb.toString()
        }

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

    /** `a ?: b`：C 里用语句表达式，保证 a 只求值一次。 */
    private fun elvis(e: ElvisExpr): String {
        // 左边可能是泛型盒子（物理类型 rsf_val），结果类型取前端算好的那个
        val have = physicalType(e.left)
        val want = model.typeOf(e)
        val code = expr(e.left)
        val test = when {
            have.base == RsfBase.STRING -> "_e.data == NULL"
            have.base == RsfBase.TYPE_PARAM -> "_e.tag == 0"
            have.base == RsfBase.EXTERNAL || have.arrayDepth > 0 || have.ptrDepth > 0 || have.nullable -> "!_e"
            else -> "false"
        }
        val pick = when {
            want.base == RsfBase.TYPE_PARAM && have.base != RsfBase.TYPE_PARAM -> boxed(have, "_e")
            have.base == RsfBase.TYPE_PARAM && want.base != RsfBase.TYPE_PARAM -> unboxed(want, "_e")
            else -> "_e"
        }
        // 左边可能 fail：`?:` 必须自己接住这次 longjmp，否则错误会穿过整个表达式。
        // 语义：失败 → 取右边；成功但值为 null → 也取右边。
        val h = "he_${trySeq++}"
        val right = expr(e.right)
        return "({ ${cType(want)} _out; ${cType(have)} _e; rsf_handler $h; " +
            "$h.prev = rsf_ctx_get()->handler; rsf_ctx_get()->handler = &$h; " +
            "if (setjmp($h.jb) == 0) { _e = $code; rsf_ctx_get()->handler = $h.prev; " +
            "_out = ($test) ? (rsf_err = false, $right) : $pick; } " +
            "else { rsf_ctx_get()->handler = $h.prev; rsf_err = false; _out = $right; } " +
            "_out; })"
    }

    private fun floatLit(v: Float): String = if (v == Float.POSITIVE_INFINITY) "(1.0f/0.0f)" else if (v == Float.NEGATIVE_INFINITY) "(-1.0f/0.0f)" else v.toString() + "f"

    private fun doubleLit(v: Double): String = if (v == Double.POSITIVE_INFINITY) "(1.0/0.0)" else if (v == Double.NEGATIVE_INFINITY) "(-1.0/0.0)" else v.toString()

    /** 把实参摊成 C 的 `(rsf_str[]){…}` 复合字面量；空表给 0。 */
    private fun cArray(items: List<String>): String {
        if (items.isEmpty()) return "0"
        return "(rsf_str[]){" + items.joinToString(", ") + "}"
    }

    private fun expr(e: Expr): String {
        val expected = expectedType
        expectedType = null
        return when (e) {
        is IntLit -> e.value.toString()
        is LongLit -> "${e.value}LL"
        is CharLit -> e.value.code.toString() + "u"
        is FloatLit -> floatLit(e.value)
        is DoubleLit -> doubleLit(e.value)
        is StringLit -> stringLit(e.value)
        is BoolLit -> if (e.value) "true" else "false"
        is NullLit -> nullValue(e, expected)
        is VarExpr -> {
            val field = model.classModel.fieldReads[e]
            if (field != null) fieldRef("self", field.className, e.name) else paramName(e.name)
        }
        // 阶段 80：扩展函数里 `this` 是 0 号形参（接收者），C 侧名字是 v_this
        is ThisExpr -> if (extensionFn) paramName("this") else "self"
        is UnaryExpr -> unary(e)
        is ForceExpr -> forceOf(e)
        is BinaryExpr -> binary(e)
        is IfExpr -> "(${expr(e.condition)} ? ${expr(e.whenTrue)} : ${expr(e.whenFalse)})"
        is ElvisExpr -> elvis(e)
        is CallExpr -> call(e)
        is IndexExpr -> indexRead(e)
        is MemberExpr -> member(e)
        is NewArrayExpr -> newArray(e)
        is NewExpr -> newObj(e)
        else -> fail("表达式 ${e::class.simpleName}")
        }
    }

    private fun nullValue(e: NullLit, expected: RsfType?): String {
        val t = expected ?: model.typeOf(e)
        if (t.base == RsfBase.STRING && t.nullable) return "rsf_str_null()"
        if (t.base == RsfBase.TYPE_PARAM) return "rsf_val_null()"
        if (t.base == RsfBase.NULL || t.isPointer || t.arrayDepth > 0 || t.base == RsfBase.EXTERNAL) return "NULL"
        fail("可空的 ${t.rsfName} 上的 null 字面量（第 ${e.at.line} 行）")
    }

    private fun newObj(e: NewExpr): String {
        // 阶段 66：new File(path) → 宿主侧 rsf_file
        if (e.className == "File" || e.className == "java.io.File" || e.className == "java/io/File") {
            usesFile = true
            val fileArgs = e.arguments
            return when (fileArgs.size) {
                0 -> "rsf_file_new(rsf_str_c(\"\"))"
                1 -> "rsf_file_new(${expr(fileArgs[0])})"
                // new File(parent, child)：两个参数都要用，别只取第一个
                else -> "rsf_file_join(${expr(fileArgs[0])}, ${expr(fileArgs[1])})"
            }
        }
        val cls = model.classModel.newTargets[e] ?: fail("new 的类（${e.className}）")
        val ctor = model.classModel.newCtors[e]
        val name = if (ctor != null) ctorNames[ctor] ?: fail("构造器命名") else defaultCtorNames[cls] ?: fail("默认构造器命名")
        val args = argsWithExpected(e.arguments, if (ctor != null) paramTypesOf(ctor) else emptyList())
        return "$name($args)"
    }

    private fun indexRead(e: IndexExpr): String {
        val arrayType = model.typeOf(e.receiver)
        val elem = elemCType(elementOf(arrayType))
        return "(($elem*)${expr(e.receiver)}.data)[${expr(e.index)}]"
    }

    /**
     * 阶段 64b：自举源码直接调 JDK（`Integer.toString` / `System.err.println` / `Math.max` …）。
     * C 后端把这些调用摊到运行时里的同名 shim 上，没登记的返回 null 交给普通成员解析。
     */
    private fun jdkMember(e: MemberExpr): String? {
        // System.err.println(x) / System.err.print(x)
        val errRecv = e.receiver as? MemberExpr
        if (errRecv != null && errRecv.name == "err" && (errRecv.receiver as? VarExpr)?.name == "System") {
            val arg = e.arguments?.firstOrNull() ?: return "fputc('\n', stderr)"
            val code = expr(arg)
            val fn = when (model.typeOf(arg).base) {
                RsfBase.STRING -> "rsf_eprint_s"
                RsfBase.INT -> "rsf_eprint_i"
                RsfBase.LONG -> "rsf_eprint_l"
                RsfBase.BOOL -> "rsf_eprint_b"
                RsfBase.FLOAT, RsfBase.DOUBLE -> "rsf_eprint_d"
                else -> "rsf_eprint_s"
            }
            val call = "$fn($code)"
            val line = call.replace("rsf_eprint_", "rsf_eprintln_")
            return if (e.name == "println") line else call
        }
        // 阶段 66：java.io.File（自举编译器遍历目录/判断文件）
        val fileType = runCatching { model.typeOf(e.receiver) }.getOrNull()
        if (fileType != null && fileType.base == RsfBase.EXTERNAL && fileType.arrayDepth == 0 &&
            (fileType.externalName == "java/io/File" || fileType.externalName == "File")
        ) {
            usesFile = true
            val recv = "((rsf_file*)(${expr(e.receiver)}))"
            return when (e.name) {
                "isFile" -> "rsf_file_is_file($recv)"
                "isDirectory" -> "rsf_file_is_dir($recv)"
                "exists" -> "rsf_file_exists($recv)"
                "length" -> "rsf_file_length($recv)"
                "getName" -> "rsf_file_name($recv)"
                "getParent" -> "rsf_file_parent($recv)"
                "getPath", "toString" -> "rsf_file_path($recv)"
                "getAbsolutePath", "getCanonicalPath" -> "rsf_file_absolute($recv)"
                "listFiles" -> "rsf_file_list($recv)"
                "mkdirs" -> "rsf_file_mkdirs($recv)"
                "delete" -> "rsf_file_delete($recv)"
                else -> null
            }
        }
        val recv = (e.receiver as? VarExpr)?.name ?: return null
        if (recv !in JDK_CLASSES) return null
        if (runCatching { model.bindingOf(e.receiver) }.isSuccess) return null
        val args = e.arguments ?: return null
        val a = args.map { expr(it) }
        return when ("$recv.${e.name}") {
            "Integer.toString" -> "rsf_int_to_str(${a[0]})"
            "Long.toString" -> "rsf_long_to_str(${a[0]})"
            "Float.toString" -> "rsf_float_to_str(${a[0]})"
            "Double.toString" -> "rsf_double_to_str(${a[0]})"
            "Integer.parseInt" -> "rsf_parse_int(${a[0]})"
            "Long.parseLong" -> "rsf_parse_long(${a[0]})"
            "Float.parseFloat" -> "rsf_parse_float(${a[0]})"
            "Float.floatToIntBits" -> "rsf_float_bits(${a[0]})"
            "Double.doubleToLongBits" -> "rsf_double_bits(${a[0]})"
            "Double.parseDouble" -> "rsf_parse_double(${a[0]})"
            "Integer.valueOf", "Long.valueOf", "Float.valueOf", "Double.valueOf" -> "(${a[0]})"
            "Math.max" -> "rsf_max_i(${a[0]}, ${a[1]})"
            "Math.min" -> "rsf_min_i(${a[0]}, ${a[1]})"
            "String.valueOf" -> asStr(args[0], a[0])
            "String.replace" -> "rsf_str_replace(${a[0]}, ${a[1]}, ${a[2]})"
            "String.removePrefix" -> "rsf_str_remove_prefix(${a[0]}, ${a[1]})"
            "System.exit" -> "rsf_exit(${a[0]})"
            "System.getProperty", "System.getenv" -> "rsf_get_prop(${a[0]})"
            "System.identityHashCode" -> "((int32_t)(intptr_t)(${a[0]}))"
            else -> null
        }
    }

    private fun member(e: MemberExpr): String {
        // 阶段 80：`3.twice()` —— 扩展函数就是普通顶层函数，接收者当 0 号实参。
        // 放在最前面：它既不是 JVM 成员，也不在成员表里（Checker 记在 extensionCalls）。
        model.extensionCalls[e]?.let { decl ->
            val params = paramTypesOf(decl)
            val first = adapt(params.getOrNull(0) ?: RsfType.INT, physicalType(e.receiver), expr(e.receiver))
            val rest = argsWithExpected(e.arguments.orEmpty(), params.drop(1))
            val all = if (rest.isEmpty()) first else "$first, $rest"
            return "${fnName(decl)}($all)"
        }
        // 阶段 79 v2：字段写了 `{ get }` / `{ is }` 时 `p.hp` 是**属性读**，摊成 `p.getHp()`。
        // 判据来自 Checker 的表（三端同源）；访问器自己的体里不会记，所以不会递归。
        model.propertyReads[e]?.let { accessor ->
            val name = methodNames[accessor.decl] ?: fail("属性 getter 命名")
            return "$name(${expr(e.receiver)})"
        }
        jdkMember(e)?.let { return it }
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
                "indexOf" -> return "rsf_str_index_of($recv, ${args[0]})"
                "lastIndexOf" -> return "rsf_str_last_index_of($recv, ${args[0]})"
                "trim" -> return "rsf_str_trim($recv)"
                "repeat" -> return "rsf_str_repeat($recv, ${args[0]})"
                "replace" -> return "rsf_str_replace($recv, ${args[0]}, ${args[1]})"
                "toUpperCase" -> return "rsf_str_upper($recv)"
                "compareTo" -> return "rsf_str_compare($recv, ${args[0]})"
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
                    val pts = paramTypesOf(resolved.decl)
                    dispatchCall(receiver, resolved.decl, args.mapIndexed { i, a ->
                        adapt(pts.getOrNull(i) ?: model.typeOf(a), model.typeOf(a), expr(a))
                    })
                }
            }
            else -> fail("成员访问 '${e.name}'（第 ${e.at.line} 行）")
        }
    }

    /** 虚分派：按「名字 + 参数个数」查槽位，从对象的虚表里取实现再调。 */
    /** 虚分派。接收者表达式的求值结果先存进临时变量——`parseTypeRef().text()` 这种接收者
     * 本身带调用的写法，如果直接内联会在产物里被求值三次（自举 exe 里“解析两次”的根因）。 */
    private fun dispatchCall(receiver: String, decl: FuncDecl, args: List<String>): String {
        val slot = slotOf(decl.name, args.size)
        val params = (listOf("void*") + paramTypesOf(decl).map { cType(it) }).joinToString(", ")
        val ret = cType(returnType(decl))
        val tmp = "__rsf_recv" + (tempSeq++)
        val call = (listOf(tmp) + args).joinToString(", ")
        return "({ void* $tmp = (void*)($receiver); (($ret (*)($params))(*(void***)$tmp)[$slot])($call); })"
    }

    private fun slotOf(name: String, arity: Int): Int =
        slots[name + "/" + arity] ?: fail("虚方法槽位（$name/$arity）")

    private fun newArray(e: NewArrayExpr): String {
        if (e.dimensions.size != 1) fail("多维数组")
        // 元素类型用语义类型：泛型实参 T 也会落到 rsf_val（阶段 66）
        val elemSize = elemCType(elementOf(model.typeOf(e)))
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

    private fun forceOf(e: ForceExpr): String {
        val operand = e.operand
        val code = expr(operand)
        val t = model.typeOf(operand)
        // 非可空值上的 !! 是编译期的空断言，C 里没有可做的；
        // 但内置函数（writeText 之类）的失败是「置错 + 返回零值」，
        // 所以 !! 必须把待处理的失败转成真正的 throw，否则错误会被悄悄吞掉。
        val raw = if (!t.nullable) code
        else if (t.base == RsfBase.STRING) "rsf_force_str($code)"
        else "rsf_force_ptr($code)"
        return "({ ${cType(model.typeOf(e))} _f = $raw; if (rsf_err) rsf_throw(); _f; })"
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
        val left = if (e.left is NullLit) {
            expectedType = model.typeOf(e.right)
            expr(e.left)
        } else {
            expr(e.left)
        }
        val right = if (e.right is NullLit) {
            expectedType = model.typeOf(e.left)
            expr(e.right)
        } else {
            expr(e.right)
        }
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
            // C 的 % 只对整数成立；浮点取余要 fmod/fmodf（语义与 Java 的 % 一致）
            TokenKind.PERCENT -> when (type.base) {
                RsfBase.FLOAT -> "(fmodf($left, $right))"
                RsfBase.DOUBLE -> "(fmod($left, $right))"
                else -> "($left % $right)"
            }
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
    private fun asStr(operand: org.example.complie.ast.Expr, code: String): String {
        val t = model.typeOf(operand)
        return when (t.base) {
            RsfBase.STRING -> code
            RsfBase.BOOL -> "rsf_bool_to_str($code)"
            RsfBase.LONG -> "rsf_long_to_str($code)"
            RsfBase.INT, RsfBase.BYTE -> "rsf_int_to_str($code)"
            RsfBase.CHAR -> "rsf_char_to_str($code)"
            RsfBase.FLOAT, RsfBase.DOUBLE -> "rsf_double_to_str($code)"
            else -> {
                val en = enumOf(t)
                if (en != null) {
                    "rsf_str_c(rsf_enum_name_${en.name}($code))"
                } else {
                    fail("字符串拼接里的 ${t.rsfName.ifEmpty { t.externalName ?: "?" }}（第 ${operand.at.line} 行）")
                }
            }
        }
    }

    private fun call(e: CallExpr): String {
        if (e.name == "print" || e.name == "println") return printCall(e)
        val implicit = model.classModel.implicitCalls[e]
        if (implicit != null) {
            val name = methodNames[implicit.decl] ?: fail("方法命名")
            return if (implicit.decl.isStatic) {
                "$name(${e.arguments.joinToString(", ") { expr(it) }})"
            } else {
                val pts = paramTypesOf(implicit.decl)
                dispatchCall("self", implicit.decl, e.arguments.mapIndexed { i, a ->
                    adapt(pts.getOrNull(i) ?: model.typeOf(a), model.typeOf(a), expr(a))
                })
            }
        }
        when (e.name) {
            "intToString" -> return "rsf_int_to_str(${expr(e.arguments[0])})"
            "memBytes" -> return "rsf_mem_bytes()"
            "memPeak" -> return "rsf_mem_peak()"
            "gcCollect" -> return "rsf_gc_collect()"
            // 阶段 81：构建脚本的宿主能力只有 `rsf build` 驱动 build.rsfs 时才有
            "outDir", "compile", "compileAll" -> fail("内置函数 ${e.name}（只有构建脚本里能用）")
            "stringBytes" -> return "rsf_str_bytes(${expr(e.arguments[0])})"
            "byteLength" -> return "rsf_str_byte_len(${expr(e.arguments[0])})"
        "slice" -> return "rsf_str_slice(${expr(e.arguments[0])}, " +
            "(int32_t)(${expr(e.arguments[1])}), (int32_t)(${expr(e.arguments[2])}))"
            "charToInt" -> return "((int32_t)${expr(e.arguments[0])})"
            "toChar" -> return "((uint16_t)${expr(e.arguments[0])})"
            "toByte" -> return "((int8_t)${expr(e.arguments[0])})"
            "parseInt" -> return "rsf_parse_int(${expr(e.arguments[0])})"
            "isDigit" -> return "rsf_is_digit(${expr(e.arguments[0])})"
            "isLetter" -> return "rsf_is_letter(${expr(e.arguments[0])})"
            "isWhitespace" -> return "rsf_is_whitespace(${expr(e.arguments[0])})"
            "toUpper" -> return "rsf_to_upper(${expr(e.arguments[0])})"
            "toLower" -> return "rsf_to_lower(${expr(e.arguments[0])})"
            "readText" -> return "rsf_read_text(${expr(e.arguments[0])})"
            "writeText" -> return "rsf_write_text(${expr(e.arguments[0])}, ${expr(e.arguments[1])})"
            "readBytes" -> return "rsf_read_bytes(${expr(e.arguments[0])})"
            "writeBytes" -> return "rsf_write_bytes(${expr(e.arguments[0])}, ${expr(e.arguments[1])})"
            "jvmCall" -> {
                usesJvmCall = true
                val a = e.arguments.map { expr(it) }
                val rest = a.drop(2)
                return "rsf_jvm_call(${a[0]}, ${a[1]}, ${rest.size}, ${cArray(rest)})"
            }
            "jvmNew" -> {
                usesJvmCall = true
                val a = e.arguments.map { expr(it) }
                val rest = a.drop(1)
                return "rsf_jvm_new(${a[0]}, ${rest.size}, ${cArray(rest)})"
            }
            "jvmCallOn" -> {
                usesJvmCall = true
                val a = e.arguments.map { expr(it) }
                val rest = a.drop(2)
                return "rsf_jvm_call_on(${a[0]}, ${a[1]}, ${rest.size}, ${cArray(rest)})"
            }
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
            val ctorParams = if (ctor != null) paramTypesOf(ctor) else emptyList()
            return "$ctorName(${argsWithExpected(e.arguments, ctorParams)})"
        }
        val target = model.callTargets[e] ?: fail("调用 '${e.name}'")
        val args = argsWithExpected(e.arguments, paramTypesOf(target))
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
            RsfBase.FLOAT, RsfBase.DOUBLE -> if (newline) "rsf_println_d($value)" else "rsf_print_d($value)"
            RsfBase.INT -> if (newline) "rsf_println_i($value)" else "rsf_print_i($value)"
            else -> {
                val en = enumOf(type)
                if (en != null) {
                    val s = "rsf_str_c(rsf_enum_name_${en.name}($value))"
                    if (newline) "rsf_println_s($s)" else "rsf_print_s($s)"
                } else {
                    fail("print 的参数类型 '${type.rsfName.ifEmpty { type.externalName ?: "?" }}'")
                }
            }
        }
    }

    /** 字符串字面量按 UTF-8 逐字节写成八进制转义，长度也按字节算（与 JVM/解释器一致）。 */
    private fun stringLit(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("rsf_str_lit_u(\"")
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
        // 码元数：Kotlin 的 String.length 本来就是 UTF-16 码元数，直接当三参发出去
        sb.append("\", ").append(bytes.size).append("LL, ").append(value.length + 1).append("LL)")
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
        if (model.gcEnabled) {
            // gc on：桥里要调 rsf_gc_thread_attach / rsf_gc_heap_install，所以直接拿运行时头
            // （它里面的 rsf_str 也有 RSF_STR_DEFINED 守卫，和下面的兜底块不冲突）。
            sb.append("#include \"rsf_runtime.h\"").append(NL).append(NL)
        }
        sb.append("#ifndef RSF_STR_DEFINED").append(NL)
        sb.append("#define RSF_STR_DEFINED").append(NL)
        sb.append("typedef struct { int64_t len; int64_t units1; const char* data; } rsf_str;").append(NL)
        sb.append(RuntimeErase).append(NL)
        sb.append("#endif").append(NL).append(NL)
        sb.append(JNI_UTF8).append(NL)

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
            if (model.gcEnabled) {
                // --lib 没有 main，产物是被 JVM 加载的 dll/so：根区间与分配器在这里补上。
                // 两个调用都幂等，所以每个导出函数前面写一遍没关系。
                sb.append("    if (!rsf_gc_thread_attached()) rsf_gc_thread_attach();").append(NL)
                sb.append("    rsf_gc_heap_install();").append(NL)
            }
            for ((i, t) in ps.withIndex()) {
                when (t.base) {
                    RsfBase.STRING -> {
                        // 不用 GetStringUTFChars：它给的是 modified UTF-8（CESU-8），
                        // BMP 之外的字符会变成两对代理项，rsf 侧看到的字节与长度都不对。
                        sb.append("    int64_t n").append(i).append(" = 0;").append(NL)
                        sb.append("    char* c").append(i)
                            .append(" = (char*)rsf_jni_utf8_of_jstring(env, j").append(i).append(", &n").append(i).append(");").append(NL)
                        sb.append("    rsf_str a").append(i)
                            .append("; a").append(i).append(".len = n").append(i)
                            .append("; a").append(i).append(".data = c").append(i).append(";").append(NL)
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
                    // 返回的 r 可能就是入参的切片（rtn s），所以先把内容交给 JVM，再释放入参缓冲。
                    sb.append("    jsize rn = 0;").append(NL)
                    sb.append("    jchar* ru = rsf_jni_jchar_of_utf8(r.data, r.len, &rn);").append(NL)
                    sb.append("    jstring out = (*env)->NewString(env, ru, rn);").append(NL)
                    sb.append("    free(ru);").append(NL)
                }
                else -> sb.append("    ").append(cType(ret)).append(" r = rsf_fn_").append(fn.name)
                    .append("(").append(args).append(");").append(NL)
            }
            for ((i, t) in ps.withIndex()) {
                if (t.base == RsfBase.STRING) {
                    sb.append("    free(c").append(i).append(");").append(NL)
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
        /**
         * JNI 字符串的双向转换（阶段 77 步骤 A）。
         *
         * JNI 的 `GetStringUTFChars` / `NewStringUTF` 走的是 **modified UTF-8**（CESU-8）：
         * BMP 之外的字符（emoji）会被编成两对代理项，`\0` 编成 `C0 80`。
         * 直接用会让 rsf 侧的字节长度与内容都错，所以这里自己做 UTF-16 ↔ UTF-8 转换。
         *
         * 约定：非法 UTF-8 / 孤立代理项一律换成 U+FFFD，不做静默截断。
         */
        private val JNI_UTF8 = """
            #ifndef RSF_JNI_UTF8_DEFINED
            #define RSF_JNI_UTF8_DEFINED
            /* JVM 的 jstring → rsf 的 UTF-8 字节（调用方负责 free） */
            static void* rsf_jni_utf8_of_jstring(JNIEnv* env, jstring js, int64_t* outLen) {
                jsize n = (*env)->GetStringLength(env, js);
                jchar* u = (jchar*)malloc(sizeof(jchar) * (size_t)(n > 0 ? n : 1));
                if (n > 0) (*env)->GetStringRegion(env, js, 0, n, u);
                size_t bytes = 0;
                for (jsize i = 0; i < n; i++) {
                    unsigned int c = (unsigned int)u[i];
                    if (c < 0x80) bytes += 1;
                    else if (c < 0x800) bytes += 2;
                    else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && (unsigned int)u[i + 1] >= 0xDC00
                             && (unsigned int)u[i + 1] <= 0xDFFF) { bytes += 4; i++; }
                    else bytes += 3;
                }
                char* out = (char*)malloc(bytes + 1);
                size_t at = 0;
                for (jsize i = 0; i < n; i++) {
                    unsigned int c = (unsigned int)u[i];
                    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && (unsigned int)u[i + 1] >= 0xDC00
                        && (unsigned int)u[i + 1] <= 0xDFFF) {
                        unsigned int cp = 0x10000u + ((c - 0xD800u) << 10) + ((unsigned int)u[i + 1] - 0xDC00u);
                        out[at++] = (char)(0xF0u | (cp >> 18));
                        out[at++] = (char)(0x80u | ((cp >> 12) & 0x3Fu));
                        out[at++] = (char)(0x80u | ((cp >> 6) & 0x3Fu));
                        out[at++] = (char)(0x80u | (cp & 0x3Fu));
                        i++;
                    } else if (c >= 0xD800 && c <= 0xDFFF) {
                        out[at++] = (char)0xEF; out[at++] = (char)0xBF; out[at++] = (char)0xBD;
                    } else if (c < 0x80) {
                        out[at++] = (char)c;
                    } else if (c < 0x800) {
                        out[at++] = (char)(0xC0u | (c >> 6));
                        out[at++] = (char)(0x80u | (c & 0x3Fu));
                    } else {
                        out[at++] = (char)(0xE0u | (c >> 12));
                        out[at++] = (char)(0x80u | ((c >> 6) & 0x3Fu));
                        out[at++] = (char)(0x80u | (c & 0x3Fu));
                    }
                }
                out[at] = 0;
                free(u);
                *outLen = (int64_t)at;
                return out;
            }

            /* rsf 的 UTF-8 字节 → JVM 的 UTF-16 码元（调用方负责 free） */
            static jchar* rsf_jni_jchar_of_utf8(const char* s, int64_t n, jsize* outLen) {
                size_t units = 0;
                for (int64_t i = 0; i < n;) {
                    unsigned char b = (unsigned char)s[i];
                    int len = (b < 0x80) ? 1 : (((b >> 5) == 6) ? 2 : (((b >> 4) == 14) ? 3 : (((b >> 3) == 30) ? 4 : 0)));
                    if (len == 0 || i + len > n) { units += 1; i += 1; }
                    else { units += (len == 4) ? 2 : 1; i += len; }
                }
                jchar* out = (jchar*)malloc(sizeof(jchar) * (units > 0 ? units : 1));
                size_t at = 0;
                for (int64_t i = 0; i < n;) {
                    unsigned char b = (unsigned char)s[i];
                    if (b < 0x80) { out[at++] = (jchar)b; i += 1; continue; }
                    int len = ((b >> 5) == 6) ? 2 : (((b >> 4) == 14) ? 3 : (((b >> 3) == 30) ? 4 : 0));
                    if (len == 0 || i + len > n) { out[at++] = (jchar)0xFFFD; i += 1; continue; }
                    unsigned int cp = (len == 2) ? (unsigned int)(b & 0x1F)
                                    : (len == 3) ? (unsigned int)(b & 0x0F)
                                                 : (unsigned int)(b & 0x07);
                    int ok = 1;
                    for (int k = 1; k < len; k++) {
                        unsigned char c = (unsigned char)s[i + k];
                        if ((c & 0xC0) != 0x80) { ok = 0; break; }
                        cp = (cp << 6) | (unsigned int)(c & 0x3F);
                    }
                    if (!ok || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
                        out[at++] = (jchar)0xFFFD; i += 1; continue;
                    }
                    if (cp >= 0x10000u) {
                        unsigned int v = cp - 0x10000u;
                        out[at++] = (jchar)(0xD800u + (v >> 10));
                        out[at++] = (jchar)(0xDC00u + (v & 0x3FFu));
                    } else {
                        out[at++] = (jchar)cp;
                    }
                    i += len;
                }
                *outLen = (jsize)at;
                return out;
            }
            #endif
        """.trimIndent()

        /**
         * `jvmCall` 的运行时（阶段 62）：native 程序自己起一个 JVM 再回头调 Java。
         *
         * 为什么用 `LoadLibrary("jvm.dll")` 而不是链接 `-ljvm`：MinGW 没有 jvm.lib，
         * 直接按导出名取函数地址最省事，也只有真正用了 `jvmCall` 的程序才编这段。
         * 类路径从环境变量 `RSF_JVM_CP` 读（缺省 `.`）。
         */
        private val JVM_RUNTIME = """
            /* ---- native -> JVM（jvmCall / jvmNew / jvmCallOn）：用到才编 ---- */
            #include <windows.h>
            #include <jni.h>
            ${JNI_UTF8}
            typedef jint (JNICALL *rsf_create_vm_t)(JavaVM**, void**, void*);
            static JavaVM* rsf_jvm = 0;
            static JNIEnv* rsf_env = 0;
            static jobject rsf_jvm_objs[256];
            static int rsf_jvm_objc = 0;

            static char* rsf_cstr(rsf_str s) {
                char* p = (char*)malloc((size_t)s.len + 1);
                if (s.len > 0) memcpy(p, s.data, (size_t)s.len);
                p[s.len] = 0;
                return p;
            }

            static rsf_str rsf_jstr_of(const char* p) {
                rsf_str out = {0, 0, NULL};
                size_t n = strlen(p);
                char* copy = (char*)malloc(n + 1);
                memcpy(copy, p, n + 1);
                out.len = (int64_t)n;
                out.data = copy;
                return out;
            }

            static void rsf_jvm_ensure(void) {
                if (rsf_jvm != 0) return;
                const char* dll = getenv("RSF_JVM_DLL");
                char dllPath[4096];
                if (dll == 0) {
                    const char* jh = getenv("JAVA_HOME");
                    if (jh != 0) {
                        snprintf(dllPath, sizeof(dllPath), "%s/bin/server/jvm.dll", jh);
                        dll = dllPath;
                    }
                }
                HMODULE lib = LoadLibraryA(dll == 0 ? "jvm.dll" : dll);
                if (lib == 0) {
                    fprintf(stderr, "rsf: cannot find jvm.dll (set JAVA_HOME or RSF_JVM_DLL)\n");
                    exit(1);
                }
                rsf_create_vm_t create = (rsf_create_vm_t)(void*)GetProcAddress(lib, "JNI_CreateJavaVM");
                if (create == 0) {
                    fprintf(stderr, "rsf: jvm.dll has no JNI_CreateJavaVM\n");
                    exit(1);
                }
                const char* cp = getenv("RSF_JVM_CP");
                static char cpopt[4096];
                if (cp == 0) cp = ".";
                snprintf(cpopt, sizeof(cpopt), "-Djava.class.path=%s", cp);
                JavaVMOption opts[2];
                opts[0].optionString = cpopt;
                opts[1].optionString = (char*)"-Dfile.encoding=UTF-8";
                JavaVMInitArgs args;
                args.version = JNI_VERSION_1_8;
                args.nOptions = 2;
                args.options = opts;
                args.ignoreUnrecognized = JNI_FALSE;
                if (create(&rsf_jvm, (void**)&rsf_env, &args) != JNI_OK) {
                    fprintf(stderr, "rsf: cannot start JVM\n");
                    exit(1);
                }
            }

            static void rsf_jvm_check(void) {
                if ((*rsf_env)->ExceptionCheck(rsf_env)) {
                    (*rsf_env)->ExceptionDescribe(rsf_env);
                    fprintf(stderr, "rsf: Java side threw\n");
                    exit(1);
                }
            }

            static jclass rsf_java_class(const char* raw) {
                static char buf[1024];
                int i = 0;
                for (; raw[i] != 0 && i < 1000; i++) buf[i] = (raw[i] == '.') ? '/' : raw[i];
                buf[i] = 0;
                jclass c = (*rsf_env)->FindClass(rsf_env, buf);
                if (c == 0) {
                    (*rsf_env)->ExceptionDescribe(rsf_env);
                    fprintf(stderr, "rsf: jvmCall cannot find class %s\n", raw);
                    exit(1);
                }
                return c;
            }

            static jobjectArray rsf_sig_of(int argc) {
                jclass sc = (*rsf_env)->FindClass(rsf_env, "java/lang/String");
                jclass cc = (*rsf_env)->FindClass(rsf_env, "java/lang/Class");
                jobjectArray arr = (*rsf_env)->NewObjectArray(rsf_env, argc, cc, 0);
                for (int i = 0; i < argc; i++) (*rsf_env)->SetObjectArrayElement(rsf_env, arr, i, sc);
                return arr;
            }

            static jobjectArray rsf_args_of(int argc, rsf_str* argv) {
                jclass oc = (*rsf_env)->FindClass(rsf_env, "java/lang/Object");
                jobjectArray arr = (*rsf_env)->NewObjectArray(rsf_env, argc, oc, 0);
                for (int i = 0; i < argc; i++) {
                    jsize n = 0;
                    jchar* u = rsf_jni_jchar_of_utf8(argv[i].data, argv[i].len, &n);
                    jstring js = (*rsf_env)->NewString(rsf_env, u, n);
                    free(u);
                    (*rsf_env)->SetObjectArrayElement(rsf_env, arr, i, js);
                    (*rsf_env)->DeleteLocalRef(rsf_env, js);
                }
                return arr;
            }

            static rsf_str rsf_of_java(jobject o) {
                if (o == 0) return rsf_jstr_of("null");
                jclass c = (*rsf_env)->GetObjectClass(rsf_env, o);
                jmethodID ts = (*rsf_env)->GetMethodID(rsf_env, c, "toString",
                    "()Ljava/lang/String;");
                jstring js = (jstring)(*rsf_env)->CallObjectMethod(rsf_env, o, ts);
                rsf_jvm_check();
                int64_t n = 0;
                char* utf = (char*)rsf_jni_utf8_of_jstring(rsf_env, js, &n);
                char* keep = (char*)malloc((size_t)n + 1);
                if (n > 0) memcpy(keep, utf, (size_t)n);
                keep[n] = 0;
                free(utf);
                (*rsf_env)->DeleteLocalRef(rsf_env, js);
                (*rsf_env)->DeleteLocalRef(rsf_env, c);
                rsf_str out = {0, 0, NULL};
                out.len = n;
                out.data = keep;
                return out;
            }

            static jobject rsf_jvm_method(jclass c, const char* name, int argc) {
                jclass cc = (*rsf_env)->FindClass(rsf_env, "java/lang/Class");
                jmethodID gm = (*rsf_env)->GetMethodID(rsf_env, cc, "getMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
                jstring jn = (*rsf_env)->NewStringUTF(rsf_env, name);
                jobjectArray sig = rsf_sig_of(argc);
                jobject m = (*rsf_env)->CallObjectMethod(rsf_env, c, gm, jn, sig);
                rsf_jvm_check();
                return m;
            }

            static jobject rsf_jvm_invoke(jobject target, jobject method, int argc, rsf_str* argv) {
                jclass mc = (*rsf_env)->FindClass(rsf_env, "java/lang/reflect/Method");
                jmethodID inv = (*rsf_env)->GetMethodID(rsf_env, mc, "invoke",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                jobjectArray args = rsf_args_of(argc, argv);
                jobject r = (*rsf_env)->CallObjectMethod(rsf_env, method, inv, target, args);
                rsf_jvm_check();
                return r;
            }

            static rsf_str rsf_jvm_call(rsf_str cls, rsf_str method, int argc, rsf_str* argv) {
                rsf_jvm_ensure();
                char* cn = rsf_cstr(cls);
                char* mn = rsf_cstr(method);
                jclass c = rsf_java_class(cn);
                jobject m = rsf_jvm_method(c, mn, argc);
                jobject r = rsf_jvm_invoke(0, m, argc, argv);
                free(cn);
                free(mn);
                return rsf_of_java(r);
            }

            static rsf_str rsf_jvm_new(rsf_str cls, int argc, rsf_str* argv) {
                rsf_jvm_ensure();
                char* cn = rsf_cstr(cls);
                jclass c = rsf_java_class(cn);
                jclass cc = (*rsf_env)->FindClass(rsf_env, "java/lang/Class");
                jmethodID gc = (*rsf_env)->GetMethodID(rsf_env, cc, "getConstructor",
                    "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;");
                jobjectArray sig = rsf_sig_of(argc);
                jobject ctor = (*rsf_env)->CallObjectMethod(rsf_env, c, gc, sig);
                rsf_jvm_check();
                jclass ct = (*rsf_env)->FindClass(rsf_env, "java/lang/reflect/Constructor");
                jmethodID ni = (*rsf_env)->GetMethodID(rsf_env, ct, "newInstance",
                    "([Ljava/lang/Object;)Ljava/lang/Object;");
                jobjectArray args = rsf_args_of(argc, argv);
                jobject obj = (*rsf_env)->CallObjectMethod(rsf_env, ctor, ni, args);
                rsf_jvm_check();
                jobject g = (*rsf_env)->NewGlobalRef(rsf_env, obj);
                if (rsf_jvm_objc >= 256) {
                    fprintf(stderr, "rsf: jvmNew out of handles\n");
                    exit(1);
                }
                rsf_jvm_objs[rsf_jvm_objc++] = g;
                char buf[32];
                snprintf(buf, sizeof(buf), "obj:%d", rsf_jvm_objc - 1);
                free(cn);
                return rsf_jstr_of(buf);
            }

            static rsf_str rsf_jvm_call_on(rsf_str handle, rsf_str method, int argc, rsf_str* argv) {
                rsf_jvm_ensure();
                char* hn = rsf_cstr(handle);
                if (strncmp(hn, "obj:", 4) != 0) {
                    fprintf(stderr, "rsf: jvmCallOn handle must look like obj:0 (got %s)\n", hn);
                    exit(1);
                }
                int idx = atoi(hn + 4);
                free(hn);
                if (idx < 0 || idx >= rsf_jvm_objc) {
                    fprintf(stderr, "rsf: jvmCallOn handle %d is unknown\n", idx);
                    exit(1);
                }
                jobject obj = rsf_jvm_objs[idx];
                jclass c = (*rsf_env)->GetObjectClass(rsf_env, obj);
                char* mn = rsf_cstr(method);
                jobject m = rsf_jvm_method(c, mn, argc);
                jobject r = rsf_jvm_invoke(obj, m, argc, argv);
                free(mn);
                return rsf_of_java(r);
            }



        """.trimIndent() + '\n'
        /** 单文件运行时：程序与这些辅助函数编在一起，不依赖任何 rsf 运行时库。 */
        /** 阶段 66：java.io.File 的 shim（只在用到 File 时插进运行时）。 */
        private val FILE_RUNTIME = """
            /* ---- 阶段 66：java.io.File 的宿主侧 shim（自举编译器要用它遍历文件） ---- */
            #include <sys/stat.h>
            #include <dirent.h>
            #ifdef _WIN32
            #include <direct.h>
            #define rsf_mkdir(p) _mkdir(p)
            #define rsf_getcwd(b, n) _getcwd(b, (int)(n))
            #else
            #include <sys/types.h>
            #include <unistd.h>
            #define rsf_mkdir(p) mkdir(p, 0777)
            #define rsf_getcwd(b, n) getcwd(b, n)
            #endif

            typedef struct rsf_file { rsf_str path; } rsf_file;

            static rsf_file* rsf_file_new(rsf_str p);
            /* new File(parent, child)：child 是绝对路径就直接用，否则 parent + '/' + child。 */
            static rsf_file* rsf_file_join(rsf_str parent, rsf_str child) {
                rsf_file* f;
                char* buf;
                int64_t n;
                if (!parent.data || parent.len == 0) return rsf_file_new(child);
                if (child.len > 0 && child.data[0] == '/') return rsf_file_new(child);
                n = parent.len + 1 + child.len;
                buf = (char*)rsf_malloc((size_t)n + 1);
                memcpy(buf, parent.data, (size_t)parent.len);
                buf[parent.len] = '/';
                if (child.len > 0) memcpy(buf + parent.len + 1, child.data, (size_t)child.len);
                buf[n] = 0;
                {
                    rsf_str s; s.len = n; s.data = buf;
                    f = rsf_file_new(s);
                }
                return f;
            }
            static rsf_file* rsf_file_new(rsf_str p) {
                rsf_file* f = (rsf_file*)rsf_malloc(sizeof(rsf_file));
                f->path = p;
                return f;
            }
            static int rsf_file_stat(rsf_file* f, struct stat* st) {
                if (!f || !f->path.data) return -1;
                return stat(rsf_zstr(f->path), st);
            }
            static bool rsf_file_is_file(rsf_file* f) {
                struct stat st;
                return rsf_file_stat(f, &st) == 0 && (st.st_mode & S_IFREG) != 0;
            }
            static bool rsf_file_is_dir(rsf_file* f) {
                struct stat st;
                return rsf_file_stat(f, &st) == 0 && (st.st_mode & S_IFDIR) != 0;
            }
            static bool rsf_file_exists(rsf_file* f) {
                struct stat st;
                return rsf_file_stat(f, &st) == 0;
            }
            static int64_t rsf_file_length(rsf_file* f) {
                struct stat st;
                if (rsf_file_stat(f, &st) != 0) return 0;
                return (int64_t)st.st_size;
            }
            static rsf_str rsf_file_path(rsf_file* f) {
                if (!f) return rsf_str_c("");
                return f->path;
            }
            static rsf_str rsf_file_name(rsf_file* f) {
                /* 返回**独立副本**：rsf_zstr 用的是共享静态缓冲，指向它会被下一次调用覆盖。 */
                if (!f || !f->path.data) return rsf_str_c("");
                int64_t i = f->path.len - 1;
                while (i >= 0 && (f->path.data[i] == '/' || f->path.data[i] == ((char)92))) i--;
                {
                    int64_t last = i;
                    while (last >= 0 && f->path.data[last] != '/' && f->path.data[last] != ((char)92)) last--;
                    last++;
                    {
                        int64_t n = i - last + 1;
                        char* p = (char*)rsf_malloc((size_t)(n > 0 ? n : 0) + 1);
                        if (n > 0) memcpy(p, f->path.data + last, (size_t)n);
                        p[n > 0 ? n : 0] = 0;
                        rsf_str s; s.len = n > 0 ? n : 0; s.data = p;
                        s.units1 = 0;
                        return rsf_str_settle(s);
                    }
                }
            }
            /* java File.getAbsolutePath：相对路径按 cwd 变绝对，再词法归一（去 // 、. 、..）。 */
            static rsf_str rsf_file_absolute(rsf_file* f) {
                char joined[4096];
                char cwd[2048];
                int64_t n = 0;
                if (!f || !f->path.data) return rsf_str_c("");
                if (f->path.len > 0 && f->path.data[0] == '/') {
                    if (f->path.len >= (int64_t)sizeof(joined)) return f->path;
                    memcpy(joined, f->path.data, (size_t)f->path.len);
                    n = f->path.len;
                } else {
                    if (!rsf_getcwd(cwd, sizeof(cwd))) return f->path;
                    n = (int64_t)strlen(cwd);
                    if (n + 1 + f->path.len >= (int64_t)sizeof(joined)) return f->path;
                    memcpy(joined, cwd, (size_t)n);
                    joined[n++] = '/';
                    memcpy(joined + n, f->path.data, (size_t)f->path.len);
                    n += f->path.len;
                }
                joined[n] = 0;
                {
                    char out[4096];
                    int64_t k = 0;
                    int64_t i = 0;
                    while (i < n) {
                        int64_t j;
                        int64_t len;
                        while (i < n && joined[i] == '/') i++;
                        if (i >= n) break;
                        j = i;
                        while (j < n && joined[j] != '/') j++;
                        len = j - i;
                        if (len == 1 && joined[i] == '.') {
                            /* "." 段丢掉 */
                        } else if (len == 2 && joined[i] == '.' && joined[i + 1] == '.') {
                            while (k > 0 && out[k - 1] != '/') k--;
                            if (k > 0) k--;
                        } else {
                            out[k++] = '/';
                            memcpy(out + k, joined + i, (size_t)len);
                            k += len;
                        }
                        i = j;
                    }
                    if (k == 0) out[k++] = '/';
                    out[k] = 0;
                    {
                        char* p = (char*)rsf_malloc((size_t)k + 1);
                        memcpy(p, out, (size_t)k + 1);
                        {
                            rsf_str s; s.len = k; s.data = p;
                            s.units1 = 0;
                            return rsf_str_settle(s);
                        }
                    }
                }
            }
            static rsf_str rsf_file_parent(rsf_file* f) {
                if (!f || !f->path.data) return rsf_str_c("");
                int64_t n = f->path.len;
                const char* p = f->path.data;
                int64_t i = n - 1;
                while (i >= 0 && (p[i] == '/' || p[i] == ((char)92))) i--;
                while (i >= 0 && p[i] != '/' && p[i] != ((char)92)) i--;
                if (i < 0) return rsf_str_c("");
                return rsf_str_substr(f->path, 0, i);
            }
            static bool rsf_file_mkdirs(rsf_file* f) {
                if (!f || !f->path.data) return false;
                char tmp[1024];
                int64_t n = f->path.len;
                if (n <= 0 || n >= 1000) return false;
                memcpy(tmp, f->path.data, (size_t)n);
                tmp[n] = 0;
                for (int64_t i = 1; i < n; i++) {
                    if (tmp[i] == '/' || tmp[i] == ((char)92)) {
                        char c = tmp[i];
                        tmp[i] = 0;
                        rsf_mkdir(tmp);
                        tmp[i] = c;
                    }
                }
                return rsf_mkdir(tmp) == 0;
            }
            static bool rsf_file_delete(rsf_file* f) {
                if (!f || !f->path.data) return false;
                return remove(rsf_zstr(f->path)) == 0;
            }
            static rsf_arr rsf_file_list(rsf_file* f) {
                rsf_arr a = rsf_arr_new(0, sizeof(void*));
                if (!f || !f->path.data) return a;
                DIR* d = opendir(rsf_zstr(f->path));
                if (!d) return a;
                int64_t n = 0;
                struct dirent* e;
                while ((e = readdir(d)) != NULL) {
                    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
                    n++;
                }
                closedir(d);
                a = rsf_arr_new(n, sizeof(void*));
                d = opendir(rsf_zstr(f->path));
                if (!d) return a;
                int64_t i = 0;
                while ((e = readdir(d)) != NULL && i < n) {
                    if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
                    int64_t plen = f->path.len;
                    int64_t nlen = (int64_t)strlen(e->d_name);
                    char* buf = (char*)rsf_malloc((size_t)(plen + nlen + 2));
                    memcpy(buf, f->path.data, (size_t)plen);
                    buf[plen] = '/';
                    memcpy(buf + plen + 1, e->d_name, (size_t)nlen);
                    buf[plen + 1 + nlen] = 0;
                    ((void**)a.data)[i] = (void*)rsf_file_new(rsf_str_c(buf));
                    i++;
                }
                closedir(d);
                return a;
            }
        """

        /** fix: rsf_runtime.h 顶部注释 + 包含守卫开头（runtimeLibSource 用）。 */
        private val LIB_PREFIX = """/* rsf_runtime.h —— rsf C 后端运行时库（头文件版 · 多文件 build 安全 · 含内存 fix）
 * 由 CBackend.runtimeLibSource() 从 RUNTIME + RuntimeErase 机械变换生成。
 * 多个 .c 可共同包含；共享状态走 extern + 单一定义：恰好一个 .c 在 include 前
 * #define RSF_RUNTIME_IMPL。所有 fix 处以 fix 注释标明。
 */
#ifndef RSF_RUNTIME_LIB_H
#define RSF_RUNTIME_LIB_H

"""

        /**
         * 分配器接口 + 上下文（native 线的内存模型：Zig 的实现，Odin 的形状）。
         *
         * 三件事一起解决：
         * - 分配器是**值 + vtable**，不再有全局的“登记簿”；
         * - `new` / 字符串拼接 / 数组这些**语法里藏着的分配**统一读当前上下文，
         *   所以发射代码一行都不用改；
         * - `rsf_err` / `rsf_err_msg` 变成宏，落进 ctx——状态搬家但对生成代码透明。
         */
        private val SHARED_STATE = """/* ============ 分配器 + 上下文（native 线的内存模型）============
 * 一个 rsf_ctx 装两样东西：
 *   alloc        分配器（值 + vtable），默认是进程级 arena；
 *   err/err_msg  `fail` / `try` 的错误状态。
 *
 * 生成代码里的 `rsf_err` / `rsf_err_msg` 是**宏**，落到「当前上下文」的字段上：
 * 所以 C 后端不需要为「状态搬家」改一行发射逻辑，但状态已经不在全局变量里了。
 *
 * 多 TU：extern 声明在这里，唯一定义由恰好一个 #define RSF_RUNTIME_IMPL 的 .c 给出。
 * ========================================================================= */
typedef struct rsf_allocator {
    void* (*alloc)(void* user, size_t size, size_t align);
    void* (*resize)(void* user, void* ptr, size_t oldSize, size_t newSize, size_t align);
    void  (*free)(void* user, void* ptr, size_t size, size_t align);
    void* user;
} rsf_allocator;

/* ---- GC 插件接口（阶段 77）：GC 怎么做是**用户的事**，运行时只留插座 ----
 * 运行时答应三件事：① 所有分配都经过 rsf_allocator（所以你的 GC 能看到每一次分配）；
 * ② 提供一个「现在扫一轮」的入口（rsf_gc_collect / 内置函数 gcCollect()）；
 * ③ 不假设你的 GC 是标记-清扫还是引用计数、移不移动对象——那是你的设计。
 *
 * 最短实现（保守 mark-sweep）：alloc/resize/free 里记账，collect 里从根开始扫。
 * 保守 GC 需要「根 = 线程栈上的指针 + 全局变量」，栈边界用平台 API 自己取
 * （Windows: TIB 的 StackBase / StackLimit；Linux: pthread_getattr_np），
 * 运行时**不替你猜**，因为它不该长平台头文件的依赖。
 *
 * 只填 collect 也能用：那时 memBytes()/memPeak() 会返回 -1（不知道你占了多少），
 * 想知道就再填 bytes。
 */
typedef struct rsf_gc {
    void   (*collect)(void* user);   /* 必填：扫一轮（可以什么都不做） */
    size_t (*bytes)(void* user);     /* 可选：当前占用字节，给 memBytes() 用 */
    void*  user;
} rsf_gc;

typedef struct rsf_handler rsf_handler;

typedef struct rsf_ctx {
    rsf_allocator alloc;
    rsf_gc gc;
    bool err;
    rsf_str err_msg;
    /* 最近的 try 的 setjmp 帧；fail 用它做非局部跳转 */
    rsf_handler* handler;
} rsf_ctx;

/* 线程局部当前上下文；-DRSF_NO_TLS 退回单线程全局（C99 编译器也能编）。 */
#if defined(RSF_NO_TLS)
#  define RSF_TLS
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#  define RSF_TLS _Thread_local
#elif defined(__GNUC__) || defined(__clang__)
#  define RSF_TLS __thread
#else
#  define RSF_TLS
#endif

extern RSF_TLS rsf_ctx* rsf_ctx_cur;
extern rsf_ctx rsf_ctx_default;

#ifdef RSF_RUNTIME_IMPL
RSF_TLS rsf_ctx* rsf_ctx_cur = NULL;
rsf_ctx rsf_ctx_default;
#endif

/* 生成代码照旧写 rsf_err / rsf_err_msg，宏把它们落到当前上下文。 */
#define rsf_err     (rsf_ctx_get()->err)
#define rsf_err_msg (rsf_ctx_get()->err_msg)

"""

        /**
         * 默认分配器：进程级 arena（bump 分配、整块释放）+ 上下文入口。
         *
         * 原来那套「每次 malloc 都登记进全局数组、退出统一 free」被替换成：
         * 默认走 arena，exit 时一次 reset；要单块回收/长驻就把自己的 rsf_allocator
         * 交给 rsf_ctx_init（宿主持有上下文，运行时不做任何分配）。
         * checkRuntimeLib() 会盯住“分配点不要绕开分配器”。
         */
        private val FIX_FUNCS = """/* ---- 码元表缓存：非 ASCII 的长串按下标访问时建一张「码元下标 → 字符」表 ----
 * 只在 >= 256 字节的串上用（源码、读进来的文件），最多 64 条；命中后 charAt / length 都是 O(1)。
 * 不用 rsf_malloc：开启 gc on 时那是 GC 堆，缓存是 C 全局链表、GC 扫不到栈外引用，
 * 会被提前回收。arena reset 与 GC 回收时都要 rsf_cp_clear()。
 */
#define RSF_CP_MIN_LEN 256
#define RSF_CP_MAX_ENTRIES 64
typedef struct rsf_cp_entry {
    const char* data; int64_t len; int64_t units;
    uint32_t* cps; struct rsf_cp_entry* next;
} rsf_cp_entry;
static rsf_cp_entry* rsf_cp_head = NULL;
static int64_t rsf_cp_count = 0;

static void* rsf_cp_raw_alloc(size_t n) { return malloc(n); }
static void  rsf_cp_raw_free(void* p) { free(p); }

static void rsf_cp_clear(void) {
    rsf_cp_entry* e = rsf_cp_head;
    while (e != NULL) { rsf_cp_entry* nx = e->next; rsf_cp_raw_free(e->cps); rsf_cp_raw_free(e); e = nx; }
    rsf_cp_head = NULL; rsf_cp_count = 0;
}

/* ---- arena：默认分配器（bump 分配，整块释放；不做单块回收）----
 * 语义跟 Zig 的 ArenaAllocator 一样：分配只增不改，reset/free 一次性归还。
 * `free` 是空操作——这既是它的优点（几乎零开销），也是它的契约：
 * 长驻代码必须在循环里手动 reset，或者换一个真正的分配器（rsf_ctx_init）。
 */
typedef struct rsf_arena_block {
    struct rsf_arena_block* next;
    size_t start;   /* 数据区相对 (b+1) 的对齐偏移 */
    size_t used;    /* 数据区已用字节 */
    size_t cap;     /* 数据区可用字节 */
} rsf_arena_block;

typedef struct rsf_arena {
    rsf_arena_block* head;
    size_t blockSize;
    size_t used;    /* 所有块已用字节之和（可观测用） */
    size_t peak;    /* 上次 reset 之后的峰值 */
} rsf_arena;

extern rsf_arena rsf_ctx_arena;
#ifdef RSF_RUNTIME_IMPL
rsf_arena rsf_ctx_arena;
#endif

static inline unsigned char* rsf_arena_data(rsf_arena_block* b) {
    return (unsigned char*)(b + 1) + b->start;
}

static inline void* rsf_arena_alloc(void* user, size_t size, size_t align) {
    rsf_arena* a = (rsf_arena*)user;
    size_t need = size < 1 ? 1 : size;
    if (align < 8) align = 8;
    if (align > 64) align = 64;
    for (rsf_arena_block* b = a->head; b != NULL; b = b->next) {
        unsigned char* d = rsf_arena_data(b);
        size_t at = (size_t)(d + b->used);
        size_t pad = (size_t)(((at + (align - 1)) & ~(align - 1)) - at);
        if (b->used + pad + need <= b->cap) {
            b->used += pad + need;
            a->used += pad + need;
            if (a->used > a->peak) a->peak = a->used;
            return (void*)(d + b->used - need);
        }
    }
    {
        size_t cap = need > a->blockSize ? need : a->blockSize;
        rsf_arena_block* nb = (rsf_arena_block*)malloc(sizeof(rsf_arena_block) + cap + align);
        if (!nb) return NULL;
        {
            size_t base = (size_t)(nb + 1);
            size_t pad = (size_t)(((base + (align - 1)) & ~(align - 1)) - base);
            nb->next = a->head;
            nb->start = pad;
            nb->used = need;
            nb->cap = cap;
            a->head = nb;
        }
        a->used += need;
        if (a->used > a->peak) a->peak = a->used;
        return (void*)((unsigned char*)(nb + 1) + nb->start);
    }
}

static inline void* rsf_arena_resize(void* user, void* ptr, size_t oldSize, size_t newSize, size_t align) {
    if (!ptr) return rsf_arena_alloc(user, newSize, align);
    if (newSize <= oldSize) return ptr;
    {
        /* 最新一次分配能原地扩就扩（字符串拼接常走这条路） */
        rsf_arena* a = (rsf_arena*)user;
        rsf_arena_block* b = a->head;
        if (b != NULL) {
            unsigned char* d = rsf_arena_data(b);
            if ((unsigned char*)ptr + oldSize == d + b->used &&
                b->used + (newSize - oldSize) <= b->cap) {
                b->used += newSize - oldSize;
                a->used += newSize - oldSize;
                if (a->used > a->peak) a->peak = a->used;
                return ptr;
            }
        }
    }
    {
        void* np = rsf_arena_alloc(user, newSize, align);
        if (!np) return NULL;
        if (oldSize) memcpy(np, ptr, oldSize);
        return np;
    }
}

/* arena 不做单块回收：ptr/size/align 全部忽略（参数留着是为了满足分配器签名）。 */
static inline void rsf_arena_release(void* user, void* ptr, size_t size, size_t align) {
    (void)user; (void)ptr; (void)size; (void)align;
}

static inline void rsf_arena_reset(rsf_arena* a) {
    rsf_arena_block* b = a->head;
    while (b != NULL) {
        rsf_arena_block* n = b->next;
        free(b);
        b = n;
    }
    a->head = NULL;
    a->used = 0;
    a->peak = 0;
    rsf_cp_clear();   /* 串的字节被整块还回去了，码元表跟着作废 */
}

/* ---- 当前上下文 ---- */
static inline void rsf_ctx_process_exit(void);

static inline rsf_ctx* rsf_ctx_get(void) {
    if (rsf_ctx_cur != NULL) return rsf_ctx_cur;
    {
        rsf_ctx* c = &rsf_ctx_default;
        rsf_ctx_arena.head = NULL;
        rsf_ctx_arena.blockSize = 65536;
        c->alloc.alloc = rsf_arena_alloc;
        c->alloc.resize = rsf_arena_resize;
        c->alloc.free = rsf_arena_release;
        c->alloc.user = &rsf_ctx_arena;
        c->gc.collect = NULL;
        c->gc.bytes = NULL;
        c->gc.user = NULL;
        c->err = false;
        c->err_msg.len = 0;
        c->err_msg.units1 = 0;
        c->err_msg.data = NULL;
        c->handler = NULL;
        rsf_ctx_cur = c;
        atexit(rsf_ctx_process_exit);
        return c;
    }
}

static inline void rsf_ctx_process_exit(void) {
    rsf_arena_reset(&rsf_ctx_arena);
}

/* 宿主注入分配器（--lib / 嵌入）：上下文由调用方持有，这里一次分配都不做。 */
static inline void rsf_ctx_init(rsf_ctx* c, rsf_allocator a) {
    c->alloc = a;
    c->gc.collect = NULL;
    c->gc.bytes = NULL;
    c->gc.user = NULL;
    c->err = false;
    c->err_msg.len = 0;
    c->err_msg.units1 = 0;
            c->err_msg.data = NULL;
}

/* ---- GC 插座（阶段 77）：把「怎么回收」交给宿主，运行时只负责问你 ----
 * 语义约定：
 *   1) 只登记 collect 也行；bytes 不填则 memBytes()/memPeak() 返回 -1。
 *   2) 你的 collect 自己决定扫哪些根。运行时不会偷偷替你扫栈——那是平台相关的。
 *   3) 没有登记 GC 时 rsf_gc_collect() 返回 false（rsf 侧 gcCollect() 拿到 false），
 *      所以同一份源码在「有 GC / 没 GC」两种宿主下都能跑，只是返回值告诉它有没有真扫。
 */
static inline void rsf_ctx_set_gc(rsf_ctx* c, rsf_gc g) {
    c->gc = g;
}

static inline bool rsf_gc_enabled(void) {
    return rsf_ctx_get()->gc.collect != NULL;
}

static inline bool rsf_gc_collect(void) {
    rsf_ctx* c = rsf_ctx_get();
    if (c->gc.collect == NULL) return false;
    c->gc.collect(c->gc.user);
    return true;
}

/* ---- 栈边界：给保守 GC 用，**不碰任何平台 API** ----
 * 做法是「锚点」：线程起来后调一次 rsf_gc_thread_attach()，它把当前帧地址记成 anchor
 * （栈的**高地址**端，因为栈向下长）；分配器每次 rsf_alloc / rsf_grow 顺手把当时帧地址
 * 记进 sp（低地址端）。当前线程的栈区间就是 [sp, anchor]。
 *
 * 为什么不用 NtCurrentTeb / pthread_getattr_np / pthread_get_stackaddr_np：
 * 那些是**别的线程**的边界，只有等你把对方停住之后才有意义；而「停线程」在 JVM 宿主里
 * 是事故源（JVM 自己用信号做安全点）。rsf 走协作式：每个线程在分配点自己发布 sp，GC 只
 * 扫发布过的区间——不需要暂停、不需要信号、不需要平台头文件，Windows / Linux / Android /
 * macOS 同一份代码。
 *
 * 约束（明写，不做隐藏假设）：可能持有 rsf 对象引用的线程，起来后必须调一次 attach。
 * 唯一的盲区是「从不分配、却攥着别人给的引用」的线程——那种线程在 rsf 里不现实（任何 rsf
 * 函数调用都可能分配）；真遇到就让它调一次 gcCollect()，那就有了发布点。
 */
extern RSF_TLS void* rsf_gc_anchor;
extern RSF_TLS void* rsf_gc_sp;
#ifdef RSF_RUNTIME_IMPL
RSF_TLS void* rsf_gc_anchor = NULL;
RSF_TLS void* rsf_gc_sp = NULL;
#endif

/* 不能内联：这几个函数要靠自己的栈帧当边界。内联进调用者之后，「当前帧」和「锚点」
 * 落到同一帧里，谁高谁低就变成编译器的自由了（实测 -O1 下会反）。 */
#if defined(_MSC_VER)
#  define RSF_NOINLINE __declspec(noinline)
#elif defined(__GNUC__) || defined(__clang__)
#  define RSF_NOINLINE __attribute__((noinline))
#else
#  define RSF_NOINLINE
#endif

RSF_NOINLINE static void rsf_gc_thread_attach_at(void* anchor) {
    rsf_gc_anchor = anchor;
    rsf_gc_sp = anchor;
}

/* 用宏而不是函数：锚点必须是**调用者自己**的帧地址。写成函数的话，记下来的是 attach
 * 自己的帧（比调用者低一格），GC 在调用者帧里跑时区间就反了——那是真的会漏扫。 */
#define rsf_gc_thread_attach()                                                    \
    do {                                                                          \
        char rsf_gc_anchor_local_;                                                \
        rsf_gc_thread_attach_at((void*)&rsf_gc_anchor_local_);                    \
    } while (0)

static inline void rsf_gc_thread_detach(void) {
    rsf_gc_anchor = NULL;
    rsf_gc_sp = NULL;
}

/* 这条线程发布过根区间没有。重复 attach 会把锚点往深处挪，扫的范围就变小了，
 * 所以入口处统一写成 if (!attached) attach; */
static inline bool rsf_gc_thread_attached(void) {
    return rsf_gc_anchor != NULL;
}

/* 当前线程的栈区间：*lo 低地址（往里扫的起点），*hi 高地址（anchor）。
 * 没 attach 过、或者区间反了（GC 在比 anchor 更浅的帧里跑）就返回 false——
 * 返回 false 时 GC 该把这条线程当成「不持有对象」，而不是猜个范围乱扫。 */
RSF_NOINLINE static bool rsf_gc_stack_bounds(void** lo, void** hi) {
    char here;
    uintptr_t low;
    uintptr_t high;
    const uintptr_t step = (uintptr_t)sizeof(void*);
    if (rsf_gc_anchor == NULL) return false;
    low = (rsf_gc_sp != NULL && rsf_gc_sp < (void*)&here) ? (uintptr_t)rsf_gc_sp : (uintptr_t)&here;
    high = (uintptr_t)rsf_gc_anchor;
    if (!(low < high)) return false;
    /* 对到指针宽度的边界再交出去：栈上取到的地址不一定是 8 的倍数，
     * 调用者按 sizeof(void*) 步长扫时，没对齐会把紧挨着的那个槽跳过去。 */
    *lo = (void*)(low & ~(step - 1));
    *hi = (void*)(high & ~(step - 1));
    return true;
}

/* ---- 内置标记-清扫 GC：`gc on` 时才装上（阶段 77 步骤 C）----
 * 模型照 Lua 5.x 的经典实现：**非移动**、标记-清扫、根是保守扫出来的。
 * 和 Lua 的两处差别都是环境逼出来的：
 *   1) 根集合不靠类型元表，而是靠上一节的锚点区间扫当前线程的栈（[sp, anchor]）；
 *   2) 对象没有类型布局，所以负载按字扫描（保守），内部指针（切片视图）也算引用。
 *
 * 只在 rsf_gc_heap_install() 之后接管分配：`gc off` 的产物一行都不会走到这里。
 * 三条不变量：
 *   1) 只有 install 之后分配的对象在链上；install 之前的归 arena 管，不归 GC 管。
 *   2) 拿不到栈区间（没 attach）时**一块都不回收**——宁可漏，不能把活对象当垃圾。
 *   3) 清扫期间不回调用户代码（没有 finalizer），所以不需要护栏。
 */
typedef struct rsf_gc_obj {
    struct rsf_gc_obj* next;
    struct rsf_gc_obj* prev;
    size_t size;
    int64_t mark;
} rsf_gc_obj;

typedef struct {
    uintptr_t start;
    uintptr_t end;
    rsf_gc_obj* obj;
} rsf_gc_span;

static rsf_gc_obj* rsf_gc_objs = NULL;
static bool rsf_gc_heap_active = false;
static bool rsf_gc_busy = false;
static size_t rsf_gc_live = 0;
static size_t rsf_gc_live_peak = 0;
static int64_t rsf_gc_cycles = 0;
static rsf_gc_obj** rsf_gc_gray = NULL;
static size_t rsf_gc_gray_len = 0;
static size_t rsf_gc_gray_cap = 0;

static void* rsf_gc_heap_alloc(void* user, size_t size, size_t align) {
    rsf_gc_obj* o;
    size_t bytes = size < 1 ? 1 : size;
    (void)user;
    (void)align;
    o = (rsf_gc_obj*)malloc(sizeof(rsf_gc_obj) + bytes);
    if (!o) return NULL;
    o->size = bytes;
    o->mark = 0;
    o->prev = NULL;
    o->next = rsf_gc_objs;
    if (o->next) o->next->prev = o;
    rsf_gc_objs = o;
    rsf_gc_live += bytes;
    if (rsf_gc_live > rsf_gc_live_peak) rsf_gc_live_peak = rsf_gc_live;
    return (void*)(o + 1);
}

static void rsf_gc_heap_free(void* user, void* ptr, size_t size, size_t align) {
    rsf_gc_obj* o;
    (void)user;
    (void)size;
    (void)align;
    if (!ptr) return;
    o = ((rsf_gc_obj*)ptr) - 1;
    if (o->prev) o->prev->next = o->next; else rsf_gc_objs = o->next;
    if (o->next) o->next->prev = o->prev;
    rsf_gc_live -= o->size;
    free(o);
}

static void* rsf_gc_heap_resize(void* user, void* ptr, size_t oldSize, size_t newSize, size_t align) {
    void* q;
    size_t bytes;
    if (newSize < 1) return ptr;
    if (!ptr) return rsf_gc_heap_alloc(user, newSize, align);
    bytes = oldSize < newSize ? oldSize : newSize;
    q = rsf_gc_heap_alloc(user, newSize, align);
    if (!q) return NULL;
    memcpy(q, ptr, bytes);
    rsf_gc_heap_free(user, ptr, oldSize, align);
    return q;
}

static int rsf_gc_span_cmp(const void* a, const void* b) {
    uintptr_t x = ((const rsf_gc_span*)a)->start;
    uintptr_t y = ((const rsf_gc_span*)b)->start;
    return x < y ? -1 : (x > y ? 1 : 0);
}

/* 内部指针也算（切片是指向大字符串中间的视图）：找包含 p 的那个对象。 */
static rsf_gc_obj* rsf_gc_find(const rsf_gc_span* spans, size_t n, uintptr_t p) {
    size_t lo = 0;
    size_t hi = n;
    if (n == 0) return NULL;
    while (lo < hi) {
        size_t mid = (lo + hi) / 2;
        if (spans[mid].start <= p) lo = mid + 1; else hi = mid;
    }
    if (lo == 0) return NULL;
    lo--;
    return p < spans[lo].end ? spans[lo].obj : NULL;
}

static void rsf_gc_push(rsf_gc_obj* o) {
    if (rsf_gc_gray_len == rsf_gc_gray_cap) {
        size_t cap = rsf_gc_gray_cap ? rsf_gc_gray_cap * 2 : 128;
        rsf_gc_obj** g = (rsf_gc_obj**)realloc(rsf_gc_gray, cap * sizeof(rsf_gc_obj*));
        if (!g) return;
        rsf_gc_gray = g;
        rsf_gc_gray_cap = cap;
    }
    rsf_gc_gray[rsf_gc_gray_len++] = o;
}

static void rsf_gc_mark(const rsf_gc_span* spans, size_t n, void* w) {
    rsf_gc_obj* o;
    if (!w) return;
    o = rsf_gc_find(spans, n, (uintptr_t)w);
    if (!o || o->mark) return;
    o->mark = 1;
    rsf_gc_push(o);
}

/* 扫一轮：标根 -> 追灰 -> 清未标。返回 false 表示「这轮什么也没做」（没 attach 等）。 */
static bool rsf_gc_cycle(void) {
    rsf_gc_obj* o;
    rsf_gc_span* spans;
    size_t n = 0;
    size_t i = 0;
    void* lo = NULL;
    void* hi = NULL;
    if (!rsf_gc_heap_active || rsf_gc_busy) return false;
    for (o = rsf_gc_objs; o; o = o->next) n++;
    if (n == 0) {
        rsf_gc_cycles++;
        return true;
    }
    if (!rsf_gc_stack_bounds(&lo, &hi)) return false;
    spans = (rsf_gc_span*)malloc(n * sizeof(rsf_gc_span));
    if (!spans) return false;
    for (o = rsf_gc_objs; o && i < n; o = o->next, i++) {
        spans[i].start = (uintptr_t)(o + 1);
        spans[i].end = (uintptr_t)(o + 1) + o->size;
        spans[i].obj = o;
        o->mark = 0;
    }
    qsort(spans, n, sizeof(rsf_gc_span), rsf_gc_span_cmp);
    rsf_gc_busy = true;
    rsf_gc_gray_len = 0;
    {
        void** p = (void**)lo;
        void** end = (void**)hi;
        for (; p < end; p++) rsf_gc_mark(spans, n, *p);
    }
    while (rsf_gc_gray_len > 0) {
        rsf_gc_obj* cur = rsf_gc_gray[--rsf_gc_gray_len];
        uintptr_t* w = (uintptr_t*)(cur + 1);
        size_t words = cur->size / sizeof(uintptr_t);
        size_t k;
        for (k = 0; k < words; k++) rsf_gc_mark(spans, n, (void*)w[k]);
    }
    o = rsf_gc_objs;
    while (o) {
        rsf_gc_obj* nx = o->next;
        if (o->mark) {
            o->mark = 0;
        } else {
            if (o->prev) o->prev->next = nx; else rsf_gc_objs = nx;
            if (nx) nx->prev = o->prev;
            rsf_gc_live -= o->size;
            free(o);
        }
        o = nx;
    }
    free(spans);
    rsf_gc_busy = false;
    rsf_cp_clear();   /* 扫掉的串可能就在码元表里 */
    rsf_gc_cycles++;
    return true;
}

static void rsf_gc_builtin_collect(void* user) {
    (void)user;
    rsf_gc_cycle();
}

static size_t rsf_gc_builtin_bytes(void* user) {
    (void)user;
    return rsf_gc_live;
}

/* 装上内置 GC：换分配器 + 登记 collect/bytes。宿主之后再 rsf_ctx_set_gc 就是覆盖它
 * （用户自己写的 GC 优先，见 DESIGN-77「让用户自己实现 GC」）。 */
static inline void rsf_gc_heap_install(void) {
    rsf_ctx* c = rsf_ctx_get();
    rsf_gc g;
    if (rsf_gc_heap_active) return;                  /* 幂等：重复调用无所谓 */
    if (c->gc.collect != NULL) return;               /* 宿主已经装了自己的 GC：它优先 */
    if (c->alloc.alloc != rsf_arena_alloc) return;   /* 宿主换过分配器：别抢 */
    rsf_gc_heap_active = true;
    c->alloc.alloc = rsf_gc_heap_alloc;
    c->alloc.resize = rsf_gc_heap_resize;
    c->alloc.free = rsf_gc_heap_free;
    c->alloc.user = NULL;
    g.collect = rsf_gc_builtin_collect;
    g.bytes = rsf_gc_builtin_bytes;
    g.user = NULL;
    rsf_ctx_set_gc(c, g);
}

static inline bool rsf_gc_heap_enabled(void) {
    return rsf_gc_heap_active;
}

static inline int64_t rsf_gc_heap_live(void) {
    return (int64_t)rsf_gc_live;
}

static inline int64_t rsf_gc_heap_peak(void) {
    return (int64_t)rsf_gc_live_peak;
}

static inline int64_t rsf_gc_heap_cycles(void) {
    return rsf_gc_cycles;
}

static inline void rsf_ctx_use(rsf_ctx* c) {
    rsf_ctx_cur = c;
}


/* ---- 可观测（阶段 77 步骤 0）：只统计默认 arena；宿主换了分配器就返回 -1 ----
 * 用途：`memBytes()` / `memPeak()` 内置函数，以及压力测试里断言「回调不涨内存」。
 * 注意 arena 不做单块回收，所以 used 只增不减，直到 rsf_arena_reset（进程退出
 * 或宿主显式调用）才归零。
 */
static inline int64_t rsf_mem_bytes(void) {
    rsf_ctx* c = rsf_ctx_get();
    if (c->gc.bytes != NULL) return (int64_t)c->gc.bytes(c->gc.user);
    if (c->alloc.alloc != rsf_arena_alloc) return -1;
    return (int64_t)((rsf_arena*)c->alloc.user)->used;
}

static inline int64_t rsf_mem_peak(void) {
    rsf_ctx* c = rsf_ctx_get();
    if (c->gc.bytes != NULL) return (int64_t)c->gc.bytes(c->gc.user);
    if (c->alloc.alloc != rsf_arena_alloc) return -1;
    return (int64_t)((rsf_arena*)c->alloc.user)->peak;
}

/* ---- 分配器入口：生成代码与运行时都走这里 ---- */
static inline void* rsf_alloc(size_t n) {
    char rsf_gc_here;
    rsf_ctx* c = rsf_ctx_get();
    rsf_gc_sp = (void*)&rsf_gc_here;  /* 发布栈指针：保守 GC 扫 [sp, anchor] 就够了 */
    void* p = c->alloc.alloc(c->alloc.user, n < 1 ? 1 : n, 16);
    if (!p) {
        fprintf(stderr, "rsf: 内存不足（%zu 字节）", n);
        fputc(10, stderr);
        exit(1);
    }
    return p;
}

static inline void rsf_dealloc(void* p, size_t n) {
    rsf_ctx* c;
    if (!p) return;
    c = rsf_ctx_get();
    c->alloc.free(c->alloc.user, p, n, 16);
}

static inline void* rsf_malloc(size_t n) {
    return rsf_alloc(n);
}

static inline void* rsf_calloc(size_t count, size_t elem) {
    size_t n = count * elem;
    void* p = rsf_alloc(n);
    memset(p, 0, n);
    return p;
}

/* 增长：旧容量显式传进来（类型是静态的，调用点一定知道），不用分配头。 */
static inline void* rsf_grow(void* ptr, size_t oldSize, size_t newSize) {
    rsf_ctx* c;
    void* p;
    char rsf_gc_here;
    if (newSize < 1) return ptr;
    c = rsf_ctx_get();
    rsf_gc_sp = (void*)&rsf_gc_here;
    p = c->alloc.resize(c->alloc.user, ptr, oldSize, newSize, 16);
    if (!p) {
        fprintf(stderr, "rsf: 内存不足（%zu 字节）", newSize);
        fputc(10, stderr);
        exit(1);
    }
    return p;
}

"""

        private val RUNTIME = """
            /* 由 rsf build --c 生成（阶段 52 的 C 后端，native 线第一刀） */
            #include <stdint.h>
            #include <stdbool.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            #include <math.h>
            #include <setjmp.h>

            #ifndef RSF_STR_DEFINED
            #define RSF_STR_DEFINED
            typedef struct { int64_t len; int64_t units1; const char* data; } rsf_str;
            #endif
            typedef struct { int64_t len; void* data; } rsf_arr;

            static rsf_arr rsf_arr_new(int64_t len, int64_t elem) {
                rsf_arr a; a.len = len; a.data = calloc((size_t)(len > 0 ? len : 0), (size_t)elem); return a;
            }

            /* 阶段 73：units1 = UTF-16 码元数 + 1，0 表示「还不知道」（用到时再数一遍）。
             * 存 +1 是为了让漏初始化的地方退化成 O(n) 而不是算错 0 个码元。 */
            static rsf_str rsf_str_lit_u(const char* data, int64_t len, int64_t units1) {
                rsf_str s; s.len = len; s.units1 = units1; s.data = data; return s;
            }

            static rsf_str rsf_str_lit(const char* data, int64_t len) {
                return rsf_str_lit_u(data, len, 0);
            }

            /* 末尾补一个 0：rsf 的 string 是长度前缀、不保证以 0 结尾，但宿主 shim
             * （stat / fopen / opendir / getenv）要的是 C 字符串。补上不改变 len。 */
            static rsf_str rsf_concat(rsf_str a, rsf_str b) {
                char* p = (char*)malloc((size_t)(a.len + b.len + 1));
                if (a.len > 0) memcpy(p, a.data, (size_t)a.len);
                if (b.len > 0) memcpy(p + a.len, b.data, (size_t)b.len);
                p[a.len + b.len] = 0;
                rsf_str s; s.len = a.len + b.len; s.data = p;
                s.units1 = (a.units1 > 0 && b.units1 > 0) ? a.units1 + b.units1 - 1 : 0;
                return s;
            }

            static rsf_str rsf_force_str(rsf_str v) {
                if (!v.data) { fprintf(stderr, "rsf: 空值上做了 '!!'（运行期错误）"); fputc(10, stderr); exit(1); }
                return v;
            }

            static void* rsf_force_ptr(void* v) {
                if (!v) { fprintf(stderr, "rsf: 空值上做了 '!!'（运行期错误）"); fputc(10, stderr); exit(1); }
                return v;
            }

            static bool rsf_err = false;
            static rsf_str rsf_err_msg;

            static void rsf_set_err(rsf_str msg) {
                if (!rsf_err) { rsf_err = true; rsf_err_msg = msg; }
            }

            /* try 压栈的 setjmp 帧。fail = 置错 + longjmp 到最近一帧，
               语义上就是异常：嵌套调用里的失败会一层层跳过，不会拿零值继续算。 */
            struct rsf_handler { jmp_buf jb; struct rsf_handler* prev; };

            /* 这行只能写 static：runtimeLibSource() 会把行首的 "static " 改写成
               "static inline "（多 TU 包含安全），写成 static inline 会变成
               写上多余的 inline 会直接编不过（见 CBackendTest 的双 inline 断言）。 */
            static void rsf_throw(void) {
                rsf_ctx* c = rsf_ctx_get();
                if (c->handler != NULL) { longjmp(c->handler->jb, 1); }
                /* 没有 try 接：与 JVM 侧未捕获异常同一口径 */
                fputs("运行期错误: ", stderr);
                if (c->err_msg.len > 0 && c->err_msg.data != NULL) {
                    fwrite(c->err_msg.data, 1, (size_t)c->err_msg.len, stderr);
                }
                fputc(10, stderr);
                exit(1);
            }

            /* 把码元数补上（就这一次 O(n)），之后 length() / charAt 都是 O(1)。
             * 纯 ASCII 时「码元数 == 字节数」，charAt 可以直接按下标取字节。 */
            static int64_t rsf_u16_len(const char* data, int64_t n);
            static rsf_str rsf_str_settle(rsf_str s) {
                if (s.units1 <= 0 && s.data != NULL && s.len > 0) { s.units1 = rsf_u16_len(s.data, s.len) + 1; }
                return s;
            }

            static rsf_str rsf_str_c(const char* s) {
                rsf_str r = {0, 0, NULL}; r.data = s; r.len = (int64_t)strlen(s); return rsf_str_settle(r);
            }

            static rsf_str rsf_str_null(void) {
                rsf_str s = {0, 0, NULL}; s.len = 0; s.data = NULL; return s;
            }

            static bool rsf_str_eq(rsf_str a, rsf_str b) {
                if (a.data == NULL || b.data == NULL) return a.data == b.data;
                return a.len == b.len && memcmp(a.data, b.data, (size_t)a.len) == 0;
            }

            /* ---- 阶段 73：字符串的计数单位是 UTF-16 码元（与 java.lang.String 一致）----
             * 存储仍然是 UTF-8 字节；下面几个小函数在「码元下标」和「字节偏移」之间换算。
             * 于是：BMP 字符算 1 个码元、emoji 这类星面字符算 2 个码元，charAt 在代理对
             * 上返回高/低代理——与 JVM 侧逐字一致。要按字节干活就显式用 stringBytes()。
             */
            static int64_t rsf_u16_step(const unsigned char* p, int64_t left) {
                unsigned char c = p[0];
                int64_t step = 1;
                if (c >= 0x80) {
                    if ((c & 0xE0) == 0xC0) step = 2;
                    else if ((c & 0xF0) == 0xE0) step = 3;
                    else if ((c & 0xF8) == 0xF0) step = 4;
                }
                return step <= left ? step : 1;
            }

            static uint32_t rsf_u16_cp(const unsigned char* p, int64_t step) {
                uint32_t c = p[0];
                if (step == 1) return c;
                if (step == 2) return (uint32_t)(((c & 0x1Fu) << 6) | (p[1] & 0x3Fu));
                if (step == 3) return (uint32_t)(((c & 0x0Fu) << 12) | ((p[1] & 0x3Fu) << 6) | (p[2] & 0x3Fu));
                return (uint32_t)(((c & 0x07u) << 18) | ((p[1] & 0x3Fu) << 12) | ((p[2] & 0x3Fu) << 6) | (p[3] & 0x3Fu));
            }

            static int64_t rsf_u16_len(const char* data, int64_t n) {
                const unsigned char* p = (const unsigned char*)data;
                int64_t i = 0, units = 0;
                while (i < n) {
                    int64_t step = rsf_u16_step(p + i, n - i);
                    uint32_t cp = rsf_u16_cp(p + i, step);
                    units += (cp > 0xFFFF) ? 2 : 1;
                    i += step;
                }
                return units;
            }

            /* 长串的码元表：命中就 O(1)，没命中就建一张（一次 O(n)）。 */
            static rsf_cp_entry* rsf_cp_find(rsf_str s) {
                rsf_cp_entry* e = rsf_cp_head;
                while (e != NULL) {
                    if (e->data == s.data && e->len == s.len) return e;
                    e = e->next;
                }
                return NULL;
            }

            static rsf_cp_entry* rsf_cp_build(rsf_str s) {
                int64_t units = rsf_u16_len(s.data, s.len);
                const unsigned char* p = (const unsigned char*)s.data;
                int64_t at = 0, u = 0;
                rsf_cp_entry* e;
                if (units <= 0) return NULL;
                if (rsf_cp_count >= RSF_CP_MAX_ENTRIES) rsf_cp_clear();
                e = (rsf_cp_entry*)rsf_cp_raw_alloc(sizeof(rsf_cp_entry));
                if (e == NULL) return NULL;
                e->cps = (uint32_t*)rsf_cp_raw_alloc((size_t)units * sizeof(uint32_t));
                if (e->cps == NULL) { rsf_cp_raw_free(e); return NULL; }
                e->data = s.data; e->len = s.len; e->units = units;
                while (at < s.len) {
                    int64_t step = rsf_u16_step(p + at, s.len - at);
                    uint32_t cp = rsf_u16_cp(p + at, step);
                    if (cp > 0xFFFF) {
                        uint32_t v = cp - 0x10000u;
                        e->cps[u++] = 0xD800u + (v >> 10);
                        e->cps[u++] = 0xDC00u + (v & 0x3FFu);
                    } else {
                        e->cps[u++] = cp;
                    }
                    at += step;
                }
                e->next = rsf_cp_head; rsf_cp_head = e; rsf_cp_count++;
                return e;
            }

            /* 码元下标 → 字节偏移（超出末尾就停在末尾） */
            static int64_t rsf_u16_to_byte(const char* data, int64_t n, int64_t units) {
                const unsigned char* p = (const unsigned char*)data;
                int64_t i = 0, u = 0;
                while (i < n && u < units) {
                    int64_t step = rsf_u16_step(p + i, n - i);
                    uint32_t cp = rsf_u16_cp(p + i, step);
                    u += (cp > 0xFFFF) ? 2 : 1;
                    i += step;
                }
                return i;
            }

            /* 阶段 73：String 的成员方法。length/charAt/substring/indexOf 一律按 UTF-16
             * 码元算（与 java.lang.String 一致），字节视图走 stringBytes()。ASCII 之外
             * 一个汉字算 1 个码元、一个 emoji 算 2 个。 */
            /* 码元数：字面量与拼接在创建时就算好（units1 = 码元数 + 1），
             * 其余来源（文件、trim、大小写转换…）留 0，这里退化成数一遍。 */
            static int64_t rsf_u16_len_of(rsf_str s) {
                rsf_cp_entry* e;
                if (s.units1 > 0) return s.units1 - 1;
                if (s.len < RSF_CP_MIN_LEN) return rsf_u16_len(s.data, s.len);
                e = rsf_cp_find(s);
                if (e == NULL) e = rsf_cp_build(s);
                return e != NULL ? e->units : rsf_u16_len(s.data, s.len);
            }

            static int32_t rsf_str_len(rsf_str s) { return (int32_t)rsf_u16_len_of(s); }


            /* 阶段 73：byteLength(s) —— 字节数就是 rsf_str.len 本身，O(1)。 */
            static int32_t rsf_str_byte_len(rsf_str s) { return (int32_t)s.len; }

            static rsf_arr rsf_str_bytes(rsf_str s) {
                rsf_arr a;
                a.len = s.len;
                a.data = malloc((size_t)(s.len > 0 ? s.len : 1));
                if (s.len > 0) memcpy(a.data, s.data, (size_t)s.len);
                return a;
            }

            static uint16_t rsf_str_char_at(rsf_str s, int32_t i) {
                const unsigned char* p = (const unsigned char*)s.data;
                int64_t at = 0, unit = 0;
                if (i < 0) return 0;
                /* 码元数 == 字节数 ⇒ 全是 ASCII，码元下标就是字节下标（自举词法器全靠这条） */
                if (s.units1 > 0 && s.units1 - 1 == s.len) {
                    if ((int64_t)i >= s.len) return 0;
                    return (uint16_t)(unsigned char)s.data[i];
                }
                /* 非 ASCII 的长串走码元表：自举编自己时源文件带中文注释，逐个 charAt
                 * 现扫 UTF-8 会退化成 O(n²)（实测从 1 秒涨到两分钟以上）。 */
                if (s.len >= RSF_CP_MIN_LEN) {
                    rsf_cp_entry* e = rsf_cp_find(s);
                    if (e == NULL) e = rsf_cp_build(s);
                    if (e != NULL) return ((int64_t)i < e->units) ? (uint16_t)e->cps[i] : 0;
                }
                while (at < s.len) {
                    int64_t step = rsf_u16_step(p + at, s.len - at);
                    uint32_t cp = rsf_u16_cp(p + at, step);
                    if (cp > 0xFFFF) {
                        uint32_t v = cp - 0x10000u;
                        if (unit == (int64_t)i) return (uint16_t)(0xD800u + (v >> 10));
                        if (unit + 1 == (int64_t)i) return (uint16_t)(0xDC00u + (v & 0x3FFu));
                        unit += 2;
                    } else {
                        if (unit == (int64_t)i) return (uint16_t)cp;
                        unit += 1;
                    }
                    at += step;
                }
                return 0;
            }

            static int64_t rsf_str_unit_len(rsf_str s) { return rsf_u16_len(s.data, s.len); }

            static rsf_str rsf_str_substr(rsf_str s, int32_t from, int32_t to) {
                int64_t units = rsf_str_unit_len(s);
                int64_t a, b;
                if (from < 0) from = 0;
                if ((int64_t)to > units) to = (int32_t)units;
                if (to < from) to = from;
                /* 边界落在代理对中间时（a.substring(1, 3) 切在 emoji 里），
                 * UTF-8 存不下孤立代理项，这里会外扩到整个码点：native 侧的
                 * substring 只以码点边界收尾，永远不产生非法 UTF-8。 */
                a = rsf_u16_to_byte(s.data, s.len, from);
                b = rsf_u16_to_byte(s.data, s.len, to);
                rsf_str r = {0, 0, NULL};
                r.len = b - a; r.data = (char*)s.data + a;
                /* 码元数不在这里数：切一次数一遍会让「每认一个 token 切一刀」退化成 O(n²)
                 * （实测自举编自己从 1 秒涨到两分钟以上）。只有父串已知是纯 ASCII 时
                 * 才能 O(1) 推出来（ASCII 里码元数就是字节数），否则留 0 让 length() 现数。 */
                r.units1 = (s.units1 > 0 && s.units1 - 1 == s.len) ? (r.len + 1) : 0;
                return r;
            }

            /* 阶段 73d：`s[a..b]`（零拷贝视图）。越界钳进 [0, n]，to 负数当 n，to < from 当空串
             * —— 与解释器、字节码后端的 `__rsf_slice` 同一套钳位规则。 */
            static rsf_str rsf_str_slice(rsf_str s, int32_t from, int32_t to) {
                int64_t units = rsf_u16_len_of(s);
                int64_t f = from < 0 ? 0 : (from > units ? units : from);
                int64_t t = to < 0 ? units : (to > units ? units : to);
                if (t < f) t = f;
                return rsf_str_substr(s, (int32_t)f, (int32_t)t);
            }

            static int32_t rsf_str_index_of(rsf_str s, rsf_str p) {
                if (p.len <= 0) { return 0; }
                for (int64_t i = 0; i + p.len <= s.len; i++) {
                    if (memcmp(s.data + i, p.data, (size_t)p.len) == 0) { return (int32_t)rsf_u16_len(s.data, i); }
                }
                return -1;
            }

            static int32_t rsf_str_last_index_of(rsf_str s, rsf_str p) {
                if (p.len <= 0) { return (int32_t)rsf_str_unit_len(s); }
                for (int64_t i = s.len - p.len; i >= 0; i--) {
                    if (memcmp(s.data + i, p.data, (size_t)p.len) == 0) { return (int32_t)rsf_u16_len(s.data, i); }
                }
                return -1;
            }

            static rsf_str rsf_str_trim(rsf_str s) {
                int64_t a = 0;
                int64_t b = s.len;
                while (a < b && (s.data[a] == 32 || s.data[a] == 9 || s.data[a] == 10 || s.data[a] == 13)) { a++; }
                while (b > a && (s.data[b - 1] == 32 || s.data[b - 1] == 9 || s.data[b - 1] == 10 || s.data[b - 1] == 13)) { b--; }
                rsf_str r = {0, 0, NULL}; r.data = s.data + a; r.len = b - a; return r;
            }

            static rsf_str rsf_str_repeat(rsf_str s, int32_t times) {
                int64_t n = s.len * (int64_t)times;
                char* buf = (char*)malloc((size_t)n + 1);
                for (int32_t i = 0; i < times; i++) { memcpy(buf + s.len * i, s.data, (size_t)s.len); }
                buf[n] = 0;
                rsf_str r = {0, 0, NULL}; r.data = buf; r.len = n; return r;
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

            static int32_t rsf_str_compare(rsf_str a, rsf_str b) {
                int64_t n = a.len < b.len ? a.len : b.len;
                for (int64_t i = 0; i < n; i++) {
                    unsigned char ca = (unsigned char)a.data[i], cb = (unsigned char)b.data[i];
                    if (ca != cb) return (int32_t)ca - (int32_t)cb;
                }
                return a.len < b.len ? -1 : (a.len > b.len ? 1 : 0);
            }

            static rsf_str rsf_str_upper(rsf_str s) {
                char* p = (char*)malloc((size_t)(s.len > 0 ? s.len : 1));
                for (int64_t i = 0; i < s.len; i++) {
                    char c = ((const char*)s.data)[i];
                    p[i] = (c >= 'a' && c <= 'z') ? (char)(c - 32) : c;
                }
                rsf_str r = {0, 0, NULL}; r.len = s.len; r.data = p; return r;
            }

            static rsf_str rsf_str_lower(rsf_str s) {
                char* p = (char*)malloc((size_t)(s.len > 0 ? s.len : 1));
                for (int64_t i = 0; i < s.len; i++) {
                    char c = ((const char*)s.data)[i];
                    p[i] = (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c;
                }
                rsf_str r = {0, 0, NULL}; r.len = s.len; r.data = p; return r;
            }

            static bool rsf_str_empty(rsf_str s) { return s.len == 0; }

            static rsf_str rsf_bool_to_str(bool v) {
                return v ? rsf_str_lit("true", 4) : rsf_str_lit("false", 5);
            }

            static rsf_str rsf_long_to_str(int64_t v) {
                char* buf = (char*)malloc(32);
                int n = snprintf(buf, 32, "%lld", (long long)v);
                rsf_str s = {0, 0, NULL}; s.len = n; s.data = buf; return s;
            }

            static void rsf_print_s(rsf_str s) { fwrite(s.data, 1, (size_t)s.len, stdout); }
            static void rsf_print_i(int32_t v)  { printf("%d", (int)v); }
            static void rsf_print_l(int64_t v)  { printf("%lld", (long long)v); }
            static void rsf_print_b(bool v)     { fputs(v ? "true" : "false", stdout); }
            static void rsf_print_d(double v)   { printf("%.15g", v); }
            static void rsf_println_d(double v) { printf("%.15g", v); putchar(10); }
            static void rsf_println_s(rsf_str s) { rsf_print_s(s); putchar('\n'); }
            static void rsf_println_i(int32_t v)  { rsf_print_i(v); putchar('\n'); }
            static void rsf_println_l(int64_t v)  { rsf_print_l(v); putchar('\n'); }
            static void rsf_println_b(bool v)     { rsf_print_b(v); putchar('\n'); }
            static void rsf_println_empty(void)   { putchar('\n'); }

            // 类文件写出器要浮点常量的 IEEE 位模式：用 union 做 bit-cast
            static int32_t rsf_float_bits(float v) {
                union { float f; int32_t i; } u;
                u.f = v;
                return u.i;
            }

            static int64_t rsf_double_bits(double v) {
                union { double d; int64_t i; } u;
                u.d = v;
                return u.i;
            }

            static rsf_str rsf_char_to_str(uint16_t ch) {
                char* b = (char*)malloc(4);
                int n = 0;
                if (ch < 0x80) { b[n++] = (char)ch; }
                else if (ch < 0x800) {
                    b[n++] = (char)(0xC0 | (ch >> 6));
                    b[n++] = (char)(0x80 | (ch & 0x3F));
                } else {
                    b[n++] = (char)(0xE0 | (ch >> 12));
                    b[n++] = (char)(0x80 | ((ch >> 6) & 0x3F));
                    b[n++] = (char)(0x80 | (ch & 0x3F));
                }
                rsf_str s = {0, 0, NULL}; s.len = n; s.data = b; return s;
            }

            /* rsf 的 string 带长度、不一定以 0 结尾：调 libc 前先拷成 C 字符串 */
            static char* rsf_zstr(rsf_str s) {
                static char* buf = NULL;
                static size_t cap = 0;
                size_t need = (size_t)s.len + 1;
                if (need > cap) {
                    size_t ncap = need * 2 + 32;
                    buf = (char*)rsf_grow(buf, cap, ncap);
                    cap = ncap;
                }
                if (s.len > 0 && s.data) memcpy(buf, s.data, (size_t)s.len);
                buf[s.len] = 0;
                return buf;
            }

            /* 与 JVM 侧 Builtins.FileRuntime 同一套消息格式：
               <name>: 文件操作失败 '<path>'：<reason>；缺文件时 JDK 给的 reason 就是路径本身。 */
            static rsf_str rsf_file_fail(rsf_str name, rsf_str path) {
                rsf_str a = rsf_concat(name, rsf_str_c(": 文件操作失败 '"));
                rsf_str b = rsf_concat(a, path);
                rsf_str c = rsf_concat(b, rsf_str_c("'："));
                return rsf_concat(c, path);
            }

            static char* rsf_read_all(rsf_str path, int64_t* outLen) {
                FILE* f = fopen(rsf_zstr(path), "rb");
                if (!f) { *outLen = 0; return NULL; }
                fseek(f, 0, SEEK_END);
                long n = ftell(f);
                fseek(f, 0, SEEK_SET);
                char* buf = (char*)malloc((size_t)n + 1);
                size_t got = fread(buf, 1, (size_t)n, f);
                fclose(f);
                buf[got] = 0;
                *outLen = (int64_t)got;
                return buf;
            }

            static rsf_str rsf_read_text(rsf_str path) {
                int64_t n = 0;
                char* b = rsf_read_all(path, &n);
                if (!b) { rsf_set_err(rsf_file_fail(rsf_str_c("readText"), path)); }
                rsf_str s = {0, 0, NULL};
                s.len = n; s.data = b;
                return rsf_str_settle(s);   /* 读一次数一遍，之后按下标扫才是 O(1) */
            }

            static rsf_arr rsf_read_bytes(rsf_str path) {
                int64_t n = 0;
                char* b = rsf_read_all(path, &n);
                if (!b) { rsf_set_err(rsf_file_fail(rsf_str_c("readBytes"), path)); }
                rsf_arr a;
                a.len = n; a.data = b;
                return a;
            }

            static bool rsf_write_text(rsf_str path, rsf_str text) {
                FILE* f = fopen(rsf_zstr(path), "wb");
                if (!f) { rsf_set_err(rsf_file_fail(rsf_str_c("writeText"), path)); return false; }
                if (text.len > 0) { fwrite(text.data, 1, (size_t)text.len, f); }
                fclose(f);
                return true;
            }

            static bool rsf_write_bytes(rsf_str path, rsf_arr bytes) {
                FILE* f = fopen(rsf_zstr(path), "wb");
                if (!f) { rsf_set_err(rsf_file_fail(rsf_str_c("writeBytes"), path)); return false; }
                if (bytes.len > 0) { fwrite(bytes.data, 1, (size_t)bytes.len, f); }
                fclose(f);
                return true;
            }

            static int32_t rsf_parse_int(rsf_str s) {
                char* cs = rsf_zstr(s);
                char* end = NULL;
                long v = strtol(cs, &end, 10);
                /* Java 的 Integer.parseInt 在越界时抛 NumberFormatException；
                   strtol 只保证 long 范围内不溢出，32 位越界必须自己查。 */
                if (end == cs || end == NULL || *end != 0 || v > 2147483647L || v < (-2147483647L - 1L)) {
                    int cap = (int)s.len + 48;
                    char* m = (char*)malloc((size_t)cap);
                    int n = snprintf(m, (size_t)cap, "parseInt: 不是合法的整数：'%s'", (const char*)s.data);
                    rsf_str msg = {0, 0, NULL}; msg.data = m; msg.len = n;
                    rsf_set_err(msg);
                    return 0;
                }
                return (int32_t)v;
            }
            static int64_t rsf_parse_long(rsf_str s) { return (int64_t)strtoll(rsf_zstr(s), NULL, 10); }
            static float rsf_parse_float(rsf_str s) { return strtof(rsf_zstr(s), NULL); }
            static double rsf_parse_double(rsf_str s) { return strtod(rsf_zstr(s), NULL); }
            static int32_t rsf_max_i(int32_t a, int32_t b) { return a > b ? a : b; }
            static bool rsf_is_digit(uint16_t c) { return c >= 48 && c <= 57; }
            static bool rsf_is_letter(uint16_t c) { return (c >= 65 && c <= 90) || (c >= 97 && c <= 122); }
            static bool rsf_is_whitespace(uint16_t c) { return c == 32 || c == 9 || c == 10 || c == 13; }
            static uint16_t rsf_to_upper(uint16_t c) { return (c >= 97 && c <= 122) ? (uint16_t)(c - 32) : c; }
            static uint16_t rsf_to_lower(uint16_t c) { return (c >= 65 && c <= 90) ? (uint16_t)(c + 32) : c; }
            static int32_t rsf_min_i(int32_t a, int32_t b) { return a < b ? a : b; }

            /* Java 的写法：整数值也带小数点（0 → "0.0"），C 的 %g 会打成 "0"。
               这里只对齐「有限、|v| < 1e7 的整数值」这条最常见路径；
               大数/极值的科学计数法（Java 用 1.0E10，C 用 1e+10）还没对齐。 */
            static rsf_str rsf_double_fmt(double v) {
                char* buf = (char*)malloc(64);
                int n;
                if (v > -1e7 && v < 1e7 && v == (double)(long long)v) {
                    n = snprintf(buf, 64, "%.1f", v);
                } else {
                    /* Java 打的是「能唯一还原这个 double 的最短串」：
                       从 1 位精度往上试，第一次能原样转回来的就是它。 */
                    int prec;
                    n = snprintf(buf, 64, "%.15g", v);
                    for (prec = 1; prec <= 17; prec++) {
                        n = snprintf(buf, 64, "%.*g", prec, v);
                        if (strtod(buf, NULL) == v) break;
                    }
                }
                { rsf_str s = {0, 0, NULL}; s.len = n; s.data = buf; return rsf_str_settle(s); }
            }

            static rsf_str rsf_float_to_str(float v) {
                return rsf_double_fmt((double)v);
            }

            static rsf_str rsf_double_to_str(double v) {
                return rsf_double_fmt(v);
            }

            static rsf_str rsf_str_replace(rsf_str s, rsf_str from, rsf_str to) {
                if (from.len <= 0 || from.len > s.len) { return s; }
                int64_t cap = s.len + 8;
                char* out = (char*)malloc((size_t)cap);
                int64_t n = 0;
                int64_t i = 0;
                while (i < s.len) {
                    bool hit = (i + from.len <= s.len) && (memcmp(s.data + i, from.data, (size_t)from.len) == 0);
                    if (hit) {
                        if (n + to.len + 1 > cap) {
                            int64_t ncap = cap;
                            while (n + to.len + 1 > ncap) { ncap = ncap * 2; }
                            out = (char*)rsf_grow(out, (size_t)cap, (size_t)ncap);
                            cap = ncap;
                        }
                        memcpy(out + n, to.data, (size_t)to.len);
                        n += to.len;
                        i += from.len;
                    } else {
                        if (n + 2 > cap) {
                            int64_t ncap = cap * 2;
                            out = (char*)rsf_grow(out, (size_t)cap, (size_t)ncap);
                            cap = ncap;
                        }
                        out[n] = s.data[i];
                        n += 1;
                        i += 1;
                    }
                }
                out[n] = 0;
                rsf_str r = {0, 0, NULL}; r.len = n; r.data = out; return r;
            }

            static rsf_str rsf_str_remove_prefix(rsf_str s, rsf_str prefix) {
                if (prefix.len > 0 && prefix.len <= s.len && memcmp(s.data, prefix.data, (size_t)prefix.len) == 0) {
                    rsf_str r = {0, 0, NULL};
                    r.len = s.len - prefix.len;
                    r.data = s.data + prefix.len;
                    return r;
                }
                return s;
            }

            static rsf_str rsf_get_prop(rsf_str key) {
                const char* k = rsf_zstr(key);
                const char* v = NULL;
                /* JDK 内建属性：C 侧没有 JVM，只能给平台上的等价物 */
                if (strcmp(k, "java.io.tmpdir") == 0) {
                    v = getenv("TMPDIR");
                    if (!v) v = "/tmp";
                }
            #ifdef _WIN32
                else if (strcmp(k, "file.separator") == 0) v = "\\";
                else if (strcmp(k, "path.separator") == 0) v = ";";
            #else
                else if (strcmp(k, "file.separator") == 0) v = "/";
                else if (strcmp(k, "path.separator") == 0) v = ":";
            #endif
                else if (strcmp(k, "line.separator") == 0) v = "\n";
                else v = getenv(k);
                rsf_str s = {0, 0, NULL};
                if (!v) { s.len = 0; s.data = NULL; return s; }
                s.data = v;
                s.len = (int64_t)strlen(v);
                return s;
            }

            static void rsf_exit(int32_t code) { exit((int)code); }
            static void rsf_eprint_s(rsf_str s) { if (s.len > 0) { fwrite(s.data, 1, (size_t)s.len, stderr); } }
            static void rsf_eprint_i(int32_t v) { fprintf(stderr, "%d", (int)v); }
            static void rsf_eprint_l(int64_t v) { fprintf(stderr, "%lld", (long long)v); }
            static void rsf_eprint_b(bool v) { fprintf(stderr, "%s", v ? "true" : "false"); }
            static void rsf_eprint_d(double v) { fprintf(stderr, "%.17g", v); }
            static void rsf_eprintln_s(rsf_str s) { if (s.len > 0) { fwrite(s.data, 1, (size_t)s.len, stderr); } fputc(10, stderr); }
            static void rsf_eprintln_i(int32_t v) { fprintf(stderr, "%d", (int)v); fputc(10, stderr); }
            static void rsf_eprintln_l(int64_t v) { fprintf(stderr, "%lld", (long long)v); fputc(10, stderr); }
            static void rsf_eprintln_b(bool v) { fprintf(stderr, "%s", v ? "true" : "false"); fputc(10, stderr); }
            static void rsf_eprintln_d(double v) { fprintf(stderr, "%.17g", v); fputc(10, stderr); }

            static rsf_str rsf_int_to_str(int32_t v) {
                char* buf = (char*)malloc(16);
                int n = snprintf(buf, 16, "%d", (int)v);
                rsf_str s = {0, 0, NULL}; s.len = n; s.data = buf; return s;
            }

        """.trimIndent()
    }
}
