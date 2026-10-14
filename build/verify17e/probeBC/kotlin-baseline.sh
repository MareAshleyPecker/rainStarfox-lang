#!/usr/bin/env bash
# 父代理验收用：用 Kotlin 版编译器给 BC 探针建基线（字节码 + 运行输出）。
set -u
cd /d/java/rainStarfox
JAR=build/libs/rainStarfox-0.9.0.jar
OUT=build/verify17e/probeBC/K
rm -rf "$OUT"; mkdir -p "$OUT"

build_and_run () {   # $1 源文件, $2 入口类名, $3 输出子目录名
  local src="$1" maincls="$2" name="$3"
  local dir="$OUT/$name"
  mkdir -p "$dir"
  echo "### $src"
  java -jar "$JAR" build "$src" -o "$dir" > "$dir.build.log" 2>&1
  echo "build exit=$?"
  sed -n '1,3p' "$dir.build.log"
  echo "classes: $(find "$dir" -name '*.class' | wc -l)"
  find "$dir" -name '*.class' | sed "s|$dir/||" | sort
  java -cp "$dir;$JAR" "$maincls" > "$dir.run.out" 2> "$dir.run.err"
  echo "run exit=$?"
  echo "--- stdout"; cat "$dir.run.out"
  echo "--- stderr"; cat "$dir.run.err"
  echo
}

build_and_run build/verify17e/probeBC/S.rsf S S
build_and_run build/verify17e/probeBC/ARG.rsf ARG ARG
build_and_run build/verify17e/probeBC/ModMain.rsf ModMain ModMain
build_and_run build/verify17e/probeBC/pkgapp/a/b/PkgMain.rsf a.b.PkgMain PkgMain
build_and_run build/verify17e/probe/M.rsf M M
build_and_run build/verify17e/probe/I.rsf I I
