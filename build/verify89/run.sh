#!/bin/sh
# 判据台 89：rsf run 的 cargo run 语义
#
# 核对：
#   1. 项目目录里 `rsf run` 先构建再运行（输出里有构建日志，也有程序输出）；
#   2. 多个可运行产物时必须用 --bin 指定，不带就报错并列出来；
#   3. 单个文件模式不受影响（不构建、直接解释执行）；
#   4. jar 产物用 java -jar 运行。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/proj" "$WORK/proj"
cd "$WORK/proj"
fail() { echo "FAIL: $1" >&2; exit 1; }

echo "== 不带 --bin：应报错并列出可选产物 =="
"$JAVA" -cp "$CP" org.example.MainKt run > multi.log 2>&1 && fail "多个可运行产物居然直接跑了"
grep -q '用 rsf run --bin' multi.log || { cat multi.log; fail "多个产物时没提示 --bin"; }

echo "== --bin main：先构建再运行 =="
"$JAVA" -cp "$CP" org.example.MainKt run --bin main > main.log 2>&1 || { cat main.log; fail "--bin main 跑失败"; }
grep -q '目标 main' main.log || fail "没有先构建"
grep -q 'exe 跑起来了' main.log || fail "没有运行 exe"

echo "== --bin tool：jar 用 java -jar =="
"$JAVA" -cp "$CP" org.example.MainKt run --bin tool > tool.log 2>&1 || { cat tool.log; fail "--bin tool 跑失败"; }
grep -q 'java -jar' tool.log || fail "jar 没有用 java -jar 运行"
grep -q 'jar 跑起来了' tool.log || fail "jar 没有跑出输出"

echo "== 单个文件模式不受影响 =="
"$JAVA" -cp "$CP" org.example.MainKt run src/main.rsf > single.log 2>&1 || { cat single.log; fail "单文件模式跑失败"; }
grep -q '构建顺序' single.log && fail "单文件模式不该触发构建"
grep -q 'exe 跑起来了' single.log || fail "单文件模式没跑起来"

echo "verify89: 全过"
