#!/usr/bin/env bash
# 17e 缺口 B/C 交付后的父代理验收一键跑（子代理交付后运行）：
#   1) verify.sh  —— 重建 A、J1（9 源）、J3 不动点、J2 残差、八示例锚点
#   2) bc-verify.sh —— 6 个 B/C 探针逐字节 + 运行输出
#   3) neg.sh —— 3 条装载器负例
set -u
cd /d/java/rainStarfox
echo "############ 1/3 verify.sh（A / J1 / J3 / J2 / J5） ############"
bash build/verify17e/verify.sh
echo
echo "############ 2/3 bc-verify.sh（B/C 探针） ############"
A=build/verify17e/A bash build/verify17e/probeBC/bc-verify.sh
echo
echo "############ 3/3 neg.sh（装载器负例） ############"
A=build/verify17e/A bash build/verify17e/probeBC/neg.sh
