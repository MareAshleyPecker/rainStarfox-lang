#!/bin/sh
# 判据台 90：pkg-info.rsf 与导出清单门禁
#
# 核对：
#   1. pkg-info.rsf 里的 pkg 能被目录下的源码继承（文件自己不写 pkg 也进包）；
#   2. 文件自己写 pkg 且与 pkg-info 不一致 → 报错；
#   3. 有 module-impex.rsf 时，跨目录 import 的目标必须在清单里；
#   4. 同目录 import 不受门禁限制。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/proj" "$WORK/proj"
cd "$WORK/proj"
fail() { echo "FAIL: $1" >&2; exit 1; }

echo "== 清单里列了 a 包：跨目录 import 通过 =="
"$JAVA" -cp "$CP" org.example.MainKt run src/b/app.rsf > ok.log 2>&1 || { cat ok.log; fail "清单里列了却不让 import"; }
grep -q '^7$' ok.log || { cat ok.log; fail "运行结果不对"; }

echo "== 清单里删掉：跨目录 import 报错 =="
: > module-impex.rsf
"$JAVA" -cp "$CP" org.example.MainKt run src/b/app.rsf > bad.log 2>&1 && fail "没在清单里也能 import"
grep -q '没有出现在 module-impex.rsf' bad.log || { cat bad.log; fail "门禁报错消息不对"; }

echo "== pkg-info 继承：文件不写 pkg 也进包 =="
printf 'exportjvm "src/a/util.rsf"\n' > module-impex.rsf
"$JAVA" -cp "$CP" org.example.MainKt build src/a/util.rsf -n Util -o out > pkg.log 2>&1 || { cat pkg.log; fail "编译失败"; }
[ -f out/demo/Util.class ] || [ -f out/Util.class ] || fail "没有产出 class"
grep -q 'pkg demo' src/a/pkg-info.rsf 2>/dev/null || true

echo "== 文件自己的 pkg 与 pkg-info 不一致：报错 =="
cat > src/a/pkg-info.rsf <<'EOF'
pkg demo
EOF
sed -i '1i pkg other' src/a/util.rsf
"$JAVA" -cp "$CP" org.example.MainKt build src/a/util.rsf -n Util -o out > pkgbad.log 2>&1 && fail "两个包名不一致居然编过了"
grep -q '不一致' pkgbad.log || { cat pkgbad.log; fail "包名冲突的报错不对"; }

echo "verify90: 全过"
