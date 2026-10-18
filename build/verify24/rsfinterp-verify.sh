#!/usr/bin/env bash
# 阶段 24d 判据：**自举解释器**（rsfSrc/bootstrap/Interp.rsf，经 InterpCli 驱动）
# 跑 `as` / `?as` / `is` / `!is` 的 stdout 必须与 Kotlin 解释器（`rsf run`）**逐字节相同**，
# 退出码也必须相同。这一列（rsf-interp）是 24d 唯一能验到 `Interp.rsf` 的判据——
# `run-probes.sh run` 只跑 Kotlin 后端，拿它当 24d 的判据会假绿。
#
# 用法：bash build/verify24/rsfinterp-verify.sh
#
# 口径：
#   * 语料 = verify24 的 9 份正例探针 + astparity/corpus 的 5 份 k* 语料（都是合法程序）。
#   * 8 份负例（n*.rsf）**不进这里**：自举链没有 Checker 层，负例在 rsf-interp 里
#     根本不会被拒（见 BOOTSTRAP.md「自举链现状」），它们的判据在 run-probes.sh 的 check 段。
#   * 只比 stdout + 退出码。真正的错误文案（未捕获的 `运行期错误: …`）在 stderr，
#     Kotlin 版那边会带栈，两边不逐字相等，所以不比 stderr。
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify24
I="$ROOT/InterpK"
fail=0
total=0

echo "=== 准备自举解释器（用 Kotlin 编 rsfSrc/bootstrap/InterpCli.rsf）==="
rm -rf "$I"; mkdir -p "$I"
java -jar "$JAR" build rsfSrc/bootstrap/InterpCli.rsf -o "$I" > "$ROOT/rsfinterp-build.log" 2>&1
bec=$?
echo "InterpCli build exit=$bec classes=$(find "$I" -name '*.class' 2>/dev/null | wc -l)"
if [ "$bec" != "0" ]; then
  echo "---- rsfinterp-build.log 末尾 ----"; tail -20 "$ROOT/rsfinterp-build.log"
  echo "==== 判据不过：自举解释器编不出来 ===="
  exit 1
fi

echo
echo "=== 逐份对拍（Kotlin 解释器 vs 自举解释器）==="
for src in "$ROOT"/probes/p*.rsf "$ROOT"/astparity/corpus/k*.rsf; do
  [ -f "$src" ] || continue
  name="$(basename "$src" .rsf)"
  total=$((total + 1))
  java -jar "$JAR" run "$src" > "$ROOT/rsfinterp_$name.kt.txt" 2> "$ROOT/rsfinterp_$name.kt.err"
  kec=$?
  java -Drsf.interp.file="$src" -cp "$I;$JAR" InterpCli \
      > "$ROOT/rsfinterp_$name.rsf.txt" 2> "$ROOT/rsfinterp_$name.rsf.err"
  rec=$?
  td="同"; [ -f "$ROOT/rsfinterp_$name.kt.txt" ] && diff -q "$ROOT/rsfinterp_$name.kt.txt" "$ROOT/rsfinterp_$name.rsf.txt" >/dev/null || td="异"
  if [ "$td" = "异" ] || [ "$kec" != "$rec" ]; then
    fail=$((fail + 1))
    echo "---- $name：stdout=$td  exit(kotlin=$kec rsf=$rec) ----"
    diff -u "$ROOT/rsfinterp_$name.kt.txt" "$ROOT/rsfinterp_$name.rsf.txt" | head -20
    echo "  [rsf stderr] $(tail -3 "$ROOT/rsfinterp_$name.rsf.err" | tr '\n' ' ')"
  else
    printf '%-28s stdout=同 exit=%s\n' "$name" "$kec"
  fi
done

echo
echo "==== 语料 $total 份  失败项合计: $fail ===="
[ "$fail" = "0" ] && echo "==== 阶段 24d 判决：OK ====" || echo "==== 阶段 24d 判决：不过 ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
