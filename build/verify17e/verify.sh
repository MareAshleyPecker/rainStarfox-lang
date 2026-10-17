#!/usr/bin/env bash
# 17e 父代理独立验收：A(自举后端) / J1 / J3 不动点 / J2 字节比对 / 行号残差 / 八示例锚点
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify17e
CLI=rsfSrc/bootstrap

rm -rf "$ROOT/A" "$ROOT/J1" "$ROOT/C" "$ROOT/K"
mkdir -p "$ROOT"

echo "=== A：Kotlin 版编出的 rsf 后端 ==="
java -jar "$JAR" build "$CLI/BackendCli.rsf" -o "$ROOT/A" > "$ROOT/A.log" 2>&1
echo "A exit=$? classes=$(find "$ROOT/A" -name '*.class' | wc -l)"
head -2 "$ROOT/A.log" | sed 's/^/   /'

echo
echo "=== J1：用 A 的后端编 8 个自举源 ==="
for src in "$CLI"/*.rsf; do
  base=$(basename "$src" .rsf)
  out="$ROOT/J1/$base"
  java -Drsf.backend.file="$src" -Drsf.backend.out="$out" -cp "$ROOT/A;$JAR" BackendCli > "$ROOT/J1_$base.log" 2>&1
  ec=$?
  n=$(find "$out" -name '*.class' 2>/dev/null | wc -l)
  printf '%-14s exit=%-3s classes=%-4s %s\n' "$base" "$ec" "$n" "$(head -1 "$ROOT/J1_$base.log")"
done

echo
echo "=== J3：用 B（= A 编出的后端）再编 8 个源，与 J1 产物逐字节比对 ==="
B="$ROOT/J1/BackendCli"
for src in "$CLI"/*.rsf; do
  base=$(basename "$src" .rsf)
  out="$ROOT/C/$base"
  java -Drsf.backend.file="$src" -Drsf.backend.out="$out" -cp "$B;$JAR" BackendCli > "$ROOT/C_$base.log" 2>&1
  printf '%-14s exit=%-3s classes=%s\n' "$base" "$?" "$(find "$out" -name '*.class' 2>/dev/null | wc -l)"
done
n1=$(find "$ROOT/J1" -name '*.class' | wc -l)
nd=$(diff -r -q "$ROOT/J1" "$ROOT/C" 2>/dev/null | wc -l)
echo "J1 类总数=$n1  diff -r 差异条目=$nd"
diff -r -q "$ROOT/J1" "$ROOT/C" 2>&1 | head -20 | sed 's/^/   /'

echo
echo "=== J2：Kotlin 直接编 4 个 CLI 源，与 J1 产物比对 ==="
for cli in BackendCli InterpCli LexerCli ParserCli; do
  java -jar "$JAR" build "$CLI/$cli.rsf" -o "$ROOT/K/$cli" > "$ROOT/K_$cli.log" 2>&1
  echo "-- $cli: kotlin exit=$? classes=$(find "$ROOT/K/$cli" -name '*.class' 2>/dev/null | wc -l)   （对照 J1=$cli: $(find "$ROOT/J1/$cli" -name '*.class' | wc -l)）"
  diff -r -q "$ROOT/J1/$cli" "$ROOT/K/$cli" 2>&1 | sed 's/^/   /'
done

echo
echo "=== 残差逐类核实（J1/BackendCli vs K/BackendCli 的每个不同类）==="
if [ -d "$ROOT/K/BackendCli" ]; then
  diff -r -q "$ROOT/J1/BackendCli" "$ROOT/K/BackendCli" 2>/dev/null \
  | sed -n 's/^Files \(.*\) and \(.*\) differ$/\1|\2/p' \
  | while IFS='|' read -r fa fb; do
      rel=${fa#"$ROOT/J1/BackendCli/"}
      sa=$(stat -c%s "$fa"); sb=$(stat -c%s "$fb")
      la=$(javap -v -p "$fa" 2>/dev/null | grep -c '^ *line ')
      lb=$(javap -v -p "$fb" 2>/dev/null | grep -c '^ *line ')
      lva=$(javap -v -p "$fa" 2>/dev/null | grep -c 'LocalVariableTable')
      lvb=$(javap -v -p "$fb" 2>/dev/null | grep -c 'LocalVariableTable')
      printf '   %-24s rsf %6sB/line%-4s/LVT%-3s | kotlin %6sB/line%-4s/LVT%s\n' "$rel" "$sa" "$la" "$lva" "$sb" "$lb" "$lvb"
    done
fi

echo
echo "=== J5：九示例输出锚点（raw 与 LF 归一化两种口径）==="
declare -A ANCH=( [main]="606 28208184" [class]="295 D2ECC25E" [generics]="234 056FD6FE" [errors]="371 E9C8CDB6" [stdlib]="948 8F397D2A" [arrays]="1119 6D1A2875" [files]="666 6A0C940A" [enums]="763 3A33A504" [inherit]="242 8AF81766" )
for name in main class generics errors stdlib arrays files enums inherit; do
  java -jar "$JAR" run "rsfSrc/lang/$name.rsf" > "$ROOT/out_$name.txt" 2> "$ROOT/err_$name.txt"
  ec=$?
  sz=$(stat -c%s "$ROOT/out_$name.txt")
  h=$(sha256sum "$ROOT/out_$name.txt" | cut -c1-8 | tr 'a-z' 'A-Z')
  tr -d '\r' < "$ROOT/out_$name.txt" > "$ROOT/out_${name}_lf.txt"
  sz2=$(stat -c%s "$ROOT/out_${name}_lf.txt")
  h2=$(sha256sum "$ROOT/out_${name}_lf.txt" | cut -c1-8 | tr 'a-z' 'A-Z')
  esz=$(stat -c%s "$ROOT/err_$name.txt")
  printf '   %-9s exit=%s raw=%sB/%s lf=%sB/%s stderr=%sB 锚点=%s\n' "$name" "$ec" "$sz" "$h" "$sz2" "$h2" "$esz" "${ANCH[$name]}"
done

echo
echo "=== 完毕 ==="
