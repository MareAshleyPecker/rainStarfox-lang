package org.rainstarfox.idea

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import java.io.File

/**
 * 重命名（Shift+F6）。
 *
 * 插件不自己算「哪些地方要改」：把光标位置和「新名字」交给语言服务器
 * （`textDocument/rename`），服务器回一份 `WorkspaceEdit`，这里按位置从后往前替换。
 * 好处是重命名的范围和 Ctrl+点、诊断用的是同一份符号解析——不会出现「跳转到 A、
 * 重命名却改了 B」。
 *
 * 服务器拒绝时（类型改名、非法名字、光标下没有声明）弹通知，一个字符都不改。
 */
class RsfRenameHandler : RenameHandler {

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean =
        CommonDataKeys.PSI_FILE.getData(dataContext) is RsfPsiFile

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        val targetFile = file ?: CommonDataKeys.PSI_FILE.getData(dataContext) ?: return
        var targetEditor = editor ?: CommonDataKeys.EDITOR.getData(dataContext)
        if (targetFile !is RsfPsiFile) return
        if (targetEditor == null) {
            targetEditor = targetFile.virtualFile?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
                ?: return
        }
        val virtualFile = targetFile.virtualFile ?: return
        val document = targetEditor.document
        val offset = targetEditor.caretModel.offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val character = offset - document.getLineStartOffset(line)

        val command = RsfLauncher.detect(project) ?: run {
            RsfRunner.notify(project, "没有找到编译器", "先在 Settings → Tools → rainStarfox 里指定启动器", NotificationType.ERROR)
            return
        }
        val baseDir = virtualFile.parent?.path?.let { File(it) }
        val client = RsfLspService.client(command, baseDir) ?: run {
            RsfRunner.notify(project, "起不来语言服务器", command.executable, NotificationType.ERROR)
            return
        }

        val oldName = wordAt(document, offset) ?: return
        val newName = Messages.showInputDialog(
            project,
            "把 '$oldName' 重命名为：",
            "rainStarfox 重命名",
            null,
            oldName,
            null,
        ) ?: return
        if (newName == oldName) return

        val uri = RsfLspClient.toUri(virtualFile.path)
        val edits = client.rename(uri, document.text, line, character, newName) ?: run {
            RsfRunner.notify(
                project,
                "重命名没有执行",
                "光标处没有可重命名的声明；类型改名暂不支持（见 DESIGN-45 的缺口）",
                NotificationType.WARNING,
            )
            return
        }
        applyEdits(project, edits)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {
        val file = elements.firstOrNull()?.containingFile as? RsfPsiFile ?: return
        invoke(project, null, file, dataContext)
    }

    /** 按「行号大的先改」应用替换：位置不会因为前面的插入而漂移。 */
    private fun applyEdits(project: Project, edits: Map<String, List<RsfLspClient.TextEdit>>) {
        var changed = 0
        WriteCommandAction.runWriteCommandAction(project) {
            val manager = FileDocumentManager.getInstance()
            for ((fileUri, list) in edits) {
                val path = RsfLspClient.fromUri(fileUri)
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: continue
                val document = manager.getDocument(virtualFile) ?: continue
                val ordered = list.sortedWith(
                    compareByDescending<RsfLspClient.TextEdit> { it.startLine }.thenByDescending { it.startCharacter },
                )
                for (edit in ordered) {
                    val start = offsetOf(document, edit.startLine, edit.startCharacter) ?: continue
                    val end = offsetOf(document, edit.endLine, edit.endCharacter) ?: continue
                    if (start > end || end > document.textLength) continue
                    document.replaceString(start, end, edit.newText)
                    changed++
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                manager.saveDocument(document)
            }
        }
        RsfRunner.notify(
            project,
            "重命名完成",
            if (changed == 0) "没有可改的位置" else "改了 $changed 处",
            if (changed == 0) NotificationType.WARNING else NotificationType.INFORMATION,
        )
    }

    private fun offsetOf(document: Document, line: Int, character: Int): Int? {
        if (line < 0 || line >= document.lineCount) return null
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return (start + character).coerceIn(start, end)
    }

    /** 光标处的标识符（停在词尾后一位也算）。 */
    private fun wordAt(document: Document, offset: Int): String? {
        val text = document.text
        if (text.isEmpty()) return null
        var index = offset.coerceIn(0, text.length - 1)
        if (!isWordChar(text[index]) && index > 0 && isWordChar(text[index - 1])) index--
        if (!isWordChar(text[index])) return null
        var start = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = index + 1
        while (end < text.length && isWordChar(text[end])) end++
        return text.substring(start, end)
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
