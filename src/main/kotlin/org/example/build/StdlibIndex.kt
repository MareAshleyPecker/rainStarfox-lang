package org.example.build

import java.io.File

/**
 * 标准库的符号索引（阶段 84）：**符号名 → 怎么 import 它**。
 *
 * 依据是 `module-impex.rsf` 里的模块声明，而不是随手扫目录：
 *
 * ```rsf
 * module "stdlib/std" to std { Vec Bits Text }   // 只有这三个对 LSP 可见
 * module "stdlib/lang" to lang                   // 没有成员表 = 整包
 * ```
 *
 * - 有成员表：成员名对应 `stdlib/std/<成员>.rsf`，导入写法 `import std.<成员>`；
 * - 没有成员表：扫目录，`pub class X` → `import <别名>.X`；
 *   顶层 `fn y(` → 整个模块 `import <别名>`（一个模块一条 import 就够）。
 *
 * 为什么以清单为准：清单就是"对外提供什么"的唯一定义。LSP 若自己去扫目录，
 * 用户从清单里删掉一个成员，补全却还建议它——编辑器和编译器说的就不是一回事了。
 */
object StdlibIndex {

    /** 一个可自动导入的符号。 */
    data class Symbol(val name: String, val importSpec: String, val module: String, val kind: String)

    private var cache: List<Symbol>? = null
    private var cacheKey: String? = null

    /** 全量索引（按清单文件 mtime 缓存）。 */
    fun all(): List<Symbol> {
        val toolchainRoot = Stdlib.dir().parentFile ?: return emptyList()
        val manifest = File(toolchainRoot, ModuleImpex.FILE_NAME)
        val key = manifest.path + ":" + manifest.lastModified() + ":" + Stdlib.dir().lastModified()
        cache?.let { if (cacheKey == key) return it }
        val out = ArrayList<Symbol>()
        if (manifest.isFile) {
            for (entry in ModuleImpex.parse(manifest)) {
                if (entry.kind != ImpexKind.MODULE_DECL) continue
                val alias = entry.alias ?: continue
                if (entry.internal) continue // 内部模块不对外，也不该被 LSP 建议
                val dir = File(toolchainRoot, entry.path)
                if (!dir.isDirectory) continue
                val members = entry.members
                if (members != null) {
                    for (name in members.sorted()) {
                        val hasClass = dir.listFiles { f -> f.isFile && f.name == "$name.rsf" }?.isNotEmpty() == true
                        out.add(
                            Symbol(
                                name = name,
                                importSpec = if (hasClass) "import $alias.$name" else "import $alias",
                                module = alias,
                                kind = if (hasClass) "class" else "function",
                            ),
                        )
                    }
                } else {
                    val files = dir.listFiles { f ->
                        f.isFile && f.name.endsWith(".rsf") &&
                            f.name != PkgInfo.FILE_NAME && f.name != ModuleImpex.FILE_NAME
                    }?.sortedBy { it.name }.orEmpty()
                    var moduleImportNeeded = false
                    for (file in files) {
                        val text = runCatching { file.readText() }.getOrNull() ?: continue
                        for (line in text.lines()) {
                            val cls = Regex("^\\s*pub class (\\w+)").find(line)
                            if (cls != null) {
                                val name = cls.groupValues[1]
                                out.add(Symbol(name, "import $alias.$name", alias, "class"))
                                continue
                            }
                            val fn = Regex("^(?:pub )?fn (\\w+)").find(line)
                            if (fn != null) {
                                out.add(Symbol(fn.groupValues[1], "import $alias", alias, "function"))
                                moduleImportNeeded = true
                            }
                        }
                    }
                    if (moduleImportNeeded) {
                        // 整包导入一条就够：把「import 别名」也当一个可选符号（名字用别名）
                        if (out.none { it.importSpec == "import $alias" }) {
                            out.add(Symbol(alias, "import $alias", alias, "module"))
                        }
                    }
                }
            }
        }
        cache = out
        cacheKey = key
        return out
    }

    /**
     * 给一条报错补「标准库里有它，这样导入」。
     *
     * 认两种消息：「未声明的变量 'X'」与「找不到…'X'」（类路径 / 类名）。
     * 编译器与 LSP 共用这一个实现，两边提示一字不差。
     */
    fun hintFor(message: String): String {
        val name = Regex("未声明的变量 '([A-Za-z_][A-Za-z0-9_]*)'").find(message)?.groupValues?.get(1)
            ?: Regex("找不到[^']*'([A-Za-z_][A-Za-z0-9_.]*)'").find(message)?.groupValues?.get(1)
                ?.substringAfterLast('.')
            ?: return ""
        val hits = exact(name)
        if (hits.isEmpty()) return ""
        return "。标准库里有 ${hits.map { it.importSpec }.distinct().joinToString("、")}"
    }

    /** 名字完全一致的符号（诊断提示用）。 */
    fun exact(name: String): List<Symbol> = all().filter { it.name == name }

    /** 以 [prefix] 开头的符号（补全用）。 */
    fun startingWith(prefix: String): List<Symbol> =
        all().filter { prefix.isEmpty() || it.name.startsWith(prefix) }
}
