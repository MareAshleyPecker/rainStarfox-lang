package org.example.complie.lexing

/**
 * 源码位置：1 基的行号与列号。
 */
data class SourceLine(val line: Int, val column: Int) {

    override fun toString(): String = "$line:$column"

    companion object {
        val NONE = SourceLine(-1, -1)
    }
}
