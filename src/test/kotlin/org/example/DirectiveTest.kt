package org.example

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.directives.BuildSettings
import org.example.complie.directives.DepKind
import org.example.complie.directives.Directives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * `#dep` / `#build` 两条文件级指令的解析、挖空与校验。
 */
class DirectiveTest {

    private fun parse(text: String, path: String = "build.rsfs") =
        Directives.parse(SourceFile.of(path, text))

    @Test
    fun `#dep 按块收依赖，种类与路径都对`() {
        val text = """
            #dep {
                java("libs/util.jar")
                c("native/helper.c")
                h("native/helper.h")
                rule("extraDeps")
            }
            fn main() -> void { rtn }
        """.trimIndent()
        val result = parse(text)
        assertEquals(4, result.deps.size)
        assertEquals(DepKind.JAVA, result.deps[0].kind)
        assertEquals("libs/util.jar", result.deps[0].target)
        assertEquals(DepKind.C, result.deps[1].kind)
        assertEquals(DepKind.H, result.deps[2].kind)
        assertEquals(DepKind.RULE, result.deps[3].kind)
        assertEquals("extraDeps", result.deps[3].target)
        assertEquals("java(\"libs/util.jar\")", result.deps[0].display())
    }

    @Test
    fun `指令被挖成空格，长度与换行都不变`() {
        val text = """
            #dep {
                java("x.jar")
            }
            fn main() -> void { rtn }
        """.trimIndent()
        val result = parse(text)
        assertEquals(text.length, result.stripped.length)
        assertEquals(text.count { it == '\n' }, result.stripped.count { it == '\n' })
        assertTrue(result.stripped.contains("fn main() -> void { rtn }"), result.stripped)
        assertTrue(!result.stripped.contains("#dep"), result.stripped)
    }

    @Test
    fun `指令挖空后行列号不变，后面的报错还指在原来那一行`() {
        val text = """
            #dep {
                java("x.jar")
            }

            fn main() -> void {
                nope()
            }
        """.trimIndent()
        val e = assertThrows<RsfError> { Rsf.analyze(SourceFile.of("keep.rsf", text)) }
        assertEquals(6, e.line)
        assertEquals(5, e.column)
    }

    @Test
    fun `不认识的依赖种类报错并给出可用清单`() {
        val text = """
            #dep {
                jar("x.jar")
            }
            fn main() -> void { rtn }
        """.trimIndent()
        val e = assertThrows<RsfError> { parse(text) }
        assertEquals(2, e.line)
        assertTrue(e.message!!.contains("不认识的依赖种类"), e.message)
        assertTrue(e.message!!.contains("rule"), e.message)
    }

    @Test
    fun `依赖块没闭合就报错`() {
        val text = """
            #dep {
                java("x.jar")
            fn main() -> void { rtn }
        """.trimIndent()
        val e = assertThrows<RsfError> { parse(text) }
        assertTrue(e.message!!.contains("#dep"), e.message)
    }

    @Test
    fun `#build 只能写在 build_rsfs 里`() {
        val text = """
            #build {
                out("build/out")
            }
            fn main() -> void { rtn }
        """.trimIndent()
        val e = assertThrows<RsfError> { parse(text, "main.rsf") }
        assertTrue(e.message!!.contains("build.rsfs"), e.message)
        // 同一个内容放在 build.rsfs 里就合法
        assertEquals("build/out", parse(text, "build.rsfs").build?.outDir)
    }

    @Test
    fun `#build 收 out target meta name 四个键，target 认 auto 与 native`() {
        val text = """
            #build {
                out("dist")
                target("auto")
                meta(true)
                name("app")
            }
            fn main() -> void { rtn }
        """.trimIndent()
        val build = parse(text).build!!
        assertEquals("dist", build.outDir)
        assertEquals("auto", build.target)
        assertEquals(true, build.meta)
        assertEquals("app", build.name)
        assertEquals("c", parse(text.replace("auto", "native")).build!!.target)
    }

    @Test
    fun `#build 里写不认识的键、重复键、坏 meta 都报错`() {
        val unknown = assertThrows<RsfError> {
            parse("#build {\n  foo(\"x\")\n}\nfn main() -> void { rtn }")
        }
        assertTrue(unknown.message!!.contains("foo"), unknown.message)

        val twice = assertThrows<RsfError> {
            parse("#build {\n  out(\"a\")\n  out(\"b\")\n}\nfn main() -> void { rtn }")
        }
        assertTrue(twice.message!!.contains("两次"), twice.message)

        val badMeta = assertThrows<RsfError> {
            parse("#build {\n  meta(\"maybe\")\n}\nfn main() -> void { rtn }")
        }
        assertTrue(badMeta.message!!.contains("meta"), badMeta.message)
    }

    @Test
    fun `没有指令时两个字段都是空的`() {
        val result = parse("fn main() -> void { rtn }")
        assertTrue(result.deps.isEmpty())
        assertNull(result.build)
        assertEquals("fn main() -> void { rtn }", result.stripped)
    }

    @Test
    fun `target auto 按每份文件自己有没有 native 声明决定产物`() {
        val root = Files.createTempDirectory("rsf-auto").toFile()
        try {
            File(root, "src").mkdirs()
            File(root, "src/jvm.rsf").writeText("fn main() -> void { println(\"jvm\") }\n")
            File(root, "src/nat.rsf").writeText(
                """
                target native

                fn addUp(p : Int*) -> Int { rtn *p + 1 }

                fn main() -> void {
                    var x : Int = 41
                    println(intToString(addUp(&x)))
                }
                """.trimIndent() + "\n",
            )
            val host = org.example.build.ProjectBuildHost(root = root, target = "auto")
            assertEquals(2, host.compileAll("src"))
            val out = File(root, "build/out")
            assertTrue(File(out, "jvm.class").isFile, out.list()?.joinToString() ?: "（空）")
            assertTrue(File(out, "nat.c").isFile, out.list()?.joinToString() ?: "（空）")
            assertTrue(File(out, "rsf_runtime.h").isFile, out.list()?.joinToString() ?: "（空）")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `settings 里命令行显式给的压过 build_rsfs 的设置`() {
        val build = BuildSettings(outDir = "from-script", target = "c", meta = true)
        val (out, target, meta) = org.example.build.ProjectBuildHost.settings(
            build = build,
            cliOut = File("from-cli"),
            cliC = false,
            cliMeta = false,
        )
        assertEquals("from-cli", out.path)
        assertEquals("c", target)
        assertTrue(meta)

        val (out2, target2, meta2) = org.example.build.ProjectBuildHost.settings(
            build = BuildSettings(outDir = "from-script", target = "auto", meta = false),
            cliOut = null,
            cliC = true,
            cliMeta = true,
        )
        assertEquals("from-script", out2.path)
        assertEquals("c", target2)
        assertTrue(meta2)
    }
}
