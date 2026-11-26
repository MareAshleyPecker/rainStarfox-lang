#!/bin/sh
# 用刚编好的编译器把 rsfSrc/bootstrap/InterpCli.rsf 编成 C，再用 gcc 出原生解释器。
# 产物：build/dev/native/interp
#
# 用法： sh tools/dev/native.sh [-q]
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEV="$ROOT/build/dev"
OUT="$DEV/native"
RT=$(cat "$DEV/rt.cp" 2>/dev/null) || { echo "先跑 tools/dev/kb.sh" >&2; exit 1; }
[ -d "$DEV/classes" ] || { echo "先跑 tools/dev/kb.sh" >&2; exit 1; }

mkdir -p "$OUT"
cd "$ROOT"
java -cp "$DEV/classes:$RT" org.example.MainKt build rsfSrc/bootstrap/InterpCli.rsf --c -o "$OUT" >"$DEV/native.log" 2>&1 || {
    echo "生成 C 失败："; tail -5 "$DEV/native.log"; exit 1; }

cd "$OUT"
gcc -std=c11 -w -O1 -I "$ROOT/stdlib" -o interp InterpCli.c -lm || { echo "gcc 失败"; exit 1; }

[ "$1" = "-q" ] || echo "原生解释器：$OUT/interp（$(du -h interp | cut -f1)）"
