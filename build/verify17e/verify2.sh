#!/usr/bin/env bash
# 17e 独立验收（二）：多文件装载端到端 / 自举解释器运行比对 / 测试汇总
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify17e
A=$ROOT/A
K=$ROOT/K
M=$ROOT/multi

echo "=== 1) 多文件 SOURCE_IMPORT 端到端：Kotlin 装载器 vs rsf 装载器 ==="
rm -rf "$M"; mkdir -p "$M"
cat > "$M/lib.rsf" <<'EOF'
public fun twice(x : int) -> int {
    rtn x * 2
}

public class Point(int x0, int y0) {
    imutvar x : int = x0
    imutvar y : int = y0
    public fun sum() -> int {
        rtn x + y
    }
}
EOF
cat > "$M/App.rsf" <<'EOF'
import "lib.rsf"

public fun main() {
    var p = Point(3, 4)
    println("sum=" + intToString(p.sum()))
    println("twice=" + intToString(twice(21)))
}
EOF
java -jar "$JAR" build "$M/App.rsf" -o "$M/K" > "$M/K.log" 2>&1
echo "kotlin build exit=$? classes=$(find "$M/K" -name '*.class' 2>/dev/null | wc -l)"; head -3 "$M/K.log" | sed 's/^/   /'
java -Drsf.backend.file="$M/App.rsf" -Drsf.backend.out="$M/R" -cp "$A;$JAR" BackendCli > "$M/R.log" 2>&1
echo "rsf    build exit=$? classes=$(find "$M/R" -name '*.class' 2>/dev/null | wc -l)"; head -3 "$M/R.log" | sed 's/^/   /'
echo "--- 逐类比对（diff -r）---"
diff -r -q "$M/K" "$M/R" 2>&1 | sed 's/^/   /'
echo "   差异条目=$(diff -r -q "$M/K" "$M/R" 2>/dev/null | wc -l)"
echo "--- 运行产物比对 ---"
java -cp "$M/K;$JAR" App > "$M/runK.txt" 2> "$M/runK.err"; ek=$?
java -cp "$M/R;$JAR" App > "$M/runR.txt" 2> "$M/runR.err"; er=$?
if cmp -s "$M/runK.txt" "$M/runR.txt" && cmp -s "$M/runK.err" "$M/runR.err"; then s="stdout/stderr 一致"; else s="**不一致**"; fi
echo "   kotlin 产物 exit=$ek  rsf 产物 exit=$er  $s"
echo "   输出：$(tr '\n' '|' < "$M/runR.txt")   stderr=$(stat -c%s "$M/runR.err")B"

echo
echo "=== 2) 自举解释器运行比对（Kotlin 编的 InterpCli vs A 编的 InterpCli）==="
for f in main class generics errors stdlib arrays files enums; do
  src="rsfSrc/lang/$f.rsf"
  java -Drsf.interp.file="$src" -cp "$K/InterpCli;$JAR" InterpCli > "$ROOT/ik_$f.txt" 2> "$ROOT/ik_$f.err"; ek=$?
  java -Drsf.interp.file="$src" -cp "$ROOT/J1/InterpCli;$JAR" InterpCli > "$ROOT/ir_$f.txt" 2> "$ROOT/ir_$f.err"; er=$?
  if cmp -s "$ROOT/ik_$f.txt" "$ROOT/ir_$f.txt" && cmp -s "$ROOT/ik_$f.err" "$ROOT/ir_$f.err"; then s="一致"; else s="**不一致**"; fi
  printf '   %-9s kotlin(exit=%s) vs rsf(exit=%s) %s  out=%sB err=%sB\n' "$f" "$ek" "$er" "$s" "$(stat -c%s "$ROOT/ir_$f.txt")" "$(stat -c%s "$ROOT/ir_$f.err")"
done

echo
echo "=== 3) Gradle 测试结果汇总（build/test-results/test/*.xml）==="
awk 'match($0,/tests="[0-9]+"/){t+=substr($0,RSTART+7,RLENGTH-8)} match($0,/failures="[0-9]+"/){f+=substr($0,RSTART+10,RLENGTH-11)} match($0,/errors="[0-9]+"/){e+=substr($0,RSTART+8,RLENGTH-9)} END{printf "   tests=%d failures=%d errors=%d\n",t,f,e}' build/test-results/test/*.xml
for x in build/test-results/test/*.xml; do
  [ -e "$x" ] || continue
  grep -o 'name="[^"]*" tests="[0-9]*"' "$x" | head -1 | sed 's/^/   /'
done

echo
echo "=== 完毕 ==="
