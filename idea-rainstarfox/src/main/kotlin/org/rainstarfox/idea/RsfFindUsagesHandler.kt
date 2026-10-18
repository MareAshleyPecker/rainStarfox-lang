package org.rainstarfox.idea

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.io.File

/**
 * 让 IDEA 自带的 **Find Usages（Alt+F7）** 直接查 rsf 的引用。
 *
 * 平台查引用走 `ReferencesSearch`，那套要求 PSI 里有 `PsiReference`；这个插件的 PSI 是扁平的
 * 词法树，没有引用对象。好在 `FindUsagesHandler.processElementUsages` 是可以整个接管的：
 * 我们问语言服务器（`textDocument/references`），把结果包成 `UsageInfo` 塞回给平台，
 * 于是结果落在标准的 Find 工具窗里——分组、跳转、导出都和 Java 一样。
 */
class RsfFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        val project = element.project
        val file = element.containingFile as? RsfPsiFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        val command = RsfLauncher.detect(project) ?: return false
        val baseDir = virtualFile.parent?.path?.let { File(it) } ?: return false
        val client = RsfLspService.client(command, baseDir) ?: return false

        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return false
        val offset = element.textRange.startOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val character = offset - document.getLineStartOffset(line)
        val usages = client.references(RsfLspClient.toUri(virtualFile.path), document.text, line, character)
        if (usages.isEmpty()) return true

        val psiManager = PsiManager.getInstance(project)
        for (usage in usages) {
            val target = LocalFileSystem.getInstance().refreshAndFindFileByPath(usage.path) ?: continue
            val psiFile: PsiFile = psiManager.findFile(target) ?: continue
            val targetDocument = FileDocumentManager.getInstance().getDocument(target) ?: continue
            val targetLine = usage.line.coerceIn(0, maxOf(0, targetDocument.lineCount - 1))
            val start = (targetDocument.getLineStartOffset(targetLine) + usage.character)
                .coerceIn(0, targetDocument.getLineEndOffset(targetLine))
            val length = wordLengthAt(targetDocument.text, start, usage.character)
            val info = UsageInfo(psiFile, start, length)
            if (!processor.process(info)) return false
        }
        return true
    }

    /** 引用处的标识符长度；拿不到就按 1 个字符算（至少能定位）。 */
    private fun wordLengthAt(text: String, start: Int, column: Int): Int {
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
        val length = end - start
        if (length > 0) return length
        return if (column >= 0 && start + column < text.length) 1 else 1
    }
}

class RsfFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = element.containingFile is RsfPsiFile

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        RsfFindUsagesHandler(element)
}

/** Find 工具窗里显示的类型名（「函数」「类」…）；词法树没有声明信息，按关键字猜。 */
class RsfFindUsagesProvider : FindUsagesProvider {

    override fun canFindUsagesFor(element: PsiElement): Boolean = element.containingFile is RsfPsiFile

    override fun getHelpId(element: PsiElement): String = "rainStarfox.reference"

    override fun getType(element: PsiElement): String = when (previousKeyword(element)) {
        "fn" -> "函数"
        "class" -> "类"
        "interface" -> "接口"
        "enum" -> "枚举"
        "var", "imutvar" -> "变量"
        else -> "声明"
    }

    override fun getDescriptiveName(element: PsiElement): String = element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text

    /** 往前找最近的关键字，够用来区分「函数 / 类 / 变量」。 */
    private fun previousKeyword(element: PsiElement): String? {
        var node = element.prevSibling
        var guard = 0
        while (node != null && guard < 12) {
            val text = node.text.trim()
            if (text.isNotEmpty()) return text
            node = node.prevSibling
            guard++
        }
        return null
    }
}
