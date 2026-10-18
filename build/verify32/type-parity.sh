#!/usr/bin/env bash
# 阶段 32a：类型层对拍。Kotlin 侧 `src/test/kotlin/org/example/TypeProbe.kt` 是 oracle，
# 自举侧 `rsfSrc/bootstrap/TypeProbe.rsf` 必须打出逐字节相同的文本。
#
# 跑：bash build/verify32/type-parity.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
cd "$REPO" || exit 2

OUT="$HERE/type-parity"
mkdir -p "$OUT"
JAR="build/libs/rainStarfox-0.9.0.jar"
KC="$(ls -d /d/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar 2>/dev/null | head -1)"
case "$KC" in /d/*) KC="D:${KC#/d}" ;; esac

fail=0

# 1) 编译两侧
./gradlew.bat testClasses -q > /dev/null 2>&1 || { echo "gradle testClasses 失败"; exit 1; }
java -jar "$JAR" build rsfSrc/bootstrap/TypeProbe.rsf -o "$OUT/probeout" > /dev/null 2>&1 \
  || { echo "自举侧 TypeProbe.rsf 编译失败"; exit 1; }

# 2) 各打一份（-cp 用 argfile，避免 MSYS 把 ';' 拆开）
printf -- '-cp\nbuild/classes/kotlin/test;build/classes/kotlin/main;%s\n' "$KC" > "$OUT/cp.args"
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 @"$OUT/cp.args" org.example.TypeProbeKt > "$OUT/kotlin.txt" 2>&1
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT/probeout" TypeProbe > "$OUT/self.txt" 2>&1

# 3) 比
if diff -u "$OUT/kotlin.txt" "$OUT/self.txt" > "$OUT/diff.txt" 2>&1; then
  echo "==== 类型层对拍：$(wc -l < "$OUT/kotlin.txt") 行，逐字节相同 ===="
else
  echo "==== 类型层对拍：不同（前 12 行差异）===="
  sed -n '1,12p' "$OUT/diff.txt"
  fail=1
fi
exit $fail
