package org.example.complie.diag

enum class Severity(val json: String) {
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
}

/**
 * 结构化诊断——给编辑器用的（源码片段式的文本报错不便机读）。
 *
 * [RsfError] 是「遇错即停」的异常，所以一次检查最多产出一条 error；
 * warning 会全部收集。
 */
data class Diagnostic(
    val severity: Severity,
    val phase: String,
    val message: String,
    val file: String,
    val line: Int,
    val col: Int,
)
