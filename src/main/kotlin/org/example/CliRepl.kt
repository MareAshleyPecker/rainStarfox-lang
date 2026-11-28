package org.example

// 交互式 repl：一行输入、括号没闭合时续行、出错时讲人话。

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

internal fun Cli.repl(): Int {
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

internal fun Cli.runSnippet(
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

internal fun Cli.listFuncs(decls: List<String>) {
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

internal fun Cli.replHelp() {
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

internal fun Cli.readLineOrNull(prompt: String): String? = try {
        input(prompt)
    } catch (_: NoSuchElementException) {
        null
    }

internal fun Cli.describeArithmetic(e: ArithmeticException): String =
        if (e.message?.contains("zero") == true) "除以零" else (e.message ?: "算术错误")

    /** 这一行是不是还没写完，需要继续读（见 repl 里的说明）。 */
internal fun Cli.needsMoreInput(text: String): Boolean =
        !bracesClosed(text) || (DECL_START.containsMatchIn(text) && !text.contains('{'))

    /** 大括号是否已经配对（用于 REPL 里判断输入是否完整）。 */
internal fun Cli.bracesClosed(src: String): Boolean {
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

// REPL 专用的三张表（原来在 Cli 的 companion 里，搬出来之后没人共享了）
private val DECL_START = Regex("^\\s*(public|private)?\\s*fn\\b")

/** 从语句里抠出变量名，让 REPL 能识别重复声明。 */
private val LET_HEAD = Regex("^\\s*(?:var|val)\\s+([A-Za-z_][A-Za-z0-9_]*)")

/** 合成函数体里分隔「会话前缀」和「刚敲的这一句」的标签。 */
private const val BEGIN_LABEL = "__rsf_repl_begin__"
