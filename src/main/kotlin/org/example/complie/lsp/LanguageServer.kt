package org.example.complie.lsp

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLDecoder
import java.net.URLEncoder

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
 * - 跳转定义：`textDocument/definition`（Ctrl+点）
 * - 悬停文档：`textDocument/hover`（RDoc：声明正上方连续的 `///` 行）
 * - 找引用：`textDocument/references`（Find Usages）
 * - 重命名：`textDocument/rename`（返回 WorkspaceEdit）
 *
 * 同步方式：**增量优先**（阶段 45 起 `textDocumentSync = 2`）——`didChange` 的
 * 每个 `contentChanges` 元素可以带 `range`，按 range 打补丁；不带 `range` 就整篇替换，
 * 老客户端（全量同步）照样能用。
 *
 * **不做**的：格式化、`workspace/symbol`、跨工程索引。
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
        "textDocument/definition" -> definition(params)
        "textDocument/hover" -> hover(params)
        "textDocument/references" -> references(params)
        "textDocument/rename" -> rename(params)
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
                    "textDocumentSync" to JsonValue.Num(2.0), // 2 = Incremental：支持 range 补丁，也兼容整篇替换
                    "completionProvider" to JsonValue.Obj(
                        linkedMapOf("triggerCharacters" to JsonValue.Arr(listOf(JsonValue.Str(".")))),
                    ),
                    "definitionProvider" to JsonValue.Bool(true),
                    "hoverProvider" to JsonValue.Bool(true),
                    "referencesProvider" to JsonValue.Bool(true),
                    "renameProvider" to JsonValue.Bool(true),
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

    /**
     * `didChange`：每个 `contentChanges` 元素要么带 `range`（增量补丁），要么只带 `text`
     * （整篇替换）。两者可以混着来，按顺序应用。
     */
    private fun didChange(params: JsonValue) {
        val uri = params.str("textDocument", "uri") ?: return
        val version = params.obj("textDocument")?.int("version") ?: 0
        val changes = params.arr("contentChanges") ?: return
        var text = documents[uri]?.text ?: ""
        var applied = false
        for (change in changes) {
            val replacement = change.str("text") ?: continue
            val range = change.obj("range")
            text = if (range == null) replacement else replaceRange(text, range, replacement)
            applied = true
        }
        if (!applied) return
        documents[uri] = Doc(uri, text, version)
        publishDiagnostics(uri)
    }

    /** 按 LSP 的 range 替换（0 基、UTF-16 码元、`end` 不含）。位置越界就原样返回：宁可不改也不改坏。 */
    private fun replaceRange(text: String, range: JsonValue, replacement: String): String {
        val start = offsetOf(text, range.obj("start")) ?: return text
        val end = offsetOf(text, range.obj("end")) ?: return text
        if (start > end || end > text.length) return text
        return text.substring(0, start) + replacement + text.substring(end)
    }

    private fun offsetOf(text: String, position: JsonValue?): Int? {
        if (position == null) return null
        val line = position.int("line") ?: return null
        val character = position.int("character") ?: 0
        var offset = 0
        var current = 0
        while (current < line) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return null
            offset = next + 1
            current++
        }
        return (offset + character).coerceAtMost(text.length)
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
        val file = File(path)
        val doc = documents[uri]
        return try {
            val baseDir = file.parentFile ?: File(".").absoluteFile
            // 正文用内存里的（未保存也算数），路径仍用真实文件：包声明校验要按
            // 「文件在哪个目录」判，把它挪到 Temp 下会误报 `pkg` 位置不对。
            EditorSupport.diagnostics(file, baseDir, doc?.text).map { d ->
                val owner = if (d.file == file.path || d.file == file.canonicalPath) uri else pathToUri(d.file)
                LocatedDiag(owner, d.line, d.col, d.endCol, d.severity, d.phase, d.message)
            }
        } catch (e: Exception) {
            log.println("[rsf-lsp] 检查 $uri 时出错：${e.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ 补全

    fun completion(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Arr(emptyList())
        val line = params.obj("position")?.int("line") ?: return JsonValue.Arr(emptyList())
        val character = params.obj("position")?.int("character") ?: 0
        val path = uriToPath(uri) ?: return JsonValue.Arr(emptyList())
        val doc = documents[uri]
        run {
            val file = File(path)
            val baseDir = file.parentFile ?: File(".").absoluteFile
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
        }
    }

    // ------------------------------------------------------------------ 跳转与悬停

    /**
     * `textDocument/definition`：Ctrl+点。
     *
     * 只回**声明位置**，不做「引用」，也不猜：解析不到就回 `null`，编辑器会保持原地不动
     * （比跳到一个错地方好）。
     */
    fun definition(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Null
        val line = params.obj("position")?.int("line") ?: return JsonValue.Null
        val character = params.obj("position")?.int("character") ?: 0
        val symbol = symbolAt(uri, line, character) ?: return JsonValue.Null
        return JsonValue.Obj(
            linkedMapOf(
                "uri" to JsonValue.Str(pathToUri(symbol.file)),
                "range" to symbolRange(symbol),
            ),
        )
    }

    /** `textDocument/hover`：悬停显示 RDoc（没有注释就只显示签名和定义位置）。 */
    fun hover(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Null
        val line = params.obj("position")?.int("line") ?: return JsonValue.Null
        val character = params.obj("position")?.int("character") ?: 0
        val path = uriToPath(uri)
        val baseDir = path?.let { File(it).parentFile }?.absolutePath
        val symbol = symbolAt(uri, line, character) ?: return JsonValue.Null
        return JsonValue.Obj(
            linkedMapOf(
                "contents" to JsonValue.Obj(
                    linkedMapOf(
                        "kind" to JsonValue.Str("markdown"),
                        "value" to JsonValue.Str(RsfSymbols.hoverMarkdown(symbol, baseDir)),
                    ),
                ),
                "range" to symbolRange(symbol),
            ),
        )
    }

    /** `textDocument/references`：Find Usages。默认带声明自身（LSP 的 includeDeclaration）。 */
    fun references(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Arr(emptyList())
        val line = params.obj("position")?.int("line") ?: return JsonValue.Arr(emptyList())
        val character = params.obj("position")?.int("character") ?: 0
        val includeDeclaration = (params.path("context", "includeDeclaration") as? JsonValue.Bool)?.value ?: true
        val symbol = symbolAt(uri, line, character) ?: return JsonValue.Arr(emptyList())
        val locations = usagesOf(uri, symbol, includeDeclaration).map { (owner, usage) ->
            JsonValue.Obj(
                linkedMapOf(
                    "uri" to JsonValue.Str(owner),
                    "range" to rangeOf(usage.line, usage.column, usage.length),
                ),
            )
        }
        return JsonValue.Arr(locations)
    }

    /**
     * `textDocument/rename`：返回一份 `WorkspaceEdit`（`changes`：uri → TextEdit 数组）。
     *
     * 名字先用同一套词法规矩校验；改不了就回 `null`，编辑器会提示「没法重命名」，
     * 比默默改一半强。v1 只做函数/方法/局部变量/字段，类型改名回 `null`（要连类型引用、
     * 构造器、JVM 名字一起动，见 DESIGN-45 的缺口）。
     */
    fun rename(params: JsonValue): JsonValue {
        val uri = params.str("textDocument", "uri") ?: return JsonValue.Null
        val line = params.obj("position")?.int("line") ?: return JsonValue.Null
        val character = params.obj("position")?.int("character") ?: 0
        val newName = params.str("newName") ?: return JsonValue.Null
        if (!IDENTIFIER.matches(newName)) return JsonValue.Null
        val symbol = symbolAt(uri, line, character) ?: return JsonValue.Null
        if (symbol.kind == "class" || symbol.kind == "interface" || symbol.kind == "enum" || symbol.kind == "annotation") {
            return JsonValue.Null
        }
        val byFile = LinkedHashMap<String, MutableList<JsonValue>>()
        for ((owner, usage) in usagesOf(uri, symbol, includeDeclaration = true)) {
            byFile.getOrPut(owner) { ArrayList() }.add(
                JsonValue.Obj(
                    linkedMapOf(
                        "range" to rangeOf(usage.line, usage.column, usage.length),
                        "newText" to JsonValue.Str(newName),
                    ),
                ),
            )
        }
        if (byFile.isEmpty()) return JsonValue.Null
        return JsonValue.Obj(
            linkedMapOf(
                "changes" to JsonValue.Obj(byFile.mapValues { JsonValue.Arr(it.value) }),
            ),
        )
    }

    /** 加载文档（正文用内存里的）并在其中算符号的引用；引用文件换算成它自己的 URI。 */
    private fun usagesOf(
        uri: String,
        symbol: RsfSymbols.RsfSymbol,
        includeDeclaration: Boolean,
    ): List<Pair<String, RsfSymbols.Usage>> {
        val path = uriToPath(uri) ?: return emptyList()
        val file = File(path)
        val baseDir = file.parentFile ?: File(".").absoluteFile
        return try {
            val loaded = org.example.complie.Rsf.load(file, baseDir, documents[uri]?.text)
            RsfSymbols.references(loaded, symbol, includeDeclaration).map { usage ->
                val owner = if (sameFile(usage.file, path)) uri else pathToUri(usage.file)
                owner to usage
            }
        } catch (e: Exception) {
            log.println("[rsf-lsp] 找 $uri 的引用时出错：${e.message}")
            emptyList()
        }
    }

    /** 光标处解析到一个声明；未保存的缓冲区先落临时文件，解析完把文件名换回文档自己的路径。 */
    private fun symbolAt(uri: String, line0: Int, character0: Int): RsfSymbols.RsfSymbol? {
        val path = uriToPath(uri) ?: return null
        val file = File(path)
        val doc = documents[uri]
        return try {
            val baseDir = file.parentFile ?: File(".").absoluteFile
            val loaded = org.example.complie.Rsf.load(file, baseDir, doc?.text)
            val root = loaded.root
            val symbol = RsfSymbols.resolveAt(loaded, root, line0, character0) ?: return null
            if (sameFile(symbol.file, root.path) || sameFile(symbol.file, file.path)) symbol.copy(file = file.path) else symbol
        } catch (e: Exception) {
            log.println("[rsf-lsp] 解析 $uri 的符号时出错：${e.message}")
            return null
        }
    }

    /** 声明名字的区间（0 基）。 */
    private fun symbolRange(symbol: RsfSymbols.RsfSymbol): JsonValue =
        rangeOf(symbol.line, symbol.column, symbol.length)

    /** 1 基（行、列、长度）→ LSP 的 0 基 range。 */
    private fun rangeOf(line: Int, column: Int, length: Int): JsonValue = JsonValue.Obj(
        linkedMapOf(
            "start" to JsonValue.Obj(
                linkedMapOf(
                    "line" to JsonValue.Num((line - 1).coerceAtLeast(0).toDouble()),
                    "character" to JsonValue.Num((column - 1).coerceAtLeast(0).toDouble()),
                ),
            ),
            "end" to JsonValue.Obj(
                linkedMapOf(
                    "line" to JsonValue.Num((line - 1).coerceAtLeast(0).toDouble()),
                    "character" to JsonValue.Num((column - 1 + length).coerceAtLeast(0).toDouble()),
                ),
            ),
        ),
    )

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

        /** 重命名接受的新名字：字母/下划线开头，后接字母、数字、下划线（和词法器同一套）。 */
        private val IDENTIFIER = Regex("[_\\p{L}][_\\p{L}\\p{N}]*")

        /** 两个路径是不是同一份文件（大小写、分隔符、`.`/`..` 都抹平）。 */
        fun sameFile(a: String, b: String): Boolean = try {
            File(a).canonicalPath.equals(File(b).canonicalPath, ignoreCase = true)
        } catch (_: Exception) {
            a.equals(b, ignoreCase = true)
        }

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
