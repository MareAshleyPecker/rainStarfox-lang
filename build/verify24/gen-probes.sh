#!/usr/bin/env bash
# 阶段 24（`as` / `?as` / `is` / `!is`）探针语料生成器。
#
# 用法：bash build/verify24/gen-probes.sh
# 产物：build/verify24/probes/p*.rsf（正例，check 必须过 + 两端 stdout 逐字节同）
#       build/verify24/probes/n*.rsf（负例，check 必须拒）
#
# 口径（用户 2026-10-17 拍板）：
#   * 失败文案里的「实际类型」印 **JVM 全名**（`Class.getName()` 的点分名；null 印 `null`；数组印 `[LB;`）
#   * 数组转型**做**（`arr as Foo[]` / `arr is Foo[]`）
#   * `null is Foo` 给 **false**
#   * 泛型实参**允许写**，按擦除判，每次写发**一条警告**（stderr，不影响 stdout）
set -eu
cd /d/java/rainStarfox || exit 2
P=build/verify24/probes
rm -rf "$P"
mkdir -p "$P"

# ---------------------------------------------------------------- 正例

cat > "$P/p1_cast_ok.rsf" <<'EOF'
// p1：上转型之后 `as` 回子类，调子类独有方法
open class A {
    fn who() -> string { rtn "A" }
}
class B : A() {
    fn only() -> string { rtn "B-only" }
}

fn main() -> void {
    var b : B = new B()
    var a : A = b
    var b2 : B = a as B
    println(b2.only())
    println(b2.who())
    rtn
}
EOF

cat > "$P/p2_cast_fail.rsf" <<'EOF'
// p2：兄弟类之间 `as` 失败 → 文案里印 JVM 全名，try/catch 接住
open class A { }
class B : A() { }
class C : A() { }

fn main() -> void {
    var a : A = new C()
    try {
        var b : B = a as B
        println("不该到这里")
    } catch (e : string) {
        println(e)
    }
    rtn
}
EOF

cat > "$P/p3_safe_cast.rsf" <<'EOF'
// p3：`?as` 成功给对象、失败给 null（结果类型 ?T）
open class A { }
class B : A() { }

fn main() -> void {
    var a : ?A = new B()
    var ok : ?B = a ?as B
    if (ok == null) {
        println("ok-null")
    } else {
        println("ok-non-null")
    }
    var a2 : ?A = new A()
    var bad : ?B = a2 ?as B
    if (bad == null) {
        println("bad-null")
    } else {
        println("bad-non-null")
    }
    rtn
}
EOF

cat > "$P/p4_is_ops.rsf" <<'EOF'
// p4：is / !is 的七种组合（类、父类、接口、否定）
open class A { }
class B : A() { }
interface Named { fn name() -> string }
class N : A(), Named { override fn name() -> string { rtn "n" } }

fn main() -> void {
    var b : B = new B()
    var a : A = b
    var n : Named = new N()
    println("" + (a is B))
    println("" + (a is A))
    println("" + (b is A))
    println("" + (b is B))
    println("" + (n is A))
    println("" + (a !is B))
    println("" + (b !is B))
    rtn
}
EOF

cat > "$P/p5_null_ops.rsf" <<'EOF'
// p5：null 的三种行为——`null is Foo` 给 false、`null ?as Foo` 给 null、`null as Foo` 抛
open class A { }

fn main() -> void {
    var a : ?A = null
    println("" + (a is A))
    var b : ?A = a ?as A
    if (b == null) {
        println("safe-null")
    }
    try {
        var c : A = a as A
        println("不该到这里")
    } catch (e : string) {
        println(e)
    }
    rtn
}
EOF

cat > "$P/p6_array_cast.rsf" <<'EOF'
// p6：数组转型与判型（协变：B[] 是 A[]，反过来运行期才判）
open class A { }
class B : A() { }
class C : A() { }

fn main() -> void {
    var bs : B[] = new B[2]
    var as1 : A[] = bs
    var bs2 : B[] = as1 as B[]
    println("" + (bs2 is B[]))
    println("" + (as1 is B[]))
    println("" + (bs is A[]))
    var cs : C[] = new C[1]
    var as2 : A[] = cs
    println("" + (as2 is B[]))
    try {
        var x : B[] = as2 as B[]
        println("不该到这里")
    } catch (e : string) {
        println(e)
    }
    rtn
}
EOF

cat > "$P/p7_iface_cast.rsf" <<'EOF'
// p7：接口判型/转型，以及「类 + 接口」混合
interface Greeter { fn greet() -> string { rtn "hi" } }
class Impl : Greeter { override fn greet() -> string { rtn "impl" } }
open class Base { }
class Both : Base(), Greeter { override fn greet() -> string { rtn "both" } }

fn main() -> void {
    var g : Greeter = new Impl()
    println("" + (g is Impl))
    println("" + (g is Greeter))
    var i : Impl = g as Impl
    println(i.greet())
    var g2 : Greeter = new Both()
    println("" + (g2 is Base))
    var b : Base = g2 as Base
    println("" + (b is Greeter))
    rtn
}
EOF

cat > "$P/p8_generic_erasure.rsf" <<'EOF'
// p8：泛型实参允许写、按擦除判（每次写发一条 stderr 警告）
open class Box&<T>(v : T) {
    var value : T = v
    fn get() -> T { rtn value }
}

fn main() -> void {
    var b : Box&<int> = new Box&<int>(5)
    var o : Box&<int> = b as Box&<int>
    println("" + o.get())
    println("" + (o is Box&<int>))
    rtn
}
EOF

cat > "$P/p9_noop_cast.rsf" <<'EOF'
// p9：同类型 / 上转型的 `as` 是空操作（不抛、不改值）
open class A { fn who() -> string { rtn "A" } }
class B : A() { }

fn main() -> void {
    var b : B = new B()
    var b2 : B = b as B
    var a : A = b2 as A
    println(b2.who())
    println(a.who())
    rtn
}
EOF

# ---------------------------------------------------------------- 负例

cat > "$P/n1_nullable_target.rsf" <<'EOF'
// n1：目标类型不许写可空
open class A { }
fn main() -> void {
    var a : A = new A()
    var b : A = a as ?A
    rtn
}
EOF

cat > "$P/n2_numeric_target.rsf" <<'EOF'
// n2：数值转换不归 as 管（走 toByte/toChar/charToInt 这些内置函数）
fn main() -> void {
    var n : int = 5
    var m : int = n as int
    rtn
}
EOF

cat > "$P/n3_chained_cast.rsf" <<'EOF'
// n3：不许链式
open class A { }
fn main() -> void {
    var a : A = new A()
    var b : A = a as A as A
    rtn
}
EOF

cat > "$P/n4_is_numeric.rsf" <<'EOF'
// n4：is 只判引用类型
fn main() -> void {
    var n : int = 5
    println("" + (n is int))
    rtn
}
EOF

cat > "$P/n5_unknown_type.rsf" <<'EOF'
// n5：目标类型必须是已知类型
open class A { }
fn main() -> void {
    var a : A = new A()
    var b : A = a as Nope
    rtn
}
EOF

cat > "$P/n6_safe_to_nonnull.rsf" <<'EOF'
// n6：?as 的结果是可空的，不能直接赋给非空变量
open class A { }
fn main() -> void {
    var a : ?A = null
    var b : A = a ?as A
    rtn
}
EOF

cat > "$P/n7_chained_is.rsf" <<'EOF'
// n7：不许把 is 的结果再拿去 is（bool 不是引用类型）
open class A { }
fn main() -> void {
    var a : A = new A()
    println("" + (a is A is A))
    rtn
}
EOF

cat > "$P/n8_generic_on_plain.rsf" <<'EOF'
// n8：非泛型类型不许写实参
open class A { }
fn main() -> void {
    var a : A = new A()
    var b : A = a as A&<int>
    rtn
}
EOF

echo "生成完毕：$(ls "$P" | wc -l) 个文件"
