#!/usr/bin/env bash
# 第 2 刀 J1/J3 对拍（不跑 gradle）：
#   A  = Kotlin 版 jar 编出的自举后端
#   J1 = A 编 9 个自举源的产物
#   K  = Kotlin 版 jar 直接编同样 9 个源的产物
#   B  = J1/BackendCli（A 自己编出来的后端）
#   C  = B 再编一遍 9 个源的产物（J3 不动点：J1 与 C 必须逐字节一致）
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify21/bootbackend/j1
CLI=rsfSrc/bootstrap
SRCS="BackendCli Backend InterpCli Interp ParserCli Parser LexerCli Lexer Sources"

rm -rf "$ROOT"
mkdir -p "$ROOT"

echo "=== A：Kotlin 版编出的自举后端 ==="
java -Dstdout.encoding=UTF-8 -jar "$JAR" build "$CLI/BackendCli.rsf" -o "$ROOT/A" > "$ROOT/A.log" 2>&1
echo "A exit=$? classes=$(find "$ROOT/A" -name '*.class' | wc -l)"

echo
echo "=== J1（A 编）/ K（Kotlin 直接编）==="
printf '%-12s %-8s %-8s %-10s %s\n' 源 J1类数 K类数 diff条目 首个差异
for b in $SRCS; do
  java -Dstdout.encoding=UTF-8 -Drsf.backend.file="$CLI/$b.rsf" -Drsf.backend.out="$ROOT/J1/$b" \
       -cp "$ROOT/A;$JAR" BackendCli > "$ROOT/J1_$b.log" 2>&1
  ec1=$?
  java -Dstdout.encoding=UTF-8 -jar "$JAR" build "$CLI/$b.rsf" -o "$ROOT/K/$b" > "$ROOT/K_$b.log" 2>&1
  ec2=$?
  n1=$(find "$ROOT/J1/$b" -name '*.class' 2>/dev/null | wc -l)
  n2=$(find "$ROOT/K/$b" -name '*.class' 2>/dev/null | wc -l)
  nd=$(diff -r -q "$ROOT/J1/$b" "$ROOT/K/$b" 2>/dev/null | wc -l)
  first=$(diff -r -q "$ROOT/J1/$b" "$ROOT/K/$b" 2>/dev/null | head -1 | sed 's#.*/##')
  printf '%-12s %-8s %-8s %-10s %s (exit %s/%s)\n' "$b" "$n1" "$n2" "$nd" "$first" "$ec1" "$ec2"
done

echo
echo "=== J3：B（= A 编出的后端）再编 9 个源，与 J1 逐字节比对 ==="
B="$ROOT/J1/BackendCli"
for b in $SRCS; do
  java -Dstdout.encoding=UTF-8 -Drsf.backend.file="$CLI/$b.rsf" -Drsf.backend.out="$ROOT/C/$b" \
       -cp "$B;$JAR" BackendCli > "$ROOT/C_$b.log" 2>&1
  printf '%-12s exit=%s classes=%s\n' "$b" "$?" "$(find "$ROOT/C/$b" -name '*.class' 2>/dev/null | wc -l)"
done
n1=$(find "$ROOT/J1" -name '*.class' | wc -l)
nd=$(diff -r -q "$ROOT/J1" "$ROOT/C" 2>/dev/null | wc -l)
echo "J1 类总数=$n1  diff -r 差异条目=$nd"
diff -r -q "$ROOT/J1" "$ROOT/C" 2>&1 | head -20 | sed 's/^/   /'
echo "=== 完毕 ==="
