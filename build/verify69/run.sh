#!/bin/sh
# 判据台 69：native 内存管理（分配器 + 上下文 + arena）
#
# 只用 gcc 验证 C 侧的运行时——不需要 JDK，也不依赖 Kotlin 编译。
# 头文件由 gen_runtime.py 从 CBackend.kt 里的四个原始字符串反推，
# 与 runtimeLibSource() 走同一套变换（含“分配点不能绕过分配器”的守卫）。
#
# 用法：  sh build/verify69/run.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$HERE/out"

mkdir -p "$OUT"
python3 "$HERE/gen_runtime.py" "$ROOT/src/main/kotlin/org/example/complie/native/CBackend.kt" -o "$OUT/rsfRuntimelib.h"

cd "$OUT"
for flags in "-std=c11 -Wall -Wextra -Werror" \
             "-std=c99 -Wall -Wextra -Werror" \
             "-std=c11 -DRSF_NO_TLS -Wall -Wextra -Werror" \
             "-std=gnu17 -O2"; do
    printf '%-46s ' "$flags"
    gcc $flags -I"$OUT" -o "$OUT/t" "$HERE/test_a.c" "$HERE/test_b.c"
    "$OUT/t" >/dev/null
    echo ok
done

echo "verify69: 全部通过"
