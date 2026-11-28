#!/bin/sh
# 判据台 99：`?:` 的 void 兜底 + lastErrorMessage()（阶段 84，根除 try/catch 第 1 步）
#
# 核对：
#   1. `!void` 调用也能用 `?:`（`f() ?: reportFailure()`），成功时兜底不跑；
#   2. `lastErrorMessage()` 拿到的是刚才那条错误，被兜住之后仍然保留；没出错时是空串；
#   3. 内置 `!T` 的 `?:` 兜底在 C 侧不再把错误旗标漏给后面的语句（以前打 0 而不是 42）；
#   4. 判据：同一程序在解释器 / `--c` / 字节码三端输出逐字节一致。
#
# 用法：  sh build/verify99/run.sh
# 前置：  sh tools/dev/kb.sh（gcc 要在 PATH 里）
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

EXPECT='初始:[]
函数兜底: -1
之后: parseInt: 不是合法的整数：'"'"'x'"'"'
内置兜底: 42
之后2: parseInt: 不是合法的整数：'"'"'y'"'"'
兜底: state: 写不进去
兜底: parseInt: 不是合法的整数：'"'"'z'"'"'
文件: hi'

echo "== 解释器 =="
"$JAVA" -cp "$CP" org.example.MainKt run proj.rsf > interp.log 2>&1 || { cat interp.log; fail "解释器跑失败"; }
grep -v '^\[rsf\] 警告' interp.log > interp.out
[ "$(cat interp.out)" = "$EXPECT" ] || { cat interp.out; fail "解释器输出不符"; }

echo "== 字节码 =="
"$JAVA" -cp "$CP" org.example.MainKt build proj.rsf -n App -o cls > jvm-b.log 2>&1 || { cat jvm-b.log; fail "字节码构建失败"; }
"$JAVA" -cp cls App > jvm.log 2>&1 || { cat jvm.log; fail "字节码产物跑失败"; }
diff interp.out jvm.log || fail "字节码与解释器输出不一致"

echo "== C 后端 =="
command -v gcc >/dev/null 2>&1 || { echo "verify99: 跳过 C 侧（没有 gcc）"; echo "verify99: 全过"; exit 0; }
"$JAVA" -cp "$CP" org.example.MainKt build proj.rsf --c --link > c.log 2>&1 || { cat c.log; fail "C 侧构建失败"; }
./out/proj > c.out 2>&1 || { cat c.out; fail "C 产物跑失败"; }
diff interp.out c.out || fail "C 后端与解释器输出不一致"

echo "== 反面：类型对不上的写法要报错 =="
printf 'fn warn() -> void { }\nfn f() -> Int { rtn 1 }\nfn main() -> void {\n    val x = f() ?: warn()\n}\n' > bad1.rsf
"$JAVA" -cp "$CP" org.example.MainKt check bad1.rsf > bad1.log 2>&1 && fail "'?:' 右边是 void 居然编过了"
grep -q '右边是 void 表达式' bad1.log || { cat bad1.log; fail "没报出「右边是 void 表达式」"; }

printf 'fn f() -> !void { }\nfn main() -> void {\n    val x = f() ?: 1\n}\n' > bad2.rsf
"$JAVA" -cp "$CP" org.example.MainKt check bad2.rsf > bad2.log 2>&1 && fail "'!void' 配非 void 兜底居然编过了"
grep -q '右边也得是不返回值的表达式' bad2.log || { cat bad2.log; fail "没报出「右边也得是不返回值的表达式」"; }

echo "verify99: 全过"
