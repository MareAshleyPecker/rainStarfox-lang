#!/bin/sh
# 判据台 92：LLVM 文本 IR 后端（--ll）
#
# 核对：
#   1. 生成的 .ll 能被 LLVM 汇编（clang 一步，或 llc + gcc）；
#   2. 跑出来的输出与 C 后端一致（这是"两个后端语义一致"的锚）；
#   3. 缺少 LLVM 工具链时明确跳过（不让判据因为环境缺件而假红）。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

HAVE_CLANG=0
command -v clang >/dev/null 2>&1 && HAVE_CLANG=1
HAVE_LLC=0
for c in llc llc-14 llc-15 llc-16 llc-17 llc-18; do
    command -v "$c" >/dev/null 2>&1 && HAVE_LLC=1
done
if [ "$HAVE_CLANG" = 0 ] && [ "$HAVE_LLC" = 0 ]; then
    echo "verify92: 跳过（没有 clang 也没有 llc）"
    exit 0
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/main.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

"$JAVA" -cp "$CP" org.example.MainKt build main.rsf --ll --link -o ll -n app > build.log 2>&1 || { cat build.log; fail "--ll 构建失败"; }
[ -f ll/app.ll ] || fail "没有生成 .ll"
OUT=$(./ll/app) || fail "LLVM 产物跑不起来"
[ "$OUT" = "14
42
from llvm ir" ] || { echo "实际输出: $OUT"; fail "输出不对"; }

# 与 C 后端对拍：同一份源码，两边输出必须一致
"$JAVA" -cp "$CP" org.example.MainKt build main.rsf --c --link -o c -n app > c.log 2>&1 || { cat c.log; fail "C 后端构建失败"; }
COUT=$(./c/app) || fail "C 产物跑不起来"
[ "$OUT" = "$COUT" ] || { echo "LLVM: $OUT"; echo "C   : $COUT"; fail "两个后端输出不一致"; }

# 控制流（while / if-else）：两条后端必须一致
cp "$HERE/control.rsf" .
"$JAVA" -cp "$CP" org.example.MainKt build control.rsf --ll --link -o ll2 -n app > ll2.log 2>&1 || { cat ll2.log; fail "LLVM 编控制流失败"; }
"$JAVA" -cp "$CP" org.example.MainKt build control.rsf --c --link -o c2 -n app > c2.log 2>&1 || { cat c2.log; fail "C 后端编控制流失败"; }
L2=$(./ll2/app); C2=$(./c2/app)
[ "$L2" = "$C2" ] || { echo "LLVM: $L2"; echo "C: $C2"; fail "控制流输出不一致"; }

echo "verify92: 全过"
