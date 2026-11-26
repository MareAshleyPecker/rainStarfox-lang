#!/bin/sh
# 判据台 97：try/catch 的新表面（阶段 84）
#
# 核对：
#   1. `try 表达式` 能传播错误（`!void` 也行，以前只能 try/catch/fail 三件套）；
#   2. `catch { }`（不绑定）与 `catch e { }`（不写类型）都能用；
#   3. 老写法 `catch (e : String)` 仍然有效（兼容）；
#   4. 不再误报「catch 体不可达」。
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

"$JAVA" -cp "$CP" org.example.MainKt check proj.rsf > check.log 2>&1 || { cat check.log; fail "新写法检查不过"; }
grep -q '不可达' check.log && { cat check.log; fail "catch 体仍被误报不可达"; }

"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > out.log 2>&1 || { cat out.log; fail "运行失败"; }
OUT=$(grep -v '^\[rsf\] 警告' out.log)
[ "$OUT" = "hi
解析失败（不关心原因）
捕获: parseInt: 不是合法的整数：'x'" ] || { echo "$OUT"; fail "输出不符"; }

# 老写法兼容
cat > legacy.rsf <<'RSF'
fn main() -> void {
    try {
        parseInt("z")!!
    } catch (e : String) {
        println("老写法: " + e)
    }
}
RSF
"$JAVA" -cp "$CP" org.example.MainKt run legacy.rsf > legacy.log 2>&1 || { cat legacy.log; fail "catch (e : String) 失效"; }
grep -q '老写法: parseInt' legacy.log || { cat legacy.log; fail "老写法输出不对"; }

echo "verify97: 全过"
