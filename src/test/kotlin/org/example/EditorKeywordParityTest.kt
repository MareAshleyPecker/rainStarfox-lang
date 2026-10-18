package org.example

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「编辑器关键字表」跟编译器词法表的对齐守卫。
 *
 * 编译器的关键字表（`Lexer.kt` 的 KEYWORDS）在仓库里抄了三份：编译器自己的补全表
 * （`lsp/EditorSupport.kt`）、IDEA 插件的高亮表（`idea-rainstarfox/.../RsfTokenType.kt`）、
 * 以及插件测试里的手抄快照。阶段 19c 的 `enum`/`switch`/`case` 当时只补进了补全表，
 * 高亮表一起漏，于是「两边一样缺」永远绿着——这种漂移没有任何测试会红。
 *
 * 这里直接读源码文件（不是读手抄快照），所以能抓到真漂移：
 *   1. 编译器关键字必须全部出现在 IDEA 插件的高亮表里；
 *   2. 那张表里多出来的词必须在 [KNOWN_NON_KEYWORD] 白名单里。
 *
 * 只看得到词、看不到作用域：具体上成什么颜色由 `RsfLexerTest` 负责。
 */
class EditorKeywordParityTest {

    /** 编辑器表里合理存在、但不属于编译器全局关键字的条目。 */
    private val knownNonKeyword = setOf(
        // for ... step ... 的上下文词，编译器只在 for 头里认它，不是全局关键字
        "step",
        // 内建函数（解释器/后端直接实现，没有 rsf 函数体）
        "print", "println",
        "toByte", "toChar", "toLower", "toUpper",
        "charToInt", "intToString", "isDigit", "isLetter", "isWhitespace", "parseInt",
    )

    private val projectRoot: File = run {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle").isFile) dir = dir.parentFile
        requireNotNull(dir) { "没找到工程根目录（settings.gradle 所在目录）" }
        dir
    }

    private fun read(relative: String): String {
        val f = File(projectRoot, relative)
        assertTrue(f.isFile, "读不到 $relative（工程根目录 $projectRoot）")
        return f.readText()
    }

    /** 编译器词法表的 `put("xxx", TokenKind.KW_...)`。 */
    private fun compilerKeywords(): Set<String> =
        Regex("""put\("([A-Za-z_][A-Za-z0-9_]*)",\s*TokenKind\.KW_""")
            .findAll(read("src/main/kotlin/org/example/complie/lexing/Lexer.kt"))
            .map { it.groupValues[1] }
            .toSet()

    /** IDEA 插件表：`RsfTokenType.kt` 里所有 `setOf("...")` 里的词。 */
    private fun ideaKeywords(): Set<String> {
        val text = read("idea-rainstarfox/src/main/kotlin/org/rainstarfox/idea/RsfTokenType.kt")
        val words = LinkedHashSet<String>()
        val quoted = Regex("""["']([A-Za-z_][A-Za-z0-9_]*)["']""")
        for (set in Regex("""setOf\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            for (m in quoted.findAll(set.groupValues[1])) words.add(m.groupValues[1])
        }
        return words
    }

    @Test
    fun `IDEA 插件的关键字表与编译器词法表对齐`() {
        val compiler = compilerKeywords()
        val idea = ideaKeywords()
        val missing = (compiler - idea).sorted()
        val extra = (idea - compiler - knownNonKeyword).sorted()
        assertTrue(
            missing.isEmpty(),
            "IDEA 插件的高亮表缺了编译器关键字（补 RsfTokenType.kt，或者把 [KNOWN_NON_KEYWORD] 更新掉）：$missing",
        )
        assertTrue(
            extra.isEmpty(),
            "IDEA 插件的高亮表多出编译器没有的词（编译器删了关键字？还是新加的没进 Lexer.kt？）：$extra",
        )
    }

    @Test
    fun `编译器的补全表认识阶段 21 与阶段 19c 的关键字`() {
        val compiler = compilerKeywords()
        val newWords = setOf("annotation", "companion", "entity")
        assertTrue(
            compiler.containsAll(newWords),
            "编译器词法表少了阶段 21 的关键字：${(newWords - compiler).sorted()}",
        )
        val editorSupport = read("src/main/kotlin/org/example/complie/lsp/EditorSupport.kt")
        for (word in listOf("annotation", "companion", "entity", "enum", "switch", "case")) {
            assertTrue(
                editorSupport.contains("\"$word\""),
                "lsp/EditorSupport.kt 的补全表缺 '$word'",
            )
        }
    }

    @Test
    fun `编译器每个关键字都在补全表里`() {
        val compiler = compilerKeywords()
        val editorSupport = read("src/main/kotlin/org/example/complie/lsp/EditorSupport.kt")
        // `mathematic` 是保留字但语法没定（BOOTSTRAP 里已移出计划），补全里不列它是故意的
        val missing = compiler
            .filter { it != "mathematic" }
            .filter { !editorSupport.contains("\"$it\"") }
            .sorted()
        assertTrue(
            missing.isEmpty(),
            "lsp/EditorSupport.kt 的补全表缺这些关键字（补 KEYWORDS 或 TYPES）：$missing",
        )
    }
}
