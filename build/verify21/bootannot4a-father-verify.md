# 第 4a 刀（注解代码生成同步）· 父代理独立复验

日期：2026-10-16 · 复验人：父代理 · 被判对象：子代理 `6582aea1-5e4c-4c8e-9ce4-71b716334f6b`
改动：`rsfSrc/bootstrap/Backend.rsf`（+1210/-20）+ 父代理补的 `rsfSrc/bootstrap/Parser.rsf` 一行（元素行号）

## 判决：**通过**（三条判据全绿；仅剩 4b 的解释器列）

### 0. 父代理做的事

1. 复核子代理报告 `build/verify21/bootannot-knife4a-report.md` 与 `git diff`（`rsfSrc/` 只有 `Backend.rsf` 一个文件被改；
   它越界动过的 `Parser.rsf` 已还原）。
2. **授权并亲手落**它申请的那一行：`rsfSrc/bootstrap/Parser.rsf:1272-1281` 的 `parseAnnotationElement` 里
   `n.line = nameTok.line`（元素名 token 的行号，对齐 Kotlin `AnnotationElement.at`）。
   理由：元素访问器要发 `LineNumberTable`，rsf 语法器原来从不给 `ANNOTATION_ELEMENT` 赋值行号 ⇒ 每个伴生类差一条属性。
   AST dump 不打印行号，故不影响第 3 刀的对拍口径。
3. 全部判据自己重跑（不采信子代理的自证）。

### 1. 探针台四列（`bash build/verify21/bootbackend/run-probes.sh both`）

```
基线（4a 落盘前）: 失败项合计: 17 （其中 rsf-interp 列 8）
本次             : 失败项合计: 8  （其中 rsf-interp 列 8，第 4b 刀的事）
```

- `p1`–`p7` 接口探针：四列全绿、`类字节差异=0`（**不许弄红**这一条守住了）。
- `a1`–`a4` 注解探针：`self(build_exit=0 stdout=同) 类字节差异=0` ← 本刀的主判据。
- `an1_annot_nonconst`：`kotlin_check_exit=0 kotlin_build_exit=1  self_build_exit=1` ← 非常量注解实参必须编不过。
- 原始日志：`build/verify21/bootannot4a-father/accept-runprobes.log`。

**父代理自己确认**：把上面那个 `n.line` 改动放进去**之前**，`a1`–`a4` 的 `类字节差异=1`（只剩伴生类的元素访问器
缺 `LineNumberTable` 一条属性）；放进去之后为 0。⇒ 「差异来源 = 行号缺失」这个归因成立，不是碰巧。

### 2. J1/J3 不动点（`bash build/verify21/bootbackend/j1-knife2.sh`）

```
A exit=0 classes=34
BackendCli exit=0 classes=34 / Backend exit=0 classes=33 / InterpCli exit=0 classes=19
Interp exit=0 classes=17 / ParserCli exit=0 classes=8 / Parser exit=0 classes=7
LexerCli exit=0 classes=4 / Lexer exit=0 classes=3 / Sources exit=0 classes=9
J1 类总数=134  diff -r 差异条目=0
```

- `126 → 134` 是**本刀新增 4 个辅助类**（`AnnEl`/`AnnInfo`/`AccInfo`/`NegLit`，`BackendCli` 与 `Backend` 各 +4）的必然结果，
  **不动点本身的判据是 `diff -r 差异条目=0` + 9 源全 `exit=0`**，不是那个字面数字；`knife-verify.sh` 的断言本来就是 `>=100`。
- 已同步更新：`knife-verify.sh:37` 横幅（去掉写死的 126）、`bootparity-audit.md`、工单 §8。

### 3. 包探针（注解接口不带包 / 伴生类带包）

```
outk/  Tag.class  app/meta/{Box,pkgprobe,Tag$Impl}.class      （Kotlin）
outs/  Tag.class  app/meta/{Box,pkgprobe,Tag$Impl}.class      （自举）
diff -r -q outk outs → 差异条目=0
```

坑（父代理踩到并记录）：`pkgroot` 到仓库根是**四层**，`-jar ../../../build/libs/...` 会 `Unable to access jarfile`，
正确是 `../../../../build/libs/rainStarfox-0.9.0.jar`。

### 4. 三件套（`bash build/verify21/knife-verify.sh knife4a`）

```
----- 1/3 gradle -----   BUILD SUCCESSFUL   BUILD FAILED 行=0  测试失败=无   ✓
----- 2/3 accept-after -----   J1 类总数=134  diff -r 差异条目=0  失败项合计=0   ✓
----- 3/3 verify2 -----   不一致行=0  失败项合计=0   ✓
===== 判决 =====  OK：三条全绿（日志在 build/verify21/knife4a/）
```

## 未覆盖 / 留给后面的

- `rsf-interp` 列 8 项（a1–a4 自举解释器）：**第 4b 刀**（`Interp.rsf` 注解语义）。
- 嵌套类型名 `X::Y`：语言面缺口，另开一刀（`build/verify21/bootannot-runtime/gap-nested-type/README.md`）。
- 接口/注解宿主的访问器同步位：属 Kotlin 侧修复，已单独提交 `c9c457a`。
