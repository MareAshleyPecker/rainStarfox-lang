package org.rainstarfox.idea

/**
 * 把语言服务器给的诊断**对齐到当前编辑器**，纯逻辑，单独拎出来好测。
 *
 * 两件事必须在这里做掉，否则 IDEA 里看到的东西会和命令行不一致：
 *
 * 1. **只留本文档的诊断**。服务器会把 `imp` 展开后所有文件的诊断都发回来（每条带
 *    自己的 `uri`）；编辑器只有本文档的 `Document`，别的文件的「第 7 行第 3 列」
 *    画到本文档上就是纯错位。
 * 2. **去重**。前端有一部分检查（比如泛型擦除警告）会在同一位置报同一条文案，
 *    叠两个波浪线只会让悬停提示闪。
 */
object RsfDiagnostics {

    /** 同一份文档？URI 在 Windows 上大小写与分隔符都不敏感，比较前先归一。 */
    fun sameDocument(diagnosticUri: String?, currentUri: String): Boolean {
        if (diagnosticUri.isNullOrBlank()) return true // 服务器没给 uri：保持旧行为，照画
        if (diagnosticUri == currentUri) return true
        return normalize(diagnosticUri).equals(normalize(currentUri), ignoreCase = isWindows())
    }

    /** 过滤 + 去重。顺序保持服务器给的顺序（error 在前是编译器的口径）。 */
    fun align(all: List<RsfLspClient.Diagnostic>, currentUri: String): List<RsfLspClient.Diagnostic> {
        val seen = HashSet<String>()
        val out = ArrayList<RsfLspClient.Diagnostic>(all.size)
        for (d in all) {
            if (!sameDocument(d.uri, currentUri)) continue
            val key = "${d.line}:${d.col}:${d.endCol}:${d.severity}:${d.message}"
            if (!seen.add(key)) continue
            out.add(d)
        }
        return out
    }

    private fun normalize(uri: String): String {
        // 百分号编码要还原（`a%20b.rsf` 与 `a b.rsf` 是同一个文件），盘符那段的 %3A 也还原成冒号
        val decoded = runCatching { java.net.URLDecoder.decode(uri, "UTF-8") }.getOrDefault(uri)
        return decoded.replace('\\', '/').trimEnd('/')
    }

    private fun isWindows(): Boolean = java.io.File.separatorChar == '\\'
}
