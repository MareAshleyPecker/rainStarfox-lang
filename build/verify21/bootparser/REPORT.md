# 自举同步第 1 刀 · 交付报告：`rsfSrc/bootstrap/Parser.rsf` 收 `interface` / `override` / 父类型子句

作者：子代理（session-4bfd7351c741）  ·  日期：2026-10-15
工单：`build/verify21/bootparser-workorder.md`（含父代理后补的 §9，以及 2026-10-15 对 §1.3/§1.5 的修订）

---

## 0. 一句话结论

- **本刀的验收口径全绿**：`./gradlew.bat cleanTest test jar` → `456 tests completed, 0 failed`（基线 456 条不增不减）；
  RsfTest 三条对拍用例（全语料 / 刁钻语料 / 非法输入）全过。
- **逐字节一致有两条独立证据**：① 三条 JUnit 对拍用例；② 本报告 §4 的旁证工具 —— 同一份探针源，
  rsf 版 dump 与 Kotlin 版 dump 归一到 LF 后 **2966 字节、SHA-256 完全相同**
  （`b7cbab00065775ad6198d65c3f81b7a8b30fddd740850af804253b4281cc46da`）。
- ⚠️ **`accept-after.sh` / `verify2.sh` 是「已知耦合红」，不是本刀的未完成项**（父代理 m00690 第 2/4 条已定性）。
  用重写判决逻辑后的脚本复盘（`KNIFE_SKIP_RUN=1 bash build/verify21/knife-verify.sh bootparser-verify`）：
  `FAIL：2 段不过`——第 2 段 `accept-after` 失败项 5（J1 类总数 **0**），第 3 段 `verify2` 不一致行 9
  （八示例全部 `kotlin(exit=0) vs rsf(exit=1) **不一致** out=0B err=88B`）。
  根因不在本刀的三份文件，而在**新旧刀耦合**：新 Parser 现在对**每个** class 都发 `SUPERS` 节点
  （`rsfSrc/bootstrap/Parser.rsf:900` 的 `var supers = new Node(NodeKind.SUPERS)`），而旧后端
  `rsfSrc/bootstrap/Backend.rsf` 的类成员白名单 `collectClasses:4826-4876` 不认它，落到 `:4870` 兜底
  `g.bad("类成员 " + kindName(part.kind))` → `[rsf-17d] 17d 后端暂不支持：类成员 SUPERS`；
  `compileProgram:5317-5330` 又没有 `INTERFACES` 分支。**`SUPERS`/`INTERFACES` 必须由第 2 刀的后端来消费**，
  J1/J2/J3 才会重新变绿。按父代理明令：**不得**为了让它变绿而删除 `SUPERS` 或改成「只在有父类型时才发」——
  那会直接破坏 AST 对拍（`src/test/kotlin/org/example/AstDump.kt:184` 对每个 class 都打 `SUPERS count=N`）。
- 旧版 `knife-verify.sh`（2026-10-15 23:06 那次）打印的「OK：三条全绿」是**假绿**：它的判决取管道 `$?`
  （被 `grep`/`tee` 吃掉了），不是日志内容。父代理已重写判决逻辑（改抓正文的「失败项合计: N」「**不一致**」，
  并加 `KNIFE_SKIP_RUN=1` 复盘模式）；本报告一切结论以新脚本为准。教训：**别信包装脚本的摘句，看 `*.log` 正文**。

---

## 1. 改了哪些文件（行数为原始计数，非 git）

| 文件 | 改动前 | 改动后 | 净增 | 说明 |
| --- | --- | --- | --- | --- |
| `rsfSrc/bootstrap/Parser.rsf` | 1949 | 2172 | **+223** | 四个新 NodeKind、四个函数/字段、parseProgram/parseClassDecl/parseFuncDecl 改造 |
| `src/test/kotlin/org/example/AstDump.kt` | 436 | 477 | **+41** | `INTERFACES` / `supers()` / `interfaceDecl()` / `override=` 字段 |
| `src/test/kotlin/org/example/RsfTest.kt` | 8226 | 8272 | **+46** | 只加语料：合法 1 条（26 行源码）+ 非法 13 条 |

父代理复核时读到的 `git diff` 数字是 `+257 / +43 / +46`（含删除行）；净增与本表一致
（Parser.rsf 有 34 行是重写掉的旧行，主要是 parseFuncDecl 的修饰词循环与几处注释）。

新建证据（全部在 `build/verify21/bootparser/`，未碰任何既有文件）：
`probes/`（14 个探针源）、`bisect/`（11 个）、`bisect2/`（9 个）、`dumps/`（每探针两侧 dump + stderr）、
`ktprobe/`（`KtDump.java` + `run_ktprobe.ps1`）、`out/`（jar 编出的 rsf 语法器字节码）、
