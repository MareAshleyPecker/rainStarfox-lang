#!/usr/bin/env bash
# 父代理验收用：BC 探针在「Kotlin 版」与「rsf 版后端（A）」下逐字节 + 运行输出对比。
# 依赖：先跑 kotlin-baseline.sh 生成 K/；A 由当前源码编出（java -jar $JAR build rsfSrc/bootstrap/BackendCli.rsf -o $A）。
set -u
cd /d/java/rainStarfox
JAR=build/libs/rainStarfox-0.9.0.jar
A=${A:-build/verify17e/A}
OUT=build/verify17e/probeBC
R=$OUT/R
rm -rf "$R"; mkdir -p "$R"
fail=0

check () {  # $1 源文件, $2 入口类名, $3 名字
  local src="$1" maincls="$2" name="$3"
  local kd="$OUT/K/$name" rd="$R/$name"
  mkdir -p "$rd"
  echo "### $name  ($src)"
  java -Drsf.backend.file="$src" -Drsf.backend.out="$rd" -cp "$A;$JAR" BackendCli > "$rd.build.log" 2>&1
  local bc=$?
  if [ $bc -ne 0 ]; then
    echo "  后端 exit=$bc  ✗ 编译失败"
    sed -n '1,3p' "$rd.build.log" | sed 's/^/    /'
    fail=$((fail+1)); echo; return
  fi
  local nk nr
  nk=$(find "$kd" -name '*.class' | wc -l | tr -d ' ')
  nr=$(find "$rd" -name '*.class' | wc -l | tr -d ' ')
  echo "  后端 exit=0   类数: Kotlin=$nk rsf=$nr"
  local d
  d=$(diff -r "$kd" "$rd" 2>&1 | head -6)
  if [ -z "$d" ]; then echo "  字节级: 全同 ✓"; else
    echo "  字节级: 有差异 ✗"; echo "$d" | sed 's/^/    /'; fail=$((fail+1))
  fi
  java -cp "$kd;$JAR" "$maincls" > "$kd.run.out" 2> "$kd.run.err"; local ke=$?
  java -cp "$rd;$JAR" "$maincls" > "$rd.run.out" 2> "$rd.run.err"; local re=$?
  echo "  运行: Kotlin exit=$ke  rsf exit=$re"
  if diff -q "$kd.run.out" "$rd.run.out" >/dev/null && diff -q "$kd.run.err" "$rd.run.err" >/dev/null && [ "$ke" -eq "$re" ]; then
    echo "  运行: 一致 ✓  stdout=$(tr '\n' '|' < "$kd.run.out")"
    if [ -s "$kd.run.err" ]; then echo "    stderr: $(tr '\n' '|' < "$kd.run.err")"; fi
  else
    echo "  运行: 不一致 ✗"
    echo "    Kotlin stdout: $(tr '\n' '|' < "$kd.run.out")   stderr: $(tr '\n' '|' < "$kd.run.err")"
    echo "    rsf    stdout: $(tr '\n' '|' < "$rd.run.out")   stderr: $(tr '\n' '|' < "$rd.run.err")"
    fail=$((fail+1))
  fi
  echo
}

check build/verify17e/probeBC/S.rsf S S
check build/verify17e/probeBC/ARG.rsf ARG ARG
check build/verify17e/probeBC/ModMain.rsf ModMain ModMain
check build/verify17e/probeBC/pkgapp/a/b/PkgMain.rsf a.b.PkgMain PkgMain
check build/verify17e/probe/M.rsf M M
check build/verify17e/probe/I.rsf I I

echo "==== 失败项合计: $fail ===="
