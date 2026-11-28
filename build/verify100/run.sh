#!/bin/sh
# 判据台 100：运行期故障直接中止（try/catch 退休后的口径）
#
# 用户拍板（2026-11-26）：`?:` 只管 `!T`；越界 / 负长度这类**运行期故障**没有捕获手段，
# 三端一律中止程序并打同一条消息。核对：
#   1. 解释器：stderr 是 `运行期错误: <消息>`，退出码 1，后面的语句不执行；
#   2. 字节码产物：同一条消息、退出码 1（不是一整段堆栈）；
#   3. 两种故障的消息格式一致（越界 / 负长度）。
#
# 用法：  sh build/verify100/run.sh
# 前置：  sh tools/dev/kb.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/index.rsf" "$HERE/negative.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

run_fault() {
    f=$1
    name=$2
    expect=$3
    "$JAVA" -cp "$CP" org.example.MainKt run "$f" > "i-$name.out" 2> "i-$name.err" && fail "$name：解释器居然没失败" || true
    grep -qF "$expect" "i-$name.err" || { cat "i-$name.err"; fail "$name：解释器的消息不对"; }
    grep -v "^\\[rsf\\] 警告" "i-$name.out" > "i-$name.stdout" || true
}

echo "== 解释器：越界 =="
run_fault index.rsf index "运行期错误: 数组下标越界：长度 3，下标 5"
[ "$(cat i-index.stdout)" = "数组长度 = 3" ] || { cat i-index.stdout; fail "越界前那句没打出来，或者多打了"; }

echo "== 解释器：负长度 =="
run_fault negative.rsf negative "运行期错误: 数组长度不能是负数：-1"

echo "== 字节码：同一条消息、退出码 1 =="
"$JAVA" -cp "$CP" org.example.MainKt build index.rsf -n Index -o cls > build.log 2>&1 || { cat build.log; fail "字节码构建失败"; }
"$JAVA" -cp cls Index > c.out 2> c.err && fail "字节码产物居然没失败" || true
grep -qF '运行期错误: 数组下标越界：长度 3，下标 5' c.err || { cat c.err; fail "字节码的消息不对"; }
grep -q 'Exception in thread\|	at ' c.err && { cat c.err; fail "字节码还在打堆栈"; }
grep -v '^\[rsf\] 警告' c.out > c.stdout
diff i-index.stdout c.stdout || fail "字节码与解释器的 stdout 不一致"

echo "== C 后端：同一条消息、退出码 1 =="
command -v gcc >/dev/null 2>&1 || { echo "verify100: 跳过 C 侧（没有 gcc）"; echo "verify100: 全过"; exit 0; }
"$JAVA" -cp "$CP" org.example.MainKt build index.rsf --c --link > cbuild.log 2>&1 || { cat cbuild.log; fail "C 侧构建失败"; }
./out/index > n.out 2> n.err && fail "C 产物居然没失败" || true
grep -qF '运行期错误: 数组下标越界：长度 3，下标 5' n.err || { cat n.err; fail "C 侧的消息不对"; }
grep -v "^\[rsf\] 警告" n.out > n.stdout || true
diff i-index.stdout n.stdout || fail "C 侧与解释器的 stdout 不一致"

echo "verify100: 全过"
