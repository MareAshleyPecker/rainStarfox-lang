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
