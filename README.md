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
rsf build <file.rsf> --meta                 把源码快照打进产物（配合 decompile）
rsf decompile <产物> [-o <dir>] [--list]    从产物还原源码（需 --meta）
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

`--c` 出来的程序可以起 JVM 再调 Java（实参都是 `String`，返回值按 `toString` 取）：

```rsf
fn main() -> void {
    println(jvmCall("Echo", "hi", "bob"))            // 静态方法，可多参
    var e = jvmNew("Echo", "tag")                     // 构造对象，返回句柄
    println(jvmCallOn(e, "prefix", "hello"))          // 实例方法
}
```

```bash
rsf build x.rsf --c -o out
gcc out/x.c -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/win32" -o out/x.exe
RSF_JVM_CP=out out/x.exe      # → java:bob
```

类路径用 `RSF_JVM_CP`（缺省 `.`）；`jvm.dll` 从 `RSF_JVM_DLL` / `$JAVA_HOME/bin/server/jvm.dll` / PATH 找。构造器只收 `String` 参数，句柄上限 256 个；JVM 构建里这三个内置函数都会报错（那边直接调 Java 就行）。细节见 `design/DESIGN-62.md`。

### 看 native 吃了多少内存（`memBytes` / `memPeak`）

native 侧默认分配器是 arena（只增不减，进程退出才整块还回去），所以"到底涨了多少"得能自己读：

```rsf
target native

fn main() -> void {
    var s = "x"
    var i = 0
    while (i < 200) {
        s = s + "abcdefghij"
        i = i + 1
    }
    val peak = memPeak()          // 先取再打：拼接自己也会分配
    val now = memBytes()
    println("peak=" + peak)       // peak=202810
    println("now=" + now)         // now=202810
}
```

两个函数返回 `Long`，只有 native 侧有（JVM 构建里检查期就报 `只有 native 侧才有`）；宿主用 `rsf_ctx_init` 换了自己的分配器时返回 `-1`。它们只读计数、不改分配行为——要真正的回收得等 `design/DESIGN-77.md` 的步骤 B/C。

## C 后端（native 线）

`rsf build x.rsf --c -o out` 把整份程序摊成**一个 C99 文件**，自带运行时（字符串拼接、`print`/`println`、`intToString`），不依赖任何 rsf 库：

```bat
rsf build hello.rsf --c -o out
gcc out\hello.c -o out\hello.exe
```

- 已支持：Int / Long / Bool / String、一维数组、类（字段 / 实例方法 / `stc` 静态方法 / 主次构造 / 单继承与覆盖，虚表分派）、接口（默认方法与虚表回落）、枚举与 `switch`（Int/Long/String 字面量、一个分支可写多个值）、指针（`T*` / `&x` / `&o.f` / `&a[i]` / `*p` / `*p = v` / `p + n`）、顶层函数、`if` / `while` / `for` / 递归。
- 还没接：JDK 接口 / JDK 父类、泛型、lambda、多维数组、浮点参与字符串拼接、`goto` / 标签——遇到会报 `error[native]`。
- 判据：解释器 stdout vs 原生 exe stdout 逐字节相同（`bash build/verify52/run-probes.sh all`，16 份语料）。
- 字符串按 **UTF-16 码元**算下标（跟 Java 一致）：`length()` 数的是码元，`charAt` 对 emoji
  返回代理对，`substring` 切完仍是零拷贝切片（只挪指针）。字面量与拼接在创建时就把码元数
  一起存好（`units1`），所以 `length()` 是 O(1)。要字节视角用 `byteLength(s)` 和
  `stringBytes(s)`。
- 切片 `s[a..b]`（阶段 73d）：半开区间，返回 `String`，`s[..b]` / `s[a..]` / `s[..]` 三种省略；
  越界钳进 `[0, length]`，`b < a` 当空串；切在 emoji 中间时边界外扩到整个码点（三端一致）。
  C 侧是零拷贝视图（只挪指针 + 记长度），JVM 侧是 `substring` 的拷贝。
- 一处有意的差异：`a.substring(1, 2)` 正好切在 emoji 的代理对中间时，JVM 会留半个代理项
  （打印成替换符），native 存 UTF-8 存不下它，会把边界外扩到整个码点（打印 `😀`）——
  宁可让原生输出永远合法 UTF-8，也不去实现「半个字符」的编码。

### 构建目标：native / jvm

同一份源码可以只用一侧的声明——`rsf build x.rsf --c` 是 native 构建，`rsf build x.rsf` 是 JVM 构建：

```rsf
target native            // 文件级：整份只进 native（JVM 构建里整份跳过）

native fn fast(x : Int) -> Int { rtn x + 1 }   // 只写在 native 侧
jvm fn slow(x : Int) -> Int { rtn x + 2 }      // 只写在 JVM 侧
fn both(x : Int) -> Int { rtn x }              // 两侧都有
```

调到「对面才有」的函数会报清楚（「函数 'fast' 只写在 native 侧」），而不是「未定义的函数」。两边互调（JNI 桥）还没接，见 `design/DESIGN-60.md`。

文件头还能写 GC 开关（阶段 77）：`gc off`（默认，纯 arena，只增不减）或 `gc on`（产物带上内置的标记-清扫 GC）。**`gc on` 只认带 `main` 的那个文件**——写在被 import 的文件里会直接报错，因为它是整份产物的开关，写在别处会让人猜不到最终用不用。

```rsf
gc on                           // 产物带上内置 GC：非移动、保守根、按需扫

fn main() -> void {
    println("live=" + memBytes())   // gc on 时这里是 GC 堆的存活字节
    val ok = gcCollect()            // 主动扫一轮，返回 true 表示真扫了
}
```

内置这台 GC 的模型照 Lua 的经典实现：**非移动**、标记-清扫、根是保守扫出来的（锚点区间 `[sp, anchor]`，不拉任何平台头文件），对象负载按字扫描，所以切片这种「指向大字符串中间的视图」也能被认成引用。三条不变量：install 之前分配的对象归 arena、拿不到栈区间时一块都不回收（宁可漏，不能把活对象当垃圾）、清扫期间不回调用户代码。局限（明写）：会漏收（保守根的假引用）、不做压缩/分代、只扫当前线程、没有 finalizer。

GC 的**策略**也可以由宿主自己实现（阶段 77）：C 侧把 `rsf_gc { collect, bytes, user }` 交给 `rsf_ctx_set_gc`，rsf 侧就能用 `gcCollect() -> Bool` 主动要求扫一轮；**宿主登记的 GC 优先**——`rsf_gc_heap_install()` 发现 `c->gc.collect != NULL` 或分配器已被换过就不接管，所以你自己写的 GC 不会被内置的抢走。所有分配都走分配器 vtable，所以自写 GC 不会漏分配；栈边界运行时直接给（`rsf_gc_thread_attach()` + `rsf_gc_stack_bounds(&lo, &hi)`），精确 GC（要类型布局/根登记）仍是后面的事。见 `design/DESIGN-77.md`。

### JVM 字节码后端

`rsf build x.rsf`（不带 `--c`）出 class：

- 已支持：类 / 继承 / 虚表分派、接口与默认方法、枚举（真 `java.lang.Enum`：`values()` / `valueOf` / `name` / `ordinal`，Java 的 `switch` 与 `EnumSet` 可直接用）、泛型（擦除 + 装箱）、`switch`（枚举用 `tableswitch`，Int/Long/String 用比较链）、浮点、注解、指针（`T*` / `&x` / `*p` / `*p = v` / `p + n` / `p == q`）。
- 指针在 JVM 上表示成 `Object[]{ 帧, 格子号 }`：只有被 `&x` 取过地址的变量会被提升进帧数组，没用指针的函数字节码不受影响（`build/verify37/` 守着）。
- 还不支持：`&obj.f`、`&a[i]`（显式报错）；`jvmCall` 只在 native 构建里可用（JVM 侧直接调 Java 即可）。

### C 自举（实验）

C 后端能把自举编译器自己编成原生 exe，再由它编出 class：

```bash
rsf build rsfSrc/bootstrap/RsfcBuild.rsf --c -o build/native-rsfc
gcc -O1 -w -o build/native-rsfc/RsfcBuild.exe build/native-rsfc/RsfcBuild.c
cd build/native-rsfc && env rsf.rsfc.cmd=build rsf.rsfc.file=示例.rsf rsf.rsfc.out=out ./RsfcBuild.exe
```

判据：`bash build/verify66/run-probes.sh`（出 exe → 编样例跑 → stage1 与 Kotlin 版反汇编等价）。

### 产物带源码，可反编译回原文

`--meta` 把参与编译的源文件原文打进产物（默认关）：JVM 构建进 class 的自定义属性 `RsfSource`，native 构建进 PE 的 `.rsfmeta` 节。之后 `rsf decompile` 能把源码原样拿回来——注释、空行、字面量写法都在：

```bash
rsf build main.rsf --meta -o out
rsf decompile out/main.class -o src/restored     # 逐字节还原 .rsf
rsf decompile out/main.exe   -o src/restored     # C 产物同理
rsf decompile out/main.class --list              # 只列不写
```

自举规模实测：`rsfSrc/bootstrap` 8 个源文件 1.01 MB → 元数据 179 KB（压缩约 5.6×），还原 8 个文件 SHA256 全一致。形态与判据见 `design/DESIGN-68.md`，验收脚本 `build/verify68/run.ps1`。

注意：元数据原样带着源码，发布前想清楚要不要把源码一起发出去——所以 `--meta` 默认关。

## Termux / aarch64

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
design/                               各阶段设计笔记（本地，不入库；索引见 design/README.md）
```

### 压掉某类警告（`#NoWarning!!`）

有些警告是「你知道自己在干什么」，比如故意留一段死代码做哨兵。按类型压掉，别整个项目关警告：

```rsf
#NoWarning!!(unreachable)

fn main() -> void {
    rtn
    println("永远跑不到，但我要留着提醒自己")
}
```

- 只压**本文件**的、**指定类型**的警告：`unreachable` / `redundant-static` / `deprecated-case` / `erasure-cast`，`all` 是全压。
- 逗号分隔多个：`#NoWarning!!(unreachable, redundant-static)`。
- **压不掉 error**（报错是「代码不成立」，不是「可以接受的啰嗦」）；类型名写错会当场报错并列出可用类型。
- 指令必须整行；它会在词法之前被挖成空格，所以行号列号与原文完全对齐。
- 结构化诊断（`rsf check --json`、LSP）里每条警告都带 `code` 字段，就是这里的类型名。

## 当前限制

- 自动内存管理分两档：JVM 那边靠 JVM 的 GC；C 那边默认是 arena（只增不减），写 `gc on` 才装内置的标记-清扫 GC（也可以由宿主用 `rsf_ctx_set_gc` 换成自己的）。
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
| C 后端（native 线） | 🚧 类/继承（虚表）/接口（默认方法）/枚举/指针已接；JDK 接口、泛型、lambda、多维数组待接 |
| JVM 互操作 | ✅ 可变参数 + 装箱、`try` 接 Java 异常（阶段 70）、枚举是真 `java.lang.Enum`（阶段 72）；待补：泛型 `Signature`、`--cp`、jar |
| LSP + IDEA 插件 | ✅ 诊断/补全/跳转/悬停/引用/重命名 |
| 原生编译器（aarch64 二进制） | ⏳ 依赖 C 后端补全后再自举 |
| 指针 L3（字段/元素取址、指针算术） | ✅ native 侧（阶段 69 起 JVM 侧由检查期挡住） |

## 许可

GPLv3，见 [LICENSE](LICENSE)。
