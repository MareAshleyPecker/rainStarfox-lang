# rainStarfox (rsf)

> **本语言仅做玩具**：真要用请换别的语言。

一个自制玩具语言：**Kotlin 写的编译器，跑在 JVM 上**，同一份前端挂三个出口——解释器、JVM 字节码、C99（再交给 gcc/clang 出原生程序）。

语法是 Kotlin / Java / C 的混合体：类、接口、枚举、泛型、可空类型、错误返回、`lam`、标签 `goto`、指针 `T*`。

## 快速开始

```bat
:: 解释执行
gradlew run --args="run rsfSrc/lang/main.rsf"

:: 编成 .class（默认落 out/）
gradlew run --args="build rsfSrc/lang/main.rsf"
java -cp out main

:: 胖 jar（自带 ASM，直接 java -jar）
gradlew jar
java -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/lang/main.rsf

:: 自带精简 JVM 的原生启动器（Windows 下是 rainStarfox.exe）
gradlew exe
build\native\rainStarfox\rainStarfox.exe run rsfSrc\lang\main.rsf
```

`exe` 用的是 JDK 自带的 `jpackage`，不需要 GraalVM。产物 `build/native/rainStarfox/` 要**整个目录**拷给别人（`runtime/` 约 44 MB，对方不用装 Java）。

## 尝一口

```rsf
class Counter(start : int) {
    var n : int = start

    fn bump(by : int) -> void { n = n + by }
}

fn sum(xs : int[]) -> int {
    var total : int = 0
    for (x in xs) { total = total + x }
    rtn total
}

fn main() -> void {
    var c : Counter = new Counter(0)
    c.bump(3)
    println(c.n)                      // 3

    var xs : int[] = new int[3]
    xs[0] = 1
    xs[1] = 2
    xs[2] = 3
    println(sum(xs))                  // 6

    var n : int = 7
    var p : int* = &n                 // 指针只在解释器与 C 后端可用
    *p = 9
    println(n)                        // 9
}
```

语句之间用换行分隔（`;` 可写可不写），`rtn` 带值要写在同一行。完整语法表见 [SYNTAX.md](SYNTAX.md)。

## 命令行

```
rsf run   <file.rsf>                        解释执行
rsf build <file.rsf> [-o <dir>] [-n 类名]    编成 .class（默认 out/）
rsf build <file.rsf> --dump                 顺便打印 ASM 文本
rsf build <file.rsf> --c [-o <dir>]          生成单文件 C99
rsf check <file.rsf>                        只检查，列函数表
rsf lex   <file.rsf>                        打印 token 流
rsf complete <file.rsf> --line L --col C    补全候选（给编辑器用）
rsf lsp                                     stdio 上的语言服务器
rsf repl                                    交互式解释器
rsf help | version
```

三条后端有一条硬约束：**解释器与字节码后端的 stdout 必须逐字节一致**（`build/verify36/` 与 `build/verify32/` 两套台子守着）。

## C 后端（native 线）

`rsf build x.rsf --c -o out` 把整份程序摊成**一个 C99 文件**，自带运行时（字符串拼接、`print`/`println`、`intToString`），不依赖任何 rsf 库：

```bat
rsf build hello.rsf --c -o out
gcc out\hello.c -o out\hello.exe
```

- 已支持：int / long / bool / string、一维数组、类（字段 / 实例方法 / `stc` 静态方法 / 主次构造）、枚举与 `switch`、指针（`T*` / `&x` / `*p` / `*p = v`）、顶层函数、`if` / `while` / `for` / 递归。
- 还没接：接口、类继承、泛型、多维数组、浮点、错误返回——遇到会报 `error[native]`。
- 判据：解释器 stdout vs 原生 exe stdout 逐字节相同（`bash build/verify52/run-probes.sh all`，9 份语料）。

### Termux / aarch64

Termux 里 `pkg install openjdk-17`，解压 `dist/rainStarfox-termux-arm64-0.9.0.tar.gz`（用 `./gradlew.bat jar` 之后按 `dist/termux/README.md` 打包）再 `./install.sh`，就有 `rsf` 命令。jar 编到 Java 17 字节码（major 61），openjdk-17 / 21 都能跑。

要**原生 ARM64 可执行文件**就走 `--c` 加 `clang`（`pkg install clang`）。编译器本体还不是原生 aarch64——那要等自举链能被 C 后端完整翻译。

## 编辑器支持

`rsf lsp` 是 stdio 上的语言服务器：诊断、补全、跳转定义、悬停文档（RDoc，用 `///` 写）、查找引用、重命名、增量同步。

IDEA 插件在 `idea-rainstarfox/`，独立 Gradle 工程：

```bat
cd idea-rainstarfox
gradlew test buildPlugin     :: 产物 build/distributions/idea-rainstarfox-0.2.1.zip
```

安装：`Settings → Plugins → ⚙ → Install Plugin from Disk…` 选 zip。插件不内嵌编译器，它起一个 `rsf lsp` 子进程；jar 模式需要 JDK 25，原生 exe 模式不需要。

## 项目结构

```
src/main/kotlin/org/example/complie/   lexing / parsing / ast / sema / ir / interp / codegen / native / lsp
rsfSrc/bootstrap/                     用 rsf 自己写的词法/语法/解释器/字节码后端（自举）
rsfSrc/core/                          自举标准库（Vec / Deque / HashMap / Sorts / Bits …）
rsfSrc/lang/                          示例与回归语料
build/verify*/                        判据台（解释器 / 字节码 / 自举 / C 后端 / CLI 对拍）
idea-rainstarfox/                     IDEA 插件
SYNTAX.md                             语法总表
BOOTSTRAP.md                          自举链的设计与现状
```

## 当前限制

- 没有垃圾回收之外的自动内存管理——JVM 那边靠 GC，C 那边字符串拼接 `malloc` 不释放。
- 泛型按擦除实现，泛型函数有已知缺口。
- 重载只按参数个数与类型，不玩隐式转换。
- 指针只有解释器与 C 后端支持；JVM 字节码后端遇到 `&` / `*` 直接报错。
- 标准库是自举用的最小集合，没有正则、没有格式化、没有迭代协议。
- 没有包管理器，多文件只有 `import "x.rsf"` 与 `module "dir"`。

## 路线图

| 方向 | 状态 |
| --- | --- |
| JVM 字节码 + 解释器双后端逐字节一致 | ✅ |
| 自举链（rsf 写前端/后端，与 Kotlin 版对拍） | ✅ 词法/语法/解释器/字节码后端 |
| C 后端（native 线） | 🚧 类/枚举/指针已接；接口/继承/泛型/浮点待接 |
| LSP + IDEA 插件 | ✅ 诊断/补全/跳转/悬停/引用/重命名 |
| 原生编译器（aarch64 二进制） | ⏳ 依赖 C 后端补全后再自举 |
| 指针 L3（字段/元素取址、指针算术） | ⏳ |

## 许可

GPLv3，见 [LICENSE](LICENSE)。
