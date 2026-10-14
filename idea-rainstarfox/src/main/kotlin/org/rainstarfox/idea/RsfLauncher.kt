package org.rainstarfox.idea

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 一条能跑起来 rainStarfox 编译器的命令行。
 *
 * [executable] + [prefix] 合起来是「怎么调编译器」，后面再拼真正的参数：
 * 原生启动器就是 `<exe>`，jar 就是 `<java> -jar <xxx.jar>`。
 */
data class RsfCommand(
    val executable: String,
    val prefix: List<String>,
    /** 给人看的说明，设置页里显示「当前用的是哪个」。 */
    val description: String,
) {
    fun commandLine(vararg args: String): GeneralCommandLine {
        val cmd = GeneralCommandLine(executable)
        cmd.addParameters(prefix)
        cmd.addParameters(*args)
        return cmd
    }
}

/**
 * 找编译器。
 *
 * 顺序：用户手填的 → 项目里自带的（`gradlew exe` 产出的原生启动器、`gradlew jar`
 * 产出的 jar） → PATH。找不到不算错，只是插件里跟编译器有关的功能集体沉默，并在
 * 用到的时候弹一次通知告诉用户去哪配。
 */
object RsfLauncher {

    private val NATIVE_CANDIDATES = listOf(
        "build/native/rainStarfox/rainStarfox.exe",
        "rainStarfox.exe",
        "rsf.exe",
    )

    fun detect(project: Project?): RsfCommand? {
        val settings = RsfSettings.getInstance().state

        val explicit = settings.launcher.trim()
        if (explicit.isNotEmpty()) {
            val f = File(explicit)
            if (f.isFile) {
                return if (f.name.endsWith(".jar", ignoreCase = true)) {
                    jarCommand(settings.javaCommand, f)
                } else {
                    RsfCommand(f.absolutePath, emptyList(), "设置里指定的启动器")
                }
            }
        }

        val base = project?.basePath?.let { File(it) }
        if (base != null) {
            for (candidate in NATIVE_CANDIDATES) {
                val f = File(base, candidate)
                if (f.isFile) {
                    return RsfCommand(f.absolutePath, emptyList(), "项目里的原生启动器（${candidate}）")
                }
            }
            val jar = newestJar(File(base, "build/libs"))
            if (jar != null) {
                return jarCommand(settings.javaCommand, jar).copy(description = "项目里的 ${jar.name}")
            }
        }

        for (name in listOf("rainStarfox.exe", "rsf.exe", "rainStarfox", "rsf")) {
            val f = findOnPath(name) ?: continue
            return RsfCommand(f.absolutePath, emptyList(), "PATH 里的 $name")
        }

        return null
    }

    private fun newestJar(dir: File): File? = dir.listFiles { f ->
        f.isFile && f.name.endsWith(".jar", ignoreCase = true) &&
            !f.name.contains("sources") && !f.name.contains("javadoc")
    }?.maxByOrNull { it.name }

    private fun jarCommand(configuredJava: String, jar: File): RsfCommand {
        val java = configuredJava.trim().ifEmpty {
            val home = System.getenv("JAVA_HOME")
            if (home != null && File(home, "bin/java.exe").isFile) File(home, "bin/java.exe").absolutePath else "java"
        }
        return RsfCommand(java, listOf("-jar", jar.absolutePath), "java -jar ${jar.name}")
    }

    private fun findOnPath(name: String): File? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val f = File(dir, name)
            if (f.isFile) return f
        }
        return null
    }

    /** 给设置页显示的一句话。 */
    fun describe(project: Project?): String {
        val cmd = detect(project) ?: return "没找到编译器——请在下面手填路径，或用 gradlew exe 生成原生启动器"
        return "${cmd.description}：${cmd.executable} ${cmd.prefix.joinToString(" ")}".trim()
    }
}
