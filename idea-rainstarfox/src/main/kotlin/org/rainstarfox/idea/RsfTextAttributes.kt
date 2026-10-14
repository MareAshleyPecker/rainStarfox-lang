package org.rainstarfox.idea

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors

/**
 * 颜色键。
 *
 * 大部分直接复用 IDEA 的标准键（关键字、字符串、数字、注释……），这样用户在
 * 「配色方案」里换主题时，rsf 的颜色会跟着主题一起变，而不是自己一套写死的色。
 * 只有标准键里没有概念的那几个（类型名、函数名、内置函数）才自建。
 */
object RsfTextAttributes {

    val KEYWORD = TextAttributesKey.createTextAttributesKey(
        "RSF_KEYWORD",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    val MODIFIER = TextAttributesKey.createTextAttributesKey(
        "RSF_MODIFIER",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    /** `int` `double` `string` `void` 这些类型名。 */
    val TYPE = TextAttributesKey.createTextAttributesKey(
        "RSF_TYPE",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    val FUNCTION_DECLARATION = TextAttributesKey.createTextAttributesKey(
        "RSF_FUNCTION_DECLARATION",
        DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
    )

    val FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
        "RSF_FUNCTION_CALL",
        DefaultLanguageHighlighterColors.FUNCTION_CALL,
    )

    /** `print` / `println`：语言内置，不是用户定义的函数。 */
    val BUILTIN = TextAttributesKey.createTextAttributesKey(
        "RSF_BUILTIN",
        DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL,
    )

    val IDENTIFIER = TextAttributesKey.createTextAttributesKey(
        "RSF_IDENTIFIER",
        DefaultLanguageHighlighterColors.IDENTIFIER,
    )

    val NUMBER = TextAttributesKey.createTextAttributesKey(
        "RSF_NUMBER",
        DefaultLanguageHighlighterColors.NUMBER,
    )

    val STRING = TextAttributesKey.createTextAttributesKey(
        "RSF_STRING",
        DefaultLanguageHighlighterColors.STRING,
    )

    val LINE_COMMENT = TextAttributesKey.createTextAttributesKey(
        "RSF_LINE_COMMENT",
        DefaultLanguageHighlighterColors.LINE_COMMENT,
    )

    val BLOCK_COMMENT = TextAttributesKey.createTextAttributesKey(
        "RSF_BLOCK_COMMENT",
        DefaultLanguageHighlighterColors.BLOCK_COMMENT,
    )

    val OPERATION = TextAttributesKey.createTextAttributesKey(
        "RSF_OPERATION",
        DefaultLanguageHighlighterColors.OPERATION_SIGN,
    )

    val PARENTHESES = TextAttributesKey.createTextAttributesKey(
        "RSF_PARENTHESES",
        DefaultLanguageHighlighterColors.PARENTHESES,
    )

    val BRACES = TextAttributesKey.createTextAttributesKey(
        "RSF_BRACES",
        DefaultLanguageHighlighterColors.BRACES,
    )

    val BRACKETS = TextAttributesKey.createTextAttributesKey(
        "RSF_BRACKETS",
        DefaultLanguageHighlighterColors.BRACKETS,
    )

    val COMMA = TextAttributesKey.createTextAttributesKey(
        "RSF_COMMA",
        DefaultLanguageHighlighterColors.COMMA,
    )

    val SEMICOLON = TextAttributesKey.createTextAttributesKey(
        "RSF_SEMICOLON",
        DefaultLanguageHighlighterColors.SEMICOLON,
    )

    val DOT = TextAttributesKey.createTextAttributesKey(
        "RSF_DOT",
        DefaultLanguageHighlighterColors.DOT,
    )

    val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey(
        "RSF_BAD_CHARACTER",
        DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE,
    )
}
