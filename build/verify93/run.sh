#!/bin/sh
# 判据台 93：rsf cc（参照 zig cc 的角色）
#
# 核对：rsf build --c 出的 .c 能直接交给 rsf cc 编成二进制并运行，
# 不需要用户自己记 -I<stdlib> / -lm。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/main.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

"$JAVA" -cp "$CP" org.example.MainKt build main.rsf --c -o c -n app > build.log 2>&1 || { cat build.log; fail "C 后端构建失败"; }
[ -f c/app.c ] || fail "没有生成 app.c"

"$JAVA" -cp "$CP" org.example.MainKt cc c/app.c -o via-rsf-cc > cc.log 2>&1 || { cat cc.log; fail "rsf cc 编不过"; }
OUT=$(./via-rsf-cc) || fail "rsf cc 的产物跑不起来"
[ "$OUT" = "compiled by rsf cc" ] || { echo "输出: $OUT"; fail "输出不对"; }

# 参数透传：-c 只编目标文件
"$JAVA" -cp "$CP" org.example.MainKt cc -c c/app.c -o app.o > obj.log 2>&1 || { cat obj.log; fail "-c 透传失败"; }
[ -f app.o ] || fail "-c 没有产出 .o"

echo "verify93: 全过"
