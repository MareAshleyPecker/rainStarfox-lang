package org.rainstarfox.idea

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.TextRange
import java.io.File

/**
 * 用编译器自己的检查结果画波浪线。
 *
 * 为什么走 [ExternalAnnotator]：它允许在**后台线程**里干活（这里要起一个进程跑编译器，
 * 必须离开 EDT），算完再回 EDT 画标记。
 *
 * 检查的对象是**编辑器里当前的文本**（含未保存的改动），不是磁盘上的那份——刚敲一半
 * 就看到错误，才有意义。
 */
class RsfExternalAnnotator : ExternalAnnotator<RsfExternalAnnotator.Input, List<RsfCli.Diagnostic>>() {

    data class Input(
        val path: String,
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

        return Input(virtualFile.path, editor.document.text, baseDir, command)
    }

    override fun doAnnotate(collectedInfo: Input): List<RsfCli.Diagnostic> =
        RsfCli.check(collectedInfo.command, collectedInfo.path, collectedInfo.text, collectedInfo.baseDir)

    override fun apply(file: PsiFile, annotationResult: List<RsfCli.Diagnostic>, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (diagnostic in annotationResult) {
            val range = rangeOf(document, diagnostic) ?: continue
            val severity = if (diagnostic.severity == "warning") HighlightSeverity.WARNING else HighlightSeverity.ERROR
            holder.newAnnotation(severity, diagnostic.message)
                .range(range)
                .create()
        }
    }

    /** 把编译器给的「行/列」换算成文档区间。列号从 1 开始，且是**字符**数不是字节数。 */
    private fun rangeOf(document: Document, diagnostic: RsfCli.Diagnostic): TextRange? {
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
