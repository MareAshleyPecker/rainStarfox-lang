package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.sema.SemanticModel
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 20 `interface` 的**两端**（解释器 + 字节码）运行时测试（M3）。
 *
 * 为什么单独一个文件：`InterfaceTest.kt` 只管前端（解析出什么 AST、Checker 放不放行），
 * 而「接口分派真的跑得起来」是另一件事——接口的抽象方法在解释器里**没有降级结果**
 * （`model.loweredOf` 会抛），在字节码里必须发 `INVOKEINTERFACE`，这两条只有跑一遍才知道。
 *
 * 口径与全仓一致（helper 与 `InteropTest.kt` / `RsfTest.kt` 同一套）：
 * **同一份源码，解释器与 `Rsf.build` 出来的 .class 的 stdout 必须逐字节相同**。
 * 每个用例都跑两端，所以「只修了后端」「只修了解释器」都会被这里咬住。
 *
 * 覆盖（对照任务书的 (a)~(g)）：
 * (a) 类类型接收者调**继承来的默认方法**（M2 的遗留修补）；
 * (b) 默认方法内部调抽象方法（隐式 `this.xxx()` 也要走接口分派）；
 * (c) 接口类型变量调抽象方法——按运行期真实类分派（同一接口两个实现）；
 * (d) 接口继承接口 + 实现类（默认方法穿过父接口传递继承）；
 * (e) `class Dog : Greeter, Runnable` 的 JDK 接口互操作；
 * (f) 负例 `new I()`（前端就拦下，不产生任何产物）；
 * (g) 产物形状：接口 .class 是 `ACC_INTERFACE|ACC_ABSTRACT`、没有 `<init>`、
 *     抽象方法无 Code、默认方法有 Code，调用点发的是 `invokeinterface`。
 */
class InterfaceRuntimeTest {

    // ================================================================== (a) 继承来的默认方法

    /**
     * 类类型的接收者也要能调**接口继承来的默认方法**。
     *
     * 修补前的原样：`var d : Dog = new Dog(); d.shout("a")` 报
     * `类 'Dog' 里没有方法 'shout'`——Checker 只翻了类自己的方法表，没沿接口链找默认方法。
     * Java 里这是合法的（默认方法被实现类继承），所以这里两端都要跑通。
     */
    @Test
    fun `类类型接收者能调继承来的默认方法`() {
        val program = """
            pub interface Greeter {
                fn greet(n : string) -> string
                fn shout(n : string) -> string {
                    rtn greet(n) + "!"
                }
            }
            class Dog : Greeter {
                pub fn greet(n : string) -> string {
                    rtn "woof"
                }
            }
            fn main() -> void {
                var d : Dog = new Dog()
                println(d.shout("a"))
                var g : Greeter = new Dog()
                println(g.shout("b"))
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("woof!", "woof!"), bothEnds(program, "RsfIfaceDefault"))
    }

    /**
     * 默认方法内部调抽象方法：`shout` 里那次 `greet(n)` 是隐式 `this.greet(n)`，
     * Checker 同样记成 `interfaceOwner = true`，所以它必须走同一条接口分派入口。
     */
    @Test
    fun `默认方法内部调抽象方法也走接口分派`() {
        val program = """
            pub interface Calc {
                fn twice(x : int) -> int
                fn quad(x : int) -> int {
                    rtn twice(twice(x))
                }
            }
            class Doubler : Calc {
                pub fn twice(x : int) -> int {
                    rtn x * 2
                }
            }
            fn main() -> void {
                var c : Calc = new Doubler()
                println(c.quad(2))
                var d : Doubler = new Doubler()
                println(d.quad(3))
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("8", "12"), bothEnds(program, "RsfIfaceInner"))
    }

    // ================================================================== (c) 按真实类虚分派

    /**
     * 接口类型变量调抽象方法：调哪一份实现看**运行期对象是谁**（`INVOKEINTERFACE` 的语义）。
     *
     * 同一个接口两个实现，其中一次调用还隔着函数参数（形参静态类型也是接口）——
     * 这条如果退化成「按静态类型找方法」就会选错实现。
     */
    @Test
    fun `接口类型变量调抽象方法按真实类分派`() {
        val program = """
            pub interface Shape {
                fn area() -> int
            }
            class Sq : Shape {
                pub fn area() -> int {
                    rtn 4
                }
            }
            class Tri : Shape {
                pub fn area() -> int {
                    rtn 3
                }
            }
            fn describe(s : Shape) -> string {
                rtn "area=" + s.area()
            }
            fn main() -> void {
                var a : Shape = new Sq()
                var b : Shape = new Tri()
                println(describe(a))
                println(describe(b))
                println(a.area() + b.area())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("area=4", "area=3", "7"), bothEnds(program, "RsfIfaceDispatch"))
    }

    // ================================================================== (d) 接口继承接口

    /**
     * 接口继承接口：子接口的默认方法要能用**父接口的抽象方法**（传递继承），
     * 实现类的接收者（`var d : Dog = new Dog()`）也要能调到这个默认方法。
     */
    @Test
    fun `接口继承接口时默认方法与父接口抽象方法都能调`() {
        val program = """
            pub interface Named {
                fn name() -> string
            }
            pub interface Greeter : Named {
                fn greet(n : string) -> string
                fn hello() -> string {
                    rtn "hi " + name() + " " + greet("x")
                }
            }
            class Dog : Greeter {
                pub fn name() -> string {
                    rtn "dog"
                }
                pub fn greet(n : string) -> string {
                    rtn "woof-" + n
                }
            }
            fn main() -> void {
                var g : Greeter = new Dog()
                println(g.hello())
                println(g.name())
                var n : Named = new Dog()
                println(n.name())
                var d : Dog = new Dog()
                println(d.hello())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(
            listOf("hi dog woof-x", "dog", "dog", "hi dog woof-x"),
            bothEnds(program, "RsfIfaceChain"),
        )
    }

    // ================================================================== (e) JDK 接口互操作

    /**
     * `class Dog : Greeter, Runnable`：类头同时挂 rsf 接口与 JDK 接口。
     *
     * 静态类型是 **JDK 接口**（`Runnable`）时，解释器拿到的却是 rsf 实现类对象——
     * 它只是个 [Interpreter.RsfObject]，**并不是真的**实现了 `Runnable`，
     * 反射 invoke 会抛 `IllegalArgumentException: object of type ... is not an instance of
     * java.lang.Runnable`，所以这条路要在 rsf 侧按真实类虚分派。字节码那边发 `invokeinterface`。
     */
    @Test
    fun `类同时实现 rsf 接口与 JDK 接口`() {
        val program = """
            pub interface Greeter {
                fn greet() -> string
            }
            class Dog : Greeter, Runnable {
                pub fn greet() -> string {
                    rtn "woof"
                }
                pub fn run() -> void {
                    println("running")
                }
            }
            fn main() -> void {
                var d : Dog = new Dog()
                println(d.greet())
                var r : Runnable = new Dog()
                r.run()
                var g : Greeter = d
                println(g.greet())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("woof", "running", "woof"), bothEnds(program, "RsfIfaceJdk"))
    }

    // ================================================================== 默认方法 + ?: 兜底

    /**
     * 默认方法体里的 `?:` 兜底：接口的抽象方法可以是 `!T`（错误返回）。
     *
     * 这条专门钉住**接口里的合成方法**——`load(n) ?: "fallback"` 要生成一个
     * `try/catch` 的合成静态方法，而它必须跟着**接口自己**的 .class 发出去，
     * 且里面那次调用得是 `invokeinterface`（不是 `invokespecial`）。
     */
    @Test
    fun `默认方法里的 elvis 兜底两端一致`() {
        val program = """
            pub interface Fetcher {
                fn load(n : int) -> !string
                fn safe(n : int) -> string {
                    rtn load(n) ?: "fallback"
                }
            }
            class F : Fetcher {
                pub fn load(n : int) -> !string {
                    if (n < 0) {
                        fail "bad"
                    }
                    rtn "ok-" + n
                }
            }
            fn main() -> void {
                var f : Fetcher = new F()
                println(f.safe(1))
                println(f.safe(-1))
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("ok-1", "fallback"), bothEnds(program, "RsfIfaceElvis"))
    }

    // ================================================================== (f) 负例

    /** `new I()`：接口不能被实例化，前端就拦下（两端都没有产物可跑）。 */
    @Test
    fun `new 接口在 check 阶段就报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    """
                        pub interface Greeter {
                            fn greet() -> string
                        }
                        fn main() -> void {
                            var g = new Greeter()
                            rtn
                        }
                    """.trimIndent() + "\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是接口，不能 new"), e.detail)
    }

    // ============== (h) 分派的虚解析与「谁有资格当实现」（父代理独立复验补测，2026-10-14）

    /**
     * 静态类型是**父接口**时，跑的也必须是运行期对象那一份（子接口覆盖过的默认方法）。
     *
     * 这是独立复验抓到的**两端背离**：解释器原先看到 `decl.body != null` 就直接跑静态类型
     * 那一份（打 `n`），而 JVM 的 `INVOKEINTERFACE` 会解析到子接口覆盖后的那份（打 `p`）。
     */
    @Test
    fun `接口静态类型下调的是子接口覆盖后的默认方法`() {
        val program = """
            pub interface Named {
                fn name() -> string {
                    rtn "n"
                }
            }
            pub interface Person : Named {
                override fn name() -> string {
                    rtn "p"
                }
            }
            class P : Person {
            }
            fn main() -> void {
                var n : Named = new P()
                println(n.name())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("p"), bothEnds(program, "RsfIfaceVirtualDefault"))
    }

    /**
     * `private` / `protected` / `static` 方法**当不了接口实现**（JVM 的口径）。
     *
     * 修补前的原样：这三种写法 Checker 全放行、`rsf run` 也跑得过，但字节码里 `Dog` 没有
     * public 的实现，接口上一调就是 `AbstractMethodError`——最坏的一种两端背离
     * （解释器说没事，JVM 说没实现）。
     */
    @Test
    fun `private protected static 方法都不能算接口实现`() {
        val cases = listOf(
            "prv 方法" to "prv ",
            "prot 方法" to "prot ",
            "stc 方法" to "stc ",
        )
        for ((why, modifier) in cases) {
            val e = assertFailsWith<RsfError> {
                Rsf.analyze(
                    source(
                        "pub interface Greeter {\n" +
                            "    fn greet() -> string\n" +
                            "}\n" +
                            "class Dog : Greeter {\n" +
                            "    $modifier" + "fn greet() -> string {\n" +
                            "        rtn \"woof\"\n" +
                            "    }\n" +
                            "}\n" +
                            "fn main() -> void {\n" +
                            "    rtn\n" +
                            "}\n",
                    ),
                )
            }
            assertTrue(e.detail.contains("是 $why"), e.detail)
            assertTrue(e.detail.contains("接口方法必须是 pub 的实例方法"), e.detail)
        }
    }

    /** D5 冲突里「拍板」的那份同样要有资格当实现：private 方法拍不了板（JVM 依旧认不出实现）。 */
    @Test
    fun `默认方法冲突不能靠 private 方法拍板`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub interface A {\n" +
                        "    fn say() -> string {\n        rtn \"a\"\n    }\n" +
                        "}\n" +
                        "pub interface B {\n" +
                        "    fn say() -> string {\n        rtn \"b\"\n    }\n" +
                        "}\n" +
                        "class C : A, B {\n" +
                        "    prv fn say() -> string {\n        rtn \"c\"\n    }\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("说不清该用哪一份"), e.detail)
    }

    // ================================================================== (g) 产物形状

    /**
     * 接口 .class 的形状（用 ASM 读回来，不看源码猜）：
     *
     * - 接口头：`ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT`，**没有 ACC_SUPER**，
     *   超类是 `java/lang/Object`，父接口数组来自 `model.superInterfacesOf`；
     * - **没有 `<init>`**（接口不能被实例化，也不该有构造器）；
     * - 抽象方法 `ACC_PUBLIC | ACC_ABSTRACT` 且**没有 Code**；
     * - 默认方法有 Code；
     * - 实现类 `Dog` 的父接口数组是 `Greeter, java/lang/Runnable`；
     * - 调用点发的是 `invokeinterface`（程序类里那条 `itf=true` 的方法调用）。
     */
    @Test
    fun `接口产物形状正确且调用点发 invokeinterface`() {
        val program = """
            pub interface Greeter {
                fn greet(n : string) -> string
                fn shout(n : string) -> string {
                    rtn greet(n) + "!"
                }
            }
            class Dog : Greeter, Runnable {
                pub fn greet(n : string) -> string {
                    rtn "woof"
                }
                pub fn run() -> void {
                    rtn
                }
            }
            fn main() -> void {
                var d : Dog = new Dog()
                println(d.shout("a"))
                var g : Greeter = new Dog()
                println(g.shout("b"))
                rtn
            }
        """.trimIndent() + "\n"

        val src = source(program)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, "RsfIfaceShape", dir)
        fun shapeOf(fileName: String): Shape =
            shapeOf(out.classFiles.single { it.name == fileName }.readBytes())

        val greeter = shapeOf("Greeter.class")
        assertEquals("Greeter", greeter.name)
        assertTrue(greeter.hasFlag(Opcodes.ACC_INTERFACE), "接口头要有 ACC_INTERFACE")
        assertTrue(greeter.hasFlag(Opcodes.ACC_PUBLIC), "接口头要有 ACC_PUBLIC")
        assertTrue(greeter.hasFlag(Opcodes.ACC_ABSTRACT), "接口头要有 ACC_ABSTRACT")
        assertTrue(!greeter.hasFlag(Opcodes.ACC_SUPER), "接口头不能有 ACC_SUPER")
        assertEquals("java/lang/Object", greeter.superName)
        assertEquals(emptyList(), greeter.interfaces)
        assertTrue(greeter.methods.none { it.name == "<init>" }, "接口不该有 <init>")

        val greet = greeter.methods.single { it.name == "greet" }
        assertEquals(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, greet.access)
        assertTrue(!greet.hasCode, "抽象方法不能有 Code 属性")
        val shout = greeter.methods.single { it.name == "shout" }
        assertEquals(Opcodes.ACC_PUBLIC, shout.access)
        assertTrue(shout.hasCode, "默认方法要有 Code 属性")

        val dog = shapeOf("Dog.class")
        assertEquals(listOf("Greeter", "java/lang/Runnable"), dog.interfaces)
        assertTrue(dog.hasFlag(Opcodes.ACC_SUPER), "类头照旧要 ACC_SUPER")

        // 调用点：`d.shout("a")` / `g.shout("b")` 都是 INVOKEINTERFACE（owner = 声明它的接口）
        val main = shapeOf("RsfIfaceShape.class")
        val calls = main.methods.flatMap { it.instructions }
            .filter { it.contains("Greeter.shout") }
        assertEquals(2, calls.size, calls.toString())
        assertTrue(
            calls[0].startsWith("INVOKEINTERFACE Greeter.shout") && calls[0].endsWith("itf=true"),
            calls.toString(),
        )
        // 实现类的接口方法不能被发成 invokespecial（那会绕过虚分派）
        assertTrue(main.methods.flatMap { it.instructions }.none { it.startsWith("INVOKESPECIAL Greeter") })
    }

    // ================================================================== ASM 读回来的形状

    /** 一个方法：访问标志 + 有没有 Code + 记下来的调用指令（够这几条断言用）。 */
    private class MethodShape(val access: Int, val name: String, val descriptor: String) {
        var hasCode = false
        val instructions = ArrayList<String>()

        fun hasFlag(flag: Int): Boolean = (access and flag) != 0
    }

    /** 一个类：类头 + 方法。 */
    private class Shape {
        var access = 0
        var name = ""
        var superName: String? = null
        var interfaces: List<String> = emptyList()
        val methods = ArrayList<MethodShape>()

        fun hasFlag(flag: Int): Boolean = (access and flag) != 0
    }

    private fun shapeOf(bytes: ByteArray): Shape {
        val shape = Shape()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    shape.access = access
                    shape.name = name
                    shape.superName = superName
                    shape.interfaces = interfaces?.toList().orEmpty()
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val m = MethodShape(access, name, descriptor)
                    shape.methods.add(m)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitCode() {
                            m.hasCode = true
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            val op = when (opcode) {
                                Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE"
                                Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL"
                                Opcodes.INVOKESTATIC -> "INVOKESTATIC"
                                Opcodes.INVOKESPECIAL -> "INVOKESPECIAL"
                                else -> "OP$opcode"
                            }
                            m.instructions.add("$op $owner.$name$descriptor itf=$isInterface")
                        }
                    }
                }
            },
            0,
        )
        return shape
    }

    // ============ (i) 跨边界：rsf 对象交给 JDK 代码（阶段 20 M4，父代理，2026-10-15）

    /**
     * 源码里的实现类**当实参**交给 JDK 代码，由 JDK 回调它——M4 的三条缺口一次全踩到：
     *
     * 1. `Collections.sort(xs, new ByValue())` 的候选挑选要认「源码类 → JDK 接口」（M4-a/b，
     *    修在 `MemberResolver.argCost` 的源码子类型谓词）；
     * 2. 解释器里 `RsfObject` 不是真的 `Comparator`，得套代理（M4-3）；
     * 3. 字节码侧 `Comparator.compare(Object,Object)` 要靠合成的桥方法顶到
     *    `compare(String,String)`（M4-c）。
     */
    @Test
    fun `源码实现类交给 JDK 代码回调时两端一致`() {
        val program = """
            import java.util.ArrayList
            import java.util.Collections
            import java.util.Comparator

            class ByValue : Comparator&<string> {
                pub fn compare(a : string, b : string) -> int {
                    var x = parseInt(a) ?: 0
                    var y = parseInt(b) ?: 0
                    rtn x - y
                }
            }

            fn main() -> void {
                var xs = new ArrayList&<string>()
                xs.add("5")
                xs.add("3")
                Collections.sort(xs, new ByValue())
                println(xs.get(0) + "," + xs.get(1))
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("3,5"), bothEnds(program, "RsfIfaceJdkCallback"))
    }

    /**
     * 同一个缺口走**构造器**实参：`new Thread(rsf 对象)`。
     *
     * 断言的是 `isAlive()`（`false`，确定性输出）而不是跑线程——线程调度不可复现，
     * 而这里要钉的是「实参能过」这件事，不是并发。
     */
    @Test
    fun `rsf 对象当 JDK 构造器实参两端一致`() {
        val program = """
            class Job : Runnable {
                pub fn run() -> void {
                    println("running")
                }
            }

            fn main() -> void {
                var t = new Thread(new Job())
                println(t.isAlive())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("false"), bothEnds(program, "RsfIfaceJdkCtor"))
    }

    /**
     * **rsf 自己声明的泛型接口**也要桥：`interface Box&<T> { fun get() -> T }` 发到类文件里
     * 是 `get()Ljava/lang/Object;`，而实现类写的是 `get() -> int`。没有桥，经接口回调
     * （`b.get()` 静态类型是 `Box&<int>`）就是 `AbstractMethodError`。
     */
    @Test
    fun `泛型 rsf 接口的实现类补桥方法两端一致`() {
        val program = """
            pub interface Box&<T> {
                fn get() -> T
            }

            class IntBox : Box&<int> {
                pub fn get() -> int {
                    rtn 7
                }
            }

            fn main() -> void {
                var b : Box&<int> = new IntBox()
                println(b.get())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("7"), bothEnds(program, "RsfIfaceGenericBridge"))
    }

    /** 桥方法本身的形状：`ACC_BRIDGE | ACC_SYNTHETIC`，且实现方法**原样保留**。 */
    @Test
    fun `桥方法是 BRIDGE SYNTHETIC 且转调实现`() {
        val program = """
            import java.util.Comparator

            class ByValue : Comparator&<string> {
                pub fn compare(a : string, b : string) -> int {
                    rtn 0
                }
            }

            fn main() -> void {
                rtn
            }
        """.trimIndent() + "\n"

        val src = source(program)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, "RsfIfaceBridgeShape", dir)
        val file = out.classFiles.first { it.name == "ByValue.class" }
        val shape = shapeOf(file.readBytes())

        val bridge = shape.methods.single {
            it.name == "compare" && it.descriptor == "(Ljava/lang/Object;Ljava/lang/Object;)I"
        }
        assertTrue(bridge.hasFlag(Opcodes.ACC_BRIDGE), "桥必须带 ACC_BRIDGE")
        assertTrue(bridge.hasFlag(Opcodes.ACC_SYNTHETIC), "桥必须带 ACC_SYNTHETIC")
        assertEquals(
            listOf("INVOKEVIRTUAL ByValue.compare(Ljava/lang/String;Ljava/lang/String;)I itf=false"),
            bridge.instructions,
        )
        // 实现方法自己还在，描述符是收窄后的那一份
        assertTrue(
            shape.methods.any { it.name == "compare" && it.descriptor == "(Ljava/lang/String;Ljava/lang/String;)I" },
            "实现方法不能被桥顶掉",
        )
    }

    // ================================================================== helper

    //
    // 与 InteropTest.kt 同一套口径：源码字符串 → Rsf.analyze → 解释执行 / Rsf.build →
    // 自定义类加载器 → 反射调 main。差别只在这里把「两端一致」收成一个 [bothEnds]。

    /** 解释器跑一遍、编译产物再跑一遍，断言 stdout 逐字节相同，返回行列表。 */
    private fun bothEnds(text: String, className: String): List<String> {
        val interpreted = runInterpreted(text)
        val compiled = runCompiledClasses(text, className)
        assertEquals(
            normalize(interpreted),
            normalize(compiled),
            "解释器与字节码对接口分派必须逐字节一致",
        )
        return lines(interpreted)
    }

    private fun source(text: String): SourceFile = SourceFile.of("<interface-runtime>", text)

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-iface", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')

    /**
     * 从临时目录里按名字取 `.class` 的加载器（程序类由调用方 `loadClass` 触发）。
     *
     * 和 `InteropTest.kt` 那份「显式 define 一批」的写法有个关键差别：**接口必须先于
     * 实现类可用**。显式 define 是按 build 出来的文件顺序走的（程序类、用户类、接口…），
     * 定义 `Dog` 时 JVM 要解析它的父接口 `Greeter`，而那时 `Greeter` 还没被 define
     * ——直接 `NoClassDefFoundError: Greeter`。交给 `findClass` 按需加载就没有这个顺序问题。
     */
    private fun programLoader(files: List<File>, dir: File): Pair<ClassLoader, String> {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                val file = File(dir, name.replace('.', '/') + ".class")
                if (!file.isFile) throw ClassNotFoundException(name)
                val bytes = file.readBytes()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return loader to binaryName(files.first(), dir)
    }

    private fun runMain(cls: Class<*>): String {
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
        return buf.toString("UTF-8")
    }

    private fun runCompiledClasses(text: String, className: String): String {
        val src = source(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, className, dir)
        val (loader, programName) = programLoader(out.classFiles, dir)
        return runMain(loader.loadClass(programName))
    }

    private fun runInterpreted(text: String): String {
        val model: SemanticModel = Rsf.analyze(source(text))
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        val saved = System.out
        System.setOut(ps)
        try {
            Interpreter(model, ps).runEntry()
        } finally {
            System.setOut(saved)
        }
        ps.flush()
        return buf.toString("UTF-8")
    }
}
