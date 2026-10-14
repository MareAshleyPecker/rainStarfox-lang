package org.example.complie.sema

import org.example.complie.lexing.SourceLine

/**
 * 一个命名变量的绑定：要么来自函数参数，要么来自局部声明。
 *
 * 过了语义检查之后这两者就没差别了——都只需要「类型 + 能不能赋值 + 在局部变量表里占哪一格」。
 * 统一成一种东西之后，解释器和字节码后端就不必到处分情况判断「这是参数还是局部变量」。
 */
class Binding(
    val name: String,
    val type: RsfType,
    val mutable: Boolean,
    /** 参数在签名里的下标；局部变量为 -1。 */
    val paramIndex: Int,
    val at: SourceLine,
    /**
     * 是不是 `catch (e : string)` 的错误变量（阶段 14；老写法 `catch (e string)` 也收）。
     *
     * 它跟参数一样「进作用域就有值」，所以不参与确定性赋值分析；
     * 归错时也要给一句专门的话，而不是让它冒充 imutvar 局部变量。
     */
    val catchVar: Boolean = false,
) {
    val isParam: Boolean get() = paramIndex >= 0

    /** 局部变量槽位。由 [org.example.complie.ir.Lowering] 分配时写入。 */
    var slot: Int = -1

    override fun toString(): String =
        "Binding($name: ${type.rsfName}, ${if (mutable) "var" else "imutvar"}, slot=$slot)"
}
