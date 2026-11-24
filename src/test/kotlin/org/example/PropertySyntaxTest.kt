package org.example

import org.example.complie.ast.AccessorKind
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.Lexer
import org.example.complie.parsing.Parser
import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 79：属性访问器的**语法与 AST**（`{ get; set }` / `{ is }` / 显式体）。
 *
 * 这一版只到「解析出来、AST 里挂着」：语义合成（展开成 getX/setX 方法、把 `p.x`
 * 改写成方法调用）还没接线，所以 Checker 会显式拦住并说明。判据分开写：
 * 语法在这里测，语义等接线后再加一组三端对拍。
 */
class PropertySyntaxTest {

    private fun parse(text: String) = Parser(Lexer(SourceFile.of("props.rsf", text)).tokenize(), SourceFile.of("props.rsf", text)).parseProgram()

    private fun fieldsOf(text: String) = parse(text).classes.first().fields

    @Test
    fun `get 与 set 都能写，自动访问器不带体`() {
        val f = fieldsOf(
            """
            class A() {
                pub var hp : Int = 100 { get; set }
            }
            """.trimIndent(),
        ).single()
        assertEquals("hp", f.name)
        assertEquals(listOf(AccessorKind.GET, AccessorKind.SET), f.accessors.map { it.kind })
        assertTrue(f.accessors.all { it.body == null }, "自动访问器不该有体")
        assertNull(f.accessors[1].param, "set 不写参数表就没有参数名")
    }

    @Test
    fun `只读属性只有 get，Bool 用 is`() {
        val fs = fieldsOf(
            """
            class A() {
                pub var id : Int = 0 { get }
                pub var alive : Bool = true { is }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(AccessorKind.GET), fs[0].accessors.map { it.kind })
        assertEquals(listOf(AccessorKind.IS), fs[1].accessors.map { it.kind })
    }

    @Test
    fun `显式 setter 带参数名与函数体`() {
        val f = fieldsOf(
            """
            class A() {
                pub var name : String = "x" {
                    get
                    set(v) {
                        this.name = v
                    }
                }
            }
            """.trimIndent(),
        ).single()
        assertEquals(listOf(AccessorKind.GET, AccessorKind.SET), f.accessors.map { it.kind })
        assertNull(f.accessors[0].body)
        val setter = f.accessors[1]
        assertEquals("v", setter.param)
        assertNotNull(setter.body)
        assertTrue(setter.body!!.statements.isNotEmpty(), "setter 体里应该有语句")
    }

    @Test
    fun `同一个访问器写两遍报错`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                class A() {
                    var hp : Int = 0 { get; get }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("只能写一次"), e.message)
    }

    @Test
    fun `不认识的访问器名字报错并列出可用写法`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                class A() {
                    var hp : Int = 0 { fetch }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("'get' / 'set' / 'is'"), e.message)
    }

    @Test
    fun `get 与 is 后面不能写参数表`() {
        val e = assertFailsWith<RsfError> {
            parse(
                """
                class A() {
                    var hp : Int = 0 { get(v) }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("不带参数表"), e.message)
    }

    @Test
    fun `访问器在 PropertyDesugar 里展开成真方法，不再拦`() {
        val src = SourceFile.of(
            "props.rsf",
            """
            class A() {
                pub var hp : Int = 100 { get; set }
            }
            fn main() -> void {
                val a = A()
                println(intToString(a.getHp()))
            }
            """.trimIndent(),
        )
        val model = Rsf.analyze(src)
        val decl = model.classModel.classes.getValue("A")
        val methods = decl.methods.map { it.name }
        assertTrue("getHp" in methods, "应该有 getHp：$methods")
        assertTrue("setHp" in methods, "应该有 setHp：$methods")
        // 字段名不变（这一版不重命名成 _hp），所以 p.hp 仍是字段直读
        assertTrue(decl.fields.any { it.name == "hp" })
    }
}
