#!/bin/sh
# 一条命令跑完「编编译器 → 出原生解释器 → 九示例对拍」。
# 出错时只打关键行，成功时只有两行。
#
# 用法： sh tools/dev/quick.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
sh "$HERE/kb.sh" -q
sh "$HERE/native.sh" -q
sh "$HERE/parity.sh"
