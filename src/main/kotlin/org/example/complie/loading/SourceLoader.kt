package org.example.complie.loading

import org.example.complie.ast.AnnotationDecl
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ClassImport
import org.example.complie.ast.EnumDecl
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.ImportDecl
import org.example.complie.ast.InterfaceDecl
import org.example.complie.ast.ModuleImport
import org.example.complie.ast.Program
import org.example.complie.ast.SourceImport
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.lexing.Lexer
import org.example.complie.parsing.Parser
import java.io.File
import java.util.IdentityHashMap

/**
 * 展开 import / module 之后的结果。
 *
 * rsf 的模块是**包含式**的：所有被导入的文件里的函数都并进同一个程序，
 * 最终编译成一个类（类名取根文件名）。这和 Go 的「同一包多个文件」是一回事，
 * 不是分别编译再链接。
 */
class LoadedProgram(
    val root: SourceFile,
    /** 参与编译的全部源文件（含根），顺序确定。 */
    val files: List<SourceFile>,
    val functions: List<FuncDecl>,
    val importDecls: List<ImportDecl>,
    /** 点分路径写的 JVM 类导入（`import java.lang.Math`）。 */
    val classImports: List<String>,
    /** 每个函数来自哪个文件——报错时才能指对文件。 */
    val sourceOfFunction: IdentityHashMap<FuncDecl, SourceFile>,
    /** 用户自定义类（阶段 12）。 */
    val classes: List<ClassDecl> = emptyList(),
    /** 每个类来自哪个文件——可见性报错时要用。 */
    val sourceOfClass: IdentityHashMap<ClassDecl, SourceFile> = IdentityHashMap(),
    /** 枚举声明（阶段 19c）。和类共用类型命名空间。 */
    val enums: List<EnumDecl> = emptyList(),
    /** 每个枚举来自哪个文件——重名报错时要用（和 [sourceOfClass] 同一个理由）。 */
    val sourceOfEnum: IdentityHashMap<EnumDecl, SourceFile> = IdentityHashMap(),
    /** 用户自定义接口（阶段 20）。和类、枚举共用类型命名空间。 */
    val interfaces: List<InterfaceDecl> = emptyList(),
    /** 每个接口来自哪个文件——接口相关的报错要指对文件（和 [sourceOfClass] 同一个理由）。 */
    val sourceOfInterface: IdentityHashMap<InterfaceDecl, SourceFile> = IdentityHashMap(),
    /** 注解声明（阶段 21）。和类、枚举、接口共用类型命名空间。 */
    val annotations: List<AnnotationDecl> = emptyList(),
    /** 每个注解来自哪个文件——注解相关的报错要指对文件（和 [sourceOfClass] 同一个理由）。 */
    val sourceOfAnnotation: IdentityHashMap<AnnotationDecl, SourceFile> = IdentityHashMap(),
    /** 根文件的包名（`pkg a.b`）；没写就是 null。生成 .class 时它就是 JVM 的包。 */
    val packageName: String? = null,
) {
    /** 合成一个 Program 交给 Checker。 */
    fun toProgram(): Program =
        Program(importDecls, functions, classes, packageName, enums, interfaces, annotations)
}

/**
 * 按 import / module 把源码树展开。
 *
 * 路径解析：相对于**发起 import 的那个文件所在目录**；找不到再相对当前工作目录试一次。
 * 同一个文件只会被加载一次（相当于 C 的 include guard），所以互相 import 不会死循环。
 */
class SourceLoader {

    private val loadedPaths = LinkedHashSet<String>()
    private val files = ArrayList<SourceFile>()
    private val functions = ArrayList<FuncDecl>()
    private val importDecls = ArrayList<ImportDecl>()
    private val classImports = LinkedHashSet<String>()
    private val sourceOfFunction = IdentityHashMap<FuncDecl, SourceFile>()
    private val classes = ArrayList<ClassDecl>()
    private val sourceOfClass = IdentityHashMap<ClassDecl, SourceFile>()
    private val enums = ArrayList<EnumDecl>()
    private val sourceOfEnum = IdentityHashMap<EnumDecl, SourceFile>()
    private val interfaces = ArrayList<InterfaceDecl>()
    private val sourceOfInterface = IdentityHashMap<InterfaceDecl, SourceFile>()
    private val annotations = ArrayList<AnnotationDecl>()
    private val sourceOfAnnotation = IdentityHashMap<AnnotationDecl, SourceFile>()

    /** 根文件所在目录：相对路径的最后一个搜索基准。 */
    private var rootDir: File? = null

    /** 根文件的包名（没写 pkg 就是 null）。 */
    private var rootPackage: String? = null

    /**
     * [baseDir] 给根文件用：编辑器会把「未保存的缓冲区」写成临时文件再拿来分析，
     * 此时它真实的 import 相对目录得从外面指定（临时文件所在目录没有意义）。
     * 被导入的文件仍然按各自的目录解析。
     */
    fun load(rootFile: File, baseDir: File? = null): LoadedProgram {
        val root = SourceFile.of(rootFile.canonicalFile)
        rootDir = baseDir?.canonicalFile ?: rootFile.canonicalFile.parentFile
        loadFile(rootFile.canonicalFile, root, baseDir?.canonicalFile)
        return LoadedProgram(
            root = root,
            files = files.toList(),
            functions = functions.toList(),
            importDecls = importDecls.toList(),
            classImports = classImports.toList(),
            sourceOfFunction = sourceOfFunction,
            classes = classes.toList(),
            sourceOfClass = sourceOfClass,
            enums = enums.toList(),
            sourceOfEnum = sourceOfEnum,
            interfaces = interfaces.toList(),
            sourceOfInterface = sourceOfInterface,
            annotations = annotations.toList(),
            sourceOfAnnotation = sourceOfAnnotation,
            packageName = rootPackage,
        )
    }

    // ------------------------------------------------------------------ 内部

    private fun loadFile(file: File, preRead: SourceFile? = null, baseOverride: File? = null) {
        val key = file.canonicalPath
        if (!loadedPaths.add(key)) return // 已经加载过（含互相 import 的情况）

        val source = preRead ?: SourceFile.of(file)
        files.add(source)

        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens, source).parseProgram()

        // 包声明：校验「文件真的在那个目录下」，根文件的包名留给生成 .class 用
        program.packageName?.let { pkg ->
            checkPackage(pkg, file, source)
            if (files.first() === source) rootPackage = pkg
        }

        for (fn in program.functions) {
            functions.add(fn)
            sourceOfFunction[fn] = source
        }

        for (cls in program.classes) {
            classes.add(cls)
            sourceOfClass[cls] = source
        }

        for (en in program.enums) {
            enums.add(en)
            sourceOfEnum[en] = source
        }

        for (iface in program.interfaces) {
            interfaces.add(iface)
            sourceOfInterface[iface] = source
        }

        // 阶段 21：注解声明也要一路带到 Checker——漏掉这里的话 `rsf check` 会
        // 悄悄把注解当没写过（M1 只做了语法，这条通路是 M2 补上的）
        for (ann in program.annotations) {
            annotations.add(ann)
            sourceOfAnnotation[ann] = source
        }

        val dir = baseOverride ?: file.parentFile ?: File(".")
        for (imp in program.imports) {
            importDecls.add(imp)
            when (imp) {
                is ClassImport -> classImports.add(imp.path)

                is SourceImport -> loadFile(resolvePath(imp.path, dir, imp), null)

                is ModuleImport -> {
                    val moduleDir = resolveDir(imp.path, dir, imp)
                    val entries = moduleDir.listFiles { f -> f.isFile && f.name.endsWith(".rsf") }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    if (entries.isEmpty()) {
                        throw RsfError(
                            "loader",
                            "module \"${imp.path}\" 里没有 .rsf 文件",
                            imp.at.line, imp.at.column, source,
                        )
                    }
                    for (entry in entries) loadFile(entry.canonicalFile, null)
                }
            }
        }
    }

    private fun resolvePath(path: String, fromDir: File, imp: ImportDecl): File {
        val candidates = searchBases(path, fromDir)
        val hit = candidates.firstOrNull { it.isFile }
        if (hit == null) {
            throw RsfError(
                "loader",
                "找不到被 import 的文件 '$path'。找过这些位置：\n" +
                    candidates.joinToString("\n") { "      " + it.path },
                imp.at.line, imp.at.column, files.last(),
            )
        }
        return hit.canonicalFile
    }

    private fun resolveDir(path: String, fromDir: File, imp: ModuleImport): File {
        val candidates = searchBases(path, fromDir)
        val hit = candidates.firstOrNull { it.isDirectory }
        if (hit == null) {
            throw RsfError(
                "loader",
                "找不到 module 目录 '$path'。找过这些位置：\n" +
                    candidates.joinToString("\n") { "      " + it.path },
                imp.at.line, imp.at.column, files.last(),
            )
        }
        return hit.canonicalFile
    }

    /**
     * 一个相对路径要在哪些地方找。
     *
     * 顺序：**发起 import 的那个文件所在目录** → 当前目录 → 根文件所在目录。
     * 路径里的 `\`、`/` 都认（Windows 写法直接粘过来就能用）。
     */
    private fun searchBases(path: String, fromDir: File): List<File> {
        val cleaned = normalizeSeparators(path)
        val bases = LinkedHashSet<File>()
        bases.add(fromDir)
        bases.add(File("."))
        rootDir?.let { bases.add(it) }
        return bases.map { File(it, cleaned) }
    }

    /**
     * 路径归一化：`\` → `/`，并去掉开头的 `./`（`../` 保留，它就是往上走的意思）。
     *
     * 不碰盘符（`C:\x` 这种绝对路径原样交给 File 处理）。
     */
    private fun normalizeSeparators(path: String): String {
        val slashed = path.replace('\\', '/')
        return when {
            slashed.startsWith("./") -> slashed.removePrefix("./")
            slashed.startsWith(".\\") -> slashed.removePrefix(".\\")
            else -> slashed
        }
    }

    /**
     * 包与目录的一致性检查。
     *
     * `pkg a.b` 的意思是「这个文件在 ./a/b 下」（以当前目录为准）。写错了在这儿就报，
     * 而不是等到生成 .class 时才在别的地方出怪事。
     */
    private fun checkPackage(pkg: String, file: File, source: SourceFile) {
        val expected = pkg.replace('.', '/')
        val actual = file.parentFile?.path?.replace('\\', '/') ?: ""
        if (actual == expected || actual.endsWith("/$expected") || actual == "." && expected.isEmpty()) return
        throw RsfError(
            "loader",
            "包声明和文件位置对不上：'pkg $pkg' 要求文件在 '$expected/' 下（以当前目录为准），" +
                "但它在 '$actual' 下",
            1, 1, source,
        )
    }
}
