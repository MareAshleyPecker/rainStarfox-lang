package org.rainstarfox.idea

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Setting → Tools → rainStarfox。
 *
 * 只有三件事可调：编译器在哪、要不要边打字边检查、要不要补全。第一项是重点——
 * 插件不内置编译器，找不到就得靠用户指路，这里还要把「现在探测到了什么」明明白白
 * 写在下面，省得用户猜。
 */
class RsfConfigurable : Configurable {

    private val launcherField = JBTextField()
    private val javaField = JBTextField()
    private val liveDiagnosticsBox = JCheckBox("边打字边检查（每次改动后调用编译器，约 0.2 秒）")
    private val completionBox = JCheckBox("启用代码补全")
    private val detectedLabel = JBLabel()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("启动器（rainStarfox.exe 或 .jar）：", launcherField)
        .addTooltip("留空则自动探测：项目里的 build/native/rainStarfox/rainStarfox.exe → build/libs/*.jar → PATH")
        .addLabeledComponent("java 命令（跑 .jar 时才用）：", javaField)
        .addTooltip("留空则用 JAVA_HOME 或 PATH 里的 java")
        .addSeparator()
        .addComponent(liveDiagnosticsBox)
        .addComponent(completionBox)
        .addSeparator()
        .addComponent(detectedLabel)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun getDisplayName(): String = "rainStarfox"

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        val state = RsfSettings.getInstance().state
        return launcherField.text.trim() != state.launcher ||
            javaField.text.trim() != state.javaCommand ||
            liveDiagnosticsBox.isSelected != state.liveDiagnostics ||
            completionBox.isSelected != state.completion
    }

    override fun apply() {
        val state = RsfSettings.getInstance().state
        state.launcher = launcherField.text.trim()
        state.javaCommand = javaField.text.trim()
        state.liveDiagnostics = liveDiagnosticsBox.isSelected
        state.completion = completionBox.isSelected
        refreshDetected()
    }

    override fun reset() {
        val state = RsfSettings.getInstance().state
        launcherField.text = state.launcher
        javaField.text = state.javaCommand
        liveDiagnosticsBox.isSelected = state.liveDiagnostics
        completionBox.isSelected = state.completion
        refreshDetected()
    }

    override fun getPreferredFocusedComponent(): JComponent = launcherField

    private fun refreshDetected() {
        val project = runCatching { ProjectManager.getInstance().openProjects.firstOrNull() }.getOrNull()
        detectedLabel.text = "当前：${RsfLauncher.describe(project)}"
    }
}
