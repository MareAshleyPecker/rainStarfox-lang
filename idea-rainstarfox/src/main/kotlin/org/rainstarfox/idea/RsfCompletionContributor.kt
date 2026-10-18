package org.rainstarfox.idea

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import java.io.File
import javax.swing.Icon

/**
 * 代码补全。
 *
 * 候选**不是**插件算的：把光标位置和整篇文本交给语言服务器（[RsfLspClient]），
 * 用的是编译器那份权威实现——语法、作用域、import 展开都不会走样。
 *
 * 服务器常驻，所以这条路只剩一次管道往返；不再像以前那样每次补全起一个 JVM。
 */
class RsfCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), RsfCompletionProvider())
    }
}

class RsfCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        if (!RsfSettings.getInstance().state.completion) return

        val file = parameters.originalFile as? RsfPsiFile ?: return
        val virtualFile = file.virtualFile ?: return
        val command = RsfLauncher.detect(file.project) ?: return
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return

        val document = parameters.editor.document
        val offset = parameters.offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(offset)
        val character = offset - document.getLineStartOffset(lineIndex)

        val client = RsfLspService.client(command, baseDir) ?: return
        val items = client.complete(RsfLspClient.toUri(virtualFile.path), document.text, lineIndex, character)
        for (item in items) {
            result.addElement(
                LookupElementBuilder.create(item.label)
                    .withTypeText(item.detail, true)
                    .withIcon(iconOf(item.kind)),
            )
        }
    }

    /** 只给有把握的几种配图标；认不出来的不给图标，比给错图标强。 */
    private fun iconOf(kind: String): Icon? = when (kind) {
        "function" -> AllIcons.Nodes.Method
        "method" -> AllIcons.Nodes.Method
        "variable" -> AllIcons.Nodes.Variable
        "field" -> AllIcons.Nodes.Field
        "class" -> AllIcons.Nodes.Class
        "type" -> AllIcons.Nodes.Type
        else -> null
    }
}
