package org.example.build

import java.io.File

/**
 * 标准库目录（阶段 84）：`stdlib/`。
 *
 * 现在里面只有生成的 `rsf_runtime.h`——生成的 `.c` 靠 `#include "rsf_runtime.h"`
 * 找它，所以 gcc 命令行必须带 `-I <stdlib>`。以后用 rsf 写的 core 模块
 * 标准库模块也放这里，构建时默认加进 import 搜索路径。
 *
 * 为什么单独一个目录，而不是每个产物目录各写一份：运行时头是全项目（乃至整个
 * 工具链）共享的**一份**，复制到产物目录只会让人分不清「哪份是真的」；
 * 而且产物目录是构建缓存，`clean` 掉之后头文件不该跟着消失。
 *
 * 查找顺序（第一次命中即用）：
 * 1. `RSF_STDLIB` 环境变量（分发时指向安装目录）；
 * 2. 从**编译器自己的位置**往上找 `stdlib/`（源码树里是 `<repo>/stdlib`，
 *    装好的 jar 旁边也是 `stdlib/`）；
 * 3. 从当前目录往上找 `stdlib/`；
 * 4. 都没有就用 `./stdlib` 并创建。
 */
object Stdlib {

    const val DIR_NAME: String = "stdlib"

    /** 运行时头文件的名字（生成的，不进版本库）。 */
    const val RUNTIME_HEADER: String = "rsf_runtime.h"

    private var cached: File? = null

    /** 解析（并在需要时创建）标准库目录。 */
    fun dir(): File {
        cached?.let { return it }
        val found = fromEnv() ?: fromCompiler() ?: fromCwd() ?: File(DIR_NAME).absoluteFile
        found.mkdirs()
        cached = found
        return found
    }

    /** 运行时头文件的完整路径（可能在生成之前还不存在）。 */
    fun runtimeHeader(): File = File(dir(), RUNTIME_HEADER)

    /** 检测时用：标准库目录里是否已经有运行时头。 */
    fun hasRuntimeHeader(): Boolean = runtimeHeader().isFile

    private fun fromEnv(): File? =
        System.getenv("RSF_STDLIB")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun fromCompiler(): File? {
        val location = try {
            Stdlib::class.java.protectionDomain?.codeSource?.location
        } catch (e: Exception) {
            null
        } ?: return null
        var dir = try {
            File(location.toURI())
        } catch (e: Exception) {
            return null
        }
        if (!dir.isDirectory) dir = dir.parentFile ?: return null
        return walkUp(dir)
    }

    private fun fromCwd(): File? = walkUp(File("").absoluteFile)

    private fun walkUp(start: File): File? {
        var d: File? = start
        while (d != null) {
            val candidate = File(d, DIR_NAME)
            if (candidate.isDirectory) return candidate
            d = d.parentFile
        }
        return null
    }
}
