package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 19d：函数/方法重载。
 *
 * 这一组测试盯的是**用户拍板的那套口径**：
 *
 * 1. **顶层函数与类方法共用一套重载解析**（按实参个数 → 类型打分 → 挑最低分）；
 * 2. **最低分并列就报错**并列出候选签名，绝不静默取「声明在前」的那个
 *    ——**构造器保持老规矩**（同分取声明在前），那个差异写在 BOOTSTRAP.md / README.md 里；
 * 3. **擦除后同描述符的重载必须编译期拒绝**（`f(Box&<int>)` 与 `f(Box&<string>)`）；
 * 4. 选中结果只由 Checker 决定一次，**解释器与字节码两端读同一份**——
 *    每条正面用例都同时断言「输出对不对」和「两端逐字节一致」。
 *
 * 采样口径与其余测试一致：解释器走 `Interpreter`，字节码走 `Rsf.build` + 反射调 `main`，
 * 两边都把 `System.out` 换成 UTF-8 的 `ByteArrayOutputStream` 再比字符串。
 */
class OverloadTest {

    // ================================================================== 正面：选得对

    @Test
    fun `按参数个数重载 顶层函数与类方法两端一致`() {
        val program = """
            fn tag() -> string {
                rtn "无参"
            }
            fn tag(a : int) -> string {
                rtn "一参：" + a
            }
            fn tag(a : int, b : int) -> string {
                rtn "两参：" + (a + b)
            }

            class Greeter {
                var who : string = "world"

                fn greet() -> string {
                    rtn "hello"
                }
                fn greet(name : string) -> string {
                    rtn "hello, " + name
                }
            }

            fn main() -> void {
                println(tag())
                println(tag(1))
                println(tag(1, 2))
                var g = new Greeter()
                println(g.greet())
                println(g.greet(g.who))
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(
            listOf("无参", "一参：1", "两参：3", "hello", "hello, world"),
            lines(interpreted),
        )
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `按精确类型重载 int string bool 各选各的`() {
        val program = """
            fn kind(a : int) -> string {
                rtn "int"
            }
            fn kind(a : string) -> string {
                rtn "string"
            }
            fn kind(a : bool) -> string {
                rtn "bool"
            }

            class Teller {
                fn kind(a : int) -> string {
                    rtn "m-int"
                }
                fn kind(a : string) -> string {
                    rtn "m-string"
                }
                fn kind(a : bool) -> string {
                    rtn "m-bool"
                }
            }

            fn main() -> void {
                println(kind(1))
                println(kind("s"))
                println(kind(true))
                var t = new Teller()
                println(t.kind(1))
                println(t.kind("s"))
                println(t.kind(true))
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(
            listOf("int", "string", "bool", "m-int", "m-string", "m-bool"),
            lines(interpreted),
        )
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `按加宽距离重载 精确优先且窄加宽优先`() {
        val program = """
            fn pick(a : int) -> string {
                rtn "int"
            }
            fn pick(a : long) -> string {
                rtn "long"
            }
            fn pick(a : double) -> string {
                rtn "double"
            }

            // 没有 int 这一档时：byte 加宽到 long 是 2 分、到 double 是 4 分 ⇒ 选 long
            fn narrow(a : long) -> string {
                rtn "narrow-long"
            }
            fn narrow(a : double) -> string {
                rtn "narrow-double"
            }

            // 字面量特例（0..127 的 int 字面量能给 byte）只值 1 分，精确匹配的 int 是 0 分
            fn lit(a : byte) -> string {
                rtn "lit-byte"
            }
            fn lit(a : int) -> string {
                rtn "lit-int"
            }

            fn main() -> void {
                var b : byte = 5
                println(pick(b))
                var i : int = 7
                println(pick(i))
                var l : long = 9L
                println(pick(l))
                println(pick(1.5))
                println(narrow(b))
                println(lit(5))
                var bb : byte = 5
                println(lit(bb))
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(
            listOf("int", "int", "long", "double", "narrow-long", "lit-int", "lit-byte"),
            lines(interpreted),
        )
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `静态方法与实例方法各自重载 含类内隐式调用`() {
        val program = """
            class Calc {
                static fn name(a : int) -> string {
                    rtn "static-int"
                }
                static fn name(a : string) -> string {
                    rtn "static-string"
                }

                fn name(a : int, b : int) -> string {
                    rtn "inst-两参：" + (a + b)
                }

                // 类体内裸写方法名：隐式 this / 隐式类名，同样走重载解析
                fn viaThis() -> string {
                    rtn name(1, 2)
                }
                static fn viaClass() -> string {
                    rtn name("s")
                }
            }

            fn main() -> void {
                println(Calc.name(1))
                println(Calc.name("s"))
                println(Calc.viaClass())
                var c = new Calc()
                println(c.name(1, 2))
                println(c.viaThis())
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(
            listOf("static-int", "static-string", "static-string", "inst-两参：3", "inst-两参：3"),
            lines(interpreted),
        )
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `重载之间的递归与互相调用`() {
        val program = """
            fn count(n : int) -> int {
                if (n <= 0) {
                    rtn 0
                }
                rtn 1 + count(n - 1)
            }

            // 两参的那一个递归回**自己**（不是递归回一参的那个）
            fn count(n : int, label : string) -> string {
                if (n <= 0) {
                    rtn label
                }
                rtn label + count(n - 1, label)
            }

            fn sum(lo : int, hi : int) -> int {
                if (lo > hi) {
                    rtn 0
                }
                rtn lo + sum(lo + 1, hi)
            }

            // 两个重载互相调用
            fn odd(n : int) -> bool {
                if (n == 0) {
                    rtn false
                }
                rtn even(n - 1)
            }
            fn even(n : int) -> bool {
                if (n == 0) {
                    rtn true
                }
                rtn odd(n - 1)
            }

            fn main() -> void {
                println(count(4))
                println(count(3, "ab"))
                println(sum(1, 10))
                println(odd(7))
                println(even(10))
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(listOf("4", "abababab", "55", "true", "true"), lines(interpreted))
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `可能失败的重载共存 try 与 elvis 都能用`() {
        val program = """
            fn risky(a : int) -> !int {
                if (a < 0) {
                    fail "negative"
                }
                rtn a * 2
            }

            // 同一个名字下的普通重载：不带 `!`，调用点不用处理错误
            fn risky(a : int, b : int) -> int {
                rtn a + b
            }

            fn main() -> void {
                try {
                    println(risky(3))
                    println(risky(9))
                } catch (e : string) {
                    println("err " + e)
                }

                // (b) 兜底值：出错取右边
                println(risky(-1) ?: -1)

                // 普通重载照常
                println(risky(1, 2))

                // (a) try / catch
                try {
                    var v = risky(-5)
                    println("不会到这里：" + v)
                } catch (e : string) {
                    println("接住 " + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(listOf("6", "18", "-1", "3", "接住 negative"), lines(interpreted))
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `泛型类的方法可以重载 类型实参替换后再解析`() {
        val program = """
            class Box&<T> {
                var value : T = 0

                constructor(v0 : T) {
                    value = v0
                }

                fn put(v : T) -> string {
                    value = v
                    rtn "一参"
                }

                // 形参里同样有 T：Box&<int> 上它与上一句只有个数不同，不构成擦除冲突
                fn put(v : T, times : int) -> string {
                    value = v
                    rtn "两参 " + times
                }
            }

            fn main() -> void {
                var b = new Box&<int>(0)
                println(b.put(7))
                println(b.put(8, 2))
                println(b.value)

                var s = new Box&<string>("hi")
                println(s.put("yo"))
                println(s.value)
                rtn
            }
        """.trimIndent() + "\n"

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(listOf("一参", "两参 2", "8", "一参", "yo"), lines(interpreted))
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `main 可以重载 无参的那个才是入口`() {
        val program = """
            fn main(a : int) -> void {
                println("带参 " + a)
                rtn
            }

            fn main() -> void {
                println("入口")
                rtn
            }
        """.trimIndent() + "\n"

        val model = Rsf.analyze(source(program))
        assertEquals(2, model.functionsNamed("main").size)
        assertEquals(0, model.entry!!.params.size)

        val (interpreted, compiled) = bothEnds(program)
        assertEquals(listOf("入口"), lines(interpreted))
        assertEquals(interpreted, compiled)
    }

    // ================================================================== 反面：拦得住

    @Test
    fun `擦除后同描述符的重载被编译期拦下`() {
        // 顶层函数之间
        val top = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class Box&<T> {\n    var value : T = 0\n}\n" +
                        "fn f(a : Box&<int>) -> int {\n    rtn 1\n}\n" +
                        "fn f(a : Box&<string>) -> int {\n    rtn 2\n}\n" +
                        "fn main() -> void {\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(top.detail.contains("擦除"), top.detail)
        assertTrue(top.detail.contains("LBox;"), top.detail)
        assertTrue(top.detail.contains("Box&<int>"), top.detail)
        assertTrue(top.detail.contains("Box&<string>"), top.detail)
        assertTrue(top.detail.contains("函数 'f'"), top.detail)

        // 同一个类的两个方法之间
        val method = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class Box&<T> {\n    var value : T = 0\n}\n" +
                        "class Holder {\n" +
                        "    fn f(a : Box&<int>) -> int {\n        rtn 1\n    }\n" +
                        "    fn f(a : Box&<string>) -> int {\n        rtn 2\n    }\n" +
                        "}\n" +
                        "fn main() -> void {\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(method.detail.contains("类 'Holder' 里的方法 'f'"), method.detail)
        assertTrue(method.detail.contains("擦除"), method.detail)
        assertTrue(method.detail.contains("LBox;"), method.detail)

        // 只靠返回类型区分也不行（重载只能看参数）
        val byReturn = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn g(a : int) -> int {\n    rtn 1\n}\n" +
                        "fn g(a : int) -> string {\n    rtn \"s\"\n}\n" +
                        "fn main() -> void {\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(byReturn.detail.contains("不看返回类型"), byReturn.detail)
    }

    @Test
    fun `最高分并列时报错并列出全部候选签名`() {
        // null 字面量给两个引用类型都是 1 分 ⇒ 真的并列，不是「声明在前」取胜
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class A {\n    var x : int = 0\n}\n" +
                        "class B {\n    var x : int = 0\n}\n" +
                        "fn pick(v : ?A) -> string {\n    rtn \"A\"\n}\n" +
                        "fn pick(v : ?B) -> string {\n    rtn \"B\"\n}\n" +
                        "fn main() -> void {\n    println(pick(null))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("并列"), e.detail)
        assertTrue(e.detail.contains("候选有 2 个"), e.detail)
        assertTrue(e.detail.contains("pick(v : ?A)"), e.detail)
        assertTrue(e.detail.contains("pick(v : ?B)"), e.detail)
        assertTrue(e.detail.contains("声明顺序"), e.detail)
    }

    @Test
    fun `实参类型对不上任何重载时报错并列出候选`() {
        // 多个候选都对不上：列候选
        val many = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f(a : int) -> string {\n    rtn \"int\"\n}\n" +
                        "fn f(a : string) -> string {\n    rtn \"string\"\n}\n" +
                        "fn main() -> void {\n    println(f(true))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(many.detail.contains("对不上任何候选"), many.detail)
        assertTrue(many.detail.contains("f(a : int)"), many.detail)
        assertTrue(many.detail.contains("f(a : string)"), many.detail)
        assertTrue(many.detail.contains("bool"), many.detail)

        // 个数不对：也要把候选列出来
        val arity = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f(a : int) -> string {\n    rtn \"int\"\n}\n" +
                        "fn f(a : string) -> string {\n    rtn \"string\"\n}\n" +
                        "fn main() -> void {\n    println(f(1, 2))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(arity.detail.contains("参数个数不对"), arity.detail)
        assertTrue(arity.detail.contains("f(a : int)"), arity.detail)
        assertTrue(arity.detail.contains("f(a : string)"), arity.detail)

        // 只有一个候选时，仍然说清是**哪一个实参**不对（老文案）
        val single = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn add(a : int) -> int {\n    rtn a\n}\n" +
                        "fn main() -> void {\n    println(add(true))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(single.detail.contains("参数类型不匹配"), single.detail)
        assertTrue(single.detail.contains("'a'"), single.detail)
    }

    @Test
    fun `两个无参 main 被拦 报入口歧义`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    rtn\n}\nfn main() -> void {\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("入口"), e.detail)
        assertTrue(e.detail.contains("main"), e.detail)
        assertTrue(e.detail.contains("只能有一个"), e.detail)
    }

    @Test
    fun `泛型函数从阶段 31 起已支持 不再是解析错误`() {
        // 阶段 31 之前这里报「泛型函数还不支持」；现在按实参推导（详见 GenericFnTest）
        Rsf.analyze(
            source(
                "fn pick&<T>(a : T) -> T {\n    rtn a\n}\n" +
                    "fn main() -> void {\n    var x : int = pick(7)\n    rtn\n}\n",
            ),
        )
    }

    @Test
    fun `签名完全一样不算重载 仍然报重复定义`() {
        val top = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn a(x : int) -> int {\n    rtn x\n}\n" +
                        "fn a(y : int) -> int {\n    rtn y\n}\n",
                ),
            )
        }
        assertTrue(top.detail.contains("重复定义"), top.detail)

        val method = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n" +
                        "    fn f(a : int) -> int {\n        rtn a\n    }\n" +
                        "    fn f(b : int) -> int {\n        rtn b\n    }\n}\n",
                ),
            )
        }
        assertTrue(method.detail.contains("重复定义"), method.detail)
    }

    // ================================================================== 工具

    private fun source(text: String): SourceFile = SourceFile.of("<overload>", text)

    /**
     * 同一份源码走两条后端，返回 (解释器 stdout, 字节码 stdout)，都已归一化成 `\n`。
     *
     * 用 [runCompiledClasses] 而不是只加载程序类的那条：重载用例里常常带用户自定义类。
     */
    private fun bothEnds(text: String): Pair<String, String> =
        normalize(runInterpreted(text)) to normalize(runCompiledClasses(text))

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-overload", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')

    /** 编译（程序类 + 每个用户类）并加载，返回 (类加载器, 程序类)。 */
    private fun compileAndLoad(text: String, className: String): Pair<ClassLoader, Class<*>> {
        val src = source(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(src), src, className, dir)
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        // 顺序：先用户类、最后程序类（程序类的方法体引用着用户类）
        for (f in out.classFiles.drop(1)) loader.define(binaryName(f, dir), f.readBytes())
        val programName = binaryName(out.classFiles.first(), dir)
        loader.define(programName, out.classFiles.first().readBytes())
        return loader to loader.loadClass(programName)
    }

    private fun runCompiledClasses(text: String, className: String = "RsfOverload"): String {
        val (_, cls) = compileAndLoad(text, className)
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

    private fun runInterpreted(text: String): String {
        val model = Rsf.analyze(source(text))
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        // 也要换掉 System.out —— 调用 Java 的 System.out.println 时写的是它
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
