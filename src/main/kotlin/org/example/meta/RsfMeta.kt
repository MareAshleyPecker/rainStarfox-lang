package org.example.meta

import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.complie.loading.LoadedProgram
import org.example.complie.diag.SourceFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 产物里的元数据块（`.rsfmeta`）。
 *
 * 目标：编译产物自己带上「这份程序是什么、源码长什么样」，这样 `rsf decompile` 不依赖
 * 任何旁路文件就能把源码原样还原，也给后续的结构化反编译（行号 / 符号 / 类型表）留位置。
 *
 * 载体：
 *  * 原生（`--c` + gcc）：PE 自定义节 `.rsfmeta`，见 [cDeclaration]。
 *  * JVM：class 文件的自定义属性 `RsfSource`（JVM 会忽略不认识的属性）。
 *
 * 二进制格式（大端，与 class 文件一致）：
 * ```
 * "RSFMETA1"      8 字节魔数
 * u8  version     目前 1
 * u8  reserved    0
 * u16 chunkCount
 * u32 totalBytes  整个块的长度（含头），用来切掉 PE 节的对齐填充
 * 重复 chunkCount 次：u8 type, u32 length, length 字节负载
 * ```
 * chunk：
 *  * [CHUNK_SOURCE]：u16 路径长 + 路径 UTF-8 + u32 原文长 + 原文 UTF-8（每个源文件一条）
 *  * [CHUNK_SYMBOL]：UTF-8 文本，一行一个符号（反编译的 L2 底料）
 *  * [CHUNK_BUILD]：UTF-8 文本，`key=value` 行（编译器版本 / 构建时间 / 类名）
 */
object RsfMeta {

    const val MAGIC = "RSFMETA1"
    const val VERSION = 1
    const val SECTION = ".rsfmeta"
    const val ATTRIBUTE = "RsfSource"

    const val CHUNK_SOURCE = 1
    const val CHUNK_SYMBOL = 2
    const val CHUNK_BUILD = 3

    /** 负载是 deflate 压缩过的。 */
    const val FLAG_DEFLATE = 1

    /** 头部长度：magic(8) + version(1) + flags(1) + chunkCount(2) + rawLen(4) + payloadLen(4)。 */
    const val HEADER = 20

    /** 一个源文件快照。 */
    data class SourceSnapshot(val path: String, val text: String)

    data class Blob(
        val sources: List<SourceSnapshot>,
        val symbols: String,
        val build: Map<String, String>,
        val raw: ByteArray,
    ) {
        val isExact: Boolean get() = sources.isNotEmpty()
    }

    // ------------------------------------------------------------------ 编码

    fun encode(sources: List<SourceSnapshot>, symbols: String, build: Map<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)

        for (s in sources) {
            val path = s.path.toByteArray(Charsets.UTF_8)
            val text = s.text.toByteArray(Charsets.UTF_8)
            out.writeByte(CHUNK_SOURCE)
            out.writeInt(2 + path.size + 4 + text.size)
            out.writeShort(path.size)
            out.write(path)
            out.writeInt(text.size)
            out.write(text)
        }
        if (symbols.isNotEmpty()) {
            val bytes = symbols.toByteArray(Charsets.UTF_8)
            out.writeByte(CHUNK_SYMBOL)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        if (build.isNotEmpty()) {
            val text = build.entries.joinToString("\n") { "${it.key}=${it.value}" }
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeByte(CHUNK_BUILD)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.flush()

        val chunkBytes = body.toByteArray()
        val chunkCount = sources.size + (if (symbols.isNotEmpty()) 1 else 0) +
            (if (build.isNotEmpty()) 1 else 0)

        // 源码文本压缩率很高（3~4 倍），压一下能少往产物里塞几 MB
        val packed = deflate(chunkBytes)
        val flags = if (packed.size < chunkBytes.size) FLAG_DEFLATE else 0
        val payload = if (flags == FLAG_DEFLATE) packed else chunkBytes

        val head = ByteArrayOutputStream()
        val h = DataOutputStream(head)
        h.write(MAGIC.toByteArray(Charsets.US_ASCII))
        h.writeByte(VERSION)
        h.writeByte(flags)
        h.writeShort(chunkCount)
        h.writeInt(chunkBytes.size)
        h.writeInt(payload.size)
        h.write(payload)
        h.flush()
        return head.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out, java.util.zip.Deflater(9)).use { it.write(data) }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected.coerceAtLeast(64))
        java.util.zip.InflaterInputStream(data.inputStream()).use { it.copyTo(out) }
        val bytes = out.toByteArray()
        if (bytes.size != expected) {
            throw RsfError("decompile", "元数据解压后长度不对（期望 $expected，实际 ${bytes.size}）")
        }
        return bytes
    }

    /**
     * 从一次加载好的程序收集源码快照。
     *
     * 路径保留编译时的写法（相对路径就写相对路径），`decompile` 会按同样的相对结构还原；
     * 绝对路径只保留文件名，避免把开发机的目录结构写进产物。
     */
    fun snapshotsOf(loaded: LoadedProgram): List<SourceSnapshot> =
        loaded.files.map { SourceSnapshot(portablePath(it), it.text) }

    /**
     * 把绝对路径改写成相对当前工作目录的路径；不在工作目录下时只留文件名。
     *
     * 产物是可拷贝、可发布的，不能把开发机的目录结构写进去。
     */
    private fun portablePath(source: SourceFile): String {
        val p = source.path.replace('\\', '/')
        val cwd = System.getProperty("user.dir").replace('\\', '/').trimEnd('/')
        if (p.startsWith("$cwd/", ignoreCase = true)) return p.substring(cwd.length + 1)
        if (p.length >= 2 && p[1] == ':') return p.substringAfterLast('/')
        if (p.startsWith("/")) return p.substringAfterLast('/')
        return p.trimStart('.', '/')
    }

    /** 给反编译兜底的符号表：类、函数、字段各一行。 */
    fun symbolsOf(loaded: LoadedProgram): String {
        val lines = ArrayList<String>()
        for (fn in loaded.functions) {
            val ps = fn.params.joinToString(", ") { "${it.name}: ${it.type.display()}" }
            lines.add("fn ${fn.name}($ps)${fn.ret.display()}")
        }
        for (cls in loaded.classes) {
            lines.add("class ${cls.name}")
            for (f in cls.fields) lines.add("  field ${f.name}: ${f.type.display()}")
            for (m in cls.methods) {
                if (m.isConstructor) {
                    val ps = m.params.joinToString(", ") { "${it.name}: ${it.type.display()}" }
                    lines.add("  ctor($ps)")
                } else {
                    val ps = m.params.joinToString(", ") { "${it.name}: ${it.type.display()}" }
                    lines.add("  fn ${m.name}($ps)${m.ret.display()}")
                }
            }
        }
        for (e in loaded.enums) lines.add("enum ${e.name}")
        for (i in loaded.interfaces) lines.add("interface ${i.name}")
        return lines.joinToString("\n")
    }

    fun describe(kind: String, className: String): Map<String, String> = linkedMapOf(
        "compiler" to "rainStarfox ${Rsf.VERSION}",
        "artifact" to kind,
        "class" to className,
        "built" to java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        ),
    )

    // ------------------------------------------------------------------ 解码

    fun decode(bytes: ByteArray): Blob {
        if (bytes.size < HEADER) throw RsfError("decompile", "元数据块太短（${bytes.size} 字节）")
        val magic = String(bytes, 0, 8, Charsets.US_ASCII)
        if (magic != MAGIC) throw RsfError("decompile", "不是 rsf 元数据块（magic=$magic）")
        val version = bytes[8].toInt() and 0xff
        if (version != VERSION) throw RsfError("decompile", "元数据版本 $version 不认识（本程序支持 $VERSION）")
        val flags = bytes[9].toInt() and 0xff
        val count = readU16(bytes, 10)
        val rawLen = readU32(bytes, 12)
        val payloadLen = readU32(bytes, 16)
        if (HEADER + payloadLen > bytes.size) {
            throw RsfError(
                "decompile",
                "元数据块被截断（声明 $payloadLen 字节，实际只有 ${bytes.size - HEADER}）",
            )
        }
        val payload = bytes.copyOfRange(HEADER, HEADER + payloadLen)
        val area = if (flags and FLAG_DEFLATE != 0) inflate(payload, rawLen) else payload
        val end = area.size
        var at = 0
        val sources = ArrayList<SourceSnapshot>()
        val build = LinkedHashMap<String, String>()
        var symbols = ""
        repeat(count) {
            if (at + 5 > end) throw RsfError("decompile", "元数据块被截断（在读第 ${sources.size + 1} 个 chunk）")
            val type = area[at].toInt() and 0xff
            val len = readU32(area, at + 1)
            at += 5
            if (at + len > end) throw RsfError("decompile", "元数据 chunk 越界（type=$type, len=$len）")
            when (type) {
                CHUNK_SOURCE -> {
                    val pathLen = readU16(area, at)
                    val path = String(area, at + 2, pathLen, Charsets.UTF_8)
                    val textLen = readU32(area, at + 2 + pathLen)
                    val text = String(area, at + 2 + pathLen + 4, textLen, Charsets.UTF_8)
                    sources.add(SourceSnapshot(path, text))
                }
                CHUNK_SYMBOL -> symbols = String(area, at, len, Charsets.UTF_8)
                CHUNK_BUILD -> {
                    for (line in String(area, at, len, Charsets.UTF_8).split('\n')) {
                        val eq = line.indexOf('=')
                        if (eq > 0) build[line.substring(0, eq)] = line.substring(eq + 1)
                    }
                }
                else -> Unit // 未知 chunk 跳过，向前兼容
            }
            at += len
        }
        return Blob(sources, symbols, build, bytes.copyOf(HEADER + payloadLen))
    }

    // ------------------------------------------------------------------ 载体

    /**
     * 生成一段 C：把 [blob] 放进 PE 的 `.rsfmeta` 节。
     *
     * 用 `"\xNN"` 字符串字面量而不是 `0xNN,` 数组：每个字节只占 4 个字符，
     * 自举那种一两 MB 的源码快照不会让 C 文件膨胀到几十 MB。
     * 每个转义都以 `\` 开头，不会和后面的十六进制位粘在一起。
     */
    fun cDeclaration(blob: ByteArray): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(blob.size * 4 + 256)
        sb.append("\n/* rsf 元数据：源码快照，供 `rsf decompile` 还原 */\n")
        sb.append("__attribute__((section(\"$SECTION\"), used, aligned(1)))\n")
        sb.append("const char __rsf_meta[] =\n")
        val perLine = 24
        var i = 0
        while (i < blob.size) {
            sb.append("    \"")
            val stop = minOf(i + perLine, blob.size)
            while (i < stop) {
                val b = blob[i].toInt() and 0xff
                sb.append("\\x").append(hex[b ushr 4]).append(hex[b and 0xf])
                i++
            }
            sb.append("\"\n")
        }
        sb.append("    ;\n")
        sb.append("const unsigned int __rsf_meta_len = ${blob.size}u;\n")
        return sb.toString()
    }

    /** 读 PE 文件的某个节（按节名）。不是 PE 或没有该节都返回 null。 */
    fun readPeSection(bytes: ByteArray, section: String): ByteArray? {
        if (bytes.size < 0x40) return null
        val peAt = readU32le(bytes, 0x3c)
        if (peAt <= 0 || peAt + 24 > bytes.size) return null
        if (String(bytes, peAt, 4, Charsets.US_ASCII) != "PE\u0000\u0000") return null
        val sectionCount = readU16le(bytes, peAt + 6)
        val optionalSize = readU16le(bytes, peAt + 20)
        var at = peAt + 24 + optionalSize
        repeat(sectionCount) {
            if (at + 40 > bytes.size) return null
            val name = String(bytes, at, 8, Charsets.US_ASCII).trimEnd('\u0000')
            if (name == section) {
                val rawSize = readU32le(bytes, at + 16)
                val rawAt = readU32le(bytes, at + 20)
                if (rawAt <= 0 || rawAt + rawSize > bytes.size) return null
                return bytes.copyOfRange(rawAt, rawAt + rawSize)
            }
            at += 40
        }
        return null
    }

    /** 从 class 文件里取自定义属性 [ATTRIBUTE] 的负载。 */
    fun readClassAttribute(bytes: ByteArray, attribute: String): ByteArray? {
        if (bytes.size < 10) return null
        if (readU32(bytes, 0) != -0x35014542) return null // 0xCAFEBABE
        var at = 8
        val cpCount = readU16(bytes, at)
        at += 2
        val utf8 = HashMap<Int, String>()
        var i = 1
        while (i < cpCount) {
            if (at >= bytes.size) return null
            when (val tag = bytes[at].toInt() and 0xff) {
                1 -> {
                    val len = readU16(bytes, at + 1)
                    utf8[i] = String(bytes, at + 3, len, Charsets.UTF_8)
                    at += 3 + len
                }
                7, 8, 16, 19, 20 -> at += 3
                15 -> at += 4
                3, 4, 9, 10, 11, 12, 17, 18 -> at += 5
                5, 6 -> { at += 9; i++ }
                else -> throw RsfError("decompile", "class 常量池标签 $tag 不认识（偏移 $at）")
            }
            i++
        }
        // access_flags, this, super
        at += 6
        val interfaceCount = readU16(bytes, at)
        at += 2 + 2 * interfaceCount
        // 字段表
        val fieldCount = readU16(bytes, at)
        at += 2
        repeat(fieldCount) {
            at += 6
            at += attributesSize(bytes, at)
        }
        // 方法表
        val methodCount = readU16(bytes, at)
        at += 2
        repeat(methodCount) {
            at += 6
            at += attributesSize(bytes, at)
        }
        // 类属性
        val attrCount = readU16(bytes, at)
        at += 2
        repeat(attrCount) {
            if (at + 6 > bytes.size) return null
            val nameIdx = readU16(bytes, at)
            val len = readU32(bytes, at + 2)
            at += 6
            if (utf8[nameIdx] == attribute) return bytes.copyOfRange(at, at + len)
            at += len
        }
        return null
    }

    /** 字段/方法的属性表长度（含 count 本身）。 */
    private fun attributesSize(bytes: ByteArray, at: Int): Int {
        val count = readU16(bytes, at)
        var p = at + 2
        repeat(count) {
            val len = readU32(bytes, p + 2)
            p += 6 + len
        }
        return p - at
    }

    // ------------------------------------------------------------------ 小工具

    private fun readU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    private fun readU16le(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun readU32le(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    /** 把产物里的路径还原成安全的相对路径（拒绝绝对路径与 `..`）。 */
    fun safeRelative(path: String): String {
        // "." 段无害（永远不改变层级），直接丢掉；".." 一律拒绝——产物里的路径
        // 只该是相对编译目录的源文件名，出现 ".." 说明产物被人改过或写坏了。
        val parts = path.replace('\\', '/').split('/').map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) {
            throw RsfError("decompile", "产物里的源路径不合法：$path")
        }
        return parts.joinToString("/")
    }

    fun writeSources(blob: Blob, outDir: File): List<File> {
        val written = ArrayList<File>()
        for (s in blob.sources) {
            val rel = safeRelative(s.path)
            val target = File(outDir, rel)
            target.parentFile?.mkdirs()
            target.writeText(s.text, Charsets.UTF_8)
            written.add(target)
        }
        return written
    }
}
