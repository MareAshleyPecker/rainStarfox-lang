package org.example

import org.example.build.ProjectBuildHost
import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.codegen.disassemble
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.interp.RsfFailure
import org.example.complie.lsp.EditorSupport
import org.example.complie.native.CBackend
import org.example.complie.sema.SemanticModel
import org.example.complie.util.input
import org.example.meta.RsfMeta
import java.io.File
import kotlin.system.exitProcess

private const val PROMPT = "rsf>>> "

fun main(args: Array<String>) {
    // 机器可读模式（--json）：stdout 强制 UTF-8。
    // 不这么做的话，Windows 上 JVM 会按本机编码（cp936）往管道里写字，编辑器那头
    // 一律按 UTF-8 解码，中文提示就会变成乱码。人看的输出维持本机编码不动——
    // 那是控制台自己的编码，改了反而花屏。
    if (args.any { it == "--json" } || args.firstOrNull() == "lsp") {
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, Charsets.UTF_8))
        System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, Charsets.UTF_8))
    }
    exitProcess(Cli().run(args.toList()))
}

class Cli {

    fun run(argv: List<String>): Int {
        if (argv.isEmpty()) return repl()
        return when (val cmd = argv[0]) {
            "run" -> wrap { cmdRun(argv.drop(1)) }
            "script" -> wrap { cmdScript(argv.drop(1)) }
            "init" -> wrap { cmdInit(argv.drop(1)) }
            "build", "compile" -> wrap { cmdBuild(argv.drop(1)) }
            "check" -> wrap { cmdCheck(argv.drop(1)) }
            "complete" -> wrap { cmdComplete(argv.drop(1)) }
            "lsp" -> wrap { cmdLsp() }
            "decompile" -> wrap { cmdDecompile(argv.drop(1)) }
            "lex" -> wrap { cmdLex(argv.drop(1)) }
            "repl" -> repl()
            "help", "--help", "-h", "-?" -> { usage(); 0 }
            "version", "--version", "-v" -> { println("rainStarfox ${Rsf.VERSION}"); 0 }
            else -> {
                System.err.println("未知命令 '$cmd'")
                usage()
                2
            }
        }
    }

    // ------------------------------------------------------------------ 命令

    /** 解释执行：不生成任何 .class。 */
    private fun cmdRun(rest: List<String>): Int {
        val file = requireFile(rest.firstOrNull(), "run <file.rsf>")
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        requireEntry(model)
        Rsf.interpret(model, System.out)
        return 0
    }

    /**
     * 建一个项目骨架：`rsf init [目录]`。
     *
     * 生成的 `build.rsfs` **必须有 main**（构建脚本的入口），但规则可以拆成模块
     * （`build/` 下的 .rsfs 用 import 拼起来）——和 gradle 的 settings/build 分层一个意思，
     * 自举出 toolchain 之后这套骨架照样能用。
     */
    private fun cmdInit(rest: List<String>): Int {
        val dir = File(rest.firstOrNull() ?: ".")
        dir.mkdirs()
        val src = File(dir, "src")
        val buildDir = File(dir, "build")
        src.mkdirs()
        buildDir.mkdirs()

        File(dir, "build.rsfs").writeText(
            """
            |// 项目构建脚本：入口是 main，规则可以拆到 build/ 下的模块里。
            |import "build/defaults.rsfs"
            |
            |// 产物设置（只认项目根的 build.rsfs）。命令行给的 -o / --c / --meta 压过这里。
            |#build {
            |    out("build/out")
            |    target("auto")
            |}
            |
            |// 依赖写在这里或任意源文件里，构建末尾汇总进 .rsfdeps/lock.json。
            |// #dep {
            |//     java("libs/util.jar")
            |//     rule("extraDeps")
            |// }
            |
            |fn main() -> void {
            |    println("构建 " + projectName() + "（" + targetName() + "）")
            |    // 把 src/ 下每个 .rsf / .rsfs 编到产物目录；只编一个就换成 compile("src/main.rsf")。
            |    val count = compileAll("src")
            |    println("编译 " + intToString(count) + " 个文件 -> " + outDir())
            |}
            """.trimMargin(),
            Charsets.UTF_8,
        )
        File(buildDir, "defaults.rsfs").writeText(
            """
            |// 构建规则的默认值；拆成模块是为了让 build.rsfs 只留流程。
            |fn projectName() -> String {
            |    rtn "app"
            |}
            |
            |fn targetName() -> String {
            |    rtn "app"
            |}
            """.trimMargin(),
            Charsets.UTF_8,
        )
        File(src, "main.rsf").writeText(
            """
            |fn main() -> void {
            |    println("hello, rsf")
            |}
            """.trimMargin(),
            Charsets.UTF_8,
        )
        File(dir, ".gitignore").writeText(".rsfcache/\n.rsfdeps/\n", Charsets.UTF_8)

        println("[rsf] 已生成 ${File(dir, "build.rsfs").path}")
        println("[rsf] 已生成 ${File(src, "main.rsf").path}")
        println("[rsf] 运行: rsf run ${File(src, "main.rsf").path}")
        println("[rsf] 构建脚本: rsf script ${File(dir, "build.rsfs").path}")
        return 0
    }

    /**
     * 脚本入口：`.rsf` / `.rsfs` 都收。
     *
     * 与 `run` 的唯一差别是**不要求 main**：
     * - 有 main 就跑 main（和 run 完全一致）；
     * - 没有 main 就把它当纯库，列出可调用的顶层函数（`--list` 显式要这份列表）。
     *
     * `.rsfs` 目前的语义只有这一条。导出面暂时是「全部顶层函数」；
     * 等 `export` 关键字落地后再收窄（那要同时改自举的 Lexer/Parser，单独一刀）。
     */
    private fun cmdScript(rest: List<String>): Int {
        val file = requireFile(rest.firstOrNull(), "script <file.rsf|.rsfs> [--list]")
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        // 只要有一个 export，导出面就只算 export 的那些；一个都没有时退回「全部顶层函数」，
        // 这样加上 export 之前的脚本还能照旧跑。
        val exported = model.functions.filter { it.exported }
        val names = (if (exported.isNotEmpty()) exported else model.functions)
            .map { it.name }.distinct().sorted()
        if (model.entry == null) {
            println("[rsf] 脚本: ${file.path}（没有 main，可调用：）")
            for (fn in names) println("  $fn")
            return 0
        }
        if (rest.contains("--list")) {
            for (fn in names) println(fn)
            return 0
        }
        Rsf.interpret(model, System.out)
        return 0
    }

    /** 编译成 .class。 */
    private fun cmdBuild(rest: List<String>): Int {
        var outDir = File("out")
        /** 用户显式给了 `-o` 吗？项目模式（build.rsfs）没给时用 `build/out`。 */
        var outGiven = false
        var className: String? = null
        var dump = false
        var cMode = false
        var cLib = false
        var cJni = false
        var meta = false
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
                Rsf.interpret(scriptModel, System.out, host)
                // 依赖可能在脚本或任一被编译的源文件里声明，所以锁文件写在跑完之后。
                if (host.hasDeps()) {
                    val lock = host.writeLock()
                    println("[rsf] 依赖锁文件: ${lock.path}")
                }
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
            // 运行时是共享头文件（rsf_runtime.h），不再随每份 .c 携带：
            // 先确保 out 里的头文件与当前编译器同源，再让这份 .c 做唯一的 RSF_RUNTIME_IMPL 定义。
            val backend = CBackend(model, withEntry = !cLib, emitImpl = true)
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
            if (metaBlob != null) println("[rsf] 已嵌入源码元数据（${metaBlob.size} 字节，节 ${RsfMeta.SECTION}）")
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

        if (dump) {
            println()
            println(disassemble(result.classFile.readBytes()))
        }
        return 0
    }

    /**
     * 从产物里把源码拿回来：`rsf decompile a.exe` / `rsf decompile Main.class`。
     *
     * 产物是用 `--meta` 编出来的才带源码快照（`a.exe` 在 PE 的 `.rsfmeta` 节里，
     * class 在自定义属性 `RsfSource` 里）；没有快照时至少把符号表和构建信息打印出来。
     */
    private fun cmdDecompile(rest: List<String>): Int {
        var outDir = File("decompiled")
        var listOnly = false
        val positional = ArrayList<String>()
        var i = 0
        while (i < rest.size) {
            when (val a = rest[i]) {
                "-o", "--out" -> {
                    val v = rest.getOrNull(i + 1) ?: throw RsfError("cli", "-o 后面要跟输出目录")
                    outDir = File(v)
                    i++
                }
                "--list" -> listOnly = true
                else -> positional.add(a)
            }
            i++
        }
        val file = requireFile(
            positional.firstOrNull(),
            "decompile <a.exe | X.class> [-o <dir>] [--list]",
        )
        if (!file.isFile) throw RsfError("decompile", "${file.path} 不是一个文件")

        val bytes = file.readBytes()
        val payload = RsfMeta.readPeSection(bytes, RsfMeta.SECTION)
            ?: RsfMeta.readClassAttribute(bytes, RsfMeta.ATTRIBUTE)
            ?: bytes.takeIf {
                it.size > RsfMeta.MAGIC.length &&
                    String(it, 0, RsfMeta.MAGIC.length, Charsets.US_ASCII) == RsfMeta.MAGIC
            }
            ?: throw RsfError(
                "decompile",
                "${file.path} 里没有 rsf 元数据。它是用 `rsf build --meta` 编出来的吗？",
            )

        val blob = RsfMeta.decode(payload)
        val build = blob.build
        println("[rsf] 产物: ${file.path}")
        if (build.isNotEmpty()) {
            println("[rsf] 编译器: ${build["compiler"] ?: "?"}   类型: ${build["artifact"] ?: "?"}")
            if (build["class"] != null) println("[rsf] 类名: ${build["class"]}   构建于: ${build["built"] ?: "?"}")
        }
        for (s in blob.sources) {
            println("[rsf] 源文件: ${s.path}（${s.text.count { it == '\n' } + 1} 行，${s.text.length} 字符）")
        }
        if (blob.sources.isEmpty()) {
            println("[rsf] 这份产物不含源码快照，只有符号表：")
            println(blob.symbols)
            return 0
        }
        if (listOnly) return 0

        // 还原到 -o 目录；产物里记的是编译时的相对路径，按同样的相对结构铺开
        val written = RsfMeta.writeSources(blob, outDir)
        for (f in written) println("[rsf] 已还原 ${f.path}")
        println("[rsf] 共还原 ${written.size} 个文件到 ${outDir.path}")
        return 0
    }

    /** 只做前端检查。`--json` 输出结构化诊断（给编辑器用）。 */
    private fun cmdCheck(rest: List<String>): Int {
        var json = false
        var baseDir: File? = null
        val positional = ArrayList<String>()

        var i = 0
        while (i < rest.size) {
            when (val a = rest[i]) {
                "--json" -> json = true
                "--base" -> {
                    val v = rest.getOrNull(i + 1) ?: throw RsfError("cli", "--base 后面要跟目录")
                    baseDir = File(v)
                    i++
                }
                else -> positional.add(a)
            }
            i++
        }

        val file = requireFile(positional.firstOrNull(), "check <file.rsf> [--json] [--base <dir>]")

        if (json) {
            println(EditorSupport.diagnosticsJson(file, baseDir))
            return 0
        }

        val loaded = Rsf.load(file, baseDir)
        val model = Rsf.analyze(loaded)
        if (loaded.files.size > 1) {
            println("[rsf] 参与编译的文件（按 import 展开）：")
            for (f in loaded.files) println("      ${f.name}")
        }
        println("[rsf] 检查通过: ${loaded.root.name}")
        for (fn in model.functions) {
            println("      " + signatureOf(fn))
        }
        // 类也列出来：字段按声明顺序（就是运行时的字段下标），方法带签名
        for ((name, cls) in model.classModel.classes) {
            val control = cls.control.label.padEnd(10)
            // 同 signatureOf：数组维度在 arrayDepth 里，必须走 display()
            val fields = cls.fields.joinToString(", ") { "${it.name} : ${it.type.display()}" }
            println("      $control class $name { $fields }")
            for (m in cls.methods) {
                val kind = if (m.name == name) "构造器" else if (m.isStatic) "静态方法" else "方法"
                println("                 $kind ${signatureOf(m).trim()}")
            }
        }
        println(
            if (model.entry != null) "      入口: main ✓"
            else "      入口: 没有 main（只能 check/build，不能 run）"
        )
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        return 0
    }

    /**
     * 补全（给编辑器用）：在指定位置算出可以补什么，输出 JSON。
     *
     * `<file.rsf>` 可以是「缓冲区临时文件」，用 `--base` 指明真实目录好让 import 能解析。
     */
    private fun cmdComplete(rest: List<String>): Int {
        var line = 1
        var col = 1
        var baseDir: File? = null
        val positional = ArrayList<String>()

        var i = 0
        while (i < rest.size) {
            when (val a = rest[i]) {
                "--line" -> {
                    line = rest.getOrNull(i + 1)?.toIntOrNull()
                        ?: throw RsfError("cli", "--line 后面要跟行号")
                    i++
                }
                "--col" -> {
                    col = rest.getOrNull(i + 1)?.toIntOrNull()
                        ?: throw RsfError("cli", "--col 后面要跟列号")
                    i++
                }
                "--base" -> {
                    val v = rest.getOrNull(i + 1) ?: throw RsfError("cli", "--base 后面要跟目录")
                    baseDir = File(v)
                    i++
                }
                else -> positional.add(a)
            }
            i++
        }

        val file = requireFile(
            positional.firstOrNull(),
            "complete <file.rsf> --line <L> --col <C> [--base <dir>]",
        )
        println(EditorSupport.completionJson(file, null, line, col, baseDir))
        return 0
    }

    /**
     * 语言服务器：stdio 上跑 LSP，给编辑器插件用。
     *
     * 和 `check --json` 的区别是**常驻**：起一次 JVM，之后每次诊断/补全只花一次管道往返。
     * stdout 是协议通道（UTF-8 的 JSON-RPC 帧），任何调试输出都只能走 stderr。
     */
    private fun cmdLsp(): Int {
        org.example.complie.lsp.LanguageServer(System.`in`, System.out).serve()
        return 0
    }

    /** 打印 token 流，调试词法用。 */
    private fun cmdLex(rest: List<String>): Int {
        val file = requireFile(rest.firstOrNull(), "lex <file.rsf>")
        val source = SourceFile.of(file)
        for (t in Rsf.tokens(source)) {
            val literal = t.literal?.let { "  = $it (${it::class.simpleName})" } ?: ""
            println("${t.line.toString().padStart(4)}:${t.column.toString().padEnd(4)} ${t.kind.name.padEnd(12)} '${t.lexeme}'$literal")
        }
        return 0
    }

    // ------------------------------------------------------------------ REPL

    private fun repl(): Int {
        println("rainStarfox ${Rsf.VERSION} 交互式解释器")
        println("直接敲语句即可立即执行，例如: println(\"hello\" + 1 + 2)")
        println("函数声明和 while / if 块可以分多行写（大括号没配对时会提示 ... 继续输入）")
        println("命令: :help 帮助   :funcs 已登记的函数   :reset 清空   :quit 退出")
        println()

        // 已登记的函数声明源码。每次执行都把它们 + 当前输入合成一个完整程序重新检查，
        // 这样 REPL 里的语义和跑文件完全一致。
        val decls = ArrayList<String>()
        // 这个会话里已经成功执行过的顶层语句。每条新输入都会把它们**静默重放**一遍
        // （不产生输出），这样 while / if 这类复合语句对变量的修改也能跨行保留。
        val history = ArrayList<String>()

        while (true) {
            val line = readLineOrNull(PROMPT) ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when (trimmed) {
                ":quit", ":q", "exit", "quit" -> return 0
                ":help", ":h" -> { replHelp(); continue }
                ":reset" -> {
                    decls.clear()
                    history.clear()
                    println("已清空登记的函数和变量")
                    continue
                }
                ":funcs" -> { listFuncs(decls); continue }
            }

            // 需要继续读下一行的两种情况：
            //   1. 大括号还没配对（while / if / 裸块、多行函数体）
            //   2. 看着像函数声明但还没出现 '{'（返回子句可能写在下一行）
            val chunk = if (needsMoreInput(trimmed)) {
                val sb = StringBuilder(line)
                while (needsMoreInput(sb.toString())) {
                    val more = readLineOrNull("   ... ") ?: break
                    sb.append('\n').append(more)
                }
                sb.toString()
            } else {
                line
            }

            if (DECL_START.containsMatchIn(chunk)) {
                val candidate = decls + chunk
                val src = SourceFile.of("<repl>", candidate.joinToString("\n") + "\n")
                try {
                    val model = Rsf.analyze(src)
                    decls.clear()
                    decls.addAll(candidate)
                    val added = model.functions.last()
                    println("已登记  ${signatureOf(added)}")
                } catch (e: RsfError) {
                    System.err.println(e.render())
                }
            } else {
                runSnippet(decls, history, chunk)
            }
        }
        println()
        return 0
    }

    private fun runSnippet(
        decls: List<String>,
        history: MutableList<String>,
        snippet: String,
    ) {
        val stmt = snippet.trimEnd().removeSuffix(";")

        // 会话会被整体重放，所以重复声明在语义上是含糊的（改哪一次？后面会怎么重放？）。
        // 与其猜，不如直接说清楚：要改值就赋值，要重来就 :reset。
        val letName = LET_HEAD.find(stmt)?.groupValues?.get(1)
        if (letName != null && history.any { LET_HEAD.find(it)?.groupValues?.get(1) == letName }) {
            System.err.println(
                "变量 '$letName' 在这个会话里已经声明过了：" +
                    "想改值直接写 '$letName = ...'，想重来用 :reset",
            )
            return
        }

        // 合成的函数体 = 会话前缀 + 一个标记标签 + 这一句。
        // 标签是给解释器看的：它先静默重放前缀把变量状态建起来，再从标签处开始带输出执行。
        val prologue = if (history.isEmpty()) "" else history.joinToString("\n") + "\n"
        val text = decls.joinToString("\n") +
            "\nfn __repl__() -> void {\n$prologue$BEGIN_LABEL:\n$stmt\n}\n"
        val src = SourceFile.of("<repl>", text)
        try {
            val model = Rsf.analyze(src)
            val fn = model.functionsNamed("__repl__").first()
            val body = model.loweredOf(fn)
            val startPc = body.labelIndex[BEGIN_LABEL] ?: 0
            Interpreter(model, System.out).runSnippet(body, startPc)

            // 只有真的跑成功了才记进会话
            history.add(stmt)
        } catch (e: RsfFailure) {
            System.err.println("运行期错误: ${e.msg}")
        } catch (e: RsfError) {
            System.err.println(e.render())
        } catch (e: ArithmeticException) {
            System.err.println("运行期错误: ${describeArithmetic(e)}")
        } catch (e: StackOverflowError) {
            System.err.println("运行期错误: 调用太深，多半是无限递归")
        }
    }

    private fun listFuncs(decls: List<String>) {
        if (decls.isEmpty()) {
            println("还没有登记任何函数")
            return
        }
        try {
            val model = Rsf.analyze(SourceFile.of("<repl>", decls.joinToString("\n") + "\n"))
            for (fn in model.functions) {
                println("  " + signatureOf(fn))
            }
        } catch (e: RsfError) {
            System.err.println(e.render())
        }
    }

    private fun replHelp() {
        println(
            """
            |函数
            |  fn main() -> void { ... }             入口（ControlType 可省；省了就是 pub；main 不能带参数）
            |  pub fn add(a : Int, b : Int) : Int { rtn a + b }
            |  pub fn parse(s : String) -> !Int { ... }   可能失败：出错走异常，调用方要 try / ?: / !!
            |      访问控制写在最左，省略就是 pub；-> : 两种返回写法等价（'::' 不再接受）
            |      参数两种风格都能写、可以混：Int a / a : Int；参数按 C 惯例默认可变
            |
            |语句
            |  var x = 1                             声明可变变量（块作用域，出了块就不可见）
            |  var n : Int = 0                       带类型标注（类型写在名字后面）
            |  val y = 2                           声明不可变变量（重新赋值会报错）
            |  x = x * 3                               赋值（只能给 var 声明的变量赋）
            |  println("x = " + x)                     输出（内置 print / println）
            |  rtn 0    /  return 0                     返回（两个关键字等价）
            |  if (c) { ... } else { ... }             条件（else 可省，也能接 else if）
            |  if c : 单条语句    /  if c ? a : b        冒号形式 / 三目（表达式）
            |  while (c) { ... }                       循环
            |  for (i in 0..9 step 2) { ... }          区间循环（两端都含，step 可省）
            |  for (var i = 0; i < 9; i = i + 1) { } 经典三段式
            |  break   /  continue                     跳出 / 直接下一轮（含步进）
            |  fail "消息"                            抛出一个错误（只能在 !T 函数里，或者 try 里）
            |  try { ... } catch (e : String) { ... } 接住 fail 抛出来的错误（也接受 catch (e String)）
            |  loop:   /  goto loop                    C 风格跳转（标签是函数作用域）
            |  { ... }                                 块，引入新作用域
            |
            |类型: void Int Long Float Double String Bool Byte Char
            |访问控制: pub prot prv（都不写 = pub）
            |REPL 命令: :funcs :reset :quit
            """.trimMargin(),
        )
    }

    // ------------------------------------------------------------------ 工具

    /** `<ControlType> <static> fun <MainType> 名字(参数表) <返回子句>` 的可读形式。 */
    private fun signatureOf(fn: org.example.complie.ast.FuncDecl): String {
        val mainType = if (fn.mainType.name == "void") "" else " ${fn.mainType.name}"
        // 用 display()：TypeRef 的数组维度单独放在 arrayDepth 里，光取 name 会把 `Int[]` 印成 `Int`
        // （自举侧一直印的是 `Int[]`，这里不对齐就会被 verify36 的 check 对拍抓到）
        val params = fn.params.joinToString(", ") { "${it.name} : ${it.type.display()}" }
        // 缺省就是 public：清单里**显示成 public**，空着会让人以为「没有权限」
        val control = fn.control.label.padEnd(10)
        val static = if (fn.isStatic) "stc " else ""
        return "$control ${static}fn$mainType ${fn.name}($params) ${fn.ret.display()}"
    }

    private fun requireFile(path: String?, usage: String): File {
        if (path == null) throw RsfError("cli", "用法: rsf $usage")
        val f = File(path)
        if (!f.isFile) throw RsfError("cli", "找不到文件: $path")
        return f
    }

    private fun requireEntry(model: SemanticModel) {
        model.entryProblem?.let { throw RsfError("checker", it) }
    }

    private fun readLineOrNull(prompt: String): String? = try {
        input(prompt)
    } catch (_: NoSuchElementException) {
        null
    }

    private inline fun wrap(block: () -> Int): Int = try {
        block()
    } catch (e: RsfFailure) {
        // `fail` / 一路没人接住的 !T 错误：内部异常不许漏到命令行外面去，
        // 按和其它运行期错误一样的格式打出来
        System.err.println("运行期错误: ${e.msg}")
        1
    } catch (e: RsfError) {
        System.err.println(e.render())
        1
    } catch (e: ArithmeticException) {
        System.err.println("运行期错误: ${describeArithmetic(e)}")
        1
    } catch (e: NullPointerException) {
        System.err.println("运行期错误: ${e.message ?: "对 null 解引用"}")
        1
    } catch (e: StackOverflowError) {
        System.err.println("运行期错误: 调用太深，多半是无限递归")
        1
    }

    private fun describeArithmetic(e: ArithmeticException): String =
        if (e.message?.contains("zero") == true) "除以零" else (e.message ?: "算术错误")

    private fun usage() {
        println(
            """
            |rainStarfox ${Rsf.VERSION} —— .rsf 源文件的编译器 / 解释器
            |
            |用法:
            |  rsf <命令> [参数]                 不带参数进 REPL
            |
            |命令:
            |  run <file.rsf>                   解释执行，不生成任何产物
            |  script <file.rsf|.rsfs> [--list]  脚本入口：允许没有 main，列出可调用函数
            |  init [目录]                      生成项目骨架（build.rsfs + src/main.rsf）
            |  script <file.rsf> [--list|--call F] 编译进内存、当场加载并调用（不落盘）
            |  build [<file.rsf>] [选项]         编译；不带文件时若项目里有 build.rsfs 就由它驱动
            |  decompile <产物> [选项]           从产物还原源码（产物要用 build --meta 编出）
            |  check <file.rsf> [选项]           只做词法、语法、类型检查
            |  lex <file.rsf>                   打印 token 流
            |  complete <file.rsf> [选项]        补全候选，JSON（编辑器用）
            |  lsp                              语言服务器，stdio 上的 LSP
            |  repl                             交互式解释器（不带参数也是）
            |  help | version                   看帮助 / 看版本
            |
            |build 的选项:
            |  -o, --out <dir>     输出目录，默认 out/
            |  -n, --name <类名>    产物名，默认取源文件名
            |  --c                 不产 .class，产 C 源码（自己交给 gcc / clang）
            |  --lib               配合 --c：当库编，不生成 main
            |  --jni               配合 --c：另出一份 <名字>_jni.c，给 JVM 侧调 native 用
            |  --meta              把源码快照和符号表打进产物，之后可 decompile 还原
            |  --dump              配合默认模式：顺便打印生成的字节码
            |
            |decompile 的选项:
            |  -o, --out <dir>     还原到哪，默认 decompiled/
            |  --list              只列产物里有哪些源文件，不落盘
            |
            |check / complete 的选项:
            |  --json              机器可读输出（check 的 JSON 诊断）
            |  --base <dir>        指定 import 的根目录（编辑器传缓冲区临时文件时用）
            |  --line L --col C    complete 的插入位置，行列都从 1 数
            |
            |例:
            |  rsf run lang/main.rsf
            |  rsf build lang/main.rsf                  然后 java -cp out main
            |  rsf build lang/main.rsf --dump           顺便看字节码
            |  rsf build lang/main.rsf --c -o build/c   然后 gcc build/c/main.c -o main.exe
            |  rsf build lang/main.rsf --meta -o out    然后 rsf decompile out/main.class -o src
            |""".trimMargin(),
        )
    }

    /** 这一行是不是还没写完，需要继续读（见 repl 里的说明）。 */
    private fun needsMoreInput(text: String): Boolean =
        !bracesClosed(text) || (DECL_START.containsMatchIn(text) && !text.contains('{'))

    /** 大括号是否已经配对（用于 REPL 里判断输入是否完整）。 */
    private fun bracesClosed(src: String): Boolean {
        var depth = 0
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '"') {
                i++
                while (i < n && src[i] != '"') {
                    if (src[i] == '\\') i++
                    i++
                }
                i++
                continue
            }
            if (c == '{') depth++
            if (c == '}') depth--
            i++
        }
        return depth <= 0
    }

    @Suppress("unused")
    private companion object {
        /**
         * 函数声明的开头：可选的修饰符 + `fun`。
         * REPL 靠它区分「登记一个函数」和「立即执行一条语句」。
         */
        val DECL_START = Regex("^\\s*(public|private)?\\s*fn\\b")

        /** 从语句里抠出变量名，让 REPL 能识别重复声明。 */
        val LET_HEAD = Regex("^\\s*(?:var|val)\\s+([A-Za-z_][A-Za-z0-9_]*)")

        /** 合成函数体里分隔「会话前缀」和「刚敲的这一句」的标签。 */
        const val BEGIN_LABEL = "__rsf_repl_begin__"
    }
}
