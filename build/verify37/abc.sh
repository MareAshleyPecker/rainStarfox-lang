#!/usr/bin/env bash
# 阶段 37：自举闭环 A→B→C。
#
# A = Kotlin 版 `rsf build` 编出 `RsfcBuild.rsf`（含它 import 进来的整条链）
# B = A 编自己（同一份 RsfcBuild.rsf）
# C = B 编自己
# 判据：A、B、C 三份 class 目录逐字节相同（`diff -r` 零差异），且 C 能跑 `check`/`build` 对拍台。
#
# 跑：bash build/verify37/abc.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
cd "$REPO" || exit 2
JAR="build/libs/rainStarfox-0.9.0.jar"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
SRC="rsfSrc/bootstrap/RsfcBuild.rsf"

rm -rf "$HERE/A" "$HERE/B" "$HERE/C"
mkdir -p "$HERE/A" "$HERE/B" "$HERE/C"

echo "-- A：Kotlin 编 $SRC"
java -jar "$JAR" build "$SRC" -o "$HERE/A" > "$HERE/a.log" 2>&1 || { echo "A 编译失败"; tail -5 "$HERE/a.log"; exit 1; }

# 自举侧用相对 classpath（MSYS bash 会吃掉绝对路径里的 `;`）
run_self_build() {
  local cp="$1" out="$2" log="$3"
  ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="../../$SRC" -Drsf.rsfc.out="$out" -cp "$cp" RsfcBuild ) > "$log" 2>&1
}

echo "-- B：A 编自己"
run_self_build "A;../../$JAR" "B" "b.log" || { echo "B 编译失败"; tail -5 "$HERE/b.log"; exit 1; }
echo "-- C：B 编自己"
run_self_build "B;../../$JAR" "C" "c.log" || { echo "C 编译失败"; tail -5 "$HERE/c.log"; exit 1; }

bad=0
for pair in "A B" "A C"; do
  set -- $pair
  if diff -r "$HERE/$1" "$HERE/$2" > "$HERE/diff-$1-$2.log" 2>&1; then
    echo "   $1 ≡ $2  类文件 $(ls "$HERE/$1"/*.class | wc -l) 个"
  else
    bad=$((bad+1))
    echo "   $1 与 $2 不同："; head -8 "$HERE/diff-$1-$2.log" | sed 's/^/      /'
  fi
done

# C 编出来的驱动要能继续干活：拿它编一份样例，class 与 Kotlin 版逐字节相同
echo "-- C 自检：用 C 里的 RsfcBuild 编样例"
rm -rf "$HERE/probe.k" "$HERE/probe.c"
f="$REPO/build/verify32/okcorpus/ok_basic.rsf"
java $JOPT -jar "$JAR" build "$f" -o "$HERE/probe.k" > "$HERE/probe.k.log" 2>&1
( cd "$HERE" && java $JOPT -Drsf.rsfc.file="$f" -Drsf.rsfc.out="probe.c" -cp "C;../../$JAR" RsfcBuild ) > "$HERE/probe.c.log" 2>&1
if diff -r "$HERE/probe.k" "$HERE/probe.c" > "$HERE/probe.diff" 2>&1; then
  echo "   C 编出的 class 与 Kotlin 版逐字节相同"
else
  bad=$((bad+1))
  echo "   C 编出的 class 与 Kotlin 版不同："; head -8 "$HERE/probe.diff" | sed 's/^/      /'
fi

echo "==== 阶段 37 判决：不同项 $bad ===="
[ "$bad" -eq 0 ]
