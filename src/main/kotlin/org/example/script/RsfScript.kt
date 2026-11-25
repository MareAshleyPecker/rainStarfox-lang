package org.example.script

import org.example.complie.Rsf
import org.example.complie.ast.BuildTarget
import org.example.complie.ast.FuncDecl
import org.example.complie.codegen.BytecodeCompiler
import org.example.complie.diag.RsfError
import org.example.complie.diag.SourceFile
import org.example.complie.sema.SemanticModel
import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 一个「可加载的 rsf 脚本」。
 *
 * 口径：**同一个语言、同一份前端与后端，只是换了个加载方式**。`rsf build` 把程序编成
 * `.class` 写进目录再 `java` 启动；这里把同一批字节留在内存里，交给自己的 ClassLoader，
 * 于是宿主可以：
 *
 * - 运行期加载脚本（不必先落盘、不必另起进程）；
 * - 按名字调用脚本里的顶层函数，参数与返回值就是 JVM 值（`Int` / `String` / 数组 / 脚本对象），
 *   不做任何编组——同一台 JVM、同一个堆；
 * - 用 [reload] 换一份新代码：新脚本是新 ClassLoader 定义的类，旧的那批只要没人引用就能被回收。
 *
 * 为什么能这么便宜：脚本函数在字节码里就是 `public static` 方法，描述符与 `Int` / `String`
 * 一一对应（见 `BytecodeCompiler.emitFunction`）。宿主这边只是一次 MethodHandle 绑定。
 *
 * ## 边界
 * - 线程安全：不是。一个实例对应一次编译结果，多线程同时 [call] 由调用方自己加锁；
 *   脚本里的全局状态（对象、`imutvar`）本来就跨调用共享。
 * - `System.exit` / `Runtime.halt`：脚本能调，会把宿主一起带走。要沙箱得先做互操作白名单。
 * - 类型检查在脚本编译期完成；宿主传错类型时由 MethodHandle 抛 `ClassCastException`
 *   （或 `WrongMethodTypeException`），不会静默转换。
 */
class RsfScript private constructor(
    /** 逻辑名字，默认就是没有扩展名的文件名。 */
    val name: String,
    val model: SemanticModel,
    private val sourceText: String,
    private val baseDir: File?,
    private val loader: ScriptClassLoader,
    private val programClass: Class<*>,
) {

    private val handles = HashMap<FuncDecl, MethodHandle>()

    /** 编译期产生的警告（不可达代码之类），宿主可以自己决定要不要转给用户。 */
    val warnings: List<String> get() = model.warnings

    /** 脚本里定义的全部顶层函数名，去重后按字典序。 */
    fun functions(): List<String> = model.functions.map { it.name }.distinct().sorted()

    /** 有没有这个函数（按名字，不管参数个数）。 */
    fun hasFunction(name: String): Boolean = model.functions.any { it.name == name }

    /**
     * 调一个顶层函数。`args` 按形参顺序给，个数必须对上；
     * 同名重载按个数挑，挑不唯一就报错（不猜类型）。
     */
    fun call(name: String, vararg args: Any?): Any? {
        val decl = pick(name, args.size)
        val handle = handles.getOrPut(decl) { bind(decl) }
        return try {
            handle.invokeWithArguments(args.toList())
        } catch (e: InvocationTargetException) {
            // 脚本 `fail` 出来的错误在字节码里是 RuntimeException；原样抛给宿主，
            // 让它能按自己的策略处理，而不是被包一层看不出所以然的东西。
            throw e.cause ?: e
        }
    }

    /** 走一遍 [call]，只关心「跑没跑成功」。 */
    fun run(name: String, vararg args: Any?): Unit {
        call(name, *args)
    }

    /** 重新编译并换一份新代码；旧实例的类在没人引用后可以被回收。 */
    fun reload(): RsfScript = compile(sourceText, name, baseDir)

    /** 这份脚本的源码（[reload] 用）。 */
    fun source(): String = sourceText

    // ------------------------------------------------------------------ 内部

    private fun pick(name: String, argc: Int): FuncDecl {
        val candidates = model.functions.filter { it.name == name }
        if (candidates.isEmpty()) {
            throw RsfError(
                "script",
                "脚本 '${this.name}' 里没有函数 '${nameOf(name)}'（有这个函数名吗？可用：" +
                    (functions().joinToString(", ").ifEmpty { "（一个都没有）" }) + "）",
            )
        }
        val byArity = candidates.filter { it.params.size == argc }
        if (byArity.size == 1) return byArity[0]
        if (byArity.isEmpty()) {
            throw RsfError(
                "script",
                "函数 '${nameOf(name)}' 没有 $argc 个参数的版本（候选：" +
                    candidates.joinToString(", ") { "${it.name}/${it.params.size}" } + "）",
            )
        }
        throw RsfError(
            "script",
            "函数 '${nameOf(name)}' 有 ${byArity.size} 个 $argc 参重载，" +
                "按实参个数挑不唯一；脚本宿主 v1 不做按类型重载（候选：" +
                byArity.joinToString(", ") { signature(it) } + "）",
        )
    }

    /** 报错时用的「脚本名.函数名」。 */
    private fun nameOf(fn: String): String = "$name.$fn"

    private fun signature(fn: FuncDecl): String =
        fn.name + "(" + fn.params.joinToString(", ") { it.type.display() } + ")"

    /**
     * 把 `FuncDecl` 绑成一个 MethodHandle。
     *
     * 不按描述符字符串去猜：在生成出来的程序类上按「名字 + 参数个数 + 静态」找那个
     * `java.lang.reflect.Method`，再 `unreflect`。名字没有 mangling（顶层函数就是原名），
     * 所以这一步永远是精确命中。
     */
    private fun bind(decl: FuncDecl): MethodHandle {
        val m = programClass.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == decl.name && it.parameterCount == decl.params.size
        } ?: throw RsfError(
            "script",
            "内部错误：脚本 '$name' 里找不到方法 '${decl.name}/${decl.params.size}'，编译产物和语义模型对不上",
        )
        return try {
            MethodHandles.publicLookup().unreflect(m)
        } catch (_: IllegalAccessException) {
            m.isAccessible = true
            MethodHandles.lookup().unreflect(m)
        }
    }

    /**
     * 一个脚本一份 ClassLoader：类名可以和别的脚本重复，卸载也只看这一个实例。
     *
     * 必须**自己定义**而不是交给父加载器：同名类在同一个加载器里只能存在一份，
     * 热重载就无从谈起。
     */
    private class ScriptClassLoader(
        private val classes: Map<String, ByteArray>,
        parent: ClassLoader,
    ) : ClassLoader(parent) {

        override fun findClass(name: String): Class<*> {
            val bytes = classes[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun toString(): String = "RsfScriptClassLoader(${classes.size} classes)"
    }

    companion object {

        /**
         * 编译一段源码。`name` 同时是脚本名与程序类名（会校验是不是合法 Java 标识符），
         * 默认 `rsf_script`；`baseDir` 是多文件 `import` 的根目录。
         */
        fun compile(
            source: String,
            name: String = "rsf_script",
            baseDir: File? = null,
        ): RsfScript {
            // 类名规则与 `rsf build` 完全一致：文件名当类名。
            // 所以这里用一个**合法文件名**做路径，而不是 "<script:...>" 那种装饰性名字，
            // 否则 deriveClassName 会把尖括号也当成类名的一部分。
            val src = SourceFile.of("$name.rsf", source)
            val className = Rsf.deriveClassName(src)
            val anchor = File(baseDir ?: File(".").absoluteFile, "$className.rsf")
            val loaded = Rsf.load(anchor, baseDir, source, BuildTarget.JVM)
            val model = Rsf.analyze(loaded)
            // 包的内部名要和 Rsf.build 一致：`pkg a.b` → a/b/类名
            val internal = model.packageName
                ?.takeIf { it.isNotEmpty() }
                ?.let { it.replace('.', '/') + "/" + className }
                ?: className
            val bytes = BytecodeCompiler(model, src.path).compileBytes(internal)
            val byBinaryName = bytes.associate { (n, b) -> n.replace('/', '.') to b }
            val loader = ScriptClassLoader(byBinaryName, RsfScript::class.java.classLoader)
            return RsfScript(name, model, source, baseDir, loader, loader.loadClass(internal.replace('/', '.')))
        }

        /** 从文件加载（`import` 相对该文件所在目录解析）。 */
        fun load(file: File, baseDir: File? = null): RsfScript {
            val src = SourceFile.of(file)
            return compile(src.text, Rsf.deriveClassName(src), baseDir ?: file.parentFile)
        }
    }
}
