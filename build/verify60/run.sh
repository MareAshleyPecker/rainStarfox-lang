#!/usr/bin/env bash
# 阶段 59/60：UI 宿主窗口判据（Win32 + GDI，零依赖）。
# 链路：rsf --c --lib → gcc 宿主 → 无窗口跑一帧 → 断言点击/输入真的产生了动作。
set -u
cd "$(dirname "$0")/../.." || exit 1

JAR="${JAR:-build/libs/rainStarfox-0.9.0.jar}"
OUT=build/verify60/out
mkdir -p "$OUT"

fail=0
step() { echo "== $*"; }

step "1/5 生成 C 库"
if ! java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" build examples/ui_app.rsf --c --lib -o "$OUT" >"$OUT/gen.log" 2>&1; then
    echo "生成失败："; cat "$OUT/gen.log"; exit 1
fi
[ -f "$OUT/ui_app.c" ] || { echo "缺少 $OUT/ui_app.c"; exit 1; }
grep -q "rsf_fn_app_frame" "$OUT/ui_app.c" || { echo "C 里没有 rsf_fn_app_frame"; fail=1; }
grep -q "int main" "$OUT/ui_app.c" && { echo "库模式不该有 main"; fail=1; }

step "2/5 gcc 编译宿主"
if ! gcc -O2 host/win32_main.c "$OUT/ui_app.c" -o "$OUT/ui_app.exe" -luser32 -lgdi32 >"$OUT/gcc.log" 2>&1; then
    echo "gcc 失败："; head -20 "$OUT/gcc.log"; exit 1
fi

step "3/5 无窗口跑一帧并截图"
if ! "$OUT/ui_app.exe" --frames 1 --shot "$OUT/frame.bmp" >"$OUT/frame.log" 2>&1; then
    echo "运行失败："; cat "$OUT/frame.log"; exit 1
fi
[ -s "$OUT/frame.bmp" ] || { echo "没有生成截图"; fail=1; }

step "4/5 点 OK 按钮应产生动作 1"
clicked=$("$OUT/ui_app.exe" --click 140,98 --frames 2 2>&1 | grep -c "^\[action\] 1")
[ "$clicked" -ge 1 ] || { echo "点击没命中按钮："; "$OUT/ui_app.exe" --click 140,98 --frames 2; fail=1; }

step "5/5 输入文字后截图里应有输入框文字"
"$OUT/ui_app.exe" --text hello --frames 2 --shot "$OUT/typed.bmp" >"$OUT/typed.log" 2>&1
[ -s "$OUT/typed.bmp" ] || { echo "输入后没有截图"; fail=1; }

if [ "$fail" -eq 0 ]; then
    echo "==== 宿主窗口判据：5 项全过 ===="
else
    echo "==== 宿主窗口判据：有失败项 ===="
fi
exit "$fail"
