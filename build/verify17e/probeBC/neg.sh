#!/usr/bin/env bash
# 父代理验收用：装载器负例（pkg 目录不匹配 / module 目录不存在 / module 空目录）两端对照。
# 只比「两边都拒绝」与首条消息文本，不做字节对拍。
set -u
cd /d/java/rainStarfox
JAR=build/libs/rainStarfox-0.9.0.jar
A=${A:-build/verify17e/A}
OUT=build/verify17e/probeBC/neg
for f in WrongPkg NoDir EmptyMod; do
  src="$OUT/$f.rsf"
  rm -rf "$OUT/k_$f" "$OUT/r_$f"
  echo "### $f"
  java -jar "$JAR" build "$src" -o "$OUT/k_$f" > "$OUT/k_$f.log" 2>&1
  echo "  Kotlin exit=$?  classes=$(find "$OUT/k_$f" -name '*.class' 2>/dev/null | wc -l)"
  java -Drsf.backend.file="$src" -Drsf.backend.out="$OUT/r_$f" -cp "$A;$JAR" BackendCli > "$OUT/r_$f.log" 2>&1
  echo "  rsf    exit=$?  classes=$(find "$OUT/r_$f" -name '*.class' 2>/dev/null | wc -l)"
  echo
done
echo "(消息原文见 $OUT/k_*.log 与 $OUT/r_*.log)"
