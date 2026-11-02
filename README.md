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
class Counter(start : Int) {
    var n : Int = start

    fn bump(by : Int) -> void { n = n + by }
}

fn sum(xs : Int[]) -> Int {
    var total : Int = 0
    for (x in xs) { total = total + x }
    rtn total
}

fn main() -> void {
    var c : Counter = new Counter(0)
    c.bump(3)
    println(c.n)                      // 3

    var xs : Int[] = new Int[3]
    xs[0] = 1
    xs[1] = 2
    xs[2] = 3
    println(sum(xs))                  // 6

    var n : Int = 7
    var p : Int* = &n                 // 指针只在解释器与 C 后端可用
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

## UI 库（`rsfSrc/ui/`）

声明式：`fn view(app) -> Widget` 每帧写一棵树，`Renderer.frame` 画面、判命中、返回动作 id；输入框文字按 `key` 跨帧复用。块套块的盒模型（`padding`/`margin`/`border`/`width`/`height`/`flex`/`align`），`showRegions(true)` 给每块描边、`startTrace()` 打印每块矩形。纯 rsf，解释器与 C 后端都能跑。

`rsf build app.rsf --c --lib -o out` 只出 C 函数、不出 `main`，交给宿主窗口调用。

跑 `rsf run examples/ui_demo.rsf` 会导出 `build/ui_demo_plain.ppm` 与 `build/ui_demo_regions.ppm`（后者带块区域描边）。

## 窗口宿主（Win32，零依赖）

`rsfSrc/ui/Host.rsf` 把 UI 包成一个 `App` 类，再导出**宿主 ABI**（`app_new / app_frame / app_click / app_move / app_text / app_pixels …`，C 侧只看 `rsf_fn_app_*`）。`host/win32_main.c` 是 300 行的 Win32 + GDI 宿主：`StretchDIBits` 把像素缓冲贴上去，鼠标按下/移动/松开、`WM_CHAR` 直接喂回 `App`。

```bat
rsf build examples/ui_app.rsf --c --lib -o build/host
gcc host/win32_main.c build/host/ui_app.c -o build/ui_app.exe -luser32 -lgdi32
build/ui_app.exe
```

无窗口跑判据：

```bat
build/ui_app.exe --frames 1 --shot frame.bmp
build/ui_app.exe --click 140,98 --frames 2   :: 打印 [action] 1
build/ui_app.exe --text hello --frames 2 --shot typed.bmp
```

判据台：`bash build/verify60/run.sh`（生成 C → gcc → 截图 → 点击命中 → 输入后截图，5 项全过）。

### native 调 JVM（`jvmCall`）

`--c` 出来的程序可以起 JVM 再调 Java 的 `public static String m(String)`：

```rsf
fn main() -> void { println(jvmCall("Echo", "hi", "bob")) }
```

```bash
rsf build x.rsf --c -o out
gcc out/x.c -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" -o out/x.exe
RSF_JVM_CP=out out/x.exe      # → java:bob
```

类路径用 `RSF_JVM_CP`（缺省 `.`）；`jvm.dll` 从 `RSF_JVM_DLL` / `$JAVA_HOME/bin/server/jvm.dll` / PATH 找。JVM 构建里 `jvmCall` 会报错（那边直接调 Java 就行）。细节见 `DESIGN-62.md`。

## C 后端（native 线）

`rsf build x.rsf --c -o out` 把整份程序摊成**一个 C99 文件**，自带运行时（字符串拼接、`print`/`println`、`intToString`），不依赖任何 rsf 库：

```bat
rsf build hello.rsf --c -o out
gcc out\hello.c -o out\hello.exe
```

- 已支持：Int / Long / Bool / String、一维数组、类（字段 / 实例方法 / `stc` 静态方法 / 主次构造 / 单继承与覆盖，虚表分派）、接口（默认方法与虚表回落）、枚举与 `switch`（Int/Long/String 字面量、一个分支可写多个值）、指针（`T*` / `&x` / `&o.f` / `&a[i]` / `*p` / `*p = v` / `p + n`）、顶层函数、`if` / `while` / `for` / 递归。
- 还没接：JDK 接口 / JDK 父类、泛型、多维数组、浮点、错误返回——遇到会报 `error[native]`。
- 判据：解释器 stdout vs 原生 exe stdout 逐字节相同（`bash build/verify52/run-probes.sh all`，16 份语料）。

### 构建目标：native / jvm

同一份源码可以只用一侧的声明——`rsf build x.rsf --c` 是 native 构建，`rsf build x.rsf` 是 JVM 构建：

```rsf
target native            // 文件级：整份只进 native（JVM 构建里整份跳过）

native fn fast(x : Int) -> Int { rtn x + 1 }   // 只写在 native 侧
jvm fn slow(x : Int) -> Int { rtn x + 2 }      // 只写在 JVM 侧
fn both(x : Int) -> Int { rtn x }              // 两侧都有
```

调到「对面才有」的函数会报清楚（「函数 'fast' 只写在 native 侧」），而不是「未定义的函数」。两边互调（JNI 桥）还没接，见 `DESIGN-60.md`。

### JVM 字节码后端

`rsf build x.rsf`（不带 `--c`）出 class：

- 已支持：类 / 继承 / 虚表分派、接口与默认方法、枚举、泛型（擦除 + 装箱）、`switch`（枚举用 `tableswitch`，Int/Long/String 用比较链）、浮点、注解、指针（`T*` / `&x` / `*p` / `*p = v` / `p + n` / `p == q`）。
- 指针在 JVM 上表示成 `Object[]{ 帧, 格子号 }`：只有被 `&x` 取过地址的变量会被提升进帧数组，没用指针的函数字节码不受影响（`build/verify37/` 守着）。
- 还不支持：`&obj.f`、`&a[i]`（显式报错）；`jvmCall` 只在 native 构建里可用（JVM 侧直接调 Java 即可）。

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
- 指针只有解释器与 C 后端支持（取地址、解引用读写、`p + n` / `p - n` 偏移、指针判等）；JVM 字节码后端遇到 `&` / `*` / 指针运算直接报错。
- 标准库是自举用的最小集合，没有正则、没有格式化、没有迭代协议。
- 没有包管理器，多文件只有 `import "x.rsf"` 与 `module "dir"`。

## 路线图

| 方向 | 状态 |
| --- | --- |
| JVM 字节码 + 解释器双后端逐字节一致 | ✅ |
| 自举链（rsf 写前端/后端，与 Kotlin 版对拍） | ✅ 词法/语法/解释器/字节码后端 |
| C 后端（native 线） | 🚧 类/继承（虚表）/接口（默认方法）/枚举/指针已接；JDK 接口与泛型待接；浮点待接 |
| LSP + IDEA 插件 | ✅ 诊断/补全/跳转/悬停/引用/重命名 |
| 原生编译器（aarch64 二进制） | ⏳ 依赖 C 后端补全后再自举 |
| 指针 L3（字段/元素取址、指针算术） | ⏳ |

## 许可

GPLv3，见 [LICENSE](LICENSE)。
