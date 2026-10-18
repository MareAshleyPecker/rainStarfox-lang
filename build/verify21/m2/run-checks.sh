#!/usr/bin/env bash
# 阶段 21 M2（注解语义层）证据脚本：写出 *.rsf 探针，逐个跑 `rsf check`，输出落 checks.log。
# 用法：bash build/verify21/m2/run-checks.sh
set -u
cd "$(dirname "$0")"
JAR=../../libs/rainStarfox-0.9.0.jar
JAVA_OPTS=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)

# ---------------------------------------------------------------- 探针
cat > probe_a1_bad_element_type.rsf <<'EOF'
class Dog {
    var x : int = 0
}

annotation Marker(value : Dog)

fun main() -> void { println("ok") }
EOF

cat > probe_a1_ok_element_types.rsf <<'EOF'
enum TokenKind { A, B }

annotation Inner(flag : bool = false)

annotation Marker(
    name : string,
    n : int,
    kind : TokenKind,
    inner : Inner,
    xs : int[],
)

fun main() -> void { println("ok") }
EOF

cat > probe_a2_nullable_element.rsf <<'EOF'
annotation Marker(value : ?string)

fun main() -> void { println("ok") }
EOF

cat > probe_a3_companion_name_clash.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity value {
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void { println("ok") }
EOF

cat > probe_a4_companion_super_class.rsf <<'EOF'
class Base {
    var x : int = 0
}

annotation Marker(value : string) {
    companion entity Impl : Base {
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void { println("ok") }
EOF

cat > probe_a5_ctor_missing_param.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        constructor(value : string) { }
        fun describe() -> string { rtn value }
    }
}

fun main() -> void { println("ok") }
EOF

cat > probe_a5b_ctor_wrong_order.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        constructor(count : int, value : string) { }
        fun describe() -> string { rtn value }
    }
}

fun main() -> void { println("ok") }
EOF

cat > probe_a5c_ctor_type_mismatch.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        constructor(value : int) { }
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void { println("ok") }
EOF

cat > probe_b7_ok_types.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        fun describe() -> string { rtn "d" }
    }
}

fun takes(a : Marker, b : Marker::Impl) -> void {
    println(a.value)
    println(b.describe())
}

fun main() -> void {
    var a : Marker = new Marker("hi")
    var b : Marker::Impl = new Marker::Impl("hi", 3)
    takes(a, b)
    println(a.value)
    println(b.value())
}
EOF

cat > probe_b7_wrong_companion_name.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void {
    var x : Marker::Nope = new Marker("hi")
    println(x.value)
}
EOF

cat > probe_b7_no_companion.rsf <<'EOF'
annotation Marker(value : string)

fun main() -> void {
    var x : Marker::Impl = new Marker("hi")
    println("ok")
}
EOF

cat > probe_b7_not_annotation.rsf <<'EOF'
class Dog {
    var x : int = 0
}

fun main() -> void {
    var x : Dog::Impl = null
    println("ok")
}
EOF

cat > probe_b8_annotation_as_super.rsf <<'EOF'
annotation Marker(value : string)

class Point : Marker {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c9_unknown_annotation.rsf <<'EOF'
@Nope("hi")
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_a1_2d_array_element.rsf <<'EOF'
annotation Marker(grid : int[][])

fun main() -> void { println("ok") }
EOF

cat > probe_a1_void_element.rsf <<'EOF'
annotation Marker(nothing : void)

fun main() -> void { println("ok") }
EOF

cat > probe_a_enum_arg_ok.rsf <<'EOF'
enum TokenKind { A, B }

annotation Marker(kind : TokenKind, name : string = "x")

@Marker(TokenKind.A)
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_b7_nameless_companion.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity {
        fun describe() -> string { rtn value }
    }
}

fun main() -> void {
    var m : Marker = new Marker("hi")
    println(m.value)
    var x : Marker::Impl = null
    println("ok")
}
EOF

cat > probe_c12_field_clash.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

@Marker("hi")
class Point {
    var Marker : string = "already here"
}

fun main() -> void { println("ok") }
EOF

cat > probe_c_ok_top_level_fun.rsf <<'EOF'
annotation Tag(value : int = 1)

@Tag(7)
fun hello() -> void { println("hi") }

@Tag
fun main() -> void { hello() }
EOF

cat > probe_c_top_level_fun_with_companion.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

@Marker("hi")
fun main() -> void { println("ok") }
EOF

cat > probe_c11_duplicate_on_method.rsf <<'EOF'
annotation Tag(value : int = 1)

class Point {
    var x : int = 0

    @Tag(1)
    @Tag(2)
    fun shift() -> void { x = x + 1 }
}

fun main() -> void { println("ok") }
EOF

cat > probe_c10_too_many_named.rsf <<'EOF'
annotation Marker(value : string)

@Marker(value = "a", value = "b")
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c10_missing_value.rsf <<'EOF'
annotation Marker(value : string, count : int)

@Marker("hi")
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c10_unknown_element.rsf <<'EOF'
@Marker(nope = 1)
class Point {
    var x : int = 0
}

annotation Marker(value : string)

fun main() -> void { println("ok") }
EOF

cat > probe_c10_arg_type_mismatch.rsf <<'EOF'
annotation Marker(value : string)

@Marker(3)
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c10_too_many_args.rsf <<'EOF'
annotation Marker(value : string)

@Marker("a", "b")
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c10_named_ok.rsf <<'EOF'
annotation Marker(value : string, count : int, flag : bool = false)

@Marker(count = 3, value = "hi")
class Point {
    var x : int = 0
}

@Marker("hi", count = 3)
class Line {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c11_duplicate.rsf <<'EOF'
annotation Marker(value : string)

@Marker("a")
@Marker("b")
class Point {
    var x : int = 0
}

fun main() -> void { println("ok") }
EOF

cat > probe_c12_accessor_clash.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

@Marker("hi")
class Point {
    var x : int = 0
    fun Marker() -> string { rtn "already here" }
}

fun main() -> void { println("ok") }
EOF

cat > probe_c_ok_member_level.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

@Marker("on the class")
class Point {
    @Marker("on the field")
    var x : int = 0

    @Marker("on the method")
    fun shift(dx : int) -> void { x = x + dx }
}

annotation Tag(value : int = 1)

@Tag
interface Greeter {
    @Tag(7)
    fun greet(name : string) -> void
}

fun main() -> void { println("ok") }
EOF

cat > probe_d13_new_ok.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        var hits : int = 0
        fun describe() -> string { rtn "Marker(" + value + ")" }
        fun run() -> void { println("run") }
    }
}

fun main() -> void {
    var a : Marker::Impl = new Marker("hi")
    var b : Marker::Impl = new Marker::Impl("yo", 3)
    println(a.describe())
    println(b.describe())
    b.run()
    println(b.hits)
}
EOF

cat > probe_d13_no_companion.rsf <<'EOF'
annotation Marker(value : string)

fun main() -> void {
    var a : Marker = new Marker("hi")
    println(a.value)
}
EOF

cat > probe_d13_arg_count.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

fun main() -> void {
    var a : Marker::Impl = new Marker()
    println(a.describe())
}
EOF

cat > probe_d13_arg_type.rsf <<'EOF'
annotation Marker(value : string) {
    companion entity Impl {
        fun describe() -> string { rtn value }
    }
}

fun main() -> void {
    var a : Marker::Impl = new Marker(3)
    println(a.describe())
}
EOF

cat > probe_d14_hidden.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        var hits : int = 0
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void {
    var m : Marker = new Marker("hi")
    println(m.count())
    println(m.describe())
}
EOF

cat > probe_d14_ok_elements_only.rsf <<'EOF'
annotation Marker(value : string, count : int = 0) {
    companion entity Impl {
        var hits : int = 0
        fun describe() -> string { rtn "d" }
    }
}

fun main() -> void {
    var m : Marker = new Marker("hi", 3)
    println(m.value)
    println(m.count())
    var impl : Marker::Impl = new Marker::Impl("yo", 4)
    println(impl.describe())
    println(impl.hits)
}
EOF

# ---------------------------------------------------------------- 逐个跑
LOG=checks.log
: > "$LOG"
{
    echo "# 阶段 21 M2 · 注解语义层 · rsf check 真实输出"
    echo "# 命令：java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \\"
    echo "#        -jar build/libs/rainStarfox-0.9.0.jar check <file>"
    echo
} >> "$LOG"

for f in probe_*.rsf; do
    echo "================================================================" >> "$LOG"
    echo "### $f" >> "$LOG"
    echo "--- 源码" >> "$LOG"
    cat "$f" >> "$LOG"
    echo "--- 输出" >> "$LOG"
    java "${JAVA_OPTS[@]}" -jar "$JAR" check "$f" >> "$LOG" 2>&1
    echo "[exit=$?]" >> "$LOG"
    echo >> "$LOG"
done

echo "wrote $LOG"
grep -c '^### ' "$LOG"
