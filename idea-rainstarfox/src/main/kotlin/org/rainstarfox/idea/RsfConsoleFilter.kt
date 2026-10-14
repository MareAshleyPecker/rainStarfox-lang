package org.rainstarfox.idea

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * 把控制台里的报错行变成可点击的链接。
 *
 * 编译器的错误格式是 `main.rsf:16:5: error[checker]: ...`——只有文件名没有全路径，
 * 所以链接要靠「哪个文件跑出来的」这个上下文补全（[directory] 就是那个文件的目录）。
 */
class RsfConsoleFilter(private val project: Project, private val directory: String) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = PATTERN.find(line) ?: return null
        val name = match.groupValues[1]
        val lineNumber = match.groupValues[2].toIntOrNull() ?: return null
        val column = match.groupValues[3].toIntOrNull() ?: return null

        val file = File(directory, name)
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null

        val lineStart = entireLength - line.length
        val start = lineStart + match.range.first
        val end = lineStart + match.range.last + 1
        return Filter.Result(start, end, RsfFileHyperlink(virtualFile, lineNumber - 1, (column - 1).coerceAtLeast(0)))
    }

    /** 点了链接就打开那个文件并跳到出错的行列。HyperlinkInfo 只有这一个方法。 */
    private class RsfFileHyperlink(
        private val file: VirtualFile,
        private val line: Int,
        private val column: Int,
    ) : HyperlinkInfo {
        override fun navigate(project: Project) {
            OpenFileDescriptor(project, file, line, column).navigate(true)
        }
    }

    companion object {
        private val PATTERN = Regex("([\\w.\\-]+\\.rsf):(\\d+):(\\d+):")
    }
}

/**
 * 报错行样本（改这个正则前先拿它验一遍）：
 *
 * ```
 * A_placeholder.rsf:1:1: error[parser]: 顶层只能写函数声明，格式是 '[public|private] fun ...
 *   1 | <public> fun main() -> void { rtn }
 *     | ^
 * ```
 *
 * 编译器的错误格式是 `<文件名>:<行>:<列>:`，只给文件名不给全路径，所以链接要靠
 * 「哪个文件跑出来的」这个上下文补全（[RsfConsoleFilter] 的 directory 就是那个文件的目录）。
 */
