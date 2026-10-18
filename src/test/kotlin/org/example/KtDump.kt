package org.example

import org.example.complie.Rsf
import org.example.complie.diag.SourceFile
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Kotlin 前端的 AST dump 打印器（判据台用，替代事故中丢失的旧 `KtDump.class`）。
 *
 * 用法：`java -cp build/classes/kotlin/test;<jar> org.example.KtDump <文件.rsf>`
 * 输出与自举 `ParserCli`（`-Drsf.ast.file=<文件>`）逐字节可比。
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("用法: KtDump <文件.rsf>")
        kotlin.system.exitProcess(2)
    }
    val path = Paths.get(args[0])
    val text = Files.readString(path)
    val program = Rsf.parse(SourceFile.of(path.toString(), text))
    print(AstDump.dump(program))
}
