package org.rainstarfox.idea

import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.charset.Charset

/**
 * 插件的编译器客户端（**只管跑程序**）。
 *
 * 诊断和补全已经改走常驻的 LSP 服务器（见 [RsfLspClient]）——不再有 `check --json`
 * 那套一次性命令，插件里也就不需要解析编译器的 JSON 了。
 * 剩下这里只有「跑一次 `rsf run` / `rsf build` 并把输出交给控制台」，那本来就是个
 * 一次性进程，没必要进服务器。
 */
object RsfCli {

    /** 人看的输出用本机编码，解错了就是乱码。见 [humanCharsetOf]。 */
    val humanCharset: Charset get() = HUMAN_CHARSET

    private val HUMAN_CHARSET: Charset = humanCharsetOf(
        nativeEncoding = System.getProperty("native.encoding"),
        jnuEncoding = System.getProperty("sun.jnu.encoding"),
        fallback = Charset.defaultCharset(),
    )

    private const val TIMEOUT_MS = 20_000

    /** 跑 `rsf run` / `rsf build`：拿完整输出文本，交给控制台显示。 */
    fun run(command: RsfCommand, workingDir: File?, args: List<String>): Pair<Int, String> {
        val commandLine = command.commandLine(*args.toTypedArray())
        workingDir?.let { commandLine.withWorkDirectory(it) }
        val handler = CapturingProcessHandler(commandLine.createProcess(), HUMAN_CHARSET, commandLine.commandLineString)
        val result = handler.runProcess(TIMEOUT_MS * 6)
        val text = result.stdout + (if (result.stderr.isNotEmpty()) "\n" + result.stderr else "")
        return result.exitCode to text
    }
}

/**
 * 挑「怎么解编译器的中文输出」用的字符集。
 *
 * 编译器往**管道**里写人看的输出时用的是本机编码（JDK 的 `stdout.encoding` 在没接控制台时
 * 取 `native.encoding`，Windows 上就是 GBK）。这里**不能**用 `Charset.defaultCharset()` 顶替：
 * IDEA 自己是拿 `-Dfile.encoding=UTF-8` 起的，这个值在 IDE 进程里是 UTF-8，跟子进程不是一回事
 * ——曾经就是这么写的，结果编辑器里所有中文报错整段变乱码。
 *
 * LSP 那条路不走这里：协议帧固定 UTF-8（由服务器强制）。
 */
internal fun humanCharsetOf(nativeEncoding: String?, jnuEncoding: String?, fallback: Charset): Charset {
    for (name in listOf(nativeEncoding, jnuEncoding)) {
        if (name.isNullOrBlank()) continue
        val charset = runCatching { Charset.forName(name) }.getOrNull()
        if (charset != null) return charset
    }
    return fallback
}
