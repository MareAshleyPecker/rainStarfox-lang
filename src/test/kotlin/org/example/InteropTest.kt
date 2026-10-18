package org.example

import org.example.complie.Rsf
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.sema.SemanticModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM 互操作 + 后端一致性的回归测试（阶段 17d 的三件前置，2026-10-11）。
 *
 * 为什么是**单独一个文件**：`RsfTest.kt` 是按阶段顺序堆起来的大文件（七千多行），
 * 这里的三件事是「把 rsf 写成编译器后端」之前的准备工作，单独放更好找。
 * helper 与 `RsfTest.kt` 里那套同口径（`Rsf.build` + 自定义类加载器 + 反射调 main），
 * 所以两边比出来的东西可比。
 *
 * 覆盖三件事：
 *
 * 1. **`byte` / `char` 上的一元负号（后端缺陷回归）**：Checker 把 `-b` 的结果定成
 *    `int`（提升），而字节码后端原来只认 INT / LONG / FLOAT / DOUBLE 四种静态类型 ⇒
 *    `rsf check` 放行、`rsf run` 正常、**`rsf build` 崩**。这是三方不一致里最坏的一种，
 *    所以除了修，还要有一条把「check / run / build 三条路」一起钉住的测试。
 * 2. **多类型实参的擦除互操作**：`HashMap&<string, int>` 的
 *    `put` / `get` / `containsKey` 要能用——17c 的解释器只能手写符号表就是卡在这。
 * 3. **外部类的 `new`**：`new File("x")` / `new ArrayList&<string>()` /
 *    `new StringBuilder(16)` 两端一致；17d 要用 rsf 调 ASM 的 `new ClassWriter(...)`，
 *    所以连 ASM 本身可用也一起钉在这里。
 *
 * 口径与全仓一致：**解释器与编译产物的 stdout 逐字节相同**。
 */
class InteropTest {

    // ================================================================== 一元负号（后端缺陷回归）

    /**
     * `byte` / `char` 上的一元负号：三条路（check / run / build）结果一致。
     *
     * 缺陷原样：`var b : byte = 5` + `-b` ⇒ `rsf check` stderr 0 字节、`rsf run` 打出 -5、
     * `rsf build` 抛 `IllegalStateException: 一元减号的操作数不是数值`。
     * 修法：后端按**提升后**的基础类型发指令（byte / char 在 JVM 栈上就是 int）。
     */
    @Test
    fun `byte 与 char 上的一元负号三条路都一致`() {
        val program = """
            fn main() -> void {
                var b : byte = 5
                var c : char = 'A'
                println(-b)
                println(-(b + b))
                println(-charToInt(c))
                println(-(-b))
                println((-b) + 1)
                println(-c)
                rtn
            }
        """.trimIndent() + "\n"

        // check：这一步以前就放行，正是「三方不一致」里被漏过去的那条路
        Rsf.analyze(source(program))

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf("-5", "-10", "-65", "5", "-4", "-65"),
            lines(interpreted),
            "一元负号的语义（byte / char 都先提升成 int）",
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropUnary")),
            "解释器与字节码对一元负号必须逐字节一致",
        )
    }

    /**
     * **三方一致性自查**：每个基础类型的一元与二元运算都过一遍 check / run / build。
     *
     * 这条测试的由来：一元负号那个缺陷的本质是「`when (静态类型)` 只列了四种类型」。
     * 全量排查下来，`BytecodeCompiler` 里按基础类型分派的地方只有两处——
     * 一元负号（已修）与二元算术（用的是**结果类型**，Checker 一定给提升后的四种之一，
     * 所以安全）；`widenInsn` 的加宽表 byte / char 的每条出口都列全了。
     * 这条用真实语料把结论钉住，而不是只靠读代码。
     */
    @Test
    fun `基础类型的一元与二元运算三条路都一致`() {
        val program = """
            fn main() -> void {
                var b : byte = 5
                var c : char = 'A'
                var i : int = 7
                var l : long = 9L
                var f : float = 1.5f
                var d : double = 2.25

                println("-b = " + (-b))
                println("-c = " + (-c))
                println("-i = " + (-i))
                println("-l = " + (-l))
                println("-f = " + (-f))
                println("-d = " + (-d))
                println("-(b + b) = " + (-(b + b)))
                println("-(c) = " + (-charToInt(c)))
                println("-(-b) = " + (-(-b)))
                println("(-b) + 1 = " + ((-b) + 1))
                println("(-b) * 2 = " + ((-b) * 2))
                println("(-l) + 1L = " + ((-l) + 1L))
                println("(-f) + 0.5f = " + ((-f) + 0.5f))
                println("(-d) * 2.0 = " + ((-d) * 2.0))

                println("b + b = " + (b + b))
                println("b - b = " + (b - b))
                println("b * 3 = " + (b * 3))
                println("b / 2 = " + (b / 2))
                println("b % 3 = " + (b % 3))
                println("c + 1 = " + (c + 1))
                println("c - c = " + (c - c))
                println("c * 2 = " + (c * 2))
                println("c / 2 = " + (c / 2))
                println("c % 5 = " + (c % 5))
                println("b + l = " + (b + l))
                println("c + l = " + (c + l))
                println("b + f = " + (b + f))
                println("c + d = " + (c + d))
                println("i + d = " + (i + d))
                println("l + f = " + (l + f))

                println("b < i = " + (b < i))
                println("c > b = " + (c > b))
                println("b == 5 = " + (b == 5))
                println("c != 66 = " + (c != 66))
                println("b <= i = " + (b <= i))
                println("l >= i = " + (l >= i))

                println("toByte(200) = " + toByte(200))
                println("toChar(66) = " + toChar(66))
                println("charToInt('A') = " + charToInt('A'))
                var t : bool = true
                println("!t = " + (!t))
                rtn
            }
        """.trimIndent() + "\n"

        Rsf.analyze(source(program))

        val interpreted = runInterpreted(program)
        val compiled = runCompiledClasses(program, "RsfInteropPrimitives")
        // 比之前先确认真的比了不少行（否则「一致」可能只是两边都空）
        assertTrue(lines(interpreted).size >= 40, "语料太小：只有 ${lines(interpreted).size} 行")
        assertEquals(normalize(interpreted), normalize(compiled), "基础类型运算两端必须逐字节一致")

        // 抽几个值钉住语义（不是「两端一样地错」）
        val text = normalize(interpreted)
        assertTrue(text.startsWith("-b = -5"), text.take(80))
        assertTrue(text.contains("-c = -65"), text.take(200))
        assertTrue(text.contains("(-b) * 2 = -10"), text.take(400))
        assertTrue(text.contains("toByte(200) = -56"), text.take(1200))
        assertTrue(text.contains("c != 66 = true"), text.take(1200))
    }

    // ================================================================== HashMap 的擦除互操作

    /**
     * `HashMap&<K, V>`：**多个类型实参**也要能把签名里擦除成 `Object` 的形参/返回值
     * 还原成对应的实参类型。
     *
     * 以前只有「恰好一个类型实参」的 `ArrayList&<int>` 能用；`HashMap&<string, int>`
     * 的 `put` 两个形参都是 `Object`，一个实参都接不住
     * （报「没有匹配的方法 'put'（实参：string, int）」）。
     */
    @Test
    fun `HashMap 多类型实参的擦除互操作两端一致`() {
        val program = """
            import java.util.HashMap

            fn main() -> void {
                var m : HashMap&<string, int> = new HashMap&<string, int>()
                m.put("a", 7)
                m.put("b", 9)
                println("size = " + m.size())
                println("a = " + m.get("a"))
                println("b = " + m.get("b"))
                println("has a = " + m.containsKey("a"))
                println("has z = " + m.containsKey("z"))
                println("a + b = " + (m.get("a") + m.get("b")))

                var s : HashMap&<string, string> = new HashMap&<string, string>()
                s.put("k", "v")
                println("s = " + s.get("k"))
                println("s.len = " + s.get("k").length())

                var n : HashMap&<int, string> = new HashMap&<int, string>()
                n.put(3, "three")
                println("n = " + n.get(3))
                println("n.size = " + n.size())
                println("n.has = " + n.containsKey(3))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf(
                "size = 2", "a = 7", "b = 9", "has a = true", "has z = false", "a + b = 16",
                "s = v", "s.len = 1", "n = three", "n.size = 1", "n.has = true",
            ),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropHashMap")),
        )
    }

    /**
     * `put` 的返回值是**旧值**，第一次 `put` 返回 `null`；把它当语句写（结果丢掉）时
     * 两端都不能去拆箱。
     *
     * 这条是 17d 能不能用 `HashMap` 的关键：常量池去重 / 槽分配都是
     * 「`m.put(k, v)` 写一句、不管返回值」。做多类型实参还原之后，`put` 的静态返回类型
     * 变成了 `V`（`int`），如果语句位置照样 CHECKCAST + 拆箱，第一次 put 直接 NPE。
     * 修法：**结果被丢弃时不做泛型结果收敛**（字节码 `emitExpr(discard = true)`、
     * 解释器 `eval(discard = true)`），这也正是 Java 的行为（`map.put(...)` 当语句不拆箱）。
     */
    @Test
    fun `HashMap 的 put 当语句写不会因为旧值为 null 而炸`() {
        val program = """
            import java.util.HashMap

            fn main() -> void {
                var m : HashMap&<string, int> = new HashMap&<string, int>()
                m.put("a", 1)
                m.put("a", 2)
                m.put("b", 5)
                println("a = " + m.get("a"))
                println("size = " + m.size())
                var old = m.put("a", 3)
                println("old = " + old)
                println("a2 = " + m.get("a"))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("a = 2", "size = 2", "old = 2", "a2 = 3"), lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropHashMapPut")),
        )
    }

    /**
     * 「用 `HashMap` 重写 `Interp.rsf` 的 `Env` 符号表」的可行性验证：
     * 作用域链（内层遮蔽外层）、沿链向上查找、覆盖写、`size` / `containsKey`。
     *
     * 17c 的 `Env` 是「两条平行数组 + 线性查 + 满 8 翻倍」，理由就是 `HashMap` 用不了。
     * 这条测试证明那个理由已经不成立。
     */
    @Test
    fun `HashMap 能当符号表用两端一致`() {
        val program = """
            import java.util.HashMap

            pub class Val(k : string) {
                var kind : string = k
                var iv : int = 0
            }

            pub class Env(p : ?Env, nm : string) {
                var parent : ?Env = p
                var table : HashMap&<string, ?Val> = new HashMap&<string, ?Val>()
                var owner : string = nm

                constructor() {
                    this(null, "root")
                }

                pub fn define(name : string, value : Val) -> void {
                    table.put(name, value)
                }

                pub fn lookup(name : string) -> ?Val {
                    var e : ?Env = this
                    while (e != null) {
                        var hit : ?Val = e!!.table.get(name)
                        if (hit != null) {
                            rtn hit
                        }
                        e = e!!.parent
                    }
                    rtn null
                }

                pub fn has(name : string) -> bool {
                    rtn table.containsKey(name)
                }

                pub fn count() -> int {
                    rtn table.size()
                }
            }

            fn main() -> void {
                var global = new Env()
                global.define("x", new Val("int"))
                global.define("y", new Val("string"))

                var inner = new Env(global, "inner")
                inner.define("x", new Val("byte"))

                println("inner.x = " + inner.lookup("x")!!.kind)
                println("inner.y = " + inner.lookup("y")!!.kind)
                println("inner.count = " + inner.count())
                println("global.count = " + global.count())
                println("inner.has(z) = " + inner.has("z"))

                inner.define("y", new Val("long"))
                println("inner.y2 = " + inner.lookup("y")!!.kind)
                println("global.y = " + global.lookup("y")!!.kind)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf(
                "inner.x = byte", "inner.y = string", "inner.count = 1", "global.count = 2",
                "inner.has(z) = false", "inner.y2 = long", "global.y = string",
            ),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropEnvMap")),
        )
    }

    /** 元素类型写成用户类的非可空形态（`HashMap&<string, Val>`）也要能用。 */
    @Test
    fun `HashMap 装用户类的实参类型两端一致`() {
        val program = """
            import java.util.HashMap

            pub class Val(k : string) {
                var kind : string = k
            }

            fn main() -> void {
                var strict : HashMap&<string, Val> = new HashMap&<string, Val>()
                strict.put("only", new Val("bool"))
                println("kind = " + strict.get("only").kind)
                println("has = " + strict.containsKey("only"))
                println("size = " + strict.size())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("kind = bool", "has = true", "size = 1"), lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropMapUserVal")),
        )
    }

    /** 类型实参个数写错仍然要报错（多类型实参的还原没有把元数检查弄丢）。 */
    @Test
    fun `HashMap 的类型实参个数仍然受检查`() {
        val e = runCatching {
            Rsf.analyze(
                source(
                    "import java.util.HashMap\n\nfn main() -> void {\n" +
                        "    var m : HashMap&<string> = new HashMap&<string>()\n    rtn\n}\n",
                ),
            )
        }.exceptionOrNull()
        assertTrue(e != null, "类型实参个数不对必须报错")
        assertTrue(e.message!!.contains("类型实参"), e.message!!)
    }

    // ================================================================== 外部类的 new

    /**
     * `new` 一个 JDK 类并在两端一致地调用它的方法。
     *
     * 现状核对：`new` **不是**只支持用户自定义类——`ClassPath` 早就把 `<init>` 收进
     * 独立的 `constructors` 表，`MemberResolver.findConstructor` 按「个数 → 类型打分」
     * 挑重载，两条后端分别发 `NEW+DUP+INVOKESPECIAL <init>` 与反射 `Constructor.newInstance`。
     * 这条测试把「真的通了」钉住。
     */
    @Test
    fun `new 外部类两端一致`() {
        val program = """
            import java.io.File
            import java.util.ArrayList

            fn main() -> void {
                var f = new File("one")
                println("f = " + f.getName())

                var d = new File("dir", "two")
                println("d = " + d.getName())

                var l = new ArrayList&<string>()
                l.add("a")
                l.add("b")
                println("l.size = " + l.size())
                println("l.get = " + l.get(1))

                var sb = new StringBuilder(16)
                sb.append("xy")
                println("sb.len = " + sb.length())
                println("sb = " + sb.toString())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf("f = one", "d = two", "l.size = 2", "l.get = b", "sb.len = 2", "sb = xy"),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropExternalNew")),
        )
    }

    /**
     * **接口类型的接收者必须发 `INVOKEINTERFACE`**（阶段 20 M0 回归，2026-10-14）。
     *
     * 缺陷原样：`l.iterator()` 的返回类型 `java.util.Iterator` 是**接口**，后端却发
     * `INVOKEVIRTUAL java/util/Iterator.hasNext` ⇒ `rsf build` 能编过，运行期抛
     * `IncompatibleClassChangeError: Found interface java.util.Iterator, but class was expected`；
     * 而 `rsf run` 照常打出 `yes`——两端背离（本仓最不该出现的那一类不一致）。
     *
     * 修法：解析时把「owner 是不是接口」写进 `ResolvedMethod.interfaceOwner`，调用点照它选指令；
     * 判定只在 Checker 里做一次，两端不再各自猜（与阶段 19d 重载同一套哲学）。
     */
    @Test
    fun `接口类型的接收者调用两端一致`() {
        val program = """
            import java.util.ArrayList

            fn main() -> void {
                var l = new ArrayList&<string>()
                l.add("hi")
                var it = l.iterator()
                if (it.hasNext()) {
                    println("yes")
                } else {
                    println("no")
                }
                println("size = " + l.size())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("yes", "size = 1"), lines(interpreted), "接口接收者调用的语义")
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropIterator")),
            "解释器与字节码对接口接收者的调用必须逐字节一致",
        )
    }

    /**
     * 外部类**构造器重载**的挑选：先按实参**个数**、再按**类型**打分
     * （精确优先、加宽其次；两端读的是同一份决定）。
     *
     * `StringBuilder()` / `StringBuilder(int)` / `StringBuilder(String)` 三个重载
     * 正好把「个数」与「类型」两件事都覆盖到。
     */
    @Test
    fun `外部类构造器重载两端一致`() {
        val program = """
            import java.util.ArrayList

            fn main() -> void {
                var s0 = new StringBuilder()
                var s1 = new StringBuilder(16)
                var s2 = new StringBuilder("seed")
                println("s0 = " + s0.length())
                println("s1 = " + s1.length())
                println("s2 = " + s2.length())
                println("s2.text = " + s2.toString())

                // 泛型外部类 + 基本类型实参：`<init>(int)` 要能挑中（以前会挑不到）
                var l = new ArrayList&<string>(4)
                l.add("x")
                println("l.size = " + l.size())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf("s0 = 0", "s1 = 0", "s2 = 4", "s2.text = seed", "l.size = 1"),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropCtorOverload")),
        )
    }

    /** `new` 用户类与 `new` 外部类互不干扰（两张表的解析各走各的）。 */
    @Test
    fun `new 用户类与外部类互不干扰两端一致`() {
        val program = """
            import java.util.ArrayList

            pub class Point(x0 : int, y0 : int) {
                var x : int = x0
                var y : int = y0

                pub fn sum() -> int {
                    rtn x + y
                }
            }

            fn main() -> void {
                var p = new Point(1, 2)
                println("sum = " + p.sum())

                var l = new ArrayList&<int>()
                l.add(p.sum())
                l.add(10)
                println("l.size = " + l.size())
                println("l0+l1 = " + (l.get(0) + l.get(1)))

                var points = new ArrayList&<Point>()
                points.add(new Point(3, 4))
                println("points.sum = " + points.get(0).sum())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf("sum = 3", "l.size = 2", "l0+l1 = 13", "points.sum = 7"),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropUserAndExternal")),
        )
    }

    /**
     * 从**包私有的父类**继承来的 JDK 方法。
     *
     * `StringBuilder.length()` / `charAt` / `substring` 都声明在
     * `java.lang.AbstractStringBuilder` 上，而那个类**不是 public**。以前两端各崩各的：
     * 字节码发 `INVOKEVIRTUAL java/lang/AbstractStringBuilder.length()I` ⇒ 运行期
     * `IllegalAccessError`；解释器 `Class.forName` + `setAccessible(true)` ⇒
     * `InaccessibleObjectException`。修法是 javac 那套：包私有的声明类不当 owner，
     * 用**接收者的静态类型**当 owner（JVM 的方法解析本来就沿父类链走）。
     */
    @Test
    fun `从包私有父类继承的 JDK 方法两端一致`() {
        val program = """
            fn main() -> void {
                var sb = new StringBuilder("seed")
                println("len = " + sb.length())
                println("charAt = " + sb.charAt(1))
                println("sub = " + sb.substring(1, 3))
                sb.append("-tail")
                println("len2 = " + sb.length())
                println("text = " + sb.toString())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf("len = 4", "charAt = e", "sub = ee", "len2 = 9", "text = seed-tail"),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropInheritedJdk")),
        )
    }

    // ================================================================== ASM（17d 的路线前提）

    /**
     * ASM 的可用性：rsf 能不能 `new ClassWriter(ClassWriter.COMPUTE_FRAMES)` 并调
     * `toByteArray()`。
     *
     * 结论：**能**——`ClassWriter` / `Opcodes` 都在编译器自己的运行期 classpath 上
     * （`build.gradle` 的 `implementation 'org.ow2.asm:asm'`，`gradlew jar` 打出来的
     * 胖包里就是 ASM 那一整包 class）。注意两条路跑起来的 classpath 不一样：
     * `rsf run` 直接用胖包（ASM 天然在），`rsf build` 出来的 `.class` 要自己把
     * `rainStarfox-0.9.0.jar` 挂到 `-cp` 上才找得到 ASM。
     */
    @Test
    fun `ASM 的 ClassWriter 能从 rsf 里 new 出来并落成字节`() {
        val program = """
            import org.objectweb.asm.ClassWriter
            import org.objectweb.asm.Opcodes

            fn main() -> void {
                var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "AsmProbe", null, "java/lang/Object", null)
                var mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
                mv.visitCode()
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
                cw.visitEnd()
                var bytes : byte[] = cw.toByteArray()
                println("class bytes > 100 = " + (bytes.length > 100))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("class bytes > 100 = true"), lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropAsmProbe")),
        )
    }

    /**
     * 再往前一步：用 ASM 真的生成一份**合法**的 class 字节（`()I` 返回 42），
     * 并检查魔数。这是「用 rsf 写字节码后端」的最小闭环（生成 → `byte[]`）。
     *
     * rsf 里没有 `ClassLoader.defineClass`（它是 protected，且 rsf 没有继承），
     * 所以「把字节变成一个真的类」这一步在测试侧（Kotlin）做——见本测试最后那段。
     */
    @Test
    fun `rsf 用 ASM 生成的字节是一份合法 class 文件`() {
        val program = """
            import org.objectweb.asm.ClassWriter
            import org.objectweb.asm.Opcodes

            fn emit() -> byte[] {
                var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "RsfMadeClass", null, "java/lang/Object", null)
                var mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "answer", "()I", null, null)
                mv.visitCode()
                mv.visitIntInsn(Opcodes.BIPUSH, 42)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
                cw.visitEnd()
                rtn cw.toByteArray()
            }

            fn main() -> void {
                var bytes : byte[] = emit()
                println("bytes = " + bytes.length)
                println("magic ok = " + (bytes[0] == toByte(-54)))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfInteropAsmBytes")),
            "ASM 生成 class 字节这条路两端必须一致",
        )
        val out = lines(interpreted)
        assertTrue(out.first().startsWith("bytes = "), interpreted.take(120))
        assertTrue(out.contains("magic ok = true"), interpreted.take(200))

        // 把 rsf 生成的那份字节拿过来，问一下 JVM：它认不认
        val captured = captureRsfGeneratedBytes(program)
        assertTrue(captured.size > 100, "生成的 class 太小：${captured.size} 字节")
        assertEquals(0xCA.toByte(), captured[0], "class 文件魔数第 1 个字节")
        assertEquals(0xFE.toByte(), captured[1], "class 文件魔数第 2 个字节")
        val cls = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }.define("RsfMadeClass", captured)
        assertEquals(42, cls.getMethod("answer").invoke(null), "rsf 生成的类要能真的被 JVM 调起来")
    }

    /**
     * 把 rsf 程序里 `emit()` 产出的 `byte[]` 取回来。
     *
     * 同一份源码再编译一次、这次不跑 `main` 而是反射调 `emit()` —— rsf 的
     * `byte[]` 在 JVM 上就是真的 `byte[]`，所以直接就能拿。
     */
    private fun captureRsfGeneratedBytes(text: String): ByteArray {
        val src = source(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, "RsfInteropAsmBytesGrab", dir)
        val (loader, programName) = programLoader(out.classFiles, dir)
        return loader.loadClass(programName).getMethod("emit").invoke(null) as ByteArray
    }

    // ================================================================== helper

    //
    // 与 RsfTest.kt 里那套同口径：源码字符串 → Rsf.analyze → 解释执行 / Rsf.build →
    // 自定义类加载器 → 反射调 main。差别只在「把一批 .class 一起 define」
    // （外部类 / 泛型类 / 用户类的测试都要用到）。

    private fun source(text: String): SourceFile = SourceFile.of("<interop>", text)

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-interop", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')

    /** 把一批 `.class` define 进同一个加载器（先被依赖的用户类，最后程序类）。 */
    private fun programLoader(files: List<File>, dir: File): Pair<ClassLoader, String> {
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        for (f in files.drop(1)) {
            loader.define(binaryName(f, dir), f.readBytes())
        }
        val programName = binaryName(files.first(), dir)
        loader.define(programName, files.first().readBytes())
        return loader to programName
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
