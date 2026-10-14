package org.rainstarfox.idea

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * PSI 的定义。
 *
 * 这里**故意不做真正的语法树**：解析器只是把词法流原样塞进一个根节点，
 * 也就是一棵扁平的 token 树。原因很实在——语法分析已经有编译器那份权威实现了，
 * 插件再写一份只会两边不一致；插件真正需要的「高亮、括号配对、注释、报错、补全」
 * 全都不依赖语法树（报错和补全直接问编译器）。
 *
 * 代价是：IDEA 的「结构」视图里看不到函数层级、重命名/跳转这类基于 PSI 的重构
 * 用不了。等哪天要做这些，再往这个解析器里填真正的语法分析。
 */
class RsfParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = RsfLexer()

    override fun createParser(project: Project?): PsiParser = RsfParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = RsfPsiFile(viewProvider)

    companion object {
        val FILE = IFileElementType("RSF_FILE", RsfLanguage)
        val COMMENTS: TokenSet = TokenSet.create(RsfTokens.LINE_COMMENT, RsfTokens.BLOCK_COMMENT)
        val STRINGS: TokenSet = TokenSet.create(RsfTokens.STRING)
    }
}

/** 扁平解析器：一路 advance 到底，什么也不组合。 */
class RsfParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        return builder.treeBuilt
    }
}

class RsfPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, RsfLanguage) {
    override fun getFileType(): FileType = RsfFileType

    override fun toString(): String = "rainStarfox 文件"
}
