#!/usr/bin/env bash
# 阶段 40：核心标准库（Vec / HashMap / Text）对拍台
#
#   run    自举解释器（RsfcRun）与 Kotlin 解释器的 stdout 逐字节比
#   build  自举后端（RsfcBuild）与 Kotlin 后端的 class 逐字节比
#   golden 用 Kotlin 解释器重冻 expected/
#   all    以上全跑
#
# 跑：bash build/verify40/run-probes.sh all
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAR="build/libs/rainStarfox-0.9.0.jar"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
SELF="build/verify36/self"
cd "$REPO" || exit 2

P="build/verify40/probes/core_probe.rsf"
EXP="$HERE/expected/core_probe.txt"

freeze() {
  mkdir -p "$HERE/expected"
  java $JOPT -jar "$JAR" run "$P" 2>/dev/null | tr -d '\r' > "$EXP"
  echo "金标已冻结：$(wc -l < "$EXP") 行"
}

run_only() {
  local bad=0
  java $JOPT -jar "$JAR" run "$P" 2>/dev/null | tr -d '\r' > "$HERE/.kt.txt"
  ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="../../$P" -cp "../../$SELF;../../$JAR" RsfcRun 2>/dev/null ) \
    | tr -d '\r' > "$HERE/.self.txt"
  if ! diff -q "$EXP" "$HERE/.kt.txt" > /dev/null; then
    bad=$((bad+1)); echo "   ★ Kotlin 解释器与金标不同（金标过期？跑 golden 重冻）"
  fi
  if ! diff -q "$HERE/.kt.txt" "$HERE/.self.txt" > /dev/null; then
    bad=$((bad+1)); echo "   ★ 自举解释器与 Kotlin 解释器不同"
    diff "$HERE/.kt.txt" "$HERE/.self.txt" | head -8 | sed 's/^/      /'
  fi
  echo "==== 解释器对拍：不同 $bad ===="
  rm -f "$HERE"/.kt.txt "$HERE"/.self.txt
  [ "$bad" -eq 0 ]
}

build_only() {
  local bad=0
  rm -rf "$HERE/out.kt" "$HERE/out.self"
  mkdir -p "$HERE/out.kt" "$HERE/out.self"
  java $JOPT -jar "$JAR" build "$P" -o "$HERE/out.kt" > /dev/null 2>&1 \
    || { echo "   ★ Kotlin 后端编译失败"; bad=1; }
  if [ -d "rsfSrc/bootstrap" ] && [ -f "$SELF/RsfcBuild.class" ]; then
    ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="../../$P" -Drsf.rsfc.out="out.self" -cp "../../$SELF;../../$JAR" RsfcBuild ) \
      > /dev/null 2>&1 || { echo "   ★ 自举后端编译失败"; bad=1; }
  else
    echo "   (自举驱动 $SELF/RsfcBuild.class 不存在，跳过 build 段)"; return 0
  fi
  local d
  d=$(diff -r -q "$HERE/out.kt" "$HERE/out.self" 2>/dev/null | wc -l)
  if [ "$d" -ne 0 ]; then
    bad=$((bad+1)); echo "   ★ class 有 $d 处不同"; diff -r "$HERE/out.kt" "$HERE/out.self" | head -6 | sed 's/^/      /'
  fi
  echo "==== 后端对拍：类文件 $(ls "$HERE/out.kt"/*.class 2>/dev/null | wc -l) 个  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

case "${1:-all}" in
  golden) freeze ;;
  run) run_only ;;
  build) build_only ;;
  all) freeze; run_only; a=$?; build_only; b=$?; [ $((a+b)) -eq 0 ] ;;
  *) echo "用法: $0 {golden|run|build|all}"; exit 2 ;;
esac
