#!/usr/bin/env bash
# 阶段 32：Checker 对拍台。Kotlin 的 `rsf check` 是 oracle；自举侧将来由
# `rsfSrc/bootstrap/CheckCli.rsf` 提供（同一份语料、同样第一处错误）。
# 只比「诊断正文」与退出码：正文 = stderr 里第一条 `文件:行:列: error[阶段]: 文案`
# 连同它的续行（到源码片段那一行为止，片段与脱字符由阶段 36 的诊断渲染负责）。
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$(cd "$ROOT/../.." && pwd)/build/libs/rainStarfox-0.9.0.jar"
CORPUS="$ROOT/corpus"
EXPECTED="$ROOT/expected"
SELF="$ROOT/self"            # 自举侧产物（build/verify32/self/CheckCli.class）
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

# 诊断正文：去掉源码片段与脱字符块
msg_of() {
  tr -d '\r' | awk 'BEGIN{s=0} /^ *[0-9]+ \| /{s=1} !s{print}'
}

kotlin_check() {
  java $JOPT -jar "$JAR" check "$1" 2>&1 >/dev/null
}

self_check() {
  # MSYS bash 会把绝对路径 classpath 里的 `;` 吃掉（见项目笔记），改用相对路径 + 子 shell
  ( cd "$ROOT" && java $JOPT -Drsf.check.file="$1" -cp "self;../../build/libs/rainStarfox-0.9.0.jar" CheckCli 2>&1 >/dev/null )
}

freeze() {
  local bad=0
  mkdir -p "$EXPECTED"
  for f in "$CORPUS"/*.rsf; do
    n="$(basename "$f" .rsf)"
    out="$(kotlin_check "$f")"; code=$?
    printf '%s' "$out" | msg_of > "$EXPECTED/$n.msg"
    printf '%s\n' "$code" > "$EXPECTED/$n.code"
    [ "$code" -eq 0 ] && { echo "  ! $n 没有报错（金标必须是错误用例）"; bad=$((bad+1)); }
    [ -s "$EXPECTED/$n.msg" ] || { echo "  ! $n 诊断正文为空"; bad=$((bad+1)); }
  done
  echo "金标已冻结：$(ls "$CORPUS"/*.rsf | wc -l) 份，问题 $bad 份"
  return 0
}

compare_side() {
  local label="$1" fn="$2"
  local total=0 stderr_cnt=0 code_cnt=0
  for f in "$CORPUS"/*.rsf; do
    n="$(basename "$f" .rsf)"
    total=$((total+1))
    out="$($fn "$f")"; code=$?
    want_code="$(cat "$EXPECTED/$n.code")"
    diff_msg="$(printf '%s' "$out" | msg_of | diff -u "$EXPECTED/$n.msg" - 2>&1)"
    if [ "$code" != "$want_code" ]; then
      code_cnt=$((code_cnt+1))
      echo "   $n 退出码 $code ≠ $want_code"
    fi
    if [ -n "$diff_msg" ]; then
      stderr_cnt=$((stderr_cnt+1))
      echo "   $n 诊断正文不同："
      printf '%s\n' "$diff_msg" | sed -n '3,12p' | sed 's/^/      /'
    fi
  done
  echo "==== $label：语料 $total 份  退出码不同 $code_cnt  正文不同 $stderr_cnt ===="
  [ $((code_cnt+stderr_cnt)) -eq 0 ]
}

case "${1:-all}" in
  freeze) freeze ;;
  kotlin) echo "== Kotlin 侧 vs 冻结金标 ==" ; compare_side "kotlin" kotlin_check ;;
  self)
    if [ ! -f "$SELF/CheckCli.class" ]; then
      echo "==== self：自举侧尚未实现（$SELF/CheckCli.class 不存在），跳过 ===="
      exit 0
    fi
    echo "== 自举侧 vs 冻结金标 =="; compare_side "self" self_check ;;
  all)
    freeze
    echo "== Kotlin 侧 vs 冻结金标 =="; compare_side "kotlin" kotlin_check || exit 1
    if [ -f "$SELF/CheckCli.class" ]; then
      echo "== 自举侧 vs 冻结金标 =="; compare_side "self" self_check
    else
      echo "==== self：自举侧尚未实现，跳过（阶段 32c/33 之后才会转绿）===="
    fi
    ;;
  *) echo "用法: run-parity.sh [freeze|kotlin|self|all]"; exit 2 ;;
esac
