package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BoolLit
import org.example.complie.ast.ControlType
import org.example.complie.ast.DoubleLit
import org.example.complie.ast.IntLit
import org.example.complie.ast.NamedAnnotationArg
import org.example.complie.ast.NullLit
import org.example.complie.ast.PositionalAnnotationArg
import org.example.complie.ast.StringLit
import org.example.complie.ast.UnaryExpr
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.TokenKind
import org.example.complie.parsing.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 21 M1：`annotation` 的**前端**回归测试（词法 / 语法 / AST），不碰语义与后端。
 *
 * 覆盖三块（DESIGN-21.md §2）：
 * 1. 注解声明：元素表可省、元素可带常量默认值、体里至多一个 `companion entity`；
 * 2. 使用点：`@M` / `@M("hi")` / `@M(value = "hi", count = 3)`，与 `public`/`static`/`override`
 *    顺序任意、可连写多个；
 * 3. 嵌套类型：类型位置上的 `注解名 :: 伴生名`（v1 只支持一层）。
 *
 * 语义层忽略注解是**预期行为**（M1 只做前端），所以这里一律只断言 AST 与 parser 报错。
 */
class AnnotationSyntaxTest {

    // ============================================================== 声明

    /** 最小声明：元素表和体都可省。 */
    @Test
    fun `最小注解声明`() {
        val program = parse(
            """
            annotation M { }
            """.trimIndent(),
        )
        assertEquals(1, program.annotations.size)
        val m = program.annotations[0]
        assertEquals("M", m.name)
        assertEquals(ControlType.DEFAULT, m.control)
        assertTrue(m.elements.isEmpty(), "元素表省略时应该是空表，实际 ${m.elements}")
        assertNull(m.companion, "体里没写 companion entity 时 companion 应该是 null")
        assertEquals(1, m.at.line)
    }

    /** 权限修饰词：`public annotation Marker { }`。 */
    @Test
    fun `注解声明可以带权限修饰词`() {
        val program = parse(
            """
            public annotation Marker { }
            private annotation Hidden { }
            """.trimIndent(),
        )
        assertEquals(listOf("Marker", "Hidden"), program.annotations.map { it.name })
        assertEquals(ControlType.PUBLIC, program.annotations[0].control)
        assertEquals(ControlType.PRIVATE, program.annotations[1].control)
    }

    /** 元素可以不带默认值（`value : string`）。 */
    @Test
    fun `注解元素不带默认值`() {
        val program = parse(
            """
            annotation M(value : string, count : int) { }
            """.trimIndent(),
        )
        val elements = program.annotations[0].elements
        assertEquals(listOf("value", "count"), elements.map { it.name })
        assertEquals(listOf("string", "int"), elements.map { it.type.name })
        assertNull(elements[0].default, "没写 '= 默认值' 的元素 default 应该是 null")
        assertNull(elements[1].default)
    }

    /** 元素可以带常量默认值，默认值表达式要留在 AST 里。 */
    @Test
    fun `注解元素带常量默认值`() {
        val program = parse(
            """
            public annotation Marker(value : string = "x", count : int = 0) { }
            """.trimIndent(),
        )
        val m = program.annotations[0]
        assertEquals("Marker", m.name)
        assertEquals(2, m.elements.size)

        val value = m.elements[0]
        assertEquals("value", value.name)
        assertEquals("string", value.type.name)
        val valueDefault = value.default
        assertTrue(valueDefault is StringLit, "默认值应该是字符串字面量，实际 $valueDefault")
        assertEquals("x", (valueDefault as StringLit).value)

        val count = m.elements[1]
        assertEquals("count", count.name)
        assertEquals("int", count.type.name)
        assertTrue(count.default is IntLit, "默认值应该是整数字面量，实际 ${count.default}")
        assertEquals(0, (count.default as IntLit).value)
    }

    /** v1 允许的默认值形状：int/long/float/double/string/char/bool/null，数值可以带一个前导 `-`。 */
    @Test
    fun `注解默认值支持常量字面量与负数`() {
        val program = parse(
            """
            annotation M(
                a : float = -1.5,
                b : int = -3,
                c : bool = true,
                d : string = null,
            ) { }
            """.trimIndent(),
        )
        val elements = program.annotations[0].elements
        assertEquals(listOf("a", "b", "c", "d"), elements.map { it.name })

        val a = elements[0].default
        assertTrue(a is UnaryExpr, "默认值 '-1.5' 应该是一元负号，实际 $a")
        assertEquals(TokenKind.MINUS, (a as UnaryExpr).op)
        // `1.5` 是 double 字面量（float 要写 `1.5f`），所以操作数是 DoubleLit
        assertTrue(a.operand is DoubleLit, "负号的操作数应该是浮点字面量，实际 ${a.operand}")

        val b = elements[1].default
        assertTrue(b is UnaryExpr && b.operand is IntLit, "默认值 '-3' 应该是一元负号 + 整数字面量，实际 $b")

        assertTrue(elements[2].default is BoolLit, "默认值 'true' 应该是布尔字面量")
        assertTrue(elements[3].default is NullLit, "默认值 'null' 应该是 null 字面量")
    }

    /** 伴生实体具名：`companion entity Impl { ... }`。 */
    @Test
    fun `伴生实体可以具名`() {
        val program = parse(
            """
            annotation M {
                companion entity Impl {
                    fn run() -> void { rtn }
                }
            }
            """.trimIndent(),
        )
        val companion = program.annotations[0].companion
        assertTrue(companion != null, "应该有伴生实体")
        assertEquals("Impl", companion.name)
        assertEquals("\$Impl", companion.internalSuffix, "内部名后缀按约定是 '\$' + 伴生名")
        assertEquals(1, companion.methods.size)
        assertEquals("run", companion.methods[0].name)
    }

    /** 伴生实体匿名：`companion entity : Runnable { ... }`，名字留 null。 */
    @Test
    fun `伴生实体可以匿名`() {
        val program = parse(
            """
            annotation M {
                companion entity : Runnable {
                    fn run() -> void { rtn }
                }
            }
            """.trimIndent(),
        )
        val companion = program.annotations[0].companion
        assertTrue(companion != null)
        assertNull(companion.name, "匿名伴生实体的名字应该是 null")
        assertEquals("\$companion", companion.internalSuffix)
        assertEquals(listOf("Runnable"), companion.supertypes.map { it.name })
    }

    /** 伴生实体的父接口列表可以是多个，也可以整个省掉。 */
    @Test
    fun `伴生实体可以带父接口列表`() {
        val program = parse(
            """
            annotation M {
                companion entity Impl : Runnable, Comparable {
                    fn run() -> void { rtn }
                }
            }
            annotation N {
                companion entity Impl { }
            }
            """.trimIndent(),
        )
        val first = program.annotations[0].companion
        assertTrue(first != null)
        assertEquals(listOf("Runnable", "Comparable"), first.supertypes.map { it.name })
        assertEquals("Impl", first.name)

        val second = program.annotations[1].companion
        assertTrue(second != null)
        assertEquals("Impl", second.name)
        assertTrue(second.supertypes.isEmpty(), "没写父接口时应该是空表")
    }

    /** 伴生实体的体是**普通类体**：字段、方法、次构造都收。 */
    @Test
    fun `伴生实体里能写字段方法与构造器`() {
        val program = parse(
            """
            annotation M {
                companion entity Impl : Runnable {
                    var hits : int = 0
                    imutvar name : string = "m"
                    constructor(tag : string) { }
                    fn describe() -> string { rtn "Marker" }
                    fn run() -> void { rtn }
                }
            }
            """.trimIndent(),
        )
        val companion = program.annotations[0].companion
        assertTrue(companion != null)
        assertEquals(listOf("hits", "name"), companion.fields.map { it.name })
        assertTrue(companion.fields[0].mutable, "'var' 的字段 mutable 应该是 true")
        assertTrue(!companion.fields[1].mutable, "'imutvar' 的字段 mutable 应该是 false")
        assertEquals(listOf("describe", "run"), companion.plainMethods.map { it.name })
        assertEquals(1, companion.constructors.size, "应该有一个次构造")
    }

    /** 注解声明自己也能被标注（`@A annotation M { }`）。 */
    @Test
    fun `注解声明自己也能被标注`() {
        val program = parse(
            """
            @A
            annotation M { }
            """.trimIndent(),
        )
        assertEquals(1, program.annotations.size)
        assertEquals(listOf("A"), program.annotations[0].annotations.map { it.name })
    }

    /** 一个文件里可以声明多个注解，顺序保持在 Program.annotations 里。 */
    @Test
    fun `Program 收下所有注解声明`() {
        val program = parse(
            """
            annotation A { }
            public annotation B(value : int = 1) { }
            fn helper() -> void { rtn }
            annotation C { }
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "C"), program.annotations.map { it.name })
        assertEquals(1, program.functions.size, "普通函数照旧进 functions")
    }

    // ============================================================== 使用点

    /** `@M` 无参标在类上。 */
    @Test
    fun `无参注解标在类上`() {
        val program = parse(
            """
            @M
            class C { }
            """.trimIndent(),
        )
        assertEquals(1, program.classes.size)
        val annotations = program.classes[0].annotations
        assertEquals(1, annotations.size)
        assertEquals("M", annotations[0].name)
        assertTrue(annotations[0].arguments.isEmpty(), "无参形式应该是空实参表")
        assertEquals("@M", annotations[0].display())
    }

    /** 位置式实参：`@M("hi")`。 */
    @Test
    fun `位置式实参的注解使用点`() {
        val program = parse(
            """
            @M("hi")
            class C { }
            """.trimIndent(),
        )
        val use = program.classes[0].annotations[0]
        assertEquals("M", use.name)
        assertEquals(1, use.arguments.size)
        val arg = use.arguments[0]
        assertTrue(arg is PositionalAnnotationArg, "应该是位置式实参，实际 $arg")
        assertTrue(arg.value is StringLit, "位置式实参的值应该是字符串字面量")
        assertEquals("hi", (arg.value as StringLit).value)
        assertEquals("@M(\"hi\")", use.display())
    }

    /** 具名实参：`@M(value = "hi", count = 3)`——具名只在注解使用点开放。 */
    @Test
    fun `具名实参的注解使用点`() {
        val program = parse(
            """
            @M(value = "hi", count = 3)
            class C { }
            """.trimIndent(),
        )
        val use = program.classes[0].annotations[0]
        assertEquals(2, use.arguments.size)

        val first = use.arguments[0]
        assertTrue(first is NamedAnnotationArg, "第一个实参应该是具名式，实际 $first")
        assertEquals("value", first.name)
        assertEquals("hi", (first.value as StringLit).value)

        val second = use.arguments[1]
        assertTrue(second is NamedAnnotationArg, "第二个实参应该是具名式，实际 $second")
        assertEquals("count", second.name)
        assertEquals(3, (second.value as IntLit).value)

        assertEquals("@M(value = \"hi\", count = 3)", use.display())
    }

    /** 位置式与具名式可以混写（`@M("hi", count = 3)`）。 */
    @Test
    fun `位置式与具名式实参可以混写`() {
        val program = parse(
            """
            @M("hi", count = 3)
            class C { }
            """.trimIndent(),
        )
        val use = program.classes[0].annotations[0]
        assertEquals(2, use.arguments.size)
        assertTrue(use.arguments[0] is PositionalAnnotationArg)
        assertTrue(use.arguments[1] is NamedAnnotationArg)
        assertEquals("count", (use.arguments[1] as NamedAnnotationArg).name)
    }

    /** 注解能标在接口、方法、字段上（v1 的可标位置）。 */
    @Test
    fun `注解能标在接口方法字段上`() {
        val program = parse(
            """
            @OnInterface
            interface Greeter {
                @OnMethod
                fn greet() -> void
            }
            class C {
                @OnField
                var tally : int = 0
            }
            """.trimIndent(),
        )
        assertEquals(listOf("OnInterface"), program.interfaces[0].annotations.map { it.name })
        assertEquals(listOf("OnMethod"), program.interfaces[0].methods[0].annotations.map { it.name })
        assertEquals(listOf("OnField"), program.classes[0].fields[0].annotations.map { it.name })
    }

    /** `@` 与 `public`/`static`/`override` 顺序任意（两种顺序都收）。 */
    @Test
    fun `注解与修饰词顺序任意`() {
        val program = parse(
            """
            interface I {
                fn run() -> void
            }
            class C : I {
                @M public static fn a() -> void { rtn }
                public @M static fn b() -> void { rtn }
                public static @M fn c() -> void { rtn }
                @M override fn run() -> void { rtn }
                override @M fn other() -> void { rtn }
            }
            """.trimIndent(),
        )
        val methods = program.classes[0].plainMethods
        assertEquals(listOf("a", "b", "c", "run", "other"), methods.map { it.name })
        for (method in methods) {
            assertEquals(listOf("M"), method.annotations.map { it.name }, "方法 ${method.name} 的注解丢了")
        }
        assertTrue(methods[0].isStatic && methods[1].isStatic && methods[2].isStatic, "static 要保持住")
        assertTrue(methods[3].isOverride && methods[4].isOverride, "override 要保持住")
    }

    /** 多个注解可以连写（`@A @B class C`），顺序保持在表里。 */
    @Test
    fun `多个注解可以连写`() {
        val program = parse(
            """
            @A @B
            @C("x")
            class C1 { }
            """.trimIndent(),
        )
        assertEquals(listOf("A", "B", "C"), program.classes[0].annotations.map { it.name })
        assertEquals(1, program.classes[0].annotations[2].arguments.size)
    }

    /** 顶层函数上的注解，以及注解名带限定（`@a.b`、`@Outer::Inner`）。 */
    @Test
    fun `顶层函数注解与限定注解名`() {
        val program = parse(
            """
            @outer.M
            fn top() -> void { rtn }

            @Outer::Inner
            fn another() -> void { rtn }
            """.trimIndent(),
        )
        assertEquals(listOf("outer.M"), program.functions[0].annotations.map { it.name })
        assertEquals(listOf("Outer.Inner"), program.functions[1].annotations.map { it.name })
    }

    // ============================================================== 嵌套类型

    /** `Marker::Impl` —— 类型位置上的嵌套写法。 */
    @Test
    fun `嵌套类型写法解析`() {
        val program = parse(
            """
            annotation Marker { }
            class Holder {
                var spot : Marker::Impl = null
            }
            """.trimIndent(),
        )
        val type = program.classes[0].fields[0].type
        assertEquals("Marker", type.name, "owner（注解名）留在 name 上")
        assertEquals("Impl", type.nested, "伴生名留在 nested 上")
        assertEquals("Marker::Impl", type.display())
    }

    /** 嵌套类型也能出现在参数、返回子句和 `new` 里（类型位置的统一写法）。 */
    @Test
    fun `嵌套类型出现在参数与返回子句里`() {
        val program = parse(
            """
            annotation Marker { }
            fn make(x : Marker::Impl) -> Marker::Impl { rtn x }
            fn direct() -> void {
                var v : Marker::Impl = new Marker::Impl()
            }
            """.trimIndent(),
        )
        assertEquals("Marker::Impl", program.functions[0].params[0].type.display())
        assertEquals("Marker::Impl", program.functions[0].ret.type.display())
    }

    // ============================================================== 证据

    /**
     * **真实证据**：把一整段注解程序解析出来，逐项断言 AST 里都留住了什么
     * （注解名 / 元素默认值 / 伴生实体名与父类型 / 使用点的具名与位置实参 / 嵌套类型 owner）。
     */
    @Test
    fun `解析证据：一整套注解语法的 AST 形状`() {
        val program = parse(
            """
            public annotation Marker(value : string = "x", count : int = 0) {
                companion entity Impl : Runnable {
                    var hits : int = 0
                    fn describe() -> string { rtn "Marker(" + value + "," + count + ")" }
                    fn run() -> void { rtn }
                }
            }

            @Marker(value = "hi", count = 3)
            class Annotated {
                @Marker("field")
                var spot : Marker::Impl = null
                @Marker
                fn go() -> void { rtn }
            }
            """.trimIndent(),
        )

        val decl = program.annotations.single()
        val companion = decl.companion
        assertTrue(companion != null)
        val use = program.classes.single().annotations.single()
        val named = use.arguments.map { (it as NamedAnnotationArg).name }
        val field = program.classes.single().fields.single()
        val method = program.classes.single().plainMethods.single()

        val shape = buildString {
            appendLine("声明     : ${decl.control.keyword.ifEmpty { "(默认)" }} annotation ${decl.name}")
            appendLine("元素     : " + decl.elements.joinToString(", ") { "${it.name} : ${it.type.display()} = ${it.default}" })
            appendLine("伴生实体 : ${companion.name} : ${companion.supertypes.joinToString(", ") { it.display() }}")
            appendLine("  字段   : " + companion.fields.joinToString(", ") { it.name })
            appendLine("  方法   : " + companion.plainMethods.joinToString(", ") { it.name })
            appendLine("使用点   : ${use.display()}  (名字=${use.name}, 具名实参=$named)")
            appendLine("字段     : ${field.name} : ${field.type.display()}  注解=${field.annotations.map { it.display() }}")
            appendLine("方法     : ${method.name}  注解=${method.annotations.map { it.display() }}")
        }
        println(shape)

        assertEquals("Marker", decl.name)
        assertEquals(ControlType.PUBLIC, decl.control)
        assertEquals(listOf("value", "count"), decl.elements.map { it.name })
        assertTrue(decl.elements[0].default is StringLit)
        assertEquals("x", (decl.elements[0].default as StringLit).value)
        assertTrue(decl.elements[1].default is IntLit)
        assertEquals(0, (decl.elements[1].default as IntLit).value)
        assertEquals("Impl", companion.name)
        assertEquals(listOf("Runnable"), companion.supertypes.map { it.name })
        assertEquals(listOf("hits"), companion.fields.map { it.name })
        assertEquals(listOf("describe", "run"), companion.plainMethods.map { it.name })
        assertEquals("Marker", use.name)
        assertEquals(listOf("value", "count"), named)
        assertEquals("Marker::Impl", field.type.display())
        assertEquals(listOf("@Marker(\"field\")"), field.annotations.map { it.display() })
        assertEquals("@Marker", method.annotations.single().display())
        assertTrue(shape.contains("@Marker(value = \"hi\", count = 3)"), "证据字符串里应该有使用点的完整形状：\n$shape")
    }

    // ============================================================== 负例（parser 层诊断）

    /** 注解体里写 `fun` → parser 层报错（注解不是类）。 */
    @Test
    fun `注解体里写 fun 当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M {
                    fn run() -> void { rtn }
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(
            e.message!!.contains("的体里只能写一个 companion entity"),
            "报错要说清注解体里只能写伴生实体，实际：${e.message}",
        )
    }

    /** 注解体里写 `var` → parser 层报错，并指向伴生实体。 */
    @Test
    fun `注解体里写字段当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M {
                    var hits : int = 0
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("字段/方法/构造器都要写进伴生实体里"), "实际：${e.message}")
    }

    /** 注解体里写 `constructor` → parser 层报错。 */
    @Test
    fun `注解体里写构造器当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M {
                    constructor() { }
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("的体里只能写一个 companion entity"), "实际：${e.message}")
    }

    /** 两个 `companion entity` → 报错（体里至多一个）。 */
    @Test
    fun `两个伴生实体当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M {
                    companion entity A { }
                    companion entity B { }
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("的体里只能写一个 companion entity"), "实际：${e.message}")
    }

    /** `A::B::C` → 嵌套类型 v1 只支持一层。 */
    @Test
    fun `嵌套类型两层当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                class Holder {
                    var x : A::B::C = null
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("嵌套类型 v1 只支持一层"), "实际：${e.message}")
    }

    /** 返回子句里的 `::` 仍然是 M0 那条报错（行为一行没改）。 */
    @Test
    fun `返回子句仍不接受双冒号`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                fn bad() :: int { rtn 0 }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertEquals(
            "返回类型子句不再接受 '::'：请用 '->' 或者 ':'，例如 fn bad() -> int { ... }。" +
                "（'::' 现在留给 xxx::xx 这种嵌套写法，例如 Marker::Impl）",
            e.message,
        )
    }

    /** 内置类型不能当嵌套类型的外层（`int::x`）。 */
    @Test
    fun `内置类型没有嵌套类型`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                class Holder {
                    var x : int::x = 0
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("是内置类型，没有嵌套类型"), "实际：${e.message}")
    }

    /** 元素默认值不支持枚举常量（`Color.RED` / 裸名字）。 */
    @Test
    fun `枚举常量默认值暂不支持`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M(kind : int = RED) { }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("的默认值只支持常量字面量"), "实际：${e.message}")
        assertTrue(e.message!!.contains("数组默认值 v1 都还不支持"), "实际：${e.message}")
    }

    /** 元素默认值不支持数组（`new int[3]`）。 */
    @Test
    fun `数组默认值暂不支持`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M(xs : int[] = new int[3]) { }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("的默认值只支持常量字面量"), "实际：${e.message}")
    }

    /** 元素重名 → parser 层报错。 */
    @Test
    fun `注解元素重名当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M(a : int = 1, a : int = 2) { }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("注解元素 'a' 重复定义了"), "实际：${e.message}")
    }

    /** 元素缺类型（`annotation M(value) { }`）→ 报错并给出写法。 */
    @Test
    fun `注解元素缺类型当场报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                annotation M(value) { }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("缺少类型：要写成「名字 : 类型」"), "实际：${e.message}")
    }

    /** 参数上的 `@`：v1 不开放，给一条说清边界的报错。 */
    @Test
    fun `参数上不能写注解`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                fn f(@M x : int) -> void { rtn }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("'@' 不能标在参数上"), "实际：${e.message}")
    }

    /** 局部变量上的 `@`：v1 不开放，给一条说清边界的报错。 */
    @Test
    fun `局部变量上不能写注解`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                fn f() -> void {
                    @M var x : int = 1
                }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("'@' 不能标在语句里（局部变量）"), "实际：${e.message}")
    }

    /** 具名实参之后又写位置式实参 → 报错（位置式没法确定对应哪个元素）。 */
    @Test
    fun `具名实参后面不能再写位置式`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                @M(value = "hi", 3)
                class C { }
                """.trimIndent(),
            )
        }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("注解实参里具名式要写在最后"), "实际：${e.message}")
    }

    /** `@` 后面缺名字 / 缺 `)` 的报错。 */
    @Test
    fun `注解使用点写坏了当场报错`() {
        val noName = assertFailsWith<RsfError> { parse("@ class C { }") }
        assertEquals("parser", noName.phase)
        assertTrue(noName.message!!.contains("'@' 后面缺少注解名"), "实际：${noName.message}")

        val unclosed = assertFailsWith<RsfError> { parse("@M(\"hi\" class C { }") }
        assertEquals("parser", unclosed.phase)
        assertTrue(unclosed.message!!.contains("缺少 ')'"), "实际：${unclosed.message}")
    }

    /** 枚举声明上的 `@`：v1 的可标注位置不含枚举，明确拦下（别让 `@M` 被悄悄丢掉）。 */
    @Test
    fun `枚举声明上不能写注解`() {
        val e = assertFailsWith<RsfError> { parse("@M enum E { A, B }") }
        assertEquals("parser", e.phase)
        assertTrue(e.message!!.contains("注解不能标在枚举声明上"), "实际：${e.message}")
    }

    // ============================================================== 词法

    /** `@` 是一个记号（不是「不认识的字符」），且新关键字都进了关键字表。 */
    @Test
    fun `词法器认识 AT 与三个新关键字`() {
        val source = source("@M annotation companion entity")
        val kinds = Rsf.tokens(source).map { it.kind }
        assertEquals(
            listOf(
                TokenKind.AT,
                TokenKind.IDENT,
                TokenKind.KW_ANNOTATION,
                TokenKind.KW_COMPANION,
                TokenKind.KW_ENTITY,
                TokenKind.EOF,
            ),
            kinds,
        )
        assertEquals("annotation", TokenKind.KW_ANNOTATION.text)
        assertEquals("companion", TokenKind.KW_COMPANION.text)
        assertEquals("entity", TokenKind.KW_ENTITY.text)
        // 三个新关键字是**硬关键字**，不是修饰词（不能塞进 isModifierKeyword）
        assertTrue(!TokenKind.KW_ANNOTATION.isModifierKeyword)
        assertTrue(!TokenKind.KW_COMPANION.isModifierKeyword)
        assertTrue(!TokenKind.KW_ENTITY.isModifierKeyword)
        assertTrue(!TokenKind.KW_ANNOTATION.isControlKeyword)
        assertTrue(!TokenKind.KW_COMPANION.isControlKeyword)
        assertTrue(!TokenKind.KW_ENTITY.isControlKeyword)
    }
}

private fun source(text: String): SourceFile = SourceFile.of("<annotation-syntax>", text)

private fun parse(text: String): org.example.complie.ast.Program =
    Parser(Rsf.tokens(source(text)), source(text)).parseProgram()
