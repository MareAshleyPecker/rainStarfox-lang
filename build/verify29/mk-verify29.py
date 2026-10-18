import os, pathlib

ROOT = pathlib.Path("build/verify29")
(ROOT / "probes" / "neg").mkdir(parents=True, exist_ok=True)
(ROOT / "expected").mkdir(parents=True, exist_ok=True)
(ROOT / "out").mkdir(parents=True, exist_ok=True)

P = {
"p1_lambda_basic": """fun main() {
    var f : (int) -> int = fun(x : int) -> int { rtn x + 1 }
    println("" + f(41))
    var g : (int, int) -> int = fun(a : int, b : int) -> int { rtn a * b }
    println("" + g(6, 7))
}
""",
"p2_capture": """fun main() {
    imutvar base : int = 10
    var add : (int) -> int = fun(x : int) -> int { rtn x + base }
    println("" + add(5))
}
""",
"p3_field_closure": """class Adder {
    public var delta : int = 0

    public constructor(d : int) {
        this.delta = d
    }

    public fun make() -> (int) -> int {
        rtn fun(x : int) -> int { rtn x + this.delta }
    }
}

fun main() {
    var a : Adder = new Adder(3)
    var f : (int) -> int = a.make()
    println("" + f(4))
}
""",
"p4_higher_order": """fun apply(x : int, g : (int) -> int) -> int {
    rtn g(x)
}

fun twice(g : (int) -> int) -> (int) -> int {
    rtn fun(x : int) -> int { rtn g(g(x)) }
}

fun main() {
    var inc : (int) -> int = fun(x : int) -> int { rtn x + 1 }
    println("" + apply(10, inc))
    var quad : (int) -> int = twice(twice(inc))
    println("" + quad(0))
}
""",
"p5_void_fn": """fun each(xs : int[], f : (int) -> void) {
    var i : int = 0
    while (i < xs.length) {
        f(xs[i])
        i = i + 1
    }
}

fun main() {
    var xs : int[] = new int[3]
    xs[0] = 1
    xs[1] = 2
    xs[2] = 3
    each(xs, fun(n : int) -> void { println("n=" + n) })
}
""",
"p6_nullable_fn": """fun main() {
    var f : ?(int) -> int = null
    println("" + (f == null))
    f = fun(x : int) -> int { rtn x * 2 }
    println("" + f!!(21))
}
""",
}

EXPECTED = {
"p1_lambda_basic": "42\n42\n",
"p2_capture": "15\n",
"p3_field_closure": "7\n",
"p4_higher_order": "11\n4\n",
"p5_void_fn": "n=1\nn=2\nn=3\n",
"p6_nullable_fn": "true\n42\n",
}

NEG = {
"n1_capture_var": """fun main() {
    var count : int = 0
    var f : (int) -> int = fun(x : int) -> int { rtn x + count }
    println("" + f(1))
}
""",
"n2_no_expected_type": """fun main() {
    var f = fun(x) -> int { rtn x + 1 }
    println("" + f(1))
}
""",
"n3_is_function_type": """fun main() {
    var f : (int) -> int = fun(x : int) -> int { rtn x }
    println("" + (f is (int) -> int))
}
""",
"n4_call_nullable": """fun main() {
    var f : ?(int) -> int = null
    println("" + f(1))
}
""",
"n5_type_mismatch": """fun main() {
    var g : (int) -> string = fun(x : int) -> int { rtn x }
    println("" + g(1))
}
""",
}

for name, src in P.items():
    (ROOT / "probes" / (name + ".rsf")).write_text(src, encoding="utf-8", newline="\n")
    (ROOT / "expected" / (name + ".txt")).write_text(EXPECTED[name], encoding="utf-8", newline="\n")
for name, src in NEG.items():
    (ROOT / "probes" / "neg" / (name + ".rsf")).write_text(src, encoding="utf-8", newline="\n")

SCRIPT = r'''#!/usr/bin/env bash
# 阶段 29（函数类型 + lambda）探针台。
#
# 用法：
#   bash build/verify29/run-probes.sh check    # 正例 check 必须过；负例 check 必须拒
#   bash build/verify29/run-probes.sh msg      # 负例首条报错必须含约定的文案
#   bash build/verify29/run-probes.sh golden   # 解释器 stdout vs 冻结金标（expected/*.txt）
#   bash build/verify29/run-probes.sh run      # 正例两端对拍：解释器 stdout vs 字节码 stdout
#   bash build/verify29/run-probes.sh all
#
# 编码口径（沿用 2026-10-18 的修法）：金标 UTF-8 + LF；java 一律挂
#   -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8，捕获后再 `tr -d '\r'`。
set -u
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify29
P="$ROOT/probes"
MODE="${1:-all}"
fail=0
JOPT="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

POS="$(ls "$P" | grep '^p.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"
NEG="$(ls "$P/neg" | grep '^n.*\.rsf$' | sed 's/\.rsf$//' | sort -V | tr '\n' ' ')"

# 负例 → 约定文案（DESIGN-29 §5 的逐字口径）
msg_of() {
  case "$1" in
    n1_capture_var)       echo "lambda 只能值捕获" ;;
    n2_no_expected_type)  echo "lambda 的参数类型推不出来" ;;
    n3_is_function_type)  echo "的目标不能是函数类型" ;;
    n4_call_nullable)     echo "是可空的函数值，先断言非空" ;;
    n5_type_mismatch)     echo "类型不匹配" ;;
    *)                    echo "" ;;
  esac
}

if [ "$MODE" = "check" ] || [ "$MODE" = "all" ]; then
  echo "=== 正例：check 必须通过 ==="
  for p in $POS; do
    java $JOPT -jar "$JAR" check "$P/$p.rsf" > "$ROOT/$p.check.log" 2>&1
    c=$?
    if [ "$c" = 0 ]; then echo "   $p  check=0"; else
      echo "   $p  check=$c  ← 首条: $(head -1 "$ROOT/$p.check.log")"; fail=$((fail+1))
    fi
  done
  echo
  echo "=== 负例：check 必须被拒 ==="
  for n in $NEG; do
    java $JOPT -jar "$JAR" check "$P/neg/$n.rsf" > "$ROOT/$n.check.log" 2>&1
    c=$?
    if [ "$c" != 0 ]; then echo "   $n  check=$c"; else
      echo "   $n  check=0  ← 漏判！"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "msg" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 负例：报错文案必须与约定一致 ==="
  for n in $NEG; do
    want="$(msg_of "$n")"
    if [ -z "$want" ]; then echo "   $n  ← 脚本里没登记文案"; fail=$((fail+1)); continue; fi
    if grep -qF "$want" "$ROOT/$n.check.log"; then
      echo "   $n  同：$want"
    else
      echo "   $n  ← 文案不符，期望含：$want"; head -2 "$ROOT/$n.check.log"; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "golden" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 冻结金标（expected/，只比 stdout）==="
  for p in $POS; do
    java $JOPT -jar "$JAR" run "$P/$p.rsf" 2> "$ROOT/$p.golden.err" | tr -d '\r' > "$ROOT/$p.golden.txt"
    gec=${PIPESTATUS[0]}
    if [ "$gec" = 0 ] && diff -q "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" >/dev/null 2>&1; then
      echo "   $p  同"
    else
      echo "   $p  异(exit=$gec)"; diff "$ROOT/expected/$p.txt" "$ROOT/$p.golden.txt" | head -4; fail=$((fail+1))
    fi
  done
fi

if [ "$MODE" = "run" ] || [ "$MODE" = "all" ]; then
  echo
  echo "=== 正例：解释器 stdout vs 字节码 stdout ==="
  for p in $POS; do
    java $JOPT -jar "$JAR" run "$P/$p.rsf" 2>/dev/null | tr -d '\r' > "$ROOT/$p.interp.txt"
    iec=${PIPESTATUS[0]}
    rm -rf "$ROOT/out/$p"; mkdir -p "$ROOT/out/$p"
    java $JOPT -jar "$JAR" build "$P/$p.rsf" -o "$ROOT/out/$p" > "$ROOT/$p.build.log" 2>&1
    bec=$?
    if [ "$bec" = 0 ]; then
      java $JOPT -cp "$ROOT/out/$p" "$p" 2>&1 | tr -d '\r' > "$ROOT/$p.jvm.txt"
      jec=${PIPESTATUS[0]}
    else
      jec="-"
    fi
    if [ "$bec" = 0 ] && diff -q "$ROOT/$p.interp.txt" "$ROOT/$p.jvm.txt" >/dev/null 2>&1; then
      d="同"
    else
      d="异"; fail=$((fail+1))
    fi
    printf '   %-20s interp(exit=%s) build(exit=%s) jvm(exit=%s) stdout=%s\n' "$p" "$iec" "$bec" "$jec" "$d"
  done
fi

echo
echo "==== 失败项合计: $fail ===="
exit $([ "$fail" = "0" ] && echo 0 || echo 1)
'''

(ROOT / "run-probes.sh").write_text(SCRIPT, encoding="utf-8", newline="\n")
print("ok", len(P), "pos", len(NEG), "neg")
