package org.example

import org.example.complie.Rsf
import org.example.complie.ast.NewExpr
import org.example.complie.ast.Program
import org.example.complie.ast.VarDeclStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.sema.AnnotationTargetKind
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 21 M2：注解的**语义层**回归测试（`sema/Checker.kt`）。
 *
 * DESIGN-21.md §6 的四组行为，正例与反例各自独立成测试：
 * - A：注解声明本身（元素白名单 / 不可空 / 默认值 / 伴生实体名字与构造器）；
 * - B：类型解析（`Marker`、`Marker::Impl`、注解不能当父类型）；
 * - C：使用点（`@Marker(...)` 的名字、实参配对、重复标注、访问器同名冲突）；
 * - D：`new`（构造伴生实例、没有伴生实体、赋给 `Marker` 后只剩元素可见）。
 *
 * 语义层**不生成字节码、不改解释器**（M3/M4 的事），所以这里只断言
 * `Rsf.analyze` 的报错与 `SemanticModel` 里给 M3/M4 准备的模型。
 * 反例一律断言错误消息里的关键片段 + 报错位置（行号）。
 */
class AnnotationSemaTest {

    // ============================================================== A：注解声明

    /** A1 正例：string / 基本类型 / 枚举 / 注解 / 它们的一维数组都在白名单里。 */
    @Test
    fun `元素白名单：string、基本类型、枚举、注解、一维数组都能用`() {
        val (_, model) = analyzeProgram(
            """
            enum TokenKind { A, B }

            annotation Inner(flag : bool = false)

            annotation Marker(
                name : string,
                n : int,
                kind : TokenKind,
                inner : Inner,
                xs : int[],
            )

            fn main() -> void { println("ok") }
            """,
        )
        val spec = assertNotNull(model.annotationOf("Marker"))
        assertEquals(listOf("name", "n", "kind", "inner", "xs"), spec.elements.map { it.name })
        assertEquals(RsfType.STRING, spec.elements[0].type)
        assertEquals(RsfType.INT, spec.elements[1].type)
        assertEquals(RsfType.external("TokenKind"), spec.elements[2].type)
        assertTrue(model.isAnnotationType(spec.elements[3].type))
        assertEquals(RsfType.INT.arrayOf(1), spec.elements[4].type)
        // 只有 Inner 的 flag 有默认值；元素自己有没有默认值是模型要暴露的信息
        assertEquals(listOf(false, false, false, false, false), spec.elements.map { it.hasDefault })
        assertTrue(assertNotNull(model.annotationOf("Inner")).elements[0].hasDefault)
    }

    /** A1 反例：普通类不能当注解元素。 */
    @Test
    fun `元素类型不合法：普通类`() {
        checkFails(
            """
            class Dog {
                var x : int = 0
            }

            annotation Marker(value : Dog)

            fn main() -> void { println("ok") }
            """,
            line = 5,
            "注解元素 'value' 的类型 'Dog' 不合法",
            "注解元素只能是 string、基本类型、枚举、注解，或它们的数组",
        )
    }

    /** A1 反例：注解元素只到一维数组。 */
    @Test
    fun `元素类型不合法：二维数组`() {
        checkFails(
            """
            annotation Marker(grid : int[][])

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "注解元素 'grid' 的类型 'int[][]' 不合法",
        )
    }

    /** A1 反例：void 不是注解元素。 */
    @Test
    fun `元素类型不合法：void`() {
        checkFails(
            """
            annotation Marker(nothing : void)

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "注解元素 'nothing' 的类型 'void' 不合法",
        )
    }

    /** A2 反例：注解元素不能是可空类型（JVM 里注解元素不能是 null）。 */
    @Test
    fun `元素不能是可空类型`() {
        checkFails(
            """
            annotation Marker(value : ?string)

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "注解元素 'value' 不能是可空类型：注解元素在 JVM 里不能是 null",
        )
    }

    /**
     * A2 反例：可空的基本类型（注意 `?int[]` 这种「可空数组」写法在 **Parser** 就被拒了：
     * 「数组本来就是可空的……去掉 '?' 写成 'int[]'」，所以语义层这条守卫只需要基本类型这条路径）。
     */
    @Test
    fun `元素不能是可空的基本类型`() {
        checkFails(
            """
            annotation Marker(count : ?int)

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "注解元素 'count' 不能是可空类型：注解元素在 JVM 里不能是 null",
        )
    }

    /** A 正例（默认值）：默认值类型对得上就通过，且进模型。 */
    @Test
    fun `元素默认值类型要对得上`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string = "x", count : int = 3, flag : bool = true)

            fn main() -> void { println("ok") }
            """,
        )
        val spec = assertNotNull(model.annotationOf("Marker"))
        assertTrue(spec.elements.all { it.hasDefault })
    }

    /** A 反例（默认值）：`int` 的元素写字符串默认值。 */
    @Test
    fun `元素默认值类型不对要报错`() {
        checkFails(
            """
            annotation Marker(count : int = "x")

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "注解元素 'count' 的默认值",
            "和声明的 int 对不上",
        )
    }

    /** A3 反例：伴生实体的名字不能和元素重名。 */
    @Test
    fun `伴生实体名字不能和元素重名`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity value {
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 2,
            "companion entity 的名字 'value' 和注解元素重名了：换一个名字",
        )
    }

    /** A4 反例：伴生实体不能继承类（类继承留到后面那一阶段）。 */
    @Test
    fun `伴生实体不能继承类`() {
        checkFails(
            """
            class Base {
                var x : int = 0
            }

            annotation Marker(value : string) {
                companion entity Impl : Base {
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 6,
            "companion entity 暂时只能继承接口：rsf 还没有类继承",
            "继承 'Base' 这类写法留到类继承那一阶段",
        )
    }

    /** A4 正例：伴生实体可以继承接口，父接口列表 = 注解接口 + 自己写的接口。 */
    @Test
    fun `伴生实体可以继承接口`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string) {
                companion entity Impl : Runnable {
                    fn describe() -> string { rtn value }
                    fn run() -> void { println(value) }
                }
            }

            fn main() -> void { println("ok") }
            """,
        )
        val supers = model.superInterfacesOf("Marker\$Impl").map { it.name }
        assertEquals(listOf("Marker", "Runnable"), supers)
    }

    /** A5 反例：手写构造器少了元素对应的参数。 */
    @Test
    fun `伴生构造器缺少元素对应参数`() {
        checkFails(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    constructor(value : string) { }
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "companion entity 的构造器参数必须和注解元素一致：缺少 'count'",
        )
    }

    /** A5 反例：手写构造器参数顺序与元素表不一致。 */
    @Test
    fun `伴生构造器参数顺序必须一致`() {
        checkFails(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    constructor(count : int, value : string) { }
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "companion entity 的构造器参数必须和注解元素一致",
            "第 1 个参数应该是 'value'，写的是 'count'",
            "名字和顺序都要和元素表一样：value、count",
        )
    }

    /** A5 反例：手写构造器参数名字对了但类型不对。 */
    @Test
    fun `伴生构造器参数类型必须一致`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    constructor(value : int) { }
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "companion entity 的构造器参数 'value' 的类型应该是 string，写的是 int",
        )
    }

    /** A5 反例：手写构造器多出了元素表里没有的参数。 */
    @Test
    fun `伴生构造器多出参数`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    constructor(value : string, extra : int) { }
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "companion entity 的构造器参数必须和注解元素一致：多出了 'extra'",
        )
    }

    /** A 正例：不写 `constructor` 时自动生成主构造，参数 = 元素表。 */
    @Test
    fun `没写构造器时自动生成主构造`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void { println("ok") }
            """,
        )
        val companion = assertNotNull(model.companionOf("Marker"))
        assertEquals("Marker\$Impl", companion.jvmName)
        assertTrue(companion.constructor.isPrimary)
        assertTrue(companion.constructor.isConstructor)
        assertEquals(listOf("value", "count"), companion.constructor.params.map { it.name })
        assertEquals(
            listOf("string", "int"),
            companion.constructor.params.map { it.type.name },
        )
        // 元素的同名字段 + 公开访问器都是自动获得的（§1）
        assertEquals(listOf("value", "count"), companion.elementFields.map { it.name })
        assertEquals(listOf("value", "count"), companion.accessors.map { it.name })
        assertEquals("value()", companion.accessors[0].name + "()")
    }

    /**
     * 设计约束：伴生实体在语义层**就是一个类**（合成 `ClassDecl` + 走普通类检查路径）。
     *
     * 这是 M3 能把它当普通类发射的前提：它要有类模型、有父接口列表，
     * 而且「谁是谁的伴生类」这个反向映射要在模型里查得到。
     */
    @Test
    fun `伴生实体在语义层是一个类`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void { println("ok") }
            """,
        )
        val companion = assertNotNull(model.companionOf("Marker"))
        assertNotNull(model.classModel.classes["Marker\$Impl"])
        assertEquals(listOf("value", "hits"), companion.decl.fields.map { it.name })
        assertEquals("Marker", assertNotNull(model.companionAnnotationOf("Marker\$Impl")).name)
        assertTrue(model.isCompanionClass("Marker\$Impl"))
        assertTrue(!model.isCompanionClass("Marker"))
        // 元素访问器在伴生类上是普通方法（M3 当普通类发射时就是在这些方法里返回字段）
        assertNotNull(model.elementAccessorOf("Marker", "value"))
        assertNotNull(model.elementFieldOf("Marker", "value"))
        assertNull(model.elementAccessorOf("Marker", "describe"))
    }

    // ============================================================== B：类型解析

    /** B7 正例：`Marker` 与 `Marker::Impl` 都能当类型用（不再报未知类型）。 */
    @Test
    fun `注解名与伴生名都能当类型`() {
        val (program, model) = analyzeProgram(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    fn describe() -> string { rtn "d" }
                }
            }

            fn takes(a : Marker, b : Marker::Impl) -> void {
                println(a.value)
                println(b.describe())
            }

            fn main() -> void {
                var a : Marker = new Marker("hi")
                var b : Marker::Impl = new Marker::Impl("hi", 3)
                takes(a, b)
            }
            """,
        )
        val takes = program.functions.first { it.name == "takes" }
        assertEquals(listOf(RsfType.external("Marker"), RsfType.external("Marker\$Impl")), model.paramTypesOf(takes))
        assertTrue(model.isAnnotationType(RsfType.external("Marker")))
        assertTrue(!model.isAnnotationType(RsfType.external("Marker\$Impl")))
    }

    /** B7 反例：伴生名对不上。 */
    @Test
    fun `伴生名写错要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void {
                var x : Marker::Nope = null
                println("ok")
            }
            """,
            line = 8,
            "注解 'Marker' 的 companion entity 叫 'Impl'，不是 'Nope'：要写就写 Marker::Impl",
        )
    }

    /** B7 反例：注解没有伴生实体，`Marker::Impl` 没有意义。 */
    @Test
    fun `没有伴生实体时写伴生类型要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            fn main() -> void {
                var x : Marker::Impl = null
                println("ok")
            }
            """,
            line = 4,
            "注解 'Marker' 没有 companion entity，所以没有 'Marker::Impl' 这个类型",
        )
    }

    /** B7 反例：伴生实体没写名字时，`::` 里写不出名字。 */
    @Test
    fun `没写名字的伴生实体写不出类型`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity {
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void {
                var x : Marker::Impl = null
                println("ok")
            }
            """,
            line = 8,
            "注解 'Marker' 的 companion entity 没有名字，写不出它的类型",
            "给它起个名字",
        )
    }

    /** B7 反例：`::` 左边不是注解。 */
    @Test
    fun `不是注解的类型写嵌套名要报错`() {
        checkFails(
            """
            class Dog {
                var x : int = 0
            }

            fn main() -> void {
                var x : Dog::Impl = null
                println("ok")
            }
            """,
            line = 6,
            "'Dog' 不是注解，没有 'Dog::Impl' 这种嵌套类型",
            "'::' 只能写成「注解名 :: 伴生名」（例如 Marker::Impl）",
        )
    }

    /** B8 反例：注解不能当父类型。 */
    @Test
    fun `注解不能当父类型`() {
        checkFails(
            """
            annotation Marker(value : string)

            class Point : Marker {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "'Marker' 是注解，不能当父类型：注解只能用来标注（@Marker）或 new 出伴生实例",
        )
    }

    /** B8 正例：注解可以标在类上（而不是当父类型）。 */
    @Test
    fun `注解可以标在类上`() {
        val (program, model) = analyzeProgram(
            """
            annotation Marker(value : string)

            @Marker("hi")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
        )
        val point = assertNotNull(program.classes.firstOrNull { it.name == "Point" })
        val site = assertNotNull(model.useSiteOf(point.annotations[0]))
        assertEquals("Marker", site.annotation.name)
        assertEquals(AnnotationTargetKind.CLASS, site.target.kind)
    }

    // ============================================================== C：使用点

    /** C9 反例：注解名不存在。 */
    @Test
    fun `注解名不存在要报错`() {
        checkFails(
            """
            @Nope("hi")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "没有这个注解：'@Nope'",
            "这份程序里还没有任何 annotation 声明",
        )
    }

    /** C9 反例：已经声明过别的注解时，提示里给出已知注解。 */
    @Test
    fun `注解名不存在时提示已知注解`() {
        checkFails(
            """
            @Nope("hi")
            class Point {
                var x : int = 0
            }

            annotation Tag(value : int = 1)

            fn main() -> void { println("ok") }
            """,
            line = 1,
            "没有这个注解：'@Nope'",
            "已定义的注解：Tag",
        )
    }

    /** C10 正例：位置式按顺序、具名按名字，没给的用默认值。 */
    @Test
    fun `实参配对：位置式与具名式都能用`() {
        val (program, model) = analyzeProgram(
            """
            annotation Marker(value : string, count : int, flag : bool = false)

            @Marker(count = 3, value = "hi")
            class Point {
                var x : int = 0
            }

            @Marker("hi", count = 3)
            class Line {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
        )
        val point = assertNotNull(program.classes.firstOrNull { it.name == "Point" })
        val line = assertNotNull(program.classes.firstOrNull { it.name == "Line" })
        assertEquals(2, point.annotations[0].arguments.size)
        assertEquals(2, line.annotations[0].arguments.size)
        assertEquals(2, model.annotationUses.size)
        assertEquals(
            listOf("Point", "Line"),
            model.annotationUses.map { it.target.hostName },
        )
    }

    /** C10 反例：元素没有默认值就必须给值。 */
    @Test
    fun `没有默认值的元素必须给值`() {
        checkFails(
            """
            annotation Marker(value : string, count : int)

            @Marker("hi")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "注解 'Marker' 的元素 'count' 没有默认值：写 @Marker 时必须给出 count",
        )
    }

    /** C10 反例：具名实参写了一个不存在的元素。 */
    @Test
    fun `具名实参元素不存在要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            @Marker(nope = 1)
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "注解 'Marker' 没有元素 'nope'",
            "'Marker' 的元素有：value",
        )
    }

    /** C10 反例：实参类型和元素类型对不上。 */
    @Test
    fun `实参类型对不上要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            @Marker(3)
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "注解 'Marker' 的元素 'value' 需要 string，实际给的是 int（3）",
        )
    }

    /** C10 反例：位置式实参给多了。 */
    @Test
    fun `实参个数给多了要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            @Marker("a", "b")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "注解 'Marker' 的实参个数不对：给了 2 个，但它的元素只有 1 个",
        )
    }

    /** C10 反例：同一个元素给了两次值。 */
    @Test
    fun `同一个元素给两次值要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            @Marker(value = "a", value = "b")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 3,
            "注解 'Marker' 的元素 'value' 给了两次值",
        )
    }

    /** C10 正例：枚举常量当注解实参。 */
    @Test
    fun `枚举常量可以当注解实参`() {
        analyzeProgram(
            """
            enum TokenKind { A, B }

            annotation Marker(kind : TokenKind, name : string = "x")

            @Marker(TokenKind.A)
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
        )
    }

    /** C11 反例：同一个类上重复标同一个注解。 */
    @Test
    fun `同一个目标重复标同一个注解要报错`() {
        checkFails(
            """
            annotation Marker(value : string)

            @Marker("a")
            @Marker("b")
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
            line = 4,
            "类 'Point' 上重复标了 '@Marker'：同一个地方同一个注解只能标一次",
        )
    }

    /** C11 反例：方法上重复标同一个注解。 */
    @Test
    fun `方法上重复标同一个注解要报错`() {
        checkFails(
            """
            annotation Tag(value : int = 1)

            class Point {
                var x : int = 0

                @Tag(1)
                @Tag(2)
                fn shift() -> void { x = x + 1 }
            }

            fn main() -> void { println("ok") }
            """,
            line = 7,
            "方法 'Point.shift' 上重复标了 '@Tag'：同一个地方同一个注解只能标一次",
        )
    }

    /** C11 正例：标两个**不同**的注解是允许的。 */
    @Test
    fun `同一个目标可以标不同的注解`() {
        analyzeProgram(
            """
            annotation A(value : int = 1)
            annotation B(value : string = "x")

            @A
            @B
            class Point {
                var x : int = 0
            }

            fn main() -> void { println("ok") }
            """,
        )
    }

    /** C12 反例：访问器名字撞上用户已有的方法。 */
    @Test
    fun `访问器撞上已有方法要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            @Marker("hi")
            class Point {
                var x : int = 0
                fn Marker() -> string { rtn "already here" }
            }

            fn main() -> void { println("ok") }
            """,
            line = 7,
            "类 'Point' 里已经有了成员 'Marker'：方案 B 要在它上面生成注解访问器 'Marker(...)'，名字撞上了",
        )
    }

    /** C12 反例：访问器名字撞上用户已有的字段。 */
    @Test
    fun `访问器撞上已有字段要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            @Marker("hi")
            class Point {
                var Marker : string = "already here"
            }

            fn main() -> void { println("ok") }
            """,
            line = 7,
            "类 'Point' 里已经有了成员 'Marker'",
        )
    }

    /** 设计约束（方案 B 的规格）：使用点要配出访问器规格，模型里查得到。 */
    @Test
    fun `使用点配出方案B的访问器规格`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            @Marker("on the class")
            class Point {
                @Marker("on the field")
                var x : int = 0

                @Marker("on the method")
                fn shift(dx : int) -> void { x = x + dx }
            }

            fn main() -> void { println("ok") }
            """,
        )
        val accessors = model.accessorsOn("Point")
        assertEquals(3, accessors.size)
        assertEquals(
            listOf(
                "pub static Marker\$Impl Marker()",
                "pub static Marker\$Impl Marker(string member)",
                "pub static Marker\$Impl Marker(string member)",
            ),
            accessors.map { it.signature() },
        )
        assertEquals(listOf(null, "x", "shift"), accessors.map { it.memberName })
        assertEquals(listOf("x", "shift"), model.accessorsOn("Point").drop(1).map { it.target.memberName })
    }

    /** 可标注位置（v1）：类 / 接口 / 注解声明 / 方法 / 字段。 */
    @Test
    fun `可标注位置覆盖类接口注解方法字段`() {
        val (_, model) = analyzeProgram(
            """
            annotation Tag(value : int = 1)

            @Tag(1)
            interface Greeter {
                @Tag(2)
                fn greet(name : string) -> void
            }

            @Tag(3)
            annotation Holder(value : int = 0)

            @Tag(4)
            class Point {
                @Tag(5)
                var x : int = 0

                @Tag(6)
                fn shift() -> void { x = x + 1 }
            }

            fn main() -> void { println("ok") }
            """,
        )
        assertEquals(
            listOf(
                AnnotationTargetKind.ANNOTATION,
                AnnotationTargetKind.INTERFACE,
                AnnotationTargetKind.METHOD,
                AnnotationTargetKind.CLASS,
                AnnotationTargetKind.FIELD,
                AnnotationTargetKind.METHOD,
            ),
            model.annotationUses.map { it.target.kind },
        )
        assertEquals(
            listOf("注解 'Holder'", "接口 'Greeter'", "方法 'Greeter.greet'", "类 'Point'", "字段 'Point.x'", "方法 'Point.shift'"),
            model.annotationUses.map { it.target.label },
        )
    }

    /** 顶层函数能写注解（v1），但没有类型可挂访问器，所以不带 companion entity 才行。 */
    @Test
    fun `顶层函数上的注解允许但没有访问器`() {
        val (_, model) = analyzeProgram(
            """
            annotation Tag(value : int = 1)

            @Tag(7)
            fn hello() -> void { println("hi") }

            @Tag
            fn main() -> void { hello() }
            """,
        )
        assertEquals(2, model.annotationUses.size)
        assertEquals(listOf(null, null), model.annotationUses.map { it.target.hostName })
        assertTrue(model.annotationAccessors.isEmpty())
    }

    /** 反例：带 companion entity 的注解不能标在顶层函数上（访问器要挂在类型上）。 */
    @Test
    fun `带伴生实体的注解不能标在顶层函数上`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            @Marker("hi")
            fn main() -> void { println("ok") }
            """,
            line = 7,
            "顶层函数 'main' 上不能标带 companion entity 的注解 '@Marker'",
            "方案 B 的访问器要挂在类型上",
        )
    }

    // ============================================================== D：new

    /** D13 正例：`new Marker(...)` 与 `new Marker::Impl(...)` 都构造伴生实例。 */
    @Test
    fun `new 注解与 new 伴生名都合法`() {
        val (program, model) = analyzeProgram(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn "Marker(" + value + ")" }
                    fn run() -> void { println("run") }
                }
            }

            fn main() -> void {
                var a : Marker::Impl = new Marker("hi")
                var b : Marker::Impl = new Marker::Impl("yo", 3)
                println(a.describe())
                println(b.describe())
                b.run()
                println(b.hits)
            }
            """,
        )
        val main = program.functions.first { it.name == "main" }
        val statements = assertNotNull(main.body).statements
        val first = assertNotNull((statements[0] as VarDeclStmt).initializer as NewExpr)
        val second = assertNotNull((statements[1] as VarDeclStmt).initializer as NewExpr)

        assertEquals(RsfType.external("Marker\$Impl"), model.typeOf(first))
        assertEquals(RsfType.external("Marker\$Impl"), model.typeOf(second))
        assertTrue(model.isAnnotationConstruction(first))
        assertTrue(model.isAnnotationConstruction(second))
        assertEquals("Marker", assertNotNull(model.annotationConstructionOf(first)).name)
        assertEquals("Marker", assertNotNull(model.annotationConstructionOf(second)).name)
        // 两个后端要靠 isConstruction 知道这是构造（解释器 / 字节码共用）
        assertTrue(model.isConstruction(first))
        assertEquals("Marker", model.constructionName(first))
    }

    /** D13 反例：注解没有伴生实体时不能 new。 */
    @Test
    fun `没有伴生实体不能 new`() {
        checkFails(
            """
            annotation Marker(value : string)

            fn main() -> void {
                var a : Marker = new Marker("hi")
                println(a.value)
            }
            """,
            line = 4,
            "注解 'Marker' 没有 companion entity，不能 new：要带行为就给它加一个 companion entity 块",
        )
    }

    /** D13 反例：`new Marker(...)` 少给了没有默认值的元素。 */
    @Test
    fun `new 少给没有默认值的元素要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void {
                var a : Marker::Impl = new Marker()
                println(a.describe())
            }
            """,
            line = 8,
            "new Marker(...) 少了元素 'value'：它没有默认值",
            "'Marker' 的元素有：value",
        )
    }

    /** D13 反例：`new Marker(...)` 实参类型不对。 */
    @Test
    fun `new 实参类型不对要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void {
                var a : Marker::Impl = new Marker(3)
                println(a.describe())
            }
            """,
            line = 8,
            "new Marker(...) 的第 1 个实参类型不对：元素 'value' 需要 string，实际给的是 int（3）",
        )
    }

    /** D14 正例：赋给 `Marker` 的变量只剩元素方法可见（字段式与方法式都行）。 */
    @Test
    fun `赋给注解类型的变量只剩元素可见`() {
        val (_, model) = analyzeProgram(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi", 3)
                println(m.value)
                println(m.count())
                var impl : Marker::Impl = new Marker::Impl("yo", 4)
                println(impl.describe())
                println(impl.hits)
            }
            """,
        )
        assertNotNull(model.elementAccessorOf("Marker", "count"))
        assertNotNull(model.elementFieldOf("Marker", "value"))
    }

    /** D14 反例：行为方法（companion entity 自己的方法）在注解类型上不可见。 */
    @Test
    fun `行为方法在注解类型上不可见`() {
        checkFails(
            """
            annotation Marker(value : string, count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn "d" }
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi")
                println(m.describe())
            }
            """,
            line = 10,
            "注解 'Marker' 上没有元素 'describe'",
            "只有伴生类的对象看得见",
        )
    }

    /** D14 反例：注解类型上没有的元素名一律报错（连字段也不是随便读）。 */
    @Test
    fun `注解类型上没有的元素名要报错`() {
        checkFails(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi")
                println(m.nope)
            }
            """,
            line = 9,
            "注解 'Marker' 上没有元素 'nope'",
            "'Marker' 的元素有：value",
        )
    }

    /** 伴生类型（`Marker::Impl`）就是普通类：行为方法、字段都看得见。 */
    @Test
    fun `伴生类型上行为方法可见`() {
        val (program, model) = analyzeProgram(
            """
            annotation Marker(value : string) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void {
                var impl : Marker::Impl = new Marker("hi")
                impl.hits = impl.hits + 1
                println(impl.describe())
            }
            """,
        )
        val main = program.functions.first { it.name == "main" }
        assertNotNull(model.classModel.classes["Marker\$Impl"])
        // `var impl` / `impl.hits = ...` / `println(...)` 三句
        assertEquals(3, assertNotNull(main.body).statements.size)
    }
}

// ---------------------------------------------------------------- 测试助手

/**
 * 源码文本 → [SourceFile]。
 *
 * `trimIndent()` 同时做两件事：去掉 Kotlin 源码里的公共缩进，以及**去掉开头那个空行**——
 * 于是测试里写的行号和用户看到的行号一致（第一个非空行就是第 1 行）。
 */
private fun source(text: String): SourceFile = SourceFile.of("<annotation-sema>", text.trimIndent())

private fun analyze(text: String): SemanticModel = Rsf.analyze(source(text))

/** 解析 + 检查，返回 AST 与模型（要翻 AST 找表达式时用）。 */
private fun analyzeProgram(text: String): Pair<Program, SemanticModel> {
    val src = source(text)
    val program = Rsf.parse(src)
    return program to Rsf.analyze(program, src)
}

/** 断言这段源码在语义层报错：阶段是 checker、行号是 [line]、消息里有每个片段。 */
private fun checkFails(text: String, line: Int, vararg fragments: String): RsfError {
    val error = assertFailsWith<RsfError> { analyze(text) }
    assertEquals("checker", error.phase, "报错阶段不对：${error.detail}")
    assertEquals(line, error.line, "报错行不对（应该在第 $line 行）：${error.detail}")
    for (fragment in fragments) {
        assertTrue(
            error.detail.contains(fragment),
            "报错消息里没有 '$fragment'：实际是 ${error.detail}",
        )
    }
    return error
}
