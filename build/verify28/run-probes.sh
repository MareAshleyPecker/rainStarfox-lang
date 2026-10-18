#!/usr/bin/env bash
# 阶段 28（标签名：反引号 + RsfLabels 元数据）探针台。
#
# 用法：
#   bash build/verify28/run-probes.sh check    # 正例 check 必须过；负例 check 必须拒
#   bash build/verify28/run-probes.sh msg      # 负例首条报错必须含约定的文案
#   bash build/verify28/run-probes.sh golden   # 解释器 stdout vs 冻结金标（expected/*.txt）
#   bash build/verify28/run-probes.sh run      # 正例两端对拍：解释器 stdout vs 字节码 stdout
#   bash build/verify28/run-probes.sh all
#
# 编码口径（2026-10-18 修）：金标统一 UTF-8 + LF；java 调用一律挂
#   -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8（本机 JDK 认这两个属性），
# 捕获后再 `tr -d '\r'` 去掉 CRLF —— 这样在 GBK 默认编码的 Windows 上也稳定。
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify28
P="$ROOT/probes"
MODE="${1:-all}"
fail=0
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

POS="$(ls "$P" | grep '^p.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"
NEG="$(ls "$P/neg" | grep '^n.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"

# 负例 → 约定文案（工单 §3 定的逐字文案，Checker/Parser 里必须原样出现）
msg_of() {
  case "$1" in
    n1_empty)          echo "标签名不能是空的" ;;
    n2_unclosed)       echo "反引号标签名没有收尾" ;;
    n3_expr_position)  echo "反引号只用来写标签名" ;;
    n4_dup)            echo "标签 'finish' 在函数 'main' 里重复定义" ;;
    n5_cross_fn)       echo "goto 的标签 '目标' 在函数 'main' 里没有定义" ;;
    *)                 echo "" ;;
  esac
}

if [ "$MODE" = "check" ] || [ "$MODE" = "all" ]; then
  echo "=== 正例：check 必须通过 ==="
  for p in $POS; do
    java $JOPT -jar "$JAR" check "$P/$p.rsf" > "$ROOT/$p.check.log" 2>&1
    c=$?
    if [ "$c" = 0 ]; then echo "   $p  check=0"; else
      echo "   $p  check=$c  ← 首条: $(head -1 "$ROOT/$p.check.log")"; fail=$((fail+1))
    fi
  done
  echo
  echo "=== 负例：check 必须被拒 ==="
  for n in $NEG; do
    java $JOPT -jar "$JAR" check "$P/neg/$n.rsf" > "$ROOT/$n.check.log" 2>&1
    c=$?
    if [ "$c" != 0 ]; then echo "   $n  check=$c"; else
      echo "   $n  check=0  ← 漏判！"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "msg" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 负例：报错文案必须与约定一致 ==="
  for n in $NEG; do
    want="$(msg_of "$n")"
    if [ -z "$want" ]; then echo "   $n  ← 脚本里没登记文案"; fail=$((fail+1)); continue; fi
    if grep -qF "$want" "$ROOT/$n.check.log"; then
      echo "   $n  同：$want"
    else
      echo "   $n  ← 文案不符，期望含：$want"; head -2 "$ROOT/$n.check.log"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "golden" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 冻结金标（expected/，只比 stdout）==="
  for p in $POS; do
    java $JOPT -jar "$JAR" run "$P/$p.rsf" 2> "$ROOT/$p.golden.err" | tr -d '\r' > "$ROOT/$p.golden.txt"
    gec=${PIPESTATUS[0]}
    if [ "$gec" = 0 ] && diff -q "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" >/dev/null 2>&1; then
      echo "   $p  同"
    else
      echo "   $p  异(exit=$gec)"; diff "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" | head -4; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "run" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 字节码 stdout ==="
  for p in $POS; do
    java $JOPT -jar "$JAR" run "$P/$p.rsf" 2>/dev/null | tr -d '\r' > "$ROOT/$p.interp.txt"
    iec=${PIPESTATUS[0]}
    rm -rf "$ROOT/out/$p"; mkdir -p "$ROOT/out/$p"
    java $JOPT -jar "$JAR" build "$P/$p.rsf" -o "$ROOT/out/$p" > "$ROOT/$p.build.log" 2>&1
    bec=$?
    if [ "$bec" = 0 ]; then
      java $JOPT -cp "$ROOT/out/$p" "$p" 2>&1 | tr -d '\r' > "$ROOT/$p.jvm.txt"
      jec=${PIPESTATUS[0]}
    else
      jec="-"
    fi
    if [ "$bec" = 0 ] && diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.jvm.txt" >/dev/null 2>&1; then
      d="同"
    else
      d="异"; fail=$((fail+1))
    fi
    printf '   %-20s interp(exit=%s) build(exit=%s) jvm(exit=%s) stdout=%s\n' "$p" "$iec" "$bec" "$jec" "$d"
  done
  echo
  echo "=== 正例：class 文件里必须能读到 RsfLabels（javap 抽查 p3）==="
  if javap -v -p -cp "$ROOT/out/p3_two_methods" p3_two_methods 2>/dev/null | grep -q "RsfLabels"; then
    echo "   p3  见 RsfLabels"
  else
    echo "   p3  ← class 里没有 RsfLabels"; fail=$((fail+1))
  fi
  if ls "$ROOT/out/p3_two_methods"/RsfLabels.class >/dev/null 2>&1; then
    echo "   ← 不该生成 RsfLabels.class（DESIGN-28 §2）"; fail=$((fail+1))
  else
    echo "   p3  没有 RsfLabels.class（符合设计）"
  fi
fi

echo
echo "==== 失败项合计: $fail ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
