package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

/**
 * 阶段 77：文件头 GC 指令 `gc on` / `gc off`。
 *
 * 现在只做了「解析 + 明确拒绝」：`gc on` 必须报「还没实现」，而不是被静默忽略——
 * 静默比不实现更糟，用户会以为已经有 GC 了。真语义在步骤 C。
 */
class GcDirectiveTest {

    private fun parse(text: String) = Rsf.parse(SourceFile.of("g.rsf", text))

    private fun analyze(text: String) = Rsf.analyze(SourceFile.of("g.rsf", text))

    @Test
    fun `没写 gc 指令时 program 的 gc 是 null`() {
        assertNull(parse("fn main() -> void { rtn }").gc)
    }

    @Test
    fun `gc off 能被解析下来，且不报错`() {
        val program = parse("gc off\n\nfn main() -> void { rtn }")
        val gc = program.gc
        assertNotNull(gc)
        assertEquals(false, gc!!.enabled)
        assertEquals(1, gc.at.line)
        analyze("gc off\nfn main() -> void { rtn }")
    }

    @Test
    fun `gc on 现在报还没实现，并指回那一行`() {
        val e = assertThrows<RsfError> { analyze("gc on\nfn main() -> void { rtn }") }
        val message = e.message!!
        assertTrue(message.contains("还没实现"), message)
        assertTrue(message.contains("DESIGN-77"), message)
        assertTrue(message.contains("memBytes"), message)
        assertEquals(1, e.line)
    }

    @Test
    fun `gc 后面不写 on 或 off 会报清楚`() {
        val e = assertThrows<RsfError> { parse("gc maybe\nfn main() -> void { rtn }") }
        val message = e.message!!
        assertTrue(message.contains("on 或 off"), message)
        assertEquals(1, e.line)
    }

    @Test
    fun `gc 写两次报只能写一次`() {
        val e = assertThrows<RsfError> { analyze("gc off\ngc off\nfn main() -> void { rtn }") }
        assertTrue(e.message!!.contains("只能写一次"), e.message!!)
    }

    @Test
    fun `target 与 gc 任意顺序都认`() {
        val targetFirst = parse("target native\ngc off\nfn main() -> void { rtn }")
        assertEquals(BuildTarget.NATIVE, targetFirst.target)
        assertEquals(false, targetFirst.gc!!.enabled)

        val gcFirst = parse("gc off\ntarget native\nfn main() -> void { rtn }")
        assertEquals(BuildTarget.NATIVE, gcFirst.target)
        assertEquals(false, gcFirst.gc!!.enabled)
    }

    @Test
    fun `gc 指令写在声明之后会报只能写在最前面`() {
        val e = assertThrows<RsfError> {
            parse("fn main() -> void { rtn }\ngc off\nfn other() -> void { rtn }")
        }
        assertTrue(e.message!!.contains("只能写在最前面"), e.message!!)
    }

    @Test
    fun `多文件里 gc 取或：任一文件 on 就整体 on`() {
        val dir = Files.createTempDirectory("rsf-gc").toFile()
        try {
            File(dir, "Other.rsf").writeText("gc on\nfn other() -> void { rtn }\n")
            val root = File(dir, "Root.rsf")
            root.writeText("gc off\nimport \"Other.rsf\"\nfn main() -> void { rtn }\n")

            val loaded = Rsf.load(root, target = BuildTarget.NATIVE)
            assertEquals(true, loaded.gc?.enabled)
            // 合并成 on 之后，Checker 就该为「还没实现」报错——而不是被静默丢掉。
            val e = assertThrows<RsfError> { Rsf.analyze(loaded) }
            assertTrue(e.message!!.contains("还没实现"), e.message!!)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `多文件里都写 off 时整体仍是 off`() {
        val dir = Files.createTempDirectory("rsf-gc").toFile()
        try {
            File(dir, "Other.rsf").writeText("gc off\nfn other() -> void { rtn }\n")
            val root = File(dir, "Root.rsf")
            root.writeText("import \"Other.rsf\"\nfn main() -> void { rtn }\n")

            val loaded = Rsf.load(root, target = BuildTarget.NATIVE)
            assertEquals(false, loaded.gc?.enabled)
            Rsf.analyze(loaded) // 不报错
        } finally {
            dir.deleteRecursively()
        }
    }
}
