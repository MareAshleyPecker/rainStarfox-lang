#!/usr/bin/env bash
# 阶段 20 前端的**父代理独立验收**（不依赖子代理写的 InterfaceTest）。
# 每个探针都用 rsf 编译器自己的 CLI 跑 check，只看退出码与首行诊断。
# 注意：Java 在这台机器上的默认字符集是 GBK，必须钉住 -Dfile.encoding=UTF-8，
# 否则中文诊断按 GBK 字节输出，grep 用 UTF-8 关键词一条也匹配不上。
set -u
cd /d/java/rainStarfox || exit 1
JAR=build/libs/rainStarfox-0.9.0.jar
DIR=build/verify20
JAVA="java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

pass=0; fail=0
run() { # run <名> <期望:ok|err> <关键词> <源码文件>
  local name=$1 want=$2 kw=$3 src=$4
  local out rc
  out=$($JAVA -jar "$JAR" check "$src" 2>&1); rc=$?
  if [ "$want" = ok ]; then
    if [ $rc -eq 0 ]; then
      printf '  %-24s ok    check 通过\n' "$name"; pass=$((pass+1)); return
    fi
    printf '  %-24s FAIL  期望通过，实得 exit=%s\n%s\n' "$name" "$rc" "$out"; fail=$((fail+1)); return
  fi
  if [ $rc -ne 1 ]; then
    printf '  %-24s FAIL  期望 exit=1，实得 %s\n%s\n' "$name" "$rc" "$out"; fail=$((fail+1)); return
  fi
  if printf '%s' "$out" | grep -q "$kw"; then
    printf '  %-24s ok    exit=1  %s\n' "$name" "$(printf '%s' "$out" | head -1 | cut -c1-110)"
    pass=$((pass+1))
  else
    printf '  %-24s FAIL  文案里没有「%s」：\n%s\n' "$name" "$kw" "$out"; fail=$((fail+1))
  fi
}

echo "==== 阶段 20 前端独立验收（父代理探针） ===="
echo "-- 正例（check 必须通过） --"
run "接口+实现类"            ok  ""  "$DIR/ok_impl.rsf"
run "接口默认方法"           ok  ""  "$DIR/ok_default.rsf"
run "接口继承接口"           ok  ""  "$DIR/ok_extends.rsf"
run "接口当参数/返回/错误返回" ok ""  "$DIR/ok_types.rsf"
run "可空接口 + null 检查"   ok  ""  "$DIR/ok_nullable.rsf"
run "实现 JDK Runnable"      ok  ""  "$DIR/ok_runnable.rsf"

echo "-- 负例（必须 exit 1 且命中关键词） --"
run "抽象方法未实现"        err "没有实现接口要求的全部方法" "$DIR/neg_missing.rsf"
run "签名对不上"            err "对不上"                    "$DIR/neg_signature.rsf"
run "返回类型对不上"        err "返回类型"                  "$DIR/neg_ret.rsf"
run "new 接口"              err "不能 new"                  "$DIR/neg_new.rsf"
run "类继承（不支持）"      err "不支持类继承"              "$DIR/neg_superclass.rsf"
run "接口里写字段"          err "只能写方法"                "$DIR/neg_field.rsf"
run "多接口默认方法冲突"    err "显式"                      "$DIR/neg_conflict.rsf"

echo "==== 合计 通过 $pass / 失败 $fail ===="
[ $fail -eq 0 ] || exit 1
