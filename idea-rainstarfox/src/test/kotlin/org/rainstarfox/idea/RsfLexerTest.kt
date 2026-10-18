package org.rainstarfox.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 词法器的单元测试。
 *
 * 这份词法器只服务于编辑器（高亮、括号配对），但它的分类一旦错位，用户看到的就是
 * 满屏串色的代码——所以该测的还是要测。测试不需要起 IDE，直接 new 出来喂字符串。
 */
class RsfLexerTest {

    private fun lex(text: String): List<Pair<String, String>> {
        val lexer = RsfLexer()
        lexer.start(text)
        val out = ArrayList<Pair<String, String>>()
        while (lexer.tokenType != null) {
            out.add(lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }

    private fun types(text: String): List<String> = lex(text).map { it.first }

    private fun tokenOf(text: String, lexeme: String): String? =
        lex(text).firstOrNull { it.second == lexeme }?.first

    @Test
    fun `关键字 修饰词 类型名 各归各类`() {
        assertEquals(RsfTokens.MODIFIER.toString(), tokenOf("pub fn f() -> void", "pub"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf("fn f() -> void", "void"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("if (a) { rtn }", "if"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("while (a) { break }", "while"))
    }

    @Test
    fun `fun 后面的名字认成函数定义`() {
        assertEquals(
            RsfTokens.FUNCTION_DECLARATION.toString(),
            tokenOf("pub fn square(int n) -> int { rtn n }", "square"),
        )
        // 带返回类型的写法也不能漏
        assertEquals(
            RsfTokens.FUNCTION_DECLARATION.toString(),
            tokenOf("fn string greet() -> string { rtn \"hi\" }", "greet"),
        )
    }

    @Test
    fun `跟左括号的名字认成函数调用`() {
        assertEquals(RsfTokens.FUNCTION_CALL.toString(), tokenOf("println(square(7))", "square"))
        assertEquals(RsfTokens.FUNCTION_CALL.toString(), tokenOf("var x = add(1, 2)", "add"))
    }

    @Test
    fun `print 与 println 是内置函数`() {
        assertEquals(RsfTokens.BUILTIN.toString(), tokenOf("println(\"hi\")", "println"))
        assertEquals(RsfTokens.BUILTIN.toString(), tokenOf("print(1)", "print"))
    }

    @Test
    fun `普通变量名只是标识符`() {
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("var count = 1", "count"))
    }

    @Test
    fun `byte 与 char 是类型关键字 字符字面量单独一类`() {
        // 阶段 16：两个新类型关键字必须进 TYPE_KEYWORD（否则高亮会跟普通名字一样）
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf("var b byte = 1", "byte"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf("var c char = 'a'", "char"))

        // 字符字面量整段一个记号，转义也一起吃掉
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf("var c = 'a'", "'a'"))
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf("var c = '\\n'", "'\\n'"))
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf("var c = '\\u0041'", "'\\u0041'"))

        // 新的内置函数也认出来
        assertEquals(RsfTokens.BUILTIN.toString(), tokenOf("println(isDigit('7'))", "isDigit"))
        assertEquals(RsfTokens.BUILTIN.toString(), tokenOf("var n = parseInt(\"1\")", "parseInt"))
    }

    @Test
    fun `没闭合的字符字面量不会吃掉后面几行`() {
        // 编辑器里漏个引号是常事：切到行末就该停，后面那行照常能切
        val tokens = lex("var c = 'a\nvar d = 2")
        assertTrue(tokens.any { it.second == "'a" }, tokens.toString())
        assertTrue(tokens.any { it.second == "2" }, "后面那行被吃掉了：$tokens")
    }

    @Test
    fun `长运算符不会被切碎`() {
        val tokens = lex("fn f() -> int { rtn 1 }")
        assertTrue(tokens.any { it.second == "->" }, tokens.toString())
        assertTrue(!tokens.any { it.second == "-" }, "-> 被切成了减号：$tokens")
        assertTrue(types("import \"a.rsf\"").contains(RsfTokens.STRING.toString()))
        assertTrue(lex("a :: b").any { it.second == "::" })
    }

    @Test
    fun `范围运算符不会被当成小数点`() {
        val tokens = lex("for (var i = 1..5) { }")
        assertTrue(tokens.any { it.second == ".." }, tokens.toString())
        assertTrue(tokens.any { it.second == "1" }, tokens.toString())
        // 真正的小数点还是要认
        assertTrue(lex("var x = 1.5").any { it.second == "1.5" })
    }

    @Test
    fun `注释与字符串可以跨行`() {
        val comment = "/* 第一行\n   第二行 */ fn f()"
        assertEquals(RsfTokens.BLOCK_COMMENT.toString(), tokenOf(comment, "/* 第一行\n   第二行 */"))

        val multilineString = "var s = \"第一行\n第二行\""
        assertEquals(RsfTokens.STRING.toString(), tokenOf(multilineString, "\"第一行\n第二行\""))

        assertEquals(RsfTokens.LINE_COMMENT.toString(), tokenOf("// 说明\nfn f()", "// 说明"))
    }

    @Test
    fun `未闭合的注释与字符串不会让整份文件崩掉`() {
        // 用户打字打到一半就是这样：后面还得能继续切
        val types = types("/* 没关的注释\n\nfn f() -> void { rtn }")
        assertTrue(types.isNotEmpty())

        val types2 = types("var s = \"没关的字符串")
        assertTrue(types2.contains(RsfTokens.STRING.toString()), types2.toString())
    }

    @Test
    fun `不认识的字符合法地被标成非法字符`() {
        // 注意：这里以前拿 `@` 当非法字符的样例，阶段 21 之后 `@` 是**合法的记号和
        // 标点**（`@Marker` 的注解记号），不能再用它了，换成 `#`。
        val types = types("fn f() { # }")
        assertTrue(types.contains(RsfTokens.BAD_CHARACTER.toString()), types.toString())
        // 非法字符不该把后面的花括号带歪
        assertTrue(types.contains(RsfTokens.RBRACE.toString()), types.toString())
    }

    @Test
    fun `注解记号 @ 与阶段 21 的三个新关键字都被认出来`() {
        assertEquals(RsfTokens.OPERATION.toString(), tokenOf("@Marker", "@"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("annotation Marker { }", "annotation"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("companion entity Impl { }", "companion"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("companion entity Impl { }", "entity"))
    }

    @Test
    fun `空白被合并成一个 token 且覆盖到文件末尾`() {
        val text = "fn f() {\n\n    rtn\n}\n"
        val lexer = RsfLexer()
        lexer.start(text)
        var guard = 0
        while (lexer.tokenType != null && guard++ < 500) lexer.advance()
        assertEquals(text.length, lexer.tokenStart, "词法器走到头时位置应该等于缓冲区末尾")
        assertTrue(types(text).isNotEmpty())
    }

    @Test
    fun `从中间开始切也不会越界`() {
        val text = "fn main() -> void { rtn }"
        // 编辑器做增量高亮时会从任意位置开始
        for (start in 0..text.length) {
            val lexer = RsfLexer()
            lexer.start(text, start, text.length, 0)
            var guard = 0
            while (lexer.tokenType != null && guard++ < 500) lexer.advance()
        }
    }

    // ------------------------------------------------------------------ 与编译器前端的对照

    /**
     * 编译器前端 `Lexer.kt` 的 `KEYWORDS` 快照（逐条抄下来的）。
     *
     * 两个工程分开构建，插件这边引不到编译器那份表，所以在这里留一份清单做对照：
     * 改 `Lexer.kt` 的 KEYWORDS 时，这张表和 [RsfKeywords] 都要跟着改，测试会拦住漏改。
     */
    private val COMPILER_KEYWORDS = setOf(
        "fn", "rtn", "return", "import", "module", "pkg",
        "var", "imutvar", "let",
        // 阶段 20：`interface` 是那时进的前端（这份快照当时漏抄了，插件的高亮表其实已经有一份）；
        // `override` 是阶段 20 追加的可选修饰词，和 public/static 同族
        "pub", "prv", "prot", "stc", "override",
        "class", "new", "this", "constructor", "interface",
        // 阶段 19c：枚举声明 + 枚举分派（这份快照和插件的高亮表当时都漏抄了）
        "enum", "switch", "case",
        // 阶段 21：注解声明与伴生实体（三个都是硬关键字）
        "annotation", "companion", "entity",
        // 阶段 22：数学类的预留关键字（先占词，语法未定）
        "mathematic",
        // 阶段 24：类型转换与类型判断（`as` / `?as` / `is` / `!is` 里的两个硬关键字）
        "as", "is",
        "if", "else", "while", "for", "in", "break", "continue", "goto",
        "fail", "try", "catch", "true", "false", "null",
        "void", "int", "long", "float", "double", "string", "str", "bool", "boolean", "byte", "char",
    )

    @Test
    fun `关键字表与编译器前端的 KEYWORDS 逐条对齐`() {
        // 多一个少一个都会让高亮串味，所以两边都逐个核对
        assertEquals(COMPILER_KEYWORDS, RsfKeywords.ALL, "插件的关键字表与 Lexer.KEYWORDS 对不上")

        for (word in COMPILER_KEYWORDS) {
            val type = tokenOf("$word zz", word)
            assertTrue(
                type != null && type != RsfTokens.IDENTIFIER.toString(),
                "'$word' 在编译器里是关键字，插件的词法器却把它当成了标识符：$type",
            )
        }
    }

    @Test
    fun `pkg constructor new this static 都归到关键字里`() {
        // 这几个是历史上漏掉过的（pkg 尤其：写了以后跟普通名字一个颜色）
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("pkg demo.util", "pkg"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("constructor(n : int) { }", "constructor"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("var p = new Point(1, 2)", "new"))
        assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf("rtn this.value", "this"))
        assertEquals(RsfTokens.MODIFIER.toString(), tokenOf("pub stc fn f() -> void { }", "stc"))
    }

    // ------------------------------------------------------------------ 阶段 19 / 19b 的新写法

    @Test
    fun `变量与字段的后缀类型标注 名字和类型各自上色`() {
        // 阶段 19：`var 名字 : 类型 = 初值`（老写法 `varis int x = 0` 已经不接受）
        val local = "var tally : int = 0"
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(local, "tally"))
        assertEquals(RsfTokens.OPERATION.toString(), tokenOf(local, ":"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(local, "int"))

        // 字段同一条规矩
        val field = "    var slot : string = \"x\""
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(field, "slot"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(field, "string"))

        // 可空与数组
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("var maybe : ?string = null", "maybe"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf("var maybe : ?string = null", "string"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("imutvar xs : int[] = new int[3]", "xs"))
    }

    @Test
    fun `用户类名当类型用时也按类型上色`() {
        // 类型关键字本来就是 TYPE_KEYWORD；自定义类名得靠位置认出来（TYPE_NAME），
        // 否则 `var p : Point` 里的 Point 会跟普通变量一个颜色
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf("var p : Marker = null", "Marker"))
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf("fn take(p : Widget) -> void { rtn }", "Widget"))
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf("fn give() -> Gadget { rtn null }", "Gadget"))
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf("fn give() :: Gadget { rtn null }", "Gadget"))
        // 错误返回 `-> !T` 里的 ! 不该把后面的类型带歪
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf("fn give() -> !Gadget { rtn null }", "Gadget"))
    }

    @Test
    fun `类型位置以外的同名标识符不受影响`() {
        // 行首标签 `loop:` 不是类型标注
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("loop:", "loop"))
        // 三目与 if 的冒号形式也一样
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("var y = if c ? alpha : beta", "beta"))
        // 老写法 `: T` 返回子句已经删掉：`:` 只是普通运算符，后面那个名字不是返回类型
        val legacy = "fn f() : What { rtn 1 }"
        assertEquals(RsfTokens.OPERATION.toString(), tokenOf(legacy, ":"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(legacy, "What"))
    }

    @Test
    fun `返回子句只认箭头与双冒号`() {
        assertEquals(RsfTokens.OPERATION.toString(), tokenOf("fn f() -> int { rtn 1 }", "->"))
        assertEquals(RsfTokens.OPERATION.toString(), tokenOf("fn f() :: int { rtn 1 }", "::"))
        // `::` 不能被切成两个 `:`
        assertTrue(!lex("fn f() :: int { rtn 1 }").any { it.second == ":" })
    }

    @Test
    fun `参数两种风格都能正确切词`() {
        val mixed = "fn mix(int a, b : string) -> int { rtn a }"
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(mixed, "int"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(mixed, "a"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(mixed, "b"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(mixed, "string"))

        // Java 风格 / Kotlin 风格里的用户类名都算类型，参数名照旧是标识符
        val cls = "fn take(who : Widget, Spot spot) -> void { rtn }"
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf(cls, "Widget"))
        assertEquals(RsfTokens.TYPE_NAME.toString(), tokenOf(cls, "Spot"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(cls, "who"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(cls, "spot"))

        // 主构造 / 次构造的参数表用的是同一套判定
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("class P(x0 : int, y0 : int) { }", "x0"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("class P(x0 : int, y0 : int) { }", "y0"))
        assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf("constructor(n : int) { }", "n"))
        // 函数名不能被参数表带歪
        assertEquals(RsfTokens.FUNCTION_DECLARATION.toString(), tokenOf(cls, "take"))
        assertEquals(RsfTokens.FUNCTION_DECLARATION.toString(), tokenOf(mixed, "mix"))
    }

    @Test
    fun `catch 的两种类型写法都认`() {
        for (text in listOf("    } catch (e : string) {", "    } catch (e string) {")) {
            assertEquals(RsfTokens.CONTROL_KEYWORD.toString(), tokenOf(text, "catch"))
            assertEquals(RsfTokens.IDENTIFIER.toString(), tokenOf(text, "e"))
            assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(text, "string"))
        }
    }

    @Test
    fun `数组类型与单引号字符字面量`() {
        val arrays = "var xs : int[] = new int[5]"
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(arrays, "int"))
        assertEquals(RsfTokens.LBRACKET.toString(), tokenOf(arrays, "["))
        assertEquals(RsfTokens.RBRACKET.toString(), tokenOf(arrays, "]"))
        // 嵌套数组的方括号照样成对
        assertEquals(4, lex("var g : int[][] = new int[2][3]").count { it.second == "[" })

        // 单引号字面量连转义一起整段一个记号；旁边的类型关键字不被吃掉
        val ch = "var c : char = 'a'"
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf(ch, "'a'"))
        assertEquals(RsfTokens.TYPE_KEYWORD.toString(), tokenOf(ch, "char"))
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf("var n = '\\n'", "'\\n'"))
        assertEquals(RsfTokens.CHARACTER.toString(), tokenOf("var u = '\\u0041'", "'\\u0041'"))
    }
}
