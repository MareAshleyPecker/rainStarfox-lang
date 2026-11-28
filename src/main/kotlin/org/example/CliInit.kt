package org.example

// rsf init：生成项目骨架（build.rsfs + build/ 规则模块 + src/main.rsf）。

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

    /**
     * 建一个项目骨架：`rsf init [目录]`。
     *
     * 生成的 `build.rsfs` **必须有 main**（构建脚本的入口），但规则可以拆成模块
     * （`build/` 下的 .rsfs 用 import 拼起来）——和 gradle 的 settings/build 分层一个意思，
     * 自举出 toolchain 之后这套骨架照样能用。
     */
internal fun Cli.cmdInit(rest: List<String>): Int {
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
            |// 要产出什么（声明顺序就是构建顺序）。
            |// 动词：exe 可执行文件 / lib 目标文件 / jar 可执行包 / copy 原样复制。
            |target {
            |    exe("@/src/main.rsf")
            |}
            |
            |// 产物设置（只认项目根的 build.rsfs）。命令行给的 -o / --c / --meta 压过这里。
            |#build {
            |    out("build/out")
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
            |    // 也可以在这里动态加目标，顺序照样是声明顺序：
            |    // target("@/src/tool.rsf", "jar")
            |    // 要改顺序就写 need("a", "b")（先 a 后 b）。
            |    // 要跑外部命令写 exec("命令")：返回退出码，非零由你自己判断。
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
