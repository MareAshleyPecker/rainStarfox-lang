package org.rainstarfox.idea

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import javax.swing.JComponent

/**
 * 跑 `rsf run` / `rsf build`，输出丢进 Run 工具窗口的控制台。
 *
 * 用的是 IDEA 自带控制台，所以报错行能点、能滚、能搜——没理由自己糊一个文本域。
 */
object RsfRunner {

    const val MODE_RUN = "run"
    const val MODE_BUILD = "build"

    fun execute(project: Project, file: VirtualFile, mode: String) {
        val command = RsfLauncher.detect(project)
        if (command == null) {
            notify(
                project,
                "没找到 rainStarfox 编译器",
                "插件会在项目里找 build/native/rainStarfox/rainStarfox.exe 或 build/libs/*.jar，" +
                    "都没找到就会来问你——请到 Setting → Tools → rainStarfox 里填一下启动器路径。",
                NotificationType.WARNING,
            )
            return
        }

        // 先把没保存的改动落盘：运行的是磁盘上的文件
        FileDocumentManager.getInstance().saveAllDocuments()

        val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        builder.addFilter(RsfConsoleFilter(project, file.parent?.path ?: project.basePath ?: "."))
        val console = builder.console

        val commandLine = command.commandLine(mode, file.path)
        // 控制台按本机编码解子进程的输出，不能跟着 IDEA 自己的 UTF-8 走（见 RsfCli.humanCharset）
        commandLine.withCharset(RsfCli.humanCharset)
        project.basePath?.let { commandLine.withWorkDirectory(it) }

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            notify(project, "没法启动编译器", "${command.executable}：${e.message}", NotificationType.ERROR)
            return
        }

        console.attachToProcess(handler)
        showConsole(project, console, console.component, "rainStarfox · ${file.name} · $mode")
        handler.startNotify()
    }

    private fun showConsole(project: Project, console: ConsoleView, component: JComponent, title: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Run") ?: return
        val content = ContentFactory.getInstance().createContent(component, title, true)
        content.setDisposer(console)
        ApplicationManager.getApplication().invokeLater {
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.activate(null)
        }
    }

    fun notify(project: Project, title: String, message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("rainStarfox")
            .createNotification(title, message, type)
            .notify(project)
    }
}
