package org.rainstarfox.idea

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 语言服务器客户端：插件这边唯一和编译器打交道的地方。
 *
 * 走的是 LSP（stdio 上的 JSON-RPC），不是 `check --json` 那种一次性命令。区别很重要：
 * 服务器**常驻**——`initialize` 之后每次诊断/补全只花一次管道往返（毫秒级），
 * 而不是每次起一个 JVM（两三百毫秒）。这就是「Kotlin 手感」的成本来源。
 *
 * 进程由 [RsfLspService] 按「启动器 + 工程目录」缓存；这个类本身只管一条连接：
 * 发请求、等对应 id 的响应、把通知丢掉。线程模型：任意线程可调，请求在锁里串行。
 */
class RsfLspClient private constructor(
    private val process: Process,
    private val commandLine: String,
) : AutoCloseable {

    data class Diagnostic(
        /** 诊断所属文档的 file URI（不是路径）。 */
        val uri: String,
        val line: Int,
        val col: Int,
        val endCol: Int,
        val severity: String,
        val phase: String,
        val message: String,
    )

    data class Item(val label: String, val kind: String, val detail: String)

    private val log = Logger.getInstance(RsfLspClient::class.java)

    private val stdin: OutputStream = process.outputStream
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<String>>()
    private val writeLock = Any()
    private var nextId = 1
    private var closed = false

    /** 每个文档的版本号：LSP 的 didChange 带版本，递增即可。 */
    private val versions = HashMap<String, Int>()

    /** 已经 didOpen 过的文档，避免重复 Open（编辑器每次重析都会调诊断）。 */
    private val opened = HashSet<String>()

    init {
        val reader = Thread({ readLoop() }, "rsf-lsp-reader")
        reader.isDaemon = true
        reader.start()
    }

    // ------------------------------------------------------------------ 生命周期

    fun initialize(rootUri: String?): Boolean {
        val params = buildString {
            append("{\"processId\":").append(ProcessHandle.current().pid())
            append(",\"clientInfo\":{\"name\":\"rainStarfox-idea\"}")
            rootUri?.let { append(",\"rootUri\":").append(quote(it)) }
            append(",\"capabilities\":{\"textDocument\":{\"diagnostic\":{},\"completion\":{}}}}")
        }
        val result = request("initialize", params)
        if (result == null || result == "null" || !process.isAlive) {
            log.warn("rsf lsp 初始化失败：$commandLine")
            return false
        }
        notify("initialized", "{}")
        return true
    }

    override fun close() {
        closed = true
        runCatching { request("shutdown", "null", timeoutMs = 1000) }
        runCatching { notify("exit", "{}") }
        runCatching { process.destroy() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        process.destroyForcibly()
    }

    fun isAlive(): Boolean = process.isAlive && !closed

    // ------------------------------------------------------------------ 请求

    /** 诊断（拉取式）。文本总是整篇发过去——全量同步，省得在插件里再维护一份增量状态。 */
    fun diagnose(uri: String, text: String): List<Diagnostic> {
        syncDocument(uri, text)
        val json = request(
            "textDocument/diagnostic",
            "{\"textDocument\":{\"uri\":${quote(uri)}}}",
        ) ?: return emptyList()
        val root = RsfJson.parse(json) ?: return emptyList()
        return RsfJson.objectsAt(root, "items").map { d ->
            val range = RsfJson.objectAt(d, "range") ?: emptyMap()
            val start = RsfJson.objectAt(range, "start") ?: emptyMap()
            val end = RsfJson.objectAt(range, "end") ?: emptyMap()
            Diagnostic(
                uri = uri,
                line = RsfJson.int(start, "line", 0) + 1,
                col = RsfJson.int(start, "character", 0) + 1,
                endCol = RsfJson.int(end, "character", 0) + 1,
                severity = when (RsfJson.int(d, "severity", 1)) {
                    2 -> "warning"
                    3 -> "info"
                    else -> "error"
                },
                phase = RsfJson.string(d, "code", "checker"),
                message = RsfJson.string(d, "message"),
            )
        }
    }

    /** 补全。位置是 0 基（LSP 口径），直接透传。 */
    fun complete(uri: String, text: String, line: Int, character: Int): List<Item> {
        syncDocument(uri, text)
        val params = "{\"textDocument\":{\"uri\":${quote(uri)}},\"position\":{\"line\":$line,\"character\":$character}}"
        val json = request("textDocument/completion", params) ?: return emptyList()
        val root = RsfJson.parse(json) ?: return emptyList()
        return RsfJson.objectsAt(root, "items").map { d ->
            Item(
                label = RsfJson.string(d, "label"),
                kind = kindName(RsfJson.int(d, "kind", 1)),
                detail = RsfJson.string(d, "detail"),
            )
        }.filter { it.label.isNotEmpty() }
    }

    /** 光标扫到这儿时把当前缓冲区同步过去（打开/改动都走 didChange）。 */
    private fun syncDocument(uri: String, text: String) {
        synchronized(versions) {
            val version = (versions[uri] ?: 0) + 1
            versions[uri] = version
            if (opened.add(uri)) {
                notify(
                    "textDocument/didOpen",
                    "{\"textDocument\":{\"uri\":${quote(uri)},\"languageId\":\"rsf\",\"version\":$version,\"text\":${quote(text)}}}",
                )
            } else {
                notify(
                    "textDocument/didChange",
                    "{\"textDocument\":{\"uri\":${quote(uri)},\"version\":$version},\"contentChanges\":[{\"text\":${quote(text)}}]}",
                )
            }
        }
    }

    private fun request(method: String, paramsJson: String, timeoutMs: Long = 10_000): String? {
        if (closed) return null
        val queue = ArrayBlockingQueue<String>(1)
        val id = synchronized(writeLock) {
            val current = nextId++
            pending[current] = queue
            val message = "{\"jsonrpc\":\"2.0\",\"id\":$current,\"method\":${quote(method)},\"params\":$paramsJson}"
            if (!send(message)) {
                pending.remove(current)
                return null
            }
            current
        }
        return try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        } finally {
            pending.remove(id)
        }
    }

    private fun notify(method: String, paramsJson: String) {
        if (closed) return
        synchronized(writeLock) {
            send("{\"jsonrpc\":\"2.0\",\"method\":${quote(method)},\"params\":$paramsJson}")
        }
    }

    private fun send(message: String): Boolean = try {
        val payload = message.toByteArray(Charsets.UTF_8)
        stdin.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        stdin.write(payload)
        stdin.flush()
        true
    } catch (e: Exception) {
        log.warn("写 rsf lsp 失败：${e.message}")
        false
    }

    // ------------------------------------------------------------------ 读线程

    private fun readLoop() {
        val input: InputStream = process.inputStream
        while (!closed) {
            val raw = try {
                readMessage(input)
            } catch (e: Exception) {
                log.warn("读 rsf lsp 失败：${e.message}")
                null
            } ?: break

            val parsed = RsfJson.parse(raw) as? Map<*, *> ?: continue
            val id = (parsed["id"] as? Double)?.toInt() ?: continue
            val queue = pending.remove(id) ?: continue
            val result = parsed["result"]
            val error = parsed["error"] as? Map<*, *>
            if (error != null) {
                log.warn("rsf lsp 返回错误：${error["message"]}")
                queue.offer("null")
            } else {
                queue.offer(render(result))
            }
        }
        // 进程没了：把还在等的请求都放掉，别让 EDT 卡住
        closed = true
        for ((_, q) in pending) q.offer("null")
        pending.clear()
    }

    private fun readMessage(input: InputStream): String? {
        var length = -1
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(idx + 1).trim().toIntOrNull() ?: return null
            }
        }
        if (length < 0) return null
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readHeaderLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    // ------------------------------------------------------------------ JSON 辅助

    private fun kindName(kind: Int): String = when (kind) {
        2 -> "method"
        3 -> "function"
        5 -> "field"
        6 -> "variable"
        7 -> "class"
        25 -> "type"
        14 -> "keyword"
        else -> "value"
    }

    /** 把解析出来的 JSON 值重新渲染成文本：客户端只需要 `result`，交给 RsfJson 再读一遍。 */
    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + render(v) }
        is List<*> -> value.joinToString(",", "[", "]") { render(it) }
        else -> quote(value.toString())
    }

    companion object {

        /** 起一个 `rsf lsp` 进程并完成握手；失败返回 null（编辑器照常能用，只是没诊断）。 */
        fun start(command: RsfCommand, workDir: File?): RsfLspClient? {
            return try {
                val commandLine = command.commandLine("lsp")
                workDir?.let { commandLine.withWorkDirectory(it) }
                val process = commandLine.createProcess()
                val client = RsfLspClient(process, commandLine.commandLineString)
                if (!client.initialize(workDir?.toURI()?.toString())) {
                    client.close()
                    null
                } else {
                    client
                }
            } catch (e: Exception) {
                Logger.getInstance(RsfLspClient::class.java).warn("起 rsf lsp 失败：${e.message}")
                null
            }
        }

        /** JSON 字符串字面量（请求里塞进去的文本/路径都得转义）。 */
        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 16)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        /** `D:\a\b.rsf` → `file:///D:/a/b.rsf`（LSP 的盘符不带百分号编码）。 */
        fun toUri(path: String): String {
            val normalized = path.replace('\\', '/')
            val encoded = normalized.split('/').mapIndexed { i, seg ->
                val e = java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
                if (i == 0) e.replace("%3A", ":", ignoreCase = true) else e
            }.joinToString("/")
            return "file://" + (if (encoded.startsWith("/")) encoded else "/$encoded")
        }
    }
}

/**
 * 连接池：按「启动器 + 工程目录」缓存一条常驻连接。
 *
 * 为什么按工程目录：服务器要解析 `imp` 的相邻文件，工作目录决定了相对路径的基准；
 * 跨工程复用会把两个工程的 import 搅在一起。
 */
object RsfLspService {

    private val clients = HashMap<String, RsfLspClient>()
    private val lock = Any()

    fun client(command: RsfCommand, workDir: File?): RsfLspClient? = synchronized(lock) {
        val key = command.commandLineString + "|" + (workDir?.absolutePath ?: "")
        val existing = clients[key]
        if (existing != null && existing.isAlive()) return existing
        existing?.close()
        val started = RsfLspClient.start(command, workDir) ?: return null
        clients[key] = started
        started
    }

    fun closeAll() = synchronized(lock) {
        for ((_, c) in clients) c.close()
        clients.clear()
    }

    /** IDE 退出时把子进程收掉：常驻进程不关，任务管理器里会留一排 java。 */
    init {
        Runtime.getRuntime().addShutdownHook(Thread({ closeAll() }, "rsf-lsp-shutdown"))
    }
}
