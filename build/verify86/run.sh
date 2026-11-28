#!/bin/sh
# 判据台 86：rsf 调 C（extern fn + import 的 .c/.h）
#
# 核对四件事：
#   1. `import "x.c"` / `"x.h"` 进依赖清单，gcc 命令行里真有 `-I` 与那个 `.c`；
#   2. `extern fn util_add(a : Int, b : Int) -> Int` 真的调到 C 函数（返回值对）；
#   3. 生成的 `.c` 里 extern 用**C 原名**（没有 rsf_fn_ 前缀）；
#   4. JVM 侧显式拒绝 extern（不是运行期 NoSuchMethodError）。
#
# 用法：  sh build/verify86/run.sh
# 前置：  sh tools/dev/kb.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CLASSES="$ROOT/build/dev/classes"
CP_FILE="$ROOT/build/dev/rt.cp"

if [ ! -d "$CLASSES" ]; then
    echo "缺少 $CLASSES：先跑 sh tools/dev/kb.sh" >&2
    exit 1
fi
JAVA=${JAVA:-java}
CP="$CLASSES:$(cat "$CP_FILE")"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/proj" "$WORK/proj"
cd "$WORK/proj"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

echo "== rsf build（exe 目标，C 侧）=="
"$JAVA" -cp "$CP" org.example.MainKt build > build.log 2>&1 || { cat build.log; echo "构建失败" >&2; exit 1; }
cat build.log

# 1. 依赖进了 gcc 命令行
grep -q -- '-Inative' build.log || fail "gcc 命令行里没有 -Inative（hInclude 没生效）"
grep -q 'native/util.c' build.log || fail "gcc 命令行里没有 native/util.c（.c 依赖没生效）"

# 2. 生成的 .c 里 extern 用原名，不是 rsf_fn_ 前缀
grep -q 'util_add(' build/out/main.c || fail "生成的 .c 里没有对 util_add 的调用"
grep -q 'rsf_fn_util_add' build/out/main.c && fail "extern 被加上了 rsf_fn_ 前缀（链接会找不到符号）"

# 3. 跑起来返回值对
OUT=$("./build/out/main") || fail "可执行文件跑不起来"
[ "$OUT" = "C 说：42" ] || fail "输出是 '$OUT'，期望 'C 说：42'"

# 4. JVM 侧明确拒绝
"$JAVA" -cp "$CP" org.example.MainKt build src/main.rsf -o jvm-out -n app > jvm.log 2>&1 && \
    fail "JVM 构建居然接受了 extern"
grep -q 'extern' jvm.log || fail "JVM 侧的拒绝消息里没提 extern"

echo "verify86: 全过"
