package org.rainstarfox.idea

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/** 把词法单元映射成颜色。 */
class RsfSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = RsfLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        RsfTokens.KEYWORD -> KEYWORD
        RsfTokens.CONTROL_KEYWORD -> KEYWORD
        RsfTokens.MODIFIER -> MODIFIER
        RsfTokens.TYPE_KEYWORD -> TYPE
        // 用户类名被当类型用的位置（`var p : Point`）：跟类型关键字一个颜色
        RsfTokens.TYPE_NAME -> TYPE
        RsfTokens.FUNCTION_DECLARATION -> FUNCTION_DECLARATION
        RsfTokens.FUNCTION_CALL -> FUNCTION_CALL
        RsfTokens.BUILTIN -> BUILTIN
        RsfTokens.IDENTIFIER -> IDENTIFIER
        RsfTokens.NUMBER -> NUMBER
        RsfTokens.STRING -> STRING
        // 字符字面量用字符串的颜色（标准配色里没有单独一项，不值得再自建一个键）
        RsfTokens.CHARACTER -> STRING
        RsfTokens.LINE_COMMENT -> LINE_COMMENT
        RsfTokens.BLOCK_COMMENT -> BLOCK_COMMENT
        RsfTokens.OPERATION -> OPERATION
        RsfTokens.LPAREN, RsfTokens.RPAREN -> PARENTHESES
        RsfTokens.LBRACE, RsfTokens.RBRACE -> BRACES
        RsfTokens.LBRACKET, RsfTokens.RBRACKET -> BRACKETS
        RsfTokens.COMMA -> COMMA
        RsfTokens.SEMICOLON -> SEMICOLON
        RsfTokens.DOT -> DOT
        RsfTokens.BAD_CHARACTER -> BAD_CHARACTER
        else -> EMPTY
    }

    companion object {
        private val KEYWORD = arrayOf(RsfTextAttributes.KEYWORD)
        private val MODIFIER = arrayOf(RsfTextAttributes.MODIFIER)
        private val TYPE = arrayOf(RsfTextAttributes.TYPE)
        private val FUNCTION_DECLARATION = arrayOf(RsfTextAttributes.FUNCTION_DECLARATION)
        private val FUNCTION_CALL = arrayOf(RsfTextAttributes.FUNCTION_CALL)
        private val BUILTIN = arrayOf(RsfTextAttributes.BUILTIN)
        private val IDENTIFIER = arrayOf(RsfTextAttributes.IDENTIFIER)
        private val NUMBER = arrayOf(RsfTextAttributes.NUMBER)
        private val STRING = arrayOf(RsfTextAttributes.STRING)
        private val LINE_COMMENT = arrayOf(RsfTextAttributes.LINE_COMMENT)
        private val BLOCK_COMMENT = arrayOf(RsfTextAttributes.BLOCK_COMMENT)
        private val OPERATION = arrayOf(RsfTextAttributes.OPERATION)
        private val PARENTHESES = arrayOf(RsfTextAttributes.PARENTHESES)
        private val BRACES = arrayOf(RsfTextAttributes.BRACES)
        private val BRACKETS = arrayOf(RsfTextAttributes.BRACKETS)
        private val COMMA = arrayOf(RsfTextAttributes.COMMA)
        private val SEMICOLON = arrayOf(RsfTextAttributes.SEMICOLON)
        private val DOT = arrayOf(RsfTextAttributes.DOT)
        private val BAD_CHARACTER = arrayOf(RsfTextAttributes.BAD_CHARACTER)
        private val EMPTY = emptyArray<TextAttributesKey>()

        /** 给编辑器里的「注释/字符串」判断用，暂时没别的地方需要。 */
        val COMMENT_TOKENS: TokenSet = TokenSet.create(RsfTokens.LINE_COMMENT, RsfTokens.BLOCK_COMMENT)
    }
}

class RsfSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = RsfSyntaxHighlighter()
}
