package org.example.build

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.directives.BuildSettings
import org.example.complie.directives.DepEntry
import org.example.complie.directives.DepKind
import org.example.complie.lexing.Lexer
import org.example.complie.native.CBackend
import org.example.complie.interop.ClassPath
import org.example.complie.parsing.Parser
import org.example.meta.RsfMeta
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

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

    /**
     * 跑一条 shell 命令（`/bin/sh -c`，工作目录是项目根），返回退出码。
     *
     * 只有**起不来**（找不到 sh、没有权限）才算失败；退出码非零原样返回，
     * 让脚本自己 `if (exec("...") != 0)` 决定要不要停——构建脚本需要这个判断。
     */
    fun exec(cmd: String): Int

    /** 往目标表里加一行：`target("@/src/app.rsf", "exe")`。 */
    fun target(src: String, kind: String)

    /** 记一条先后约束：`a` 要先于 `b`（名字或路径都认）。 */
    fun need(from: String, to: String)

    /**
     * 往依赖清单里加一条（规则体里的 `java(...)` / `c(...)` / `h(...)` / `dir(...)`）。
     *
     * [kind] 是 `DepKind.keyword`；清单与 `#dep { … }` 块共用同一份，
     * 所以锁文件、存在性校验都只有一条路。
     */
    fun declareDep(kind: String, path: String)

    /** 目标表（跑完 main 之后由 `rsf build` 拿走排好的顺序）。 */
    fun plan(): BuildPlan
}

/**
 * 项目目录里的构建宿主。
 *
 * @param root   项目根目录；脚本里写相对路径时按它解析（测试里指向临时目录）。
 * @param srcDir 默认 `src`；脚本里调 `compileAll()` 不传目录时用它的名字。
 * @param outDir 产物目录，相对 [root] 解析；`rsf build` 默认给 `build/out`。
 * @param target 编哪一侧：`"jvm"` / `"c"` / `"auto"`（`auto` 逐文件看有没有 `native` 声明）。
 * @param meta   是否把源码快照打进产物（`--meta` 或 `#build { meta(true) }`）。
 * @param deps   `#dep` 声明的依赖，用于写 `.rsfdeps/lock.json`（见 [writeLock]）。
 */
/**
 * 一条依赖声明展开成若干条：**给目录就是里面同类型的文件**（阶段 84）。
 *
 * ```
 * javaInclude("libs")            // libs 下的所有 .jar（按名字排序，顺序确定）
 * javaInclude("a.jar", "b.jar")  // 各自一条
 * cInclude("native")             // native 下的所有 .c
 * hInclude("native")             // native 下的所有 .h
 * dirInclude("res")              // 不展开：它本来就是「整个目录」
 * ```
 *
 * 为什么在**声明时**展开而不是出产物时：锁文件要记具体文件（大小、哈希），
 * 声明时展开，清单与磁盘就一一对应；代价是「先声明、后由 exec 生成」的目录
 * 抓不到——那种情况把 `exec` 写在 include 之前。
 *
 * 路径不存在时**原样保留**这一条：报错的责任在 writeLock（一处报错，消息统一），
 * 而不是在这里静默丢掉一个用户写错的名字。
 */
internal fun expandDep(entry: DepEntry, root: File): List<DepEntry> {
    // rule 指向的是函数名；dirInclude 表示「整个目录」，都不是文件清单
    if (entry.kind == DepKind.RULE || entry.kind == DepKind.DIR) return listOf(entry)
    val path = if (entry.target.startsWith("@/")) entry.target.substring(2) else entry.target
    val file = if (File(path).isAbsolute) File(path) else File(root, path)
    if (!file.isDirectory) return listOf(entry)
    val ext = when (entry.kind) {
        DepKind.JAVA -> "jar"
        DepKind.C -> "c"
        DepKind.H -> "h"
        else -> return listOf(entry)
    }
    val files = file.listFiles()
        ?.filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) return listOf(entry)
    return files.map { DepEntry(entry.kind, relativeToRoot(it, root)) }
}

/** 项目内的文件记相对路径（锁文件才可移植）；不在项目里就记绝对路径。 */
internal fun relativeToRoot(file: File, root: File): String {
    val base = root.absoluteFile.normalize()
    val target = file.absoluteFile.normalize()
    return try {
        target.relativeTo(base).path
    } catch (e: IllegalArgumentException) {
        target.path
    }
}

class ProjectBuildHost(
    private val root: File = File("."),
    private val srcDir: File = File("src"),
    outDir: File = File("build/out"),
    private val target: String = "jvm",
    private val meta: Boolean = false,
    private val deps: List<DepEntry> = emptyList(),
) : BuildHost {

    private val outDir: File = (if (outDir.isAbsolute) outDir else File(root, outDir.path)).normalize()

    /** 依赖：脚本自己声明的 + 每个被编译文件里声明的（`#dep` 可以写在任意源文件里）。 */
    private val collectedDeps = LinkedHashSet(deps.flatMap { expandDep(it, root) })

    /** 有没有依赖需要写锁文件。 */
    fun hasDeps(): Boolean = collectedDeps.isNotEmpty()

    override fun outDir(): String = outDir.path

    /** 目标表：`#target` 块（阶段 0）与 `target()` 调用（阶段 2）都进这一张。 */
    private val buildPlan = BuildPlan()

    override fun plan(): BuildPlan = buildPlan

    /**
     * 跑一条命令：工作目录固定为项目根（脚本里写的相对路径全是按项目根算的），
     * 输出**直接继承**到当前终端——构建日志要实时看得见，也不要替用户吞掉 gcc 的报错。
     *
     * 只有「起不来」才抛；退出码非零原样返回给脚本判断。
     */
    override fun exec(cmd: String): Int {
        if (cmd.isBlank()) throw RsfError("build", "exec(\"\")：命令不能是空的")
        val shell = if (System.getProperty("os.name").lowercase().contains("win")) "cmd" else "/bin/sh"
        val argv = if (shell == "cmd") listOf(shell, "/c", cmd) else listOf(shell, "-c", cmd)
        println("[rsf] 执行: $cmd")
        val process = try {
            ProcessBuilder(argv).directory(root).inheritIO().start()
        } catch (e: Exception) {
            throw RsfError("build", "exec：起不了命令 '$cmd'（${e.message}）。构建脚本里写的路径按项目根目录解析")
        }
        return process.waitFor()
    }

    /**
     * `target(src, kind)`：往计划表里加一行。
     *
     * [src] 必须是**文件**（`@/` 前缀按项目根解析，和 import 一致）；此处只做形状校验，
     * 文件存不存在留到出产物阶段——构建脚本里先声明、后生成是正常写法（`exec` 造出文件再 copy）。
     */
    override fun target(src: String, kind: String) {
        addTarget(TargetSpec.of(src, kind, null, "构建脚本里 target(\"$src\", \"$kind\")"))
    }

    /** 直接加一行已经造好的目标（`#target` 块走这里，它自带行号与可选的产物路径）。 */
    fun addTarget(spec: TargetSpec) {
        // 源路径在这里只做「写没写」的形状校验；文件在不在留到出产物阶段——
        // 构建脚本里「先 exec 生成、后 copy」是正常写法，过早检查会误报。
        if (spec.src.isBlank()) {
            throw RsfError("build", "'${spec.origin}' 里的源路径是空的")
        }
        buildPlan.add(spec)
    }

    override fun declareDep(kind: String, path: String) {
        if (path.isBlank()) throw RsfError("build", "$kind(\"\")：路径不能是空的")
        val depKind = DepKind.entries.firstOrNull { it.keyword == kind }
            ?: throw RsfError("build", "不认识的依赖种类 '$kind'")
        // 和 #dep 块一样：声明只是声明，存在性留给 writeLock 统一校验。
        // 给目录的话在这里展开成里面的文件（多条路径来自 vararg，见解释器）。
        var added = false
        for (entry in expandDep(DepEntry(depKind, path), root)) {
            if (collectedDeps.add(entry)) added = true
        }
        // 阶段 84：规则里**当场**声明的 jar 要立刻生效——后面 `compile()` 分析源码时
        // Checker 就得能看见里面的类。已经装过的同一批路径不会重复装（见 installJars）。
        if (added && depKind == DepKind.JAVA) installJars()
    }

    /**
     * 把清单里所有 `java(...)` 的 jar 装进 [ClassPath]（阶段 84）。
     *
     * 为什么不用 `-cp`：构建脚本要调的 jar 是**项目的一部分**（`#dep` 里声明、
     * 跟着仓库走），不该要求用户先改启动命令。这里用 URLClassLoader，父加载器是
     * 系统那个，所以 JDK 类照旧。
     *
     * 已经在清单里出现过的同一批路径不重复装（装一次就会清一次 ClassPath 的缓存）。
     */
    fun installJars() {
        val jars = javaDeps().map { resolveInProject(it) }.filter { it.isFile }
        if (jars.isEmpty()) {
            if (installedOnce) ClassPath.installLoader(null)
            return
        }
        if (installedOnce && jars == installedJars) return
        val urls = jars.map { it.toURI().toURL() }.toTypedArray()
        ClassPath.installLoader(URLClassLoader(urls, ClassLoader.getSystemClassLoader()))
        installedJars = jars
        installedOnce = true
        println("[rsf] 依赖 jar: " + jars.joinToString("、") { it.name })
    }

    private var installedJars: List<File> = emptyList()
    private var installedOnce = false

    /** 清单里的 `java(...)` 路径（相对项目根）。 */
    fun javaDeps(): List<String> = collectedDeps.filter { it.kind == DepKind.JAVA }.map { it.target }

    override fun need(from: String, to: String) {
        if (from.isBlank() || to.isBlank()) throw RsfError("build", "need(\"$from\", \"$to\")：两个参数都要写")
        buildPlan.need(from, to)
    }

    /** 按项目根解析脚本里写的路径（`@/` 前缀与 import 同义）。 */
    fun resolveInProject(path: String): File {
        val p = if (path.startsWith("@/")) path.substring(2) else path
        val f = File(p)
        return (if (f.isAbsolute) f else File(root, p)).normalize()
    }

    /** 项目根（出产物阶段按它算相对路径）。 */
    fun root(): File = root

    /** 这份文件该编到哪一侧：显式设置优先，`auto` 时看它自己有没有 `native` 声明。 */
    private fun targetOf(file: File): String {
        if (target != "auto") return target
        // 直接读这一份文件的 AST：文件头的 `target native` 记在 Program 上，
        // 不在各个 FuncDecl 上，所以不能用 LoadedProgram 猜。
        val source = SourceFile.of(file.canonicalFile)
        val program = Parser(Lexer(source).tokenize(), source).parseProgram()
        val native = program.target == BuildTarget.NATIVE ||
            program.functions.any { it.target == BuildTarget.NATIVE } ||
            program.classes.any { it.target == BuildTarget.NATIVE }
        return if (native) "c" else "jvm"
    }

    override fun compile(path: String): String {
        val file = if (File(path).isAbsolute) File(path) else File(root, path)
        if (!file.isFile) {
            throw RsfError("build", "compile：找不到源文件 '${file.path}'（构建脚本里写相对路径时，基准是项目根目录）")
        }
        val side = targetOf(file)
        val nativeSide = side == "c"
        val loaded = Rsf.load(file, target = if (nativeSide) BuildTarget.NATIVE else BuildTarget.JVM)
        collectedDeps.addAll(loaded.deps)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        val name = Rsf.deriveClassName(loaded.root)
        outDir.mkdirs()
        if (!nativeSide) {
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

    override fun compileAll(dir: String): Int {        val name = dir.ifEmpty { srcDir.path }
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

    /**
     * 把 `#dep` 声明的依赖写成 `.rsfdeps/lock.json`。
     *
     * v1 只**记账 + 校验存在**，不下载：`java`/`c`/`h` 指向的文件必须已经在仓库里，
     * 少了就报错并列出缺哪些（构建不偷偷联网）。
     */
    fun writeLock(): File {
        if (collectedDeps.isEmpty()) throw RsfError("dep", "writeLock：没有依赖可写（只有写了 `#dep { … }` 才需要锁文件）")
        val deps = collectedDeps
        val missing = deps.filter { it.kind != DepKind.RULE }.mapNotNull { entry ->
            val file = if (File(entry.target).isAbsolute) File(entry.target) else File(root, entry.target)
            // dir 指的是目录里的全部文件，所以校验 isDirectory（别的种类都是文件）
            val present = if (entry.kind == DepKind.DIR) file.isDirectory else file.isFile
            if (present) null else entry.display()
        }
        if (missing.isNotEmpty()) {
            throw RsfError(
                "dep",
                "#dep 里声明的文件不存在：${missing.joinToString("、")}。" +
                    "依赖要跟源文件一起放在仓库里（路径以项目根目录为基准）",
            )
        }
        val dir = File(root, ".rsfdeps")
        dir.mkdirs()
        val lock = File(dir, "lock.json")
        val sb = StringBuilder()
        sb.append("{\n  \"version\": 1,\n  \"deps\": [\n")
        val entries = deps.sortedWith(compareBy({ it.kind.keyword }, { it.target }))
        entries.forEachIndexed { i, entry ->
            sb.append("    {")
            sb.append("\"kind\": \"").append(entry.kind.keyword).append("\", ")
            sb.append("\"path\": ").append(json(entry.target))
            // 目录不记 size/sha256：那要遍历整棵子树，v1 只记路径（存在性上面校验过了）
            if (entry.kind != DepKind.RULE && entry.kind != DepKind.DIR) {
                val file = if (File(entry.target).isAbsolute) File(entry.target) else File(root, entry.target)
                sb.append(", \"size\": ").append(file.length())
                sb.append(", \"sha256\": \"").append(sha256(file)).append("\"")
            }
            sb.append("}")
            if (i != entries.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}\n")
        lock.writeText(sb.toString(), Charsets.UTF_8)
        return lock
    }

    private fun json(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * 把 `#build` 设置与命令行开关合成一套实际参数：**命令行显式给了就以命令行为准**。
         */
        fun settings(
            build: BuildSettings?,
            cliOut: File?,
            cliC: Boolean,
            cliMeta: Boolean,
            defaultOut: File = File("build/out"),
        ): Triple<File, String, Boolean> {
            val out = cliOut ?: build?.outDir?.let { File(it) } ?: defaultOut
            val target = when {
                cliC -> "c"
                build?.target != null -> build.target
                else -> "jvm"
            }
            val meta = cliMeta || build?.meta == true
            return Triple(out, target, meta)
        }
    }
}
