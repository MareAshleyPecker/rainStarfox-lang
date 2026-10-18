# rainStarfox on Termux（aarch64）

编译器是 Kotlin/JVM 写的，Termux 上跑 JVM 版即可；**它编出来的程序可以是真的 aarch64 原生可执行文件**。

## 装

```sh
pkg install openjdk-17
tar xzf rainStarfox-termux-arm64-0.9.0.tar.gz
cd rainStarfox-termux-arm64-0.9.0
./install.sh          # 装到 $PREFIX/opt/rainStarfox，并在 $PREFIX/bin/rsf 建软链
rsf version
```

没有 `tar` 就先 `pkg install tar`。

## 用

```sh
rsf run hello.rsf                       # 解释执行
rsf build hello.rsf -o out              # 出 .class（V17，Termux 的 java 17 能跑）
rsf build hello.rsf --c -o out          # 出单文件 C99
clang out/hello.c -o hello              # 真·aarch64 原生程序
./hello
```

`pkg install clang` 之后 `--c` 这条路就是「rsf 源码 → C → ARM64 可执行文件」，不经过 JVM。

`rsf build` 产出的 class 是 Java 17 版本（61），所以 Termux 的 openjdk-17 与 21 都能直接跑。

## 注意

- 需要能跑 `java -jar` 的环境：Termux 的 `openjdk-17` 或 `openjdk-21` 都行；JRE 17 就够。
- `--c` 产出的 C 只用 `stdint.h / stdbool.h / stdio.h / stdlib.h / string.h`，clang 直接编。
- 编译器本体不是原生 aarch64 可执行文件（它是 JVM 程序）；要原生编译器得等自举链（`rsfSrc/bootstrap/`）能被 C 后端完整翻译。
