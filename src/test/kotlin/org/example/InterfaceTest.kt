package org.example

import org.example.complie.Rsf
import org.example.complie.ast.ControlType
import org.example.complie.ast.ExprStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.VarDeclStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.sema.UserMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 20 `interface` 的前端（M1 语法 + M2 语义）。
 *
 * 这里只测**前端**：解析出什么 AST、Checker 放行还是报错、报什么。
 * 两端（解释器 / 字节码）的接口支持是 M3 的事，不在这里测。
 */
class InterfaceTest {

    // ------------------------------------------------------------------ 语法（M1）

    @Test
    fun `接口声明能解析出抽象方法与默认方法`() {
        val program = Rsf.parse(
            source(
                "public interface Greeter {\n" +
                    "    fn greet(name : string) -> string\n" +
                    "    fn shout(name : string) -> string {\n" +
                    "        rtn name\n" +
                    "    }\n" +
                    "}\n",
            ),
        )

        val iface = program.interfaces.single()
        assertEquals("Greeter", iface.name)
        assertEquals(ControlType.PUBLIC, iface.control)
        assertEquals(listOf("greet", "shout"), iface.methods.map { it.name })
        // 没写体的就是抽象方法，写了体的（默认方法）body 不为空
        assertEquals(listOf("greet"), iface.abstractMethods.map { it.name })
        assertEquals(listOf("shout"), iface.defaultMethods.map { it.name })
        assertTrue(iface.supertypes.isEmpty(), iface.supertypes.toString())
    }

    @Test
    fun `接口头能带类型参数与父接口`() {
        val program = Rsf.parse(
            source(
                "public interface Base {\n    fn id() -> int\n}\n" +
                    "public interface Box&<T> : Base {\n    fn get() -> T\n}\n",
            ),
        )

        val box = program.interfaces.single { it.name == "Box" }
        assertEquals(listOf("T"), box.typeParams)
        assertEquals(listOf("Base"), box.supertypes.map { it.name })
    }

    @Test
    fun `类头能写多个父类型`() {
        val program = Rsf.parse(
            source(
                "public interface A {\n    fn a() -> int\n}\n" +
                    "public interface B {\n    fn b() -> int\n}\n" +
                    "class C : A, B {\n" +
                    "    public fn a() -> int {\n        rtn 1\n    }\n" +
                    "    public fn b() -> int {\n        rtn 2\n    }\n" +
                    "}\n",
            ),
        )

        assertEquals(listOf("A", "B"), program.classes.single().supertypes.map { it.name })
    }

    // ------------------------------------------------------------------ 语义：能过（M2）

    @Test
    fun `接口变量能持有实现类 并调用接口方法`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet(name : string) -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = new Dog()\n" +
                "    println(g.greet(\"a\"))\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `接口类型的参数与返回`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet(n : string) -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn hello(g : Greeter) -> string {\n" +
                "    rtn g.greet(\"x\")\n" +
                "}\n" +
                "fn pick() -> Greeter {\n" +
                "    rtn new Dog()\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    println(hello(new Dog()))\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `默认方法被实现类继承`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet(n : string) -> string\n" +
                "    fn shout(n : string) -> string {\n" +
                "        rtn greet(n) + \"!\"\n" +
                "    }\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = new Dog()\n" +
                "    println(g.shout(\"a\"))\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `接口继承接口 子接口的实例能赋给父接口`() {
        val text =
            "public interface Named {\n" +
                "    fn name() -> string\n" +
                "}\n" +
                "public interface Greeter : Named {\n" +
                "    fn greet(n : string) -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn name() -> string {\n" +
                "        rtn \"dog\"\n" +
                "    }\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = new Dog()\n" +
                "    var n : Named = g\n" +
                "    var n2 : Named = new Dog()\n" +
                "    println(n.name() + n2.name())\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `可空接口类型能持空与实现类`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet() -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var a : ?Greeter = null\n" +
                "    var b : ?Greeter = new Dog()\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `接口类型的返回可以写 mayError 子句`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet() -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "public fn pick() -> !Greeter {\n" +
                "    rtn new Dog()\n" +
                "}\n" +
                "public fn take() -> !string {\n" +
                "    var g : Greeter = pick()!!\n" +
                "    rtn g.greet()\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    try {\n" +
                "        println(take())\n" +
                "    } catch (e : string) {\n" +
                "        println(e)\n" +
                "    }\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `泛型接口能声明 实现与调用`() {
        val text =
            "public interface Box&<T> {\n" +
                "    fn get() -> T\n" +
                "}\n" +
                "class IntBox : Box&<int> {\n" +
                "    public fn get() -> int {\n" +
                "        rtn 7\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var b : Box&<int> = new IntBox()\n" +
                "    println(b.get())\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `能实现 classpath 上的 JVM 接口`() {
        val text =
            "import java.lang.Runnable\n" +
                "class Robot : Runnable {\n" +
                "    public fn run() -> void {\n" +
                "        rtn\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var r : Runnable = new Robot()\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    @Test
    fun `接口方法的调用点记下了接口归属`() {
        val src = source(
            "public interface Greeter {\n" +
                "    fn greet(n : string) -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet(n : string) -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = new Dog()\n" +
                "    g.greet(\"a\")\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val main = program.functions.single { it.name == "main" }
        val call = (main.body!!.statements[1] as ExprStmt).expr as MemberExpr
        val member = model.classModel.members[call]
        assertTrue(member is UserMember.Method, member.toString())
        // 后端要靠这个标志选 invokeinterface（DESIGN §7 风险 R1）
        assertTrue((member as UserMember.Method).interfaceOwner, "接口接收者的成员没被标成接口方法")
        assertEquals("Greeter", member.className)
    }

    @Test
    fun `接口类型的变量能传给接口类型的参数`() {
        val text =
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet() -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fn hello(g : Greeter) -> string {\n" +
                "    rtn g.greet()\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = new Dog()\n" +
                "    println(hello(g))\n" +
                "    rtn\n" +
                "}\n"
        Rsf.analyze(source(text))
    }

    // ------------------------------------------------------------------ 语义：必须报错（M2）

    @Test
    fun `接口里写字段是错误`() {
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("public interface I {\n    var x : int = 0\n}\n"))
        }
        assertTrue(e.detail.contains("接口里只能写方法"), e.detail)
    }

    @Test
    fun `抽象方法没实现要逐个列出来`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet(n : string) -> string\n" +
                        "    fn shout() -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n" +
                        "    public fn greet(n : string) -> string {\n" +
                        "        rtn \"woof\"\n" +
                        "    }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("还缺"), e.detail)
        assertTrue(e.detail.contains("shout() -> string"), e.detail)
        assertTrue(e.detail.contains("接口 'Greeter'"), e.detail)
    }

    @Test
    fun `实现方法的返回类型必须一致`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n" +
                        "    public fn greet() -> int {\n" +
                        "        rtn 1\n" +
                        "    }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("返回类型和接口对不上"), e.detail)
    }

    @Test
    fun `实现方法的签名必须一致`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet(n : string) -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n" +
                        "    public fn greet(n : int) -> string {\n" +
                        "        rtn \"woof\"\n" +
                        "    }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("和接口签名对不上"), e.detail)
    }

    @Test
    fun `接口不能 new`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var g = new Greeter()\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是接口，不能 new"), e.detail)
    }

    @Test
    fun `继承 final 类要报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "final class Base {\n    var x : int = 0\n}\n" +
                        "class Sub : Base() {\n}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是 final 的"), e.detail)
    }

    @Test
    fun `多接口同签名默认方法冲突要求显式覆盖`() {
        val conflict =
            "public interface A {\n" +
                "    fn hi() -> string {\n        rtn \"a\"\n    }\n" +
                "}\n" +
                "public interface B {\n" +
                "    fn hi() -> string {\n        rtn \"b\"\n    }\n" +
                "}\n" +
                "class C : A, B {\n}\n"
        val e = assertFailsWith<RsfError> { Rsf.analyze(source(conflict)) }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("说不清该用哪一份"), e.detail)

        // 显式覆盖之后就没有歧义了
        Rsf.analyze(
            source(
                conflict.replace(
                    "class C : A, B {\n}\n",
                    "class C : A, B {\n    public fn hi() -> string {\n        rtn \"c\"\n    }\n}\n",
                ),
            ),
        )
    }

    @Test
    fun `子接口给的默认方法不算冲突`() {
        // B 继承 A 并覆盖了 hi——这份比 A 的更具体，实现类不用再写
        Rsf.analyze(
            source(
                "public interface A {\n" +
                    "    fn hi() -> string {\n        rtn \"a\"\n    }\n" +
                    "}\n" +
                    "public interface B : A {\n" +
                    "    fn hi() -> string {\n        rtn \"b\"\n    }\n" +
                    "}\n" +
                    "class C : B {\n}\n",
            ),
        )
    }

    @Test
    fun `接口继承成环要报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface A : B {\n    fn a() -> int\n}\n" +
                        "public interface B : A {\n    fn b() -> int\n}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("接口继承成环了"), e.detail)
    }

    @Test
    fun `接口方法不能是 private`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("public interface I {\n    private fn f() -> int {\n        rtn 1\n    }\n}\n"))
        }
        assertTrue(e.detail.contains("只能是 public"), e.detail)
    }

    @Test
    fun `接口方法不能是 static`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("public interface I {\n    static fn f() -> int {\n        rtn 1\n    }\n}\n"))
        }
        assertTrue(e.detail.contains("static"), e.detail)
    }

    @Test
    fun `父类型名字写错要报找不到`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("public interface I : Nope {\n    fn f() -> int\n}\n"))
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("找不到"), e.detail)

        // 反过来，类也不能继承枚举
        val e2 = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public enum Color {\n    RED,\n    GREEN\n}\n" +
                        "class C : Color {\n}\n",
                ),
            )
        }
        assertTrue(e2.detail.contains("是个枚举"), e2.detail)
    }

    @Test
    fun `可空接口类型接不住非空接口`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n" +
                        "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var a : ?Greeter = null\n" +
                        "    var b : Greeter = a\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("不能"), e.detail)
    }

    @Test
    fun `接口里读字段要报错并说明接口没有字段`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string {\n" +
                        "        rtn name\n" +
                        "    }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("没有字段"), e.detail)
    }

    @Test
    fun `实现类少写方法时 new 出来也照样报错`() {
        // 报错发生在类上，与用不用得着无关——不能等到 new 的时候才拦
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n}\n" +
                        "fn main() -> void {\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(e.detail.contains("没有实现接口要求的全部方法"), e.detail)
    }

    @Test
    fun `接口名和类名冲突要报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Thing {\n    fn f() -> int\n}\n" +
                        "class Thing {\n}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("冲突") || e.detail.contains("重复定义"), e.detail)
    }

    @Test
    fun `接口方法可以放心重载`() {
        Rsf.analyze(
            source(
                "public interface Greeter {\n" +
                    "    fn greet(n : string) -> string\n" +
                    "    fn greet(n : int) -> string\n" +
                    "}\n" +
                    "class Dog : Greeter {\n" +
                    "    public fn greet(n : string) -> string {\n        rtn \"s\"\n    }\n" +
                    "    public fn greet(n : int) -> string {\n        rtn \"i\"\n    }\n" +
                    "}\n" +
                    "fn main() -> void {\n" +
                    "    var g : Greeter = new Dog()\n" +
                    "    println(g.greet(\"a\") + g.greet(1))\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
    }

    @Test
    fun `接口里不能写构造器`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface I {\n" +
                        "    constructor(x : int) {\n    }\n" +
                        "}\n",
                ),
            )
        }
        assertTrue(
            e.detail.contains("不能写构造器") || e.detail.contains("只能写方法"),
            e.detail,
        )
    }

    @Test
    fun `接口的实例不能塞进 new 之外的地方`() {
        // `Greeter(...)` 这种省略 new 的写法也必须被拦住，不能生成一条构造调用
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet() -> string\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var g = Greeter()\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是接口，不能 new"), e.detail)
    }

    @Test
    fun `接口类型的局部变量能推断出声明类型`() {
        val src = source(
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var d = new Dog()\n" +
                "    var g : Greeter = d\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val main = program.functions.single { it.name == "main" }
        val decl = main.body!!.statements[1] as VarDeclStmt
        assertEquals("Greeter", decl.declaredType?.name)
        assertTrue(model.typeOf(decl.initializer).externalName == "Dog")
    }

    @Test
    fun `实现类可以有别的方法和字段`() {
        Rsf.analyze(
            source(
                "public interface Greeter {\n" +
                    "    fn greet() -> string\n" +
                    "}\n" +
                    "class Dog : Greeter {\n" +
                    "    var name : string = \"rex\"\n" +
                    "    var tag : int = 0\n" +
                    "    public fn greet() -> string {\n        rtn name\n    }\n" +
                    "}\n" +
                    "fn main() -> void {\n" +
                    "    var d = new Dog()\n" +
                    "    d.tag = 3\n" +
                    "    println(d.greet())\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
    }

    @Test
    fun `接口方法上不能写与接口同名的名字`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("public interface I {\n    fn I() -> int\n}\n"))
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("不能和接口同名"), e.detail)
    }

    @Test
    fun `泛型接口的类型实参要对得上`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Box&<T> {\n" +
                        "    fn get() -> T\n" +
                        "}\n" +
                        "class IntBox : Box&<int> {\n" +
                        "    public fn get() -> int {\n        rtn 7\n    }\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var b : Box&<string> = new IntBox()\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("不能"), e.detail)
    }

    @Test
    fun `接口类型的参数能收实现类的子类型`() {
        Rsf.analyze(
            source(
                "public interface Greeter {\n" +
                    "    fn greet() -> string\n" +
                    "}\n" +
                    "public interface Loud : Greeter {\n" +
                    "    fn volume() -> int\n" +
                    "}\n" +
                    "class Dog : Loud {\n" +
                    "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                    "    public fn volume() -> int {\n        rtn 9\n    }\n" +
                    "}\n" +
                    "fn hello(g : Greeter) -> string {\n" +
                    "    rtn g.greet()\n" +
                    "}\n" +
                    "fn main() -> void {\n" +
                    "    println(hello(new Dog()))\n" +
                    "    rtn\n" +
                    "}\n",
            ),
        )
    }

    @Test
    fun `接口方法的实参个数不对要报错`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "public interface Greeter {\n" +
                        "    fn greet(n : string) -> string\n" +
                        "}\n" +
                        "class Dog : Greeter {\n" +
                        "    public fn greet(n : string) -> string {\n        rtn \"woof\"\n    }\n" +
                        "}\n" +
                        "fn main() -> void {\n" +
                        "    var g : Greeter = new Dog()\n" +
                        "    println(g.greet())\n" +
                        "    rtn\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("参数个数不对"), e.detail)
    }

    @Test
    fun `父接口的解析结果交给后端的是内部名与实参`() {
        // 后端拼 ClassWriter.visit 的 interfaces 数组就抄这一份：rsf 接口是默认包里的
        // 名字，JDK 接口是 java/lang/... 全名，rsf 泛型接口的实参已经代换好了。
        val text =
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "public interface Box&<T> {\n" +
                "    fn get() -> T\n" +
                "}\n" +
                "class Dog : Greeter, Runnable {\n" +
                "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                "    public fn run() -> void {\n        rtn\n    }\n" +
                "}\n" +
                "class IntBox : Box&<int> {\n" +
                "    public fn get() -> int {\n        rtn 7\n    }\n" +
                "}\n"
        val model = Rsf.analyze(source(text))

        val dog = model.superInterfacesOf("Dog")
        assertEquals(listOf("Greeter", "java/lang/Runnable"), dog.map { it.internalName })
        assertEquals(listOf(false, true), dog.map { it.fromClassPath })
        assertTrue(dog.all { it.args.isEmpty() })

        val intBox = model.superInterfacesOf("IntBox").single()
        assertEquals("Box", intBox.internalName)
        assertEquals(listOf("int"), intBox.args.map { it.rsfName })
        // 接口继承接口也在这张表里（子接口的父接口同样是「父接口」）
        assertTrue(model.superInterfacesOf("不存在").isEmpty())
    }

    @Test
    fun `错误返回的接口类型与实现类在 elvis 里收敛到接口`() {
        // `?:` 的公共类型走 promoteCommon：`!Greeter` 与 `Dog` 若退回 Object，
        // 就会报「不能把 Object 赋给 Greeter 变量」。javac 给的是最近的公共父类型，
        // 语言里有了接口之后这一步必须跟上（promote 自己只认基础类型与泛型实参）。
        val src = source(
            "public interface Greeter {\n" +
                "    fn greet() -> string\n" +
                "}\n" +
                "class Dog : Greeter {\n" +
                "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                "}\n" +
                "fn must() -> !Greeter {\n" +
                "    rtn new Dog()\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var g : Greeter = must() ?: new Dog()\n" +
                "    println(g.greet())\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val elvis = (program.functions.single { it.name == "main" }.body!!.statements[0] as VarDeclStmt)
            .initializer

        assertEquals("Greeter", model.typeOf(elvis).externalName)
        assertTrue(!model.typeOf(elvis).isNullable, model.typeOf(elvis).rsfName)
    }

    @Test
    fun `两个不相干的引用类型在 elvis 里仍然收敛到 Object`() {
        val src = source(
            "class Dog {\n" +
                "    public fn greet() -> string {\n        rtn \"woof\"\n    }\n" +
                "}\n" +
                "class Cat {\n" +
                "    public fn greet() -> string {\n        rtn \"meow\"\n    }\n" +
                "}\n" +
                "fn pick(flag : bool) -> ?Dog {\n" +
                "    if (flag) {\n        rtn new Dog()\n    }\n" +
                "    rtn null\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var x = pick(true) ?: new Cat()\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val elvis = (program.functions.single { it.name == "main" }.body!!.statements[0] as VarDeclStmt)
            .initializer

        assertEquals("java/lang/Object", model.typeOf(elvis).externalName)
    }

    @Test
    fun `接口里的 void 方法能被类实现`() {
        val src = source(
            "public interface P {\n" +
                "    fn show() -> void\n" +
                "}\n" +
                "public class D : P {\n" +
                "    public fn show() -> void {\n" +
                "        println(\"d\")\n" +
                "        rtn\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        Rsf.analyze(program, src)
    }

    @Test
    fun `void 方法不能拿别的返回类型顶替`() {
        val src = source(
            "public interface P {\n" +
                "    fn show() -> void\n" +
                "}\n" +
                "public class D : P {\n" +
                "    public fn show() -> int {\n" +
                "        rtn 1\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val err = assertFailsWith<RsfError> { Rsf.analyze(program, src) }

        assertTrue(err.message.contains("返回类型"), err.message)
    }
}

private fun source(text: String): SourceFile = SourceFile.of("<test>", text)
