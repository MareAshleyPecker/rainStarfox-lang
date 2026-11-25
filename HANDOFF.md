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
- 扩展函数现在会得到一句明确的 `error[checker]: 扩展函数还没接语义`。

## 3. 下一刀：扩展函数语义（照着做）

目标：`fn Int.twice() -> Int { rtn this * 2 }` 后，`3.twice()` 在**解释器**里返回 6。

1. **Parser 加首参**：`receiverType != null` 时，在 `params` 前面插一个
   `Param("this", receiverType)`。保留 `receiverType`（LSP 和「不能访问私有成员」要用）。
2. **Checker 绑 `this`**：`checkFunctionBody` 里，扩展函数把名字 `this` 绑到 0 号形参，
   让 `ThisExpr` 能解析（今天 `this` 只在类里有效）。
3. **查找回退**：成员调用解析失败处（`Checker.kt:6534` / `6607` 附近），
   若接收者静态类型命中某个扩展，记 `SemanticModel.extensionCalls[expr] = decl`，
   实参按 `decl.params.drop(1)` 检查。
4. **解释器**：`Interpreter.evalMember` 的调用路径里，命中扩展就以接收者为第一个实参调用。
5. **字节码 + C**：调用点变成「static 调用 + 接收者在前」；没接之前**显式拒绝**，
   不要错编。
6. **LSP**：`lsp/RsfSymbols.kt` 把扩展挂到接收者类型下（文档符号分组 "Extensions"）；
   `EditorSupport` 里 `x.` 之后补全该类型的扩展；悬停/跳转定位到 `fn Foo.bar` 那行。
7. 规则同 Kotlin：**不能访问私有成员**；**静态解析**（按声明类型选）。

验证口径：`rsf run /tmp/ext.rsf` 输出 6；`quick.sh` 仍 9/9（旧语料不含扩展，不该动）。

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
