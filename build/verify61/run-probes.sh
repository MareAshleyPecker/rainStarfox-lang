#!/usr/bin/env bash
# 阶段 60b/60c：JNI 桥 + 类型级构建目标。
set -u
cd "$(dirname "$0")"
ROOT="../.."
JAR="$ROOT/build/libs/rainStarfox-0.9.0.jar"
OUT="out"
rm -rf "$OUT"; mkdir -p "$OUT"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_HOME_REAL="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | grep -E '^[[:space:]]+java\.home = ' | head -1 | sed 's/.*= //')"

fail=0
for p in probes/*.rsf; do
  base="$(basename "$p" .rsf)"
  mkdir -p "$OUT/$base/jvm" "$OUT/$base/c"
  "$JAVA_BIN" -Dstdout.encoding=UTF-8 -jar "$JAR" build "$p" -o "$OUT/$base/jvm" >"$OUT/$base/jvm.log" 2>&1 || { echo "FAIL $base: JVM 构建失败"; cat "$OUT/$base/jvm.log"; fail=1; continue; }
  "$JAVA_BIN" -Dstdout.encoding=UTF-8 -jar "$JAR" build "$p" --c --lib --jni -o "$OUT/$base/c" >"$OUT/$base/c.log" 2>&1 || { echo "FAIL $base: C 构建失败"; cat "$OUT/$base/c.log"; fail=1; continue; }

  gcc -shared "$OUT/$base/c/$base.c" "$OUT/$base/c/${base}_jni.c" \
      -I"$JAVA_HOME_REAL/include" -I"$JAVA_HOME_REAL/include/win32" \
      -o "$OUT/$base/$base.dll" 2>"$OUT/$base/gcc.log" || { echo "FAIL $base: gcc 链接失败"; cat "$OUT/$base/gcc.log"; fail=1; continue; }

  "$JAVA_BIN" -Dstdout.encoding=UTF-8 -Djava.library.path="$(pwd)/$OUT/$base" \
      -cp "$OUT/$base/jvm" "$base" >"$OUT/$base/actual.txt" 2>"$OUT/$base/run.err" || { echo "FAIL $base: java 运行失败"; cat "$OUT/$base/run.err"; fail=1; continue; }

  tr -d '\r' < "expected/$base.txt" > "$OUT/$base/exp.norm"
  tr -d '\r' < "$OUT/$base/actual.txt" > "$OUT/$base/act.norm"
  if ! diff -u "$OUT/$base/exp.norm" "$OUT/$base/act.norm"; then
    echo "FAIL $base: 输出与金标不同"; fail=1; continue
  fi

  javap -p "$OUT/$base/jvm/$base.class" >"$OUT/$base/javap.txt"
  grep -q "native int add(int, int)" "$OUT/$base/javap.txt" || { echo "FAIL $base: add 不是 native"; fail=1; }
  grep -q "native java.lang.String greet(java.lang.String)" "$OUT/$base/javap.txt" || { echo "FAIL $base: greet 不是 native"; fail=1; }
  if grep -q "class Fast" "$OUT/$base/javap.txt"; then echo "FAIL $base: native 类漏进 JVM 构建"; fail=1; fi
  echo "OK   $base"
done

if [ "$fail" -eq 0 ]; then echo "==== JNI 对拍：全部通过 ===="; else echo "==== JNI 对拍：有失败 ===="; fi
exit "$fail"
