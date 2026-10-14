package org.example.complie.util

/**
 * 类似 Python 的 input()
 * @param prompt 提示信息
 * @return 用户输入的字符串
 */
fun input(prompt: String = ""): String {
    if (prompt.isNotEmpty()) print(prompt)
    return readlnOrNull() ?: throw NoSuchElementException("EOF when reading a line")
}

/**
 * 带类型转换的 input,等价于 Python 的 int(input()), float(input()) 等
 */
inline fun <reified T> input(prompt: String = ""): T {
    val line = input(prompt)
    return when (T::class) {
        String::class -> line as T
        Int::class -> line.toInt() as T
        Long::class -> line.toLong() as T
        Double::class -> line.toDouble() as T
        Float::class -> line.toFloat() as T
        Boolean::class -> line.toBoolean() as T
        else -> throw IllegalArgumentException("不支持的类型: ${T::class}")
    }
}