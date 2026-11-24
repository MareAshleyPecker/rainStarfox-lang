package org.example.complie.interop

/**
 * `catch (e : String)` 里那个 `e` 该拿到的文案（阶段 70）。
 *
 * 两端的 `try` 都接得住三类东西：
 *
 * 1. rsf 自己的失败——`fail "..."`、没接住的 `!T`、越界、`parseInt` 失败……这些在
 *    字节码里被摊成 `throw new RuntimeException(消息)`（见
 *    `BytecodeCompiler.emitFail`），解释器里是 `RsfFailure`。文案**就是**那条消息，
 *    不带任何前缀——阶段 14 起就是这口径，改名前后的测试都按这个比。
 * 2. Java 方法自己抛的异常（`IOException`、`NumberFormatException`……）。文案是
 *    `完整类名: 消息`，例如 `java.io.IOException: 系统找不到指定的文件`。
 * 3. 没有消息的异常（`NullPointerException` 这类）。文案就是完整类名。
 *
 * 区分 1 和 2 靠的是「抛出来的到底是不是**裸的** `java.lang.RuntimeException`」：
 * rsf 自己只造这一种，而且从不给它设 cause。这不完美（Java 库自己也可能裸抛
 * `RuntimeException`，那种会被当成 rsf 消息、不带前缀），但换来的好处是 **rsf 的
 * 错误文案一字不改**——这条比前缀重要。
 *
 * 字节码侧没有「调 Kotlin」这回事，[Thrown.message] 的等价逻辑是内联发的（见
 * `BytecodeCompiler.emitCaughtMessage`），两条路必须逐字对齐，否则两端对拍会炸。
 */
internal object Thrown {
    /** rsf 自己抛的失败在字节码里就是这个类。 */
    const val PLAIN: String = "java.lang.RuntimeException"

    /**
     * 解释器调 JDK 方法是走反射的，Java 那边抛的东西到手时被包了一层
     * `java.lang.reflect.InvocationTargetException`。文案要按**真正抛出来的那个**算，
     * 否则用户看到的是 `java.lang.reflect.InvocationTargetException` 而不是
     * `java.io.FileNotFoundException: 系统找不到指定的文件`。
     */
    fun unwrap(t: Throwable): Throwable {
        var e = t
        while (e is java.lang.reflect.InvocationTargetException && e.targetException != null) {
            e = e.targetException
        }
        return e
    }

    fun message(t: Throwable): String {
        val thrown = unwrap(t)
        val name = thrown.javaClass.name
        val text = thrown.message
        return when {
            name == PLAIN -> text ?: ""
            text == null -> name
            else -> "$name: $text"
        }
    }
}
