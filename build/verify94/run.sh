#!/bin/sh
# 判据台 94：标准库的「自动导入」（单文件场景）
#
# 核对：
#   1. 单文件里写了标准库符号（Vec），补全列表给出它，并附带插入 import 的编辑；
#   2. 没导入就编译时，报错里直接写出该加哪条 import；
#   3. 照着提示加上 import 之后，程序真的能编能跑（提示是对的，不是嘴上说说）。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
CP="$ROOT/build/dev/classes:$(cat "$ROOT/build/dev/rt.cp")"
JAVA=${JAVA:-java}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/need.rsf" "$HERE/solo.rsf" "$WORK/"
cd "$WORK"
fail() { echo "FAIL: $1" >&2; exit 1; }

echo "== 补全：Vec 附带 import std.Vec =="
"$JAVA" -cp "$CP" org.example.MainKt complete solo.rsf --line 2 --col 20 > comp.json 2>&1 || { cat comp.json; fail "complete 失败"; }
python3 - comp.json <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
hits = [i for i in doc["items"] if i.get("additionalTextEdits")]
assert hits, "没有带 additionalTextEdits 的补全项"
texts = [e["newText"] for i in hits for e in i["additionalTextEdits"]]
print("自动导入:", texts)
assert any("import std.Vec" in t for t in texts), texts
PY

echo "== 诊断：报错里写出该加的 import =="
"$JAVA" -cp "$CP" org.example.MainKt check need.rsf > need.log 2>&1 && fail "没导入居然编过了"
grep -q '标准库里有 import std.Numbers' need.log || { cat need.log; fail "诊断里没有标准库提示"; }

echo "== 照着提示加上 import：能编能跑 =="
cat > fixed.rsf <<'RSF'
import std.Numbers

fn main() -> void {
    println(intToString(Numbers.abs(-3)))
}
RSF
"$JAVA" -cp "$CP" org.example.MainKt run fixed.rsf > fixed.log 2>&1 || { cat fixed.log; fail "加上 import 后跑不起来"; }
grep -q '^3$' fixed.log || { cat fixed.log; fail "运行结果不对"; }

echo "verify94: 全过"
