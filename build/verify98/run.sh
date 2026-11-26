#!/bin/sh
# 判据台 98：fail 带错误种类（阶段 84）
#
# 核对：
#   1. `fail io, "…"` / `fail range, "…"` 生成 `种类: 消息`（和 Exception 的格式一致）；
#   2. 种类名写错在**编译期**就报错，并列出可用的（不等运行期字符串比较）；
#   3. 字符串字面量形式 `fail "io", "…"` 也认。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/proj.rsf" "$HERE/bad.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > out.log 2>&1 || { cat out.log; fail "种类用例跑失败"; }
OUT=$(grep -v '^\[rsf\] 警告' out.log)
[ "$OUT" = "种类: range: n 不能为负：-1
第二个: io: 打不开" ] || { echo "$OUT"; fail "输出不符"; }

"$JAVA" -cp "$CP" org.example.MainKt check bad.rsf > bad.log 2>&1 && fail "写错种类居然编过了"
grep -q '不认识的错误种类' bad.log || { cat bad.log; fail "没报出种类错误"; }
grep -q 'io / parse / range' bad.log || { cat bad.log; fail "没列出可用种类"; }

echo "verify98: 全过"
