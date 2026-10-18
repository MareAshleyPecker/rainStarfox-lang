package org.example

import org.example.complie.Rsf
import org.example.complie.ast.LambdaExpr
import org.example.complie.ast.TypeRef
import org.example.complie.ast.VarDeclStmt
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 阶段 30a：函数类型与 `lam` 字面量的**前端**行为（只解析 + 类型检查，后端 30b 才接）。
 *
 * 覆盖：词法（`lam` 关键字）、类型语法与 `display()`、形参/返回/数组位置、
 * `lam` 的形参类型推断与期望类型传播、捕获规则、以及 DESIGN-30 §5 的五条文案。
 */
class LambdaFrontendTest {

    // ---------------------------------------------------------------- 词法

    @Test
    fun `lam 是关键字`() {
        val tokens = Rsf.tokens(source("lam(x : int) -> int { rtn x }"))
        assertEquals(TokenKind.KW_LAM, tokens.first().kind)
        // 它进的是普通关键字集合，不是修饰词
        assertTrue(!TokenKind.KW_LAM.isModifierKeyword, "lam 不该被当成修饰词")
        assertTrue(!TokenKind.KW_LAM.isTypeKeyword, "lam 不该被当成类型关键字")
    }

    // ------------------------------------------------------- 类型语法与 display

    @Test
    fun `函数类型的 display 形式`() {
        val cases = listOf(
            "(int) -> int" to "(int) -> int",
            "(int, string) -> bool" to "(int, string) -> bool",
            "() -> void" to "() -> void",
            "?(int) -> int" to "?(int) -> int",
            "((int) -> int)[]" to "((int) -> int)[]",
        )
        for ((text, want) in cases) {
            val program = Rsf.parse(source("fn main() -> void {\n    var f : $text = null\n}\n"))
            val decl = program.functions.single().body!!.statements.first() as VarDeclStmt
            assertEquals(want, decl.declaredType?.display(), "写的是 $text")
        }
    }

    @Test
    fun `函数类型的结构化字段`() {
        val program = Rsf.parse(source("fn main() -> void {\n    var f : (int, string) -> bool = null\n}\n"))
        val decl = program.functions.single().body!!.statements.first() as VarDeclStmt
        val ref = decl.declaredType!!
        assertEquals("fn", ref.name)
        assertTrue(ref.isFunctionType)
        assertEquals(2, ref.fnParams.size)
        assertEquals("int", ref.fnParams[0].display())
        assertEquals("string", ref.fnParams[1].display())
        assertEquals("bool", ref.fnRet?.display())

        val nullable = Rsf.parse(source("fn main() -> void {\n    var f : ?(int) -> int = null\n}\n"))
            .functions.single().body!!.statements.first() as VarDeclStmt
        assertEquals("?fn", nullable.declaredType!!.name)
        assertTrue(nullable.declaredType!!.isFunctionType)

        val array = Rsf.parse(source("fn main() -> void {\n    var fs : ((int) -> int)[] = null\n}\n"))
            .functions.single().body!!.statements.first() as VarDeclStmt
        assertEquals(1, array.declaredType!!.arrayDepth)
        assertEquals("fn", array.declaredType!!.name)
    }

    @Test
    fun `函数类型可以出现在形参和返回位置`() {
        val program = Rsf.parse(
            source(
                "fn apply(x : int, g : (int) -> int) -> (int) -> int {\n" +
                    "    rtn g\n" +
                    "}\n",
            ),
        )
        val fn = program.functions.single()
        assertEquals("(int) -> int", fn.params[1].type.display())
        assertEquals("(int) -> int", fn.ret.type.display())
    }

    @Test
    fun `函数类型缺箭头时报专门的语法错误`() {
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    var f : (int) = null\n}\n"))
        }
        assertEquals("parser", e.phase)
        assertTrue(e.detail.contains("函数类型要写成 '(参数类型表) -> 返回类型'（DESIGN-30）"), e.detail)
    }

    // ---------------------------------------------------------------- lam 字面量

    @Test
    fun `lam 的形参类型与返回子句都可省`() {
        val program = Rsf.parse(
            source(
                "fn main() -> void {\n" +
                    "    var f : (int) -> int = lam(x) { rtn x + 1 }\n" +
                    "}\n",
            ),
        )
        val decl = program.functions.single().body!!.statements.first() as VarDeclStmt
        val lambda = decl.initializer as LambdaExpr
        assertEquals(1, lambda.params.size)
        assertTrue(lambda.params[0].type.isMissing, "类型省略时该是缺省标记")
        assertEquals(null, lambda.declaredReturn)
    }

    @Test
    fun `lam 可以带完整标注并出现在实参位置`() {
        val program = Rsf.parse(
            source(
                "fn each(f : (int) -> void) -> void {\n" +
                    "    rtn\n" +
                    "}\n" +
                    "fn main() -> void {\n" +
                    "    each(lam(n : int) -> void { rtn })\n" +
                    "}\n",
            ),
        )
        val main = program.functions.last()
        val call = main.body!!.statements.first() as org.example.complie.ast.ExprStmt
        val arg = (call.expr as org.example.complie.ast.CallExpr).arguments.single()
        assertTrue(arg is LambdaExpr, "实参位置应该拿到 LambdaExpr")
        assertEquals("void", (arg as LambdaExpr).declaredReturn!!.display())
    }

    @Test
    fun `可空函数值用 !! 断言之后可以调用`() {
        val src = source(
            "fn main() -> void {\n" +
                "    var f : ?(int) -> int = null\n" +
                "    f = lam(x : int) -> int { rtn x * 2 }\n" +
                "    println(\"\" + f!!(21))\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val lambda = program.functions.single().body!!.statements[1]
        val assigned = (lambda as org.example.complie.ast.AssignStmt).value
        assertEquals("(int) -> int", model.typeOf(assigned as LambdaExpr).rsfName)
    }

    // ------------------------------------------------------------ 期望类型传播

    @Test
    fun `期望类型能补上形参类型与返回类型`() {
        val src = source(
            "fn apply(x : int, g : (int) -> int) -> int {\n" +
                "    rtn g(x)\n" +
                "}\n" +
                "fn main() -> void {\n" +
                "    var r : int = apply(10, lam(v) { rtn v + 1 })\n" +
                "}\n",
        )
        val program = Rsf.parse(src)
        val model = Rsf.analyze(program, src)
        val decl = program.functions.last().body!!.statements.single() as VarDeclStmt
        val lambda = (decl.initializer as org.example.complie.ast.CallExpr).arguments[1] as LambdaExpr
        assertEquals("(int) -> int", model.typeOf(lambda).rsfName)
    }

    @Test
    fun `imutvar 值捕获合法`() {
        Rsf.analyze(
            source(
                "fn main() -> void {\n" +
                    "    imutvar base : int = 10\n" +
                    "    var add : (int) -> int = lam(x : int) -> int { rtn x + base }\n" +
                    "    println(\"\" + add(5))\n" +
                    "}\n",
            ),
        )
    }

    // ------------------------------------------------------------- 五条文案

    @Test
    fun `捕获 var 报值捕获文案`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var count : int = 0\n" +
                        "    var f : (int) -> int = lam(x : int) -> int { rtn x + count }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertEquals(
            "lambda 只能值捕获：'count' 是 var（DESIGN-30）——改成 imutvar，或者把它当参数传进来",
            e.detail,
        )
    }

    @Test
    fun `推不出形参类型时报文案`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(source("fn main() -> void {\n    var f = lam(x) -> int { rtn x + 1 }\n}\n"))
        }
        assertEquals("checker", e.phase)
        assertEquals(
            "lambda 的参数类型推不出来：'x' 没写类型，这里也没有期望的函数类型（DESIGN-30）——把参数类型写上，或者先赋给一个有类型的变量",
            e.detail,
        )
    }

    @Test
    fun `可空函数值直接调用报文案`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var f : ?(int) -> int = null\n" +
                        "    println(\"\" + f(1))\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertEquals("'f' 是可空的函数值，先断言非空（f!!）再调用（DESIGN-30）", e.detail)
    }

    @Test
    fun `is 的目标不能是函数类型`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var f : (int) -> int = lam(x : int) -> int { rtn x }\n" +
                        "    if (f is (int) -> int) { println(\"yes\") }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertEquals("'is' 的目标不能是函数类型（DESIGN-30）", e.detail)
    }

    @Test
    fun `as 的目标不能是函数类型`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var f : (int) -> int = lam(x : int) -> int { rtn x }\n" +
                        "    var g : (int) -> int = f as (int) -> int\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertEquals("'as' 的目标不能是函数类型（DESIGN-30）", e.detail)
    }

    @Test
    fun `lambda 返回类型对不上报类型不匹配`() {
        val e = assertFailsWith<RsfError> {
            Rsf.analyze(
                source(
                    "fn main() -> void {\n" +
                        "    var g : (int) -> string = lam(x : int) -> int { rtn x }\n" +
                        "}\n",
                ),
            )
        }
        assertEquals("checker", e.phase)
        assertTrue(e.detail.contains("类型不匹配"), e.detail)
    }

    @Test
    fun `lam 不许当 is 的右操作数`() {
        val e = assertFailsWith<RsfError> {
            Rsf.parse(source("fn main() -> void {\n    if (1 is lam) { rtn }\n}\n"))
        }
        assertEquals("parser", e.phase)
    }

    // ---------------------------------------------------------------- 小工具

    private fun source(text: String): SourceFile = SourceFile.of("<test>", text)
}
