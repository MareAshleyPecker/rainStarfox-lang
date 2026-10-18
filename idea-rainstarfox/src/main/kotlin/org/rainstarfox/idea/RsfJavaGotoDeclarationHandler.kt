package org.rainstarfox.idea

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.search.GlobalSearchScope

/**
 * Ctrl+点落在 **Java 的名字** 上时，交给 IDEA 的 Java 支持去跳。
 *
 * rsf 调 Java 的写法就是 Java 式的（`Math.max`、`Integer.parseInt`、`System.out.println`，
 * `import java.lang.Math`），但那些类不在 rsf 的符号表里——语言服务器的
 * `textDocument/definition` 只能指到 rsf 源码。所以这里补一段：按 Java 的解析习惯列候选
 * 全限定名，再用 `JavaPsiFacade` 查。查到就跳源码/反编译，和写 Java 时一样。
 *
 * 只有装了 Java 插件的 IDE 才注册这个类（`plugin.xml` 里 optional depends + 独立描述文件），
 * 所以 CLion / GoLand 那种不带 Java 支持的 IDE 不会加载它。
 */
class RsfJavaGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val psiFile = sourceElement?.containingFile as? RsfPsiFile ?: return null
        val project = psiFile.project
        val text = editor.document.immutableCharSequence.toString()
        val range = RsfJavaNames.identifierAt(text, offset) ?: return null
        val token = text.substring(range.first, range.last + 1)
        val qualifier = RsfJavaNames.qualifierBefore(text, range.first)

        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val cache = HashMap<String, PsiClass?>()
        fun findClass(name: String): PsiClass? = cache.getOrPut(name) { facade.findClass(name, scope) }

        // 1) 光标处是类名：`Math`、`java.lang.Math`、或 import 进来的名字
        for (name in RsfJavaNames.classCandidates(token, qualifier, text)) {
            findClass(name)?.let { return arrayOf(it) }
        }

        // 2) 光标处是成员：`Math.max`、`System.out.println`（限定部分可能是字段链）
        val owner = resolveOwner(qualifier, text, ::findClass) ?: return null
        val methods = owner.findMethodsByName(token, true)
        if (methods.isNotEmpty()) return Array<PsiElement>(methods.size) { methods[it] }
        owner.findFieldByName(token, true)?.let { return arrayOf(it) }
        return null
    }

    /** `System.out` 这种链：先解析类，再把字段的类型一层层解开。 */
    private fun resolveOwner(qualifier: String, fileText: String, findClass: (String) -> PsiClass?): PsiClass? {
        if (qualifier.isEmpty()) return null
        findClass(qualifier)?.let { return it }
        findClass("java.lang.$qualifier")?.let { return it }

        val parts = qualifier.split('.')
        var owner = findClass(parts[0])
            ?: findClass("java.lang.${parts[0]}")
            ?: RsfJavaNames.simpleClassCandidates(parts[0], fileText).firstNotNullOfOrNull { findClass(it) }
            ?: return null
        for (part in parts.drop(1)) {
            val field: PsiField = owner.findFieldByName(part, true) ?: return null
            owner = (field.type as? PsiClassType)?.resolve() ?: return null
        }
        return owner
    }
}
