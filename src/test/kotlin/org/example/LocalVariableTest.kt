package org.example

import org.example.complie.Rsf
import org.example.complie.diag.SourceFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 26 刀：调试信息里的 **LocalVariableTable**（`codegen/BytecodeCompiler.kt` 与
 * `rsfSrc/bootstrap/Backend.rsf` 两端同口径）。
 *
 * 这一层**只**用 ASM `ClassReader` 读类文件的调试属性，不跑任何程序：条目该不该有、
 * 名字/描述符/槽位对不对，都是类文件形状的事，跑起来看不出来。断言口径与工单一致：
 * 1. 形参按**声明顺序**、用 `param.slot` 的槽位（实例方法第一个形参从 1 起）；
 * 2. `var` 局部变量带名字和真实槽位（入口 main 的 `localOffset = 1` 要算进去）；
 * 3. 构造器 slot 0 是 `this`（描述符 = `L<本类内部名>;`），其后才是形参；
 * 4. 抽象方法没有 `Code`，也就没有 `LocalVariableTable`；
 * 5. 内层遮蔽时同名不同槽**两条都要在**（不是覆盖、不是去重）。
 *
 * 另外两条边界也一起钉住：`catch` 绑定算局部变量（名字来自 AST，槽位来自异常表），
 * 而编译器自己造的临时槽（入口 main 的 `args`、`this` 之外的合成形参）**不进表**。
 */
class LocalVariableTest {

    // ------------------------------------------------------------ (1) 形参按声明序

    @Test
    fun `实例方法的形参按声明顺序登记且从槽 1 起`() {
        val dir = compile(
            """
            class Calc {
                fn sum(a : int, b : int, c : int) -> int {
                    rtn a + b + c
                }
            }

            fn main() -> void { println("ok") }
            """,
            "RsfLvtParam",
        )

        val sum = locals(dir, "Calc", "sum", "(III)I")
        assertEquals(
            listOf(Lv("this", "LCalc;", 0), Lv("a", "I", 1), Lv("b", "I", 2), Lv("c", "I", 3)),
            sum,
            "实例方法应该是 this → 形参（声明序），槽位 0/1/2/3",
        )
    }

    // --------------------------------------------------- (2) var 局部变量的名字与槽位

    @Test
    fun `var 局部变量带名字和真实槽位且入口 main 的偏移算进去`() {
        val dir = compile(
            """
            fn main() -> void {
                var a : int = 1
                var b : string = "s"
                println(b + a)
                rtn
            }
            """,
            "RsfLvtLocal",
        )

        val main = locals(dir, "RsfLvtLocal", "main", "([Ljava/lang/String;)V")
        assertEquals(listOf(Lv("a", "I", 1), Lv("b", "Ljava/lang/String;", 2)), main)
        assertTrue(
            main.none { it.desc == "[Ljava/lang/String;" },
            "入口 main 的 args 是编译器塞的槽，不许进 LocalVariableTable：$main",
        )
    }

    // ------------------------------------------------------------ (3) 构造器 this + 形参

    @Test
    fun `构造器的 slot 0 是 this 其后是形参`() {
        val dir = compile(
            """
            class Point(x : int, y : int) {
                var x : int = x
                var y : int = y
            }

            fn main() -> void { println("ok") }
            """,
            "RsfLvtCtor",
        )

        assertEquals(
            listOf(Lv("this", "LPoint;", 0), Lv("x", "I", 1), Lv("y", "I", 2)),
            locals(dir, "Point", "<init>", "(II)V"),
        )
    }

    // --------------------------------------------------------- (4) 抽象方法没有 Code

    @Test
    fun `抽象方法没有 Code 也就没有 LocalVariableTable`() {
        val dir = compile(
            """
            pub interface Greeter {
                fn greet(name : string) -> string
            }

            fn main() -> void { println("ok") }
            """,
            "RsfLvtAbstract",
        )

        val greet = readMethod(dir, "Greeter", "greet", "(Ljava/lang/String;)Ljava/lang/String;")
        assertFalse(greet.hasCode, "抽象方法不应该有 Code 属性（accept 只看 ACC_ABSTRACT 不够）")
        assertTrue(greet.entries.isEmpty(), "没有 Code 就不可能有 LocalVariableTable：${greet.entries}")
    }

    // --------------------------------------------------------------- (5) 遮蔽：两条都在

    @Test
    fun `内层遮蔽时同名不同槽两条都登记`() {
        val dir = compile(
            """
            fn main() -> void {
                var x : int = 1
                {
                    var x : int = 99
                    println("inner = " + x)
                }
                println("outer = " + x)
                rtn
            }
            """,
            "RsfLvtShadow",
        )

        val entries = locals(dir, "RsfLvtShadow", "main", "([Ljava/lang/String;)V").filter { it.name == "x" }
        assertEquals(
            listOf(Lv("x", "I", 1), Lv("x", "I", 2)),
            entries,
            "遮蔽不该去重，两条同名条目按槽位升序排：$entries",
        )
    }

    // ------------------------------------------------------------------ catch 绑定

    @Test
    fun `catch 绑定算局部变量且槽位取自异常表`() {
        val dir = compile(
            """
            pub fn parse(text : string) -> !int {
                if (text.length() == 0) {
                    fail "空字符串没法转数字"
                }
                rtn 42
            }

            fn main() -> void {
                var before : int = 7
                try {
                    var n : int = parse("")!!
                    println("n = " + n)
                } catch (e : string) {
                    println("接住了：" + e)
                }
                println(before)
                rtn
            }
            """,
            "RsfLvtCatch",
        )

        val entries = locals(dir, "RsfLvtCatch", "main", "([Ljava/lang/String;)V")
        // 槽位是 `Lowering` 分配的：catch 绑定的槽在 try 体之前就占下，所以 e 在 n 前面。
        // 表按**槽位升序**写（不是声明序），两端同一条规则（`sortedBy { it.slot }`）。
        assertEquals(
            listOf(
                Lv("before", "I", 1),
                Lv("e", "Ljava/lang/String;", 2),
                Lv("n", "I", 3),
            ),
            entries,
            "catch 绑定 e 要按异常表里的槽位登记：$entries",
        )
    }

    // ================================================================ 工具

    private data class Lv(val name: String, val desc: String, val slot: Int)

    private class MethodLvt(val hasCode: Boolean, val entries: List<Lv>)

    private fun compile(text: String, className: String): File {
        val src = SourceFile.of("<lvt>", text.trimIndent())
        val dir = File.createTempFile("rsf-lvt", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        Rsf.build(Rsf.analyze(src), src, className, dir)
        return dir
    }

    private fun locals(dir: File, internalName: String, name: String, desc: String): List<Lv> {
        val m = readMethod(dir, internalName, name, desc)
        assertTrue(m.hasCode, "$internalName.$name$desc 应该有 Code")
        return m.entries
    }

    private fun readMethod(dir: File, internalName: String, name: String, desc: String): MethodLvt {
        val bytes = File(dir, "$internalName.class").readBytes()
        var found: MethodLvt? = null
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                mName: String,
                mDesc: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (mName != name || mDesc != desc) return null
                var hasCode = false
                val entries = mutableListOf<Lv>()
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitCode() {
                        hasCode = true
                    }

                    override fun visitLocalVariable(
                        vName: String,
                        vDesc: String,
                        vSignature: String?,
                        start: Label,
                        end: Label,
                        index: Int,
                    ) {
                        assertEquals("", vSignature ?: "", "signature 一律 null（不发 LocalVariableTypeTable）")
                        entries += Lv(vName, vDesc, index)
                    }

                    override fun visitEnd() {
                        found = MethodLvt(hasCode, entries)
                    }
                }
            }
        }
        ClassReader(bytes).accept(visitor, 0)
        return found ?: throw AssertionError("$internalName 里没有方法 $name$desc")
    }
}
