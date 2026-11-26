package org.example.build

import org.example.complie.diag.RsfError

/**
 * 一个构建目标要产出什么（`#target { @/a.rsf -> exe }` 里的 `exe`）。
 *
 * 名字取文件主名：`@/src/app.rsf -> exe` 的目标叫 `app`，产物是 `app`（Windows 上 `app.exe`）。
 * 四种种类对应四类产物，都是**已有的**那几条路，没有新概念：
 *
 * | 种类 | 产物 | 走哪条路 |
 * | --- | --- | --- |
 * | `exe` | 可执行文件 | `--c --link`（gcc 直接出二进制） |
 * | `lib` | 目标文件 `.o` / `.obj` | `--c --lib`（只编译不链接） |
 * | `jar` | 可执行 jar | JVM 后端 + `--jar` |
 * | `copy` | 原样复制到产物目录 | 不编译（配置、资源、脚本） |
 *
 * **为什么不叫 `native` / `class`**：`exe` 说的是「能直接跑」，`lib` 说的是「给别人链」，
 * 跟后端怎么实现解耦；将来自举出机器码后端时，`exe` 的含义不变。
 */
enum class TargetKind(val keyword: String) {
    EXE("exe"),
    LIB("lib"),
    JAR("jar"),
    COPY("copy"),
    ;

    companion object {
        fun of(keyword: String): TargetKind? = entries.firstOrNull { it.keyword == keyword }

        val KEYWORDS: String = entries.joinToString(" / ") { it.keyword }
    }
}

/**
 * 目标表里的一行：`TargetSpec{ name, src, kind, out? }`。
 *
 * [origin] 记「这一行是从哪来的」（`#target` 第几行 / `target()` 调用点），
 * 重名报错时两处来源都要列出来，否则用户不知道删哪一行。
 * [src] 保持用户写的样子（`@/src/app.rsf`、相对项目根、绝对路径都行），
 * 到真正编译时再由 [ProjectBuildHost] 按项目根解析。
 */
data class TargetSpec(
    val name: String,
    val src: String,
    val kind: TargetKind,
    /** 显式产物路径；写 `out(...)` 才有，否则按 [kind] 的约定推。 */
    val out: String? = null,
    val origin: String,
) {
    companion object {
        /**
         * 从「源路径 + 种类关键字」造一行。两种写法（`#target` 块与 `target()` 调用）
         * 都从这里过，所以「名字取文件主名」这条规矩只有一处实现。
         */
        fun of(src: String, kindKeyword: String, out: String?, origin: String): TargetSpec {
            val kind = TargetKind.of(kindKeyword)
                ?: throw RsfError(
                    "build",
                    "'$origin' 里的种类 '$kindKeyword' 不认识，只能写 ${TargetKind.KEYWORDS}",
                )
            val filePart = src.replace('\\', '/').substringAfterLast('/')
            val name = filePart.substringBeforeLast('.')
            if (name.isEmpty() || name == "." || name == "..") {
                throw RsfError("build", "'$origin' 里认不出文件名（'$src'）——目标名取的是文件主名")
            }
            return TargetSpec(name, src, kind, out, origin)
        }
    }
}

/**
 * 目标表：声明顺序 + `need` 的先后约束。
 *
 * 两条规矩：
 * - **声明顺序即构建顺序**——用户写下的顺序就是他要的顺序，不做字母排序；
 * - `need(a, b)` 只说「b 前先做 a」，不改别的相对顺序（稳定拓扑排序）。
 *
 * 名字取文件主名，所以重名会当场报错（两个 `@/x/app.rsf`、`@/y/app.rsf` 都叫 `app`）。
 * 检测放在 [add] 里，而不是等到出产物：构建脚本跑到一半才说重名，用户已经等了半天。
 */
class BuildPlan {

    private val specs = ArrayList<TargetSpec>()

    /** 原始约束：`a` 必须先于 `b`；参数是用户写的名字或路径，[ordered] 时才解析。 */
    private val needs = ArrayList<Pair<String, String>>()

    val size: Int get() = specs.size

    fun isEmpty(): Boolean = specs.isEmpty()

    /** 声明顺序（给 `rsf build --list` 之类看）。 */
    fun declared(): List<TargetSpec> = specs.toList()

    fun add(spec: TargetSpec) {
        val clash = specs.firstOrNull { it.name == spec.name }
        if (clash != null) {
            throw RsfError(
                "build",
                "两个目标都叫 '${spec.name}'：${clash.origin}（${clash.src}）和 " +
                    "${spec.origin}（${spec.src}）。" +
                    "目标名取文件主名，同名就分不清该出哪一个；改名或换目录",
            )
        }
        specs.add(spec)
    }

    fun need(from: String, to: String) = needs.add(from to to)

    /**
     * 排好的构建顺序：声明顺序，受 `need` 约束（稳定拓扑排序）。
     *
     * `need` 的参数可以写目标名（`"app"`）也可以写源路径（`"@/src/app.rsf"`），
     * 因为用户手里同时有这两样东西。解析不到的、成环的当场报错，
     * 消息里列出参与者——静默跳过会让「顺序不对」变成随机 bug。
     */
    fun ordered(): List<TargetSpec> {
        if (needs.isEmpty()) return specs.toList()
        val index = specs.withIndex().associate { it.value.name to it.index }
        // 先把每条约束解析成下标；同一对重复写不算错（幂等）。
        val edges = ArrayList<Pair<Int, Int>>()
        for ((rawFrom, rawTo) in needs) {
            val from = resolve(rawFrom, "need")
            val to = resolve(rawTo, "need")
            if (from.name == to.name) {
                throw RsfError("build", "need('${from.name}', '${to.name}')：一个目标不能依赖它自己")
            }
            val edge = index.getValue(from.name) to index.getValue(to.name)
            if (edge !in edges) edges.add(edge)
        }
        val order = LinkedHashSet<Int>()
        val visiting = HashSet<Int>()
        fun emit(i: Int, path: List<String>) {
            if (i in order) return
            if (!visiting.add(i)) {
                val cycle = (path + specs[i].name).joinToString(" -> ")
                throw RsfError("build", "need(...) 成环了：$cycle。把其中一条依赖去掉，构建顺序才定得下来")
            }
            for ((from, to) in edges) if (to == i) emit(from, path + specs[i].name)
            visiting.remove(i)
            order.add(i)
        }
        for (spec in specs) emit(index.getValue(spec.name), emptyList())
        return order.map { specs[it] }
    }

    /** 把用户写的名字/路径认成一个目标；认不出来就报错并列出所有目标名。 */
    private fun resolve(raw: String, where: String): TargetSpec {
        val byName = specs.firstOrNull { it.name == raw }
        if (byName != null) return byName
        val want = normalize(raw)
        val byPath = specs.firstOrNull { normalize(it.src) == want }
        if (byPath != null) return byPath
        val known = specs.joinToString("、") { "${it.name}（${it.src}）" }
        throw RsfError("build", "$where(\"$raw\") 指不到任何目标。现有目标：$known")
    }

    /** 路径比较用的规范化：去掉 `@/` 前缀与 `./`，并把分隔符统一成 `/`。 */
    private fun normalize(path: String): String {
        var p = path.replace('\\', '/').trim()
        if (p.startsWith("@/")) p = p.substring(2) else if (p.startsWith("./")) p = p.substring(2)
        // 绝对路径两边都会去掉开头的 '/'，所以「同一个绝对路径」照样认得出；
        // 这里不调 File.isAbsolute，是为了让 `@/a.rsf` 与 `/项目/a.rsf` 也能互认。
        return p.trimStart('/').ifEmpty { p }
    }
}
