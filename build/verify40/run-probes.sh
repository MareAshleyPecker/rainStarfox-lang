#!/usr/bin/env bash
# 阶段 40：核心标准库（Vec / HashMap / Text / Deque / Sorts）对拍台
#
#   run    自举解释器（RsfcRun）与 Kotlin 解释器的 stdout 逐字节比
#   build  自举后端（RsfcBuild）与 Kotlin 后端的 class 逐字节比
#   golden 用 Kotlin 解释器重冻 expected/
#   all    以上全跑
#
# 语料是 probes/*.rsf，每个文件一份金标 expected/<名字>.txt。
# 跑：bash build/verify40/run-probes.sh all
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAR="build/libs/rainStarfox-0.9.0.jar"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
SELF="${SELF:-build/verify36/self}"
cd "$REPO" || exit 2

# 路径一律相对仓库根（java -cp 里的 ';' 在 MSYS bash 下会被绝对路径搞坏）
REL="build/verify40"
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

run_only() {
  local bad=0 p base exp
  for p in $(probe_list); do
    base="$(basename "$p" .rsf)"
    exp="$HERE/expected/$base.txt"
    if [ ! -f "$exp" ]; then
      echo "   ★ 缺金标 $base（先跑 golden）"; bad=$((bad+1)); continue
    fi
    java $JOPT -jar "$JAR" run "$p" 2>/dev/null | tr -d '\r' > "$HERE/.kt.txt"
    ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="../../$p" -cp "../../$SELF;../../$JAR" RsfcRun 2>/dev/null ) \
      | tr -d '\r' > "$HERE/.self.txt"
    if ! diff -q "$exp" "$HERE/.kt.txt" > /dev/null; then
      bad=$((bad+1)); echo "   ★ $base：Kotlin 解释器与金标不同（金标过期？跑 golden 重冻）"
    fi
    if ! diff -q "$HERE/.kt.txt" "$HERE/.self.txt" > /dev/null; then
      bad=$((bad+1)); echo "   ★ $base：自举解释器与 Kotlin 解释器不同"
      diff "$HERE/.kt.txt" "$HERE/.self.txt" | head -8 | sed 's/^/      /'
    fi
    rm -f "$HERE"/.kt.txt "$HERE"/.self.txt
  done
  echo "==== 解释器对拍：语料 $(probe_list | wc -l) 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

build_only() {
  local bad=0 p base
  if [ ! -f "$SELF/RsfcBuild.class" ]; then
    echo "   (自举驱动 $SELF/RsfcBuild.class 不存在，跳过 build 段)"; return 0
  fi
  for p in $(probe_list); do
    base="$(basename "$p" .rsf)"
    rm -rf "$HERE/out.kt" "$HERE/out.self"
    mkdir -p "$HERE/out.kt" "$HERE/out.self"
    java $JOPT -jar "$JAR" build "$p" -o "$HERE/out.kt" > /dev/null 2>&1 \
      || { echo "   ★ $base：Kotlin 后端编译失败"; bad=$((bad+1)); continue; }
    ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="../../$p" -Drsf.rsfc.out="out.self" -cp "../../$SELF;../../$JAR" RsfcBuild ) \
      > /dev/null 2>&1 || { echo "   ★ $base：自举后端编译失败"; bad=$((bad+1)); continue; }
    local d
    d=$(diff -r -q "$HERE/out.kt" "$HERE/out.self" 2>/dev/null | wc -l)
    if [ "$d" -ne 0 ]; then
      bad=$((bad+1)); echo "   ★ $base：class 有 $d 处不同"
      diff -r "$HERE/out.kt" "$HERE/out.self" | head -6 | sed 's/^/      /'
    fi
  done
  echo "==== 后端对拍：语料 $(probe_list | wc -l) 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

case "${1:-all}" in
  golden) freeze ;;
  run) run_only ;;
  build) build_only ;;
  all) freeze; run_only; a=$?; build_only; b=$?; [ $((a+b)) -eq 0 ] ;;
  *) echo "用法: $0 {golden|run|build|all}"; exit 2 ;;
esac
