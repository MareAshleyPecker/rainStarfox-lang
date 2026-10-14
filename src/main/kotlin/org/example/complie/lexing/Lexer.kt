package org.example.complie.lexing

import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile

/**
 * 可变变量/字段的老关键字（2026-10-11 起唯一写法是 `var`）。
 *
 * 它**不**在 [KEYWORDS] 里——关键字表要与 rsf 版自举词法器（`rsfSrc/bootstrap/Lexer.rsf`
 * 的 `keywordKind`）**逐条对齐**，条数不能变（45 项）。所以拦它靠的是
 * [Lexer.readIdentOrKeyword] 里一条独立的判断，rsf 版那边也写了同一条。
 */
internal const val LEGACY_VAR_KEYWORD: String = "varis"

/** 老写法 `varis` 的统一报错文案：说清改成了什么、以及一句话能照抄的改写。 */
internal const val LEGACY_VAR_MESSAGE: String = "'varis' 已改名为 'var'：请写成 'var x : int = 0'"

private val KEYWORDS: Map<String, TokenKind> = buildMap {
    put("fun", TokenKind.KW_FUN)
    put("rtn", TokenKind.KW_RTN)
    put("return", TokenKind.KW_RETURN)
    put("import", TokenKind.KW_IMPORT)
    put("module", TokenKind.KW_MODULE)
    put("pkg", TokenKind.KW_PKG)
    put("var", TokenKind.KW_VAR)
    put("imutvar", TokenKind.KW_IMUTVAR)
    put("let", TokenKind.KW_LET)
    put("public", TokenKind.KW_PUBLIC)
    put("private", TokenKind.KW_PRIVATE)
    put("protected", TokenKind.KW_PROTECTED)
    put("static", TokenKind.KW_STATIC)
    // 阶段 20 追加：可选的 override（语义照 Java 的 @Override）
    put("override", TokenKind.KW_OVERRIDE)
    put("class", TokenKind.KW_CLASS)
    // 阶段 19c：枚举声明与枚举分派
    put("enum", TokenKind.KW_ENUM)
    put("interface", TokenKind.KW_INTERFACE)
    put("switch", TokenKind.KW_SWITCH)
    put("case", TokenKind.KW_CASE)
    put("new", TokenKind.KW_NEW)
    put("this", TokenKind.KW_THIS)
    // 阶段 18：构造器（主构造写在类头，次构造写 constructor(...)）
    put("constructor", TokenKind.KW_CONSTRUCTOR)
    put("if", TokenKind.KW_IF)
    put("else", TokenKind.KW_ELSE)
    put("while", TokenKind.KW_WHILE)
    put("for", TokenKind.KW_FOR)
    put("in", TokenKind.KW_IN)
    put("break", TokenKind.KW_BREAK)
    put("continue", TokenKind.KW_CONTINUE)
    put("goto", TokenKind.KW_GOTO)
    // 阶段 14：错误返回与 try/catch
    put("fail", TokenKind.KW_FAIL)
    put("try", TokenKind.KW_TRY)
    put("catch", TokenKind.KW_CATCH)
    put("true", TokenKind.KW_TRUE)
    put("false", TokenKind.KW_FALSE)
    put("null", TokenKind.KW_NULL)
    // 类型关键字（str / boolean 是别名）
    put("void", TokenKind.KW_VOID)
    put("int", TokenKind.KW_INT)
    put("long", TokenKind.KW_LONG)
    put("float", TokenKind.KW_FLOAT)
    put("double", TokenKind.KW_DOUBLE)
    put("string", TokenKind.KW_STRING)
    put("str", TokenKind.KW_STRING)
    put("bool", TokenKind.KW_BOOL)
    put("boolean", TokenKind.KW_BOOL)
    // 阶段 16：byte / char
    put("byte", TokenKind.KW_BYTE)
    put("char", TokenKind.KW_CHAR)
}

/**
 * rainStarfox 词法分析器。把 .rsf 源码切成 [Token] 流。
 *
 * 支持：双斜杠行注释、斜杠-星号块注释、十进制整数 / long / float / double
 * 字面量、带转义的字符串字面量。
 *
 * 注意：本文件的注释里不能出现块的开始定界符，Kotlin 的块注释是会嵌套的。
 */
class Lexer(private val source: SourceFile) {

    private val text: String = source.text
    private var pos = 0
    private var line = 1
    private var column = 1

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            skipTrivia()
            if (pos >= text.length) {
                out.add(Token(TokenKind.EOF, "<eof>", SourceLine(line, column)))
                return out
            }
            val at = SourceLine(line, column)
            val c = text[pos]
            when {
                c.isDigit() -> out.add(readNumber(at))
                c == '"' -> out.add(readString(at))
                c == '\'' -> out.add(readCharLiteral(at))
                isIdentStart(c) -> out.add(readIdentOrKeyword(at))
                else -> out.add(readPunct(at))
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun advance(): Char {
        val c = text[pos++]
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun peek(offset: Int = 0): Char? {
        val i = pos + offset
        return if (i < text.length) text[i] else null
    }

    private fun skipTrivia() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                c == '/' && peek(1) == '/' -> {
                    while (pos < text.length && text[pos] != '\n') advance()
                }
                c == '/' && peek(1) == '*' -> {
                    val start = SourceLine(line, column)
                    advance(); advance()
                    var closed = false
                    while (pos < text.length) {
                        if (text[pos] == '*' && peek(1) == '/') {
                            advance(); advance(); closed = true; break
                        }
                        advance()
                    }
                    if (!closed) {
                        throw RsfError("lexer", "块注释没有闭合（缺少 */）", start.line, start.column, source)
                    }
                }
                else -> return
            }
        }
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** 老写法 `varis` 的报错（位置指向那个词本身，与 rsf 版 `reportError` 同口径）。 */
    private fun legacyVarError(at: SourceLine): RsfError =
        RsfError("lexer", LEGACY_VAR_MESSAGE, at.line, at.column, source)

    private fun readIdentOrKeyword(at: SourceLine): Token {
        val sb = StringBuilder()
        while (pos < text.length && isIdentPart(text[pos])) sb.append(advance())
        val word = sb.toString()
        // 旧名 `varis`（2026-10-11 改名为 `var`）**不再是关键字**，所以它落在
        // 关键字表外面——这里是唯一一处能拦住它、并给出改写建议的地方。
        // 拦在词法层而不是语法层：`varis` 可以出现在任何声明位置（顶层 / 类体字段 /
        // 局部 / for 初始化），逐个入口去查会漏；而且 `var` 已经是关键字，
        // 再让 `varis` 当合法标识符只会让两套写法并存。
        if (word == LEGACY_VAR_KEYWORD) throw legacyVarError(at)
        val kind = KEYWORDS[word]
        return if (kind == null) {
            Token(TokenKind.IDENT, word, at)
        } else {
            val literal = when (kind) {
                TokenKind.KW_TRUE -> true
                TokenKind.KW_FALSE -> false
                else -> null
            }
            Token(kind, word, at, literal)
        }
    }

    private fun readNumber(at: SourceLine): Token {
        val sb = StringBuilder()
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '_')) sb.append(advance())

        var hasDot = false
        if (peek() == '.' && peek(1)?.isDigit() == true) {
            hasDot = true
            sb.append(advance()) // '.'
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '_')) sb.append(advance())
        }

        var suffix = '\u0000'
        val next = peek()
        if (next != null && (next == 'l' || next == 'L' || next == 'f' || next == 'F' || next == 'd' || next == 'D')) {
            suffix = next.lowercaseChar()
            advance()
        }

        val raw = sb.toString().replace("_", "")
        val lexeme = raw + if (suffix == '\u0000') "" else suffix.toString()

        return when (suffix) {
            'l' -> Token(TokenKind.LONG_LIT, lexeme, at, raw.toLongOrNull() ?: overflow(raw, at, "long"))
            'f' -> Token(TokenKind.FLOAT_LIT, lexeme, at, raw.toFloatOrNull() ?: overflow(raw, at, "float"))
            'd' -> Token(TokenKind.DOUBLE_LIT, lexeme, at, raw.toDoubleOrNull() ?: overflow(raw, at, "double"))
            '\u0000' -> if (hasDot) {
                Token(TokenKind.DOUBLE_LIT, lexeme, at, raw.toDoubleOrNull() ?: overflow(raw, at, "double"))
            } else {
                val v = raw.toIntOrNull()
                    ?: throw RsfError(
                        "lexer",
                        "整数字面量 $raw 超出 int 范围，请加 L 后缀写成 long",
                        at.line, at.column, source,
                    )
                Token(TokenKind.INT_LIT, lexeme, at, v)
            }
            else -> throw IllegalStateException("unreachable suffix $suffix")
        }
    }

    private fun overflow(raw: String, at: SourceLine, type: String): Nothing =
        throw RsfError("lexer", "字面量 $raw 无法解析为 $type", at.line, at.column, source)

    /**
     * 字符串字面量。**允许跨行**——Go 风格的 import 路径可以写成
     * ```
     * import "
     *     java.util
     * "
     * ```
     * 代价是漏了收尾引号时会一路吃到文件末尾，所以初值位置会记住、报错指向开引号。
     */
    private fun readString(at: SourceLine): Token {
        advance() // 开引号
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) {
                throw RsfError("lexer", "字符串没有闭合（缺少 \"）", at.line, at.column, source)
            }
            val c = advance()
            when (c) {
                '"' -> break
                '\\' -> {
                    if (pos >= text.length) {
                        throw RsfError("lexer", "字符串没有闭合（缺少 \"）", at.line, at.column, source)
                    }
                    val e = advance()
                    // 未识别的转义按字面反斜杠算：路径里到处是 `.\xxx\yyy.rsf`，
                    // 为这个把整份源码判成词法错误不划算
                    sb.append(
                        when (e) {
                            'n' -> "\n"
                            't' -> "\t"
                            'r' -> "\r"
                            '0' -> "\u0000"
                            '\\' -> "\\"
                            '"' -> "\""
                            '\'' -> "'"
                            else -> "\\$e"
                        },
                    )
                }
                else -> sb.append(c)
            }
        }
        return Token(TokenKind.STRING_LIT, sb.toString(), at, sb.toString())
    }

    /**
     * 字符字面量（阶段 16）：单引号里正好一个字符——`'a'`、`'\n'`、`'\u0041'`。
     *
     * 和字符串字面量（[readString]）故意有三点不同：
     *
     * - **只能有一个字符**：空 `''` 和多字符 `'ab'` 都在这里报错（字符串里多字符是正常的）；
     * - **不允许跨行**：没等到收尾引号就撞上换行／文件末尾时立刻报「没有闭合」。
     *   字符串允许跨行是为了 Go 风格的 import 路径，字符字面量没这个需求，
     *   漏一个引号让它一路吃到文件末尾只会把报错位置指到很远的地方；
     * - **不认识的转义直接报错**：字符串那边为了迁就 Windows 路径（`.\lib\x.rsf`）
     *   把 `\x` 当字面反斜杠算，字符字面量里没有这种顾虑，「写错了」比「按字面值算」有用。
     *
     * [Token.lexeme] 存的是**源码里的原文**（`'\n'` 而不是那个真的换行符），
     * 这样报错和反汇编里的显示都还是人写的样子。
     */
    private fun readCharLiteral(at: SourceLine): Token {
        val startPos = pos
        advance() // 开引号

        if (pos >= text.length || text[pos] == '\n') {
            throw RsfError("lexer", "字符字面量没有闭合（缺少 '）", at.line, at.column, source)
        }
        if (text[pos] == '\'') {
            advance()
            throw RsfError(
                "lexer",
                "字符字面量不能是空的（''）——里面要正好放一个字符，例如 'a'、'\\n'、'\\u0041'",
                at.line, at.column, source,
            )
        }

        val value: Char = if (text[pos] == '\\') {
            advance()
            readCharEscape(at)
        } else {
            advance()
        }

        if (pos < text.length && text[pos] != '\'' && text[pos] != '\n') {
            throw RsfError(
                "lexer",
                "字符字面量只能放一个字符，'$value' 后面还有多的字符。" +
                    "要写多个字符请用双引号字符串（\"$value...\"）",
                at.line, at.column, source,
            )
        }
        if (pos >= text.length || text[pos] != '\'') {
            throw RsfError("lexer", "字符字面量没有闭合（缺少 '）", at.line, at.column, source)
        }
        advance() // 收尾引号
        return Token(TokenKind.CHAR_LIT, text.substring(startPos, pos), at, value)
    }

    /** 字符字面量里的一个转义序列，返回它代表的那个字符。 */
    private fun readCharEscape(at: SourceLine): Char {
        if (pos >= text.length || text[pos] == '\n') {
            throw RsfError("lexer", "字符字面量没有闭合（缺少 '）", at.line, at.column, source)
        }
        val e = advance()
        return when (e) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '0' -> '\u0000'
            '\\' -> '\\'
            '\'' -> '\''
            '"' -> '"'
            'u' -> readCharUnicodeEscape(at)
            else -> throw RsfError(
                "lexer",
                "字符字面量里不认识的转义 '\\$e'。" +
                    "认得的有：\\n \\t \\r \\0 \\\\ \\' \\\" 和四位十六进制的 \\uXXXX",
                at.line, at.column, source,
            )
        }
    }

    /** `'\u0041'` → `'A'`：四位十六进制，少一位、多一位、混进别的字符都报错。 */
    private fun readCharUnicodeEscape(at: SourceLine): Char {
        val sb = StringBuilder(4)
        while (sb.length < 4) {
            val c = if (pos < text.length) text[pos] else null
            if (c == null || c == '\n' || c == '\'' || !isHexDigit(c)) {
                throw RsfError(
                    "lexer",
                    "\\u 转义要写满四位十六进制数字，例如 '\\u0041'（大写 A）",
                    at.line, at.column, source,
                )
            }
            advance()
            sb.append(c)
        }
        return sb.toString().toInt(16).toChar()
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun readPunct(at: SourceLine): Token {
        val c = advance()
        fun one(kind: TokenKind) = Token(kind, kind.text, at)
        fun two(kind: TokenKind): Token {
            advance()
            return Token(kind, kind.text, at)
        }
        return when (c) {
            '(' -> one(TokenKind.LPAREN)
            ')' -> one(TokenKind.RPAREN)
            '{' -> one(TokenKind.LBRACE)
            '}' -> one(TokenKind.RBRACE)
            // 阶段 13：数组（类型后缀 `int[]`、下标 `a[0]`、创建 `new int[5]`）
            '[' -> one(TokenKind.LBRACKET)
            ']' -> one(TokenKind.RBRACKET)
            ',' -> one(TokenKind.COMMA)
            ';' -> one(TokenKind.SEMI)
            '?' -> if (peek() == ':') two(TokenKind.ELVIS) else one(TokenKind.QUESTION)
            '.' -> if (peek() == '.') two(TokenKind.RANGE) else one(TokenKind.DOT)
            ':' -> if (peek() == ':') two(TokenKind.DOUBLE_COLON) else one(TokenKind.COLON)
            '+' -> one(TokenKind.PLUS)
            '*' -> one(TokenKind.STAR)
            '/' -> one(TokenKind.SLASH)
            '\\' -> one(TokenKind.BACKSLASH)
            '%' -> one(TokenKind.PERCENT)
            '&' -> if (peek() == '&') two(TokenKind.ANDAND) else one(TokenKind.AMP)
            // 逻辑或是 `||`；rsf 没有按位或，孤零零一个 `|` 直接报清楚，
            // 别让它以「不认识的字符」的样子出现（那个提示对用户没有指导性）
            '|' -> if (peek() == '|') {
                two(TokenKind.OROR)
            } else {
                throw RsfError(
                    "lexer",
                    "逻辑或要写成两个竖线 '||'（rsf 里没有按位或，单独一个 '|' 不是运算符）",
                    at.line, at.column, source,
                )
            }
            '-' -> if (peek() == '>') two(TokenKind.ARROW) else one(TokenKind.MINUS)
            '=' -> if (peek() == '=') two(TokenKind.EQ) else one(TokenKind.ASSIGN)
            '!' -> if (peek() == '=') two(TokenKind.NE) else one(TokenKind.BANG)
            '<' -> if (peek() == '=') two(TokenKind.LE) else one(TokenKind.LT)
            '>' -> if (peek() == '=') two(TokenKind.GE) else one(TokenKind.GT)
            else -> throw RsfError("lexer", "不认识的字符 '$c'", at.line, at.column, source)
        }
    }
}
