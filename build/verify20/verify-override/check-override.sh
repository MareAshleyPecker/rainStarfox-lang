#!/usr/bin/env bash
# 父代理独立复验 override（不看子代理的探针，自己另造一套）
# 用法：bash build/verify20/verify-override/check-override.sh
set -u
cd "$(dirname "$0")/../../.." || exit 1
JAR=build/libs/rainStarfox-0.9.0.jar
DIR=build/verify20/verify-override
SRC=$DIR/src
mkdir -p "$SRC" "$DIR/out"
JF=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)
PASS=0; FAIL=0
norm() { printf '%s' "$1" | tr -d '\r'; }
ok()   { PASS=$((PASS+1)); echo "  PASS  $1"; }
no()   { FAIL=$((FAIL+1)); echo "  FAIL  $1"; echo "        $2"; }

# ---------- 探针源 ----------
cat > "$SRC/p_class.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
    fn shout(name : string) -> string {
        rtn greet(name) + "!"
    }
}

class Dog(name : string) : Greeter {
    override pub fn greet(n : string) -> string {
        rtn "woof " + n
    }
}

fn main() -> void {
    var g : Greeter = new Dog("rex")
    println(g.greet("bob"))
    println(g.shout("bob"))
    rtn
}
EOF

cat > "$SRC/p_iface.rsf" <<'EOF'
pub interface Named {
    fn name() -> string
}

pub interface Person : Named {
    override pub fn name() -> string {
        rtn "p"
    }
}

class P : Person {
}

fn main() -> void {
    var n : Named = new P()
    println(n.name())
    rtn
}
EOF

cat > "$SRC/p_order1.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    override pub fn greet(name : string) -> string {
        rtn "woof " + name
    }
}

fn main() -> void {
    println(new Dog().greet("bob"))
    rtn
}
EOF

cat > "$SRC/p_order2.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    pub override fn greet(name : string) -> string {
        rtn "woof " + name
    }
}

fn main() -> void {
    println(new Dog().greet("bob"))
    rtn
}
EOF

cat > "$SRC/p_nokw.rsf" <<'EOF'
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

cat > "$SRC/p_jdk.rsf" <<'EOF'
import java.lang.Runnable

class Robot : Runnable {
    override pub fn run() -> void {
        println("running")
    }
}

fn main() -> void {
    var r : Runnable = new Robot()
    r.run()
    rtn
}
EOF

cat > "$SRC/n_toplevel.rsf" <<'EOF'
override fn f() -> void {
    rtn
}
EOF

cat > "$SRC/n_ctor.rsf" <<'EOF'
class A {
    override constructor() {
        rtn
    }
}
EOF

cat > "$SRC/n_class_field.rsf" <<'EOF'
class A {
    override var x = 1
}
EOF

cat > "$SRC/n_nocover_class.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    pub fn greet(name : string) -> string {
        rtn "woof"
    }
    override pub fn bark() -> void {
        rtn
    }
}
EOF

cat > "$SRC/n_nocover_iface.rsf" <<'EOF'
pub interface Named {
    fn name() -> string
}

pub interface Person : Named {
    override fn age() -> int {
        rtn 1
    }
}
EOF

cat > "$SRC/n_nocover_jdk.rsf" <<'EOF'
import java.lang.Runnable

class Robot : Runnable {
    pub fn run() -> void {
        rtn
    }
    override pub fn walk() -> void {
        rtn
    }
}
EOF

# ---------- 正例：两端 stdout 与退出码必须都等于写死的期望 ----------
ok_case() {
  local name=$1 file=$2 want=$3
  local ro rc bo bc jc jo
  ro=$(java "${JF[@]}" -jar "$JAR" run "$file" 2>&1); rc=$?
  bo=$(java "${JF[@]}" -jar "$JAR" build "$file" -o "$DIR/out/$name" -n "P_$name" 2>&1); bc=$?
  jo=$(java "${JF[@]}" -cp "$DIR/out/$name" "P_$name" 2>&1); jc=$?
  local rn jn wn; rn=$(norm "$ro"); jn=$(norm "$jo"); wn=$(printf '%s' "$want" | tr -d '\r')
  if [ "$rc" = 0 ] && [ "$bc" = 0 ] && [ "$jc" = 0 ] && [ "$rn" = "$wn" ] && [ "$jn" = "$wn" ]; then
    ok "$name（run 与 build+java 都 = 期望）"
  else
    no "$name" "rc=$rc/$bc/$jc run=[$rn] java=[$jn] want=[$wn] | build: $(norm "$bo" | head -3 | tr '\n' ' ')"
  fi
}

echo "== 正例（override 写了必须真覆盖，且两端一致）"
ok_case p_class    "$SRC/p_class.rsf"    $'woof bob\nwoof bob!'
ok_case p_iface    "$SRC/p_iface.rsf"    "p"
ok_case p_order1   "$SRC/p_order1.rsf"   "woof bob"
ok_case p_order2   "$SRC/p_order2.rsf"   "woof bob"
ok_case p_jdk      "$SRC/p_jdk.rsf"      "running"
echo "== 不写 override 也要照旧通过（可选性）"
ok_case p_nokw     "$SRC/p_nokw.rsf"     "woof bob"

# ---------- 负例：exit=1 且命中文案 ----------
neg_case() {
  local name=$1 file=$2 kw=$3
  local out rc
  out=$(java "${JF[@]}" -jar "$JAR" check "$file" 2>&1); rc=$?
  local n; n=$(norm "$out")
  if [ "$rc" = 1 ] && printf '%s' "$n" | grep -qF -- "$kw"; then
    ok "$name（exit=1 且命中「$kw」）"
  else
    no "$name" "rc=$rc | $(printf '%s' "$n" | head -3 | tr '\n' ' ')"
  fi
}

echo "== 负例（写了 override 但覆盖不到东西 / 写在不能写的地方）"
neg_case n_toplevel       "$SRC/n_toplevel.rsf"       "只有类/接口里的方法能写 override"
neg_case n_ctor           "$SRC/n_ctor.rsf"           "构造器不能写 override"
neg_case n_class_field    "$SRC/n_class_field.rsf"    "只能写在方法前面"
neg_case n_nocover_class  "$SRC/n_nocover_class.rsf"  "写了 override，但父类型里没有可覆盖的方法"
neg_case n_nocover_iface  "$SRC/n_nocover_iface.rsf"  "继承的接口里没有 age()"
neg_case n_nocover_jdk    "$SRC/n_nocover_jdk.rsf"    "接口里没有人要求 walk()"

# ---------- override 必须完全擦除：同名源文件、只差一个关键字 ----------
echo "== override 擦除性（同名源、只差关键字，三个 .class 必须逐字节相同）"
mkdir -p "$DIR/e1" "$DIR/e2"
cat > "$DIR/e1/OverrideErasure.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    override pub fn greet(name : string) -> string {
        rtn "woof " + name
    }
}

fn main() -> void {
    println(new Dog().greet("bob"))
    rtn
}
EOF
sed 's/override pub fn greet/pub fn greet/' "$DIR/e1/OverrideErasure.rsf" > "$DIR/e2/OverrideErasure.rsf"
java "${JF[@]}" -jar "$JAR" build "$DIR/e1/OverrideErasure.rsf" -o "$DIR/e1/out" -n OverrideErasure >/dev/null 2>&1
java "${JF[@]}" -jar "$JAR" build "$DIR/e2/OverrideErasure.rsf" -o "$DIR/e2/out" -n OverrideErasure >/dev/null 2>&1
diff -r "$DIR/e1/out" "$DIR/e2/out" > "$DIR/erasure.diff" 2>&1
if [ -s "$DIR/erasure.diff" ]; then
  no "擦除性" "$(head -5 "$DIR/erasure.diff" | tr '\n' ' ')"
else
  ok "擦除性（$(ls "$DIR/e1/out" | tr '\n' ' ') 逐字节相同）"
fi

echo
echo "==== 通过 $PASS / 失败 $FAIL ===="
[ "$FAIL" = 0 ]
