#!/usr/bin/env bash
# 比较两个 class 目录是否「反汇编等价」。
# 字节不要求相同：自举后端用自己写的 class 写出器，没有 StackMapTable、maxs 也不同。
set -u
A="$1"; B="$2"
LA="$(cd "$A" && ls *.class 2>/dev/null | sort)"; LB="$(cd "$B" && ls *.class 2>/dev/null | sort)"
[ "$LA" = "$LB" ] || { echo "类集合不同"; exit 1; }
for f in $LA; do
  if ! diff -q <(javap -p -c "$A/$f" 2>/dev/null | python3 "$(dirname "$0")/norm.py") \
               <(javap -p -c "$B/$f" 2>/dev/null | python3 "$(dirname "$0")/norm.py") > /dev/null; then
    echo "类 $f 反汇编不同"
    exit 1
  fi
done
exit 0
