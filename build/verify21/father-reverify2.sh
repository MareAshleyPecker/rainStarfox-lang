#!/usr/bin/env bash
# 父代理对第 1 刀**最终字节**的独立复验（不依赖子代理的任何产物，自己建 ParserCli、自己写负例）。
# 用法：bash build/verify21/father-reverify2.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=build/verify21/parsereyeball2
JAR=build/libs/rainStarfox-0.9.0.jar
PY="java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp $OUT;$JAR ParserCli"
BAD=0

echo "=== 1) 用当前 jar 编 rsf 版语法器 ==="
rm -rf "$OUT"; mkdir -p "$OUT/neg"
java -Dstdout.encoding=UTF-8 -jar "$JAR" build rsfSrc/bootstrap/ParserCli.rsf -o "$OUT" > "$OUT/build.log" 2>&1
echo "编译 exit=$? 类数=$(find "$OUT" -maxdepth 1 -name '*.class' | wc -l)"

run_one() {  # $1 = 文件（相对路径）
  java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Drsf.ast.file="$1" -cp "$OUT;$JAR" ParserCli 2>&1 | head -1
}

echo "=== 2) 正例：全语料必须可解析 ==="
OKL=0; BADL=0
for f in $(find rsfSrc/lang -name '*.rsf' | sort); do
  h=$(run_one "$f"); case "$h" in PROGRAM*) OKL=$((OKL+1));; *) BADL=$((BADL+1)); echo "  FAIL $f :: $(echo "$h" | cut -c1-80)";; esac
done
OKB=0; BADB=0
for f in $(find rsfSrc/bootstrap -name '*.rsf' | sort); do
  h=$(run_one "$f"); case "$h" in PROGRAM*) OKB=$((OKB+1));; *) BADB=$((BADB+1)); echo "  FAIL $f :: $(echo "$h" | cut -c1-80)";; esac
done
echo "lang $OKL/15 通过（失败 $BADL）；bootstrap $OKB/9 通过（失败 $BADB）"
[ "$BADL$BADB" = "00" ] || BAD=$((BAD+1))

echo "=== 3) 负例：十条必须判失败 ==="
mkdir -p "$OUT/neg"
write_neg() { printf '%s\n' "$2" > "$OUT/neg/$1.rsf"; }
write_neg n01_super_brace          'class C : { }'
write_neg n02_super_question       'class C : ?Greeter { }'
write_neg n03_super_builtin        'class C : int { }'
write_neg n04_super_array          'class C : int[] { }'
write_neg n05_iface_super_brace    'interface I : { }'
write_neg n06_iface_field          'interface I { var x : int = 1 }'
write_neg n07_iface_ctor           'interface I { constructor() { } }'
write_neg n08_iface_static         'interface I { static fun f() -> void { rtn } }'
write_neg n09_iface_private        'interface I { private fun f() -> void { rtn } }'
write_neg n10_top_override         'override fun f() -> void { rtn }'
NOK=0
for f in $(find "$OUT/neg" -name '*.rsf' | sort); do
  h=$(run_one "$f")
  case "$h" in *解析失败*) NOK=$((NOK+1));; *) echo "  ✗ 未判失败 $(basename "$f") :: $(echo "$h" | cut -c1-80)";; esac
done
echo "负例 $NOK/10 判失败"
[ "$NOK" = 10 ] || BAD=$((BAD+1))

echo "=== 4) 正例 dump 抽查：接口 + 父类型 + 覆盖 ==="
cat > "$OUT/pos_iface.rsf" <<'EOF'
pub interface Greeter {
    fun greet(name : string) -> string
    fun shout() -> string { rtn "hi" }
}
interface Empty { }
interface Box&<T> : Greeter { }
pub class Impl(x0 : int) : Greeter, Box&<int> {
    pub override fun greet(name : string) -> string { rtn name }
}
EOF
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Drsf.ast.file="$OUT/pos_iface.rsf" -cp "$OUT;$JAR" ParserCli > "$OUT/pos_iface.dump" 2>&1
grep -nE 'INTERFACES|INTERFACE |SUPERS|SUPER |override=|BLOCK|control=' "$OUT/pos_iface.dump" | head -40
echo "（完整 dump 见 $OUT/pos_iface.dump）"

echo "=== 判决 ==="
if [ "$BAD" = 0 ]; then echo "OK：正例全通过、负例全拦、dump 抽查已打印"; else echo "FAIL：$BAD 组不过"; fi
exit "$BAD"
