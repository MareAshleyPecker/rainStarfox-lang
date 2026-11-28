#!/bin/sh
# 编译 Kotlin 编译器本体（不经过 gradle：本机没有 JDK 25，jvmToolchain(25) 过不去）。
# 依赖全部取自 ~/.gradle/caches；产物落 build/dev/classes。
#
# **版本不写死**：每个 artifact 取缓存里版本号最大的那一份。缓存会随 gradle 刷新而变
# （2026-11-28 就遇到过：缓存里 2.3.10 被清掉、只剩 2.4.21，写死版本号直接编不了）。
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

# 某个 artifact 下「版本号最大」那一份里的第一个 jar（没有就输出空）
jar_of() {
    d=$(ls "$C/$1" 2>/dev/null | sort -V | tail -1)
    [ -n "$d" ] || return 0
    find "$C/$1/$d" -name '*.jar' 2>/dev/null | head -1
}

KC=""
for a in \
    org.jetbrains.kotlin/kotlin-compiler-embeddable \
    org.jetbrains.kotlin/kotlin-daemon-embeddable \
    org.jetbrains.kotlin/kotlin-script-runtime \
    org.jetbrains.kotlin/kotlin-stdlib \
    org.jetbrains.intellij.deps/trove4j \
    org.jetbrains/annotations \
    org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm \
    org.jetbrains.kotlin/kotlin-reflect
do
    j=$(jar_of "$a")
    [ -n "$j" ] && KC="$KC$j:"
done

RT=""
for a in \
    org.jetbrains.kotlin/kotlin-stdlib \
    org.ow2.asm/asm \
    org.ow2.asm/asm-util \
    org.ow2.asm/asm-tree \
    org.ow2.asm/asm-analysis
do
    j=$(jar_of "$a")
    [ -n "$j" ] && RT="$RT$j:"
done

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
