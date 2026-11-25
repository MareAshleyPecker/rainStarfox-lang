package org.example.complie.diag

import org.example.complie.directives.Directives
import org.example.complie.directives.ParsedDirectives
import java.io.File

/** `.rsfs` 是脚本：允许没有 main，也允许把语句直接写在顶层。 */
private fun looksLikeScript(path: String): Boolean = path.endsWith(".rsfs", ignoreCase = true)

/**
 * 一份被编译的 .rsf 源码。保留原文是为了在报错时能打印出出错那一行和插入符。
 *
 * [script] 为真时按**脚本**解析（`.rsfs`）：顶层可以直接写语句，编译器把它们裹进
 * 合成的 `main`（见 `Parser.parseProgram`）。默认按扩展名判断，`rsf lsp --script`
 * 之类的场景可以在 [of] 里显式指定。
 */
class SourceFile(val path: String, val text: String, val script: Boolean = looksLikeScript(path)) {

    val lines: List<String> = text.split("\n")

    /**
     * 这份文件用 `#NoWarning!!(类型)` 压掉了哪些警告（见 [WarningDirectives]）。
     *
     * 在构造时解析并校验：类型名写错要**当场**报错，不能等到出警告时才静默失效。
     * 只影响本文件的警告（诊断里带文件名，按文件匹配）。
     */
    val suppressedWarnings: Set<String> = WarningDirectives.kinds(this)

    /**
     * 文件级指令（`#dep` / `#build`）的解析结果，构造时就校验过。
     *
     * 装载层从这里取依赖清单与构建设置；`codeText` 是挖掉这些块之后交给词法器的正文。
     */
    val directives: ParsedDirectives = Directives.parse(this)

    /** 交给词法器的正文：`#dep` / `#build` / `#NoWarning!!` 都已挖成空格（行列号不变）。 */
    val codeText: String = directives.stripped

    /** 取第 [line] 行（1 基）的原文，越界返回 null。 */
    fun lineAt(line: Int): String? =
        if (line in 1..lines.size) lines[line - 1].trimEnd('\r') else null

    /** 只取文件名部分，用于报错与生成类名。 */
    val name: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    /** 不含扩展名的文件名。 */
    val baseName: String
        get() = name.substringBeforeLast('.')

    companion object {
        /** [script] 为 null 时按扩展名判断（`.rsfs` 就是脚本）。 */
        fun of(file: File, script: Boolean? = null): SourceFile =
            SourceFile(file.path, file.readText(), script ?: looksLikeScript(file.path))

        /** [script] 为 null 时按扩展名判断（`.rsfs` 就是脚本）。 */
        fun of(path: String, text: String, script: Boolean? = null): SourceFile =
            SourceFile(path, text, script ?: looksLikeScript(path))
    }
}
