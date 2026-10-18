#!/usr/bin/env bash
# 生成阶段 20 前端验收探针（父代理独立编写，语义参照 DESIGN-20 的 §2/§3）。
set -eu
cd /d/java/rainStarfox || exit 1
D=build/verify20

cat > "$D/ok_impl.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    pub fn greet(name : string) -> string {
        rtn "woof " + name
    }
}

fn main() -> void {
    var g : Greeter = new Dog()
    println(g.greet("bob"))
    rtn
}
EOF

cat > "$D/ok_default.rsf" <<'EOF'
pub interface Greeter {
    fn name() -> string
    fn shout() -> string {
        rtn name() + "!"
    }
}

class Dog : Greeter {
    pub fn name() -> string {
        rtn "rex"
    }
}

fn main() -> void {
    var g : Greeter = new Dog()
    println(g.shout())
    rtn
}
EOF

cat > "$D/ok_extends.rsf" <<'EOF'
pub interface Base {
    fn id() -> int
}

pub interface Named : Base {
    fn name() -> string
}

class Dog : Named {
    pub fn id() -> int {
        rtn 7
    }
    pub fn name() -> string {
        rtn "rex"
    }
}

fn main() -> void {
    var d : Named = new Dog()
    println(intToString(d.id()) + "/" + d.name())
    rtn
}
EOF

cat > "$D/ok_types.rsf" <<'EOF'
pub interface Greeter {
    fn greet() -> string
}

class Dog : Greeter {
    pub fn greet() -> string {
        rtn "woof"
    }
}

fn hello(g : Greeter) -> string {
    rtn g.greet()
}

fn must() -> !Greeter {
    rtn new Dog()
}

fn main() -> void {
    println(hello(new Dog()))
    var g : Greeter = must() ?: new Dog()
    println(g.greet())
    rtn
}
EOF

cat > "$D/ok_nullable.rsf" <<'EOF'
pub interface Greeter {
    fn greet() -> string
}

class Dog : Greeter {
    pub fn greet() -> string {
        rtn "woof"
    }
}

fn pick(flag : bool) -> ?Greeter {
    if (flag) {
        rtn new Dog()
    }
    rtn null
}

fn main() -> void {
    var g : ?Greeter = pick(true)
    println((g ?: new Dog()).greet())
    var h : ?Greeter = pick(false)
    println((h ?: new Dog()).greet())
    rtn
}
EOF

cat > "$D/ok_runnable.rsf" <<'EOF'
import java.lang.Runnable

class Job : Runnable {
    pub fn run() -> void {
        println("running")
    }
}

fn main() -> void {
    var j : Runnable = new Job()
    j.run()
    rtn
}
EOF

cat > "$D/neg_missing.rsf" <<'EOF'
pub interface Greeter {
    fn greet() -> string
    fn shout() -> string
}

class Dog : Greeter {
    pub fn greet() -> string {
        rtn "woof"
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$D/neg_signature.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    pub fn greet(count : int) -> string {
        rtn "woof"
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$D/neg_ret.rsf" <<'EOF'
pub interface Greeter {
    fn greet() -> string
}

class Dog : Greeter {
    pub fn greet() -> int {
        rtn 1
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$D/neg_new.rsf" <<'EOF'
pub interface Greeter {
    fn greet() -> string
}

fn main() -> void {
    var g = new Greeter()
    rtn
}
EOF

cat > "$D/neg_superclass.rsf" <<'EOF'
class Base {
    pub fn id() -> int {
        rtn 1
    }
}

class Child : Base {
    pub fn name() -> string {
        rtn "c"
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$D/neg_field.rsf" <<'EOF'
pub interface Greeter {
    imutvar name : string = "rex"
    fn greet() -> string
}

fn main() -> void {
    rtn
}
EOF

cat > "$D/neg_conflict.rsf" <<'EOF'
pub interface A {
    fn say() -> string {
        rtn "a"
    }
}

pub interface B {
    fn say() -> string {
        rtn "b"
    }
}

class C : A, B {
}

fn main() -> void {
    rtn
}
EOF

echo "探针已生成：$(ls "$D"/*.rsf | wc -l) 个"
