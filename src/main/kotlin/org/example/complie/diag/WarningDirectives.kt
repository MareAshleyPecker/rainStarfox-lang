package org.example.complie.diag

/**
 * 警告的「类型」——`#NoWarning!!(类型)` 里写的就是这些名字。
 *
 * 每条警告都带一个稳定的短名（不是给机器看的编号，是给人写进指令的那种词），
 * 这样抑制指令不用抄一长串中文文案，文案改了也不会失效。
 */
object WarningKind {

    /** 压掉所有类型的警告。 */
    const val ALL: String = "all"

    /** 不可达代码（永远执行不到）。 */
    const val UNREACHABLE: String = "unreachable"

    /** 顶层函数上写了多余的 `stc`。 */
    const val REDUNDANT_STATIC: String = "redundant-static"

    /** switch 分支上写了已弃用的 `case`。 */
    const val DEPRECATED_CASE: String = "deprecated-case"

    /** `as` / `is` 写了泛型实参，运行期按擦除判。 */
    const val ERASURE_CAST: String = "erasure-cast"

    val KNOWN: List<String> = listOf(UNREACHABLE, REDUNDANT_STATIC, DEPRECATED_CASE, ERASURE_CAST)

    /** 报错时列给人看的清单。 */
    val KINDS_TEXT: String = (KNOWN + ALL).joinToString(" / ")
}

/**
 * 文件级指令 `#NoWarning!!(类型, 类型)`：把指定类型的警告压掉。
 *
 * 为什么是 `#` 开头：`#` 在 rsf 里本来没有含义（注释是 `//` 与 `/* */`），
 * 而这条东西的语义就是「预处理指令」——它不参与语法树，只在词法之前把那一行挖成空格。
 * 挖成空格而不是删行，是为了让行号、列号与原文完全对齐（报错位置不受影响）。
 *
 * 为什么是 `!!`：`!!` 已经是 rsf 的「强制」标记，`#NoWarning!!` 读起来就是
 * 「强制不要这条警告」；也让指令一眼区别于普通的 `#` 注释风格。
 *
 * 粒度是**文件**：警告本来就带文件名，按文件抑制最不容易误伤（写在一个文件的头部，
 * 只影响这个文件自己的警告）。`all` 是全压。
 */
object WarningDirectives {

    const val PREFIX: String = "#NoWarning!!"

    /**
     * 整行匹配：允许前后空白，括号里逗号分隔。
     * 用 `[^)]*` 而不是贪婪，是为了让 `#NoWarning!!(all)` 这种一行里只吃一次。
     */
    private val DIRECTIVE: Regex = Regex("""^[ \t]*#NoWarning!![ \t]*\(([^)]*)\)[ \t]*$""")

    /** 找到的指令：类型清单 + 它在第几行（1 基），报错要用。 */
    private class Found(val line: Int, val text: String)

    private fun scan(text: String): List<Found> {
        if (!text.contains(PREFIX)) return emptyList()
        val out = ArrayList<Found>()
        var line = 1
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            val raw = text.substring(start, end)
            val body = raw.removeSuffix("\r")
            if (body.trimStart().startsWith("#") && body.contains(PREFIX)) {
                out.add(Found(line, body))
            }
            if (nl < 0) break
            start = nl + 1
            line++
        }
        return out
    }

    /**
     * 解析出这份文件抑制了哪些类型（已校验）。
     *
     * 类型名字写错会直接报错，而不是静默失效——抑制指令静默失效是最难查的一类 bug
     * （用户以为压掉了，其实还在）。
     */
    fun kinds(source: SourceFile): Set<String> {
        val text = source.text
        if (!text.contains(PREFIX)) return emptySet()
        val out = LinkedHashSet<String>()
        for (found in scan(text)) {
            val match = DIRECTIVE.matchEntire(found.text)
                ?: throw RsfError(
                    "warning",
                    "抑制指令写错了：应该是 `#NoWarning!!(类型, 类型)`，括号里只写类型名。" +
                        "可用的类型：${WarningKind.KINDS_TEXT}",
                    found.line,
                    1,
                    source,
                )
            val names = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) {
                throw RsfError(
                    "warning",
                    "抑制指令的括号里要写类型（例：`#NoWarning!!(unreachable)`）。" +
                        "可用的类型：${WarningKind.KINDS_TEXT}",
                    found.line,
                    1,
                    source,
                )
            }
            for (name in names) {
                if (name != WarningKind.ALL && name !in WarningKind.KNOWN) {
                    throw RsfError(
                        "warning",
                        "不认识的警告类型 '$name'。可用的类型：${WarningKind.KINDS_TEXT}",
                        found.line,
                        1,
                        source,
                    )
                }
                out.add(name)
            }
        }
        return out
    }

    /**
     * 把指令行整行挖成空格（长度不变、换行保留），交给词法器。
     *
     * 只处理整行就是指令的情况：写在别的语句后面的 `#NoWarning!!(...)` 不当指令，
     * 让它掉到词法器报「不认识的字符 '#'」—— 抑制指令必须独立成行才好读。
     */
    fun strip(text: String): String {
        if (!text.contains(PREFIX)) return text
        val found = scan(text)
        if (found.isEmpty()) return text
        val lines = found.map { it.line }.toHashSet()
        val sb = StringBuilder(text)
        var line = 1
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            if (line in lines) {
                for (i in start until end) sb.setCharAt(i, ' ')
            }
            if (nl < 0) break
            start = nl + 1
            line++
        }
        return sb.toString()
    }
}
