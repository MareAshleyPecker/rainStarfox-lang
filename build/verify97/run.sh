#!/bin/sh
# 判据台 97：try/catch 语法的退休（阶段 84）
#
# 核对：
#   1. `try { … } catch { … }` 已经报错，而且报错里给出改法（?: / !! / try 表达式）；
#   2. `try 表达式` 仍然能用：`try f()` 在 !T 函数里传播错误（!void 也行）；
#   3. 不再误报「catch 体不可达」。
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

echo "== try 表达式仍然可用 =="
"$JAVA" -cp "$CP" org.example.MainKt check proj.rsf > check.log 2>&1 || { cat check.log; fail "try 表达式的用法检查不过"; }
grep -q '不可达' check.log && { cat check.log; fail "又误报不可达"; }

"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > out.log 2>&1 || { cat out.log; fail "运行失败"; }
OUT=$(grep -v '^\[rsf\] 警告' out.log)
[ "$OUT" = "hi" ] || { echo "$OUT"; fail "输出不符"; }

echo "== try { … } catch { … }：报错并给改法 =="
cat > legacy.rsf <<'LEGACY'
fn main() -> void {
    try {
        parseInt("z")!!
    } catch e {
        println("老写法: " + e)
    }
}
LEGACY
"$JAVA" -cp "$CP" org.example.MainKt check legacy.rsf > legacy.log 2>&1 && fail "try/catch 居然还编得过"
grep -q '已经移除' legacy.log || { cat legacy.log; fail "没报出「已经移除」"; }
grep -q 'lastErrorMessage' legacy.log || { cat legacy.log; fail "改法里没提 lastErrorMessage()"; }
grep -q '?:' legacy.log || { cat legacy.log; fail "改法里没提 ?:"; }

echo "verify97: 全过"
