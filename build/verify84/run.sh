#!/bin/sh
# 判据台 84：rsf build 四阶段（指令 → main → 产物）
#
# 把一个真实小项目复制到临时目录里跑 `rsf build`，然后核对：
#   1. 阶段 0 的 #target（exe + copy 带显式产物路径）与阶段 2 的 target() 都进了同一张表；
#   2. need 真的改了顺序（lib 在 main 之前）；
#   3. 三种产物的内容对：跑得出输出、目标文件是 ELF/obj、复制件字节一致；
#   4. exec 的退出码回到了脚本里（echo 成功 -> 不打印「exec 失败」）；
#   5. #dep rule("genDeps") 在阶段 1 先跑，且跑在 main 之前。
#
# 用法：  sh build/verify84/run.sh
# 前置：  sh tools/dev/kb.sh（编出 build/dev/classes）
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CLASSES="$ROOT/build/dev/classes"
CP_CHUNK="$ROOT/build/dev/rt.cp"

if [ ! -d "$CLASSES" ]; then
    echo "缺少 $CLASSES：先跑 sh tools/dev/kb.sh" >&2
    exit 1
fi

# JDK 只用来跑编译器本体（zx 环境没有 javac 也行，java 够）
JAVA=${JAVA:-java}
CP="$CLASSES:$(cat "$CP_CHUNK")"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/proj/." "$WORK/"
cd "$WORK"

echo "== rsf build =="
"$JAVA" -cp "$CP" org.example.MainKt build > build.log 2>&1 || {
    cat build.log
    echo "构建失败" >&2
    exit 1
}
cat build.log

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# 1. 产物都在
[ -f build/out/main ] || fail "没有 build/out/main"
[ -f build/out/lib.o ] || fail "没有 build/out/lib.o"
[ -f build/out/copied.txt ] || fail "没有 build/out/copied.txt"

# 2. exe 能跑，输出是程序自己的那一行
"$JAVA" -cp "$CP" org.example.MainKt version > /dev/null
OUT=$("./build/out/main") || fail "main 跑不起来"
[ "$OUT" = "hello from exe" ] || fail "main 输出是 '$OUT'，期望 'hello from exe'"

# 3. copy 是原样字节
cmp res/data.txt build/out/copied.txt || fail "copied.txt 与源文件不一致"

# 4. lib.o 真的是目标文件（ELF / Mach-O / PE 开头都对）
head -c 4 build/out/lib.o | od -An -tx1 | tr -d ' \n' | grep -Eq '^(7f454c46|feedface|feedfacf|cafebabe|4d5a)' \
    || fail "lib.o 不像是目标文件"

# 5. need 改了顺序：lib 在 main 之前
LIB_LINE=$(grep -n '目标 lib' build.log | cut -d: -f1)
MAIN_LINE=$(grep -n '目标 main' build.log | cut -d: -f1)
[ -n "$LIB_LINE" ] && [ -n "$MAIN_LINE" ] || fail "构建日志里找不到目标行"
[ "$LIB_LINE" -lt "$MAIN_LINE" ] || fail "need 没生效：main 在 lib 之前出了"

# 6. exec 的退出码回到了脚本里
grep -q '来自 exec 的一行' build.log || fail "exec 没跑"
grep -q 'exec 失败' build.log && fail "exec 成功却走了失败分支"

# 7. 阶段 1：规则先跑，且跑在 main 之前
grep -q '规则 genDeps 先跑' build.log || fail "阶段 1 没跑规则"
RULE_LINE=$(grep -n '规则 genDeps 先跑' build.log | cut -d: -f1)
MAIN_LOG_LINE=$(grep -n 'main 在规则之后' build.log | cut -d: -f1)
[ "$RULE_LINE" -lt "$MAIN_LOG_LINE" ] || fail "规则在 main 之后才跑"
grep -q '规则里的 exec' build.log || fail "规则里的 exec 没跑"

# 8. 规则里的声明内建进了依赖清单（c/h/dir/java 四种都有）
LOCK=.rsfdeps/lock.json
[ -f "$LOCK" ] || fail "没有依赖锁文件"
for kind in cInclude hInclude javaInclude dirInclude; do
    grep -q "\"kind\": \"$kind\"" "$LOCK" || fail "锁文件里没有 $kind 依赖"
done
# 目录展开：javaInclude("libs") 要落成具体的 jar 文件，而不是目录本身
grep -q '"path": "libs/demo.jar"' "$LOCK" || fail "javaInclude 目录没展开成 jar 文件"
grep -q '"path": "native/util.c"' "$LOCK" || fail "cInclude 目录没展开成 .c 文件"
grep -q '"path": "native/util.h"' "$LOCK" || fail "hInclude 目录没展开成 .h 文件"
grep -q '"path": "res"' "$LOCK" || fail "dirInclude 依赖没进锁文件"

echo "verify84: 全过"
