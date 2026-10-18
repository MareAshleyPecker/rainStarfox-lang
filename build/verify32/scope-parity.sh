#!/usr/bin/env bash
# 阶段 32b：符号表对拍。Kotlin 侧 `src/test/kotlin/org/example/ScopeProbe.kt`（map + 父链）是 oracle，
# 自举侧 `rsfSrc/bootstrap/ScopeProbe.rsf`（并排数组）必须打出逐字节相同的文本。
#
# 跑：bash build/verify32/scope-parity.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
cd "$REPO" || exit 2

OUT="$HERE/scope-parity"
mkdir -p "$OUT"
JAR="build/libs/rainStarfox-0.9.0.jar"
KC="$(ls -d /d/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar 2>/dev/null | head -1)"
case "$KC" in /d/*) KC="D:${KC#/d}" ;; esac

./gradlew.bat testClasses -q > /dev/null 2>&1 || { echo "gradle testClasses 失败"; exit 1; }
java -jar "$JAR" build rsfSrc/bootstrap/ScopeProbe.rsf -o "$OUT/selfout" > /dev/null 2>&1 \
  || { echo "自举侧 ScopeProbe.rsf 编译失败"; exit 1; }

printf -- '-cp\nbuild/classes/kotlin/test;build/classes/kotlin/main;%s\n' "$KC" > "$OUT/cp.args"
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 @"$OUT/cp.args" org.example.ScopeProbeKt > "$OUT/kotlin.txt" 2>&1
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/selfout" ScopeProbe > "$OUT/self.txt" 2>&1

if diff -u "$OUT/kotlin.txt" "$OUT/self.txt" > "$OUT/diff.txt" 2>&1; then
  echo "==== 符号表对拍：$(wc -l < "$OUT/kotlin.txt") 行，逐字节相同 ===="
else
  echo "==== 符号表对拍：不同（前 16 行差异）===="
  sed -n '1,16p' "$OUT/diff.txt"
  exit 1
fi
