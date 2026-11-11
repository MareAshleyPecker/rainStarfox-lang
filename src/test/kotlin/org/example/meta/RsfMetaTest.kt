package org.example.meta

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.Attribute
import org.objectweb.asm.ByteVector
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 阶段 68：产物元数据（[RsfMeta]）的单测。
 *
 * 只钉格式与读写：编码→解码往返、载体（PE 节 / class 属性）的定位、
 * 以及「编译产物里真能找到源码快照」。gcc 与端到端还原在
 * `build/verify68/run.ps1` 里跑。
 *
 * 临时文件全部落在 `java.io.tmpdir`，Gradle 已把它指到 build/tmp/tests。
 */
class RsfMetaTest {

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rsf-meta-$name-${System.nanoTime()}")
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    // ------------------------------------------------------------------ 编解码

    @Test
    fun `encode decode round trip keeps sources symbols and build info`() {
        val sources = listOf(
            RsfMeta.SourceSnapshot("main.rsf", "fun main() {\n    println(\"hi\")\n}\n"),
            RsfMeta.SourceSnapshot("lib/util.rsf", "fn add(a: Int, b: Int) -> Int { a + b }\n"),
        )
        val symbols = "fn main()\nclass Foo\n  fn bar(x: Int)\n"
        val build = linkedMapOf("compiler" to "rainStarfox 0.9.0", "artifact" to "native")

        val blob = RsfMeta.decode(RsfMeta.encode(sources, symbols, build))

        assertEquals(sources, blob.sources)
        assertEquals(symbols, blob.symbols)
        assertEquals(build, blob.build)
        assertTrue(blob.isExact)
    }

    @Test
    fun `big repetitive source is deflated and survives the round trip`() {
        // 5 万行同构代码：deflate 一定比原文小，覆盖 FLAG_DEFLATE 与 inflate 的校验
        val text = StringBuilder()
        repeat(50_000) { text.append("fn f$it() -> Int { $it + 1 }\n") }
        val sources = listOf(RsfMeta.SourceSnapshot("big.rsf", text.toString()))

        val raw = RsfMeta.encode(sources, "", emptyMap())
        assertEquals(RsfMeta.FLAG_DEFLATE, raw[9].toInt() and 0xff, "重复文本应当被压缩")

        val blob = RsfMeta.decode(raw)
        assertEquals(1, blob.sources.size)
        assertEquals(sources[0].text, blob.sources[0].text)
    }

    @Test
    fun `decode rejects foreign magic bad version and truncation`() {
        val good = RsfMeta.encode(listOf(RsfMeta.SourceSnapshot("a.rsf", "fn a()\n")), "", emptyMap())

        val wrongMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows<RsfError> { RsfMeta.decode(wrongMagic) }

        val wrongVersion = good.copyOf().also { it[8] = 99.toByte() }
        assertThrows<RsfError> { RsfMeta.decode(wrongVersion) }

        assertThrows<RsfError> { RsfMeta.decode(good.copyOfRange(0, good.size - 5)) }
        assertThrows<RsfError> { RsfMeta.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `unknown chunk type is skipped for forward compatibility`() {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.writeByte(42) // 未知类型
        out.writeInt(3)
        out.write(byteArrayOf(1, 2, 3))
        val text = "fn a()\n".toByteArray()
        val path = "a.rsf".toByteArray()
        out.writeByte(RsfMeta.CHUNK_SOURCE)
        out.writeInt(2 + path.size + 4 + text.size)
        out.writeShort(path.size)
        out.write(path)
        out.writeInt(text.size)
        out.write(text)
        out.flush()

        val chunk = body.toByteArray()
        val head = ByteArrayOutputStream()
        val h = DataOutputStream(head)
        h.write(RsfMeta.MAGIC.toByteArray())
        h.writeByte(RsfMeta.VERSION)
        h.writeByte(0)
        h.writeShort(2)
        h.writeInt(chunk.size)
        h.writeInt(chunk.size)
        h.write(chunk)
        h.flush()

        val blob = RsfMeta.decode(head.toByteArray())
        assertEquals(1, blob.sources.size)
        assertEquals("a.rsf", blob.sources[0].path)
    }

    // ------------------------------------------------------------------ C 载体

    @Test
    fun `c declaration puts bytes in a named section`() {
        val blob = RsfMeta.encode(listOf(RsfMeta.SourceSnapshot("a.rsf", "fn a()\n")), "", emptyMap())
        val c = RsfMeta.cDeclaration(blob)

        assertTrue(c.contains("__attribute__((section(\"${RsfMeta.SECTION}\"), used, aligned(1)))"))
        assertTrue(c.contains("const unsigned int __rsf_meta_len = ${blob.size}u;"))
        // 每个字节一个 \xNN，总字节数与元数据块一致
        assertEquals(blob.size, Regex("""\\x[0-9a-f]{2}""").findAll(c).count())
    }

    @Test
    fun `readPeSection finds raw bytes of a named section`() {
        val payload = RsfMeta.encode(listOf(RsfMeta.SourceSnapshot("a.rsf", "fn a()\n")), "", emptyMap())
        val exe = minimalPe(payload)
        assertArrayEquals(payload, RsfMeta.readPeSection(exe, RsfMeta.SECTION))
        assertNull(RsfMeta.readPeSection(exe, ".other"))
        assertNull(RsfMeta.readPeSection(byteArrayOf(1, 2, 3), RsfMeta.SECTION))
    }

    @Test
    fun `readClassAttribute finds the attribute payload`() {
        val payload = RsfMeta.encode(listOf(RsfMeta.SourceSnapshot("a.rsf", "fn a()\n")), "fn a()\n", emptyMap())
        val bytes = classWithAttribute(RsfMeta.ATTRIBUTE, payload)
        assertArrayEquals(payload, RsfMeta.readClassAttribute(bytes, RsfMeta.ATTRIBUTE))
        assertNull(RsfMeta.readClassAttribute(bytes, "NoSuchAttribute"))
        assertNull(RsfMeta.readClassAttribute("not a class".toByteArray(), RsfMeta.ATTRIBUTE))
    }

    // ------------------------------------------------------------------ 落盘

    @Test
    fun `safeRelative rejects escaping paths`() {
        assertEquals("a/b.rsf", RsfMeta.safeRelative("a\\b.rsf"))
        assertEquals("a/b.rsf", RsfMeta.safeRelative("./a/b.rsf"))
        assertThrows<RsfError> { RsfMeta.safeRelative("../evil.rsf") }
        assertThrows<RsfError> { RsfMeta.safeRelative("a/../../evil.rsf") }
        assertThrows<RsfError> { RsfMeta.safeRelative("   ") }
    }

    @Test
    fun `writeSources restores nested paths and refuses to escape outDir`() {
        val dir = tempDir("write")
        val blob = RsfMeta.decode(
            RsfMeta.encode(
                listOf(
                    RsfMeta.SourceSnapshot("src/main.rsf", "fn main()\n"),
                    RsfMeta.SourceSnapshot("src/deep/util.rsf", "fn u()\n"),
                ),
                "",
                emptyMap(),
            ),
        )

        val written = RsfMeta.writeSources(blob, dir)
        assertEquals(2, written.size)
        assertEquals("fn main()\n", File(dir, "src/main.rsf").readText())
        assertEquals("fn u()\n", File(dir, "src/deep/util.rsf").readText())
        assertTrue(written.all { it.canonicalPath.startsWith(dir.canonicalPath) })

        val evil = RsfMeta.decode(
            RsfMeta.encode(listOf(RsfMeta.SourceSnapshot("../escape.rsf", "x")), "", emptyMap()),
        )
        assertThrows<RsfError> { RsfMeta.writeSources(evil, dir) }
    }

    // ------------------------------------------------------------------ 编译产物里真有快照

    @Test
    fun `compiled class carries the exact source snapshot`() {
        val dir = tempDir("e2e")
        val src = File(dir, "hello.rsf")
        src.writeText(
            "fn main() {\n" +
                "    println(\"hello meta\")\n" +
                "}\n",
        )

        val loaded = Rsf.load(src)
        val model = Rsf.analyze(loaded)
        val meta = RsfMeta.encode(
            RsfMeta.snapshotsOf(loaded),
            RsfMeta.symbolsOf(loaded),
            RsfMeta.describe("jvm", "hello"),
        )
        val outDir = File(dir, "out")
        Rsf.build(model, loaded.root, "hello", outDir, meta)

        val classBytes = File(outDir, "hello.class").readBytes()
        val found = RsfMeta.readClassAttribute(classBytes, RsfMeta.ATTRIBUTE)
            ?: error("class 里没有 ${RsfMeta.ATTRIBUTE} 属性")
        val blob = RsfMeta.decode(found)
        assertTrue(blob.isExact)
        assertEquals(1, blob.sources.size)
        assertEquals(src.readText(), blob.sources[0].text)
        assertTrue(blob.sources[0].path.endsWith("hello.rsf"))
        assertTrue(blob.symbols.contains("main"))
        assertEquals("jvm", blob.build["artifact"])

        // 还原到另一目录后逐字节一致
        val restored = RsfMeta.writeSources(blob, File(dir, "restored"))
        assertEquals(1, restored.size)
        assertEquals(src.readText(), restored[0].readText())
    }

    // ------------------------------------------------------------------ 素材构造

    /** 造一个够真的 PE：DOS 头 → PE 签名 → 节表里有一节指向 [payload]。 */
    private fun minimalPe(payload: ByteArray): ByteArray {
        val peAt = 0x40
        val optSize = 0xE0
        val sectionAt = peAt + 24 + optSize
        val rawAt = sectionAt + 40
        val total = rawAt + payload.size
        val bytes = ByteArray(total)

        fun u16(at: Int, v: Int) {
            bytes[at] = (v and 0xff).toByte()
            bytes[at + 1] = ((v ushr 8) and 0xff).toByte()
        }
        fun u32(at: Int, v: Int) {
            u16(at, v and 0xffff)
            u16(at + 2, (v ushr 16) and 0xffff)
        }

        u32(0x3c, peAt)
        "PE\u0000\u0000".forEachIndexed { i, c -> bytes[peAt + i] = c.code.toByte() }
        u16(peAt + 6, 1)
        u16(peAt + 20, optSize)

        val name = RsfMeta.SECTION.padEnd(8, '\u0000')
        name.forEachIndexed { i, c -> bytes[sectionAt + i] = c.code.toByte() }
        u32(sectionAt + 16, payload.size)
        u32(sectionAt + 20, rawAt)
        payload.copyInto(bytes, rawAt)
        return bytes
    }

    private class RawAttribute(type: String, private val data: ByteArray) : Attribute(type) {
        override fun write(
            classWriter: ClassWriter,
            code: ByteArray?,
            codeLength: Int,
            maxStack: Int,
            maxLocals: Int,
        ): ByteVector = ByteVector().putByteArray(data, 0, data.size)
    }

    private fun classWithAttribute(name: String, payload: ByteArray): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Holder", null, "java/lang/Object", null)
        cw.visitEnd()
        cw.visitAttribute(RawAttribute(name, payload))
        return cw.toByteArray()
    }
}
