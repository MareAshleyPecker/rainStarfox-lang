#!/usr/bin/env bash
# 阶段 29（关键字改名 fun → fn）判据台。
#
# 用法：bash build/verify29/run-probes.sh all
#
# 只有一条负例：写 `fun main()` 必须被拒，并且报错文案是约定的那一句。
# 其余判据（gradle 全绿、三个探针台、自举三阶段）由 knife-verify / 既有脚本覆盖。
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify29
P="$ROOT/probes"
fail=0
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

echo "=== 负例：旧关键字 fun 必须被拒，且文案是改名声 ==="
for f in "$P"/neg/n*.rsf; do
  n="$(basename "$f" .rsf)"
  java $JOPT -jar "$JAR" check "$f" > "$ROOT/$n.check.log" 2>&1
  c=$?
  if [ "$c" = 0 ]; then
    echo "   $n  check=0  ← 漏判！旧关键字居然还能过"
    fail=$((fail + 1))
    continue
  fi
  if grep -qF "'fun' 已改名为 'fn'" "$ROOT/$n.check.log"; then
    echo "   $n  check=$c  同：'fun' 已改名为 'fn'"
  else
    echo "   $n  check=$c  ← 文案不符，首条：$(head -1 "$ROOT/$n.check.log")"
    fail=$((fail + 1))
  fi
done

echo
echo "==== 失败项合计: $fail ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
