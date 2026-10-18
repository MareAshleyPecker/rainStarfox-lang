#!/usr/bin/env bash
# 阶段 36b：自举驱动 `Rsfc` 的 `check` 对拍（Kotlin `rsf check` 为 oracle）
#
# 口径：stdout + stderr + 退出码逐字节相同（stderr 含源码片段与 caret，先去 CR）。
# 语料：build/verify32/okcorpus/*.rsf（6 份正例）+ build/verify32/corpus/*.rsf（错误语料）。
#
# 跑：bash build/verify36/run-probes.sh check|all
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
cd "$REPO" || exit 2
JAR="build/libs/rainStarfox-0.9.0.jar"
SELF="$HERE/self"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

build_self() {
  mkdir -p "$SELF"
  java -jar "$JAR" build rsfSrc/bootstrap/Rsfc.rsf -o "$SELF" > /dev/null 2>&1 \
    || { echo "自举侧 Rsfc.rsf 编译失败"; exit 1; }
  # 三个驱动一起重建：只重建 Rsfc 会留下旧的 RsfcBuild/RsfcRun（阶段 41 踩过）
  java -jar "$JAR" build rsfSrc/bootstrap/RsfcBuild.rsf -o "$SELF" > /dev/null 2>&1 \
    || { echo "自举侧 RsfcBuild.rsf 编译失败"; exit 1; }
  java -jar "$JAR" build rsfSrc/bootstrap/RsfcRun.rsf -o "$SELF" > /dev/null 2>&1 \
    || { echo "自举侧 RsfcRun.rsf 编译失败"; exit 1; }
}

# 自举侧：在 build/verify36 里用相对 classpath 跑（MSYS bash 会吃掉绝对路径里的 `;`）
self_check() {
  ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="$1" -cp "self;../../$JAR" Rsfc 2>&1 )
}

run_check() {
  local total=0 bad=0
  for f in "$REPO"/build/verify32/okcorpus/*.rsf "$REPO"/build/verify32/corpus/*.rsf; do
    total=$((total+1))
    local n; n="$(basename "$f")"
    java $JOPT -jar "$JAR" check "$f" > "$HERE/.kt.out" 2>&1; local kt_code=$?
    self_check "$f" > "$HERE/.self.out" 2>&1; local self_code=$?
    tr -d '\r' < "$HERE/.kt.out" > "$HERE/.kt.txt"
    tr -d '\r' < "$HERE/.self.out" > "$HERE/.self.txt"
    if [ "$kt_code" != "$self_code" ] || ! diff -q "$HERE/.kt.txt" "$HERE/.self.txt" > /dev/null; then
      bad=$((bad+1))
      echo "   $n 不同（退出码 $kt_code vs $self_code）"
      diff -u "$HERE/.kt.txt" "$HERE/.self.txt" | sed -n '3,8p' | sed 's/^/      /'
    fi
  done
  rm -f "$HERE"/.kt.out "$HERE"/.self.out "$HERE"/.kt.txt "$HERE"/.self.txt
  echo "==== Rsfc check：语料 $total 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

# 阶段 36c：`build -o` 对拍（stdout 归一化分隔符后逐字节 + class 目录 diff -r）
run_build() {
  local total=0 bad=0
  for f in "$REPO"/build/verify32/okcorpus/*.rsf; do
    total=$((total+1))
    local n; n="$(basename "$f" .rsf)"
    rm -rf "$HERE/bk.$n" "$HERE/bs.$n"
    ( cd "$HERE" && java $JOPT -jar "../../$JAR" build "$f" -o "bk.$n" 2>&1 ) | sed -e $'s/$//' | tr '\\' '/' > "$HERE/.bk"
    ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="$f" -Drsf.rsfc.out="bs.$n" -cp "self;../../$JAR" RsfcBuild 2>&1 )       | sed -e $'s/$//' | tr '\\' '/' | sed "s#bs\.$n#bk.$n#g" > "$HERE/.bs"
    if ! diff -q "$HERE/.bk" "$HERE/.bs" > /dev/null; then
      bad=$((bad+1)); echo "   $n 的 build 输出不同"; diff "$HERE/.bk" "$HERE/.bs" | head -6 | sed 's/^/      /'
    elif ! diff -r "$HERE/bk.$n" "$HERE/bs.$n" > /dev/null; then
      bad=$((bad+1)); echo "   $n 的 class 不同"
    fi
  done
  rm -f "$HERE"/.bk "$HERE"/.bs
  echo "==== Rsfc build：语料 $total 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

# 阶段 36c：`run` 对拍（只比 stdout + 退出码；stderr 文案差异不在本刀范围）
run_run() {
  local total=0 bad=0
  for f in "$REPO"/build/verify32/okcorpus/*.rsf; do
    total=$((total+1))
    local n; n="$(basename "$f" .rsf)"
    java $JOPT -jar "$JAR" run "$f" > "$HERE/.rk" 2>/dev/null; local kc=$?
    ( cd "$HERE" && java $JOPT -Drsf.rsfc.file="$f" -cp "self;../../$JAR" RsfcRun > "$HERE/.rs" 2>/dev/null ); local sc=$?
    sed -e $'s/$//' < "$HERE/.rk" > "$HERE/.rkn"
    sed -e $'s/$//' < "$HERE/.rs" > "$HERE/.rsn"
    if [ "$kc" != "$sc" ] || ! diff -q "$HERE/.rkn" "$HERE/.rsn" > /dev/null; then
      bad=$((bad+1)); echo "   $n 的 run 不同"; diff "$HERE/.rkn" "$HERE/.rsn" | head -6 | sed 's/^/      /'
    fi
  done
  rm -f "$HERE"/.rk "$HERE"/.rs "$HERE"/.rkn "$HERE"/.rsn
  echo "==== Rsfc run：语料 $total 份  不同 $bad ===="
  [ "$bad" -eq 0 ]
}

case "${1:-all}" in
  check) build_self; run_check ;;
  build) build_self; run_build ;;
  run) build_self; run_run ;;
  all)
    build_self
    run_check; a=$?
    run_build; b=$?
    run_run; c=$?
    [ $((a+b+c)) -eq 0 ]
    ;;
  *) echo "用法: run-probes.sh [check|all]"; exit 2 ;;
esac
