#!/usr/bin/env bash
# 自举后端对语料的覆盖清扫：Kotlin 后端 vs 自举后端，逐文件比类数/差异/首条报错
# 用法：bash build/verify21/bootparity/backend-sweep.sh
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
A=build/verify17e/A
OUT=build/verify21/bootparity/sweep
rm -rf "$OUT"; mkdir -p "$OUT"

run_one() {
  local src="$1" tag="$2"
  local kd="$OUT/$tag/K" rd="$OUT/$tag/R"
  mkdir -p "$kd" "$rd"
  java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build "$src" -o "$kd" > "$OUT/$tag/K.log" 2>&1
  local ek=$?
  java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
       -Drsf.backend.file="$src" -Drsf.backend.out="$rd" \
       -cp "$A;$JAR" BackendCli > "$OUT/$tag/R.log" 2>&1
  local er=$?
  local kc rc dif
  kc=$(find "$kd" -name '*.class' 2>/dev/null | wc -l)
  rc=$(find "$rd" -name '*.class' 2>/dev/null | wc -l)
  dif=$(diff -r -q "$kd" "$rd" 2>/dev/null | wc -l)
  local kerr rerr
  kerr=$(grep -m1 -E 'error|错误|不支持|失败' "$OUT/$tag/K.log" | head -c 120)
  rerr=$(grep -m1 -E 'error|不支持|失败|解析' "$OUT/$tag/R.log" | head -c 120)
  printf '%-28s K(exit=%s,cls=%s) R(exit=%s,cls=%s) diff=%s\n' "$tag" "$ek" "$kc" "$er" "$rc" "$dif"
  [ -n "$rerr" ] && printf '%34s R: %s\n' '' "$rerr"
}

for f in main class generics errors stdlib arrays files enums; do
  run_one "rsfSrc/lang/$f.rsf" "$f"
done
for f in usejava rsflib; do
  [ -f "rsfSrc/lang/interop/$f.rsf" ] && run_one "rsfSrc/lang/interop/$f.rsf" "interop_$f"
done
for f in main; do
  [ -f "rsfSrc/lang/multifile/$f.rsf" ] && run_one "rsfSrc/lang/multifile/$f.rsf" "multifile_$f"
done
echo
echo "日志目录：$OUT"
