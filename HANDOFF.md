# HANDOFF — rainStarfox 当前状态（给下一位 agent / 未来的我）

> 面向自动化交接：只写「现在在哪、怎么跑、下一步做什么」，不写历史。
> 分支 `feat/import-redesign`，本地提交，**未推送**。

## 0. 一分钟交接

- **编译器**：Kotlin 写的（`src/main/kotlin`），三个后端：解释器、JVM 字节码、
  C（native），外加实验性的 LLVM 文本 IR（`--ll`）与 aarch64 汇编（`--asm`）。
- **自举**：`rsfSrc/bootstrap` 是用 rsf 写的编译器，今天就走 C 后端编自己
  （`tools/dev/native.sh` → `build/dev/native/interp`），与 Kotlin 版逐字节对拍 9/9。
- **构建语言**：`.rsfs` + `build.rsfs`，四阶段（指令 → 规则 → main → 产物）已通，
  `project {}` / `target {}` / `dependencies {}` / `run { use … }` 四个块语句已落地，
  `rsf run` 是 cargo run 语义，`module-impex.rsf` + 模块别名按包组织标准库。
- **标准库**：`stdlib/{lang,io,std,classfile}`，模块用「类 + 静态方法」组织
  （`Text.join` / `Io.readLines` / `Lang.…` / `Exception.…`）。
- **下一步**：根除 `try/catch`（五步，见 §10「根除 try/catch 的计划」）。
  第 1 步（`?:` 的 void 兜底 + `lastErrorMessage()`，判据 `verify99`）已完成；
  下一步是迁移 `rsfSrc/lang` 的 19 处，并把这三个新表面补进自举侧。

## 1. 这台机器怎么跑

没有 JDK 25（`build.gradle` 里 `jvmToolchain(25)` 过不去），所以绕开 gradle：

```sh
sh tools/dev/kb.sh        # 用 ~/.gradle/caches 里的 kotlinc 编编译器 → build/dev/classes
sh tools/dev/native.sh    # 把 InterpCli.rsf 编成 C，再 gcc 出 build/dev/native/interp
sh tools/dev/parity.sh    # 九示例：原生解释器 vs Kotlin 解释器，逐字节对拍
sh tools/dev/quick.sh     # 上面三步一条命令（约 75s，成功只打 parity: 9/9）
sh tools/dev/probe.sh --cmp /tmp/x.rsf   # 单文件探针 + 对拍
```

- 依赖：`~/.gradle/caches/modules-2/files-2.1` 里的 `kotlin-compiler-embeddable:2.3.10`、
  `asm:9.9.1`。换机器先把 `~/.gradle` 拷过来。
- 原生解释器读输入用环境变量 `rsf.interp.file`（rsf 的 `main` 接不到命令行参数）。
- gcc 链接要带 `-lm`（`fmod`）。
- 编译器命令：`java -cp build/dev/classes:$(cat build/dev/rt.cp) org.example.MainKt <子命令>`。

### 判据台（都可单独跑：`sh build/verifyNN/run.sh`）

| 判据 | 覆盖 | 依赖 |
|---|---|---|
| `verify84` | 构建四阶段、`project{}`、`#def` 规则、`dependencies{}`、stdlib 头文件、旧 `#target` 迁移提示 | gcc |
| `verify85` | jar 进分析期 classpath、`import` 行补全 | gcc + jar |
| `verify86` | `extern fn` 调 C、`import "*.c"/"*.h"` | gcc |
| `verify88` | `module-impex.rsf` 三种出口、`exportjvm` 类型约束 | gcc |
| `verify89` | `rsf run` 的 cargo 语义、`run { use }` | gcc |
| `verify90` | `pkg-info.rsf` 继承、导出清单门禁 | — |
| `verify92` | `--ll`（LLVM 文本 IR）与 C 后端对拍 | clang 或 llc |
| `verify93` | `rsf cc`（编 rsf 自己出的 C） | gcc |
| `verify94` | 标准库自动导入（补全带 import 编辑、诊断提示） | — |
| `verify95` | 标准库常用面（ArrayList/Conv/Lang/Io/Vec 新方法） | — |
| `verify96` | `Exception` 结构化错误串 | — |
| `verify97` | `try` 表达式、`catch` 三种写法、不再误报不可达 | — |
| `verify98` | `fail 种类, 消息`、种类名编译期校验 | — |
| `verify99` | `?:` 的 void 兜底、`lastErrorMessage()`、三端输出对拍 | gcc |

## 2. 2026-11-26 这一轮落地了什么

| 提交 | 内容 |
|---|---|
| `12c55dd` | rsf build 四阶段（`#target` 时代）：`exec` / `target` / `need` + 出产物 |
| `399dccc` | `#def` 定义构建规则，阶段 1 按序调用（解糖成函数） |
| `ab24ead` | 规则里声明依赖（当时叫 `java/c/h/dir`），名字只在 build.rsfs 占用 |
| `2082565` | jar 进分析期 classpath（编辑器共用），改名 `javaInclude/cInclude/hInclude/dirInclude`，支持目录 |
| `7389155` | `import` 行补全列出依赖 jar 里的类 |
| `3e390a9` | `extern fn` 调 C，`import "*.c"/"*.h"` |
| `94xxx`/`948655b` | `#` 变成封闭集合，未知指令报错并列出内置 |
| `9c4b997` | 定下 native 路线 A（自举仍走 C 后端，`--ll` 按需扩） |
| `d6f390a`/`18539ed` | LLVM 文本 IR 后端（`--ll`）：整数、比较、控制流、调用、打印 |
| `3580333` | `rsf cc`：编 rsf 自己出的 C（自动补 `-std/-I/-lm`） |
| `97cc149` | `rsf run` 改为 cargo run 语义（`--bin`、`--`、jar 用 `java -jar`） |
| `989eb93` | `run { use 名字 }` 块（选默认产物，按行执行） |
| `a9b14ab`/`41ef616` | `#target` → `target { exe(…) }` 块语句 |
| `a36b5dd`/`dc5d0a7` | `dependencies {}` 块语句 + `stdlib/` 目录（运行时头只生成一份） |
| `d76a747` | `pkg-info.rsf` 与导出清单门禁 |
| `615f31d`/`1994c53` | `module "…" to 别名 { … }`（清单里与源文件里都能写） |
| `533f87b` | 标准库扩展（ArrayList/Conv/Lang/Exception/Io 补 readLines）+ 清除重复 API |
| `2ac9377` | `try` 表达式、`catch` 省略绑定；修 `ControlFlow` 没传 handlers（所有 catch 体被误报不可达） |
| `3c0ad9c` | `fail 种类, 消息`（`ErrorKinds` 表，编译期校验） |
| `823f581` | `Io` 的三处重抛改用 `try 表达式`（根除 try/catch 的样板） |
| `e1d6088` | `?:` 的 void 兜底 + `lastErrorMessage()`（根除 try/catch 第 1 步，判据 `verify99`） |

规则：
- `.rsfs` 与 `.rsf` 同语言，唯一差别是**允许没有 main**；
- `export` 只允许顶层函数，导出面以 `module-impex.rsf` 为准；
- 构建脚本里能用的内建（`exec`/`target`/`need`/`include`/`config`/`use`）只在 `build.rsfs` 里占名字。

## 3. 扩展函数语义（✅ 已做完，2026-11-25）

结果：`fn Int.twice() -> Int { rtn this * 2 }` 之后 `3.twice()` 在解释器 / JVM / C 三端
都返回 6；成员同名时成员优先；同接收者重名同参数个数报错；LSP 的 `x.` 补全会列出扩展。
细节与判据写在 `design/DESIGN-80.md`，回归 725 → 731。

落地方式（原计划 vs 实做）：

1. Parser 首参 —— **上游已做**，`params[0]` 就是接收者，`this` 绑到它。
2. Checker 绑 `this` —— 上游已做（`currentExtensionReceiver`）。
3. 查找回退 —— 做了 5 处回退点（`checkUserMember` 字段/方法、`checkInterfaceMember`、
   `checkMember` 尾巴、JVM 重载没匹配上），命中记 `SemanticModel.extensionCalls`；
   扩展表键是「接收者类型名去泛型实参后的最后一段」，同键重名同参数个数在
   `collectExtensions()` 里报错。
4. 解释器 —— `evalMember` 开头查表，接收者当第一个实参。
5. 字节码 + C —— 摊成静态调用；**坑**：JVM 侧 `this` 必须读局部变量 0
   （`ExprCtx.extensionReceiver`），发 `aload_0` 会 `VerifyError`；C 侧读 `v_this`。
6. LSP —— `EditorSupport.memberItems()` 现在会列扩展；`RsfSymbols` 的悬停/跳转**没做**
   （扩展仍按普通函数索引）。
7. 规则同 Kotlin —— 静态解析、不能碰私有成员；重名限制已实现。

## 4. ~~之后：`#dep` 与构建计划~~（已落地，见 §8；本节留作历史）

- `#dep { java("x.jar") c("y.c") h("z.h") }`：块里必须是**合法 rsf 语句**，
  `java/c/h/rule` 是内置函数；解析在装载层，不进 AST。
- 依赖目录 `.rsfdeps/`（跟着源文件走，gitignore），`lock.json` 记 sha256。
- 自定义规则 = 一个返回依赖清单的 rsf 函数，运行器先单独跑它（此时无依赖）。
- 构建计划执行（target / rule）还没设计，等 `#dep` 落地。

## 5. ~~再之后：自举线（直出机器码）~~（已开工：路线 A，见 §11；本节留作历史）

- 起点已经具备：C 后端有 setjmp 错误模型；原生解释器端到端跑通（启动 10ms vs JVM 400ms）。
- 导出：**出目标文件交给系统链接器**，`export fn` 决定可见性、`rsf_` 前缀防撞名；
  只有「单文件零工具链」成为真需求时才手写 PE `.edata` / ELF `.dynsym`。
- 边界类型：`rsf_str` 是 16–24 字节结构体，**跨边界一律传指针**；`!T` 用返回值 + 错误出参。

## 6. 已知坑（踩过，别再踩）

- `check(DOT)` 只窥视不消费，扩展函数解析必须 `advance()`。
- `rsf_u16_len_of` 对 `units1 == 0` 的字符串查**按 data 指针做键**的缓存，arena 复用地址会命中
  陈旧条目 → 手工拼的 `rsf_str` 一律 `rsf_str_settle(s)` 再返回。
- C 后端 `try/catch` 里 `break/continue` 目前**显式拒绝**（v1），别以为能用。
- Kotlin 块注释会**嵌套**：文档里写 `build/*.rsfs` 会把注释打开，编译报 Unclosed comment。
- gradle 依赖缓存没有的话，先在有网机器 build 一次再拷 `~/.gradle`。

## 7. ~~导入机制改版（`import as` + 显式注入）~~（**已被 §10 的 `module "…" to 别名` 取代**，本节留作历史）

```rsf
import "core/Vec.rsf" as vec          // 只给限定名，不注入
import "core/Vec.rsf" as vec { * }    // 全部注入
import "core/Vec.rsf" as vec { push, size }   // 只注入这两个
```

- 限定词用 **`as`**（import 位置无歧义，不复用 `from`/`of` 这类含糊词）。
- **默认不注入**：不写花括号就只拿限定名 `vec.push(...)`；要裸用必须显式 `{ * }` 或列名字。
- 同名注入在**编译期报错**并指出两处来源，不要后导入静默覆盖。
- `@/` = **项目根**（`build.rsfs` 所在目录），`./` 当前目录可省，`../` 保留。
  这样跨层引用写成 `@/rsfSrc/core/Vec.rsf`，`@2/` 这类设计不再需要。
- 限定名分隔符用 `.`，模块别名走独立命名空间，查找顺序：模块别名 → 类 → 变量。

实现顺序（口径必须四处一致，否则自举链先崩）：
1. `loading/SourceLoader.kt` 与 `directives/`：解析 `as` 与花括号清单、`@/` 展开；
2. `sema/Checker.kt`：模块别名表 + 显式注入表 + 同名冲突报错；
3. `lsp/RsfSymbols.kt` / `EditorSupport.kt`：补全里区分“本文件可见”与“需限定”；
4. `rsfSrc/bootstrap/Sources.rsf`：同一套规则的 rsf 版（parity 的关键一步）；
5. 判据：新增 `build/verify83/`，正例（三种导入形态）+ 负例（同名冲突、缺名导入、
   `@/` 越界）各若干，并保证九示例不受影响。

## 8. rsf build 线（阶段 83；四个阶段都跑得动了）

四阶段，缺一个都会让语义含糊：

| 阶段 | 做什么 | 状态 |
|---|---|---|
| 0 读指令 | `#dep` / `#build`（挖成空格）与 `#def`（解糖成函数）；产物用 `target { }` 块语句 | 做了 |
| 1 跑规则 | `#def 名字 { … }` 定义的规则，`#dep rule("名字")` 按声明顺序调用 | 做了（规则体是代码） |
| 2 跑 main | 追加 `target()` / `need()`；`exec()` 只在这里可用 | 做了 |
| 3 出产物 | 按表顺序（有 `need` 就拓扑序），复用 `--c --link` / `--jar`；第一个失败即停并打印完整命令 | 做了 |

目标表：`TargetSpec{ name, src, kind, out? }`，`kind ∈ {exe, lib, jar, copy}`（`build/BuildPlan.kt`）。
两种写法进同一张表：`target { exe("@/a.rsf") }` 与 `target("@/a.rsf", "exe")`；
名字取文件名，重名报错并列两处来源；声明顺序即构建顺序，`need(a, b)` 只改顺序（稳定拓扑排序，成环报错）。

`#target` 的第三个 `->` 段可省，写了就是显式产物路径：

```
target {
    exe("@/src/main.rsf")
    copy("@/res/data.txt", "build/out/copied.txt")
}
```

内建（只在 build.rsfs 可用）：`exec(cmd : String) -> Int`、`target(src : String, kind : String) -> void`、
`need(a : String, b : String) -> void`。

`#def` 是规则的定义（阶段 83 用声明式替换了最初「规则函数返回 `c:/h:/…` 前缀串」的设想）：

```rsf
// build/rules.rsfs —— 规则可以放在被 import 的模块里
#def genDeps {
    println("先跑我")
    exec("protoc --cpp_out=gen proto/x.proto")
}

// build.rsfs
import "build/rules.rsfs"
#dep {
    rule("genDeps")
}
```

- `#def 名字 { … }` 在装载期**解糖**成 `fn 名字() -> void { … }`：只改开头那一段、
  `{` 之后原样保留，所以规则体里每个字符的行列都不动，体里报错仍指回用户写的那一行
  （已验证：`println(nope)` 报在原文第 3 行第 13 列）。
- 规则因此就是普通函数：main 里可以直接调，规则之间也能互相调；两条同名 `#def`
  会走 Checker 的「函数重复定义」，不用另写一套重名检测。
- `#dep rule("名字")` 在**装载期**校验：认不出来就报错，并列出已知规则与零参数函数。
- 规则和 main 在**同一个解释器实例**里跑（`runEntry(rules)` 先跑规则再跑入口），
  全局变量只初始化一次——否则 `const val X = readText(...)` 这种初值会被算两遍。
- 规则体里能做的「计算」只有两类：`exec("…")` 与调用 rsf 函数 / 类。声明依赖用四个内建
  `javaInclude(...)` / `cInclude(...)` / `hInclude(...)` / `dirInclude(...)`，进的是和 `#dep { … }` **同一份清单**
  （锁文件、存在性校验只有一条路；`dirInclude` 校验 `isDirectory`、锁文件里不记哈希）。
  四个都是 **vararg**，且**给目录就展开成里面同类型的文件**（声明时展开，所以锁文件记的是
  具体文件；`javaInclude("libs")` = libs 下所有 `.jar`，`cInclude`/`hInclude` 同理）。
  `dirInclude` 不展开——它表达的就是「整个目录」。

### jar 进分析期 classpath（阶段 84）

`#dep { javaInclude("libs") }` 声明的 jar 由 rsf 自己装进分析期：`ClassPath` 加了一个可换的
loader，`installLoader` 会**清缓存**（缓存里存过「没找到」，不清就会变成「重启才行」）。
两处用同一个 loader：
- 分析期读 `.class` 字节（ASM，`getResourceAsStream`，**不初始化类**）；
- 解释器反射调用 Java 方法（`ClassPath.loadClass` 走 `Class.forName(name, true, loader())`——
  原来那 12 处 `Class.forName` 全换了，否则 jar 里的类在运行期依然看不见）。

时序上：`#dep` 里的 jar 在**分析构建脚本之前**装上（脚本自己就能 `import demo.Util`）；
规则里 `javaInclude(...)` 当场声明的 jar 立刻生效（后续 `compile()` 就能看见），
但对**已经分析过的**这份构建脚本无效——这是声明式指令与运行时声明不可避免的先后差。

编辑器那一侧复用了同一个机制（`build/ProjectJars.kt`）：按文件往上找 `build.rsfs`，
读它的 `#dep` 并装上，指纹（根 + jar 路径/大小/时间）没变就不重装；只读字节不初始化类。
`rsf complete` 与 LSP 的四个分析入口都接了：`Util.` 能补出 jar 里的方法，
`import ` 那一行能补出依赖 jar 里的类名（`ProjectJars.installedClassNames` 直接读 jar 条目，
过滤内部类与 `META-INF`，上限 300 条）。

`javaInclude` / `cInclude` / `hInclude` / `dirInclude` 这些名字**只在 build.rsfs 里被占用**——普通程序里
`fn c(x : Int)` 是正常写法，不该因为构建脚本存在被禁掉。为此有三处守卫，缺一处都会出岔子：

1. Checker 的保留名检查跳过「构建脚本专用内建」（`BUILTINS_BUILD_ONLY`）；
2. Checker 的**调用解析**：有同名用户函数时先认用户函数（否则声明成功、调用却落到内建上）；
3. 解释器 / 字节码 / C 三个内建分派点：`callTargets` 已解析到用户函数时不走内建
   （否则运行期被内建截走）。

三处与最初设计不同，都是有意的：

- **`exec` 不标 `!Int`**：退出码非零是构建脚本要判断的**正常结果**
  （`if (exec("git rev-parse") != 0) …`），标了 `!` 就得给每条命令套 try。
  真正算失败的是「命令起不来」，那种直接中止构建并指出是哪些路径按项目根解析的。
- **`target` 的 kind 写字符串**：rsf 里的裸标识符不是值；`#target` 块中没有类型系统，
  所以那边可以写裸 `exe`，函数这边写 `"exe"`，两边最终都进同一张表。

三道关卡，缺一不可：
1. `Checker.checkBuiltinCall`：`source.name != build.rsfs` 就报错（构建脚本 import 的模块按根文件算，照样允许）；
2. 解释器：没有宿主时抛「只有构建脚本里能用」（`rsf script` 之类绕过项目的路径兜底）；
3. `CBackend` / `BytecodeCompiler`：分派表里显式 `fail` / `RsfError`（产物里没有编译器可调）。

判据（已跑通）：`#target` 的 exe + main 里 `target(lib)` + `need` 改序 + `copy` 带显式路径，
一条 `rsf build` 出 `main`、`lib.o`、`copied.txt`，再 `./build/out/main` 打印正常；
jar 目标 `java -jar build/out/tool.jar` 打印正常。

下一步（按顺序，一步一刀）：
1. jar 进分析期 classpath（`URLClassLoader` 读 `.rsfdeps` 里声明的 jar）——没有这一步
   「构建脚本调 jar」只是口号；
3. `#target` 里写 `need` 行（现在是 main 里才能写），以及 `--list` 只看计划不构建；
4. `compile()` / `compileAll()` 与目标表的关系收口：明确「compile 立即编、target 进计划」。

## 9. rsf 调 C（阶段 84 收口：extern fn + import 的 .c/.h）

```rsf
import "native/util.c"      // 等于 cInclude：这个 .c 进链接
import "native/util.h"      // 等于 hInclude：它的目录进 -I

extern fn util_add(a : Int, b : Int) -> Int   // 只有签名，体在 util.c

fn main() -> void {
    println("C 说：" + intToString(util_add(40, 2)))   // 42
}
```

- `extern` 是**顶层函数**修饰词（和 `export` 同一个位置），解析出来是「无体 + `isExtern`」；
  返回子句**必须写**（和抽象方法同一个理由），`!T` 被拒（C 侧没有 rsf 的 longjmp 通道）。
- C 后端：原型用 **C 原名**（不加 `rsf_fn_`），调用点也按原名调；不生成函数定义。
- JVM 侧（字节码 / 解释器）**显式拒绝**：`extern` 在 JVM 上没有对应物。
- `import` 的 `.c` / `.h` 在装载器里按扩展名分流进依赖清单（`.rsf`/`.rsfs` 以外的扩展名
  只有这两种有意义；别的照旧当源码，找不到就报错）。
- 依赖路径的基准：**有 `build.rsfs` 就相对项目根，否则相对当前目录**——必须和构建命令
  解析依赖的基准一致，否则清单里的 `../native/util.c` 会被解析到别处。

判据：`build/verify86/run.sh`（gcc 命令行里有 `-I` 与 `.c`、真调到 C 函数、生成的 .c 里
没有 `rsf_fn_` 前缀、JVM 侧报错）。手工另验过单文件模式 `rsf build src/main.rsf --c --link`。

**已知缺口**：自举侧的 `Lexer.rsf`/`Parser.rsf` 还不认识 `extern`（它连 `#dep`/`#def`
那一套指令都没有）。所以在自举派上用场之前，含 `extern` 的源码只能由 Kotlin 侧编译。
等 bootstrap 补语法时，这条要和其它指令一起补，不是单独一刀。

## 10. `.rsfs` 语法定稿（进行中，逐条拍板）

**根除 try/catch 的计划**（阶段 84，用户拍板；早期设计债，越堆越多，现在清理）：

目标形态只剩四个运算符 + 两个查询函数，`try { } catch { }` 整块消失：

| 现在 | 替换 |
|---|---|
| `expr!!` | 不变（取值/向上抛） |
| `try expr` | 不变（传播，`!void` 也行）——已实现 |
| `expr ?: fallback` | 不变（出错取值） |
| `try { f() } catch { }` | `f() ?: <void 表达式>`——已实现（`!void` 也能 `?:`） |
| `try { } catch e { 用 e }` | `expr ?: …` + `lastErrorMessage()`——已实现 |
| `catch e { 多语句 }` | 抽函数，或 `?: null` + `if` |
| `finally` | 以后换 `defer` |

五步：① `?:` void 兜底 + `lastErrorMessage()`（三端 + 判据）→ ② 迁移 `stdlib` 的 3 处
（已完成 Io）→ ③ 迁移 `rsfSrc/lang` 19 处（parity 示例）→ ④ 迁移 `rsfSrc/bootstrap`
31 处并跑通自举 → ⑤ 删掉 `try {} catch {}` 语法（Parser 报迁移提示，自举 Parser 同步）。

**① 已完成**（判据 `verify99`）：

```rsf
writeText(p, s) ?: reportFailure()     // !void 的 ?: ：出错才跑兜底（两边都得是 void）
println(lastErrorMessage())            // 最近一条错误消息；被兜住后仍保留，没出错是空串
```

三端各自的槽：解释器是 `RsfFailure` 抛出时记下的文件级变量；字节码是程序类上的静态字段
`__rsf_lastErrorMessage`（抛错时 `getMessage()` 落进去，只在程序真用过时发，旧产物不变）；
C 侧直接用运行时的 `rsf_err_msg`。同时修掉一个老不一致：C 的 `?:` 以前没看
`rsf_err`，`parseInt("x") ?: -1` 会拿到 `0` 并把错误旗标漏给后面的语句。

现状：`stdlib/io/Io.rsf` 的 3 处重抛已改成 `try 表达式`；`rsfSrc/lang/errors.rsf`
是错误模型的教学示例，下一步（③）就该用它展示 `lastErrorMessage()`——
注意那时要同步补自举侧（bootstrap 的 Parser/Checker/Interp 还不认识这些新表面）。

**错误种类**（阶段 84 第二步的表面部分）：

```rsf
fail range, "n 不能为负：" + intToString(n)   // 也认 fail "range", "…"
```

种类表在 `complie/diag/ErrorKinds.kt`（io / parse / range / state / todo /
unreachable / runtime / user）。写错种类是**编译期**错误并列出可用的；
生成的消息统一是 `种类: 消息`，和 `Exception.kindOf` 的约定一致。

**还没做**：把 `!T` 的载荷从 `String` 换成 `(kind, message)`——那要动 C 运行时、
三个后端与自举（约 3–4 个专项），届时这张表直接变成枚举取值。

**错误处理的新表面**（阶段 84 第一步；语义仍是现有的 try/catch，三端未动）：

```rsf
try writeText(p, s)                     // Zig 风格：!void 也能直接传播
try readText(p)                         // 丢弃值的语句形式
try { f() } catch { … }                 // 不要绑定
try { f() } catch e { … }               // 要绑定，不写类型
try { f() } catch (e : String) { … }    // 老写法，兼容
```

`try 表达式` 解糖成等价的 `try { 表达式 } catch (e : String) { fail e }`，
所以只能在可能失败的函数（`!T`）里用——在 `void` 函数里会明确报错。

顺带修掉一个老 bug：`Checker.analyzeFlow` 构造 `ControlFlow` 时**没传 handlers**，
异常边不在 CFG 里，导致 **每个** catch 体都被误报「不可达代码」（现有代码里一直有，
只是没人细看）。已修，判据 verify97 断言不再误报。

**标准库布局**（阶段 84）：`stdlib/{lang,io,std,classfile}`，模块用**类 + 静态方法**组织
（`Text.join(...)` / `Io.readLines(...)` / `Lang.parseIntOr(...)` / `Conv.toInt(...)`），
因为 rsf 里 `名.成员` 的左边必须是类型名。

- `lang`：`Lang`（parseIntOr / defaultIfBlank / oneLine）、`Exception`（结构化错误串：
  `kind: message` + kindOf/messageOf/isKind/check*；rsf 的错误通道只有 String，所以
  这里不造异常类，只约定格式，三端行为一致）
- `io`：`Io`（读写在原有四个内置之上加了 readLines / writeLines / readTextOr）
- `std`：Vec（新增 getOr / insert / addAll / removeLast）、**ArrayList**（Java 风格的
  常用列表，内部包 Vec）、**Conv**（toInt / toIntOrNull / toBoolOrNull / toString(Int|Bool|Char)
  / toBytes / toChars）、Strings（新增 lines）、以及原有 Bits/Numbers/Sorts/…

**模块别名写在源文件里**（阶段 84，用户拍板）：一行声明即可用，不必依赖清单文件：

```rsf
module "stdlib/std" to std { Vec, Text }   // 起名字 + 只导入这两个
module "stdlib/lang" to lang               // 没有成员表 = 整包
```

- 声明**立即导入**列出的成员，之后 `new Vec&<Int>()` / `isBlank(…)` 直接能用；
- 同一个别名也能用于点分 import（`import std.Vec`），成员表之外的会被拒；
- `module-impex.rsf` 里的 `module "…" to …` 是**项目级**声明（对外面貌），源文件里的
  是**文件级**（只影响这个文件）；两者规则一致，解析时文件级优先。

**块语句家族**（阶段 84，都不带 `#`）：`project { 字段 = 值 }`、
`target { 动词(参数) }`、`dependencies { 声明 }`、`run { use 名字 }`，
以及 `rsf run` 的 cargo 语义。`run { }` 里 `use project` / `use dependencies`
是显式声明（本来就全局生效），`use <产物名>` 选中 `rsf run` 默认跑哪个
（命令行 `--bin` 优先）。

`dependencies { javaInclude(…) cInclude(…) rule(…) }` 由**装载层**处理（和 `#dep` 同一条路），
因为 jar 必须在分析之前进 classpath，所以块里只认字面量。`#dep` 仍可用，新写法是块语句。

**`pkg-info.rsf`**（每包目录一份，元信息文件）：`pkg a.b` 让目录下的源码不必每份都写
`pkg`；文件自己也写 `pkg` 时必须一致。其它 `键 "值"` 进元数据表（暂只记录）。
它不进 AST、不被当源码编译，`compileAll` 与 module 目录列举都会跳过它。

**导出清单门禁**：项目里存在 `module-impex.rsf` 时，**跨目录** import 的目标必须在
清单里出现过（`export` / `exportjvm` / `extern` / `import` 任一）；**同目录不受限制**
（包内互见，类比 Java 的 package-private）。构建脚本 `build.rsfs` 豁免。
项目根按「哪个祖先目录有清单」算，不要求同时有 build.rsfs。

**标准库 `stdlib/`**：`rsf_runtime.h` 现在只生成一份，放在 `stdlib/`（不进版本库），
gcc 命令行固定带 `-I<stdlib>`；查找顺序见 `build/Stdlib.kt`（`RSF_STDLIB` →
编译器位置往上找 → 当前目录往上找 → `./stdlib`）。

**`rsf run` 是 cargo run 语义**（阶段 84）：在项目目录里不带文件名时，先跑四阶段构建，
再从产物里挑一个运行——只有一个可运行的（`exe` / `jar`）就跑它，多个必须
`rsf run --bin <名字>`；`--` 之后的参数原样转给程序。带文件名的 `rsf run a.rsf`
保持原语义（解释执行、不构建）。

已定的条目（用户拍板，实现按这个走）：

1. **`#` 只留给内置指令**——`#dep` / `#build` / `#target` / `#def` 这一族不改名、不给用户扩展；
   编译器认识的指令才配 `#` 前缀。
2. **配置块不带 `#`**：`project { name = … }` 是语言级声明（已实现：AST `ConfigBlock`，
   在 globals 之后、规则与 main 之前求值）。同理 `dependencies { }` / `run { }` 也走这一套。
3. **`run { }` 里用 `use`（不是 `using`），按行执行**——一行一个动作，顺序即执行顺序。
4. **两个特殊文件**：`module-impex.rsf`（模块的 import/export 声明：导出给普通 rsf、
   extern C、JNI、javaClass 导出）与 `pkg-info.rsf`（包声明 + 注解 + 元数据，类比
   Java 的 package-info）。**其他语法同前**（`def` / `fn` / `class` / `export` /
   `const val` / `lazy` / 配置块）。

待补的实现点（按依赖顺序）：
- 装载器按**约定文件名**识别这两个特殊文件（在哪个目录：项目根还是每个包目录，待定）；
- `module-impex.rsf` 是 `export` / `extern` / JNI / javaClass 的唯一落点；
- 指令解析保持「词法前处理」，但 `#` 集合封闭，遇到未知 `#xxx` 直接报错并列出内置指令；
- 产物命名缺口（`InterpCli` vs `interp`）和 `#target` 的 `name(...)` 一起收。

## 11. native 线：LLVM 文本 IR（按 Zig 的路子，阶段 84 起）

决定：**native 线先交给 LLVM**，以后自己的编码器成熟了再逐步替换；C 与 class 后端都保留
（C 是覆盖面最广的参照，class 是 JVM 互操作与 LSP 的参照）。

已有两个实验性后端，都只覆盖很小的子集，遇到不支持的构造**显式报错**：

| 开关 | 产物 | 说明 |
|---|---|---|
| `--ll [--link]` | `.ll` 文本 | 交给 clang 一步出二进制；没有 clang 就退回 `llc` + gcc |
| `--asm [--link]` | aarch64 `.s` | 自己指令选择的验证；不依赖 LLVM |

**路线决定（用户拍板）**：自举继续用 **C 后端**（它今天就能编 `rsfSrc`，`native.sh` 走的就是它），
`--ll` 只服务用户程序、按需扩覆盖面；等它覆盖面接近编译器自身时再考虑把自举切过来，
切的时候拿 C 后端当对照。**不**为了自举去追 `--ll` 的功能表。

`--ll` 的扩展顺序（按价值）：字符串拼接 → 数组 → 类/结构体 → 枚举/switch → 闭包 →
`fail`/`try` → 文件 IO。

两个目标集合（aarch64/amd64、Linux ELF/Windows PE）都能由 LLVM 覆盖，所以
多架构不再是问题：换 `--target` 即可，不用为每个架构写编码器。
`verify92` 用「LLVM 产物与 C 后端输出对拍」作为语义锚点。

## 12. 下一步（按顺序）

1. ~~`?:` void 兜底 + `lastErrorMessage()`~~ ✅ 已完成，判据 `verify99`。
2. 迁移 `rsfSrc/lang` 的 19 处（**parity 示例**，输出必须逐字节不变）——
   要注意自举侧现在还不认识 `?:` 的 void 兜底与 `lastErrorMessage()`，
   这一步实际包含「bootstrap 的 Parser / Checker / Interp 同步」。
   然后再迁移 `rsfSrc/bootstrap` 的 31 处，跑通自举。
3. 删掉 `try {} catch {}` 语法：Parser 报「已移除，改用 …」，自举 Parser 同步。
4. `try` 表达式（`val x = try { f() } catch { 0 }`）——需要 Lowering 支持“表达式内的块值”，
   与载荷改造同级，单独安排。
5. `!T` 载荷从 `String` 换成 `(kind, message)`：`ErrorKinds` 表直接变枚举取值，
   `Exception.isKind` 的字符串解析退役。
6. 两个特殊类型（用户 2026-11-26 提）：`Any`（任何非 void 值都能塞进去）与
   `Unit`（只有 `unit` 一个值的单元类型，给泛型与「没有信息量的返回值」用）。
   要想清楚：`Any` 与现有 `Object` 互操作的关系、装箱表示、三个后端各自的落地，
   以及 `Unit` 与 `void` 的分工（`void` 不能当值/当类型实参，`Unit` 可以）。
   涉及类型系统核心，单独排期，别和 try/catch 收尾混在一起。
7. 其余：LSP 跳到标准库定义、标准库继续补（排序/遍历/格式化）、`defer`。

## 13. 这份文档怎么读

- §0–§2 是最新的（状态、命令、判据、本轮提交）。
- §3–§7 是历史设计记录，标题上标了「已落地 / 已取代 / 留作历史」。
- §8–§11 是各条线的详细设计：构建语言四阶段（§8）、C 互操作（§9）、
  `.rsfs` 语法定稿与标准库/错误模型（§10）、native 线（§11）。
