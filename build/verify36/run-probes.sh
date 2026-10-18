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

case "${1:-all}" in
  check|all) build_self; run_check ;;
  *) echo "用法: run-probes.sh [check|all]"; exit 2 ;;
esac
