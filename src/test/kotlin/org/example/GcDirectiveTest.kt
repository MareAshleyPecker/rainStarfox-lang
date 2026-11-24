package org.example

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.native.CBackend
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
 * `gc on` 现在是真的：C 后端装上内置的标记-清扫 GC（非移动、保守根、按需扫）。
 * 指令只认**带 main 的那个文件**，写在被 import 的文件里直接报错。
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
    fun `gc on 落进模型，C 后端据此装上内置 GC`() {
        val model = analyze("gc on\nfn main() -> void { rtn }")
        assertEquals(true, model.gcEnabled)
        val c = CBackend(model).emit()
        assertTrue(c.contains("rsf_gc_heap_install();"), c.take(400))
        assertTrue(c.contains("if (!rsf_gc_thread_attached()) rsf_gc_thread_attach();"))
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
    fun `gc 写在被 import 的文件里会报错，并指出去哪写`() {
        val dir = Files.createTempDirectory("rsf-gc").toFile()
        try {
            File(dir, "Other.rsf").writeText("gc on\nfn other() -> void { rtn }\n")
            val root = File(dir, "Root.rsf")
            root.writeText("import \"Other.rsf\"\nfn main() -> void { rtn }\n")

            val e = assertThrows<RsfError> { Rsf.load(root, target = BuildTarget.NATIVE) }
            assertTrue(e.message!!.contains("只能写在带 main 的文件里"), e.message!!)
            assertTrue(e.message!!.contains("Root.rsf"), e.message!!)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `多文件里 gc 只认 main 那份，别的文件写 off 也一样要报`() {
        val dir = Files.createTempDirectory("rsf-gc").toFile()
        try {
            File(dir, "Other.rsf").writeText("gc off\nfn other() -> void { rtn }\n")
            val root = File(dir, "Root.rsf")
            root.writeText("gc off\nimport \"Other.rsf\"\nfn main() -> void { rtn }\n")

            val e = assertThrows<RsfError> { Rsf.load(root, target = BuildTarget.NATIVE) }
            assertTrue(e.message!!.contains("只能写在带 main 的文件里"), e.message!!)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `main 所在文件写 gc 就有效，别的文件不写也没关系`() {
        val dir = Files.createTempDirectory("rsf-gc").toFile()
        try {
            File(dir, "Other.rsf").writeText("fn other() -> void { rtn }\n")
            val root = File(dir, "Root.rsf")
            root.writeText("gc on\nimport \"Other.rsf\"\nfn main() -> void { rtn }\n")

            val loaded = Rsf.load(root, target = BuildTarget.NATIVE)
            assertEquals(true, loaded.gc?.enabled)
            assertEquals(true, Rsf.analyze(loaded).gcEnabled)
        } finally {
            dir.deleteRecursively()
        }
    }
}
