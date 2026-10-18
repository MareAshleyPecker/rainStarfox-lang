package org.example

import org.example.complie.sema.RsfBase
import org.example.complie.sema.RsfType

/**
 * 阶段 32a 的类型层对拍探针（Kotlin 侧 = oracle）。
 *
 * 打一张固定的表：每个类型的名字 / 描述符 / 槽位 / 各类判定 / print 描述符，
 * 再打 16×16 的 `accepts` 矩阵与 4 条 `substitute` 结果。
 * 自举侧的 `rsfSrc/bootstrap/TypeProbe.rsf` 必须打出逐字节相同的文本。
 */
private val ROWS: List<Pair<String, RsfType>> = listOf(
    "int" to RsfType.INT,
    "?int" to RsfType.INT.asNullable(),
    "long" to RsfType.LONG,
    "byte" to RsfType.BYTE,
    "char" to RsfType.CHAR,
    "void" to RsfType.VOID,
    "null" to RsfType.NULL,
    "string" to RsfType.STRING,
    "?string" to RsfType.STRING.asNullable(),
    "int[]" to RsfType.INT.arrayOf(1),
    "int[][]" to RsfType.INT.arrayOf(2),
    "Box&<int>" to RsfType(RsfBase.EXTERNAL, false, "demo/Box", listOf(RsfType.INT)),
    "?Box&<string>" to RsfType(RsfBase.EXTERNAL, true, "demo/Box", listOf(RsfType.STRING)),
    "T" to RsfType(RsfBase.TYPE_PARAM, false, "T"),
    "T[]" to RsfType(RsfBase.TYPE_PARAM, false, "T", emptyList(), 1),
    "Object" to RsfType(RsfBase.EXTERNAL, false, "java/lang/Object"),
)

private fun flag(b: Boolean) = if (b) "1" else "0"

fun main() {
    for ((label, t) in ROWS) {
        println(
            listOf(
                label,
                t.rsfName,
                t.descriptor,
                t.slots.toString(),
                flag(t.isPrimitive),
                flag(t.isNumeric),
                flag(t.isArithmetic),
                flag(t.isExternal),
                flag(t.isTypeParam),
                flag(t.isNullable),
                flag(t.isVoid),
                flag(t.isNullLiteral),
                t.printDescriptor,
            ).joinToString("|")
        )
    }
    println("--- accepts ---")
    for ((i, a) in ROWS.withIndex()) {
        for ((j, b) in ROWS.withIndex()) {
            println("$i $j ${flag(a.second.accepts(b.second))}")
        }
    }
    println("--- substitute T -> int ---")
    val subst = mapOf("T" to RsfType.INT)
    for ((label, t) in ROWS) {
        val r = t.substitute(subst)
        println("$label -> ${r.rsfName} ${r.descriptor}")
    }
}
