package org.rainstarfox.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.io.File

/**
 * 用编译器自己的检查结果画波浪线。
 *
 * 走 [ExternalAnnotator]：检查在**后台线程**跑（要和语言服务器通信），算完回 EDT 画标记。
 * 检查对象是编辑器里的**当前文本**（含未保存改动），不是磁盘上那份——刚敲一半就出错才有用。
 *
 * 后端从「每次起一个 JVM 跑 `check --json`」换成了常驻的 LSP 服务器（见 [RsfLspClient]）：
 * 同样的检查，延迟从两三百毫秒降到网络往返级别，这才是 Kotlin 那种「边打边报」的手感。
 */
class RsfExternalAnnotator : ExternalAnnotator<RsfExternalAnnotator.Input, List<RsfLspClient.Diagnostic>>() {

    data class Input(
        val uri: String,
        val text: String,
        val baseDir: File,
        val command: RsfCommand,
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        if (file !is RsfPsiFile) return null
        if (!RsfSettings.getInstance().state.liveDiagnostics) return null

        val virtualFile = file.virtualFile ?: return null
        val command = RsfLauncher.detect(file.project) ?: return null
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return null

        return Input(RsfLspClient.toUri(virtualFile.path), editor.document.text, baseDir, command)
    }

    override fun doAnnotate(collectedInfo: Input): List<RsfLspClient.Diagnostic> {
        val client = RsfLspService.client(collectedInfo.command, collectedInfo.baseDir) ?: return emptyList()
        // 服务器会连 import 进来的文件一起报；这里只要本文档的，否则会画到别人头上
        return RsfDiagnostics.align(client.diagnose(collectedInfo.uri, collectedInfo.text), collectedInfo.uri)
    }

    override fun apply(file: PsiFile, annotationResult: List<RsfLspClient.Diagnostic>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (diagnostic in annotationResult) {
            val range = rangeOf(document, diagnostic) ?: continue
            val severity = when (diagnostic.severity) {
                "warning" -> HighlightSeverity.WARNING
                "info" -> HighlightSeverity.INFORMATION
                else -> HighlightSeverity.ERROR
            }
            holder.newAnnotation(severity, diagnostic.message)
                .range(range)
                .create()
        }
    }

    /** 把编译器给的「行/列」换算成文档区间。列号从 1 开始，且是**字符**数不是字节数。 */
    private fun rangeOf(document: Document, diagnostic: RsfLspClient.Diagnostic): TextRange? {
        val lineIndex = diagnostic.line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null

        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val start = (lineStart + diagnostic.col - 1).coerceIn(lineStart, lineEnd)
        val rawEnd = if (diagnostic.endCol > diagnostic.col) lineStart + diagnostic.endCol - 1 else start + 1
        val end = rawEnd.coerceIn(start, lineEnd).let { if (it <= start) (start + 1).coerceAtMost(lineEnd) else it }
        if (end <= start) return null
        return TextRange(start, end)
    }
}
