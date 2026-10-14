package org.rainstarfox.idea

import com.intellij.lang.Commenter

/** `//` 和 斜杠星号 注释，快捷键 Ctrl+/ 与 Ctrl+Shift+/ 用。 */
class RsfCommenter : Commenter {

    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
