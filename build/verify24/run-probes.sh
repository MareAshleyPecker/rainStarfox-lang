#!/usr/bin/env bash
# 阶段 24（`as` / `?as` / `is` / `!is`）探针台。
#
# 用法：
#   bash build/verify24/run-probes.sh check   # 语法+语义：正例 check 必须过，负例必须拒
#   bash build/verify24/run-probes.sh golden  # 解释器 stdout vs 冻结金标（expected/*.txt）
#   bash build/verify24/run-probes.sh warn    # p8 这类「允许写泛型实参」必须发 stderr 警告
#   bash build/verify24/run-probes.sh run     # 正例两端对拍：解释器 stdout vs 字节码 stdout 逐字节相同
#   bash build/verify24/run-probes.sh all     # 四段都跑
#
# 与 verify21/verify25 同一口径：同一份源码，解释器 stdout 与字节码 stdout 必须逐字节相同。
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify24
P="$ROOT/probes"
MODE="${1:-all}"
fail=0

POS="$(ls "$P" | grep '^p.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"
NEG="$(ls "$P" | grep '^n.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"

if [ "$MODE" = "check" ] || [ "$MODE" = "all" ]; then
  echo "=== 正例：check 必须通过 ==="
  for p in $POS; do
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" check "$P/$p.rsf" > "$ROOT/$p.check.log" 2>&1
    c=$?
    if [ "$c" = 0 ]; then echo "   $p  check=0"; else
      echo "   $p  check=$c  ← 首条: $(head -1 "$ROOT/$p.check.log")"; fail=$((fail+1))
    fi
  done
  echo
  echo "=== 负例：check 必须被拒 ==="
  for n in $NEG; do
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" check "$P/$n.rsf" > "$ROOT/$n.check.log" 2>&1
    c=$?
    if [ "$c" != 0 ]; then echo "   $n  check=$c  首条: $(head -1 "$ROOT/$n.check.log")"; else
      echo "   $n  check=0  ← 漏判！"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "golden" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 冻结金标（expected/，只比 stdout）==="
  for p in $POS; do
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" run "$P/$p.rsf" > "$ROOT/$p.golden.txt" 2> "$ROOT/$p.golden.err"
    gec=$?
    if [ "$gec" = 0 ] && diff -q "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" >/dev/null 2>&1; then
      echo "   $p  同"
    else
      echo "   $p  异(exit=$gec)"; diff "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" | head -4; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "warn" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 泛型实参写了就要警告（stderr 非空）==="
  for p in p8_generic_erasure; do
    if [ -s "$ROOT/$p.golden.err" ]; then
      echo "   $p  stderr 非空（OK）：$(head -1 "$ROOT/$p.golden.err")"
    else
      echo "   $p  没有警告 ← 漏了"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "run" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 字节码 stdout ==="
  for p in $POS; do
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" run "$P/$p.rsf" > "$ROOT/$p.interp.txt" 2>/dev/null
    iec=$?
    rm -rf "$ROOT/out/$p"; mkdir -p "$ROOT/out/$p"
    java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build "$P/$p.rsf" -o "$ROOT/out/$p" > "$ROOT/$p.build.log" 2>&1
    bec=$?
    if [ "$bec" = 0 ]; then
      java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$ROOT/out/$p" "$p" > "$ROOT/$p.jvm.txt" 2> "$ROOT/$p.jvm.err"
      jec=$?
    else
      jec="-"
    fi
    if [ "$bec" = 0 ] && diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.jvm.txt" >/dev/null 2>&1; then
      d="同"
    else
      d="异"; fail=$((fail+1))
    fi
    printf '   %-24s interp(exit=%s) build(exit=%s) jvm(exit=%s) stdout=%s\n' "$p" "$iec" "$bec" "$jec" "$d"
  done
fi

echo
echo "==== 失败项合计: $fail ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
