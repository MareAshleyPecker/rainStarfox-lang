package org.example.build

import org.example.complie.diag.SourceFile
import org.example.complie.directives.DepEntry
import org.example.complie.directives.DepKind
import org.example.complie.interop.ClassPath
import java.io.File
import java.net.URLClassLoader

/**
 * 把项目声明过的 jar 装进 [ClassPath]——**编辑器那一侧也用它**（阶段 84）。
 *
 * 构建命令有宿主（[ProjectBuildHost]）可以问「这个项目声明了哪些 jar」，
 * 但语言服务器和 `rsf complete` 手里只有**一个文件路径**：用户在编辑器里打开
 * `src/app.rsf`，那不是 `build.rsfs`，也没有宿主。所以这里从文件往上找项目根
 * （有 `build.rsfs` 的那一层），读它的 `#dep { java(...) }`。
 *
 * 三条边界，都是刻意的：
 *
 * - **只读 `.class` 字节，不初始化类**：`ClassPath.find` 走 `getResourceAsStream`，
 *   不会触发静态初始化。编辑器进程里加载并初始化陌生代码是要出事的，
 *   而「看得到签名」只需要字节。
 * - **一次只装一个项目的 jar**：[ClassPath] 的 loader 是进程级的，切项目时会换掉。
 *   指纹（根目录 + jar 路径/大小/时间）没变就不重装，避免每次按键都清缓存。
 * - **指令写错不能把编辑器搞崩**：这个文件是用户的，随时可能正在编辑中。
 *   解析失败就当「没有 jar」，真正的报错留给诊断那条路。
 */
object ProjectJars {

    /** 上次装的是哪份组合（根目录 + jar 的路径/大小/修改时间）。 */
    private var fingerprint: String? = null

    /** 上次装上的 jar（`import` 补全要按它们列类名）。 */
    private var installed: List<File> = emptyList()

    /**
     * 按 [file] 找到项目根，把根 `build.rsfs` 里声明的 jar 装上。
     *
     * 返回实际装上的 jar；没有项目、没有声明、指令在读的时候写坏了都是空列表。
     */
    fun installFor(file: File): List<File> {
        val root = projectRoot(file) ?: run {
            clear()
            return emptyList()
        }
        val script = File(root, "build.rsfs")
        val jars = try {
            declaredJars(script, root)
        } catch (e: Exception) {
            // 编辑器场景：文件可能正被编辑到一半，指令块不完整是常态。
            // 静默当作「暂时没有 jar」，诊断会另外报。
            emptyList<File>()
        }
        val key = root.path + "|" + jars.joinToString(",") {
            "${it.path}:${it.length()}:${it.lastModified()}"
        }
        if (key == fingerprint) return jars
        fingerprint = key
        installed = jars
        ClassPath.installLoader(
            if (jars.isEmpty()) {
                null
            } else {
                URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
            },
        )
        return jars
    }

    /** 不再属于任何项目时把 loader 摘掉（否则上一个项目的 jar 会一直可见）。 */
    fun clear() {
        if (fingerprint == null) return
        fingerprint = null
        installed = emptyList()
        ClassPath.installLoader(null)
    }

    /**
     * 依赖 jar 里的类名（点分），按名字排序。
     *
     * 给 `import` 补全用：jar 里一个 entry（`demo/Util.class`）就是一个类。
     * 过滤掉三样：`$` 开头的内部类（`Outer$Inner` 补出来会误导）、`module-info`、
     * 以及 `META-INF` 这类目录项。上限 [limit] 条——大 jar 动辄几千个类，
     * 全塞进补全列表既卡又吵。
     */
    fun installedClassNames(limit: Int = 300): List<String> {
        val names = sortedSetOf<String>()
        for (jar in installed) {
            try {
                java.util.jar.JarFile(jar).use { file ->
                    for (entry in file.entries()) {
                        val path = entry.name
                        if (!path.endsWith(".class")) continue
                        if (path.startsWith("META-INF/")) continue
                        val dotted = path.removeSuffix(".class").replace('/', '.')
                        if (dotted.contains('$')) continue
                        if (dotted.endsWith("module-info") || dotted.endsWith("package-info")) continue
                        names.add(dotted)
                        if (names.size >= limit) return names.toList()
                    }
                }
            } catch (e: Exception) {
                // 读不了的 jar（正在被写、损坏）就当它没有类：编辑器不能因此崩
            }
        }
        return names.toList()
    }

    /** 从文件所在目录往上找第一个带 `build.rsfs` 的目录。 */
    fun projectRoot(file: File): File? {
        var dir = file.absoluteFile.parentFile ?: return null
        while (true) {
            if (File(dir, "build.rsfs").isFile) return dir
            dir = dir.parentFile ?: return null
        }
    }

    private fun declaredJars(script: File, root: File): List<File> =
        SourceFile.of(script).directives.deps
            .filter { it.kind == DepKind.JAVA }
            // 和构建命令走同一套展开：javaInclude("libs") 就是 libs 里所有 jar
            .flatMap { entry: DepEntry -> expandDep(entry, root) }
            .map { entry: DepEntry ->
                if (File(entry.target).isAbsolute) File(entry.target) else File(root, entry.target)
            }
            .filter { it.isFile }
}
