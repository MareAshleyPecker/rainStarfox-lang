package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 21 M3：注解的**代码生成**回归测试（`codegen/BytecodeCompiler.kt`）。
 *
 * 解释器侧的注解支持是 M4 的事，所以这里**不比对两端 stdout**，而是两头都查：
 * - **类文件层**：用 ASM `ClassReader` 读产物，断言注解接口的头（`0x2601`）、
 *   元素方法的 `AnnotationDefault`、`@Retention(RUNTIME)` 元注解、使用点的
 *   `RuntimeVisibleAnnotations`、枚举元素写出来是 `e` 标签、访问器的 `ACC_SYNCHRONIZED`
 *   （宿主是接口/注解声明时**必须没有**这一位）、元素访问器调用发的是 `INVOKEINTERFACE`；
 * - **运行期层**：`Rsf.build` 出来的 .class 交给自定义 [ClassLoader] 按需加载，
 *   用纯 JDK 反射断言 `getAnnotation` 读得到、元素默认值生效、伴生类的元素字段、
 *   `annotationType()`、方案 B 访问器的值与懒构造缓存、传错 member 返回 null。
 *
 * 为什么两头都要：类文件的形状错了运行期可能**静默**地读不出来（漏 `@Retention`
 * 就是最典型的一种——`getAnnotation` 不报错，只是返回 null），只查一头会漏。
 *
 * 覆盖（对照任务书要求的 (1)~(5)）：
 * (1) 注解接口（头/父接口/元素方法/默认值/`@Retention`）；
 * (2) 伴生类（元素字段按构造器参数赋值、`annotationType()`、按普通类发）；
 * (3) 使用点（类/接口/注解声明/方法/字段，常量按元素类型装箱，枚举写 `e`）；
 * (4) 方案 B 的访问器（类型级 / 成员级、懒构造、同实例、传错 member 返回 null）；
 * (5) 元素访问器调用是 `INVOKEINTERFACE`；
 * 外加反例（数组元素 / 非常量值只能在 codegen 阶段拦下）与不动点（无注解源码不多任何东西）。
 */
class AnnotationCodegenTest {

    // ============================================================ (1) 注解接口

    /** (1) 注解接口的头：`0x2601` + 父接口 `java.lang.annotation.Annotation` + 无字段无构造器。 */
    @Test
    fun `注解接口的头是 ACC_ANNOTATION 且父接口是 Annotation`() {
        val c = compile(
            """
            enum Level { LOW, HIGH }

            annotation Marker(value : string = "x", count : int = 0, level : Level) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnHead",
        )

        val marker = c.read("Marker")
        assertEquals(0x2601, marker.access, "注解接口的头应该是 ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT|ACC_ANNOTATION")
        assertEquals("Marker", marker.name)
        assertEquals("java/lang/Object", marker.superName)
        assertEquals(listOf("java/lang/annotation/Annotation"), marker.interfaces)
        assertEquals(0, marker.fields.size, "注解接口不能有字段（伴生类才有）")
        assertTrue(marker.methods.none { it.name == "<init>" }, "注解接口不能有构造器")
        assertEquals(listOf("value", "count", "level"), marker.methods.map { it.name })
        for (m in marker.methods) {
            assertEquals(0x0401, m.access, "元素方法 '${m.name}' 应该是 pub abstract")
        }
        assertEquals("()Ljava/lang/String;", marker.method("value").desc)
        assertEquals("()I", marker.method("count").desc)
        assertEquals("()LLevel;", marker.method("level").desc)
    }

    /** (1) 有默认值的元素写 `AnnotationDefault`，没默认值的不写。 */
    @Test
    fun `有默认值的元素写 AnnotationDefault`() {
        val c = compile(
            """
            annotation Marker(value : string = "x", count : int = 0, level : Level, ok : bool = true) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            enum Level { LOW, HIGH }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnDefault",
        )

        val marker = c.read("Marker")
        assertEquals("x", marker.method("value").default)
        assertEquals(0, marker.method("count").default)
        assertNull(marker.method("level").default, "没写默认值的元素不该有 AnnotationDefault")
        assertEquals(true, marker.method("ok").default)

        // 反射那一侧读的是同一个 attribute
        val cls = c.load("Marker")
        assertEquals("x", cls.getMethod("value").defaultValue)
        assertEquals(0, cls.getMethod("count").defaultValue)
        assertNull(cls.getMethod("level").defaultValue)
    }

    /** (1) **必须**带 `@Retention(RUNTIME)`：漏了运行期 `getAnnotation` 会静默返回 null。 */
    @Test
    fun `注解接口带 Retention RUNTIME 元注解`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker("cls")
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnRetention",
        )

        val retention = c.read("Marker").annotations.single { it.desc == "Ljava/lang/annotation/Retention;" }
        assertEquals(
            EnumVal("Ljava/lang/annotation/RetentionPolicy;", "RUNTIME"),
            retention.values["value"],
            "@Retention 的值必须是 RUNTIME（CLASS 保留的话运行期读不到）",
        )
        // 运行期确实生效：这个断言在漏 @Retention 时会变成 null
        val cls = c.load("Marker")
        val ann: Retention = assertNotNull(cls.getAnnotation(Retention::class.java))
        assertEquals(RetentionPolicy.RUNTIME, ann.value)
        assertTrue(cls.isAnnotation, "运行期看它也得是注解类型")
        assertTrue(Modifier.isAbstract(cls.modifiers), "注解接口在反射里是 abstract 的")
        assertEquals(0, cls.declaredFields.size)
        assertEquals(0, cls.declaredConstructors.size)
    }

    // ============================================================ (3) 使用点

    /** (3) 类级使用点：反射读得到，且每个元素按声明类型装箱。 */
    @Test
    fun `类级使用点运行期读得到且元素值按类型装箱`() {
        val c = compile(
            """
            annotation Marker(
                name : string = "x",
                n : int = 0,
                flag : bool = false,
                ratio : double = 0.0,
                letter : char = 'a',
                small : byte = 0,
                big : long = 0,
            )

            @Marker(name = "cls", n = 7, flag = true, ratio = 1.5, letter = 'z', small = 3, big = 9)
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnClassUse",
        )

        val marker = c.classOf("Marker")
        val onClass = assertNotNull(
            c.load("Demo").getAnnotation(marker),
            "类级使用点读不到——多半是 @Retention 或 visitAnnotation 的位置不对",
        )
        assertEquals("cls", member(onClass, "name"))
        assertEquals(7, member(onClass, "n"))
        assertEquals(true, member(onClass, "flag"))
        assertEquals(1.5, member(onClass, "ratio"))
        assertEquals('z', member(onClass, "letter"))
        assertEquals(3.toByte(), member(onClass, "small"), "byte 元素必须装成 Byte（装成 Integer 会 AnnotationTypeMismatchException）")
        assertEquals(9L, member(onClass, "big"))
    }

    /** (3) 方法级使用点：必须写在 `mv.visitEnd()` 之前，否则 ASM 直接丢掉。 */
    @Test
    fun `方法级使用点写在 RuntimeVisibleAnnotations 上`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            class Demo {
                @Marker("method")
                fn greet() -> string { rtn "hi" }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnMethodUse",
        )

        val shape = c.read("Demo")
        val greet = shape.method("greet")
        val ann = greet.annotations.single { it.desc == "LMarker;" }
        assertEquals("method", ann.values["value"])

        val onMethod = assertNotNull(
            c.load("Demo").getDeclaredMethod("greet").getAnnotation(c.classOf("Marker")),
        )
        assertEquals("method", member(onMethod, "value"))
    }

    /** (3) 字段级使用点：同样必须在 `fv.visitEnd()` 之前。 */
    @Test
    fun `字段级使用点写在 RuntimeVisibleAnnotations 上`() {
        val c = compile(
            """
            annotation Marker(value : string = "x", n : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            class Demo {
                @Marker("field", 2)
                var tag : string = "t"
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnFieldUse",
        )

        val tag = c.read("Demo").field("tag")
        val ann = tag.annotations.single { it.desc == "LMarker;" }
        assertEquals("field", ann.values["value"])
        assertEquals(2, ann.values["n"])

        val onField = assertNotNull(
            c.load("Demo").getDeclaredField("tag").getAnnotation(c.classOf("Marker")),
        )
        assertEquals("field", member(onField, "value"))
        assertEquals(2, member(onField, "n"))
    }

    /** (3) 接口级使用点。 */
    @Test
    fun `接口级使用点运行期读得到`() {
        val c = compile(
            """
            annotation Marker(value : string = "x")

            @Marker("iface")
            interface Named {
                fn name() -> string
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnIfaceUse",
        )

        val shape = c.read("Named")
        assertEquals(Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_PUBLIC, shape.access)
        assertEquals("iface", shape.annotations.single { it.desc == "LMarker;" }.values["value"])
        val onIface = assertNotNull(c.load("Named").getAnnotation(c.classOf("Marker")))
        assertEquals("iface", member(onIface, "value"))
    }

    /** (3) 注解声明自己身上的元注解（`OnAnnotation`）。 */
    @Test
    fun `注解声明自己能标别的注解`() {
        val c = compile(
            """
            annotation Marker(value : string = "x")

            @Marker("meta")
            annotation Other(text : string = "o")

            fn main() -> void { println("ok") }
            """,
            "RsfAnnMetaUse",
        )

        val other = c.read("Other")
        assertEquals(
            "meta",
            other.annotations.single { it.desc == "LMarker;" }.values["value"],
            "注解声明上的注解也要写出来",
        )
        // 元注解 + 自己的 @Retention 都在
        assertTrue(other.annotations.any { it.desc == "Ljava/lang/annotation/Retention;" })
        val onOther = assertNotNull(c.load("Other").getAnnotation(c.classOf("Marker")))
        assertEquals("meta", member(onOther, "value"))
    }

    /** (3) 枚举元素写成 `e` 标签（枚举常量值），不是字符串也不是 int。 */
    @Test
    fun `枚举元素写成 e 标签`() {
        val c = compile(
            """
            enum Level { LOW, HIGH }

            annotation Marker(level : Level) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker(level = Level.HIGH)
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnEnumValue",
        )

        val ann = c.read("Demo").annotations.single { it.desc == "LMarker;" }
        assertEquals(
            EnumVal("LLevel;", "HIGH"),
            ann.values["level"],
            "枚举元素必须是 enum_const_value（e 标签 + 枚举描述符 + 常量名）",
        )
        // 已知限制（阶段 19c 的设计）：rsf 的枚举**不是** java.lang.Enum 的子类
        // （`enum Level` 生成的是一个带静态单例的普通类），而 JDK 的注解解析器要求
        // 枚举元素指向真正的枚举类，所以**这个成员**在运行期读不出来
        // （`value()` / `n()` 这些非枚举成员不受影响）。类文件本身是合法的，
        // 这里连带把这条限制钉住，免得以后有人以为是 M3 写坏了。
        val onClass = assertNotNull(c.load("Demo").getAnnotation(c.classOf("Marker")))
        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            member(onClass, "level")
        }
        assertTrue(
            failure.cause is java.lang.annotation.AnnotationTypeMismatchException,
            "枚举成员读不出来应该是 AnnotationTypeMismatchException：实际 ${failure.cause}",
        )
        // 伴生类上的元素字段是普通字段，照样读得到
        val impl = c.load("Marker\$Impl")
        val levelClass = c.load("Level")
        val instance = impl.getConstructor(levelClass).newInstance(levelClass.getField("HIGH").get(null))
        assertEquals(levelClass.getField("HIGH").get(null), impl.getField("level").get(instance))
    }

    // ============================================================ (2) 伴生类

    /** (2) 伴生类按普通类发光：`implements Marker`、有 `<init>`、`annotationType()` 也在。 */
    @Test
    fun `伴生类是普通类并且实现注解接口`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl : Runnable {
                    var hits : int = 0
                    fn run() -> void { hits = hits + 1 }
                    fn describe() -> string { rtn value }
                }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnCompanion",
        )

        val impl = c.read("Marker\$Impl")
        assertEquals(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, impl.access, "伴生类就是普通类（pub + ACC_SUPER）")
        assertEquals("java/lang/Object", impl.superName)
        assertEquals(listOf("Marker", "java/lang/Runnable"), impl.interfaces, "父接口 = 注解接口 + 自己写的接口")

        assertTrue(impl.methods.any { it.name == "<init>" })
        assertEquals(
            "()Ljava/lang/Class;",
            impl.method("annotationType").desc,
            "annotationType() 的描述符是 Annotation 接口的擦除签名",
        )
        assertEquals(
            Opcodes.ACC_PUBLIC,
            impl.method("annotationType").access,
            "annotationType() 是 pub 的实例方法（实现 Annotation 接口的那个）",
        )
        // 元素字段 + 访问器 + 伴生实体自己的成员
        assertEquals(
            listOf("value", "hits"),
            impl.fields.map { it.name },
            "元素字段排在伴生实体自己字段的前面（和主构造形参表对齐）",
        )
        assertEquals(
            listOf("value", "run", "describe", "toString"),
            impl.methods.filter { it.name != "<init>" && it.name != "annotationType" }.map { it.name },
            "伴生类里的方法：元素访问器 + 伴生实体自己的方法（+ 合成的 toString）",
        )
        assertTrue(c.files.any { it.name == "Marker\$Impl.class" }, "伴生类要作为独立 .class 发出去")
    }

    /** (2) 元素字段的值来自**构造器参数**（M2 的 initializer 只是占位）。 */
    @Test
    fun `伴生构造器把参数写进元素字段`() {
        val c = compile(
            """
            enum Level { LOW, HIGH }

            annotation Marker(value : string = "x", count : int = 0, level : Level, ratio : double = 0.0) {
                companion entity Impl {
                    var hits : int = 0
                    fn describe() -> string { rtn value + "/" + count }
                }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnCompanionCtor",
        )

        val impl = c.load("Marker\$Impl")
        val ctor = impl.getConstructor(
            String::class.java, Int::class.javaPrimitiveType, c.load("Level"), Double::class.javaPrimitiveType,
        )
        val instance = ctor.newInstance("ctor", 5, c.load("Level").getField("HIGH").get(null), 2.5)
        assertEquals("ctor", impl.getField("value").get(instance), "元素字段必须来自构造器参数")
        assertEquals(5, impl.getField("count").get(instance))
        assertEquals(c.load("Level").getField("HIGH").get(null), impl.getField("level").get(instance))
        assertEquals(2.5, impl.getField("ratio").get(instance))
        assertEquals(0, impl.getField("hits").get(instance), "伴生实体自己的字段按声明里的初值走")
        assertEquals("ctor/5", impl.getMethod("describe").invoke(instance), "伴生实体里的方法读得到元素字段")
        assertEquals("ctor", impl.getMethod("value").invoke(instance), "元素访问器方法读的是元素字段")
    }

    /** (2) `annotationType()` 返回 `Marker.class`。 */
    @Test
    fun `伴生类的 annotationType 返回注解接口的 class`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnAnnotationType",
        )

        val marker = c.classOf("Marker")
        val impl = c.load("Marker\$Impl")
        assertTrue(Annotation::class.java.isAssignableFrom(impl), "伴生类是完整的注解实现（能当 Annotation 用）")
        val instance = impl.getConstructor(String::class.java).newInstance("v")
        assertEquals(marker, impl.getMethod("annotationType").invoke(instance))
        // 通过接口那条抽象方法调也得通（描述符写成 ()LMarker; 的话这里会 AbstractMethodError）
        assertEquals(marker, Annotation::class.java.getMethod("annotationType").invoke(instance))
    }

    // ============================================================ (4) 方案 B 的访问器

    /** (4) 类型级访问器：签名 + `ACC_SYNCHRONIZED` + 懒构造缓存同一个实例。 */
    @Test
    fun `类型级访问器是静态同步方法并且缓存实例`() {
        val c = compile(
            """
            annotation Marker(value : string = "x", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker(value = "cls", count = 7)
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnTypeAccessor",
        )

        val shape = c.read("Demo").method("Marker")
        assertEquals("()LMarker\$Impl;", shape.desc)
        assertEquals(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNCHRONIZED,
            shape.access,
            "方案 B 的访问器是 pub stc synchronized（懒构造要串行化）",
        )
        assertEquals(0, shape.annotations.size, "访问器本身不该有注解")

        val accessor = c.load("Demo").getDeclaredMethod("Marker")
        assertTrue(Modifier.isStatic(accessor.modifiers) && Modifier.isSynchronized(accessor.modifiers))
        val first = assertNotNull(accessor.invoke(null))
        val second = assertNotNull(accessor.invoke(null))
        assertTrue(first === second, "懒构造：同一个使用点的两次调用必须返回同一个实例")
        val impl = c.load("Marker\$Impl")
        assertEquals("cls", impl.getMethod("value").invoke(first))
        assertEquals(7, impl.getMethod("count").invoke(first))
        assertEquals("cls", member(first as Annotation, "value"), "访问器实例要能当注解实例读")
    }

    /** (4) 成员级访问器：`Marker(string member)` 按成员分派，取不到返回 null。 */
    @Test
    fun `成员级访问器按成员名分派且取不到返回 null`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            class Demo {
                @Marker("field")
                var tag : string = "t"

                @Marker("method")
                fn greet() -> string { rtn "hi" }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnMemberAccessor",
        )

        val shape = c.read("Demo").method("Marker", "(Ljava/lang/String;)LMarker\$Impl;")
        assertEquals(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNCHRONIZED,
            shape.access,
        )

        val accessor = c.load("Demo").getDeclaredMethod("Marker", String::class.java)
        val impl = c.load("Marker\$Impl")
        val forField = assertNotNull(accessor.invoke(null, "tag"))
        assertEquals("field", impl.getMethod("value").invoke(forField))
        val forMethod = assertNotNull(accessor.invoke(null, "greet"))
        assertEquals("method", impl.getMethod("value").invoke(forMethod))
        assertTrue(forField !== forMethod, "每个使用点一个实例")
        assertTrue(accessor.invoke(null, "tag") === forField, "同一个使用点要复用缓存")
        assertNull(accessor.invoke(null, "nope"), "成员名对不上要返回 null（不抛异常）")
        assertNull(accessor.invoke(null, ""), "空成员名也返回 null")
    }

    /**
     * (4) 宿主是**接口 / 注解声明**时，访问器不能带 `ACC_SYNCHRONIZED`。
     *
     * JVMS 4.6 不允许接口方法带同步位：带了整个类文件在加载期就
     * `ClassFormatError: Method Marker in class Named has illegal modifiers: 0x29`，
     * 而且**只有真去加载那个类才会炸**（`javap` 能读、跑别的类也没事），所以这里两头都查：
     * 类文件标志 + 把接口/注解声明真的加载起来并把访问器调一次。
     */
    @Test
    fun `接口与注解声明上的访问器不带同步位`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker("iface")
            interface Named {
                fn name() -> string
            }

            @Marker("meta")
            annotation Doc(note : string = "n")

            class N : Named {
                fn name() -> string { rtn "n" }
            }

            fn main() -> void { println(new N().name()) }
            """,
            "RsfAnnIfaceHost",
        )

        for (host in listOf("Named", "Doc")) {
            val shape = c.read(host).method("Marker")
            assertEquals(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                shape.access,
                "$host 是接口/注解声明，访问器不能带同步位（否则 ClassFormatError: 0x29）",
            )
        }

        val impl = c.load("Marker\$Impl")
        val onInterface = assertNotNull(c.load("Named").getDeclaredMethod("Marker").invoke(null))
        assertEquals("iface", impl.getMethod("value").invoke(onInterface))
        val onAnnotation = assertNotNull(c.load("Doc").getDeclaredMethod("Marker").invoke(null))
        assertEquals("meta", impl.getMethod("value").invoke(onAnnotation))
        assertEquals("meta", member(c.load("Doc").getAnnotation(c.classOf("Marker")), "value"))

        // 实现类也必须能加载（父接口加载不了的话这里就 ClassFormatError）
        assertEquals(listOf("n"), runMain(c.load("RsfAnnIfaceHost")))
    }

    /** (4) 使用点少给实参时，访问器按元素声明顺序补默认值。 */
    @Test
    fun `访问器给缺省的实参补元素默认值`() {
        val c = compile(
            """
            annotation Marker(value : string = "x", count : int = 0) {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker("only")
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnAccessorDefault",
        )

        val instance = assertNotNull(c.load("Demo").getDeclaredMethod("Marker").invoke(null))
        val impl = c.load("Marker\$Impl")
        assertEquals("only", impl.getMethod("value").invoke(instance))
        assertEquals(0, impl.getMethod("count").invoke(instance), "没给 count，应该落到元素默认值 0")
        val onClass = assertNotNull(c.load("Demo").getAnnotation(c.classOf("Marker")))
        assertEquals(0, member(onClass, "count"))
    }

    /** (4) 两个不同的注解各有各的访问器，互不串。 */
    @Test
    fun `多个注解的访问器互不干扰`() {
        val c = compile(
            """
            annotation Aaa(value : string = "a") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            annotation Bbb(value : string = "b") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Aaa("onA")
            @Bbb("onB")
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnTwoAccessors",
        )

        val demo = c.load("Demo")
        val a = assertNotNull(demo.getDeclaredMethod("Aaa").invoke(null))
        val b = assertNotNull(demo.getDeclaredMethod("Bbb").invoke(null))
        assertEquals("onA", c.load("Aaa\$Impl").getMethod("value").invoke(a))
        assertEquals("onB", c.load("Bbb\$Impl").getMethod("value").invoke(b))
        assertEquals("onA", member(assertNotNull(demo.getAnnotation(c.classOf("Aaa"))), "value"))
        assertEquals("onB", member(assertNotNull(demo.getAnnotation(c.classOf("Bbb"))), "value"))
    }

    // ============================================================ (5) INVOKEINTERFACE

    /** (5) 元素访问器调用必须发 `INVOKEINTERFACE`（对接口发 INVOKEVIRTUAL 会 VerifyError）。 */
    @Test
    fun `元素访问器调用发的是 INVOKEINTERFACE`() {
        val c = compile(
            """
            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            fn main() -> void {
                var m : Marker = new Marker("hi")
                println(m.value())
                println(m.value)
            }
            """,
            "RsfAnnInvokeInterface",
        )

        val main = c.read("RsfAnnInvokeInterface").method("main")
        val call = main.calls.single { it.name == "value" && it.desc == "()Ljava/lang/String;" }
        assertEquals(
            Opcodes.INVOKEINTERFACE, call.opcode,
            "元素访问器的 owner 是注解接口，必须 INVOKEINTERFACE（INVOKEVIRTUAL 会 IncompatibleClassChangeError）",
        )
        assertEquals("Marker", call.owner)
        // 元素**字段**读的是伴生类上的字段
        val getfield = main.calls.single { it.opcode == Opcodes.GETFIELD && it.name == "value" }
        assertEquals("Marker\$Impl", getfield.owner)
        // 而且这份产物真的能被 JVM 校验通过并跑起来（VerifyError 会在加载时炸）
        val out = runMain(c.load("RsfAnnInvokeInterface"))
        assertEquals(listOf("hi", "hi"), out, "产物必须能加载并执行（发错 INVOKEVIRTUAL 的话这里就炸了）")
    }

    // ============================================================ 反例

    /** 反例：数组元素在使用点给值——类文件里的注解值编码不了数组，codegen 必须拦下。 */
    @Test
    fun `数组元素在使用点给值会被 codegen 拦下`() {
        val error = assertFailsWith<RsfError> {
            compile(
                """
                annotation Marker(xs : int[]) {
                    companion entity Impl {
                        var hits : int = 0
                    }
                }

                @Marker(xs = new int[3])
                class Demo { }

                fn main() -> void { println("ok") }
                """,
                "RsfAnnArrayValue",
            )
        }
        assertEquals("codegen", error.phase)
        assertTrue(error.detail.contains("数组"), "报错要说清是数组元素：${error.detail}")
    }

    /** 反例：元素值不是编译期常量——M2 放行（只要类型对），codegen 必须拦下。 */
    @Test
    fun `非常量的元素值会被 codegen 拦下`() {
        val error = assertFailsWith<RsfError> {
            compile(
                """
                annotation Marker(value : string = "x") {
                    companion entity Impl {
                        var hits : int = 0
                    }
                }

                @Marker("a" + "b")
                class Demo { }

                fn main() -> void { println("ok") }
                """,
                "RsfAnnNonConst",
            )
        }
        assertEquals("codegen", error.phase)
        assertTrue(error.detail.contains("编译期常量"), "报错要说清是常量要求：${error.detail}")
    }

    // ============================================================ 不动点 / 边界

    /** 不动点：没有注解的源码，类文件里不该多出访问器、缓存字段或 `$` 名字。 */
    @Test
    fun `没有注解的程序不生成任何注解产物`() {
        val c = compile(
            """
            interface Greeter {
                fn greet(n : string) -> string
            }

            class Dog : Greeter {
                var name : string = "d"
                fn greet(n : string) -> string { rtn "woof" }
            }

            fn main() -> void {
                var d : Dog = new Dog()
                println(d.greet("a"))
            }
            """,
            "RsfAnnFixedPoint",
        )

        assertEquals(
            setOf("RsfAnnFixedPoint.class", "Dog.class", "Greeter.class"),
            c.files.map { it.name }.toSet(),
            "没有注解就不该多出任何 .class（也不该少）",
        )
        for (name in listOf("RsfAnnFixedPoint", "Dog", "Greeter")) {
            val shape = c.read(name)
            assertTrue(
                shape.fields.none { it.name.startsWith("__rsf_ann") },
                "$name 里出现了注解缓存字段",
            )
            assertTrue(
                shape.methods.none { it.desc.endsWith("Impl;") || it.name.startsWith("__rsf_ann") },
                "$name 里出现了注解访问器",
            )
            assertTrue(shape.annotations.isEmpty(), "$name 上不该有注解")
        }
        assertEquals(listOf("woof"), runMain(c.load("RsfAnnFixedPoint")))
    }

    /** 没有 companion 的注解：使用点照样写出来，但**不生成**伴生类和访问器。 */
    @Test
    fun `没有伴生实体的注解只写使用点`() {
        val c = compile(
            """
            annotation Marker(value : string = "x", n : int = 1)

            @Marker("cls", 2)
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnNoCompanion",
        )

        assertTrue(c.files.none { it.name.contains('$') }, "没有 companion entity 就不该有 \$ 类")
        assertTrue(c.read("Demo").methods.none { it.name == "Marker" }, "没有伴生类就没有访问器")
        val onClass = assertNotNull(c.load("Demo").getAnnotation(c.classOf("Marker")))
        assertEquals("cls", member(onClass, "value"))
        assertEquals(2, member(onClass, "n"))
        assertEquals("x", c.load("Marker").getMethod("value").defaultValue)
    }

    /** `new Marker(...)` 少给实参时按元素默认值补齐（少补一个就会 NoSuchMethodError）。 */
    @Test
    fun `new 伴生实例时少给的实参补默认值`() {
        val c = compile(
            """
            annotation Plain(v : string = "d", n : int = 1) {
                companion entity Impl {
                    fn describe() -> string { rtn v + "/" + n }
                }
            }

            fn main() -> void {
                var p : Plain::Impl = new Plain()
                println(p.describe())
                var q : Plain::Impl = new Plain("q")
                println(q.describe())
            }
            """,
            "RsfAnnNewDefault",
        )

        assertEquals(listOf("d/1", "q/1"), runMain(c.load("RsfAnnNewDefault")))
    }

    /** 带包的程序：注解接口 / 伴生类 / 访问器的描述符都要跟着包走。 */
    @Test
    fun `带包的注解与伴生类跟着包走`() {
        val c = compile(
            """
            pkg app.meta

            annotation Marker(value : string = "x") {
                companion entity Impl {
                    var hits : int = 0
                }
            }

            @Marker("cls")
            class Demo { }

            fn main() -> void { println("ok") }
            """,
            "RsfAnnPkg",
        )

        // 注解接口和 rsf 的接口一样只在默认包里生成（`sema` 的父接口表里写的就是简名），
        // 伴生类是**类**，跟着程序类的包走。
        assertEquals("Marker", c.read("Marker").name)
        assertEquals("app/meta/Marker\$Impl", c.read("app/meta/Marker\$Impl").name)
        assertEquals(
            listOf("Marker"),
            c.read("app/meta/Marker\$Impl").interfaces,
            "伴生的 implement 列表要指向注解接口真正的内部名（对不上就 NoClassDefFoundError）",
        )
        assertEquals(
            "()Lapp/meta/Marker\$Impl;",
            c.read("app/meta/Demo").method("Marker").desc,
            "访问器的返回类型必须跟着包走（默认包写法会 NoClassDefFoundError）",
        )
        val demo = c.load("app.meta.Demo")
        assertEquals("app.meta.Marker\$Impl", demo.getDeclaredMethod("Marker").returnType.name)
        val instance = assertNotNull(demo.getDeclaredMethod("Marker").invoke(null))
        assertEquals("cls", c.load("app.meta.Marker\$Impl").getMethod("value").invoke(instance))
        assertEquals("cls", member(assertNotNull(demo.getAnnotation(c.classOf("Marker"))), "value"))
    }

    // ============================================================ 工具

    private class Compiled(val dir: File, val files: List<File>, val loader: ClassLoader) {
        fun read(internalName: String): ClassShape =
            readClass(File(dir, "$internalName.class").readBytes(), internalName)

        fun load(binaryName: String): Class<*> = loader.loadClass(binaryName)

        @Suppress("UNCHECKED_CAST")
        fun classOf(binaryName: String): Class<out kotlin.Annotation> =
            load(binaryName) as Class<out kotlin.Annotation>
    }

    private fun compile(text: String, className: String): Compiled {
        val src = SourceFile.of("<annotation-codegen>", text.trimIndent())
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, className, dir)
        // 按需加载：注解接口 <- 伴生类 <- 宿主类 之间有解析顺序依赖，不能按文件顺序 define
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                val file = File(dir, name.replace('.', '/') + ".class")
                if (!file.isFile) throw ClassNotFoundException(name)
                val bytes = file.readBytes()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return Compiled(dir, out.classFiles, loader)
    }

    private fun tempDir(): File = File.createTempFile("rsf-ann", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun runMain(cls: Class<*>): List<String> {
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        val saved = System.out
        System.setOut(ps)
        try {
            cls.getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
        } finally {
            System.setOut(saved)
        }
        ps.flush()
        return buf.toString("UTF-8").replace("\r\n", "\n").trim().lines()
    }

    /** 读注解实例的元素（走 `annotationType()`，JDK 代理和伴生类实例都吃得下）。 */
    private fun member(annotation: Any, name: String): Any? {
        val type = annotation.javaClass.methods.first { it.name == "annotationType" }
            .invoke(annotation) as Class<*>
        return type.getMethod(name).invoke(annotation)
    }
}

// ================================================================ 类文件形状（ASM 只读）

/** 一条注解（`RuntimeVisibleAnnotations` 里的一项）。 */
private class Ann(val desc: String, val values: Map<String, Any?>)

/** 枚举元素值：`e` 标签带着枚举类型描述符与常量名。 */
private data class EnumVal(val desc: String, val value: String)

/** 一条指令（字段/方法都记在这里）。 */
private data class Insn(val opcode: Int, val owner: String, val name: String, val desc: String)

private class FieldShape(val name: String, val desc: String, val access: Int) {
    val annotations = mutableListOf<Ann>()
}

private class MethodShape(val name: String, val desc: String, val access: Int) {
    val annotations = mutableListOf<Ann>()
    val calls = mutableListOf<Insn>()
    var default: Any? = null
}

private class ClassShape(
    val access: Int,
    val name: String,
    val superName: String,
    val interfaces: List<String>,
    val annotations: List<Ann>,
    val fields: List<FieldShape>,
    val methods: List<MethodShape>,
) {
    fun method(name: String, desc: String? = null): MethodShape {
        val hits = methods.filter { it.name == name && (desc == null || it.desc == desc) }
        assertEquals(1, hits.size, "类 '$this' 里 '$name' 应该有且只有一个：实际 ${hits.map { it.desc }}")
        return hits.single()
    }

    fun field(name: String): FieldShape =
        fields.singleOrNull { it.name == name }
            ?: throw AssertionError("类 '$this' 里没有字段 '$name'（有 ${fields.map { it.name }}）")

    override fun toString(): String = name
}

private fun annValues(desc: String, onDone: (Map<String, Any?>) -> Unit): AnnotationVisitor =
    object : AnnotationVisitor(Opcodes.ASM9) {
        private val values = LinkedHashMap<String, Any?>()

        override fun visit(name: String?, value: Any?) {
            values[name ?: ""] = value
        }

        override fun visitEnum(name: String?, desc: String?, value: String?) {
            values[name ?: ""] = EnumVal(desc ?: "", value ?: "")
        }

        override fun visitAnnotation(name: String?, desc: String?): AnnotationVisitor =
            annValues(desc ?: "") { values[name ?: ""] = Ann(desc ?: "", it) }

        override fun visitEnd() {
            onDone(values)
        }
    }

private fun readClass(bytes: ByteArray, sourceName: String): ClassShape {
    var shape: ClassShape? = null
    val visitor = object : ClassVisitor(Opcodes.ASM9) {
        private var access = 0
        private var name = ""
        private var superName = ""
        private var interfaces: List<String> = emptyList()
        private val annotations = mutableListOf<Ann>()
        private val fields = mutableListOf<FieldShape>()
        private val methods = mutableListOf<MethodShape>()

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            this.access = access
            this.name = name
            this.superName = superName ?: ""
            this.interfaces = interfaces?.toList() ?: emptyList()
        }

        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor =
            annValues(desc) { annotations.add(Ann(desc, it)) }

        override fun visitField(
            access: Int,
            name: String,
            desc: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor {
            val field = FieldShape(name, desc, access)
            fields.add(field)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor =
                    annValues(desc) { field.annotations.add(Ann(desc, it)) }
            }
        }

        override fun visitMethod(
            access: Int,
            name: String,
            desc: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val method = MethodShape(name, desc, access)
            methods.add(method)
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor =
                    annValues(desc) { method.annotations.add(Ann(desc, it)) }

                override fun visitAnnotationDefault(): AnnotationVisitor =
                    annValues("") { method.default = it[""] }

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                    method.calls.add(Insn(opcode, owner, name, desc))
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    desc: String,
                    isInterface: Boolean,
                ) {
                    method.calls.add(Insn(opcode, owner, name, desc))
                }
            }
        }

        override fun visitEnd() {
            shape = ClassShape(access, name, superName, interfaces, annotations, fields, methods)
        }
    }
    ClassReader(bytes).accept(visitor, 0)
    return shape ?: throw AssertionError("读不出 '$sourceName' 的类文件形状")
}
