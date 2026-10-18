#!/usr/bin/env bash
# 第 5 刀回归：只用**自己这一份** Backend.rsf 编出来的自举后端（A2），
# 把现有探针台的正例全编一遍，与 Kotlin 后端产物逐字节比。
#
# 目的：在不动 build/verify21/bootbackend/{A,out_*} 这些**共享目录**的前提下
# （第 4b 刀子代理正在用它们），先确认「注解 + 嵌套类型」这两处改动没有回归。
#
# 用法：bash build/verify21/nested/backend-regress.sh

set -u
# 本脚本在 build/verify21/nested/ 下，往上三级才是仓库根
cd "$(dirname "$0")/../../.." || exit 3
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify21/nested/regress
P=build/verify21/bootbackend/probes
POS="p1_default_method p2_default_calls_abstract p3_dispatch p4_iface_inherit p5_multi_iface p6_default_dispatch p7_default_err_capable a1_annot_basic a2_annot_defaults a3_annot_positions a4_annot_arg_kinds"

mkdir -p "$ROOT"
echo "==== [1/2] 用当前 Backend.rsf 构建自举后端 A2 ===="
rm -rf "$ROOT/A2"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build rsfSrc/bootstrap/BackendCli.rsf -o "$ROOT/A2" > "$ROOT/A2.log" 2>&1
acls=$(find "$ROOT/A2" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
echo "A2 类数=$acls"
if [ "$acls" -lt 30 ]; then echo "FAIL：自举后端没编出来（看 $ROOT/A2.log）"; exit 3; fi

echo "==== [2/2] 正例：Kotlin 后端 vs 自举后端 逐字节 ===="
bad=0
for p in $POS; do
    rm -rf "$ROOT/k/$p" "$ROOT/s/$p"; mkdir -p "$ROOT/k/$p" "$ROOT/s/$p"
    java -Dstdout.encoding=UTF-8 -jar "$JAR" build "$P/$p.rsf" -o "$ROOT/k/$p" > "$ROOT/k/$p.log" 2>&1
    java -Dstdout.encoding=UTF-8 -Drsf.backend.file="$P/$p.rsf" -Drsf.backend.out="$ROOT/s/$p" \
        -cp "$ROOT/A2;$JAR" BackendCli > "$ROOT/s/$p.log" 2>&1
    kc=$(find "$ROOT/k/$p" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
    sc=$(find "$ROOT/s/$p" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
    nd=$(diff -r -q "$ROOT/k/$p" "$ROOT/s/$p" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$kc" = "0" ] || [ "$sc" = "0" ]; then
        echo "  [X] $p  有一侧没编出类（kotlin=$kc self=$sc）"; bad=$((bad+1)); continue
    fi
    if [ "$nd" = "0" ]; then
        echo "  [OK] $p  类字节差异=0（$kc 个类）"
    else
        echo "  [X] $p  类字节差异=$nd"; bad=$((bad+1))
    fi
done

echo "==== 判决 ===="
if [ "$bad" -eq 0 ]; then echo "OK：11 个正例与 Kotlin 后端逐字节一致（无回归）"; exit 0; fi
echo "FAIL：$bad 个正例有差异"
exit 1
