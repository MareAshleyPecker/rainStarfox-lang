#!/bin/sh
# 判据台 88：module-impex.rsf（模块对外面貌清单）
#
# 核对：
#   1. exportjvm 产出一个库 jar，里面有对应的类；
#   2. extern 产出 C 目标文件（.o），不是可执行文件；
#   3. 清单里的 import/export 只校验存在（v1）；
#   4. 引用不存在的文件时，报错指到清单那一行。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CLASSES="$ROOT/build/dev/classes"
CP="$CLASSES:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/proj" "$WORK/proj"
cd "$WORK/proj"

fail() { echo "FAIL: $1" >&2; exit 1; }

"$JAVA" -cp "$CP" org.example.MainKt build > build.log 2>&1 || { cat build.log; echo "构建失败" >&2; exit 1; }
cat build.log

grep -q '模块清单' build.log || fail "没有读 module-impex.rsf"
[ -f build/out/Tool.jar ] || fail "exportjvm 没产出 jar"
[ -f build/out/lib.o ] || fail "extern 没产出目标文件"
[ -x build/out/lib ] && fail "extern 不该产出可执行文件"

# jar 里要有 Tool.class（点分名字在包外时就是顶层）
python3 - "$WORK/proj/build/out/Tool.jar" <<'PY'
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
assert 'Tool.class' in names, names
PY

# 引用不存在的文件：报错要指到清单的行
printf 'exportjvm "src/Nope.rsf"\n' >> module-impex.rsf
"$JAVA" -cp "$CP" org.example.MainKt build > bad.log 2>&1 && fail "引用不存在的文件居然构建成功"
grep -q 'module-impex.rsf 第 6 行' bad.log || { cat bad.log; fail "报错没指到清单的行"; }

echo "verify88: 全过"
