#!/bin/sh
# 编译 Kotlin 编译器本体（不经过 gradle：本机没有 JDK 25，jvmToolchain(25) 过不去）。
# 依赖全部取自 ~/.gradle/caches；产物落 build/dev/classes。
#
# 用法： sh tools/dev/kb.sh [-q]
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
C="$HOME/.gradle/caches/modules-2/files-2.1"
OUT="$ROOT/build/dev/classes"

if [ ! -d "$C" ]; then
    echo "找不到 gradle 依赖缓存：$C" >&2
    echo "（需要先在有网的机器上 build 过一次，再把 ~/.gradle 拷过来）" >&2
    exit 1
fi

KC=$(find "$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.10" \
          "$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.3.10" \
          "$C/org.jetbrains.kotlin/kotlin-script-runtime/2.3.10" \
          "$C/org.jetbrains.kotlin/kotlin-stdlib/2.3.10" \
          "$C/org.jetbrains.intellij.deps/trove4j/1.0.20200330" \
          "$C/org.jetbrains/annotations/13.0" \
          "$C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0" \
          "$C/org.jetbrains.kotlin/kotlin-reflect/2.2.0" -name '*.jar' 2>/dev/null | tr '\n' ':')
RT=$(find "$C/org.jetbrains.kotlin/kotlin-stdlib/2.3.10" \
         "$C/org.ow2.asm/asm/9.9.1" "$C/org.ow2.asm/asm-util/9.9.1" \
         "$C/org.ow2.asm/asm-tree/9.9.1" "$C/org.ow2.asm/asm-analysis/9.9.1" -name '*.jar' 2>/dev/null | tr '\n' ':')

if [ -z "$KC" ] || [ -z "$RT" ]; then
    echo "依赖 jar 找不全（kotlinc 或 asm/stdlib 缺）" >&2
    exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT" "$ROOT/build/dev"
printf '%s' "$RT" > "$ROOT/build/dev/rt.cp"

cd "$ROOT"
java -Xmx1500m -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$RT" -jvm-target 17 \
    -d "$OUT" src/main/kotlin 2>&1 | grep -E "error:" && { echo "编译失败" >&2; exit 1; }

[ "$1" = "-q" ] || echo "编译器已编好：$(find "$OUT" -name '*.class' | wc -l) 个类"
