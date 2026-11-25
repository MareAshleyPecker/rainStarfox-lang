#!/bin/sh
# 用原生解释器跑一个 .rsf 文件；加 --cmp 再跑一遍 Kotlin 版并 diff。
#
# 用法：
#   sh tools/dev/probe.sh /tmp/x.rsf
#   sh tools/dev/probe.sh --cmp /tmp/x.rsf
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEV="$ROOT/build/dev"
RT=$(cat "$DEV/rt.cp" 2>/dev/null) || { echo "先跑 tools/dev/kb.sh" >&2; exit 1; }
INTERP="$DEV/native/interp"
[ -x "$INTERP" ] || { echo "先跑 tools/dev/native.sh" >&2; exit 1; }

CMP=0
[ "$1" = "--cmp" ] && { CMP=1; shift; }
FILE=$1
[ -n "$FILE" ] || { echo "用法: probe.sh [--cmp] <file.rsf>" >&2; exit 2; }

TMP="$DEV/probe"
mkdir -p "$TMP"
timeout 180 env rsf.interp.file="$FILE" "$INTERP" >"$TMP/n.txt" 2>&1 || true

if [ "$CMP" -eq 1 ]; then
    (cd "$ROOT" && timeout 180 java -cp "$DEV/classes:$RT" org.example.MainKt run "$FILE" 2>&1 \
        | grep -v '^\[rsf\] 警告' >"$TMP/j.txt") || true
    if diff -q "$TMP/j.txt" "$TMP/n.txt" >/dev/null; then
        echo "一致（$(wc -l < "$TMP/n.txt") 行）"
    else
        diff "$TMP/j.txt" "$TMP/n.txt" | head -20
        exit 1
    fi
else
    cat "$TMP/n.txt"
fi
