package org.example

/**
 * 阶段 32b 的符号表对拍探针（Kotlin 侧 = oracle）。
 *
 * 这里用 `HashMap` + 父链（就是 `Checker.Scope` 的写法）跑一段固定脚本；
 * 自举侧 `rsfSrc/bootstrap/ScopeProbe.rsf` 用并排数组实现同一契约，两边输出必须逐字节相同。
 */
private class Binding(val name: String, val kind: String, val typeText: String) {
    fun text() = "$name/$kind/$typeText"
}

private class Scope(val parent: Scope?) {
    private val vars = HashMap<String, Binding>()
    val depth: Int = (parent?.depth ?: 0) + 1

    fun declares(name: String): Boolean = vars.containsKey(name)

    fun declare(name: String, b: Binding) {
        vars[name] = b
    }

    fun lookup(name: String): Binding? = vars[name] ?: parent?.lookup(name)
}

fun main() {
    var cur = Scope(null)

    fun enter() {
        cur = Scope(cur)
    }

    fun leave() {
        cur = cur.parent ?: cur
    }

    fun dec(name: String, kind: String, t: String): String {
        if (cur.declares(name)) return "dup"
        cur.declare(name, Binding(name, kind, t))
        return "ok"
    }

    fun look(name: String): String = cur.lookup(name)?.text() ?: "none"

    fun state() {
        println("depth=${cur.depth}")
    }

    state()
    println("declare a:local:int -> ${dec("a", "local", "int")}")
    println("declare a:local:int -> ${dec("a", "local", "int")}")
    println("lookup a -> ${look("a")}")
    println("lookup b -> ${look("b")}")

    enter()
    state()
    println("declare b:param:string -> ${dec("b", "param", "string")}")
    println("lookup b -> ${look("b")}")
    println("lookup a -> ${look("a")}")
    println("declare a:local:long -> ${dec("a", "local", "long")}")
    println("lookup a -> ${look("a")}")

    enter()
    state()
    println("lookup a -> ${look("a")}")
    println("lookup b -> ${look("b")}")

    leave()
    state()
    println("lookup a -> ${look("a")}")
    println("lookup b -> ${look("b")}")

    leave()
    state()
    println("lookup a -> ${look("a")}")
    println("lookup b -> ${look("b")}")
}
