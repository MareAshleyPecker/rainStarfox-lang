package org.example

import org.example.complie.Rsf
import org.example.complie.ast.CallExpr
import org.example.complie.ast.ClassImport
import org.example.complie.ast.ControlType
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.NewExpr
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interop.ClassPath
import org.example.complie.interp.Interpreter
import org.example.complie.interp.RsfFailure
import org.example.complie.lsp.EditorSupport
import org.example.complie.loading.LoadedProgram
import org.example.complie.sema.RsfType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RsfTest {

    // ------------------------------------------------------------------ 签名

    @Test
    fun `两种返回样式等价`() {
        val arrow = Rsf.parse(source("pub fn one() -> int {\n    rtn 1\n}\n"))
        val colon = Rsf.parse(source("pub fn one() : int {\n    rtn 1\n}\n"))

        val a = arrow.functions.single()
        val b = colon.functions.single()
        for (decl in listOf(a, b)) {
            assertEquals("one", decl.name)
            assertEquals("int", decl.ret.type.name)
            assertEquals(ControlType.PUBLIC, decl.control)
        }
    }

    @Test
    fun `MainType 槽位缺省是 void 且只接受 void`() {
        val decl = Rsf.parse(source("fn main() -> void {\n    rtn\n}\n")).functions.single()
        assertEquals("void", decl.mainType.name)
        assertEquals(ControlType.DEFAULT, decl.control)
        assertEquals("void", decl.ret.type.name)

        val withKeyword = Rsf.parse(source("fn void main() -> void {\n    rtn\n}\n")).functions.single()
        assertEquals("void", withKeyword.mainType.name)

        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn int f() -> int {\n    rtn 1\n}\n"))
        }
        assertTrue(e.detail.contains("<MainType>"), e.detail)
    }

    @Test
    fun `访问控制必须写在 fn 前面`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn pub add() -> int {\n    rtn 1\n}\n"))
        }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("fn") && e.detail.contains("前面") || e.detail.contains("函数名"), e.detail)
    }

    // ------------------------------------------------------------------ protected / static

    @Test
    fun `protected 与 static 写在 fun 前面`() {
        val decl = Rsf.parse(source("prot stc fn util() -> int {\n    rtn 1\n}\n")).functions.single()
        assertEquals(ControlType.PROTECTED, decl.control)
        assertTrue(decl.isStatic, "stc 槽位没被记下来")

        // 三档访问控制都能解析出来
        assertEquals(
            ControlType.PRIVATE,
            Rsf.parse(source("prv fn f() -> int {\n    rtn 1\n}\n")).functions.single().control,
        )
        assertEquals(
            ControlType.DEFAULT,
            Rsf.parse(source("fn f() -> int {\n    rtn 1\n}\n")).functions.single().control,
        )
    }

    @Test
    fun `顶层写 static 只是多余 给警告但照常能调`() {
        val model = Rsf.analyze(
            source(
                "stc fn twice(n : int) -> int {\n    rtn n * 2\n}\n\n" +
                    "fn main() -> void {\n    println(twice(21))\n    rtn\n}\n",
            ),
        )
        assertTrue(model.warnings.any { it.contains("stc") }, model.warnings.toString())

        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("42"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `protected 函数可以跨文件调用`() {
        val root = project(
            mapOf(
                "lib/util.rsf" to "prot fn secret() -> int {\n    rtn 7\n}\n",
                "main.rsf" to "import \"lib/util.rsf\"\n\nfn main() -> void {\n    println(secret())\n    rtn\n}\n",
            ),
        )
        val model = Rsf.analyze(Rsf.load(root))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("7"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `private 函数跨文件调用在编译期就被拦住`() {
        // 这是关键一条：private 编译成 ACC_PRIVATE 之后，解释器跑得通、字节码会在运行期抛
        // IllegalAccessError。两端行为会分歧，所以必须在编译期挡掉。
        val root = project(
            mapOf(
                "lib/util.rsf" to "prv fn secret() -> int {\n    rtn 7\n}\n",
                "main.rsf" to "import \"lib/util.rsf\"\n\nfn main() -> void {\n    println(secret())\n    rtn\n}\n",
            ),
        )
        val e = assertFailsWith<RsfError> { Rsf.analyze(Rsf.load(root)) }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("prv"), e.detail)
        assertTrue(e.detail.contains("util.rsf"), e.detail)
    }

    @Test
    fun `private 函数在自己文件里照常能调`() {
        val model = Rsf.analyze(
            source(
                "prv fn secret() -> int {\n    rtn 7\n}\n\n" +
                    "fn main() -> void {\n    println(secret())\n    rtn\n}\n",
            ),
        )
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("7"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `访问控制在字节码里就是 JVM 的访问标志`() {
        val bytes = compileToBytes(
            "prot fn util() -> int {\n    rtn 1\n}\n\n" +
                "fn main() -> void {\n    println(util())\n    rtn\n}\n",
        )
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        val method = loader.define("RsfParity", bytes).getDeclaredMethod("util")
        assertTrue(java.lang.reflect.Modifier.isProtected(method.modifiers), "修饰符=${method.modifiers}")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers), "修饰符=${method.modifiers}")
    }

    // ------------------------------------------------------------------ 类（阶段 12 语法层）

    @Test
    fun `类声明能解析出字段 构造器与普通方法`() {
        val program = Rsf.parse(
            source(
                "pub class Point {\n" +
                    "    var x : int = 0\n" +
                    "    imutvar id : int = 7\n" +
                    "    constructor(x0 : int) {\n        x = x0\n    }\n" +
                    "    prot fn sum() -> int {\n        rtn x\n    }\n" +
                    "}\n",
            ),
        )
        val cls = program.classes.single()
        assertEquals("Point", cls.name)
        assertEquals(ControlType.PUBLIC, cls.control)
        assertEquals(listOf("x", "id"), cls.fields.map { it.name })
        assertEquals(listOf(true, false), cls.fields.map { it.mutable })
        assertEquals("int", cls.fields.first().type.name)
        // 名字等于类名的那个方法就是构造器
        assertEquals(listOf("Point"), cls.constructors.map { it.name })
        assertEquals(listOf("sum"), cls.plainMethods.map { it.name })
        assertEquals(ControlType.PROTECTED, cls.plainMethods.single().control)
    }

    @Test
    fun `new 与 this 能解析成 AST 节点`() {
        val program = Rsf.parse(
            source(
                "fn main() -> void {\n" +
                    "    var p = new Point(1, 2)\n" +
                    "    println(this.x)\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
        val statements = program.functions.single().body!!.statements

        val decl = statements[0] as VarDeclStmt
        val init = decl.initializer
        assertTrue(init is NewExpr, init.toString())
        assertEquals("Point", (init as NewExpr).className)
        assertEquals(2, init.arguments.size)

        // this.x 走的是普通的成员访问，接收者是 ThisExpr
        val printed = (statements[1] as ExprStmt).expr as CallExpr
        val member = printed.arguments.single() as MemberExpr
        assertTrue(member.receiver is ThisExpr, member.receiver.toString())
        assertEquals("x", member.name)
    }

    @Test
    fun `字段必须写类型和初值`() {
        val noType = assertFailsWith<RsfError> {
            Rsf.parse(source("class P {\n    var x = 1\n}\n"))
        }
        assertTrue(noType.detail.contains("必须写类型"), noType.detail)

        val noInit = assertFailsWith<RsfError> {
            Rsf.parse(source("class P {\n    var x : int\n}\n"))
        }
        assertTrue(noInit.detail.contains("必须给初值"), noInit.detail)
    }

    @Test
    fun `类程序能通过检查 未知的类是真诊断`() {
        // 12-C1 之后：类程序（含 new）能正常通过 check 与 run，不再报「还没实现」
        val model = Rsf.analyze(
            source("class P {\n    var x : int = 1\n}\n\nfn main() -> void {\n    var p = new P()\n    rtn\n}\n"),
        )
        assertEquals(listOf("P"), model.classModel.classes.keys.toList())

        // 未知的类是**真诊断**（不是「没实现」）
        val created = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var p = new P()\n    rtn\n}\n"))
        }
        assertTrue(created.detail.contains("没有这个类"), created.detail)
    }

    // ------------------------------------------------------------------ 类语义（阶段 12-B）

    @Test
    fun `方法里的裸字段名 与 this 都能解析`() {
        val program = """
            class P {
                var v : int = 1
                fn bump() -> void {
                    v = v + 1
                    this.v = this.v + 1
                }
                fn get() -> int { rtn this.v }
            }
            fn main() -> void {
                var p = new P()
                println(p.get())
                p.bump()
                println(p.get())
                rtn
            }
        """.trimIndent()

        val model = Rsf.analyze(source(program))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        // 1 →（bump 里改了两回）3
        assertEquals(listOf("1", "3"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `对象之间互不影响 字段初值每次都跑`() {
        val program = """
            class Counter {
                var n : int = 0
                pub fn inc() -> int {
                    n = n + 1
                    rtn n
                }
            }
            fn main() -> void {
                var a = new Counter()
                var b = new Counter()
                println(a.inc())
                println(a.inc())
                println(b.inc())
                rtn
            }
        """.trimIndent()

        val model = Rsf.analyze(source(program))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("1", "2", "1"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `构造器实参 静态方法 字段初值互相引用 都能跑`() {
        val program = """
            class Point {
                var x : int = 0
                var y : int = 0
                var origin_x : int = 100
                constructor(x0 : int, y0 : int) {
                    x = x0
                    y = y0
                }
                pub fn sum() -> int { rtn x + y }
                pub stc fn zero() -> int { rtn 0 }
            }
            fn main() -> void {
                var p = new Point(3, 4)
                println(p.sum())
                println(p.origin_x)
                println(Point.zero())
                rtn
            }
        """.trimIndent()

        val model = Rsf.analyze(source(program))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("7", "100", "0"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `类名能用在顶层函数签名里 方法之间能直接按名字调`() {
        // 这两条都是写 lang/class.rsf 时被示例抓出来的真 bug：
        //   1) declareClasses 排在 declareFunctions 后面 → 顶层函数签名里的类名解析不到
        //   2) 方法体里 `add(1)` 这种隐式 this 调用只认顶层函数表
        val program = """
            pub class Box {
                var v : int = 0
                constructor(v0 : int) { v = v0 }
                pub fn bump() -> void { add(1) }
                prv fn add(d : int) -> void { v = v + d }
            }
            pub fn total(a : Box, b : Box) -> int {
                rtn a.v + b.v
            }
            fn main() -> void {
                var x = new Box(1)
                var y = new Box(2)
                x.bump()
                println(total(x, y))
                rtn
            }
        """.trimIndent()

        val model = Rsf.analyze(source(program))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("4"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `类程序能编译成 class 而不是报还没接通`() {
        // 阶段 12-C2 拆掉了「类的字节码生成还没接通」那道闸门：
        // build 现在会为程序类和每个用户类各写一个 .class，不再只编顶层函数。
        val program = source(
            "class P {\n    var x : int = 0\n}\n\nfn main() -> void {\n" +
                "    var p = new P()\n    println(p.x)\n    rtn\n}\n",
        )
        val model = Rsf.analyze(program)
        val dir = File.createTempFile("rsf-build", "").let { it.delete(); it.mkdirs(); it }
        val out = Rsf.build(model, program, "RsfClassGate", dir)
        assertTrue(out.classFile.isFile, "程序类没生成：${out.classFile}")
        assertTrue(File(dir, "P.class").isFile, "用户类没生成：${File(dir, "P.class")}")
    }

    @Test
    fun `字段初值类型不对会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("class P {\n    var x : int = \"abc\"\n}\nfn main() -> void { rtn }\n"))
        }
        assertTrue(e.detail.contains("字段 'x'"), e.detail)
        assertTrue(e.detail.contains("int"), e.detail)
    }

    @Test
    fun `构造器参数个数与类型都要对得上`() {
        val arity = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor(x0 : int) { x = x0 }\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(arity.detail.contains("参数个数不对"), arity.detail)
        assertTrue(arity.detail.contains("P(int x0)"), arity.detail)

        val type = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor(x0 : int) { x = x0 }\n}\n" +
                        "fn main() -> void {\n    var p = new P(\"nope\")\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(type.detail.contains("第 1 个实参"), type.detail)
    }

    @Test
    fun `没写构造器时只能 new 空参`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n}\n" +
                        "fn main() -> void {\n    var p = new P(1)\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("没写构造器"), e.detail)
    }

    @Test
    fun `静态方法与实例方法的调用方式不能搞混`() {
        val viaObject = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    pub stc fn make() -> int { rtn 1 }\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    println(p.make())\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(viaObject.detail.contains("stc"), viaObject.detail)

        val viaClass = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    pub fn get() -> int { rtn x }\n}\n" +
                        "fn main() -> void {\n    println(P.get())\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(viaClass.detail.contains("对象"), viaClass.detail)
    }

    @Test
    fun `访问不存在的成员会列出可用的成员`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    var y : int = 0\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    println(p.z)\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("没有字段 'z'"), e.detail)
        assertTrue(e.detail.contains("x"), e.detail)
    }

    @Test
    fun `给 imutvar 字段重新赋值会被拦住`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    imutvar id : int = 7\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    p.id = 9\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("imutvar"), e.detail)
    }

    @Test
    fun `private 方法只能在类内部用`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    prv fn secret() -> int { rtn 1 }\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    println(p.secret())\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("prv"), e.detail)
        assertTrue(e.detail.contains("prot 或 pub"), e.detail)
    }

    @Test
    fun `类名不能和函数名重名 方法可以重载`() {
        val clash = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn P() -> int { rtn 1 }\nclass P {\n    var x : int = 0\n}\n"))
        }
        assertTrue(clash.detail.contains("不能重名"), clash.detail)

        // 阶段 19d：同一个类里的同名方法不再一律报错——它是重载，按实参挑。
        // 挑的结果记进语义模型，解释器与字节码两端读同一份。
        val program = """
            class P {
                var x : int = 0
                fn f() -> int { rtn 1 }
                fn f(a : int) -> int { rtn a }
            }
            fn main() -> void {
                var p = new P()
                println(p.f())
                println(p.f(7))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("1", "7"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiledClasses(program)))

        // 签名完全一样仍然要拦（那已经不算重载了）
        val same = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    fn f(a : int) -> int { rtn a }\n" +
                        "    fn f(b : int) -> int { rtn b }\n}\n",
                ),
            )
        }
        assertTrue(same.detail.contains("重复定义"), same.detail)
    }

    @Test
    fun `字段可以用自定义类做类型`() {
        val program = """
            class Node {
                var value : int = 7
            }
            class Holder {
                var node : ?Node = null
            }
            fn main() -> void {
                var h = new Holder()
                println(h.node)
                h.node = new Node()
                println(h.node!!.value)
                rtn
            }
        """.trimIndent()

        val model = Rsf.analyze(source(program))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        // 没赋值时是 null，赋上之后能顺着读出字段
        assertEquals(listOf("null", "7"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `rtn 与 return 等价`() {        val a = Rsf.analyze(source("fn main() -> void {\n    println(1)\n    rtn\n}\n"))
        val b = Rsf.analyze(source("fn main() -> void {\n    println(1)\n    return\n}\n"))
        assertEquals(a.functions.single().name, b.functions.single().name)
        assertEquals("void", b.functions.single().ret.type.name)
    }

    @Test
    fun `rtn 独占一行时不会吃掉下一行语句`() {
        // 这是 ASI 那类问题：旧写法会把 println(1) 当成返回值
        val model = Rsf.analyze(
            source("fn main() -> void {\n    rtn\n    println(1)\n}\n"),
        )
        assertTrue(model.warnings.any { it.contains("不可达") }, model.warnings.toString())
    }

    // ------------------------------------------------------------------ 阶段 14：!T 与 try / catch

    @Test
    fun `未处理的 !T 调用会编译报错`() {
        // 旧行为（`!T` 还没实现）已经被阶段 14 取代：声明合法，但调用方必须显式处理
        val err = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub fn parse(s : string) -> !int {\n" +
                        "    rtn 1\n" +
                        "}\n\n" +
                        "fn main() -> void {\n" +
                        "    println(parse(\"x\"))\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        // 报错文案必须把三种处理方式都说清楚
        assertTrue(err.detail.contains("三种写法"), err.detail)
        assertTrue(err.detail.contains("try"), err.detail)
        assertTrue(err.detail.contains("?:"), err.detail)
        assertTrue(err.detail.contains("!!"), err.detail)
        assertTrue(err.detail.contains("!int"), err.detail)

        // 处理掉就通过（这里用 ?:；另外两种在别的用例里）
        Rsf.analyze(
            source(
                "pub fn parse(s : string) -> !int {\n" +
                    "    rtn 1\n" +
                    "}\n\n" +
                    "fn main() -> void {\n" +
                    "    println(parse(\"x\") ?: -1)\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )

        // `?:` 只处理它**左边那一次**调用：藏在实参里的 !T 调用仍要自己处理
        // （这里 f 返回可空类型，左边根本不是 !T 调用，走的是「null 就取右边」）
        val nested = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub fn parse(s : string) -> !int {\n" +
                        "    rtn 1\n" +
                        "}\n\n" +
                        "fn wrap(s : ?string) -> ?string {\n" +
                        "    rtn s\n" +
                        "}\n\n" +
                        "fn main() -> void {\n" +
                        "    var x = wrap(\"\" + parse(\"x\")) ?: \"兜底\"\n" +
                        "    println(x)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(nested.detail.contains("三种写法"), nested.detail)
    }

    @Test
    fun `fail 用在不该用的地方会报错`() {
        // 非 !T 函数、又不在 try 里 —— 这个错误没人接得住
        val err = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    fail \"boom\"\n    rtn\n}\n"))
        }
        assertTrue(err.detail.contains("!T"), err.detail)
        assertTrue(err.detail.contains("try"), err.detail)

        // 包进 try 就合法（哪怕函数本身不是 !T）
        Rsf.analyze(
            source(
                "fn main() -> void {\n" +
                    "    try {\n" +
                    "        fail \"boom\"\n" +
                    "    } catch (e : string) {\n" +
                    "        println(e)\n" +
                    "    }\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )

        // 消息必须是 string
        val notString = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f() -> !void {\n    fail 42\n    rtn\n}\n"))
        }
        assertTrue(notString.detail.contains("string"), notString.detail)
    }

    @Test
    fun `!! 传播要求本函数也是 !T`() {
        val program = "pub fn parse(s : string) -> !int {\n    rtn 1\n}\n\n"

        val err = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    program +
                        "fn main() -> void {\n" +
                        "    println(parse(\"x\")!!)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(err.detail.contains("!T"), err.detail)

        // 本函数也是 !T 就合法：错误继续往上抛
        val ok = Rsf.analyze(
            source(
                program +
                    "pub fn bump() -> !int {\n" +
                    "    rtn parse(\"x\")!! + 1\n" +
                    "}\n\n" +
                    "fn main() -> void {\n" +
                    "    try {\n" +
                    "        println(bump())\n" +
                    "    } catch (e : string) {\n" +
                    "        println(\"接住\")\n" +
                    "    }\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
        assertEquals(3, ok.functions.size) // parse / bump / main 都检查过了
    }

    @Test
    fun `fail 与 try catch 的两端一致`() {
        val program = """
            pub fn parse(s : string) -> !int {
                if (s.length() == 0) {
                    fail "空字符串没法转数字"
                }
                rtn 42
            }

            fn main() -> void {
                try {
                    var n = parse("abc")
                    println("n = " + n)
                } catch (e : string) {
                    println("出错了：" + e)
                }

                try {
                    var m = parse("")
                    println("m = " + m)
                } catch (e : string) {
                    println("接住了：" + e)
                }

                // 嵌套 try：必须由**内层**先接住（JVM 异常表是按顺序找第一条匹配的）
                try {
                    try {
                        println("内层正常：" + parse(""))
                    } catch (e : string) {
                        println("内层接住：" + e)
                    }
                    println("内层之后还能继续")
                } catch (e : string) {
                    println("外层不该接住：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "n = 42",
            "接住了：空字符串没法转数字",
            "内层接住：空字符串没法转数字",
            "内层之后还能继续",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        // 硬承诺：两条后端逐字节一致
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrParity")),
        )
    }

    @Test
    fun `问号冒号兜底值两端一致`() {
        // ?: 左边是 !T 调用时：出错取右边的值，正常路径取左边的值（类型都是非 ! 的正常类型）
        val program = """
            pub fn parse(s : string) -> !int {
                if (s.length() == 0) {
                    fail "空字符串"
                }
                rtn 42
            }

            pub fn boom() -> !string {
                fail "实参里的错"
            }

            pub fn note() -> int {
                println("兜底被求值了")
                rtn -1
            }

            fn main() -> void {
                println("出错的：" + (parse("") ?: -1))
                println("正常的：" + (parse("ab") ?: -1))

                var n = parse("") ?: 7
                println("赋值：" + n)

                var s = parse("") ?: 8
                println("再来一次：" + s)

                // 兜底值只在**真的出错**时才求值（两端都必须是这样）
                println("正常不取兜底：" + (parse("ab") ?: note()))
                println("出错才取兜底：" + (parse("") ?: note()))

                // 实参求值时的失败不算「这次调用出错」：继续往外层 try 传（两端一致）
                try {
                    println("实参：" + (parse(boom()) ?: -1))
                } catch (e : string) {
                    println("外层接住：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "出错的：-1",
            "正常的：42",
            "赋值：7",
            "再来一次：8",
            "正常不取兜底：42",
            "兜底被求值了",
            "出错才取兜底：-1",
            "外层接住：实参里的错",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrElvis")),
        )
    }

    @Test
    fun `错误能跨多层函数传播`() {
        val program = """
            pub fn c(n : int) -> !int {
                if (n < 0) {
                    fail "负数：" + n
                }
                rtn n * 2
            }

            pub fn b(n : int) -> !int {
                rtn c(n)!! + 1
            }

            pub fn a(n : int) -> !int {
                rtn b(n)!! + 1
            }

            fn main() -> void {
                try {
                    println("a(1) = " + a(1))
                    println("a(-1) = " + a(-1))
                } catch (e : string) {
                    println("A 接住：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("a(1) = 4", "A 接住：负数：-1")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrDeep")),
        )
    }

    @Test
    fun `!void 函数可以 rtn 不带值`() {
        val program = """
            pub fn check(n : int) -> !void {
                if (n < 0) {
                    fail "check 只接受非负数"
                }
                println("check 通过：" + n)
                rtn
            }

            fn main() -> void {
                try {
                    check(3)
                    check(-3)
                    println("这一行不会执行")
                } catch (e : string) {
                    println("失败：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("check 通过：3", "失败：check 只接受非负数")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrVoid")),
        )

        // !void 后面还能继续写语句（fail 只是抛出去，不是 rtn）
        val after = Rsf.analyze(
            source(
                "pub fn f() -> !void {\n" +
                    "    rtn\n" +
                    "}\n\n" +
                    "fn main() -> void {\n" +
                    "    try {\n" +
                    "        f()\n" +
                    "        println(\"回来了\")\n" +
                    "    } catch (e : string) {\n" +
                    "        println(e)\n" +
                    "    }\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
        assertEquals(2, after.functions.size)
    }

    @Test
    fun `类方法里的 !T 与兜底值两端一致`() {
        val program = """
            class Calc {
                var base : int = 0

                constructor(v : int) {
                    base = v
                }

                pub fn divide(n : int) -> !int {
                    if (n == 0) {
                        fail "除以零：" + base
                    }
                    rtn base / n
                }

                pub fn safeDivide(n : int) -> int {
                    rtn divide(n) ?: -1
                }

                pub stc fn parse(s : string) -> !int {
                    if (s.length() == 0) {
                        fail "静态方法里的空串"
                    }
                    rtn 7
                }
            }

            fn main() -> void {
                var c = new Calc(10)
                println("10 / 2 = " + c.safeDivide(2))
                println("10 / 0 = " + c.safeDivide(0))

                try {
                    println("直接调：" + c.divide(0))
                } catch (e : string) {
                    println("接住：" + e)
                }

                println("静态：" + (Calc.parse("") ?: 99))
                println("静态正常：" + (Calc.parse("x") ?: 99))
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "10 / 2 = 5",
            "10 / 0 = -1",
            "接住：除以零：10",
            "静态：99",
            "静态正常：7",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrMethod")),
        )
    }

    @Test
    fun `try 里的 break 与 rtn 都不影响控制流分析`() {
        // `while (true)` 里从 try 块中 break 出去 = 这个循环有出口。
        // 漏判的话「所有路径都返回」会算错，字节码那边还会指着一个不存在的标签。
        val program = """
            pub fn pick(n : int) -> !int {
                if (n < 0) {
                    fail "负数"
                }
                rtn n
            }

            fn firstOk(limit : int) -> int {
                var i = 0
                while (true) {
                    try {
                        println("试 " + i)
                        if (i >= limit) {
                            break
                        }
                        var n = pick(i - 1)
                        println("拿到 " + n)
                    } catch (e : string) {
                        println("接了：" + e)
                    }
                    i = i + 1
                }
                rtn i
            }

            fn main() -> void {
                println("结果：" + firstOk(2))
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("试 0", "接了：负数", "试 1", "拿到 0", "试 2", "结果：2")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfErrFlow")),
        )
    }

    @Test
    fun `可空与错误返回不能在同一处混用`() {
        // `?int!` 这种「既可能失败、又可能返回 null」的函数：`?:` / `!!` 到底是解包还是抛错
        // 说不清，所以这一版**明确拒绝**，而不是含糊地放过去。
        val program = "pub fn maybe(n : int) -> !?int {\n    rtn n\n}\n\n"

        val force = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    program +
                        "fn main() -> void {\n" +
                        "    println(\"x = \" + (maybe(1)!!))\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(force.detail.contains("暂不支持"), force.detail)
        assertTrue(force.detail.contains("可空"), force.detail)

        val elvis = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    program +
                        "fn main() -> void {\n" +
                        "    println(\"x = \" + (maybe(1) ?: 0))\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(elvis.detail.contains("暂不支持"), elvis.detail)

        // 分开处理就没问题：try 里接住，再由 ?: 处理 null
        Rsf.analyze(
            source(
                program +
                    "fn main() -> void {\n" +
                    "    try {\n" +
                    "        var v : ?int = maybe(1)\n" +
                    "        println(\"x = \" + (v ?: 0))\n" +
                    "    } catch (e : string) {\n" +
                    "        println(e)\n" +
                    "    }\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
    }

    @Test
    fun `try 与 catch 的写法错误会给出提示`() {
        val noCatch = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    try {\n        println(1)\n    }\n    rtn\n}\n"))
        }
        assertEquals("parser", noCatch.phase)
        assertTrue(noCatch.detail.contains("catch"), noCatch.detail)

        val badType = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    try {\n        println(1)\n    } catch (e int) {\n        println(e)\n    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(badType.detail.contains("string"), badType.detail)

        val noParen = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    try {\n        println(1)\n    } catch e string {\n        println(e)\n    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(noParen.detail.contains("'('"), noParen.detail)

        val bareCatch = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    catch (e : string) { }\n    rtn\n}\n"))
        }
        assertEquals("parser", bareCatch.phase)
        assertTrue(bareCatch.detail.contains("try"), bareCatch.detail)

        val noMessage = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f() -> !void {\n    fail\n    rtn\n}\n"))
        }
        assertEquals("parser", noMessage.phase)
        assertTrue(noMessage.detail.contains("fail"), noMessage.detail)
    }

    @Test
    fun `catch 两种类型写法都能用`() {
        // 阶段 19b：catch 变量的类型标注改成 Kotlin 风格 `catch (e : string)`，
        // 老写法 `catch (e string)` 不打死（和函数参数一样两种风格并存）。
        // 两种写法解析出来的 TryStmt 完全一样，所以两端输出必须一字不差。
        val kotlinStyle = """
            fn boom(n : int) -> !int {
                if (n < 0) { fail "负数：" + n }
                rtn n * 2
            }

            fn main() -> void {
                try {
                    println("正常 " + boom(3))
                } catch (e : string) {
                    println("出错 " + e)
                }
                try {
                    println("不该走到这里 " + boom(-1))
                } catch (e : string) {
                    println("接住了：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val javaStyle = kotlinStyle.replace("catch (e : string)", "catch (e string)")
        assertTrue(javaStyle.contains("catch (e string)"), "老写法没被替换出来")

        val expected = listOf("正常 6", "接住了：负数：-1")
        val interpreted = runInterpreted(kotlinStyle)
        assertEquals(expected, lines(interpreted))
        // 老写法只是词法上多一个冒号的区别，跑出来必须一模一样
        assertEquals(
            normalize(interpreted),
            normalize(runInterpreted(javaStyle)),
            "两种 catch 写法解释执行必须一致",
        )

        val compiledNew = runCompiledClasses(kotlinStyle, "RsfCatchStyleKotlin")
        val compiledOld = runCompiledClasses(javaStyle, "RsfCatchStyleJava")
        assertEquals(
            normalize(interpreted),
            normalize(compiledNew),
            "Kotlin 风格 catch 必须两端逐字节一致",
        )
        assertEquals(
            normalize(compiledNew),
            normalize(compiledOld),
            "两种 catch 写法编译执行必须一致",
        )

        // 漏了类型：两种写法的提示都要给出来（用户照着任一条改都能过）
        val missing = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    try {\n        println(1)\n    } catch (e) {\n        println(e)\n    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertEquals("parser", missing.phase)
        assertTrue(missing.detail.contains("catch (e : string)"), missing.detail)
        assertTrue(missing.detail.contains("catch (e string)"), missing.detail)

        // 类型写错（Kotlin 风格那一支）：同样把两种写法都列出来
        val wrongType = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    try {\n        println(1)\n    } catch (e : int) {\n        println(e)\n    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(wrongType.detail.contains("catch (e : string)"), wrongType.detail)
        assertTrue(wrongType.detail.contains("catch (e string)"), wrongType.detail)
    }

    // ------------------------------------------------------------------ 控制流

    @Test
    fun `while 循环两端一致`() {
        val program = """
            fn fibOf10() -> int {
                var a = 0
                var b = 1
                var i = 0
                while (i < 10) {
                    var t = a + b
                    a = b
                    b = t
                    i = i + 1
                }
                rtn a
            }

            fn main() -> void {
                println("fib = " + fibOf10())
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("fib = 55"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `goto 与标签两端一致`() {
        val program = """
            fn main() -> void {
                var n = 0
            top:
                if (n >= 3) { goto done }
                println("n = " + n)
                n = n + 1
                goto top
            done:
                rtn
            }
        """.trimIndent() + "\n"
        val expected = listOf("n = 0", "n = 1", "n = 2")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `break 与 continue 两端一致`() {
        val program = """
            fn main() -> void {
                var i = 0
                var acc = 0
                while (i < 10) {
                    i = i + 1
                    if (i % 2 == 0) : continue
                    if (i > 7) { break }
                    acc = acc + i
                }
                println("acc = " + acc)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("acc = 16"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `三目是表达式两端一致`() {
        val program = """
            fn pick() -> int {
                var x = 3
                rtn if x > 2 ? 100 : 200
            }

            fn main() -> void {
                println(pick())
                var s = if 1 < 2 ? "y" : "n"
                println(s)
                println(if true ? 1 : 2L)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("100", "y", "1"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `块引入新作用域`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var c = true\n" +
                        "    if (c) { var x = 1 }\n" +
                        "    println(x)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("未声明的变量 'x'"), e.detail)
    }

    @Test
    fun `同名变量可以在内层块里遮蔽`() {
        val program = """
            fn main() -> void {
                var x = 1
                {
                    var x = 99
                    println("inner = " + x)
                }
                println("outer = " + x)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("inner = 99", "outer = 1"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    // ------------------------------------------------------------------ else 与可变性

    @Test
    fun `else 与 else if 链两端一致`() {
        val program = """
            fn classify() -> string {
                var x = 7
                if (x < 0) {
                    rtn "negative"
                } else if (x == 0) {
                    rtn "zero"
                } else if (x < 5) {
                    rtn "small"
                } else {
                    rtn "big"
                }
            }

            fn main() -> void {
                println(classify())
                var n = 4
                if n > 3 : println("big")
                else : println("small")
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("big", "big"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `else 分支也会参与所有路径返回的判断`() {
        // 两个分支都 rtn，函数末尾不可达 —— 不该报“走到函数末尾”
        val ok = Rsf.analyze(
            source(
                "fn f() -> int {\n" +
                    "    var c = true\n" +
                    "    if (c) { rtn 1 } else { rtn 2 }\n" +
                    "}\n",
            ),
        )
        assertEquals(RsfType.INT, ok.returnTypeOf(ok.functions.single()))

        // else 分支没 rtn，就可能掉出去
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f() -> int {\n" +
                        "    var c = true\n" +
                        "    if (c) { rtn 1 } else { println(2) }\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("走到函数末尾"), e.detail)
    }

    @Test
    fun `else 块里声明的变量出了块不可见`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var c = true\n" +
                        "    if (c) { rtn } else { imutvar y = 1 }\n" +
                        "    println(y)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("未声明的变量 'y'"), e.detail)
    }

    @Test
    fun `imutvar 不能重新赋值`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    imutvar x = 1\n    x = 2\n    rtn\n}\n"))
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("imutvar"), e.detail)
    }

    @Test
    fun `var 可以重新赋值`() {
        val program = """
            fn main() -> void {
                var x = 1
                x = x + 41
                println(x)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("42"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `imutvar 与 var 生成的指令序列一致`() {
        val a = Rsf.analyze(source("fn main() -> void {\n    imutvar x = 1 + 2\n    rtn\n}\n"))
        val b = Rsf.analyze(source("fn main() -> void {\n    var x = 1 + 2\n    rtn\n}\n"))
        assertEquals(
            a.loweredOf(a.functions.single()).instructions.size,
            b.loweredOf(b.functions.single()).instructions.size,
        )
    }

    @Test
    fun `let 已经换成 var 与 imutvar 并给出提示`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    let x = 1\n    rtn\n}\n"))
        }
        assertEquals("parser", e.phase)
        // 带引号比，免得被 `imutvar` 里那三个字母 `var` 蒙混过去
        assertTrue(e.detail.contains("'var'"), e.detail)
        assertTrue(e.detail.contains("imutvar"), e.detail)
        // 老名字 `varis` 已经彻底退场：提示里不该再出现它
        assertFalse(e.detail.contains("varis"), e.detail)
    }

    @Test
    fun `varis 已改名为 var 并给出改写提示`() {
        // 2026-10-11 拍板：`var` 是唯一写法，`varis` **不再接受**。
        // 它已经不在关键字表里（表要跟 rsf 版逐条对齐，51 项不变），所以是在词法层
        // 被一条独立判断拦下的 —— 这条测试钉住「报错清楚 + 给出改写 + 两端同一句话」。
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    varis x : int = 0\n    rtn\n}\n"))
        }
        assertEquals("lexer", e.phase)
        assertTrue(e.detail.contains("'varis' 已改名为 'var'"), e.detail)
        assertTrue(e.detail.contains("var x : int = 0"), e.detail)

        // 局部变量只是其中一种位置：字段 / for 初始化 / 顶层 —— 走的是同一个词法入口，
        // 所以都要给出同一条文案（不是「先报别的错、顺带提一句」）。
        val positions = listOf(
            "class P {\n    varis x : int = 0\n}\n",
            "fn main() -> void {\n    for (varis i = 0; i < 3; i = i + 1) { }\n    rtn\n}\n",
            "varis g : int = 1\nfn main() -> void {\n    rtn\n}\n",
            "fn main() -> void {\n    varis n : int = 0\n    rtn\n}\n",
        )
        for (bad in positions) {
            val err = assertFailsWith<RsfError> { Rsf.parse(source(bad)) }
            assertEquals("lexer", err.phase, bad)
            assertTrue(err.detail.contains("'varis' 已改名为 'var'"), "$bad\n-> ${err.detail}")
        }

        // rsf 版词法器（rsfSrc/bootstrap/Lexer.rsf）给的是**同一条文案**：
        // 它那边的 keywordKind 表里同样没有 `varis`，靠的是同一条独立判断。
        val rsfMessage = normalize(
            rsfLexerOutput(
                "fn main() -> void {\n" +
                    "    println(lexError(\"varis x : int = 0\\n\"))\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        ).trim()
        assertEquals(e.detail, rsfMessage, "`varis` 的报错文案两端必须逐字相同")
    }

    // ------------------------------------------------------------------ 控制流分析

    @Test
    fun `跳过 var 的 goto 会被确定性赋值拦住`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    goto skip\n" +
                        "    var x = 1\n" +
                        "skip:\n" +
                        "    println(x)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("可能还没被赋值"), e.detail)
    }

    @Test
    fun `跳进块里绕过初始化同样被拦住`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    goto inside\n" +
                        "    {\n" +
                        "        var y = 7\n" +
                        "    inside:\n" +
                        "        println(y)\n" +
                        "    }\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("可能还没被赋值"), e.detail)
    }

    @Test
    fun `非 void 函数存在掉出去的路径会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f() -> int {\n" +
                        "    var c = true\n" +
                        "    if (c) { rtn 1 }\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("走到函数末尾"), e.detail)
    }

    @Test
    fun `所有路径都 return 就合法`() {
        val model = Rsf.analyze(
            source(
                "fn f() -> int {\n" +
                    "    var c = true\n" +
                    "    if (c) { rtn 1 }\n" +
                    "    rtn 2\n" +
                    "}\n",
            ),
        )
        val fn = model.functions.single()
        assertEquals("f", fn.name)
        assertEquals(RsfType.INT, model.returnTypeOf(fn))
        assertTrue(model.warnings.isEmpty(), model.warnings.toString())
    }

    @Test
    fun `无限循环加 return 也算所有路径都返回`() {
        val program = """
            fn f() -> int {
                while (true) {
                    rtn 7
                }
            }

            fn main() -> void {
                println(f())
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("7"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `break 与 continue 必须在循环里`() {
        val b = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    break\n    rtn\n}\n"))
        }
        assertTrue(b.detail.contains("break"), b.detail)

        val c = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    continue\n    rtn\n}\n"))
        }
        assertTrue(c.detail.contains("continue"), c.detail)
    }

    @Test
    fun `goto 不能跳到不存在的标签`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    goto nowhere\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("没有定义"), e.detail)
    }

    @Test
    fun `重复标签会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\nloop:\n    rtn\nloop:\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("重复定义"), e.detail)
    }

    @Test
    fun `if 的条件必须是 bool`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    if (1) { rtn }\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("bool"), e.detail)
    }

    @Test
    fun `三目两个分支类型不一致会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var x = if true ? 1 : \"s\"\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("不一致"), e.detail)
    }

    @Test
    fun `for-in 缺区间会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    for (i in 0) { }\n    rtn\n}\n"))
        }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains(".."), e.detail)
    }

    // ------------------------------------------------------------------ for

    @Test
    fun `for-in 两端一致`() {
        val program = """
            fn main() -> void {
                var sum = 0
                for (i in 0..4) {
                    sum = sum + i
                }
                println("sum = " + sum)

                var even = 0
                for (i in 0..10 step 2) {
                    even = even + i
                }
                println("even = " + even)

                var desc = ""
                for (i in 3..0 step -1) {
                    desc = desc + i
                }
                println("desc = " + desc)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("sum = 10", "even = 30", "desc = 3210"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `for 的 continue 落到步进而不是条件`() {
        // 如果解糖成 while，continue 会跳过 `i = i + 1`，这里就会死循环
        val program = """
            fn main() -> void {
                var odd = 0
                for (i in 0..9) {
                    if (i % 2 == 0) : continue
                    odd = odd + i
                }
                println("for-in odd = " + odd)

                var odd2 = 0
                for (var j = 0; j < 10; j = j + 1) {
                    if (j % 2 == 0) : continue
                    odd2 = odd2 + j
                }
                println("classic odd = " + odd2)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("for-in odd = 25", "classic odd = 25"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `Java 式 for 的 break 与嵌套两端一致`() {
        val program = """
            fn main() -> void {
                var count = 0
                for (var k = 0; k < 100; k = k + 1) {
                    if (k >= 3) { break }
                    count = count + 1
                }
                println("count = " + count)

                var nested = 0
                for (var a = 0; a < 3; a = a + 1) {
                    for (var b = 0; b < 3; b = b + 1) {
                        if (b == 1) : continue
                        nested = nested + 1
                    }
                }
                println("nested = " + nested)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("count = 3", "nested = 6"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `for 三段都可以省略`() {
        val program = """
            fn main() -> void {
                var n = 0
                for (;;) {
                    n = n + 1
                    if (n >= 3) { break }
                }
                println("n = " + n)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("n = 3"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `for 的初始化变量出了循环就不可见`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    for (var i = 0; i < 3; i = i + 1) { }\n" +
                        "    println(i)\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("未声明的变量 'i'"), e.detail)
    }

    @Test
    fun `for-in 的循环变量不可赋值`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    for (i in 0..3) { i = 99 }\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("imutvar"), e.detail)
    }

    @Test
    fun `for 的条件必须是 bool`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    for (var i = 0; i; i = i + 1) { }\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("bool"), e.detail)
    }

    @Test
    fun `step 仍然可以作为普通标识符`() {
        // step 只在 for 头部当关键字
        val program = """
            fn main() -> void {
                var step = 5
                for (i in 0..4) {
                    step = step + i
                }
                println("step = " + step)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("step = 15"), lines(runInterpreted(program)))
    }

    // ------------------------------------------------------------------ 函数参数

    @Test
    fun `参数与递归两端一致`() {
        val program = """
            fn fact(n : int) -> int {
                if (n <= 1) { rtn 1 }
                rtn n * fact(n - 1)
            }

            fn fib(n : int) -> int {
                if (n < 2) { rtn n }
                rtn fib(n - 1) + fib(n - 2)
            }

            pub fn add(a : int, b : int) -> int {
                rtn a + b
            }

            fn main() -> void {
                println("fact(5) = " + fact(5))
                println("fib(10) = " + fib(10))
                println("add(add(1, 2), fact(3)) = " + add(add(1, 2), fact(3)))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("fact(5) = 120", "fib(10) = 55", "add(add(1, 2), fact(3)) = 9"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `实参会按形参类型加宽`() {
        val program = """
            fn mix(a : int, b : long, c : double) -> double {
                rtn a + b + c
            }

            fn onlyLong(a : long) -> long {
                rtn a
            }

            fn main() -> void {
                println(mix(1, 10L, 0.5))
                println(mix(1, 10, 0.5d))
                println(onlyLong(7))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("11.5", "11.5", "7"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `参数默认可变`() {
        val program = """
            fn twice(n : int) -> int {
                n = n * 2
                rtn n
            }

            fn main() -> void {
                println(twice(21))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("42"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `参数个数不对会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn add(a : int, b : int) -> int {\n    rtn a + b\n}\n" +
                        "fn main() -> void {\n    println(add(1))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("参数个数不对"), e.detail)
        assertTrue(e.detail.contains("add(a : int, b : int)"), e.detail)
    }

    @Test
    fun `参数类型不匹配会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn add(a : int) -> int {\n    rtn a\n}\n" +
                        "fn main() -> void {\n    println(add(\"s\"))\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("参数类型不匹配"), e.detail)
        assertTrue(e.detail.contains("'a'"), e.detail)
    }

    @Test
    fun `参数类型不能是 void`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f(a : void) -> int {\n    rtn 1\n}\n"))
        }
        assertTrue(e.detail.contains("不能是 void"), e.detail)
    }

    @Test
    fun `参数重名会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f(a : int, a : int) -> int {\n    rtn a\n}\n"))
        }
        assertTrue(e.detail.contains("重名"), e.detail)
    }

    @Test
    fun `参数与函数体顶层同名会报重复声明`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn f(a : int) -> int {\n    var a = 2\n    rtn a\n}\n"),
            )
        }
        assertTrue(e.detail.contains("重复声明"), e.detail)
    }

    @Test
    fun `参数可以在内层块里被遮蔽`() {
        val program = """
            fn show(a : int) -> int {
                var total = a
                {
                    var a = 100
                    total = total + a
                }
                rtn total
            }

            fn main() -> void {
                println(show(1))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("101"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `main 带参数时 run 会报错但函数本身可用`() {
        val source = source(
            "fn main(a : int) -> void {\n    println(a)\n    rtn\n}\n",
        )
        // 静态检查通过
        val model = Rsf.analyze(source)
        assertEquals(1, model.paramsOf(model.functions.single()).size)
        // 但不能当入口跑
        val e = assertFailsWith<RsfError> {
            Interpreter(model, PrintStream(ByteArrayOutputStream())).runEntry()
        }
        assertTrue(e.detail.contains("不能有参数"), e.detail)
    }

    // ------------------------------------------------------------------ 可空类型

    @Test
    fun `可空返回与 elvis 两端一致`() {
        val program = """
            fn find(id : int) : ?string {
                if (id == 1) { rtn "one" }
                rtn null
            }

            fn greet(who : ?string) -> string {
                rtn "hello, " + (who ?: "stranger")
            }

            fn main() -> void {
                println(find(1) ?: "not found")
                println(find(9) ?: "not found")
                println(greet("rsf"))
                println(greet(null))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("one", "not found", "hello, rsf", "hello, stranger"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `null 比较与强制解包两端一致`() {
        val program = """
            fn maybe(n : int) : ?string {
                if (n > 0) { rtn "yes" }
                rtn null
            }

            fn main() -> void {
                var a = maybe(1)
                if (a == null) { println("a is null") } else { println("a = " + a) }
                println("a!! = " + a!!)

                var b = maybe(0)
                if (b == null) { println("b is null") } else { println("b = " + b) }
                println("b ?: - = " + (b ?: "-"))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("a = yes", "a!! = yes", "b is null", "b ?: - = -"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `可空基本类型会装箱两端一致`() {
        val program = """
            fn maybeInt(n : int) -> ?int {
                if (n > 0) { rtn n * 10 }
                rtn null
            }

            fn maybeLong(n : int) : ?long {
                if (n > 0) { rtn 9000000000L }
                rtn null
            }

            fn main() -> void {
                var n = maybeInt(4)
                println("n = " + n)
                println("n ?: -1 = " + (n ?: -1))

                var m = maybeInt(-1)
                println("m = " + m)
                println("m ?: -1 = " + (m ?: -1))

                var big = maybeLong(1)
                println("big ?: 0L = " + (big ?: 0L))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("n = 40", "n ?: -1 = 40", "m = null", "m ?: -1 = -1", "big ?: 0L = 9000000000"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `显式类型标注可以声明可空变量`() {
        val program = """
            fn main() -> void {
                var s : ?string = null
                println("s = " + s)
                s = "set"
                println("s = " + s)

                imutvar k : int = 7
                println("k = " + k)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("s = null", "s = set", "k = 7"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `非空函数不能 rtn null`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f() -> string {\n    rtn null\n}\n"))
        }
        assertTrue(e.detail.contains("不能 'rtn null'"), e.detail)
        assertTrue(e.detail.contains("?string"), e.detail)
    }

    @Test
    fun `可空不能赋给非空`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f() : ?string {\n    rtn null\n}\n" +
                        "fn main() -> void {\n    var t = \"x\"\n    t = f()\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("不能把 ?string 赋给 string"), e.detail)
    }

    @Test
    fun `没法从 null 推断变量类型`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var x = null\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("没法从 null 推断"), e.detail)
    }

    @Test
    fun `非空值跟 null 比较会被拦下`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var s = \"hi\"\n    if (s == null) { println(1) }\n    rtn\n}\n"),
            )
        }
        assertTrue(e.detail.contains("恒为假"), e.detail)
    }

    @Test
    fun `可空值不能直接参与运算`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn f() -> ?int {\n    rtn 1\n}\n" +
                    "fn main() -> void {\n    var n = f()\n    println(n + 1)\n    rtn\n}\n"),
            )
        }
        assertTrue(e.detail.contains("可空值不能直接参与运算"), e.detail)
    }

    @Test
    fun `强制解包只能用在可空值上`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var s = \"hi\"\n    println(s!!)\n    rtn\n}\n"),
            )
        }
        assertTrue(e.detail.contains("'!!' 只能用在可空值上"), e.detail)
    }

    @Test
    fun `对 null 用双叹号在两端都是 NullPointerException`() {
        val program = """
            fn f() : ?string {
                rtn null
            }

            fn main() -> void {
                println(f()!!)
                rtn
            }
        """.trimIndent() + "\n"
        val fromInterp = assertFailsWith<NullPointerException> { runInterpreted(program) }
        assertTrue(fromInterp.message!!.contains("!!"), fromInterp.message)

        val fromCompiled = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            runCompiled(program)
        }
        assertTrue(fromCompiled.targetException is NullPointerException)
    }

    // ------------------------------------------------------------------ import / module

    /** 在临时目录里铺一套多文件工程，返回根文件。 */
    private fun project(files: Map<String, String>): File {
        val dir = File.createTempFile("rsf-proj", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        for ((rel, text) in files) {
            val f = File(dir, rel)
            f.parentFile.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
        return File(dir, files.keys.first { it.endsWith("main.rsf") })
    }

    @Test
    fun `import 路径里的反斜杠 点斜杠 都认`() {
        val root = project(
            mapOf(
                "lib/util.rsf" to "pub fn greet() -> string {\n    rtn \"hi\"\n}\n",
                "plugins/a.rsf" to "pub fn pluginA() -> string {\n    rtn \"A\"\n}\n",
                "main.rsf" to (
                    "import \".\\lib\\util.rsf\"\n" + // Windows 写法，单反斜杠
                        "module \".\\plugins\"\n" +
                        "fn main() -> void {\n" +
                        "    println(greet() + pluginA())\n" +
                        "    rtn\n" +
                        "}\n"
                    ),
            ),
        )
        val model = Rsf.analyze(Rsf.load(root))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("hiA"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `import 也认正斜杠与上跳目录`() {
        val root = project(
            mapOf(
                "lib/util.rsf" to "pub fn greet() -> string {\n    rtn \"hi\"\n}\n",
                "app/main.rsf" to (
                    "import \"../lib/util.rsf\"\n" + // 往上跳一层
                        "fn main() -> void {\n" +
                        "    println(greet())\n" +
                        "    rtn\n" +
                        "}\n"
                    ),
            ),
        )
        val model = Rsf.analyze(Rsf.load(root))
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("hi"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `pkg 的三种分隔符都归一化成点分形式`() {
        val cases = mapOf(
            "pkg util" to "util",
            "pkg a.b" to "a.b",
            "pkg a\\b" to "a.b",
            "pkg a/b" to "a.b",
            "pkg a.b\\c/d" to "a.b.c.d",
        )
        for ((decl, expected) in cases) {
            val program = Rsf.parse(source("$decl\n\nfn main() -> void {\n    rtn\n}\n"))
            assertEquals(expected, program.packageName, "声明：$decl")
        }
    }

    @Test
    fun `pkg 的文件要真的在那个目录下`() {
        // 位置对得上：app/util.rsf 里写 pkg app
        val ok = project(
            mapOf(
                "app/util.rsf" to "pkg app\n\npub fn twice(n : int) -> int {\n    rtn n * 2\n}\n",
                "app/main.rsf" to (
                    "pkg app\n\nimport \"util.rsf\"\n\n" +
                        "fn main() -> void {\n    println(twice(21))\n    rtn\n}\n"
                    ),
            ),
        )
        val loaded = Rsf.load(ok)
        assertEquals("app", loaded.packageName)
        val model = Rsf.analyze(loaded)
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("42"), buf.toString("UTF-8").trim().lines())

        // 位置对不上：根目录下的文件写 pkg a.b
        val bad = project(mapOf("main.rsf" to "pkg a.b\n\nfn main() -> void {\n    rtn\n}\n"))
        val e = assertFailsWith<RsfError> { Rsf.load(bad) }
        assertEquals("loader", e.phase)
        assertTrue(e.detail.contains("对不上"), e.detail)
    }

    @Test
    fun `pkg 会成为 JVM 的包 类文件落在对应子目录`() {
        val root = project(
            mapOf(
                "app/main.rsf" to (
                    "pkg app\n\n" +
                        "fn main() -> void {\n    println(\"hi\")\n    rtn\n}\n"
                    ),
            ),
        )
        val loaded = Rsf.load(root)
        val model = Rsf.analyze(loaded)
        val dir = File.createTempFile("rsf-pkg", "").let { it.delete(); it.mkdirs(); it }

        val out = Rsf.build(model, SourceFile.of(root), Rsf.deriveClassName(loaded.root), dir)
        // out/app/main.class
        assertEquals(File(dir, "app/main.class").canonicalPath, out.classFile.canonicalPath)
        assertTrue(out.classFile.isFile, "类文件没生成：${out.classFile}")
        // 而且真的能当 app.main 加载
        val bytes = out.classFile.readBytes()
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        assertEquals("app.main", loader.define("app.main", bytes).name)
    }

    @Test
    fun `pkg 只能写在文件最前面`() {
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    rtn\n}\n\npkg a\n"))
        }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("最前面"), e.detail)
    }

    @Test
    fun `三种 import 语法都能把函数并进来`() {        val root = project(
            mapOf(
                "lib/util.rsf" to "fn greet(who : string) -> string {\n    rtn \"hi, \" + who\n}\n",
                "lib/math.rsf" to "fn add(a : int, b : int) -> int {\n    rtn a + b\n}\n",
                "plugins/a.rsf" to "fn pluginA() -> string {\n    rtn \"A\"\n}\n",
                "plugins/b.rsf" to "fn pluginB() -> string {\n    rtn \"B\"\n}\n",
                "main.rsf" to (
                    "import \"lib/util.rsf\"\n" +
                        "import \"lib/\n       math.rsf\"\n" + // 路径跨行（Go 风格）
                        "module \"plugins\"\n" +
                        "\n" +
                        "fn main() -> void {\n" +
                        "    println(greet(\"rsf\"))\n" +
                        "    println(add(1, 2))\n" +
                        "    println(pluginA() + pluginB())\n" +
                        "    rtn\n" +
                        "}\n"
                    ),
            ),
        )

        val loaded = Rsf.load(root)
        assertEquals(5, loaded.files.size)
        val model = Rsf.analyze(loaded)
        assertEquals(5, model.functions.size)

        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("hi, rsf", "3", "AB"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `同一个文件被两个地方 import 只算一次`() {
        val root = project(
            mapOf(
                "shared.rsf" to "fn twice(n : int) -> int {\n    rtn n * 2\n}\n",
                "a.rsf" to "import \"shared.rsf\"\nfn fromA() -> int {\n    rtn twice(1)\n}\n",
                "b.rsf" to "import \"shared.rsf\"\nfn fromB() -> int {\n    rtn twice(2)\n}\n",
                "main.rsf" to "import \"a.rsf\"\nimport \"b.rsf\"\nfn main() -> void {\n    rtn\n}\n",
            ),
        )
        val loaded = Rsf.load(root)
        // main + a + b + shared = 4（shared 只出现一次）
        assertEquals(4, loaded.files.size)
        assertEquals(4, Rsf.analyze(loaded).functions.size)
    }

    @Test
    fun `互相 import 不会死循环`() {
        val root = project(
            mapOf(
                "a.rsf" to "import \"b.rsf\"\nfn fromA() -> int {\n    rtn fromB() + 1\n}\n",
                "b.rsf" to "import \"a.rsf\"\nfn fromB() -> int {\n    rtn 41\n}\n",
                "main.rsf" to "import \"a.rsf\"\nfn main() -> void {\n    println(fromA())\n    rtn\n}\n",
            ),
        )
        val loaded = Rsf.load(root)
        val model = Rsf.analyze(loaded)
        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        assertEquals(listOf("42"), buf.toString("UTF-8").trim().lines())
    }

    @Test
    fun `被导入文件里的错误会指向那个文件`() {
        val root = project(
            mapOf(
                "bad.rsf" to "fn helper() -> int {\n    rtn \"nope\"\n}\n",
                "main.rsf" to "import \"bad.rsf\"\nfn main() -> void {\n    rtn\n}\n",
            ),
        )
        val e = assertFailsWith<RsfError> { Rsf.analyze(Rsf.load(root)) }
        assertEquals("bad.rsf", e.source?.name)
        assertTrue(e.render().startsWith("bad.rsf:"), e.render())
    }

    @Test
    fun `找不到文件或空目录会报错`() {
        val missing = project(mapOf("main.rsf" to "import \"nope.rsf\"\nfn main() -> void { rtn }\n"))
        val e1 = assertFailsWith<RsfError> { Rsf.load(missing) }
        assertEquals("loader", e1.phase)
        assertTrue(e1.detail.contains("找不到被 import 的文件"), e1.detail)

        val emptyDir = project(mapOf("main.rsf" to "module \"nothing\"\nfn main() -> void { rtn }\n"))
        File(emptyDir.parentFile, "nothing").mkdirs()
        val e2 = assertFailsWith<RsfError> { Rsf.load(emptyDir) }
        assertTrue(e2.detail.contains("没有 .rsf 文件"), e2.detail)
    }

    @Test
    fun `跨文件重复定义函数会报错`() {
        val root = project(
            mapOf(
                "one.rsf" to "fn dup() -> int {\n    rtn 1\n}\n",
                "two.rsf" to "fn dup() -> int {\n    rtn 2\n}\n",
                "main.rsf" to "import \"one.rsf\"\nimport \"two.rsf\"\nfn main() -> void { rtn }\n",
            ),
        )
        val e = assertFailsWith<RsfError> { Rsf.analyze(Rsf.load(root)) }
        assertTrue(e.detail.contains("重复定义"), e.detail)
    }

    @Test
    fun `JVM 类导入能解析且方法能被调用`() {
        // 之前这里会报「成员访问还没接通」，现在是真能算了
        val program = "import java.lang.Math\nfn main() -> void {\n" +
            "    println(Math.max(1, 2))\n    rtn\n}\n"
        assertEquals(listOf("2"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    // ------------------------------------------------------------------ Java/Kotlin 互调用

    @Test
    fun `静态方法与静态字段两端一致`() {
        val program = """
            import java.lang.Math
            import java.lang.Integer

            fn main() -> void {
                println("max = " + Math.max(3, 7))
                println("abs = " + Math.abs(-5))
                println("sqrt = " + Math.sqrt(2.0))
                println("pi = " + Math.PI)
                println("parse = " + Integer.parseInt("42"))
                println("toString = " + Integer.toString(255))
                println("valueOf = " + String.valueOf(3.5))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "max = 7", "abs = 5", "sqrt = 1.4142135623730951", "pi = 3.141592653589793",
                "parse = 42", "toString = 255", "valueOf = 3.5",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `实例方法两端一致`() {
        val program = """
            fn main() -> void {
                var s = "rainStarfox"
                println(s.length())
                println(s.toUpperCase())
                println(s.substring(0, 4))
                println(s.contains("Star"))
                println(s.startsWith("rain"))
                println(s.indexOf("Star"))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("11", "RAINSTARFOX", "rain", "true", "true", "4"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `静态字段加实例方法链两端一致`() {
        // System.out 是静态字段，println 是它上面的实例方法
        val program = """
            fn main() -> void {
                System.out.println("via System.out")
                System.out.print("no newline")
                System.out.println()
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("via System.out", "no newline"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `引用类型之间按类层次匹配 CharSequence`() {
        // String 能当 CharSequence 用，靠的是遍历接口链
        val program = """
            fn main() -> void {
                var s = "abc"
                println(s.contains("b"))
                println(String.valueOf(1))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("true", "1"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `找不到方法时列出候选签名`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var s = \"x\"\n    s.contains(1)\n    rtn\n}\n"),
            )
        }
        assertTrue(e.detail.contains("没有匹配的方法"), e.detail)
        assertTrue(e.detail.contains("同名的有"), e.detail)
        assertTrue(e.detail.contains("contains"), e.detail)
    }

    @Test
    fun `找不到静态字段时给出提示`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(Math.NOPE)\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("没有静态字段"), e.detail)
    }

    @Test
    fun `import 不存在的类会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("import no.such.Klass\nfn main() -> void { rtn }\n"))
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("classpath 上找不到"), e.detail)
    }

    @Test
    fun `可空值上不能直接访问成员`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn f() : ?string {\n    rtn null\n}\n" +
                        "fn main() -> void {\n    var s = f()\n    println(s.length())\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("可空值上不能直接访问成员"), e.detail)
    }

    @Test
    fun `解释器与编译器选到同一条重载`() {
        // Math.max 有 int/long/float/double 四个重载，实参类型决定选哪个。
        // 两端必须一致，否则输出会分叉。
        val program = """
            fn main() -> void {
                println(Math.max(1, 2))
                println(Math.max(1L, 2L))
                println(Math.max(1.5, 2.5))
                println(Math.max(1.5f, 2.5f))
                println(Math.abs(-7))
                println(Math.abs(-7L))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("2", "2", "2.5", "2.5", "7", "7"),
            lines(runInterpreted(program)),
        )
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `反向调用 Java 能直接调 rsf 的 public 函数`() {
        val program = """
            pub fn greet(who : string) -> string {
                rtn "hi, " + who
            }

            pub fn fact(n : int) -> int {
                if (n <= 1) { rtn 1 }
                rtn n * fact(n - 1)
            }

            pub fn maybe(n : int) : ?string {
                if (n > 0) { rtn "yes" }
                rtn null
            }

            fn main() -> void { rtn }
        """.trimIndent() + "\n"
        val className = "RsfJavaCallable"
        val bytes = compileToBytes(program, className)
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        val cls = loader.define(className, bytes)

        // Java 侧看到的签名就是普通静态方法
        val greet = cls.getMethod("greet", String::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(greet.modifiers))
        assertTrue(java.lang.reflect.Modifier.isPublic(greet.modifiers))
        assertEquals("hi, Java", greet.invoke(null, "Java"))

        val fact = cls.getMethod("fact", Int::class.javaPrimitiveType)
        assertEquals(120, fact.invoke(null, 5))

        val maybe = cls.getMethod("maybe", Int::class.javaPrimitiveType)
        assertNull(maybe.invoke(null, -1))
        assertEquals("yes", maybe.invoke(null, 1))
    }

    // ------------------------------------------------------------------ 编辑器支持

    @Test
    fun `诊断 JSON 定位到出错的位置`() {
        val dir = File.createTempFile("rsf-diag", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "bad.rsf")
        f.writeText("fn main() -> void {\n    nope()\n    rtn\n}\n", Charsets.UTF_8)

        val json = EditorSupport.diagnosticsJson(f, null)
        // 形状固定，编辑器侧直接 JSON.parse
        assertTrue(json.startsWith("{\"diagnostics\":["), json)
        assertTrue(json.contains("\"line\":2"), json)
        assertTrue(json.contains("\"severity\":\"error\""), json)
        assertTrue(json.contains("未定义的函数 'nope'"), json)
        assertTrue(json.contains("\"endCol\":"), json)
    }

    @Test
    fun `诊断 JSON 里警告也会带出来`() {
        val dir = File.createTempFile("rsf-diag2", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "warn.rsf")
        f.writeText("fn main() -> void {\n    rtn\n    println(1)\n}\n", Charsets.UTF_8)

        val json = EditorSupport.diagnosticsJson(f, null)
        assertTrue(json.contains("\"severity\":\"warning\""), json)
        assertTrue(json.contains("不可达代码"), json)
    }

    @Test
    fun `补全 JSON 含函数签名与局部变量`() {
        val dir = File.createTempFile("rsf-comp", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "c.rsf")
        f.writeText(
            "pub fn add(a : int, b : int) -> int {\n    rtn a + b\n}\n\n" +
                "fn main() -> void {\n    var local = 1\n    rtn\n}\n",
            Charsets.UTF_8,
        )

        // 光标在第 7 行（rtn 那行），此时 local 已经声明过了
        val json = EditorSupport.completionJson(f, null, 7, 5, null)
        assertTrue(json.contains("\"label\":\"add\""), json)
        assertTrue(json.contains("add(a : int, b : int) -> int"), json)
        assertTrue(json.contains("\"label\":\"local\""), json)
        assertTrue(json.contains("\"label\":\"println\""), json)
    }

    @Test
    fun `补全在文件还没写完时也能给出名字`() {
        // 解析一定失败（少了收尾花括号），走 token 扫描兜底
        val dir = File.createTempFile("rsf-comp2", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "broken.rsf")
        f.writeText("fn helper() -> int {\n    rtn 1\n}\n\nfn main() -> void {\n    var x = \n", Charsets.UTF_8)

        val json = EditorSupport.completionJson(f, null, 6, 14, null)
        assertTrue(json.contains("\"label\":\"helper\""), json)
        assertTrue(json.contains("\"label\":\"x\""), json)
    }

    @Test
    fun `补全会列出类的静态成员`() {
        val dir = File.createTempFile("rsf-comp3", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "m.rsf")
        f.writeText("fn main() -> void {\n    Math.\n    rtn\n}\n", Charsets.UTF_8)

        val json = EditorSupport.completionJson(f, null, 2, 10, null)
        assertTrue(json.contains("\"label\":\"PI\""), json)
        assertTrue(json.contains("Math.max"), json)
        // private / synthetic 的成员不该出现
        assertTrue(!json.contains("assertionsDisabled"), json)
    }

    @Test
    fun `照抄草稿里的占位符写法时 提示要说清楚那是占位符`() {
        // lang/example.rsf 里写的是 <ControlType> fun <MainType> ...，有人会照抄进真代码。
        // 报错不能只说「读到 <」，还得说清楚 <> 是占位符、真语法怎么写。
        val dir = File.createTempFile("rsf-placeholder", "").let { it.delete(); it.mkdirs(); it }
        val f = File(dir, "ph.rsf")
        f.writeText("<public> fn main() -> void { rtn }\n", Charsets.UTF_8)

        val json = EditorSupport.diagnosticsJson(f, null)
        assertTrue(json.contains("占位符"), json)
        assertTrue(json.contains("pub"), json)
        assertTrue(json.contains("实际读到"), json)
    }

    @Test
    fun `补全会带上被 import 进来的函数`() {
        val dir = File.createTempFile("rsf-comp4", "").let { it.delete(); it.mkdirs(); it }
        File(dir, "lib.rsf").writeText(
            "pub fn greet(who : string) -> string {\n    rtn \"hi \" + who\n}\n",
            Charsets.UTF_8,
        )
        val f = File(dir, "main.rsf")
        f.writeText(
            "import \"lib.rsf\"\n\nfn main() -> void {\n    rtn\n}\n",
            Charsets.UTF_8,
        )

        val json = EditorSupport.completionJson(f, null, 4, 5, null)
        assertTrue(json.contains("\"label\":\"greet\""), json)
        assertTrue(json.contains("greet(who : string) -> string"), json)
    }

    // ------------------------------------------------------------------ 基础语义

    @Test
    fun `解释执行与编译执行输出逐字节一致`() {
        val program = """
            pub fn add() -> int {
                rtn 1 + 2 * 3
            }

            prv fn half() : float {
                rtn 1.5f
            }

            fn greeting() -> string {
                rtn "rsf"
            }

            fn main() -> void {
                println(greeting() + " " + add())
                println(half())
                var x = 10
                var y = 3
                println(x / y)
                println(x % y)
                println(x / 3.0d)
                println(x > y)
                println(!(x > y))
                println("mix " + 1 + 2.5 + true)
                rtn null
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(interpreted, runCompiled(program), "解释器与 .class 的输出必须一致")
        assertEquals(
            listOf("rsf 7", "1.5", "3", "1", "3.3333333333333335", "true", "false", "mix 12.5true"),
            lines(interpreted),
        )
    }

    @Test
    fun `整型溢出与截断除法遵循 JVM 语义`() {
        val program = """
            fn main() -> void {
                var a = 2147483647
                println(a + 1)
                var b = -7
                println(b / 2)
                println(b % 2)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("-2147483648", "-3", "-1"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `数值加宽赋值两端一致`() {
        val program = """
            fn main() -> void {
                var x = 1
                var y = 2L
                x = 5
                println(x + y)
                println(x / 2)
                println(x / 2.0)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("7", "2", "2.5"), lines(runInterpreted(program)))
        assertEquals(normalize(runInterpreted(program)), normalize(runCompiled(program)))
    }

    @Test
    fun `没有 main 时解释执行会报错`() {
        val model = Rsf.analyze(source("fn foo() -> int {\n    rtn 1\n}\n"))
        val e = assertFailsWith<RsfError> {
            Interpreter(model, PrintStream(ByteArrayOutputStream())).runEntry()
        }
        assertTrue(e.detail.contains("main"))
    }

    @Test
    fun `返回类型不匹配会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn foo() -> int {\n    rtn 1.5\n}\n"))
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("返回类型不匹配"))
    }

    @Test
    fun `未定义的函数会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    nope()\n    rtn\n}\n"))
        }
        assertTrue(e.detail.contains("未定义的函数 'nope'"))
    }

    @Test
    fun `重复定义函数会报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn a() -> int {\n    rtn 1\n}\nfn a() -> int {\n    rtn 2\n}\n"))
        }
        assertTrue(e.detail.contains("重复定义"))
    }

    @Test
    fun `编译产物里 main 是合法 JVM 入口且 private 函数保留可见性`() {
        val program = """
            prv fn hidden() -> int {
                rtn 41
            }

            pub fn exposed() : int {
                rtn hidden() + 1
            }

            fn main() -> void {
                println(exposed())
                rtn
            }
        """.trimIndent() + "\n"
        val className = "RsfVisibilityProbe"
        val bytes = compileToBytes(program, className)
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        val cls = loader.define(className, bytes)

        val main = cls.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(java.lang.reflect.Modifier.isPublic(main.modifiers))
        assertTrue(java.lang.reflect.Modifier.isStatic(main.modifiers))

        assertTrue(java.lang.reflect.Modifier.isPrivate(cls.getDeclaredMethod("hidden").modifiers))
        assertTrue(java.lang.reflect.Modifier.isPublic(cls.getDeclaredMethod("exposed").modifiers))
    }

    // ------------------------------------------------------------------ 阶段 12-C2：类的字节码生成

    @Test
    fun `new 的实参先于字段初值求值 两端一致`() {
        // JVM 的 new 是「先算实参，再进 <init>（字段初值在里面跑）」。
        // 解释器必须照同一次序，否则字段初值带副作用时两端输出会分叉。
        val program = """
            class P {
                var n : int = note("字段初值")
                constructor(v : int) {
                    println("构造器收到 " + v)
                }
                pub fn get() -> int { rtn n }
            }

            pub fn note(tag : string) -> int {
                println(tag)
                rtn 7
            }

            fn main() -> void {
                var p = new P(note("实参"))
                println("n = " + p.get())
                rtn
            }
        """.trimIndent()

        val interpreted = runInterpreted(program)
        val compiled = runCompiledClasses(program, "RsfNewOrder")
        assertEquals(
            listOf("实参", "字段初值", "构造器收到 7", "n = 7"),
            interpreted.trim().lines(),
            "次序应该是：实参 → 字段初值 → 构造器体",
        )
        assertEquals(interpreted, compiled, "解释器与 .class 的输出必须逐字节一致")
    }

    @Test
    fun `类的两个后端输出一致`() {
        val program = classParityProgram
        val interpreted = runInterpreted(program)
        val compiled = runCompiledClasses(program)

        assertEquals(interpreted, compiled, "解释器与 .class 的输出必须逐字节一致")
        // 顺带确认这个程序不是空跑：这几行是类、静态方法、可空字段各自产出的
        val out = lines(interpreted)
        assertTrue(out.contains("sum = 7"), out.toString())
        assertTrue(out.contains("q = point(0, 0)"), out.toString())
        assertTrue(out.contains("filled: has = true, sumOrZero = 30"), out.toString())
        assertTrue(out.contains("counter = 30"), out.toString())
    }

    @Test
    fun `构造器 字段 静态方法与隐式 this 调用在编译产物里都对`() {
        val program = """
            pub class Box {
                var n : int = 1
                var twice : int = n * 2
                imutvar tag : string = "box"

                constructor(start : int) {
                    n = start
                }

                pub fn value() -> int {
                    rtn n + twice
                }

                pub fn bump(n : int) -> void {
                    this.n = n
                }

                prv fn magic() -> int {
                    rtn 7
                }

                pub fn reveal() -> int {
                    rtn magic()
                }

                pub stc fn of(start : int) -> Box {
                    rtn new Box(start)
                }
            }

            fn main() -> void {
                var b = new Box(3)
                println(b.tag + " n = " + b.n)
                println("value = " + b.value())
                b.bump(10)
                println("n = " + b.n)
                println("reveal = " + b.reveal())
                var c = Box.of(4)
                println("c = " + c.value())
                rtn
            }
        """.trimIndent() + "\n"

        val (loader, cls) = compileAndLoad(program, "RsfClassBehaviour")

        // 行为：字段初值（twice = 1 * 2）→ 构造器 → 实例方法 → this.字段 → 隐式 this → 静态方法
        assertEquals(
            listOf("box n = 3", "value = 5", "n = 10", "reveal = 7", "c = 6"),
            lines(runMain(cls)),
        )

        // 形状：字段是 public 的实例字段，静态方法真的是 static，实例方法真的不是
        val box = loader.loadClass("Box")
        val n = box.getDeclaredField("n")
        assertTrue(java.lang.reflect.Modifier.isPublic(n.modifiers), "字段应当是 pub")
        assertTrue(!java.lang.reflect.Modifier.isStatic(n.modifiers), "字段应当是实例字段")
        assertEquals(Int::class.javaPrimitiveType, n.type)
        assertTrue(
            java.lang.reflect.Modifier.isStatic(
                box.getDeclaredMethod("of", Int::class.javaPrimitiveType!!).modifiers,
            ),
            "静态方法应当编译成 stc 方法",
        )
        assertTrue(
            !java.lang.reflect.Modifier.isStatic(box.getDeclaredMethod("value").modifiers),
            "实例方法不能是 stc（0 号槽是 this）",
        )
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(box.getDeclaredMethod("magic").modifiers),
            "prv 方法要保留 prv",
        )
        // 构造器描述符只按声明的参数来，不能带上入口 main 那个 String[]
        box.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
        assertFailsWith<NoSuchMethodException> { box.getDeclaredConstructor() }
    }

    @Test
    fun `类程序会为每个用户类产出一个 class 文件`() {
        val program = classParityProgram
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(source(program)), source(program), "RsfClassFiles", dir)

        // 程序类 + Point / Holder / Counter，一个都不能少
        assertEquals(
            listOf("RsfClassFiles.class", "Point.class", "Holder.class", "Counter.class"),
            out.classFiles.map { it.relativeTo(dir).path.replace(File.separatorChar, '/') },
        )
        assertEquals(File(dir, "RsfClassFiles.class").canonicalPath, out.classFile.canonicalPath)
        for (name in listOf("Point.class", "Holder.class", "Counter.class")) {
            assertTrue(File(dir, name).isFile, "没生成 $name")
        }
    }

    @Test
    fun `类名撞车与 static 方法里用 this 都在编译期明确拦下`() {
        val program = classParityProgram
        val dir = tempDir()

        // 程序类名和某个用户类名撞车：两个 .class 会写到同一个路径上，必须报错而不是悄悄覆盖
        val clash = Rsf.analyze(source(program))
        val e = assertFailsWith<RsfError> { Rsf.build(clash, source(program), "Point", dir) }
        assertEquals("codegen", e.phase)
        assertTrue(e.detail.contains("Point"), e.detail)

        // static 方法里用裸字段名（需要 this）：编译期就说清楚，别让 JVM 甩 VerifyError
        val inStatic = source(
            "class S {\n    var x : int = 5\n    pub stc fn bad() -> int {\n" +
                "        rtn x\n    }\n}\n\nfn main() -> void {\n    println(S.bad())\n    rtn\n}\n",
        )
        val e2 = assertFailsWith<RsfError> {
            Rsf.build(Rsf.analyze(inStatic), inStatic, "RsfStatic", tempDir())
        }
        assertEquals("codegen", e2.phase)
        assertTrue(e2.detail.contains("stc"), e2.detail)
    }

    @Test
    fun `带 pkg 的类文件落在包目录里且能按包名加载运行`() {
        val root = project(
            mapOf(
                "app/main.rsf" to (
                    "pkg app\n\n" +
                        "pub class Pt {\n" +
                        "    var x : int = 7\n" +
                        "    constructor() { x = x + 1 }\n" +
                        "    pub fn get2() -> int { rtn x * 2 }\n" +
                        "}\n\n" +
                        "pub fn twice(p : Pt) -> int {\n    rtn p.get2()\n}\n\n" +
                        "fn main() -> void {\n" +
                        "    var p = new Pt()\n" +
                        "    println(\"x = \" + p.x + \", twice = \" + twice(p))\n" +
                        "    rtn\n}\n"
                    ),
            ),
        )
        val loaded = Rsf.load(root)
        val model = Rsf.analyze(loaded)
        val className = Rsf.deriveClassName(loaded.root)
        val dir = tempDir()
        val out = Rsf.build(model, SourceFile.of(root), className, dir)

        // 用户类跟着程序类的包走，描述符也要指向包内（不能是默认包的 LPt;）
        assertEquals(
            listOf("app/main.class", "app/Pt.class"),
            out.classFiles.map { it.relativeTo(dir).path.replace(File.separatorChar, '/') },
        )
        assertTrue(File(dir, "app/Pt.class").isFile)

        val loader = programLoader(out.classFiles, dir).first
        assertEquals("app.main", loader.loadClass("app.main").name)
        assertEquals(
            listOf("x = 8, twice = 16"),
            lines(runMain(loader.loadClass("app.main"))),
        )
    }

    // ------------------------------------------------------------------ 阶段 15：泛型

    /**
     * `class Box&<T>` 的完整例子：类型参数用在字段 / 参数 / 返回类型上。
     *
     * 擦除语义：`Box&<int>` 与 `Box&<string>` 是**同一个 JVM 类** `LBox;`，
     * 字段描述符是 `Ljava/lang/Object;`，类型实参只活在编译期——所以
     * `get()` 的结果要 CHECKCAST + 拆箱（解释器里是等价的收敛），两端输出必须一样。
     */
    private val genericBoxProgram: String = """
        pub class Box&<T> {
            var value : T = 0

            constructor(v0 : T) {
                value = v0
            }

            pub fn get() -> T {
                rtn value
            }

            pub fn set(v : T) -> void {
                value = v
            }
        }

        fn main() -> void {
            var b : Box&<int> = new Box&<int>(0)
            b.set(7)
            println("get = " + b.get())
            println(b.get() + 1)

            var s : Box&<string> = new Box&<string>("hi")
            println("s = " + s.get())
            println(s.get().length())
            rtn
        }
    """.trimIndent() + "\n"

    @Test
    fun `泛型类的基本用法两端一致`() {
        assertEquals(
            listOf("get = 7", "8", "s = hi", "2"),
            lines(runInterpreted(genericBoxProgram)),
        )
        assertEquals(
            normalize(runInterpreted(genericBoxProgram)),
            normalize(runCompiledClasses(genericBoxProgram, "RsfGenericBasic")),
        )
    }

    @Test
    fun `泛型可以嵌套`() {
        val program = """
            pub class Box&<T> {
                var value : T = 0

                constructor(v0 : T) {
                    value = v0
                }

                pub fn get() -> T {
                    rtn value
                }
            }

            fn main() -> void {
                var nested : Box&<Box&<int>> = new Box&<Box&<int>>(new Box&<int>(5))
                println(nested.get().get())
                println(nested.get().get() + 2)
                var names : Box&<Box&<string>> = new Box&<Box&<string>>(new Box&<string>("rsf"))
                println(names.get().get())
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("5", "7", "rsf"), lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfGenericNested")),
        )
    }

    @Test
    fun `泛型实参个数不对会报错`() {
        // 擦除后 `Box&<int, int>` 跟 `Box&<int>` 完全一样，JVM 一点都拦不住，
        // 所以元数必须在编译期查，而且要说清期望几个、实际几个。
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub class Box&<T> {\n" +
                        "    var value : T = 0\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var b : Box&<int, int> = new Box&<int, int>()\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("期望 1 个"), e.detail)
        assertTrue(e.detail.contains("实际给了 2 个"), e.detail)
        assertTrue(e.detail.contains("Box"), e.detail)

        // 方向反过来：非泛型类硬写类型实参，期望 0 个
        val e2 = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class Plain {\n    var x : int = 1\n}\n" +
                        "fn main() -> void {\n" +
                        "    var p : Plain&<int> = new Plain()\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e2.detail.contains("期望 0 个"), e2.detail)
        assertTrue(e2.detail.contains("实际给了 1 个"), e2.detail)
    }

    @Test
    fun `泛型实参不同的同一个类不能互相赋值`() {
        // 这是擦除语义最危险的地方：`Box&<int>` 和 `Box&<string>` 都是 `LBox;`，
        // 赋值在字节码里完全合法（aload/astore 就行），只有编译期能拦住。
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub class Box&<T> {\n" +
                        "    var value : T = 0\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var a : Box&<int> = new Box&<int>()\n" +
                        "    var b : Box&<string> = new Box&<string>()\n" +
                        "    b = a\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("Box&<int>"), e.detail)
        assertTrue(e.detail.contains("Box&<string>"), e.detail)

        // 反过来（把 string 的赋给 int 的）同样要拦住
        val e2 = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub class Box&<T> {\n" +
                        "    var value : T = 0\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var a : Box&<int> = new Box&<int>()\n" +
                        "    var b : Box&<string> = new Box&<string>()\n" +
                        "    a = b\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e2.detail.contains("Box&<string>"), e2.detail)
        assertTrue(e2.detail.contains("Box&<int>"), e2.detail)
    }

    @Test
    fun `类体里 T 能当字段 参数 返回类型`() {
        val program = """
            pub class Box&<T> {
                var value : T = 0

                constructor(v0 : T) {
                    value = v0
                }

                pub fn get() -> T {
                    rtn value
                }

                pub fn echo(v : T) -> T {
                    var copy : T = v
                    rtn copy
                }

                pub fn show() -> string {
                    rtn "value = " + value
                }
            }

            fn main() -> void {
                var a : Box&<int> = new Box&<int>(3)
                println(a.show())
                println(a.echo(11))
                println("again = " + a.get())

                var b : Box&<string> = new Box&<string>("hi")
                println(b.show())
                println(b.echo("yo"))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("value = 3", "11", "again = 3", "value = hi", "yo"),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfGenericBody")),
        )
    }

    @Test
    fun `泛型可以有多个类型参数`() {
        val program = """
            pub class Pair&<K, V> {
                var key : K = 0
                var val : V = 0

                constructor(k0 : K, v0 : V) {
                    key = k0
                    val = v0
                }

                pub fn keyOf() -> K {
                    rtn key
                }

                pub fn valOf() -> V {
                    rtn val
                }
            }

            fn main() -> void {
                var p : Pair&<string, int> = new Pair&<string, int>("a", 1)
                println(p.keyOf() + " -> " + p.valOf())
                println(p.valOf() + 41)
                var q : Pair&<int, string> = new Pair&<int, string>(7, "seven")
                println(q.keyOf() + 1)
                println(q.valOf())
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("a -> 1", "42", "8", "seven"), lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfGenericPair")),
        )
    }

    @Test
    fun `JVM 泛型集合能用`() {
        // Java 的 `ArrayList.add(Object)` / `get(): Object` 在 rsf 里看是
        // `ArrayList&<int>` 的元素位置：传进去要装箱，取出来要 CHECKCAST + 拆箱。
        val program = """
            import java.util.ArrayList

            fn main() -> void {
                var xs : ArrayList&<int> = new ArrayList&<int>()
                xs.add(3)
                xs.add(4)
                println(xs.size())
                println(xs.get(0) + 1)
                println("sum = " + (xs.get(0) + xs.get(1)))

                var names : ArrayList&<string> = new ArrayList&<string>()
                names.add("rain")
                names.add("starfox")
                println(names.get(1).length())
                println(names.get(0) + "-" + names.get(1))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("2", "4", "sum = 7", "7", "rain-starfox"),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfGenericCollection")),
        )
    }

    // ------------------------------------------------------------------ 阶段 16：byte / char

    @Test
    fun `byte 与 char 的基本用法两端一致`() {
        val program = """
            fn echo(b : byte) -> byte {
                rtn b
            }

            fn code(c : char) -> int {
                rtn charToInt(c)
            }

            fn main() -> void {
                // byte：字面量直接给（0..127），算术一律按 int 提升
                var b : byte = 100
                println(b)
                println(b + b)
                println(b * 3)
                println(b - 200)
                println(echo(7))
                println("b = " + b)

                // char：字面量就是 char，不能跟 int 混
                var c : char = 'a'
                println(c)
                println(code(c))
                println(toChar(98))
                println(charToInt('0'))
                println(c == 'a')
                println(c < 'b')
                println('a' + 1)

                // 显式窄化：toByte 就是 Java 的 (byte)
                println(toByte(200))
                println(toByte(300))
                println(toByte(65))
                println(toChar(65))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "100", "200", "300", "-100", "7", "b = 100",
                "a", "97", "b", "48", "true", "true", "98",
                "-56", "44", "65", "A",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `char 字面量的转义都认`() {
        val program = """
            fn name(c : char) -> string {
                if (c == '\n') { rtn "LF" }
                if (c == '\t') { rtn "TAB" }
                if (c == '\r') { rtn "CR" }
                if (c == '\0') { rtn "NUL" }
                if (c == '\\') { rtn "BACKSLASH" }
                if (c == '\'') { rtn "QUOTE" }
                rtn "?"
            }

            fn main() -> void {
                println(name('\n'))
                println(name('\t'))
                println(name('\r'))
                println(name('\0'))
                println(name('\\'))
                println(name('\''))
                println(charToInt('\n'))
                println(charToInt('\t'))
                println(charToInt('\u0041'))
                println('\u0041')
                println('\u4e2d')
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf("LF", "TAB", "CR", "NUL", "BACKSLASH", "QUOTE", "10", "9", "65", "A", "中"),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `char 字面量写错会报错`() {
        // 空字符
        val empty = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = ''\n}\n")) }
        assertEquals("lexer", empty.phase)
        assertTrue(empty.detail.contains("不能是空的"), empty.detail)

        // 多字符
        val many = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = 'ab'\n}\n")) }
        assertEquals("lexer", many.phase)
        assertTrue(many.detail.contains("只能放一个字符"), many.detail)

        // 没闭合（换行前没等到收尾引号）
        val unclosed = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = 'a\n}\n")) }
        assertEquals("lexer", unclosed.phase)
        assertTrue(unclosed.detail.contains("没有闭合"), unclosed.detail)

        // 文件末尾就断了
        val eof = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = '")) }
        assertEquals("lexer", eof.phase)
        assertTrue(eof.detail.contains("没有闭合"), eof.detail)

        // 不认识的转义
        val bad = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = '\\q'\n}\n")) }
        assertTrue(bad.detail.contains("不认识的转义"), bad.detail)

        // \u 后面必须写满四位
        val short = assertFailsWith<RsfError> { Rsf.parse(source("fn main() -> void {\n    var c = '\\u12'\n}\n")) }
        assertTrue(short.detail.contains("四位十六进制"), short.detail)
    }

    @Test
    fun `char 与 int 不能隐式互赋`() {
        // 反向也要拦住：char 不能当 int 用
        val toInt = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var n : int = 'a'\n    rtn\n}\n"))
        }
        assertTrue(toInt.detail.contains("不能把 char 赋给 int"), toInt.detail)

        val toChar = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var c : char = 65\n    rtn\n}\n"))
        }
        assertTrue(toChar.detail.contains("不能把 int 赋给 char"), toChar.detail)

        // 参数位置同样不放过
        val arg = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn take(c : char) -> int {\n    rtn 0\n}\n\nfn main() -> void {\n    println(take(65))\n    rtn\n}\n"))
        }
        assertTrue(arg.detail.contains("take"), arg.detail)

        // byte 与 char 之间也不行（两个方向）
        val byteToChar = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var c : char = toByte(65)\n    rtn\n}\n"))
        }
        assertTrue(byteToChar.detail.contains("不能把 byte 赋给 char"), byteToChar.detail)

        // int -> byte 的窄化也要显式：字面量在范围内是特例，算不出来就不行
        val narrow = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var n : int = 300\n    var b : byte = n\n    rtn\n}\n"))
        }
        assertTrue(narrow.detail.contains("不能把 int 赋给 byte"), narrow.detail)

        // 显式转换函数能绕开全部上面这些
        val program = """
            fn main() -> void {
                var n : int = 65
                var c : char = toChar(n)
                var back : int = charToInt(c)
                var b : byte = toByte(n)
                var widened : int = b
                println(c)
                println(back)
                println(b)
                println(widened)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(listOf("A", "65", "65", "65"), lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `可空的 byte 与 char 两端一致`() {
        val program = """
            fn maybeChar(n : int) -> ?char {
                if (n > 0) { rtn 'x' }
                rtn null
            }

            fn maybeByte(n : int) -> ?byte {
                if (n > 0) { rtn toByte(9) }
                rtn null
            }

            fn main() -> void {
                var c : ?char = maybeChar(1)
                println("c = " + c)
                println(c ?: 'z')

                var empty : ?char = maybeChar(-1)
                println("empty = " + empty)
                println(empty ?: 'z')
                println(empty == null)

                var b : ?byte = null
                println("b = " + b)
                println(b ?: toByte(7))
                b = 5
                println("b = " + b)
                println(b ?: toByte(7))

                // 装箱后是 java/lang/Byte / java/lang/Character：拼接和打印都按对象走
                println("拼 " + b + " 与 " + c)

                // 顺带钉住一条同源的坑：可空基本类型的初值在「先转换再装箱」时，
                // 必须装成目标类型的包装类（这里 5 要装成 Byte，不能是 Integer），
                // 否则槽位里躺着 Integer、拆箱发 Byte.byteValue() 会 VerifyError。
                var lit : ?byte = 5
                println("lit = " + lit)
                println(lit ?: toByte(0))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "c = x", "x", "empty = null", "z", "true", "b = null", "7", "b = 5", "5",
                "拼 5 与 x", "lit = 5", "5",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `标准库的 char 分类与转换函数都对`() {
        val program = """
            fn classify(c : char) -> string {
                if (isDigit(c)) { rtn "digit" }
                if (isLetter(c)) { rtn "letter" }
                if (isWhitespace(c)) { rtn "space" }
                rtn "other"
            }

            fn main() -> void {
                println(classify('7'))
                println(classify('x'))
                println(classify(' '))
                println(classify('\t'))
                println(classify('\n'))
                println(classify('+'))

                println(isDigit('7'))
                println(isDigit('a'))
                println(isLetter('Z'))
                println(isLetter('5'))
                println(isWhitespace(' '))
                println(isWhitespace('.'))

                println(toUpper('a'))
                println(toUpper('z'))
                println(toUpper('5'))
                println(toLower('A'))
                println(toLower('Z'))

                println(charToInt('A'))
                println(charToInt('0'))
                println(toChar(97))
                println(toChar(48))
                println(intToString(42) + "!")

                // 大小写对非字母原样返回，码点也不变
                println(charToInt(toUpper('7')))
                println(charToInt(toLower('+')))
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "digit", "letter", "space", "space", "space", "other",
                "true", "false", "true", "false", "true", "false",
                "A", "Z", "5", "a", "z",
                "65", "48", "a", "0", "42!",
                "55", "43",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `parseInt 失败会 fail 且能被 try 接住`() {
        // 先把「没处理错误就编译不过」这条钉住
        val unhandled = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var n = parseInt(\"1\")\n    rtn\n}\n"))
        }
        assertTrue(unhandled.detail.contains("必须显式处理错误"), unhandled.detail)

        // 实参类型也只能是 string
        val wrongArg = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(parseInt(1) ?: 0)\n    rtn\n}\n"))
        }
        assertTrue(wrongArg.detail.contains("parseInt"), wrongArg.detail)

        val program = """
            fn twice(s : string) -> int {
                rtn (parseInt(s) ?: 0) * 2
            }

            // !int：把 parseInt 的错误往上抛，用来验证错误能跨函数帧传出去
            fn failTwice(s : string) -> !int {
                rtn (parseInt(s)!!) * 2
            }

            fn main() -> void {
                println(parseInt("42") ?: -1)
                println(parseInt("0") ?: -1)
                println(parseInt("-7") ?: -1)
                println(parseInt("2147483647") ?: -1)
                println(parseInt("2147483648") ?: -1)
                println(parseInt("x") ?: -1)
                println(parseInt("") ?: -1)
                println(parseInt("12a") ?: -1)
                println(parseInt(" 42") ?: -1)
                println(twice("21"))

                // 接住错误消息：解释器抛 RsfFailure、编译产物抛 RuntimeException，
                // 两端 catch 里打出来的东西必须一模一样
                try {
                    var n = parseInt("abc")
                    println(n)
                } catch (e : string) {
                    println("caught: " + e)
                }

                // 失败跨过一层函数也能被接住（failTwice 用 !! 把错误往外抛）
                try {
                    println(failTwice("bad"))
                } catch (e : string) {
                    println("caught2: " + e)
                }
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "42", "0", "-7", "2147483647", "-1", "-1", "-1", "-1", "-1", "42",
                "caught: parseInt: 不是合法的整数：'abc'",
                "caught2: parseInt: 不是合法的整数：'bad'",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    @Test
    fun `字符串拼接 char 两端一致`() {
        val program = """
            fn main() -> void {
                var c : char = 'x'
                var s : string = "ab"

                println("c=" + c)
                println(c + "!")
                println(s + c)
                println(c + s)
                println("" + c)
                println(c + "")

                // 字符串切片：charAt 给回来的是 char，能直接参与拼接和比较
                println(s.charAt(1))
                println("第二个字符是 " + s.charAt(1))
                println(s.charAt(0) == 'a')
                println("类型对了才能这么拼：" + toUpper(s.charAt(0)) + toLower(s.charAt(1)))

                // 数字与字符在同一串里：byte 打成数字，char 打成字符
                var b : byte = 65
                println("b=" + b + " c=" + c)

                var nc : ?char = null
                println("nc=" + nc)
                rtn
            }
        """.trimIndent() + "\n"
        assertEquals(
            listOf(
                "c=x", "x!", "abx", "xab", "x", "x",
                "b", "第二个字符是 b", "true", "类型对了才能这么拼：Ab",
                "b=65 c=x", "nc=null",
            ),
            lines(runInterpreted(program)),
        )
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiled(program)),
        )
    }

    // ------------------------------------------------------------------ 工具

    /** 覆盖类全套写法的示例程序：字段、构造器、实例/私有/静态方法、隐式 this、可空类字段。 */
    private val classParityProgram: String = """
        pub class Point {
            var x : int = 0
            var y : int = 0
            imutvar label : string = "point"

            constructor(x0 : int, y0 : int) {
                x = x0
                y = y0
            }

            pub fn sum() -> int {
                rtn x + y
            }

            pub fn shift(dx : int, dy : int) -> void {
                x = x + dx
                y = y + dy
            }

            pub fn describe() -> string {
                rtn label + "(" + x + ", " + y + ")"
            }

            pub fn doubleBoth() -> void {
                shift(x, y)
            }

            prv fn secret() -> int {
                rtn 42
            }

            pub fn reveal() -> int {
                rtn secret()
            }

            pub stc fn origin() -> Point {
                rtn new Point(0, 0)
            }
        }

        pub class Holder {
            var held : ?Point = null

            pub fn keep(p : Point) -> void {
                held = p
            }

            pub fn has() -> bool {
                rtn held != null
            }

            pub fn sumOrZero() -> int {
                rtn if held == null ? 0 : held!!.sum()
            }
        }

        pub class Counter {
            var base : int = 10
            var doubled : int = base * 2

            pub fn value() -> int {
                rtn base + doubled
            }
        }

        pub fn distanceSquared(a : Point, b : Point) -> int {
            var dx = a.x - b.x
            var dy = a.y - b.y
            rtn dx * dx + dy * dy
        }

        pub fn makePoint(n : int) -> Point {
            rtn new Point(n, n * 2)
        }

        fn main() -> void {
            var p = new Point(3, 4)
            println(p.describe())
            println("sum = " + p.sum())

            p.shift(1, 1)
            println(p.describe())
            println("prv 方法经公开方法访问 = " + p.reveal())

            var q = Point.origin()
            println("q = " + q.describe())
            println("距离平方 = " + distanceSquared(p, q))

            var r = makePoint(5)
            println("r = " + r.describe())

            var a = new Point(1, 1)
            a.doubleBoth()
            println("a = " + a.describe() + " p = " + p.describe())

            var h = new Holder()
            println("empty: has = " + h.has() + ", sumOrZero = " + h.sumOrZero())
            h.keep(new Point(10, 20))
            println("filled: has = " + h.has() + ", sumOrZero = " + h.sumOrZero())

            var c = new Counter()
            println("counter = " + c.value())

            var total = 0
            var walker = new Point(0, 0)
            for (var i = 0; i < 4; i = i + 1) {
                walker.shift(1, 2)
                total = total + walker.sum()
            }
            println("walk: " + walker.describe() + " total = " + total)
            rtn
        }
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------ 数组（阶段 13）

    @Test
    fun `数组的基本用法两端一致`() {
        val program = """
            fn sum(xs : int[]) -> int {
                var total = 0
                for (var i = 0; i < xs.length; i = i + 1) {
                    total = total + xs[i]
                }
                rtn total
            }

            fn main() -> void {
                var a : int[] = new int[4]
                println("长度 = " + a.length)
                println("默认值 = " + a[0] + "," + a[1] + "," + a[2] + "," + a[3])
                a[0] = 10
                a[3] = 4
                a[1] = a[0] + a[3]
                println("写入后 = " + a[0] + "," + a[1] + "," + a[2] + "," + a[3])
                println("求和 = " + sum(a))

                // 长度是运行期表达式
                var n : int = 2
                var names : string[] = new string[n + 1]
                println("引用类型数组长度 = " + names.length + "，默认值是 " + names[0])
                names[1] = "rsf"
                println("写入后 names[1] = " + names[1] + "，names[2] = " + names[2])

                // bool / char / long 数组
                var flags : bool[] = new bool[1]
                flags[0] = true
                println("bool[] = " + flags[0])
                var cs : char[] = new char[3]
                cs[0] = 'r'
                cs[1] = 's'
                cs[2] = 'f'
                println("char[] = " + cs[0] + cs[1] + cs[2])
                var ls : long[] = new long[2]
                ls[0] = 9000000000L
                println("long[] = " + ls[0] + "," + ls[1])
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "长度 = 4",
            "默认值 = 0,0,0,0",
            "写入后 = 10,14,0,4",
            "求和 = 28",
            "引用类型数组长度 = 3，默认值是 null",
            "写入后 names[1] = rsf，names[2] = null",
            "bool[] = true",
            "char[] = rsf",
            "long[] = 9000000000,0",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        // 硬承诺：两条后端逐字节一致
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayBasic")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `数组作为参数与返回值的引用语义两端一致`() {
        val program = """
            fn fill(xs : int[], value : int) -> void {
                for (var i = 0; i < xs.length; i = i + 1) {
                    xs[i] = value
                }
                rtn
            }

            fn makeSquares(n : int) -> int[] {
                var out = new int[n]
                for (var i = 0; i < n; i = i + 1) {
                    out[i] = i * i
                }
                rtn out
            }

            fn bump(xs : int[]) -> int[] {
                xs[0] = xs[0] + 1
                rtn xs
            }

            fn main() -> void {
                var a : int[] = new int[3]
                // 赋给另一个变量：还是同一个数组
                var b : int[] = a
                b[1] = 99
                println("通过 b 写入后 a[1] = " + a[1])

                // 传参：函数里改元素，外面看得见
                fill(a, 7)
                println("fill 之后 a = " + a[0] + "," + a[1] + "," + a[2] + "，b[0] = " + b[0])

                // 返回：返回值就是同一个数组
                var c : int[] = bump(a)
                println("bump 之后 a[0] = " + a[0] + "，c[0] = " + c[0])

                var sq : int[] = makeSquares(4)
                println("平方 = " + sq[0] + "," + sq[1] + "," + sq[2] + "," + sq[3] + " 长度 = " + sq.length)
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "通过 b 写入后 a[1] = 99",
            "fill 之后 a = 7,7,7，b[0] = 7",
            "bump 之后 a[0] = 8，c[0] = 8",
            "平方 = 0,1,4,9 长度 = 4",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayRef")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `用户类数组与泛型类数组两端一致`() {
        val program = """
            pub class Point {
                var x : int = 0
                var y : int = 0

                constructor(x0 : int, y0 : int) {
                    x = x0
                    y = y0
                }

                pub fn sum() -> int { rtn x + y }
            }

            pub class Box&<T> {
                var value : T = 0
                pub fn get() -> T { rtn value }
                pub fn set(v : T) -> void { value = v }
            }

            fn total(ps : Point[]) -> int {
                var t = 0
                for (var i = 0; i < ps.length; i = i + 1) {
                    t = t + ps[i].sum()
                }
                rtn t
            }

            fn main() -> void {
                var ps : Point[] = new Point[2]
                println("默认 ps[0] 是对象吗（长度为 " + ps.length + "）")
                ps[0] = new Point(3, 4)
                ps[1] = new Point(10, 20)
                println("ps[0].sum() = " + ps[0].sum() + "，ps[1].sum() = " + ps[1].sum())
                println("total = " + total(ps))

                // 元素是对象引用：通过另一个变量改，数组里看得见
                var first : Point = ps[0]
                first.x = 100
                println("改过之后 ps[0].sum() = " + ps[0].sum())

                var boxes : Box&<int>[] = new Box&<int>[2]
                boxes[0] = new Box&<int>()
                boxes[0].set(42)
                // 与 rsfSrc/lang/arrays.rsf 一致：这句是**展示文案**，保持带空格的老写法
                // （它的 stdout 被 SHA256 钉着），语法位置仍然是 `Box&<int>`
                println("Box &<int>[] 长度 = " + boxes.length + "，boxes[0].get() = " + boxes[0].get())

                var bs : Box&<string>[] = new Box&<string>[1]
                bs[0] = new Box&<string>()
                bs[0].set("hi")
                println("Box&<string>[] = " + bs[0].get())

                // 泛型实参本身是数组：Box&<int[]>（擦除后是 Object，取回来要 CHECKCAST 成 [I）
                var bi : Box&<int[]> = new Box&<int[]>()
                bi.set(new int[3])
                var inner : int[] = bi.get()
                inner[1] = 5
                println("泛型实参是数组：长度 = " + bi.get().length + "，inner[1] = " + inner[1])
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "默认 ps[0] 是对象吗（长度为 2）",
            "ps[0].sum() = 7，ps[1].sum() = 30",
            "total = 37",
            "改过之后 ps[0].sum() = 104",
            "Box &<int>[] 长度 = 2，boxes[0].get() = 42",
            "Box&<string>[] = hi",
            "泛型实参是数组：长度 = 3，inner[1] = 5",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayClass")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `数组越界两端报同样的错`() {
        val caught = """
            fn main() -> void {
                var a : int[] = new int[3]
                try {
                    println("不该走到这里：" + a[5])
                } catch (e : string) {
                    println("读越界：" + e)
                }
                try {
                    a[-1] = 1
                } catch (e : string) {
                    println("写越界：" + e)
                }
                try {
                    var bad : int[] = new int[-2]
                    println("不该走到这里：" + bad.length)
                } catch (e : string) {
                    println("负长度：" + e)
                }
                println("越界之后照常往下走")
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "读越界：数组下标越界：长度 3，下标 5",
            "写越界：数组下标越界：长度 3，下标 -1",
            "负长度：数组长度不能是负数：-2",
            "越界之后照常往下走",
        )
        assertEquals(expected, lines(runInterpreted(caught)))
        assertEquals(
            normalize(runInterpreted(caught)),
            normalize(runCompiledClasses(caught, "RsfArrayOob")),
            "解释器与 .class 的输出必须逐字节一致",
        )

        // 没被接住时：解释器抛的是**rsf 自己的错误**，不是 JVM 的 ArrayIndexOutOfBoundsException
        val leaked = """
            fn main() -> void {
                var a : int[] = new int[2]
                println(a[7])
                rtn
            }
        """.trimIndent() + "\n"
        val fromInterp = assertFailsWith<RsfFailure> { runInterpreted(leaked) }
        assertEquals("数组下标越界：长度 2，下标 7", fromInterp.msg)

        val fromCompiled = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            runCompiledClasses(leaked, "RsfArrayOob2")
        }
        assertTrue(
            fromCompiled.targetException is RuntimeException,
            "编译产物那边应该是 RuntimeException：${fromCompiled.targetException}",
        )
        // 两端消息一字不差
        assertEquals(fromInterp.msg, fromCompiled.targetException.message)
    }

    @Test
    fun `byte 数组能创建并按下标读写`() {
        val program = """
            // 阶段 16b（byte[] + Files.write 落盘）的前提：byte 数组能建、能按字节读写
            fn fillZeros(raw : byte[], from : int) -> void {
                for (var i = from; i < raw.length; i = i + 1) {
                    raw[i] = 0
                }
                rtn
            }

            fn main() -> void {
                var raw : byte[] = new byte[4]
                println("长度 = " + raw.length)
                println("默认 = " + raw[0] + "," + raw[1] + "," + raw[2] + "," + raw[3])
                raw[0] = toByte(200)     // 截断成 -56
                raw[1] = 1               // 0..127 的字面量可以直接赋给 byte
                raw[2] = toByte(255)     // -1
                println("写入后 = " + raw[0] + "," + raw[1] + "," + raw[2])
                fillZeros(raw, 1)
                println("清零后 = " + raw[0] + "," + raw[1] + "," + raw[2])

                var copy : byte[] = new byte[raw.length]
                for (var i = 0; i < raw.length; i = i + 1) {
                    copy[i] = raw[i]
                }
                println("拷贝 = " + copy[0] + "," + copy[1] + "," + copy[3])
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "长度 = 4",
            "默认 = 0,0,0,0",
            "写入后 = -56,1,-1",
            "清零后 = -56,0,0",
            "拷贝 = -56,0,0",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayByte")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `数组可以当类的字段`() {
        val program = """
            pub class Bag {
                var items : int[] = new int[2]
                imutvar label : string = "bag"

                constructor(a : int, b : int) {
                    items[0] = a
                    items[1] = b
                }

                pub fn total() -> int { rtn items[0] + items[1] }

                pub fn grow(size : int) -> void {
                    // 字段数组可以整体换掉（引用赋值）
                    items = new int[size]
                }
            }

            pub class Pair {
                var names : string[] = new string[2]
                var grid : int[][] = new int[2][2]
            }

            fn main() -> void {
                var bag = new Bag(3, 4)
                println("长度 = " + bag.items.length + "，total = " + bag.total())
                bag.items[0] = 10
                println("写元素后 total = " + bag.total())
                bag.grow(3)
                // 换了新数组：元素回到默认值 0
                println("换数组后长度 = " + bag.items.length + "，total = " + bag.total())

                var p = new Pair()
                println("字段默认 names[0] = " + p.names[0] + "，grid[0][1] = " + p.grid[0][1])
                p.names[1] = "rsf"
                p.grid[1][1] = 5
                println("写入后 names[1] = " + p.names[1] + "，grid[1][1] = " + p.grid[1][1])
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "长度 = 2，total = 7",
            "写元素后 total = 14",
            "换数组后长度 = 3，total = 0",
            "字段默认 names[0] = null，grid[0][1] = 0",
            "写入后 names[1] = rsf，grid[1][1] = 5",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayField")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `下标必须是 int`() {
        // 下标是纯整数位置：char / string / long 都不接受，报错要说清该写什么
        val charIndex = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var a : int[] = new int[2]\n    println(a['x'])\n    rtn\n}\n"),
            )
        }
        assertEquals("checker", charIndex.phase)
        assertTrue(charIndex.detail.contains("数组下标必须是 int"), charIndex.detail)
        assertTrue(charIndex.detail.contains("charToInt"), charIndex.detail)

        val stringIndex = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var a : int[] = new int[2]\n    a[\"0\"] = 1\n    rtn\n}\n"),
            )
        }
        assertTrue(stringIndex.detail.contains("数组下标必须是 int"), stringIndex.detail)

        val longIndex = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n    var a : int[] = new int[2]\n" +
                        "    var i : long = 0L\n    println(a[i])\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(longIndex.detail.contains("长度和下标的类型都固定是 int"), longIndex.detail)

        // 不是数组的东西不能写下标；数组的 length 是属性不是方法
        val notArray = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(1[0])\n    rtn\n}\n"))
        }
        assertTrue(notArray.detail.contains("不是数组"), notArray.detail)

        val lengthCall = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var a : int[] = new int[2]\n    println(a.length())\n    rtn\n}\n"),
            )
        }
        assertTrue(lengthCall.detail.contains("不要括号"), lengthCall.detail)

        // 数组不能直接打印：身份哈希两端没法一致，所以编译期就拦住
        val printArray = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var a : int[] = new int[2]\n    println(a)\n    rtn\n}\n"),
            )
        }
        assertTrue(printArray.detail.contains("不能直接打印"), printArray.detail)

        // 数组元素类型不对也要报清楚
        val badElement = assertFailsWith<RsfError> {
            Rsf.analyze(
                source("fn main() -> void {\n    var a : int[] = new int[2]\n    a[0] = \"x\"\n    rtn\n}\n"),
            )
        }
        assertTrue(badElement.detail.contains("元素类型是 int"), badElement.detail)
    }

    @Test
    fun `嵌套数组两端一致`() {
        val program = """
            fn sumGrid(grid : int[][]) -> int {
                var total = 0
                for (var r = 0; r < grid.length; r = r + 1) {
                    for (var c = 0; c < grid[r].length; c = c + 1) {
                        total = total + grid[r][c]
                    }
                }
                rtn total
            }

            fn makeGrid(rows : int, cols : int) -> int[][] {
                var g = new int[rows][cols]
                for (var r = 0; r < rows; r = r + 1) {
                    for (var c = 0; c < cols; c = c + 1) {
                        g[r][c] = r * 10 + c
                    }
                }
                rtn g
            }

            fn main() -> void {
                var grid : int[][] = new int[2][3]
                println("grid.length = " + grid.length + "，grid[0].length = " + grid[0].length)
                println("默认 grid[0][0] = " + grid[0][0] + "，grid[1][2] = " + grid[1][2])
                grid[1][2] = 42
                grid[0][0] = 7
                println("写入后 grid[0][0] = " + grid[0][0] + "，grid[1][2] = " + grid[1][2])

                // grid[0] 就是一层数组（引用语义：改它等于改 grid[0]）
                var row : int[] = grid[0]
                row[1] = 9
                println("通过 row 写入后 grid[0][1] = " + grid[0][1])

                println("sumGrid = " + sumGrid(grid))
                var made : int[][] = makeGrid(3, 2)
                println("made = " + made.length + " x " + made[0].length + "，sum = " + sumGrid(made))

                // 二维的越界也要两端一致
                try {
                    println("不该走到这里：" + grid[0][9])
                } catch (e : string) {
                    println("内层越界：" + e)
                }
                try {
                    println("不该走到这里：" + grid[9][0])
                } catch (e : string) {
                    println("外层越界：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "grid.length = 2，grid[0].length = 3",
            "默认 grid[0][0] = 0，grid[1][2] = 0",
            "写入后 grid[0][0] = 7，grid[1][2] = 42",
            "通过 row 写入后 grid[0][1] = 9",
            "sumGrid = 58",
            "made = 3 x 2，sum = 63",
            "内层越界：数组下标越界：长度 3，下标 9",
            "外层越界：数组下标越界：长度 2，下标 9",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfArrayNested")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    // ------------------------------------------------------------------ 构造器（阶段 18）

    @Test
    fun `主构造的参数能在字段初值里用两端一致`() {
        val program = """
            pub class Point(x0 : int, y0 : int) {
                var x : int = x0
                var y : int = y0
                var doubled : int = x0 * 2 + y0

                // 有了主构造，次构造必须委托给它
                constructor(n : int) {
                    this(n, n)
                }

                pub fn sum() -> int { rtn x + y }
                pub fn describe() -> string { rtn "(" + x + "," + y + ") d=" + doubled }
            }

            fn main() -> void {
                var p = new Point(3, 4)
                println(p.describe() + " sum=" + p.sum())
                println(new Point(5).describe())
                // 主构造参数不在方法作用域里，只有字段能看见
                println("x = " + p.x)
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "(3,4) d=10 sum=7",
            "(5,5) d=15",
            "x = 3",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfCtorPrimary")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `次构造可以写多个并按实参挑选两端一致`() {
        val program = """
            pub class Q {
                var v : int = 0
                var tag : string = "?"

                constructor() {
                    tag = "none"
                }

                constructor(n : int) {
                    v = n
                    tag = "int"
                }

                constructor(n : long) {
                    v = 100
                    tag = "long"
                }

                constructor(s : string) {
                    v = s.length()
                    tag = "string"
                }

                pub fn show() -> string { rtn tag + ":" + v }
            }

            fn main() -> void {
                println(new Q().show())
                println(new Q(7).show())       // int 是精确匹配，赢了 long
                println(new Q(7L).show())
                println(new Q("abcd").show())
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("none:0", "int:7", "long:100", "string:4")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfCtorOverload")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `构造器委托 this 两端一致`() {
        val program = """
            pub class R(x0 : int) {
                var x : int = x0
                var trace : int = 0

                constructor() {
                    this(5)
                    trace = trace + 1        // 委托之后的语句照常跑
                }

                constructor(a : int, b : int) {
                    this(a + b)
                    trace = trace + 10
                }

                constructor(s : string) {
                    this(s.length(), 1)      // 委托链：string -> (int,int) -> 主构造
                    trace = trace + 100
                }

                pub fn show() -> string { rtn "x=" + x + " trace=" + trace }
            }

            fn main() -> void {
                println(new R().show())
                println(new R(2, 3).show())
                println(new R(9).show())          // 直接走主构造
                println(new R("abcd").show())
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "x=5 trace=1",
            "x=5 trace=10",
            "x=9 trace=0",
            // string -> (int,int) -> 主构造：x = "abcd".length() + 1 = 5
            "x=5 trace=110",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfCtorDelegation")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `隐式无参与显式无参主构造两端一致`() {
        val program = """
            // 一个构造器都不写：等价于隐式无参构造器
            pub class Empty {
                var n : int = 7
                var xs : int[] = new int[2]
                pub fn get() -> int { rtn n + xs.length }
            }

            // 显式无参主构造：写 class Zero()
            pub class Zero() {
                var z : int = 3
                pub fn get() -> int { rtn z }
            }

            // 主构造没有参数、次构造委托给它
            pub class WithSecondary() {
                var w : int = 1
                constructor(n : int) {
                    this()
                    w = n
                }
                pub fn get() -> int { rtn w }
            }

            fn main() -> void {
                println("Empty = " + new Empty().get())
                println("Zero = " + new Zero().get())
                println("WithSecondary() = " + new WithSecondary().get())
                println("WithSecondary(9) = " + new WithSecondary(9).get())
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "Empty = 9",
            "Zero = 3",
            "WithSecondary() = 1",
            "WithSecondary(9) = 9",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfCtorImplicit")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    @Test
    fun `构造器规则在编译期拦下`() {
        // 方法不能再冒充构造器
        val oldStyle = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    fn P(a : int) -> void { x = a }\n}\n" +
                        "fn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(oldStyle.detail.contains("不能和类同名"), oldStyle.detail)
        assertTrue(oldStyle.detail.contains("constructor"), oldStyle.detail)

        // 两个参数表一样的构造器
        val dup = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor(a : int) { x = a }\n" +
                        "    constructor(b : int) { x = b }\n}\nfn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(dup.detail.contains("参数表完全一样"), dup.detail)

        // 有主构造时次构造必须委托
        val noDelegation = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P(x0 : int) {\n    var x : int = x0\n    constructor() { x = 1 }\n}\n" +
                        "fn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(noDelegation.detail.contains("必须委托"), noDelegation.detail)

        // 委托必须是第一句
        val notFirst = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P(x0 : int) {\n    var x : int = x0\n    constructor() {\n" +
                        "        x = 1\n        this(2)\n    }\n}\nfn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(notFirst.detail.contains("第一句"), notFirst.detail)

        // 委托只能在构造器里
        val inMethod = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P(x0 : int) {\n    var x : int = x0\n" +
                        "    pub fn f() -> void { this(1) }\n}\nfn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(inMethod.detail.contains("构造器委托"), inMethod.detail)

        // 委托不能成环
        val cycle = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor() { this(1) }\n" +
                        "    constructor(a : int) { this() }\n}\nfn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(cycle.detail.contains("成环"), cycle.detail)

        // 委托给自己
        val self = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor() { this() }\n}\n" +
                        "fn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(self.detail.contains("不能委托给自己"), self.detail)

        // constructor 不写返回类型、不写修饰词
        val ret = assertFailsWith<RsfError> {
            Rsf.parse(
                source("class P {\n    var x : int = 0\n    constructor() -> void { x = 1 }\n}\n"),
            )
        }
        assertEquals("parser", ret.phase)
        assertTrue(ret.detail.contains("不写返回类型"), ret.detail)

        val modifier = assertFailsWith<RsfError> {
            Rsf.parse(
                source("class P {\n    var x : int = 0\n    pub constructor() { x = 1 }\n}\n"),
            )
        }
        assertEquals("parser", modifier.phase)
        assertTrue(modifier.detail.contains("权限修饰词"), modifier.detail)

        // constructor 只能写在类体里
        val topLevel = assertFailsWith<RsfError> {
            Rsf.parse(source("constructor() { }\nfn main() -> void { rtn }\n"))
        }
        assertEquals("parser", topLevel.phase)
        assertTrue(topLevel.detail.contains("类体"), topLevel.detail)

        // new 的实参个数不对时把可用的构造器列出来
        val arity = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P {\n    var x : int = 0\n    constructor(a : int) { x = a }\n}\n" +
                        "fn main() -> void {\n    var p = new P()\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(arity.detail.contains("参数个数不对"), arity.detail)
        assertTrue(arity.detail.contains("P(int a)"), arity.detail)

        // 主构造参数不在方法的可见范围里
        val scopeLeak = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "class P(x0 : int) {\n    var x : int = x0\n" +
                        "    pub fn f() -> int { rtn x0 }\n}\nfn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(scopeLeak.detail.contains("未声明的变量 'x0'"), scopeLeak.detail)
    }

    @Test
    fun `构造器体里出错两端一致`() {
        // 构造器不能是 !T、也不能自己 fail，但**运行期**错误（越界）还是可能从构造器里冒出来：
        // 两端都要让调用方的 try 接得住，而且消息一模一样。
        val program = """
            pub class P(n0 : int) {
                var xs : int[] = new int[2]
                var n : int = n0

                constructor() {
                    this(0)
                    xs[5] = 1          // 委托之后，在次构造体里越界
                }

                pub fn get() -> int { rtn n }
            }

            fn main() -> void {
                try {
                    var p = new P(3)
                    println("正常 = " + p.get())
                } catch (e : string) {
                    println("不该到这里：" + e)
                }
                try {
                    var q = new P()
                    println("不该到这里：" + q.get())
                } catch (e : string) {
                    println("构造器体里越界：" + e)
                }
                println("之后照常往下走")
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "正常 = 3",
            "构造器体里越界：数组下标越界：长度 2，下标 5",
            "之后照常往下走",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfCtorThrow")),
            "解释器与 .class 的输出必须逐字节一致",
        )
    }

    // ------------------------------------------------------------------ 阶段 16b：文件读写 + byte[] 互操作

    @Test
    fun `能读能写文本文件 两端一致`() {
        val dir = tempDir()
        val file = File(dir, "hello.txt")

        val program = """
            fn main() -> void {
                try {
                    writeText(${rsfPath(file)}, "第一行\n第二行")
                    var back : string = readText(${rsfPath(file)})
                    println("长度 = " + back.length())
                    println("内容 = " + back)
                    println("前三个字符 = " + back.charAt(0) + back.charAt(1) + back.charAt(2))
                } catch (e : string) {
                    println("不该失败：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("长度 = 7", "内容 = 第一行", "第二行", "前三个字符 = 第一行")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfFilesText")),
            "解释器与 .class 的输出必须逐字节一致",
        )
        // 落盘的内容也得对（UTF-8，换行原样）
        assertEquals("第一行\n第二行", file.readText())
    }

    @Test
    fun `能读能写二进制文件 两端一致`() {
        val dir = tempDir()
        val file = File(dir, "raw.bin")

        val program = """
            fn main() -> void {
                var data : byte[] = new byte[4]
                data[0] = toByte(200)
                data[1] = 1
                data[2] = toByte(255)
                data[3] = 0
                try {
                    writeBytes(${rsfPath(file)}, data)
                    var back : byte[] = readBytes(${rsfPath(file)})
                    println("长度 = " + back.length)
                    println("元素 = " + back[0] + "," + back[1] + "," + back[2] + "," + back[3])
                    println("和写进去的一样 = " + (back[0] == data[0]) + "," + (back[2] == data[2]))
                } catch (e : string) {
                    println("不该失败：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("长度 = 4", "元素 = -56,1,-1,0", "和写进去的一样 = true,true")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfFilesBytes")),
        )
        // 落到磁盘上的就是那 4 个字节（含负数），一个不多一个不少
        assertTrue(byteArrayOf(-56, 1, -1, 0).contentEquals(file.readBytes()), "二进制内容不对")
    }

    @Test
    fun `文件不存在时失败能被 try 接住 两端消息一致`() {
        val dir = tempDir()
        val missing = File(dir, "no-such-file.txt")
        val missingDir = File(dir, "no-such-dir")

        val program = """
            fn main() -> void {
                try {
                    var text : string = readText(${rsfPath(missing)})
                    println("不该到这里：" + text)
                } catch (e : string) {
                    println("读文本：" + e)
                }
                try {
                    var raw : byte[] = readBytes(${rsfPath(missing)})
                    println("不该到这里：" + raw.length)
                } catch (e : string) {
                    println("读二进制：" + e)
                }
                try {
                    writeText(${rsfPath(File(missingDir, "x.txt"))}, "写不进去")
                    println("不该到这里")
                } catch (e : string) {
                    println("写文本：" + e)
                }
                println("三次失败之后照常往下走")
                rtn
            }
        """.trimIndent() + "\n"

        val shown = missing.absolutePath
        val expected = listOf(
            "读文本：readText: 文件操作失败 '$shown'：$shown",
            "读二进制：readBytes: 文件操作失败 '$shown'：$shown",
            "写文本：writeText: 文件操作失败 '${File(missingDir, "x.txt").absolutePath}'：" +
                "${File(missingDir, "x.txt").absolutePath}",
            "三次失败之后照常往下走",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfFilesFail")),
            "解释器与 .class 的失败消息必须逐字节一致",
        )
    }

    // 注：Kotlin 的反引号测试名不允许出现 ':'（`?:` 写不进去），所以这条名字里用
    // 「兜底值」与「!!」来指代任务里说的 `?:` 与 `!!` 两种处理方式。
    @Test
    fun `文件失败也能用兜底值 与 !! 传播`() {
        val dir = tempDir()
        val missing = File(dir, "gone.txt")
        val good = File(dir, "ok.txt")
        good.writeText("兜底用的内容")

        val program = """
            pub fn loadOrFail(path : string) -> !string {
                var text : string = readText(path)!!
                rtn text
            }

            fn main() -> void {
                println("兜底 = " + (readText(${rsfPath(missing)}) ?: "读不到"))

                try {
                    var a : string = loadOrFail(${rsfPath(missing)})
                    println("不该到这里：" + a)
                } catch (e : string) {
                    println("!! 传播：" + e)
                }

                try {
                    println("!! 成功 = " + loadOrFail(${rsfPath(good)}))
                } catch (e : string) {
                    println("不该失败：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val shown = missing.absolutePath
        val expected = listOf(
            "兜底 = 读不到",
            "!! 传播：readText: 文件操作失败 '$shown'：$shown",
            "!! 成功 = 兜底用的内容",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfFilesElvis")),
        )
    }

    @Test
    fun `JVM 的 byte 数组 API 能用`() {
        val dir = tempDir()
        val file = File(dir, "jvm-bytes.bin")

        val program = """
            fn main() -> void {
                var text : string = "RsF"
                var utf : byte[] = text.getBytes()
                println("字节数 = " + utf.length)
                println("前两个 = " + utf[0] + "," + utf[1])

                var back : string = new String(utf)
                println("转回来 = " + back)
                println("和原来相等 = " + (back == text))

                try {
                    writeBytes(${rsfPath(file)}, utf)
                    var again : byte[] = readBytes(${rsfPath(file)})
                    println("落盘再读回来 = " + again.length + " 个字节，" +
                        "第一个是 " + again[0])
                } catch (e : string) {
                    println("不该失败：" + e)
                }
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf(
            "字节数 = 3",
            "前两个 = 82,115",
            "转回来 = RsF",
            "和原来相等 = true",
            "落盘再读回来 = 3 个字节，第一个是 82",
        )
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfJvmBytes")),
        )
    }

    @Test
    fun `数组类型的 JVM 方法签名能解析`() {
        // 直接对 ClassPath.typeFromDescriptor 的回归：以 '[' 开头的描述符不再是 null
        assertEquals(RsfType.BYTE.arrayOf(1), ClassPath.typeFromDescriptor("[B"))
        assertEquals(RsfType.BYTE.arrayOf(2), ClassPath.typeFromDescriptor("[[B"))
        assertEquals(RsfType.CHAR.arrayOf(1), ClassPath.typeFromDescriptor("[C"))
        assertEquals(RsfType.INT.arrayOf(1), ClassPath.typeFromDescriptor("[I"))
        assertEquals(RsfType.STRING.arrayOf(1), ClassPath.typeFromDescriptor("[Ljava/lang/String;"))
        assertEquals(RsfType.external("Point").arrayOf(2), ClassPath.typeFromDescriptor("[[LPoint;"))
        assertEquals("[B", ClassPath.typeFromDescriptor("[B")!!.descriptor)
        assertEquals("[[B", ClassPath.typeFromDescriptor("[[B")!!.descriptor)
        assertEquals("string[]", ClassPath.typeFromDescriptor("[Ljava/lang/String;")!!.rsfName)
        // 元素描述符认不出来 / 畸形（`[V`）时仍然是 null，调用方据此跳过整个方法
        assertNull(ClassPath.typeFromDescriptor("[Q"))
        assertNull(ClassPath.typeFromDescriptor("[V"))
        assertEquals(listOf("I", "[B", "[[Ljava/lang/String;"), ClassPath.splitParams("(I[B[[Ljava/lang/String;)V"))

        // 带 byte[] 的 JDK 成员现在真的在签名表里了（修复前它们被整个跳过）
        val stringClass = ClassPath.find("java/lang/String")!!
        val getBytes = stringClass.methods.firstOrNull { it.name == "getBytes" && it.paramTypes.isEmpty() }
            ?: error("String.getBytes() -> byte[] 没能解析出来")
        assertEquals(RsfType.BYTE.arrayOf(1), getBytes.returnType)
        assertEquals("()[B", getBytes.descriptor)
        val byteCtor = stringClass.constructors.firstOrNull { it.descriptor == "([B)V" }
            ?: error("String(byte[]) 没能解析出来")
        assertEquals(listOf(RsfType.BYTE.arrayOf(1)), byteCtor.paramTypes)

        // 端到端：上面这两条签名在两条后端里都真的调得动
        val program = """
            fn main() -> void {
                var text : string = "RsF 16b"
                var raw : byte[] = text.getBytes()
                var back : string = new String(raw)
                println("字节数 = " + raw.length)
                println("转回来 = " + back)
                println("相等 = " + (back == text))
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("字节数 = 7", "转回来 = RsF 16b", "相等 = true")
        assertEquals(expected, lines(runInterpreted(program)))
        assertEquals(
            normalize(runInterpreted(program)),
            normalize(runCompiledClasses(program, "RsfJvmArraySig")),
        )
    }

    // ------------------------------------------------------------------ 语法现代化（阶段 19）

    @Test
    fun `参数两种风格都能用且可以混写`() {
        // 同一个参数表里 Java 风格（类型在前）与 Kotlin 风格（名字在前）混着写；
        // 泛型参数 / 数组参数 / 可空参数各来一个，验证「两种风格下&<> [] ? 都自动可用」。
        val program = """
            pub class Box&<T> {
                var value : T = 0
                constructor(v0 : T) { value = v0 }
                pub fn get() -> T { rtn value }
            }

            fn javaStyle(int a, string s) -> int { rtn a + s.length() }
            fn kotlinStyle(a : int, s : string) -> int { rtn a + s.length() }
            fn mixed(int a, s : string, Box&<int> b, xs : int[], maybe : ?int) -> int {
                var extra = maybe ?: 0
                rtn a + s.length() + b.get() + xs.length + extra
            }

            fn main() -> void {
                var s = "abcd"
                println("java   = " + javaStyle(1, s))
                println("kotlin = " + kotlinStyle(1, s))
                var b = new Box&<int>(10)
                var xs : int[] = new int[3]
                println("mixed  = " + mixed(1, s, b, xs, null))
                println("mixed  = " + mixed(1, s, b, xs, 7))
                rtn
            }
        """.trimIndent() + "\n"

        val expected = listOf("java   = 5", "kotlin = 5", "mixed  = 18", "mixed  = 25")
        val interpreted = runInterpreted(program)
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfParamStyles")),
            "参数两种风格必须两端逐字节一致",
        )
    }

    @Test
    fun `参数漏了类型时提示两种写法`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn f(x) -> int { rtn 1 }\n"))
        }
        assertTrue(e.detail.contains("参数 'x' 缺少类型"), e.detail)
        // 两种写法的提示都必须出现（用户照着任一条改都能过）
        assertTrue(e.detail.contains("int x"), e.detail)
        assertTrue(e.detail.contains("x : int"), e.detail)

        // 同一个错误在构造器参数上也要给同样的两种写法
        val ctor = assertFailsWith<RsfError> {
            Rsf.analyze(source("class P {\n    var v : int = 0\n    constructor(x) { v = x }\n}\n"))
        }
        assertTrue(ctor.detail.contains("int x"), ctor.detail)
        assertTrue(ctor.detail.contains("x : int"), ctor.detail)
    }

    @Test
    fun `自动 new 与显式 new 等价`() {
        val program = """
            pub class Point(x0 : int, y0 : int) {
                var x : int = x0
                var y : int = y0
                constructor() { this(0, 0) }
                constructor(v : int) { this(v, 0) }
                pub fn sum() -> int { rtn x + y }
            }

            pub class Marker {
                var tag : string = "m"
            }

            fn makePoint(a : int, b : int) -> Point { rtn new Point(a, b) }

            fn main() -> void {
                // 带参数的主构造
                var p = Point(3, 4)
                var q = new Point(3, 4)
                println("p = " + p.sum() + " / q = " + q.sum())

                // 无参 / 隐式无参
                println("noArg = " + Point().sum() + " / " + new Point().sum())
                println("implicit = " + Marker().tag + " / " + new Marker().tag)

                // 次构造重载挑选（三个构造器：无参委托 / 两参主构造 / 一参）
                var one = Point(7)
                println("one = " + one.x + "," + one.y)

                // 嵌套在表达式里
                println("nested = " + Point(1, 2).sum())
                println("asArg = " + makePoint(Point(1, 1).sum(), 5).sum())

                // 作为实参 / 返回值 / 字段初值
                var held = Point(Point(2, 3).sum(), 1)
                println("held = " + held.sum())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "p = 7 / q = 7",
            "noArg = 0 / 0",
            "implicit = m / m",
            "one = 7,0",
            "nested = 3",
            "asArg = 7",
            "held = 6",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfAutoNew")),
            "省掉 new 与显式 new 必须两端逐字节一致",
        )

        // 同一条程序，把 new 全删掉之后输出必须**一模一样**
        val noNew = program.replace("new Point(", "Point(").replace("new Marker(", "Marker(")
        assertEquals(normalize(interpreted), normalize(runInterpreted(noNew)))
        assertEquals(
            normalize(runInterpreted(noNew)),
            normalize(runCompiledClasses(noNew, "RsfAutoNew2")),
        )
    }

    @Test
    fun `名字是函数时仍然是函数调用`() {
        // 类名与函数名不能重名 ⇒ 一个名字只可能是一种；函数名必须照旧当函数调用
        val program = """
            pub class Widget {
                var n : int = 1
            }

            fn make() -> int { rtn 41 }
            fn widget() -> Widget { rtn Widget() }

            fn main() -> void {
                var a = make()              // 函数调用，不是构造
                var b = widget()            // 函数调用（返回对象）
                println("a = " + a + " b = " + b.n)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("a = 41 b = 1"), lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfFuncNotCtor")),
        )
    }

    @Test
    fun `类名与函数名重名仍然被拦住`() {
        // 自动 new 的判定依据就是「类名与函数名不能重名」，这条检查必须还在
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub class Widget {\n    var n : int = 1\n}\n\n" +
                        "fn Widget() -> int { rtn 1 }\n",
                ),
            )
        }
        assertTrue(e.detail.contains("函数名和类名不能重名"), e.detail)
    }

    @Test
    fun `省掉 new 构造泛型类与数组元素`() {
        val program = """
            pub class Box&<T> {
                var value : T = 0
                constructor(v0 : T) { value = v0 }
                pub fn get() -> T { rtn value }
            }

            pub class Pair&<K, V>(k0 : K, v0 : V) {
                var k : K = k0
                var v : V = v0
            }

            fn main() -> void {
                // 泛型类：`Box&<int>(0)` 与 `new Box&<int>(0)` 一样
                var b = Box&<int>(0)
                println("b = " + b.get())
                var s = Box&<string>("hi")
                println("s = " + s.get().length())

                // 显式 new 的泛型构造仍然可用，两者输出必须相同
                var c = new Box&<int>(9)
                println("c = " + c.get())

                // 多类型参数
                var p = Pair&<string, int>("age", 30)
                println("pair = " + p.k + ":" + p.v)

                // 用户类数组里放手写构造的对象
                var ps : Box&<int>[] = new Box&<int>[2]
                ps[0] = Box&<int>(1)
                ps[1] = new Box&<int>(2)
                println("arr = " + ps[0].get() + "," + ps[1].get())
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf("b = 0", "s = 2", "c = 9", "pair = age:30", "arr = 1,2")
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfAutoNewGeneric")),
        )
    }

    @Test
    fun `JVM 类也能省掉 new`() {
        val program = """
            import java.util.ArrayList

            fn main() -> void {
                var text : string = "RsF 16b"
                var raw : byte[] = text.getBytes()
                // 显式 new 与省掉 new 各来一次，结果必须相同
                var a : string = new String(raw)
                var b : string = String(raw)
                println("a = " + a + " / b = " + b)
                var xs = ArrayList&<int>()
                xs.add(7)
                println("xs = " + xs.get(0))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(listOf("a = RsF 16b / b = RsF 16b", "xs = 7"), lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfAutoNewJvm")),
        )
    }

    @Test
    fun `拼接用户类对象两端一致`() {
        val program = """
            pub class Plain(a : int, b : bool) {
                var n : int = a
                var flag : bool = b
            }

            pub class WithText(t : string, c : char) {
                var name : string = t
                var mark : char = c
                var count : long = 7L
                var ratio : double = 0.5
            }

            pub class Nested {
                var inner : Plain = new Plain(1, true)     // 对象字段：跳过
                var items : int[] = new int[2]              // 数组字段：跳过
                var id : byte = toByte(200)
            }

            fn main() -> void {
                var p = new Plain(3, false)
                println("p = " + p)
                println(p)
                var w = WithText("hi", 'x')
                println("w = " + w)
                var n = Nested()
                println("n = " + n)
                // 嵌套在别的拼接里也要一致
                println("[" + p + "]" + " / " + w)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(
            listOf(
                "p = Plain(n=3, flag=false)",
                "Plain(n=3, flag=false)",
                "w = WithText(name=hi, mark=x, count=7, ratio=0.5)",
                "n = Nested(inner=..., items=..., id=-56)",
                "[Plain(n=3, flag=false)] / WithText(name=hi, mark=x, count=7, ratio=0.5)",
            ),
            lines(interpreted),
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfObjectConcat")),
            "拼接用户类对象必须两端逐字节一致",
        )
        // 同一个程序跑两次，文本也必须一样（身份哈希那种做法会随机）
        assertEquals(normalize(interpreted), normalize(runInterpreted(program)))
        assertEquals(
            normalize(runCompiledClasses(program, "RsfObjectConcat2")),
            normalize(runCompiledClasses(program, "RsfObjectConcat3")),
        )
    }

    @Test
    fun `返回子句不再接受双冒号写法`() {
        // 2026-10-15 拍板：`:: T` 删掉，`::` 腾给 `xxx::xx`（伴生类 Marker::Impl）
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("fn f() :: int { rtn 1 }\n"))
        }
        // 报错要说清改用 -> 或 :
        assertTrue(e.detail.contains("->"), e.detail)
        assertTrue(e.detail.contains(":"), e.detail)

        // `->` 和 `:` 都还在
        Rsf.parse(source("fn f() -> int { rtn 1 }\n"))
        Rsf.parse(source("fn f() : int { rtn 1 }\n"))
    }

    @Test
    fun `变量与字段的类型只接受名字后置写法`() {
        // 新写法：名字 : 类型
        Rsf.parse(source("fn main() -> void {\n    var x : int = 0\n    rtn\n}\n"))
        Rsf.parse(source("class P {\n    var n : int = 0\n}\n"))

        // 老写法：类型在前 —— 报错里要直接给出新写法
        val local = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    var int x = 0\n    rtn\n}\n"))
        }
        assertTrue(local.detail.contains("名字"), local.detail)
        assertTrue(local.detail.contains("var x : int ="), local.detail)

        val field = assertFailsWith<RsfError> {
            Rsf.parse(source("class P {\n    var int x = 0\n}\n"))
        }
        assertTrue(field.detail.contains("var x : int ="), field.detail)

        // 冒号后面不是类型：要说清这里写的是类型
        val bad = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    var x : 5 = 1\n    rtn\n}\n"))
        }
        assertTrue(bad.detail.contains("x : 类型"), bad.detail)
    }

    @Test
    fun `泛型类的字段读写两端一致`() {
        // 回归：泛型字段在类文件里是**擦除**后的描述符（`Ljava/lang/Object;`）。
        // 以前 GETFIELD / PUTFIELD 用的是**替换之后**的类型（`Ljava/lang/String;` / `I`），
        // 编译能过、运行期 NoSuchFieldError——而解释器那边完全正常，典型的两端不一致。
        val program = """
            pub class Box&<T> {
                var value : T = 0
                constructor(v0 : T) { value = v0 }
                pub fn get() -> T { rtn value }
                pub fn put(v : T) -> void { value = v }
            }

            pub class Pair&<K, V>(k0 : K, v0 : V) {
                var k : K = k0
                var v : V = v0
            }

            fn main() -> void {
                var b = Box&<int>(5)
                println("读 = " + b.value)
                b.value = 9
                println("写后再读 = " + b.value)
                b.put(11)
                println("方法写后再读 = " + b.get())

                var s = Box&<string>("hi")
                println("字符串字段 = " + s.value)
                s.value = "bye"
                println("改字符串字段 = " + s.value)

                var p = Pair&<string, int>("age", 30)
                println("pair = " + p.k + ":" + p.v)
                p.v = 31
                println("pair 改后 = " + p.k + ":" + p.v)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "读 = 5", "写后再读 = 9", "方法写后再读 = 11",
            "字符串字段 = hi", "改字符串字段 = bye",
            "pair = age:30", "pair 改后 = age:31",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfGenericField")),
            "泛型字段读写必须两端逐字节一致",
        )
    }

    @Test
    fun `示例文件同时能解释执行与编译执行且两端一致`() {
        // 示例是交付物的一部分：它必须一直能跑，而且两条后端逐字节一致。
        // 这里只跑 files.rsf（唯一一个自己会写文件的示例，也是 16b 的验收物）。
        val example = File(System.getProperty("user.dir"), "rsfSrc/lang/files.rsf")
        assertTrue(example.isFile, "找不到示例: ${example.absolutePath}")
        val text = example.readText(Charsets.UTF_8)

        val interpreted = runInterpreted(text)
        assertTrue(interpreted.isNotEmpty(), "示例没有输出")
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(text, "files")),
            "files.rsf 示例两端输出必须逐字节一致",
        )
        // 跑两次也要一样（示例里的临时文件每次都重建，不能有残留影响）
        assertEquals(normalize(interpreted), normalize(runInterpreted(text)))
    }

    @Test
    fun `enums 示例同时能解释执行与编译执行且两端一致`() {
        // 阶段 19c 的示例（enum + switch + &&/||）。它比别的小、也不碰文件，
        // 所以除了两端一致，还把「跑两次结果相同」一起钉住。
        val example = File(System.getProperty("user.dir"), "rsfSrc/lang/enums.rsf")
        assertTrue(example.isFile, "找不到示例: ${example.absolutePath}")
        val text = example.readText(Charsets.UTF_8)

        val interpreted = runInterpreted(text)
        assertTrue(interpreted.isNotEmpty(), "示例没有输出")
        assertTrue(
            lines(interpreted).any { it.contains("IDENT") },
            "示例应当演示枚举打印值名：$interpreted",
        )
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(text, "enums")),
            "enums.rsf 示例两端输出必须逐字节一致",
        )
        assertEquals(normalize(interpreted), normalize(runInterpreted(text)))
    }

    // ------------------------------------------------------------------ 阶段 19c：&& / ||

    @Test
    fun `与或的真值表两端一致`() {
        // 4×2 组合全部走一遍，再混进比较运算和括号——
        // 后者验的是**优先级**：`a == b && c == d` 必须是 `(a == b) && (c == d)`，
        // `x || y && z` 必须是 `x || (y && z)`（`&&` 比 `||` 紧）。
        val program = """
            fn main() -> void {
                var t = true
                var f = false
                println("t && t = " + (t && t))
                println("t && f = " + (t && f))
                println("f && t = " + (f && t))
                println("f && f = " + (f && f))
                println("t || t = " + (t || t))
                println("t || f = " + (t || f))
                println("f || t = " + (f || t))
                println("f || f = " + (f || f))

                var a = 1
                var b = 1
                var c = 2
                var d = 2
                println("比较与 = " + (a == b && c == d))
                println("比较与 假 = " + (a == b && a == c))
                println("比较或 = " + (a == c || c == d))
                println("混合 = " + (a == c || a == b && c == d))
                println("或与结合 = " + (f || t && f))
                println("括号 = " + ((f || t) && f))
                println("非 = " + (!f && !f) + " / " + !(a == c))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "t && t = true", "t && f = false", "f && t = false", "f && f = false",
            "t || t = true", "t || f = true", "f || t = true", "f || f = false",
            "比较与 = true", "比较与 假 = false", "比较或 = true",
            "混合 = true", "或与结合 = false", "括号 = false", "非 = true / true",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfLogicTruth")),
            "与或真值表必须两端逐字节一致",
        )
    }

    @Test
    fun `与或真短路`() {
        // 短路是这两条运算符**唯一**的实现难点，所以拿副作用直接钉住：
        // `false && g()` 里的 g 一次都不能跑、`true || g()` 里的 g 一次都不能跑，
        // 而 `true && g()` / `false || g()` 里必须跑。调用次数与顺序都打印出来比。
        val program = """
            pub class Counter {
                var n : int = 0
            }

            fn f(c : Counter) -> bool {
                c.n = c.n + 1
                println("f 第 " + c.n + " 次")
                rtn false
            }

            fn t(c : Counter) -> bool {
                c.n = c.n + 1
                println("t 第 " + c.n + " 次")
                rtn true
            }

            fn main() -> void {
                var c = Counter()

                println("假与 = " + (f(c) && t(c)))
                println("调用次数 = " + c.n)

                println("真或 = " + (t(c) || f(c)))
                println("调用次数 = " + c.n)

                println("真与 = " + (t(c) && f(c)))
                println("调用次数 = " + c.n)

                println("假或 = " + (f(c) || t(c)))
                println("调用次数 = " + c.n)

                println("左边是字面量 = " + (false && f(c)) + " / " + (true || f(c)))
                println("嵌套 = " + (f(c) && (t(c) || f(c))))
                println("最终调用次数 = " + c.n)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        // 逐条核对「谁跑了」：右边不该跑的那两处，调用次数必须停在原地
        val expected = listOf(
            "f 第 1 次", "假与 = false", "调用次数 = 1",
            "t 第 2 次", "真或 = true", "调用次数 = 2",
            "t 第 3 次", "f 第 4 次", "真与 = false", "调用次数 = 4",
            "f 第 5 次", "t 第 6 次", "假或 = true", "调用次数 = 6",
            "左边是字面量 = false / true",
            "f 第 7 次", "嵌套 = false", "最终调用次数 = 7",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfLogicShort")),
            "短路必须两端逐字节一致（右边一次都不能被求值）",
        )
    }

    @Test
    fun `与或的操作数必须是 bool`() {
        // 报错要说清**哪一边**、**实际是什么类型**
        val leftInt = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(1 && true)\n    rtn\n}\n"))
        }
        assertTrue(leftInt.detail.contains("两边必须是 bool"), leftInt.detail)
        assertTrue(leftInt.detail.contains("左边是 int"), leftInt.detail)

        val rightInt = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(true || 2)\n    rtn\n}\n"))
        }
        assertTrue(rightInt.detail.contains("两边必须是 bool"), rightInt.detail)
        assertTrue(rightInt.detail.contains("右边是 int"), rightInt.detail)

        val string = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    println(\"a\" && true)\n    rtn\n}\n"))
        }
        assertTrue(string.detail.contains("左边是 string"), string.detail)

        // 可空 bool 也不收：先说清怎么改（?: 或 !!），别只丢一句「类型不对」
        val nullable = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var p : ?bool = null\n" +
                        "    println(p && true)\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(nullable.detail.contains("左边是 ?bool"), nullable.detail)
        assertTrue(nullable.detail.contains("?:"), nullable.detail)

        // 单个 `|` 不是记号：词法阶段就要给出「逻辑或写两个竖线」的提示
        val single = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    println(true | false)\n    rtn\n}\n"))
        }
        assertEquals("lexer", single.phase)
        assertTrue(single.detail.contains("||"), single.detail)
    }

    @Test
    fun `与或与泛型实参记号不冲突`() {
        // `&` 是泛型实参表的引子（规范写法是**紧跟**的 `Box&<int>`，2026-10-11 拍板），
        // `&&` 是逻辑与。同一条程序里两者混用、甚至写在同一行里，都要各归各位。
        val program = """
            pub class Box&<T> {
                var value : T = 0
                constructor(v0 : T) { value = v0 }
                pub fn get() -> T { rtn value }
            }

            fn main() -> void {
                var b = Box&<int>(7)
                var ok = b.value == 7 && b.value > 0
                println("box = " + b.get() + " ok = " + ok)

                var s = Box&<string>("hi")
                var same = s.value == "hi" && b.value == 7
                println("类型实参各是各的 = " + same)

                var f = false
                println("同行混用 = " + (b.value > 100 || f && ok))

                // 类型标注里的 `&<` 与数组后缀 `[]` 紧挨着；同一个程序里还有 `&&`
                var xs : Box&<int>[] = new Box&<int>[2]
                xs[0] = b
                var first = xs[0]
                println("泛型数组 = " + (xs.length == 2 && first.get() == 7))
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "box = 7 ok = true",
            "类型实参各是各的 = true",
            "同行混用 = false",
            "泛型数组 = true",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfLogicGenerics")),
            "`&<...>` 与 `&&` 共存必须两端逐字节一致",
        )

        // 规范写法是**无空格**的 `Box&<int>`；带空格的 `Box &<int>` **仍然能编译**
        // （词法上空白本来就不重要，这里刻意不打死），两种写法的输出必须一模一样。
        val spaced = program.replace("&<", " &<")
        assertTrue(spaced.contains("Box &<int>[]"), "空格写法要真的替换出来了：\n$spaced")
        assertEquals(expected, lines(runInterpreted(spaced)), "带空格的写法必须产出同样的输出")
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(spaced, "RsfLogicGenericsSpaced")),
            "带空格的写法编译执行也要与无空格写法逐字节一致",
        )
    }

    // ------------------------------------------------------------------ 阶段 19c：enum

    @Test
    fun `枚举声明与取值两端一致`() {
        // 声明带尾逗号、取值、== / !=、以及**打印出值名**（println 与字符串拼接两条路）
        val program = """
            pub enum TokenKind {
                IDENT,
                INT_LIT,
                STRING,
                EOF,
            }

            fn main() -> void {
                var k = TokenKind.IDENT
                println(k)
                println("kind = " + k)
                println("相等 = " + (k == TokenKind.IDENT))
                println("不等 = " + (k != TokenKind.EOF))
                println("同一个常量 = " + (TokenKind.INT_LIT == TokenKind.INT_LIT))
                println("不同的常量 = " + (TokenKind.INT_LIT == TokenKind.STRING))
                println("直接取 = " + TokenKind.EOF)
                println("拼两个 = " + TokenKind.IDENT + "/" + TokenKind.STRING)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "IDENT",
            "kind = IDENT",
            "相等 = true",
            "不等 = true",
            "同一个常量 = true",
            "不同的常量 = false",
            "直接取 = EOF",
            "拼两个 = IDENT/STRING",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfEnumBasic")),
            "枚举取值与打印名字必须两端逐字节一致",
        )
    }

    @Test
    fun `枚举可以当字段 参数 返回值 与数组元素`() {
        val program = """
            pub enum Color { RED, GREEN, BLUE }

            pub class Palette(k0 : Color) {
                var current : Color = k0
                var history : Color[] = new Color[3]
                pub fn get() -> Color { rtn current }
                pub fn pick(c : Color) -> Color { rtn c }
                pub fn set(c : Color) -> void { current = c }
            }

            fn first(c : Color, other : Color) -> Color {
                rtn c
            }

            fn main() -> void {
                var p = Palette(Color.RED)
                println("字段 = " + p.current)
                println("方法返回 = " + p.get())

                p.set(Color.BLUE)
                println("改字段后 = " + p.current)

                println("参数与返回 = " + p.pick(Color.GREEN) + " / " + first(Color.BLUE, Color.RED))

                p.history[0] = Color.GREEN
                p.history[1] = p.current
                println("数组 = " + p.history[0] + "," + p.history[1] + "," + p.history[2])
                println("数组长度 = " + p.history.length)
                println("对象 = " + p)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "字段 = RED",
            "方法返回 = RED",
            "改字段后 = BLUE",
            "参数与返回 = GREEN / BLUE",
            "数组 = GREEN,BLUE,null",
            "数组长度 = 3",
            "对象 = Palette(current=BLUE, history=...)",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfEnumMembers")),
            "枚举当字段/参数/返回值/数组元素必须两端逐字节一致",
        )
    }

    @Test
    fun `可空枚举与兜底值两端一致`() {
        val program = """
            pub enum Mode { FAST, SLOW, OFF }

            fn pick(m : ?Mode) -> Mode {
                rtn m ?: Mode.OFF
            }

            fn main() -> void {
                var maybe : ?Mode = null
                println("可空打印 = " + maybe)
                println("兜底 = " + (maybe ?: Mode.FAST))
                println("函数兜底 = " + pick(null) + " / " + pick(Mode.SLOW))

                maybe = Mode.SLOW
                println("解包 = " + maybe!!)
                println("比较 = " + (maybe!! == Mode.SLOW))

                var modes : Mode[] = new Mode[2]
                println("数组没填过的格子 = " + modes[0])
                modes[0] = Mode.FAST
                // 注意：数组元素的静态类型是非空 `Mode`（元素可空本阶段不支持），
                // 所以这里不能写 `modes[0]!!`——未填的格子运行时才是 null
                println("数组写入后 = " + modes[0])
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "可空打印 = null",
            "兜底 = FAST",
            "函数兜底 = OFF / SLOW",
            "解包 = SLOW",
            "比较 = true",
            "数组没填过的格子 = null",
            "数组写入后 = FAST",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfEnumNullable")),
            "可空枚举 + ?: + !! 必须两端逐字节一致",
        )
    }

    @Test
    fun `枚举值重名 与 枚举名重名会被拦下`() {
        // 值名重复
        val dupValue = assertFailsWith<RsfError> {
            Rsf.analyze(source("pub enum E { A, B, A }\n\nfn main() -> void { rtn }\n"))
        }
        assertTrue(dupValue.detail.contains("值 'A' 重复"), dupValue.detail)

        // 枚举名与类名冲突（同一个类型命名空间）
        val clashClass = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Thing { A }\n\n" +
                        "pub class Thing { var v : int = 0 }\n\n" +
                        "fn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(clashClass.detail.contains("和同名的枚举冲突"), clashClass.detail)

        // 枚举名与函数名冲突
        val clashFun = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Thing { A }\n\n" +
                        "pub fn Thing() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(clashFun.detail.contains("函数名和枚举名不能重名"), clashFun.detail)

        // 两个同名枚举
        val dupEnum = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Thing { A }\n\n" +
                        "pub enum Thing { B }\n\n" +
                        "fn main() -> void { rtn }\n",
                ),
            )
        }
        assertTrue(dupEnum.detail.contains("重复定义"), dupEnum.detail)

        // 空枚举：语法层就该拦住（没有任何值可取）
        val empty = assertFailsWith<RsfError> {
            Rsf.parse(source("pub enum E { }\n\nfn main() -> void { rtn }\n"))
        }
        assertTrue(empty.detail.contains("至少要有一个值"), empty.detail)

        // 不存在的值名：报错里要**列出有哪些值**
        val badValue = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum TokenKind { IDENT, INT_LIT }\n\n" +
                        "fn main() -> void {\n    println(TokenKind.IDNET)\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(badValue.detail.contains("没有值 'IDNET'"), badValue.detail)
        assertTrue(badValue.detail.contains("IDENT, INT_LIT"), badValue.detail)
    }

    @Test
    fun `不同枚举类型之间不能比较`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Left { X }\n\n" +
                        "pub enum Right { X }\n\n" +
                        "fn main() -> void {\n" +
                        "    var a = Left.X\n" +
                        "    var b = Right.X\n" +
                        "    println(a == b)\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("两边是不同的枚举"), e.detail)
        assertTrue(e.detail.contains("Left"), e.detail)
        assertTrue(e.detail.contains("Right"), e.detail)

        // 同一个枚举的两个值当然可以比
        Rsf.analyze(
            source(
                "pub enum Left { X, Y }\n\n" +
                    "fn main() -> void {\n" +
                    "    println(Left.X == Left.Y)\n" +
                    "    rtn\n}\n",
            ),
        )

        // 枚举不能构造：`new` 与省略 new 两种写法都要给出明确提示
        val ctor = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Left { X }\n\n" +
                        "fn main() -> void {\n    var a = new Left()\n    rtn\n}\n",
                ),
            )
        }
        assertTrue(ctor.detail.contains("Left"), ctor.detail)

        // 枚举值要从**类型**上取，不能拿变量当接收者
        val viaVar = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "pub enum Left { X }\n\n" +
                        "fn main() -> void {\n" +
                        "    var a = Left.X\n" +
                        "    println(a.X)\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(viaVar.detail.contains("要从**类型**上取"), viaVar.detail)
    }

    @Test
    fun `跨文件使用枚举`() {
        // 枚举在别的文件里声明、根文件带 `pkg`：类型解析、成员访问、
        // switch 分派、以及**生成 .class 时的包路径**都要跟着走。
        val root = project(
            mapOf(
                "lib/kinds.rsf" to (
                    "pub enum TokenKind {\n" +
                        "    IDENT,\n" +
                        "    INT_LIT,\n" +
                        "    EOF,\n" +
                        "}\n\n" +
                        "pub fn describe(k : TokenKind) -> string {\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.IDENT : rtn \"标识符\"\n" +
                        "        TokenKind.INT_LIT : rtn \"整数\"\n" +
                        "        TokenKind.EOF : rtn \"结束\"\n" +
                        "    }\n" +
                        "}\n"
                    ),
                "app/main.rsf" to (
                    "pkg app\n" +
                        "import \"../lib/kinds.rsf\"\n\n" +
                        "pub class Token {\n" +
                        "    var kind : TokenKind = TokenKind.EOF\n" +
                        "    constructor(k : TokenKind) { kind = k }\n" +
                        "}\n\n" +
                        "fn main() -> void {\n" +
                        "    var t = Token(TokenKind.INT_LIT)\n" +
                        "    println(\"kind = \" + t.kind)\n" +
                        "    println(\"describe = \" + describe(t.kind))\n" +
                        "    println(\"相等 = \" + (t.kind == TokenKind.INT_LIT))\n" +
                        "    rtn\n" +
                        "}\n"
                    ),
            ),
        )

        val loaded = Rsf.load(root)
        val model = Rsf.analyze(loaded)

        val buf = ByteArrayOutputStream()
        Interpreter(model, PrintStream(buf, true, "UTF-8")).runEntry()
        val interpreted = buf.toString("UTF-8")
        val expected = listOf("kind = INT_LIT", "describe = 整数", "相等 = true")
        assertEquals(expected, lines(interpreted))

        // 编译侧：程序类与枚举类都在 `app` 包里，靠类文件的路径验证这一点
        val dir = tempDir()
        val out = Rsf.build(model, loaded.root, "RsfEnumCross", dir)
        val enumClass = out.classFiles.first { it.name == "TokenKind.class" }
        assertEquals(
            "app/TokenKind.class".replace('/', File.separatorChar),
            enumClass.relativeTo(dir).path,
            "带 pkg 时枚举类应当和用户类一样落在包里",
        )

        val (loader, programName) = programLoader(out.classFiles, dir)
        assertEquals(
            normalize(interpreted),
            normalize(runMain(loader.loadClass(programName))),
            "跨文件 + pkg 的枚举必须两端逐字节一致",
        )
    }


    // ------------------------------------------------------------------ 阶段 19c：switch

    /** 阶段 19c 测试里反复用到的枚举 + 分派函数，拼进各自的程序里。 */
    private val switchPrelude = """
        pub enum TokenKind {
            IDENT,
            INT_LIT,
            STRING,
            EOF,
        }

    """.trimIndent()

    @Test
    fun `switch 匹配枚举常量两端一致`() {
        // 命中第一个 / 中间 / 最后一个，以及走 else 的那一路
        val program = switchPrelude + """
        fn classify(k : TokenKind) -> string {
            switch (k) {
                TokenKind.IDENT : rtn "标识符"
                TokenKind.INT_LIT : rtn "整数"
                TokenKind.STRING : rtn "字符串"
                TokenKind.EOF : rtn "结束"
            }
        }

        fn main() -> void {
            println(classify(TokenKind.IDENT))
            println(classify(TokenKind.INT_LIT))
            println(classify(TokenKind.STRING))
            println(classify(TokenKind.EOF))

            var k = TokenKind.STRING
            switch (k) {
                TokenKind.IDENT : println("第一支")
                TokenKind.STRING : println("中间支")
                TokenKind.EOF : println("最后一支")
                else : println("兜底支")
            }
            switch (k) {
                TokenKind.IDENT : println("只有 IDENT 这一支")
                else : println("else 命中")
            }
            rtn
        }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "标识符", "整数", "字符串", "结束",
            "中间支",
            "else 命中",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfSwitchMatch")),
            "switch 命中各支必须两端逐字节一致",
        )
    }

    @Test
    fun `switch 只执行命中的分支且没有 fallthrough`() {
        // 每一支都带副作用（打印 + 自增），只应该看到**一支**的输出。
        // 这一条同时钉住两件事：不命中就不执行、以及**没有 fallthrough**。
        val program = switchPrelude + """
        pub class Counter {
            var n : int = 0
        }

        fn hit(c : Counter, tag : string) -> void {
            c.n = c.n + 1
            println("跑了 " + tag + "（第 " + c.n + " 次）")
        }

        fn main() -> void {
            var c = Counter()
            var k = TokenKind.INT_LIT
            switch (k) {
                TokenKind.IDENT : hit(c, "IDENT")
                TokenKind.INT_LIT : hit(c, "INT_LIT")
                TokenKind.STRING : hit(c, "STRING")
                TokenKind.EOF : hit(c, "EOF")
            }
            println("穷尽无 else 时的调用次数 = " + c.n)

            switch (k) {
                TokenKind.IDENT : hit(c, "A")
                TokenKind.EOF : hit(c, "B")
                else : hit(c, "ELSE")
            }
            println("带 else 时的调用次数 = " + c.n)
            rtn
        }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "跑了 INT_LIT（第 1 次）",
            "穷尽无 else 时的调用次数 = 1",
            "跑了 ELSE（第 2 次）",
            "带 else 时的调用次数 = 2",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfSwitchNoFall")),
            "只执行命中分支这一点必须两端逐字节一致",
        )
    }

    @Test
    fun `switch 缺分支会报错并列出缺少的值`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.IDENT : println(\"i\")\n" +
                        "        TokenKind.EOF : println(\"e\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("必须覆盖"), e.detail)
        assertTrue(e.detail.contains("INT_LIT"), e.detail)
        assertTrue(e.detail.contains("STRING"), e.detail)
        // 缺的那两个都要列出来，别只报第一个
        assertTrue(
            e.detail.contains("INT_LIT, STRING") || e.detail.contains("STRING, INT_LIT"),
            e.detail,
        )

        // 补上 else 就不报错了（不穷尽也没关系）
        Rsf.analyze(
            source(
                switchPrelude +
                    "fn main() -> void {\n" +
                    "    var k = TokenKind.IDENT\n" +
                    "    switch (k) {\n" +
                    "        TokenKind.IDENT : println(\"i\")\n" +
                    "        else : println(\"其他\")\n" +
                    "    }\n" +
                    "    rtn\n}\n",
            ),
        )
    }

    @Test
    fun `switch 的重复 case 报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.IDENT : println(\"一\")\n" +
                        "        TokenKind.IDENT : println(\"二\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("重复"), e.detail)
        assertTrue(e.detail.contains("TokenKind.IDENT"), e.detail)
    }

    @Test
    fun `阶段 21 起 switch 分支不写 case（旧写法只发弃用警告）`() {
        // 新写法：分支直接写「枚举名.值名 : 语句」，一条警告都没有
        val clean = Rsf.analyze(
            source(
                switchPrelude +
                    "fn main() -> void {\n" +
                    "    var k = TokenKind.IDENT\n" +
                    "    switch (k) {\n" +
                    "        TokenKind.IDENT : println(\"一\")\n" +
                    "        else : println(\"其他\")\n" +
                    "    }\n" +
                    "    rtn\n}\n",
            ),
        )
        assertTrue(clean.warnings.isEmpty(), "新写法不该有警告：${clean.warnings}")

        // 旧写法照收，但每个 `case` 发一条弃用警告——和 Kotlin 的 @Deprecated(since = …) 一个风格
        val legacy = Rsf.analyze(
            source(
                switchPrelude +
                    "fn main() -> void {\n" +
                    "    var k = TokenKind.IDENT\n" +
                    "    switch (k) {\n" +
                    "        case TokenKind.IDENT : println(\"一\")\n" +
                    "        case TokenKind.EOF : println(\"末\")\n" +
                    "        else : println(\"其他\")\n" +
                    "    }\n" +
                    "    rtn\n}\n",
            ),
        )
        assertEquals(2, legacy.warnings.size, legacy.warnings.toString())
        for (w in legacy.warnings) {
            assertTrue(w.startsWith("<test>:"), w)
            assertTrue(w.contains("'case' 已弃用"), w)
            assertTrue(w.contains("is deprecated since ${Rsf.VERSION}"), w)
            assertTrue(w.contains("枚举名.值名 : 语句"), w)
        }
    }

    @Test
    fun `case 写到 switch 外面报错并指路`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    case TokenKind.IDENT : println(\"一\")\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("switch 的块里"), e.detail)
        assertTrue(e.detail.contains("可以不写"), e.detail)
    }

    @Test
    fun `case 写了别的枚举的常量报错`() {
        // 写别的枚举的常量：跨枚举的值永远不相等，编译器直接拦住
        val other = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "pub enum Other { IDENT }\n\n" +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        Other.IDENT : println(\"别的枚举\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(other.detail.contains("但 switch 的值是 TokenKind"), other.detail)

        // 值名不存在：报错要列出这个枚举有哪些值
        val bad = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.NOPE : println(\"?\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(bad.detail.contains("没有值 'NOPE'"), bad.detail)
        assertTrue(bad.detail.contains("IDENT, INT_LIT, STRING, EOF"), bad.detail)

        // 默认分支写 default 也不行——rsf 用 else
        val dflt = assertFailsWith<RsfError> {
            Rsf.parse(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.IDENT : println(\"i\")\n" +
                        "        default : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(dflt.detail.contains("else"), dflt.detail)
    }

    @Test
    fun `对非枚举做 switch 报错`() {
        // 主体不是枚举：case 写得合法（枚举常量），错在**被分派的值**上，
        // 所以报错必须落在 checker 的「只能对枚举做」上，而且要说清实际是什么类型。
        val intSubject = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var n = 1\n" +
                        "    switch (n) {\n" +
                        "        TokenKind.IDENT : println(\"i\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(intSubject.detail.contains("只能对枚举做"), intSubject.detail)
        assertTrue(intSubject.detail.contains("int"), intSubject.detail)

        val stringSubject = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var s = \"a\"\n" +
                        "    switch (s) {\n" +
                        "        TokenKind.IDENT : println(\"i\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(stringSubject.detail.contains("只能对枚举做"), stringSubject.detail)
        assertTrue(stringSubject.detail.contains("string"), stringSubject.detail)

        // case 后面只收枚举常量：写表达式要给出「不支持」的提示（这里是语法层拦住）
        val notConstant = assertFailsWith<RsfError> {
            Rsf.parse(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k = TokenKind.IDENT\n" +
                        "    switch (k) {\n" +
                        "        case 1 : println(\"一\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(notConstant.detail.contains("枚举名"), notConstant.detail)

        // 可空枚举也不行：case 表达不出 null 走哪一支
        val nullable = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    switchPrelude +
                        "fn main() -> void {\n" +
                        "    var k : ?TokenKind = null\n" +
                        "    switch (k) {\n" +
                        "        TokenKind.IDENT : println(\"i\")\n" +
                        "        else : println(\"其他\")\n" +
                        "    }\n" +
                        "    rtn\n}\n",
                ),
            )
        }
        assertTrue(nullable.detail.contains("不能是可空枚举"), nullable.detail)
    }

    @Test
    fun `switch 里的 break 与 rtn`() {
        val program = switchPrelude + """
        fn classify(k : TokenKind) -> string {
            switch (k) {
                TokenKind.IDENT : rtn "标识符"
                TokenKind.INT_LIT : rtn "整数"
                TokenKind.STRING : rtn "字符串"
                TokenKind.EOF : rtn "结束"
            }
        }

        fn main() -> void {
            // 分支体里的 rtn 正常结束函数
            println("rtn = " + classify(TokenKind.STRING))

            // switch 在循环里：分支体里的 break 作用于**循环**（switch 自己不需要 break），
            // continue 同理
            var seen = ""
            var i = 0
            while (i < 4) {
                var k = if i < 2 ? TokenKind.IDENT : TokenKind.EOF
                switch (k) {
                    TokenKind.IDENT : {
                        seen = seen + "i" + i + " "
                        i = i + 1
                    }
                    else : {
                        seen = seen + "e" + i + " "
                        i = i + 1
                        break
                    }
                }
            }
            println("seen = " + seen)
            println("i = " + i)

            var skipped = 0
            var j = 0
            while (j < 4) {
                j = j + 1
                switch (TokenKind.EOF) {
                    TokenKind.EOF : continue
                    else : skipped = skipped + 100
                }
            }
            println("continue 后 j = " + j + "，else 里跑过 " + skipped + " 次")
            rtn
        }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "rtn = 字符串",
            "seen = i0 i1 e2 ",
            "i = 3",
            "continue 后 j = 4，else 里跑过 0 次",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfSwitchBreak")),
            "switch 里的 break / continue / rtn 必须两端逐字节一致",
        )
    }

    @Test
    fun `switch 用在自举风格的分派上两端一致`() {
        // 模拟「按 token 种类分派」：10 个值、每个分支做一件小事。
        // 这是 17b 语法器要用的形状，所以单独钉一条。
        val program = """
            pub enum Kind {
                IDENT,
                INT,
                LONG,
                FLOAT,
                DOUBLE,
                STRING,
                CHAR,
                PLUS,
                MINUS,
                EOF,
            }

            pub class Counters {
                var idents : int = 0
                var numbers : int = 0
                var punct : int = 0
            }

            fn bucket(c : Counters, k : Kind) -> void {
                switch (k) {
                    Kind.IDENT : c.idents = c.idents + 1
                    Kind.INT : c.numbers = c.numbers + 1
                    Kind.LONG : c.numbers = c.numbers + 1
                    Kind.FLOAT : c.numbers = c.numbers + 1
                    Kind.DOUBLE : c.numbers = c.numbers + 1
                    Kind.STRING : println("字符串字面量")
                    Kind.CHAR : println("字符字面量")
                    Kind.PLUS : c.punct = c.punct + 1
                    Kind.MINUS : c.punct = c.punct + 1
                    Kind.EOF : println("结束")
                }
            }

            fn label(k : Kind) -> string {
                switch (k) {
                    Kind.IDENT : rtn "标识符"
                    Kind.INT : rtn "整数"
                    Kind.LONG : rtn "长整数"
                    Kind.FLOAT : rtn "单精度"
                    Kind.DOUBLE : rtn "双精度"
                    Kind.STRING : rtn "字符串"
                    Kind.CHAR : rtn "字符"
                    Kind.PLUS : rtn "加号"
                    Kind.MINUS : rtn "减号"
                    Kind.EOF : rtn "文件结束"
                }
            }

            fn main() -> void {
                var c = Counters()
                var kinds : Kind[] = new Kind[11]
                kinds[0] = Kind.IDENT
                kinds[1] = Kind.INT
                kinds[2] = Kind.LONG
                kinds[3] = Kind.FLOAT
                kinds[4] = Kind.DOUBLE
                kinds[5] = Kind.STRING
                kinds[6] = Kind.CHAR
                kinds[7] = Kind.PLUS
                kinds[8] = Kind.MINUS
                kinds[9] = Kind.IDENT
                kinds[10] = Kind.EOF

                var i = 0
                while (i < kinds.length) {
                    bucket(c, kinds[i])
                    println(i + " => " + label(kinds[i]) + " (" + kinds[i] + ")")
                    i = i + 1
                }
                println("标识符 " + c.idents + " / 数字 " + c.numbers + " / 标点 " + c.punct)
                rtn
            }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        val expected = listOf(
            "0 => 标识符 (IDENT)",
            "1 => 整数 (INT)",
            "2 => 长整数 (LONG)",
            "3 => 单精度 (FLOAT)",
            "4 => 双精度 (DOUBLE)",
            "字符串字面量",
            "5 => 字符串 (STRING)",
            "字符字面量",
            "6 => 字符 (CHAR)",
            "7 => 加号 (PLUS)",
            "8 => 减号 (MINUS)",
            "9 => 标识符 (IDENT)",
            "结束",
            "10 => 文件结束 (EOF)",
            "标识符 2 / 数字 4 / 标点 2",
        )
        assertEquals(expected, lines(interpreted))
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfSwitchDispatch")),
            "自举风格的 10 路分派必须两端逐字节一致",
        )
    }

    @Test
    fun `switch 遇到 null 值两端抛同一条错误`() {
        // 没写 else 的 switch 是**穷尽**的，所以「哪一支都不命中」只剩一种可能：
        // 值是 null（引用类型数组的元素默认就是 null）。两端必须抛**同一条**消息——
        // 各抛各的异常类型没关系（解释器 RsfFailure / 编译产物 RuntimeException，
        // 都能被 catch 接住），消息分叉就会让两端输出不一致。
        val program = switchPrelude + """
        fn main() -> void {
            var kinds : TokenKind[] = new TokenKind[2]
            try {
                switch (kinds[1]) {
                    TokenKind.IDENT : println("IDENT")
                    TokenKind.INT_LIT : println("INT_LIT")
                    TokenKind.STRING : println("STRING")
                    TokenKind.EOF : println("EOF")
                }
                println("不该走到这里")
            } catch (e : string) {
                println("接住 = " + e)
            }

            // 带 else 时 null 走 else（不抛错）
            switch (kinds[0]) {
                TokenKind.IDENT : println("IDENT")
                TokenKind.INT_LIT : println("INT_LIT")
                TokenKind.STRING : println("STRING")
                TokenKind.EOF : println("EOF")
                else : println("null 走了 else")
            }
            rtn
        }
        """.trimIndent() + "\n"

        val interpreted = runInterpreted(program)
        assertEquals(2, lines(interpreted).size, interpreted)
        assertTrue(lines(interpreted)[0].startsWith("接住 = switch 没匹配到任何分支"), interpreted)
        assertEquals("null 走了 else", lines(interpreted)[1])
        assertEquals(
            normalize(interpreted),
            normalize(runCompiledClasses(program, "RsfSwitchNull")),
            "switch 遇到 null 时两端必须抛同一条消息",
        )
    }

    // ------------------------------------------------------------------ 阶段 17a：rsf 版词法器对拍

    @Test
    fun `rsf 版词法器与 Kotlin 版 token 流逐条一致`() {
        // 基准是 Kotlin 版 `rsf lex` 的输出；rsf 版（rsfSrc/bootstrap/LexerCli.rsf）编译出来的
        // 产物按同一个格式打印，两边逐行比。跑的是 rsfSrc/lang 下**全部** .rsf（含子目录）。
        //
        // **阶段 17a 的例外已经在 17b 取消**：17a 冻结的 rsf 版词法器不认识 `enum` /
        // `switch` / `case`（当标识符）、也不认识 `&&` / `||`，所以那时用了阶段 19c
        // 写法的样例被 `usesPhase20Syntax` 分流到另一条测试里「显式记录差异」。
        // 17b 把那三个关键字与两个记号补齐了，分流因此**整套删掉**：
        // 这条现在就是「全部样例、一个都不跳过」，不再需要任何筛子。
        val langDir = File(System.getProperty("user.dir"), "rsfSrc/lang")
        assertTrue(langDir.isDirectory, "找不到示例目录: ${langDir.absolutePath}")
        val examples = langDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".rsf") }
            .sortedBy { it.path }
            .toList()
        assertTrue(examples.size >= 8, "rsfSrc/lang 下的示例太少（${examples.size} 个），对拍没覆盖到")

        var covered = 0
        var withNewSyntax = 0
        for (file in examples) {
            val text = file.readText(Charsets.UTF_8)
            covered++
            if (usesPhase20Syntax(text)) withNewSyntax++
            assertTokenStreamsMatch(
                label = file.relativeTo(File(System.getProperty("user.dir"))).path,
                text = text,
                reference = referenceTokenDump(Rsf.tokens(source(text))),
            )
        }
        assertTrue(covered >= 8, "对拍跑到的样例太少（$covered 个）")
        // 「分流取消」这件事也要有东西守着：如果哪天新语法的样例全被删了，
        // 上面那轮的「逐条一致」就退化成只比旧语法，差异会悄悄溜过去。
        assertTrue(
            withNewSyntax >= 1,
            "对拍里一个用阶段 19c 写法（enum / switch / case / && / ||）的样例都没有：" +
                "覆盖退化成了只比旧语法",
        )
    }

    /**
     * 这份源码有没有用到阶段 19c 的新记号/关键字。
     *
     * 判据取**Kotlin 版**的词法结果（它是最新的事实标准），不靠字符串搜关键字：
     * 搜字符串会把注释和字符串字面量里的 `enum` 也算进来。
     *
     * 17b 起它不再用来「跳过」样例，只用来**数**有几个新语法样例被覆盖到。
     */
    private fun usesPhase20Syntax(text: String): Boolean =
        Rsf.tokens(source(text)).any {
            it.kind == org.example.complie.lexing.TokenKind.KW_ENUM ||
                it.kind == org.example.complie.lexing.TokenKind.KW_SWITCH ||
                it.kind == org.example.complie.lexing.TokenKind.KW_CASE ||
                it.kind == org.example.complie.lexing.TokenKind.ANDAND ||
                it.kind == org.example.complie.lexing.TokenKind.OROR
        }

    @Test
    fun `rsf 版词法器已经认识阶段 19c 的新记号并与 Kotlin 版一致`() {
        // 17a 时这条叫「rsf 版词法器还不认识阶段 19c 的新记号」，钉的是三项**落后**：
        //   1. `enum` / `switch` / `case` 在 rsf 版里是标识符（关键字表少三个）；
        //   2. `&&` 在 rsf 版里被切成两个 `&`；
        //   3. `||` 在 rsf 版里是「不认识的字符」，整份 lex 失败。
        //
        // 17b 把这三项补齐之后，这条测试**反过来**钉住「已经认识、而且与 Kotlin 版
        // 逐字节相同」——同样三件事，逐条翻转：
        //   1. 三个关键字走关键字表，dump 与 Kotlin 版一模一样；
        //   2. `&&` 是**一个** ANDAND 记号；
        //   3. `||` 是**一个** OROR 记号，而**孤零零一个 `|` 两端都要报错**——
        //      这一条是「一致的失败」，不要求 token 流一致（rsf 侧没有异常），
        //      但要求**文案逐字相同**，因为那就写在同一个语言规范里。
        val withKeywords = "enum E { A }\nswitch (x) { case E.A : rtn }\n"
        val referenceKeywords = referenceTokenDump(Rsf.tokens(source(withKeywords)))
        assertTrue(
            referenceKeywords.any { it.contains("KW_ENUM") } &&
                referenceKeywords.any { it.contains("KW_SWITCH") } &&
                referenceKeywords.any { it.contains("KW_CASE") },
            "Kotlin 版应当把 enum / switch / case 认成关键字：$referenceKeywords",
        )
        assertTrue(
            tokenDumpsAgreeFor(withKeywords),
            "rsf 版词法器应当已经把 'enum' / 'switch' / 'case' 认成关键字，" +
                "并与 Kotlin 版的 dump 逐字节相同",
        )

        val withAnd = "var a = b && c\n"
        val refAnd = referenceTokenDump(Rsf.tokens(source(withAnd)))
        assertTrue(refAnd.any { it.contains("ANDAND") }, "Kotlin 版应当有 ANDAND 记号")
        assertTrue(tokenDumpsAgreeFor(withAnd), "rsf 版词法器应当已经认识 '&&'")
        // 反过来钉住「不是两个 &」：一条 ANDAND，而不是两条 AMP
        assertTrue(
            normalize(rsfLexerOutput(probeFor(withAnd))).contains("ANDAND"),
            "rsf 版应当把 '&&' 切成一个 ANDAND 记号",
        )
        assertTrue(
            normalize(rsfLexerOutput(probeFor(withAnd))).lines().none { it.contains("AMP ") },
            "rsf 版不该把 '&&' 切成两个 '&' 记号",
        )

        val withOr = "var a = b || c\n"
        assertTrue(
            referenceTokenDump(Rsf.tokens(source(withOr))).any { it.contains("OROR") },
            "Kotlin 版应当有 OROR 记号",
        )
        assertTrue(tokenDumpsAgreeFor(withOr), "rsf 版词法器应当已经认识 '||'")

        // 单独一个 `|`：两端都判定为失败，而且文案逐字相同
        val lonePipe = "var a = b | c\n"
        val kt = assertFailsWith<RsfError> { Rsf.tokens(source(lonePipe)) }
        assertTrue(kt.detail.contains("逻辑或要写成两个竖线"), "Kotlin 版的报错文案：${kt.detail}")
        val rsfMessage = normalize(
            rsfLexerOutput(
                "fn main() -> void {\n" +
                    "    println(lexError(\"var a = b | c\\n\"))\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        ).trim()
        assertEquals(kt.detail, rsfMessage, "单独一个 '|' 的报错文案两端必须逐字相同")
    }

    @Test
    fun `rsf 版词法器与 Kotlin 版的关键字表与记号表逐条对齐`() {
        // 17b 的第 0 步要求「把 pkg / constructor / this / static / public / protected /
        // private / byte / char / `&<` / `[]` 逐个核对一遍」。抽查没有意义——关键字表
        // 漏一项就是漏一项，而且漏了以后表现是「悄悄变成标识符」，不报错。
        //
        // 所以这条把 Kotlin 版 TokenKind 里的**所有**关键字与标点各写一份，
        // 要求两边 dump 逐字节相同。少一个关键字 / 多一个记号，这条立刻红。
        //
        // 2026-10-15 自举同步（词法那一刀）：把阶段 20 的 override / interface、
        // 阶段 21 的 annotation / companion / entity、阶段 22 先占位的 mathematic
        // 与注解前缀 `@` 也列进来 —— 这几项此前只有 Kotlin 版认，靠下面那条
        // 「已知差异」用例单独钉着；现在两边都认，那份差异清单随之删掉。
        val keywords = listOf(
            "fn", "rtn", "return", "import", "module", "pkg", "var", "imutvar", "let",
            "pub", "prv", "prot", "stc", "override", "class",
            "enum", "interface", "annotation", "companion", "entity", "mathematic",
            "switch", "case",
            "new", "this", "constructor", "if", "else", "while", "for", "in",
            "break", "continue", "goto", "fail", "try", "catch",
            "true", "false", "null",
            "void", "int", "long", "float", "double", "string", "str", "bool", "boolean",
            "byte", "char",
        )
        // 标点：单独一个 `|` 是**错误**（见上一条），所以这里不放它
        val punct = listOf(
            "(", ")", "{", "}", "[", "]", ",", ";",
            "->", ":", "::", "?", "?:", "..", ".", "=", "==", "!=",
            "<", ">", "<=", ">=", "+", "-", "*", "/", "%", "!", "&", "&&", "||", "\\",
            "@",
        )
        // 一行一个，免得 `?` 后面的 `:` 被当成 `?:`
        val allTokens = (keywords + punct).joinToString("\n") + "\n"
        val reference = referenceTokenDump(Rsf.tokens(source(allTokens)))
        // 每个关键字都得认出来：一个都没落成 IDENT
        val misread = reference.filter { it.contains("IDENT") }
        assertTrue(misread.isEmpty(), "这些关键字被切成了标识符：$misread")
        assertTokenStreamsMatch(label = "<关键字与记号全表>", text = allTokens, reference = reference)

        // 泛型实参 `&<` 与数组后缀 `[]`：都是**两个记号**拼出来的，单独过一遍
        val composite = "var b : Box&<int>[] = new Box&<int>[2]\nvar c = a[0]\n"
        assertTokenStreamsMatch(
            label = "<&< 与 []>",
            text = composite,
            reference = referenceTokenDump(Rsf.tokens(source(composite))),
        )
        val kinds = Rsf.tokens(source(composite)).map { it.kind.name }
        assertEquals(
            listOf(
                "KW_VAR", "IDENT", "COLON", "IDENT", "AMP", "LT", "KW_INT", "GT",
                "LBRACKET", "RBRACKET", "ASSIGN", "KW_NEW", "IDENT", "AMP", "LT", "KW_INT",
                "GT", "LBRACKET", "INT_LIT", "RBRACKET",
                "KW_VAR", "IDENT", "ASSIGN", "IDENT", "LBRACKET", "INT_LIT", "RBRACKET",
                "EOF",
            ),
            kinds,
        )
    }

    /** 一份源码在 rsf 版与 Kotlin 版词法器下的 dump 是否**恰好相同**。 */
    private fun tokenDumpsAgreeFor(text: String): Boolean {
        val rsfDump = normalize(rsfLexerOutput(probeFor(text))).trimEnd('\n')
        val kotlinDump = normalize(referenceTokenDump(Rsf.tokens(source(text))).joinToString("\n"))
        return rsfDump == kotlinDump
    }

    /**
     * 阶段 20 / 21 / 22 的新关键字：**两边都认，而且 token 流逐字节相同**。
     *
     * 2026-10-15 自举同步（词法那一刀）之前，`rsfSrc/bootstrap/Lexer.rsf` 的关键字表
     * 停在 45 项 —— `interface` / `override` / `annotation` / `companion` / `entity` /
     * `mathematic` 在 rsf 版里都被切成 `IDENT`，注解前缀 `@` 是「不认识的字符」。
     * 这条用例当年就是为那个**已知差异**写的。关键字表补齐（45 → 51）并收下 `@` 之后，
     * 同一条用例**逐条翻转**成现在这样：两个词法器在这些输入上完全一致。
     */
    @Test
    fun `rsf 版词法器已经收下阶段 20、21、22 的新关键字并与 Kotlin 版一致`() {
        val text = "interface Greeter { }\n" +
            "override fn f() -> void { }\n" +
            "annotation Marker(value : string = \"x\") { }\n" +
            "companion entity Impl { }\n" +
            "entity E\n" +
            "mathematic Math\n" +
            "@Marker(\"hi\")\n"
        val reference = referenceTokenDump(Rsf.tokens(source(text)))
        for (kind in listOf(
            "KW_INTERFACE", "KW_OVERRIDE", "KW_ANNOTATION",
            "KW_COMPANION", "KW_ENTITY", "KW_MATHEMATIC", "AT",
        )) {
            assertTrue(reference.any { it.contains(kind) }, "Kotlin 版应当把新语法切成 $kind")
        }
        assertTokenStreamsMatch(label = "<阶段 20/21/22 新关键字>", text = text, reference = reference)
        assertTrue(tokenDumpsAgreeFor(text), "两个词法器在新关键字上的 dump 必须一致")
    }

    /** 把一份源码写成临时文件，拼出「让 rsf 版词法器 dump 它」的探针 main。 */
    private fun probeFor(text: String): String {
        val tmp = File.createTempFile("rsf-lex-agree-", ".rsf")
        tmp.writeText(text, Charsets.UTF_8)
        tmp.deleteOnExit()
        return "fn main() -> void {\n" +
            "    var ts : Token[] = lexFile(${rsfPath(tmp)}) ?: new Token[0]\n" +
            "    if (ts.length == 0) { println(\"<词法出错或读不到文件>\") }\n" +
            "    dumpTokens(ts)\n" +
            "    rtn\n" +
            "}\n"
    }

    @Test
    fun `rsf 版词法器能处理字面量与注释的刁钻写法`() {
        // 一份塞满边界的源码：char 字面量与 \u 转义、跨行字符串、两种注释、
        // `..` 范围、float / double / long 后缀与下划线、泛型实参 `&<int>`、`?:` / `->`。
        // 注意 multi 那个字符串字面量**真的跨行**（字符串允许跨行是基准行为之一）。
        val tricky = """
            // 行注释：整行都该被吃掉 /* 这个星号不该开块注释 */
            var c : char = 'a'
            var u : char = '\u0041'
            var n : char = '\n'
            var q : char = '\''
            var bs : char = '\\'
            var multi : string = "多行
            字符串"
            /* 块注释
               跨两行，里面有 " 引号 和 // 行注释的样子 */
            for (var i = 1..5; i < 3; i = i + 1) { }
            var f = 1.5f
            var d = 3.14
            var dd = 2.5D
            var big = 9000000000L
            var sep = 1_000_000
            var box : Box&<int>[] = new Box&<int>[2]
            var arrow = 1 -> 2
            var elvis = 1 ?: 2
        """.trimIndent() + "\n"
        assertTokenStreamsMatch(
            label = "<刁钻写法>",
            text = tricky,
            reference = referenceTokenDump(Rsf.tokens(source(tricky))),
        )
    }

    @Test
    fun `rsf 版词法器覆盖关键字别名与各种边角形态`() {
        // 再补一轮「形状奇怪但合法」的输入：关键字别名（str / boolean）、下划线标识符、
        // 反斜杠标点（pkg a\b）、`!!` 两个 BANG、`1.:` 这种「点后面不是数字」、
        // `1.5e3` 与 `0xFF`（词法器都不认，按「数字 + 标识符」切开）。
        val edge = """
            str s = "a"
            boolean b = true
            var _x = 1
            var _ = 2
            pkg a\b
            var p = x!!
            var v : void = 0
            var t = 1.:2
            var e = 1.5e3
            var h = 0xFF
            var w = 1.0_5
            var z = 7L
            var y = 7d
            var x2 = 7f
        """.trimIndent() + "\n"
        assertTokenStreamsMatch(
            label = "<边角形态>",
            text = edge,
            reference = referenceTokenDump(Rsf.tokens(source(edge))),
        )
    }

    @Test
    fun `rsf 版词法器遇到非法输入的行为被钉住`() {
        // 这一条记的是**已知的、两端本来就无法一致**的边界：
        //
        //   * Kotlin 版遇到未闭合的字符串会抛 RsfError（「字符串没有闭合（缺少 "）」）；
        //     rsf 版没有异常可用（`fail` 会让整个程序停下来，没法在同一个进程里观察），
        //     所以 `lex()` 返回空数组，错误信息从 `lexError()` 取；`lexSafe()` 则把错误
        //     落成一个 ILLEGAL token 继续往下切。
        //
        // 这个测试固定的是 **rsf 版自己**的行为（照实写清楚，而不是假装两边一样）。
        // 故意用**只有一个引号**、且后面不再出现引号的多行输入：Kotlin 版会一路吃到
        // 文件末尾才报错，报错行是「开引号那一行」，与 rsf 版记的行号规则一致。
        val broken = "var s : string = \"没闭合\nvar t : int = 1\n"

        val e = assertFailsWith<RsfError> { Rsf.tokens(source(broken)) }
        assertTrue(e.detail.contains("字符串没有闭合"), "Kotlin 版应报字符串没有闭合：${e.detail}")
        assertEquals(1, e.line, "Kotlin 版报的是开引号那一行")
        // 开引号在第 18 列，报的位置指向**那个引号本身**，所以是 18。
        // （2026-10-11 `varis`→`var` 改名让这一行短了 2 个字符，改名前这里是 20。）
        assertEquals(18, e.column, "Kotlin 版报的是开引号那一列")

        // rsf 版：lex() 给出空数组 + 错误消息；lexSafe() 给出带 ILLEGAL 的 token 流
        val probe = rsfLexerOutput(
            "fn main() -> void {\n" +
                "    var src = \"var s : string = \\\"没闭合\\nvar t : int = 1\\n\"\n" +
                "    println(\"错误=\" + lexError(src))\n" +
                "    println(\"lex 长度=\" + lex(src).length)\n" +
                "    var safe = lexSafe(src)\n" +
                "    println(\"lexSafe 长度=\" + safe.length)\n" +
                "    for (var i = 0; i < safe.length; i = i + 1) {\n" +
                "        println(dumpToken(safe[i]))\n" +
                "    }\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(
            probe.contains("错误=字符串没有闭合（缺少 \"）"),
            "rsf 版应报同一条消息，实际：\n$probe",
        )
        assertTrue(probe.contains("lex 长度=0"), "lex() 对非法输入给空数组，实际：\n$probe")
        // lexSafe 把前面的 5 个正常 token 都切出来了，最后落一个 ILLEGAL 收场。
        // 位置与 Kotlin 版报的完全一致：1:18（指向那个开引号本身；
        // `varis`→`var` 改名让这一行短了 2 个字符，改名前这里是 1:20）。
        assertTrue(probe.contains("lexSafe 长度=6"), "lexSafe 落一个 ILLEGAL，实际：\n$probe")
        assertTrue(
            probe.contains("   1:18   ILLEGAL      '字符串没有闭合（缺少 \"）'"),
            "ILLEGAL 应指向 1:18（与 Kotlin 版同位置），实际：\n$probe",
        )

        // 另一条边界：整数字面量超出 int 范围。Kotlin 版在词法阶段就按 int 解析并报错，
        // rsf 版**不算值**（只按形状切），所以它给出一个正常的 INT_LIT —— 这一点照实钉住。
        val overflow = "var big : long = 99999999999\n"
        val kt = assertFailsWith<RsfError> { Rsf.tokens(source(overflow)) }
        assertTrue(kt.detail.contains("超出 int 范围"), "Kotlin 版应报超出 int 范围：${kt.detail}")

        val ovProbe = rsfLexerOutput(
            "fn main() -> void {\n" +
                "    var ts = lex(\"var big : long = 99999999999\\n\")\n" +
                "    println(\"长度=\" + ts.length)\n" +
                "    for (var i = 0; i < ts.length; i = i + 1) {\n" +
                "        println(dumpToken(ts[i]))\n" +
                "    }\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(ovProbe.contains("长度=7"), "rsf 版照常切出 7 个 token，实际：\n$ovProbe")
        assertTrue(
            ovProbe.contains("INT_LIT      '99999999999'  = 99999999999 (Int)"),
            "rsf 版把它当 INT_LIT（不做范围检查），实际：\n$ovProbe",
        )
    }

    @Test
    fun `rsf 版词法器的入口读不到系统属性时会兜底`() {
        // LexerCli.rsf 用 `System.getProperty("rsf.lex.file")` 挑输入（rsf 的 main 收不到
        // 命令行参数，见 BOOTSTRAP.md 的 17a 段）。属性不存在时这个 JVM 方法返回 null，
        // 而 rsf 里它的声明类型是 `string` —— 直接 `path.length()` 会在**编译产物**上抛
        // NullPointerException（解释器那条路反而看不出问题，因为解释器里 null 就是 null）。
        // 所以入口要写成 `?string` 接住再 `?:` 兜底；这一条把那个写法钉住。
        val probe = rsfLexerOutput(
            "fn pickPath() -> string {\n" +
                "    var given : ?string = System.getProperty(\"rsf.lex.nope.not.set\")\n" +
                "    rtn given ?: \"兜底值\"\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    println(pickPath())\n" +
                "    rtn\n" +
                "}\n",
        )
        assertEquals("兜底值", normalize(probe).trim(), "属性不存在时应当走 ?: 的兜底值")
    }

    /** 用 rsf 版词法器跑一份源码，返回它 dump 出来的 token 文本。 */
    private fun rsfLexerOutput(probeSource: String): String = interpretWithRsfLexer(probeSource)

    /**
     * 参照 [org.example.MainKt] 的 `cmdLex` 打印 Kotlin 版 token 流。
     *
     * **格式必须与 rsfSrc/bootstrap/Lexer.rsf 的 dumpToken 一字不差**，否则对拍没意义：
     * 行号右对齐宽 4、列号左对齐宽 4、kind 左对齐宽 12，字面量再跟 `  = 值 (类型)`。
     */
    private fun referenceTokenDump(tokens: List<org.example.complie.lexing.Token>): List<String> =
        tokens.map { t ->
            val literal = t.literal?.let { v ->
                val type = when (v) {
                    is Int -> "Int"
                    is Long -> "Long"
                    is Float -> "Float"
                    is Double -> "Double"
                    is String -> "String"
                    is Char -> "Char"
                    is Boolean -> "Boolean"
                    else -> v::class.simpleName ?: ""
                }
                "  = $v ($type)"
            } ?: ""
            "${t.line.toString().padStart(4)}:${t.column.toString().padEnd(4)} " +
                "${t.kind.name.padEnd(12)} '${t.lexeme}'$literal"
        }

    /**
     * 把 [text] 写进临时文件，让 rsf 版词法器读进来 dump，再与 [reference] 逐行对拍。
     *
     * 走文件（而不是把源码拼进 rsf 字符串字面量）是故意的：两边的输入必须是**同一串字符**，
     * 拼接就得处理转义，转义一旦出错，对拍比的就不是同一份输入了。
     */
    private fun assertTokenStreamsMatch(label: String, text: String, reference: List<String>) {
        val tmp = File.createTempFile("rsf-lex-", ".rsf")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            val probe = "fn main() -> void {\n" +
                "    var ts : Token[] = lexFile(${rsfPath(tmp)}) ?: new Token[0]\n" +
                "    if (ts.length == 0) { println(\"<词法出错或读不到文件>\") }\n" +
                "    dumpTokens(ts)\n" +
                "    rtn\n" +
                "}\n"
            val raw = interpretWithRsfLexer(probe)
            // 逐条比「token 的 dump 文本」。注意**不能**对整份输出做 trim 后再按行比：
            // 字符字面量 `'\n'`、跨行字符串的「值」里就是真的换行符，按行比会把一条 token
            // 拆成好几行，两边的行号从此错开（实测会在第 18 条报出一个假的空格差异）。
            // 所以比的是「N 条 token 的 dump 拼起来」这一个字符串。
            // 逐条输出**拼成一份文本**再比。注意不能按行比：字符字面量 `'\n'`、
            // 跨行字符串的「值」里就是真的换行符，一条 token 的 dump 会横跨好几个物理行，
            // 两边一旦在别处错开半行，按行比就会报出假的差异（定位反而更难）。
            //
            // 另外：rsf 侧是 `println`，Windows 上是 CRLF；Kotlin 侧的参照行是用 `\n` 拼的。
            // 差别只在行尾，统一成 LF 再比（比的是「每一行的内容」，不是行尾风格）。
            val rsfDump = normalize(raw).trimEnd('\n')
            val kotlinDump = normalize(reference.joinToString("\n"))

            if (rsfDump != kotlinDump) {
                val at = firstDifference(rsfDump, kotlinDump)
                val line = rsfDump.substring(0, minOf(at, rsfDump.length)).count { it == '\n' } + 1
                throw AssertionError(
                    "$label：rsf 版与 Kotlin 版的 token 流不一致（第一个不同的字节在第 $line 行附近）\n" +
                        "  Kotlin 版: ...${escapeDump(kotlinDump, at)}\n" +
                        "  rsf    版: ...${escapeDump(rsfDump, at)}",
                )
            }
            assertTrue(rsfDump.isNotEmpty(), "$label：token dump 是空的")
        } finally {
            tmp.delete()
        }
    }

    /** 两个字符串第一处不同的位置（都相同就返回较短的那个长度）。 */
    private fun firstDifference(a: String, b: String): Int {
        var i = 0
        while (i < minOf(a.length, b.length)) {
            if (a[i] != b[i]) return i
            i++
        }
        return i
    }

    /** 出错点附近的片段（控制字符转成看得见的形式），用来在断言消息里定位。 */
    private fun escapeDump(s: String, at: Int): String {
        val from = maxOf(0, at - 60)
        val to = minOf(s.length, at + 60)
        return s.substring(from, to)
            .replace("\r", "<CR>").replace("\n", "<LF>\n            ")
    }

    /** 把 rsfSrc/bootstrap/Lexer.rsf 与一段探针 main 合成一个程序，跑一遍并收集 stdout。 */
    private fun interpretWithRsfLexer(probeSource: String): String {
        val lexerFile = File(System.getProperty("user.dir"), "rsfSrc/bootstrap/Lexer.rsf")
        assertTrue(lexerFile.isFile, "找不到 rsf 版词法器: ${lexerFile.absolutePath}")
        val lexerSrc = SourceFile.of(lexerFile.path, lexerFile.readText(Charsets.UTF_8))
        val probeSrc = SourceFile.of("<rsf-lexer-probe>", probeSource)

        // 探针用的是 Lexer.rsf 里的 Token / lex / dumpTokens，跟「写在同一个文件里」一样。
        // 这里手工把两份源码合成一个 Program——等价于探针文件写 `import "Lexer.rsf"`，
        // 但不用让 Loader 去解析路径，测试因此不依赖当前目录。
        val lexerProgram = Rsf.parse(lexerSrc)
        val probeProgram = Rsf.parse(probeSrc)
        val imports = lexerProgram.imports + probeProgram.imports
        val model = Rsf.analyze(
            LoadedProgram(
                root = probeSrc,
                files = listOf(lexerSrc, probeSrc),
                functions = lexerProgram.functions + probeProgram.functions,
                importDecls = imports,
                classImports = imports.filterIsInstance<ClassImport>().map { it.path },
                sourceOfFunction = IdentityHashMap(),
                classes = lexerProgram.classes + probeProgram.classes,
                sourceOfClass = IdentityHashMap(),
                packageName = null,
            ),
        )
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

    private fun source(text: String): SourceFile = SourceFile.of("<test>", text)

    /** 把绝对路径写成 rsf 的字符串字面量（含引号）；反斜杠要转义，`\n` 之类不会被误读。 */
    private fun rsfPath(file: File): String =
        "\"" + file.absolutePath.replace("\\", "\\\\") + "\""

    // ------------------------------------------------------------------ 阶段 17b：rsf 版语法器对拍
    //
    // 口径：Kotlin 版 AST 由测试源集里的 [AstDump] 打成规范化文本（一行一个节点、
    // 不带对象地址、顺序稳定）；rsf 版（rsfSrc/bootstrap/Parser.rsf）按**同一份格式**
    // 打自己的胖节点树。两边**逐行**比，不一致就把第一处不同的行号与两侧内容打出来。

    @Test
    fun `rsf 版语法器与 Kotlin 版 AST 逐条一致`() {
        // 跑 rsfSrc/lang 下**全部** .rsf（含 multifile / interop 子目录）。
        // 用 `Rsf.parse` 而不是 `Rsf.analyze`：比的是**语法**，语义检查（类型、名字解析）
        // 不是这一阶段的事，而且分析会要求 import 真的能加载。
        val langDir = File(System.getProperty("user.dir"), "rsfSrc/lang")
        assertTrue(langDir.isDirectory, "找不到示例目录: ${langDir.absolutePath}")
        val examples = langDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".rsf") }
            .sortedBy { it.path }
            .toList()
        assertTrue(examples.size >= 8, "rsfSrc/lang 下的示例太少（${examples.size} 个），对拍没覆盖到")

        var totalLines = 0
        for (file in examples) {
            val text = file.readText(Charsets.UTF_8)
            totalLines += assertAstsMatch(
                label = file.relativeTo(File(System.getProperty("user.dir"))).path,
                text = text,
            )
        }
        // 「一致」必须建立在**真的比了很多行**之上：要是哪天 dump 退化成只打一个 PROGRAM，
        // 上面那个循环照样绿。所以这里给一个总量下限。
        assertTrue(totalLines >= 1500, "对拍总共只比了 $totalLines 行 AST，覆盖面退化了")
    }

    @Test
    fun `rsf 版语法器在刁钻语料上与 Kotlin 版 AST 逐条一致`() {
        // 示例文件覆盖的是「正常写法」。这一份把「覆盖范围」里那些边角形态逐条写出来：
        // 两种参数风格混写、两种返回子句、`-> !T`、`-> null`、主/次构造与 this() 委托、
        // 泛型实参与数组类型、两种 if、两种 for、for-in 的 step、switch/else、
        // 三目、?:、!!、成员/下标赋值、各种字面量写法、一元/二元全体优先级。
        val corpus = listOf(
            "顶层与 import" to """
                pkg a.b

                import java.lang.Math
                import "lib/util.rsf"
                import "lib/
                       math.rsf"
                module "plugins"

                pub fn f(a : int, b : string) -> int { rtn a }
                prot stc fn g() : !string { rtn "x" }
                prv fn h(xs : int[], m : Box&<int>) -> void { rtn }
                fn i() -> !Token { rtn }
                fn j() -> null { rtn }
                fn k() { rtn }
                fn alias(s : str, b : boolean, c : byte, d : char) -> int { rtn 0 }
            """.trimIndent() + "\n",

            "类与构造器" to """
                pub class Point&<T>(x0 : int, y0 : int) {
                    var x : int = x0
                    imutvar y : int = y0
                    pub fn sum() -> int { rtn x + y }
                    pub stc fn origin() -> int { rtn 0 }
                    constructor() { this(0, 0) }
                    constructor(v : int, w : int) {
                        this(v, w)
                        x = v
                    }
                }

                class Plain {
                    var v : string = "?"
                    fn get() -> string { rtn v }
                }

                class NoCtor { var n : int = 1 }
            """.trimIndent() + "\n",

            "枚举与 switch" to """
                enum Color { RED, GREEN, BLUE, }
                pub enum Empty { ONLY }

                fn name(c : Color) -> string {
                    switch (c) {
                        Color.RED : rtn "r"
                        Color.GREEN : {
                            var t = 1
                            rtn "g"
                        }
                        Color.BLUE : rtn "b"
                    }
                }

                fn partial(c : Color) -> void {
                    switch (c) {
                        Color.RED : println("r")
                        else : println("other")
                    }
                    switch (c) { Color.RED : rtn }
                    switch (c) { ; ; Color.RED : rtn ; }
                    rtn
                }
            """.trimIndent() + "\n",

            "语句全家桶" to """
                fn stmts(a : int[], p : Point) -> int {
                    var x : int = 0
                    imutvar y = 1
                    var z : Box&<int>[] = new Box&<int>[2]
                    var q : int[][] = new int[1][1]
                    x = 2
                    p.x = 3
                    a[0] = 4
                    p.items[1].z = 5
                    l1: goto l2
                    l2: goto l1
                    break
                    continue
                    {
                        var inner = 1
                    }
                    ;
                    rtn x
                }
            """.trimIndent() + "\n",

            "if while for" to """
                fn branches(n : int) -> int {
                    if (n > 0) { rtn 1 } else if (n < 0) { rtn -1 } else { rtn 0 }
                }

                fn branches2(n : int) -> int {
                    if n > 0 : rtn 1
                    if n > 0 : { rtn 1 }
                    if n ? 1 : 2
                    while (n > 0) { n = n - 1 }
                    while n > 0 : n = n - 1
                    rtn 0
                }

                fn loops(n : int) -> void {
                    for (var i = 0; i < 9; i = i + 1) { }
                    for (var i = 0; ; i = i + 1) { break }
                    for (;;) { break }
                    for (var i = 0, j = 1; i < 9; i = i + 1, j = j + 1) { continue }
                    for (i in 0..9) { }
                    for (i in 0..9 step 2) { }
                    for (i in 10..0 step -1) { }
                    for (i in 0..9 step 1) { for (j in 0..9) { } }
                    rtn
                }
            """.trimIndent() + "\n",

            "fail 与 try" to """
                fn boom() -> !int {
                    try { fail "boom" } catch (e : string) { rtn 1 }
                    try { fail "x" } catch (e string) { rtn 2 }
                    try { fail "y" } catch (e : string) { rtn 3 }
                    rtn 0
                }

                fn nest() -> !void {
                    try {
                        try { fail "in" } catch (e : string) { println(e) }
                    } catch (outer : string) { println(outer) }
                    rtn
                }
            """.trimIndent() + "\n",

            "表达式全家桶" to """
                fn expr(a : int[], o : Obj, s : string) -> int {
                    var i = 1 + 2 * 3 - 4 / 5 % 2
                    var u = -i
                    var v = !true
                    var b = a[0] == 1 != false
                    var c = i < 2 && i > 0 || !(i <= 3)
                    var d = i >= 1
                    var e = if s.length() > 0 ? s.charAt(0) : 'x'
                    var f = a ?: 0
                    var g = o.f!!
                    var h = o.m(1, 2).n[3]
                    var j = new Box&<int>(0)
                    var k = Box&<int>(0)
                    var l = Box&<Box&<int>>(0)
                    var m = new int[5]
                    var n = new int[3][4]
                    var p = new Point[2]
                    var q = new Box&<int>[2]
                    var r : ?string = null
                    var t = this
                    var w = if i > 0 ? 1 : 2
                    var x = if i > 0 ? (1 + 2) : (3 - 4)
                    rtn i
                }
            """.trimIndent() + "\n",

            "字面量写法" to """
                fn literals() -> void {
                    var a = 0
                    var b = 007
                    var c = 1_000_000
                    var d = 2147483647
                    var e = 9000000000L
                    var f = 7L
                    var g = 1.5f
                    var h = 0.1f
                    var i = 3.14
                    var j = 2.5D
                    var k = 7d
                    var l = 3.141592653589793238
                    var m = 'a'
                    var n = '\n'
                    var o = '\u0041'
                    var p = '\''
                    var q = '\\'
                    var r = "hello world"
                    var s = "带 空格 和 制表	的字符串"
                    var t = ""
                    var u = true
                    var v = false
                    var w = null
                    rtn
                }
            """.trimIndent() + "\n",

            // 字符串里真的是换行（字符串允许跨行是基准行为之一）
            "跨行字符串与注释" to "fn multi() -> void {\n" +
                "    /* 块注释\n" +
                "       跨两行 */\n" +
                "    var s = \"第一行\n" +
                "第二行\"\n" +
                "    // 行注释\n" +
                "    rtn\n" +
                "}\n",

            // 阶段 20：`interface` / `override` / `:` 父类型子句（类与接口共用同一个子句解析）。
            // 覆盖：抽象方法（只写签名、不打 BLOCK）、默认方法（写体）、override（类体与接口体
            // 都能写，且与权限修饰词/static 混写、顺序随意）、空接口、泛型接口、接口继承接口、
            // 类实现多个父类型（含泛型实参）、方法后面可选的分号。
            "接口与父类型子句" to """
                pub interface Greeter {
                    fn greet(name : string) -> string
                    fn loud(name : string) -> string { rtn "HI" }
                }

                interface Empty { }

                interface Box&<T> : Greeter {
                    override fn greet(name : string) -> string
                    fn get() -> T
                }

                interface Semi {
                    fn p() -> void ;
                }

                class Impl(x0 : int) : Greeter, Box&<int> {
                    var x : int = x0
                    pub override fn greet(name : string) -> string { rtn name }
                    override pub fn get() -> int { rtn x }
                    override stc fn make() -> int { rtn 1 }
                }
            """.trimIndent() + "\n",

            // 阶段 21：注解（解析器侧）。语法能收、AST 有据、**语义仍忽略**。
            // 空体 / 空括号两种写法在 AST 里分不出（都是空元素表）；注解声明可带权限修饰词；
            // 元素表允许尾逗号，元素类型走 `名字 : 类型` 的后缀风格。
            "注解声明（空体与元素表）" to """
                annotation Marker { }

                annotation Marker2() { }

                pub annotation Loud {
                }

                prv annotation Quiet() { }

                annotation Full(value : string = "x", count : int = 0, flag : bool = true,
                                ratio : double = -1.5, c : char = 'a', l : long = 3,
                                f : float = 1.5, n : ?string = null,) { }

                annotation NoDefault(a : int, b : ?string) { }
            """.trimIndent() + "\n",

            // 元注解（注解声明自己也能被标注，多个按书写顺序保留；修饰词可以与注解混写）、
            // 限定名（`.` 与 `::` 都拼成点分形式），以及三种伴生实体：具名带父接口 / 匿名 /
            // 自己带注解（**收下但语义层忽略**，AST 里根本没有这个字段）。
            "元注解与伴生实体" to """
                @Meta
                annotation M { }

                @Meta @Other("x", count = 3)
                annotation N { }

                @a.b @Outer::Inner(1)
                annotation Q { }

                @Meta pub annotation Qual { }

                annotation WithCompanion {
                    companion entity Handlers : Runnable {
                        var x : int = 1
                        fn run() -> void { rtn }
                        constructor() { }
                    }
                }

                annotation AnonCompanion {
                    companion entity { var y : int = 2 }
                }

                annotation AnnotatedCompanion {
                    @Marker companion entity { }
                }
            """.trimIndent() + "\n",

            // 注解使用点：类 / 接口 / 函数 / 字段都能标；位置式与具名式实参混写；
            // 同一目标上的多个注解按书写顺序保留。抽象方法（不打 BLOCK）也能标——
            // 注解列表一律追加在所有孩子之后，位置索引不受影响。
            "注解使用点" to """
                @Marker("hi", count = 3)
                class C {
                    @Marker var f : int = 0
                    @Marker() @Other fn g() -> void { rtn }
                }

                @Marker
                interface I {
                    @Marker fn m() -> void
                    @Other() fn n() -> void { rtn }
                }

                @Marker @Other("x")
                fn h() -> void { rtn }

                @Marker
                pub fn k() -> void { rtn }

                @Marker()
                fn j() -> void { rtn }
            """.trimIndent() + "\n",
            "嵌套类型名（注解名::伴生名）" to """
                annotation Marker {
                    companion entity Impl : Runnable {
                        fn run() -> void {
                            rtn
                        }
                    }
                }

                class Uses {
                    @Marker var holder : Marker::Impl = new Marker()
                    var list : Marker::Impl[] = new Marker::Impl[2]
                    fn make(m : Marker::Impl) -> void {
                        m.run()
                        rtn
                    }
                }

                fn take(v : Marker::Impl) -> void {
                    v.run()
                    rtn
                }
            """.trimIndent() + "\n",
        )

        var corpusLines = 0
        for ((label, text) in corpus) {
            // 先确认 Kotlin 版**收得下**这份语料：语料本身写错的话，这条测试就变成
            // 「两边一起报错」，什么都验不到。
            val program = try {
                Rsf.parse(source(text))
            } catch (e: RsfError) {
                throw AssertionError("刁钻语料 <$label> 在 Kotlin 版就不合法，先修语料：${e.detail}", e)
            }
            corpusLines += assertAstsMatch(label = "<$label>", text = text, reference = AstDump.dump(program))
        }
        // 与上一条同理：语料必须真的撑起足够多的节点（当前实测约 730 行）
        assertTrue(corpusLines >= 600, "刁钻语料只比了 $corpusLines 行 AST，覆盖面退化了")
    }

    @Test
    fun `rsf 版语法器对非法输入与 Kotlin 版一样判失败`() {
        // **这一条只要求「两边都判定为失败」，不要求错误消息逐字相同。**
        //
        // 原因：Kotlin 版是遇错即停、消息写给用户看（每一处报错都带上下文、还常常给
        // 「应该怎么写」的建议），rsf 版没有异常可用（`fail` 会把整个程序停下来），
        // 走的是「记一个失败标志 + 占位节点继续走」那条路。逐字复刻几十条消息既做
        // 不到、也没必要——**语法**边界一致才是这一阶段的目标，「错误消息体验」
        // 是下一批（17c）的活。这里只钉两件事：
        //   1. 凡 Kotlin 版判失败的输入，rsf 版也必须判失败（不能悄悄吞掉）；
        //   2. rsf 版的失败必须带消息（不能是「静默失败」）。
        val bad = listOf(
            "fn f( -> int { rtn 1 }\n",
            "fn f() -> int { var = 1 }\n",
            "class C { var x = 1 }\n",
            "enum E { }\n",
            "fn f() -> int { switch (x) { } rtn 1 }\n",
            "fn f() -> int { if (x) rtn 1 }\n",
            "var a = b | c\n",
            "fn f() -> int { rtn 1 } extra\n",
            "fn f() -> int { rtn 1\n",
            "fn f() -> int { a[0 = 1 }\n",
            "class\n",
            "module str\n",
            "pkg\nfn main() -> void { rtn }\n",
            "fn main() -> void { try { rtn } rtn }\n",
            "pub constructor() { }\n",
            "fn main() -> void { fail }\n",
            "fn main() -> void { switch (x) { default : rtn } }\n",
            "fn main() -> void { var x : = 1 }\n",
            "class C { constructor(v : int) -> void { } }\n",
            "fn main() -> int { rtn 1 -> int }\n",

            // 阶段 20 的语法边界。前半段是父类型子句四条必须失败的写法，中间是接口体的
            // 四条禁令，最后两条钉 `override` 的**位置**：顶层函数、构造器上都不准写
            // （Kotlin 版靠 parseFuncDecl/parseConstructor 的开关拦，rsf 版靠 overrideOk）。
            "class C : { }\n",
            "class C : ?Greeter { }\n",
            "class C : Greeter? { }\n",
            "class C : int { }\n",
            "class C : int[] { }\n",
            "interface I : { }\n",
            "interface I { var x : int = 1 }\n",
            "interface I { constructor() { } }\n",
            "interface I { stc fn f() -> void { rtn } }\n",
            "interface I { prv fn f() -> void { rtn } }\n",
            "interface I { fn f() -> void { rtn } extra }\n",
            "override fn f() -> void { rtn }\n",
            "class C { override constructor() { } }\n",

            // 阶段 21 的语法边界。注解**不是类**：体里只能有（至多一个）companion entity，
            // 元素重名、Java 风格的「类型 名字」元素写法、非字面量默认值、`@` 写在
            // 参数 / 语句 / 枚举声明上，都要在**语法**层被拒——Kotlin 版靠
            // parseAnnotationDecl / parseParam / parseStmtInner 里的显式禁令，rsf 版靠同一批判据。
            "annotation M { fn f() -> void { rtn } }\n",
            "annotation M { var x : int = 1 }\n",
            "annotation M { constructor() { } }\n",
            "annotation M { companion entity A { } companion entity B { } }\n",
            "annotation M { @Meta companion entity A { } companion entity B { } }\n",
            "annotation M(value : string, value : int) { }\n",
            "annotation M(string value) { }\n",
            "annotation M(v : string = RED) { }\n",
            "annotation M(v : int = 1 + 2) { }\n",
            "annotation M { companion entity int { } }\n",
            "annotation M { companion { } }\n",
            "annotation M {\n",
            "fn @Marker f() -> void { rtn }\n",
            "fn f(@Marker x : int) -> void { rtn }\n",
            "fn f() -> void { @Marker var x : int = 1 }\n",
            "@Marker enum E { A }\n",
            "pub @Meta annotation M { }\n",
            "companion entity { }\n",
            // 嵌套类型名（`注解名::伴生名`）：内置类型后面不能跟 `::`、只允许一层、`::` 后面要有名字。
            "fn f() -> void { var x : int::Impl = new Marker() rtn }\n",
            "fn f() -> void { var x : A::B::C = new Marker() rtn }\n",
            "fn f() -> void { var x : Marker:: = new Marker() rtn }\n",
        )

        for (src in bad) {
            val kotlinRejected = try {
                Rsf.parse(source(src))
                false
            } catch (e: RsfError) {
                true
            }
            assertTrue(kotlinRejected, "Kotlin 版应当拒绝这段源码（语料本身要挑得准）：\n$src")

            val rsfOut = normalize(rsfAstDump(src)).trim()
            assertTrue(
                rsfOut.startsWith("<解析失败>"),
                "rsf 版也应当判定为失败：\n$src\nrsf 版实际输出：\n$rsfOut",
            )
        }

        // 消息不要求一致，但**必须有**：挑一条确定会失败的输入，把两边的消息都取出来，
        // 只断言「都不为空」，并显式记下「刻意不比它们相等」。
        val sample = "enum E { }\n"
        val kotlinMessage = assertFailsWith<RsfError> { Rsf.parse(source(sample)) }.detail
        val rsfMessage = normalize(
            rsfParserProbe(
                "fn main() -> void {\n" +
                    "    println(parseErrorMessage(\"enum E { }\\n\"))\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        ).trim()
        assertTrue(kotlinMessage.isNotEmpty(), "Kotlin 版的消息不该是空的")
        assertTrue(rsfMessage.isNotEmpty(), "rsf 版的消息不该是空的")
        assertTrue(
            kotlinMessage.contains("至少要有一个值"),
            "Kotlin 版对空枚举的说法：$kotlinMessage",
        )
        // 注意：这里**故意不**断言两边消息相等；只要两边都判失败、都给了消息就够了。
        // 报告里说明了这条边界，下一批（17c）若要把消息也对齐，标准要另立。
    }

    @Test
    fun `rsf 版语法器的入口编译执行与解释执行一致`() {
        val file = parserCliFile()
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)

        val example = File(System.getProperty("user.dir"), "rsfSrc/lang/enums.rsf")
        assertTrue(example.isFile, "找不到示例: ${example.absolutePath}")
        val tmp = File.createTempFile("rsf-ast-cli-", ".rsf")
        try {
            tmp.writeText(example.readText(Charsets.UTF_8), Charsets.UTF_8)
            withAstFileProperty(tmp.absolutePath) {
                val interpreted = normalize(interpretModel(model))
                val outDir = tempDir()
                val built = Rsf.build(model, loaded.root, "ParserCli", outDir)
                val (loader, name) = programLoader(built.classFiles, outDir)
                val compiled = normalize(runMain(loader.loadClass(name)))
                assertEquals(
                    interpreted,
                    compiled,
                    "ParserCli 编译执行与解释执行的 AST dump 必须逐字节一致",
                )
                assertTrue(interpreted.startsWith("PROGRAM"), interpreted.take(200))
                assertTrue(interpreted.contains("ENUMS count=2"), interpreted.take(600))
            }
        } finally {
            tmp.delete()
        }
    }

    /** rsfSrc/bootstrap/ParserCli.rsf（rsf 版语法器的命令行入口）。 */
    private fun parserCliFile(): File {
        val file = File(System.getProperty("user.dir"), "rsfSrc/bootstrap/ParserCli.rsf")
        assertTrue(file.isFile, "找不到 rsf 版语法器入口: ${file.absolutePath}")
        return file
    }

    /**
     * 让 rsf 版语法器把 [text] 打成 AST dump。
     *
     * 走的是**真的入口**（ParserCli.rsf 读 `-Drsf.ast.file`），不是合成一个探针程序：
     * 这样对拍用的就是「用户会跑的那条路」，而不是一条只有测试才知道的近路。
     * 模型只解析一次（`Rsf.load` + `Rsf.analyze` 很贵），每条语料只换一次系统属性。
     */
    private fun rsfAstDump(text: String): String {
        val tmp = File.createTempFile("rsf-ast-", ".rsf")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            return withAstFileProperty(tmp.absolutePath) {
                normalize(interpretModel(parserModel()))
            }
        } finally {
            tmp.delete()
        }
    }

    /** 与 [rsfAstDump] 同一套机制，但跑的是临时拼出来的一段 rsf 源码（探针）。 */
    private fun rsfParserProbe(probeSource: String): String {
        val dir = tempDir()
        // 把两个库复制过去，让 Loader 能按相对路径解析 import（不改仓库里的文件）
        val bootstrap = File(System.getProperty("user.dir"), "rsfSrc/bootstrap")
        for (name in listOf("Lexer.rsf", "Parser.rsf")) {
            File(bootstrap, name).copyTo(File(dir, name), overwrite = true)
        }
        val probe = File(dir, "Probe.rsf")
        probe.writeText("import \"Parser.rsf\"\n\n" + probeSource, Charsets.UTF_8)
        val loaded = Rsf.load(probe)
        return interpretModel(Rsf.analyze(loaded))
    }

    private val cachedParserModel by lazy { Rsf.analyze(Rsf.load(parserCliFile())) }

    private fun parserModel(): org.example.complie.sema.SemanticModel = cachedParserModel

    /** 临时把 `rsf.ast.file` 设成 [path]，跑完还原（测试之间不能互相影响）。 */
    private fun <T> withAstFileProperty(path: String, body: () -> T): T {
        val saved = System.getProperty("rsf.ast.file")
        System.setProperty("rsf.ast.file", path)
        try {
            return body()
        } finally {
            if (saved == null) System.clearProperty("rsf.ast.file") else System.setProperty("rsf.ast.file", saved)
        }
    }

    /** 跑一个已经分析好的模型的 main，收集 stdout（UTF-8）。 */
    private fun interpretModel(model: org.example.complie.sema.SemanticModel): String {
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

    /**
     * 两份 AST dump 逐行比。不一致时把**第一处不同的行号与两侧内容**打出来。
     *
     * 参照是 [AstDump] 打在 Kotlin 版 AST 上的文本（行尾 `\n`）；rsf 侧是它的
     * `println`（Windows 上是 CRLF），所以两边都先做 `\r\n` → `\n`。
     */
    private fun assertAstsMatch(label: String, text: String, reference: String? = null): Int {
        val kotlinDump = normalize(reference ?: AstDump.dump(Rsf.parse(source(text))))
        val rsfDump = normalize(rsfAstDump(text))

        val ktLines = kotlinDump.split("\n")
        val rsfLines = rsfDump.split("\n")
        var i = 0
        while (i < minOf(ktLines.size, rsfLines.size) && ktLines[i] == rsfLines[i]) i++

        if (i < ktLines.size || i < rsfLines.size) {
            val from = maxOf(0, i - 3)
            val context = (from until i).joinToString("") { "      ${it + 1}: ${ktLines[it]}\n" }
            throw AssertionError(
                "$label：rsf 版与 Kotlin 版的 AST 不一致（第一个不同的行是第 ${i + 1} 行）\n" +
                    "$context" +
                    "  Kotlin 版 ${i + 1}: ${ktLines.getOrNull(i) ?: "<没有这一行>"}\n" +
                    "  rsf    版 ${i + 1}: ${rsfLines.getOrNull(i) ?: "<没有这一行>"}\n" +
                    "  （Kotlin 版共 ${ktLines.size} 行，rsf 版共 ${rsfLines.size} 行）",
            )
        }
        assertTrue(ktLines.isNotEmpty(), "$label：两边的 dump 都是空的")
        return ktLines.size
    }

    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun lines(s: String): List<String> = normalize(s).trim().lines()

    private fun tempDir(): File = File.createTempFile("rsf-classes", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    /**
     * 把一批 .class 都 define 进同一个类加载器，返回 (类加载器, 程序类的二进制名)。
     *
     * 顺序有讲究：先把用户类 define 完，最后才 define 程序类——程序类的方法体里
     * 引用着用户类（`new Point` / `INVOKEVIRTUAL Point.sum`），先定义被依赖的那些更稳。
     * （`Rsf.build` 保证程序类排在 classFiles 第一个。）
     */
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

    /** `out/app/main.class` → `app.main`（`defineClass` 要的是二进制名，不是内部名）。 */
    private fun binaryName(file: File, dir: File): String =
        file.relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')

    /** 编译（程序类 + 每个用户类）并加载，返回 (类加载器, 程序类)。 */
    private fun compileAndLoad(text: String, className: String): Pair<ClassLoader, Class<*>> {
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(source(text)), source(text), className, dir)
        val (loader, programName) = programLoader(out.classFiles, dir)
        return loader to loader.loadClass(programName)
    }

    /** 反射调 main(String[]) 并捕获输出（编译产物的 stdout）。 */
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

    private fun runCompiledClasses(text: String, className: String = "RsfClassParity"): String {
        val (_, cls) = compileAndLoad(text, className)
        return runMain(cls)
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

    private fun compileToBytes(text: String, className: String = "RsfParity"): ByteArray {
        val model = Rsf.analyze(source(text))
        val dir = File.createTempFile("rsf-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val out = Rsf.build(model, source(text), className, dir)
        return out.classFile.readBytes()
    }

    private fun runCompiled(text: String): String {
        val className = "RsfParity"
        val bytes = compileToBytes(text, className)
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        val cls = loader.define(className, bytes)

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

    // ================================================================== 阶段 17c：rsf 版解释器对拍
    //
    // 口径：同一份 `.rsf` 源码，Kotlin 版 `Interpreter`（= `rsf run` 走的那条路）的 stdout
    // 与**rsf 版解释器**（`rsfSrc/bootstrap/Interp.rsf`）的 stdout **逐字节相同**。
    //
    // rsf 版解释器本身是 rsf 源码，所以这里跟 17a/17b 一样走「把库合成一个程序、
    // 用 Kotlin 解释器跑它」那条路（`interpProbeFile()`），不另起 JVM 进程：
    // 输入路径通过系统属性 `rsf.interp.probe` 传给探针，一次分析、多次跑。
    //
    // files.rsf 也进这条测试：它会读写系统临时目录（Kotlin 版与 rsf 版用的路径、
    // 读回来的字符数、错误消息长度都是一样的），两端都在跑完把临时文件删掉，
    // 不会互相干扰、也不给仓库留垃圾。

    @Test
    fun `rsf 版解释器与 Kotlin 版输出逐字节一致`() {
        val dir = File(System.getProperty("user.dir"), "rsfSrc/lang")
        assertTrue(dir.isDirectory, "找不到示例目录: ${dir.absolutePath}")
        val names = listOf("main", "class", "generics", "errors", "stdlib", "arrays", "files", "enums", "inherit")
        var comparedLines = 0
        for (name in names) {
            val file = File(dir, "$name.rsf")
            assertTrue(file.isFile, "找不到示例: ${file.absolutePath}")
            val text = file.readText(Charsets.UTF_8)

            val expected = runInterpreted(text)
            val (actual, stderr) = rsfInterpRun(file)
            // **逐字节**：两边都是同一个平台上的 `PrintStream(.., "UTF-8")`，
            // 连行尾都该一样，所以这里不做 `\r\n` 归一化
            assertEquals(expected, actual, "示例 $name 的 stdout 与 Kotlin 版不一致（rsf 侧 stderr: $stderr）")
            assertTrue(stderr.isEmpty(), "示例 $name 不该往 stderr 上写东西：$stderr")
            assertTrue(expected.isNotEmpty(), "示例 $name 的输出是空的，对拍没有意义")
            val n = expected.trimEnd('\n').lines().size
            assertTrue(n >= 5, "示例 $name 只比了 $n 行，覆盖太少")
            comparedLines += n
        }
        // 总量下限：防止「一致」是建立在「几乎没比几行」之上（覆盖悄悄退化的典型形态
        // 是某个示例被跳过、或者解释器早早对空输出返回）。八个示例合计 222 行，
        // 少一个最小的（class.rsf 14 行）也还在这条线之上，所以这里同时按「单例 ≥ 5 行」
        // 守着：两道闸门一起，跳掉任何一例都会红。
        assertTrue(comparedLines >= 200, "对拍总量太小（只有 $comparedLines 行），覆盖可能悄悄退化了")
    }

    /**
     * 自己写的刁钻语料：递归、数组越界与负长度、`&&`/`||` 的短路副作用、枚举 `switch` 分派、
     * 构造器重载与 `this(...)` 委托、字符串与 char、控制流全家桶、泛型集合与对象数组。
     *
     * 示例程序覆盖不到这些角落（示例是「给人看的」，这里是「给对拍看的」）。
     */
    @Test
    fun `rsf 版解释器在刁钻程序上与 Kotlin 版一致`() {
        val cases = listOf(
            "递归与数值提升" to trickyRecursion,
            "数组越界与负长度" to trickyArrays,
            "逻辑短路副作用" to trickyShortCircuit,
            "枚举与 switch 分派" to trickyEnums,
            "构造器重载与委托" to trickyConstructors,
            "字符串与错误处理" to trickyStrings,
            "控制流全家桶" to trickyControlFlow,
            "泛型集合与对象数组" to trickyGenerics,
        )
        var comparedLines = 0
        for ((label, text) in cases) {
            val expected = runInterpreted(text)
            val (actual, stderr) = rsfInterpRunText(label, text)
            assertEquals(expected, actual, "$label：stdout 与 Kotlin 版不一致（rsf 侧 stderr: $stderr）")
            assertTrue(stderr.isEmpty(), "$label：不该往 stderr 上写东西：$stderr")
            assertTrue(expected.trimEnd('\n').lines().size >= 5, "$label：输出太短，对拍没覆盖面")
            comparedLines += expected.trimEnd('\n').lines().size
        }
        assertTrue(comparedLines >= 80, "刁钻语料的对拍总量太小（只有 $comparedLines 行）")
    }

    /**
     * **绝不允许静默算错**：本解释器还不支持的语法/语义，必须报出一条看得懂的错误，
     * 而不是「什么都不做」或者「算出一个看起来像的值」。
     *
     * 这些用例在 Kotlin 版那边是**合法**的（能跑出输出），差别是 rsf 版解释器的已知缺口，
     * 所以这里只要求：rsf 侧必须往 stderr 给出消息、stdout 不能装作跑成功了。
     */
    @Test
    fun `rsf 版解释器对不支持的语法报清楚错误`() {
        val cases = listOf(
            // JVM 互操作：本阶段只做了 ArrayList / File / System.getProperty 三个口子
            "unknown_jvm_static" to
                "import java.lang.Math\n\nfn main() -> void {\n    println(Math.max(1, 2))\n    rtn\n}\n",
            "hashmap_construction" to
                "import java.util.HashMap\n\nfn main() -> void {\n" +
                "    var m : HashMap&<string, string> = new HashMap&<string, string>()\n" +
                "    m.put(\"a\", \"b\")\n    rtn\n}\n",
            "unknown_jvm_construction" to
                "import java.util.TreeMap\n\nfn main() -> void {\n" +
                "    var m = new TreeMap&<string, string>()\n    rtn\n}\n",
            // 顶层变量：两边的**语法分析器**都拒（这条对拍的是「都判失败」）
            "toplevel_var" to
                "var g : int = 1\n\nfn main() -> void {\n    println(g)\n    rtn\n}\n",
        )
        for ((label, text) in cases) {
            val (stdout, stderr) = rsfInterpRunText(label, text)
            assertTrue(
                stderr.isNotEmpty(),
                "$label：rsf 版解释器必须给出错误消息，实际什么都没说（stdout=$stdout）",
            )
            assertTrue(
                stderr.contains("17c 解释器暂不支持") ||
                    stderr.contains("解析失败") ||
                    stderr.contains("运行期错误"),
                "$label：错误消息看不出是什么问题：$stderr",
            )
        }

        // 反面：能支持的语法**不许**报「暂不支持」（否则上一条测试就变成了自我实现的预言）
        val (okOut, okErr) = rsfInterpRunText("supported", "fn main() -> void {\n    println(\"fine\")\n    rtn\n}\n")
        assertTrue(okErr.isEmpty(), "受支持的语法不该报错：$okErr")
        assertEquals("fine${System.lineSeparator()}", okOut)
    }

    @Test
    fun `rsf 版解释器的入口编译执行与解释执行一致`() {
        val file = interpCliFile()
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)

        val example = File(System.getProperty("user.dir"), "rsfSrc/lang/arrays.rsf")
        assertTrue(example.isFile, "找不到示例: ${example.absolutePath}")
        val tmp = File.createTempFile("rsf-interp-cli-", ".rsf")
        try {
            tmp.writeText(example.readText(Charsets.UTF_8), Charsets.UTF_8)
            withInterpCliProperty(tmp.absolutePath) {
                val interpreted = normalize(interpretModel(model))
                val outDir = tempDir()
                val built = Rsf.build(model, loaded.root, "InterpCli", outDir)
                val (loader, name) = programLoader(built.classFiles, outDir)
                val compiled = normalize(runMain(loader.loadClass(name)))
                assertEquals(
                    interpreted,
                    compiled,
                    "InterpCli 编译执行与解释执行的 stdout 必须逐字节一致",
                )
                assertTrue(interpreted.contains("数组下标越界"), interpreted.take(300))
            }
        } finally {
            tmp.delete()
        }
    }

    /** rsfSrc/bootstrap/InterpCli.rsf（rsf 版解释器的命令行入口）。 */
    private fun interpCliFile(): File {
        val file = File(System.getProperty("user.dir"), "rsfSrc/bootstrap/InterpCli.rsf")
        assertTrue(file.isFile, "找不到 rsf 版解释器入口: ${file.absolutePath}")
        return file
    }

    /**
     * 只在测试里存在的探针程序：把 bootstrap 那三个库当库 import 进来，读 `-Drsf.interp.probe`
     * 指的文件，交给 `runSource` 跑。
     *
     * 为什么不用 `InterpCli.rsf` 本体：它只认 `-Drsf.interp.file` 并且**出错就 `System.exit(1)`**，
     * 在测试里会把整个 JVM 干掉；探针把错误落到 stderr 上，测试才能断言「报了清楚的错」。
     */
    private fun interpProbeFile(): File {
        val dir = tempDir()
        val bootstrap = File(System.getProperty("user.dir"), "rsfSrc/bootstrap")
        for (name in listOf("Lexer.rsf", "Parser.rsf", "Interp.rsf")) {
            val src = File(bootstrap, name)
            assertTrue(src.isFile, "找不到 rsf 版解释器的库: ${src.absolutePath}")
            src.copyTo(File(dir, name), overwrite = true)
        }
        val probe = File(dir, "InterpProbe.rsf")
        probe.writeText(
            "import \"Interp.rsf\"\n" +
                "\n" +
                "import java.lang.System\n" +
                "\n" +
                "fn main() -> void {\n" +
                "    var given : ?string = System.getProperty(\"rsf.interp.probe\")\n" +
                "    var path = given ?: \"\"\n" +
                "    var text = \"\"\n" +
                "    var ok = false\n" +
                "    try {\n" +
                "        text = readText(path)!!\n" +
                "        ok = true\n" +
                "    } catch (readError : string) {\n" +
                "        ok = false\n" +
                "    }\n" +
                "    if (!ok) {\n" +
                "        System.err.println(\"运行期错误: 读不到探针输入 \" + path)\n" +
                "        rtn\n" +
                "    }\n" +
                "    try {\n" +
                "        runSource(text)!!\n" +
                "    } catch (e : string) {\n" +
                "        if (isSignal(e)) {\n" +
                "            System.err.println(\"运行期错误: 内部信号 \" + signalKind(e))\n" +
                "        } else {\n" +
                "            System.err.println(\"运行期错误: \" + e)\n" +
                "        }\n" +
                "        rtn\n" +
                "    }\n" +
                "    rtn\n" +
                "}\n",
            Charsets.UTF_8,
        )
        return probe
    }

    private val cachedInterpProbeModel by lazy { Rsf.analyze(Rsf.load(interpProbeFile())) }

    /** 临时把 `rsf.interp.probe` 设成 [path]，跑完还原。 */
    private fun <T> withInterpProbeProperty(path: String, body: () -> T): T {
        val saved = System.getProperty("rsf.interp.probe")
        System.setProperty("rsf.interp.probe", path)
        try {
            return body()
        } finally {
            if (saved == null) System.clearProperty("rsf.interp.probe") else System.setProperty("rsf.interp.probe", saved)
        }
    }

    /** 同 [withInterpProbeProperty]，但设的是入口自己读的那个属性名。 */
    private fun <T> withInterpCliProperty(path: String, body: () -> T): T {
        val saved = System.getProperty("rsf.interp.file")
        System.setProperty("rsf.interp.file", path)
        try {
            return body()
        } finally {
            if (saved == null) System.clearProperty("rsf.interp.file") else System.setProperty("rsf.interp.file", saved)
        }
    }

    /** 让 rsf 版解释器跑一个文件，返回 (stdout, stderr)，两边都是 UTF-8、不归一化行尾。 */
    private fun rsfInterpRun(file: File): Pair<String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outPs = PrintStream(outBuf, true, "UTF-8")
        val errPs = PrintStream(errBuf, true, "UTF-8")
        withInterpProbeProperty(file.absolutePath) {
            val savedOut = System.out
            val savedErr = System.err
            System.setOut(outPs)
            System.setErr(errPs)
            try {
                Interpreter(cachedInterpProbeModel, outPs).runEntry()
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
            }
        }
        outPs.flush()
        errPs.flush()
        return outBuf.toString("UTF-8") to errBuf.toString("UTF-8")
    }

    /** 同 [rsfInterpRun]，但输入是一段源码（写进临时文件再喂给探针）。 */
    private fun rsfInterpRunText(label: String, text: String): Pair<String, String> {
        val tmp = File.createTempFile("rsf-interp-$label-", ".rsf")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            return rsfInterpRun(tmp)
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ 刁钻语料

    private val trickyRecursion = """
        // 递归 / 整数与浮点提升 / 溢出回绕
        fn fact(n : int) -> int {
            if (n <= 1) { rtn 1 }
            rtn n * fact(n - 1)
        }

        fn ack(m : int, n : int) -> int {
            if (m == 0) { rtn n + 1 }
            if (n == 0) { rtn ack(m - 1, 1) }
            rtn ack(m - 1, ack(m, n - 1))
        }

        fn main() -> void {
            println("fact(10) = " + fact(10))
            println("ack(2, 3) = " + ack(2, 3))
            println("10 / 3 = " + (10 / 3))
            println("-7 / 2 = " + (0 - 7) / 2)
            println("-7 % 2 = " + (0 - 7) % 2)
            println("7 % -2 = " + 7 % (0 - 2))
            println("10 / 3.0d = " + (10 / 3.0d))
            println("10 / 3.0f = " + (10 / 3.0f))
            println("1 + 2L = " + (1 + 2L))
            println("1 + 2L + 0.5 = " + (1 + 2L + 0.5))
            println("大数加 = " + (2000000000 + 2000000000))
            println("大数乘 = " + (100000 * 100000))
            var big : long = 9000000000L
            println("long 加 = " + (big + big))
            var b : byte = 100
            println("b + b = " + (b + b))
            println("b * 3 = " + (b * 3))
            var c : char = 'A'
            println("c + 1 = " + (c + 1))
            println("charToInt = " + charToInt(c))
            rtn
        }
    """.trimIndent()

    private val trickyArrays = """
        // 数组：越界 / 负长度 / 多维 / 引用语义 / 默认值
        fn make(n : int) -> int[] {
            rtn new int[n]
        }

        fn fill(a : int[], n : int) -> void {
            for (var i = 0; i < n; i = i + 1) {
                a[i] = i * i
            }
            rtn
        }

        fn main() -> void {
            var a = new int[5]
            fill(a, 5)
            println("a[0]=" + a[0] + " a[4]=" + a[4] + " length=" + a.length)

            try {
                println(a[5])
            } catch (e : string) {
                println("越界读：" + e)
            }
            try {
                a[0 - 1] = 9
            } catch (e : string) {
                println("越界写：" + e)
            }
            println("越界写之后 a[0] 还是 " + a[0])

            try {
                var bad = new int[0 - 3]
                println("不该到这 " + bad.length)
            } catch (e : string) {
                println("负长度：" + e)
            }

            var grid = new int[2][3]
            for (var r = 0; r < 2; r = r + 1) {
                for (var c = 0; c < 3; c = c + 1) {
                    grid[r][c] = r * 10 + c
                }
            }
            println("grid[1][2] = " + grid[1][2] + "，grid.length = " + grid.length)
            println("grid[0].length = " + grid[0].length)

            var names = new string[3]
            println("string 数组默认值 = " + names[0])
            names[1] = "rain"
            println("names[1] = " + names[1])

            var made = make(2)
            made[0] = 42
            println("函数返回数组 = " + made[0] + "/" + made.length)
            rtn
        }
    """.trimIndent()

    private val trickyShortCircuit = """
        // `&&` / `||` 的短路副作用：右边的函数**一次都不能多跑**
        pub class Log {
            var s : string = ""

            pub fn note(tag : string, v : bool) -> bool {
                s = s + tag
                rtn v
            }

            pub fn reset() -> void {
                s = ""
                rtn
            }
        }

        fn main() -> void {
            var lg = new Log()

            var r1 = lg.note("a", false) && lg.note("b", true)
            println("r1=" + r1 + " log=" + lg.s)

            lg.reset()
            var r2 = lg.note("a", true) && lg.note("b", false)
            println("r2=" + r2 + " log=" + lg.s)

            lg.reset()
            var r3 = lg.note("a", true) || lg.note("b", false)
            println("r3=" + r3 + " log=" + lg.s)

            lg.reset()
            var r4 = lg.note("a", false) || lg.note("b", true)
            println("r4=" + r4 + " log=" + lg.s)

            lg.reset()
            var r5 = lg.note("a", false) && lg.note("b", true) || lg.note("c", true)
            println("r5=" + r5 + " log=" + lg.s)

            lg.reset()
            var x = 3
            if (x > 0 && x < 10) {
                lg.s = lg.s + "in"
            }
            println("范围判断 log=" + lg.s)

            var i = 0
            lg.reset()
            while (i < 3 && lg.note("w", true)) {
                i = i + 1
            }
            println("while 短路次数 i=" + i + " log=" + lg.s)
            rtn
        }
    """.trimIndent()

    private val trickyEnums = """
        // 枚举与 switch 分派（穷尽 / else / 引用相等 / 数组里分派）
        enum Color { RED, GREEN, BLUE }

        enum TokenKind { IDENT, INT_LIT, PLUS, EOF }

        fn name(c : Color) -> string {
            switch (c) {
                Color.RED : rtn "红"
                Color.GREEN : rtn "绿"
                Color.BLUE : rtn "蓝"
            }
            rtn "?"
        }

        fn weight(k : TokenKind) -> int {
            var out = 0
            switch (k) {
                TokenKind.IDENT : out = 1
                TokenKind.INT_LIT : out = 2
                TokenKind.PLUS : out = 3
                else : out = 0 - 1
            }
            rtn out
        }

        fn main() -> void {
            println(name(Color.RED) + "/" + name(Color.GREEN) + "/" + name(Color.BLUE))
            println("weight = " + weight(TokenKind.IDENT) + "," + weight(TokenKind.INT_LIT) + "," + weight(TokenKind.PLUS))
            println("else 分支 = " + weight(TokenKind.EOF))

            var a = Color.RED
            var b = Color.RED
            println("同一个常量 == " + (a == b))
            println("不同常量 == " + (a == Color.BLUE))
            println("toString = " + "" + TokenKind.INT_LIT)

            var all = new TokenKind[4]
            all[0] = TokenKind.IDENT
            all[1] = TokenKind.INT_LIT
            all[2] = TokenKind.PLUS
            all[3] = TokenKind.EOF
            var sum = 0
            for (var i = 0; i < all.length; i = i + 1) {
                switch (all[i]) {
                    TokenKind.IDENT : sum = sum + 1
                    TokenKind.INT_LIT : sum = sum + 10
                    TokenKind.PLUS : sum = sum + 100
                    else : sum = sum + 1000
                }
            }
            println("循环分派 sum = " + sum)
            rtn
        }
    """.trimIndent()

    private val trickyConstructors = """
        // 构造器重载 / this() 委托 / 字段初值顺序 / stc / prv / 对象 toString
        pub class Vec(x0 : int, y0 : int) {
            var x : int = x0 * 2
            var y : int = y0 * 2
            var tag : string = "vec"

            constructor() {
                this(0, 0)
            }

            constructor(k : int) {
                this(k, k)
            }

            pub fn sum() -> int {
                rtn x + y
            }

            pub fn describe() -> string {
                rtn tag + "(" + x + ", " + y + ")"
            }

            pub fn bump(d : int) -> void {
                x = x + d
                y = y + d
                rtn
            }

            pub stc fn zero() -> Vec {
                rtn new Vec()
            }

            prv fn secret() -> int {
                rtn 7
            }

            pub fn reveal() -> int {
                rtn secret()
            }
        }

        pub class Holder {
            var held : ?Vec = null

            pub fn keep(v : Vec) -> void {
                held = v
                rtn
            }

            pub fn has() -> bool {
                rtn held != null
            }

            pub fn total() -> int {
                rtn if held == null ? 0 : held!!.sum()
            }
        }

        fn main() -> void {
            var a = new Vec(1, 2)
            println("主构造 = " + a.describe())
            var b = new Vec()
            println("无参次构造 = " + b.describe())
            var c = new Vec(5)
            println("单参次构造 = " + c.describe())
            var d = Vec(3, 4)
            println("省 new = " + d.describe())
            println("静态工厂 = " + Vec.zero().describe())
            println("reveal = " + a.reveal())
            a.bump(10)
            println("bump 之后 = " + a.describe() + " sum=" + a.sum())

            var h = new Holder()
            println("空 Holder = " + h.has() + "/" + h.total())
            h.keep(a)
            println("放进去之后 = " + h.has() + "/" + h.total())
            println("对象拼接 = " + a)
            println("Holder 拼接 = " + h)
            rtn
        }
    """.trimIndent()

    private val trickyStrings = """
        // 字符串 / char / 可空 / parseInt / fail / try / ?: / !!
        fn pick(s : string) -> ?string {
            if (s.length() == 0) { rtn null }
            rtn s
        }

        fn mustInt(s : string) -> !int {
            if (s.length() == 0) { fail "空串" }
            rtn parseInt(s)!!
        }

        fn main() -> void {
            var text = "rainStarfox"
            println("length = " + text.length())
            println("charAt(0) = " + text.charAt(0))
            println("substring(0, 4) = " + text.substring(0, 4))
            println("substring(4, 9) = " + text.substring(4, 9))

            var out = ""
            var i = text.length() - 1
            while (i >= 0) {
                out = out + text.charAt(i)
                i = i - 1
            }
            println("倒过来 = " + out)

            println("转义 = " + "tab\t|nl\n|done")
            println("char 转义 = " + '\n' + '\t' + '\u0041')
            println("charToInt('\\n') = " + charToInt('\n'))

            println("pick(\"\") ?: = " + (pick("") ?: "兜底"))
            println("pick(\"x\") ?: = " + (pick("x") ?: "兜底"))
            var maybe = pick("")
            if (maybe == null) {
                println("null 判断命中")
            } else {
                println("不该到这 " + maybe!!)
            }

            println("parseInt 正常 = " + (parseInt("42") ?: 0 - 1))
            println("parseInt 失败兜底 = " + (parseInt("nope") ?: 0 - 1))
            try {
                var v = parseInt("12x")
                println("不该到这 " + v)
            } catch (e : string) {
                println("parseInt 失败接住：" + e)
            }
            try {
                println("mustInt = " + mustInt("7")!!)
                println("mustInt 空串 = " + mustInt("")!!)
                println("不该到这")
            } catch (e : string) {
                println("mustInt 接住：" + e)
            }
            println("mustInt ?: = " + (mustInt("5") ?: 0 - 1))
            rtn
        }
    """.trimIndent()

    private val trickyControlFlow = """
        // 控制流全家桶：while / 两种 for / break / continue / goto / 标签 / 块作用域 / 三目
        fn main() -> void {
            var sum = 0
            var i = 0
            while (i < 10) {
                i = i + 1
                if (i % 2 == 0) : continue
                if (i > 7) { break }
                sum = sum + i
            }
            println("while = " + sum)

            var even = 0
            for (i in 0..10 step 2) {
                even = even + i
            }
            println("for-in step 2 = " + even)

            var desc = ""
            for (i in 3..0 step -1) {
                desc = desc + i
            }
            println("for-in 递减 = " + desc)

            var tri = 0
            for (var r = 1; r <= 4; r = r + 1) {
                tri = tri + r
            }
            println("for 三段式 = " + tri)

            var nested = 0
            for (var a = 0; a < 3; a = a + 1) {
                for (var b = 0; b < 3; b = b + 1) {
                    if (b == 1) : continue
                    if (a == 2) { break }
                    nested = nested + a * 10 + b
                }
            }
            println("嵌套 = " + nested)

            var c = 0
        countdown:
            if (c >= 3) { goto finished }
            println("countdown " + (3 - c))
            c = c + 1
            goto countdown

        finished:

            var outer = 1
            {
                var inner = 10
                println("块作用域 = " + (outer + inner))
            }

            var x = 3
            var label = if x > 2 ? "big" : "small"
            println("三目 = " + label)
            if x > 3 : println("大")
            else : println("小")
            rtn
        }
    """.trimIndent()

    private val trickyGenerics = """
        // 泛型擦除 / ArrayList 互操作 / 对象数组
        import java.util.ArrayList

        pub class Box&<T> {
            var value : T = 0

            constructor(v0 : T) {
                value = v0
            }

            pub fn get() -> T {
                rtn value
            }

            pub fn set(v : T) -> void {
                value = v
                rtn
            }
        }

        pub class P(x0 : int) {
            var x : int = x0

            pub fn twice() -> int {
                rtn x * 2
            }
        }

        fn main() -> void {
            var xs : ArrayList&<int> = new ArrayList&<int>()
            for (var i = 1; i <= 5; i = i + 1) {
                xs.add(i * i)
            }
            println("size = " + xs.size())
            var total = 0
            for (var i = 0; i < xs.size(); i = i + 1) {
                total = total + xs.get(i)
            }
            println("平方和 = " + total)
            println("get(0) + 1 = " + (xs.get(0) + 1))

            var names : ArrayList&<string> = new ArrayList&<string>()
            names.add("rain")
            names.add("starfox")
            println("拼接 = " + (names.get(0) + "-" + names.get(1)))

            var b : Box&<int> = new Box&<int>(21)
            println("Box = " + b.get() + "，" + (b.get() + 1))
            var bs : Box&<string> = new Box&<string>("hi")
            println("Box string = " + bs.get() + "，长度 " + bs.get().length())

            var ps = new P[3]
            for (var i = 0; i < ps.length; i = i + 1) {
                ps[i] = new P(i + 1)
            }
            var psum = 0
            for (var i = 0; i < ps.length; i = i + 1) {
                psum = psum + ps[i].twice()
            }
            println("对象数组 = " + psum)
            rtn
        }
    """.trimIndent()
}
