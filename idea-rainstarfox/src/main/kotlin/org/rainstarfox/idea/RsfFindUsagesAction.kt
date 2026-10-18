package org.rainstarfox.idea

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import java.awt.Component

/**
 * 查找引用（Find Usages）。
 *
 * 结果来自语言服务器（`textDocument/references`），点一条就跳过去。**不是** IDEA 标准的
 * Find 工具窗：那套要用 `PsiReference` 做索引，而这个插件的 PSI 是扁平的（词法树）。
 * 所以这里给一个轻量弹窗列表——够用、且不会假装自己支持重构索引。
 */
class RsfFindUsagesAction : DumbAwareAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) as? RsfPsiFile ?: return
        val virtualFile = file.virtualFile ?: return

        val command = RsfLauncher.detect(project) ?: run {
            notify(project, "没有找到编译器", "先在 Settings → Tools → rainStarfox 里指定启动器", NotificationType.ERROR)
            return
        }
        val baseDir = virtualFile.parent?.path?.let { File(it) }
        val client = RsfLspService.client(command, baseDir) ?: run {
            notify(project, "起不来语言服务器", command.executable, NotificationType.ERROR)
            return
        }

        val document = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val character = offset - document.getLineStartOffset(line)
        val uri = RsfLspClient.toUri(virtualFile.path)
        val usages = client.references(uri, document.text, line, character)
        if (usages.isEmpty()) {
            notify(project, "没有引用", "光标处没有能解析的声明，或没有别处引用它", NotificationType.INFORMATION)
            return
        }
        if (usages.size == 1 && event.getData(PlatformDataKeys.EDITOR) != null) {
            // 只有声明自己：仍然列出来，但先说清楚，省得以为「查不到」
            notify(project, "只有声明自身", "没有别处引用它", NotificationType.INFORMATION)
            return
        }

        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(usages)
            .setTitle("rainStarfox 引用（${usages.size} 处）")
            .setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    focus: Boolean,
                ): Component {
                    val location = value as? RsfLspClient.Location
                    val text = if (location == null) {
                        ""
                    } else {
                        val name = File(location.path).name
                        val flag = if (RsfLspClient.toUri(location.path) == uri) "" else "  ← ${File(location.path).parentFile?.name ?: ""}"
                        "$name:${location.line + 1}:${location.character + 1}$flag"
                    }
                    return super.getListCellRendererComponent(list, text, index, selected, focus)
                }
            })
            .setItemChosenCallback { location ->
                val target = LocalFileSystem.getInstance().refreshAndFindFileByPath(location.path) ?: return@setItemChosenCallback
                val targetDocument = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(target)
                val targetOffset = if (targetDocument == null) {
                    0
                } else {
                    val targetLine = location.line.coerceIn(0, maxOf(0, targetDocument.lineCount - 1))
                    (targetDocument.getLineStartOffset(targetLine) + location.character)
                        .coerceIn(0, targetDocument.getLineEndOffset(targetLine))
                }
                OpenFileDescriptor(project, target, targetOffset).navigate(true)
            }
            .createPopup()
        popup.showInBestPositionFor(editor)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.PSI_FILE) is RsfPsiFile
    }

    private fun notify(project: com.intellij.openapi.project.Project, title: String, message: String, type: NotificationType) {
        RsfRunner.notify(project, title, message, type)
    }
}
