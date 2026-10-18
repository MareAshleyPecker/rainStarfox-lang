package org.rainstarfox.idea

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** Setting → Editor → Color Scheme → rainStarfox：让用户能逐项改颜色。 */
class RsfColorSettingsPage : ColorSettingsPage {

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "rainStarfox"

    override fun getIcon(): Icon = RsfFileType.ICON

    override fun getHighlighter(): SyntaxHighlighter = RsfSyntaxHighlighter()

    override fun getDemoText(): String = DEMO

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    companion object {
        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor("关键字", RsfTextAttributes.KEYWORD),
            AttributesDescriptor("权限修饰词（pub/prv）", RsfTextAttributes.MODIFIER),
            AttributesDescriptor("类型名（类型关键字与用户类名）", RsfTextAttributes.TYPE),
            AttributesDescriptor("函数定义名", RsfTextAttributes.FUNCTION_DECLARATION),
            AttributesDescriptor("函数调用", RsfTextAttributes.FUNCTION_CALL),
            AttributesDescriptor("内置函数（print/println）", RsfTextAttributes.BUILTIN),
            AttributesDescriptor("标识符", RsfTextAttributes.IDENTIFIER),
            AttributesDescriptor("数字", RsfTextAttributes.NUMBER),
            AttributesDescriptor("字符串", RsfTextAttributes.STRING),
            AttributesDescriptor("行注释", RsfTextAttributes.LINE_COMMENT),
            AttributesDescriptor("块注释", RsfTextAttributes.BLOCK_COMMENT),
            AttributesDescriptor("运算符", RsfTextAttributes.OPERATION),
            AttributesDescriptor("圆括号", RsfTextAttributes.PARENTHESES),
            AttributesDescriptor("花括号", RsfTextAttributes.BRACES),
            AttributesDescriptor("方括号", RsfTextAttributes.BRACKETS),
            AttributesDescriptor("逗号", RsfTextAttributes.COMMA),
            AttributesDescriptor("分号", RsfTextAttributes.SEMICOLON),
            AttributesDescriptor("点号", RsfTextAttributes.DOT),
            AttributesDescriptor("非法字符", RsfTextAttributes.BAD_CHARACTER),
        )

        private val DEMO = """
            // rainStarfox 示例（这段同时用来预览配色）
            pkg demo.util

            import java.lang.Math

            pub class Point(x0 : int, y0 : int) {
                var x : int = x0
                var y : int = y0

                constructor() { this(0, 0) }

                pub fn sum() :: int { rtn x + y }
            }

            fn main() -> void {
                var who : string = "rainStarfox"   // 可变；类型写在名字后面
                imutvar pi : double = Math.PI        // 不可变
                var p : Point = Point(3, 4)        // 构造对象时 new 可以省
                println("hi " + who + "，sum = " + p.sum())

                var xs : int[] = new int[3]
                for (i in 0..2 step 1) { xs[i] = i * i }

                var c : char = 'r'                 // 单引号是字符字面量
                try {
                    fail "有 " + xs.length + " 个元素"
                } catch (e : string) {               // catch 变量也是冒号写法
                    println(c + e)
                }
                rtn
            }
        """.trimIndent()
    }
}
