#!/usr/bin/env bash
# 阶段 27 判据②③：自举字节码后端 vs Kotlin 后端的**逐字节**对拍 + stdout 逐字节对拍。
#
# 语料 = build/verify27/corpus/（7 份正例：默认开放的各类继承形态 + final 类/方法 + 抽象类）
#
# 对每一份：
#   1) Kotlin 后端 `rsf build`                    → out_kotlin/<k>/，跑一遍取 stdout
#   2) 自举后端（A 编出的 BackendCli）             → out_self/<k>/，  跑一遍取 stdout
#   3) `diff -r -q` 两个产物目录 —— 必须**零字节差异**（不是「行为相同」）
#   4) `cmp` 两份 stdout —— 必须逐字节相同
#
# 自举那一列的 classpath 必须**带上产物目录**：Backend.rsf 的 `writeSkeletons`
# 会把尚未落盘的类的类头骨架写进 `rsf.backend.out`，让 ASM 默认的
# `getCommonSuperClass` 能算出帧合并点的 LCA（rsf 写不出 ClassWriter 子类）。
#
# 用法：bash build/verify27/bootstrap-parity.sh [A 所在目录]
#   A 缺省 = build/verify27/A（= Kotlin 后端编 BackendCli.rsf 的产物）
set -u

cd "$(dirname "$0")/../.." || exit 3

JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify27/bootstrap-parity
P=build/verify27/corpus
A="${1:-build/verify27/A}"

if [ ! -f "$JAR" ]; then
    echo "缺少 jar：$JAR（先跑 ./gradlew.bat jar）"
    exit 2
fi

# A 比 Backend.rsf 旧就会静默产红（LVT 那一刀这样假红过一次）：自举后端变了就重建。
if [ ! -d "$A" ] || [ rsfSrc/bootstrap/Backend.rsf -nt "$A" ]; then
    echo "==== 重建自举后端 $A ===="
    rm -rf "$A"
    java -Dstdout.encoding=UTF-8 -jar "$JAR" build rsfSrc/bootstrap/BackendCli.rsf -o "$A" > /dev/null 2>&1 \
        || { echo "重建自举后端失败"; exit 2; }
fi

rm -rf "$ROOT"
mkdir -p "$ROOT/out_kotlin" "$ROOT/out_self"

fail=0
nclass=0
nstdout=0
n=0

for f in $(ls "$P" | grep '\.rsf$' | sed 's/\.rsf$//' | sort -V); do
    n=$((n + 1))

    # ---- 1) Kotlin 后端 ----
    if ! java -Dstdout.encoding=UTF-8 -jar "$JAR" build "$P/$f.rsf" -o "$ROOT/out_kotlin/$f" > "$ROOT/$f.kotlin-build.log" 2>&1; then
        echo "[$f] Kotlin 后端编译失败（$ROOT/$f.kotlin-build.log）"
        fail=$((fail + 1))
        continue
    fi
    java -Dstdout.encoding=UTF-8 -cp "$ROOT/out_kotlin/$f" "$f" > "$ROOT/$f.kotlin.out" 2>&1

    # ---- 2) 自举后端 ----
    mkdir -p "$ROOT/out_self/$f"
    if ! java -Dstdout.encoding=UTF-8 -Drsf.backend.file="$P/$f.rsf" -Drsf.backend.out="$ROOT/out_self/$f" \
              -cp "$ROOT/out_self/$f;$A;$JAR" BackendCli > "$ROOT/$f.self-build.log" 2>&1; then
        echo "[$f] 自举后端编译失败（$ROOT/$f.self-build.log）"
        fail=$((fail + 1))
        continue
    fi
    java -Dstdout.encoding=UTF-8 -cp "$ROOT/out_self/$f" "$f" > "$ROOT/$f.self.out" 2>&1

    # ---- 3) 类文件逐字节 ----
    d=$(diff -r -q "$ROOT/out_kotlin/$f" "$ROOT/out_self/$f" 2>&1 | wc -l)
    # ---- 4) stdout 逐字节 ----
    so=0
    cmp -s "$ROOT/$f.kotlin.out" "$ROOT/$f.self.out" || so=1

    nclass=$((nclass + d))
    nstdout=$((nstdout + so))
    if [ "$d" -ne 0 ] || [ "$so" -ne 0 ]; then
        fail=$((fail + 1))
        echo "[$f] diff -r 差异条目=$d  stdout不同=$so"
        diff -r -q "$ROOT/out_kotlin/$f" "$ROOT/out_self/$f" 2>&1 | head -10
        if [ "$so" -ne 0 ]; then
            diff "$ROOT/$f.kotlin.out" "$ROOT/$f.self.out" | head -10
        fi
    else
        k=$(ls "$ROOT/out_kotlin/$f"/*.class 2>/dev/null | wc -l)
        echo "[$f] 类文件 $k 个  diff -r 差异条目=0  stdout 逐字节相同"
    fi
done

echo "==== 语料 $n 份  失败项合计=$fail  （diff -r 差异条目合计=$nclass，stdout 不同份数=$nstdout） ===="
exit "$fail"
