#!/usr/bin/env bash
# 第 5 刀隔离版 J1/J3：用**自己这一份** Backend.rsf 编出的自举后端 A2，
# 把 9 个自举源各编一遍，与 Kotlin 后端产物逐字节比 + 数类总数。
# 与 build/verify21/bootbackend/j1-knife2.sh 同判据，但全部落在 nested/regress 下，
# 不碰共享目录（第 4b 刀子代理正在用）。
#
# 用法：bash build/verify21/nested/j1-isolated.sh

set -u
cd "$(dirname "$0")/../../.." || exit 3
JAR=build/libs/rainStarfox-0.9.0.jar
R=build/verify21/nested/regress
A2=$R/A2
SRCS="Backend BackendCli Interp InterpCli Lexer LexerCli Parser ParserCli Sources"

if [ ! -d "$A2" ]; then echo "先跑 build/verify21/nested/backend-regress.sh（要 $A2）"; exit 3; fi

echo "==== 逐源对拍（Kotlin 后端 vs 自举后端 A2）===="
bad=0
for n in $SRCS; do
    src=rsfSrc/bootstrap/$n.rsf
    rm -rf "$R/j1k/$n" "$R/j1s/$n"; mkdir -p "$R/j1k/$n" "$R/j1s/$n"
    java -Dstdout.encoding=UTF-8 -jar "$JAR" build "$src" -o "$R/j1k/$n" > "$R/j1k/$n.log" 2>&1
    kc=$(find "$R/j1k/$n" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
    java -Dstdout.encoding=UTF-8 -Drsf.backend.file="$src" -Drsf.backend.out="$R/j1s/$n" \
        -cp "$A2;$JAR" BackendCli > "$R/j1s/$n.log" 2>&1
    sc=$(find "$R/j1s/$n" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
    if [ "$k" = "" ]; then :; fi
    if [ "$kc" = "0" ]; then
        # Kotlin 后端对**非入口**的库源拒编（Backend/Interp/Lexer/Parser/Sources 没有 main）
        echo "  [--] $n  Kotlin 拒编（非入口文件，自举侧 $sc 个类）"
        continue
    fi
    nd=$(diff -r -q "$R/j1k/$n" "$R/j1s/$n" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$sc" = "0" ]; then echo "  [X] $n  自举侧没编出类"; bad=$((bad+1)); continue; fi
    if [ "$nd" = "0" ]; then echo "  [OK] $n  $kc 个类逐字节一致"; else echo "  [X] $n  差异 $nd 条"; bad=$((bad+1)); fi
done

echo "==== 自举后端编出的类总数（历史值 134）===="
tot=0
for n in $SRCS; do
    c=$(find "$R/j1s/$n" -name '*.class' 2>/dev/null | wc -l | tr -d ' ')
    tot=$((tot+c))
done
echo "总类数=$tot"

echo "==== 判决 ===="
if [ "$bad" -eq 0 ]; then echo "OK：逐源对拍无差异"; exit 0; fi
echo "FAIL：$bad 项"
exit 1
