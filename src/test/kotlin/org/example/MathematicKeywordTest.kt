package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.TokenKind
import org.example.complie.parsing.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 数学类的**预留**关键字 `mathematic`（2026-10-15 用户拍板先收进词法表）。
 *
 * 这一阶段只做「保留」：语法还没定（`BOOTSTRAP.md` 的「搁置：`mathematic`」一节只留意图与待决点），
 * 所以它既不进访问控制 / 静态 / 修饰词集合，也不进类型表——现在写它应当得到一句
 * 「保留关键字（数学类预留）」，而不是被当成普通名字悄悄用掉，也不是被误当修饰词混进声明头。
 *
 * 边界：词法按**整词**匹配，所以 `mathematics` / `myMathematic` 这类名字不受影响。
 */
class MathematicKeywordTest {

    @Test
    fun `mathematic 是关键字而不是标识符`() {
        val kinds = Rsf.tokens(source("mathematic")).map { it.kind }
        assertEquals(listOf(TokenKind.KW_MATHEMATIC, TokenKind.EOF), kinds)
        assertEquals("mathematic", TokenKind.KW_MATHEMATIC.text)
    }

    @Test
    fun `mathematic 不是修饰词也不是类型`() {
        val k = TokenKind.KW_MATHEMATIC
        assertTrue(!k.isControlKeyword, "mathematic 不是访问控制词（pub/prv/prot）")
        assertTrue(!k.isStaticKeyword, "mathematic 不是 stc")
        assertTrue(!k.isModifierKeyword, "语法未定，先不放进修饰词集合")
        assertTrue(!k.isTypeKeyword, "语法未定，先不放进类型表")
    }

    @Test
    fun `以 mathematic 开头的长名字不受影响`() {
        val kinds = Rsf.tokens(source("mathematics myMathematic")).map { it.kind }
        assertEquals(listOf(TokenKind.IDENT, TokenKind.IDENT, TokenKind.EOF), kinds)
    }

    @Test
    fun `顶层写 mathematic 报还没实现`() {
        val e = assertFailsWith<RsfError> { parse("mathematic Math { }") }
        assertEquals("parser", e.phase)
        val msg = e.message
        assertTrue(msg.contains("保留关键字"), "报错要说清这是保留关键字，实际：$msg")
        assertTrue(msg.contains("mathematic"), "报错要带上关键字本身，实际：$msg")
        assertEquals(1, e.line)
        assertEquals(1, e.column)
    }

    @Test
    fun `拿 mathematic 当名字用也会报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                fn f() -> void {
                    var mathematic : int = 0
                    rtn
                }
                """.trimIndent(),
            )
        }
        assertTrue(
            e.message.contains("mathematic"),
            "当名字用时要报出是哪个词（不能被当成合法标识符），实际：${e.message}",
        )
    }
}

private fun source(text: String): SourceFile = SourceFile.of("<mathematic-keyword>", text)

private fun parse(text: String): org.example.complie.ast.Program =
    Parser(Rsf.tokens(source(text)), source(text)).parseProgram()
