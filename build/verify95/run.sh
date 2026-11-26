#!/bin/sh
# 判据台 95：标准库常用面（ArrayList / Conv / Lang / Io / Vec 新方法 / Strings.lines）
#
# 一个程序把新增的常用 API 全用一遍，核对输出——既是回归，也是这些 API 的用法样例。
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

"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > out.log 2>&1 || { cat out.log; fail "标准库用例跑失败"; }
OUT=$(grep -v '^\[rsf\] 警告' out.log)
EXPECT='-1 3
4
a-b
3:b
fallback
x y
42 -1
true
3
a|b|c
缺省'
[ "$OUT" = "$EXPECT" ] || { echo "--- 实际 ---"; echo "$OUT"; echo "--- 期望 ---"; echo "$EXPECT"; fail "输出不符"; }
[ -f out.txt ] || fail "writeLines 没产出文件"
echo "verify95: 全过"
