package org.example

// 工具类命令：run / script / check / complete / lsp / lex（阶段 84 搬运）。
//
// 和 CliBuild.kt 同一个套路：`Cli` 的扩展函数，函数体原样不动。

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

    /** 解释执行：不生成任何 .class。 */
internal fun Cli.cmdRun(rest: List<String>): Int {
        // 阶段 84：`rsf run` 在项目目录里变成 cargo run——先构建，再跑产物。
        // 单个文件（`rsf run a.rsf`）保持原语义：解释执行，不构建。
        var bin: String? = null
        val programArgs = ArrayList<String>()
        val positional = ArrayList<String>()
        var i = 0
        var afterDoubleDash = false
        while (i < rest.size) {
            val a = rest[i]
            when {
                afterDoubleDash -> programArgs.add(a)
                a == "--" -> afterDoubleDash = true
                a == "--bin" -> {
                    bin = rest.getOrNull(i + 1)
                        ?: throw org.example.complie.diag.RsfError("cli", "--bin 后面要跟产物名")
                    i++
                }
                a.startsWith("-") -> throw org.example.complie.diag.RsfError(
                    "cli",
                    "run 不认识参数 '$a'。用法：rsf run [<file.rsf>] [--bin <产物名>] [-- 程序参数…]",
                )
                else -> positional.add(a)
            }
            i++
        }
        if (positional.isEmpty() && java.io.File("build.rsfs").isFile) {
            return runProject(bin, programArgs)
        }
        if (positional.isEmpty()) {
            throw org.example.complie.diag.RsfError(
                "cli",
                "用法: rsf run <file.rsf>（在一个有 build.rsfs 的项目目录里可以不带文件名：先构建再运行）",
            )
        }
        val file = requireFile(positional.firstOrNull(), "run <file.rsf>")
        val loaded = Rsf.load(file)
        val model = Rsf.analyze(loaded)
        for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
        requireEntry(model)
        Rsf.interpret(model, System.out)
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
internal fun Cli.cmdScript(rest: List<String>): Int {
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

    /** 只做前端检查。`--json` 输出结构化诊断（给编辑器用）。 */
internal fun Cli.cmdCheck(rest: List<String>): Int {
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
internal fun Cli.cmdComplete(rest: List<String>): Int {
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
     *
     * `--script`：把打开的每一份文件都当**脚本**（`.rsfs`）解析——顶层可以直接写语句。
     * 编辑器那边把 `.rsfs` 交给同一个服务器时，这个开关让文件不必真叫 `.rsfs`。
     */
internal fun Cli.cmdLsp(rest: List<String>): Int {
        var script = false
        for (arg in rest) {
            when (arg) {
                "--script", "-s" -> script = true
                else -> throw RsfError("usage", "lsp 不认识参数 '$arg'。用法：rsf lsp [--script]")
            }
        }
        org.example.complie.lsp.LanguageServer(System.`in`, System.out, script).serve()
        return 0
    }

    /** 打印 token 流，调试词法用。 */
internal fun Cli.cmdLex(rest: List<String>): Int {
        val file = requireFile(rest.firstOrNull(), "lex <file.rsf>")
        val source = SourceFile.of(file)
        for (t in Rsf.tokens(source)) {
            val literal = t.literal?.let { "  = $it (${it::class.simpleName})" } ?: ""
            println("${t.line.toString().padStart(4)}:${t.column.toString().padEnd(4)} ${t.kind.name.padEnd(12)} '${t.lexeme}'$literal")
        }
        return 0
    }

    // ------------------------------------------------------------------ REPL

internal fun Cli.requireEntry(model: SemanticModel) {
        model.entryProblem?.let { throw RsfError("checker", it) }
    }

/**
 * `rsf run` 的项目模式：先跑构建（四阶段），再从产物里挑一个来运行。
 *
 * 挑法照 cargo 的规矩：只有一个可运行的产物就跑它；有多个就用 `--bin 名字` 指定。
 * 可运行 = `exe`（native 二进制）或 `jar`（JVM 包）；`lib` / `copy` 不是程序。
 */
internal fun Cli.runProject(bin: String?, programArgs: List<String>): Int {
    lastBuilt = emptyList()
    val rc = cmdBuild(emptyList())
    if (rc != 0) return rc
    val runnable = lastBuilt.filter { it.spec.kind == org.example.build.TargetKind.EXE || it.spec.kind == org.example.build.TargetKind.JAR }
    if (runnable.isEmpty()) {
        throw org.example.complie.diag.RsfError(
            "build",
            "目标表里没有可运行的产物：rsf run 要的是 exe 或 jar，" +
                "现在只有 ${lastBuilt.joinToString("、") { "${it.spec.name}（${it.spec.kind.keyword}）" }}",
        )
    }
    val chosen = when {
        bin != null -> runnable.firstOrNull { it.spec.name == bin }
            ?: throw org.example.complie.diag.RsfError(
                "build",
                "没有叫 '$bin' 的可运行产物。可选的：${runnable.joinToString("、") { it.spec.name }}",
            )
        runnable.size == 1 -> runnable.first()
        else -> throw org.example.complie.diag.RsfError(
            "build",
            "有多个可运行的产物：${runnable.joinToString("、") { it.spec.name }}。" +
                "用 rsf run --bin <名字> 指定一个",
        )
    }
    val cmd = when (chosen.spec.kind) {
        org.example.build.TargetKind.EXE -> listOf(chosen.file.path)
        else -> listOf("java", "-jar", chosen.file.path)
    } + programArgs
    println("[rsf] 运行: ${cmd.joinToString(" ")}")
    val process = ProcessBuilder(cmd).inheritIO().start()
    return process.waitFor()
}
