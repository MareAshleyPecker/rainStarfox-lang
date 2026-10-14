package org.rainstarfox.idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/**
 * 解码字符集的单元测试。
 *
 * 这条逻辑曾经写错过一次：拿 `Charset.defaultCharset()` 去解编译器的中文报错，
 * 而 IDEA 进程里这个值是 UTF-8，子进程写的却是本机编码（Windows 上是 GBK），
 * 结果编辑器里**所有中文报错整段变乱码**。所以这里把它钉住。
 */
class RsfCharsetTest {

    private val gbk = Charset.forName("GBK")
    private val utf8 = Charsets.UTF_8

    @Test
    fun `优先用 native_encoding`() {
        assertEquals(gbk, humanCharsetOf(nativeEncoding = "GBK", jnuEncoding = "UTF-8", fallback = utf8))
    }

    @Test
    fun `native_encoding 缺失时退回 sun_jnu_encoding`() {
        assertEquals(gbk, humanCharsetOf(nativeEncoding = null, jnuEncoding = "GBK", fallback = utf8))
        assertEquals(gbk, humanCharsetOf(nativeEncoding = "  ", jnuEncoding = "GBK", fallback = utf8))
    }

    @Test
    fun `两个都拿不到时退回给定的兜底值`() {
        assertEquals(utf8, humanCharsetOf(nativeEncoding = null, jnuEncoding = null, fallback = utf8))
    }

    @Test
    fun `名字非法时不抛异常 退回兜底值`() {
        assertEquals(utf8, humanCharsetOf(nativeEncoding = "NoSuchCharset-42", jnuEncoding = null, fallback = utf8))
    }

    @Test
    fun `关键性质 只要系统报了本机编码就不能拿 UTF8 去解`() {
        // 用一个真实的中文报错片段验证：按 GBK 解出来是中文，按 UTF-8 解出来是乱码
        val message = "顶层只能写函数声明，实际读到 '<'"
        val bytes = message.toByteArray(gbk)

        assertEquals(message, String(bytes, humanCharsetOf("GBK", null, utf8)))
        assertEquals(false, String(bytes, humanCharsetOf("GBK", null, utf8)).contains('\uFFFD'))
    }
}
