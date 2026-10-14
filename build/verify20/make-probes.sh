#!/usr/bin/env bash
# 生成阶段 20 前端验收探针（父代理独立编写，语义参照 DESIGN-20 的 §2/§3）。
set -eu
cd /d/java/rainStarfox || exit 1
D=build/verify20

cat > "$D/ok_impl.rsf" <<'EOF'
public interface Greeter {
    fun greet(name : string) -> string
}

class Dog : Greeter {
    public fun greet(name : string) -> string {
        rtn "woof " + name
    }
}

fun main() -> void {
    var g : Greeter = new Dog()
    println(g.greet("bob"))
    rtn
}
EOF

cat > "$D/ok_default.rsf" <<'EOF'
public interface Greeter {
    fun name() -> string
    fun shout() -> string {
        rtn name() + "!"
    }
}

class Dog : Greeter {
    public fun name() -> string {
        rtn "rex"
    }
}

fun main() -> void {
    var g : Greeter = new Dog()
    println(g.shout())
    rtn
}
EOF

cat > "$D/ok_extends.rsf" <<'EOF'
public interface Base {
    fun id() -> int
}

public interface Named : Base {
    fun name() -> string
}

class Dog : Named {
    public fun id() -> int {
        rtn 7
    }
    public fun name() -> string {
        rtn "rex"
    }
}

fun main() -> void {
    var d : Named = new Dog()
    println(intToString(d.id()) + "/" + d.name())
    rtn
}
EOF

cat > "$D/ok_types.rsf" <<'EOF'
public interface Greeter {
    fun greet() -> string
}

class Dog : Greeter {
    public fun greet() -> string {
        rtn "woof"
    }
}

fun hello(g : Greeter) -> string {
    rtn g.greet()
}

fun must() -> !Greeter {
    rtn new Dog()
}

fun main() -> void {
    println(hello(new Dog()))
    var g : Greeter = must() ?: new Dog()
    println(g.greet())
    rtn
}
EOF

cat > "$D/ok_nullable.rsf" <<'EOF'
public interface Greeter {
    fun greet() -> string
}

class Dog : Greeter {
    public fun greet() -> string {
        rtn "woof"
    }
}

fun pick(flag : bool) -> ?Greeter {
    if (flag) {
        rtn new Dog()
    }
    rtn null
}

fun main() -> void {
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
    public fun run() -> void {
        println("running")
    }
}

fun main() -> void {
    var j : Runnable = new Job()
    j.run()
    rtn
}
EOF

cat > "$D/neg_missing.rsf" <<'EOF'
public interface Greeter {
    fun greet() -> string
    fun shout() -> string
}

class Dog : Greeter {
    public fun greet() -> string {
        rtn "woof"
    }
}

fun main() -> void {
    rtn
}
EOF

cat > "$D/neg_signature.rsf" <<'EOF'
public interface Greeter {
    fun greet(name : string) -> string
}

class Dog : Greeter {
    public fun greet(count : int) -> string {
        rtn "woof"
    }
}

fun main() -> void {
    rtn
}
EOF

cat > "$D/neg_ret.rsf" <<'EOF'
public interface Greeter {
    fun greet() -> string
}

class Dog : Greeter {
    public fun greet() -> int {
        rtn 1
    }
}

fun main() -> void {
    rtn
}
EOF

cat > "$D/neg_new.rsf" <<'EOF'
public interface Greeter {
    fun greet() -> string
}

fun main() -> void {
    var g = new Greeter()
    rtn
}
EOF

cat > "$D/neg_superclass.rsf" <<'EOF'
class Base {
    public fun id() -> int {
        rtn 1
    }
}

class Child : Base {
    public fun name() -> string {
        rtn "c"
    }
}

fun main() -> void {
    rtn
}
EOF

cat > "$D/neg_field.rsf" <<'EOF'
public interface Greeter {
    imutvar name : string = "rex"
    fun greet() -> string
}

fun main() -> void {
    rtn
}
EOF

cat > "$D/neg_conflict.rsf" <<'EOF'
public interface A {
    fun say() -> string {
        rtn "a"
    }
}

public interface B {
    fun say() -> string {
        rtn "b"
    }
}

class C : A, B {
}

fun main() -> void {
    rtn
}
EOF

echo "探针已生成：$(ls "$D"/*.rsf | wc -l) 个"
