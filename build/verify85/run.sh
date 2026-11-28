#!/bin/sh
# 判据台 85：jar 进分析期 classpath（阶段 84）
#
# 用一个 rsf 自己编出来的类当「外部 jar」（只返回常量，不依赖任何运行期），
# 然后核对三件事：
#   1. 构建脚本 `import demo.Util` + `Util.version()` 能跑（分析期看得见 jar）；
#   2. `#dep { javaInclude("libs") }` 给目录时展开成里面的 jar（锁文件记具体文件）；
#   3. 编辑器一侧（`rsf complete`）同样看得见 jar 里的方法，
#      并且 `import ` 那一行能补出依赖 jar 里的类名。
#
# 用法：  sh build/verify85/run.sh
# 前置：  sh tools/dev/kb.sh（编出 build/dev/classes）
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
cp -r "$HERE/impl" "$WORK/impl"
cp -r "$HERE/proj" "$WORK/proj"
mkdir -p "$WORK/proj/libs"

echo "== 编一个类，打成 jar =="
cd "$WORK/impl"
"$JAVA" -cp "$CP" org.example.MainKt build demo/Util.rsf -n Util -o out > /dev/null 2>&1 || {
    echo "编 jar 里的类失败" >&2
    exit 1
}
[ -f out/demo/Util.class ] || { echo "没有出 demo/Util.class" >&2; exit 1; }
python3 - "$WORK" <<'PY'
import sys, zipfile, os
work = sys.argv[1]
src = os.path.join(work, 'impl', 'out', 'demo', 'Util.class')
dst = os.path.join(work, 'proj', 'libs', 'lib.jar')
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(src, 'demo/Util.class')
print("[判据] jar:", zipfile.ZipFile(dst).namelist())
PY

echo "== 构建脚本调用 jar 里的类 =="
cd "$WORK/proj"
"$JAVA" -cp "$CP" org.example.MainKt build > build.log 2>&1 || { cat build.log; echo "构建失败" >&2; exit 1; }
cat build.log

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# 1. 构建脚本真的调到了 jar 里的静态方法（没有这一步，说明 ClassPath 没装上）
grep -q 'jar 说：v1-from-jar' build.log || fail "构建脚本没调到 jar 里的方法"

# 2. javaInclude 给目录时展开成里面的 jar，并写进锁文件
[ -f .rsfdeps/lock.json ] || fail "没有依赖锁文件"
grep -q '"path": "libs/lib.jar"' .rsfdeps/lock.json || fail "javaInclude 没展开成 libs/lib.jar"

# 3. 产物照旧出得来（jar 依赖不影响目标表）
[ -x build/out/main ] || fail "没有出 build/out/main"

# 4. 编辑器一侧：补全里能看到 jar 的方法
"$JAVA" -cp "$CP" org.example.MainKt complete src/app.rsf --line 4 --col 10 > complete.json 2>&1 \
    || { cat complete.json; fail "complete 跑失败"; }
grep -q '"version"' complete.json || fail "补全里看不到 jar 里的 version()"

# 5. `import ` 那一行本身也要能从依赖 jar 里补类名
"$JAVA" -cp "$CP" org.example.MainKt complete src/app.rsf --line 1 --col 8 > import.json 2>&1 \
    || { cat import.json; fail "import 补全跑失败"; }
grep -q 'demo.Util' import.json || fail "import 补全里看不到依赖 jar 里的类名"

echo "verify85: 全过"
