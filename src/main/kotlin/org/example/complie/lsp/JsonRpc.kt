package org.example.complie.lsp

/**
 * LSP 需要的那一小撮 JSON：解析请求、拼响应。
 *
 * 只做 LSP 用得着的部分，不追求通用（不支持大整数精度、不保留对象键顺序）。
 * 单独写一份而不是引依赖，是因为这个项目一直在「零外部依赖」的前提下跑
 * （只有 ASM 是例外，那是后端必需）。
 */
sealed class JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    object Null : JsonValue()

    // --- 便捷取值：LSP 里的字段几乎都可能是 null 或缺席，读取一律走这几个

    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    fun str(key: String): String? = (get(key) as? Str)?.value

    fun str(key: String, vararg more: String): String? = (path(key, *more) as? Str)?.value

    fun int(key: String): Int? = when (val v = get(key)) {
        is Num -> v.value.toInt()
        is Str -> v.value.toIntOrNull()
        else -> null
    }

    fun int(key: String, vararg more: String): Int? = when (val v = path(key, *more)) {
        is Num -> v.value.toInt()
        is Str -> v.value.toIntOrNull()
        else -> null
    }

    fun obj(key: String): JsonValue? = get(key) as? Obj

    /** 按路径取值：`message.path("params", "textDocument", "uri")`。 */
    fun path(vararg keys: String): JsonValue? {
        var cur: JsonValue? = this
        for (k in keys) cur = cur?.get(k) ?: return null
        return cur
    }

    fun arr(key: String): List<JsonValue>? = (get(key) as? Arr)?.items

    fun bool(key: String): Boolean = (get(key) as? Bool)?.value ?: false

    fun render(): String = buildString { write(this) }

    private fun write(sb: StringBuilder) {
        when (this) {
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(Json.escape(k)).append("\":")
                    v.write(sb)
                }
                sb.append('}')
            }

            is Arr -> {
                sb.append('[')
                for ((i, v) in items.withIndex()) {
                    if (i > 0) sb.append(',')
                    v.write(sb)
                }
                sb.append(']')
            }

            is Str -> sb.append('"').append(Json.escape(value)).append('"')

            is Num -> {
                // `1.0` 这种整数值按整数发出去：LSP 的 id / line / character 都是整数
                val d = value
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    sb.append(d.toLong())
                } else {
                    sb.append(d)
                }
            }

            is Bool -> sb.append(if (value) "true" else "false")

            Null -> sb.append("null")
        }
    }
}

/** 手写解析器：输入是一整条 JSON 文本。解析失败抛 [IllegalArgumentException]。 */
object JsonParser {

    fun parse(text: String): JsonValue {
        val p = Cursor(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.atEnd()) { "JSON 末尾还有多余字符：位置 ${p.pos}" }
        return v
    }

    private class Cursor(val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): JsonValue {
            skipWs()
            if (atEnd()) fail("内容为空")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) number() else fail("不认识的字符 '$c'")
            }
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON 解析失败（位置 $pos）：$msg")

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) fail("期望 '$word'")
            pos += word.length
            return value
        }

        private fun number(): JsonValue {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos < s.length && s[pos] == '.') {
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val text = s.substring(start, pos)
            return JsonValue.Num(text.toDoubleOrNull() ?: fail("不是数字：'$text'"))
        }

        private fun obj(): JsonValue {
            pos++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("对象的键必须是字符串")
                val key = string()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("键 '$key' 后面缺冒号")
                pos++
                fields[key] = value()
                skipWs()
                if (atEnd()) fail("对象没有收尾的 }")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }

                    else -> fail("对象里期望 , 或 }")
                }
            }
        }

        private fun arr(): JsonValue {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(value())
                skipWs()
                if (atEnd()) fail("数组没有收尾的 ]")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }

                    else -> fail("数组里期望 , 或 ]")
                }
            }
        }

        private fun string(): String {
            pos++ // 开引号
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("转义符后面没有字符")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("\\u 后面不足 4 位")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }

                            else -> fail("不认识的转义 '\\$e'")
                        }
                    }

                    else -> sb.append(c)
                }
            }
            fail("字符串没有收尾")
        }
    }
}

/**
 * JSON-RPC over stdio 的一帧：`Content-Length: N\r\n\r\n{...}`。
 *
 * LSP 规定头是 ASCII、正文是 UTF-8；长度按**字节**算，不是字符数。
 */
object JsonRpc {

    /** 读一帧；流结束（EOF）返回 null。头可以有多行，只认 Content-Length。 */
    fun readMessage(input: java.io.InputStream): String? {
        var length = -1
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                length = value.toIntOrNull() ?: return null
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

    /** 读一行头（到 \n 为止，去掉尾部的 \r）。EOF 且一行都没读到时返回 null。 */
    private fun readHeaderLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** 写一帧并立刻 flush（编辑器那头是阻塞读的，不 flush 就永远等不到）。 */
    fun writeMessage(output: java.io.OutputStream, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}
