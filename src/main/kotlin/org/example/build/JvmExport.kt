package org.example.build

import org.example.complie.diag.RsfError
import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel

/**
 * `exportjvm` 的接口约束：**外部 JVM 调用方只认原生类型**。
 *
 * 为什么必须有这条：jar 里的方法是给 Java/Kotlin 直接 `Tool.twice(21)` 调的。
 * 一旦签名里出现 rsf 自己的东西（用户类、接口、枚举、指针、类型参数），
 * 调用方就得连带加载 rsf 的类型表示才能把值构造出来——那样「导出给 JVM 系语言」
 * 就只是一句口号。所以导出面限定在 Java 原生能表达的那几种：
 *
 * | rsf | JVM |
 * | --- | --- |
 * | Int | int |
 * | Long | long |
 * | Double / Float | double / float |
 * | Bool | boolean |
 * | String | java.lang.String |
 * | 以上任意 `[]` | 对应数组 |
 *
 * 返回 `void` 可以（没有返回值）。**不是**限制语言本身——普通函数随便写，
 * 只有列进 `module-impex.rsf` 的那些必须守这条。
 */
object JvmExport {

    /** 放行的原生类型名（`rsfName`）。 */
    private val NATIVE = setOf("Int", "Long", "Double", "Float", "Bool", "String", "Char", "Byte")

    /** 校验一份导出的模型；不合法就抛错，消息里指出是哪个函数、哪个位置。 */
    fun validate(model: SemanticModel, origin: String) {
        for (fn in model.functions) {
            if (fn.isExtern) continue
            val ret = model.returnTypeOf(fn)
            if (ret.base != RsfBase.VOID) checkType(ret, "'${fn.name}' 的返回类型", fn, origin)
            for ((i, t) in model.paramTypes[fn].orEmpty().withIndex()) {
                checkType(t, "'${fn.name}' 的第 ${i + 1} 个参数", fn, origin)
            }
        }
    }

    private fun checkType(type: RsfType, what: String, fn: org.example.complie.ast.FuncDecl, origin: String) {
        if (isNative(type)) return
        throw RsfError(
            "build",
            "$origin（${fn.name}）：${what}是 '${type.rsfName}'，JVM 调用方没法直接用。" +
                "导出面的类型只能是 ${NATIVE.joinToString(" / ")} 和它们的数组" +
                "（Java 那边的 int/long/double/float/boolean/String 与数组）。" +
                "要么换个签名，要么把这条从 module-impex.rsf 里拿掉——它仍然可以在模块内部使用",
            fn.at.line, fn.at.column, null,
        )
    }

    private fun isNative(type: RsfType): Boolean {
        if (type.rsfName == "void" || type.base == RsfBase.VOID) return true
        if (type.isPointer) return false
        if (type.isTypeParam) return false
        val element = if (type.isArray) type.elementType else type
        return element.rsfName in NATIVE && element.base != RsfBase.EXTERNAL
    }
}
