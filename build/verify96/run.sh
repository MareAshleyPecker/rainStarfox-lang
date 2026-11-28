#!/bin/sh
# 判据台 96：Exception（结构化错误串）
#
# rsf 的错误通道只有 String，所以标准库提供的是「格式 + 构造/解析助手」。
# 核对：构造（io / range）、解析（kindOf / messageOf / isKind）、断言（check*）。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/proj.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > out.log 2>&1 || { cat out.log; fail "Exception 用例跑失败"; }
OUT=$(grep -v '^\[rsf\] 警告' out.log)
EXPECT='io
a.txt: 打不开
true
range: 越界了'
[ "$OUT" = "$EXPECT" ] || { echo "--- 实际 ---"; echo "$OUT"; echo "--- 期望 ---"; echo "$EXPECT"; fail "输出不符"; }

echo "verify96: 全过"
