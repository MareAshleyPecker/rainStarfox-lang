package org.example.complie.sema

/**
 * 一个内置函数：名字、形参类型、返回类型，外加「会不会失败」。
 *
 * 内置函数**没有 rsf 函数体**，解释器与字节码后端各写一遍实现（`print` / `println`
 * 也是这么做的）。所以签名必须集中放在这里：
 *
 * - Checker 拿它做类型检查与「必须处理错误」的判断；
 * - 解释器拿它决定参数怎么收敛、返回什么；
 * - 字节码后端拿它决定压什么栈、发哪条指令。
 *
 * 三边都读同一张表，「两端输出逐字节一致」这条承诺才不会因为签名抄错而破。
 */
class Builtin(
    val name: String,
    val params: List<RsfType>,
    val ret: RsfType,
    /**
     * 是不是「可能失败」（相当于 rsf 里的 `!T`）：正常路径返回 [ret]，
     * 失败时抛错（解释器抛 RsfFailure、编译产物抛 RuntimeException），
     * 调用方必须用 try / `?:` / `!!` 显式处理。目前是 `parseInt` 与四个文件函数。
     */
    val mayError: Boolean = false,
    /** 补全与报错里给人看的一句话。 */
    val doc: String,
    /**
     * 编译产物里为它生成的辅助方法（名字 + 描述符）——只有 [mayError] 的才需要。
     *
     * **为什么放在这张表里**：`?:` 的合成方法（`__rsf_orelse$N`）与调用点都要
     * 「按内置函数名找到该调哪个辅助方法」，以前这两处各写死了一次 `parseInt`。
     * 集中到 [Builtin] 上，再加一个可能失败的内置函数时只用在这一处填名字，
     * 两个调用点都不用改。辅助方法都生成在**程序类**上（public static），
     * 名字带 `$`，rsf 源码写不出，绝不会跟用户代码撞车。
     */
    val helperName: String? = null,
    val helperDesc: String? = null,
) {
    /** `charAt(int) -> char` 这种可读签名（可能失败的带 `!`）。补全列表也用它。 */
    fun display(): String {
        val mark = if (mayError) "!" else ""
        return "$name(${params.joinToString(", ") { it.rsfName }}) -> $mark${ret.rsfName}"
    }
}

/**
 * 阶段 16 的标准库切片：`byte` / `char` 相关的内置函数，加上阶段 16b 的文件读写。
 *
 * 这里放的是**写词法器与自举最少要用的那一套**。
 * 选内置函数而不是 rsf 写的标准库，理由很直接：rsf 写的库要 `import`，
 * 而 import 会把「标准库在哪」变成构建环境问题；内置函数跟 `print` 一样，
 * 两份后端各实现一次就够了，也没有额外的名字解析。
 *
 * 命名规则（跟已有的 `print` / `println` 一致，全小写驼峰）：
 *
 * | 函数 | 作用 | 编译产物里是什么 |
 * | --- | --- | --- |
 * | `toByte(int) -> byte` | 显式窄化（Java 的 `(byte)`） | `I2B` |
 * | `toChar(int) -> char` | 显式转换（Java 的 `(char)`） | `I2C` |
 * | `charToInt(char) -> int` | 取码点 | 什么都不用发（char 在栈上就是 int） |
 * | `intToString(int) -> string` | 整数转字符串 | `String.valueOf(int)` |
 * | `parseInt(string) -> !int` | 字符串转整数，失败 `fail` | `Integer.parseInt` + 失败改抛 RuntimeException |
 * | `isDigit / isLetter / isWhitespace(char) -> bool` | 字符分类 | `Character.isXxx(char)` |
 * | `toUpper / toLower(char) -> char` | 大小写转换 | `Character.toUpperCase / toLowerCase` |
 * | `readText(string) -> !string` | 读整个文本文件（UTF-8） | `Files.readString` + 失败改抛 RuntimeException |
 * | `writeText(string, string) -> !void` | 写文本（覆盖） | `Files.write` + 同上 |
 * | `readBytes(string) -> !byte[]` | 读二进制 | `Files.readAllBytes` + 同上 |
 * | `writeBytes(string, byte[]) -> !void` | 写二进制（自举落盘 `.class` 用它） | `Files.write` + 同上 |
 *
 * 分类与大小写这三对**直接调 `java.lang.Character` 的对应方法**（解释器也是），
 * 这样 Unicode 边界情况（比如 `isWhitespace` 不认不间断空格、`toUpper` 对非字母原样返回）
 * 两端一定一模一样——自己实现一套判断迟早会在某个偏僻字符上跑偏。
 * 四个文件函数同理：**两端调的是同一组 `java.nio.file` 方法**，
 * 失败消息也就天然一字不差（见 [FileRuntime]）。
 *
 * `string` 那三个常用成员（`length()` / `charAt(int) -> char` / `substring(int, int)`）
 * **不在这里**：它们是 `java.lang.String` 的真方法，走既有的 JVM 互操作那条路就够了
 * （`charAt` 的描述符是 `(I)C`，ClassPath 会把它解析成 `char`）。
 */
object Builtins {

    val TO_BYTE = Builtin(
        "toByte", listOf(RsfType.INT), RsfType.BYTE,
        doc = "把 int 截断成 byte（Java 的 (byte) x）",
    )

    val TO_CHAR = Builtin(
        "toChar", listOf(RsfType.INT), RsfType.CHAR,
        doc = "把码点当 char（Java 的 (char) x）",
    )

    val CHAR_TO_INT = Builtin(
        "charToInt", listOf(RsfType.CHAR), RsfType.INT,
        doc = "取字符的码点：charToInt('A') == 65",
    )

    val INT_TO_STRING = Builtin(
        "intToString", listOf(RsfType.INT), RsfType.STRING,
        doc = "整数转字符串：intToString(42) == \"42\"",
    )

    val PARSE_INT = Builtin(
        "parseInt", listOf(RsfType.STRING), RsfType.INT, mayError = true,
        doc = "字符串转整数，失败就 fail（!int）；要兜底值写 parseInt(s) ?: 0",
        helperName = PARSE_INT_HELPER,
        helperDesc = PARSE_INT_HELPER_DESC,
    )

    val IS_DIGIT = Builtin("isDigit", listOf(RsfType.CHAR), RsfType.BOOL, doc = "是不是数字字符")
    val IS_LETTER = Builtin("isLetter", listOf(RsfType.CHAR), RsfType.BOOL, doc = "是不是字母字符")
    val IS_WHITESPACE = Builtin(
        "isWhitespace", listOf(RsfType.CHAR), RsfType.BOOL,
        doc = "是不是空白字符（空格 / 制表 / 换行 / 回车…）",
    )
    val TO_UPPER = Builtin("toUpper", listOf(RsfType.CHAR), RsfType.CHAR, doc = "转大写")
    val TO_LOWER = Builtin("toLower", listOf(RsfType.CHAR), RsfType.CHAR, doc = "转小写")

    // ------------------------------------------------------------------ 文件读写（阶段 16b）

    /** `byte[]` 的 rsf 类型：`readBytes` 的返回、`writeBytes` 的参数都用它。 */
    private val BYTE_ARRAY: RsfType = RsfType.BYTE.arrayOf(1)

    val READ_TEXT = Builtin(
        "readText", listOf(RsfType.STRING), RsfType.STRING, mayError = true,
        doc = "读整个文本文件（UTF-8），失败就 fail（!string）",
        helperName = FileRuntime.READ_TEXT_HELPER,
        helperDesc = FileRuntime.READ_TEXT_HELPER_DESC,
    )

    val WRITE_TEXT = Builtin(
        "writeText", listOf(RsfType.STRING, RsfType.STRING), RsfType.VOID, mayError = true,
        doc = "把字符串按 UTF-8 写进文件（覆盖），失败就 fail（!void）",
        helperName = FileRuntime.WRITE_TEXT_HELPER,
        helperDesc = FileRuntime.WRITE_TEXT_HELPER_DESC,
    )

    val READ_BYTES = Builtin(
        "readBytes", listOf(RsfType.STRING), BYTE_ARRAY, mayError = true,
        doc = "读整个二进制文件（!byte[]），失败就 fail",
        helperName = FileRuntime.READ_BYTES_HELPER,
        helperDesc = FileRuntime.READ_BYTES_HELPER_DESC,
    )

    val WRITE_BYTES = Builtin(
        "writeBytes", listOf(RsfType.STRING, BYTE_ARRAY), RsfType.VOID, mayError = true,
        doc = "把 byte[] 原样写进文件（覆盖），失败就 fail（!void）",
        helperName = FileRuntime.WRITE_BYTES_HELPER,
        helperDesc = FileRuntime.WRITE_BYTES_HELPER_DESC,
    )

    /** 带签名的内置函数（`print` / `println` 是单参数特例，签名不在这里表达）。 */
    val ALL: List<Builtin> = listOf(
        TO_BYTE, TO_CHAR, CHAR_TO_INT, INT_TO_STRING, PARSE_INT,
        IS_DIGIT, IS_LETTER, IS_WHITESPACE, TO_UPPER, TO_LOWER,
        READ_TEXT, WRITE_TEXT, READ_BYTES, WRITE_BYTES,
    )

    private val BY_NAME: Map<String, Builtin> = ALL.associateBy { it.name }

    val NAMES: Set<String> = BY_NAME.keys

    operator fun get(name: String): Builtin? = BY_NAME[name]

    /**
     * `print` / `println`：接受 0 或 1 个任意参数，单独一套规则（见 Checker.checkCall）。
     * 它们也在 [Checker.BUILTINS] 里，因为「用户不能重定义内置函数」这条要一起管。
     */
    val PRINT_LIKE: Set<String> = setOf("print", "println")

    // ------------------------------------------------------------------ parseInt 的错误

    /**
     * `parseInt` 失败时的错误消息格式。
     *
     * **两端逐字节一致**的前提：解释器抛的 RsfFailure 和编译产物抛的 RuntimeException
     * 消息必须一模一样（catch 里 println(e) 打出来的就是它）。所以格式只在这里定义，
     * 解释器直接调 [parseIntFailure]，字节码后端用前后缀拼出同一个串。
     */
    const val PARSE_INT_PREFIX = "parseInt: 不是合法的整数：'"

    const val PARSE_INT_SUFFIX = "'"

    fun parseIntFailure(text: String): String = PARSE_INT_PREFIX + text + PARSE_INT_SUFFIX

    /** 编译产物里生成的辅助方法名（带 `$`，rsf 源码写不出这个名字，不会撞车）。 */
    const val PARSE_INT_HELPER = "__rsf_parseInt\$rt"

    /** 辅助方法的描述符：`String -> int`。 */
    const val PARSE_INT_HELPER_DESC = "(Ljava/lang/String;)I"
}

/**
 * 枚举分派的运行期错误（阶段 19c）。
 *
 * 和 [ArrayRuntime] 同一个理由：**消息必须集中在这里**，因为两端各抛各的异常类型
 * （解释器抛 `RsfFailure`，编译产物抛 `RuntimeException`，都能被 `catch (e : string)`
 * 接住），只有消息文本统一，两端输出才可能逐字节一致。
 *
 * 什么时候会用到它：`switch` 没写 `else` 时，Checker 已经保证 case 覆盖了枚举的
 * **每一个值**，所以「都不命中」在正确程序里不可能发生——唯一的旁路是**值是 null**：
 * 枚举常量都不是 null，而引用类型数组的元素默认就是 null（`switch (arr[1])`），
 * 泛型容器取出来的空槽也可能是 null。这时两端都抛这条错误，
 * 而不是「悄悄什么都不做」——静默跳过会让 `catch` 接不到、输出也看不出问题。
 */
object EnumRuntime {

    /**
     * 完整消息：`switch 没匹配到任何分支：TokenKind 的常量都不是 null，所以这个值只能是 null...`
     *
     * 消息里带上枚举名，是因为一份程序里往往有多个枚举，光看「没匹配到」不知道是哪个。
     */
    fun noMatchFailure(enumName: String): String =
        "switch 没匹配到任何分支：$enumName 的常量都不是 null，" +
            "所以被分派的值只能是 null（数组元素的默认值、泛型容器取出来的空槽都会是 null）。" +
            "给这个 switch 加一支 'else : ...' 就能兜住它"
}

/**
 * 数组的运行期错误（阶段 13）。
 *
 * **为什么这些消息必须集中在这里**：越界是 JVM 自己也会抛的（`ArrayIndexOutOfBoundsException`），
 * 但它的消息是 `Index 5 out of bounds for length 3`，而解释器抛的是自己那套——
 * 两边一旦分叉，「两端输出逐字节一致」这条硬承诺就破了（`try { ... } catch (e : string) { println(e) }`
 * 打出来的东西会不一样）。所以**两端都做显式边界检查**，都抛同一套 rsf 运行期错误：
 * 解释器抛 `RsfFailure`，编译产物抛 `RuntimeException`，消息都是下面这个格式。
 *
 * 负数长度同理：`new int[-1]` 在 JVM 上是 `NegativeArraySizeException`，
 * 那个异常解释器接不住（它只接 rsf 自己的错误），所以这里也自己检查一遍。
 */
object ArrayRuntime {

    /** 越界消息的前缀 / 中间 / 结尾，编译产物用它们拼 invokeDynamic 的拼接常量。 */
    const val INDEX_PREFIX = "数组下标越界：长度 "
    const val INDEX_MID = "，下标 "

    /** 完整消息：`数组下标越界：长度 3，下标 5`。 */
    fun indexFailure(length: Int, index: Int): String =
        INDEX_PREFIX + length + INDEX_MID + index

    const val LENGTH_PREFIX = "数组长度不能是负数："

    /** 完整消息：`数组长度不能是负数：-1`。 */
    fun lengthFailure(length: Int): String = LENGTH_PREFIX + length

    /**
     * 编译产物里生成的边界检查辅助方法（带 `$`，rsf 源码写不出这个名字）。
     *
     * `(int length, int index) -> int`：不越界就**把下标原样返回**，
     * 于是调用点可以写成「求长度 → 求下标 → 检查 → 直接用这个下标取元素」，
     * 数组引用与下标都不需要额外的局部变量。
     */
    const val INDEX_HELPER = "__rsf_arrayIndex\$rt"

    const val INDEX_HELPER_DESC = "(II)I"

    /** `(int length) -> int`：负数就抛错，否则原样返回（`new int[n]` 用）。 */
    const val LENGTH_HELPER = "__rsf_arrayLength\$rt"

    const val LENGTH_HELPER_DESC = "(I)I"
}

/**
 * 文件读写的运行期约定（阶段 16b）。
 *
 * **两端为什么能一字不差**：四个文件函数在两边调的都是**同一组 `java.nio.file` 方法**
 * （`Paths.get` + `Files.readString` / `Files.readAllBytes` / `Files.write`），
 * 所以同一个失败在两边得到的异常消息本来就相同；差的只是「抛什么」——
 * 解释器抛 `RsfFailure`、编译产物抛 `RuntimeException`，消息都由 [failure] 拼出来。
 * 编译产物里这件事由程序类上的四个 `__rsf_*$rt` 辅助方法做（catch 之后换异常再抛），
 * 解释器则在 [org.example.complie.interp.Interpreter] 的 `callBuiltin` 里直接拼。
 *
 * **为什么编译产物不能内联 try/catch**：和 `parseInt` / 数组边界检查同一个理由——
 * 异常处理器一进去操作数栈就只剩异常对象，而表达式中间求值时栈上往往还有没消费完的值
 * （`println(readText(p))` 里那个 PrintStream），生成出来的类会以 VerifyError 报废。
 *
 * **catch 的是 `java/lang/Exception` 而不是 `IOException`**：路径本身就非法时
 * （Windows 下的 `Paths.get("a<b")`）抛的是 `InvalidPathException`，它**不是** IOException；
 * 两边都按同一个上限接住，才不会出现「解释器接住、编译产物漏出去」这种分歧。
 */
object FileRuntime {

    /**
     * 失败消息里「函数名」与「路径」之间的固定部分，编译产物用 ldc 压它。
     *
     * 完整消息长得像：`readText: 文件操作失败 'C:\tmp\x.txt'：C:\tmp\x.txt`
     * （最后那截是 JDK 自己给的原因，`NoSuchFileException` 的消息就是路径本身）。
     */
    const val PREFIX_TAIL = ": 文件操作失败 '"

    /** 路径与原因之间的固定部分。 */
    const val PATH_TAIL = "'："

    /** 完整的失败消息（唯一真源：消息格式只在 [PREFIX_TAIL] / [PATH_TAIL] / 这里）。 */
    fun failure(name: String, path: String, reason: String?): String =
        name + PREFIX_TAIL + path + PATH_TAIL + reason

    // ---------------------------------------------------------------- 四个辅助方法

    /** `(String path) -> String`：读文本，`Files.readString(Paths.get(path))`。 */
    const val READ_TEXT_HELPER = "__rsf_readText\$rt"

    const val READ_TEXT_HELPER_DESC = "(Ljava/lang/String;)Ljava/lang/String;"

    /** `(String path, String text) -> void`：`Files.write(Paths.get(path), text.getBytes(UTF_8), new OpenOption[0])`。 */
    const val WRITE_TEXT_HELPER = "__rsf_writeText\$rt"

    const val WRITE_TEXT_HELPER_DESC = "(Ljava/lang/String;Ljava/lang/String;)V"

    /** `(String path) -> byte[]`：读二进制，`Files.readAllBytes(Paths.get(path))`。 */
    const val READ_BYTES_HELPER = "__rsf_readBytes\$rt"

    const val READ_BYTES_HELPER_DESC = "(Ljava/lang/String;)[B"

    /** `(String path, byte[] data) -> void`：写二进制，`Files.write(Paths.get(path), data, new OpenOption[0])`。 */
    const val WRITE_BYTES_HELPER = "__rsf_writeBytes\$rt"

    const val WRITE_BYTES_HELPER_DESC = "(Ljava/lang/String;[B)V"
}

/**
 * 用户类对象的 `toString()` 格式——**两端唯一真源**。
 *
 * 缺陷背景：拼接字符串时 `"p = " + p` 两端不一致——字节码后端对**对象类型**
 * 发的是 `String.valueOf(LPoint;)`（这个重载根本不存在 ⇒ 运行期 `NoSuchMethodError`），
 * 而解释器那边能正常拿到文本。修法是两条一起做：
 *
 * 1. 拼接时凡是对象类型一律发 `String.valueOf(Ljava/lang/Object;)`（见
 *    `BytecodeCompiler.emitToString`），走对象的 `toString()`；
 * 2. 给每个用户类生成一个**确定的** `toString()`，解释器在 [org.example.complie.interp.Interpreter.RsfObject]
 *    上按**同一段规则**渲染——规则就写在这个对象里，两头都读它，不可能各写一套。
 *
 * 格式：`类名(字段=值, 字段=值)`；字段按**声明顺序**，只列「基础类型 / string / char / bool」
 * 的字段，**数组与对象字段不递归展开**，统一写 [SKIPPED]（写 `...` 而不是整个跳过：
 * 少一个字段会让人以为类里没有它）。不递归是为了让格式在自引用结构上也能停下来。
 *
 * 为什么不用 JVM 默认的 `Object.toString()`（`Point@1a2b3c`）：那是**身份哈希**，
 * 同一个程序跑两次都不一样，解释器与编译产物更不可能一样——「两端逐字节一致」
 * 这条硬承诺直接破掉。
 */
object ObjectFormat {

    /** 数组 / 对象字段占位。 */
    const val SKIPPED = "..."

    /** 类名后面那一截：`Point(`。 */
    const val OPEN_TAIL = "("

    /** 末尾的 `)`。 */
    const val CLOSE = ")"

    /** 字段之间的分隔。 */
    const val SEP = ", "

    /** 类名 + `(`。 */
    fun open(className: String): String = className + OPEN_TAIL

    /** `字段名=`。 */
    fun fieldPrefix(name: String): String = name + "="

    /**
     * 这个字段的值要不要真的打印。**两端共用这一条判断**（字节码端据此决定发
     * `StringBuilder.append` 还是压一个 [SKIPPED] 字面量）。
     *
     * 可空的基本类型（`?int`）也算：它在 JVM 上是包装类，`append(Object)` 打出来
     * 就是 `5` 或者 `null`，解释器那边是同一个包装类，结果一致。
     *
     * [isEnum]（阶段 19c）：枚举字段**是可打印的**——它的 `toString()` 就是值名，
     * 两端都能给出确定文本。不认这一条的话 `Holder(kind=TokenKind.INT_LIT)` 会打成
     * `Holder(kind=...)`，明明有名字却不显示，纯粹是白丢信息。
     * 注意枚举**不递归展开**（它没有字段，也没有自引用的可能），所以不会像普通对象
     * 那样有无限递归的风险——普通对象字段仍然是 [SKIPPED]。
     */
    fun isDisplayable(t: RsfType, isEnum: Boolean = false): Boolean =
        isEnum || (!t.isArray && (t.base == RsfBase.STRING || t.base.isPrimitive))

    /**
     * 字节码端这个字段该发哪个 `StringBuilder.append` 重载；**null = 不打印**（写 [SKIPPED]）。
     *
     * 几个容易踩的点：
     * - `byte` 的字段描述符是 `B`，但它在操作数栈上就是 int，所以发 `append(I)`
     *   （JDK 根本没有 `append(byte)`）；解释器那边存的是 `Byte`，`toString()` 同样是数字。
     * - `char` 必须发 `append(C)`：发 `append(I)` 会把字符打成码点（`'A'` → `65`）。
     * - `bool` 必须发 `append(Z)`：发 `append(I)` 会打成 `1` / `0`。
     * - 可空的基本类型在栈上是包装类，只能按 `Object` 走。
     * - 枚举（[isEnum]）也按 `Object` 走：`append(Object)` 会调到生成的 `toString()`
     *   （返回值名），和解释器那条 `RsfEnumValue.toString()` 得到同一个字符串。
     */
    fun appendDescriptor(t: RsfType, isEnum: Boolean = false): String? = when {
        !isDisplayable(t, isEnum) -> null
        isEnum -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        t.base == RsfBase.STRING -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        t.nullable -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        t.base == RsfBase.BOOL -> "(Z)Ljava/lang/StringBuilder;"
        t.base == RsfBase.CHAR -> "(C)Ljava/lang/StringBuilder;"
        t.base == RsfBase.BYTE -> "(I)Ljava/lang/StringBuilder;"
        t.base == RsfBase.INT -> "(I)Ljava/lang/StringBuilder;"
        t.base == RsfBase.LONG -> "(J)Ljava/lang/StringBuilder;"
        t.base == RsfBase.FLOAT -> "(F)Ljava/lang/StringBuilder;"
        t.base == RsfBase.DOUBLE -> "(D)Ljava/lang/StringBuilder;"
        else -> null
    }

    /**
     * 一个字段值怎么变成文本。**必须和字节码端 `StringBuilder.append` 的行为对齐**：
     * 解释器里字段的运行时载体就是 JVM 的那些包装类（`Byte` / `Character` / `Boolean` …），
     * 所以绝大多数情况 `toString()` 就等于 `append` 的结果。
     *
     * 枚举（[isEnum]）走 `toString()`：解释器里那个对象是
     * [org.example.complie.interp.Interpreter.RsfEnumValue]，`toString()` 返回值名，
     * 与字节码端生成的 `toString()` 一字不差。
     */
    fun valueText(v: Any?, t: RsfType, isEnum: Boolean = false): String = when {
        v == null -> "null"
        isEnum -> v.toString()
        t.base == RsfBase.STRING -> v as String
        else -> v.toString()
    }

    /**
     * 解释器用的完整渲染（[names] / [types] / [values] 都按**声明顺序**一一对应）。
     *
     * [isEnum] 用来认枚举字段（[isDisplayable]）：解释器手里有语义模型，
     * 一个 `model::isEnumType` 就能答，不必让 [RsfType] 自己记「我是不是枚举」
     * （那会把「枚举」这个概念铺进类型系统里，而它其实只是「一个生成了
     * 单例静态字段的类」）。
     */
    fun render(
        className: String,
        names: List<String>,
        types: List<RsfType>,
        values: Array<Any?>,
        isEnum: (RsfType) -> Boolean = { false },
    ): String {
        val sb = StringBuilder(open(className))
        for (i in names.indices) {
            if (i > 0) sb.append(SEP)
            sb.append(fieldPrefix(names[i]))
            val t = types.getOrElse(i) { RsfType.INT }
            val enumField = isEnum(t)
            sb.append(
                if (isDisplayable(t, enumField)) valueText(values.getOrNull(i), t, enumField) else SKIPPED,
            )
        }
        sb.append(CLOSE)
        return sb.toString()
    }
}

