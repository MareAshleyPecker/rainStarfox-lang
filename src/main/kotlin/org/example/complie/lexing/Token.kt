package org.example.complie.lexing

enum class TokenKind(val text: String) {
    // ---- 字面量 ----
    INT_LIT("整数字面量"),
    LONG_LIT("long 字面量"),
    FLOAT_LIT("float 字面量"),
    DOUBLE_LIT("double 字面量"),
    STRING_LIT("字符串字面量"),
    /** 字符字面量：单引号包一个字符（`'a'`、`'\n'`、`'\u0041'`），阶段 16。 */
    CHAR_LIT("字符字面量"),

    // ---- 标识符 ----
    IDENT("标识符"),

    // ---- 关键字 ----
    KW_FUN("fn"),
    KW_RTN("rtn"),
    KW_RETURN("return"),
    KW_IMPORT("import"),
    KW_MODULE("module"),
    /** 包声明：`pkg 名字`（以当前目录为准，段之间用 . 或 \ 或 / 都行）。 */
    KW_PKG("pkg"),
    KW_VAR("var"),
    KW_IMUTVAR("imutvar"),
    /** 旧关键字，保留只为给出迁移提示。 */
    KW_LET("let"),
    KW_PUBLIC("public"),
    KW_PRIVATE("private"),
    KW_PROTECTED("protected"),
    /** 静态修饰词：控制「怎么调用」（类名.方法 还是 对象.方法）。 */
    KW_STATIC("static"),
    /**
     * `override` 修饰词（阶段 20 的追加件）：只对**类/接口里的方法**有意义。
     *
     * 语义照 Java 的 `@Override`——**可写可不写**：写它是给编译器一句断言
     * （必须在父类型里覆盖到同名同参的方法，否则报错），不写照样能覆盖。
     * 所以它是**硬关键字**：当作标识符用的旧代码本来就没有，收进来不会撞。
     */
    KW_OVERRIDE("override"),
    /**
     * `final` 修饰词（阶段 25，语义在阶段 27 反转）：**收窄**默认开放的类或方法——
     * `final class` 不可被继承，`final fn` 不可被覆盖。
     *
     * 阶段 27 删掉了 `open`（旧法「默认 final」的记录见 DESIGN-25 §4 与 DESIGN-27）。
     */
    KW_FINAL("final"),
    /**
     * `abstract` 修饰词（阶段 25）：抽象类（不能 `new`）与抽象方法（只声明签名）。
     *
     * 它同时是**类**和**方法**的修饰词，所以只能在声明最前面出现；写在别处
     * （顶层函数、局部变量）由 Parser 与 Checker 分别报错。
     */
    KW_ABSTRACT("abstract"),
    /** 类声明（阶段 12）。 */
    KW_CLASS("class"),
    /**
     * 枚举声明（阶段 19c）：`public enum TokenKind { IDENT, INT_LIT }`。
     *
     * 编译口径见 [org.example.complie.ast.EnumDecl]：生成为一个**普通类**，
     * 不是 JVM 的 `enum`（那种类文件里带 `ACC_ENUM`、还继承 `java.lang.Enum`，
     * 两头都得跟着长一套特殊处理；我们要的只是「一组具名单例 + 打印出名字」）。
     */
    KW_ENUM("enum"),
    /**
     * 接口声明（阶段 20）：`public interface Greeter { fn greet(name: string) -> string }`。
     *
     * 注意与阶段 21 的 `@interface`（注解类型，JVM 侧 `ACC_ANNOTATION|ACC_INTERFACE`）
     * 是**两回事**：那是个带 `@` 前缀的独立记号/独立节点，别把这条关键字复用过去。
     * 本阶段只做「普通接口」：编译产物是 JVM 接口，`ACC_INTERFACE|ACC_ABSTRACT`，
     * 没有 `<init>`，也不产生对象。
     */
    KW_INTERFACE("interface"),
    /**
     * 次构造（阶段 18）：`constructor(参数) { ... }`，只能写在类体里。
     *
     * 主构造写在类头的括号里（`class P(int x0)`），次构造用这个关键字——
     * 两者都不写返回类型，也**不能**用「名字等于类名的方法」冒充（那个现在直接报错）。
     */
    KW_CONSTRUCTOR("constructor"),
    /**
     * 注解声明（阶段 21）：`public annotation Marker(value : string = "x") { ... }`。
     *
     * 注意与 [KW_INTERFACE] 是**两个不同的关键字**：注解虽然也编译成 JVM 接口
     * （`ACC_ANNOTATION|ACC_INTERFACE`），但语法里它是独立的一种声明，
     * 而且内部**只能**放一个伴生实体（见 [KW_COMPANION]）。
     *
     * 它是**硬关键字**（不是修饰词）：`isControlKeyword` / `isModifierKeyword`
     * 都不包含它——`annotation` 只能出现在声明最前面，不能跟 public/static 混写。
     */
    KW_ANNOTATION("annotation"),
    /**
     * 伴生实体（阶段 21）：只写在注解体里，`companion entity [名字] [: 父接口] { 类体 }`。
     *
     * 「伴生」是注解特有的东西：注解自己只能带常量元素，行为（方法/字段/构造器）
     * 要写在同一个注解块里的这个实体里，编译产物是 `注解名$名字`（匿名则 `注解名$companion`）。
     */
    KW_COMPANION("companion"),
    /** 实体声明（阶段 21）：目前**只**作为 `companion entity` 的第二半出现。 */
    KW_ENTITY("entity"),
    /**
     * 数学类（阶段 22）的**预留**关键字（2026-10-15 用户拍板先收进词法表）。
     *
     * 语法还没定：`BOOTSTRAP.md` 的阶段 22 只登记了意图与待决点（`mathematic class Math { }`？
     * 成员隐式 `static`？能不能实例化？…）。所以这里**只做保留**——
     * [isControlKeyword] / [isStaticKeyword] / [isModifierKeyword] / [isTypeKeyword]
     * 一个都不包含它：现在写它会在顶层得到一句「阶段 22 还没实现」，
     * 而不是被当成普通名字悄悄用掉，也不会被误当修饰词。
     */
    KW_MATHEMATIC("mathematic"),
    /** `new 类名(实参)`。 */
    KW_NEW("new"),
    /** 当前对象：只在类的方法里有效。 */
    KW_THIS("this"),
    /**
     * `super`（阶段 25）：父类接收者。两种写法 `super.方法()` / `super(接口).方法()`。
     *
     * 它是**表达式起始词**，所以不进 [isModifierKeyword]——否则 `modifierRun()`
     * 会把它当成排在 `fn` 前面的修饰词数进去，`super(1)` 这种构造委托会被
     * 误判成「修饰词后面写了个 '('」。
     */
    KW_SUPER("super"),
    /**
     * `as`（阶段 24）：`值 as 目标类型`、`值 ?as 目标类型`。
     *
     * 硬关键字：写成表达式里的中缀运算符，不走 [isTypeKeyword] / [isStaticKeyword]
     * 那些修饰词集合——它只在 [Expr] 的后缀位置出现。
     */
    KW_AS("as"),
    /**
     * `is`（阶段 24）：`值 is 目标类型` / `值 !is 目标类型`，结果是 `bool`。
     *
     * 同样是硬关键字、同样是后缀运算符，不进任何修饰词集合。
     */
    KW_IS("is"),
    KW_IF("if"),
    KW_ELSE("else"),
    /**
     * 枚举分派（阶段 19c）：`switch (kind) { case TokenKind.IDENT : ... else : ... }`。
     *
     * **默认分支用 `else`**（跟 `if` 的 else 同一个关键字、同一个记号），不是
     * Java 的 `default`——语言里已经有一个「都不是就走这里」的关键字了，
     * 再引一个 `default` 只会让人多记一个词。
     */
    KW_SWITCH("switch"),
    /** `case 枚举名.值名 : 语句`。**只接受枚举常量**，不支持区间/字面量。 */
    KW_CASE("case"),
    KW_WHILE("while"),
    KW_FOR("for"),
    KW_IN("in"),
    KW_BREAK("break"),
    KW_CONTINUE("continue"),
    KW_GOTO("goto"),
    /** 抛出一个错误：`fail "消息"`（阶段 14）。 */
    KW_FAIL("fail"),
    /** 捕获错误：`try { ... } catch (e : string) { ... }`（阶段 14；老写法 `catch (e string)` 也收）。 */
    KW_TRY("try"),
    KW_CATCH("catch"),
    KW_TRUE("true"),
    KW_FALSE("false"),
    KW_NULL("null"),

    // ---- 类型关键字 ----
    KW_VOID("void"),
    KW_INT("int"),
    KW_LONG("long"),
    KW_FLOAT("float"),
    KW_DOUBLE("double"),
    KW_STRING("string"),
    KW_BOOL("bool"),
    /** 8 位有符号整数（阶段 16）：JVM 描述符 `B`。 */
    KW_BYTE("byte"),
    /** 16 位无符号字符（阶段 16）：JVM 描述符 `C`。 */
    KW_CHAR("char"),

    // ---- 标点与运算符 ----
    LPAREN("("),
    RPAREN(")"),
    LBRACE("{"),
    RBRACE("}"),
    /**
     * 方括号（阶段 13）：数组。三种用法——
     * 类型后缀（`int[]`）、下标访问（`a[0]`）、数组创建（`new int[5]`）。
     */
    LBRACKET("["),
    RBRACKET("]"),
    COMMA(","),
    SEMI(";"),
    ARROW("->"),
    COLON(":"),
    DOUBLE_COLON("::"),
    /**
     * `@`（阶段 21）：**只**用作注解使用点的前缀——`@Marker`、`@Marker("hi")`。
     *
     * 写法固定是「`@` 写在被标注的声明前面」，和 `public` / `static` / `override`
     * 这些修饰词**顺序任意、可连写多个**；v1 的可标注位置是类 / 接口 / 注解声明 /
     * 方法 / 字段，参数与局部变量上写 `@` 会在语法阶段报错。
     * 注意它不参与返回子句：`fn f() :: int` 仍然报旧错（`::` 只留给 `A::B` 嵌套类型）。
     */
    AT("@"),
    QUESTION("?"),
    ELVIS("?:"),
    RANGE(".."),
    DOT("."),
    ASSIGN("="),
    EQ("=="),
    NE("!="),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">="),
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    /** 反斜杠：只用在包名分隔与路径里（`pkg a\b`），不是运算符。 */
    BACKSLASH("\\"),
    PERCENT("%"),
    BANG("!"),
    /**
     * `&`：**只**用来引入泛型的类型实参表 `&<int>`。
     *
     * 逻辑与是单独一个记号 [ANDAND]（阶段 19c 起），所以这里的 `&` 只可能出现在
     * `&<...>` 里；反过来 `&` 也**必须**紧跟 `<` 才有意义，
     * 这样单独的 `<` 仍然只表示「小于」（比较运算），两者不会打架。
     *
     * 选择「`&` 单独成记号、由 parser 要求后面跟 `<`」而不是合成一个 `AMP_LT`：
     * 少了 `&` 后面跟别的字符时能报出更准确的错（“'&' 后面缺少 '<'”），
     * 而不会让 `&` 在词法阶段就变成一个别人看不懂的记号。
     */
    AMP("&"),

    /**
     * `&&`：逻辑与（**短路**），阶段 19c 起。
     *
     * 和 [AMP] 是**两个不同的记号**，这一点是刻意的：`&` 只用来引出泛型实参表
     * `&<int>`，而 `&&` 是逻辑运算。词法上按「两个字符」先认 `&&`，剩下孤零零的
     * 一个 `&` 才当 [AMP]——所以 `Box&<int>` 与 `a && b` 在同一行里也不会打架
     * （有测试守着这条）。
     */
    ANDAND("&&"),
    /**
     * `||`：逻辑或（**短路**），阶段 19c 起。
     *
     * rsf 里**没有按位或**，所以单独的 `|` 不是记号：词法阶段看到孤零零的 `|`
     * 直接报「逻辑或要写两个竖线」，免得它一路跑到 parser 变成一句看不懂的错。
     */
    OROR("||"),

    /**
     * 反引号标签名（阶段 28a）：`` `a-b` `` 的 [Token.lexeme] 是**去掉一对反引号之后**
     * 的名字（`a-b`），不含引号。
     *
     * 只许出现在两处：语句首 `` `名字`: `` 的标签声明、`goto` 后面的跳转目标。
     * 其它任何位置出现在 parser 里都报「反引号只用来写标签名」——反引号不扩大
     * 标识符面（变量 / 字段 / 函数名仍只收 Unicode 字母、数字、下划线）。
     */
    LABEL_NAME("反引号标签名"),

    EOF("文件结束"),
    ;

    /** 类型关键字集合，供 parser 快速判断。 */
    val isTypeKeyword: Boolean
        get() = this == KW_VOID || this == KW_INT || this == KW_LONG || this == KW_FLOAT ||
            this == KW_DOUBLE || this == KW_STRING || this == KW_BOOL ||
            this == KW_BYTE || this == KW_CHAR

    /** 函数定义关键字（目前只有 fn 一个）。 */
    val isFunKeyword: Boolean
        get() = this == KW_FUN

    /** 返回语句关键字（rtn 与 return 共存）。 */
    val isReturnKeyword: Boolean
        get() = this == KW_RTN || this == KW_RETURN

    /** 变量声明关键字：var 可变，imutvar 不可变。 */
    val isVarKeyword: Boolean
        get() = this == KW_VAR || this == KW_IMUTVAR

    /** 访问控制修饰符（<ControlType>）。 */
    val isControlKeyword: Boolean
        get() = this == KW_PUBLIC || this == KW_PRIVATE || this == KW_PROTECTED

    /**
     * 静态修饰符（<static>），写在 <ControlType> 和 fn 之间。 */
    val isStaticKeyword: Boolean
        get() = this == KW_STATIC

    /**
     * 继承修饰词（阶段 25；阶段 27 起只剩 `final` / `abstract`）。
     *
     * 两者都是**声明级**的修饰词（写在 `class` / `fn` / 字段前面），
     * 语义见 [KW_FINAL] / [KW_ABSTRACT]。
     */
    val isInheritKeyword: Boolean
        get() = this == KW_FINAL || this == KW_ABSTRACT

    /**
     * 方法修饰词：`fn` 前面那串能按**任意顺序**写的词
     * （`public` / `private` / `protected` / `static` / `override` / `final` / `abstract`）。
     *
     * 拿来「数一数 fn 前面到底排了几个修饰词」，好让 Parser 在
     * `override public fn` 和 `public override fn` 两种写法上看到同一个 `fn`。
     */
    val isModifierKeyword: Boolean
        get() = isControlKeyword || isStaticKeyword || isInheritKeyword || this == KW_OVERRIDE
}

data class Token(
    val kind: TokenKind,
    val lexeme: String,
    val at: SourceLine,
    /** 字面量的实际值：Int / Long / Float / Double / String / Char / Boolean / null */
    val literal: Any? = null,
) {
    val line: Int get() = at.line
    val column: Int get() = at.column

    /** 给报错用的可读描述。 */
    fun describe(): String = when (kind) {
        TokenKind.EOF -> "文件结束"
        TokenKind.IDENT -> "标识符 '$lexeme'"
        TokenKind.LABEL_NAME -> "标签名 '$lexeme'"
        TokenKind.INT_LIT, TokenKind.LONG_LIT, TokenKind.FLOAT_LIT,
        TokenKind.DOUBLE_LIT, TokenKind.STRING_LIT, TokenKind.CHAR_LIT -> lexeme
        else -> "'${kind.text}'"
    }

    override fun toString(): String = "$kind($lexeme)@${at.line}:${at.column}"
}
