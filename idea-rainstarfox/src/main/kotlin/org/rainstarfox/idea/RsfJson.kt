package org.rainstarfox.idea

/**
 * 一个刚好够用的 JSON 读取器。
 *
 * 为什么不引第三方库：插件里只有两处用到 JSON（诊断、补全），形状固定，为它拉一个
 * 依赖不划算；也不直接用平台内部带的 gson——那是实现细节，哪天平台换掉就炸了。
 * 这里只做「读」，不做「写」，遇到坏输入一律返回 null 而不是抛异常。
 */
internal object RsfJson {

    fun parse(text: String): Any? = runCatching { Reader(text).value() }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun objectAt(root: Any?, key: String): Map<String, Any?>? = (root as? Map<String, Any?>)?.get(key) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arrayAt(root: Any?, key: String): List<Any?> = (root as? Map<String, Any?>)?.get(key) as? List<Any?> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun objectsAt(root: Any?, key: String): List<Map<String, Any?>> =
        arrayAt(root, key).filterIsInstance<Map<String, Any?>>()

    fun string(map: Map<String, Any?>, key: String, fallback: String = ""): String =
        map[key]?.toString() ?: fallback

    fun int(map: Map<String, Any?>, key: String, fallback: Int = 0): Int =
        (map[key] as? Double)?.toInt() ?: fallback

    private class Reader(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skip()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> take("true", true)
                'f' -> take("false", false)
                'n' -> take("null", null)
                else -> num()
            }
        }

        private fun take(word: String, result: Any?): Any? {
            if (s.startsWith(word, i)) {
                i += word.length
                return result
            }
            i++
            return null
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skip()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (i < s.length) {
                skip()
                if (i >= s.length || s[i] != '"') break
                val key = str()
                skip()
                if (i < s.length && s[i] == ':') i++
                out[key] = value()
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') i++
                break
            }
            return out
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skip()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (i < s.length) {
                out.add(value())
                skip()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') i++
                break
            }
            return out
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // 开引号
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    break
                }
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i + 1, minOf(i + 5, s.length))
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i++
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        /** 读不出来就返回 null——调用方靠这个判断「这压根不是 JSON」。 */
        private fun num(): Double? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            if (i == start) {
                i++
                return null
            }
            return s.substring(start, i).toDoubleOrNull()
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
