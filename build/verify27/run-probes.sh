#!/usr/bin/env bash
# 阶段 27 收口判据（父代理独立复验）：删掉 `open`（默认开放、`final` 才封）之后，
# 两端 + 自举链是否仍然同步。
#
# 用法：bash build/verify27/run-probes.sh [pos|neg|ast|parity|interp|all]
#   pos    7 份正例：Kotlin 后端编译 + 运行，stdout 与 expected/ 逐字节比
#   interp 7 份正例：Kotlin 解释器直跑，stdout 与 expected/ 逐字节比（两端一头一尾）
#   neg    6 份负例：Kotlin 前端必须拒，且报错里带期望关键词（口径变了就要改这里）
#   ast    自举 ParserCli vs Kotlin ParserCli 的 dump 逐字节对拍（build/verify27/ast-verify.sh）
#   parity 自举后端 vs Kotlin 后端：非入口类逐字节 + stdout 逐字节（build/verify27/bootstrap-parity.sh）
#   all    上面五段全跑
#
# 判据口径：只在「语料 / 期望」上做硬比较；脚本自身不写金标（金标是人工核过后冻进 expected/ 的）。
set -u
cd "$(dirname "$0")/../.." || exit 3

JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify27
C=$ROOT/corpus
N=$ROOT/neg
E=$ROOT/expected
TMP=$ROOT/out
mkdir -p "$TMP"

MODE="${1:-all}"
FAILED=0

if [ ! -f "$JAR" ]; then echo "缺少 jar：$JAR（先跑 ./gradlew.bat jar）"; exit 2; fi

# 负例 → 期望的报错关键词（新口径下 6 条错法各有各的句子）
expect_of() {
    case "$1" in
        n1_extend_final_class)     echo "是 final 的（DESIGN-27）" ;;
        n2_override_final_method)  echo "父方法写了 'final'（DESIGN-27）" ;;
        n3_open_keyword)           echo "只能写函数声明" ;;
        n4_abstract_final)         echo "不能和 'final' 写在一起" ;;
        n5_static_final)           echo "静态方法不能是 'final' / 'abstract'" ;;
        n6_missing_override)       echo "没写 override" ;;
        *) echo "" ;;
    esac
}

seg_pos() {
    echo "==== [pos] 正例：Kotlin 后端编译 + 运行，比 expected/ ===="
    local fail=0 n=0
    for f in "$C"/*.rsf; do
        local b; b=$(basename "$f" .rsf); n=$((n + 1))
        rm -rf "$TMP/$b"
        if ! java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build "$f" -o "$TMP/$b" > "$TMP/$b.build.log" 2>&1; then
            echo "  [X] $b 编译失败"; sed -n 1,3p "$TMP/$b.build.log"; fail=$((fail + 1)); continue
        fi
        java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$TMP/$b" "$b" > "$TMP/$b.out" 2>&1
        if cmp -s "$TMP/$b.out" "$E/$b.txt"; then
            echo "  [OK] $b  stdout 逐字节相同"
        else
            echo "  [X] $b  stdout 不同"; diff "$E/$b.txt" "$TMP/$b.out" | head -6; fail=$((fail + 1))
        fi
    done
    echo "  → 正例 $n 份，不过 $fail 份"
    return "$fail"
}

seg_interp() {
    echo "==== [interp] 正例：Kotlin 解释器直跑，比 expected/ ===="
    local fail=0 n=0
    for f in "$C"/*.rsf; do
        local b; b=$(basename "$f" .rsf); n=$((n + 1))
        java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" run "$f" > "$TMP/$b.interp.out" 2>&1
        if cmp -s "$TMP/$b.interp.out" "$E/$b.txt"; then
            echo "  [OK] $b"
        else
            echo "  [X] $b  解释器输出与金标不符"; diff "$E/$b.txt" "$TMP/$b.interp.out" | head -6; fail=$((fail + 1))
        fi
    done
    echo "  → 正例 $n 份，不过 $fail 份"
    return "$fail"
}

seg_neg() {
    echo "==== [neg] 负例：Kotlin 前端必须拒，且带期望关键词 ===="
    local fail=0 n=0
    for f in "$N"/*.rsf; do
        local b; b=$(basename "$f" .rsf); n=$((n + 1))
        local want; want=$(expect_of "$b")
        if java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build "$f" -o "$TMP/neg_$b" > "$TMP/$b.neg.log" 2>&1; then
            echo "  [X] $b  居然编译成功了（应拒）"; fail=$((fail + 1)); continue
        fi
        if [ -z "$want" ]; then
            echo "  [?] $b  没有登记期望关键词（加了负例要同步 expect_of）"; fail=$((fail + 1)); continue
        fi
        if grep -qF "$want" "$TMP/$b.neg.log"; then
            echo "  [OK] $b  拒绝且带关键词：$want"
        else
            echo "  [X] $b  拒绝了但报错里没有：$want"; sed -n 1,3p "$TMP/$b.neg.log"; fail=$((fail + 1))
        fi
    done
    echo "  → 负例 $n 份，不过 $fail 份"
    return "$fail"
}

if [ "$MODE" = pos ] || [ "$MODE" = all ]; then
    seg_pos || FAILED=$((FAILED + 1))
fi
if [ "$MODE" = interp ] || [ "$MODE" = all ]; then
    seg_interp || FAILED=$((FAILED + 1))
fi
if [ "$MODE" = neg ] || [ "$MODE" = all ]; then
    seg_neg || FAILED=$((FAILED + 1))
fi
if [ "$MODE" = ast ] || [ "$MODE" = all ]; then
    echo "==== [ast] 自举解析 vs Kotlin 解析 ===="
    bash "$ROOT/ast-verify.sh" || FAILED=$((FAILED + 1))
fi
if [ "$MODE" = parity ] || [ "$MODE" = all ]; then
    echo "==== [parity] 自举后端 vs Kotlin 后端 ===="
    bash "$ROOT/bootstrap-parity.sh" || FAILED=$((FAILED + 1))
fi

echo "==== 阶段 27 判决：不过的段数=$FAILED ===="
exit "$FAILED"
