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
 * 候选**不是**插件自己算的，而是把光标位置交给编译器的 `rsf complete`——语法、
 * 作用域、import 展开这些规则编译器已经有一份权威实现，插件照着再写一遍必然走样。
 * 代价是每次补全要起一次 JVM（两三百毫秒），实测在补全菜单弹出时感觉不到。
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

        val document = parameters.editor.document
        val offset = parameters.offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(offset)
        val line = lineIndex + 1
        val column = offset - document.getLineStartOffset(lineIndex) + 1
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return

        val items = RsfCli.complete(command, virtualFile.path, document.text, line, column, baseDir)
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
        "variable" -> AllIcons.Nodes.Variable
        "field" -> AllIcons.Nodes.Field
        "class" -> AllIcons.Nodes.Class
        else -> null
    }
}
