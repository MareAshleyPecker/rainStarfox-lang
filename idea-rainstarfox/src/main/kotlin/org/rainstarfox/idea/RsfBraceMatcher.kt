package org.rainstarfox.idea

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** 括号配对：`()` `{}` `[]`。光靠这个 IDEA 就能画括号高亮、跳配对、自动补右括号。 */
class RsfBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(RsfTokens.LPAREN, RsfTokens.RPAREN, false),
            BracePair(RsfTokens.LBRACE, RsfTokens.RBRACE, true),
            BracePair(RsfTokens.LBRACKET, RsfTokens.RBRACKET, false),
        )
    }
}
