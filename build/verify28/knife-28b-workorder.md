# 28b 工单：自举链同步反引号标签 + `@RsfLabels` 元数据

日期：2026-10-18　前置：**28a 已提交**（Kotlin 侧行为是唯一参照）。依据：[DESIGN-28.md](DESIGN-28.md)。

## 0 一句话

自举 Lexer/Parser 认 `` `任意字符`: `` 与 `` goto `任意字符` ``，自举 Backend 给每个有标签的方法发与 Kotlin **逐字节相同**的 `@RsfLabels(value = string[])`。`Interp.rsf` 不用改（它按名字查标签，Parser 已把引号去掉）。

## 1 允许改的文件（严格）

- `rsfSrc/bootstrap/Lexer.rsf`
- `rsfSrc/bootstrap/Parser.rsf`
- `rsfSrc/bootstrap/Backend.rsf`

**禁止**：`rsfSrc/bootstrap/Interp.rsf`、`src/**`（Kotlin 侧已定稿）、`build/verify28/**` 的探针与金标与脚本、`build/verify21|24|27/**`、文档。

## 2 Lexer（`Lexer.rsf`）

- `TokenKind`（约 `:31` 的 `public static fun ident()`）加一个 `labelName()` → `"LABEL_NAME"`。
- `run()`（约 `:900`）的分派里加一支：`c == '`'` → 调用新的 `readLabelName()`。
- `readLabelName()`：吃掉开引号，扫到同行的下一个 `` ` ``；文本 = 引号之间的内容（**不含引号**）；`add(TokenKind.labelName(), text, startLine, startCol)`。
- 三种错误，文案与 Kotlin **逐字**相同（Kotlin 侧 28a 的实现在 `src/main/kotlin/org/example/complie/lexing/Lexer.kt`，先读它对齐）：
  - 空 → `标签名不能是空的`
  - 到行尾/文件尾没闭合 → `反引号标签名没有收尾`
  - 引号内出现换行按未闭合处理
- 反引号内容不查标识符字符集，允许空格/中文/`-` `.` `$` `@` `/` `:`。

## 3 Parser（`Parser.rsf`）

- `goto` 分支（`:2031` 的 `if (k == "KW_GOTO")`）：目标接受 `IDENT` **或** `LABEL_NAME`。
- 行首标签声明（`IDENT` + `COLON` 那一支，grep `COLON` 找）：`IDENT` 或 `LABEL_NAME` 后面跟 `COLON` 都算标签声明，名字取 token 文本（LABEL_NAME 已去引号）。
- **其它任何位置**出现 `LABEL_NAME` → 与 Kotlin 同文案的错误：`反引号只用来写标签名`。
- dump **不用改**：`AstDump`/自举 dump 都是 `LABEL name=<esc(name)>`，名字里已没有引号，`escapeValue` 该转义空格等字符的照旧。

## 4 Backend（`Backend.rsf`）

### 4.1 每方法标签状态（先修这个）

`labelFor`（`:2978`）与 `resetLabels`（`:2974`）背后是 `labNames/labVals/labCount` 三个 **Gen 级**字段，而 `resetLabels()` **当前没有任何调用点**（grep 只有定义）⇒ 两个方法各写一个同名标签时，会共用同一个 `Label` 对象。28b 必须：

- 在**每个方法/函数体开始编译之前**调用 `resetLabels()`（`emitUserMethod`（`:6428`）入口等所有走函数体的地方；grep `emitStmt(` 的通路，确认没有漏的入口，例如顶层函数、构造器、伴生类方法）。
- 新增 **声明顺序**列表：`declLabels : string[]` / `declCount : int`，在 `emitStmt` 的 `LABEL` 分支（`:3339`，就是 `ins.lbl = this.labelFor(prop(st, "name"))` 那支）**逐条登记**（goto 分支绝不登记）。`resetLabels()` 里一并清零。
- 顺序必须是**声明顺序**，不是「首次被提到」的顺序：`goto x` 写在 `x:` 前面时，`x` 只能出现在 `x:` 那次的登记里。

### 4.2 asmTable 加一行

`AnnotationVisitor` 现在只有 3 条（`visit` / `visitEnum` / `visitEnd`，约 `:2724-2733`）。加：

```
visitArray  (Ljava/lang/String;)Lorg/objectweb/asm/AnnotationVisitor;
```

`asmLookup` 按 (owner, name, argc) 查表，位置随意，建议紧跟在 `visit` 之后。

### 4.3 发注解

- 位置：`flushBody`（`:6247`）里，体发完、`lvtEnd(g, mv, off)` / `mv.visitMaxs(0, 0)`（约 `:6297-6299`）**之前**（属性先后不影响字节；关键是注解与 Kotlin 的**相对次序**一致：Kotlin 在 `BytecodeCompiler.kt:1028` 先发用户注解、`@RsfLabels` 在方法尾 `:1446` 前发，所以自举侧同样「用户注解在前、RsfLabels 在体后」）。
- 逻辑：
  ```
  if (g.declCount > 0) {
      var av : AnnotationVisitor = mv.visitAnnotation("LRsfLabels;", true)
      var arr : AnnotationVisitor = av.visitArray("value")
      for each name in declLabels[0..declCount): arr.visit(null, name)
      arr.visitEnd()
      av.visitEnd()
  }
  ```
- **没有标签的方法不发**（旧语料字节必须不变）。
- **不生成** `RsfLabels` 注解接口类（与 Kotlin 一致：描述符悬空）。

## 5 判据（逐条实跑，贴原始输出）

1. `bash build/verify28/run-probes.sh all` → `失败项合计: 0`（这条同时跑 Kotlin 解释器与 Kotlin 字节码）。
2. `bash build/verify28/bootstrap-parity.sh` → 4 份 `p*` 全部 `diff -r=0` 且 stdout 逐字节相同（这条是 28b 的主判据：自举产出的 class 里注解必须与 Kotlin 一模一样）。
3. `bash build/verify24/bootstrap-parity.sh` → 9 份 0（旧语料零回归；这些语料没有标签 ⇒ 字节不许变）。
4. `bash build/verify21/bootbackend/run-probes.sh both` → 失败项 0（自举解释器跑探针，含标签用例）。
5. `bash build/verify24/run-probes.sh all` → 失败项 0。
6. `bash build/verify27/run-probes.sh all` → 不过段数 0。
7. `bash build/verify21/knife-verify.sh stage28b` → 三绿（J1 148 / diff 0 / verify2 0）。
8. `./gradlew.bat test` → 失败 0（Kotlin 侧测试，确认没被自举侧改动带坏）。

## 6 交付要求

- 上面 8 条必须**逐条实跑**；不许改判据脚本或探针。
- 报告给：改动文件 + 行号、每条判据的关键输出行、`diff -r` 为 0 的证据、以及你对 `resetLabels` 调用点的清点结果（哪些入口会走函数体）。
- 遇到 Kotlin 侧行为不清（报错位置、token 文本）→ 读 28a 的实现对齐，不许自己发明第二套口径。
