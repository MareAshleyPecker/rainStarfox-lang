package org.rainstarfox.idea

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.io.File
import java.nio.charset.Charset

/**
 * 插件的编译器客户端。
 *
 * 所有编译器的活都走这里——插件不重复实现词法/语义分析，只负责「把当前缓冲区的
 * 文本交给编译器，再把它的 JSON 结果翻译成 IDEA 能画的东西」。好处是插件和命令行
 * 永远说一套话；代价是每次请求要起一次 JVM（约 200~300ms），所以诊断是编辑器停止
 * 输入之后才跑的。
 *
 * 缓冲区还没保存时，先把文本写进临时文件，用 `--base` 告诉编译器真实的相对目录，
 * import 才找得到旁边的文件。
 */
object RsfCli {

    data class Diagnostic(
        val line: Int,
        val col: Int,
        val endCol: Int,
        val severity: String,
        val phase: String,
        val message: String,
    )

    data class Item(val label: String, val kind: String, val detail: String)

    /** 编译器的 `--json` 输出固定是 UTF-8（见 Main.kt），所以这里写死 UTF-8。 */
    private val JSON_CHARSET = Charsets.UTF_8

    /** 人看的输出用本机编码，解错了就是乱码。见 [humanCharset]。 */
    val humanCharset: Charset get() = HUMAN_CHARSET

    private val HUMAN_CHARSET: Charset = humanCharsetOf(
        nativeEncoding = System.getProperty("native.encoding"),
        jnuEncoding = System.getProperty("sun.jnu.encoding"),
        fallback = Charset.defaultCharset(),
    )

    private const val TIMEOUT_MS = 20_000

    // ------------------------------------------------------------------ 诊断

    fun check(command: RsfCommand, path: String, text: String?, baseDir: File?): List<Diagnostic> {
        val (file, temp) = materialize(path, text)
        try {
            val args = ArrayList<String>()
            args.add("check")
            args.add(file.absolutePath)
            args.add("--json")
            baseDir?.let {
                args.add("--base")
                args.add(it.absolutePath)
            }
            val output = capture(command, JSON_CHARSET, args) ?: return emptyList()
            val root = RsfJson.parse(output) ?: return emptyList()
            return RsfJson.objectsAt(root, "diagnostics").map { d ->
                Diagnostic(
                    line = RsfJson.int(d, "line", 1),
                    col = RsfJson.int(d, "col", 1),
                    endCol = RsfJson.int(d, "endCol", -1),
                    severity = RsfJson.string(d, "severity", "error"),
                    phase = RsfJson.string(d, "phase", "checker"),
                    message = RsfJson.string(d, "message"),
                )
            }
        } finally {
            temp?.delete()
        }
    }

    // ------------------------------------------------------------------ 补全

    fun complete(command: RsfCommand, path: String, text: String?, line: Int, col: Int, baseDir: File?): List<Item> {
        val (file, temp) = materialize(path, text)
        try {
            val args = arrayListOf("complete", file.absolutePath, "--line", line.toString(), "--col", col.toString())
            baseDir?.let {
                args.add("--base")
                args.add(it.absolutePath)
            }
            val output = capture(command, JSON_CHARSET, args) ?: return emptyList()
            val root = RsfJson.parse(output) ?: return emptyList()
            return RsfJson.objectsAt(root, "items").map { d ->
                Item(
                    label = RsfJson.string(d, "label"),
                    kind = RsfJson.string(d, "kind", "value"),
                    detail = RsfJson.string(d, "detail"),
                )
            }.filter { it.label.isNotEmpty() }
        } finally {
            temp?.delete()
        }
    }

    // ------------------------------------------------------------------ 运行

    /** 跑 `rsf run` / `rsf build`：拿完整输出文本，交给控制台显示。 */
    fun run(command: RsfCommand, workingDir: File?, args: List<String>): Pair<Int, String> {
        val commandLine = command.commandLine(*args.toTypedArray())
        workingDir?.let { commandLine.withWorkDirectory(it) }
        val handler = CapturingProcessHandler(commandLine.createProcess(), HUMAN_CHARSET, commandLine.commandLineString)
        val result = handler.runProcess(TIMEOUT_MS * 6)
        val text = result.stdout + (if (result.stderr.isNotEmpty()) "\n" + result.stderr else "")
        return result.exitCode to text
    }

    // ------------------------------------------------------------------ 内部

    private fun capture(command: RsfCommand, charset: Charset, args: List<String>): String? {
        return try {
            val commandLine = command.commandLine(*args.toTypedArray())
            val handler = CapturingProcessHandler(commandLine.createProcess(), charset, commandLine.commandLineString)
            handler.runProcess(TIMEOUT_MS).stdout
        } catch (_: Exception) {
            // 启动器不在、路径带空格、编译器崩了……编辑器都不该跟着炸
            null
        }
    }

    /** 缓冲区没存盘时，落一个临时文件出来给编译器读。 */
    private fun materialize(path: String, text: String?): Pair<File, File?> {
        if (text == null) return File(path) to null
        val temp = File.createTempFile("rsf-idea-", ".rsf")
        temp.writeText(text, Charsets.UTF_8)
        // Windows 上文件被占用时删不掉，留个兜底让 JVM 退出时清
        temp.deleteOnExit()
        return temp to temp
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
 * `--json` 输出不走这里：那条路编译器强制 UTF-8。
 */
internal fun humanCharsetOf(nativeEncoding: String?, jnuEncoding: String?, fallback: Charset): Charset {
    for (name in listOf(nativeEncoding, jnuEncoding)) {
        if (name.isNullOrBlank()) continue
        val charset = runCatching { Charset.forName(name) }.getOrNull()
        if (charset != null) return charset
    }
    return fallback
}
