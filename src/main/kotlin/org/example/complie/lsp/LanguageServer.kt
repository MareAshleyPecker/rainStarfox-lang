package org.example.complie.lsp

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files

/**
 * rainStarfox 的语言服务器（LSP over stdio）。
 *
 * 和 `check --json` / `complete` 那两条一次性命令的关系：那两个是「起 JVM 算一次」，
 * 这个是常驻——`initialize` 之后所有请求都在同一个进程里跑，编辑器那边每次只要
 * 一次管道往返（毫秒级）。
 *
 * 支持的能力（保守够用）：
 * - 全量同步：`didOpen` / `didChange` / `didClose`
 * - 诊断：`textDocument/diagnostic`（拉取）；同时在开/改之后 `publishDiagnostics` 推送
 * - 补全：`textDocument/completion`
 *
 * **不做**的：跳转定义、引用、重命名、格式化。这些要先用上语义模型里的符号位置，
 * 属于下一刀；接口形状已经留好（见 [dispatch]）。
 *
 * 协议层（分帧、JSON）在 [JsonRpc] / [JsonParser]，这里只管方法分发。
 */
class LanguageServer(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: PrintStream = System.err,
) {

    /** 编辑器打开的文档：uri → 文本。诊断优先用这份，而不是磁盘上的旧内容。 */
    private val documents = LinkedHashMap<String, Doc>()

    private var shutdownRequested = false

    /** 客户端声明了拉取式诊断（`textDocument.diagnostic`）时就不再推送，省一半计算。 */
    private var pullDiagnostics = false

    private data class Doc(val uri: String, val text: String, val version: Int)

    // ------------------------------------------------------------------ 主循环

    fun serve() {
        while (true) {
            val raw = JsonRpc.readMessage(input) ?: return
            val message = try {
                JsonParser.parse(raw)
            } catch (e: IllegalArgumentException) {
                log.println("[rsf-lsp] 收到无法解析的消息：${e.message}")
                continue
            }
            val method = message.str("method") ?: continue
            val id = message["id"]
            if (method == "exit") return
            val result = try {
                dispatch(method, message["params"] ?: JsonValue.Null)
            } catch (e: Exception) {
                log.println("[rsf-lsp] 处理 $method 失败：${e.message}")
                if (id != null) errorResponse(id, -32603, e.message ?: e::class.simpleName ?: "内部错误")
                continue
            }
            if (id != null) {
                val body = if (method == "shutdown") JsonValue.Null else (result ?: JsonValue.Null)
                JsonRpc.writeMessage(output, response(id, body).render())
                if (method == "shutdown") shutdownRequested = true
            }
        }
    }

    // ------------------------------------------------------------------ 方法分发

    fun dispatch(method: String, params: JsonValue): JsonValue? = when (method) {
        "initialize" -> initialize(params)
        "initialized" -> null
        "shutdown" -> JsonValue.Null
        "textDocument/didOpen" -> {
            didOpen(params)
            null
        }

        "textDocument/didChange" -> {
            didChange(params)
            null
        }

        "textDocument/didClose" -> {
            params.str("textDocument", "uri")?.let { documents.remove(it) }
            null
        }

        "textDocument/diagnostic" -> diagnostic(params)
        "textDocument/completion" -> completion(params)
        // 编辑器会发这些，回个空结果让它别等
        "textDocument/definition", "textDocument/hover" -> JsonValue.Null
        "$/cancelRequest", "workspace/didChangeConfiguration", "workspace/didChangeWatchedFiles" -> null
        else -> {
            log.println("[rsf-lsp] 忽略未知方法 $method")
            null
        }
    }

    private fun initialize(params: JsonValue): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "capabilities" to JsonValue.Obj(
                linkedMapOf(
                    "textDocumentSync" to JsonValue.Num(1.0), // 1 = Full：每次发整篇文本，简单可靠
                    "completionProvider" to JsonValue.Obj(
                        linkedMapOf("triggerCharacters" to JsonValue.Arr(listOf(JsonValue.Str(".")))),
                    ),
                    "diagnosticProvider" to JsonValue.Obj(
                        linkedMapOf(
                            "interFileDependencies" to JsonValue.Bool(true),
                            "workspaceDiagnostics" to JsonValue.Bool(false),
                        ),
                    ),
                ),
            ),
            "serverInfo" to JsonValue.Obj(
                linkedMapOf(
                    "name" to JsonValue.Str("rainStarfox"),
                    "version" to JsonValue.Str(org.example.complie.Rsf.VERSION),
                ),
            ),
        ),
    ).also {
        pullDiagnostics = params.path("capabilities", "textDocument", "diagnostic") != null
    }

    private fun didOpen(params: JsonValue) {
        val td = params.obj("textDocument") ?: return
        val uri = td.str("uri") ?: return
        val text = td.str("text") ?: return
        val version = td.int("version") ?: 0
        documents[uri] = Doc(uri, text, version)
        publishDiagnostics(uri)
    }

    private fun didChange(params: JsonValue) {
        val uri = params.str("textDocument", "uri") ?: return
        val change = params.arr("contentChanges")?.firstOrNull() ?: return
        val text = change.str("text") ?: return
        val version = params.obj("textDocument")?.int("version") ?: 0
        documents[uri] = Doc(uri, text, version)
        publishDiagnostics(uri)
    }

    // ------------------------------------------------------------------ 诊断

    /** 拉取式诊断（LSP 3.17）：编辑器要的时候算一次，返回整篇的报告。 */
    fun diagnostic(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return emptyReport()
        val items = JsonValue.Arr(diagnosticsOf(uri).map { it.toLsp() })
        return JsonValue.Obj(linkedMapOf("kind" to JsonValue.Str("full"), "items" to items))
    }

    /** 推送式：开/改之后主动告诉编辑器，别的编辑器（VS Code）靠这条。 */
    private fun publishDiagnostics(uri: String) {
        // 支持拉取诊断的客户端（IDEA 插件、VS Code 新版）会自己来要，推了它也不看
        if (pullDiagnostics) return
        val payload = JsonValue.Obj(
            linkedMapOf(
                "uri" to JsonValue.Str(uri),
                "diagnostics" to JsonValue.Arr(diagnosticsOf(uri).map { it.toLsp() }),
            ),
        )
        val message = JsonValue.Obj(
            linkedMapOf(
                "jsonrpc" to JsonValue.Str("2.0"),
                "method" to JsonValue.Str("textDocument/publishDiagnostics"),
                "params" to payload,
            ),
        )
        runCatching { JsonRpc.writeMessage(output, message.render()) }
    }

    private fun emptyReport(): JsonValue =
        JsonValue.Obj(linkedMapOf("kind" to JsonValue.Str("full"), "items" to JsonValue.Arr(emptyList())))

    private fun diagnosticsOf(uri: String): List<LocatedDiag> {
        val path = uriToPath(uri) ?: return emptyList()
        val doc = documents[uri]
        val temp = doc?.let { writeTemp(it) }
        val file = temp ?: File(path)
        return try {
            val baseDir = File(path).parentFile ?: File(".").absoluteFile
            EditorSupport.diagnostics(file, baseDir).map { d ->
                // 临时文件的名字不该出现在编辑器里：落在临时文件里的那条报回文档自己的 URI
                val owner = if (d.file == file.path) uri else pathToUri(d.file)
                LocatedDiag(owner, d.line, d.col, d.endCol, d.severity, d.phase, d.message)
            }
        } catch (e: Exception) {
            log.println("[rsf-lsp] 检查 $uri 时出错：${e.message}")
            emptyList()
        } finally {
            temp?.delete()
        }
    }

    /** 未保存的缓冲区交给编译器时要落个临时文件（import 仍按真实目录找）。 */
    private fun writeTemp(doc: Doc): File {
        val temp = Files.createTempFile("rsf-lsp-", ".rsf").toFile()
        temp.writeText(doc.text, Charsets.UTF_8)
        temp.deleteOnExit()
        return temp
    }

    // ------------------------------------------------------------------ 补全

    fun completion(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Arr(emptyList())
        val line = params.obj("position")?.int("line") ?: return JsonValue.Arr(emptyList())
        val character = params.obj("position")?.int("character") ?: 0
        val path = uriToPath(uri) ?: return JsonValue.Arr(emptyList())
        val doc = documents[uri]
        val temp = doc?.let { writeTemp(it) }
        try {
            val file = temp ?: File(path)
            val baseDir = File(path).parentFile ?: File(".").absoluteFile
            val items = EditorSupport
                .completions(file, doc?.text, line + 1, character + 1, baseDir)
                .map { item ->
                    JsonValue.Obj(
                        linkedMapOf(
                            "label" to JsonValue.Str(item.label),
                            "kind" to JsonValue.Num(kindOf(item.kind).toDouble()),
                            "detail" to JsonValue.Str(item.detail),
                        ),
                    )
                }
            return JsonValue.Obj(
                linkedMapOf("isIncomplete" to JsonValue.Bool(false), "items" to JsonValue.Arr(items)),
            )
        } finally {
            temp?.delete()
        }
    }

    /** LSP 的 CompletionItemKind 编号（不是字符串）。 */
    private fun kindOf(kind: String): Int = when (kind) {
        "function" -> 3
        "method" -> 2
        "variable" -> 6
        "field" -> 5
        "class" -> 7
        "type" -> 25
        "keyword" -> 14
        else -> 1
    }

    // ------------------------------------------------------------------ 编解码

    /** 一条 LSP 诊断。位置是 0 基，字符按 UTF-16 码元算（Kotlin 的 String 下标正好如此）。 */
    private data class LocatedDiag(
        val uri: String,
        val line: Int,
        val col: Int,
        val endCol: Int,
        val severity: String,
        val phase: String,
        val message: String,
    ) {
        fun toLsp(): JsonValue = JsonValue.Obj(
            linkedMapOf(
                "range" to JsonValue.Obj(
                    linkedMapOf(
                        "start" to JsonValue.Obj(
                            linkedMapOf(
                                "line" to JsonValue.Num((line - 1).coerceAtLeast(0).toDouble()),
                                "character" to JsonValue.Num((col - 1).coerceAtLeast(0).toDouble()),
                            ),
                        ),
                        "end" to JsonValue.Obj(
                            linkedMapOf(
                                "line" to JsonValue.Num((line - 1).coerceAtLeast(0).toDouble()),
                                "character" to JsonValue.Num((endCol - 1).coerceAtLeast(col).toDouble()),
                            ),
                        ),
                    ),
                ),
                "severity" to JsonValue.Num(
                    when (severity) {
                        "warning" -> 2.0
                        "info" -> 3.0
                        else -> 1.0
                    },
                ),
                "code" to JsonValue.Str(phase),
                "source" to JsonValue.Str("rainStarfox"),
                "message" to JsonValue.Str(message),
            ),
        )
    }

    private fun response(id: JsonValue, result: JsonValue): JsonValue = JsonValue.Obj(
        linkedMapOf("jsonrpc" to JsonValue.Str("2.0"), "id" to id, "result" to result),
    )

    private fun errorResponse(id: JsonValue, code: Int, message: String): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "jsonrpc" to JsonValue.Str("2.0"),
            "id" to id,
            "error" to JsonValue.Obj(
                linkedMapOf("code" to JsonValue.Num(code.toDouble()), "message" to JsonValue.Str(message)),
            ),
        ),
    )

    /** 内部日志只走 stderr：stdout 是协议通道，写一点别的进去编辑器就解不了帧。 */
    fun note(text: String) = log.println("[rsf-lsp] $text")

    companion object {

        /** `file:///D:/a/b.rsf` ↔ `D:/a/b.rsf`；非 file 协议返回 null。 */
        fun uriToPath(uri: String): String? {
            if (!uri.startsWith("file:", ignoreCase = true)) return null
            var rest = uri.substring(5)
            if (rest.startsWith("//")) rest = rest.substring(2)
            // 去掉主机名（`file://localhost/...`），Windows 下冒号前的那段是盘符，保留
            val slash = rest.indexOf('/')
            if (slash >= 0 && rest.substring(0, slash).contains('.')) rest = rest.substring(slash)
            val decoded = runCatching { URLDecoder.decode(rest, "UTF-8") }.getOrDefault(rest)
            // Windows：`/D:/x` → `D:/x`
            return if (decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':') decoded.substring(1) else decoded
        }

        fun pathToUri(path: String): String {
            val normalized = path.replace('\\', '/')
            val encoded = normalized.split('/').mapIndexed { i, seg ->
                val e = runCatching { URLEncoder.encode(seg, "UTF-8").replace("+", "%20") }.getOrDefault(seg)
                // 盘符里的冒号（`D:`）是 URI 路径的一部分，不能编码成 %3A
                if (i == 0) e.replace("%3A", ":", ignoreCase = true) else e
            }.joinToString("/")
            val withSlash = if (encoded.startsWith("/")) encoded else "/$encoded"
            return "file://" + withSlash
        }
    }
}
