#!/usr/bin/env bash
# 阶段 20 的**运行时**独立验收（父代理探针台，独立于子代理写的单元测试）。
#
# 每个探针跑两遍：一遍解释器（`rsf run`），一遍编译产物（`rsf build` 后 `java` 起同一个
# main），两端 stdout 与退出码必须逐字节相同；**而且**必须等于这里写死的期望输出——
# 光比「两端一致」不够：两边一起错也是「一致」。
#
# 另一半是接口 `.class` 的**形状**：javap 查 ACC_INTERFACE/ACC_ABSTRACT 组合、没有
# 构造器、抽象方法没有 Code、类里 interfaces 数组真的写进去了、调用点真的发了
# invokeinterface。形状错了往往能过 check、跑起来才炸，所以这里用字节码原文判。
set -u
cd /d/java/rainStarfox || exit 1
JAR=build/libs/rainStarfox-0.9.0.jar
D=build/verify20
OUT=$D/out
# Java 在这台机器上的默认字符集是 GBK，钉住 UTF-8，否则中文诊断/输出没法比
JAVA="java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
rm -rf "$OUT"; mkdir -p "$OUT"

pass=0; fail=0

# 这台机器上 java 的 println 走 CRLF，而 `$( )` 只会剥掉行尾的换行——
# 单行输出正好被连带剥掉，多行输出中间那些 \r 会留下来，于是「看起来一样却不等」。
# 比较前统一把 \r 去掉，免得把harness自己的毛病记到语言头上。
norm() { printf '%s' "$1" | tr -d '\r'; }

# run_one <名> <源文件> <期望 stdout（printf 转义）>
run_one() {
  local name=$1 src=$2 want=$3
  local i_out i_rc dir cls b_log b_out b_rc expected
  cls="P_$(basename "$src" .rsf)"
  dir="$OUT/$(basename "$src" .rsf)"

  i_out=$($JAVA -jar "$JAR" run "$src" 2>&1); i_rc=$?
  if [ $i_rc -ne 0 ]; then
    printf '  %-24s FAIL  解释器 exit=%s\n%s\n' "$name" "$i_rc" "$i_out"; fail=$((fail+1)); return
  fi

  b_log=$($JAVA -jar "$JAR" build "$src" -o "$dir" -n "$cls" 2>&1); b_rc=$?
  if [ $b_rc -ne 0 ]; then
    printf '  %-24s FAIL  build exit=%s\n%s\n' "$name" "$b_rc" "$b_log"; fail=$((fail+1)); return
  fi

  b_out=$(cd "$dir" && $JAVA -cp . "$cls" 2>&1); b_rc=$?
  if [ $b_rc -ne 0 ]; then
    printf '  %-24s FAIL  编译产物 exit=%s\n%s\n' "$name" "$b_rc" "$b_out"; fail=$((fail+1)); return
  fi

  if [ "$(norm "$i_out")" != "$(norm "$b_out")" ]; then
    printf '  %-24s FAIL  两端不一致\n    解释器: %s\n    字节码: %s\n' \
      "$name" "$(printf '%s' "$i_out" | tr '\n' '|')" "$(printf '%s' "$b_out" | tr '\n' '|')"
    fail=$((fail+1)); return
  fi

  expected=$(printf "$want")
  if [ "$(norm "$i_out")" != "$(norm "$expected")" ]; then
    printf '  %-24s FAIL  输出与期望不符\n    实际: %s\n    期望: %s\n' \
      "$name" "$(printf '%s' "$i_out" | tr '\n' '|')" "$(printf '%s' "$expected" | tr '\n' '|')"
    fail=$((fail+1)); return
  fi

  printf '  %-24s ok    两端一致  %s\n' "$name" "$(printf '%s' "$i_out" | tr '\n' '|')"
  pass=$((pass+1))
}

# javap_has <类文件> <正则> <说明>
javap_has() {
  local f=$1 pat=$2 what=$3
  if [ ! -f "$f" ]; then
    printf '  %-24s FAIL  类文件不存在: %s\n' "$what" "$f"; fail=$((fail+1)); return
  fi
  if javap -v -p "$f" 2>&1 | grep -Eq "$pat"; then
    printf '  %-24s ok    %s\n' "$what" "$pat"; pass=$((pass+1))
  else
    printf '  %-24s FAIL  没匹配上 %s\n--- %s ---\n%s\n' "$what" "$pat" "$f" "$(javap -v -p "$f" 2>&1 | head -40)"
    fail=$((fail+1))
  fi
}

# javap_lacks <类文件> <正则> <说明>
javap_lacks() {
  local f=$1 pat=$2 what=$3
  if [ ! -f "$f" ]; then
    printf '  %-24s FAIL  类文件不存在: %s\n' "$what" "$f"; fail=$((fail+1)); return
  fi
  if javap -v -p "$f" 2>&1 | grep -Eq "$pat"; then
    printf '  %-24s FAIL  不该出现却出现了 %s\n--- %s ---\n%s\n' "$what" "$pat" "$f" "$(javap -v -p "$f" 2>&1 | head -40)"
    fail=$((fail+1))
  else
    printf '  %-24s ok    没有 %s\n' "$what" "$pat"; pass=$((pass+1))
  fi
}

echo "==== 阶段 20 运行时独立验收（父代理探针） ===="
echo "-- 两端（解释器 / 字节码）stdout 与退出码逐字节一致，且等于期望 --"
run_one "接口+实现类"          "$D/ok_impl.rsf"     'woof bob\n'
run_one "接口默认方法"         "$D/ok_default.rsf"  'rex!\n'
run_one "接口继承接口"         "$D/ok_extends.rsf"  '7/rex\n'
run_one "接口当参数/返回"      "$D/ok_types.rsf"    'woof\nwoof\n'
run_one "可空接口 + 兜底"      "$D/ok_nullable.rsf" 'woof\nwoof\n'
run_one "实现 JDK Runnable"    "$D/ok_runnable.rsf" 'running\n'
run_one "实现 JDK 泛型接口"    "$D/ok_comparator.rsf" '2\n-2\n'

echo "-- 跨边界：把 rsf 对象当实参交给 JDK 代码去回调（M4 的重点） --"
# 字节码端要过就得发桥方法（擦除后的 compare(Object,Object)）；
# 解释器端要过就得给 RsfObject 套 JDK 接口代理。两条都还没做时这里会红——
# 红不是回归，是「M4 还没做完」的计时器。
run_one "JDK 回调 rsf 比较器"   "$D/ok_sort.rsf"      '3,5\n'
run_one "泛型 rsf 接口"        "$D/ok_generic_box.rsf" '7\n'

echo "-- 接口 .class 的形状（javap 原文判定） --"
# 纯抽象接口（ok_impl 的 Greeter 只有一条抽象方法）：整个类文件不该有 Code 属性
javap_has   "$OUT/ok_impl/Greeter.class" 'flags: \(0x0601\) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT' "接口标志位组合"
javap_lacks "$OUT/ok_impl/Greeter.class" 'Code:' "纯抽象接口没有 Code 属性（无构造器 / 无方法体）"
javap_has   "$OUT/ok_impl/Greeter.class" 'flags: \(0x0401\) ACC_PUBLIC, ACC_ABSTRACT' "抽象方法带 ACC_ABSTRACT"
# 类照旧 ACC_SUPER，并且 interfaces 数组里真的写上了 rsf 接口
javap_has   "$OUT/ok_impl/Dog.class"     'flags: \(0x0021\) ACC_PUBLIC, ACC_SUPER' "类仍是 ACC_PUBLIC, ACC_SUPER"
javap_has   "$OUT/ok_impl/Dog.class"     'interfaces: 1' "类的 interfaces 数组有 1 项"
javap_has   "$OUT/ok_impl/Dog.class"     '// Greeter' "interfaces 数组指向 Greeter"
# 调用点：接口类型的接收者必须发 invokeinterface（owner 是接口）
javap_has   "$OUT/ok_impl/P_ok_impl.class" 'invokeinterface.*// InterfaceMethod Greeter\.greet' "调用点发 invokeinterface"
# 默认方法：接口里带体的方法要有 Code，抽象方法没有
javap_has   "$OUT/ok_default/Greeter.class" 'Code:' "默认方法带 Code"
javap_has   "$OUT/ok_default/Greeter.class" 'flags: \(0x0601\) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT' "带默认方法的接口标志位组合"
javap_lacks "$OUT/ok_default/Greeter.class" 'descriptor: \(\)V' "接口里没有构造器（()V）"
# 接口继承接口：子接口的 interfaces 数组
javap_has   "$OUT/ok_extends/Named.class" 'interfaces: 1' "子接口的 interfaces 数组有 1 项"
javap_has   "$OUT/ok_extends/Named.class" '// Base' "子接口的父接口是 Base"
# JDK 接口互操作：类的 interfaces 数组里是全名
javap_has   "$OUT/ok_runnable/Job.class" '// java/lang/Runnable' "实现 JDK 接口写的是全名"

echo "==== 合计 通过 $pass / 失败 $fail ===="
[ $fail -eq 0 ] || exit 1
