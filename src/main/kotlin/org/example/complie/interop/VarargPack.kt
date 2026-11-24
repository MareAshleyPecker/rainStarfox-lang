package org.example.complie.interop

import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType

/**
 * varargs 调用点的「打包计划」：前 [fixedCount] 个实参原样传，剩下的打包成一个
 * [elementType] 数组，塞进最后一个形参。
 *
 * ## 为什么要有这张表
 *
 * Java 的 `String.format(String, Object...)` 在 class 文件里的描述符是
 * `(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;`——**形参就是一个数组**，
 * `ACC_VARARGS` 只是给编译器看的提示。所以调用点 `String.format("%d", 1)` 在字节码里
 * 必须自己 `ANEWARRAY java/lang/Object` + 逐个装箱 + `AASTORE`。
 *
 * 判定规则（和 javac 完全一致）：
 * - 方法不是 varargs ⇒ 不打包；
 * - 实参个数 == 形参个数、且最后一个实参本来就能当那个数组用
 *   （`String.format("%d", arr)` 里的 `arr : String[]`）⇒ 不打包，直接把数组传进去；
 * - 其余情况（少了、多了、类型不对）⇒ 打包。
 *
 * **同一份判据必须被三处共用**：Checker 的类型校验、字节码后端、解释器的反射调用。
 * 任何一处自己抄一遍，`rsf run` 与 `java -cp out` 就可能选到不同的重载、或者
 * 一个打包一个不打包——那正是这个语言一直在防的「两端背离」。
 */
class VarargPack private constructor(
    /** 前几个实参原样传（= 形参个数 - 1）。 */
    val fixedCount: Int,
    /** 打包出来的数组的元素类型。 */
    val elementType: RsfType,
) {

    /** 尾部要打包几个实参。 */
    fun tailCount(argumentCount: Int): Int = argumentCount - fixedCount

    companion object {

        /**
         * 静态类型已知时（Checker 与字节码后端）：看最后一个实参的**类型**判断
         * 它是不是直接就能当那个数组用。
         */
        fun of(method: JvmMethod, argTypes: List<RsfType>): VarargPack? {
            if (!method.isVarArgs || method.paramTypes.isEmpty()) return null
            val fixed = method.paramTypes.size - 1
            if (argTypes.size < fixed) return null
            val want = method.paramTypes.last()
            if (!want.isArray) return null
            if (argTypes.size == method.paramTypes.size && arrayAssignable(want, argTypes.last())) return null
            return VarargPack(fixed, want.elementType)
        }

        /**
         * 只有运行期的值（解释器）：[directArray] 由调用方按**值的实际 Class** 判定
         * （「这个对象是不是 `Object[]` 的实例」），剩下的规则与 [of] 一致。
         */
        fun atRuntime(method: JvmMethod, argumentCount: Int, directArray: Boolean): VarargPack? {
            if (!method.isVarArgs || method.paramTypes.isEmpty()) return null
            val fixed = method.paramTypes.size - 1
            if (argumentCount < fixed) return null
            val want = method.paramTypes.last()
            if (!want.isArray) return null
            if (directArray) return null
            return VarargPack(fixed, want.elementType)
        }

        /**
         * `want` 这个数组类型能不能直接接住 `got`。
         *
         * 数组是**协变**的：`String[]` 能当 `Object[]` 用（JVM 允许，运行期才检查元素）。
         * 基本类型数组不协变，`Int[]` 与 `Object[]` 是两回事。
         *
         * 公开是因为 [MemberResolver] 的重载打分也要用同一条判据——两个调用点
         * `String.format("%s", arr)`（直接把数组传进去）和 `String.format("%s", s)`
         * （打包成一个数组）的分界就是它。
         */
        fun arrayAssignable(want: RsfType, got: RsfType): Boolean {
            if (want == got) return true
            if (!want.isArray || !got.isArray) return false
            if (want.arrayDepth != got.arrayDepth) return false
            val w = want.elementType
            val g = got.elementType
            if (w == g) return true
            if (w.isPrimitive || g.isPrimitive) return false
            val wName = internalNameOf(w) ?: return false
            val gName = internalNameOf(g) ?: return false
            return ClassPath.isSubtype(gName, wName)
        }

        private fun internalNameOf(type: RsfType): String? = when {
            type.isExternal -> type.externalName
            type.base == RsfBase.STRING -> "java/lang/String"
            type.nullable && type.base.isPrimitive -> type.base.wrapper
            else -> null
        }
    }
}
