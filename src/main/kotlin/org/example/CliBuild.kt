package org.example

// rsf build 的全部命令实现与产物生成辅助（从 Main.kt 搬出来，阶段 84 第一刀）。
//
// 这里全是 `Cli` 的**扩展函数**：`this` 与原来当成员函数时完全一样，
// 所以函数体一个字都没改，搬迁的风险只在包可见性上（同包＝不用 import）。

import org.example.build.ProjectBuildHost
import org.example.build.TargetKind
import org.example.build.TargetSpec
import org.example.complie.BuildResult
import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.codegen.disassemble
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.directives.DepEntry
import org.example.complie.directives.DepKind
import org.example.complie.interp.RsfFailure
import org.example.complie.loading.LoadedProgram
import org.example.complie.lsp.EditorSupport
import org.example.complie.native.CBackend
import org.example.complie.sema.SemanticModel
import org.example.complie.util.input
import org.example.meta.RsfMeta
import java.io.File
import kotlin.system.exitProcess

internal fun Cli.cmdBuild(rest: List<String>): Int {
        var outDir = File("out")
        /** 用户显式给了 `-o` 吗？项目模式（build.rsfs）没给时用 `build/out`。 */
        var outGiven = false
        var className: String? = null
        var dump = false
        var cMode = false
        var cLib = false
        var cJni = false
        var meta = false
        var jar = false
        var link = false
        val positional = ArrayList<String>()

        var i = 0
        while (i < rest.size) {
            when (val a = rest[i]) {
                "-o", "--out" -> {
                    val v = rest.getOrNull(i + 1) ?: throw RsfError("cli", "-o 后面要跟输出目录")
                    outDir = File(v)
                    outGiven = true
                    i++
                }
                "-n", "--name" -> {
                    className = rest.getOrNull(i + 1) ?: throw RsfError("cli", "-n 后面要跟类名")
                    i++
                }
                "--dump" -> dump = true
                "--c" -> cMode = true
                "--lib" -> cLib = true
                "--jni" -> cJni = true
                "--meta" -> meta = true
                "--jar" -> jar = true
                "--link" -> link = true
                else -> positional.add(a)
            }
            i++
        }

        // 不带文件名时：项目里若有 build.rsfs，就由它驱动构建（构建脚本必须有 main）。
        // 这样 `rsf build` 在项目目录里和 `rsf build x.rsf` 在单文件模式下是同一个命令。
        // 阶段 81：脚本拿到一个宿主（`compile` / `compileAll` / `outDir`），真的能编译东西；
        // `--c` / `--meta` 也走这条路——它们只影响宿主怎么编。
        if (positional.isEmpty() && !dump) {
            val buildScript = File("build.rsfs")
            if (buildScript.isFile) {
                val loadedScript = Rsf.load(buildScript)
                // 阶段 84：`#dep { java(...) }` 声明的 jar 必须在**分析之前**装上，
                // 否则 Checker 看不见 jar 里的类，构建脚本也就调不到它们。
                // 宿主先造出来（它只管路径与设置，不依赖分析结果），安装完再分析。
                val (effOut0, effTarget0, effMeta0) = ProjectBuildHost.settings(
                    build = loadedScript.build,
                    cliOut = if (outGiven) outDir else null,
                    cliC = cMode,
                    cliMeta = meta,
                )
                val earlyHost = ProjectBuildHost(
                    srcDir = File("src"),
                    outDir = effOut0,
                    target = effTarget0,
                    meta = effMeta0,
                    deps = loadedScript.deps,
                )
                earlyHost.installJars()
                val scriptModel = Rsf.analyze(loadedScript)
                for (warning in scriptModel.warnings) System.err.println("[rsf] 警告: $warning")
                val problem = scriptModel.entryProblem
                if (problem != null) throw RsfError("build", "build.rsfs 不能当构建脚本：$problem")
                // 没写 -o 时用项目约定 `build/out`（脚本里 `outDir()` 看到的就是它）；
                // 阶段 82：`#build { … }` 的 out / target / meta 是默认值，命令行显式给了就压过它。
                val (effOut, effTarget, effMeta) = ProjectBuildHost.settings(
                    build = loadedScript.build,
                    cliOut = if (outGiven) outDir else null,
                    cliC = cMode,
                    cliMeta = meta,
                )
                val host = ProjectBuildHost(
                    srcDir = File("src"),
                    outDir = effOut,
                    target = effTarget,
                    meta = effMeta,
                    deps = loadedScript.deps,
                )
                if (loadedScript.deps.isNotEmpty()) {
                    println("[rsf] 依赖: ${loadedScript.deps.joinToString("、") { it.display() }}")
                }
                // 阶段 0：`#target` 块里声明的目标先入表——声明顺序就是构建顺序，
                // 所以它一定排在 main 里 `target()` 加的那些前面。
                for (t in loadedScript.targets) {
                    host.addTarget(
                        TargetSpec.of(t.src, t.kind, t.out, "build.rsfs 第 ${t.line} 行的 #target"),
                    )
                }
                // 阶段 1：`#dep rule(...)` 里的规则，按声明顺序先跑。
                // 名字在装载期就校验：规则就是函数（#def 解糖出来的），所以「认得出名字」
                // 和「函数在不在」是同一件事——写错立刻报，并列出能用的名字。
                val ruleNames = loadedScript.deps.filter { it.kind == DepKind.RULE }.map { it.target }
                if (ruleNames.isNotEmpty()) {
                    val zeroArg = scriptModel.functions.filter { it.params.isEmpty() }.map { it.name }
                    val missing = ruleNames.filter { it !in zeroArg }
                    if (missing.isNotEmpty()) {
                        val knownRules = loadedScript.rules.map { it.name }
                        throw RsfError(
                            "build",
                            "#dep rule(...) 指不到函数：${missing.joinToString("、")}。" +
                                "已知规则：${if (knownRules.isEmpty()) "（没有 #def）" else knownRules.joinToString("、")}；" +
                                "可调的零参数函数：${zeroArg.joinToString("、")}",
                        )
                    }
                    println("[rsf] 规则：" + ruleNames.joinToString(" -> "))
                }
                // 阶段 2：跑 main。target / need / exec 都只有这里能用（宿主由上面这行注入）。
                // 规则和 main 在同一个解释器实例里跑，全局变量只初始化一次。
                Rsf.interpret(scriptModel, System.out, host, ruleNames)
                // 依赖可能在脚本或任一被编译的源文件里声明，所以锁文件写在跑完之后。
                if (host.hasDeps()) {
                    val lock = host.writeLock()
                    println("[rsf] 依赖锁文件: ${lock.path}")
                }
                // 阶段 3：按计划出产物（第一个失败即停，异常直接冒到 CLI）。
                buildPlanned(host, effMeta)
                return 0
            }
        }
        val file = requireFile(
            positional.firstOrNull(),
            "build [<file.rsf>] [-o <dir>] [-n <类名>] [--dump] [--c [--lib] [--jni]]",
        )
        // 阶段 60：`--c` 是 native 构建，只收 target native / 两侧都写的声明
        val loaded = Rsf.load(file, target = if (cMode) BuildTarget.NATIVE else BuildTarget.JVM)
        val source = loaded.root
        val model = Rsf.analyze(loaded)
        val name = className ?: Rsf.deriveClassName(source)
        // --meta：把源码快照 + 符号表打进产物，供 `rsf decompile` 还原
        val metaBlob = if (meta) {
            RsfMeta.encode(
                RsfMeta.snapshotsOf(loaded),
                RsfMeta.symbolsOf(loaded),
                RsfMeta.describe(if (cMode) "native" else "jvm", name),
            )
        } else {
            null
        }

        if (cMode) {
            for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
            val out = emitNative(loaded, model, name, outDir, metaBlob, withEntry = !cLib)
            // 阶段 60b：JVM → native 的桥。符号名要跟 JVM 类名一致，所以这里用同一个 name。
            if (cJni) {
                val bridge = File(outDir, "${name}_jni.c")
                bridge.writeText(CBackend(model, withEntry = false).emitJni(name), Charsets.UTF_8)
                println("[rsf] 已生成 JNI 桥 ${bridge.path}")
                println("[rsf] 链接: gcc -shared " + out.path + " " + bridge.path +
                    " -I%JAVA_HOME%/include -I%JAVA_HOME%/include/win32 -o " + name + ".dll")
            }
            if (cLib) {
                println("[rsf] 这是库（没有 main）: gcc -c ${out.path} -o ${File(outDir, "$name.o").path}")
            } else if (link) {
                linkExe(
                    out, outDir, name,
                    extraSources = depFiles(loaded.deps, DepKind.C) { File(it) },
                    includeDirs = depFiles(loaded.deps, DepKind.H) { File(it) }.mapNotNull { it.parentFile },
                )
            } else {
                println("[rsf] 编译: gcc ${out.path} -o ${File(outDir, "$name.exe").path} -lm")
            }
            return 0
        }

        val result = Rsf.build(model, source, name, outDir, metaBlob)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        for (w in result.warnings) System.err.println("[rsf] 警告: $w")
        println("[rsf] 已生成 ${result.classFile.path}")
        // 带用户自定义类的程序会一起生成每个类自己的 .class（类文件在同一个包目录下）
        for (extra in result.classFiles.drop(1)) println("[rsf] 已生成 ${extra.path}")
        if (model.entry != null) {
            // 带了包时要用全限定名启动：java -cp out app.main
            val launch = model.packageName
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$it.${result.className}" }
                ?: result.className
            println("[rsf] 运行: java -cp ${outDir.path} $launch")
        } else {
            System.err.println("[rsf] 警告: 源文件里没有 main 函数，生成的类不能用 java 直接启动")
        }

        if (jar) {
            val launch = model.packageName
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$it.${result.className}" }
                ?: result.className
            val f = writeJar(outDir, name, result, if (model.entry != null) launch else null)
            println("[rsf] 已打包 ${f.path}")
            if (model.entry != null) println("[rsf] 运行: java -jar ${f.path}")
        }

        if (dump) {
            println()
            println(disassemble(result.classFile.readBytes()))
        }
        return 0
    }


    /**
     * 阶段 3：按目标表出产物。
     *
     * 顺序由 [BuildPlan.ordered] 定（声明顺序 + `need` 约束）；**第一个失败即停**，
     * 异常一路冒到 CLI——半成品留在磁盘上不可怕，继续编下去才可怕。
     */
internal fun Cli.buildPlanned(host: ProjectBuildHost, meta: Boolean) {
        val plan = host.plan()
        if (plan.isEmpty()) {
            println("[rsf] 目标表是空的：构建脚本既没写 #target 块，也没调 target(...)，没有产物可出")
            return
        }
        val ordered = plan.ordered()
        println("[rsf] 构建顺序：" + ordered.joinToString(" -> ") { it.name })
        val outRoot = host.resolveInProject(host.outDir())
        outRoot.mkdirs()
        for (spec in ordered) buildOne(host, spec, outRoot, meta)
    }

    /** 出一个目标。[spec] 的源路径与产物路径都按项目根解析。 */
internal fun Cli.buildOne(host: ProjectBuildHost, spec: TargetSpec, outRoot: File, meta: Boolean) {
        val src = host.resolveInProject(spec.src)
        if (!src.isFile) {
            throw RsfError(
                "build",
                "目标 '${spec.name}' 的源文件不存在：'${spec.src}'（按项目根解析成 ${src.path}）",
            )
        }
        // 显式产物路径优先（`#target` 的第三段）；没写就按种类放到产物目录里。
        val dest = spec.out?.let { host.resolveInProject(it) }
        val started = System.currentTimeMillis()
        val produced: File = when (spec.kind) {
            // copy 不编译：配置、资源、脚本原样搬进产物目录（文件名保留扩展名）
            TargetKind.COPY -> {
                val target = dest ?: File(outRoot, src.name)
                target.parentFile?.mkdirs()
                src.copyTo(target, overwrite = true)
                target
            }
            // exe：native 后端出 .c，再交给系统 C 编译器链接成可执行文件
            TargetKind.EXE -> {
                val loaded = Rsf.load(src, target = BuildTarget.NATIVE)
                val model = Rsf.analyze(loaded)
                for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
                val cFile = emitNative(loaded, model, spec.name, outRoot, metaBlobOf(loaded, "native", spec.name, meta), withEntry = true)
                val exe = linkExe(
                    cFile, outRoot, spec.name,
                    extraSources = (depFiles(loaded.deps, DepKind.C) { host.resolveInProject(it) } + host.cDeps())
                        .distinctBy { it.absolutePath },
                    includeDirs = (depFiles(loaded.deps, DepKind.H) { host.resolveInProject(it) } + host.hDeps())
                        .mapNotNull { it.parentFile }.distinctBy { it.absolutePath },
                )
                relocate(exe, dest)
            }
            // lib：只编成目标文件，不链接（给别人 `gcc app.o` 用）
            TargetKind.LIB -> {
                val loaded = Rsf.load(src, target = BuildTarget.NATIVE)
                val model = Rsf.analyze(loaded)
                for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
                val cFile = emitNative(loaded, model, spec.name, outRoot, metaBlobOf(loaded, "native", spec.name, meta), withEntry = false)
                relocate(
                    compileObject(
                        cFile, outRoot, spec.name,
                        includeDirs = (depFiles(loaded.deps, DepKind.H) { host.resolveInProject(it) } + host.hDeps())
                            .mapNotNull { it.parentFile }.distinctBy { it.absolutePath },
                    ),
                    dest,
                )
            }
            // jar：JVM 后端 + 打包（和单文件模式的 --jar 完全同一条路）
            TargetKind.JAR -> {
                val jarFile = buildJar(src, outRoot, spec.name, metaBlobOf(Rsf.load(src), "jvm", spec.name, meta))
                relocate(jarFile, dest)
            }
        }
        val ms = System.currentTimeMillis() - started
        println("[rsf] 目标 ${spec.name}（${spec.kind.keyword}）-> ${produced.path}（${ms} ms）")
    }

    /** 显式产物路径：把刚出的文件搬过去（同目录时什么都不做）。 */
internal fun Cli.relocate(produced: File, dest: File?): File {
        if (dest == null) return produced
        if (dest.absoluteFile == produced.absoluteFile) return produced
        dest.parentFile?.mkdirs()
        produced.copyTo(dest, overwrite = true)
        produced.delete()
        return dest
    }

    /** 把已分析好的 native 程序落成一份 .c（连运行时头），返回 .c 路径。 */
internal fun Cli.emitNative(
        loaded: LoadedProgram,
        model: SemanticModel,
        name: String,
        outDir: File,
        metaBlob: ByteArray?,
        withEntry: Boolean,
    ): File {
        // 运行时是共享头文件（rsf_runtime.h），不再随每份 .c 携带：
        // 先确保 out 里的头文件与当前编译器同源，再让这份 .c 做唯一的 RSF_RUNTIME_IMPL 定义。
        val backend = CBackend(model, withEntry = withEntry, emitImpl = true)
        outDir.mkdirs()
        if (backend.ensureRuntimeLib(outDir)) {
            println("[rsf] 已生成 ${File(outDir, "rsf_runtime.h").path}")
        }
        val text = backend.emit()
        val out = File(outDir, "$name.c")
        out.writeText(
            if (metaBlob != null) text + RsfMeta.cDeclaration(metaBlob) else text,
            Charsets.UTF_8,
        )
        println("[rsf] 已生成 ${out.path}")
        if (metaBlob != null) {
            println("[rsf] 已嵌入源码元数据（${metaBlob.size} 字节，节 ${RsfMeta.SECTION}）")
        }
        return out
    }

    /**
     * 调系统 C 编译器链接出可执行文件；失败时把命令原样打出来，方便手调。
     *
     * [extraSources] 是依赖清单里的 `.c`（`cInclude` 或 `import "x.c"`）——extern 函数
     * 的实体就在里面；[includeDirs] 是 `hInclude` 那些头文件所在的目录（`-I`）。
     */
    /** 依赖清单里某一类的文件（按 [resolve] 解路径，去掉不存在与重复）。 */
internal fun Cli.depFiles(deps: List<DepEntry>, kind: DepKind, resolve: (String) -> File): List<File> =
        deps.filter { it.kind == kind }
            .map { resolve(it.target) }
            .filter { it.isFile }
            .distinctBy { it.absolutePath }

internal fun Cli.linkExe(
        cFile: File,
        outDir: File,
        name: String,
        extraSources: List<File> = emptyList(),
        includeDirs: List<File> = emptyList(),
    ): File {
        val exe = File(outDir, if (isWindows()) "$name.exe" else name)
        val cc = System.getenv("CC") ?: "gcc"
        val cmd = ArrayList<String>()
        cmd += listOf(cc, "-std=c11", "-O1", "-w")
        for (dir in includeDirs.distinct()) cmd += "-I${dir.path}"
        cmd += cFile.path
        for (src in extraSources.distinctBy { it.path }) cmd += src.path
        cmd += listOf("-o", exe.path, "-lm")
        cmd as List<String>
        println("[rsf] 链接: ${cmd.joinToString(" ")}")
        val rc = ProcessBuilder(cmd).inheritIO().start().waitFor()
        if (rc != 0) throw RsfError("build", "链接失败（${cc} 退出码 $rc）：${cmd.joinToString(" ")}")
        println("[rsf] 已生成 ${exe.path}")
        return exe
    }

    /** `gcc -c` 出一个目标文件（`lib` 种类的产物；`-I` 不能少，否则头文件找不到）。 */
internal fun Cli.compileObject(cFile: File, outDir: File, name: String, includeDirs: List<File> = emptyList()): File {
        val obj = File(outDir, if (isWindows()) "$name.obj" else "$name.o")
        val cc = System.getenv("CC") ?: "gcc"
        val cmd = ArrayList<String>()
        cmd += listOf(cc, "-std=c11", "-O1", "-w", "-c")
        for (dir in includeDirs.distinct()) cmd += "-I${dir.path}"
        cmd += listOf(cFile.path, "-o", obj.path)
        cmd as List<String>
        println("[rsf] 编译: ${cmd.joinToString(" ")}")
        val rc = ProcessBuilder(cmd).inheritIO().start().waitFor()
        if (rc != 0) throw RsfError("build", "编目标文件失败（${cc} 退出码 $rc）：${cmd.joinToString(" ")}")
        println("[rsf] 已生成 ${obj.path}")
        return obj
    }

    /** JVM 后端 + 打 jar。 */
internal fun Cli.buildJar(file: File, outDir: File, name: String, metaBlob: ByteArray?): File {
        val loaded = Rsf.load(file, target = BuildTarget.JVM)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        val result = Rsf.build(model, loaded.root, name, outDir, metaBlob)
        for (warning in result.warnings) System.err.println("[rsf] 警告: $warning")
        val launch = model.packageName
            ?.takeIf { it.isNotEmpty() }
            ?.let { "$it.${result.className}" }
            ?: result.className
        val jar = writeJar(outDir, name, result, if (model.entry != null) launch else null)
        println("[rsf] 已打包 ${jar.path}")
        return jar
    }

    /** `--meta` 的整块：源码快照 + 符号表 + 构建信息；不要元数据时给 null。 */
internal fun Cli.metaBlobOf(loaded: LoadedProgram, side: String, name: String, meta: Boolean): ByteArray? =
        if (!meta) {
            null
        } else {
            RsfMeta.encode(
                RsfMeta.snapshotsOf(loaded),
                RsfMeta.symbolsOf(loaded),
                RsfMeta.describe(side, name),
            )
        }

/** 把这次生成的 .class 打成一个可执行 jar（有 main 就写 Main-Class）。 */
internal fun Cli.writeJar(outDir: File, name: String, result: BuildResult, mainClass: String?): File {
        val jar = File(outDir, "$name.jar")
        val manifest = java.util.jar.Manifest()
        manifest.mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
        if (mainClass != null) {
            manifest.mainAttributes[java.util.jar.Attributes.Name.MAIN_CLASS] = mainClass
        }
        java.util.jar.JarOutputStream(jar.outputStream(), manifest).use { out ->
            for (f in result.classFiles) {
                val rel = f.absoluteFile.relativeTo(outDir.absoluteFile).path.replace(File.separatorChar, '/')
                out.putNextEntry(java.util.zip.ZipEntry(rel))
                out.write(f.readBytes())
                out.closeEntry()
            }
        }
        return jar
    }

internal fun Cli.isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("win")
