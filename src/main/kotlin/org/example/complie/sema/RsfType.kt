package org.example.complie.sema

/**
 * 基础类型——不含可空信息。
 *
 * 拆出这一层是因为「可空」是正交于「是什么类型」的一个维度：
 * `int` 和 `?int` 是同一个基础类型，但 JVM 表示完全不同（`I` vs `Ljava/lang/Integer;`）。
 */
enum class RsfBase(
    val rsfName: String,
    /** 非空时的 JVM 描述符。 */
    val descriptor: String,
    /** 非空时占几个局部变量槽（long/double 占 2）。 */
    val slots: Int,
) {
    VOID("void", "V", 0),
    BOOL("bool", "Z", 1),

    /**
     * 8 位有符号整数（阶段 16）：JVM 描述符 `B`，占 1 格槽。
     *
     * **JVM 上 byte 就是一个 int**：操作数栈和局部变量槽里都是 int，
     * `ILOAD` / `ISTORE` / `IRETURN` 全都用它，只有字段/参数/返回的描述符才写 `B`。
     * 要在 byte 的范围内截断得显式发 `I2B`（内置函数 `toByte` 就是这么做的）。
     */
    BYTE("byte", "B", 1),

    /**
     * 16 位无符号字符（阶段 16）：JVM 描述符 `C`，占 1 格槽。
     *
     * 和 byte 一样在栈上就是 int，截断用 `I2C`（`toChar`）。
     * 它**不是**数值：`char` 与 `int` 之间没有隐式赋值，要显式转。
     */
    CHAR("char", "C", 1),

    INT("int", "I", 1),
    LONG("long", "J", 2),
    FLOAT("float", "F", 1),
    DOUBLE("double", "D", 2),
    STRING("string", "Ljava/lang/String;", 1),

    /**
     * `null` 字面量自己的类型。
     *
     * 它只出现在「期望类型已知」的位置（作实参、赋给可空变量、rtn、跟可空值比较），
     * 永远不会被存进变量或当返回值类型——那样就没有类型信息可用了。
     * 描述符只是占位，实际不会按它发指令。
     */
    NULL("null", "Ljava/lang/String;", 1),

    /**
     * JVM 类（含 Java / Kotlin 编译出来的类）。具体是哪一个由
     * [RsfType.externalName] 决定，所以这里的名字/描述符只是占位。
     */
    EXTERNAL("", "Ljava/lang/Object;", 1),

    /**
     * 泛型类自己的**类型参数占位**：`class Box&<T>` 的类体里 `T` 就是这个东西。
     *
     * 名字存在 [RsfType.externalName] 里（`T`）。它在 JVM 上没有自己的表示——
     * 按擦除规则**退化成 `Object`**，所以描述符固定是 `Ljava/lang/Object;`：
     * `var value : T` 编译出的是 `Ljava/lang/Object; value`，`Box&<int>` 和
     * `Box&<string>` 因此是同一个 JVM 类（类型实参只在编译期起作用）。
     */
    TYPE_PARAM("T", "Ljava/lang/Object;", 1),
    ;

    /**
     * 真·数值类型：**按宽度**加宽的那四种。
     *
     * `byte` / `char` **不在**这里——它们参与算术时先按 `int` 提升（见 [promoted]），
     * 所以「是不是能参与算术」问的是 [isArithmetic]，不是这个。
     * 这个属性留给「只有数值才有的行为」，比如泛型互操作的重载打分。
     */
    val isNumeric: Boolean
        get() = this == INT || this == LONG || this == FLOAT || this == DOUBLE

    /**
     * 能参与算术运算 / 比较的类型：byte、char 与四种数值（阶段 16 起）。
     *
     * `bool`、`string` 不在里面——`true + 1`、`"a" > "b"` 都要报错。
     */
    val isArithmetic: Boolean
        get() = isNumeric || this == BYTE || this == CHAR

    /** JVM 的「基本类型」：栈上按值（而不是引用）传的那些。bool/byte/char 都算。 */
    val isPrimitive: Boolean
        get() = this == BOOL || isArithmetic

    /**
     * 参与算术时先提升成什么（Java 的 binary numeric promotion）。
     *
     * `byte` 和 `char` 都先变成 `int`：`b + b` 是 int、`c - c` 是 int、
     * `b == c` 也是按 int 比。这样 byte/char 就不会有自己的一套溢出规则，
     * 也**正是不允许它们互相隐式赋值的原因**（`byte = charValue` 在 Java 里也要显式转）。
     */
    fun promoted(): RsfBase = if (this == BYTE || this == CHAR) INT else this

    /**
     * 数值宽度：`int` < `long` < `float` < `double`。
     *
     * `byte` / `char` 与 `int` 同档——它们先提升成 int，所以宽度上不多不少。
     * 非数值（bool/string/…）为 -1。**它只管算术结果的宽度**；
     * 「谁能隐式赋给谁」是另一张表，见 [widenTargets]。
     */
    val rank: Int
        get() = when (this) {
            BYTE, CHAR, INT -> 0
            LONG -> 1
            FLOAT -> 2
            DOUBLE -> 3
            else -> -1
        }


    /** 装箱后的描述符——可空的基本类型在字节码里必须是包装类。 */
    val boxedDescriptor: String
        get() = when (this) {
            BOOL -> "Ljava/lang/Boolean;"
            BYTE -> "Ljava/lang/Byte;"
            CHAR -> "Ljava/lang/Character;"
            INT -> "Ljava/lang/Integer;"
            LONG -> "Ljava/lang/Long;"
            FLOAT -> "Ljava/lang/Float;"
            DOUBLE -> "Ljava/lang/Double;"
            else -> descriptor
        }

    /** 包装类的内部名，用于 `valueOf` / `xxxValue`。非基本类型为 null。 */
    val wrapper: String?
        get() = when (this) {
            BOOL -> "java/lang/Boolean"
            BYTE -> "java/lang/Byte"
            CHAR -> "java/lang/Character"
            INT -> "java/lang/Integer"
            LONG -> "java/lang/Long"
            FLOAT -> "java/lang/Float"
            DOUBLE -> "java/lang/Double"
            else -> null
        }

    /** 包装类拆箱方法名的后缀：Integer.intValue / Byte.byteValue / Character.charValue … */
    val unboxMethod: String?
        get() = when (this) {
            BOOL -> "booleanValue"
            BYTE -> "byteValue"
            CHAR -> "charValue"
            INT -> "intValue"
            LONG -> "longValue"
            FLOAT -> "floatValue"
            DOUBLE -> "doubleValue"
            else -> null
        }
}

/**
 * 隐式加宽表：`from` 类型的值能**直接**赋给哪些类型（阶段 16 起含 `byte` / `char`）。
 *
 * 这是 rsf 的「隐式转换边界」的全部内容，一张表说完：
 *
 * - `byte`  → int、long、float、double（跟 Java 一样，byte 是「窄的整数」）
 * - `char`  → **一个都不给**
 * - `int`   → long、float、double
 * - `long`  → float、double
 * - `float` → double
 *
 * `char` 为什么一个口子都不开：它**不是整数**，它是「一个字符」。
 * `var n : int = 'a'` 这种写法在别处太容易变成事故（想把字符当数字用了、
 * 或者把码点当成了字符本身），所以 char 与任何数值之间都必须显式写出来：
 * `charToInt(c)` 取码点、`toChar(n)` 反向转。反过来 `int → char` 当然也不给。
 *
 * 表里没有的一律**不能**隐式转，尤其是：`int → byte`（窄化）、`int → char`、
 * `byte ↔ char`（两个方向都不行）。这几种要显式转换函数：
 * `toByte(int)`、`toChar(int)`、`charToInt(char)` —— rsf 没有 `(byte) x`
 * 这种强制转换语法，窄化和 char/int 之间的转换全靠这几个内置函数。
 *
 * **算术不受这张表约束**：`byte` 和 `char` 参与算术时都先提升成 `int`
 * （见 [RsfBase.promoted]），所以 `b + b`、`'a' + 1`、`c < 100` 都合法，
 * 结果类型是 int。跟 Java 的 binary numeric promotion 一致。
 *
 * 唯一的赋值特例是**字面量特例**（在 Checker.acceptsValue 里）：
 * `0..127` 的整数字面量可以直接赋给 `byte`，否则 `var b : byte = 5` 根本写不出来。
 */
private fun widenTargets(base: RsfBase): Set<RsfBase> = when (base) {
    RsfBase.BYTE -> setOf(RsfBase.INT, RsfBase.LONG, RsfBase.FLOAT, RsfBase.DOUBLE)
    RsfBase.INT -> setOf(RsfBase.LONG, RsfBase.FLOAT, RsfBase.DOUBLE)
    RsfBase.LONG -> setOf(RsfBase.FLOAT, RsfBase.DOUBLE)
    RsfBase.FLOAT -> setOf(RsfBase.DOUBLE)
    else -> emptySet()
}

/**
 * rainStarfox 的静态类型 = 基础类型 + 可空性 + 泛型实参。
 *
 * 可空类型（`?int` / `?string`）在 JVM 上：基本类型走包装类，引用类型就是它自己
 * （null 本来就是引用类型的一个值）。所以 `?string` 零成本，`?int` 需要装箱。
 *
 * [typeArgs] 只在编译期起作用：JVM 不做泛型，`Box&<int>` 与 `Box&<string>`
 * 擦除后是同一个类 `LBox;`。所以**描述符只看 [base]/[externalName]**，
 * 而类型检查（能不能互相赋值、成员的类型）必须连 [typeArgs] 一起看。
 */
data class RsfType(
    val base: RsfBase,
    val nullable: Boolean = false,
    /** 仅当 [base] 是 [RsfBase.EXTERNAL] / [RsfBase.TYPE_PARAM] 时有意义：内部名或类型参数名。 */
    val externalName: String? = null,
    /** 泛型实参：`Box&<int>` → [`int`]。非泛型是空表（老调用点一个字都不用改）。 */
    val typeArgs: List<RsfType> = emptyList(),
    /**
     * 数组的维数（阶段 13）：`int[]` → 1、`int[][]` → 2、不是数组 → 0。
     *
     * **为什么选「维数」而不是「元素类型」这个表示**：
     *
     * 1. JVM 的数组类型是**完全擦除**的，`int[][]` 的描述符就是 `[[I`，它就是
     *    「元素类型为 `int[]` 的数组」——换句话说「套了几层 `[]`」已经**是**全部信息，
     *    再叠一层递归的 `elementType` 只是把同一个东西写两遍。
     * 2. 描述符因此只有一条规则：`"[".repeat(arrayDepth) + 元素描述符`，
     *    一条覆盖 `[I` / `[[I` / `[Ljava/lang/String;` / `[LPoint;`。
     * 3. 既有的 [accepts] / [promote] / [substitute] / [slots] 这些判断只需要多比
     *    一个字段，不用把每个 `when (base)` 都改写成树的遍历（改写就是漏一处的机会）。
     * 4. [RsfBase] **不加新枚举值**：数组是「类型的修饰」而不是新的基础类型。
     *    真加了 `RsfBase.ARRAY` 的话，全项目几十个 `when (base)` 都得跟着改，
     *    而且 `base.descriptor` / `rank` / `isArithmetic` 这些属性对数组根本没有答案。
     *
     * 用 [elementType] 拿元素类型、[arrayOf] 套维度，别在别处自己拼。
     *
     * 约定（阶段 13 定，和 Checker 一致）：**不写 `?T[]`**——`?` 是给「值可能为 null」
     * 的引用/装箱类型用的，而数组本身就是引用，元素默认值天然是 `0 / false / null`。
     * 所以数组的 [nullable] 永远是 false，`isNullable` 也就不会跟数组打架。
     */
    val arrayDepth: Int = 0,
    /**
     * 指针层数（阶段 53）：`int*` → 1、`Point**` → 2。不是指针是 0。
     *
     * 指针**不是** JVM 类型：字节码后端遇到 ptrDepth > 0 直接报错，
     * 只有解释器与 C 后端（native 线）有它的语义。
     */
    val ptrDepth: Int = 0,
) {
    val rsfName: String
        get() {
            val head = when {
                base == RsfBase.EXTERNAL || base == RsfBase.TYPE_PARAM -> simpleExternalName
                base == RsfBase.STRING -> "string"
                else -> base.rsfName
            }
            // `Box&<int>`——擦除后两个不同的实例化是同一个 JVM 类型，
            // 报错时只有带上实参才说得清是哪一个
            val mark = if (nullable && base != RsfBase.NULL) "?" else ""
            val args = if (typeArgs.isEmpty()) {
                ""
            } else {
                "&<" + typeArgs.joinToString(", ") { it.rsfName } + ">"
            }
            // `Box&<int>[]`：`[]` 写在最后，和源码里的写法一致
            return mark + head + args + "[]".repeat(arrayDepth) + "*".repeat(ptrDepth)
        }

    /** `java/lang/Math` → `Math`；类型参数占位 → 它的名字（`T`）。 */
    val simpleExternalName: String
        get() = when {
            base == RsfBase.TYPE_PARAM -> externalName ?: "T"
            // 这里按 base 判断而不是 isExternal：`Point[]` 也要报成 `Point[]`，
            // 而不是空字符串（isExternal 对数组是 false，见那里的说明）
            base == RsfBase.EXTERNAL -> externalName?.substringAfterLast('/') ?: "object"
            base == RsfBase.STRING -> "string"
            else -> base.rsfName
        }

    /**
     * 该类型在 JVM 上的描述符（可空基本类型 → 包装类）。
     *
     * **泛型实参不参与**——这正是擦除：`Box of int` 与 `Box of string` 都是 `LBox;`。
     * 数组参与：`int[]` → `[I`、`int[][]` → `[[I`、`Box&<int>[]` → `[LBox;`。
     */
    val descriptor: String
        get() = if (arrayDepth == 0) {
            elementDescriptor
        } else {
            "[".repeat(arrayDepth) + elementDescriptor
        }

    /** 不带数组维度的那部分描述符（数组的一层元素）。 */
    private val elementDescriptor: String
        get() = when {
            base == RsfBase.EXTERNAL -> "L$externalName;"
            // 类型参数擦除到 Object（`var value : T` → `Ljava/lang/Object; value`；
            // `T[]` → `[Ljava/lang/Object;`）
            base == RsfBase.TYPE_PARAM -> "Ljava/lang/Object;"
            nullable && base.isPrimitive -> base.boxedDescriptor
            else -> base.descriptor
        }

    /** 该类型占几个局部变量槽。数组是引用（1 格）；可空基本类型装箱后也是 1 格。 */
    val slots: Int
        get() = if (arrayDepth > 0) 1 else if (nullable && base.isPrimitive) 1 else base.slots

    /** 数值运算只对非空数值有意义（数组不算）。 */
    val isNumeric: Boolean
        get() = arrayDepth == 0 && !nullable && base.isNumeric

    /** 能不能参与算术 / 比较（byte、char、int、long、float、double，且非空、非数组）。 */
    val isArithmetic: Boolean
        get() = arrayDepth == 0 && !nullable && base.isArithmetic

    /** JVM 的基本类型（栈上按值传）：数组是引用，不是。 */
    val isPrimitive: Boolean
        get() = arrayDepth == 0 && !nullable && base.isPrimitive

    /**
     * 是不是一个 JVM 类的**对象**。
     *
     * 数组**不算**：`Point[]` 的 base 也是 [RsfBase.EXTERNAL]、externalName 也是 `Point`，
     * 但它是一个数组、不是 `Point`。这一条要是漏了，`ps.sum()` 会被当成
     * 「在 Point 上找成员」而放过去（然后在运行期炸掉）。
     */
    val isExternal: Boolean
        get() = arrayDepth == 0 && base == RsfBase.EXTERNAL

    /** 是不是泛型类体里的类型参数占位（`class Box of T` 里的 `T`；`T[]` 也算）。 */
    val isTypeParam: Boolean
        get() = base == RsfBase.TYPE_PARAM

    /** 是不是数组类型（`T[]`、`int[][]`）。 */
    val isArray: Boolean
        get() = arrayDepth > 0

    /**
     * 数组的**元素类型**（去掉一层 `[]`）：`int[][]` → `int[]`、`int[]` → `int`。
     * 不是数组时返回自己。
     */
    val elementType: RsfType
        get() = if (arrayDepth == 0) this else copy(arrayDepth = arrayDepth - 1)

    /** 是不是指针（阶段 53）。 */
    val isPointer: Boolean
        get() = ptrDepth > 0

    /** 套上 [n] 层 `*`：`int` + 1 → `int*`。 */
    fun pointerOf(n: Int = 1): RsfType =
        if (n == 0) this else copy(ptrDepth = ptrDepth + n)

    /** 去掉一层 `*`（解引用的结果类型）；不是指针时返回自己。 */
    val pointee: RsfType
        get() = if (ptrDepth == 0) this else copy(ptrDepth = ptrDepth - 1)

    /** 套上 [depth] 层 `[]`：`int` + 1 → `int[]`。 */
    fun arrayOf(depth: Int = 1): RsfType =
        if (depth == 0) this else copy(arrayDepth = arrayDepth + depth)

    /** 类型参数占位的名字（`T`）；不是占位则 null。 */
    val typeParamName: String?
        get() = if (isTypeParam) externalName else null

    val isNullable: Boolean
        get() = nullable && base != RsfBase.NULL

    val isVoid: Boolean
        get() = base == RsfBase.VOID

    /** 是不是 `null` 字面量那个占位类型。 */
    val isNullLiteral: Boolean
        get() = base == RsfBase.NULL

    fun asNullable(): RsfType = if (nullable || base == RsfBase.VOID) this else copy(nullable = true)

    fun asNonNull(): RsfType = if (nullable) copy(nullable = false) else this

    /** 泛型的第一个实参：`Box&<int>` → `int`；不是泛型则 null。 */
    val firstTypeArg: RsfType? get() = typeArgs.firstOrNull()

    /** 带上（或去掉）泛型实参。 */
    fun withArgs(args: List<RsfType>): RsfType = copy(typeArgs = args)

    /**
     * 递归替换类型参数占位：[subst] 是「类型参数名 → 实际类型」，例如 `T`
     * 在 `Box&<int>` 里换成 `int`。
     *
     * `Box&<T>` 的成员（`fun get() -> T`）解析出来时带的是占位 `T`，
     * 拿到实参类型才能知道 `b.get()` 是 int 还是 string。
     */
    fun substitute(subst: Map<String, RsfType>): RsfType {
        if (subst.isEmpty()) return this
        val name = typeParamName
        if (name != null) {
            val actual = subst[name] ?: return this
            // `T[]` 的 T 换成 int 之后是 `int[]`——维度要一起搬过去，
            // 漏了这一步 `Box&<int>` 里的 `var items : T[]` 会退化成 `int`
            val replaced = actual.arrayOf(arrayDepth)
            return if (nullable) replaced.asNullable() else replaced
        }
        if (typeArgs.isEmpty()) return this
        return copy(typeArgs = typeArgs.map { it.substitute(subst) })
    }

    /** 作为 print / println 实参时的 JVM 方法描述符。 */
    val printDescriptor: String
        get() = when {
            isExternal -> "(Ljava/lang/Object;)V"
            // 数组没有对应的 PrintStream 重载（`println([I)` 不存在），
            // 只能按 Object 走。**注意**：数组本身的打印是被 Checker 拦下的
            // （两端都会打出身份哈希，不可控），这里只是防御性的兜底。
            isArray -> "(Ljava/lang/Object;)V"
            // PrintStream 没有 print(byte) 这个重载（只有 print(int)），
            // 而 byte 在栈上本来就是 int，所以按 int 打——打出来是数字，
            // 这跟解释器把 byte 当整数渲染是一回事。
            base == RsfBase.BYTE && !nullable -> "(I)V"
            else -> "($descriptor)V"
        }

    /**
     * [source] 类型的值能不能赋给本类型。
     *
     * 允许：同类型（含泛型实参相同）；非空 → 可空；加宽（非空：`byte → int/long/float/double`、
     * `int → long/float/double`，见文件末尾的隐式加宽表）；`null` 字面量 → 任何可空类型；
     * 任何引用类型 → `Object`。
     * 不允许：可空 → 非空（这正是可空要拦住的事）；窄化；`char` 与任何数值类型之间
     * （两个方向都要显式转换函数）；`byte` 与 `char` 互转；两个不同的外部类之间互赋；
     * **泛型实参不同的同一个类**（`Box&<int>` ↔ `Box&<string>`）——擦除后它们是同一个
     * JVM 类型，JVM 一点都拦不住，所以必须在编译期拦住。
     *
     * **数组（阶段 13）**：维度是类型的一部分，`int[]` 与 `int[][]` 互不相容（这一点
     * JVM 也认：`[[I` 不能当 `[I` 用）。数组**可以**赋给 `Object`（走下面那条「任何引用
     * 类型都能当 Object 用」——数组在 JVM 上就是引用）；反过来 `Object` **不能**直接当
     * 数组用（要 CHECKCAST，而 rsf 没有强制转换语法，本版不做）。
     */
    fun accepts(source: RsfType): Boolean {
        if (base == RsfBase.VOID || source.base == RsfBase.VOID) return false
        // 泛型类体里的类型参数占位（`class Box&<T>` 的 `var T value = 0`）：
        // T 在 JVM 上擦除成 Object，什么值都放得下，所以类体内部不拦。
        // 出了类体，T 已经被实际类型替换掉了，检查照旧严格。
        if (isTypeParam) return true
        if (source.isNullLiteral) return isNullable || isExternal
        if (this == source) return true
        // 非空 -> 可空（同一基础/外部类，且泛型实参、数组维度一致）
        if (base == source.base && externalName == source.externalName) {
            return typeArgs == source.typeArgs &&
                arrayDepth == source.arrayDepth &&
                nullable && !source.nullable
        }
        // 任何引用类型都能当 Object 用
        if (isExternal && externalName == "java/lang/Object") {
            return !source.isPrimitive || (source.nullable && source.base.isPrimitive)
        }
        // 加宽：两边都必须非空，且 source 的基础类型确实能加宽到本类型。
        // byte / char 参与的那几条（byte→int、char→int、char→long…）也走这张表。
        if (isArithmetic && source.isArithmetic) return base in widenTargets(source.base)
        return false
    }

    override fun toString(): String = rsfName

    companion object {
        val VOID = RsfType(RsfBase.VOID)
        val BOOL = RsfType(RsfBase.BOOL)
        val BYTE = RsfType(RsfBase.BYTE)
        val CHAR = RsfType(RsfBase.CHAR)
        val INT = RsfType(RsfBase.INT)
        val LONG = RsfType(RsfBase.LONG)
        val FLOAT = RsfType(RsfBase.FLOAT)
        val DOUBLE = RsfType(RsfBase.DOUBLE)
        val STRING = RsfType(RsfBase.STRING)

        /** `null` 字面量的类型。 */
        val NULL = RsfType(RsfBase.NULL)

        val BYTE_NULLABLE = RsfType(RsfBase.BYTE, nullable = true)
        val CHAR_NULLABLE = RsfType(RsfBase.CHAR, nullable = true)
        val INT_NULLABLE = RsfType(RsfBase.INT, nullable = true)
        val LONG_NULLABLE = RsfType(RsfBase.LONG, nullable = true)
        val FLOAT_NULLABLE = RsfType(RsfBase.FLOAT, nullable = true)
        val DOUBLE_NULLABLE = RsfType(RsfBase.DOUBLE, nullable = true)
        val BOOL_NULLABLE = RsfType(RsfBase.BOOL, nullable = true)
        val STRING_NULLABLE = RsfType(RsfBase.STRING, nullable = true)

        private val BY_NAME: Map<String, RsfType> =
            listOf(
                VOID, BOOL, BYTE, CHAR, INT, LONG, FLOAT, DOUBLE, STRING,
                BOOL_NULLABLE, BYTE_NULLABLE, CHAR_NULLABLE, INT_NULLABLE, LONG_NULLABLE,
                FLOAT_NULLABLE, DOUBLE_NULLABLE, STRING_NULLABLE,
            ).associateBy { it.rsfName } +
                mapOf("str" to STRING, "boolean" to BOOL)

        /** 按源码里的写法解析类型名，支持 `?` 前缀。 */
        fun fromName(name: String): RsfType? = BY_NAME[name]

        /**
         * 两个类型求「共同类型」，用于三目、`?:` 的合并（不是算术结果，算术用 [arithmetic]）。
         *
         * 规则：`null` 跟谁配就变成谁的可空版；同基础类型则取可空的那侧；
         * 其余非空算术类型按宽度提升（`byte` 与 `char` 在这里就变成 `int`）。
         * 返回 null 表示两边合不到一起（调用方报错）。
         */
        fun promote(a: RsfType, b: RsfType): RsfType? {
            if (a.isNullLiteral && b.isNullLiteral) return a
            if (a.isNullLiteral) return if (b.isVoid) null else b.asNullable()
            if (b.isNullLiteral) return if (a.isVoid) null else a.asNullable()
            if (a == b) return a
            if (a.base == b.base && a.externalName == b.externalName && a.typeArgs == b.typeArgs) {
                return RsfType(a.base, a.nullable || b.nullable, a.externalName, a.typeArgs)
            }
            if (a.isArithmetic && b.isArithmetic) {
                // 先各自提升（byte/char → int），再按宽度取宽的那个
                val left = a.base.promoted()
                val right = b.base.promoted()
                return RsfType(if (left.rank >= right.rank) left else right)
            }
            // 两边都是引用类型时，退到 Object（和 javac 的合流规则一致）
            if (!a.isPrimitive && !b.isPrimitive) {
                return external("java/lang/Object").copy(nullable = a.nullable || b.nullable)
            }
            return null
        }

        /**
         * 算术运算的结果类型（Java 的 binary numeric promotion）。
         *
         * 和 [promote] 的区别只有一处，但很关键：**`byte` / `char` 一律提升成 `int`**。
         * `b1 + b2` 是 int（不是 byte！），`'a' + 1` 也是 int。
         * [promote] 是「两个类型合并成共同类型」，给三目 / `?:` 用，
         * 那里 `?byte ?: byte` 必须还是 byte，所以不能混用这一个函数。
         *
         * 两边只要有一边不是算术类型就返回 null（调用方报错）。
         */
        fun arithmetic(left: RsfType, right: RsfType): RsfType? {
            if (!left.isArithmetic || !right.isArithmetic) return null
            val a = left.base.promoted()
            val b = right.base.promoted()
            return RsfType(if (a.rank >= b.rank) a else b)
        }

        /** 从类文件的内部名构造一个外部类类型：`java/lang/Math` → `Math`。 */
        fun external(internalName: String): RsfType =
            RsfType(RsfBase.EXTERNAL, nullable = false, externalName = internalName)

        /** 泛型类体里的类型参数占位：`class Box&<T>` 里的 `T` → 占位「T」。 */
        fun typeParam(name: String): RsfType =
            RsfType(RsfBase.TYPE_PARAM, nullable = false, externalName = name)

        val OBJECT: RsfType get() = external("java/lang/Object")
    }
}
