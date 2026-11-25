package org.example.build

import org.example.complie.ast.BuildTarget
import org.example.complie.native.CBackend
import org.example.complie.Rsf
import org.example.complie.diag.RsfError
import org.example.meta.RsfMeta
import java.io.File

/**
 * 构建脚本（`build.rsfs`）能用的宿主能力。
 *
 * `rsf build` 在项目目录里跑起来时，会把一个实现塞进解释器；脚本里写
 * `compileAll()` / `compile("src/x.rsf")`，实际编译的是**这份实现**在做。
 *
 * 为什么不做成语言特性：编译动作要读磁盘、要跑两个后端、要把产物写出去——这些都是
 * 宿主的事。脚本只描述「编哪些、编到哪」，剩下交给这里。
 */
interface BuildHost {
    /** 产物目录（`compile` 会写到这里）。 */
    fun outDir(): String

    /** 编译一个源文件，返回落盘的产物路径。 */
    fun compile(path: String): String

    /** 编译一个目录下的全部 `.rsf` / `.rsfs`，返回编译了几个文件。 */
    fun compileAll(dir: String): Int
}

/**
 * 项目目录里的构建宿主。
 *
 * @param root   项目根目录；脚本里写相对路径时按它解析（测试里指向临时目录）。
 * @param srcDir 默认 `src`；脚本里调 `compileAll()` 不传目录时用它的名字。
 * @param outDir 产物目录，相对 [root] 解析；`rsf build` 默认给 `build/out`。
 * @param cMode  `--c` 时编成 C（`<名字>.c` + `rsf_runtime.h`），否则编成 `.class`。
 * @param meta   是否把源码快照打进产物（`--meta` 才开）。
 */
class ProjectBuildHost(
    private val root: File = File("."),
    private val srcDir: File = File("src"),
    outDir: File = File("build/out"),
    private val cMode: Boolean = false,
    private val meta: Boolean = false,
) : BuildHost {

    private val outDir: File = if (outDir.isAbsolute) outDir else File(root, outDir.path)

    override fun outDir(): String = outDir.path

    override fun compile(path: String): String {
        val file = if (File(path).isAbsolute) File(path) else File(root, path)
        if (!file.isFile) {
            throw RsfError("build", "compile：找不到源文件 '${file.path}'（构建脚本里写相对路径时，基准是项目根目录）")
        }
        val loaded = Rsf.load(file, target = if (cMode) BuildTarget.NATIVE else BuildTarget.JVM)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        val name = Rsf.deriveClassName(loaded.root)
        outDir.mkdirs()
        if (!cMode) {
            val result = Rsf.build(model, loaded.root, name, outDir)
            for (warning in result.warnings) System.err.println("[rsf] 警告: $warning")
            return result.classFile.path
        }
        val metaBlob = if (meta) {
            RsfMeta.encode(
                RsfMeta.snapshotsOf(loaded),
                RsfMeta.symbolsOf(loaded),
                RsfMeta.describe("native", name),
            )
        } else {
            null
        }
        val backend = CBackend(model, withEntry = true, emitImpl = true)
        backend.ensureRuntimeLib(outDir)
        val text = backend.emit()
        val out = File(outDir, "$name.c")
        out.writeText(if (metaBlob != null) text + RsfMeta.cDeclaration(metaBlob) else text, Charsets.UTF_8)
        return out.path
    }

    override fun compileAll(dir: String): Int {
        val name = dir.ifEmpty { srcDir.path }
        val dirFile = if (File(name).isAbsolute) File(name) else File(root, name)
        if (!dirFile.isDirectory) {
            throw RsfError("build", "compileAll：'${dirFile.path}' 不是目录（构建脚本里写相对路径时，基准是项目根目录）")
        }
        val sources = dirFile.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .filter { it.extension == "rsf" || it.extension == "rsfs" }
            .sortedBy { it.path }
            .toList()
        for (source in sources) {
            val produced = compile(source.path)
            println("[rsf] 构建 ${source.path} -> $produced")
        }
        return sources.size
    }
}
