package org.example

import org.example.complie.Rsf
import org.example.complie.ast.ControlType
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.sema.SemanticModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 20 追加件：可选修饰词 `override` 的回归测试。
 *
 * 为什么是**单独一个文件**：`InterfaceTest.kt` 那份是阶段 20 主体（`interface` 语法 + 语义）的
 * 前端测试，`override` 是随后追加的一件小事——语义照 Java 的 `@Override`：**写不写都能覆盖**，
 * 写了就必须真的覆盖到某个父类型方法。它跨的词法/语法/语义三层都有活，单独放更好找。
 *
 * 口径与全仓一致：**解释器与编译产物的 stdout 逐字节相同**（第 1、3 条跑两端），
 * 其余是前端（解析出什么 AST、Checker 报什么）。
 */
class OverrideTest {

    // ================================================================== 不写 override：行为不变

    /**
     * **anti-regression**：一个词都不写 `override` 时，实现接口方法照旧能过、照旧两端一致。
     *
     * 「可选」是这件追加件的全部意义所在——它只允许**加一句断言**，不允许改变任何既有程序
     * 的解析、语义或报错文案。
     */
    @Test
    fun `不写 override 也能实现接口方法`() {
        val program =
            """
            public interface Greeter {
                fn greet(n : string) -> string
            }
            class Dog : Greeter {
                public fn greet(n : string) -> string {
                    rtn "woof"
                }
            }
            fn main() -> void {
                var g : Greeter = new Dog()
                println(g.greet("a"))
                rtn
            }
            """.trimIndent() + "\n"

        val seen = bothEnds(program, "RsfOverrideNoKeyword")
        assertEquals(listOf("woof"), seen, "不写 override 时接口分派照旧")
    }

    // ================================================================== 语法：两种顺序

    /** `override` 与权限修饰词可以按任意顺序写，解析结果必须完全一样（都记成 isOverride）。 */
    @Test
    fun `override 与权限修饰词两种顺序都收`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet(n : string) -> string\n" +
                "}\n" +
                "class DogA : Greeter {\n" +
                "    override public fn greet(n : string) -> string {\n" +
                "        rtn \"a\"\n" +
                "    }\n" +
                "}\n" +
                "class DogB : Greeter {\n" +
                "    public override fn greet(n : string) -> string {\n" +
                "        rtn \"b\"\n" +
                "    }\n" +
                "}\n"
        val program = Rsf.parse(source(text))

        val methods = program.classes.flatMap { it.methods }.filter { !it.isConstructor }
        assertEquals(listOf("greet", "greet"), methods.map { it.name })
        assertTrue(methods.all { it.isOverride }, "两种顺序都要记成 isOverride=true")
        assertTrue(methods.all { it.control == ControlType.PUBLIC }, "顺序换了控制修饰词也不能丢")
    }

    // ================================================================== 类：覆盖成功

    /**
     * 类里的 `override` 覆盖到接口方法：两端（解释器 / 字节码）跑出来的 stdout 逐字节相同。
     *
     * 顺带钉住「默认方法内部调 `greet`」这件事——默认方法在接口里写的 `greet(n)` 是**接口分派**，
     * 运行期要落到实现类的覆盖版本上（这正是 M3 的 INVOKEINTERFACE 语义）。
     */
    @Test
    fun `类里 override 覆盖接口方法后两端一致`() {
        val program =
            """
            public interface Greeter {
                fn greet(n : string) -> string
                fn shout(n : string) -> string {
                    rtn greet(n) + "!"
                }
            }
            class Dog : Greeter {
                public override fn greet(n : string) -> string {
                    rtn "woof-" + n
                }
            }
            fn main() -> void {
                var g : Greeter = new Dog()
                println(g.greet("a"))
                println(g.shout("b"))
                rtn
            }
            """.trimIndent() + "\n"

        val seen = bothEnds(program, "RsfOverrideClass")
        assertEquals(listOf("woof-a", "woof-b!"), seen, "默认方法里的 greet 也要落到覆盖版本上")
    }

    /** 类里的 `override` 覆盖的是 **JDK** 接口（`Runnable`）的方法：JDK 那一支同样认。 */
    @Test
    fun `类里 override 能覆盖 JDK 接口方法`() {
        val text =
            "import java.lang.Runnable\n" +
                "class Robot : Runnable {\n" +
                "    public override fn run() -> void {\n" +
                "        rtn\n" +
                "    }\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    // ================================================================== 类：覆盖落空

    /** 类里写了 `override`，父接口里却没有同名同参数的方法——报错，并说清缺的是什么签名。 */
    @Test
    fun `类里 override 覆盖不到父接口方法就报错`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet(n : string) -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "    public override fn shout(n : string) -> string {\n" +
                "        rtn \"x\"\n" +
                "    }\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("checker", e.phase)
        assertTrue(
            e.detail.contains("类 'Dog' 的 'shout' 写了 override，但父类型里没有可覆盖的方法"),
            e.detail,
        )
        assertTrue(e.detail.contains("接口里没有人要求 shout"), e.detail)
    }

    /** 类里写了 `override`，但 JDK 接口里也没有那条签名——同样报错。 */
    @Test
    fun `类里 override 覆盖不到 JDK 接口方法也报错`() {
        val text =
            "import java.lang.Runnable\n" +
                "class Robot : Runnable {\n" +
                "    public fn run() -> void {\n" +
                "        rtn\n" +
                "    }\n" +
                "    public override fn walk() -> void {\n" +
                "        rtn\n" +
                "    }\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("类 'Robot' 的 'walk' 写了 override"), e.detail)
    }

    /**
     * **一个接口都没实现的类**写 `override` 也要报错。
     *
     * rsf 看不见父类（D2 不支持类继承），也看不见 `java/lang/Object` 的成员——用户对象上
     * 连 `toString()` 都调不到，后端自己合成的那个不算可覆盖的方法。所以这种类里的 `override`
     * 一定落空，报出来比放着当装饰好。
     */
    @Test
    fun `类里一个接口都没实现时 override 也报错`() {
        val text =
            "class Plain {\n" +
                "    public override fn f() -> int {\n" +
                "        rtn 1\n" +
                "    }\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("checker", e.phase)
        assertTrue(
            e.detail.contains("类 'Plain' 的 'f' 写了 override，但父类型里没有可覆盖的方法"),
            e.detail,
        )
    }

    // ================================================================== 接口：覆盖父接口方法

    /** 接口里的 `override`：父接口的**抽象方法**与**默认方法**都算可覆盖。 */
    @Test
    fun `接口里 override 覆盖父接口方法能过`() {
        val text =
            "public interface Named {\n" +
                "    fn name() -> string\n" +
                "    fn tag() -> string {\n" +
                "        rtn \"named\"\n" +
                "    }\n" +
                "}\n" +
                "public interface Person : Named {\n" +
                "    override public fn name() -> string\n" +
                "    public override fn tag() -> string {\n" +
                "        rtn \"person\"\n" +
                "    }\n" +
                "}\n" +
                "class Emp : Person {\n" +
                "    public fn name() -> string {\n" +
                "        rtn \"emp\"\n" +
                "    }\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    /** 接口里的 `override` 找不到父接口方法：报错文案说的是「它继承的接口」。 */
    @Test
    fun `接口里 override 没有父接口可覆盖就报错`() {
        val text =
            "public interface Person {\n" +
                "    override public fn name() -> string\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("checker", e.phase)
        assertTrue(
            e.detail.contains("接口 'Person' 的 'name' 写了 override，但它继承的接口里没有"),
            e.detail,
        )
    }

    // ================================================================== 写错了地方：当场报错

    /** 顶层函数写 `override`：没有父类型可覆盖，解析阶段就说清楚是哪个词出问题。 */
    @Test
    fun `顶层函数写 override 当场报错`() {
        val text =
            "override fn f() -> void {\n" +
                "    rtn\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("parser", e.phase)
        assertTrue(
            e.detail.contains("只有类/接口里的方法能写 override：顶层函数没有父类型方法可覆盖"),
            e.detail,
        )
    }

    /** 构造器写 `override`：构造器不参与覆盖，解析阶段就拦下。 */
    @Test
    fun `构造器写 override 当场报错`() {
        val text =
            "class P {\n" +
                "    override constructor() {\n" +
                "    }\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("构造器不能写 override"), e.detail)
    }

    /** 字段写 `override`：字段不是方法，解析阶段就拦下（别掉进「顶层只能写函数声明」还摸不着头脑）。 */
    @Test
    fun `字段写 override 当场报错`() {
        val text =
            "class P {\n" +
                "    override var x : int = 1\n" +
                "}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(text)) }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("实际读到 'var'"), e.detail)
        // 通用文案看不出是哪个词的问题，Parser 会在 override 这一支上补一句专说的
        assertTrue(e.detail.contains("'override' 只能写在方法前面（fn 前）"), e.detail)
    }

    // ================================================================== helper

    /** 解释器跑一遍、编译产物再跑一遍，断言 stdout 逐字节相同，返回行列表。 */
    private fun bothEnds(text: String, className: String): List<String> {
        val interpreted = runInterpreted(text)
        val compiled = runCompiledClasses(text, className)
        assertEquals(
            normalize(interpreted),
            normalize(compiled),
            "解释器与字节码对覆盖/接口分派必须逐字节一致",
        )
        return lines(interpreted)
    }

    private fun source(text: String): SourceFile = SourceFile.of("<override>", text)

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-override", "").let {
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
     * 与 `InterfaceRuntimeTest.kt` 同一份写法：显式 define 一批是按文件顺序走的，而这批程序里
     * 接口要先于实现类可用，交给 `findClass` 按需加载才没有顺序问题。
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
