#!/usr/bin/env bash
# 自举同步第 2 刀（Backend.rsf / Interp.rsf 支持接口）的探针台。
#
# 用法：
#   bash build/verify21/bootbackend/run-probes.sh kotlin   # 只用 Kotlin 版编译器（本体自检，两边同一套产物）
#   bash build/verify21/bootbackend/run-probes.sh self     # 用自举工具链（A = Kotlin 编 BackendCli；rsf-interp = Kotlin 编 InterpCli）
#   bash build/verify21/bootbackend/run-probes.sh both     # 两套都跑，逐字节比对（第 2 刀的正式验收）
#
# 口径（与 InterfaceRuntimeTest 一致）：同一份源码，解释器 stdout 与字节码 stdout 必须逐字节相同；
# 两套编译器（Kotlin / 自举）产出的 .class 也必须逐字节相同。
#
# 四列含义：
#   interp      = Kotlin 解释器（`rsf run`，`Main.kt:52-58 → Rsf.kt:71`）
#   kotlin      = Kotlin 后端编出的类和它的 stdout
#   self        = 自举后端（BackendCli 经 -Drsf.backend.file 驱动）编出的类和它的 stdout
#   rsf-interp  = 自举解释器（InterpCli 经 -Drsf.interp.file 驱动）★这一列才覆盖 Interp.rsf
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify21/bootbackend
P="$ROOT/probes"
A="$ROOT/A"                      # 自举后端（Kotlin 编 BackendCli.rsf 的产物）
I="$ROOT/InterpK"                # 自举解释器（Kotlin 编 InterpCli.rsf 的产物）
MODE="${1:-kotlin}"

POS="p1_default_method p2_default_calls_abstract p3_dispatch p4_iface_inherit p5_multi_iface p6_default_dispatch p7_default_err_capable"
NEG="n1_new_interface n2_ambiguous_default n3_static_interface_method"
fail=0

build_kotlin() { # $1=probe  $2=outdir
  rm -rf "$2"; mkdir -p "$2"
  java -jar "$JAR" build "$P/$1.rsf" -o "$2" > "$2.log" 2>&1
}
build_self() {   # $1=probe  $2=outdir
  rm -rf "$2"; mkdir -p "$2"
  java -Drsf.backend.file="$P/$1.rsf" -Drsf.backend.out="$2" -cp "$A;$JAR" BackendCli > "$2.log" 2>&1
}
run_class() { java -cp "$1" "$2" 2>&1; }
run_rsf_interp() { java -Drsf.interp.file="$P/$1.rsf" -cp "$I;$JAR" InterpCli 2>&1; }

if [ "$MODE" = "self" ] || [ "$MODE" = "both" ]; then
  echo "=== 准备自举工具链（都用 Kotlin 编出来）==="
  rm -rf "$A"; mkdir -p "$A"
  java -jar "$JAR" build rsfSrc/bootstrap/BackendCli.rsf -o "$A" > "$ROOT/A.log" 2>&1
  echo "A(自举后端) exit=$? classes=$(find "$A" -name '*.class' 2>/dev/null | wc -l)"
  rm -rf "$I"; mkdir -p "$I"
  java -jar "$JAR" build rsfSrc/bootstrap/InterpCli.rsf -o "$I" > "$ROOT/InterpK.log" 2>&1
  echo "rsf-interp(自举解释器) exit=$? classes=$(find "$I" -name '*.class' 2>/dev/null | wc -l)"
fi

echo
echo "=== 正例：解释器 stdout vs 字节码 stdout（每套编译器各一次）==="
for p in $POS; do
  java -jar "$JAR" run "$P/$p.rsf" > "$ROOT/$p.interp.txt" 2>"$ROOT/$p.interp.err"
  iec=$?
  line="$(printf '%-26s interp(exit=%s)' "$p" "$iec")"
  if [ "$MODE" = "kotlin" ] || [ "$MODE" = "both" ]; then
    build_kotlin "$p" "$ROOT/out_kotlin/$p"; kec=$?
    run_class "$ROOT/out_kotlin/$p" "$p" > "$ROOT/$p.kotlin.txt"
    diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.kotlin.txt" >/dev/null && kd="同" || { kd="异"; fail=$((fail+1)); }
    line="$(printf '%-26s interp(exit=%s)  kotlin(build_exit=%s stdout=%s)' "$p" "$iec" "$kec" "$kd")"
  fi
  if [ "$MODE" = "self" ] || [ "$MODE" = "both" ]; then
    build_self "$p" "$ROOT/out_self/$p"; sec=$?
    run_class "$ROOT/out_self/$p" "$p" > "$ROOT/$p.self.txt"
    diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.self.txt" >/dev/null && sd="同" || { sd="异"; fail=$((fail+1)); }
    run_rsf_interp "$p" > "$ROOT/$p.rsfinterp.txt"
    ric=$?
    diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.rsfinterp.txt" >/dev/null && id="同" || { id="异"; fail=$((fail+1)); }
    [ "$ric" = "$iec" ] || { id="$id/exit异"; fail=$((fail+1)); }
    line="$line  self(build_exit=$sec stdout=$sd)  rsf-interp(exit=$ric stdout=$id)"
  fi
  if [ "$MODE" = "both" ]; then
    nd=$(diff -r -q "$ROOT/out_kotlin/$p" "$ROOT/out_self/$p" 2>/dev/null | wc -l)
    [ "$nd" = "0" ] || fail=$((fail+1))
    line="$line  类字节差异=$nd"
  fi
  echo "   $line"
done

echo
echo "=== 负例：两套编译器都必须判失败 ==="
for n in $NEG; do
  java -jar "$JAR" check "$P/$n.rsf" >/dev/null 2>&1; kec=$?
  line="$(printf '%-26s kotlin_check_exit=%s' "$n" "$kec")"
  [ "$kec" != "0" ] || fail=$((fail+1))
  if [ "$MODE" = "self" ] || [ "$MODE" = "both" ]; then
    build_self "$n" "$ROOT/out_self/$n"; sec=$?; [ "$sec" != "0" ] || fail=$((fail+1))
    run_rsf_interp "$n" > "$ROOT/$n.rsfinterp.txt" 2>&1; ric=$?; [ "$ric" != "0" ] || fail=$((fail+1))
    line="$line  self_build_exit=$sec  rsf_interp_exit=$ric"
  fi
  echo "   $line"
done

echo
echo "==== 失败项合计: $fail ===="
[ "$fail" = "0" ] || echo "（提示：第 1 刀落盘前 self/rsf-interp 两列的全红是基线红，不是本刀判据；见 README「基线」一节）"
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
