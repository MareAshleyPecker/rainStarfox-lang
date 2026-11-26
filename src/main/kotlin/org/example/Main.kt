package org.example


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

internal const val PROMPT = "rsf>>> "

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

    /**
     * 上一次构建出了哪些产物（阶段 84）。
     *
     * `rsf run` 在项目目录里要先构建再运行，而构建逻辑在 [cmdBuild] 里——它原来只打印。
     * 与其把整段构建流程再抄一遍，不如让它把结果留在这里，`run` 直接取。
     * `Cli` 是一次性命令对象，这点状态不会跨命令泄漏。
     */
    internal var lastBuilt: List<BuiltArtifact> = emptyList()

    /** `run { use 名字 }` 里选中的默认产物（阶段 84）：`rsf run` 的优先选择。 */
    internal var preferredRun: String? = null

    fun run(argv: List<String>): Int {
        if (argv.isEmpty()) return repl()
        return when (val cmd = argv[0]) {
            "run" -> wrap { cmdRun(argv.drop(1)) }
            "script" -> wrap { cmdScript(argv.drop(1)) }
            "init" -> wrap { cmdInit(argv.drop(1)) }
            "build", "compile" -> wrap { cmdBuild(argv.drop(1)) }
            "check" -> wrap { cmdCheck(argv.drop(1)) }
            "complete" -> wrap { cmdComplete(argv.drop(1)) }
            "lsp" -> wrap { cmdLsp(argv.drop(1)) }
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

    /** 编译成 .class。 */
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

    /** `<ControlType> <static> fun <MainType> 名字(参数表) <返回子句>` 的可读形式。 */
    internal fun signatureOf(fn: org.example.complie.ast.FuncDecl): String {
        val mainType = if (fn.mainType.name == "void") "" else " ${fn.mainType.name}"
        // 用 display()：TypeRef 的数组维度单独放在 arrayDepth 里，光取 name 会把 `Int[]` 印成 `Int`
        // （自举侧一直印的是 `Int[]`，这里不对齐就会被 verify36 的 check 对拍抓到）
        val params = fn.params.joinToString(", ") { "${it.name} : ${it.type.display()}" }
        // 缺省就是 public：清单里**显示成 public**，空着会让人以为「没有权限」
        val control = fn.control.label.padEnd(10)
        val static = if (fn.isStatic) "stc " else ""
        return "$control ${static}fn$mainType ${fn.name}($params) ${fn.ret.display()}"
    }

    internal fun requireFile(path: String?, usage: String): File {
        if (path == null) throw RsfError("cli", "用法: rsf $usage")
        val f = File(path)
        if (!f.isFile) throw RsfError("cli", "找不到文件: $path")
        return f
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
            |  lsp [--script]                   语言服务器（stdio）；--script 把打开的文件都当 .rsfs 脚本
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
