package org.rainstarfox.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtil
import java.io.File

/**
 * 把 rsf 的编译输出目录（默认 `<项目>/out`）挂到模块的类路径上。
 *
 * 为什么需要：`rsf build` 产出的 `.class` 不在任何 Gradle/Maven 模型里，IDEA 的索引也就
 * 不知道它们存在——于是 Java 侧写 `rsflib.greet("x")` 会整片飘红（javac 其实编得过，
 * 只是 IDE 看不见）。挂一个名为 `rsf-classes` 的库，Java 侧就能解析到这些类，
 * 反向互操作（Java → rsf）才真的「在 IDE 里也能用」。
 *
 * 只加一个类根，不碰源码根；库名固定，重复调用不会加第二份。
 */
object RsfOutputLibrary {

    const val LIBRARY_NAME = "rsf-classes"

    /** 输出目录（相对项目根）；`rsf build` 的默认值就是 `out`。 */
    private const val OUTPUT_DIR = "out"

    fun ensure(project: Project) {
        if (project.isDisposed || project.isDefault) return
        val base = project.basePath ?: return
        val out = File(base, OUTPUT_DIR)
        val modules = ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) return
        // 已经挂过就别再挂：多模块工程里每个模块各挂一份会让 Project Structure 里出现一堆重名库
        if (modules.any { module ->
                ModuleRootManager.getInstance(module).orderEntries.any { it.presentableName == LIBRARY_NAME }
            }
        ) {
            return
        }
        val url = VfsUtil.pathToUrl(out.path)

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                WriteAction.run<RuntimeException> {
                    for (module in modules) {
                        ModuleRootModificationUtil.addModuleLibrary(
                            module,
                            LIBRARY_NAME,
                            listOf(url),
                            emptyList(),
                            emptyList(),
                            DependencyScope.COMPILE,
                        )
                    }
                }
            } catch (e: Exception) {
                // 项目模型正在被别的插件改：这次算了，下次启动/编译再试
                RsfRunner.notify(project, "没能把 rsf 输出挂进类路径", e.message ?: "未知原因")
            }
        }
    }
}

/**
 * 项目打开后挂一次；`RsfBuildAction` 编译完再挂一次（第一次编译前 `out/` 可能还不存在）。
 */
class RsfOutputActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!looksLikeRsfProject(project)) return
        RsfOutputLibrary.ensure(project)
    }

    /** 只有项目里真有 `.rsf` 才动项目模型——别去改一个跟 rsf 无关的工程。 */
    private fun looksLikeRsfProject(project: Project): Boolean {
        val base = project.basePath ?: return false
        val root = File(base)
        if (root.listFiles()?.any { it.isFile && it.extension == "rsf" } == true) return true
        val rsfSrc = File(root, "rsfSrc")
        return rsfSrc.walkTopDown().maxDepth(2).any { it.isFile && it.extension == "rsf" }
    }
}
