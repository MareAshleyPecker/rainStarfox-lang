package org.example.complie.loading

import org.example.complie.ast.*
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.directives.BuildSettings
import org.example.complie.directives.DepEntry
import org.example.complie.directives.DepKind
import org.example.build.ImpexKind
import org.example.build.ModuleImpex
import org.example.build.PkgInfo
import org.example.complie.directives.DirectiveRule
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
    /** 阶段 60：本次构建目标。 */
    val target: BuildTarget = BuildTarget.JVM,
    /** 阶段 60：被过滤掉的名字 → 它属于哪一侧。 */
    val otherSide: Map<String, BuildTarget> = emptyMap(),
    /**
     * 阶段 77：文件头的 `gc on` / `gc off`，只取自**带 main 的那个文件**。
     *
     * `gc on` = 产物带上内置的标记-清扫 GC（非移动、保守根、按需扫）；`gc off` / 不写 =
     * 走 arena（只增不减）。写在别的文件里会报错（见 [SourceLoader.resolveGc]）。
     */
    val gc: GcDirective? = null,
    /**
     * 阶段 82：参与编译的文件里 `#dep { … }` 声明的依赖（去重、保持出现顺序）。
     *
     * 只是**声明**：`.rsfdeps/lock.json` 与存在性校验由构建驱动（`ProjectBuildHost`）做，
     * 编译器本身不下载也不改 classpath。
     */
    val deps: List<DepEntry> = emptyList(),
    /**
     * 阶段 85：所有文件里的全局变量（`const var` / `const val`），保持「文件被载入的顺序」。
     *
     * 全局变量是**整程序一份**：位置自由（写在哪一层、哪个文件都行），所以这里合并成一张表；
     * 声明顺序决定初值的执行顺序（前向引用在 Checker 里报「未声明的变量」）。
     */
    val globals: List<GlobalVarDecl> = emptyList(),
    /** 阶段 82：`build.rsfs` 里的 `#build { … }`；别的文件写了会报错。 */
    val build: BuildSettings? = null,
    /**
     * 阶段 83：所有参与编译的文件里 `#def` 声明的构建规则（保持载入顺序）。
     *
     * `#dep rule("名字")` 就按这张表认名字；规则体本身已经是普通函数（解糖过了），
     * 所以「规则在哪份文件里」不影响调用——只要那份文件被 import 进来。
     */
    val rules: List<DirectiveRule> = emptyList(),
    /**
     * 阶段 84：顶层配置块（`project { … }`）。**只能写在项目根的 build.rsfs 里**
     * （装载时校验），求值在 globals 之后、规则与 main 之前。
     */
    val configs: List<ConfigBlock> = emptyList(),
) {
    /** 合成一个 Program 交给 Checker。 */
    fun toProgram(): Program =
        Program(
            imports = importDecls,
            functions = functions,
            classes = classes,
            packageName = packageName,
            target = target,
            otherSide = otherSide,
            enums = enums,
            interfaces = interfaces,
            annotations = annotations,
            gc = gc,
            globals = globals,
            configs = configs,
        )
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

    /** 阶段 60：这一趟构建的目标侧；文件级 / 声明级标记与它不符的就丢。 */
    private var target: BuildTarget = BuildTarget.JVM

    /** 阶段 60：被过滤掉的名字 → 它属于哪一侧（给 Checker 报错用）。 */
    private val otherSide = LinkedHashMap<String, BuildTarget>()

    /** 阶段 77：收集到的 gc 指令 + 它写在哪个文件里，最后由 [resolveGc] 挑一份。 */
    private val gcDecls = ArrayList<Pair<GcDirective, SourceFile>>()

    /** 阶段 82：各文件 `#dep` 声明的依赖（`java`/`c`/`h`/`rule`），按出现顺序去重。 */
    private val deps = LinkedHashSet<DepEntry>()
    private val rules = ArrayList<DirectiveRule>()
    /** 阶段 85：各文件的全局变量，按载入顺序合并（初值就按这个顺序跑）。 */
    private val globals = ArrayList<GlobalVarDecl>()
    private val configs = ArrayList<ConfigBlock>()

    /** 目录 → 它的 pkg-info.rsf 包名（null 表示这个目录没有/没写 pkg）。 */
    private val dirPackageCache = HashMap<File, String?>()

    /** 项目根的 module-impex.rsf 清单（没有就 null）；键是规范化后的绝对路径。 */
    private var manifestCache: Map<String, File>? = null
    private var manifestRoot: File? = null

    /**
     * 阶段 84：清单门禁。项目根有 `module-impex.rsf` 时，跨目录 import 的目标
     * 必须在清单里出现过（export / exportjvm / extern / import 任一）——
     * **同目录的文件不受限制**（包内互见，类比 Java 的 package-private）。
     *
     * 构建脚本（build.rsfs）不受这条限制：它是驱动者，不是模块成员。
     */
    private fun gateImport(importer: SourceFile, target: File, imp: ImportDecl) {
        val importerFile = File(importer.path).absoluteFile
        if (importerFile.name == "build.rsfs") return
        // 项目根按「哪个祖先目录有 module-impex.rsf」算——清单本身就是模块的边界，
        // 不要求旁边一定还有 build.rsfs（单模块仓库可以只有清单）。
        val root = findManifestRoot(importerFile.parentFile ?: importerFile) ?: return
        val manifestFile = File(root, ModuleImpex.FILE_NAME)
        if (!manifestFile.isFile) return
        // 同目录：包内互见
        if (target.parentFile?.canonicalFile == importerFile.parentFile?.canonicalFile) return
        val listed = manifestEntries(root, manifestFile)
        val canonical = target.canonicalFile.path
        if (listed.containsKey(canonical)) return
        // 包形态：目标文件在清单声明的包目录下，就算对外提供（`export pkg std`）
        for (dir in packageDirs(root, manifestFile)) {
            if (canonical.startsWith(dir.canonicalFile.path + File.separator)) return
        }
        throw RsfError(
            "loader",
            "'${target.name}' 没有出现在 ${ModuleImpex.FILE_NAME} 里：" +
                "跨目录用它，要么在清单里加 export \"…\"（对外可见），" +
                "要么加 import \"…\"（只在本模块内部用）。同目录的文件不受这条限制",
            imp.at.line, imp.at.column, importer,
        )
    }

    /**
     * `import std.Vec` / `import std.*` / `import std`：把一个包里的源码拉进来。
     *
     * `*` 或包名本身 = 整个目录；否则按最后一段找同名 `.rsf`（Java 的「一个类一个文件」）。
     */
    private fun loadPackageImport(imp: ClassImport, pkg: String, member: String, dir: File) {
        if (!dir.isDirectory) {
            throw RsfError(
                "loader",
                "包 '$pkg'（对应目录 ${dir.path}）不存在：module-impex.rsf 里的包名要和 stdlib/ 下的目录一致",
                imp.at.line, imp.at.column, null,
            )
        }
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".rsf") && f.name != PkgInfo.FILE_NAME && f.name != ModuleImpex.FILE_NAME
        }?.sortedBy { it.name }.orEmpty()
        val picked = when {
            member.isEmpty() || member == "*" -> files
            else -> {
                val name = member.substringAfterLast('.')
                val hit = files.firstOrNull { it.nameWithoutExtension == name }
                    ?: throw RsfError(
                        "loader",
                        "包 '$pkg' 里没有 '$member'。可用的：${files.joinToString("、") { it.nameWithoutExtension }}",
                        imp.at.line, imp.at.column, null,
                    )
                listOf(hit)
            }
        }
        if (picked.isEmpty()) {
            throw RsfError("loader", "包 '$pkg' 里没有 .rsf 文件", imp.at.line, imp.at.column, null)
        }
        for (f in picked) loadFile(f.canonicalFile, null)
    }

    /**
     * `import std.Vec` / `import std.*` / `import std`：从别名指向的目录里取源码。
     *
     * 白名单（`module "stdlib/std" to std { Vec Text }`）在这里生效：
     * 不在表里的成员直接报错并列出可用的，而不是让它掉进 JVM 类的报错。
     */
    private fun loadModuleMember(
        imp: ClassImport,
        alias: String,
        member: String,
        dir: File,
        table: ModuleTable,
    ) {
        if (!dir.isDirectory) {
            throw RsfError(
                "loader",
                "模块 '$alias' 的目录不存在：${dir.path}（module 清单里写的是相对项目根的路径）",
                imp.at.line, imp.at.column, null,
            )
        }
        val allowed = table.members[alias]
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".rsf") && f.name != PkgInfo.FILE_NAME && f.name != ModuleImpex.FILE_NAME
        }?.sortedBy { it.name }.orEmpty()
        val visible = if (allowed == null) files else files.filter { it.nameWithoutExtension in allowed }
        val picked = when {
            member.isEmpty() || member == "*" -> visible
            else -> {
                val name = member.substringAfterLast('.')
                if (allowed != null && name !in allowed) {
                    throw RsfError(
                        "loader",
                        "模块 '$alias' 没有开放 '$name'。开放的成员：${allowed.joinToString("、")}",
                        imp.at.line, imp.at.column, null,
                    )
                }
                val hit = visible.firstOrNull { it.nameWithoutExtension == name }
                    ?: throw RsfError(
                        "loader",
                        "模块 '$alias' 里没有 '$name'。可用的：${visible.joinToString("、") { it.nameWithoutExtension }}",
                        imp.at.line, imp.at.column, null,
                    )
                listOf(hit)
            }
        }
        if (picked.isEmpty()) {
            throw RsfError("loader", "模块 '$alias' 里没有可导入的 .rsf 文件", imp.at.line, imp.at.column, null)
        }
        for (f in picked) loadFile(f.canonicalFile, null)
    }

    private fun findManifestRoot(from: File): File? {
        var d: File? = from.absoluteFile
        while (d != null) {
            if (File(d, ModuleImpex.FILE_NAME).isFile) return d
            d = d.parentFile
        }
        return null
    }

    /**
     * 包的解析表：**项目自己的清单** + **工具链的清单**（标准库跟着编译器走）。
     *
     * 项目清单优先（同名包先看项目里的），没有项目清单时只用工具链的。
     * 这就是为什么用户项目里不写 `module-impex.rsf` 也能 `import std.Vec`。
     */
    private fun packageTableFor(file: File): PackageTable? {
        val projectRoot = findManifestRoot(file.parentFile ?: file)
        val merged = LinkedHashMap<String, File>()
        val exportedAll = LinkedHashSet<String>()
        if (projectRoot != null) {
            val t = packagesOf(projectRoot, File(projectRoot, ModuleImpex.FILE_NAME))
            merged.putAll(t.dirs)
            exportedAll.addAll(t.exported)
        }
        val toolchainRoot = org.example.build.Stdlib.dir().parentFile
        if (toolchainRoot != null) {
            val f = File(toolchainRoot, ModuleImpex.FILE_NAME)
            if (f.isFile) {
                val t = packagesOf(toolchainRoot, f)
                for ((k, v) in t.dirs) merged.putIfAbsent(k, v)
                exportedAll.addAll(t.exported)
            }
        }
        if (merged.isEmpty()) return null
        return PackageTable(merged, exportedAll)
    }

    /**
     * 模块别名表：`module "stdlib/std" to std` → `std` 指向 `<根>/stdlib/std`。
     *
     * 源码里的 `import std.Vec` 先查这张表（命中就是 rsf 的包成员），
     * 没命中才按 JVM 类解析——歧义因此只可能来自"别名和 JDK 包重名"，
     * 而别名是你在清单里亲手起的。
     */
    private class ModuleTable(
        val dirs: Map<String, File>,
        val members: Map<String, Set<String>?>,
        val internal: Set<String>,
    )

    private var moduleTable: ModuleTable? = null
    private var moduleTableRoot: File? = null

    private fun modulesOf(root: File, manifestFile: File): ModuleTable {
        moduleTable?.let { if (moduleTableRoot == root) return it }
        val dirs = LinkedHashMap<String, File>()
        val members = LinkedHashMap<String, Set<String>?>()
        val internal = LinkedHashSet<String>()
        for (e in ModuleImpex.parse(manifestFile)) {
            if (e.kind != ImpexKind.MODULE_DECL) continue
            val alias = e.alias ?: continue
            dirs[alias] = File(root, e.path)
            members[alias] = e.members
            if (e.internal) internal.add(alias)
        }
        val table = ModuleTable(dirs, members, internal)
        moduleTable = table
        moduleTableRoot = root
        return table
    }

    /** 项目清单 + 工具链清单的别名表（项目优先）。 */
    private fun moduleTableFor(file: File): ModuleTable? {
        val dirs = LinkedHashMap<String, File>()
        val members = LinkedHashMap<String, Set<String>?>()
        val internal = LinkedHashSet<String>()
        findManifestRoot(file.parentFile ?: file)?.let { root ->
            val t = modulesOf(root, File(root, ModuleImpex.FILE_NAME))
            for ((k, v) in t.dirs) if (!dirs.containsKey(k)) { dirs[k] = v; members[k] = t.members[k] }
            internal.addAll(t.internal)
        }
        org.example.build.Stdlib.dir().parentFile?.let { root ->
            val f = File(root, ModuleImpex.FILE_NAME)
            if (f.isFile) {
                val t = modulesOf(root, f)
                for ((k, v) in t.dirs) if (!dirs.containsKey(k)) { dirs[k] = v; members[k] = t.members[k] }
                internal.addAll(t.internal)
            }
        }
        if (dirs.isEmpty()) return null
        return ModuleTable(dirs, members, internal)
    }

    /** 清单里声明的包：包名 → 目录（约定 `<根>/stdlib/<包名>`），以及哪些包对外导出。 */
    private class PackageTable(val dirs: Map<String, File>, val exported: Set<String>)

    private var packageTable: PackageTable? = null
    private var packageTableRoot: File? = null

    private fun packagesOf(root: File, manifestFile: File): PackageTable {
        packageTable?.let { if (packageTableRoot == root) return it }
        val dirs = LinkedHashMap<String, File>()
        val exported = LinkedHashSet<String>()
        for (e in ModuleImpex.parse(manifestFile)) {
            if (e.kind != ImpexKind.PACKAGE_EXPORT && e.kind != ImpexKind.PACKAGE_IMPORT) continue
            dirs[e.path] = File(root, "stdlib/${e.path}")
            if (e.kind == ImpexKind.PACKAGE_EXPORT) exported.add(e.path)
        }
        val table = PackageTable(dirs, exported)
        packageTable = table
        packageTableRoot = root
        return table
    }

    /** 清单里声明的包目录（`export pkg std` → `<root>/stdlib/std`）。 */
    private fun packageDirs(root: File, manifestFile: File): List<File> =
        ModuleImpex.parse(manifestFile)
            .filter { it.kind == ImpexKind.PACKAGE_EXPORT || it.kind == ImpexKind.PACKAGE_IMPORT }
            .map { File(root, "stdlib/${it.path}") }
            .filter { it.isDirectory }

    private fun manifestEntries(root: File, manifestFile: File): Map<String, File> {
        manifestCache?.let { if (manifestRoot == root) return it }
        val map = LinkedHashMap<String, File>()
        for (e in ModuleImpex.parse(manifestFile)) {
            val f = if (File(e.path).isAbsolute) File(e.path) else File(root, e.path)
            map[f.canonicalFile.path] = f
        }
        manifestCache = map
        manifestRoot = root
        return map
    }

    private fun pkgOfDir(dir: File?): String? {
        if (dir == null) return null
        return dirPackageCache.getOrPut(dir) {
            runCatching { org.example.build.PkgInfo.readIn(dir)?.packageName }.getOrNull()
        }
    }

    /** 阶段 82：`build.rsfs` 的 `#build` 设置（`Directives.parse` 已经保证只有它能写）。 */
    private var build: BuildSettings? = null

    /**
     * [baseDir] 给根文件用：编辑器会把「未保存的缓冲区」写成临时文件再拿来分析，
     * 此时它真实的 import 相对目录得从外面指定（临时文件所在目录没有意义）。
     * 被导入的文件仍然按各自的目录解析。
     */
    fun load(
        rootFile: File,
        baseDir: File? = null,
        text: String? = null,
        target: BuildTarget = BuildTarget.JVM,
        script: Boolean? = null,
    ): LoadedProgram {
        this.target = target
        // text 不为空 = 「盘上那份不是正文」：编辑器里有未保存的缓冲区。
        // 路径必须用**真实文件**的（包声明校验、import 相对路径都按它算），
        // 否则 `pkg testbag` 会因为临时目录在 Temp 下而误报。
        // script 显式给 true 时按脚本解析（`rsf lsp --script` 编辑的文件不一定叫 .rsfs）。
        val root = if (text != null) {
            SourceFile.of(rootFile.canonicalFile.path, text, if (script == true) true else null)
        } else {
            SourceFile.of(rootFile.canonicalFile, if (script == true) true else null)
        }
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
            target = target,
            otherSide = otherSide.toMap(),
            gc = resolveGc(root),
            deps = deps.toList(),
            build = build,
            rules = rules.toList(),
            configs = configs.toList(),
            globals = globals.toList(),
        )
    }

    /**
     * 阶段 77：`gc` 只认**带 main 的那个文件**（`--lib` 没有 main 时认根文件）。
     *
     * 用户 2026-11-24 拍板的口径：GC 是「整份产物带不带运行时」的开关，写在哪份被 import
     * 的文件里都会生效会让人猜不透；强制写在有 main 的那份里，读代码的人一眼就在入口处看到。
     * 别的文件写了不是「默默忽略」——直接报错并指出去哪写。
     */
    private fun resolveGc(root: SourceFile): GcDirective? {
        if (gcDecls.isEmpty()) return null
        val entry = functions.firstOrNull { it.name == "main" }
        val home = entry?.let { sourceOfFunction[it] } ?: root
        val stray = gcDecls.firstOrNull { it.second !== home } ?: return gcDecls.first().first
        throw RsfError(
            "gc",
            "${stray.second.name}:${stray.first.at.line}: 'gc' 只能写在带 main 的文件里（${home.name}）；" +
                "GC 是整份产物的开关，写在被 import 的文件里会让人猜不到最终用不用",
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

        // 阶段 60：文件级 target 与本次构建目标不符 → 整份文件不进编译，
        // 但名字要记下来，调用点才能报「只写在 native 侧」。
        if (program.target != BuildTarget.BOTH && program.target != target) {
            val other = program.target
            for (fn in program.functions) otherSide[fn.name] = other
            for (cls in program.classes) otherSide[cls.name] = other
            for (en in program.enums) otherSide[en.name] = other
            for (iface in program.interfaces) otherSide[iface.name] = other
            return
        }

        // 包声明：校验「文件真的在那个目录下」，根文件的包名留给生成 .class 用。
        // 阶段 84：文件自己没写 pkg 时，继承同目录 pkg-info.rsf 里的包名；
        // 两边都写就必须一致（两份声明说不同的包，没法猜哪份算数）。
        val dirPkg = pkgOfDir(file.parentFile)
        val declared = program.packageName
        if (declared != null) {
            if (dirPkg != null && dirPkg != declared) {
                throw RsfError(
                    "loader",
                    "这个文件的 'pkg $declared' 和同目录 pkg-info.rsf 的 'pkg $dirPkg' 不一致：" +
                        "包名只该在一处声明（写了 pkg-info.rsf 就从文件里删掉 pkg）",
                    1, 1, source,
                )
            }
            checkPackage(declared, file, source)
            if (files.first() === source) rootPackage = declared
        } else if (dirPkg != null) {
            checkPackage(dirPkg, file, source)
            if (files.first() === source) rootPackage = dirPkg
        }

        // 阶段 77：先记下来，等全部文件读完再挑（要知道 main 在哪份文件里）。
        // 放在 target 过滤之后——不参与编译的文件不算数。
        program.gc?.let { gcDecls.add(it to source) }

        // 阶段 82：`#dep` 相加、`#build` 只认 build.rsfs（Directives.parse 已保证唯一）。
        source.directives.deps.forEach { deps.add(it) }
        source.directives.build?.let { if (build == null) build = it }
        rules.addAll(source.directives.rules)
        // 阶段 84：配置块。它描述「整份构件」，和 #build 一样只认项目根的 build.rsfs。
        for (cfg in program.configs) {
            if (source.name != "build.rsfs") {
                throw RsfError(
                    "loader",
                    "配置块 ${cfg.name} { … } 只能写在项目根目录的 build.rsfs 里：" +
                        "它描述的是整份构件的设置，写进普通源文件会让人猜不到最终用哪套",
                    cfg.at.line, cfg.at.column, source,
                )
            }
            configs.add(cfg)
        }
        // 阶段 85：合并全局变量（整程序一份；顺序 = 载入顺序，初值就按这个顺序跑）。
        for (g in program.globals) globals.add(g)

        for (fn in program.functions) {
            // 阶段 60b：JVM 构建里 native 函数**保留声明**（编成 ACC_NATIVE 方法，由 JNI 桥实现），
            // 所以不再丢进 otherSide；C 构建里反过来，jvm 函数直接丢。
            if (fn.target == BuildTarget.JVM && target == BuildTarget.NATIVE) {
                otherSide[fn.name] = fn.target
                continue
            }
            functions.add(fn)
            sourceOfFunction[fn] = source
        }

        for (cls in program.classes) {
            if (cls.target != BuildTarget.BOTH && cls.target != target) {
                otherSide[cls.name] = cls.target
                continue
            }
            classes.add(cls)
            sourceOfClass[cls] = source
        }

        for (en in program.enums) {
            enums.add(en)
            sourceOfEnum[en] = source
        }

        for (iface in program.interfaces) {
            if (iface.target != BuildTarget.BOTH && iface.target != target) {
                otherSide[iface.name] = iface.target
                continue
            }
            interfaces.add(iface)
            sourceOfInterface[iface] = source
        }

        // 阶段 21：注解声明也要一路带到 Checker——漏掉这里的话 `rsf check` 会
        // 悄悄把注解当没写过（M1 只做了语法，这条通路是 M2 补上的）
        for (ann in program.annotations) {
            if (ann.target != BuildTarget.BOTH && ann.target != target) {
                otherSide[ann.name] = ann.target
                continue
            }
            annotations.add(ann)
            sourceOfAnnotation[ann] = source
        }

        val dir = baseOverride ?: file.parentFile ?: File(".")
        for (imp in program.imports) {
            importDecls.add(imp)
            when (imp) {
                is ClassImport -> {
                    // 先查模块别名表：`import std.Vec` 命中 `module "…" to std` 就是 rsf 的包；
                    // 没命中才按 JVM 类解析（`import java.lang.Math` 照旧）。
                    val table = moduleTableFor(file)
                    val head = imp.path.substringBefore('.')
                    val dir = table?.dirs?.get(head)
                    if (dir != null) {
                        loadModuleMember(imp, head, imp.path.removePrefix(head).trimStart('.'), dir, table)
                        importDecls.remove(imp)
                    } else {
                        classImports.add(imp.path)
                    }
                }

                is SourceImport -> {
                    val resolved = resolvePath(imp.path, dir, imp)
                    gateImport(source, resolved, imp)
                    // 阶段 84：`import "native/util.c"` / `"util.h"` 不是 rsf 源码，
                    // 而是 C 依赖——`.c` 进链接（等于 cInclude）、`.h` 只进 `-I`（等于 hInclude）。
                    // 这是**唯一**按扩展名分流的地方：别的扩展名照旧当源码。
                    when (resolved.extension.lowercase()) {
                        "c" -> deps.add(DepEntry(DepKind.C, depPath(resolved)))
                        "h" -> deps.add(DepEntry(DepKind.H, depPath(resolved)))
                        else -> loadFile(resolved, null)
                    }
                }

                is ModuleImport -> {
                    val moduleDir = resolveDir(imp.path, dir, imp)
                    val entries = moduleDir.listFiles { f ->
                        f.isFile && (f.name.endsWith(".rsf") || f.name.endsWith(".rsfs")) &&
                            // 两个特殊文件是元信息/清单，不是源码
                            f.name != PkgInfo.FILE_NAME && f.name != ModuleImpex.FILE_NAME
                    }
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

    /**
     * `@/xxx`：相对**项目根**（最近的、含 build.rsfs 的祖先目录）。
     *
     * 这样跨层引用不用写 `../../..`，层级一变也不用改：`@/rsfSrc/core/Vec.rsf`。
     * 工程里没有 build.rsfs 时退回本次加载的根目录。
     */
    /** 从 [from] 往上找带 `build.rsfs` 的目录；没有项目就返回 null（`@/` 那种情况要求必须有）。 */
    private fun findProjectRoot(from: File): File? {
        var d: File? = from.absoluteFile
        while (d != null) {
            if (File(d, "build.rsfs").isFile) return d
            d = d.parentFile
        }
        return null
    }

    private fun projectRoot(from: File): File {
        var d: File? = from.absoluteFile
        while (d != null) {
            if (File(d, "build.rsfs").isFile) return d
            d = d.parentFile
        }
        return rootDir ?: from.absoluteFile
    }

    /**
     * 依赖清单里的路径：**相对项目根**（和 `#dep`、`@/` 同一套基准）。
     *
     * 不在项目里（比如系统里的 `.h`）就退回绝对路径——锁文件里至少还是一个能定位的路径。
     */
    private fun depPath(file: File): String {
        // 有 build.rsfs 就相对项目根（与 `@/`、`#dep` 同一套基准）；没有项目就相对**当前目录**
        // ——构建命令解析依赖时用的就是当前目录，两边必须同基准，否则清单里的路径指回别处。
        val root = findProjectRoot(file.parentFile ?: file) ?: File(".").absoluteFile
        return try {
            file.absoluteFile.normalize().relativeTo(root.absoluteFile.normalize()).path
        } catch (e: IllegalArgumentException) {
            file.absolutePath
        }
    }

    private fun resolvePath(path: String, fromDir: File, imp: ImportDecl): File {
        if (path.startsWith("@/")) {
            val root = projectRoot(fromDir)
            val f = File(root, path.removePrefix("@/"))
            if (!f.isFile) {
                throw RsfError(
                    "loader",
                    "找不到被 import 的文件 '$path'：@/ 指项目根 ${root.path}",
                    imp.at.line, imp.at.column, files.last(),
                )
            }
            return f.canonicalFile
        }
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
        // 标准库命名空间：`import "std/Vec.rsf"` 里的 `std` 是包名（= stdlib 下的目录）。
        // 先项目、后工具链；工具链那份跟着**编译器**走，不跟项目走。
        findManifestRoot(fromDir)?.let { bases.add(File(it, "stdlib")) }
        org.example.build.Stdlib.dir().parentFile?.let { bases.add(File(it, "stdlib")) }
        // 顺带也认 `import "stdlib/std/Vec.rsf"` 这种带前缀的完整路径
        org.example.build.Stdlib.dir().parentFile?.let { bases.add(it) }
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
