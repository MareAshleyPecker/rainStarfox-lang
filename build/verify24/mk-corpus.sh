#!/usr/bin/env bash
# 阶段 24b（自举词法 + 解析同步 `as` / `?as` / `is` / `!is`）的 AST 对拍语料。
# 正例只要"能被 Kotlin 版解析"，负例只要"两侧都必须在解析期拒"。
set -u
cd /d/java/rainStarfox || exit 2
R=build/verify24/astparity
mkdir -p "$R/corpus" "$R/neg"

cat > "$R/corpus/k1_basic_cast.rsf" <<'EOF'
open class A { }
class B : A() { var x : int = 1 }
fn main() -> void {
    var a : A = new B()
    var b : B = a as B
    println("" + b.x)
    rtn
}
EOF

cat > "$R/corpus/k2_safe_cast.rsf" <<'EOF'
open class A { }
class B : A() { }
fn main() -> void {
    var a : A = new B()
    var c : ?B = a ?as B
    rtn
}
EOF

cat > "$R/corpus/k3_is_ops.rsf" <<'EOF'
open class A { }
class B : A() { }
fn main() -> void {
    var a : A = new B()
    if (a is B) { println("y") }
    if (a !is B) { println("n") }
    rtn
}
EOF

cat > "$R/corpus/k4_cast_targets.rsf" <<'EOF'
open class A { }
open class Box&<T>(v : T) { var value : T = v }
fn main() -> void {
    var arr : int[] = new int[0]
    var a : A = new A()
    var b1 : int[] = arr as int[]
    var b2 : Box&<int> = a as Box&<int>
    var b3 : bool = a is Box&<int>
    rtn
}
EOF

cat > "$R/corpus/k5_expr_pos.rsf" <<'EOF'
open class A { }
class B : A() { var x : int = 7 }
fn take(b : B) -> void {
    println("" + b.x)
}
fn main() -> void {
    var a : A = new B()
    take(a as B)
    println("" + (a as B).x)
    println("" + (a ?as B)!!.x)
    rtn
}
EOF

cat > "$R/neg/nb1_nullable_target.rsf" <<'EOF'
open class A { }
class B : A() { }
fn main() -> void {
    var a : A = new B()
    var b : B = a as ?B
    rtn
}
EOF

cat > "$R/neg/nb2_chained_cast.rsf" <<'EOF'
open class A { }
class B : A() { }
fn main() -> void {
    var a : A = new B()
    var c : A = a as A as A
    rtn
}
EOF

cat > "$R/neg/nb3_chained_is.rsf" <<'EOF'
open class A { }
fn main() -> void {
    var a : A = new A()
    if (a is A is A) { println("x") }
    rtn
}
EOF

cat > "$R/neg/nb4_missing_target.rsf" <<'EOF'
open class A { }
fn main() -> void {
    var a : A = new A()
    var b : A = a as
    rtn
}
EOF

echo "语料：$(ls "$R/corpus" | wc -l | tr -d ' ') 正例 / $(ls "$R/neg" | wc -l | tr -d ' ') 负例"
