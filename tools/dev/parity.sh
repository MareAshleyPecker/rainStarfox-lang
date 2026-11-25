#!/bin/sh
# 对拍：九个示例分别用「原生解释器」和「Kotlin 版解释器」跑，逐字节比 stdout。
# 成功只打一行；失败才打 diff 的前几行。
#
# 用法： sh tools/dev/parity.sh
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEV="$ROOT/build/dev"
RT=$(cat "$DEV/rt.cp" 2>/dev/null) || { echo "先跑 tools/dev/kb.sh" >&2; exit 1; }
INTERP="$DEV/native/interp"
[ -x "$INTERP" ] || { echo "先跑 tools/dev/native.sh" >&2; exit 1; }

TMP="$DEV/parity"
mkdir -p "$TMP"
cd "$ROOT"

pass=0
fail=0
for f in rsfSrc/lang/*.rsf; do
    n=$(basename "$f" .rsf)
    timeout 180 env rsf.interp.file="$f" "$INTERP" >"$TMP/n_$n.txt" 2>&1 || true
    timeout 180 java -cp "$DEV/classes:$RT" org.example.MainKt run "$f" 2>&1 \
        | grep -v '^\[rsf\] 警告' >"$TMP/j_$n.txt" || true

    if diff -q "$TMP/j_$n.txt" "$TMP/n_$n.txt" >/dev/null 2>&1; then
        pass=$((pass + 1))
    else
        fail=$((fail + 1))
        echo "  ✗ $n"
        diff "$TMP/j_$n.txt" "$TMP/n_$n.txt" | head -6 | sed 's/^/    /'
    fi
done

echo "parity: $pass/$((pass + fail))"
[ "$fail" -eq 0 ]
