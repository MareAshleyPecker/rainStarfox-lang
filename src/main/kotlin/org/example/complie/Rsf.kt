package org.example.complie

import org.example.complie.ast.Program
import org.example.complie.codegen.BytecodeCompiler
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.interp.Interpreter
import org.example.complie.lexing.Lexer
import org.example.complie.lexing.Token
import org.example.complie.loading.LoadedProgram
import org.example.complie.loading.SourceLoader
import org.example.complie.parsing.Parser
import org.example.complie.sema.Checker
import org.example.complie.sema.SemanticModel
import java.io.File
import java.io.PrintStream

data class BuildResult(
    val className: String,
    val classFile: File,
    val warnings: List<String>,
    /**
     * 这一次写出去的**全部** .class：程序类在前（就是 [classFile]），
     * 每个用户自定义类按声明顺序跟在后面。
     */
    val classFiles: List<File> = emptyList(),
)

/**
 * 编译流水线门面：源码 → token → AST → 语义模型 → 解释执行 / 字节码。
 *
 * 两条后端共用同一份 [SemanticModel]，所以「解释跑出来的结果」和
 * 「编译成 .class 再跑出来的结果」在语义范围内是一致的（有测试守着）。
 */
object Rsf {

    const val VERSION = "0.9.0"

    private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    // ------------------------------------------------------------------ 前端

    fun tokens(source: SourceFile): List<Token> = Lexer(source).tokenize()

    fun parse(source: SourceFile): Program = Parser(tokens(source), source).parseProgram()

    fun analyze(source: SourceFile): SemanticModel = Checker(parse(source), source).check()

    fun analyze(program: Program, source: SourceFile): SemanticModel =
        Checker(program, source).check()

    // ------------------------------------------------------------------ 多文件

    /** 从文件加载，展开 import / module。[baseDir] 见 [SourceLoader.load]。 */
    fun load(file: File, baseDir: File? = null): LoadedProgram = SourceLoader().load(file, baseDir)

    /** 检查一个已经展开好的多文件程序。 */
    fun analyze(loaded: LoadedProgram): SemanticModel =
        Checker(
            loaded.toProgram(),
            loaded.root,
            loaded.sourceOfFunction,
            loaded.sourceOfClass,
            loaded.sourceOfEnum,
            loaded.sourceOfInterface,
            loaded.sourceOfAnnotation,
        ).check()

    // ------------------------------------------------------------------ 解释

    fun interpret(model: SemanticModel, out: PrintStream = System.out): Any? =
        Interpreter(model, out).runEntry()

    // ------------------------------------------------------------------ 编译

    /** 源文件主名即类名，这样 `main.rsf` 编译出 `main.class`，能直接 `java main`。 */
    fun deriveClassName(source: SourceFile): String {
        val raw = source.baseName
        if (!JAVA_IDENTIFIER.matches(raw)) {
            throw RsfError(
                "codegen",
                "文件名 '$raw' 不能直接当 Java 类名（只允许字母、数字、下划线，且不能以数字开头）。" +
                    "可以重命名文件，或者用 -n <类名> 指定。",
            )
        }
        return raw
    }

    fun build(
        model: SemanticModel,
        source: SourceFile,
        className: String,
        outDir: File,
    ): BuildResult {
        if (!JAVA_IDENTIFIER.matches(className)) {
            throw RsfError("codegen", "'$className' 不是合法的 Java 类名")
        }
        // 写了 `pkg a.b` 时，包名就是 JVM 的包：类文件落到 out/a/b/ 下，用 java a.b.<类名> 启动
        val internalName = model.packageName
            ?.takeIf { it.isNotEmpty() }
            ?.let { it.replace('.', '/') + "/" + className }
            ?: className
        val compiler = BytecodeCompiler(model, source.name)
        // 程序类 + 每个用户自定义类各一个 .class
        val classFiles = compiler.compile(internalName, outDir)
        return BuildResult(className, classFiles.first(), compiler.warnings.toList(), classFiles)
    }
}
