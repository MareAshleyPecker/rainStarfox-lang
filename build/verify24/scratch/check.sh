#!/usr/bin/env bash
# 阶段 24 的父代理加试语料（子代理看不到）：只验 5 个探针语料没覆盖到的点。
#   1. 失败文案里的包名（`package demo` → `demo.C`）
#   2. 泛型擦除：`Box&<int>` 的值 `is Box&<string>` 必须 true
#   3. JDK 对象被当成 rsf 类：文案印 `java.lang.String`
#   4. 多维数组：`int[][]` 的 `['[[I']` / `['[I']` 形态
#   5. `is` 直接用在 if / while 条件里
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
D=build/verify24/scratch
mkdir -p "$D"

cat > "$D/t1_pkg.rsf" <<'EOF'
package demo
open class A { }
class B : A() { }
class C : A() { }
fun main() -> void {
    var a : A = new C()
    try {
        var b : B = a as B
        println("no")
    } catch (e : string) { println(e) }
    rtn
}
EOF

cat > "$D/t2_erasure.rsf" <<'EOF'
open class Box&<T>(v : T) { var value : T = v }
fun main() -> void {
    var b : Box&<int> = new Box&<int>(5)
    println("" + (b is Box&<string>))
    var o : Box&<string> = b as Box&<string>
    println("" + o.value)
    rtn
}
EOF

cat > "$D/t3_jdk.rsf" <<'EOF'
open class A { }
fun main() -> void {
    var s : string = "x"
    try {
        var a : A = s as A
        println("no")
    } catch (e : string) { println(e) }
    var s2 : ?string = null
    var p : ?A = s2 ?as A
    if (p == null) { println("safe-null") }
    rtn
}
EOF

cat > "$D/t4_array2d.rsf" <<'EOF'
fun main() -> void {
    var g : int[][] = new int[2][2]
    println("" + (g is int[][]))
    println("" + (g is int[]))
    try {
        var h : int[] = g as int[]
        println("no")
    } catch (e : string) { println(e) }
    rtn
}
EOF

cat > "$D/t5_cond.rsf" <<'EOF'
open class A { }
class B : A() { }
fun main() -> void {
    var a : A = new B()
    if (a is B) { println("yes") }
    if (a !is B) { println("no") } else { println("else-branch") }
    var i = 0
    while (a is A && i < 2) {
        println("loop")
        i = i + 1
    }
    rtn
}
EOF

fail=0
for f in "$D"/t*.rsf; do
  b=$(basename "$f" .rsf)
  pkg=$(grep -m1 '^package ' "$f" | sed 's/^package[[:space:]]*//' | tr -d '\r')
  cls="$b"; [ -n "${pkg:-}" ] && cls="$pkg.$b"
  java -jar "$JAR" check "$f" > "$D/$b.check.log" 2>&1 || { echo "   $b check 失败：$(head -1 "$D/$b.check.log")"; fail=$((fail+1)); continue; }
  java -jar "$JAR" run "$f" > "$D/$b.interp.txt" 2>/dev/null
  iec=$?
  rm -rf "$D/out/$b"; mkdir -p "$D/out/$b"
  java -jar "$JAR" build "$f" -o "$D/out/$b" > "$D/$b.build.log" 2>&1
  bec=$?
  if [ "$bec" = 0 ]; then java -cp "$D/out/$b" "$cls" > "$D/$b.jvm.txt" 2>&1; jec=$?; else jec="-"; fi
  if [ "$bec" = 0 ] && diff -q "$D/$b.interp.txt" "$D/$b.jvm.txt" >/dev/null 2>&1; then d="同"; else d="异"; fail=$((fail+1)); fi
  printf '   %-12s interp(exit=%s) build(exit=%s) jvm(exit=%s) stdout=%s\n' "$b" "$iec" "$bec" "$jec" "$d"
  echo "      ---- stdout ----"
  iconv -f CP936 -t UTF-8 "$D/$b.interp.txt" 2>/dev/null | tr -d '\r' | sed 's/^/      /'
done
echo "==== 加试失败项: $fail ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
