#!/usr/bin/env bash
# 17e 独立验收（三）：?: 左边成员/隐式调用的硬报错探针
cd /d/java/rainStarfox || exit 2
JAR=build/libs/rainStarfox-0.9.0.jar
ROOT=build/verify17e
A=$ROOT/A
P=$ROOT/probe
rm -rf "$P"; mkdir -p "$P"

cat > "$P/M.rsf" <<'EOF'
class Box(v : int) {
    imutvar v : int = v
    public fn risky() -> !int {
        rtn v
    }
}
public fn main() {
    var b = Box(5)
    println("x=" + intToString(b.risky() ?: 0))
}
EOF

cat > "$P/I.rsf" <<'EOF'
class Box(v : int) {
    imutvar v : int = v
    public fn risky() -> !int {
        rtn v
    }
    public fn use() -> int {
        rtn risky() ?: 0
    }
}
public fn main() {
    var b = Box(5)
    println("x=" + intToString(b.use()))
}
EOF

for n in M I; do
  echo "== $n.rsf =="
  java -jar "$JAR" build "$P/$n.rsf" -o "$P/K$n" > "$P/K$n.log" 2>&1
  echo "   Kotlin 版: exit=$? classes=$(find "$P/K$n" -name '*.class' 2>/dev/null | wc -l)"
  head -2 "$P/K$n.log" | sed 's/^/      /'
  if [ -d "$P/K$n" ]; then
    java -cp "$P/K$n;$JAR" "$n" > "$P/K$n.out" 2>&1
    echo "   Kotlin 产物运行: exit=$? out=$(tr '\n' '|' < "$P/K$n.out")"
  fi
  java -Drsf.backend.file="$P/$n.rsf" -Drsf.backend.out="$P/R$n" -cp "$A;$JAR" BackendCli > "$P/R$n.log" 2>&1
  echo "   rsf 后端:  exit=$? classes=$(find "$P/R$n" -name '*.class' 2>/dev/null | wc -l)"
  head -2 "$P/R$n.log" | sed 's/^/      /'
done
echo "=== 完毕 ==="
