package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BinaryExpr
import org.example.complie.ast.ControlType
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interop.ClassPath
import org.example.complie.interp.Interpreter
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import org.example.complie.sema.SuperCall
import org.example.complie.sema.UserMember
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 25 「类继承」的前端与语义（K1 刀）+ 解释器（K3 刀）与字节码（K2 刀）两侧的回归。
 *
 * 前半段只测**前端**：语法收不收、Checker 放行还是报错、报什么、给后端留了什么模型。
 * 中段（`K3：解释器侧` 那一节）测解释器真的把带父类的程序跑成什么样：构造链顺序、
 * 字段绝对下标、父链方法查找、`super.f()` / `super(接口).f()`、JDK 父类那条链。
 * 末段（`K2：字节码后端` 那一节）测 `Rsf.build` 出来的 .class：同一份源码两端 stdout
 * **逐字节相同**（口径与 `InterfaceRuntimeTest.kt` 一致），另外用 ASM 读一遍产物，
 * 把「子类构造器第一条是父类 `<init>`」「`super.f()` 发 invokespecial」「抽象方法没有 Code」
 * 这几条只看 stdout 验不出来的判据锁住——相当于一份**可断言的 `javap -c`**。
 *
 * 反例逐条对应 `DESIGN-25.md` §7 的判据清单。
 */
class InheritanceTest {

    // ------------------------------------------------------------ 正例（走 check）

    @Test
    fun `open 类能被子类继承 覆盖 open 方法 并调用 super 方法`() {
        val src = rsfSource(
            "open class Base(x : int) {\n" +
                "    public open fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "    override fun tag() -> string {\n" +
                "        rtn super.tag()\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        // 父类单独一个槽位（不混进 supertypes）：后端拼 extends 时抄这一份
        val parent = model.superClassOf("D")!!
        assertEquals("Base", parent.name)
        assertEquals("Base", parent.internalName)
        // 非泛型父类没有类型实参
        assertTrue(parent.args.isEmpty(), parent.args.toString())
        assertTrue(!parent.fromClassPath)
        assertEquals("Base", parent.declaration!!.name)
        // 没有父类的类不在这张表里
        assertNull(model.superClassOf("Base"))

        // `super.tag()` 记的是**父类链上声明它的类**
        val d = program.classes.single { it.name == "D" }
        val ret = d.methods.single { it.name == "tag" }.body!!.statements[0] as ReturnStmt
        val call = ret.value as MemberExpr
        assertTrue(call.receiver is SuperExpr, call.receiver.toString())
        // 不带接口名的 super → target 为 null（走直接父类那一份）
        assertNull((call.receiver as SuperExpr).target)
        val target = model.classModel.superCallOf(call)
        assertTrue(target is SuperCall.Rsf, target.toString())
        assertEquals("Base", (target as SuperCall.Rsf).className)
        assertTrue(!target.interfaceOwner)
    }

    @Test
    fun `父类排最前 接口跟在后面 父类实现的接口要求子类不用再实现`() {
        val src = rsfSource(
            "public interface Tag {\n" +
                "    fun tag() -> string\n" +
                "}\n" +
                "open class Base : Tag {\n" +
                "    override fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D : Base() {\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val d = model.superClassOf("D")!!
        assertEquals("Base", d.name)
        // 父类实参表写了但是空的：`: Base()` 与 `: Base` 一个意思（后者才报「必须写实参」）
        assertTrue(d.args.isEmpty(), d.args.toString())

        // 接口要求是**父类**实现的：D 自己没有 tag，但 D 沿类链继承到了，所以过
        val declD = program.classes.single { it.name == "D" }
        assertTrue(declD.methods.none { it.name == "tag" }, declD.methods.toString())
    }

    @Test
    fun `接口写在父类前面要报错`() {
        val e = fails(
            "public interface Tag {\n" +
                "    fun tag() -> string\n" +
                "}\n" +
                "open class Base {\n" +
                "}\n" +
                "class D : Tag, Base() {\n" +
                "    override fun tag() -> string {\n" +
                "        rtn \"d\"\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("最前面"), e.detail)
    }

    @Test
    fun `super 接口名 能挑出指定接口的默认实现`() {
        val src = rsfSource(
            "public interface A {\n" +
                "    fun m() -> string {\n" +
                "        rtn \"a\"\n" +
                "    }\n" +
                "}\n" +
                "public interface B {\n" +
                "    fun m() -> string {\n" +
                "        rtn \"b\"\n" +
                "    }\n" +
                "}\n" +
                "class C : A, B {\n" +
                "    override fun m() -> string {\n" +
                "        rtn super(B).m()\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val c = program.classes.single { it.name == "C" }
        val ret = c.methods.single { it.name == "m" }.body!!.statements[0] as ReturnStmt
        val call = ret.value as MemberExpr
        val sup = call.receiver as SuperExpr
        // `super(B)` 在 AST 里就是 SuperExpr(target = 接口名)
        assertEquals("B", sup.target!!.name)

        val target = model.classModel.superCallOf(call)
        assertTrue(target is SuperCall.Rsf, target.toString())
        // 挑中的是 B 那一份默认实现：接口归属 + 接口名
        assertEquals("B", (target as SuperCall.Rsf).className)
        assertTrue(target.interfaceOwner, "super(接口).方法() 选中的默认实现要标成接口方法")
    }

    @Test
    fun `字段布局是父类字段在前 下标是绝对下标 owner 是声明类`() {
        val src = rsfSource(
            "open class Base {\n" +
                "    var a : int = 0\n" +
                "}\n" +
                "class Sub : Base() {\n" +
                "    var b : string = \"\"\n" +
                "    public fun getA() -> int {\n" +
                "        rtn this.a\n" +
                "    }\n" +
                "    public fun getB() -> string {\n" +
                "        rtn this.b\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val sub = program.classes.single { it.name == "Sub" }
        fun member(name: String): UserMember.Field {
            val ret = sub.methods.single { it.name == name }.body!!.statements[0] as ReturnStmt
            val expr = ret.value as MemberExpr
            val got = model.classModel.members[expr]
            assertTrue(got is UserMember.Field, got.toString())
            return got as UserMember.Field
        }

        // Base.a 在对象字段数组的第 0 位，owner 是**声明它的 Base**（也就是 PUTFIELD/GETFIELD 的 owner）
        val a = member("getA")
        assertEquals("Base", a.className)
        assertEquals(0, a.index)
        // Sub.b 排在父类字段后面：1 = 父链字段数(1) + 本类段内下标(0)
        val b = member("getB")
        assertEquals("Sub", b.className)
        assertEquals(1, b.index)
    }

    @Test
    fun `抽象类能被继承 子类实现抽象方法就能过`() {
        val src = rsfSource(
            "abstract class Shape(x : int) {\n" +
                "    public abstract fun area() -> double\n" +
                "}\n" +
                "class Sq(y : int) : Shape(y) {\n" +
                "    override fun area() -> double {\n" +
                "        rtn 1.0\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        assertTrue(model.superClassOf("Sq")!!.declaration!!.isAbstract)
        assertEquals(
            listOf("area"),
            program.classes.single { it.name == "Sq" }.methods.filter { !it.isConstructor }.map { it.name },
        )
    }

    @Test
    fun `允许继承 JDK 类`() {
        val src = rsfSource(
            "class MyError(msg : string) : Exception(msg) {\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val parent = model.superClassOf("MyError")!!
        assertEquals("java/lang/Exception", parent.internalName)
        assertTrue(parent.fromClassPath)
        assertNull(parent.declaration, "JDK 父类没有 rsf 的 ClassDecl")
    }

    @Test
    fun `次构造里的 super 实参 委托给父类构造器`() {
        val src = rsfSource(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D(v : int) : Base(v) {\n" +
                "    constructor() {\n" +
                "        super(1)\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val d = program.classes.single { it.name == "D" }
        val primary = d.primaryConstructor!!
        val secondary = d.constructors.single { !it.isPrimary }

        // 主构造那条：实参来自类头，没有对应的语句节点
        val headerCall = model.classModel.superDelegationOf(primary)!!
        assertEquals("Base", headerCall.superClass.name)
        assertEquals(1, headerCall.arguments.size)
        assertNull(headerCall.stmt)
        assertEquals("Base", headerCall.target!!.name)

        // 次构造那条：实参来自 `super(1)`
        val ctorCall = model.classModel.superDelegationOf(secondary)!!
        assertEquals("Base", ctorCall.superClass.name)
        assertEquals(1, ctorCall.arguments.size)
        assertEquals("Base", ctorCall.target!!.name)
        assertTrue(ctorCall.stmt != null, "次构造的委托要留下那条 super(...) 语句")
    }

    // ------------------------------------------------------------ 反例（DESIGN-25 §7）

    @Test
    fun `继承没写 open 的类要报错`() {
        val e = fails(
            "class Base {\n" +
                "    var x : int = 0\n" +
                "}\n" +
                "class Sub : Base() {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("没有写 'open'"), e.detail)
        assertTrue(e.detail.contains("Base"), e.detail)
    }

    @Test
    fun `覆盖父类没写 open 的方法要报错`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "    public fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "    override fun tag() -> string {\n" +
                "        rtn \"d\"\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("没有写 'open'"), e.detail)
    }

    @Test
    fun `同签名的方法漏写 override 要报错`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "    public open fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "    public fun tag() -> string {\n" +
                "        rtn \"d\"\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("没写 override"), e.detail)
    }

    @Test
    fun `字段遮蔽要报错`() {
        val e = fails(
            "open class Base {\n" +
                "    var a : int = 0\n" +
                "}\n" +
                "class Sub : Base() {\n" +
                "    var a : string = \"\"\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("不许字段遮蔽"), e.detail)
    }

    @Test
    fun `两个父类要报错`() {
        val e = fails(
            "open class A(x : int) {\n" +
                "}\n" +
                "open class B(y : int) {\n" +
                "}\n" +
                "class C(z : int) : A(z), B(z) {\n" +
                "}\n",
        )
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("只能有一个父类"), e.detail)
    }

    @Test
    fun `枚举不能当父类`() {
        val e = fails(
            "public enum Color {\n" +
                "    RED,\n" +
                "    GREEN\n" +
                "}\n" +
                "class C : Color {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是个枚举"), e.detail)
    }

    @Test
    fun `注解不能当父类`() {
        val e = fails(
            "annotation Marker()\n" +
                "class C : Marker {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("是注解"), e.detail)
    }

    @Test
    fun `内置类型不能当父类`() {
        val e = fails(
            "class C : int {\n" +
                "}\n",
        )
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("内置类型"), e.detail)
    }

    @Test
    fun `父类构造实参类型不符要报错`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D(y : int) : Base(\"s\") {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("Base"), e.detail)
        assertTrue(
            e.detail.contains("没有匹配的构造器") || e.detail.contains("实参类型不对"),
            e.detail,
        )
    }

    @Test
    fun `父类构造实参不能引用 this`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D(y : int) : Base(this) {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("不能写 this"), e.detail)
    }

    @Test
    fun `super 不能用在静态方法里`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "    public open fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "    public static fun use() -> string {\n" +
                "        rtn super.tag()\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("静态方法"), e.detail)
    }

    @Test
    fun `super 不能用在类外`() {
        val e = fails(
            "fun top() -> string {\n" +
                "    rtn super.tag()\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("只能用在类的方法里"), e.detail)
    }

    @Test
    fun `super 实参 必须是构造器体的第一句`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D(v : int) : Base(v) {\n" +
                "    constructor() {\n" +
                "        var t : int = 0\n" +
                "        super(1)\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("第一句"), e.detail)
    }

    @Test
    fun `抽象类不能 new`() {
        val e = fails(
            "abstract class Shape(x : int) {\n" +
                "    public abstract fun area() -> double\n" +
                "}\n" +
                "fun make() -> Shape {\n" +
                "    rtn new Shape(1)\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("抽象类"), e.detail)
        assertTrue(e.detail.contains("不能 new"), e.detail)
    }

    @Test
    fun `抽象方法不能写函数体`() {
        val e = fails(
            "abstract class Shape {\n" +
                "    public abstract fun area() -> double {\n" +
                "        rtn 1.0\n" +
                "    }\n" +
                "}\n",
        )
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("抽象方法不能写函数体"), e.detail)
    }

    @Test
    fun `抽象方法要写出返回子句`() {
        val e = fails(
            "abstract class Shape {\n" +
                "    public abstract fun area()\n" +
                "}\n",
        )
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("返回子句"), e.detail)
    }

    @Test
    fun `子类没实现抽象成员要报错`() {
        val e = fails(
            "abstract class Shape {\n" +
                "    public abstract fun area() -> double\n" +
                "}\n" +
                "class Sq : Shape() {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("抽象方法"), e.detail)
        assertTrue(e.detail.contains("area"), e.detail)
    }

    @Test
    fun `类继承成环要报错`() {
        val e = fails(
            "open class A : B() {\n" +
                "}\n" +
                "open class B : A() {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("成了环"), e.detail)
    }

    @Test
    fun `泛型父类的类型实参与构造实参各写各的位置`() {
        val src = rsfSource(
            "open class Base(x : int) {\n" +
                "}\n" +
                "open class Box&<T>(v : T) : Base(1) {\n" +
                "}\n" +
                "class IntBox : Box&<int>(2) {\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        val parent = model.superClassOf("IntBox")!!
        assertEquals("Box", parent.name)
        // 类型实参在父类槽位里（后端拼 extends 时用得到）
        assertEquals(listOf(RsfType.INT), parent.args)
        assertEquals(listOf("T"), parent.declaration!!.typeParams)

        // 父类自己的父类也在表里，且实参是写死在类头上的那个 1
        val box = model.superClassOf("Box")!!
        assertEquals("Base", box.name)
        assertTrue(box.args.isEmpty(), box.args.toString())
    }

    @Test
    fun `父类的类型实参个数不对要报错`() {
        val e = fails(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D : Base&<int>(1) {\n" +
                "}\n",
        )
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("类型实参"), e.detail)
    }

    // ------------------------------------------------------------ 字节码后端（K2）

    /**
     * K1 时代那条守卫已经撤掉：带父类的程序现在真的 build 得出来，而且两端 stdout 一致。
     *
     * 这一条同时压住四件事：父类字段的**绝对下标**（`value()` 读的是继承来的 `x`，
     * 子类自己还加了一个字段）、子类构造器的**父类 `<init>` 链**（不调父类构造器
     * `x` 就是 0）、**虚分派**（`var b : Base = d` 之后 `b.tag()` 必须打到 Derived 的
     * 重写上）、以及 `super.tag()` 发的是 `invokespecial` 而不是又一次虚分派
     * （发错就是无限递归）。
     */
    @Test
    fun `带父类的程序 build 跑得动且两端 stdout 逐字节相同`() {
        val program = """
            open class Base(p : int) {
                var x : int = p
                fun value() -> int {
                    rtn x
                }
                open fun tag() -> string {
                    rtn "base"
                }
            }
            class Derived(p : int) : Base(p) {
                var y : int = p + 1
                override fun tag() -> string {
                    rtn super.tag() + "+derived/" + intToString(y)
                }
            }
            fun main() -> void {
                var d : Derived = new Derived(7)
                println(d.tag())
                println(intToString(d.value()))
                var b : Base = d
                println(b.tag())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("base+derived/8", "7", "base+derived/8"), bothEnds(program, "RsfInheritBuild"))
    }

    /**
     * 「子类构造器第一条指令是父类 `<init>`」「`super.f()` 是 invokespecial」这两条判据
     * 只有看指令流才验得出来，所以这里读一遍 .class。
     *
     * `super.tag()` 若发成 `invokevirtual`，跑起来是**打到自己的重写上**（无限递归，
     * StackOverflow 或者干脆死循环），而字段布局若把父类字段漏掉，两端 stdout 说不定
     * 还能凑巧一样——所以这两个断言不能交给 stdout。
     */
    @Test
    fun `子类构造器先调父类 init 且 super 方法发 invokespecial`() {
        val program = """
            open class Base(p : int) {
                var x : int = p
                open fun tag() -> string {
                    rtn "base"
                }
            }
            class Derived(p : int) : Base(p) {
                var y : int = p + 1
                override fun tag() -> string {
                    rtn super.tag() + "!"
                }
            }
            fun main() -> void {
                rtn
            }
        """.trimIndent() + "\n"

        val shape = compiledShape(program, "RsfInheritShape", "Derived.class")
        // 父类名必须是真正的父类，而不是写死的 java/lang/Object
        assertEquals("Base", shape.superName)

        val ctor = shape.methods.single { it.name == "<init>" }
        // `this` → 实参 → 父类 <init>：第一条永远是 ALOAD 0（接收者），第二条是父类构造器实参
        assertEquals(
            listOf("ALOAD 0", "ILOAD 1", "INVOKESPECIAL Base.<init>(I)V itf=false"),
            ctor.instructions.take(3),
            "构造器前三条必须是 this + 实参 + 父类 <init>：${ctor.instructions}",
        )

        val tag = shape.methods.single { it.name == "tag" }
        assertTrue(
            tag.instructions.any { it == "INVOKESPECIAL Base.tag()Ljava/lang/String; itf=false" },
            "super.tag() 必须发 invokespecial 且 owner 是声明它的父类：${tag.instructions}",
        )
    }

    /** 抽象方法只发签名（`ACC_ABSTRACT`、没有 Code 属性），抽象类自己带 `ACC_ABSTRACT`。 */
    @Test
    fun `抽象方法与抽象类都带 ACC_ABSTRACT 且抽象方法没有 Code`() {
        val program = """
            abstract class Shape(nm : string) {
                var name : string = nm
                abstract fun area() -> int
                fun describe() -> string {
                    rtn name + "=" + intToString(area())
                }
            }
            class Square(side0 : int) : Shape("square") {
                var side : int = side0
                override fun area() -> int {
                    rtn side * side
                }
            }
            fun main() -> void {
                var s : Shape = new Square(3)
                println(s.describe())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("square=9"), bothEnds(program, "RsfInheritAbstract"))

        val shape = compiledShape(program, "RsfInheritAbstractShape", "Shape.class")
        assertTrue(shape.hasFlag(Opcodes.ACC_ABSTRACT), "抽象类本身要带 ACC_ABSTRACT")
        val area = shape.methods.single { it.name == "area" }
        assertTrue(area.hasFlag(Opcodes.ACC_ABSTRACT), "抽象方法要带 ACC_ABSTRACT")
        assertTrue(area.instructions.isEmpty(), "抽象方法不能有 Code 属性：${area.instructions}")
    }

    /**
     * toString 沿**整条父链**渲染（父类字段在前）——这是两端约定，见
     * `K3 toString 把整条父链的字段都渲染出来（父类字段在前）` 与 `ObjectFormat.render`。
     * 字节码端如果只遍历自己的字段，同一份程序就会打出 `Child(b=2)`，所以这条必须两端对。
     */
    @Test
    fun `toString 沿整条父链渲染且两端逐字节一致`() {
        val program = """
            open class Base(x : int) {
                var a : int = x
            }
            class Child(x : int, y : int) : Base(x) {
                var b : int = y
            }
            fun main() -> void {
                var c : Child = new Child(1, 2)
                println(c)
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("Child(a=1, b=2)"), bothEnds(program, "RsfInheritToString"))
    }

    /**
     * JDK 父类那条链：父类是 `java/lang/Exception`，构造器按实参静态类型选到
     * `Exception(String)`，继承来的 `getMessage()` 有两条路（隐式 `this.getMessage()`
     * 与显式 `e.getMessage()`）都得走通。
     */
    @Test
    fun `JDK 父类的构造与继承来的方法两端一致`() {
        val program = """
            import java.lang.Exception
            class MyError(msg : string) : Exception(msg) {
                fun describe() -> string {
                    rtn "MyError:" + getMessage()
                }
            }
            fun main() -> void {
                var e : MyError = new MyError("boom")
                println(e.describe())
                println(e.getMessage())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("MyError:boom", "boom"), bothEnds(program, "RsfInheritJdkParent"))

        val shape = compiledShape(program, "RsfInheritJdkParentShape", "MyError.class")
        assertEquals("java/lang/Exception", shape.superName)
    }

    /** 类继承与接口默认方法混用：`super(Greeter).greet()` 要点到接口那一份，不是自己的重写。 */
    @Test
    fun `接口默认方法与类继承混用两端一致`() {
        val program = """
            interface Greeter {
                fun greet() -> string {
                    rtn "hi"
                }
            }
            interface Named {
                fun name() -> string
                fun greet() -> string {
                    rtn "named-hi"
                }
            }
            open class Base {
                fun base() -> string {
                    rtn "base"
                }
            }
            class E : Base(), Greeter, Named {
                override fun name() -> string {
                    rtn "E"
                }
                override fun greet() -> string {
                    rtn super(Named).greet()
                }
                fun which() -> string {
                    rtn super(Greeter).greet() + ":" + name()
                }
            }
            fun main() -> void {
                var e : E = new E()
                println(e.greet())
                println(e.which())
                rtn
            }
        """.trimIndent() + "\n"

        assertEquals(listOf("named-hi", "hi:E"), bothEnds(program, "RsfInheritIfaceMix"))
    }

    @Test
    fun `带父类的程序 run 跑得动（K3 刀落地，K1 的解释器守卫撤了）`() {
        // K1 时这条测的是「解释器显式报还没实现（K3）」；K3 落地后同一条程序必须真的跑出
        // 结果——语义层 check 通过、解释器却跑不了的那种缝隙，就由这条测试盯着。
        val out = runInterpreted(
            "open class Base(x : int) {\n" +
                "    var v : int = x\n" +
                "    public open fun tag() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "    override fun tag() -> string {\n" +
                "        rtn super.tag() + \"+d\"\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var d : D = new D(7)\n" +
                "    println(d.tag())\n" +
                "    println(intToString(d.v))\n" +
                "    rtn\n" +
                "}\n",
        )
        // `d.v` 是父类的字段：能读到 7 就说明字段绝对下标（父类在前）排对了，
        // `super.tag()` 只跳一层就到 Base。
        assertEquals("base+d\n7\n", out)
    }

    // ------------------------------------------- 父代理对拍探针暴露的 5 个缺口（G1–G5）

    @Test
    fun `G1 子类能上转型成父类：变量、实参、返回值、数组元素、字段都算`() {
        val src = rsfSource(
            "open class A(nm : string) {\n" +
                "}\n" +
                "open class B(nm : string) : A(nm) {\n" +
                "}\n" +
                "class C(nm : string) : B(nm) {\n" +
                "}\n" +
                "open class Holder(v : A) {\n" +
                "    var slot : A = v\n" +
                "}\n" +
                "fun take(a : A) -> void {\n" +
                "    rtn\n" +
                "}\n" +
                "fun give() -> A {\n" +
                "    rtn new C(\"c\")\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var c : C = new C(\"c\")\n" +
                "    var b : B = c\n" +
                "    var a : A = b\n" +
                "    var xs : A[] = new A[2]\n" +
                "    xs[0] = c\n" +
                "    take(c)\n" +
                "    var h : Holder = new Holder(c)\n" +
                "    h.slot = b\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        // 子类型关系漏了父类那一跳时，这些行会依次报「不能把 C 赋给 B」之类
        Rsf.analyze(program, src)
    }

    @Test
    fun `G2 隐式 this 调的父类方法沿类链找 记的是声明它的类`() {
        val src = rsfSource(
            "open class Base {\n" +
                "    public fun base() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D : Base() {\n" +
                "    public fun use() -> string {\n" +
                "        rtn base()\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val target = model.classModel.implicitCalls.values.single()
        // 继承来的方法记**父类**名：后端据此发 invokevirtual
        assertEquals("Base", target.className)
        assertEquals("base", target.decl.name)
    }

    @Test
    fun `G3 继承字段的写路径沿类链找 owner 是声明类 下标父类字段在前`() {
        val src = rsfSource(
            "open class Base(p : int) {\n" +
                "    var a : int = p\n" +
                "    var b : int = 10\n" +
                "}\n" +
                "class Child(p : int, c : int) : Base(p) {\n" +
                "    var d : int = c\n" +
                "    public fun bump() -> void {\n" +
                "        b = b + 1\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var o : Child = new Child(1, 2)\n" +
                "    o.b = o.b + 100\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)

        // `o.b = ...`：owner 是声明它的 Base，下标是烘好的绝对值（Base 的 a=0、b=1）
        val write = model.classModel.fieldWrites.values.single()
        assertEquals("Base", write.className)
        assertEquals(1, write.index)

        // 父类方法体里裸写的 `b = b + 1` 也解析到同一个槽位
        val bare = model.classModel.bareFieldWrites.values.single()
        assertEquals("Base", bare.className)
        assertEquals(1, bare.index)
    }

    @Test
    fun `G4 泛型父类的成员类型实参要代换到子类那一层`() {
        val src = rsfSource(
            "open class Holder&<T>(v0 : T) {\n" +
                "    var v : T = v0\n" +
                "    public fun get() -> T {\n" +
                "        rtn v\n" +
                "    }\n" +
                "}\n" +
                "class IntHolder(v0 : int) : Holder&<int>(v0) {\n" +
                "}\n" +
                "class Wrap(v0 : string) : Holder&<string>(v0) {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var h : IntHolder = new IntHolder(5)\n" +
                "    var w : Wrap = new Wrap(\"s\")\n" +
                "    println(intToString(h.get()))\n" +
                "    println(w.get())\n" +
                "    rtn\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        // `h.get()` 必须是 int、`w.get()` 必须是 string（父类的 T 各按子类代换过），
        // 否则 intToString / println 那两行会红。
        val types = model.classModel.members.keys.filter { it.name == "get" }.map { model.typeOf(it) }.toSet()
        assertEquals(setOf(RsfType.INT, RsfType.STRING), types)
    }

    @Test
    fun `G5 JDK 父类继承来的方法能按名字调 且记下声明它的 JVM 类`() {
        val src = rsfSource(
            "import java.lang.Exception\n" +
                "class MyError(msg : string) : Exception(msg) {\n" +
                "    public fun describe() -> string {\n" +
                "        rtn \"MyError:\" + getMessage()\n" +
                "    }\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val call = model.classModel.implicitJvmCalls.keys.single()
        val resolved = model.classModel.implicitJvmCallOf(call)!!
        assertEquals("getMessage", resolved.method.name)
        // owner 是**声明**它的 Throwable，不是接收者的静态类型 MyError
        assertEquals("java/lang/Throwable", resolved.method.owner)
    }

    // ------------------------------------------------------------ K3：解释器侧（run 路径）

    @Test
    fun `K3 构造链顺序：父构造器 → 自己字段初值 → 构造体`() {
        // 显式写 super(实参) 的那条路最容易错：K1 把它留给指令循环，字段初值必须在
        // 父构造器**之后**、构造体**之前**跑。顺序错一个位置 stdout 就不一样。
        val out = runInterpreted(
            "open class Base {\n" +
                "    constructor(msg : string) {\n" +
                "        println(\"base:\" + msg)\n" +
                "    }\n" +
                "    constructor() {\n" +
                "        println(\"base:default\")\n" +
                "    }\n" +
                "}\n" +
                "open class Child : Base() {\n" +
                "    var x : int = 1\n" +
                "    constructor() {\n" +
                "        super(\"explicit\")\n" +
                "        println(\"child-body:x=\" + intToString(x))\n" +
                "    }\n" +
                "}\n" +
                "open class Grand : Base() {\n" +
                "    constructor() {\n" +
                "        super()\n" +
                "        println(\"grand-post\")\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    println(\"grand-pre\")\n" +
                "    var c : Child = new Child()\n" +
                "    var g : Grand = new Grand()\n" +
                "    rtn\n" +
                "}\n",
        )
        assertEquals("grand-pre\nbase:explicit\nchild-body:x=1\nbase:default\ngrand-post\n", out)
    }

    @Test
    fun `K3 字段绝对下标：父类字段排在前 子类字段跟着排 两边读的是同一格`() {
        val out = runInterpreted(
            "open class Base(p : int) {\n" +
                "    var a : int = p\n" +
                "    var b : int = 10\n" +
                "    public fun sum() -> int {\n" +
                "        rtn a + b\n" +
                "    }\n" +
                "    public fun bump() -> void {\n" +
                "        b = b + 1\n" +
                "        rtn\n" +
                "    }\n" +
                "}\n" +
                "class Child(p : int, c : int) : Base(p) {\n" +
                "    var d : int = c + 1\n" +
                "    public fun total() -> int {\n" +
                "        rtn sum() + d\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var o : Child = new Child(1, 2)\n" +
                "    println(intToString(o.total()))\n" +
                "    o.bump()\n" +
                "    o.b = o.b + 100\n" +
                "    println(intToString(o.total()))\n" +
                "    println(intToString(o.sum()))\n" +
                "    rtn\n" +
                "}\n",
        )
        // a=0、b=1（父类）、d=2（子类）。若子类字段从 0 开始写，`d` 会把 `a` 盖掉，
        // 这三行立刻不对。
        assertEquals("14\n115\n112\n", out)
    }

    @Test
    fun `K3 虚分派：静态类型是父类 跑的是子类那一份覆盖`() {
        val out = runInterpreted(
            "open class A {\n" +
                "    public open fun t() -> string {\n" +
                "        rtn \"A\"\n" +
                "    }\n" +
                "}\n" +
                "open class B : A() {\n" +
                "    override fun t() -> string {\n" +
                "        rtn \"B>\" + super.t()\n" +
                "    }\n" +
                "}\n" +
                "class C : B() {\n" +
                "    override fun t() -> string {\n" +
                "        rtn \"C>\" + super.t()\n" +
                "    }\n" +
                "}\n" +
                "open class Animal(nm : string) {\n" +
                "    var name : string = nm\n" +
                "    public open fun sound() -> string {\n" +
                "        rtn \"...\"\n" +
                "    }\n" +
                "    public fun who() -> string {\n" +
                "        rtn name\n" +
                "    }\n" +
                "}\n" +
                "class Dog(nm : string) : Animal(nm) {\n" +
                "    override fun sound() -> string {\n" +
                "        rtn \"woof\"\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var c : C = new C()\n" +
                "    var b : B = c\n" +
                "    var a : A = c\n" +
                "    println(c.t())\n" +
                "    println(b.t())\n" +
                "    println(a.t())\n" +
                "    var x : Animal = new Dog(\"d\")\n" +
                "    println(x.who() + \":\" + x.sound())\n" +
                "    rtn\n" +
                "}\n",
        )
        // 三行都要是 C>B>A：静态类型换了一层，虚分派照样从运行期类起找；而 `super.t()`
        // 每次只跳一层（C→B→A），跳多了会变成 C>C>A 之类。
        assertEquals("C>B>A\nC>B>A\nC>B>A\nd:woof\n", out)
    }

    @Test
    fun `K3 super(接口) 只跑指定接口那一份默认实现 不是虚分派`() {
        val out = runInterpreted(
            "public interface Greeter {\n" +
                "    public fun greet() -> string {\n" +
                "        rtn \"hi\"\n" +
                "    }\n" +
                "}\n" +
                "public interface Named {\n" +
                "    public fun name() -> string\n" +
                "    public fun greet() -> string {\n" +
                "        rtn \"named-hi\"\n" +
                "    }\n" +
                "}\n" +
                "open class Base {\n" +
                "    public fun base() -> string {\n" +
                "        rtn \"base\"\n" +
                "    }\n" +
                "}\n" +
                "class D : Base(), Greeter {\n" +
                "    public fun greet2() -> string {\n" +
                "        rtn super(Greeter).greet() + \"/\" + base()\n" +
                "    }\n" +
                "}\n" +
                "class E : Base(), Greeter, Named {\n" +
                "    override fun name() -> string {\n" +
                "        rtn \"E\"\n" +
                "    }\n" +
                "    override fun greet() -> string {\n" +
                "        rtn super(Named).greet()\n" +
                "    }\n" +
                "    public fun which() -> string {\n" +
                "        rtn super(Greeter).greet() + \":\" + name()\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var d : D = new D()\n" +
                "    println(d.greet())\n" +
                "    println(d.greet2())\n" +
                "    var e : E = new E()\n" +
                "    println(e.greet())\n" +
                "    println(e.which())\n" +
                "    rtn\n" +
                "}\n",
        )
        // E 自己覆盖了 greet，`super(Named).greet()` 仍必须拿到 Named 的默认实现
        // （走虚分派会回到 E 自己，直接递归死）。
        assertEquals("hi\nhi/base\nnamed-hi\nhi:E\n", out)
    }

    @Test
    fun `K3 接口实现在父类里写、子类继承：按接口调用要沿类链找得到`() {
        val out = runInterpreted(
            "public interface Named {\n" +
                "    public fun label() -> string\n" +
                "}\n" +
                "open class Base : Named {\n" +
                "    override fun label() -> string {\n" +
                "        rtn \"base-label\"\n" +
                "    }\n" +
                "}\n" +
                "class Child : Base() {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var n : Named = new Child()\n" +
                "    println(n.label())\n" +
                "    rtn\n" +
                "}\n",
        )
        // 运行期类是 Child，实现却写在 Base 里——只查运行期类自己的方法表会报
        // 「没有实现接口方法」。
        assertEquals("base-label\n", out)
    }

    @Test
    fun `K3 JDK 父类：反射造出父类实例 getMessage 能调`() {
        val out = runInterpreted(
            "import java.lang.Exception\n" +
                "class MyError(msg : string) : Exception(msg) {\n" +
                "    public fun describe() -> string {\n" +
                "        rtn \"MyError:\" + getMessage()\n" +
                "    }\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var e : MyError = new MyError(\"boom\")\n" +
                "    println(e.describe())\n" +
                "    println(e.getMessage())\n" +
                "    rtn\n" +
                "}\n",
        )
        // 两行分别走两条路：`getMessage()`（隐式 this，implicitJvmCalls）与
        // `e.getMessage()`（显式成员访问，memberTargets→ResolvedMethod）。
        // 两条都得把接收者换成配对出来的 JDK 父类实例（解释器里 MyError 没有真实 class）。
        assertEquals("MyError:boom\nboom\n", out)
    }

    @Test
    fun `K3 toString 把整条父链的字段都渲染出来（父类字段在前）`() {
        val out = runInterpreted(
            "open class Base(x : int) {\n" +
                "    var a : int = x\n" +
                "}\n" +
                "class Child(x : int, y : int) : Base(x) {\n" +
                "    var b : int = y\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var c : Child = new Child(1, 2)\n" +
                "    println(c)\n" +
                "    rtn\n" +
                "}\n",
        )
        // 这是**两端约定**：字段布局是「父链在前 + 自己」，toString 也按这个顺序与命名来。
        // 解释器这边按整条链渲染；字节码端（K2）的 emitUserToString 现在还只遍历自己的
        // 字段，落地时必须对齐成同一条链，否则同一份程序两端打出 `Child(a=1, b=2)` 与
        // `Child(b=2)` 两种文本。
        assertEquals("Child(a=1, b=2)\n", out)
    }

    // ------------------------------------------------------------ 助手

    //
    // 口径与 InterfaceRuntimeTest.kt / InteropTest.kt 同一套：源码 → Rsf.analyze →
    // 解释执行一遍；Rsf.build 出 .class → 类加载器 → 反射调 main 再跑一遍；两边 stdout
    // 逐字节比较。K2 起「只修了一端」会被这里咬住。

    /** 两端各跑一遍，断言 stdout 逐字节相同，返回解释器那一侧的行列表。 */
    private fun bothEnds(text: String, className: String): List<String> {
        val interpreted = runInterpreted(text)
        val compiled = runCompiled(text, className)
        assertEquals(
            normalize(interpreted),
            normalize(compiled),
            "解释器与字节码对类继承必须逐字节一致",
        )
        return normalize(interpreted).trim().lines()
    }

    /**
     * `Rsf.build` 出来的 .class 跑一遍 main。
     *
     * 类加载器按需 `defineClass`（不是按 build 的文件顺序显式 define 一批）：
     * 定义子类时 JVM 要解析它的父类，而父类那时可能还没被 define 过。
     */
    private fun runCompiled(text: String, className: String): String {
        val src = rsfSource(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(Rsf.parse(src), src), src, className, dir)
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                val file = File(dir, name.replace('.', '/') + ".class")
                if (!file.isFile) throw ClassNotFoundException(name)
                val bytes = file.readBytes()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        val programName = out.classFiles.first().relativeTo(dir).path
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')
            .replace('/', '.')
        return runMain(loader.loadClass(programName))
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

    /** build 一遍，取出其中某个 .class 的形状（不跑）。 */
    private fun compiledShape(text: String, className: String, fileName: String): ClassShape {
        val src = rsfSource(text)
        val dir = tempDir()
        val out = Rsf.build(Rsf.analyze(Rsf.parse(src), src), src, className, dir)
        val file = out.classFiles.firstOrNull { it.name == fileName }
            ?: throw AssertionError("产物里没有 $fileName：${out.classFiles.map { it.name }}")
        return shapeOf(file.readBytes())
    }

    /**
     * 用 ASM 读一遍 .class，记下父类名、标志与指令文本——`javap -c` 的可断言版本。
     *
     * 帧与调试信息都跳过，只留指令；`visitCode` 没被调过的方法（抽象方法）指令列表为空。
     */
    private fun shapeOf(bytes: ByteArray): ClassShape {
        val methods = ArrayList<MethodShape>()
        var superName: String? = null
        var access = 0
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    acc: Int,
                    name: String,
                    signature: String?,
                    superClass: String?,
                    interfaces: Array<out String>?,
                ) {
                    access = acc
                    superName = superClass
                }

                override fun visitMethod(
                    acc: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val instructions = ArrayList<String>()
                    methods.add(MethodShape(name, descriptor, acc, instructions))
                    return object : MethodVisitor(Opcodes.ASM9) {
                        private fun add(text: String) {
                            instructions.add(text)
                        }

                        override fun visitInsn(opcode: Int) = add(opcodeName(opcode))
                        override fun visitVarInsn(opcode: Int, v: Int) = add("${opcodeName(opcode)} $v")
                        override fun visitTypeInsn(opcode: Int, type: String) = add("${opcodeName(opcode)} $type")
                        override fun visitFieldInsn(opcode: Int, o: String, n: String, d: String) =
                            add("${opcodeName(opcode)} $o.$n:$d")

                        override fun visitMethodInsn(opcode: Int, o: String, n: String, d: String, itf: Boolean) =
                            add("${opcodeName(opcode)} $o.$n$d itf=$itf")

                        override fun visitJumpInsn(opcode: Int, label: Label) = add(opcodeName(opcode))
                        override fun visitLdcInsn(value: Any?) = add("LDC $value")
                        override fun visitIntInsn(opcode: Int, operand: Int) = add("${opcodeName(opcode)} $operand")
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return ClassShape(superName, access, methods)
    }

    private fun opcodeName(opcode: Int): String = when (opcode) {
        Opcodes.ALOAD -> "ALOAD"
        Opcodes.ASTORE -> "ASTORE"
        Opcodes.ILOAD -> "ILOAD"
        Opcodes.ISTORE -> "ISTORE"
        Opcodes.RETURN -> "RETURN"
        Opcodes.IRETURN -> "IRETURN"
        Opcodes.ARETURN -> "ARETURN"
        Opcodes.DUP -> "DUP"
        Opcodes.NEW -> "NEW"
        Opcodes.GETFIELD -> "GETFIELD"
        Opcodes.PUTFIELD -> "PUTFIELD"
        Opcodes.GETSTATIC -> "GETSTATIC"
        Opcodes.PUTSTATIC -> "PUTSTATIC"
        Opcodes.INVOKESPECIAL -> "INVOKESPECIAL"
        Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL"
        Opcodes.INVOKESTATIC -> "INVOKESTATIC"
        Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE"
        else -> "#$opcode"
    }

    /** 一个类文件里我们关心的形状：父类名、类标志、每条方法的标志与指令序列。 */
    private class ClassShape(val superName: String?, val access: Int, val methods: List<MethodShape>) {
        fun hasFlag(flag: Int): Boolean = access and flag != 0
    }

    private class MethodShape(
        val name: String,
        val descriptor: String,
        val access: Int,
        val instructions: List<String>,
    ) {
        fun hasFlag(flag: Int): Boolean = access and flag != 0
    }

    private fun tempDir(): File = File.createTempFile("rsf-inherit", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    /**
     * 跑解释器并把 stdout 收成字符串（K3 的回归全部靠它）。
     *
     * 换行统一成 LF：`println` 用的是平台换行（本机上是 CRLF），断言里写 `\n` 更清楚，
     * 也和「解释器源码里一行一行对」的读法一致。
     */
    private fun runInterpreted(text: String): String {
        val src = rsfSource(text)
        val model: SemanticModel = Rsf.analyze(Rsf.parse(src), src)
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
        return normalize(buf.toString("UTF-8"))
    }

    /** 平台换行 → LF。 */
    private fun normalize(s: String): String = s.replace("\r\n", "\n")

    private fun fails(text: String): RsfError {
        val src = rsfSource(text)
        // 解析也包在里面：几条语法级的反例（两个父类、内置类型当父类、抽象方法带体）
        // 是在 Parser 里就报的，报错阶段是 "parser"。
        return assertFailsWith<RsfError> {
            val program = Rsf.parse(src)
            Rsf.analyze(program, src)
        }
    }

    // ------------------------------------------------- 阶段 25 K4：JDK 父类那一支收口（缺口 A–F）

    /**
     * 缺口 A：classpath 上 `final` 的类不许当父类。
     *
     * 解释器侧本来能跑通（它只把父类实例挂在 `jdkParent` 上，不做真正的 `extends`），
     * 字节码侧却在 JVM 装载时炸：`IncompatibleClassChangeError: class SB cannot inherit
     * from final class java.lang.StringBuilder`。两端不许分叉，所以拦在编译期。
     */
    @Test
    fun `缺口 A classpath 上 final 的类不能当父类`() {
        val e = fails(
            "import java.lang.StringBuilder\n" +
                "class SB(s : string) : StringBuilder(s) {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(e.detail.contains("final"), e.detail)
        assertTrue(e.detail.contains("StringBuilder"), e.detail)

        // 元数据这一侧也得认得出 final 标志（Checker 拦人就靠它）
        assertTrue(ClassPath.find("java/lang/StringBuilder")!!.isFinal, "StringBuilder 在 JDK 里是 final 的")
        assertTrue(!ClassPath.find("java/lang/Exception")!!.isFinal, "Exception 不是 final")
    }

    /**
     * 缺口 B：JDK 抽象父类继承来的抽象方法必须在这一支上被实现。
     *
     * `class L : AbstractList&<int>()` 现在放行的话，解释器能 `new` 出对象、字节码产物也能
     * 装载，直到真去调 `get(0)` 才抛 `AbstractMethodError`——又是一处分叉。报错必须点名缺了谁。
     */
    @Test
    fun `缺口 B 继承 JDK 抽象类要把它继承来的抽象方法实现掉`() {
        val e = fails(
            "import java.util.AbstractList\n" +
                "class L : AbstractList&<int>() {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(e.detail.contains("get(int)"), e.detail)
        assertTrue(e.detail.contains("size()"), e.detail)
    }

    /**
     * 缺口 C：JDK 父类上的 `super.f()` 是**非虚调用**——打到声明类那一份实现。
     *
     * 字节码必须是 `INVOKESPECIAL java/lang/Throwable.getMessage`（声明类），不能是
     * `INVOKEVIRTUAL`（那会按运行期类型重新分派）；解释器同理，取的是声明类的反射方法。
     */
    @Test
    fun `缺口 C JDK 父类上的 super 调用打到声明类那一份`() {
        val program = "import java.lang.Exception\n" +
            "class MyErr(msg : string) : Exception(msg) {\n" +
            "    fun describe() -> string {\n" +
            "        rtn \"e:\" + super.getMessage()\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var e : MyErr = new MyErr(\"boom\")\n" +
            "    println(e.describe())\n" +
            "    rtn\n" +
            "}\n"

        assertEquals(listOf("e:boom"), bothEnds(program, "RsfInheritJdkSuper"))

        // 语义模型：目标是 JDK 方法，owner 是**声明类**（getMessage 声明在 Throwable 上）
        val src = rsfSource(program)
        val ast = Rsf.parse(src)
        val model = Rsf.analyze(ast, src)
        val err = ast.classes.single { it.name == "MyErr" }
        val ret = err.methods.single { it.name == "describe" }.body!!.statements[0] as ReturnStmt
        val call = (ret.value as BinaryExpr).right as MemberExpr
        val target = model.classModel.superCallOf(call)
        assertTrue(target is SuperCall.Jvm, target.toString())
        target as SuperCall.Jvm
        assertEquals("java/lang/Throwable", target.member.method.owner)
        assertEquals("getMessage", target.member.method.name)

        // 字节码：INVOKESPECIAL，owner 是声明类而不是直接父类 Exception
        val shape = compiledShape(program, "RsfInheritJdkSuperShape", "MyErr.class")
        val insns = shape.methods.single { it.name == "describe" }.instructions
        assertTrue(
            insns.contains("INVOKESPECIAL java/lang/Throwable.getMessage()Ljava/lang/String; itf=false"),
            insns.toString(),
        )
    }

    /**
     * 缺口 D：父类构造器只由语义模型选一次，两个后端都只读那一份。
     *
     * 在这之前 Checker 选完就扔掉，K2/K3 各自拿一份抄来的打分函数再选一遍——两处
     * 打分的规则一旦走偏，同一段代码在两端就会选到不同的 `<init>`。
     */
    @Test
    fun `缺口 D 父类构造器由语义模型选中 两端读同一份`() {
        val program = "import java.lang.Exception\n" +
            "class MyError(msg : string) : Exception(msg) {\n" +
            "    fun describe() -> string {\n" +
            "        rtn \"MyError:\" + getMessage()\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var e : MyError = new MyError(\"boom\")\n" +
            "    println(e.describe())\n" +
            "    rtn\n" +
            "}\n"

        assertEquals(listOf("MyError:boom"), bothEnds(program, "RsfInheritCtorJvm"))

        val src = rsfSource(program)
        val ast = Rsf.parse(src)
        val model = Rsf.analyze(ast, src)
        val decl = ast.classes.single { it.name == "MyError" }
        val ctor = decl.methods.single { it.isPrimary }
        val chosen = model.classModel.superDelegationOf(ctor)!!.jvmCtor!!
        assertEquals("java/lang/Exception", chosen.owner)
        assertEquals("(Ljava/lang/String;)V", chosen.descriptor)

        // 字节码侧打的就是这一份：Exception(String)
        val shape = compiledShape(program, "RsfInheritCtorJvmShape", "MyError.class")
        val init = shape.methods.single { it.name == "<init>" }.instructions
        assertTrue(
            init.contains("INVOKESPECIAL java/lang/Exception.<init>(Ljava/lang/String;)V itf=false"),
            init.toString(),
        )

        // 没有主构造、只有 `constructor(m) { super(m) }` 的那种类（p15 的形状）：
        // 选中的 `<init>` 必须挂在**这条委托边**上——挂在「类 → 构造器」的一张表上，
        // 同一个类的两条边就会互相覆盖。
        val secondary = "import java.lang.Exception\n" +
            "class E : Exception() {\n" +
            "    constructor(m : string) {\n" +
            "        super(m)\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var e : E = new E(\"boom\")\n" +
            "    println(e.getMessage())\n" +
            "    rtn\n" +
            "}\n"
        assertEquals(listOf("boom"), bothEnds(secondary, "RsfInheritCtorJvmSecondary"))

        val secSrc = rsfSource(secondary)
        val secAst = Rsf.parse(secSrc)
        val secModel = Rsf.analyze(secAst, secSrc)
        val secCtor = secAst.classes.single { it.name == "E" }.methods.single { !it.isPrimary }
        assertEquals(
            "(Ljava/lang/String;)V",
            secModel.classModel.superDelegationOf(secCtor)!!.jvmCtor!!.descriptor,
        )
    }

    /**
     * 缺口 E：JDK 父类的 `protected` 构造器，解释器也得建得出父类实例。
     *
     * `ClassLoader()` 是 `protected` 的，`trySetAccessible()` 实测直接返回 false
     * （java.base 不开 java.lang），反射调不动；解释器只能在自己包里 define 一个
     * 「公开构造器 + 调父类那一个构造器」的桥（Interpreter.kt 末尾的 JdkParentBridge）。
     * 一旦反射失败，报错要落成带 文件:行:列 的 RsfError，而不是让 Java 异常穿出去。
     */
    @Test
    fun `缺口 E JDK 父类的 protected 构造器解释器也建得出来`() {
        val program = "import java.lang.ClassLoader\n" +
            "class MyLoader : ClassLoader() {\n" +
            "    fun tag() -> string {\n" +
            "        rtn \"loader\"\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var l : MyLoader = new MyLoader()\n" +
            "    println(l.tag())\n" +
            "    rtn\n" +
            "}\n"

        // 解释器这一侧走的是子类桥；字节码侧是正常的 invokespecial 到父类 protected 构造器
        assertEquals(listOf("loader"), bothEnds(program, "RsfInheritProtectedCtor"))
    }

    /**
     * 缺口 F：static 不继承，报文不许自相矛盾。
     *
     * 原来报「类 'Child' 里没有方法 'make'」后面还跟一句成员提示，提示里正好列着
     * `make`（名字表不分 static/实例）——读起来像「没有，但有」。static 只能写在
     * 声明它的类上（DESIGN-25 §3）。
     */
    @Test
    fun `缺口 F static 方法不继承 报文不再自相矛盾`() {
        val e = fails(
            "open class Base {\n" +
                "    static fun make() -> string {\n" +
                "        rtn \"base-static\"\n" +
                "    }\n" +
                "}\n" +
                "class Child : Base() {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    println(Child.make())\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(e.detail.contains("静态"), e.detail)
        assertTrue(e.detail.contains("不继承"), e.detail)
        assertTrue(e.detail.contains("Base.make"), e.detail)
        assertTrue(!e.detail.contains("（有："), e.detail)
    }

    // ------------------------------- 阶段 25 K4b：JDK 父类用户类上的 Object 方法族

    /**
     * K4b①：`toString()` 与裸 `println(对象)` 是同一个字符串（ObjectFormat 渲染）。
     *
     * 字节码侧发的是 `invokevirtual <声明类>.toString`，运行期接收者是 rsf 生成的类，
     * 它**总是**带着 rsf 生成的 `toString()`；解释器里的对象没有对应的 JVM 类，
     * 原来会反射打到配对出来的 JDK 父类实例上，印出 `java.lang.Exception: x`。
     * 这里把三种调用形态都钉住：显式 `a.toString()`、隐式 `toString()`、
     * `this.toString()`，再加上静态类型是 JDK 父类的上转型接收者。
     */
    @Test
    fun `K4b JDK 父类用户类的 toString 与裸 println 同一个字符串`() {
        val program = "import java.lang.Exception\n" +
            "class E2(m : string) : Exception(m) {\n" +
            "    fun implicit() -> string {\n" +
            "        rtn toString()\n" +
            "    }\n" +
            "    fun explicit() -> string {\n" +
            "        rtn this.toString()\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var a : E2 = new E2(\"x\")\n" +
            "    println(a.toString())\n" +
            "    println(a.implicit())\n" +
            "    println(a.explicit())\n" +
            "    println(a)\n" +
            "    var up : Exception = new E2(\"x\")\n" +
            "    println(up.toString())\n" +
            "    rtn\n" +
            "}\n"

        assertEquals(
            listOf("E2()", "E2()", "E2()", "E2()", "E2()"),
            bothEnds(program, "RsfK4bToString"),
        )
    }

    /**
     * K4b②：`equals(其他)` 按 **rsf 对象的同一性**算。
     *
     * 字节码侧继承来的是 `java/lang/Object.equals`（同一性），解释器里就得是
     * `self === 其他`——反射打到 JDK 父类实例上会变成「拿父类实例比」，`a.equals(a)`
     * 都会答 false。
     */
    @Test
    fun `K4b JDK 父类用户类的 equals 按 rsf 对象同一性算`() {
        val program = "import java.lang.Exception\n" +
            "class E3(m : string) : Exception(m) {\n" +
            "    fun same(o : E3) -> bool {\n" +
            "        rtn this.equals(o)\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var a : E3 = new E3(\"x\")\n" +
            "    var b : E3 = new E3(\"x\")\n" +
            "    println(a.equals(a))\n" +
            "    println(a.equals(b))\n" +
            "    println(a.same(a))\n" +
            "    println(b.same(a))\n" +
            "    println(a.equals(null))\n" +
            "    rtn\n" +
            "}\n"

        assertEquals(
            listOf("true", "false", "true", "false", "false"),
            bothEnds(program, "RsfK4bEquals"),
        )
    }

    /**
     * K4b③：`hashCode()` 用 identity hash，**只做单测、不进探针 golden**。
     *
     * 两端算出来的**数字**永远不可能一样（JVM 上 hash 的是 rsf 生成类的实例，解释器里
     * hash 的是 `RsfObject`），所以只钉「同一性语义」这件事：同一个对象稳定同值、
     * 不同对象不同值、显式与隐式调用同值。（两个活对象的 identity hash 撞车概率约
     * 2^-31，真撞上算彩票。）
     */
    @Test
    fun `K4b JDK 父类用户类的 hashCode 按 identity hash 算`() {
        val program = "import java.lang.Exception\n" +
            "class E4(m : string) : Exception(m) {\n" +
            "    fun h() -> int {\n" +
            "        rtn hashCode()\n" +
            "    }\n" +
            "}\n" +
            "fun main() -> void {\n" +
            "    var a : E4 = new E4(\"x\")\n" +
            "    var b : E4 = new E4(\"x\")\n" +
            "    println(a.hashCode() == a.hashCode())\n" +
            "    println(a.hashCode() == b.hashCode())\n" +
            "    println(a.h() == a.hashCode())\n" +
            "    rtn\n" +
            "}\n"

        assertEquals(
            listOf("true", "false", "true"),
            bothEnds(program, "RsfK4bHashCode"),
        )
    }

    /**
     * K4b④：用户类上的 `getClass()` 编译期就被拒，理由写在报文里。
     *
     * 解释器里的对象是 `RsfObject`（类名 + 字段平数组），配对出来的只有 JDK 父类实例
     * ——答出来是 `class java.lang.Exception`；字节码那边是 rsf 自己生成的类。两端拿不到
     * 同一个 `Class` 对象，所以只能拦。**接收者本来就是 JDK 对象的那条路照旧可用**。
     */
    @Test
    fun `K4b 用户类上的 getClass 编译期就被拒`() {
        val e = fails(
            "import java.lang.Exception\n" +
                "class Err3(m : string) : Exception(m) {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var a : Err3 = new Err3(\"x\")\n" +
                "    println(a.getClass())\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(e.detail.contains("getClass"), e.detail)
        assertTrue(e.detail.contains("Class"), e.detail)
        assertTrue(e.detail.contains("两端拿不到同一个 Class"), e.detail)

        // 合法路径：接收者真的是 JDK 对象，两端照旧一致
        val jdk = "import java.lang.Exception\n" +
            "fun main() -> void {\n" +
            "    var e : Exception = new Exception(\"boom\")\n" +
            "    println(e.getClass())\n" +
            "    rtn\n" +
            "}\n"
        assertEquals(listOf("class java.lang.Exception"), bothEnds(jdk, "RsfK4bGetClassJdk"))
    }

    /**
     * K4b⑤：`java/lang/Object` 上**别的**实例方法不经 JDK 父链漏进用户类。
     *
     * `wait` / `notify` / `clone` 这些在解释器里只能反射打到配对出来的 JDK 父类实例上
     * （真身错了，`clone` 还因为 protected 直接抛 Java 异常），索性按「查不到」处理——
     * 这就是那句原则：除了 rsf 自己定义语义的 `toString` / `equals` / `hashCode`，
     * Object 的方法一个都不漏进来。
     */
    @Test
    fun `K4b Object 上别的实例方法不经 JDK 父链漏进用户类`() {
        val notify = fails(
            "import java.lang.Exception\n" +
                "class Err5(m : string) : Exception(m) {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var a : Err5 = new Err5(\"x\")\n" +
                "    a.notify()\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(notify.detail.contains("没有方法 'notify'"), notify.detail)

        val clone = fails(
            "import java.lang.Exception\n" +
                "class Err6(m : string) : Exception(m) {\n" +
                "}\n" +
                "fun main() -> void {\n" +
                "    var a : Err6 = new Err6(\"x\")\n" +
                "    a.clone()\n" +
                "    rtn\n" +
                "}\n",
        )
        assertTrue(clone.detail.contains("没有方法 'clone'"), clone.detail)
    }

    // --------------------------------- 阶段 25 K5a-fix：类头修饰词顺序（对齐 Kotlin）

    /**
     * K5a-fix：类头的权限修饰词（`public` / `protected` / `private`）与继承修饰词
     * （`open` / `final` / `abstract`）**任意穿插**——两种顺序都 ACCEPT，且控制级别与三个继承
     * 标志一个都不能丢（顺序不是语义）。修之前 `public open class A {}` 撞的是
     * `class 声明缺少 'class'（实际读到 'open'）`，只有 `open public class` 能过。
     */
    @Test
    fun `K5a-fix 类头修饰词两种顺序都收`() {
        val pairs = listOf(
            "public open class A { }" to "open public class A { }",
            "public final class B { }" to "final public class B { }",
            "public abstract class C { }" to "abstract public class C { }",
            "private open class D { }" to "open private class D { }",
            "protected abstract class E { }" to "abstract protected class E { }",
        )
        for ((first, second) in pairs) {
            val a = Rsf.parse(rsfSource(first)).classes.single()
            val b = Rsf.parse(rsfSource(second)).classes.single()
            assertEquals(a.control, b.control, "两种顺序的控制级别必须一样：$first / $second")
            assertEquals(a.isOpen, b.isOpen, "isOpen 必须一样：$first / $second")
            assertEquals(a.isFinal, b.isFinal, "isFinal 必须一样：$first / $second")
            assertEquals(a.isAbstract, b.isAbstract, "isAbstract 必须一样：$first / $second")
        }

        // 不是「两边都退化成 DEFAULT」：README.md:978 / DESIGN-25.md:10 的写法要真的收成
        // PUBLIC + open，而且整条链路（解析 + 语义）都不报错。
        val doc = rsfSource(
            "public open class A(x : int) {\n" +
                "    public open fun tag() -> string { rtn \"a\" }\n" +
                "}\n",
        )
        val program = Rsf.parse(doc)
        Rsf.analyze(program, doc) // 语义阶段也必须收下（不抛就是不报错）
        val a = program.classes.single()
        assertEquals(ControlType.PUBLIC, a.control)
        assertTrue(a.isOpen, "public open class 要收成 open")
        assertTrue(!a.isFinal && !a.isAbstract, "没有写 final / abstract 就都不该置位")
    }

    /**
     * K5a-fix 的硬判据：顺序不参与 AST——`public open class A { ... }` 与
     * `open public class A { ... }` 两份源码的 dump 必须**逐字节相同**。
     */
    @Test
    fun `K5a-fix 两种顺序的 AST dump 逐字节相同`() {
        val member = "    public open fun tag() -> string { rtn \"a\" }\n"
        val a = AstDump.dump(Rsf.parse(rsfSource("public open class A {\n" + member + "}\n")))
        val b = AstDump.dump(Rsf.parse(rsfSource("open public class A {\n" + member + "}\n")))
        assertEquals(a, b)
        // 顺带确认 dump 里确实记着 PUBLIC + OPEN（不是两个空壳相等）
        assertTrue(a.contains("control=PUBLIC"), a)
        assertTrue(a.contains("OPEN"), a)
    }

    /**
     * K5a-fix 只放松顺序，不放松互斥：`open` + `final`（含被权限词**隔开**的
     * `open public final class`）、`abstract` 与另两个混写、同一个词写两遍，仍按原文案当场拒。
     */
    @Test
    fun `K5a-fix 类头互斥规则没被放松`() {
        val openFinal = fails("open final class A { }")
        assertTrue(openFinal.detail.contains("'open' 与 'final' 不能同时写"), openFinal.detail)

        // 顺序放开后 `open public final class` 不再在 'class' 上撞错，但也**不许**被收下：
        // 两段继承修饰词必须合起来判互斥（mergeInherit 那条通路）。
        val splitOpenFinal = fails("open public final class B { }")
        assertTrue(splitOpenFinal.detail.contains("'open' 与 'final' 不能同时写"), splitOpenFinal.detail)

        val abstractOpen = fails("abstract public open class C { }")
        assertTrue(
            abstractOpen.detail.contains("'abstract' 不能和 'open' / 'final' 写在一起"),
            abstractOpen.detail,
        )

        val twice = fails("open open class D { }")
        assertTrue(twice.detail.contains("'open' 写了两遍"), twice.detail)

        // 权限修饰词仍然只收一个：第二个落到 expect(KW_CLASS) 上，文案与修正前一致。
        val twoControls = fails("public private class E { }")
        assertTrue(twoControls.detail.contains("class 声明缺少 'class'"), twoControls.detail)
    }
}

private fun rsfSource(text: String): SourceFile = SourceFile.of("<test>", text)
