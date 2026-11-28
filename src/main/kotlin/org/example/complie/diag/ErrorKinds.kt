package org.example.complie.diag

/**
 * 错误的**种类**（阶段 84）：一组固定的短标识。
 *
 * rsf 的错误通道是字符串（`fail` / `!T` / `catch (e : String)`），约定格式是
 * `kind: message`。种类放在这里而不是散在各处，是为了：
 *
 * - `fail io, "a.txt: 打不开"` 里写错种类能被**编译期**拦下（列出可用的）；
 * - 运行期错误与用户错误用同一套名字，`Exception.kindOf(e)` 的结果因此可预期；
 * - 以后把载荷改成 `(code, message)` 时，这张表直接变成枚举的取值。
 *
 * 名字故意短（`io` / `parse`），因为它们会出现在每条错误串的开头。
 */
object ErrorKinds {

    /** 读写出错（文件打不开、写不进）。 */
    const val IO = "io"

    /** 解析失败（数字格式不对、语法不对）。 */
    const val PARSE = "parse"

    /** 下标/范围越界。 */
    const val RANGE = "range"

    /** 状态不合法（断言失败、调用顺序不对）。 */
    const val STATE = "state"

    /** 还没实现。 */
    const val TODO = "todo"

    /** 理论上不可能到达。 */
    const val UNREACHABLE = "unreachable"

    /** 运行期自己抛的错（空引用、除零之类）。 */
    const val RUNTIME = "runtime"

    /** 用户自定义（不想归类时用它）。 */
    const val USER = "user"

    val ALL: List<String> = listOf(IO, PARSE, RANGE, STATE, TODO, UNREACHABLE, RUNTIME, USER)

    fun isKnown(name: String): Boolean = name in ALL

    /** `io: 消息` —— 生成错误串的唯一格式。 */
    fun format(kind: String, message: String): String = "$kind: $message"
}
