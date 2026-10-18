#!/usr/bin/env bash
# 阶段 39：自举 Checker 的「不可达代码」警告对拍（Kotlin `check` vs 自举 CheckCli）
# 比 stderr 全文 + 退出码（文件名两侧都是 basename）。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
JAR="$REPO/build/libs/rainStarfox-0.9.0.jar"
SELF="$REPO/build/verify32/self"
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

total=0; bad=0
for f in "$HERE"/probes/*.rsf; do
  total=$((total+1))
  n="$(basename "$f" .rsf)"
  java $JOPT -jar "$JAR" check "$f" >/dev/null 2>"$HERE/.k"
  kc=$?
  ( cd "$REPO/build/verify32" && java $JOPT -Drsf.check.file="$f" -cp "self;../../build/libs/rainStarfox-0.9.0.jar" CheckCli >/dev/null 2>"$HERE/.s" )
  sc=$?
  if [ "$kc" != "$sc" ] || ! diff -q "$HERE/.k" "$HERE/.s" >/dev/null; then
    bad=$((bad+1)); echo "   $n 不同（退出码 $kc/$sc）"; diff "$HERE/.k" "$HERE/.s" | head -6 | sed 's/^/      /'
  fi
done
rm -f "$HERE"/.k "$HERE"/.s
echo "==== 警告对拍：语料 $total 份  不同 $bad ===="
[ "$bad" -eq 0 ]
