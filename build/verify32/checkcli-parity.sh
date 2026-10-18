#!/usr/bin/env bash
# 阶段 32c：`CheckCli` 的两端对拍。
#
# 口径（DESIGN-32 §1 + §3）：
#   * 词法/语法类语料（Kotlin 侧报 error[lexer]/error[parser]）→ 自举侧必须**逐字节相同**（stderr + 退出码）
#   * 语义类语料（error[checker]）→ 32c 阶段自举侧还没有 Checker，只统计「待实现」份数，不计失败
#   * 正例 → 两侧都打 `[rsf] 检查通过: <文件名>`、退出 0
#
# 跑：bash build/verify32/checkcli-parity.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
cd "$REPO" || exit 2

JAR="build/libs/rainStarfox-0.9.0.jar"
SELF="$HERE/self"
OUT="$HERE/checkcli-parity"
mkdir -p "$OUT"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

java -jar "$JAR" build rsfSrc/bootstrap/CheckCli.rsf -o "$SELF" > /dev/null 2>&1 \
  || { echo "自举侧 CheckCli.rsf 编译失败"; exit 1; }

kt_out() { java $JOPT -jar "$JAR" check "$1" 2>&1 >/dev/null; }
kt_err() { java $JOPT -jar "$JAR" check "$1" 2>/dev/null; }
self_run() {
  java $JOPT -Drsf.check.file="$1" -cp "build/verify32/self;$JAR" CheckCli > "$OUT/o.txt" 2> "$OUT/e.txt"
  rtn=$?
  cat "$OUT/o.txt" "$OUT/e.txt"
  return $rtn
}

parser_total=0; parser_bad=0; checker_todo=0; ok_total=0; ok_bad=0

for f in "$HERE"/corpus/*.rsf; do
  n="$(basename "$f")"
  kind="$(kt_out "$f" | sed -n '1p')"
  if printf '%s' "$kind" | grep -q "error\[parser\]\|error\[lexer\]"; then
    parser_total=$((parser_total+1))
    kt_out "$f" > "$OUT/$n.kt.err"; kt_code=$?
    self_run "$f" > "$OUT/$n.self.err" 2>&1; self_code=$?
    if [ "$kt_code" != "$self_code" ] || ! diff -q "$OUT/$n.kt.err" "$OUT/$n.self.err" > /dev/null; then
      parser_bad=$((parser_bad+1))
      echo "   $n 不同（退出码 $kt_code vs $self_code）："
      diff -u "$OUT/$n.kt.err" "$OUT/$n.self.err" | sed -n '3,10p' | sed 's/^/      /'
    fi
  else
    checker_todo=$((checker_todo+1))
  fi
done

for f in "$HERE"/okcorpus/*.rsf; do
  [ -e "$f" ] || continue
  n="$(basename "$f")"
  ok_total=$((ok_total+1))
  # 正例只比「第一行」（`[rsf] 检查通过: <文件名>`）；函数签名清单与入口检查属于阶段 36 的诊断明细
  java $JOPT -jar "$JAR" check "$f" 2>/dev/null | sed -n '1p' > "$OUT/$n.kt.out"; kt_code=${PIPESTATUS[0]}
  self_run "$f" > "$OUT/$n.self.out" 2>&1; self_code=$?
  sed -i '1!d' "$OUT/$n.self.out"
  if [ "$kt_code" != "$self_code" ] || ! diff -q "$OUT/$n.kt.out" "$OUT/$n.self.out" > /dev/null; then
    ok_bad=$((ok_bad+1))
    echo "   正例 $n 不同："
    diff -u "$OUT/$n.kt.out" "$OUT/$n.self.out" | sed -n '3,10p' | sed 's/^/      /'
  fi
done

echo "==== CheckCli 对拍：parser/lexer $parser_total 份（不同 $parser_bad）  语义待实现 $checker_todo 份  正例 $ok_total 份（不同 $ok_bad）===="
[ $((parser_bad+ok_bad)) -eq 0 ] && [ "$parser_total" -gt 0 ]
