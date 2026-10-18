#!/usr/bin/env bash
# 自举同步各刀的父代理独立复验（一条命令跑完，输出压成摘要）。
# 用法：bash build/verify21/knife-verify.sh <证据目录名，例如 bootparser-verify>
#
# ★判决口径（2026-10-15 修）：**不看子脚本的退出码**——accept-after.sh / verify2.sh
# 在失败时仍 exit=0，只把失败写进正文（「失败项合计: N」「**不一致**」）。
# 所以逐段抓正文判据，任何一段不过就整体 FAIL 并 exit 1。
#
# 环境变量：
#   KNIFE_ALLOW_RED_ACCEPT=1  第 2 段（accept-after）允许红，仅告警不计入失败
#                             —— 已知窗口：第 1 刀落地后、第 2 刀落地前，
#                             `SUPERS` 节点已产出而旧 Backend.rsf 不认，J1 必红。
#   KNIFE_SKIP_RUN=1          不重跑任何东西，只按 $OUT/ 里已有的日志重新判决（复盘用）。
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT="build/verify21/${1:-knife-verify}"
mkdir -p "$OUT"
FAILED=0

# 日志可能是 UTF-8 也可能是 GBK（Windows 工具链混着来）。判据：**按 UTF-8 直接 grep 得到
# 关键词就用原文件**（这是实测常态：accept.log/verify2.log 都能被裸 grep 命中），
# 一个都命中不了才当 GBK 转一次。
# （别用 `iconv -f UTF-8 -t UTF-8` 探测合法性：日志里常混几个坏字节，会把好文件误判成 GBK。）
decode() {
  if grep -aqE "失败项合计|J1 类总数|\*\*不一致\*\*|BUILD (SUCCESSFUL|FAILED)" "$1"; then
    cat "$1"
  else
    iconv -c -f GBK -t UTF-8 "$1"
  fi
}

if [ "${KNIFE_SKIP_RUN:-0}" = 1 ]; then
  echo "（KNIFE_SKIP_RUN=1：只复盘 $OUT/ 里的既有日志）"
else
  echo "===== 1/3 gradle cleanTest test jar ====="
  ./gradlew.bat cleanTest test jar --console=plain > "$OUT/gradle.log" 2>&1
  echo "===== 2/3 accept-after.sh（J1 类总数>=100 / diff 0 / 负例）====="
  bash build/verify17e/accept-after.sh > "$OUT/accept.log" 2>&1
  echo "===== 3/3 verify2.sh（自举解释器跑八示例）====="
  bash build/verify17e/verify2.sh > "$OUT/verify2.log" 2>&1
fi

echo "----- 1/3 gradle -----"
if [ -f "$OUT/gradle.log" ]; then
  decode "$OUT/gradle.log" | grep -aoE "[0-9]+ tests completed[^,]*|BUILD (SUCCESSFUL|FAILED)" | tail -3
  G_BUILD_FAILED=$(decode "$OUT/gradle.log" | grep -acE "^BUILD FAILED|^> Task .* FAILED")
  G_TASK_FAILED=$(decode "$OUT/gradle.log" | grep -aoE "[0-9]+ (tests? )?failed" | tail -1)
  echo "BUILD FAILED 行=$G_BUILD_FAILED  测试失败=${G_TASK_FAILED:-无}"
  if [ "$G_BUILD_FAILED" = 0 ] && [ -z "$G_TASK_FAILED" ] && decode "$OUT/gradle.log" | grep -aqE "BUILD SUCCESSFUL"; then
    echo "  ✓ 第 1 段通过"
  else
    echo "  ✗ 第 1 段失败"; FAILED=$((FAILED+1))
  fi
else
  echo "  ✗ 缺 gradle.log"; FAILED=$((FAILED+1))
fi

echo "----- 2/3 accept-after -----"
if [ -f "$OUT/accept.log" ]; then
  A_TXT=$(decode "$OUT/accept.log")
  A_SUM=$(echo "$A_TXT" | grep -aE "失败项合计" | tail -1 | grep -oE "[0-9]+" | tail -1)
  A_J1=$(echo "$A_TXT" | grep -aE "J1 类总数" | tail -1)
  A_J1N=$(echo "${A_J1:-0}" | grep -oE "类总数=[0-9]+" | head -1 | grep -oE "[0-9]+")
  # 注意别用 `grep -oE "[0-9]+" | head -1`：行首的 "J1" 里那个 1 会被先抓出来（曾因此误判）。
  echo "${A_J1:-J1 类总数缺失}   失败项合计=${A_SUM:-?}"
  if [ "${A_SUM:-1}" = 0 ] && [ "${A_J1N:-0}" -ge 100 ]; then
    echo "  ✓ 第 2 段通过（失败项 0，J1 类数 $A_J1N）"
  elif [ "${KNIFE_ALLOW_RED_ACCEPT:-0}" = 1 ]; then
    echo "  ⚠ 第 2 段红，已按 KNIFE_ALLOW_RED_ACCEPT=1 放行：失败项合计=${A_SUM:-?}，J1 类数=${A_J1N:-0}"
    echo "    已知窗口：第 1 刀已产出 SUPERS 节点、第 2 刀尚未让 Backend.rsf 认它"
  else
    echo "  ✗ 第 2 段失败"; FAILED=$((FAILED+1))
  fi
else
  echo "  ✗ 缺 accept.log"; FAILED=$((FAILED+1))
fi

echo "----- 3/3 verify2 -----"
if [ -f "$OUT/verify2.log" ]; then
  V_TXT=$(decode "$OUT/verify2.log")
  V_MIS=$(echo "$V_TXT" | grep -acE "\*\*不一致\*\*")
  V_SUM=$(echo "$V_TXT" | grep -aE "失败项合计" | tail -1 | grep -oE "[0-9]+" | tail -1)
  echo "$V_TXT" | grep -aE "一致|失败项合计" | tail -10
  echo "不一致行=$V_MIS  失败项合计=${V_SUM:-0}"
  if [ "$V_MIS" = 0 ] && [ "${V_SUM:-0}" = 0 ]; then
    echo "  ✓ 第 3 段通过"
  else
    echo "  ✗ 第 3 段失败"; FAILED=$((FAILED+1))
  fi
else
  echo "  ✗ 缺 verify2.log"; FAILED=$((FAILED+1))
fi

echo "===== 判决 ====="
if [ "$FAILED" = 0 ]; then
  echo "OK：三条全绿（日志在 $OUT/）"
else
  echo "FAIL：$FAILED 段不过（日志在 $OUT/）"
fi
exit "$FAILED"
