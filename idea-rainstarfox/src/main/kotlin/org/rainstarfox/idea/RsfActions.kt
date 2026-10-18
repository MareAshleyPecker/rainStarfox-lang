package org.rainstarfox.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile

/** 取当前选中的 .rsf 文件；编辑器里、项目树里、右键菜单里都能取到。 */
private fun rsfFileOf(event: AnActionEvent): VirtualFile? {
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    return if (!file.isDirectory && file.extension.equals("rsf", ignoreCase = true)) file else null
}

/** 解释执行当前文件（`rsf run`），输出进 Run 控制台。 */
class RsfRunAction : DumbAwareAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = rsfFileOf(event) ?: return
        RsfRunner.execute(project, file, RsfRunner.MODE_RUN)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = rsfFileOf(event) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** 编译成 .class（`rsf build`，产物默认落在项目的 out/ 下）。 */
class RsfBuildAction : DumbAwareAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = rsfFileOf(event) ?: return
        RsfRunner.execute(project, file, RsfRunner.MODE_BUILD)
        // 编译产物落在 <项目>/out：先把它挂进类路径，Java 侧下一次解析就能看到这些类
        RsfOutputLibrary.ensure(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = rsfFileOf(event) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
