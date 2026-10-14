package org.rainstarfox.idea

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** `.rsf` 文件类型。`fieldName="INSTANCE"` 让平台直接取这个单例。 */
object RsfFileType : LanguageFileType(RsfLanguage) {

    val ICON: Icon = IconLoader.getIcon("/icons/rsf.svg", RsfFileType::class.java)

    override fun getName(): String = "rainStarfox File"

    override fun getDescription(): String = "rainStarfox 源文件"

    override fun getDefaultExtension(): String = "rsf"

    override fun getIcon(): Icon = ICON
}
