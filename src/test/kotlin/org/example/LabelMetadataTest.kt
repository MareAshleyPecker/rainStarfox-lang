package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.TokenKind
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 阶段 28a：标签名的反引号写法（`` `任意字符`: `` / `` goto `任意字符` ``）与
 * `@RsfLabels` 元数据。
 *
 * 两条线各查一头：
 * - **词法 / 语法 / 语义层**：反引号标签名的 lexeme 是**去掉引号之后**的名字；空名、
 *   未收尾、写在表达式位置、与普通写法重名、跨函数 goto 各自的报错文案逐字对工单 §3。
 * - **类文件层**：用 ASM `ClassReader` 读回产出的 .class，断言方法上的 `LRsfLabels;`
 *   注解（`value` = 标签的**声明顺序**），以及**没有标签的方法一条注解都没有**。
 *
 * 两个刻意的口径（DESIGN-28 §2，代码里也写着）：
 * 1. 注解是 **runtime-visible**（`visitAnnotation(desc, true)`），不是 invisible——
 *    `javap -v` 要能在 `RuntimeVisibleAnnotations` 段里看见它。
 * 2. **不生成** `RsfLabels` 注解接口类：描述符 `LRsfLabels;` 是悬空的。所以反射
 *    `getAnnotation` 会 `TypeNotPresentException`，只有字节码级工具读得到；这条
 *    缺口是设计上认下来的，测试里反而要断言「目录里没有 RsfLabels.class」。
 *
 * 不动点也在这里守着一条最容易被踩坏的：`Lowering` 给 try/catch / when 之类的内部
 * 标签留了 `'\u0000'` 前缀（`Lowering.kt:301-302` 的 `fresh()`），它们**不是**用户
 * 标签——带 try/catch 但没写过标签的方法必须一条注解都没有，否则旧语料的字节全变。
 */
class LabelMetadataTest {

    // ============================================================ 类文件层（ASM 读回）

    /** (1) 有标签的方法：注解在，`value` = 声明顺序；没有标签的方法：注解一条都没有。 */
    @Test
    fun `有标签的方法带 RsfLabels 且 value 是声明顺序`() {
        val dir = compile(
            """
            fn one() -> void {
                `loop`:
                println("one")
                goto `出口`
                println("不该打印")
                `出口`:
                rtn
            }

            fn main() -> void {
                one()
                println("main")
            }
            """.trimIndent(),
            "LmOrder",
        )

        val labels = labelsOf(dir, "LmOrder")
        val one = requireLabels(labels["one"], "one() 有两个标签，必须带 @RsfLabels")
        assertEquals(listOf("loop", "出口"), one.values, "value 必须是声明顺序，不去重、不排序")
        assertTrue(one.visible, "注解必须是 runtime-visible（javap 的 RuntimeVisibleAnnotations 段）")

        assertNull(labels["main"], "main() 没写过标签，不许有 @RsfLabels（字节必须与改动前一致）")
    }

    /** (3) 两个方法各有标签：各自的注解互不串联（同名标签在不同方法里不冲突）。 */
    @Test
    fun `两个方法的注解互不串联`() {
        val dir = compile(
            """
            fn a() -> void {
                `重新开始`:
                rtn
            }

            fn b() -> void {
                `结束`:
                rtn
            }

            fn c() -> void {
                rtn
            }
            """.trimIndent(),
            "LmSplit",
        )

        val labels = labelsOf(dir, "LmSplit")
        assertEquals(listOf("重新开始"), labels["a"]?.values)
        assertEquals(listOf("结束"), labels["b"]?.values)
        assertNull(labels["c"], "c() 没标签，不许被 a/b 的注解串上")
    }

    /** (4) 特殊字符名原样进 value——不做转义、不换成标识符。 */
    @Test
    fun `特殊字符标签名原样出现在 value 里`() {
        val dir = compile(
            """
            fn main() -> void {
                var n = 0
                `a.b${'$'}c@d/e`:
                n = n + 1
                `第一步 开始`:
                if (n < 2) { goto `a.b${'$'}c@d/e` }
                println("done")
                rtn
            }
            """.trimIndent(),
            "LmSpecial",
        )

        val main = requireLabels(labelsOf(dir, "LmSpecial")["main"], "main() 有反引号标签")
        assertEquals(listOf("a.b\$c@d/e", "第一步 开始"), main.values)
    }

    /**
     * (2) + 不动点：带 try/catch（内部标签一堆、但一个用户标签都没有）的方法
     * **一条注解都没有**——`'\u0000'` 前缀的内部标签不许漏进 `value`。
     */
    @Test
    fun `只有内部标签的方法不发注解`() {
        val dir = compile(
            """
            fn main() -> void {
                try {
                    fail "boom"
                } catch (e : string) {
                    println(e)
                }
                rtn
            }
            """.trimIndent(),
            "LmInternal",
        )

        assertNull(
            labelsOf(dir, "LmInternal")["main"],
            "try/catch 的内部标签不是用户标签，带了注解就说明 '\u0000' 前缀没滤掉",
        )
    }

    /** (5) runtime-visible + 不生成注解接口类：目录里没有 RsfLabels.class，类也照常能跑。 */
    @Test
    fun `不生成 RsfLabels 注解接口类且类照常执行`() {
        val dir = compile(
            """
            fn main() -> void {
                `go`:
                println("run-ok")
                rtn
            }
            """.trimIndent(),
            "LmDangling",
        )

        assertFalse(File(dir, "RsfLabels.class").isFile, "DESIGN-28 §2：不生成注解接口类，描述符是悬空的")
        assertTrue(labelsOf(dir, "LmDangling")["main"]?.visible == true)

        // 悬空的注解描述符只在**反射**时才会炸；类加载与方法执行必须照旧。
        val loader = URLClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
        val buf = ByteArrayOutputStream()
        val saved = System.out
        val ps = PrintStream(buf, true, "UTF-8")
        System.setOut(ps)
        try {
            val cls = loader.loadClass("LmDangling")
            cls.getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
        } finally {
            System.setOut(saved)
            ps.flush()
            loader.close()
        }
        assertEquals(listOf("run-ok"), buf.toString("UTF-8").replace("\r\n", "\n").trim().lines())
    }

    // ============================================================ 词法 / 语法 / 语义

    /** 词法：`LABEL_NAME` 的 lexeme 是**去掉一对反引号**之后的名字。 */
    @Test
    fun `反引号标签名的 lexeme 不含引号`() {
        val src = SourceFile.of("<label-lex>", "fn main() -> void {\n    `a-b.c`:\n    rtn\n}")
        val tok = Rsf.tokens(src).first { it.kind == TokenKind.LABEL_NAME }
        assertEquals("a-b.c", tok.lexeme)
        assertEquals(2, tok.line, "位置取开引号那个字符（第 2 行行首缩进后）")
        assertEquals(5, tok.column)
        assertEquals("标签名 'a-b.c'", tok.describe())
    }

    /** 空名：文案里必须有 `标签名不能是空的`。 */
    @Test
    fun `空的反引号标签名报错`() {
        assertMessage("标签名不能是空的") {
            """
            fn main() -> void {
                ``:
                rtn
            }
            """
        }
    }

    /** 未收尾（含「反引号里换行」）：文案里必须有 `反引号标签名没有收尾`。 */
    @Test
    fun `未收尾的反引号标签名报错`() {
        assertMessage("反引号标签名没有收尾") {
            """
            fn main() -> void {
                `abc:
                rtn
            }
            """
        }
    }

    /** 表达式位置：文案里必须有 `反引号只用来写标签名`。 */
    @Test
    fun `表达式位置的反引号报错`() {
        assertMessage("反引号只用来写标签名") {
            """
            fn main() -> void {
                var x = `abc`
                rtn
            }
            """
        }
    }

    /** 声明位置（变量名）同样只许用普通标识符：反引号不扩大标识符面。 */
    @Test
    fun `变量名不能用反引号`() {
        assertMessage("反引号只用来写标签名") {
            """
            fn main() -> void {
                var `名字` = 1
                rtn
            }
            """
        }
    }

    /** 作用域不变：`` `finish` `` 与 `finish` 是同一个标签，全函数不许重名。 */
    @Test
    fun `反引号与普通写法算同一个标签名`() {
        assertMessage("标签 'finish' 在函数 'main' 里重复定义") {
            """
            fn main() -> void {
                `finish`:
                finish:
                rtn
            }
            """
        }
    }

    /** 作用域不变：标签是函数作用域的，跨函数 goto 仍然找不到。 */
    @Test
    fun `跨函数 goto 反引号标签找不到`() {
        assertMessage("goto 的标签 '目标' 在函数 'main' 里没有定义") {
            """
            fn one() -> void {
                `目标`:
                rtn
            }

            fn main() -> void {
                goto `目标`
            }
            """
        }
    }

    /** `goto` 后面必须是标签名（两种写法都收，别的记号报老文案）。 */
    @Test
    fun `goto 后面缺标签名报错`() {
        assertMessage("'goto' 后面缺少标签名") {
            """
            fn main() -> void {
                goto 1
                rtn
            }
            """
        }
    }

    // ============================================================ 工具

    private fun compile(text: String, className: String): File {
        val src = SourceFile.of("<label-metadata>", text)
        val dir = File.createTempFile("rsf-labels", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        Rsf.build(Rsf.analyze(src), src, className, dir)
        return dir
    }

    private fun assertMessage(want: String, body: () -> String) {
        val src = SourceFile.of("<label-metadata>", body().trimIndent())
        val e = assertFailsWith<RsfError> { Rsf.analyze(src) }
        assertTrue(
            e.message?.contains(want) == true,
            "报错文案必须逐字含「$want」，实际是「${e.message}」",
        )
    }
}

// ================================================================ ASM 只读读回

/**
 * 一个方法上的 `@RsfLabels`：[values] 是 `value` 数组的内容，[visible] 是
 * `RuntimeVisibleAnnotations`（true）还是 `RuntimeInvisibleAnnotations`（false）。
 */
private class LabelsAnnotation(val visible: Boolean, val values: List<String>)

/** 读 `dir/<internalName>.class`，返回「方法名 → @RsfLabels」，没有注解的方法不出现在表里。 */
private fun labelsOf(dir: File, internalName: String): Map<String, LabelsAnnotation> {
    val out = LinkedHashMap<String, LabelsAnnotation>()
    val bytes = File(dir, "$internalName.class").readBytes()
    ClassReader(bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                private var visible = false
                private var values: MutableList<String>? = null

                override fun visitAnnotation(descriptor: String, isVisible: Boolean): AnnotationVisitor? {
                    if (descriptor != "LRsfLabels;") return null
                    visible = isVisible
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visitArray(name: String?): AnnotationVisitor? {
                            if (name != "value") return null
                            val list = ArrayList<String>()
                            values = list
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String?, value: Any?) {
                                    list += value as String
                                }
                            }
                        }
                    }
                }

                override fun visitEnd() {
                    values?.let { out[name] = LabelsAnnotation(visible, it) }
                }
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return out
}

private fun requireLabels(value: LabelsAnnotation?, message: String): LabelsAnnotation =
    value ?: throw AssertionError(message)
