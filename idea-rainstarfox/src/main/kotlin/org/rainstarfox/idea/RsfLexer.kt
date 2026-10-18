package org.rainstarfox.idea

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.lexer.LexerPositionImpl
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * rainStarfox 词法分析器（给编辑器用的那一份）。
 *
 * 和编译器前端的 `Lexer.kt` 是两套独立实现——故意不共用：前端那份的首要目标是
 * 「报错准确」，这份的首要目标是「永不抛异常」。编辑器会在用户打字打到一半时
 * 拿半个文件来切词，这里碰到任何怪东西都只标一个 [RsfTokens.BAD_CHARACTER] 就完事。
 *
 * 实现方式很朴素：`start()` 时把整段文本一次切完，之后 `advance()` 只是在列表上
 * 挪指针。rsf 源文件都很小，这么做换来的是「多行字符串、未闭合块注释」这类跨行
 * 结构永远不会因为增量起点的不同而算错。
 */
class RsfLexer : Lexer() {

    private class Tok(val start: Int, val end: Int, val type: IElementType)

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokens: List<Tok> = emptyList()
    private var index = 0

    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        this.tokens = scan(buffer, startOffset, endOffset)
        this.index = 0
        advance()
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    /** 词法器本身没有状态，这个位置只用来支持增量重切。 */
    override fun getCurrentPosition(): LexerPosition = LexerPositionImpl(tokenStart, 0)

    override fun restore(position: LexerPosition) {
        val offset = position.offset
        val next = tokens.indexOfFirst { it.end > offset }
        index = if (next < 0) tokens.size else next
        advance()
    }

    override fun advance() {
        if (index >= tokens.size) {
            tokenType = null
            tokenStart = endOffset
            tokenEnd = endOffset
            return
        }
        val t = tokens[index++]
        tokenStart = t.start
        tokenEnd = t.end
        tokenType = t.type
    }

    // ------------------------------------------------------------------ 切词

    private fun scan(text: CharSequence, from: Int, to: Int): List<Tok> {
        val out = ArrayList<Tok>()
        // 反引号标签名（阶段 28a）在 out 里的下标：classify 那趟不走「类型 / 调用」
        // 的猜测，直接给普通名字色——标签本来就是这个颜色（普通 `loop:` 也是）。
        val backticked = HashSet<Int>()
        var i = from
        while (i < to) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> {
                    val start = i
                    while (i < to && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n')) i++
                    out.add(Tok(start, i, TokenType.WHITE_SPACE))
                }

                c == '/' && i + 1 < to && text[i + 1] == '/' -> {
                    val start = i
                    while (i < to && text[i] != '\n') i++
                    out.add(Tok(start, i, RsfTokens.LINE_COMMENT))
                }

                c == '/' && i + 1 < to && text[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    // 块注释可以跨行，找不到收尾就一直吃到文件末尾——编辑器不该因此报错
                    while (i < to && !(text[i] == '*' && i + 1 < to && text[i + 1] == '/')) i++
                    if (i < to) i += 2
                    out.add(Tok(start, i, RsfTokens.BLOCK_COMMENT))
                }

                c == '"' -> {
                    val start = i
                    i++
                    while (i < to) {
                        val d = text[i]
                        if (d == '\\' && i + 1 < to) {
                            i += 2
                            continue
                        }
                        i++
                        if (d == '"') break
                    }
                    out.add(Tok(start, i, RsfTokens.STRING))
                }

                // 字符字面量（阶段 16）：`'a'`、`'\n'`、`'\u0041'`。
                // 和编译器前端一样**不跨行**：撞上换行就收尾，免得打字打到一半时
                // 一个没收尾的引号把后面半屏都吃掉。空 ''/多字符在编辑器里不算错，
                // 报错交给编译器诊断（这份词法器的首要目标是永不抛异常）。
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < to && text[i] != '\n') {
                        val d = text[i]
                        if (d == '\\' && i + 1 < to && text[i + 1] != '\n') {
                            i += 2
                            continue
                        }
                        i++
                        if (d == '\'') break
                    }
                    out.add(Tok(start, i, RsfTokens.CHARACTER))
                }

                // 反引号标签名（阶段 28a）：`a-b:` 与 goto `a-b`。
                // 只扫到同一行的下一个反引号；没等到收尾就吃到行尾——编辑器这边
                // 永不抛异常（报错交给编译器的「反引号标签名没有收尾」）。
                c == '`' -> {
                    val start = i
                    i++
                    while (i < to && text[i] != '`' && text[i] != '\n') i++
                    if (i < to && text[i] == '`') i++
                    backticked.add(out.size)
                    // token 区间**含**两个反引号：只吃名字的话，那两个引号会各自
                    // 掉进 BAD_CHARACTER，编辑器里就一片红。
                    out.add(Tok(start, i, RsfTokens.IDENTIFIER))
                }

                c.isDigit() -> {
                    val start = i
                    while (i < to && text[i].isDigit()) i++
                    // 小数点：后面还得跟数字，否则是范围运算符 `..`
                    if (i + 1 < to && text[i] == '.' && text[i + 1].isDigit()) {
                        i++
                        while (i < to && text[i].isDigit()) i++
                    }
                    if (i < to && (text[i] == 'e' || text[i] == 'E')) {
                        var j = i + 1
                        if (j < to && (text[j] == '+' || text[j] == '-')) j++
                        if (j < to && text[j].isDigit()) {
                            i = j
                            while (i < to && text[i].isDigit()) i++
                        }
                    }
                    if (i < to && text[i] in "fFdDlL") i++
                    out.add(Tok(start, i, RsfTokens.NUMBER))
                }

                isIdentStart(c) -> {
                    val start = i
                    while (i < to && isIdentPart(text[i])) i++
                    val word = text.subSequence(start, i).toString()
                    out.add(Tok(start, i, keywordType(word)))
                }

                else -> {
                    val op = punctuation(text, i, to)
                    out.add(Tok(i, i + op.length, punctType(op)))
                    i += op.length
                }
            }
        }
        return classify(out, text, backticked)
    }

    /**
     * 把 `fun` 后面的那个名字认出来，顺便把函数调用、内置函数、以及**类型位置上的名字**
     * 标出来。
     *
     * 类型位置（见 [typeNamePositions]）优先于其它分类：`var p : Point` 里的 `Point`
     * 必须上成类型色，而不是普通标识符。
     */
    private fun classify(raw: List<Tok>, text: CharSequence, backticked: Set<Int>): List<Tok> {
        fun lexemeOf(t: Tok): String = text.subSequence(t.start, t.end).toString()
        fun isFun(t: Tok?): Boolean = t != null && t.type == RsfTokens.CONTROL_KEYWORD && lexemeOf(t) == "fn"

        val typeNames = typeNamePositions(raw, text)

        val out = ArrayList<Tok>(raw.size)
        for ((n, t) in raw.withIndex()) {
            if (t.type != RsfTokens.IDENTIFIER) {
                out.add(t)
                continue
            }
            // 反引号标签名（阶段 28a）：位置一旦确定就是标签声明或 goto 的目标，
            // 不参与「类型名 / 函数调用」那套猜测，恒为普通名字色（与 `loop:` 一致）。
            if (n in backticked) {
                out.add(t)
                continue
            }
            val word = lexemeOf(t)
            val p1 = previousIndex(raw, n)
            val p2 = if (p1 >= 0) previousIndex(raw, p1) else -1
            val previous = if (p1 >= 0) raw[p1] else null
            val next = nextIndex(raw, n).let { if (it >= 0) raw[it] else null }

            val type = when {
                // 名字出现在类型位置（阶段 19 的后缀类型标注 / 参数表 / 返回子句）
                n in typeNames -> RsfTokens.TYPE_NAME
                // fun 名字
                isFun(previous) -> RsfTokens.FUNCTION_DECLARATION
                // fun <类型> 名字
                previous?.type == RsfTokens.TYPE_KEYWORD && p2 >= 0 && isFun(raw[p2]) -> RsfTokens.FUNCTION_DECLARATION
                word in RsfKeywords.BUILTINS -> RsfTokens.BUILTIN
                next?.type == RsfTokens.LPAREN -> RsfTokens.FUNCTION_CALL
                else -> RsfTokens.IDENTIFIER
            }
            out.add(Tok(t.start, t.end, type))
        }
        return out
    }

    /**
     * 「这个标识符是个类型名」的记号下标集合。
     *
     * 只认这四种地方——别处的 `:` 另有含义（`if c : 语句`、三目 `? :`、行首标签 `loop:`），
     * 一律当类型会把颜色搞乱：
     *
     * 1. 变量 / 字段的后缀类型标注：`var 名字 : 类型`（阶段 19 起只认这一种写法）；
     * 2. 声明参数表内部（`fun 名字(...)` / `constructor(...)` / `class 名字(...)`）：
     *    Kotlin 风格 `名字 : 类型` 的类型，Java 风格 `类型 名字` 的类型；
     * 3. 返回子句 `-> T` / `:: T` 的 T（`!T` / `?T` 里的 `!` `?` 会被跳过）；
     * 4. Kotlin 风格参数表里，排在 `名字 :` 后面的那个名字（同 2）。
     */
    private fun typeNamePositions(raw: List<Tok>, text: CharSequence): Set<Int> {
        fun lexemeOf(t: Tok): String = text.subSequence(t.start, t.end).toString()
        fun isColon(i: Int): Boolean =
            i >= 0 && raw[i].type == RsfTokens.OPERATION && lexemeOf(raw[i]) == ":"

        val out = HashSet<Int>()

        // ---- 2 / 4：声明参数表内部
        for (range in declarationParamRanges(raw, text)) {
            for (n in range) {
                if (raw[n].type != RsfTokens.IDENTIFIER) continue
                // Kotlin 风格：前一个有效记号是 `:`
                if (isColon(previousIndex(raw, n))) {
                    out.add(n)
                    continue
                }
                // Java 风格：后面还跟着一个名字 / `&<` / `[]`
                val next = nextIndex(raw, n)
                if (next < 0) continue
                val after = raw[next]
                when {
                    after.type == RsfTokens.IDENTIFIER -> out.add(n)
                    after.type == RsfTokens.LBRACKET -> out.add(n)
                    // 泛型 `Box&<int> b`：`&` 在插件里归在 OPERATION
                    after.type == RsfTokens.OPERATION && lexemeOf(after) == "&" -> out.add(n)
                    else -> {}
                }
            }
        }

        // ---- 1：变量 / 字段的 `名字 : 类型`
        for (n in raw.indices) {
            val t = raw[n]
            if (t.type != RsfTokens.CONTROL_KEYWORD) continue
            val w = lexemeOf(t)
            if (w != "var" && w != "imutvar") continue
            val name = nextIndex(raw, n)
            if (name < 0 || raw[name].type != RsfTokens.IDENTIFIER) continue
            val colon = nextIndex(raw, name)
            if (!isColon(colon)) continue
            val type = nextIndex(raw, colon)
            if (type >= 0 && raw[type].type == RsfTokens.IDENTIFIER) out.add(type)
        }

        // ---- 3：返回子句 `-> T` / `:: T`
        for (n in raw.indices) {
            val t = raw[n]
            if (t.type != RsfTokens.OPERATION) continue
            val w = lexemeOf(t)
            if (w != "->" && w != "::") continue
            var j = nextIndex(raw, n)
            // 阶段 14 的错误返回 `-> !T`、可空 `-> ?T`：跳过 `!` / `?`
            while (j >= 0 && raw[j].type == RsfTokens.OPERATION &&
                (lexemeOf(raw[j]) == "!" || lexemeOf(raw[j]) == "?")
            ) {
                j = nextIndex(raw, j)
            }
            if (j >= 0 && raw[j].type == RsfTokens.IDENTIFIER) out.add(j)
        }

        return out
    }

    /**
     * 声明参数表 `(...)` 的**内部**记号下标区间：`fun 名字(...)`、`constructor(...)`、
     * `class 名字(...)`。
     *
     * 关键字与 `(` 之间只可能是：第二个名字（`fun 名字` / `class 名字`）、泛型
     * `&<T, U>`、以及阶段 18 的 MainType 槽位（类型关键字）——别的记号一出现就说明
     * 这不是一个声明头，直接放弃，免得把表达式里的括号当成参数表。
     */
    private fun declarationParamRanges(raw: List<Tok>, text: CharSequence): List<IntRange> {
        fun lexemeOf(t: Tok): String = text.subSequence(t.start, t.end).toString()
        fun isDeclKeyword(t: Tok): Boolean =
            t.type == RsfTokens.CONTROL_KEYWORD &&
                (lexemeOf(t) == "fn" || lexemeOf(t) == "constructor" || lexemeOf(t) == "class")

        // 注：`&` `<` `>` 在插件里没有单独的记号类型，都归在 OPERATION，所以这里
        // 得连字面量一起看——只放过泛型用的那三个，别的运算符一出现就说明不是声明头
        fun isHeaderToken(t: Tok): Boolean = when (t.type) {
            RsfTokens.IDENTIFIER, RsfTokens.TYPE_KEYWORD, RsfTokens.COMMA -> true

            RsfTokens.OPERATION -> lexemeOf(t) == "&" || lexemeOf(t) == "<" || lexemeOf(t) == ">"

            else -> false
        }

        val out = ArrayList<IntRange>()
        for (i in raw.indices) {
            if (!isDeclKeyword(raw[i])) continue

            var j = nextIndex(raw, i)
            while (j >= 0 && isHeaderToken(raw[j])) j = nextIndex(raw, j)
            if (j < 0 || raw[j].type != RsfTokens.LPAREN) continue
            val open = j

            var depth = 0
            var close = -1
            for (k in open until raw.size) {
                when (raw[k].type) {
                    RsfTokens.LPAREN -> depth++
                    RsfTokens.RPAREN -> {
                        depth--
                        if (depth == 0) close = k
                    }

                    else -> {}
                }
                if (close >= 0) break
            }
            if (close > open) out.add((open + 1) until close)
        }
        return out
    }

    private fun previousIndex(raw: List<Tok>, from: Int): Int {
        var i = from - 1
        while (i >= 0) {
            if (raw[i].type != TokenType.WHITE_SPACE) return i
            i--
        }
        return -1
    }

    private fun nextIndex(raw: List<Tok>, from: Int): Int {
        var i = from + 1
        while (i < raw.size) {
            if (raw[i].type != TokenType.WHITE_SPACE) return i
            i++
        }
        return -1
    }

    private fun keywordType(word: String): IElementType = when (word) {
        in RsfKeywords.MODIFIERS -> RsfTokens.MODIFIER
        in RsfKeywords.TYPES -> RsfTokens.TYPE_KEYWORD
        in RsfKeywords.CONTROL -> RsfTokens.CONTROL_KEYWORD
        else -> RsfTokens.IDENTIFIER
    }

    private fun punctType(op: String): IElementType = when (op) {
        "(" -> RsfTokens.LPAREN
        ")" -> RsfTokens.RPAREN
        "{" -> RsfTokens.LBRACE
        "}" -> RsfTokens.RBRACE
        "[" -> RsfTokens.LBRACKET
        "]" -> RsfTokens.RBRACKET
        "," -> RsfTokens.COMMA
        ";" -> RsfTokens.SEMICOLON
        "." -> RsfTokens.DOT
        // 阶段 21：注解记号。插件这边不细分「注解记号」这个类别，落到通用符号色
        // （和 `->` / `::` 同一类），关键是**不能再当非法字符**。
        "@" -> RsfTokens.OPERATION
        in OPERATORS -> RsfTokens.OPERATION
        else -> RsfTokens.BAD_CHARACTER
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    companion object {
        /** 语言里认得的运算符（`.` `,` `;` 和括号另有归类，不在这里）。 */
        private val OPERATORS = setOf(
            "->", "::", "?:", "!!", "==", "!=", "<=", ">=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "++", "--", "..", ":",
            "+", "-", "*", "/", "%", "<", ">", "=", "!", "?", "&", "|", "^", "~",
        )

        /** 长的放前面，否则 `->` 会被切成 `-` 和 `>`。 */
        private val PUNCT = OPERATORS.sortedByDescending { it.length } + listOf(
            "(", ")", "{", "}", "[", "]", ",", ";", ".",
            // 阶段 21：注解使用点的 `@`。它不是运算符，单独列在这里
            // （不放进 OPERATORS，免得「运算符」这个词被撑大）。
            "@",
        )

        private fun punctuation(text: CharSequence, at: Int, to: Int): String {
            for (op in PUNCT) {
                if (at + op.length <= to && text.regionMatches(at, op, 0, op.length)) return op
            }
            // 单个字符兜底：语言里不存在的符号会被切成一个 BAD_CHARACTER，不影响后面
            return text[at].toString()
        }
    }
}
