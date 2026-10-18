package org.rainstarfox.idea

import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider

/**
 * 样板（live template）：`fn` `lam` `class` `itf` `enum` `main` `for` `while` `switch` `try`。
 *
 * 走 IDEA 原生的模板机制，而不是自己拦补全——这样 `Tab` 跳占位符、`${x}` 改名、
 * 「Settings → Live Templates」里改默认值全都是免费得到的。
 *
 * 模板本体在 [liveTemplates/rainStarfox.xml]（资源路径不带 `.xml`）。
 */
class RsfLiveTemplates : DefaultLiveTemplatesProvider {

    override fun getDefaultLiveTemplateFiles(): Array<String> = arrayOf("/liveTemplates/rainStarfox")

    override fun getHiddenLiveTemplateFiles(): Array<String>? = null
}
