#!/usr/bin/env bash
# 阶段 62 判据台：native -> JVM（jvmCall / jvmNew / jvmCallOn）
#   1. --c 出来的 C 里带 JVM 运行时，且没有 jvmCall 的程序不带
#   2. gcc 编得过（需要 JAVA_HOME/include 与 include/win32）
#   3. 跑起来真的进 JVM 调了 Java（静态单参 / 静态两参 / 构造器 + 实例方法）
set -u
cd "$(dirname "$0")/../.." || exit 1
ROOT="$(pwd)"
OUT=build/verify62/out
JAR=build/libs/rainStarfox-0.9.0.jar
fail=0
step() { printf '== %s\n' "$1"; }
bad() { printf '  !! %s\n' "$1"; fail=$((fail + 1)); }

step "1/4 生成 C（带 jvmCall 的一份 + 不带的一份）"
rm -rf "$OUT" && mkdir -p "$OUT"
java -jar "$JAR" build build/verify62/probes/reverse.rsf --c -o "$OUT" >/dev/null || bad "rsf build --c"
grep -q "rsf_jvm_call(" "$OUT/reverse.c" || bad "reverse.c 里没有 rsf_jvm_call"
grep -q "rsf_jvm_new(" "$OUT/reverse.c" || bad "reverse.c 里没有 rsf_jvm_new"
grep -q "rsf_jvm_call_on(" "$OUT/reverse.c" || bad "reverse.c 里没有 rsf_jvm_call_on"
grep -q "JNI_CreateJavaVM" "$OUT/reverse.c" || bad "reverse.c 里没有 JVM 运行时"
java -jar "$JAR" build build/verify52/probes/c_basic.rsf --c -o "$OUT" >/dev/null || bad "无关语料编译"
if grep -q "JNI_CreateJavaVM" "$OUT/c_basic.c"; then bad "没用 jvmCall 的程序不该编进 JVM 运行时"; fi

step "2/4 javac 辅助类"
javac -d "$OUT" build/verify62/java/Echo.java || bad "javac"

step "3/4 gcc 链接 jni.h"
INC1="$JAVA_HOME/include"
INC2="$JAVA_HOME/include/win32"
gcc -O2 "$OUT/reverse.c" -I"$INC1" -I"$INC2" -o "$OUT/reverse.exe" || bad "gcc"

step "4/4 跑：native 起 JVM 调 Java"
ACTUAL="$(RSF_JVM_CP="$ROOT/$OUT" "$OUT/reverse.exe" 2>&1 | tr -d '\r')"
WANT="java:bob
ab-cd-2
[tag]hello"
if [ "$ACTUAL" = "$WANT" ]; then
    printf '  ok %s\n' "$(printf '%s' "$ACTUAL" | tr '\n' ' ')"
else
    bad "输出是 '$ACTUAL'（期望三行：java:bob / ab-cd-2 / [tag]hello）"
fi

if [ "$fail" -eq 0 ]; then echo "==== 阶段 62 判据：4 项全过 ===="; exit 0; fi
echo "==== 阶段 62 判据：$fail 项失败 ===="
exit 1
