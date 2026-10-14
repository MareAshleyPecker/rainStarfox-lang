package org.rainstarfox.idea

import com.intellij.psi.tree.IElementType

/** 一个词法单元的种类。做法和编译器前端的 TokenKind 一一对应，方便对照排错。 */
class RsfTokenType(debugName: String) : IElementType(debugName, RsfLanguage) {
    override fun toString(): String = "rainStarfox." + super.toString()
}

/**
 * 所有词法单元。分类比编译器前端细一点：多分出了「函数名」「函数调用」「类型关键字」
 * 这几种，纯粹是为了让颜色区分得开，语义上没有影响。
 */
object RsfTokens {

    // ---- 注释与空白
    val LINE_COMMENT = RsfTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = RsfTokenType("BLOCK_COMMENT")

    // ---- 字面量
    val NUMBER = RsfTokenType("NUMBER")
    val STRING = RsfTokenType("STRING")

    /** 字符字面量 `'a'` / `'\n'`（阶段 16）。颜色跟字符串一样，只是词法上分得开。 */
    val CHARACTER = RsfTokenType("CHARACTER")

    // ---- 名字
    val IDENTIFIER = RsfTokenType("IDENTIFIER")
    val FUNCTION_DECLARATION = RsfTokenType("FUNCTION_DECLARATION")
    val FUNCTION_CALL = RsfTokenType("FUNCTION_CALL")
    val BUILTIN = RsfTokenType("BUILTIN")

    // ---- 关键字
    val KEYWORD = RsfTokenType("KEYWORD")

    /** `public` / `private`：权限修饰词，单独一色好看些。 */
    val MODIFIER = RsfTokenType("MODIFIER")

    /** `if` `else` `while` `for` `goto` `break` `continue` `rtn` `return`。 */
    val CONTROL_KEYWORD = RsfTokenType("CONTROL_KEYWORD")

    /** `int` `long` `double` `float` `string` `bool` `void` `byte` `char`。 */
    val TYPE_KEYWORD = RsfTokenType("TYPE_KEYWORD")

    /**
     * **用户定义的类名**被当类型用的位置（阶段 19 的后缀类型标注 `var p : Point`、
     * 参数表里的 `p : Point` / `Point p`、返回子句 `-> Point`）。
     *
     * 词法上它跟普通标识符没区别，是靠位置认出来的；配色跟 [TYPE_KEYWORD] 走同一份
     * 颜色键（都是「类型」），所以用户不会觉得这是两种东西。
     */
    val TYPE_NAME = RsfTokenType("TYPE_NAME")

    // ---- 符号
    val OPERATION = RsfTokenType("OPERATION")
    val LPAREN = RsfTokenType("LPAREN")
    val RPAREN = RsfTokenType("RPAREN")
    val LBRACE = RsfTokenType("LBRACE")
    val RBRACE = RsfTokenType("RBRACE")
    val LBRACKET = RsfTokenType("LBRACKET")
    val RBRACKET = RsfTokenType("RBRACKET")
    val COMMA = RsfTokenType("COMMA")
    val SEMICOLON = RsfTokenType("SEMICOLON")
    val DOT = RsfTokenType("DOT")

    // ---- 杂项
    val BAD_CHARACTER = RsfTokenType("BAD_CHARACTER")

    val COMMENTS = setOf(LINE_COMMENT, BLOCK_COMMENT)
    val STRINGS = setOf(STRING)

    /** 引号里包着的字面量：字符串与字符（编辑器判断「光标在字面量里」用）。 */
    val QUOTED = setOf(STRING, CHARACTER)
}

/** 关键字表：和 Lexer.kt 里的 KEYWORDS 保持一致，多一个少一个都会让高亮串味。 */
object RsfKeywords {

    // 阶段 20：`override` 是可选的校验修饰词（和 ControlType/static 同族，只写在 fun 前，
    // 两种顺序都收：`override public fun` / `public override fun`）
    val MODIFIERS = setOf("public", "protected", "private", "static", "override")

    val CONTROL = setOf(
        "fun", "rtn", "return", "if", "else", "while", "for", "in",
        "break", "continue", "goto", "import", "module",
        // 阶段 6a 的 pkg —— 这张表一直漏着它，于是 `pkg demo.util` 里的 pkg
        // 在编辑器里跟普通名字一个颜色。和 Lexer.KEYWORDS 对齐时补上
        "pkg",
        "var", "imutvar", "let", "true", "false", "null",
        // 阶段 12：类
        "class", "new", "this",
        // 阶段 14：错误处理（漏了一阵，和 EditorSupport.KEYWORDS、
        // vscode 的 tmLanguage 三处必须一致）
        "fail", "try", "catch",
        // 阶段 18：次构造
        "constructor",
        // 阶段 20：接口声明（`@interface` 是阶段 21 的事，先不列）
        "interface",
    )

    /**
     * 类型关键字。**必须和编译器前端的 Lexer.KEYWORDS 对齐**（多一个少一个都会让高亮串味）。
     * 阶段 16 加上了 `byte` / `char`，同一个列表在另外两处也有一份：
     * 编译器自己的补全表（EditorSupport.TYPES）和 VS Code 的 tmLanguage。
     */
    val TYPES = setOf(
        "void", "int", "long", "float", "double", "string", "str", "bool", "boolean",
        "byte", "char",
    )

    val ALL: Set<String> = MODIFIERS + CONTROL + TYPES

    /**
     * 内置函数，由解释器/字节码后端直接实现，没有对应的 rsf 函数体。
     *
     * 阶段 16 起多了一批（`toByte` / `charToInt` / `parseInt` / `isDigit`…），
     * 它们和 `print` / `println` 一样不该被当成「函数调用」上色。
     * 这张表要和编译器的 `Builtins` 对齐。
     */
    val BUILTINS = setOf(
        "print", "println",
        "toByte", "toChar", "charToInt", "intToString", "parseInt",
        "isDigit", "isLetter", "isWhitespace", "toUpper", "toLower",
    )
}
