package org.example

import org.example.complie.Rsf
import org.example.complie.codegen.disassemble
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.interp.RsfFailure
import org.example.complie.lsp.EditorSupport
import org.example.complie.native.CBackend
import org.example.complie.sema.SemanticModel
import org.example.complie.util.input
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
            "build", "compile" -> wrap { cmdBuild(argv.drop(1)) }
            "check" -> wrap { cmdCheck(argv.drop(1)) }
            "complete" -> wrap { cmdComplete(argv.drop(1)) }
            "lsp" -> wrap { cmdLsp() }
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

    /** 编译成 .class。 */
    private fun cmdBuild(rest: List<String>): Int {
        var outDir = File("out")
        var className: String? = null
        var dump = false
        var cMode = false
        val positional = ArrayList<String>()

        var i = 0
        while (i < rest.size) {
            when (val a = rest[i]) {
                "-o", "--out" -> {
                    val v = rest.getOrNull(i + 1) ?: throw RsfError("cli", "-o 后面要跟输出目录")
                    outDir = File(v)
                    i++
                }
                "-n", "--name" -> {
                    className = rest.getOrNull(i + 1) ?: throw RsfError("cli", "-n 后面要跟类名")
                    i++
                }
                "--dump" -> dump = true
                "--c" -> cMode = true
                else -> positional.add(a)
            }
            i++
        }

        val file = requireFile(
            positional.firstOrNull(),
            "build <file.rsf> [-o <dir>] [-n <类名>] [--dump] [--c]",
        )
        val loaded = Rsf.load(file)
        val source = loaded.root
        val model = Rsf.analyze(loaded)
        val name = className ?: Rsf.deriveClassName(source)

        if (cMode) {
            for (warning in model.warnings) System.err.println("[rsf] 警告: $warning")
            val text = CBackend(model).emit()
            outDir.mkdirs()
            val out = File(outDir, "$name.c")
            out.writeText(text, Charsets.UTF_8)
            println("[rsf] 已生成 ${out.path}")
            println("[rsf] 编译: gcc ${out.path} -o ${File(outDir, "$name.exe").path}")
            return 0
        }

        val result = Rsf.build(model, source, name, outDir)
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
            |  pub fn add(a : int, b : int) : int { rtn a + b }
            |  pub fn parse(s : string) -> !int { ... }   可能失败：出错走异常，调用方要 try / ?: / !!
            |      访问控制写在最左，省略就是 pub；-> : 两种返回写法等价（'::' 不再接受）
            |      参数两种风格都能写、可以混：int a / a : int；参数按 C 惯例默认可变
            |
            |语句
            |  var x = 1                             声明可变变量（块作用域，出了块就不可见）
            |  var n : int = 0                       带类型标注（类型写在名字后面）
            |  imutvar y = 2                           声明不可变变量（重新赋值会报错）
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
            |  try { ... } catch (e : string) { ... } 接住 fail 抛出来的错误（也接受 catch (e string)）
            |  loop:   /  goto loop                    C 风格跳转（标签是函数作用域）
            |  { ... }                                 块，引入新作用域
            |
            |类型: void int long float double string bool byte char
            |访问控制: pub prot prv（都不写 = pub）
            |REPL 命令: :funcs :reset :quit
            """.trimMargin(),
        )
    }

    // ------------------------------------------------------------------ 工具

    /** `<ControlType> <static> fun <MainType> 名字(参数表) <返回子句>` 的可读形式。 */
    private fun signatureOf(fn: org.example.complie.ast.FuncDecl): String {
        val mainType = if (fn.mainType.name == "void") "" else " ${fn.mainType.name}"
        // 用 display()：TypeRef 的数组维度单独放在 arrayDepth 里，光取 name 会把 `int[]` 印成 `int`
        // （自举侧一直印的是 `int[]`，这里不对齐就会被 verify36 的 check 对拍抓到）
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
            |rainStarfox ${Rsf.VERSION} —— 玩具语言 .rsf 的编译器 / 解释器
            |
            |用法:
            |  rsf run   <file.rsf>                        解释执行，不生成 .class
            |  rsf build <file.rsf> [-o <dir>] [-n 类名]    编译成 .class（默认输出到 out/）
            |  rsf check <file.rsf>                        只做词法/语法/类型检查
            |  rsf lex   <file.rsf>                        打印 token 流
            |  rsf complete <file.rsf> --line L --col C   补全候选（编辑器用，也可自己看）
            |  rsf lsp                                     语言服务器（stdio 上的 LSP）
            |  rsf repl                                    交互式解释器（不带参数也是）
            |  rsf help | version
            |
            |例:
            |  rsf run   lang/main.rsf
            |  rsf build lang/main.rsf
            |  java -cp out main
            |  rsf build lang/main.rsf --dump              顺便打印生成的字节码
            """.trimMargin(),
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
        val LET_HEAD = Regex("^\\s*(?:var|imutvar)\\s+([A-Za-z_][A-Za-z0-9_]*)")

        /** 合成函数体里分隔「会话前缀」和「刚敲的这一句」的标签。 */
        const val BEGIN_LABEL = "__rsf_repl_begin__"
    }
}
