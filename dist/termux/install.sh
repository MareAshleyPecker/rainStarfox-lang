#!/bin/sh
# 装到 Termux：pkg install openjdk-17 之后跑 ./install.sh
# 会装进 $PREFIX/opt/rainStarfox，并在 $PREFIX/bin/rsf 建软链。
set -e

DIR=$(cd "$(dirname "$0")" && pwd)
PREFIX=${PREFIX:-/usr/local}

if ! command -v java >/dev/null 2>&1; then
    echo "先装 JDK：pkg install openjdk-17" >&2
    exit 1
fi

mkdir -p "$PREFIX/opt/rainStarfox" "$PREFIX/bin"
cp "$DIR/rainStarfox-0.9.0.jar" "$PREFIX/opt/rainStarfox/"
cp "$DIR/rsf" "$PREFIX/opt/rainStarfox/"
chmod +x "$PREFIX/opt/rainStarfox/rsf"
ln -sf "$PREFIX/opt/rainStarfox/rsf" "$PREFIX/bin/rsf"

echo "装好了：$(command -v rsf)"
echo "试一下：rsf version"
