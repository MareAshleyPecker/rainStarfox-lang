package org.example.complie.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter

/** 把生成的 .class 反汇编成人可读的 ASM 列表，供 `rsf build --dump` 使用。 */
fun disassemble(classBytes: ByteArray): String {
    val sw = StringWriter()
    val pw = PrintWriter(sw)
    ClassReader(classBytes).accept(TraceClassVisitor(pw), 0)
    pw.flush()
    return sw.toString()
}
