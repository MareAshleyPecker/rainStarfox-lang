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
    /**
     * 警告的**类型短名**（见 [WarningKind]），`#NoWarning!!(类型)` 按它匹配。
     *
     * error 用空串——抑制指令只压警告，压不掉错误（错误是「代码不成立」，
     * 不是「可以接受的啰嗦」）。
     */
    val code: String = "",
)
