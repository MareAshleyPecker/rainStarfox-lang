package org.rainstarfox.idea

import com.intellij.lang.Language

/**
 * rainStarfox 语言本体。
 *
 * 用 `object`（Kotlin 单例）而不是 class：IDEA 要求同一门语言在 LanguageRegistry 里
 * 只注册一次，单例天然满足。这个对象由 [RsfFileType] 间接实例化（平台加载 fileType
 * 扩展点时会构造它），所以不需要在 plugin.xml 里单独注册语言。
 */
object RsfLanguage : Language("rainStarfox") {
    override fun getDisplayName(): String = "rainStarfox"

    override fun isCaseSensitive(): Boolean = true
}
