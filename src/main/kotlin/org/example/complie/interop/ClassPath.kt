package org.example.complie.interop

import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** 一个 JVM 方法。owner 是**声明**它的类（不是接收者的静态类型），invokevirtual 要用它。 */
class JvmMethod(
    val name: String,
    val paramTypes: List<RsfType>,
    val returnType: RsfType,
    val isStatic: Boolean,
    val owner: String,
    val descriptor: String,
    val isVarArgs: Boolean,
    /**
     * 每个形参对应到**类的第几个形式类型参数**（0 基）；null = 这个形参不是类型变量。
     *
     * 来自方法的**泛型签名**（`put(TK;TV;)TV;` → `[0, 1]`）：`HashMap&<string, int>`
     * 的 `put` 在描述符里两个形参都擦除成了 `Object`，光看描述符分不清哪个对哪个，
     * 签名里的 `TK;` / `TV;` 才是唯一可靠的对齐信息。
     *
     * 空表 = 没有签名（非泛型方法）⇒ 调用方退回「恰好一个类型实参」那条老路。
     */
    val paramTypeVars: List<Int?> = emptyList(),
    /** 返回值对应到类的第几个形式类型参数；null = 不是类型变量（或没有签名）。 */
    val returnTypeVar: Int? = null,
    /**
     * 是不是抽象方法（接口里没写体的那些）。
     *
     * 阶段 20 才需要它：`class Robot : Runnable` 必须把 Runnable 的**抽象**方法
     * 都实现掉，而 `Iterator.remove`（抽象）与 `Iterable.forEach`（有默认实现）
     * 在描述符上完全一样，只有 ACC_ABSTRACT 能分开。
     */
    val isAbstract: Boolean = false,
) {
    /** `max(int, int) -> int` 这样的可读签名，用于报错。 */
    fun display(ownerSimple: String): String {
        val ps = paramTypes.joinToString(", ") { it.rsfName }
        return "$ownerSimple.$name($ps) -> ${returnType.rsfName}"
    }
}

/** 一个 JVM 字段。 */
class JvmField(
    val name: String,
    val type: RsfType,
    val isStatic: Boolean,
    val owner: String,
    val descriptor: String,
)

/** 一个已加载的 JVM 类（只取签名，跳过方法体）。 */
class JvmClass(
    val internalName: String,
    val superName: String?,
    val interfaces: List<String>,
    val isInterface: Boolean,
    val methods: List<JvmMethod>,
    val fields: List<JvmField>,
    /**
     * `<init>` 的签名（构造器）。
     *
     * 单独一张表而不是混进 [methods]：`new 类名(...)` 走的是构造器，
     * 成员访问（`对象.名字(...)`）永远碰不到 `<init>`，两者混在一起只会互相干扰。
     * 构造器**不继承**，所以解析时只看这个类自己。
     */
    val constructors: List<JvmMethod> = emptyList(),
    /** 抽象类不能 `new`（接口同理，接口的 ACC_ABSTRACT 也置着）。 */
    val isAbstract: Boolean = false,
    /**
     * 这个类是不是 `final`（阶段 25 K4，缺口 A）。
     *
     * `class SB : StringBuilder(...)` 在**解释器**里跑得通（rsf 的父类只是一条模型边、
     * 不做真的 JVM 继承），可字节码产物是**真的** `extends java/lang/StringBuilder`，
     * JVM 装载时直接抛
     * `IncompatibleClassChangeError: class SB cannot inherit from final class java.lang.StringBuilder`。
     * 两端因此分叉，必须在**编译期**就拒掉：判据只能从这里来（classpath 上的 `.class`
     * 才记着 ACC_FINAL）。
     */
    val isFinal: Boolean = false,
    /**
     * 这个类声明了几个类型参数（从泛型签名里数出来）。null = 说不准。
     *
     * 只在**元数检查**时用：`ArrayList&<int, int>` 要报「需要 1 个，实际给了 2 个」。
     * 类型实参本身不参与描述符——JVM 上就是擦除的（这一点见 [RsfType.typeArgs]）。
     */
    val typeParamCount: Int? = null,
    /**
     * 类的形式类型参数的**名字**，按声明顺序（`<K:...;V:...;>` → `["K", "V"]`）。
     *
     * 方法签名里的 `TK;` 要靠它才能翻成「第 0 个类型实参」。签名缺失或畸形时是空表：
     * 那时擦除互操作退回 `singleTypeArg` 那条老路（只有恰好一个类型实参才替换）。
     */
    val typeParams: List<String> = emptyList(),
    /**
     * 这个类自己是不是 `public`。
     *
     * 拿**包私有**的类当 `INVOKEVIRTUAL` 的 owner 会在运行期 `IllegalAccessError`
     * （典型例子：`StringBuilder.length()` 声明在包私有的 `java.lang.AbstractStringBuilder`
     * 上）；解释器那边 `Class.forName` + `setAccessible` 也进不去
     * （`InaccessibleObjectException`）。所以调用点要能看出「这个 owner 能不能用」，
     * 见 [MemberResolver]。
     */
    val isPublic: Boolean = true,
) {
    val simpleName: String get() = internalName.substringAfterLast('/')
    val dottedName: String get() = internalName.replace('/', '.')
}

/**
 * 从 classpath 上读 JVM 类的签名。
 *
 * 用 ASM 解析 `.class`（项目本来就依赖它），`SKIP_CODE` 跳过方法体。
 * 结果按内部名缓存——同一个类在一次编译里只会被读一次。
 *
 * 数组类型（`[I` / `[[B` / `[Ljava/lang/String;`）自阶段 16b 起由
 * [typeFromDescriptor] 正常解析成带 `arrayDepth` 的 rsf 类型。
 */
object ClassPath {

    private val cache = HashMap<String, JvmClass?>()

    /** 读一个类；找不到返回 null。传内部名（`java/lang/Math`）或点分名都可以。 */
    fun find(name: String): JvmClass? {
        val internal = name.replace('.', '/')
        return cache.getOrPut(internal) { read(internal) }
    }

    /** 沿继承链找成员时用：把 `this` 到根父类依次列出来。 */
    fun hierarchy(internalName: String): List<JvmClass> {
        val out = ArrayList<JvmClass>()
        var current: String? = internalName
        val guard = HashSet<String>()
        while (current != null && guard.add(current)) {
            val cls = find(current) ?: break
            out.add(cls)
            current = cls.superName
        }
        return out
    }

    /**
     * [sub] 是不是 [sup] 的子类型（含接口实现）。
     *
     * 沿父类链和接口链一起走——`String` 能当 `CharSequence` 用全靠这里。
     * `Object` 是所有类型的父类型。
     */
    fun isSubtype(sub: String, sup: String): Boolean {
        if (sub == sup) return true
        if (sup == "java/lang/Object") return true
        val visited = HashSet<String>()
        fun walk(name: String): Boolean {
            if (!visited.add(name)) return false
            val cls = find(name) ?: return false
            val parent = cls.superName
            if (parent != null) {
                if (parent == sup || walk(parent)) return true
            }
            for (iface in cls.interfaces) {
                if (iface == sup || walk(iface)) return true
            }
            return false
        }
        return walk(sub)
    }

    private fun read(internal: String): JvmClass? {
        val stream = loader().getResourceAsStream("$internal.class") ?: return null
        stream.use { input ->
            var superName: String? = null
            var interfaces: List<String> = emptyList()
            var isInterface = false
            var isAbstract = false
            var isFinal = false
            var isPublic = true
            var typeParamCount: Int? = null
            // 类的形式类型参数名（`HashMap` → ["K", "V"]）：ASM 保证 visit 先于 visitMethod，
            // 所以下面收方法签名时它已经就位
            var typeParams: List<String> = emptyList()
            val methods = ArrayList<JvmMethod>()
            val fields = ArrayList<JvmField>()
            val constructors = ArrayList<JvmMethod>()

            val reader = ClassReader(input)
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName0: String?,
                        interfaces0: Array<out String>?,
                    ) {
                        superName = superName0
                        interfaces = interfaces0?.toList() ?: emptyList()
                        isInterface = (access and Opcodes.ACC_INTERFACE) != 0
                        isAbstract = (access and Opcodes.ACC_ABSTRACT) != 0
                        isFinal = (access and Opcodes.ACC_FINAL) != 0
                        isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                        typeParamCount = typeParamCountOf(signature)
                        typeParams = classTypeParams(signature) ?: emptyList()
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        // 静态初始化块不由 rsf 调用
                        if (name == "<clinit>") return null

                        // 构造器单独收：`new 类名(...)` 要用它。
                        // 泛型签名（signature）一律**不看**——擦除后的描述符才是
                        // 真正决定调用形态的东西，这也正是 rsf 泛型互操作的落点。
                        if (name == "<init>") {
                            val ctor = jvmMethod(name, descriptor, internal, isStatic = false, signature, typeParams)
                                ?: return null
                            if (!isHidden(access)) constructors.add(ctor)
                            return null
                        }

                        // 只收外部能调的成员：private 调不到，synthetic/bridge 是编译器生成的
                        if (isHidden(access)) return null

                        val m = jvmMethod(
                            name, descriptor, internal, (access and Opcodes.ACC_STATIC) != 0,
                            signature, typeParams,
                        )
                        if (m != null) {
                            methods.add(
                                JvmMethod(
                                    name = m.name,
                                    paramTypes = m.paramTypes,
                                    returnType = m.returnType,
                                    isStatic = m.isStatic,
                                    owner = m.owner,
                                    descriptor = m.descriptor,
                                    isVarArgs = (access and Opcodes.ACC_VARARGS) != 0,
                                    paramTypeVars = m.paramTypeVars,
                                    returnTypeVar = m.returnTypeVar,
                                    isAbstract = (access and Opcodes.ACC_ABSTRACT) != 0,
                                ),
                            )
                        }
                        return null
                    }

                    override fun visitField(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        value: Any?,
                    ): FieldVisitor? {
                        if (isHidden(access)) return null
                        val t = typeFromDescriptor(descriptor)
                        if (t != null) {
                            fields.add(
                                JvmField(
                                    name = name,
                                    type = t,
                                    isStatic = (access and Opcodes.ACC_STATIC) != 0,
                                    owner = internal,
                                    descriptor = descriptor,
                                ),
                            )
                        }
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )

            return JvmClass(
                internalName = internal,
                superName = superName,
                interfaces = interfaces,
                isInterface = isInterface,
                methods = methods,
                fields = fields,
                constructors = constructors,
                isAbstract = isAbstract,
                isFinal = isFinal,
                typeParamCount = typeParamCount,
                typeParams = typeParams,
                isPublic = isPublic,
            )
        }
    }

    /**
     * 类签名里形式类型参数的个数：`<K:...;V:...;>Ljava/lang/Object;` → 2。
     *
     * 只数**第一段** `<...>` 里顶层的 `;`——每个形式参数（`K:Ljava/lang/Object;`）
     * 正好以一个顶层 `;` 收尾，而参数上界里的嵌套泛型会把深度抬高，不会被误数。
     * 签名是 null（非泛型类）→ 0；数不出个所以然（畸形签名）→ null，调用方就不拦。
     *
     * 只用于**元数检查**：类型实参在 JVM 上是擦除的，描述符里根本没有它们。
     */
    fun typeParamCountOf(signature: String?): Int? {
        if (signature == null) return 0
        if (!signature.startsWith("<")) return 0
        var depth = 1
        var count = 0
        var i = 1
        while (i < signature.length) {
            when (signature[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return count
                }
                ';' -> if (depth == 1) count++
            }
            i++
        }
        return null
    }

    /**
     * 类签名里形式类型参数的**名字**，按声明顺序：
     * `<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/util/AbstractMap<TK;TV;>;` → `["K", "V"]`。
     *
     * 只有开头那一段 `<...>` 是形式参数表；每个参数是 `名字:上界`（或 `名字::上界`），
     * 上界本身可能是泛型（`T::Ljava/lang/Comparable<TT;>;`），所以按尖括号深度
     * 只切**顶层**的 `;`。
     *
     * 返回 null = 签名畸形（说不准），调用方退回老路。签名是 null（非泛型类）→ 空表。
     */
    fun classTypeParams(signature: String?): List<String>? {
        if (signature == null) return emptyList()
        if (!signature.startsWith("<")) return emptyList()
        // 1. 配平尖括号，切出形式参数表 body
        var depth = 0
        var end = -1
        var i = 0
        while (i < signature.length) {
            val ch = signature[i]
            if (ch == '<') {
                depth++
            } else if (ch == '>') {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
            i++
        }
        if (end < 0) return null
        val body = signature.substring(1, end)
        // 2. 按顶层 ';' 切成条目，条目里 ':' 之前就是名字
        val out = ArrayList<String>()
        var d = 0
        var start = 0
        for (j in body.indices) {
            when (body[j]) {
                '<' -> d++
                '>' -> d--
                ';' -> if (d == 0) {
                    val entry = body.substring(start, j)
                    val colon = entry.indexOf(':')
                    if (colon > 0) out.add(entry.substring(0, colon))
                    start = j + 1
                }
            }
        }
        // 最后一条没以 ';' 收尾 = 畸形
        if (start != body.length) return null
        return out
    }

    /** 方法签名里的类型变量映射：[params] 每个形参一个（null = 不是类类型变量），[ret] 同上。 */
    class TypeVarMap(val params: List<Int?>, val ret: Int?)

    /**
     * 方法签名 → 「第 i 个形参 / 返回值是类的第几个形式类型参数」。
     *
     * 例：`HashMap.put` 的签名 `(TK;TV;)TV;` + 类参数 `["K","V"]` → `params=[0,1], ret=1`。
     * 形如 `Ljava/util/Collection<TK;>;` 这种**不是**裸类型变量的位置一律给 null
     * （`keySet()` 返回的是 `Set<K>`，不是 K 本身）。
     *
     * 方法自己的形式类型参数（`<X:...>(TX;)TX;`）先跳过：那些名字**遮蔽**类的同名参数，
     * 拿它们去对类的类型实参是错的。返回 null = 没有签名或畸形。
     */
    fun methodTypeVars(signature: String?, classParams: List<String>): TypeVarMap? {
        if (signature == null || classParams.isEmpty()) return null
        var i = 0
        // 跳过方法自己的形式类型参数段
        if (i < signature.length && signature[i] == '<') {
            var depth = 0
            var closed = false
            while (i < signature.length) {
                if (signature[i] == '<') {
                    depth++
                } else if (signature[i] == '>') {
                    depth--
                    if (depth == 0) {
                        i++
                        closed = true
                        break
                    }
                }
                i++
            }
            if (!closed) return null
        }
        if (i >= signature.length || signature[i] != '(') return null
        i++
        val params = ArrayList<Int?>()
        while (i < signature.length && signature[i] != ')') {
            val read = readSignatureType(signature, i) ?: return null
            params.add(typeVarIndexOf(read.first, classParams))
            i = read.second
        }
        if (i >= signature.length) return null
        i++ // 跳过 ')'
        val ret = readSignatureType(signature, i) ?: return null
        return TypeVarMap(params, typeVarIndexOf(ret.first, classParams))
    }

    /**
     * 从 [start] 读**一个**签名里的类型，返回（原文, 下一个位置）。
     *
     * 签名语法只比描述符多 `T名字;` 与 `L...<...>;` 两种，`;` 的配对要按尖括号深度走
     * （`Ljava/util/Map<TK;TV;>;` 里前两个 `;` 都在尖括号里面）。
     */
    private fun readSignatureType(sig: String, start: Int): Pair<String, Int>? {
        var i = start
        while (i < sig.length && sig[i] == '[') i++
        if (i >= sig.length) return null
        if (sig[i] == 'T') {
            val end = sig.indexOf(';', i)
            if (end < 0) return null
            return (sig.substring(start, end + 1) to end + 1)
        }
        if (sig[i] == 'L') {
            var depth = 0
            var j = i
            while (j < sig.length) {
                when (sig[j]) {
                    '<' -> depth++
                    '>' -> depth--
                    ';' -> if (depth == 0) return (sig.substring(start, j + 1) to j + 1)
                }
                j++
            }
            return null
        }
        // 基本类型 / V：一个字符
        return (sig.substring(start, i + 1) to i + 1)
    }

    /** 签名里的一个类型是**裸的类类型变量**时，给出它是第几个类形式参数；否则 null。 */
    private fun typeVarIndexOf(text: String, classParams: List<String>): Int? {
        if (text.length < 3 || text[0] != 'T' || text[text.length - 1] != ';') return null
        val name = text.substring(1, text.length - 1)
        val index = classParams.indexOf(name)
        return if (index >= 0) index else null
    }

    /**
     * 描述符 → [JvmMethod]。
     *
     * 只要有任何一个参数/返回值解析不出来就返回 null，让调用方把这整个方法当成
     * 「不存在」——签名不完整的方法记下来也没法正确调用。
     *
     * [signature] 是泛型签名（可能为 null）。它**不参与**描述符，只用来记下
     * 「第 i 个形参 / 返回值对应类的第几个形式类型参数」——多个类型实参的擦除互操作
     * 全靠这条信息（见 [JvmMethod.paramTypeVars]）。
     */
    private fun jvmMethod(
        name: String,
        descriptor: String,
        owner: String,
        isStatic: Boolean,
        signature: String? = null,
        classTypeParams: List<String> = emptyList(),
    ): JvmMethod? {
        val paramTypes = ArrayList<RsfType>()
        for (desc in splitParams(descriptor)) {
            val t = typeFromDescriptor(desc) ?: return null
            paramTypes.add(t)
        }
        val ret = typeFromDescriptor(returnDescriptor(descriptor)) ?: return null
        val vars = methodTypeVars(signature, classTypeParams)
        return JvmMethod(
            name = name,
            paramTypes = paramTypes,
            returnType = ret,
            isStatic = isStatic,
            owner = owner,
            descriptor = descriptor,
            isVarArgs = false,
            // 签名与描述符的实参个数必须对得上，对不上就整条丢掉（宁可不替换，也不要错位替换）
            paramTypeVars = if (vars != null && vars.params.size == paramTypes.size) {
                vars.params
            } else {
                emptyList()
            },
            returnTypeVar = if (vars != null && vars.params.size == paramTypes.size) vars.ret else null,
        )
    }

    private fun loader(): ClassLoader = ClassLoader.getSystemClassLoader()

    /**
     * private / synthetic / bridge 的成员不收。
     *
     * private 从外部根本调不到；synthetic 和 bridge 是编译器生成的东西
     * （`$assertionsDisabled`、协变返回值的桥方法等），列在补全里只会干扰。
     */
    private fun isHidden(access: Int): Boolean =
        (access and Opcodes.ACC_PRIVATE) != 0 ||
            (access and Opcodes.ACC_SYNTHETIC) != 0 ||
            (access and Opcodes.ACC_BRIDGE) != 0

    // ------------------------------------------------------------------ 描述符

    /**
     * JVM 描述符 → rsf 类型。
     *
     * `Ljava/lang/String;` 会映射成内建的 string（它有专门的拼接/输出处理），
     * 其余对象类型变成外部类类型。
     *
     * **阶段 16b 起数组描述符也认了**：开头连续的 `[` 个数就是 [RsfType.arrayDepth]，
     * 剩下的部分照常当元素类型解析，再把维度套上去。`[B` → `byte[]`、`[[B` → `byte[][]`、
     * `[Ljava/lang/String;` → `string[]`。
     *
     * 这一步是「rsf 接得住 `ClassWriter.toByteArray()` 的 `byte[]`」的前提：
     * 在此之前凡是签名里带数组的方法都会被**整个跳过**——[jvmMethod] 只要有一个
     * 参数解析不出来就返回 null，于是 `String.getBytes() -> [B`、
     * `Files.readAllBytes(Path) -> [B` 这些根本查不到。
     *
     * 阶段 16 起 `B` / `C` 有对应的基础类型了，所以 `String.charAt(int) -> char`
     * （描述符 `(I)C`）能原样解析出来——这正是「标准库切片」能用的前提。
     * 注意 `Ljava/lang/Byte;` / `Ljava/lang/Character;` 仍然当**外部类**看，
     * 跟 `Integer` / `Long` 的既有处理一致（装箱形态的可空类型只在语言内部产生）。
     */
    fun typeFromDescriptor(desc: String): RsfType? {
        // 先数数组前缀：`[[B` → depth = 2，剩下的 `B` 才是元素类型
        var i = 0
        while (i < desc.length && desc[i] == '[') i++
        if (i == 0) return elementTypeFromDescriptor(desc)
        val element = elementTypeFromDescriptor(desc.substring(i)) ?: return null
        // `[V` 不是合法描述符；拒掉它，免得造出 `void[]` 这种没有 JVM 表示的类型
        if (element.isVoid) return null
        // 数组的 [RsfType.nullable] 约定与 Checker 一致：永远是 false
        return element.arrayOf(i)
    }

    /**
     * 单个**非数组**描述符 → rsf 类型；认不出来返回 null。
     *
     * 拆出来是因为数组要递归到元素上：`[[Ljava/lang/String;` 的两层 `[` 只是维度，
     * 真正决定 base / externalName 的永远是最后那一段。
     */
    private fun elementTypeFromDescriptor(desc: String): RsfType? = when {
        desc == "V" -> RsfType.VOID
        desc == "Z" -> RsfType.BOOL
        desc == "B" -> RsfType.BYTE
        desc == "C" -> RsfType.CHAR
        desc == "I" -> RsfType.INT
        desc == "J" -> RsfType.LONG
        desc == "F" -> RsfType.FLOAT
        desc == "D" -> RsfType.DOUBLE
        desc.length > 2 && desc.startsWith("L") && desc.endsWith(";") -> {
            when (val internal = desc.substring(1, desc.length - 1)) {
                "java/lang/String" -> RsfType.STRING
                "java/lang/Object" -> RsfType.OBJECT
                else -> RsfType.external(internal)
            }
        }
        else -> null
    }

    /** `(IJ)V` → `["I", "J"]`。 */
    fun splitParams(descriptor: String): List<String> {
        val start = descriptor.indexOf('(')
        val end = descriptor.indexOf(')')
        if (start < 0 || end < 0 || end < start) return emptyList()
        val body = descriptor.substring(start + 1, end)
        val out = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            var j = i
            // 数组前缀属于这个参数自己（`[B` / `[[B`），跳过它之后才找元素描述符的结尾；
            // 数组本身由 typeFromDescriptor 解析成带 arrayDepth 的类型（阶段 16b）
            while (body[j] == '[') j++
            if (body[j] == 'L') {
                j = body.indexOf(';', j) + 1
            } else {
                j++
            }
            out.add(body.substring(i, j))
            i = j
        }
        return out
    }

    fun returnDescriptor(descriptor: String): String {
        val end = descriptor.indexOf(')')
        return if (end < 0) "V" else descriptor.substring(end + 1)
    }
}
