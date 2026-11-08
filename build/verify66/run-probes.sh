#!/usr/bin/env bash
# 阶段 66 判据台：C 后端打出的原生自举编译器（stage0）能自己编程序。
#   ① rsf build RsfcBuild.rsf --c → gcc → RsfcBuild.exe
#   ② stage0 编 probes/hello.rsf → 跑 → 比金标
#   ③ stage0 编 RsfcBuild.rsf 自己（stage1）→ 与 Kotlin 版产物比反汇编等价
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="$ROOT/build/libs/rainStarfox-0.9.0.jar"
W="$HERE/work"
JOPT="-Dstdout.encoding=UTF-8"
mkdir -p "$W"

echo "== ① C 后端出原生自举编译器"
( cd "$ROOT" && java $JOPT -jar "$JAR" build rsfSrc/bootstrap/RsfcBuild.rsf --c -o "build/verify66/work/c" > /dev/null ) || { echo "   FAIL：生成 C 失败"; exit 1; }
( cd "$W/c" && gcc -O1 -w -o RsfcBuild.exe RsfcBuild.c ) || { echo "   FAIL：gcc 失败"; exit 1; }
echo "   产物 $(stat -c%s "$W/c/RsfcBuild.exe") 字节"

echo "== ② stage0 编样例并运行"
rm -rf "$W/out" && mkdir -p "$W/out"
( cd "$W" && env 'rsf.rsfc.cmd=build' 'rsf.rsfc.file=../probes/hello.rsf' 'rsf.rsfc.out=out' ./c/RsfcBuild.exe > /dev/null ) || { echo "   FAIL：stage0 编译失败"; exit 1; }
actual="$(java -cp "$W/out" hello | tr -d '\r')"
expected="$(cat "$HERE/expected/hello.txt")"
if [ "$actual" != "$expected" ]; then
  echo "   FAIL：输出不同"
  echo "   ---- 期望"; echo "$expected"
  echo "   ---- 实际"; echo "$actual"
  exit 1
fi
echo "   输出一致"

echo "== ③ stage0 编自己（stage1）与 Kotlin 产物比反汇编"
rm -rf "$W/stage1" "$W/kt" && mkdir -p "$W/stage1" "$W/kt"
( cd "$W" && env 'rsf.rsfc.cmd=build' 'rsf.rsfc.file=../../../rsfSrc/bootstrap/RsfcBuild.rsf' 'rsf.rsfc.out=stage1' ./c/RsfcBuild.exe > /dev/null ) || { echo "   FAIL：stage1 编译失败"; exit 1; }
( cd "$ROOT" && java $JOPT -jar "$JAR" build rsfSrc/bootstrap/RsfcBuild.rsf -o "build/verify66/work/kt" > /dev/null ) || { echo "   FAIL：Kotlin 侧编译失败"; exit 1; }
if ! bash "$ROOT/build/tools/classes_equal.sh" "$W/stage1" "$W/kt"; then
  echo "   FAIL：stage1 与 Kotlin 产物不等价"
  exit 1
fi
echo "   stage1 与 Kotlin 产物反汇编等价（$(cd "$W/kt" && ls *.class | wc -l) 个类）"
echo "==== 原生自举判据：通过 ===="
