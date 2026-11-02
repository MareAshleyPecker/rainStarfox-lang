#!/usr/bin/env bash
# 阶段 63 判据台：rsf 写的 class 文件写出器（ClassFile.rsf）能产出可被 JVM 加载的类
set -u
SELF="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SELF/../.." && pwd)"
JAR="$ROOT/build/libs/rainStarfox-0.9.0.jar"
cd "$ROOT" || exit 1
mkdir -p build/verify63/out
java -Dstdout.encoding=UTF-8 -jar "$JAR" run build/verify63/probes/classfile_probe.rsf || exit 1
java -cp build/verify63/out Hello 2>&1 | tr -d '\r' > build/verify63/out/hello.txt || { echo "JVM 加载失败"; exit 1; }
if diff -q build/verify63/expected/hello.txt build/verify63/out/hello.txt > /dev/null; then
  echo "==== class 文件判据：一致 ===="
else
  echo "==== class 文件判据：不一致 ===="
  diff build/verify63/expected/hello.txt build/verify63/out/hello.txt
  exit 1
fi
