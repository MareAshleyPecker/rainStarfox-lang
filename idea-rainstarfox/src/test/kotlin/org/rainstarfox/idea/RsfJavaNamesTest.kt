package org.rainstarfox.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 名字翻译的纯字符串逻辑（不碰 PSI，所以能直接单测）。
 *
 * 这几条守的是「rsf 里 Ctrl+点能落到 Java 类」的候选集：漏掉 java.lang 或漏掉 import，
 * 用户就会觉得「点了没反应」。
 */
class RsfJavaNamesTest {

    @Test
    fun `光标在类名上取到标识符范围`() {
        val text = "Math.max(3, 7)"
        assertEquals(0..3, RsfJavaNames.identifierAt(text, 1))
        // 光标停在词尾后一位（Math|.max）也算在 Math 上
        assertEquals(0..3, RsfJavaNames.identifierAt(text, 4))
        assertEquals(5..7, RsfJavaNames.identifierAt(text, 6))
    }

    @Test
    fun `光标在空白处返回 null`() {
        assertEquals(null, RsfJavaNames.identifierAt("  ", 1))
        assertEquals(null, RsfJavaNames.identifierAt("", 0))
    }

    @Test
    fun `限定部分取到最后一个点之前`() {
        assertEquals("Math", RsfJavaNames.qualifierBefore("Math.max", 5))
        assertEquals("java.lang", RsfJavaNames.qualifierBefore("java.lang.Math", 10))
        assertEquals("System.out", RsfJavaNames.qualifierBefore("System.out.println", 11))
        assertEquals("", RsfJavaNames.qualifierBefore("max", 0))
        assertEquals("", RsfJavaNames.qualifierBefore("a + b", 4))
    }

    @Test
    fun `java lang 的类不用 import 也能当候选`() {
        val text = "fn main() -> void { println(Math.max(1, 2)) }"
        val candidates = RsfJavaNames.classCandidates("Math", "", text)
        assertTrue(candidates.contains("Math"))
        assertTrue(candidates.contains("java.lang.Math"))
    }

    @Test
    fun `显式 import 的类进候选`() {
        val text = "import java.util.ArrayList\n\nfn main() -> void { }\n"
        assertTrue(RsfJavaNames.simpleClassCandidates("ArrayList", text).contains("java.util.ArrayList"))
        assertEquals(listOf("import java.util.ArrayList"), RsfJavaNames.importLines(text))
    }

    @Test
    fun `通配 import 也进候选`() {
        val text = "import java.util.*\n"
        assertTrue(RsfJavaNames.simpleClassCandidates("HashMap", text).contains("java.util.HashMap"))
    }

    @Test
    fun `带限定部分的候选先试全限定名`() {
        val text = "java.lang.System.out.println(1)"
        val candidates = RsfJavaNames.classCandidates("System", "java.lang", text)
        assertEquals("java.lang.System", candidates.first())
    }
}
