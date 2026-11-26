# HANDOFF — rainStarfox 当前状态（给下一位 agent / 未来的我）

> 面向自动化交接：只写「现在在哪、怎么跑、下一步做什么」，不写历史。
> 分支 `feat/script-embed`，本地提交，**未推送**。

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

## 2. 这一轮已经落地的东西

| 提交 | 内容 |
|---|---|
| `0435d74` 及之前 | **C 后端错误模型改成 setjmp/longjmp**，原生解释器九示例全绿 |
| `921e976` | `.rsfs` 接上脚本入口：无 `main` 时可当库，列出可调用函数 |
| `dae5a53` | `rsf init`：生成 `build.rsfs`（必须有 main）+ `build/` 模块 + `src/main.rsf` |
| `fbb87db` | 项目目录里 `rsf build` 驱动 `build.rsfs` |
| `f127053` | `export` 修饰词（Kotlin / 自举 Lexer+Parser / IDEA 关键字表三处对齐） |
| `e54ed73` | 扩展函数**语法**：`fn Int.twice()` / `fn Foo.bar()` → `FuncDecl.receiverType` |

规则：
- `.rsfs` 与 `.rsf` 同语言，唯一差别是**允许没有 main**；
- `export` 只允许顶层函数，语义为零（只影响导出面）；
- 扩展函数语法齐了，调用侧语义在**本节之后已经做完**（见 §3，2026-11-25 补）。

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

## 4. 之后：`#dep` 与构建计划

- `#dep { java("x.jar") c("y.c") h("z.h") }`：块里必须是**合法 rsf 语句**，
  `java/c/h/rule` 是内置函数；解析在装载层，不进 AST。
- 依赖目录 `.rsfdeps/`（跟着源文件走，gitignore），`lock.json` 记 sha256。
- 自定义规则 = 一个返回依赖清单的 rsf 函数，运行器先单独跑它（此时无依赖）。
- 构建计划执行（target / rule）还没设计，等 `#dep` 落地。

## 5. 再之后：自举线（直出机器码）

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

## 7. 导入机制改版（已定设计，未实现）

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

## 10. `.rsfs` 语法定稿（进行中，逐条拍板）

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
