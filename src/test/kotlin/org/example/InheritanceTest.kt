package org.example

import org.example.complie.Rsf
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.SuperExpr
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.sema.RsfType
import org.example.complie.sema.SemanticModel
import org.example.complie.sema.UserMember
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 25 「类继承」的前端与语义（K1 刀）+ 解释器侧跑得动的回归（K3 刀）。
 *
 * 前半段只测**前端**：语法收不收、Checker 放行还是报错、报什么、给后端留了什么模型。
 * 后半段（`K3：解释器侧` 那一节）测解释器真的把带父类的程序跑成什么样：构造链顺序、
 * 字段绝对下标、父链方法查找、`super.f()` / `super(接口).f()`、JDK 父类那条链。
 * 字节码端的支持是 K2 的事，不在这里测——K3 已撤掉解释器的守卫，`build` 仍会显式报
 * 「K2 还没实现」（见下面 `带父类的程序 build 要显式报还没实现`），所以这里不碰 `Rsf.build`。
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
        assertTrue(target is UserMember.Method, target.toString())
        assertEquals("Base", (target as UserMember.Method).className)
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
        assertTrue(target is UserMember.Method, target.toString())
        // 挑中的是 B 那一份默认实现：接口归属 + 接口名
        assertEquals("B", (target as UserMember.Method).className)
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

    // ------------------------------------------------------------ 后端守卫（K2）

    @Test
    fun `带父类的程序 build 要显式报还没实现`() {
        val src = rsfSource(
            "open class Base(x : int) {\n" +
                "}\n" +
                "class D(y : int) : Base(y) {\n" +
                "}\n",
        )
        val model = Rsf.analyze(Rsf.parse(src), src)
        val e = assertFailsWith<RsfError> { Rsf.build(model, src, "D", tempDir()) }
        assertEquals("codegen", e.phase)
        // 点名是 K2 那一刀，而且说出是哪个类有父类——绝不静默产错字节码
        assertTrue(e.detail.contains("K2"), e.detail)
        assertTrue(e.detail.contains("Base"), e.detail)
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
}

private fun rsfSource(text: String): SourceFile = SourceFile.of("<test>", text)
