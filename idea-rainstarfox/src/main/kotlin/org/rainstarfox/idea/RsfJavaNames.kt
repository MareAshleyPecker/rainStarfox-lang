package org.rainstarfox.idea

/**
 * 把 rsf 源码里的名字翻成 Java 的候选全限定名。
 *
 * 为什么要猜：rsf 没有自己的类型系统入口给 IDE 用（符号表在编译器里），
 * 但 rsf 调 Java 的写法是 Java 式的——`Math.max`、`Integer.parseInt`、`System.out.println`，
 * 以及 `import java.lang.Math`。所以按 Java 的解析习惯（java.lang 自动可见、显式 import、
 * 通配 import）列出候选，再交给 IDEA 的 Java PSI 去查。
 *
 * 这里只做字符串层面的工作，不碰 PSI，方便单测。
 */
object RsfJavaNames {

    /** 光标处的标识符范围；光标在词尾（`Math|.max`）时取前一个词。 */
    fun identifierAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var pos = offset.coerceIn(0, text.length)
        // 光标正好停在词尾：看前一个字符
        if (pos == text.length || !isPart(text[pos])) {
            if (pos == 0) return null
            pos -= 1
        }
        if (!isPart(text[pos])) return null
        var start = pos
        while (start > 0 && isPart(text[start - 1])) start -= 1
        var end = pos
        while (end + 1 < text.length && isPart(text[end + 1])) end += 1
        return start..end
    }

    /** `Math.max` 里 `max` 前的限定部分（`Math`）；`a.b.C` 里 `C` 前是 `a.b`。 */
    fun qualifierBefore(text: String, tokenStart: Int): String {
        var pos = tokenStart - 1
        while (pos >= 0 && text[pos].isWhitespace()) pos -= 1
        if (pos < 0 || text[pos] != '.') return ""
        var end = pos
        while (pos >= 0 && (isPart(text[pos]) || text[pos] == '.')) pos -= 1
        val raw = text.substring(pos + 1, end)
        // 去掉尾部多余的点和开头的点（`..` 之类不用管，反正后面查不到）
        return raw.trim('.')
    }

    /** 光标处可能是类名：自身、java.lang 下、或 import 进来的。 */
    fun classCandidates(token: String, qualifier: String, fileText: String): List<String> {
        val out = LinkedHashSet<String>()
        if (qualifier.isNotEmpty()) out += "$qualifier.$token"
        out += simpleClassCandidates(token, fileText)
        return out.toList()
    }

    /** 一个简单名可能对应的全限定名：`Math` → `Math`、`java.lang.Math`、以及 import 里的。 */
    fun simpleClassCandidates(name: String, fileText: String): List<String> {
        val out = LinkedHashSet<String>()
        out += name
        out += "java.lang.$name"
        for (line in importLines(fileText)) {
            val imported = line.removePrefix("import").trim()
            if (imported.endsWith(".*")) {
                out += imported.dropLast(1) + name
            } else if (imported.substringAfterLast('.') == name) {
                out += imported
            }
        }
        return out.toList()
    }

    /** `import java.lang.Math` 这类行的原样内容（含 `import`）。 */
    fun importLines(fileText: String): List<String> =
        fileText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

    private fun isPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()
}
