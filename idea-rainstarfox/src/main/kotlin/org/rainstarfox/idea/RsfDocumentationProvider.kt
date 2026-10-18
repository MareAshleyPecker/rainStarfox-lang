package org.rainstarfox.idea

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.io.File

/**
 * 悬停文档（RDoc）。
 *
 * 文档来源是语言服务器：声明正上方连续的 `///` 行 + 签名。所以「写注释」这件事
 * 在插件、命令行、自举实现里是同一份规则，不存在两套解析。
 *
 * 入口用 [generateDoc] 而不是 `getCustomDocumentation*`：2025.3 的
 * `DocumentationProvider` 已经没有返回字符串的自定义入口了，能拿到光标偏移的
 * 只有传进来的那个叶子元素（rsf 的 PSI 是扁平 token 树，偏移就是它的起点）。
 */
class RsfDocumentationProvider : DocumentationProvider {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val leaf = element ?: return null
        val file = leaf.containingFile as? RsfPsiFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val command = RsfLauncher.detect(file.project) ?: return null
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return null

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val position = leaf.textRange.startOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(position)
        val character = position - document.getLineStartOffset(line)

        val client = RsfLspService.client(command, baseDir) ?: return null
        val markdown = client.hover(RsfLspClient.toUri(virtualFile.path), document.text, line, character)
            ?: return null
        return RsfMarkdown.toHtml(markdown)
    }
}
