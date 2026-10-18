package org.example.complie.codegen

import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.AsExpr
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.Block
import org.example.complie.ast.BoolLit
import org.example.complie.ast.CallExpr
import org.example.complie.ast.CharLit
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ControlType
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.ElvisExpr
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.Expr
import org.example.complie.ast.FieldDecl
import org.example.complie.ast.FloatLit
import org.example.complie.ast.ForceExpr
import org.example.complie.ast.ForStmt
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.IfExpr
import org.example.complie.ast.IfStmt
import org.example.complie.ast.IndexExpr
import org.example.complie.ast.IntLit
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.IsExpr
import org.example.complie.ast.LambdaExpr
import org.example.complie.ast.LongLit
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NamedAnnotationArg
import org.example.complie.ast.NewArrayExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.Node
import org.example.complie.ast.NullLit
import org.example.complie.ast.PositionalAnnotationArg
import org.example.complie.ast.StringLit
import org.example.complie.ast.Stmt
import org.example.complie.ast.SuperExpr
import org.example.complie.ast.SwitchStmt
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TryStmt
import org.example.complie.ast.UnaryExpr
import org.example.complie.ast.VarExpr
import org.example.complie.ast.WhileStmt
import org.example.complie.diag.RsfError
import org.example.complie.ir.Capture
import org.example.complie.ir.ClosurePlan
import org.example.complie.ir.ClosureScopes
import org.example.complie.ir.ControlFlow
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
import org.example.complie.ir.LambdaClosure
import org.example.complie.ir.LoweredFunction
import org.example.complie.interop.ClassPath
import org.example.complie.interop.JvmMethod
import org.example.complie.interop.ResolvedField
import org.example.complie.interop.ResolvedMethod
import org.example.complie.lexing.TokenKind
import org.example.complie.sema.AnnotationAccessor
import org.example.complie.sema.AnnotationElementSpec
import org.example.complie.sema.AnnotationSpec
import org.example.complie.sema.AnnotationTarget
import org.example.complie.sema.AnnotationUseSite
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
import org.example.complie.sema.UserMember
import org.example.complie.sema.fnSignatureOf
import org.example.complie.sema.fnTypeText
import org.example.complie.sema.isFunctionType
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File

/**
 * 字节码后端：把检查过的程序编译成一批 .class，可以直接 `java 类名` 跑。
 *
 * 控制流直接照着 [LoweredFunction] 的指令表发：`ILabel` → ASM Label，
 * `IGoto` → GOTO，`IBranch` → 条件跳转。不可达指令整条跳过，产物里不留死代码。
 *
 * 约定：
 * - 每个 rsf 函数编译成程序类上的同名 static 方法；
 * - 每个用户自定义类编译成自己的 .class（字段 + `<init>` + 方法），内部名 = 类名，
 *   写了 `pkg` 时跟着程序类一起带上包路径；
 * - `public` / 缺省 → ACC_PUBLIC，`private` → ACC_PRIVATE；实例方法不加 ACC_STATIC；
 * - 顶层名为 `main` 的函数自动带上 `String[]` 形参，从而成为合法 JVM 入口
 *   （类里叫 `main` 的方法只是普通方法，不是入口）；
 * - 类名 = 源文件主名，所以 `lang/main.rsf` 编译出 `main.class`。
 */
class BytecodeCompiler(
    private val model: SemanticModel,
    private val sourceName: String,
) {

    /** 编译中收集到的非致命提示。 */
    val warnings = ArrayList<String>()

    /**
     * 程序类的内部名（顶层函数都住在那儿）。
     *
     * 用户类的方法要调顶层函数时，调用目标必须是它，而不是当前类——
     * 这类调用是跨类的，写错了只会在运行期炸成 NoSuchMethodError。
     */
    private var programInternalName: String = ""

    /**
     * 编译整个程序，返回**这一批**写出去的 .class（程序类排第一）。
     *
     * [className] 是程序类的内部名（写了 `pkg a.b` 时是 `a/b/主文件名`）。
     * 用户类跟着程序类的包走：`pkg a.b` 里的 `class Point` → `a/b/Point.class`。
     */
    fun compile(className: String, outDir: File): List<File> {
        // 阶段 25 K2：K1 在这里的那道守卫（「带父类的类还不能 build」）已经撤掉——
        // 带父类的类现在真的生成 extends、父类构造链、父链字段与虚分派。
        programInternalName = className
        // 先把所有类都生成出来（生成期间可能报错），一个字节都还不落盘；
        // 全部成功之后再写文件——失败的一次构建不该留下半套产物。
        val pending = ArrayList<Pair<String, ByteArray>>()
        pending += className to programClass(className).toByteArray()

        for (decl in model.classModel.classes.values) {
            val internalName = classInternalName(decl.name)
            // 程序类名（来自文件名或 -n）和用户类名撞车时，两个 .class 会写到同一个路径上，
            // 后写的把先写的盖掉——产物直接坏掉，必须在这里拦下。
            // 比较**不分大小写**：Windows / macOS 的文件系统不区分大小写，
            // `m.rsf` 里的 `class M` 会正好写到程序类的 `m.class` 上。
            if (internalName.equals(className, ignoreCase = true)) {
                val how = if (internalName == className) {
                    "两者同名"
                } else {
                    "两者只差大小写（在不区分大小写的文件系统上就是同一个文件）"
                }
                throw RsfError(
                    "codegen",
                    "'$internalName' 既是程序类名（来自文件名或 -n），又是这份程序里的一个类名：" +
                        "$how，会写到同一个 $className.class 上互相覆盖。" +
                        "用 `-n <别的名字>` 换个程序类名。",
                    1, 1, null,
                )
            }
            pending += internalName to userClass(internalName, decl).toByteArray()
        }

        // 接口（阶段 20）：和用户类一样各是一个 .class，也一起做「撞程序类名」的检查。
        // 顺序同样只由源码声明顺序决定（model.interfaces 是 LinkedHashMap），产物可复现。
        // 父接口数组**不在这里解析 AST**：Checker 已经把结论（JVM 内部名）放在
        // model.superInterfacesOf 里了（R1：同一个判定不做两遍）。
        for (decl in model.interfaces.values) {
            val internalName = classInternalName(decl.name)
            if (internalName.equals(className, ignoreCase = true)) {
                val how = if (internalName == className) {
                    "两者同名"
                } else {
                    "两者只差大小写（在不区分大小写的文件系统上就是同一个文件）"
                }
                throw RsfError(
                    "codegen",
                    "'$internalName' 既是程序类名（来自文件名或 -n），又是这份程序里的一个接口名：" +
                        "$how，会写到同一个 $className.class 上互相覆盖。" +
                        "用 `-n <别的名字>` 换个程序类名。",
                    1, 1, null,
                )
            }
            pending += internalName to interfaceClass(internalName, decl).toByteArray()
        }

        // 枚举类（阶段 19c）：和用户类一样各是一个 .class，也一起做「撞程序类名」的检查。
        // 它们排在用户类后面、程序类**之前**的位置其实无所谓（写盘顺序不影响加载），
        // 但顺序保持确定：所有 .class 的生成顺序只由源码顺序决定，产物可复现。
        for (decl in model.enums.values) {
            val internalName = classInternalName(decl.name)
            if (internalName.equals(className, ignoreCase = true)) {
                throw RsfError(
                    "codegen",
                    "'$internalName' 既是程序类名（来自文件名或 -n），又是这份程序里的一个枚举名：" +
                        "两者会写到同一个 $className.class 上互相覆盖。用 `-n <别的名字>` 换个程序类名。",
                    1, 1, null,
                )
            }
            pending += internalName to enumClass(internalName, decl).toByteArray()
        }

        // 注解接口（阶段 21 M3）：`annotation Marker(...)` 自己也是一个 .class
        // （`ACC_ANNOTATION|ACC_INTERFACE|ACC_ABSTRACT` + 父接口 `java.lang.annotation.Annotation`）。
        // 伴生类（`Marker$Impl`）**不在这里**：M2 已经把它包成一个普通 ClassDecl 放进了
        // model.classModel.classes，走上面第一个循环照常发光（DESIGN-21 §5）。
        for (spec in model.annotations.values) {
            val internalName = classInternalName(spec.name)
            if (internalName.equals(className, ignoreCase = true)) {
                throw RsfError(
                    "codegen",
                    "'$internalName' 既是程序类名（来自文件名或 -n），又是这份程序里的一个注解名：" +
                        "两者会写到同一个 $className.class 上互相覆盖。用 `-n <别的名字>` 换个程序类名。",
                    1, 1, null,
                )
            }
            pending += internalName to annotationClass(internalName, spec).toByteArray()
        }

        // 函数类型的 SAM 接口（阶段 30b，工单 §1）：一个**实际用到的** arity 一个类，
        // 按 arity 升序生成。没有 lambda 的程序一个都不生成——旧语料写出去的 .class
        // 列表和每个文件的字节都不变（判据台的 J1 仍是 148）。
        for (arity in usedArities()) {
            val internalName = samInternalName(arity)
            if (pending.any { it.first.equals(internalName, ignoreCase = true) }) {
                throw RsfError(
                    "codegen",
                    "'$internalName' 是函数类型的 SAM 接口名，但程序里已经有一个同名（或只差大小写）的类：" +
                        "两者会写到同一个 .class 上互相覆盖。给那个类改个名字。",
                    1, 1, null,
                )
            }
            pending += internalName to samInterface(internalName, arity)
        }

        if (!outDir.exists() && !outDir.mkdirs()) {
            throw IllegalStateException("无法创建输出目录: ${outDir.absolutePath}")
        }
        return pending.map { (internalName, bytes) -> writeClass(internalName, bytes, outDir) }
    }

    private fun writeClass(internalName: String, bytes: ByteArray, outDir: File): File {
        val out = File(outDir, "$internalName.class")
        // internalName 可能是带包的内部名（`a/b/Main`），目录要先铺出来
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return out
    }

    private fun newClassWriter(): ClassWriter =
        object : ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS) {
            /**
             * 阶段 25 K2：**真的**最近公共祖先。
             *
             * 类继承之前，我们的引用类型之间没有继承关系（用户类都直接继承 Object），
             * 「同一个类型取它自己，否则取 Object」就是对的。有了继承之后它就不够了：
             * `COMPUTE_FRAMES` 在两条控制流汇合、同一个槽位上是两个不同的引用类型时会问
             * 这个问题，答案会写进 StackMapTable。写成 Object 之后，那个槽位在合流点上
             * 就变成 Object，紧接着一条「按父类用」的指令（invokevirtual 父类.方法 /
             * putfield）会被校验器拒绝——`VerifyError: Bad type on operand stack`，
             * 而解释器那边完全正常（又一处两端背离）。
             *
             * ASM 默认的实现是 `Class.forName` + `getSuperclass`，对本程序**正在生成**的类
             * 必然抛异常（它们此刻还没写盘、更没被加载），所以只能自己算：
             * 本程序的类沿 [SemanticModel.classChainOf] 走，链条尽头若是 JDK 父类就接
             * [ClassPath.hierarchy]；类链找不到公共祖先时再看接口链（两个兄弟类实现同一个
             * 接口、合流后按接口调用，是同一个坑的接口版本）。
             */
            override fun getCommonSuperClass(type1: String, type2: String): String =
                commonSuperClassOf(type1, type2)
        }

    /**
     * 两个内部名的最近公共祖先（阶段 25 K2，见 [newClassWriter] 里的说明）。
     *
     * 返回**内部名**。数组只在两边完全相同时才相等（和 ASM 默认实现一致）——不同维数 /
     * 不同组件的数组之间取 Object，那是校验器接受的最宽答案。
     */
    private fun commonSuperClassOf(type1: String, type2: String): String {
        if (type1 == type2) return type1
        if (type1.startsWith("[") || type2.startsWith("[")) return "java/lang/Object"
        val classes1 = classAncestry(type1)
        val classes2 = classAncestry(type2)
        if (classes1.isEmpty() || classes2.isEmpty()) return "java/lang/Object"
        val set2 = classes2.toHashSet()
        // 类链是「自己 → 父类 → …」，两边都这么排，所以第一个命中的就是最近的公共类
        for (t in classes1) if (t in set2) return t
        val ifaces2 = interfaceAncestry(type2).toHashSet()
        for (i in interfaceAncestry(type1)) if (i in ifaces2) return i
        return "java/lang/Object"
    }

    /** 内部名 → 本程序里的类名（不是本程序的类就是 null）。 */
    private fun rsfClassNameOf(internal: String): String? =
        model.classModel.classes.keys.firstOrNull { classInternalName(it) == internal }

    /**
     * 一个类型的**类**祖先链（自己排最前，`java/lang/Object` 排最后）。
     *
     * 本程序的类从 [SemanticModel.classChainOf] 取（Checker 已经算好，后端不重算一遍）；
     * 链顶那个类的父类若是 classpath 上的 JDK 类，接着沿 [ClassPath.hierarchy] 往上补。
     * 纯 JDK 类型（`String` / `ArrayList`）直接走 classpath；找不到的（本程序生成的枚举名
     * 之类）给空表，调用方会退回 Object。
     */
    private fun classAncestry(internal: String): List<String> {
        val out = ArrayList<String>()
        val rsf = rsfClassNameOf(internal)
        if (rsf == null) {
            addJvmChain(internal, out)
            return out
        }
        val chain = model.classChainOf(rsf)
        for (decl in chain) out.add(classInternalName(decl.name))
        val top = chain.lastOrNull() ?: return out
        val parent = model.superClassOf(top.name)
        if (parent != null && parent.fromClassPath) addJvmChain(parent.internalName, out)
        return out
    }

    private fun addJvmChain(internal: String, out: MutableList<String>) {
        for (cls in ClassPath.hierarchy(internal)) {
            if (!out.contains(cls.internalName)) out.add(cls.internalName)
        }
    }

    /**
     * 一个类型实现的**接口**链（含接口继承来的）。
     *
     * 本程序的类/接口从 [SemanticModel.superInterfacesOf] 取（接口名就是内部名），
     * JDK 接口继续沿 [JvmClass.interfaces] 往下走。
     */
    private fun interfaceAncestry(internal: String): List<String> {
        val out = LinkedHashSet<String>()
        for (cls in classAncestry(internal)) collectSuperInterfaces(cls, out)
        return out.toList()
    }

    private fun collectSuperInterfaces(internal: String, out: MutableSet<String>) {
        // 本程序的接口用**内部名**当键（见 isInterfaceInternalName）；类用类名
        val rsf = rsfClassNameOf(internal) ?: internal.takeIf { model.interfaces.containsKey(it) }
        if (rsf != null) {
            for (s in model.superInterfacesOf(rsf)) {
                if (out.add(s.internalName)) collectSuperInterfaces(s.internalName, out)
            }
            return
        }
        val visited = HashSet<String>()
        fun walk(name: String) {
            if (!visited.add(name)) return
            val cls = ClassPath.find(name) ?: return
            for (i in cls.interfaces) if (out.add(i)) walk(i)
        }
        walk(internal)
    }

    /** 程序类：顶层函数编译成它的静态方法，文件名主名就是类名。 */
    private fun programClass(className: String): ClassWriter {
        val cw = newClassWriter()
        cw.visit(
            V17,
            ACC_PUBLIC or ACC_FINAL or ACC_SUPER,
            className,
            null,
            "java/lang/Object",
            null,
        )
        cw.visitSource(sourceName, null)
        emitDefaultConstructor(cw, className)

        for (fn in model.functions) {
            emitFunction(cw, className, fn)
        }
        // 阶段 16：parseInt 的辅助方法（失败要把 Java 的异常换成 rsf 的消息）
        emitParseIntHelper(cw)
        // 阶段 16b：四个文件函数的辅助方法（同上：失败换成 rsf 的消息）
        emitFileHelpers(cw)
        // 阶段 13：数组的边界检查 / 负数长度检查（两端必须抛同一套消息）
        emitArrayHelpers(cw)
        // 阶段 24：`as` 的判型 + 抛错（失败消息与解释器一字不差）
        emitCastHelper(cw)
        // `?:` 生成的合成方法跟调用点同住一个类（这样私有方法也照样能调）
        emitOrElseMethods(cw)
        cw.visitEnd()
        return cw
    }

    /**
     * 程序类上的 `parseInt` 辅助方法（阶段 16）。
     *
     * **为什么要有它**：rsf 的 `parseInt` 失败时要把错误消息定成
     * [Builtins.parseIntFailure] 的格式（解释器抛的 RsfFailure 就是那个格式，
     * 两端 `catch` 里打出来的必须一字不差），而 `Integer.parseInt` 抛的是
     * `NumberFormatException` 加它自己的消息。换消息就得 catch 再抛，
     * 而**调用点不能内联 try/catch**：异常处理器一进去操作数栈就只剩异常对象，
     * 表达式中间求值时栈上往往还有没消费完的值（`println(parseInt(s))` 里那个
     * PrintStream），生成出来的类会以 VerifyError 报废——这条经验见 [emitErrorCallElvis]。
     *
     * 所以照抄 javac 处理 try-with-resources 的思路：把「调用 + 换异常」放进一个
     * 独立方法，它的保护范围从空栈开始，天然合法。
     *
     * 放在**程序类**上并且是 public 的：用户类里的调用点、以及那些类里为 `?:`
     * 生成的 `__rsf_orelse$N` 合成方法都要跨类调它。名字里带 `$`，
     * rsf 的标识符语法写不出这个名字，绝不会跟用户代码撞车。
     */
    private fun emitParseIntHelper(cw: ClassWriter) {
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_STATIC,
            Builtins.PARSE_INT_HELPER,
            Builtins.PARSE_INT_HELPER_DESC,
            null,
            null,
        )
        mv.visitCode()
        val start = Label()
        val end = Label()
        val handler = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/NumberFormatException")
        mv.visitLabel(start)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false)
        mv.visitLabel(end)
        mv.visitInsn(IRETURN)

        // 失败：throw new RuntimeException(前缀 + 原文 + 后缀)
        mv.visitLabel(handler)
        mv.visitTypeInsn(NEW, "java/lang/RuntimeException")
        mv.visitInsn(DUP)
        mv.visitLdcInsn(Builtins.PARSE_INT_PREFIX)
        mv.visitVarInsn(ALOAD, 0)
        emitConcat(mv)
        mv.visitLdcInsn(Builtins.PARSE_INT_SUFFIX)
        emitConcat(mv)
        mv.visitMethodInsn(
            INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false,
        )
        mv.visitInsn(ATHROW)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 数组的两个运行期辅助方法（阶段 13）。
     *
     * **为什么不是内联检查**：内联的边界检查要在调用点自己搭 `if` 与 `throw` 的骨架，
     * 而调用点往往在**表达式中间**（`println(a[i] + 1)` 里栈上还压着 PrintStream），
     * 一进异常处理器栈上就只剩异常对象了——生成出来的类会以 `VerifyError` 报废
     * （这条经验见 [emitErrorCallElvis]）。搬进独立方法，保护范围从空栈开始，天然合法。
     *
     * **为什么必须有它**：JVM 自己抛的 `ArrayIndexOutOfBoundsException` 消息是
     * `Index 5 out of bounds for length 3`，而解释器抛的是 rsf 自己那套。两端要在
     * `try { ... } catch (e : string) { println(e) }` 下打出一模一样的字，就必须都走
     * 同一套显式检查（消息格式只定义在 [ArrayRuntime] 一处）。
     *
     * 两个方法都放在**程序类**上并且是 public：用户类里的下标访问也要跨类调用它们。
     * 名字带 `$`，rsf 的标识符语法写不出，绝不会跟用户代码撞车。
     */
    private fun emitArrayHelpers(cw: ClassWriter) {
        // __rsf_arrayIndex(int length, int index) -> int
        // 不越界就把下标原样返回，于是调用点可以写成
        // 「求长度 → 求下标 → 检查 → 直接用这个下标取元素」，
        // 数组引用与下标都不需要额外的局部变量（也就不会跟栈帧合并打架）。
        run {
            val mv = cw.visitMethod(
                ACC_PUBLIC or ACC_STATIC,
                ArrayRuntime.INDEX_HELPER,
                ArrayRuntime.INDEX_HELPER_DESC,
                null,
                null,
            )
            mv.visitCode()
            val bad = Label()
            mv.visitVarInsn(ILOAD, 1) // index
            mv.visitJumpInsn(IFLT, bad)
            mv.visitVarInsn(ILOAD, 1)
            mv.visitVarInsn(ILOAD, 0) // length
            mv.visitJumpInsn(IF_ICMPGE, bad)
            mv.visitVarInsn(ILOAD, 1)
            mv.visitInsn(IRETURN)

            mv.visitLabel(bad)
            emitRuntimeExceptionHeader(mv)
            mv.visitLdcInsn(ArrayRuntime.INDEX_PREFIX)
            mv.visitVarInsn(ILOAD, 0)
            emitStringValueOfInt(mv)
            emitConcat(mv)
            mv.visitLdcInsn(ArrayRuntime.INDEX_MID)
            emitConcat(mv)
            mv.visitVarInsn(ILOAD, 1)
            emitStringValueOfInt(mv)
            emitConcat(mv)
            emitRuntimeExceptionThrow(mv)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // __rsf_arrayLength(int length) -> int（负数长度：JVM 会抛
        // NegativeArraySizeException，而解释器接不住它——两端都自己检查）
        run {
            val mv = cw.visitMethod(
                ACC_PUBLIC or ACC_STATIC,
                ArrayRuntime.LENGTH_HELPER,
                ArrayRuntime.LENGTH_HELPER_DESC,
                null,
                null,
            )
            mv.visitCode()
            val ok = Label()
            mv.visitVarInsn(ILOAD, 0)
            mv.visitJumpInsn(IFGE, ok)

            emitRuntimeExceptionHeader(mv)
            mv.visitLdcInsn(ArrayRuntime.LENGTH_PREFIX)
            mv.visitVarInsn(ILOAD, 0)
            emitStringValueOfInt(mv)
            emitConcat(mv)
            emitRuntimeExceptionThrow(mv)

            mv.visitLabel(ok)
            mv.visitVarInsn(ILOAD, 0)
            mv.visitInsn(IRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }

    /**
     * 四个文件函数的辅助方法（阶段 16b）。
     *
     * **为什么必须有它**：rsf 的文件函数失败时要把 Java 的异常换成 rsf 自己的错误消息
     * （[FileRuntime.failure] 的格式，解释器抛的 `RsfFailure` 就是这个格式，
     * 两端 `catch (e : string) { println(e) }` 打出来的必须一字不差），而
     * `Files.readString` 抛的是 `NoSuchFileException` / `AccessDeniedException`。
     * 换消息就得 catch 再抛，而**调用点不能内联 try/catch**——异常处理器一进去
     * 操作数栈就只剩异常对象，表达式中间求值时栈上往往还有没消费完的值，
     * 生成出来的类会以 VerifyError 报废（同 `parseInt`，见 [emitParseIntHelper]）。
     *
     * **可变参数不靠 `ClassPath` 解析**（它不认 varargs）：`Paths.get(String, String...)`
     * 与 `Files.write(Path, byte[], OpenOption...)` 都是 varargs，但字节码里根本没有
     * 「可变参数」这回事——发调用时按**擦除后的数组描述符**发，再当场造一个空数组
     * （`ICONST_0` + `ANEWARRAY`）填进去就行，效果和源码里 `Paths.get(path)` 完全一样。
     *
     * 四个方法都放在**程序类**上并且是 public：用户类里的方法/字段初值也要跨类调它们。
     * 名字带 `$`，rsf 源码写不出，绝不会跟用户代码撞车。
     */
    private fun emitFileHelpers(cw: ClassWriter) {
        // readText(path) -> Files.readString(Paths.get(path, new String[0]))
        emitFileHelper(cw, Builtins.READ_TEXT) { mv ->
            emitPathFromArg0(mv)
            mv.visitMethodInsn(
                INVOKESTATIC, "java/nio/file/Files", "readString",
                "(Ljava/nio/file/Path;)Ljava/lang/String;", false,
            )
        }

        // writeText(path, text) -> Files.write(Paths.get(path, new String[0]),
        //                                      text.getBytes(UTF_8), new OpenOption[0])
        emitFileHelper(cw, Builtins.WRITE_TEXT) { mv ->
            emitPathFromArg0(mv)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitFieldInsn(
                GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;",
            )
            mv.visitMethodInsn(
                INVOKEVIRTUAL, "java/lang/String", "getBytes",
                "(Ljava/nio/charset/Charset;)[B", false,
            )
            emitEmptyOptionArray(mv)
            mv.visitMethodInsn(
                INVOKESTATIC, "java/nio/file/Files", "write",
                "(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", false,
            )
            mv.visitInsn(POP) // Files.write 返回的是那个 Path，rsf 的 writeText 是 void
        }

        // readBytes(path) -> Files.readAllBytes(Paths.get(path, new String[0]))
        emitFileHelper(cw, Builtins.READ_BYTES) { mv ->
            emitPathFromArg0(mv)
            mv.visitMethodInsn(
                INVOKESTATIC, "java/nio/file/Files", "readAllBytes",
                "(Ljava/nio/file/Path;)[B", false,
            )
        }

        // writeBytes(path, data) -> Files.write(Paths.get(path, new String[0]), data,
        //                                       new OpenOption[0])
        emitFileHelper(cw, Builtins.WRITE_BYTES) { mv ->
            emitPathFromArg0(mv)
            mv.visitVarInsn(ALOAD, 1)
            emitEmptyOptionArray(mv)
            mv.visitMethodInsn(
                INVOKESTATIC, "java/nio/file/Files", "write",
                "(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;", false,
            )
            mv.visitInsn(POP)
        }
    }

    /**
     * 一个文件辅助方法的骨架：
     *
     * ```
     *     try { <core> }  catch (Exception e) { throw new RuntimeException(前缀 + path + 中间 + e.getMessage()) }
     * ```
     *
     * 保护范围从**空栈**开始，处理器里也只有异常对象——这就是它能通过校验的原因。
     * 返回 `void` 就发 `RETURN`，否则 `ARETURN`（这几条都是引用类型的返回）。
     *
     * catch 的上限是 `java/lang/Exception`（不是 `IOException`）：路径本身就非法时
     * （Windows 下的 `Paths.get("a<b")`）抛的是 `InvalidPathException`，
     * 它不是 IOException；解释器那边接的范围一样（见 `Interpreter.fileCall`），
     * 否则会出现「解释器接住、编译产物漏出去」的分歧。
     */
    private fun emitFileHelper(cw: ClassWriter, builtin: Builtin, core: (MethodVisitor) -> Unit) {
        val helper = builtin.helperName
            ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法名")
        val desc = builtin.helperDesc
            ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法描述符")

        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, helper, desc, null, null)
        mv.visitCode()
        val start = Label()
        val end = Label()
        val handler = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/Exception")
        mv.visitLabel(start)
        core(mv)
        mv.visitLabel(end)
        if (builtin.ret.isVoid) mv.visitInsn(RETURN) else mv.visitInsn(ARETURN)

        mv.visitLabel(handler)
        // 形参占几个槽（都是引用类型，各 1 格），异常对象就存到它后面那一格
        val exSlot = builtin.params.sumOf { it.slots }
        mv.visitVarInsn(ASTORE, exSlot)
        emitRuntimeExceptionHeader(mv)
        mv.visitLdcInsn(builtin.name + FileRuntime.PREFIX_TAIL)
        mv.visitVarInsn(ALOAD, 0)
        emitConcat(mv)
        mv.visitLdcInsn(FileRuntime.PATH_TAIL)
        emitConcat(mv)
        mv.visitVarInsn(ALOAD, exSlot)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/Throwable", "getMessage", "()Ljava/lang/String;", false,
        )
        emitConcat(mv)
        emitRuntimeExceptionThrow(mv)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** `Paths.get(aload_0, new String[0])` → 栈上得到一个 `Path`（varargs 当面补空数组）。 */
    private fun emitPathFromArg0(mv: MethodVisitor) {
        mv.visitVarInsn(ALOAD, 0)
        mv.visitInsn(ICONST_0)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String")
        mv.visitMethodInsn(
            INVOKESTATIC, "java/nio/file/Paths", "get",
            "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;", false,
        )
    }

    /** `new OpenOption[0]`：`Files.write` 的 varargs 参数——空数组就是它的默认行为。 */
    private fun emitEmptyOptionArray(mv: MethodVisitor) {
        mv.visitInsn(ICONST_0)
        mv.visitTypeInsn(ANEWARRAY, "java/nio/file/OpenOption")
    }

    /** `new RuntimeException(` 的开头：NEW + DUP（后面接消息、再接 [emitRuntimeExceptionThrow]）。 */
    private fun emitRuntimeExceptionHeader(mv: MethodVisitor) {
        mv.visitTypeInsn(NEW, "java/lang/RuntimeException")
        mv.visitInsn(DUP)
    }

    /** 关掉 `new RuntimeException(消息)` 并把参数压好之后的收尾：`<init>` + ATHROW。 */
    private fun emitRuntimeExceptionThrow(mv: MethodVisitor) {
        mv.visitMethodInsn(
            INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false,
        )
        mv.visitInsn(ATHROW)
    }

    /** 栈顶 int → String（拼错误消息用）。 */
    private fun emitStringValueOfInt(mv: MethodVisitor) {
        mv.visitMethodInsn(
            INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false,
        )
    }

    // ------------------------------------------------------------------ 用户自定义类（阶段 12-C2）

    /**
     * 用户类的内部名：跟着程序类的包走（`pkg a.b` + `P` → `a/b/P`）。
     *
     * 规则本身（它解释了为什么枚举跟着包走、而注解接口不跟）住在
     * [SemanticModel.classInternalName]——阶段 24 起解释器也要拼同一个名字来印
     * `as` 的报错文案，两边只能有一份实现。
     */
    private fun classInternalName(simpleName: String): String =
        model.classInternalName(simpleName)

    /**
     * 这个内部名是不是**这份程序里的接口**（阶段 20）。
     *
     * 目前只用在一处：`?:` 的合成静态方法 `__rsf_orelse$N` 就挂在当前 owner 上，而
     * **接口的静态方法必须用 InterfaceMethodref 引用**（`visitMethodInsn` 的 `itf` 传 true），
     * 否则 JVM 报 `IncompatibleClassChangeError: ... must be InterfaceMethodref constant`。
     * rsf 的接口不带包前缀（见 [classInternalName]），所以表里的键就是内部名。
     */
    private fun isInterfaceInternalName(internalName: String): Boolean =
        model.interfaces.containsKey(internalName)

    /**
     * rsf 类型 → JVM 描述符。
     *
     * 用户类的类型在检查器里是 `RsfType.external(简单类名)`（那时包名还没参与），
     * 写了 `pkg` 时要在这里补上包路径，否则描述符会指向默认包里的同名类。
     */
    private fun descriptorOfType(type: RsfType): String {
        // 阶段 30b：函数类型在 JVM 上的表示就是它的 SAM 接口（工单 §1）：
        // `(int) -> int` → `L__RsfFn1;`，可空与否共用同一个接口（可空 = 引用可能是 null）。
        // 阶段 13：数组 = 一层 '[' + 元素的描述符，**递归一层**。
        // （别写成 `"[".repeat(arrayDepth) + descriptorOfType(elementType)`：
        // elementType 自己还带着剩下的维度，那样 `int[][]` 会算出 `[[[I`。）
        if (type.isArray) return "[" + descriptorOfType(type.elementType)
        type.fnTypeText?.let { text ->
            val sig = fnSignatureOf(text)
                ?: throw IllegalStateException("内部错误：函数类型解析失败（$text）")
            return "L${samInternalName(sig.first.size)};"
        }
        val name = type.externalName
        if (type.isExternal && name != null && model.classModel.classes.containsKey(name)) {
            return "L${classInternalName(name)};"
        }
        // 枚举类型同理（阶段 19c）：`TokenKind` 生成出来的类跟着程序类的包走
        if (type.isExternal && name != null && model.enums.containsKey(name)) {
            return enumDescriptorOf(name)
        }
        // 注解接口类型（阶段 21 M3）：和 rsf 接口一样只在默认包里生成，所以
        // `var m : Marker` 的描述符就是 `LMarker;`（`type.descriptor` 直接给对了）——
        // 这里刻意**不**接 `classInternalName`，免得 `pkg` 下和使用点/父接口表对不上。
        return type.descriptor
    }

    private fun fieldTypesOf(decl: ClassDecl): List<RsfType> =
        model.classModel.fieldTypes[decl].orEmpty()

    /**
     * 字段在**类文件里**的类型（未替换类型参数）：`class Pair&<K, V>` 的 `k` 是 `K`，
     * 擦除后在类文件里就是 `Ljava/lang/Object;`。
     *
     * 这个和 [UserMember.Field.type] **不是一回事**：后者把 `K` 替换成了实参类型
     * （`Pair&<string, int>` 的 `k` 是 `string`），那是「拿到值之后当成什么用」，
     * 用来插 CHECKCAST / 拆箱；而 `GETFIELD` / `PUTFIELD` 的**描述符必须用擦除后的那个**，
     * 拿 `Ljava/lang/String;` 去取一个声明为 `Ljava/lang/Object;` 的字段会直接
     * `NoSuchFieldError`（编译能过、跑起来就炸，而解释器那边完全是好的——
     * 典型的两端不一致）。
     * 阶段 25 K2：**继承来的字段**也在这一支上（`class Child : Base` 里读 `Base.b`），
     * 而 [UserMember.Field.index] 报的是**对象里**的绝对槽位——父链字段排在本类字段前面，
     * 所以这里必须先把父链字段数（[SemanticModel.fieldBaseOf]）减掉，才是本类
     * [fieldTypesOf] 里的下标。没有父类时它恒为 0，既有产物一个字节都不变。
     */
    private fun declaredFieldType(className: String, index: Int, fallback: RsfType): RsfType {
        val decl = model.classModel.classes[className] ?: return fallback
        val local = index - model.fieldBaseOf(className)
        return fieldTypesOf(decl).getOrNull(local) ?: fallback
    }

    /**
     * 一个用户类：字段 + `<init>` + 方法。
     *
     * 字段一律 ACC_PUBLIC：rsf 的字段写法里没有 `<ControlType>` 的槽位，字段是「类可见」的。
     * 类本身也一律 ACC_PUBLIC——顶层类在 JVM 里根本没有 private/protected 这一说
     * （那两位只对嵌套类有意义），而 Checker 也没给类级可见性定语义。
     */
    private fun userClass(internalName: String, decl: ClassDecl): ClassWriter {
        val cw = newClassWriter()
        // 阶段 20：`class Dog : Greeter, Runnable` 的父类型进 **interfaces 数组**（不是父类）。
        // 这份名单由 Checker 算好（rsf 接口给接口名，JDK 接口给 `java/lang/Runnable` 这种
        // 内部名），后端照抄，不再自己解析一遍 `:` 子句——R1：同一个判定不做两遍。
        //
        // 阶段 25 K2：**父类**同样照抄 Checker 的决定（`model.superClassOf`）：本程序的父类
        // 用 `classInternalName`（跟着包走），JDK 父类用 Checker 读出来的内部名。以前这里写死
        // `java/lang/Object`，带上继承之后那就等于把父类整个丢了——子类会另起一套不兼容的
        // 字段/方法表，`super.f()` 与上转型全都会在运行期炸。
        val parent = model.superClassOf(decl.name)
        val superName = when {
            parent == null -> "java/lang/Object"
            parent.declaration != null -> classInternalName(parent.declaration.name)
            else -> parent.internalName
        }
        // 抽象类：有抽象方法（没有 MethodImpl 的那些）的类**必须**是 ACC_ABSTRACT，
        // 否则 JVM 在调用到那条方法时抛 AbstractMethodError 的位置和报错都很怪；
        // 「能不能 new」由 Checker 拦（见 DESIGN-25 §4），这里只把标志写对。
        cw.visit(
            V17,
            ACC_PUBLIC or ACC_SUPER or (if (decl.isAbstract) ACC_ABSTRACT else 0),
            internalName, null, superName,
            superInterfaceNames(decl.name),
        )
        cw.visitSource(sourceName, null)

        // 类级注解使用点（阶段 21 M3）：必须在 visitEnd() 之前写。
        emitUseSites({ descriptor, visible -> cw.visitAnnotation(descriptor, visible) }, decl)

        val fieldTypes = fieldTypesOf(decl)
        for ((i, f) in decl.fields.withIndex()) {
            val type = fieldTypes.getOrElse(i) { RsfType.INT }
            val fv = cw.visitField(ACC_PUBLIC, f.name, descriptorOfType(type), null, null)
            // 字段级注解使用点：写在 fv.visitEnd() 之前
            emitUseSites({ descriptor, visible -> fv.visitAnnotation(descriptor, visible) }, f)
            fv.visitEnd()
        }

        // 构造器：**写了几个就发几条 `<init>`**（JVM 本来就靠描述符区分重载的构造器）。
        // 一个都没写 → 一条隐式的无参 `<init>`（等价于 `class P()`）。
        if (decl.constructors.isEmpty()) {
            emitUserConstructor(cw, internalName, decl, fieldTypes, null)
        } else {
            for (ctor in decl.constructors) {
                emitUserConstructor(cw, internalName, decl, fieldTypes, ctor)
            }
        }
        for (method in decl.plainMethods) {
            emitUserMethod(cw, internalName, method)
        }
        emitBridges(cw, internalName, decl)
        emitUserToString(cw, decl)
        emitOrElseMethods(cw)
        // 伴生类（`Marker$Impl`）额外要三样东西（阶段 21 M3，DESIGN-21 §5）：
        // 1) 访问器的**懒构造缓存字段**（每个使用点一个，方案 B 不写 <clinit>）；
        // 2) `annotationType()`：返回 `Marker.class`（rsf 层没有 class 字面量，只能在字节码里发）；
        // 3) 它自己（以及它的成员）身上的注解使用点。
        if (model.isCompanionClass(decl.name)) {
            emitAnnotationCaches(cw, decl.name)
            model.companionAnnotationOf(decl.name)?.let { emitAnnotationType(cw, it.name, internalName) }
        }
        emitAnnotationAccessors(cw, decl.name, sync = true)
        cw.visitEnd()
        return cw
    }

    /**
     * 类/接口的父接口内部名数组（阶段 20）。
     *
     * 没有 `:` 子句时是空数组——ASM 写出来的 `interfaces_count` 就是 0，和以前传 `null`
     * 的字节完全一样，所以既有产物一个字节都不会变。
     */
    private fun superInterfaceNames(name: String): Array<String> =
        model.superInterfacesOf(name).map { it.internalName }.toTypedArray()

    // ------------------------------------------------- 桥方法（阶段 20 M4-c）

    /**
     * 一条「接口要求实现的方法」，形态按**擦除后**记：
     * 名字、类文件里的描述符、形参与返回类型。
     */
    private class BridgeRequirement(
        val name: String,
        val descriptor: String,
        val paramTypes: List<RsfType>,
        val returnType: RsfType,
    )

    /**
     * 给一个类补**桥方法**（`ACC_BRIDGE | ACC_SYNTHETIC`）——阶段 20 M4-c。
     *
     * 为什么必须有：JVM 的接口方法签名是**擦除后**的。`class ByValue : Comparator&<string>`
     * 里实现方法是 `compare(Ljava/lang/String;Ljava/lang/String;)I`，而 `Comparator` 声明的
     * 是 `compare(Ljava/lang/Object;Ljava/lang/Object;)I`。javac 在这种「实现签名比接口签名窄」
     * 的时候会合成一个桥（`Object` 版转调 `String` 版），JVM 的回调、`invokeinterface`
     * 才找得到实现；rsf 不发桥，`Collections.sort` 这类 JDK 回调就会
     * `AbstractMethodError: Method ByValue.compare(Ljava/lang/Object;Ljava/lang/Object;)I is abstract`。
     *
     * 只给**签名真的不同**的要求发桥：签名一样时桥是多余的，而多余的桥会改变既有产物的
     * 字节（`run-probes.sh` 对那些类文件的 javap 形状有断言）。
     */
    private fun emitBridges(cw: ClassWriter, owner: String, decl: ClassDecl) {
        val emitted = HashSet<String>()
        for (req in collectBridgeRequirements(decl.name)) {
            if (!emitted.add("${req.name}${req.descriptor}")) continue
            val impl = decl.plainMethods.firstOrNull { m ->
                m.name == req.name &&
                    !m.isStatic &&
                    m.control != ControlType.PRIVATE &&
                    model.paramsOf(m).size == req.paramTypes.size &&
                    descriptorOf(m) != req.descriptor &&
                    bridgeFits(m, req)
            } ?: continue
            emitBridge(cw, owner, impl, req)
        }
    }

    /**
     * 一个类身上所有「接口要求实现」的方法（穿过 rsf 接口继承链与 JDK 接口继承链，菱形只走一次）。
     *
     * 两边的描述符来源不同：rsf 接口照 [descriptorOf] 现算（类型参数擦除到 `Object`，
     * 和 [interfaceClass] 发出去的那份**逐字相同**）；JDK 接口直接取 classpath 上读到的
     * 原始描述符（那是权威，不要自己按 rsf 类型重拼一遍）。
     */
    private fun collectBridgeRequirements(className: String): List<BridgeRequirement> {
        val out = LinkedHashMap<String, BridgeRequirement>()
        val seen = HashSet<String>()

        fun addJvm(internalName: String) {
            if (!seen.add(internalName)) return
            val cls = ClassPath.find(internalName) ?: return
            for (m in cls.methods) {
                if (m.isStatic || m.name == "<init>" || isObjectSignature(m.name, m.paramTypes.size)) continue
                out.putIfAbsent(
                    "${m.name}${m.descriptor}",
                    BridgeRequirement(m.name, m.descriptor, m.paramTypes, m.returnType),
                )
            }
            cls.interfaces.forEach { addJvm(it) }
        }

        fun walk(name: String) {
            for (sup in model.superInterfacesOf(name)) {
                if (sup.fromClassPath) {
                    addJvm(sup.internalName)
                    continue
                }
                if (!seen.add(sup.name)) continue
                val iface = model.interfaceOf(sup.name) ?: continue
                for (m in iface.methods) {
                    if (m.isStatic) continue
                    val desc = descriptorOf(m)
                    out.putIfAbsent(
                        "${m.name}$desc",
                        BridgeRequirement(m.name, desc, model.paramTypesOf(m), model.returnTypeOf(m)),
                    )
                }
                walk(sup.name)
            }
        }

        walk(className)
        return out.values.toList()
    }

    /** `equals(Object)` / `hashCode()` / `toString()`——Object 自己的方法，不需要桥。 */
    private fun isObjectSignature(name: String, arity: Int): Boolean = when (name) {
        "equals" -> arity == 1
        "hashCode", "toString" -> arity == 0
        else -> false
    }

    /**
     * 这条「要求」真的能由这个实现补出桥来吗？
     *
     * 桥的两头必须能用 `CHECKCAST` / 装箱拆箱连起来，而且方向只能是**收窄**：
     * 形参 `Object → String`、`Object → int`（拆箱）都可以；反过来（要求的是 `int`、
     * 实现收的是引用）在 JVM 上根本不是同一件事，宁可不发桥，也不要发一条跑起来才炸的。
     */
    private fun bridgeFits(impl: FuncDecl, req: BridgeRequirement): Boolean {
        val ownParams = model.paramTypesOf(impl)
        if (ownParams.size != req.paramTypes.size) return false
        for (i in ownParams.indices) {
            val from = req.paramTypes[i]
            val to = ownParams[i]
            val fromPrim = isJvmPrimitive(from)
            val toPrim = isJvmPrimitive(to)
            when {
                fromPrim && toPrim -> if (from.descriptor != to.descriptor) return false
                fromPrim && !toPrim -> return false
                else -> {}
            }
        }
        val ownRet = model.returnTypeOf(impl)
        val reqRet = req.returnType
        if (reqRet.isVoid) return ownRet.isVoid
        if (isJvmPrimitive(reqRet)) {
            return isJvmPrimitive(ownRet) && ownRet.descriptor == reqRet.descriptor
        }
        return !ownRet.isVoid
    }

    /** JVM 意义上真正的基本类型（可空基本类型已经装箱成引用，数组是引用）。 */
    private fun isJvmPrimitive(type: RsfType): Boolean =
        !type.isArray && !type.isTypeParam && !(type.nullable && type.base.isPrimitive) && type.base.isPrimitive

    /**
     * 一条桥：按**要求**的描述符收参数，转调实现方法。
     *
     * ```
     * public Object get() {                       // 要求（接口里擦除后的形态）
     *     return Integer.valueOf(this.get());      // 转调实现（`get() -> int`）
     * }
     * ```
     */
    private fun emitBridge(cw: ClassWriter, owner: String, impl: FuncDecl, req: BridgeRequirement) {
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_BRIDGE or ACC_SYNTHETIC, req.name, req.descriptor, null, null,
        )
        mv.visitCode()
        // 桥方法是**实例方法**：LVT 只有 0 号槽的 this（形参是合成出来的，没有 rsf 名字）
        val lvt = beginLvt(mv, owner)
        mv.visitVarInsn(ALOAD, 0)
        val ownParams = model.paramTypesOf(impl)
        var slot = 1
        for ((i, from) in req.paramTypes.withIndex()) {
            loadSlot(mv, from, slot)
            slot += if (!from.isArray && !from.nullable &&
                (from.base == RsfBase.LONG || from.base == RsfBase.DOUBLE)
            ) 2 else 1
            emitResultCast(mv, from, ownParams.getOrElse(i) { from })
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, impl.name, descriptorOf(impl), false)
        val ownReturn = model.returnTypeOf(impl)
        if (isJvmPrimitive(ownReturn) && !isJvmPrimitive(req.returnType)) boxValue(mv, ownReturn.base)
        mv.visitInsn(returnInsn(req.returnType))
        endLvt(mv, lvt)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 一个接口编译出来的类（阶段 20）。
     *
     * 形状（口径写在 [org.example.complie.ast.InterfaceDecl] 的注释里，这里只落到字节码上）：
     * ```
     * public interface Greeter {
     *     public abstract void greet(String);     // 抽象方法：ACC_ABSTRACT，且**没有 Code**
     *     public void shout(String) { ... }       // 默认方法：照普通方法写 Code
     * }
     * ```
     *
     * - **不发字段、不发 `<init>`**：接口不能有字段（D3，Parser/Checker 已拦），也不能被实例化；
     * - 超类写 `java/lang/Object`（JVM 要求）且**不加 ACC_SUPER**：那一位是给类的超方法解析
     *   规则用的，落在接口头上没有语义；
     * - 抽象方法发 `ACC_PUBLIC | ACC_ABSTRACT` 之后**直接 visitEnd()**，绝不能调 `visitCode()`
     *   ——一旦带上 Code 属性，类加载器会以 ClassFormatError 拒绝（abstract/native 方法不许有 Code）；
     * - 默认方法复用 [emitUserMethod]：接口方法只能 public、不能 static（Checker 拦），
     *   描述符与调用点走的是同一套规则；
     * - `?:` 的合成方法也要在这里发（见下）。
     */
    private fun interfaceClass(internalName: String, decl: InterfaceDecl): ClassWriter {
        val cw = newClassWriter()
        cw.visit(
            V17,
            ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT,
            internalName,
            null,
            "java/lang/Object",
            superInterfaceNames(decl.name),
        )
        cw.visitSource(sourceName, null)

        // 接口级注解使用点（阶段 21 M3）
        emitUseSites({ descriptor, visible -> cw.visitAnnotation(descriptor, visible) }, decl)

        for (method in decl.methods) {
            if (method.isAbstract) {
                val mv = cw.visitMethod(ACC_PUBLIC or ACC_ABSTRACT, method.name, descriptorOf(method), null, null)
                // 抽象方法的注解使用点：写在 mv.visitEnd() 之前
                emitUseSites({ descriptor, visible -> mv.visitAnnotation(descriptor, visible) }, method)
                mv.visitEnd()
            } else {
                emitUserMethod(cw, internalName, method)
            }
        }
        // 默认方法体里可能有 `parseInt(s) ?: 0` 这类需要 try/catch 的调用点：那些合成方法
        // 登记在 [orElseMethods] 里，必须跟着**当前这个接口**的类文件发出去（emitOrElseMethods
        // 末尾会清空缓冲，所以每个类各发自己那一份，不会串到下一个类上）。
        // V17 的接口允许 private static 方法（Java 9 起），所以合成方法的可见性不用改。
        emitOrElseMethods(cw)
        // 方案 B 的访问器（挂在这个接口上的那些）。接口里的方法**不能**带
        // ACC_SYNCHRONIZED（JVMS 4.6：接口方法只允许 public/private/abstract/static/
        // strict/final/synthetic/bridge/varargs），带了类文件直接 ClassFormatError。
        emitAnnotationAccessors(cw, decl.name, sync = false)
        cw.visitEnd()
        return cw
    }

    // ------------------------------------------------------------------ 注解（阶段 21 M3）

    /**
     * 一个注解声明 → **注解接口** class（DESIGN-21 §5）。
     *
     * 形状（`build/verify21/m3probe/` 里实测过的配方）：
     * ```
     * flags: (0x2601) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT, ACC_ANNOTATION
     * this_class: Marker      super_class: java/lang/Object
     * interfaces: java/lang/annotation/Annotation
     * fields: 0               无 <init>
     * public abstract value()Ljava/lang/String;
     *     AnnotationDefault: "x"
     * RuntimeVisibleAnnotations: @java.lang.annotation.Retention(RUNTIME)
     * ```
     * 三条漏了都不报编译错、只在**运行期**炸的地方：
     * - `ACC_ABSTRACT` 不能少：不带 Code 属性的方法只有 native/abstract 才合法，
     *   漏掉会在类加载时报 `ClassFormatError: Absent Code attribute in method that is
     *   not native or abstract`；
     * - 父接口写死 `java/lang/annotation/Annotation`（rsf 源码里写不出这个 `:` 子句）；
     * - **必须**带 `@Retention(RUNTIME)`：注解接口默认是 CLASS 保留，运行期
     *   `Demo.class.getAnnotation(Marker.class)` 会**静默**返回 null——不报错，最难查。
     */
    private fun annotationClass(internalName: String, spec: AnnotationSpec): ClassWriter {
        val cw = newClassWriter()
        cw.visit(
            V17,
            ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION,
            internalName,
            null,
            "java/lang/Object",
            arrayOf("java/lang/annotation/Annotation"),
        )
        cw.visitSource(sourceName, null)

        // @Retention(RetentionPolicy.RUNTIME)：写在 cw.visitEnd() 之前
        val retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true)
        retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
        retention.visitEnd()

        // 元素方法：`public abstract <元素类型> <元素名>()`，有默认值的补 AnnotationDefault
        for (element in spec.elements) {
            val mv = cw.visitMethod(
                ACC_PUBLIC or ACC_ABSTRACT, element.name, elementDescriptor(element), null, null,
            )
            val default = element.decl.default
            if (element.hasDefault && default != null) {
                annotationConstant(element.type, default)?.let { value ->
                    // 元素默认值的名字槽是 **null**（不是元素名）
                    mv.visitAnnotationDefault().visit(null, value)
                }
            }
            mv.visitEnd()
        }

        // 注解声明自己身上的注解（`@Marker` 标在 `annotation Other` 上）
        emitUseSites({ descriptor, visible -> cw.visitAnnotation(descriptor, visible) }, spec.decl)
        // 注解接口里的方法同样不能带 ACC_SYNCHRONIZED（见 interfaceClass 的同款注释）。
        emitAnnotationAccessors(cw, spec.name, sync = false)
        cw.visitEnd()
        return cw
    }

    /** 元素方法的描述符：`()Ljava/lang/String;`（无参，返回元素类型）。 */
    private fun elementDescriptor(element: AnnotationElementSpec): String =
        "()" + descriptorOfType(element.type)

    /** 伴生类的 JVM 描述符（跟着程序类的包走）。 */
    private fun companionDescriptor(companionName: String): String =
        "L${classInternalName(companionName)};"

    /**
     * [node] 身上有哪些注解使用点。
     *
     * 判定用**同一性**（`===`）：M2 的 `annotationUses` 里存的就是 AST 上的那些声明对象，
     * 同一个 `FuncDecl` 只会有一条 `OnMethod` 记录（同名重载也各是各的对象）。
     */
    private fun useSitesOn(node: Node): List<AnnotationUseSite> =
        model.annotationUses.filter { site ->
            when (val target = site.target) {
                is AnnotationTarget.OnClass -> node is ClassDecl && target.decl === node
                is AnnotationTarget.OnInterface -> node is InterfaceDecl && target.decl === node
                is AnnotationTarget.OnAnnotation -> node is AnnotationDecl && target.decl === node
                is AnnotationTarget.OnMethod -> node is FuncDecl && target.decl === node
                is AnnotationTarget.OnField -> node is FieldDecl && target.decl === node
            }
        }

    /**
     * 把 [node] 身上的注解写成 `RuntimeVisibleAnnotations`。
     *
     * [av] 是「开一条注解」的入口：类上 `cw.visitAnnotation`、方法上 `mv.visitAnnotation`、
     * 字段上 `fv.visitAnnotation`——三处都必须在对应的 `visitEnd()` **之前**调，
     * 之后写进去的会被 ASM 直接丢掉（DESIGN-21 §7）。
     *
     * 使用点与目标的配对**只读** `model.annotationUses`（M2 的结论），后端不再拿 AST 对一遍。
     */
    private fun emitUseSites(av: (String, Boolean) -> AnnotationVisitor, node: Node) {
        for (site in useSitesOn(node)) {
            val visitor = av("L${classInternalName(site.annotation.name)};", true)
            val explicit = explicitValues(site)
            for ((index, element) in site.annotation.elements.withIndex()) {
                val value = explicit[index] ?: element.decl.default ?: continue
                // 类文件里没有「null 注解值」这个编码（元素值只能是常量），跳过不写：
                // 运行期读这个元素会抛 IncompleteAnnotationException，但不会产出坏类文件。
                if (value is NullLit) continue
                val enumRef = model.enumValueOf(value)
                if (enumRef != null) {
                    visitor.visitEnum(element.name, enumDescriptorOf(enumRef.enumName), enumRef.valueName)
                    continue
                }
                annotationConstant(element.type, value)?.let { constant ->
                    visitor.visit(element.name, constant)
                }
            }
            visitor.visitEnd()
        }
    }

    /**
     * 一个使用点的实参 → 元素表（下标对齐元素声明顺序，没给的位置是 null）。
     *
     * 规则和 M2 的 `checkAnnotationArguments` 是**同一套**：位置式实参按**实参下标**
     * 对上元素下标，具名实参按名字对上（`@Marker(value="hi", count=3)`）。
     * 调用方拿到 null 时再退到元素默认值。
     */
    private fun explicitValues(site: AnnotationUseSite): List<Expr?> {
        val elements = site.annotation.elements
        val out = arrayOfNulls<Expr>(elements.size)
        for ((index, arg) in site.use.arguments.withIndex()) {
            when (arg) {
                is PositionalAnnotationArg -> if (index < out.size) out[index] = arg.value
                is NamedAnnotationArg -> {
                    val at = elements.indexOfFirst { it.name == arg.name }
                    if (at >= 0) out[at] = arg.value
                }
            }
        }
        return out.toList()
    }

    /**
     * 注解元素值 → **类文件里的常量**（`AnnotationVisitor.visit` / `visitAnnotationDefault`
     * 收的那种装箱值）。
     *
     * 装箱按**元素声明的类型**来：`@M(b = 1)` 的元素是 `byte`，值必须是 `Byte`
     * ——塞 `Integer` 的话类文件里 tag 会变成 'I'，反射读它直接
     * `AnnotationTypeMismatchException`。返回 null 表示「这个值编码不进类文件」
     * （只有 `null` 字面量会走到这里）。
     */
    private fun annotationConstant(type: RsfType, expr: Expr): Any? {
        val raw: Any = when (expr) {
            is IntLit -> expr.value
            is LongLit -> expr.value
            is FloatLit -> expr.value
            is DoubleLit -> expr.value
            is StringLit -> expr.value
            is CharLit -> expr.value
            is BoolLit -> expr.value
            is NullLit -> return null
            is UnaryExpr -> {
                if (expr.op != TokenKind.MINUS) throw badAnnotationValue(type, expr)
                when (val operand = expr.operand) {
                    is IntLit -> -operand.value
                    is LongLit -> -operand.value
                    is FloatLit -> -operand.value
                    is DoubleLit -> -operand.value
                    else -> throw badAnnotationValue(type, expr)
                }
            }
            else -> throw badAnnotationValue(type, expr)
        }
        return when (type.base) {
            RsfBase.BOOL -> raw as? Boolean ?: throw badAnnotationValue(type, expr)
            RsfBase.BYTE -> (raw as? Number)?.toByte() ?: throw badAnnotationValue(type, expr)
            RsfBase.CHAR -> when (raw) {
                is Char -> raw
                is Number -> raw.toInt().toChar()
                else -> throw badAnnotationValue(type, expr)
            }
            RsfBase.INT -> (raw as? Number)?.toInt() ?: throw badAnnotationValue(type, expr)
            RsfBase.LONG -> (raw as? Number)?.toLong() ?: throw badAnnotationValue(type, expr)
            RsfBase.FLOAT -> (raw as? Number)?.toFloat() ?: throw badAnnotationValue(type, expr)
            RsfBase.DOUBLE -> (raw as? Number)?.toDouble() ?: throw badAnnotationValue(type, expr)
            RsfBase.STRING -> raw as? String ?: throw badAnnotationValue(type, expr)
            else -> throw badAnnotationValue(type, expr)
        }
    }

    private fun badAnnotationValue(type: RsfType, expr: Expr): RsfError = RsfError(
        "codegen",
        "注解元素的值必须是编译期常量（字面量、取负的字面量或枚举常量）：" +
            "'${type.rsfName}' 这里拿不到常量。数组/注解类型的元素 v1 只能用声明里的默认值" +
            "（rsf 还没有数组字面量）。",
        expr.at.line, expr.at.column, null,
    )

    /**
     * 把一个注解元素值**压到操作数栈上**（重写 `new Marker(...)` 和方案 B 访问器里
     * 补默认值的那几段用）。
     *
     * 和 [annotationConstant] 是一对：那边给类文件的装箱常量，这边给 JVM 指令。
     * 元素类型是枚举时发 `GETSTATIC`（枚举常量在 JVM 上就是它自己的单例字段）。
     */
    private fun pushAnnotationValue(mv: MethodVisitor, type: RsfType, elementName: String, expr: Expr) {
        model.enumValueOf(expr)?.let { enumRef ->
            mv.visitFieldInsn(
                GETSTATIC, classInternalName(enumRef.enumName), enumRef.valueName,
                enumDescriptorOf(enumRef.enumName),
            )
            return
        }
        if (type.isArray) {
            throw RsfError(
                "codegen",
                "注解元素 '$elementName' 是数组：v1 的数组元素只能用注解声明里的默认值，" +
                    "使用点给不了（rsf 还没有数组字面量）。",
                expr.at.line, expr.at.column, null,
            )
        }
        val value = annotationConstant(type, expr) ?: throw RsfError(
            "codegen",
            "注解元素 '$elementName' 不能是 null：类文件里的注解值没有 null 这个编码。",
            expr.at.line, expr.at.column, null,
        )
        when (type.base) {
            RsfBase.BOOL -> mv.visitInsn(if (value as Boolean) ICONST_1 else ICONST_0)
            RsfBase.BYTE -> pushInt(mv, (value as Byte).toInt())
            RsfBase.CHAR -> pushInt(mv, (value as Char).code)
            RsfBase.INT -> pushInt(mv, value as Int)
            RsfBase.LONG -> mv.visitLdcInsn(value as Long)
            RsfBase.FLOAT -> mv.visitLdcInsn(value as Float)
            RsfBase.DOUBLE -> mv.visitLdcInsn(value as Double)
            RsfBase.STRING -> mv.visitLdcInsn(value as String)
            else -> throw badAnnotationValue(type, expr)
        }
    }

    /**
     * 方案 B 的懒构造缓存字段（一个使用点一个），都挂在**伴生类**上。
     *
     * 名字形如 `__rsf_ann$宿主$成员`：rsf 的标识符写不出 `$`（见 `Lexer.isIdentStart`），
     * 所以不可能和用户字段撞名。访问器在宿主类型上，缓存字段在伴生类上，两边同包
     * （都是 `model.packageName`）所以包私有就够了——不额外公开一个实现细节。
     */
    private fun emitAnnotationCaches(cw: ClassWriter, companionName: String) {
        for (accessor in model.annotationAccessors) {
            if (accessor.companionName != companionName) continue
            cw.visitField(ACC_STATIC, cacheFieldName(accessor), companionDescriptor(companionName), null, null)
                .visitEnd()
        }
    }

    private fun cacheFieldName(accessor: AnnotationAccessor): String =
        "__rsf_ann\$${accessor.hostName}\$${accessor.memberName ?: ""}"

    /**
     * 方案 B 的注解访问器（DESIGN-21 §3）：**每个使用点一个静态方法**。
     *
     * - 类型级 → `public static Marker$Impl Marker()`，注解名就是方法名（rsf 的无缝重载）；
     * - 成员级 → `public static Marker$Impl Marker(string member)`，`member` 对不上返回 **null**。
     *
     * 懒构造：先读缓存字段，非空直接返回，否则 `new` 一个存回去。宿主是**类**时方法带
     * `ACC_SYNCHRONIZED`（§3 定的），所以不需要 `<clinit>` 也不需要 DCL；宿主是**接口或注解
     * 声明**时同步位必须去掉（`sync = false`）——JVMS 4.6 不允许接口方法带 `ACC_SYNCHRONIZED`，
     * 带了整个类文件在加载期就 `ClassFormatError`。少了同步最多让两个线程各构造一个实例，
     * 缓存字段的写入本身是原子的（先构造完再 PUTSTATIC），不会读到半成品。
     *
     * 访问标志**不走** [accessOf]（那是给 rsf 源码里的 `FuncDecl` 用的，
     * 这里没有 FuncDecl 可查），直接发 ACC_PUBLIC|ACC_STATIC[|ACC_SYNCHRONIZED]。
     */
    private fun emitAnnotationAccessors(cw: ClassWriter, hostName: String?, sync: Boolean) {
        if (hostName == null) return
        val sites = model.accessorsOn(hostName)
        if (sites.isEmpty()) return
        // 同一个宿主上：同注解的类型级只有一个；成员级按注解分组，一个 member 一个分支
        val groups = LinkedHashMap<String, MutableList<AnnotationAccessor>>()
        for (accessor in sites) {
            val key = accessor.annotation.name + if (accessor.memberName == null) "#type" else "#member"
            groups.getOrPut(key) { ArrayList() }.add(accessor)
        }
        for (group in groups.values) {
            val first = group.first()
            val desc = companionDescriptor(first.companionName)
            if (first.memberName == null) {
                emitTypeLevelAccessor(cw, first, desc, sync)
            } else {
                emitMemberLevelAccessor(cw, group, desc, sync)
            }
        }
    }

    private fun emitTypeLevelAccessor(cw: ClassWriter, accessor: AnnotationAccessor, desc: String, sync: Boolean) {
        val companion = classInternalName(accessor.companionName)
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_STATIC or if (sync) ACC_SYNCHRONIZED else 0, accessor.annotation.name, "()$desc", null, null,
        )
        mv.visitCode()
        mv.visitFieldInsn(GETSTATIC, companion, cacheFieldName(accessor), desc)
        mv.visitVarInsn(ASTORE, 0)
        mv.visitVarInsn(ALOAD, 0)
        val ready = Label()
        mv.visitJumpInsn(IFNONNULL, ready)
        emitAccessorNew(mv, accessor)
        mv.visitInsn(DUP)
        mv.visitFieldInsn(PUTSTATIC, companion, cacheFieldName(accessor), desc)
        mv.visitVarInsn(ASTORE, 0)
        mv.visitLabel(ready)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitMemberLevelAccessor(cw: ClassWriter, group: List<AnnotationAccessor>, desc: String, sync: Boolean) {
        val first = group.first()
        val companion = classInternalName(first.companionName)
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_STATIC or if (sync) ACC_SYNCHRONIZED else 0, first.annotation.name,
            "(Ljava/lang/String;)$desc", null, null,
        )
        mv.visitCode()
        for (accessor in group) {
            val next = Label()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitLdcInsn(accessor.memberName ?: "")
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
            mv.visitJumpInsn(IFEQ, next)
            mv.visitFieldInsn(GETSTATIC, companion, cacheFieldName(accessor), desc)
            mv.visitVarInsn(ASTORE, 1)
            mv.visitVarInsn(ALOAD, 1)
            val ready = Label()
            mv.visitJumpInsn(IFNONNULL, ready)
            emitAccessorNew(mv, accessor)
            mv.visitInsn(DUP)
            mv.visitFieldInsn(PUTSTATIC, companion, cacheFieldName(accessor), desc)
            mv.visitVarInsn(ASTORE, 1)
            mv.visitLabel(ready)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitInsn(ARETURN)
            mv.visitLabel(next)
        }
        // 没有这个成员：返回 null（§3：「取不到返回 null」）
        mv.visitInsn(ACONST_NULL)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 一个使用点的伴生实例：`new Marker$Impl(实参…)` —— 实参按**元素声明顺序**摆，
     * 使用点没给的元素在这里补上它的默认值（M2 允许少给实参）。
     */
    private fun emitAccessorNew(mv: MethodVisitor, accessor: AnnotationAccessor) {
        val spec = accessor.annotation
        val companion = spec.companion
            ?: throw IllegalStateException(
                "内部错误：注解 '@${spec.name}' 没有 companion entity，不该有方案 B 的访问器。",
            )
        val companionName = classInternalName(accessor.companionName)
        val params = model.paramTypesOf(companion.constructor)
        mv.visitTypeInsn(NEW, companionName)
        mv.visitInsn(DUP)
        val explicit = explicitValues(
            AnnotationUseSite(spec, accessor.use, accessor.target),
        )
        for ((index, element) in spec.elements.withIndex()) {
            val value = explicit[index] ?: element.decl.default
                ?: throw RsfError(
                    "codegen",
                    "内部错误：注解 '@${spec.name}' 的元素 '${element.name}' 在使用点没有值，" +
                        "也没有默认值（M2 本该拦掉）。",
                    accessor.target.at.line, accessor.target.at.column, null,
                )
            pushAnnotationValue(mv, element.type, element.name, value)
        }
        mv.visitMethodInsn(
            INVOKESPECIAL, companionName, "<init>",
            "(" + params.joinToString("") { descriptorOfType(it) } + ")V", false,
        )
    }

    /**
     * 伴生类的 `annotationType()`：返回 `Marker.class`。
     *
     * rsf 层没有 class 字面量（`Marker` 上也没有这个元素），所以这一条**只在字节码里发**，
     * 源码里写不出来。描述符用 `()Ljava/lang/Class;`——这正是
     * `java.lang.annotation.Annotation.annotationType()` 的擦除签名，所以伴生类是一个
     * **完整**的注解实现；写成 `()LMarker;` 就只是同名的另一个方法，
     * 接口那条抽象方法仍然没人实现，通过接口调它会在运行期 AbstractMethodError。
     */
    private fun emitAnnotationType(cw: ClassWriter, annotationName: String, self: String) {
        val mv = cw.visitMethod(ACC_PUBLIC, "annotationType", "()Ljava/lang/Class;", null, null)
        mv.visitCode()
        // 伴生类的实例方法：LVT 只有 this
        val lvt = beginLvt(mv, self)
        mv.visitLdcInsn(Type.getObjectType(classInternalName(annotationName)))
        mv.visitInsn(ARETURN)
        endLvt(mv, lvt)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 用户类的**确定性** `toString()`：`类名(字段=值, 字段=值)`。
     *
     * 为什么必须生成：`"p = " + p` 在解释器里走
     * [org.example.complie.interp.Interpreter.RsfObject] 的 `toString()`，
     * 在编译产物里走 `String.valueOf(Object)` → 这个（或继承来的）`toString()`。
     * 不生成的话编译产物拿到的是 `Object.toString()` —— `Point@1a2b3c`，
     * 那是**身份哈希**，每次跑都不一样，解释器与编译产物不可能对上。
     *
     * 格式规则（字段怎么挑、`...` 怎么写、分隔符是什么）全在
     * [ObjectFormat] 里，**解释器读的是同一段规则**，所以两端逐字节一致。
     *
     * 类里自己写了 `toString()` 就不生成（否则同一个类里会出现两条
     * `toString()Ljava/lang/String;`，类文件直接报废）。
     *
     * 生成出来的形状（用 `StringBuilder` 而不是 invokedynamic，是因为要处理
     * `char` / `bool` / `byte` 各自的 append 重载，见 [ObjectFormat.appendDescriptor]）：
     * ```
     * public String toString() {
     *     return new StringBuilder("Point(x=").append(x).append(", y=").append(y).append(")").toString();
     * }
     * ```
     */
    private fun emitUserToString(
        cw: ClassWriter,
        decl: ClassDecl,
    ) {
        val userDefined = decl.plainMethods.any { it.name == "toString" && it.params.isEmpty() }
        if (userDefined) return

        val sb = "java/lang/StringBuilder"
        val mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        mv.visitCode()
        // 生成出来的 toString 是实例方法：LVT 只有 this（体里没有 rsf 变量）
        val lvt = beginLvt(mv, classInternalName(decl.name))
        mv.visitTypeInsn(NEW, sb)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, sb, "<init>", "()V", false)

        fun appendText(text: String) {
            mv.visitLdcInsn(text)
            mv.visitMethodInsn(
                INVOKEVIRTUAL, sb, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false,
            )
        }

        appendText(ObjectFormat.open(decl.name))
        // 阶段 25 K2：渲染**整条父链**的字段——父类字段在前、自己的在后（DESIGN-25 §7）。
        // 这一段与解释器的 chainFieldsOf 是逐行同一个算法（先按父链顺序取 owner、再按
        // 声明顺序取它的字段，类型取**声明时**那一份、不代换类型实参），两端才逐字节一致。
        // 开头写 decl.name 而不是运行期类名：`toString` 是虚分派，跑起来的必然就是运行期
        // 那一份（`println(child)` 在解释器里读的是 obj.className，这里由虚拟调用对上）。
        var firstField = true
        for (fieldOwner in model.fieldChainOf(decl.name).map { it.first }.distinct()) {
            val ownerTypes = fieldTypesOf(fieldOwner)
            for ((i, f) in fieldOwner.fields.withIndex()) {
                if (!firstField) appendText(ObjectFormat.SEP)
                firstField = false
                appendText(ObjectFormat.fieldPrefix(f.name))
                val type = ownerTypes.getOrElse(i) { RsfType.INT }
                // 枚举字段是可打印的（阶段 19c）：`append(Object)` 会调到它生成的
                // `toString()`（返回值名），解释器渲染同一套规则（见 ObjectFormat）
                val desc = ObjectFormat.appendDescriptor(type, model.isEnumType(type))
                if (desc == null) {
                    // 数组 / 对象字段：不递归展开，写一个占位（两端同一条规则）
                    appendText(ObjectFormat.SKIPPED)
                } else {
                    mv.visitVarInsn(ALOAD, 0)
                    mv.visitFieldInsn(
                        GETFIELD, classInternalName(fieldOwner.name), f.name, descriptorOfType(type),
                    )
                    mv.visitMethodInsn(INVOKEVIRTUAL, sb, "append", desc, false)
                }
            }
        }
        appendText(ObjectFormat.CLOSE)
        mv.visitMethodInsn(INVOKEVIRTUAL, sb, "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(ARETURN)
        endLvt(mv, lvt)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 一条 `<init>`：`super()` 或 `this(...)` → 字段初值 → 构造器体。
     *
     * [ctor] 为 null 表示这个类一个构造器都没写（隐式无参构造器：只有字段初值）。
     *
     * **阶段 18 的委托**：`constructor(...) { this(实参) ... }` 编译成
     * ```
     *     aload_0                 // 未初始化的 this
     *     实参…
     *     invokespecial <owner>.<init>(目标描述符)V      // 这一次调用就是「初始化 this」
     *     委托之后的语句…
     *     return
     * ```
     * 关键是这条 `<init>` **不调 `Object.<init>`、也不跑字段初值**——那两件事由链的根
     * （一路 this 下去最终落到主构造）做。JVM 的校验器只要求「碰 this/字段之前先初始化
     * 一次」，而这个 `this(...)` 正好就是那次初始化（javac 生成的委托构造器是同一个形状）。
     */
    private fun emitUserConstructor(
        cw: ClassWriter,
        owner: String,
        decl: ClassDecl,
        fieldTypes: List<RsfType>,
        ctor: FuncDecl?,
    ) {
        val params = if (ctor == null) emptyList() else model.paramsOf(ctor)
        val descriptor = "(" + params.joinToString("") { descriptorOfType(it.type) } + ")V"

        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null)
        mv.visitCode()
        // LVT 的起点在**序言之前**：起止一律方法级，字段初值/父类那一跳也在覆盖范围里
        val lvt = beginLvt(mv, owner)

        val delegation = ctor?.let { model.classModel.delegationOf(it) }
        if (delegation != null && ctor != null) {
            // 委托构造器：把 this 和实参压好后直接调目标 <init>
            mv.visitVarInsn(ALOAD, 0)
            val ctx = ExprCtx(mv, owner, model.loweredOf(ctor), localOffset = 0)
            val targetParams = model.paramTypesOf(delegation.target)
            for ((i, arg) in delegation.stmt.arguments.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), targetParams[i])
            }
            mv.visitMethodInsn(
                INVOKESPECIAL, owner, "<init>", descriptorOf(delegation.target), false,
            )
        } else {
            // 阶段 25 K2：父类那一跳排在**字段初值前面**（DESIGN-25 §6：父类构造器跑完之前，
            // 子类那份状态一个字都还没有）。解释器 K3 的 runConstructor 是同一顺序：
            // 先 runSuperDelegation，再 runFieldInitializers。
            emitParentCtor(mv, owner, decl, ctor)

            // 字段初值在「this 就是刚分配的这个对象」的上下文里求值：0 号槽正是 this。
            // 主构造的参数就在 1..n 号槽上，所以 `class P(x0 : int) { var x : int = x0 }`
            // 的初值表达式能直接用 x0（帧就是主构造的帧，和解释器一致）。
            val ctx = ExprCtx(mv, owner, ctor?.let { model.loweredOf(it) }, localOffset = 0)
            // 伴生类的**元素字段**例外（阶段 21 M3）：M2 给它们的 initializer 只是占位
            // （元素默认值字面量或零值），真正的值来自构造器参数——`new Marker("hi", 3)`
            // 和方案 B 的访问器都把实参压进来。伴生主构造的形参顺序 = 元素顺序（§6 校验过），
            // 所以第 i 个元素字段直接取第 i 个形参的槽位。
            val elementCount = if (model.isCompanionClass(decl.name)) {
                model.companionAnnotationOf(decl.name)?.companion?.elementFields?.size ?: 0
            } else {
                0
            }
            val ctorParams = if (ctor == null) emptyList() else model.paramsOf(ctor)
            for ((i, f) in decl.fields.withIndex()) {
                val type = fieldTypes.getOrElse(i) { RsfType.INT }
                mv.visitVarInsn(ALOAD, 0)
                val param = ctorParams.getOrNull(i)
                if (i < elementCount && param != null) {
                    loadSlot(mv, param.type, param.slot)
                } else {
                    emitExpr(ctx, f.initializer)
                    emitWiden(mv, model.typeOf(f.initializer), type)
                }
                mv.visitFieldInsn(PUTFIELD, owner, f.name, descriptorOfType(type))
            }
        }

        if (ctor != null) {
            // 构造器体和普通方法体走同一条生成路径（实例方法，localOffset = 0）；
            // 体里那句 this(...) 已经在上面发过了，emitBody 会跳过它
            emitBody(
                mv, owner, ctor, localOffset = 0, returnType = RsfType.VOID, lvt = lvt,
                closures = ClosureScopes.of(model, ctor),
            )
        } else {
            // 隐式无参构造器：没有形参也没有局部变量，LVT 只有 0 号槽的 this
            mv.visitInsn(RETURN)
            endLvt(mv, lvt)
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        // 构造器体里的 lambda：合成方法同样紧跟在这个 <init> 后面（工单 §1）
        if (ctor != null) emitLambdaMethods(cw, owner, ctor)
    }

    /**
     * `<init>` 序言里的**父类那一跳**（阶段 25 K2）。
     *
     * 三种写法到了语义模型里是同一条 SuperDelegation：
     * - 类头 `class D(y : int) : Base(y)`（主构造，键是主构造那个 [FuncDecl]）；
     * - 次构造体第一句的 `super(实参)`（键是该次构造）；
     * - 编译器补的无参 `super()`：类写了构造器时挂在构造器上，一个构造器都没写时挂在类上
     *   （[org.example.complie.sema.ClassModel.implicitSuperDelegationOf]）。
     *
     * 判据是 `superClass.declaration == null`（父类来自 classpath），**不是 target==null**
     * ——rsf 父类一个构造器都没写时 target 也是 null（等价于隐式无参构造器）。
     */
    private fun emitParentCtor(mv: MethodVisitor, owner: String, decl: ClassDecl, ctor: FuncDecl?) {
        val delegation = if (ctor != null) {
            model.classModel.superDelegationOf(ctor)
        } else {
            model.classModel.implicitSuperDelegationOf(decl)
        }
        val parent = delegation?.superClass
        if (delegation == null || parent == null) {
            // 没有父类：还是那一条 `Object.<init>()V`，字节与阶段 24 完全一致
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            return
        }

        // 实参在**本构造器的帧**里求值（和 Java 一样：super(实参) 的实参先算），
        // 主构造的形参就在 1..n 号槽上。Checker 保证这些实参碰不到 this / 自己的字段
        // ——父类构造器返回之前，this 在 JVM 上还是 uninitializedThis，碰了会被校验器拒绝。
        val ctx = ExprCtx(mv, owner, ctor?.let { model.loweredOf(it) }, localOffset = 0)
        val args = delegation.arguments

        if (parent.declaration != null) {
            // rsf 父类：Checker 已经选好了要调哪个构造器（SuperDelegation.target）
            val rsfOwner = classInternalName(parent.declaration.name)
            val target = delegation.target
            if (target == null) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitMethodInsn(INVOKESPECIAL, rsfOwner, "<init>", "()V", false)
                return
            }
            val targetParams = model.paramTypesOf(target)
            mv.visitVarInsn(ALOAD, 0)
            for ((i, arg) in args.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), targetParams[i])
            }
            mv.visitMethodInsn(INVOKESPECIAL, rsfOwner, "<init>", descriptorOf(target), false)
            return
        }

        // JDK 父类（`class MyError(msg : string) : Exception(msg)`）。
        //
        // 用 **Checker 已经挑好的那一条** `<init>`（阶段 25 K4，缺口 D）：从前这里按
        // 「同一口径」再挑一次，而解释器又有自己的第三份打分——三份 `minByOrNull` 的平局
        // 规则只要有一处不同，同一份源码两端就会建出**不同的父类状态**。现在这条选择
        // 只做一次、挂在**这条委托边**上（[org.example.complie.sema.SuperDelegation.jvmCtor]）。
        val chosen = delegation.jvmCtor
            ?: throw RsfError(
                "codegen",
                "内部错误：没有为 '${decl.name}' 选中 JDK 父类 '${parent.internalName}' 的构造器",
                decl.at.line, decl.at.column, null,
            )
        mv.visitVarInsn(ALOAD, 0)
        for ((i, arg) in args.withIndex()) {
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), chosen.paramTypes[i])
        }
        mv.visitMethodInsn(INVOKESPECIAL, parent.internalName, "<init>", chosen.descriptor, false)
    }

    /**
     * 用户类的方法。
     *
     * 实例方法**不加** ACC_STATIC：0 号槽是 `this`，而 [org.example.complie.ir.Lowering]
     * 给实例方法分配的局部槽本来就是从 1 起来的，所以 `localOffset = 0`（不能重复加偏移）。
     * 类里叫 `main` 的方法也只是普通方法——它不带 `String[]` 形参，不是 JVM 入口。
     */
    private fun emitUserMethod(cw: ClassWriter, owner: String, fn: FuncDecl) {
        val access = accessOf(fn) or if (fn.isStatic) ACC_STATIC else 0
        // 阶段 25 K2：抽象方法（`abstract fun area() -> int`）只发签名，**不写 Code 属性**
        // ——带上 Code 的话类加载器会以 ClassFormatError 拒绝（JVMS 4.6：abstract / native
        // 方法不许有 Code）。它仍然必须发出来：`Shape.describe()` 里那句隐式 this 的
        // `area()` 编译成 `invokevirtual Shape.area`，类文件里没有这条声明时，方法解析
        // 会在 NoSuchMethodError 上停下（而不是像预期那样落到子类的实现上）。
        if (fn.isAbstract) {
            val mv = cw.visitMethod(access or ACC_ABSTRACT, fn.name, descriptorOf(fn), null, null)
            emitUseSites({ descriptor, visible -> mv.visitAnnotation(descriptor, visible) }, fn)
            mv.visitEnd()
            return
        }
        val mv = cw.visitMethod(access, fn.name, descriptorOf(fn), null, null)
        // 方法级注解使用点（阶段 21 M3）：写在 mv.visitEnd() 之前
        emitUseSites({ descriptor, visible -> mv.visitAnnotation(descriptor, visible) }, fn)
        mv.visitCode()
        // 实例方法的 0 号槽是 this；static 方法没有
        val lvt = beginLvt(mv, if (fn.isStatic) null else owner)
        emitBody(
            mv, owner, fn, localOffset = 0, returnType = model.returnTypeOf(fn), lvt = lvt,
            closures = ClosureScopes.of(model, fn),
        )
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        // lambda 合成方法紧跟在包围方法后面（工单 §1：方法表顺序确定）
        emitLambdaMethods(cw, owner, fn)
    }

    private fun accessOf(fn: FuncDecl): Int = when (fn.control) {
        ControlType.PRIVATE -> ACC_PRIVATE
        ControlType.PROTECTED -> ACC_PROTECTED
        ControlType.PUBLIC, ControlType.DEFAULT -> ACC_PUBLIC
    }

    /** 用户类方法的调用指令：static → INVOKESTATIC，private 实例方法 → INVOKESPECIAL，其余虚拟调用。 */
    private fun callOpcode(fn: FuncDecl): Int = when {
        fn.isStatic -> INVOKESTATIC
        fn.control == ControlType.PRIVATE -> INVOKESPECIAL
        else -> INVOKEVIRTUAL
    }

    /**
     * 用户类/接口**成员**的调用指令（阶段 20）：接收者静态类型是接口时一律 `INVOKEINTERFACE`。
     *
     * 这一支对**默认方法**同样成立：`var d : Dog = new Dog(); d.shout()` 里 `shout` 声明在
     * `Greeter` 上，javac 在这里发的也是 `invokeinterface Greeter.shout`（owner 是声明它的
     * 那个接口，而不是实现类）。
     */
    private fun callOpcode(member: UserMember.Method): Int =
        if (member.interfaceOwner) INVOKEINTERFACE else callOpcode(member.decl)

    /**
     * 只有实例方法/构造器/字段初值里才有 `this`（槽 0）。
     *
     * Checker 目前会放行「static 方法里写裸字段名 / 写 this / 隐式调实例方法」这种写法
     * （那是它那边待补的一个缺口），解释器跑到那里会抛内部错误。字节码这边如果不拦，
     * 生成的 `aload 0` 要等到类加载时才炸成一个 JVM 的 VerifyError——不如在这里说清楚。
     */
    private fun requireInstanceScope(ctx: ExprCtx, what: String) {
        // 字段初值跑在 <init> 里，本来就有 this
        val fn = ctx.body?.function ?: return
        val owner = model.classModel.ownerOf(fn) ?: return // 顶层函数碰不到这些构造
        if (!fn.isStatic) return
        throw RsfError(
            "codegen",
            "$what 需要 this，但 '${fn.name}' 是 ${owner.name} 的 stc 方法，没有 this。" +
                "去掉 stc 改成实例方法，或者别在这里用当前对象的字段/方法。",
            fn.at.line, fn.at.column, null,
        )
    }

    // ------------------------------------------------------------------ 枚举（阶段 19c）

    /** 枚举类型的描述符：`TokenKind` → `LTokenKind;`（跟着程序类的包走）。 */
    private fun enumDescriptorOf(enumName: String): String = "L${classInternalName(enumName)};"

    /**
     * 一个枚举编译出来的类（阶段 19c）——**普通类，不是 JVM 的 `enum`**。
     *
     * 生成出来的形状（口径写在 [org.example.complie.ast.EnumDecl] 的注释里）：
     * ```
     * public final class TokenKind extends java.lang.Object {
     *     public static final TokenKind IDENT;      // 每个值一个单例
     *     public static final TokenKind INT_LIT;
     *     private final String name;                // 私有的名字字段
     *     private TokenKind(String name) { super(); this.name = name; }
     *     public String toString() { return this.name; }
     *     static {                                  // <clinit>：造出全部单例
     *         IDENT = new TokenKind("IDENT");
     *         INT_LIT = new TokenKind("INT_LIT");
     *     }
     * }
     * ```
     *
     * 三个决定了形状的取舍：
     *
     * 1. **`<clinit>` 而不是「每个字段各自初始化」**：JVM 的静态字段初值本来就统一
     *    放在 `<clinit>` 里，一条 `PUTSTATIC` 一个值，顺序就是声明顺序。
     *    这样 `GETSTATIC` 第一次触发类初始化时，那些单例必然已经造好了。
     * 2. **单例**：值字段是 `public static final`，且构造器是 `private`——外面造不出
     *    第二个 `TokenKind`，所以引用相等就是「同一个常量」，`==` 在两端都成立。
     * 3. **`toString()` 返回名字**：这是「打印出值名」和「`"k = " + k` 拼出值名」
     *    两条路的**同一个落点**——JVM 上 `PrintStream.println(Object)` 与
     *    `String.valueOf(Object)` 都会调到它。解释器那边对应
     *    [org.example.complie.interp.Interpreter.RsfEnumValue.toString]，两个
     *    `toString` 返回的都是 `valueName` 本身，所以文本必然一致。
     *    （`StringBuilder.append(Object)` 那条也走同一个 `toString()`。）
     *
     * 类标记 `ACC_FINAL | ACC_SUPER | ACC_PUBLIC`：`final` 是因为 rsf 里没有继承，
     * 枚举更不该被继承；加了 `ACC_SUPER` 才是现代 JVM 的常规形态。
     */
    private fun enumClass(internalName: String, decl: EnumDecl): ClassWriter {
        val cw = newClassWriter()
        cw.visit(V17, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, internalName, null, "java/lang/Object", null)
        cw.visitSource(sourceName, null)

        val selfDesc = "L$internalName;"

        // 每个值一个 public static final 单例字段
        for (v in decl.values) {
            cw.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL, v.name, selfDesc, null, null).visitEnd()
        }
        // 私有的名字字段：toString() 返回的就是它
        cw.visitField(ACC_PRIVATE or ACC_FINAL, ENUM_NAME_FIELD, "Ljava/lang/String;", null, null).visitEnd()

        // private <init>(String name) { super(); this.name = name; }
        run {
            val mv = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;)V", null, null)
            mv.visitCode()
            // 生成的构造器：LVT 只有 this（那个 String 形参是合成的，不发）
            val lvt = beginLvt(mv, internalName)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitFieldInsn(PUTFIELD, internalName, ENUM_NAME_FIELD, "Ljava/lang/String;")
            mv.visitInsn(RETURN)
            endLvt(mv, lvt)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // public String toString() { return this.name; }
        run {
            val mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
            mv.visitCode()
            val lvt = beginLvt(mv, internalName)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, internalName, ENUM_NAME_FIELD, "Ljava/lang/String;")
            mv.visitInsn(ARETURN)
            endLvt(mv, lvt)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // static { IDENT = new TokenKind("IDENT"); ... }：顺序 = 声明顺序
        run {
            val mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
            mv.visitCode()
            for (v in decl.values) {
                mv.visitTypeInsn(NEW, internalName)
                mv.visitInsn(DUP)
                mv.visitLdcInsn(v.name)
                mv.visitMethodInsn(INVOKESPECIAL, internalName, "<init>", "(Ljava/lang/String;)V", false)
                mv.visitFieldInsn(PUTSTATIC, internalName, v.name, selfDesc)
            }
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw
    }

    // ------------------------------------------------------------------ 类骨架

    private fun emitDefaultConstructor(cw: ClassWriter, owner: String) {
        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        // 程序类的隐式无参构造器：LVT 只有 0 号槽的 this
        val lvt = beginLvt(mv, owner)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(RETURN)
        endLvt(mv, lvt)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 只有「无参的 main」才当 JVM 入口——带参数的 main 编译成普通静态方法。
     *
     * 类里的 `main` 一律不是入口：那是这个类自己的方法，`java 类名` 启动的是
     * 程序类以及它上面那个签名正确的 main。
     */
    private fun isEntry(fn: FuncDecl): Boolean =
        fn.name == "main" && fn.params.isEmpty() && !model.classModel.owner.containsKey(fn)

    private fun descriptorOf(fn: FuncDecl): String {
        val ret = model.returnTypeOf(fn)
        val sb = StringBuilder("(")
        if (isEntry(fn)) sb.append("[Ljava/lang/String;")
        for (t in model.paramTypesOf(fn)) sb.append(descriptorOfType(t))
        sb.append(')').append(descriptorOfType(ret))
        return sb.toString()
    }

    private fun emitFunction(cw: ClassWriter, owner: String, fn: FuncDecl) {
        val returnType = model.returnTypeOf(fn)

        if (isEntry(fn) && returnType != RsfType.VOID) {
            warnings += "入口函数 main 的返回类型是 ${returnType.rsfName}，" +
                "JVM 只认 void，这个类不能用 `java` 直接启动（写法：fn main() -> void）"
        }
        if (fn.name == "main" && fn.params.isNotEmpty()) {
            warnings += "main 带了 ${fn.params.size} 个参数，JVM 启动器只认无参的 " +
                "main(String[])，所以这个类不能用 `java` 直接启动（rsf 内部照样能调用它）"
        }

        // 顶层函数一律是静态方法（rsf 没有全局对象），<static> 槽在顶层只是把它写出来而已
        val mv = cw.visitMethod(accessOf(fn) or ACC_STATIC, fn.name, descriptorOf(fn), null, null)
        // 顶层函数上的注解使用点（阶段 21 M3）：hostName 是 null（顶层函数没有类型可挂
        // 访问器，带 companion 的注解 M2 已经拦掉了），但标注本身照样要写出来。
        emitUseSites({ descriptor, visible -> mv.visitAnnotation(descriptor, visible) }, fn)
        mv.visitCode()
        // 顶层函数一律 static：LVT 里没有 this，只有形参与局部变量
        val lvt = beginLvt(mv, null)
        emitBody(
            mv, owner, fn, localOffset = if (isEntry(fn)) 1 else 0, returnType = returnType, lvt = lvt,
            closures = ClosureScopes.of(model, fn),
        )
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        // lambda 合成方法紧跟在包围函数后面（工单 §1：方法表顺序确定）
        emitLambdaMethods(cw, owner, fn)
    }

    // ------------------------------------------------------------------ 函数值 / lambda（阶段 30b）

    /**
     * `LambdaMetafactory.metafactory` 的句柄——描述符是工单 §1 逐字规定的那个。
     *
     * 为什么不用 `altMetafactory`：rsf 的 lambda 不捕获泛型、不做序列化、没有
     * 桥接方法，`metafactory` 正好够用，而且它的参数表最短，30c 复刻时最不容易走样。
     */
    private val lambdaMetafactory = Handle(
        H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;",
        false,
    )

    /** 函数类型的 SAM 接口内部名：`(int, int) -> int` → `__RsfFn2`（工单 §1）。 */
    private fun samInternalName(arity: Int): String = "__RsfFn$arity"

    /** `invoke` 的描述符：形参全擦成 Object，返回也是 Object（工单 §1）。 */
    private fun samInvokeDescriptor(arity: Int): String =
        "(" + "Ljava/lang/Object;".repeat(arity) + ")Ljava/lang/Object;"

    /** 创建点那条 indy 的描述符：捕获值逐个进来，吐出一个 SAM 接口引用。 */
    private fun samFactoryDescriptor(arity: Int, captures: List<Capture>): String =
        "(" + captures.joinToString("") { descriptorOfType(it.type) } + ")L${samInternalName(arity)};"

    /**
     * lambda 实现方法的描述符：捕获值 + 自己的形参 → 声明返回类型（工单 §1）。
     *
     * **void 的 lambda 是个例外**：返回 `void` 的 lambda（`(int) -> void`）在这里落成
     * `Ljava/lang/Object;`，方法体末尾压 null 返回。原因是 JVM 的硬约束——
     * `LambdaMetafactory` 要求实现方法的返回类型**能放进** SAM 的返回类型（Object），
     * 而 `void` 放不进：
     * ```
     * LambdaConversionException: Type mismatch for lambda return: void is not convertible to class java.lang.Object
     * ```
     * 非 void 的 lambda 不受这条影响（基本类型由 metafactory 装箱，见
     * [instantiatedDescriptor]），所以只有这一档偏离工单。
     */
    private fun implDescriptorOf(closure: LambdaClosure): String {
        val sb = StringBuilder("(")
        for (c in closure.captures) sb.append(descriptorOfType(c.type))
        for (p in closure.params) sb.append(descriptorOfType(p.type))
        sb.append(')')
        val ret = if (closure.returnType == RsfType.VOID) {
            "Ljava/lang/Object;"
        } else {
            descriptorOfType(closure.returnType)
        }
        return sb.append(ret).toString()
    }

    /**
     * 程序里**实际用到**的函数类型 arity，升序。
     *
     * 口径：只看有降级体的函数/方法——lambda 只可能出现在这些体里。没有 lambda 的
     * 程序得到空表，[compile] 因此一个 SAM 类都不写（旧语料的类文件列表一个不差）。
     */
    private fun usedArities(): List<Int> {
        val arities = sortedSetOf<Int>()
        for (fn in lambdaHosts()) {
            for (closure in ClosureScopes.of(model, fn).closures) arities += closure.arity
        }
        return arities.toList()
    }

    /** 所有可能有 lambda 的函数/方法/构造器：顶层函数 + 类/接口/枚举的方法。 */
    private fun lambdaHosts(): List<FuncDecl> {
        val hosts = LinkedHashSet<FuncDecl>()
        hosts += model.lowered.keys
        hosts += model.classModel.methodLowered.keys
        return hosts.toList()
    }

    /**
     * SAM 接口类（工单 §1）：`__RsfFn<arity>`。
     *
     * 形状是硬性的——`V17`、`ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT`、父类 Object、
     * 没有字段没有构造器，只有一个抽象 `invoke`。30c 的自举后端要逐字节复刻它。
     */
    private fun samInterface(internalName: String, arity: Int): ByteArray {
        val cw = newClassWriter()
        cw.visit(
            V17,
            ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_ABSTRACT, "invoke", samInvokeDescriptor(arity), null, null,
        )
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * lambda 创建点（工单 §1）：把捕获值按顺序压栈，再一条 `invokedynamic`
     * 换来一个 SAM 实例。
     *
     * 栈上的东西**就是** indy 的实参——被调方法 `lambda$…` 把这几格当形参收，
     * 所以创建点不需要 new、不需要构造器，也不需要往字段里塞值。
     */
    private fun emitLambda(ctx: ExprCtx, expr: LambdaExpr) {
        val mv = ctx.mv
        val closure = ctx.closures.lambdaOf(expr)
            ?: throw IllegalStateException("内部错误：lambda 没有闭包分析结果（${expr.at.line} 行）")
        // 捕获值按工单 §1 的顺序压栈：`this` 在最前，然后按体里首次引用的顺序。
        for (c in closure.captures) {
            when (c) {
                is Capture.This -> emitThis(ctx, "lambda 里的 this")
                is Capture.Local -> loadSlot(mv, c.type, ctx.slotOf(c.binding) + ctx.localOffset)
            }
        }
        val samDesc = samFactoryDescriptor(closure.arity, closure.captures)
        mv.visitInvokeDynamicInsn(
            "invoke",
            samDesc,
            lambdaMetafactory,
            // samMethodType：`invoke` 在接口里的擦除形态 —— (Object…)Object
            Type.getMethodType(samInvokeDescriptor(closure.arity)),
            // 实现方法：程序类（或用户类）上的 private static 合成方法
            Handle(H_INVOKESTATIC, ctx.owner, closure.implName, implDescriptorOf(closure), false),
            // instantiatedMethodType：形参/返回的**真实类型**。JVM 校验要求它每一项都能
            // 放进 samMethodType 的 Object 里，所以基本类型在这一栏落成**包装类**
            // （`(int)int` → `(Ljava/lang/Integer;)Ljava/lang/Integer;`）——
            // 装箱/拆箱由 LambdaMetafactory 适配到实现方法自己的 `(I)I`。这正是 javac
            // 为 `Function<Integer,Integer>` 写出来的形状。
            Type.getMethodType(instantiatedDescriptor(closure)),
        )
    }

    /**
     * `instantiatedMethodType` 的描述符（工单 §1 的「形参/返回真实类型」）。
     *
     * 与 [implDescriptorOf] 的唯一区别：基本类型用**包装类**。JVM 的
     * `LambdaMetafactory` 会校验「samMethodType 的每一项都能装下 instantiated 的对应项」，
     * 而 `Object` 装不下 `int`（原始类型不是 Object 的子类型），只装得下 `Integer`。
     */
    private fun instantiatedDescriptor(closure: LambdaClosure): String {
        val sb = StringBuilder("(")
        for (p in closure.params) sb.append(samBoxedDescriptor(p.type))
        // void 的 lambda：`invoke` 的返回在接口里恒为 Object，JVM 校验要求
        // instantiated 和实现方法的返回都**能放进** Object 里（`void` 放不进），
        // 所以两栏都落成 Object（实现方法压 null 返回，见 [implDescriptorOf]）。
        sb.append(')').append(
            if (closure.returnType == RsfType.VOID) "Ljava/lang/Object;" else samBoxedDescriptor(closure.returnType),
        )
        return sb.toString()
    }

    /** 基本类型取包装类描述符，其余同 [descriptorOfType]。 */
    private fun samBoxedDescriptor(type: RsfType): String =
        if (type.isPrimitive) type.base.boxedDescriptor else descriptorOfType(type)

    /**
     * 把 [fn] 体里的 lambda 实现方法逐个发出去——**紧跟在包围方法后面**（工单 §1：
     * 方法表顺序必须确定，30c 要拿它对拍到字节）。
     */
    private fun emitLambdaMethods(cw: ClassWriter, owner: String, fn: FuncDecl) {
        val closures = ClosureScopes.of(model, fn)
        for (closure in closures.closures) {
            val mv = cw.visitMethod(
                ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC,
                closure.implName,
                implDescriptorOf(closure),
                null,
                null,
            )
            mv.visitCode()
            // 合成方法不发 LVT、不发 @RsfLabels：它的帧布局（捕获 + 形参 + 体里的
            // 局部变量）是自己的一套，和 `bodyLvtEntries` 的符号表口径不是一回事。
            emitBody(
                mv = mv,
                owner = owner,
                fn = fn,
                localOffset = 0,
                returnType = closure.returnType,
                lvt = null,
                closures = closures,
                closure = closure,
                voidReturnsObject = closure.returnType == RsfType.VOID,
            )
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }

    /**
     * 按名字调用一个**函数值**（`f(1)` 的 `f`，或 `f!!(1)` 里那个 `f!!`）：
     * 取出引用 → `CHECKCAST __RsfFn<arity>` → 实参装箱 → `INVOKEINTERFACE invoke`
     * → 按静态返回类型收敛（工单 §1）。
     */
    private fun emitFnValueCall(ctx: ExprCtx, expr: CallExpr, fnType: RsfType, target: Expr?) {
        val mv = ctx.mv
        val text = fnType.fnTypeText
            ?: throw IllegalStateException("内部错误：'${expr.name}' 的类型不是函数类型")
        val (params, _) = fnSignatureOf(text)
            ?: throw IllegalStateException("内部错误：函数类型解析失败（$text）")
        val arity = params.size
        val sam = samInternalName(arity)
        if (target != null) {
            // `f!!(1)`：目标本身是个表达式（ForceExpr），它已经把值收敛成函数类型了
            emitExpr(ctx, target)
        } else {
            val binding = ctx.closures.fnValueCallee(expr)
            if (binding != null) {
                loadSlot(mv, binding.type, ctx.slotOf(binding) + ctx.localOffset)
            } else {
                emitExpr(ctx, expr) // 走不到：emitCall 已判过 callee
            }
        }
        mv.visitTypeInsn(CHECKCAST, sam)
        for ((i, arg) in expr.arguments.withIndex()) {
            val declared = params.getOrNull(i) ?: fnType
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), declared)
            // SAM 的形参全是 Object：基本类型要装箱，引用类型原样（包括数组/可空值）
            if (declared.isPrimitive) boxValue(mv, declared.base)
        }
        mv.visitMethodInsn(INVOKEINTERFACE, sam, "invoke", samInvokeDescriptor(arity), true)
        // invoke 的返回在类文件里恒为 Object：void 的直接丢掉，其余按静态类型收敛
        val resultType = model.typeOf(expr)
        if (resultType == RsfType.VOID) mv.visitInsn(POP) else emitObjectTo(mv, resultType)
    }

    /**
     * 把栈上的一个 `Object`（擦除后的引用）收敛到 rsf 的静态类型 [want]。
     *
     * 和 [emitResultCast] 是同一条规矩，只是**没有**「声明类型」可比——`invoke` 的
     * 返回在类文件里恒为 Object。拿一个永不相等的声明类型当引子，复用同一份实现，
     * 避免两条路各自长出一套装箱/拆箱的分支。
     */
    private fun emitObjectTo(mv: MethodVisitor, want: RsfType) {
        emitResultCast(mv, RsfType.external("java/lang/Object"), want)
    }

    /**
     * 压 `this`。
     *
     * 普通方法体里它就是 0 号槽；**lambda 帧**里 0 号槽是第一个捕获值，`this` 得从
     * 自己的捕获槽取（工单 §1：`this` 算一项捕获，排在捕获清单最前）。
     */
    private fun emitThis(ctx: ExprCtx, what: String) {
        val slot = ctx.thisSlot
        if (slot != null) {
            ctx.mv.visitVarInsn(ALOAD, slot)
        } else {
            requireInstanceScope(ctx, what)
            ctx.mv.visitVarInsn(ALOAD, 0)
        }
    }

    // ------------------------------------------------------------------ 调试信息（第 26 刀：LVT）

    /** 一条 `LocalVariableTable` 条目：名字、字段描述符、JVM 帧里的**绝对**槽号。 */
    private class LvtEntry(val name: String, val desc: String, val slot: Int)

    /**
     * 一个方法体的 LVT 把手：[start] 是 `visitCode()` 之后立刻发的标签，[entries] 在
     * 发射过程中累积（`this` / 形参 / 局部变量 / catch 变量），最后交给 [endLvt] 写表。
     */
    private class LvtScope(val start: Label, val entries: MutableList<LvtEntry>)

    /**
     * 开一个方法级 LVT：`visitCode()` 之后**立刻**调用。
     *
     * 起止一律方法级（工单口径）：标签对 `[start, end)` 覆盖整个 Code，所以调试器里每条
     * 条目在方法全程都「活着」。`signature` 一律 `null` —— 不发明 `LocalVariableTypeTable`
     * （泛型签名表是另一件事，这一刀不发）。
     *
     * [thisOwner] 是实例方法的类内部名（槽 0 的 `this` 用它拼 `L…;`）；静态方法传 null。
     */
    private fun beginLvt(mv: MethodVisitor, thisOwner: String?): LvtScope {
        val entries = ArrayList<LvtEntry>()
        if (thisOwner != null) entries.add(LvtEntry("this", "L$thisOwner;", 0))
        val start = Label()
        mv.visitLabel(start)
        return LvtScope(start, entries)
    }

    /** 体发完后、`visitMaxs` 之前调用：发结束标签，再按**槽位升序**逐条写表。 */
    private fun endLvt(mv: MethodVisitor, scope: LvtScope) {
        val end = Label()
        mv.visitLabel(end)
        // 槽位是单调分配的（this 0 → 形参 → 局部变量），按槽位排正好是工单要的顺序：
        // this → 形参（声明序）→ 局部变量（含 catch 绑定，槽位升序）。
        for (e in scope.entries.sortedBy { it.slot }) {
            mv.visitLocalVariable(e.name, e.desc, null, scope.start, end, e.slot)
        }
    }

    /**
     * 把一个 rsf 函数/方法/构造器的符号表搬进 LVT：**全部**形参（声明序）+ **全部**
     * `IVarDecl` 的局部变量 + catch 变量。
     *
     * 槽位口径与 [emitBody] 里的 `storeSlot(…, binding.slot + localOffset)` 完全一致。
     * 局部变量**不看可达性**：[Lowering] 给所有语句都分配槽位并生成 `IVarDecl`，而自举
     * 后端的 Walker 也是全量走一遍 —— 两边条目必须一样多、一样排，否则 J3 会差字节。
     *
     * 后端内部的临时槽（`__rsf_orelse$N` 的形参、注解访问器的缓存槽之类）一个都不进来：
     * 它们不经 [model] 的符号表，本来也不在这个循环的范围内。
     */
    private fun bodyLvtEntries(fn: FuncDecl, body: LoweredFunction, localOffset: Int, scope: LvtScope) {
        for (p in model.paramsOf(fn)) {
            scope.entries.add(LvtEntry(p.name, descriptorOfType(p.type), p.slot + localOffset))
        }
        for (instr in body.instructions) {
            if (instr !is IVarDecl) continue
            val binding = model.bindingOf(instr.stmt)
            scope.entries.add(
                LvtEntry(binding.name, descriptorOfType(binding.type), binding.slot + localOffset),
            )
        }
        // catch 变量：Binding 只活在 Lowering 的 `catchBindings` 里（SemanticModel 没暴露），
        // 名字从 AST 的 TryStmt 取，槽位用异常表那条记录的 catchSlot。配对靠**顺序**：
        // Lowering 是「先保护体、再处理体、最后登记自己」的后序，和 [collectTryStmts] 一致。
        val tries = ArrayList<TryStmt>()
        collectTryStmts(fn.body, tries)
        if (tries.size == body.handlers.size) {
            for ((i, h) in body.handlers.withIndex()) {
                scope.entries.add(
                    LvtEntry(tries[i].catchName, "Ljava/lang/String;", h.catchSlot + localOffset),
                )
            }
        }
    }

    /** 后序收集全部 [TryStmt]（顺序 = `Lowering` 的 `handlerSpecs`；见 [bodyLvtEntries]）。 */
    private fun collectTryStmts(stmt: Stmt?, out: MutableList<TryStmt>) {
        when (stmt) {
            null -> return
            is Block -> for (s in stmt.statements) collectTryStmts(s, out)
            is IfStmt -> {
                collectTryStmts(stmt.body, out)
                collectTryStmts(stmt.elseBody, out)
            }
            is WhileStmt -> collectTryStmts(stmt.body, out)
            // for 的更新语句在 Lowering 里排在循环体**之后**（continue 落到它上面），照抄
            is ForStmt -> {
                collectTryStmts(stmt.body, out)
                collectTryStmts(stmt.step, out)
            }
            is SwitchStmt -> {
                for (c in stmt.cases) collectTryStmts(c.body, out)
                collectTryStmts(stmt.elseBody, out)
            }
            is TryStmt -> {
                collectTryStmts(stmt.body, out)
                collectTryStmts(stmt.catchBody, out)
                out.add(stmt)
            }
            else -> {}
        }
    }

    /**
     * 一个函数/方法/构造器的函数体。程序类的静态方法、用户类的实例方法、`<init>`
     * 都走这里——区别只在访问标志、描述符和 [localOffset]。
     *
     * [localOffset] 是「rsf 的 0 号局部槽对应 JVM 的几号槽」：入口 main 前面多一个
     * `String[]`（偏移 1），其余情况都是 0（实例方法的 `this` 正好落在 JVM 的 0 号槽，
     * 而 Lowering 给它的局部槽本来就从 1 开始）。
     */
    private fun emitBody(
        mv: MethodVisitor,
        owner: String,
        fn: FuncDecl,
        localOffset: Int,
        returnType: RsfType,
        lvt: LvtScope?,
        closures: ClosurePlan = ClosurePlan.EMPTY,
        closure: LambdaClosure? = null,
        voidReturnsObject: Boolean = false,
    ) {
        // 阶段 30b：lambda 的体是**另一个帧**（捕获项占最前面的槽），由闭包分析单独
        // 降级好了放在 [closure] 里；为 null 时就是常规的函数体。
        val body = closure?.body ?: model.loweredOf(fn)
        if (lvt != null && closure == null) bodyLvtEntries(fn, body, localOffset, lvt)
        val cf = ControlFlow(body.instructions, body.labelIndex, body.handlers)
        val reach = cf.reachable()
        val ctx = ExprCtx(mv, owner, body, localOffset, closures, closure)

        val labels = HashMap<String, Label>()
        for (instr in body.instructions) {
            if (instr is ILabel) labels[instr.label] = Label()
        }

        // ------------------------------------------------------------------ try/catch（阶段 14）
        //
        // 一条 try 对应 JVM 异常表里的一条记录：保护范围 [start, end) 里抛出来的
        // RuntimeException 跳到 handler，栈上带着那个异常对象。
        //
        // 只登记**保护体可达**的那些：不可达的标签根本不会被 visit，而 ASM 在
        // visitMaxs（COMPUTE_FRAMES）时碰到没访问过的 Label 会直接抛异常。
        // 保护体不可达 = 这段 try 永远不会执行，不登记也不会有语义差别。
        val handlerLabels = HashSet<String>()
        val handlerSlots = HashMap<String, Int>()
        for (h in body.handlers) {
            if (!reach[h.startPc]) continue
            handlerLabels += h.startLabel
            handlerLabels += h.endLabel
            handlerLabels += h.handlerLabel
            handlerSlots[h.handlerLabel] = h.catchSlot
            mv.visitTryCatchBlock(
                labels[h.startLabel]!!,
                labels[h.endLabel]!!,
                labels[h.handlerLabel]!!,
                "java/lang/RuntimeException",
            )
        }

        for ((i, instr) in body.instructions.withIndex()) {
            // 处理器用到的三个标签即使落在不可达位置也必须 visit 一次：
            // `try { rtn 1 } catch (e : string) { ... }` 的 try.end 就在 RETURN 后面，
            // 不 visit 的话异常表会指着一个没访问过的 Label。
            val forced = instr is ILabel && instr.label in handlerLabels
            if (!reach[i] && !forced) continue

            val line = instr.at.line.coerceAtLeast(1)
            if (instr is ILabel) {
                val label = labels[instr.label]!!
                mv.visitLabel(label)
                mv.visitLineNumber(line, label)
                // catch 处理体的入口：进这里时栈上正好是异常对象，取出消息写进 catch 变量。
                // （解释器在查表命中时做同一件事：把错误消息写进同一个槽位。）
                handlerSlots[instr.label]?.let { slot ->
                    mv.visitMethodInsn(
                        INVOKEVIRTUAL, "java/lang/RuntimeException", "getMessage",
                        "()Ljava/lang/String;", false,
                    )
                    storeSlot(mv, RsfType.STRING, slot + localOffset)
                }
                continue
            }

            val here = Label()
            mv.visitLabel(here)
            mv.visitLineNumber(line, here)

            when (instr) {
                is ILabel -> {}

                is IVarDecl -> {
                    val binding = model.bindingOf(instr.stmt)
                    emitExpr(ctx, instr.stmt.initializer)
                    emitWiden(mv, model.typeOf(instr.stmt.initializer), binding.type)
                    storeSlot(mv, binding.type, ctx.slotOf(binding) + localOffset)
                }

                is IAssign -> {
                    // 裸字段名赋值（`x = 1`）：目标是字段而不是局部槽，this 在 0 号槽
                    val field = model.classModel.bareFieldWrites[instr.stmt]
                    if (field != null) {
                        emitThis(ctx, "裸字段名 '${instr.stmt.name}'")
                        emitExpr(ctx, instr.stmt.value)
                        val declared = declaredFieldType(field.className, field.index, field.type)
                        // 往**声明**类型上收敛（泛型字段擦除后是 Object ⇒ 这里要装箱）
                        emitWiden(mv, model.typeOf(instr.stmt.value), declared)
                        mv.visitFieldInsn(
                            PUTFIELD, classInternalName(field.className), instr.stmt.name,
                            descriptorOfType(declared),
                        )
                    } else {
                        val binding = model.bindingOf(instr.stmt)
                        emitExpr(ctx, instr.stmt.value)
                        emitWiden(mv, model.typeOf(instr.stmt.value), binding.type)
                        storeSlot(mv, binding.type, ctx.slotOf(binding) + localOffset)
                    }
                }

                // `对象.字段 = ...`：接收者先求值（`this.字段` 的接收者就是 0 号槽），
                // 再求值右侧，最后 PUTFIELD。
                is IFieldAssign -> {
                    val field = model.classModel.fieldWrites[instr.stmt]
                        ?: throw IllegalStateException(
                            "内部错误：字段赋值没有解析到字段（${instr.stmt.at.line} 行）",
                        )
                    val target = instr.stmt.target
                    if (target.receiver is ThisExpr) {
                        emitThis(ctx, "this.${target.name}")
                    } else {
                        emitExpr(ctx, target.receiver)
                    }
                    emitExpr(ctx, instr.stmt.value)
                    val declared = declaredFieldType(field.className, field.index, field.type)
                    // 往**声明**类型上收敛（泛型字段擦除后是 Object ⇒ 这里要装箱）
                    emitWiden(mv, model.typeOf(instr.stmt.value), declared)
                    mv.visitFieldInsn(
                        PUTFIELD, classInternalName(field.className), target.name,
                        descriptorOfType(declared),
                    )
                }

                is IExpr -> {
                    // discard：这条语句的结果马上就要 POP 掉，别做泛型互操作的结果收敛——
                    // `m.put(k, v)` 的返回值（旧值）可能是 null，拆箱会炸，而 Java 里
                    // 把它当语句写是**不拆箱**的（见 [emitResultCast] 的调用点）
                    emitExpr(ctx, instr.stmt.expr, discard = true)
                    val t = model.typeOf(instr.stmt.expr)
                    if (t != RsfType.VOID) {
                        mv.visitInsn(if (t.slots == 2) POP2 else POP)
                    }
                }

                // `this(实参)` —— 构造器委托（阶段 18）：指令已经在 `<init>` 的序言里发过了
                // （见 emitUserConstructor），这里什么都不用发，委托之后的语句照常往下走。
                is ICtorDelegate -> {}

                // `super(实参)` —— **父类**构造委托（阶段 25 K2）：和 `this(...)` 一样，
                // 指令已经在 `<init>` 的序言里发过了（见 emitParentCtor），这里什么都不用发。
                // 顺序也由此保证：父类构造器 → 本类字段初值 → 构造器体（DESIGN-25 §6），
                // 与解释器 runConstructor 一致。
                is ISuperDelegate -> {}

                // `数组[下标] = 值`（阶段 13）。求值顺序与解释器逐字对齐：
                // 接收者 → 长度 → 下标 → **越界检查** → 右值 → `*ASTORE`。
                // 右值放在检查之后：越界时右值里的副作用一次都不能发生
                // （JVM 上越界会直接抛，`a[i] = f()` 里的 f 根本不会跑）。
                is IIndexAssign -> {
                    val target = instr.stmt.target
                    val element = model.typeOf(target)
                    emitExpr(ctx, target.receiver)
                    mv.visitInsn(DUP)
                    mv.visitInsn(ARRAYLENGTH)
                    emitExpr(ctx, target.index)
                    emitArrayIndexCheck(mv)
                    emitExpr(ctx, instr.stmt.value)
                    emitWiden(mv, model.typeOf(instr.stmt.value), element)
                    mv.visitInsn(arrayStoreOpcode(element))
                }

                is IReturn -> {
                    val value = instr.stmt.value
                    if (value == null) {
                        // 裸 `rtn`：void 方法用 RETURN；void 的 lambda 方法返回 Object
                        // （见 implDescriptorOf），得压 null 再 ARETURN
                        if (voidReturnsObject) mv.visitInsn(ACONST_NULL)
                        mv.visitInsn(if (voidReturnsObject) ARETURN else RETURN)
                    } else if (value is NullLit) {
                        // 可空返回类型：压 null 再按返回类型的指令返回
                        mv.visitInsn(ACONST_NULL)
                        mv.visitInsn(returnInsn(returnType))
                    } else {
                        emitExpr(ctx, value)
                        emitWiden(mv, model.typeOf(value), returnType)
                        mv.visitInsn(returnInsn(returnType))
                    }
                }

                // `fail <消息>`：new RuntimeException(消息) + ATHROW。
                // 直接用 JDK 现成的异常类型，不新造类——解释器那边抛的 RsfFailure
                // 也带着同样的消息，两端的可观察行为一致（谁接住、消息是什么）。
                is IFail -> {
                    mv.visitTypeInsn(NEW, "java/lang/RuntimeException")
                    mv.visitInsn(DUP)
                    emitExpr(ctx, instr.stmt.message)
                    mv.visitMethodInsn(
                        INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                        "(Ljava/lang/String;)V", false,
                    )
                    mv.visitInsn(ATHROW)
                }

                is IGoto -> mv.visitJumpInsn(GOTO, labels[instr.label]!!)

                is IBranch -> {
                    emitExpr(ctx, instr.cond)
                    mv.visitJumpInsn(IFNE, labels[instr.target]!!)
                }

                // `switch`（阶段 19c）——一条 ISwitch 发一串「取值 + 逐常量比较」：
                //
                // ```
                //     <被分派的值>              ; 只求值一次，结果留在栈上：[v]
                //     DUP; GETSTATIC E.A; IF_ACMPEQ pad0
                //     DUP; GETSTATIC E.B; IF_ACMPEQ pad1
                //     POP                        ; 一个都没命中：[]
                //     GOTO default  /  throw     ; 没写 else 且穷尽时是 throw（值只能是 null）
                // pad0: POP; GOTO case0          ; 把比较时留下的 v 弹掉，再进分支体
                // pad1: POP; GOTO case1
                // case0: ...                     ; 分支体看到的栈是干净的（[]）
                // ```
                //
                // 两个要点：
                // - **值只求一次**（`switch (f())` 里的 f 只能跑一次），靠比较之前的
                //   `DUP` 保住它；`IF_ACMPEQ` 是**引用比较**，和解释器那句
                //   `subject === enumValue(...)` 是同一种比较（枚举是单例）。
                // - **必须过一层「弹出跳板」（pad）**：跳转时 v 还在栈上，而分支体
                //   期望空栈——直接跳到分支体上会得到「栈上有 1 个元素」的帧，
                //   校验器当场拒绝（VerifyError: Instruction type does not match stack map）。
                //   跳板只有两条指令（POP; GOTO），但它是让栈在分支入口处干净的关键。
                is ISwitch -> {
                    val enumName = model.typeOf(instr.stmt.subject).simpleExternalName
                    val pads = instr.stmt.cases.map { Label() }
                    emitExpr(ctx, instr.stmt.subject)
                    for ((i, c) in instr.stmt.cases.withIndex()) {
                        mv.visitInsn(DUP)
                        mv.visitFieldInsn(
                            GETSTATIC, classInternalName(c.enumName), c.valueName,
                            enumDescriptorOf(c.enumName),
                        )
                        mv.visitJumpInsn(IF_ACMPEQ, pads[i])
                    }
                    mv.visitInsn(POP)
                    val fallback = instr.defaultTarget
                    if (fallback != null) {
                        mv.visitJumpInsn(GOTO, labels[fallback]!!)
                    } else {
                        // 没写 else 且 case 穷尽：静态上到不了这里（值只可能是 null）。
                        // 发的形状和 `fail "..."` 一模一样（`throw new RuntimeException(消息)`），
                        // 解释器那边抛的是同一个消息的 RsfFailure——两端的 catch 与输出因此一致。
                        mv.visitTypeInsn(NEW, "java/lang/RuntimeException")
                        mv.visitInsn(DUP)
                        mv.visitLdcInsn(EnumRuntime.noMatchFailure(enumName))
                        mv.visitMethodInsn(
                            INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                            "(Ljava/lang/String;)V", false,
                        )
                        mv.visitInsn(ATHROW)
                    }
                    for ((i, pad) in pads.withIndex()) {
                        mv.visitLabel(pad)
                        mv.visitInsn(POP)
                        mv.visitJumpInsn(GOTO, labels[instr.targets[i]]!!)
                    }
                }
            }
        }

        // 走到函数末尾：void 函数正常结束；非 void 时 Checker 已经拦住了，这里是防御
        if (reach[cf.exit] && voidReturnsObject) {
            // void 的 lambda 走到体末尾：返回 null（对应描述符里的 Object 返回）
            mv.visitInsn(ACONST_NULL)
            mv.visitInsn(ARETURN)
        } else if (reach[cf.exit]) {
            if (returnType == RsfType.VOID) {
                mv.visitInsn(RETURN)
            } else {
                mv.visitTypeInsn(NEW, "java/lang/IllegalStateException")
                mv.visitInsn(DUP)
                mv.visitLdcInsn("rsf: 函数 '${fn.name}' 缺少 rtn")
                mv.visitMethodInsn(
                    INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                    "(Ljava/lang/String;)V", false,
                )
                mv.visitInsn(ATHROW)
            }
        }

        // 体发完、visitMaxs 之前：发 LVT 的结束标签，再逐条写表（见 beginLvt）
        if (lvt != null) endLvt(mv, lvt)

        // 阶段 28a：再把标签名写进 @RsfLabels（同样必须在 visitMaxs 之前）。
        // lambda 合成方法不发：标签表属于包围方法那一帧。
        if (closure == null) emitLabelMetadata(mv, fn)
    }

    /**
     * 阶段 28a：把本方法的用户标签名按**声明顺序**写成 runtime-visible 注解
     * `@RsfLabels(value = string[])`。
     *
     * 收集口径（工单 §4）：直接按 `body.instructions` 的顺序扫 [ILabel]，不去重
     * （重名已被 Checker 拦在语义阶段）。`Lowering` 给内部标签留了 `'\u0000'` 前缀
     * （`Lowering.kt:301-302` 的 `fresh()`），那不是用户写的标签，这里一律滤掉——
     * 否则每个带 try/catch 或 `when` 的方法都会凭空多出一条注解、字节全变。
     *
     * 描述符 `LRsfLabels;` 是**悬空**的：故意不生成 `RsfLabels` 注解接口类
     * （DESIGN-28 §2），所以反射读它会 `TypeNotPresentException`，只有 ASM /
     * `javap -v` 这类字节码级工具看得见。
     *
     * 没有标签的方法**一个字节都不发**（连注解都不出现）⇒ 旧语料的产出逐字节不变。
     */
    private fun emitLabelMetadata(mv: MethodVisitor, fn: FuncDecl) {
        val names = ArrayList<String>()
        for (instr in model.loweredOf(fn).instructions) {
            if (instr is ILabel && !instr.label.startsWith(SYNTHETIC_LABEL_PREFIX)) names += instr.label
        }
        if (names.isEmpty()) return
        val av: AnnotationVisitor = mv.visitAnnotation("LRsfLabels;", true)
        val arr = av.visitArray("value")
        for (name in names) arr.visit(null, name)
        arr.visitEnd()
        av.visitEnd()
    }

    // ------------------------------------------------------------------ 表达式

    /**
     * 表达式生成的上下文。
     *
     * [body] 是「这条表达式属于哪个降级函数」，只有生成方法体时有；字段初值不在任何
     * 函数帧里（它们跑在 `<init>` 里），那时是 null。
     */
    private class ExprCtx(
        val mv: MethodVisitor,
        val owner: String,
        val body: LoweredFunction?,
        val localOffset: Int,
        /** 本方法的闭包清单（lambda 创建点、函数值调用都从这儿查）。 */
        val closures: ClosurePlan = ClosurePlan.EMPTY,
        /** 非 null 表示正在发一个 lambda 合成方法——帧布局是闭包自己的那一套。 */
        private val closure: LambdaClosure? = null,
    ) {

        /** lambda 帧里 `this` 的捕获槽；不在 lambda 里就是 null（= 0 号槽）。 */
        val thisSlot: Int? = closure?.captures?.firstOrNull { it is Capture.This }?.slot

        /**
         * 本帧里某个绑定占哪一格。
         *
         * 常规帧就是 `binding.slot`；lambda 帧要先过一层闭包分析的槽位表（捕获项、
         * Checker 另造的形参绑定都在这张表里换号）。
         */
        fun slotOf(binding: Binding): Int = closure?.slotOf(binding) ?: binding.slot
    }

    private fun emitExpr(ctx: ExprCtx, expr: Expr, discard: Boolean = false) {
        val mv = ctx.mv
        when (expr) {
            is IntLit -> pushInt(mv, expr.value)
            is LongLit -> mv.visitLdcInsn(expr.value)
            is FloatLit -> mv.visitLdcInsn(expr.value)
            is DoubleLit -> mv.visitLdcInsn(expr.value)
            is StringLit -> mv.visitLdcInsn(expr.value)
            // 字符字面量：char 在 JVM 栈上就是它的码点（int），压一个整数即可
            is CharLit -> pushInt(mv, expr.value.code)
            is BoolLit -> mv.visitInsn(if (expr.value) ICONST_1 else ICONST_0)
            is NullLit -> mv.visitInsn(ACONST_NULL)

            // `super` / `super(接口)` —— 阶段 25 K1：它**不是**能单独取值的表达式，
            // 只允许出现在 `super.方法(...)` 的接收者位置，那种情况由 Checker 记进
            // `superCalls` 表、走成员调用那条路（见 emitExpr 的 MemberExpr 分支）。
            // 走到这里说明是一个裸 `super`——前端已经拦掉了，这里只是兜底。
            is SuperExpr -> throw RsfError(
                "codegen", "'super' 只能当方法调用的接收者（super.方法(...)）", expr.at.line, expr.at.column, null,
            )

            is VarExpr -> {
                // 方法体里的裸字段名（`rtn x`）：先看类解析结果，其次才是局部变量槽。
                // 命中字段时 this 就是 0 号槽。
                val field = model.classModel.fieldReads[expr]
                if (field != null) {
                    emitThis(ctx, "裸字段名 '${expr.name}'")
                    mv.visitFieldInsn(
                        GETFIELD, classInternalName(field.className), expr.name,
                        descriptorOfType(field.type),
                    )
                } else {
                    val binding = model.bindingOf(expr)
                    loadSlot(mv, binding.type, ctx.slotOf(binding) + ctx.localOffset)
                }
            }

            // 阶段 12-C2：用户自定义类
            is NewExpr -> emitNew(ctx, expr)

            // 阶段 13：数组
            is NewArrayExpr -> emitNewArray(ctx, expr)

            is IndexExpr -> {
                val element = model.typeOf(expr)
                emitExpr(ctx, expr.receiver) // 栈上：[数组]
                mv.visitInsn(DUP)
                mv.visitInsn(ARRAYLENGTH) // [数组, 长度]
                emitExpr(ctx, expr.index) // [数组, 长度, 下标]
                emitArrayIndexCheck(mv) // [数组, 下标]（越界就抛，不越界原样返回下标）
                mv.visitInsn(arrayLoadOpcode(element))
            }

            // 实例方法（含构造器）的 0 号槽就是 this；lambda 帧里它是第一个捕获槽
            is ThisExpr -> emitThis(ctx, "this")

            is CallExpr -> if (model.isConstruction(expr)) emitNew(ctx, expr) else emitCall(ctx, expr)

            is UnaryExpr -> {
                emitExpr(ctx, expr.operand)
                when (expr.op) {
                    TokenKind.MINUS -> mv.visitInsn(
                        // 操作数在栈上的**实际宽度**由「提升后」的基础类型决定：
                        // byte / char 在 JVM 栈上就是 int，Checker 也把 `-b` 的结果定成
                        // 提升后的类型（见 Checker 的 UnaryExpr → `operand.base.promoted()`），
                        // 所以这里必须先 promoted() 再挑指令。
                        // 以前直接比 RsfType.INT/LONG/FLOAT/DOUBLE 四种静态类型，
                        // `-b`（b : byte）会抛「一元减号的操作数不是数值」——
                        // 而 rsf check 放行、rsf run 正常，三方不一致（2026-10-11 修）。
                        when (val base = model.typeOf(expr.operand).base.promoted()) {
                            RsfBase.INT -> INEG
                            RsfBase.LONG -> LNEG
                            RsfBase.FLOAT -> FNEG
                            RsfBase.DOUBLE -> DNEG
                            else -> throw IllegalStateException("一元减号的操作数不是数值（$base）")
                        },
                    )
                    TokenKind.BANG -> {
                        // !b  ==  b ^ 1
                        mv.visitInsn(ICONST_1)
                        mv.visitInsn(IXOR)
                    }
                    else -> throw IllegalStateException("不认识的一元运算符 ${expr.op}")
                }
            }

            is BinaryExpr -> emitBinary(ctx, expr)

            is IfExpr -> {
                // 三目：两条分支各算一遍，宽度统一到表达式静态类型后再汇合，
                // 保证合流点两侧栈上类型一致（int vs long 混着汇合是校验器不认的）
                val resultType = model.typeOf(expr)
                val onTrue = Label()
                val end = Label()
                emitExpr(ctx, expr.condition)
                mv.visitJumpInsn(IFNE, onTrue)
                emitExpr(ctx, expr.whenFalse)
                emitWiden(mv, model.typeOf(expr.whenFalse), resultType)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(onTrue)
                emitExpr(ctx, expr.whenTrue)
                emitWiden(mv, model.typeOf(expr.whenTrue), resultType)
                mv.visitLabel(end)
            }

            is ElvisExpr -> emitElvis(ctx, expr)

            is ForceExpr -> {
                val operandType = model.typeOf(expr.operand)
                val resultType = model.typeOf(expr)
                if (isErrorCall(expr.operand)) {
                    // `f()!!`：只是把错误往上抛，什么都不用发——异常自己会传出去
                    // （Checker 已经保证本函数/本 try 接得住它）
                    emitExpr(ctx, expr.operand)
                    return
                }
                val ok = Label()
                emitExpr(ctx, expr.operand)          // 引用形式（装箱或本来就是引用）
                mv.visitInsn(DUP)
                mv.visitJumpInsn(IFNONNULL, ok)
                mv.visitInsn(POP)
                mv.visitTypeInsn(NEW, "java/lang/NullPointerException")
                mv.visitInsn(DUP)
                mv.visitLdcInsn("rsf: 对 null 使用了 '!!'")
                mv.visitMethodInsn(
                    INVOKESPECIAL, "java/lang/NullPointerException", "<init>",
                    "(Ljava/lang/String;)V", false,
                )
                mv.visitInsn(ATHROW)
                mv.visitLabel(ok)
                if (resultType.isPrimitive) unboxValue(mv, resultType.base)
            }

            // 阶段 24：类型判断。`INSTANCEOF` 对 null 本身就回 0（= false），
            // 正好对上「`null is A` 恒为 false」；`!is` 再把结果取反。
            is IsExpr -> {
                emitExpr(ctx, expr.operand)
                mv.visitTypeInsn(INSTANCEOF, internalNameOf(model.castTargetOf(expr)))
                if (expr.negated) {
                    mv.visitInsn(ICONST_1)
                    mv.visitInsn(IXOR)
                }
            }

            // 阶段 24：类型转换。`?as` 失败得 null，`as` 失败抛（见 [emitCastHelper]）。
            is AsExpr -> {
                if (expr.safe) emitSafeCast(ctx, expr) else emitCheckedCast(ctx, expr)
            }

            // 阶段 30b：lambda 创建点——捕获值压栈 + 一条 LambdaMetafactory 的 indy。
            // 实现方法（`lambda$<包围方法>$<k>`）由 [emitLambdaMethods] 紧跟在包围方法后面发。
            is LambdaExpr -> emitLambda(ctx, expr)

            is MemberExpr -> {
                // `super.方法(...)` / `super(接口).方法(...)`（阶段 25 K2）必须**最先**查这张表：
                // Checker 把「super 调用到底落到哪一份」记在 superCalls 里（owner 是**声明**
                // 那个方法的父类/接口；接口默认方法那一路 interfaceOwner = true），而
                // `super.方法` 本身在 members / memberOf 两张表里都查不到东西——不先查就会走到
                // `IllegalStateException("内部错误：未解析的成员")`。
                val superCall = model.classModel.superCallOf(expr)
                if (superCall != null) {
                    emitSuperMember(ctx, expr, superCall, discard)
                    return
                }
                // 阶段 19c：枚举常量 `TokenKind.IDENT` → `GETSTATIC TokenKind.IDENT LTokenKind;`。
                // 它一定是 STATIC 字段：单例是在 `<clinit>` 里造好的（见 [enumClass]），
                // 所以这条指令本身就是「取那个唯一实例」——两端因此拿到同一个对象，
                // `==`（引用相等 / Objects.equals）在两端的判等结果必然相同。
                val enumRef = model.enumValueOf(expr)
                if (enumRef != null) {
                    mv.visitFieldInsn(
                        GETSTATIC, classInternalName(enumRef.enumName), enumRef.valueName,
                        enumDescriptorOf(enumRef.enumName),
                    )
                } else if (isArrayLength(expr)) {
                    // 阶段 13：数组的 `.length` 是属性（Checker 保证这里只有它，且不带括号）。
                    // 判断用**静态类型**，解释器读的是同一份语义模型，两端不可能走偏。
                    emitExpr(ctx, expr.receiver)
                    mv.visitInsn(ARRAYLENGTH)
                } else {
                    // 用户自定义类的成员先查——它跟 JVM 反射那条路是两张互斥的表
                    val user = model.classModel.members[expr]
                    if (user != null) {
                        emitUserMember(ctx, expr, user, discard)
                    } else {
                        when (val target = model.memberOf(expr)) {
                            is ResolvedField -> {
                                val f = target.field
                                if (f.isStatic) {
                                    mv.visitFieldInsn(GETSTATIC, f.owner, f.name, f.descriptor)
                                } else {
                                    emitExpr(ctx, expr.receiver)
                                    mv.visitFieldInsn(GETFIELD, f.owner, f.name, f.descriptor)
                                }
                            }

                            is ResolvedMethod -> {
                                val m = target.method
                                if (!m.isStatic) emitExpr(ctx, expr.receiver)
                                for ((i, arg) in expr.arguments.orEmpty().withIndex()) {
                                    emitExpr(ctx, arg)
                                    emitWiden(mv, model.typeOf(arg), m.paramTypes[i])
                                }
                                // owner 是接口（`Iterator` / `List` / `Comparator`…）只能发 INVOKEINTERFACE：
                                // 发 INVOKEVIRTUAL 会在运行期抛 IncompatibleClassChangeError（Find interface ...,
                                // but class was expected），而解释器那条路照常能跑——两端就背离了。
                                // 判定由 Checker 解析时写进 ResolvedMethod，后端只照做。
                                mv.visitMethodInsn(
                                    when {
                                        m.isStatic -> INVOKESTATIC
                                        target.interfaceOwner -> INVOKEINTERFACE
                                        else -> INVOKEVIRTUAL
                                    },
                                    m.owner, m.name, m.descriptor, target.interfaceOwner,
                                )
                                // 泛型集合：`get()` 擦除后返回 Object，静态类型是元素类型。
                                // **结果被丢弃时不做这一步**（见 [emitExpr] 的 discard）：
                                // `m.put(k, v)` 返回的是旧值，可能为 null，白白拆箱会在
                                // 空表上 NPE——Java 里 `map.put(...)` 当语句写也不会炸。
                                if (!discard) emitResultCast(mv, m.returnType, model.typeOf(expr))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * `接收者.length` 里接收者是不是数组（阶段 13）。
     *
     * 用 `exprTypes` 的**查表**而不是 `model.typeOf`：静态成员访问（`Math.max(...)`）
     * 的接收者是一个类名，Checker 从不给它标注类型——`typeOf` 会在那里直接抛
     * 「表达式缺少类型标注」。查表拿不到就说明它不是数组，正常走后面的分支。
     */
    private fun isArrayLength(expr: MemberExpr): Boolean =
        expr.arguments == null &&
            expr.name == "length" &&
            model.exprTypes[expr.receiver]?.isArray == true

    /**
     * 数组的创建（阶段 13）：`new T[n]` / `new T[n][m]`。
     *
     * 长度的求值次序就是压栈次序（一维一个），和解释器逐字对齐；每一维都过一遍
     * 负数检查（JVM 的 `NegativeArraySizeException` 解释器接不住）。
     *
     * 指令的选择：
     * - 一维 + 基本类型 → `NEWARRAY <atype>`（`int[]` 是 `T_INT`）
     * - 一维 + 引用类型 → `ANEWARRAY <组件类型>`
     * - 多维 → `MULTIANEWARRAY <数组描述符>, 维数`（`new int[3][4]` → `[[I`，
     *   一次分配出「3 个长度为 4 的 int 数组」，正好就是「数组的数组」）
     */
    private fun emitNewArray(ctx: ExprCtx, expr: NewArrayExpr) {
        val mv = ctx.mv
        val resultType = model.typeOf(expr)
        val component = resultType.elementType

        for (dim in expr.dimensions) {
            emitExpr(ctx, dim)
            mv.visitMethodInsn(
                INVOKESTATIC, programInternalName,
                ArrayRuntime.LENGTH_HELPER, ArrayRuntime.LENGTH_HELPER_DESC, false,
            )
        }

        if (expr.dimensions.size == 1) {
            if (component.isPrimitive) {
                mv.visitIntInsn(NEWARRAY, primitiveArrayType(component.base))
            } else {
                mv.visitTypeInsn(ANEWARRAY, internalNameOf(component))
            }
        } else {
            mv.visitMultiANewArrayInsn(descriptorOfType(resultType), expr.dimensions.size)
        }
    }

    /**
     * 越界检查：栈上是 `[数组, 长度, 下标]`，调用辅助方法之后变成 `[数组, 下标]`。
     *
     * 辅助方法 `(int length, int index) -> int` 不越界就把下标原样还回来，
     * 所以调用点不用额外的局部变量来存数组或下标。
     */
    private fun emitArrayIndexCheck(mv: MethodVisitor) {
        mv.visitMethodInsn(
            INVOKESTATIC, programInternalName,
            ArrayRuntime.INDEX_HELPER, ArrayRuntime.INDEX_HELPER_DESC, false,
        )
    }

    /**
     * 取数组元素的指令：**按元素类型选**，跟 javac 完全一致。
     *
     * `byte[]` 用 `BALOAD`、`char[]` 用 `CALOAD`、`bool[]` 用 `BALOAD`
     * （JVM 规范里 `baload` 同时管 boolean 与 byte）、`int[]` 用 `IALOAD`；
     * 引用类型（含**数组的数组**——`int[][]` 的元素是 `int[]`）用 `AALOAD`。
     */
    private fun arrayLoadOpcode(element: RsfType): Int = when {
        element.isArray || !element.base.isPrimitive || element.nullable -> AALOAD
        element.base == RsfBase.BOOL || element.base == RsfBase.BYTE -> BALOAD
        element.base == RsfBase.CHAR -> CALOAD
        element.base == RsfBase.INT -> IALOAD
        element.base == RsfBase.LONG -> LALOAD
        element.base == RsfBase.FLOAT -> FALOAD
        else -> DALOAD
    }

    /** 存数组元素的指令：与 [arrayLoadOpcode] 一一对应。 */
    private fun arrayStoreOpcode(element: RsfType): Int = when {
        element.isArray || !element.base.isPrimitive || element.nullable -> AASTORE
        element.base == RsfBase.BOOL || element.base == RsfBase.BYTE -> BASTORE
        element.base == RsfBase.CHAR -> CASTORE
        element.base == RsfBase.INT -> IASTORE
        element.base == RsfBase.LONG -> LASTORE
        element.base == RsfBase.FLOAT -> FASTORE
        else -> DASTORE
    }

    /** `NEWARRAY` 的 atype：每种基本类型一个固定编号。 */
    private fun primitiveArrayType(base: RsfBase): Int = when (base) {
        RsfBase.BOOL -> T_BOOLEAN
        RsfBase.BYTE -> T_BYTE
        RsfBase.CHAR -> T_CHAR
        RsfBase.INT -> T_INT
        RsfBase.LONG -> T_LONG
        RsfBase.FLOAT -> T_FLOAT
        RsfBase.DOUBLE -> T_DOUBLE
        else -> throw IllegalStateException("不能建数组的基本类型: $base")
    }

    /**
     * 一个构造点：显式 `new 类名(实参)`，或者**省掉 `new`** 的 `类名(实参)`
     * （AST 上后者是 [CallExpr]，但 Checker 已经把它判定成构造了）。
     *
     * 发的是 `NEW` + `DUP` + 实参 + `<init>`；两种写法的指令序列**一模一样**。
     */
    private fun emitNew(ctx: ExprCtx, expr: Expr) {
        val mv = ctx.mv
        val args = model.constructionArgs(expr)

        // 构造导入的 JVM 类（`new ArrayList&<int>()` / `ArrayList&<int>()`）：
        // 直接 NEW + INVOKESPECIAL <init>
        model.externalNewTargets[expr]?.let { target ->
            val m = target.method
            mv.visitTypeInsn(NEW, m.owner)
            mv.visitInsn(DUP)
            for ((i, arg) in args.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), m.paramTypes[i])
            }
            mv.visitMethodInsn(INVOKESPECIAL, m.owner, "<init>", m.descriptor, false)
            return
        }

        val decl = model.classModel.newTargets[expr]
            ?: throw IllegalStateException(
                "内部错误：构造没有解析到类 '${model.constructionName(expr)}'",
            )
        val owner = classInternalName(decl.name)

        mv.visitTypeInsn(NEW, owner)
        mv.visitInsn(DUP)

        // 用哪一个构造器是 Checker 挑好的（constructor 重载）；null = 隐式无参构造器
        val ctor = model.classModel.ctorOf(expr)
        val params = if (ctor == null) emptyList() else model.paramTypesOf(ctor)
        for ((i, arg) in args.withIndex()) {
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), params[i])
        }
        // `new Marker("hi")`（阶段 21 M3）：注解的伴生主构造有**每个元素**一个形参，
        // 而使用点可以只写一部分（其余的落到元素默认值上，M2 允许少给实参）。
        // 少了这一步，`new Marker("hi")` 会去调一个不存在的 `<init>(Ljava/lang/String;)V`，
        // 运行期 NoSuchMethodError。缺的实参在这里按元素声明顺序补上默认值。
        model.annotationConstructionOf(expr)?.let { spec ->
            for (i in args.size until spec.elements.size) {
                val element = spec.elements[i]
                val default = element.decl.default
                    ?: throw RsfError(
                        "codegen",
                        "内部错误：注解 '@${spec.name}' 的元素 '${element.name}' 在使用点没有值，" +
                            "也没有默认值（M2 本该拦掉）。",
                        expr.at.line, expr.at.column, null,
                    )
                pushAnnotationValue(mv, element.type, element.name, default)
            }
        }
        mv.visitMethodInsn(
            INVOKESPECIAL, owner, "<init>",
            "(" + params.joinToString("") { descriptorOfType(it) } + ")V", false,
        )
    }

    /** 用户类的成员访问：字段走 GETFIELD，方法按 static/private 选调用指令。 */
    private fun emitUserMember(ctx: ExprCtx, expr: MemberExpr, member: UserMember, discard: Boolean = false) {
        val mv = ctx.mv

        when (member) {
            is UserMember.Field -> {
                // rsf 没有静态字段，所以字段访问一定有一个实例接收者
                if (expr.receiver is ThisExpr) {
                    emitThis(ctx, "this.${expr.name}")
                } else {
                    emitExpr(ctx, expr.receiver)
                }
                // 描述符用**声明**类型（泛型字段擦除后是 Object），取回来之后再按
                // 替换后的类型收敛（CHECKCAST / 拆箱）——见 [declaredFieldType]
                val declared = declaredFieldType(member.className, member.index, member.type)
                mv.visitFieldInsn(
                    GETFIELD, classInternalName(member.className), expr.name,
                    descriptorOfType(declared),
                )
                // `var value : T` 擦除后是 Object：按接收者的实参类型收敛回来
                // （结果被丢弃时就别转了：`Box&<int>` 的值可能是 null，拆箱会炸，
                //   而 Java 里写 `box.value;` 这种语句是不拆箱的）
                if (!discard) emitResultCast(mv, declared, model.typeOf(expr))
            }

            is UserMember.Method -> {
                val owner = classInternalName(member.className)
                val decl = member.decl
                if (!decl.isStatic) emitExpr(ctx, expr.receiver)
                val params = model.paramTypesOf(decl)
                for ((i, arg) in expr.arguments.orEmpty().withIndex()) {
                    emitExpr(ctx, arg)
                    emitWiden(mv, model.typeOf(arg), params[i])
                }
                // 阶段 20：接收者的静态类型是接口 → 发 INVOKEINTERFACE（`itf` 参数必须为 true）。
                // 拿着接口的 owner 发 INVOKEVIRTUAL 会在运行期抛 IncompatibleClassChangeError
                // （Found interface Greeter, but class was expected），而解释器那条路照常能跑。
                // 继承来的**默认方法**也是这一支：owner 是**声明**它的那个接口，方法表在运行期
                // 按接收者的真实类解析（实现类没覆盖就落到接口的默认实现上）。
                mv.visitMethodInsn(callOpcode(member), owner, decl.name, descriptorOf(decl), member.interfaceOwner)
                // `fun get() -> T` 擦除后返回 Object，静态类型来自类型实参
                // （结果被丢弃时不转，理由同上面那条：值可能为 null）
                if (!discard) emitResultCast(mv, model.returnTypeOf(decl), model.typeOf(expr))
            }
        }
    }

    /**
     * `super.方法(...)` / `super(接口名).方法(...)`（阶段 25 K2）。
     *
     * 与普通成员调用只有两处不同，两处都是 `super` 语义的要害：
     * - **指令一律 `INVOKESPECIAL`**（接口默认方法那一路 `itf = true`）。非虚调用：绕过
     *   运行期类的覆盖，直接跑**声明**的那一份。发成 INVOKEVIRTUAL 的话
     *   `override fun tag() -> string { rtn super.tag() }` 就是自己调自己，栈溢出。
     * - **owner 是声明那个方法的类/接口**（Checker 记进 superCalls 的 `className`），
     *   不是本类（本类压根没有这条方法的声明）。
     *
     * 接收者不看语法树：`super.f()` 的接收者语义上就是 this（0 号槽），`super(I).f()` 也一样
     * ——接口默认方法体里的 this 就是实现类的那个对象。
     */
    private fun emitSuperMember(ctx: ExprCtx, expr: MemberExpr, member: SuperCall, discard: Boolean) {
        when (member) {
            is SuperCall.Rsf -> emitSuperRsfMember(ctx, expr, member, discard)
            // 阶段 25 K4（缺口 C）：父类那一份在 classpath 上（`super.getMessage()`）。
            // 目标方法没有 rsf 的 FuncDecl，描述符与 owner 都从 classpath 那份 **JvmMethod**
            // 来——owner 是**声明类**（`java/lang/Throwable`），这正是非虚调用的落点。
            is SuperCall.Jvm -> emitSuperJvmMember(ctx, expr, member.member, discard)
        }
    }

    /** [SuperCall.Rsf] 那一支：目标有 rsf 的 `FuncDecl`，描述符由它算出来。 */
    private fun emitSuperRsfMember(
        ctx: ExprCtx,
        expr: MemberExpr,
        member: SuperCall.Rsf,
        discard: Boolean,
    ) {
        val mv = ctx.mv
        val decl = member.decl
        requireInstanceScope(ctx, "super.${expr.name}(...)")
        mv.visitVarInsn(ALOAD, 0)
        val params = model.paramTypesOf(decl)
        for ((i, arg) in expr.arguments.orEmpty().withIndex()) {
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), params[i])
        }
        mv.visitMethodInsn(
            INVOKESPECIAL, classInternalName(member.className), decl.name, descriptorOf(decl),
            member.interfaceOwner,
        )
        if (!discard) emitResultCast(mv, model.returnTypeOf(decl), model.typeOf(expr))
    }

    /**
     * [SuperCall.Jvm] 那一支：目标是 **classpath 上的方法**（阶段 25 K4，缺口 C）。
     *
     * 与 rsf 那一支的差别只在「描述符与 owner 从哪来」：这里全部抄 [ResolvedMethod] 里
     * 那条已解析的 `JvmMethod`（`owner` 是**声明类**、`descriptor` 是擦除形态）。
     * 泛型擦除的收口交给 [emitResultCast]——和普通 JDK 调用走同一条规矩，两端一致。
     */
    private fun emitSuperJvmMember(
        ctx: ExprCtx,
        expr: MemberExpr,
        member: ResolvedMethod,
        discard: Boolean,
    ) {
        val mv = ctx.mv
        val m = member.method
        requireInstanceScope(ctx, "super.${expr.name}(...)")
        mv.visitVarInsn(ALOAD, 0)
        for ((i, arg) in expr.arguments.orEmpty().withIndex()) {
            emitExpr(ctx, arg)
            m.paramTypes.getOrNull(i)?.let { emitWiden(mv, model.typeOf(arg), it) }
        }
        mv.visitMethodInsn(INVOKESPECIAL, m.owner, m.name, m.descriptor, member.interfaceOwner)
        if (!discard) emitResultCast(mv, m.returnType, model.typeOf(expr))
    }

    /**
     * 泛型互操作的结果收敛。
     *
     * JVM 没有泛型：`Box&<int>.get()` 与 `ArrayList&<int>.get()` 声明返回的都是擦除后的
     * `Object`，而 rsf 的静态类型（来自类型实参）是 `int`。所以这里补一条
     * `CHECKCAST`（基本类型再 `intValue()` 拆箱），解释器那边靠
     * `coerceGenericResult` 做同一件事——两端因此表现一致。
     */
    private fun emitResultCast(mv: MethodVisitor, declared: RsfType, want: RsfType) {
        if (declared == want || want.isVoid || want.isNullLiteral) return
        // 类体内部：`T` 本来就是 Object，不需要转。但 `T[]` **要**转——
        // 它擦除后是 `[Ljava/lang/Object;`，是有自己的描述的
        if (want.isTypeParam && !want.isArray) return
        when {
            want.isPrimitive -> {
                want.base.wrapper?.let { mv.visitTypeInsn(CHECKCAST, it) }
                unboxValue(mv, want.base)
            }
            want.nullable && want.base.isPrimitive ->
                want.base.wrapper?.let { mv.visitTypeInsn(CHECKCAST, it) }
            else -> mv.visitTypeInsn(CHECKCAST, internalNameOf(want))
        }
    }

    /**
     * 一条 `CHECKCAST` 要的内部名。
     *
     * ASM 的 `visitTypeInsn` 收的是**内部名**（`java/lang/String`），不是描述符
     * （`Ljava/lang/String;`）——两者混用会在类加载时报
     * `ClassFormatError: Illegal class name`。
     */
    private fun internalNameOf(type: RsfType): String {
        // 阶段 13：数组——ASM 的 visitTypeInsn / ANEWARRAY 收的是「内部名**或者**
        // 数组描述符」，数组给描述符即可（`[I`、`[Ljava/lang/String;`）
        if (type.isArray) return descriptorOfType(type)
        // 阶段 30b：函数类型 CHECKCAST 到它的 SAM 接口（`__RsfFn<arity>`）
        type.fnTypeText?.let { text ->
            val sig = fnSignatureOf(text)
                ?: throw IllegalStateException("内部错误：函数类型解析失败（$text）")
            return samInternalName(sig.first.size)
        }
        // 类型参数擦除到 Object（`new T[3]` 建出来的就是 Object[]）
        if (type.isTypeParam) return "java/lang/Object"
        // 可空的基本类型在 JVM 上是包装类（`?int[]` 的元素是 Integer）
        if (type.nullable && type.base.isPrimitive) return type.base.wrapper!!
        val name = type.externalName
        if (type.isExternal && name != null) {
            // 用户类要跟着程序类的包走；classpath 上的 JVM 类原样用内部名。
            // 接口（含注解接口，阶段 21 M3）不在此列——见 `classInternalName` 的说明。
            return if (model.classModel.classes.containsKey(name)) {
                classInternalName(name)
            } else {
                name
            }
        }
        if (type.base == RsfBase.STRING) return "java/lang/String"
        throw IllegalStateException("不能 CHECKCAST 到 $type")
    }

    /**
     * `a ?: b`。
     *
     * 难点是两条分支压在栈上的表示必须一致：左边是可空类型（引用形式：引用类型就是它自己，
     * 基本类型是包装类），右边是结果类型。所以让**两条路都汇成「结果的装箱引用」**，
     * 汇合之后再按需要拆箱。左边的基础类型和结果基础类型不同时（`?int ?: 0L`），
     * 在非空分支里拆箱→加宽→重新装箱。
     */
    // ------------------------------------------------------------------ 类型转换 / 类型判断（阶段 24）

    /**
     * `x as A`：值是 null 或者不是 A 就抛 `RuntimeException`，否则原样返回。
     *
     * 判型与抛错全在程序类的 [CAST_HELPER] 里做，调用点只发三件事：
     * 求值操作数、压目标类常量、压目标 JVM 名，然后 `INVOKESTATIC` + `CHECKCAST`。
     *
     * **为什么不内联** `if` + `throw`：调用点往往在表达式中间（`println(x as A)` 里
     * 栈上还压着 PrintStream），一进异常处理器栈上就只剩异常对象，生成出来的类
     * 会以 VerifyError 报废——这条经验见 [emitParseIntHelper] 与 [emitErrorCallElvis]。
     *
     * 为什么把**类常量**传进去，而不是给每个目标类型生成一个专用方法：目标可能是
     * `A`、`A[]`、`I` 接口、擦除后的 `Object`（泛型目标）……一个通用方法就够了，
     * 而且 `Class.isInstance` 对数组也给出与 `INSTANCEOF` 相同的结果
     * （`[LB;` 的 `isInstance` 只认 `B[]` 的实例）。
     */
    private fun emitCheckedCast(ctx: ExprCtx, expr: AsExpr) {
        val mv = ctx.mv
        val target = model.castTargetOf(expr)
        emitExpr(ctx, expr.operand)
        mv.visitLdcInsn(Type.getObjectType(castTargetInternal(target)))
        mv.visitLdcInsn(jvmClassNameOf(target))
        mv.visitMethodInsn(
            INVOKESTATIC, programInternalName, CAST_HELPER, CAST_HELPER_DESC, false,
        )
        mv.visitTypeInsn(CHECKCAST, internalNameOf(target))
    }

    /**
     * `x ?as A`：判型失败得 `null`，不抛。
     *
     * 骨架（判型失败那一支把值替换成 null）：
     *
     * ```
     * 求值 x            // [v]
     * DUP               // [v, v]
     * INSTANCEOF A      // [v, 是不是 A]
     * IFEQ  Lnull       // [v]
     * CHECKCAST A       // [A]
     * GOTO  Lend
     * Lnull: POP        // []
     *        ACONST_NULL
     * Lend:             // [A 或 null]
     * ```
     *
     * 两支汇合时栈上都是一个引用，校验器把 `A` 与 `null` 合成 `A`——不需要额外的局部变量。
     * 这里**可以**内联分支：两支都是纯粹的「值替换」，没有 try/catch 保护范围，
     * 中间也不需要空栈。
     */
    private fun emitSafeCast(ctx: ExprCtx, expr: AsExpr) {
        val mv = ctx.mv
        val target = model.castTargetOf(expr)
        val internal = castTargetInternal(target)
        val nullBranch = Label()
        val end = Label()
        emitExpr(ctx, expr.operand)
        mv.visitInsn(DUP)
        mv.visitTypeInsn(INSTANCEOF, internal)
        mv.visitJumpInsn(IFEQ, nullBranch)
        mv.visitTypeInsn(CHECKCAST, internal)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(nullBranch)
        mv.visitInsn(POP)
        mv.visitInsn(ACONST_NULL)
        mv.visitLabel(end)
    }

    /**
     * `INSTANCEOF` / `CHECKCAST` / 类常量要的内部名。
     *
     * 和 [internalNameOf] 的差别只有一处：数组在这里已经是合法的
     * `INSTANCEOF` 操作数（`[LB;`），不用再拆；类型参数按擦除算 `Object`。
     */
    private fun castTargetInternal(target: RsfType): String = internalNameOf(target)

    /**
     * 目标类型在**运行期**的 JVM 名——就是 `Class.getName()` 会给的那个字符串：
     * 类用点分名（`demo.B`）、数组用描述符（`[LB;`、`[I`）。
     *
     * 错误消息里印的就是它（`rsf: 不能把 X 当成 Y` 的 Y）。真正的实现在
     * [SemanticModel.jvmRuntimeNameOf]：解释器也调同一个方法，两端的文案
     * 逐字一致靠的是**只有一份实现**，不是两边各写一遍再对。
     */
    private fun jvmClassNameOf(type: RsfType): String = model.jvmRuntimeNameOf(type)

    /**
     * 程序类上的类型转换辅助方法 `__rsf_cast(Object, Class, String) -> Object`（阶段 24）。
     *
     * 语义（与 [Interpreter] 的 `castValue` 逐字对齐）：
     * ```
     * if (v == null)                 throw new RuntimeException("rsf: 不能把 null 当成 " + targetName);
     * if (!target.isInstance(v))     throw new RuntimeException("rsf: 不能把 "
     *                                        + v.getClass().getName() + " 当成 " + targetName);
     * return v;
     * ```
     *
     * 放在**程序类**上并且是 public 的：用户类里的方法也要跨类调它（和 `parseInt`
     * 那批辅助方法是同一个理由）。名字带 `$`，rsf 的标识符语法写不出，绝不撞车。
     */
    private fun emitCastHelper(cw: ClassWriter) {
        val mv = cw.visitMethod(
            ACC_PUBLIC or ACC_STATIC,
            CAST_HELPER,
            CAST_HELPER_DESC,
            null,
            null,
        )
        mv.visitCode()

        // if (v == null) throw new RuntimeException("rsf: 不能把 null 当成 " + targetName)
        val nonNull = Label()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitJumpInsn(IFNONNULL, nonNull)
        emitRuntimeExceptionHeader(mv)
        mv.visitLdcInsn("rsf: 不能把 null 当成 ")
        mv.visitVarInsn(ALOAD, 2)
        emitConcat(mv)
        emitRuntimeExceptionThrow(mv)
        mv.visitLabel(nonNull)

        // if (!target.isInstance(v)) throw new RuntimeException("rsf: 不能把 " + 实际类名 + " 当成 " + targetName)
        val ok = Label()
        mv.visitVarInsn(ALOAD, 1)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false,
        )
        mv.visitJumpInsn(IFNE, ok)
        emitRuntimeExceptionHeader(mv)
        mv.visitLdcInsn("rsf: 不能把 ")
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false,
        )
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false,
        )
        emitConcat(mv)
        mv.visitLdcInsn(" 当成 ")
        emitConcat(mv)
        mv.visitVarInsn(ALOAD, 2)
        emitConcat(mv)
        emitRuntimeExceptionThrow(mv)
        mv.visitLabel(ok)

        mv.visitVarInsn(ALOAD, 0)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitElvis(ctx: ExprCtx, expr: ElvisExpr) {
        val mv = ctx.mv
        // 阶段 14：`f() ?: 兜底`——左边是 !T 调用时按「出错取右边」生成，不是 null 判断
        if (isErrorCall(expr.left)) {
            emitErrorCallElvis(ctx, expr)
            return
        }
        val leftType = model.typeOf(expr.left)
        val rightType = model.typeOf(expr.right)
        val resultType = model.typeOf(expr)

        val have = Label()
        val end = Label()

        emitExpr(ctx, expr.left)
        mv.visitInsn(DUP)
        mv.visitJumpInsn(IFNONNULL, have)
        mv.visitInsn(POP)
        emitExpr(ctx, expr.right)
        emitWiden(mv, rightType, resultType)
        if (resultType.isPrimitive) boxValue(mv, resultType.base)
        mv.visitJumpInsn(GOTO, end)

        mv.visitLabel(have)
        if (resultType.isPrimitive) {
            // 栈上是 Boxed<leftBase>，换算成 Boxed<resultBase>
            unboxValue(mv, leftType.base)
            if (leftType.base != resultType.base) {
                mv.visitInsn(widenInsn(leftType.base, resultType.base))
            }
            boxValue(mv, resultType.base)
        }
        mv.visitLabel(end)

        if (resultType.isPrimitive) unboxValue(mv, resultType.base)
    }

    /**
     * `f() ?: 兜底`（f 是 `!T` 调用，阶段 14）：出错就取右边的值。
     *
     * **为什么不用内联的 try/catch**：JVM 的异常处理器一进去，操作数栈就只有那个异常对象了
     * ——而表达式中间求值时，调用方往往还有没消费完的栈值（`println(f() ?: 1)` 里已经压好的
     * PrintStream）。内联的处理器没法把那些值「放回去」，生成出来的类会在类加载时
     * 以 `VerifyError: Instruction type does not match stack map` 直接报废。
     *
     * 所以这里学 javac 处理 try-with-resources 的做法：把「调用 + 捕获」搬进一个**合成静态
     * 方法**（[OrElse]），它的保护范围从空栈开始，天然合法。调用点变成：
     * ```
     *     接收者、实参…        （实参照常在调用点求值，于是实参抛的错不算这次调用出错）
     *     invokestatic __rsf_orelse$N(...)Ljava/lang/Object;
     *     dup / ifnonnull 正常  // 失败时返回 null
     *     失败：pop，求兜底值
     * ```
     * 用 null 当失败标记是安全的：`!T` 的正常结果不可能是 null（返回类型非空，
     * 可空 + !T 的组合已经在 Checker 里拦下了）。
     */
    private fun emitErrorCallElvis(ctx: ExprCtx, expr: ElvisExpr) {
        val mv = ctx.mv
        val resultType = model.typeOf(expr)
        val helper = emitOrElseCall(ctx, expr.left)

        // 合成方法也是当前 owner 上的静态方法。阶段 20：owner 是**接口**时（默认方法体里的
        // `?:`），它得按 InterfaceMethodref 引用——接口的静态方法不能用 Class 引用去 invoke，
        // 否则 `IncompatibleClassChangeError: Method '... Fetcher.__rsf_orelse$0(...)'
        // must be InterfaceMethodref constant`。
        mv.visitMethodInsn(
            INVOKESTATIC, ctx.owner, helper.name, helper.descriptor, isInterfaceInternalName(ctx.owner),
        )

        val ok = Label()
        val done = Label()
        mv.visitInsn(DUP)
        mv.visitJumpInsn(IFNONNULL, ok)
        mv.visitInsn(POP)
        emitExpr(ctx, expr.right)
        emitWiden(mv, model.typeOf(expr.right), resultType)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(ok)
        emitResultCast(mv, RsfType.OBJECT, resultType)
        mv.visitLabel(done)
    }

    /**
     * 一个 `?:` 调用点用的合成静态方法：调用目标，出错返回 null。
     *
     * [params] 就是合成方法的形参（接收者 + 目标方法的形参）；结果统一按 `Object` 返回
     * （基本类型装箱，调用点再按静态类型收敛回来）。
     */
    private class OrElse(
        val name: String,
        val descriptor: String,
        /** 调用目标用的指令：INVOKESTATIC / INVOKEVIRTUAL / INVOKESPECIAL / INVOKEINTERFACE。 */
        val opcode: Int,
        val owner: String,
        val target: String,
        val targetDescriptor: String,
        val params: List<RsfType>,
        /** 目标方法的返回类型（`!void` 不会走到这里）。 */
        val result: RsfType,
        /** 目标是入口 main：要补一个 String[] 实参。 */
        val entryArg: Boolean,
        /** 目标的 owner 是接口：`INVOKEINTERFACE` 的最后一个实参 `itf`（阶段 20）。 */
        val itf: Boolean = false,
    )

    private val orElseMethods = ArrayList<OrElse>()
    private var orElseCounter = 0

    /** 登记一个合成方法并返回它的名字（同名的不合并：玩具语言里不值得为此建索引）。 */
    private fun registerOrElse(
        opcode: Int,
        owner: String,
        target: String,
        targetDescriptor: String,
        params: List<RsfType>,
        result: RsfType,
        entryArg: Boolean,
        itf: Boolean = false,
    ): OrElse {
        val name = "__rsf_orelse$" + orElseCounter++
        val descriptor = "(" + params.joinToString("") { descriptorOfType(it) } + ")Ljava/lang/Object;"
        val spec = OrElse(
            name, descriptor, opcode, owner, target, targetDescriptor, params, result, entryArg, itf,
        )
        orElseMethods.add(spec)
        return spec
    }

    /**
     * 把 `?:` 左边那次「可能失败的调用」拆开：接收者与实参**照常在调用点求值**并压栈
     * （它们就是合成方法的形参），返回登记好的合成方法描述。
     */
    private fun emitOrElseCall(ctx: ExprCtx, expr: Expr): OrElse {
        val mv = ctx.mv

        if (expr is CallExpr) {
            // 阶段 16：`parseInt(s) ?: 兜底`；阶段 16b：`readText(p) ?: 兜底`——
            // 内置函数没有 FuncDecl，合成方法里直接调程序类上的辅助方法（名字/描述符
            // 登记在 Builtin 上）。实参照常在这里求值，所以「实参自己抛的错」不算这次调用失败（两端一致）。
            Builtins[expr.name]?.takeIf { it.mayError }?.let { builtin ->
                val builtinParams = ArrayList<RsfType>()
                for ((i, arg) in expr.arguments.withIndex()) {
                    emitExpr(ctx, arg)
                    emitWiden(mv, model.typeOf(arg), builtin.params[i])
                    builtinParams.add(builtin.params[i])
                }
                return registerOrElse(
                    INVOKESTATIC, programInternalName,
                    builtin.helperName
                        ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法名"),
                    builtin.helperDesc
                        ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法描述符"),
                    builtinParams, builtin.ret, entryArg = false,
                )
            }

            // 类里按名字直接调本类方法（隐式 this / 隐式类名）
            val implicit = model.classModel.implicitCalls[expr]
            if (implicit != null) {
                val decl = implicit.decl
                val owner = classInternalName(implicit.className)
                val params = ArrayList<RsfType>()
                if (!decl.isStatic) {
                    requireInstanceScope(ctx, "'${expr.name}(...)'（隐式 this 调用）")
                    mv.visitVarInsn(ALOAD, 0)
                    params.add(RsfType.external(owner))
                }
                val declared = model.paramTypesOf(decl)
                for ((i, arg) in expr.arguments.withIndex()) {
                    emitExpr(ctx, arg)
                    emitWiden(mv, model.typeOf(arg), declared[i])
                    params.add(declared[i])
                }
                return registerOrElse(
                    callOpcode(implicit), owner, decl.name, descriptorOf(decl), params,
                    model.returnTypeOf(decl), entryArg = false, itf = implicit.interfaceOwner,
                )
            }

            val target = model.callTargets[expr]
                ?: throw IllegalStateException("内部错误：?: 左边的调用没有解析到函数 '${expr.name}'")
            val declared = model.paramTypesOf(target)
            val params = ArrayList<RsfType>()
            for ((i, arg) in expr.arguments.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), declared[i])
                params.add(declared[i])
            }
            return registerOrElse(
                INVOKESTATIC, programInternalName, target.name, descriptorOf(target), params,
                model.returnTypeOf(target), entryArg = isEntry(target),
            )
        }

        if (expr is MemberExpr) {
            val member = model.classModel.members[expr] as? UserMember.Method
                ?: throw IllegalStateException(
                    "内部错误：?: 左边的成员调用没有解析到方法 '${expr.name}'",
                )
            val decl = member.decl
            val owner = classInternalName(member.className)
            val params = ArrayList<RsfType>()
            if (!decl.isStatic) {
                if (expr.receiver is ThisExpr) {
                    emitThis(ctx, "this.${expr.name}")
                } else {
                    emitExpr(ctx, expr.receiver)
                }
                params.add(RsfType.external(owner))
            }
            val declared = model.paramTypesOf(decl)
            for ((i, arg) in expr.arguments.orEmpty().withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), declared[i])
                params.add(declared[i])
            }
            return registerOrElse(
                callOpcode(member), owner, decl.name, descriptorOf(decl), params,
                model.returnTypeOf(decl), entryArg = false, itf = member.interfaceOwner,
            )
        }

        throw IllegalStateException("内部错误：?: 的左边不是一次可能失败的调用")
    }

    /** 把登记下来的合成方法写进当前类（每个类生成完之后调用一次）。 */
    private fun emitOrElseMethods(cw: ClassWriter) {
        for (m in orElseMethods) {
            val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC, m.name, m.descriptor, null, null)
            mv.visitCode()
            val start = Label()
            val end = Label()
            val handler = Label()
            // 保护范围从**空栈**开始，handler 里也只有异常对象——这就是它能通过校验的原因
            mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException")
            mv.visitLabel(start)
            if (m.entryArg) mv.visitInsn(ACONST_NULL)
            var slot = 0
            for (p in m.params) {
                loadSlot(mv, p, slot)
                slot += p.slots
            }
            mv.visitMethodInsn(m.opcode, m.owner, m.target, m.targetDescriptor, m.itf)
            if (m.result.isPrimitive) boxValue(mv, m.result.base)
            mv.visitLabel(end)
            mv.visitInsn(ARETURN)

            mv.visitLabel(handler)
            mv.visitInsn(ACONST_NULL)
            mv.visitInsn(ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        orElseMethods.clear()
    }

    /**
     * 这个表达式是不是一次「可能失败」的调用（`!T` 函数 / `!T` 方法）。
     *
     * 判据就是语义模型里已经解析好的那几张表——解释器用的是同一份，两端
     * 对「这里要不要按错误处理」的判断因此不会走偏。
     */
    private fun isErrorCall(expr: Expr): Boolean {
        // 阶段 16：内置函数里可能失败的（parseInt）也要走「出错取兜底值」这条路。
        // 它没有 FuncDecl 可以查，按名字查 Builtins（用户函数不允许占用内置函数名）。
        if (expr is CallExpr) {
            Builtins[expr.name]?.let { return it.mayError }
        }
        val fn = when (expr) {
            is CallExpr -> model.callTargets[expr] ?: model.classModel.implicitCalls[expr]?.decl
            is MemberExpr -> (model.classModel.members[expr] as? UserMember.Method)?.decl
            else -> null
        } ?: return false
        return fn.ret.mayError
    }

    private fun emitCall(ctx: ExprCtx, expr: CallExpr) {
        val mv = ctx.mv

        // 阶段 30b：`f(1)` 里的 `f` 是一个**函数值**（局部变量/形参/捕获项）。
        // Checker 让局部变量遮住同名顶层函数，所以这里必须抢在隐式调用和 callTargets
        // 之前判——判据就是闭包分析记下的那张表（与解释器读同一份，见 ClosurePlan）。
        val fnValue = ctx.closures.fnValueCallee(expr)
        if (fnValue != null) {
            emitFnValueCall(ctx, expr, fnValue.type, null)
            return
        }
        if (expr.callee != null && model.typeOf(expr.callee).isFunctionType) {
            emitFnValueCall(ctx, expr, model.typeOf(expr.callee), expr.callee)
            return
        }

        if (expr.name == "print" || expr.name == "println") {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            if (expr.arguments.isEmpty()) {
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", expr.name, "()V", false)
            } else {
                val arg = expr.arguments[0]
                val argType = model.typeOf(arg)
                emitExpr(ctx, arg)
                // 可空的基本类型装箱后是 Object；null 字面量按 String 走
                val desc = when {
                    argType.isNullLiteral -> "(Ljava/lang/String;)V"
                    argType.nullable && argType.base.isPrimitive -> "(Ljava/lang/Object;)V"
                    else -> argType.printDescriptor
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", expr.name, desc, false)
            }
            return
        }

        // 阶段 16：带签名的内置函数（toByte / toChar / charToInt / parseInt / isDigit…）
        Builtins[expr.name]?.let { builtin ->
            emitBuiltinCall(ctx, expr, builtin)
            return
        }

        // 类里按名字直接调本类方法（隐式 this / 隐式类名）
        val implicit = model.classModel.implicitCalls[expr]
        if (implicit != null) {
            val decl = implicit.decl
            // 实例方法的接收者就是当前对象：0 号槽（lambda 帧里是捕获槽）
            if (!decl.isStatic) {
                emitThis(ctx, "'${expr.name}(...)'（隐式 this 调用）")
            }
            val params = model.paramTypesOf(decl)
            for ((i, arg) in expr.arguments.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), params[i])
            }
            // 阶段 20：隐式调用也可能落在**接口**上——默认方法体里写 `greet(n)` 调的就是
            // 同一个接口里的另一个方法，它必须发成 INVOKEINTERFACE（而不是 INVOKEVIRTUAL），
            // 否则类加载/首次执行时报 `IncompatibleClassChangeError: Found interface Greeter,
            // but class was expected`。
            mv.visitMethodInsn(
                callOpcode(implicit), classInternalName(implicit.className), decl.name,
                descriptorOf(decl), implicit.interfaceOwner,
            )
            emitGenericReturnAdapt(ctx, expr, decl)
            return
        }

        // 阶段 25 K2：**继承来的 JDK 方法**（`class MyError(msg : string) : Exception(msg)` 里
        // 直接写 `getMessage()`）。这条调用既不在 implicitCalls（那是 rsf 类链上的方法），
        // 也不在 callTargets（它不是顶层函数），只有 Checker 解析出来的 JvmMethod 记录。
        // 接收者是 this（继承来的 JDK 方法打在同一个对象上——我们真的 extends 了 JDK 类，
        // 所以在 JVM 上这就是一条普通的 invokevirtual）。
        val inheritedJvm = model.classModel.implicitJvmCallOf(expr)
        if (inheritedJvm != null) {
            val m = inheritedJvm.method
            if (!m.isStatic) {
                emitThis(ctx, "'${expr.name}(...)'（隐式 this 调用继承来的 JDK 方法）")
            }
            for ((i, arg) in expr.arguments.withIndex()) {
                emitExpr(ctx, arg)
                emitWiden(mv, model.typeOf(arg), m.paramTypes[i])
            }
            // owner 是**声明**这个方法的 JDK 类（Checker 记的），不是当前类：
            // 解析从接收者的真实类开始，所以 this 是 MyError 也能落到 Throwable.getMessage。
            // 返回值不做泛型收敛——Checker 记的返回类型就是这个 JvmMethod.returnType
            // （解释器那条路也一样，见 Interpreter.callInheritedJvmMethod）。
            mv.visitMethodInsn(
                when {
                    m.isStatic -> INVOKESTATIC
                    inheritedJvm.interfaceOwner -> INVOKEINTERFACE
                    else -> INVOKEVIRTUAL
                },
                m.owner, m.name, m.descriptor, inheritedJvm.interfaceOwner,
            )
            return
        }

        val target = model.callTargets[expr]
            ?: throw IllegalStateException("内部错误：未解析的函数调用 '${expr.name}'")

        // 入口函数额外带一个 String[] 形参，rsf 里调用它时补个 null
        if (isEntry(target)) mv.visitInsn(ACONST_NULL)

        // 实参按签名顺序压栈；宽度不够的先加宽
        val paramTypes = model.paramTypesOf(target)
        for ((i, arg) in expr.arguments.withIndex()) {
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), paramTypes[i])
        }

        // 顶层函数都住在**程序类**里，不是当前类：在用户类的方法/字段初值里调顶层函数时，
        // 用 ctx.owner（当前类）会生成 INVOKESTATIC P.note(...)，运行时 NoSuchMethodError
        mv.visitMethodInsn(INVOKESTATIC, programInternalName, target.name, descriptorOf(target), false)
        emitGenericReturnAdapt(ctx, expr, target)
    }

    /**
     * 阶段 31b：泛型函数的返回值在描述符里被擦成 `Object`（数组则是 `[LObject;`），
     * 调用点按**推导出来的绑定**还原成静态类型：引用类型 `CHECKCAST`，基本类型先
     * `CHECKCAST` 到包装类再拆箱。非泛型调用点在这里原样返回，一条指令都不多发。
     */
    private fun emitGenericReturnAdapt(ctx: ExprCtx, expr: CallExpr, target: FuncDecl) {
        val site = model.genericCalls[expr] ?: return
        val declared = model.returnTypeOf(target)
        val want = site.returnType
        if (declared == want || want.isVoid || want.isNullLiteral) return
        val mv = ctx.mv
        if (want.isPrimitive) {
            val wrapper = want.base.wrapper ?: return
            mv.visitTypeInsn(CHECKCAST, wrapper)
            unboxValue(mv, want.base)
        } else {
            mv.visitTypeInsn(CHECKCAST, internalNameOf(want))
        }
    }

    /**
     * 内置函数调用（阶段 16）。
     *
     * 实参先按签名加宽（`toByte(b)` 里 byte 参数不用做任何事——它在栈上就是 int），
     * 再发实现指令。每一行都对应解释器里 `Interpreter.callBuiltin` 的同一行。
     *
     * `charToInt` 是**空实现**：char 在 JVM 栈上就是它的码点，取码点不需要指令。
     * 这一点和解释器不同（那边 Char 是个对象，要 `.code`），但可观察的结果一样。
     */
    private fun emitBuiltinCall(ctx: ExprCtx, expr: CallExpr, builtin: Builtin) {
        val mv = ctx.mv
        for ((i, arg) in expr.arguments.withIndex()) {
            emitExpr(ctx, arg)
            emitWiden(mv, model.typeOf(arg), builtin.params[i])
        }
        when (builtin.name) {
            // 截断 / 转换：对应 Java 的 (byte) 与 (char)
            "toByte" -> mv.visitInsn(I2B)
            "toChar" -> mv.visitInsn(I2C)
            "charToInt" -> {} // 见上面的说明：什么都不用发

            "intToString" -> mv.visitMethodInsn(
                INVOKESTATIC, "java/lang/String", "valueOf",
                "(I)Ljava/lang/String;", false,
            )

            // 失败的语义要自己给：辅助方法体里把 NumberFormatException 换成
            // 带 rsf 消息的 RuntimeException（和解释器的 RsfFailure 一字不差）
            "parseInt" -> mv.visitMethodInsn(
                INVOKESTATIC, programInternalName,
                Builtins.PARSE_INT_HELPER, Builtins.PARSE_INT_HELPER_DESC, false,
            )

            // 分类与大小写：直接调 java.lang.Character，和解释器调的是同一个方法
            "isDigit" -> characterCall(mv, "isDigit", "(C)Z")
            "isLetter" -> characterCall(mv, "isLetter", "(C)Z")
            "isWhitespace" -> characterCall(mv, "isWhitespace", "(C)Z")
            "toUpper" -> characterCall(mv, "toUpperCase", "(C)C")
            "toLower" -> characterCall(mv, "toLowerCase", "(C)C")

            // 四个文件函数（阶段 16b）：调程序类上的辅助方法。方法体里发的是
            // 「Paths.get + Files.readXxx / write」那一串，失败换成 rsf 的消息
            // （和解释器一字不差）；签名与名字都登记在 Builtin.helperName 上。
            "readText", "writeText", "readBytes", "writeBytes" -> mv.visitMethodInsn(
                INVOKESTATIC, programInternalName,
                builtin.helperName
                    ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法名"),
                builtin.helperDesc
                    ?: throw IllegalStateException("内部错误：'${builtin.name}' 没有辅助方法描述符"),
                false,
            )

            else -> throw IllegalStateException("内部错误：没实现的内置函数 '${builtin.name}'")
        }
    }

    private fun characterCall(mv: MethodVisitor, name: String, descriptor: String) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", name, descriptor, false)
    }

    private fun emitBinary(ctx: ExprCtx, expr: BinaryExpr) {
        val mv = ctx.mv
        val op = expr.op
        val leftType = model.typeOf(expr.left)
        val rightType = model.typeOf(expr.right)
        val resultType = model.typeOf(expr)

        // `&&` / `||`（阶段 19c）：**短路**，用标签实现。
        // 关键是右边**根本不在直路上**——它被夹在一条条件跳转和一条 GOTO 之间，
        // 而不是「两边都算完再合并」。这样 `false && g()` 里的 g 一次都不会被调用，
        // 和解释器完全一致（那边是 if (!left) return false）。
        //
        // 非空 bool 在栈上就是 int（0/1），所以条件跳转用 IFEQ / IFNE 就够：
        //   a && b:  算 a; IFEQ 假; 算 b; GOTO 结束; 假: ICONST_0; 结束:
        //   a || b:  算 a; IFNE 真; 算 b; GOTO 结束; 真: ICONST_1; 结束:
        // 注意 `a && b` 的真分支**直接就是 b 的值**（不必再归一化）——
        // b 的类型已经被 Checker 钉死成 bool，栈上必然是 0 或 1。
        if (op == TokenKind.ANDAND || op == TokenKind.OROR) {
            val branchLabel = Label()
            val endLabel = Label()
            emitExpr(ctx, expr.left)
            mv.visitJumpInsn(if (op == TokenKind.ANDAND) IFEQ else IFNE, branchLabel)
            emitExpr(ctx, expr.right)
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(branchLabel)
            mv.visitInsn(if (op == TokenKind.ANDAND) ICONST_0 else ICONST_1)
            mv.visitLabel(endLabel)
            return
        }

        if (op == TokenKind.PLUS && resultType == RsfType.STRING) {
            emitExpr(ctx, expr.left)
            emitToString(mv, leftType)
            emitExpr(ctx, expr.right)
            emitToString(mv, rightType)
            emitConcat(mv)
            return
        }

        if (op in COMPARISONS) {
            emitComparison(ctx, expr, leftType, rightType)
            return
        }

        emitExpr(ctx, expr.left)
        emitWiden(mv, leftType, resultType)
        emitExpr(ctx, expr.right)
        emitWiden(mv, rightType, resultType)

        val insn = when (resultType) {
            RsfType.INT -> when (op) {
                TokenKind.PLUS -> IADD
                TokenKind.MINUS -> ISUB
                TokenKind.STAR -> IMUL
                TokenKind.SLASH -> IDIV
                TokenKind.PERCENT -> IREM
                else -> throw IllegalStateException("不是算术运算符 $op")
            }
            RsfType.LONG -> when (op) {
                TokenKind.PLUS -> LADD
                TokenKind.MINUS -> LSUB
                TokenKind.STAR -> LMUL
                TokenKind.SLASH -> LDIV
                TokenKind.PERCENT -> LREM
                else -> throw IllegalStateException("不是算术运算符 $op")
            }
            RsfType.FLOAT -> when (op) {
                TokenKind.PLUS -> FADD
                TokenKind.MINUS -> FSUB
                TokenKind.STAR -> FMUL
                TokenKind.SLASH -> FDIV
                TokenKind.PERCENT -> FREM
                else -> throw IllegalStateException("不是算术运算符 $op")
            }
            RsfType.DOUBLE -> when (op) {
                TokenKind.PLUS -> DADD
                TokenKind.MINUS -> DSUB
                TokenKind.STAR -> DMUL
                TokenKind.SLASH -> DDIV
                TokenKind.PERCENT -> DREM
                else -> throw IllegalStateException("不是算术运算符 $op")
            }
            else -> throw IllegalStateException("算术结果类型不对: $resultType")
        }
        mv.visitInsn(insn)
    }

    /** 比较运算：产生 JVM 的 int（0/1），rsf 里当作 bool。 */
    private fun emitComparison(ctx: ExprCtx, expr: BinaryExpr, leftType: RsfType, rightType: RsfType) {
        val mv = ctx.mv
        val op = expr.op
        val trueLabel = Label()
        val endLabel = Label()

        val numericPair = leftType.isArithmetic && rightType.isArithmetic
        val boolPair = leftType.base == RsfBase.BOOL && rightType.base == RsfBase.BOOL &&
            !leftType.nullable && !rightType.nullable

        if (!numericPair) {
            if (boolPair) {
                // 非空 bool 在栈上就是 int
                emitExpr(ctx, expr.left)
                emitExpr(ctx, expr.right)
                mv.visitJumpInsn(if (op == TokenKind.EQ) IF_ICMPEQ else IF_ICMPNE, trueLabel)
            } else {
                // 引用语义：字符串按内容、可空装箱值按 equals、null 按引用。
                // 可空基本类型在栈上已经是包装类，所以这里两边都是引用。
                emitExpr(ctx, expr.left)
                emitExpr(ctx, expr.right)
                mv.visitMethodInsn(
                    INVOKESTATIC, "java/util/Objects", "equals",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false,
                )
                mv.visitJumpInsn(if (op == TokenKind.EQ) IFNE else IFEQ, trueLabel)
            }
        } else {
            val common = RsfType.promote(leftType, rightType)
                ?: throw IllegalStateException("比较运算的类型不对: $leftType / $rightType")
            emitExpr(ctx, expr.left)
            emitWiden(mv, leftType, common)
            emitExpr(ctx, expr.right)
            emitWiden(mv, rightType, common)

            when (common.base) {
                // byte / char 也走 int 比较：它们在栈上就是 int（`b1 == b2` 时
                // promote 会把共同类型原样给回来，所以这两支必须列上）
                RsfBase.INT, RsfBase.BOOL, RsfBase.BYTE, RsfBase.CHAR -> mv.visitJumpInsn(
                    when (op) {
                        TokenKind.EQ -> IF_ICMPEQ
                        TokenKind.NE -> IF_ICMPNE
                        TokenKind.LT -> IF_ICMPLT
                        TokenKind.GT -> IF_ICMPGT
                        TokenKind.LE -> IF_ICMPLE
                        TokenKind.GE -> IF_ICMPGE
                        else -> throw IllegalStateException("不是比较运算符 $op")
                    },
                    trueLabel,
                )

                RsfBase.LONG -> {
                    mv.visitInsn(LCMP)
                    mv.visitJumpInsn(cmpJump(op), trueLabel)
                }

                RsfBase.FLOAT -> {
                    // NaN 语义：< 和 <= 用 cmpg（NaN → 1 → 判假），> 和 >= 用 cmpl
                    mv.visitInsn(if (op == TokenKind.LT || op == TokenKind.LE) FCMPG else FCMPL)
                    mv.visitJumpInsn(cmpJump(op), trueLabel)
                }

                RsfBase.DOUBLE -> {
                    mv.visitInsn(if (op == TokenKind.LT || op == TokenKind.LE) DCMPG else DCMPL)
                    mv.visitJumpInsn(cmpJump(op), trueLabel)
                }

                else -> throw IllegalStateException("不能比较 $common")
            }
        }

        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(trueLabel)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(endLabel)
    }

    private fun cmpJump(op: TokenKind): Int = when (op) {
        TokenKind.EQ -> IFEQ
        TokenKind.NE -> IFNE
        TokenKind.LT -> IFLT
        TokenKind.GT -> IFGT
        TokenKind.LE -> IFLE
        TokenKind.GE -> IFGE
        else -> throw IllegalStateException("不是比较运算符 $op")
    }

    // ------------------------------------------------------------------ 指令工具

    /**
     * 拼接字符串时把栈顶的值转成 `String`。
     *
     * **对象一律走 `String.valueOf(Ljava/lang/Object;)`**——这个重载存在，它会调对象的
     * `toString()`。以前非 String 的外部类/用户类走的是 `String.valueOf(LXxx;)`，
     * 那个重载**根本不存在**（JDK 的 `String.valueOf` 只有 Object / char[] / 基本类型
     * 那几个），于是 `"p = " + p` 编译能过、运行期 `NoSuchMethodError`；
     * 而解释器那边能正常打出文本——两端不一致，破了「逐字节一致」的硬承诺。
     * 用户自定义类的 `toString()` 由 [emitUserToString] 生成，解释器按
     * [org.example.complie.sema.ObjectFormat] 渲染同一套格式，所以两边文本相同。
     */
    private fun emitToString(mv: MethodVisitor, type: RsfType) {
        // 引用类型里的 string（含 ?string）本来就是 String，直接拼；null 直接拼成 "null"
        if (type.base == RsfBase.STRING || type.isNullLiteral) return
        // 不是基本类型的一律按 Object 走：数组、外部类、用户类、类型参数占位（擦除成 Object），
        // 以及**可空的基本类型**（栈上已经是包装类，null 会拼成 "null"）。
        // `String.valueOf(Ljava/lang/Object;)` 对它们都存在，且都会调到各自的 toString()。
        if (!type.isPrimitive) {
            mv.visitMethodInsn(
                INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false,
            )
            return
        }
        // byte 没有 valueOf(byte) 这个重载（只有 valueOf(int)），而它在栈上本来就是
        // int，所以按 int 转成字符串——`"b=" + b` 得到 "b=5"，跟解释器的渲染一致。
        // char 有 valueOf(char)，直接用它，拼出来是字符本身（"c=x"）。
        val desc = if (type.base == RsfBase.BYTE) "(I)Ljava/lang/String;" else "(${type.descriptor})Ljava/lang/String;"
        mv.visitMethodInsn(
            INVOKESTATIC, "java/lang/String", "valueOf",
            desc, false,
        )
    }

    private fun emitConcat(mv: MethodVisitor) {
        mv.visitInvokeDynamicInsn(
            "makeConcatWithConstants",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            Handle(
                H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/String;" +
                    "[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            ),
            "\u0001\u0001",
        )
    }

    private fun emitWiden(mv: MethodVisitor, from: RsfType, to: RsfType) {
        if (from == to) return
        if (from.isNullLiteral) return // null 字面量本身就是引用

        // 阶段 31b：泛型形参 `T` / `?T` 擦除后都是 Object —— 引用实参在栈上本来
        // 就是它（不需要 CHECKCAST：泛型代码里 T 是不透明占位，能传进来的都合法），
        // 基本类型实参则装箱。
        if (to.base == RsfBase.TYPE_PARAM) {
            if (from.isPrimitive) boxValue(mv, from.base)
            return
        }

        // 基本类型 -> 引用类型（对象参数、可空同类型）：装箱
        if (!to.isPrimitive && to.base != RsfBase.NULL && from.isPrimitive) {
            // 目标是「可空的基本类型」（`?byte` / `?char` / `?long`…）而且基础类型不一样时，
            // 必须**先按目标基础类型转换再装箱**：`var b : ?byte = 5` 要装成 Byte、
            // `var n : ?long = 1` 要装成 Long。少了这一步会装成 Integer，
            // 槽位里躺着 Integer、后面拆箱时发 Byte.byteValue() / Long.longValue()，
            // 类加载直接 VerifyError。
            if (to.nullable && to.base.isPrimitive && from.base != to.base) {
                mv.visitInsn(widenInsn(from.base, to.base))
                boxValue(mv, to.base)
                return
            }
            boxValue(mv, from.base)
            return
        }

        if (to.nullable && to.base.isPrimitive) {
            // 非空基本类型 -> 可空同一类型：先加宽到目标基础类型，再装箱
            if (from.nullable) throw IllegalStateException("可空的 $from 不能当 $to 用")
            if (from.base != to.base) mv.visitInsn(widenInsn(from.base, to.base))
            boxValue(mv, to.base)
            return
        }

        if (from.nullable) throw IllegalStateException("可空的 $from 不能当非空的 $to 用")
        // 引用类型之间不需要转换指令：子类型关系（String → CharSequence、类 → Object）
        // 在检查阶段已经确认过了，栈上本来就都是对象引用
        if (!from.isPrimitive && !to.isPrimitive && to.base != RsfBase.NULL) return
        // 引用类型之间同基础类型就是同一个东西；数值之间按宽度加宽
        if (from.base == to.base) return
        mv.visitInsn(widenInsn(from.base, to.base))
    }

    /**
     * 两个基础类型之间的转换指令（阶段 16 起含 byte / char）。
     *
     * `byte` 和 `char` 在 JVM 栈上**就是 int**，所以「提升成 int」不需要任何指令，
     * 这里返回 [NOP] 而不是 null：有几个调用点（`?:` 的类型收敛、装箱前的加宽）
     * 直接写 `visitInsn(widenInsn(...))`，返回 null 会在那儿变成 NPE；
     * 发一条空指令永远合法，语义上等于什么都没做。
     */
    private fun widenInsn(from: RsfBase, to: RsfBase): Int = when {
        // 窄化 / char 与 int 之间的转换：**必须**显式发指令（Java 的 (byte) / (char)）。
        // 正常代码路径上 Checker 已经拦住了隐式的情况，这几条是内置函数
        // toByte / toChar 的实现，以及「0..127 字面量赋给 byte」那条特例落下来的地方。
        from == RsfBase.BYTE && to == RsfBase.INT -> NOP // 栈上本来就是 int
        from == RsfBase.CHAR && to == RsfBase.INT -> NOP
        from == RsfBase.INT && to == RsfBase.BYTE -> I2B
        from == RsfBase.INT && to == RsfBase.CHAR -> I2C
        from == RsfBase.BYTE && to == RsfBase.CHAR -> I2C
        from == RsfBase.CHAR && to == RsfBase.BYTE -> I2B
        from == RsfBase.BYTE && to == RsfBase.LONG -> I2L
        from == RsfBase.BYTE && to == RsfBase.FLOAT -> I2F
        from == RsfBase.BYTE && to == RsfBase.DOUBLE -> I2D
        from == RsfBase.CHAR && to == RsfBase.LONG -> I2L
        from == RsfBase.CHAR && to == RsfBase.FLOAT -> I2F
        from == RsfBase.CHAR && to == RsfBase.DOUBLE -> I2D
        from == RsfBase.INT && to == RsfBase.LONG -> I2L
        from == RsfBase.INT && to == RsfBase.FLOAT -> I2F
        from == RsfBase.INT && to == RsfBase.DOUBLE -> I2D
        from == RsfBase.LONG && to == RsfBase.FLOAT -> L2F
        from == RsfBase.LONG && to == RsfBase.DOUBLE -> L2D
        from == RsfBase.FLOAT && to == RsfBase.DOUBLE -> F2D
        else -> throw IllegalStateException("不支持的类型转换 $from -> $to")
    }

    /** 基本类型装箱：`Integer.valueOf(I)Ljava/lang/Integer;` 之类。 */
    private fun boxValue(mv: MethodVisitor, base: RsfBase) {
        val wrapper = base.wrapper ?: return
        mv.visitMethodInsn(
            INVOKESTATIC, wrapper, "valueOf",
            "(${base.descriptor})${base.boxedDescriptor}", false,
        )
    }

    /** 装箱类型拆箱。 */
    private fun unboxValue(mv: MethodVisitor, base: RsfBase) {
        val wrapper = base.wrapper ?: return
        mv.visitMethodInsn(
            INVOKEVIRTUAL, wrapper, base.unboxMethod!!, "()${base.descriptor}", false,
        )
    }

    /**
     * 取局部变量用的指令。可空的基本类型在 JVM 上已经装箱成引用，所以走 ALOAD/ASTORE。
     */
    private fun slotOpcode(type: RsfType, primitive: Int, wide: Int, floatOp: Int, doubleOp: Int, ref: Int): Int {
        if (type.base == RsfBase.VOID || type.base == RsfBase.NULL) {
            throw IllegalStateException("${type.rsfName} 不能做局部变量")
        }
        // 阶段 13：数组是引用（占一格、走 ALOAD/ASTORE）——这一条必须在按 base 分派
        // **之前**，否则 `long[]` 会因为 base 是 LONG 而被当成两格的 long
        if (type.isArray) return ref
        if (type.nullable && type.base.isPrimitive) return ref
        return when (type.base) {
            // byte / char 在栈上和局部变量槽里都是 int（JVM 的校验类型不区分它们）
            RsfBase.BOOL, RsfBase.BYTE, RsfBase.CHAR, RsfBase.INT -> primitive
            RsfBase.LONG -> wide
            RsfBase.FLOAT -> floatOp
            RsfBase.DOUBLE -> doubleOp
            RsfBase.STRING, RsfBase.EXTERNAL -> ref
            // 类型参数（`var value : T`）擦除到 Object，占一格引用
            RsfBase.TYPE_PARAM -> ref
            RsfBase.VOID, RsfBase.NULL -> throw IllegalStateException("unreachable")
        }
    }

    private fun loadSlot(mv: MethodVisitor, type: RsfType, slot: Int) {
        mv.visitVarInsn(slotOpcode(type, ILOAD, LLOAD, FLOAD, DLOAD, ALOAD), slot)
    }

    private fun storeSlot(mv: MethodVisitor, type: RsfType, slot: Int) {
        mv.visitVarInsn(slotOpcode(type, ISTORE, LSTORE, FSTORE, DSTORE, ASTORE), slot)
    }

    private fun returnInsn(type: RsfType): Int {
        if (type.isVoid) return RETURN
        // 数组是引用，用 ARETURN 返回（`fun make() -> int[]`）
        if (type.isArray) return ARETURN
        if (type.nullable && type.base.isPrimitive) return ARETURN
        return when (type.base) {
            RsfBase.VOID -> RETURN
            // byte / char 用 IRETURN 返回（JVM 把 byte/char/boolean 都当 int 返回）
            RsfBase.BOOL, RsfBase.BYTE, RsfBase.CHAR, RsfBase.INT -> IRETURN
            RsfBase.LONG -> LRETURN
            RsfBase.FLOAT -> FRETURN
            RsfBase.DOUBLE -> DRETURN
            RsfBase.STRING, RsfBase.NULL, RsfBase.EXTERNAL -> ARETURN
            RsfBase.TYPE_PARAM -> ARETURN
        }
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when {
            value in -1..5 -> mv.visitInsn(ICONST_0 + value)
            value in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, value)
            value in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    // ------------------------------------------------------------------ Opcodes 别名
    //
    // ASM 的常量都在 Opcodes 接口里；这里显式列出用到的，避免整文件 Opcodes. 前缀。

    private companion object {
        /**
         * 阶段 28a：`Lowering` 给**内部**标签留的前缀（`Lowering.kt:301-302` 的
         * `fresh()` 写的是 `"\u0000$kind.$n"`）——用户标签永远撞不上它。
         *
         * `@RsfLabels` 只收用户写的标签名，所以扫指令流时要按这个前缀滤一遍。
         */
        const val SYNTHETIC_LABEL_PREFIX = "\u0000"

        /**
         * 阶段 24：`as` 的判型辅助方法名与描述符。
         *
         * `(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;`
         * 三个参数分别是「被转的值」「目标类常量」「目标类的 JVM 名（印进错误消息）」。
         */
        const val CAST_HELPER = "__rsf_cast"
        const val CAST_HELPER_DESC =
            "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;"

        const val ACC_PUBLIC = Opcodes.ACC_PUBLIC
        const val ACC_PRIVATE = Opcodes.ACC_PRIVATE
        const val ACC_PROTECTED = Opcodes.ACC_PROTECTED
        const val ACC_STATIC = Opcodes.ACC_STATIC
        const val ACC_FINAL = Opcodes.ACC_FINAL
        const val ACC_SUPER = Opcodes.ACC_SUPER
        // 阶段 20：接口的头与抽象方法的头（`ACC_INTERFACE | ACC_ABSTRACT`）。
        const val ACC_INTERFACE = Opcodes.ACC_INTERFACE
        const val ACC_ABSTRACT = Opcodes.ACC_ABSTRACT
        // 阶段 20 M4-c：桥方法（泛型擦除后实现签名比接口窄时合成的那一条）
        const val ACC_BRIDGE = Opcodes.ACC_BRIDGE
        const val ACC_SYNTHETIC = Opcodes.ACC_SYNTHETIC
        // 阶段 21 M3：注解接口的头（`ACC_ANNOTATION|ACC_INTERFACE|ACC_ABSTRACT` = 0x2601）
        // 与方案 B 访问器上的 `ACC_SYNCHRONIZED`（懒构造必须串行化）
        const val ACC_ANNOTATION = Opcodes.ACC_ANNOTATION
        const val ACC_SYNCHRONIZED = Opcodes.ACC_SYNCHRONIZED

        // Java 17：既支持 StringConcatFactory 的 invokedynamic（Java 9+），也不至于太新
        const val V17 = Opcodes.V17

        const val ACONST_NULL = Opcodes.ACONST_NULL
        const val CHECKCAST = Opcodes.CHECKCAST
        // 阶段 24：`is` 用的类型判断
        const val INSTANCEOF = Opcodes.INSTANCEOF
        const val ICONST_0 = Opcodes.ICONST_0
        const val ICONST_1 = Opcodes.ICONST_1
        const val BIPUSH = Opcodes.BIPUSH
        const val SIPUSH = Opcodes.SIPUSH

        const val ILOAD = Opcodes.ILOAD
        const val LLOAD = Opcodes.LLOAD
        const val FLOAD = Opcodes.FLOAD
        const val DLOAD = Opcodes.DLOAD
        const val ALOAD = Opcodes.ALOAD
        const val ISTORE = Opcodes.ISTORE
        const val LSTORE = Opcodes.LSTORE
        const val FSTORE = Opcodes.FSTORE
        const val DSTORE = Opcodes.DSTORE
        const val ASTORE = Opcodes.ASTORE

        const val POP = Opcodes.POP
        const val POP2 = Opcodes.POP2
        const val DUP = Opcodes.DUP

        const val IADD = Opcodes.IADD
        const val ISUB = Opcodes.ISUB
        const val IMUL = Opcodes.IMUL
        const val IDIV = Opcodes.IDIV
        const val IREM = Opcodes.IREM
        const val INEG = Opcodes.INEG
        const val LADD = Opcodes.LADD
        const val LSUB = Opcodes.LSUB
        const val LMUL = Opcodes.LMUL
        const val LDIV = Opcodes.LDIV
        const val LREM = Opcodes.LREM
        const val LNEG = Opcodes.LNEG
        const val FADD = Opcodes.FADD
        const val FSUB = Opcodes.FSUB
        const val FMUL = Opcodes.FMUL
        const val FDIV = Opcodes.FDIV
        const val FREM = Opcodes.FREM
        const val FNEG = Opcodes.FNEG
        const val DADD = Opcodes.DADD
        const val DSUB = Opcodes.DSUB
        const val DMUL = Opcodes.DMUL
        const val DDIV = Opcodes.DDIV
        const val DREM = Opcodes.DREM
        const val DNEG = Opcodes.DNEG
        const val IXOR = Opcodes.IXOR

        /**
         * 枚举类里那个**私有名字字段**的字段名（阶段 19c）。
         *
         * 为什么叫 `$name` 而不是 `name`：rsf 的标识符只允许「字母 / 数字 / 下划线」
         * （见 `Lexer.isIdentStart`），**永远写不出 `$`**，所以这个名字与任何
         * 枚举值名都不可能撞车——`enum E { name }` 这种写法在 rsf 里是合法的，
         * 若私有字段也叫 `name`，生成的类里就会有两个同名字段，类文件直接报废。
         * 用 `$` 前缀把「编译器内部字段」和「用户写的值名」彻底分开，
         * 比在 Checker 里加一条「值名不能叫 name」的限制干净。
         */
        const val ENUM_NAME_FIELD = "\$name"

        const val I2L = Opcodes.I2L
        const val I2F = Opcodes.I2F
        const val I2D = Opcodes.I2D
        const val L2F = Opcodes.L2F
        const val L2D = Opcodes.L2D
        const val F2D = Opcodes.F2D
        // 阶段 16：byte / char 的截断（Java 的 (byte) / (char)）
        const val I2B = Opcodes.I2B
        const val I2C = Opcodes.I2C
        const val NOP = Opcodes.NOP

        const val LCMP = Opcodes.LCMP
        const val FCMPL = Opcodes.FCMPL
        const val FCMPG = Opcodes.FCMPG
        const val DCMPL = Opcodes.DCMPL
        const val DCMPG = Opcodes.DCMPG

        const val IFEQ = Opcodes.IFEQ
        const val IFNE = Opcodes.IFNE
        const val IFNONNULL = Opcodes.IFNONNULL
        const val IFLT = Opcodes.IFLT
        const val IFGE = Opcodes.IFGE
        const val IFGT = Opcodes.IFGT
        const val IFLE = Opcodes.IFLE
        const val IF_ICMPEQ = Opcodes.IF_ICMPEQ
        const val IF_ICMPNE = Opcodes.IF_ICMPNE
        // 阶段 19c：枚举分派的引用比较（`switch` 逐常量比）
        const val IF_ACMPEQ = Opcodes.IF_ACMPEQ
        const val IF_ICMPLT = Opcodes.IF_ICMPLT
        const val IF_ICMPGE = Opcodes.IF_ICMPGE
        const val IF_ICMPGT = Opcodes.IF_ICMPGT
        const val IF_ICMPLE = Opcodes.IF_ICMPLE
        const val GOTO = Opcodes.GOTO

        const val IRETURN = Opcodes.IRETURN
        const val LRETURN = Opcodes.LRETURN
        const val FRETURN = Opcodes.FRETURN
        const val DRETURN = Opcodes.DRETURN
        const val ARETURN = Opcodes.ARETURN
        const val RETURN = Opcodes.RETURN

        const val GETSTATIC = Opcodes.GETSTATIC
        const val GETFIELD = Opcodes.GETFIELD
        const val PUTFIELD = Opcodes.PUTFIELD
        // 阶段 19c：枚举类的 <clinit> 用它把单例写进静态字段
        const val PUTSTATIC = Opcodes.PUTSTATIC
        const val INVOKEVIRTUAL = Opcodes.INVOKEVIRTUAL
        const val INVOKESPECIAL = Opcodes.INVOKESPECIAL
        const val INVOKESTATIC = Opcodes.INVOKESTATIC
        // 阶段 20 M0：owner 是接口的成员调用必须用它（否则运行期 IncompatibleClassChangeError）
        const val INVOKEINTERFACE = Opcodes.INVOKEINTERFACE
        const val NEW = Opcodes.NEW
        const val ATHROW = Opcodes.ATHROW

        // ---- 阶段 13：数组 ----
        const val ARRAYLENGTH = Opcodes.ARRAYLENGTH
        const val NEWARRAY = Opcodes.NEWARRAY
        const val ANEWARRAY = Opcodes.ANEWARRAY
        const val IALOAD = Opcodes.IALOAD
        const val LALOAD = Opcodes.LALOAD
        const val FALOAD = Opcodes.FALOAD
        const val DALOAD = Opcodes.DALOAD
        const val AALOAD = Opcodes.AALOAD
        const val BALOAD = Opcodes.BALOAD
        const val CALOAD = Opcodes.CALOAD
        const val IASTORE = Opcodes.IASTORE
        const val LASTORE = Opcodes.LASTORE
        const val FASTORE = Opcodes.FASTORE
        const val DASTORE = Opcodes.DASTORE
        const val AASTORE = Opcodes.AASTORE
        const val BASTORE = Opcodes.BASTORE
        const val CASTORE = Opcodes.CASTORE

        // NEWARRAY 的 atype（JVM 规范里写死的编号）
        const val T_BOOLEAN = Opcodes.T_BOOLEAN
        const val T_BYTE = Opcodes.T_BYTE
        const val T_CHAR = Opcodes.T_CHAR
        const val T_INT = Opcodes.T_INT
        const val T_LONG = Opcodes.T_LONG
        const val T_FLOAT = Opcodes.T_FLOAT
        const val T_DOUBLE = Opcodes.T_DOUBLE

        const val H_INVOKESTATIC = Opcodes.H_INVOKESTATIC

        val COMPARISONS = setOf(
            TokenKind.EQ, TokenKind.NE, TokenKind.LT, TokenKind.GT, TokenKind.LE, TokenKind.GE,
        )
    }
}
