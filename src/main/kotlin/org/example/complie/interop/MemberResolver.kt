package org.example.complie.interop

import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType

/** 解析出来的成员：要么是方法，要么是字段。 */
sealed interface ResolvedMember

/**
 * 一个解析出来的 JVM 方法。
 *
 * [interfaceOwner] = 这个方法的 owner 是一个**接口**（`Iterator`、`List`、`Comparator`…）。
 * JVM 的硬规矩：owner 是接口时必须发 `INVOKEINTERFACE`，发 `INVOKEVIRTUAL` 会在运行期
 * 抛 `IncompatibleClassChangeError: Found interface ..., but class was expected`。
 * 谁是不是接口由 Checker 在解析时定一次（`JvmClass.isInterface`），后端不再自己查。
 */
class ResolvedMethod(
    val method: JvmMethod,
    val interfaceOwner: Boolean = false,
) : ResolvedMember

class ResolvedField(val field: JvmField) : ResolvedMember

/**
 * 把 `接收者.名字` 解析到具体的 JVM 成员。
 *
 * 查找沿继承链走（`ArrayList.add` 声明在 ArrayList，`String.hashCode` 声明在 Object）。
 * 重载选择是「精确匹配优先、加宽其次」的简单打分：每个实参算一个代价，
 * 精确同一个类型 0 分，加宽或传 null 给引用类型 1 分，不兼容则该候选出局；
 * 总分最低者胜，并列取先声明的。
 *
 * 打分的具体规则**必须和解释器的反射调用保持一致**，否则同一份源码在
 * `rsf run` 和 `java -cp out main` 下可能选到不同的重载。
 */
class MemberResolver(
    /** 简单类名 → 内部名。来自 `import` 语句和 java.lang 的隐式导入。 */
    private val importedClasses: Map<String, String>,
) {

    // ------------------------------------------------------------------ 类

    /** 名字能不能当类用：显式导入的，或者 java.lang 里的。 */
    fun resolveClass(simpleName: String): JvmClass? {
        importedClasses[simpleName]?.let { internal ->
            ClassPath.find(internal)?.let { return it }
        }
        // 和 Java 一样隐式可用 java.lang.*
        return ClassPath.find("java/lang/$simpleName")
    }

    /** 某个静态类型对应哪个 JVM 类（用于找实例成员）。 */
    fun classOf(type: RsfType): String? = when {
        type.isExternal -> type.externalName
        type.base == RsfBase.STRING -> "java/lang/String"
        // 可空基本类型在 JVM 上就是包装类
        type.nullable && type.base.isPrimitive -> type.base.wrapper
        type.base == RsfBase.NULL -> null
        else -> null
    }

    // ------------------------------------------------------------------ 字段

    fun findField(className: String, name: String, wantStatic: Boolean): JvmField? {
        for (cls in ClassPath.hierarchy(className)) {
            val hit = cls.fields.firstOrNull { it.name == name && it.isStatic == wantStatic }
            if (hit != null) return hit
        }
        return null
    }

    /** 只按名字找（静态/实例都接受），用于给出更好的错误提示。 */
    fun findFieldAnyVisibility(className: String, name: String): JvmField? {
        for (cls in ClassPath.hierarchy(className)) {
            val hit = cls.fields.firstOrNull { it.name == name }
            if (hit != null) return hit
        }
        return null
    }

    // ------------------------------------------------------------------ 方法

    /** 按名字+元数+可赋值性挑最合适的重载；找不到返回 null。 */
    fun findMethod(
        className: String,
        name: String,
        argTypes: List<RsfType>,
        wantStatic: Boolean,
    ): JvmMethod? {
        var best: JvmMethod? = null
        var bestScore = Int.MAX_VALUE

        for (cls in ClassPath.hierarchy(className)) {
            for (m in cls.methods) {
                if (m.name != name) continue
                if (m.isStatic != wantStatic) continue
                if (m.paramTypes.size != argTypes.size) continue
                val score = score(m.paramTypes, argTypes) ?: continue
                if (score < bestScore) {
                    bestScore = score
                    best = m
                }
            }
        }
        return best?.let { accessibleOwner(it, className) }
    }

    /**
     * 换一个**访问得到**的 owner。
     *
     * 方法可能是从包私有的父类继承来的：`StringBuilder.length()` / `charAt` /
     * `substring` 都声明在 `java.lang.AbstractStringBuilder` 上，而那个类不是 `public`。
     * 拿它当 owner 发 `INVOKEVIRTUAL java/lang/AbstractStringBuilder.length()I`，
     * 运行期直接 `IllegalAccessError`；解释器那边 `Class.forName("java.lang.AbstractStringBuilder")`
     * + `setAccessible(true)` 会抛 `InaccessibleObjectException`——
     * 也就是**两端都跑不起来**（2026-10-11 实测）。
     *
     * javac 对这种情况的做法就是**用接收者的静态类型当 owner**（`javap` 里看到的是
     * `invokevirtual java/lang/StringBuilder.length:()I`），JVM 的方法解析本来就会
     * 沿父类链往上找，所以找到的还是同一个方法。静态调用同理：`className` 就是
     * 用户写出来的那个类名。
     */
    private fun accessibleOwner(m: JvmMethod, receiverClass: String): JvmMethod {
        val declaring = ClassPath.find(m.owner) ?: return m
        if (declaring.isPublic) return m
        return JvmMethod(
            name = m.name,
            paramTypes = m.paramTypes,
            returnType = m.returnType,
            isStatic = m.isStatic,
            owner = receiverClass,
            descriptor = m.descriptor,
            isVarArgs = m.isVarArgs,
            paramTypeVars = m.paramTypeVars,
            returnTypeVar = m.returnTypeVar,
        )
    }

    /** 只按名字找（忽略静态性和元数），用于错误提示。 */
    fun findMethodByName(className: String, name: String): JvmMethod? {
        for (cls in ClassPath.hierarchy(className)) {
            val hit = cls.methods.firstOrNull { it.name == name }
            if (hit != null) return hit
        }
        return null
    }

    // ------------------------------------------------------------------ 构造器

    /**
     * `new 类名(实参)` 的构造器解析。
     *
     * **只看这个类自己**：构造器不像普通方法那样继承（`class A extends B` 并不会
     * 多出 B 的构造器），走 [ClassPath.hierarchy] 只会挑到不该挑的 `<init>`。
     * 打分规则与 [findMethod] 完全一样，这样解释器的反射调用和字节码的
     * `INVOKESPECIAL <init>` 一定选到同一个。
     */
    fun findConstructor(cls: JvmClass, argTypes: List<RsfType>): JvmMethod? {
        var best: JvmMethod? = null
        var bestScore = Int.MAX_VALUE
        for (ctor in cls.constructors) {
            if (ctor.paramTypes.size != argTypes.size) continue
            val score = score(ctor.paramTypes, argTypes) ?: continue
            if (score < bestScore) {
                bestScore = score
                best = ctor
            }
        }
        return best
    }

    /** 构造器候选签名，用于「没有匹配的构造器」时的提示。 */
    fun constructorsOf(cls: JvmClass): List<String> =
        cls.constructors.map { "new ${cls.simpleName}(${it.paramTypes.joinToString(", ") { p -> p.rsfName }})" }

    /** 候选签名对实参的代价；null 表示不兼容。 */
    private fun score(params: List<RsfType>, args: List<RsfType>): Int? {
        var total = 0
        for (i in params.indices) {
            val p = params[i]
            val a = args[i]
            val cost = argCost(p, a) ?: return null
            total += cost
        }
        return total
    }

    private fun argCost(param: RsfType, arg: RsfType): Int? {
        if (param == arg) return 0
        if (arg.isNullLiteral) {
            // JVM 的引用类型天然收 null
            return if (!param.isPrimitive) 1 else null
        }
        if (param.accepts(arg)) return 1
        // 数值加宽：param 更宽就行（accepts 已经覆盖，这里只是显式化意图）
        if (param.isNumeric && arg.isNumeric && arg.base.rank <= param.base.rank) return 1
        // 引用类型之间按类层次判断子类型：String 能当 CharSequence 用就靠这条
        val argClass = classOf(arg)
        val paramClass = classOf(param)
        if (argClass != null && paramClass != null && ClassPath.isSubtype(argClass, paramClass)) {
            return 1
        }
        return null
    }
}
