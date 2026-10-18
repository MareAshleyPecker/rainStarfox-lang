# 阶段 30c 工单：自举链同步 `lam` 与函数类型

日期：2026-10-18
前置：30a（Kotlin 前端）与 30b（Kotlin 两端）已提交（`d2d124e`、`822e600`）。
设计：`DESIGN-30.md`；JVM 形状以 30b 的实现为准（**逐字节复刻**）。

## 0 一句话

让 `rsfSrc/bootstrap/{Lexer,Parser,Backend,Interp}.rsf` 认 `lam` 字面量与函数类型，产出的 class 与 Kotlin 后端**逐字节相同**，解释器输出与 Kotlin 解释器逐字节相同。

## 1 允许改 / 禁止改

允许：`rsfSrc/bootstrap/Lexer.rsf`、`Parser.rsf`、`Backend.rsf`、`Interp.rsf`（只这四个）。
禁止：`src/**`（Kotlin 侧已定稿，拿它当参照物读，不要改）、`build/verify30/**`（判据台）、其它 `build/verify*` 判据脚本与语料、其它自举源。
**绝对不要跑 `gradlew clean`**（会删掉整个 `build/`，里面有未跟踪的判据台；用 `./gradlew.bat cleanTest test jar`）。

## 2 要做的事

### 2.1 词法（`Lexer.rsf`）

- 关键字表加 `lam`（56 → 57），Kind 用 `KW_LAM`；`keywordKind` 里与 `fn` 并列。
- 头部注释里的沿革补一句（2026-10-18 阶段 30：加 `lam`，56 → 57）。
- 与 Kotlin `src/main/kotlin/org/example/complie/lexing/Lexer.kt` 逐条对齐（那边 `put("lam", TokenKind.KW_LAM)`）。

### 2.2 语法（`Parser.rsf`）

- **函数类型**：类型位置 `(` 开头的 `(int, string) -> bool`、`() -> void`、`?(int) -> int`、`((int) -> int)[]`。
  与 Kotlin `parseParenType` / `parseFunctionType` 同口径（`Parser.kt` 里搜这两个函数）；AST 形状也要一致（`AstDump.kt` 与 `Parser.rsf` 的 dump 必须逐字节可比，判据是 `build/verify24/ast-verify.sh` 与 `build/verify27/ast-verify.sh` 那种对拍，本刀要保证既有对拍不掉）。
- **`lam` 字面量**：`lam(x : int) -> int { rtn x + 1 }`，参数类型/返回子句可省；节点 `NodeKind.LAM`（Kotlin 侧是 `LambdaExpr`），dump 与 `src/test/kotlin/org/example/AstDump.kt` 的 LambdaExpr 分支逐字节一致。
- **`f!!(…)`**：可空函数值先断言非空再调用（Kotlin 侧在 `Parser.kt` 的 postfix 处理里）。

### 2.3 后端（`Backend.rsf`）—— 最重的一刀

按 Kotlin `BytecodeCompiler.kt` 的实现复刻（读它，不要自己发明）：

- 函数类型 JVM 表示 `L__RsfFn<arity>;`；**只为实际用到的 arity** 生成 `__RsfFn<n>` 接口类（`V17`、`ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT`、唯一方法 `invoke`，描述符 `(Ljava/lang/Object;…)Ljava/lang/Object;`）。
- lambda 实现方法：名字 `lambda$<包围方法名>$<k>`、`ACC_PRIVATE|ACC_STATIC|ACC_SYNTHETIC`，描述符 = 捕获（按 body 首次引用顺序，`this` 算第一个捕获）+ 形参 + 返回；**紧跟包围方法之后**发射。
- 创建点：`visitInvokeDynamicInsn("invoke", "(<捕获类型>)L__RsfFn<n>;", LambdaMetafactory.metafactory 的 Handle, samMethodType, implHandle, instantiatedMethodType)`。
- 调用点：`CHECKCAST __RsfFn<n>` → 装箱实参 → `INVOKEINTERFACE invoke` → 结果按静态类型收敛（`CHECKCAST` / 拆箱）。
- 三个 30b 踩过的硬约束（照抄）：① **void lambda 的实现方法返回 `Ljava/lang/Object;`**（体内 `aconst_null; areturn`）——`metafactory` 不接受 `V`；② `instantiatedMethodType` 的基本类型用**装箱**形式（同 javac）；③ 声明返回 `void` 的函数值调用点要补 `POP`。
- 顺序也要对齐，否则常量池/方法表顺序变了 `diff -r` 就不为 0：看 Kotlin 里 `programClass` 与 SAM 类的发射先后、`emitUserMethod` 之后紧跟 lambda 方法的位置。
- 自举侧 `asmTable` 若缺 `visitInvokeDynamicInsn` / `visitMethodInsn(INVOKEINTERFACE)` / `Type.getMethodType` 这类行，按既有模式（`asmTable` 是一张手抄的反射行表）补。

### 2.4 解释器（`Interp.rsf`）

- 新增闭包值（值快照 + 形参名 + 体 + 可选 `this`），`lam` 求值得到它；`Call` 遇到闭包值时按 Kotlin `Interpreter.kt` 的 `RsfClosure` 语义调用；`f!!(…)` 的强制非空沿用既有 `!!`。
- 错误通道沿用 `fail`（`as` 失败风格），文案要与 Kotlin 解释器逐字一致。

## 3 判据（父代理会独立复跑，你只需保证交付时全部绿）

1. `bash build/verify30/run-probes.sh all` → 失败项 0（Kotlin 两端仍绿，没被改坏）。
2. `bash build/verify30/bootstrap-parity.sh` → **6 份探针、`diff -r` 差异条目 0、stdout 逐字节相同**。
3. `bash build/verify21/bootbackend/run-probes.sh both` → 失败项 0。
4. `bash build/verify21/knife-verify.sh stage30c` → 「OK：三条全绿」（J1 148 / diff 0 / verify2 0）。
5. `./gradlew.bat cleanTest test jar` → BUILD SUCCESSFUL，`tests=581 failures=0`。
6. 回归：`bash build/verify24/run-probes.sh all` 与 `bash build/verify24/ast-verify.sh`、`build/verify27/run-probes.sh all`、`build/verify28/run-probes.sh all`、`build/verify29/run-probes.sh all` 全部 0。

## 4 报告

只写：改了哪些文件与关键位置、`diff -r` 是否为 0 及你为此做的顺序调整、任何偏离本工单的地方。不要贴长篇自验日志（父代理会独立复跑）。
