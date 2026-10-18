#!/usr/bin/env bash
# 阶段 52：C 后端（native 线第一刀）对拍台
#
#   golden  用 Kotlin 解释器重冻 expected/
#   c       每个探针：生成 C → gcc 编成 exe → 跑 → stdout 与解释器逐字节比
#   all     golden + c
#
# 语料是 probes/*.rsf，每个文件一份金标 expected/<名字>.txt。
# 跑：bash build/verify52/run-probes.sh all
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAR="build/libs/rainStarfox-0.9.0.jar"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
cd "$REPO" || exit 2

REL="build/verify52"
OUT="$HERE/out"
probe_list() {
  ls "$REL"/probes/*.rsf 2>/dev/null | sort
}

freeze() {
  mkdir -p "$HERE/expected"
  local p base
  for p in $(probe_list); do
    base="$(basename "$p" .rsf)"
    java $JOPT -jar "$JAR" run "$p" 2>/dev/null | tr -d '\r' > "$HERE/expected/$base.txt"
    echo "金标已冻结：$base（$(wc -l < "$HERE/expected/$base.txt") 行）"
  done
}

run_c() {
  local bad=0 p base exp got
  mkdir -p "$OUT"
  for p in $(probe_list); do
    base="$(basename "$p" .rsf)"
    exp="$HERE/expected/$base.txt"
    if [ ! -f "$exp" ]; then
      echo "  缺金标：$base（先跑 golden）"
      bad=$((bad + 1))
      continue
    fi
    if ! java $JOPT -jar "$JAR" build "$p" --c -o "$OUT" > "$OUT/$base.emit.log" 2>&1; then
      echo "  生成 C 失败：$base"
      sed -n '1,5p' "$OUT/$base.emit.log"
      bad=$((bad + 1))
      continue
    fi
    if ! gcc "$OUT/$base.c" -o "$OUT/$base.exe" 2> "$OUT/$base.gcc.log"; then
      echo "  gcc 失败：$base"
      sed -n '1,5p' "$OUT/$base.gcc.log"
      bad=$((bad + 1))
      continue
    fi
    got="$(cd "$OUT" && ./"$base.exe" | tr -d '\r')"
    if [ "$got" != "$(cat "$exp")" ]; then
      echo "  输出不同：$base"
      diff -u "$exp" <(printf '%s\n' "$got") | sed -n '1,12p'
      bad=$((bad + 1))
    fi
  done
  echo "==== C 后端对拍：语料 $(probe_list | wc -l) 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

case "${1:-all}" in
  golden) freeze ;;
  c) run_c ;;
  all) freeze; run_c ;;
  *) echo "用法: $0 [golden|c|all]"; exit 2 ;;
esac
