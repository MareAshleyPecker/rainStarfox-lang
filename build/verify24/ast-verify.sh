#!/usr/bin/env bash
# 第 5 刀（嵌套类型名 `注解::伴生`）· 父代理独立复验：rsf 版 dump 与 Kotlin 版 dump 逐字节比。
#
# 语料：build/verify24/astparity/{corpus,neg}
# 参照物：测试源集里的 AstDump（复用第 3 刀的 org.example.KtDumpKt：build/verify21/annot/ktclasses/org.example.KtDumpKt.class）
#
# 用法：bash build/verify21/nested-verify.sh
# 退出码：0 = 正例逐字节一致且负例两侧都拒；非 0 = 有项不过（明细打在 stdout）。

set -u
cd "$(dirname "$0")/../.." || exit 3
ROOT=build/verify24/astparity
JAR=build/libs/rainStarfox-0.9.0.jar
OUT=$ROOT/out
DUMPS=$ROOT/dumps
KTCP="build/classes/kotlin/test;$JAR"
mkdir -p "$OUT" "$DUMPS"

echo "==== [1/4] 用当前 Parser.rsf 构建 ParserCli ===="
rm -rf "$OUT"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build rsfSrc/bootstrap/ParserCli.rsf -o "$OUT" 2>&1 | tail -3
cls=$(find "$OUT" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
echo "ParserCli 类数=$cls"
if [ "$cls" -lt 1 ]; then echo "FAIL：ParserCli 没编出来"; exit 3; fi

norm() { sed -e 's/\r$//' "$1"; }

echo "==== [2/4] 正例：rsf dump vs Kotlin dump 逐字节比 ===="
pos_fail=0; pos_total=0
for f in "$ROOT"/corpus/*.rsf; do
    base=$(basename "$f" .rsf); pos_total=$((pos_total+1))
    abs=$(cd "$(dirname "$f")" && pwd)/$(basename "$f")
    java -Dstdout.encoding=UTF-8 -Drsf.ast.file="$abs" -cp "$OUT;$JAR" ParserCli > "$DUMPS/$base.rsf.txt" 2>"$DUMPS/$base.rsf.err"
    rsf_code=$?
    java -Dstdout.encoding=UTF-8 -cp "$KTCP" org.example.KtDumpKt "$abs" > "$DUMPS/$base.kt.txt" 2>"$DUMPS/$base.kt.err"
    kt_code=$?
    if [ "$kt_code" -ne 0 ]; then echo "  [?] $base：Kotlin 参照物自己就失败（语料问题，不是判据）"; continue; fi
    norm "$DUMPS/$base.rsf.txt" > "$DUMPS/$base.rsf.n"
    norm "$DUMPS/$base.kt.txt"  > "$DUMPS/$base.kt.n"
    if [ "$rsf_code" -ne 0 ] || grep -q '解析失败' "$DUMPS/$base.rsf.txt"; then
        echo "  [X] $base：rsf 侧解析失败"; pos_fail=$((pos_fail+1)); continue
    fi
    if cmp -s "$DUMPS/$base.rsf.n" "$DUMPS/$base.kt.n"; then
        echo "  [OK] $base  $(wc -l < "$DUMPS/$base.kt.n" | tr -d ' ') 行一致"
    else
        first=$(diff "$DUMPS/$base.rsf.n" "$DUMPS/$base.kt.n" | head -6 | tr '\n' '|')
        echo "  [X] $base：dump 不一致 → $first"; pos_fail=$((pos_fail+1))
    fi
done

echo "==== [3/4] 负例：两侧都必须拒 ===="
neg_fail=0; neg_total=0
for f in "$ROOT"/neg/*.rsf; do
    base=$(basename "$f" .rsf); neg_total=$((neg_total+1))
    abs=$(cd "$(dirname "$f")" && pwd)/$(basename "$f")
    java -Dstdout.encoding=UTF-8 -Drsf.ast.file="$abs" -cp "$OUT;$JAR" ParserCli > "$DUMPS/neg-$base.rsf.txt" 2>&1
    rsf_code=$?
    java -Dstdout.encoding=UTF-8 -cp "$KTCP" org.example.KtDumpKt "$abs" > /dev/null 2>&1
    kt_code=$?
    rsf_rej=no; { [ "$rsf_code" -ne 0 ] || grep -q '解析失败' "$DUMPS/neg-$base.rsf.txt"; } && rsf_rej=yes
    kt_rej=no; [ "$kt_code" -ne 0 ] && kt_rej=yes
    if [ "$rsf_rej" = yes ] && [ "$kt_rej" = yes ]; then
        echo "  [OK] $base  rsf拒=$rsf_rej kotlin拒=$kt_rej"
    else
        echo "  [X] $base  rsf拒=$rsf_rej(exit=$rsf_code) kotlin拒=$kt_rej(exit=$kt_code)"; neg_fail=$((neg_fail+1))
    fi
done

echo "==== [4/4] 判决 ===="
echo "正例 $pos_total 项，不过 $pos_fail 项；负例 $neg_total 项，不过 $neg_fail 项"
if [ "$pos_fail" -eq 0 ] && [ "$neg_fail" -eq 0 ]; then echo "OK：两侧 AST 完全一致（阶段 24 对拍语料）"; exit 0; fi
echo "FAIL：见上面 [X] 行"; exit 1
