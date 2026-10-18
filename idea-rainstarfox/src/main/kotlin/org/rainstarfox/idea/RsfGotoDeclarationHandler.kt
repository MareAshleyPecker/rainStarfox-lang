package org.rainstarfox.idea

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Ctrl+点跳转。
 *
 * 跳哪儿**不是**插件猜的：把光标位置交给语言服务器（`textDocument/definition`），
 * 声明位置由编译器自己的符号表给出。所以插件里不维护第二份「谁是声明」的知识。
 *
 * 跨文件也行：服务器回的是目标文件的绝对路径，这里换成 [PsiElement]，IDEA 自己会
 * 打开那个文件并把光标放上去。
 */
class RsfGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val file = sourceElement?.containingFile as? RsfPsiFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val command = RsfLauncher.detect(file.project) ?: return null
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return null

        val document = editor.document
        val offsetInDocument = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offsetInDocument)
        val character = offsetInDocument - document.getLineStartOffset(line)

        val client = RsfLspService.client(command, baseDir) ?: return null
        val target = client.definition(RsfLspClient.toUri(virtualFile.path), document.text, line, character) ?: return null

        val targetVirtualFile = LocalFileSystem.getInstance().findFileByPath(target.path) ?: return null
        val targetPsi = PsiManager.getInstance(file.project).findFile(targetVirtualFile) ?: return null
        val targetDocument = FileDocumentManager.getInstance().getDocument(targetVirtualFile)
            ?: return arrayOf(targetPsi)

        val targetLine = target.line.coerceIn(0, maxOf(0, targetDocument.lineCount - 1))
        val start = targetDocument.getLineStartOffset(targetLine)
        val end = targetDocument.getLineEndOffset(targetLine)
        val position = (start + target.character).coerceIn(start, end)
        return arrayOf(targetPsi.findElementAt(position) ?: targetPsi)
    }
}
