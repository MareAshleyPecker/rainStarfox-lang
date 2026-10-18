#!/usr/bin/env bash
# override 边界探针（父代理第二轮：子代理 12 条测试没覆盖的角落）
set -u
cd "$(dirname "$0")/../../.." || exit 1
JAR=build/libs/rainStarfox-0.9.0.jar
DIR=build/verify20/verify-override
SRC=$DIR/edge
mkdir -p "$SRC" "$DIR/out"
JF=(-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8)
norm() { printf '%s' "$1" | tr -d '\r'; }

cat > "$SRC/e_multi.rsf" <<'EOF'
pub interface A {
    fn say() -> string
}

pub interface B {
    fn say() -> string
}

class C : A, B {
    override pub fn say() -> string {
        rtn "c"
    }
}

fn main() -> void {
    var a : A = new C()
    var b : B = new C()
    println(a.say())
    println(b.say())
    rtn
}
EOF

cat > "$SRC/e_default_conflict.rsf" <<'EOF'
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
    override pub fn say() -> string {
        rtn "c"
    }
}

fn main() -> void {
    println(new C().say())
    rtn
}
EOF

cat > "$SRC/e_default_override.rsf" <<'EOF'
pub interface Named {
    fn name() -> string {
        rtn "n"
    }
}

pub interface Person : Named {
    override fn name() -> string {
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

cat > "$SRC/e_private.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    prv override fn greet(name : string) -> string {
        rtn "woof"
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$SRC/e_private_nokw.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    prv fn greet(name : string) -> string {
        rtn "woof"
    }
}

fn main() -> void {
    rtn
}
EOF

cat > "$SRC/e_static.rsf" <<'EOF'
pub interface Greeter {
    fn greet(name : string) -> string
}

class Dog : Greeter {
    pub fn greet(name : string) -> string {
        rtn "woof"
    }
    override stc fn helper() -> void {
        rtn
    }
}

fn main() -> void {
    rtn
}
EOF

run_case() { # name file 期望 stdout（空 = 只要求两端一致且 exit 0）
  local name=$1 file=$2 want=${3-}
  local ro rc bo bc jo jc rn jn wn
  ro=$(java "${JF[@]}" -jar "$JAR" run "$file" 2>&1); rc=$?
  bo=$(java "${JF[@]}" -jar "$JAR" build "$file" -o "$DIR/out/$name" -n "P_$name" 2>&1); bc=$?
  jo=$(java "${JF[@]}" -cp "$DIR/out/$name" "P_$name" 2>&1); jc=$?
  rn=$(norm "$ro"); jn=$(norm "$jo"); wn=$(printf '%s' "$want" | tr -d '\r')
  echo "---- $name: run rc=$rc [$(printf '%s' "$rn" | tail -2 | tr '\n' '|')] build rc=$bc java rc=$jc [$(printf '%s' "$jn" | tail -2 | tr '\n' '|')]"
  if [ -n "$want" ] && { [ "$rn" != "$wn" ] || [ "$jn" != "$wn" ]; }; then
    echo "     ^^ 与期望不符（期望 [$wn]）"
  fi
}

echo "== e_multi（两个接口同签名，类写 override 一次满足）"
run_case e_multi "$SRC/e_multi.rsf" $'c\nc'
echo "== e_default_conflict（两个接口各带同签名默认方法，类写 override 拍板）"
run_case e_default_conflict "$SRC/e_default_conflict.rsf" "c"
echo "== e_default_override（接口 override 父接口的默认方法）"
run_case e_default_override "$SRC/e_default_override.rsf" "p"
echo "== e_private（写了 override 但方法私有：看是否被拦）"
run_case e_private "$SRC/e_private.rsf"
echo "== e_private_nokw（同一份、不写 override：作对照）"
run_case e_private_nokw "$SRC/e_private_nokw.rsf"
echo "== e_static（静态方法写 override）"
run_case e_static "$SRC/e_static.rsf"
