# 第 4a 刀报告：阶段 21 注解代码生成同步进自举后端

**结论**：注解接口 / 伴生类 / 方案 B 访问器+缓存 / 使用点 RuntimeVisibleAnnotations / `annotationType()` /
元素访问器都已按 Kotlin 口径在 `Backend.rsf` 实现，`失败项合计` 17 → 12，J1/J3 不动点仍绿。
**唯一未达标处**：a1–a4 与 pkg 各残留 1 个文件的字节差异（伴生类元素访问器缺 `LineNumberTable`），根因在
`Parser.rsf`，见 §4。改动文件**仅** `rsfSrc/bootstrap/Backend.rsf`（+1216/-23）；`git diff --stat` 里另外 4 个
`idea-rainstarfox/**` 是父代理既有改动，`rsfSrc/bootstrap/Parser.rsf` 无输出（未碰）。

## 1 改了哪些块（行号 = 落盘后行号）

- 导入/标志 `31-47`、`89-97`：`import org.objectweb.asm.Type/AnnotationVisitor/FieldVisitor`；`accAnnotation()`=8192、`accSync()`=32（与 `accSuper()` 同值但不混用）。
- ASM 白名单 `2368-2460`：`new AsmMem[23]`→`[30]`，+7 行（3 个 `visitAnnotation`、`visitAnnotationDefault`、`AnnotationVisitor.visit/visitEnum/visitEnd`）；`jdkMember` 的 ASM 分派 `2521` 加 `AnnotationVisitor`。
- `Type.getObjectType` `1636-1663`：`jdkStatic` 新增 `org/objectweb/asm/Type` 分支（拍板 4）。
- 数据类 `6070-6158`：`AnnEl`（带 `line`）、`AnnInfo`、`AccInfo`、`NegLit` —— **4 个新类**。
- 常量编码集中段 `6160-6410`：`annBad`/`annConst`/`annDoubleVal`/`annCacheName`（拍板 2「一段集中放」）。
- `buildCompanion` `6416-6508`：元素字段 → 合成/用户 `<init>` → 注解接口**前插**父接口 → 缓存字段。
- `collectAnnotations` `6509-6614`：元素表 / 使用点 / 访问器登记（照 Kotlin 全局登记序：注解→伴生字段→伴生方法→接口→类→字段→方法→顶层函数）。
- `emitAnnotationClass` `6622-6682`：**简名** writer、`ACC_ANNOTATION`、`visitSource`、`@Retention(RUNTIME)`、元素方法 + `visitAnnotationDefault().visit(null, 常量)`。
- 使用点 `6683-6783` `emitAnnSites{,F,M}`/`emitAnnArgs`/`emitAnnPair`：`visitAnnotation(desc,true)`、位置/具名配对、枚举 `visitEnum`、`NullLit` 跳过。
- 伴生尾部 `6784-6832` `emitAnnElemAccessors`/`emitAnnCaches`/`emitAnnType`；宿主访问器 `6833-6985` `emitAnnotationAccessors` + `emitAnnTypeAccessor`/`emitAnnMemberAccessor`/`emitAnnNew`/`emitAnnPushArg`（**类宿主带 32，接口/注解宿主不带**）。
- 挂接点 `3638-3675`、`4840-4850`、`5267-5270`、`5395-5400`、`5498-5513`（`emitFieldInits` 元素字段取形参）、`5531-5587`、`5704-5822`（`emitUserClass`）、`6023-6042`（`emitUserInterface`）、`7006-7101`（`compileProgram` 增 `ANNOTATIONS` 桶 + 发射序）。

## 2 `bash build/verify21/bootbackend/run-probes.sh both`（全文 `bootannot4a/accept-runprobes.log`）

```
   p1_default_method … p7_default_err_capable   self(exit=0 stdout=同)  类字节差异=0
   a1_annot_basic             interp(exit=0)  kotlin(build_exit=0 stdout=同)  self(build_exit=0 stdout=同)  rsf-interp(exit=1 stdout=异/exit异)  类字节差异=1
   a2_annot_defaults          interp(exit=0)  kotlin(build_exit=0 stdout=同)  self(build_exit=0 stdout=同)  rsf-interp(exit=1 stdout=异/exit异)  类字节差异=1
   a3_annot_positions         interp(exit=0)  kotlin(build_exit=0 stdout=同)  self(build_exit=0 stdout=同)  rsf-interp(exit=1 stdout=异/exit异)  类字节差异=1
   a4_annot_arg_kinds         interp(exit=0)  kotlin(build_exit=0 stdout=同)  self(build_exit=0 stdout=同)  rsf-interp(exit=1 stdout=异/exit异)  类字节差异=1
   n1_new_interface           kotlin_check_exit=1  self_build_exit=1  rsf_interp_exit=1
   n2_ambiguous_default       kotlin_check_exit=1  self_build_exit=1  rsf_interp_exit=1
   n3_static_interface_method kotlin_check_exit=1  self_build_exit=1  rsf_interp_exit=1
   an1_annot_nonconst         kotlin_check_exit=0 kotlin_build_exit=1  self_build_exit=1
==== 失败项合计: 12 （其中 rsf-interp 列 8，第 4a 刀不管这一列） ====
```

## 3 另两条验收

`bash build/verify21/bootbackend/j1-knife2.sh`（全文 `bootannot4a/accept-j1.log`）：9 个源 `exit=0`，`J1 类总数=134  diff -r 差异条目=0`。**126→134 是本刀新增 4 个辅助类的必然结果**（BackendCli 与 Backend 各 +4），不动点本身仍逐字节相同。
pkg 探针（全文 `bootannot4a/accept-pkg.log`）：两侧 `exit=0`，文件表一致 `Tag.class` + `app/meta/{Box,pkgprobe,Tag$Impl}.class`（注解接口无包、伴生类带包 ✓）；`diff -r -q outk outs` 只剩 `Tag$Impl.class` 一处。

## 4 没做到的地方与原因

`javap -v` 逐属性比对：差异**只有**元素访问器缺 `LineNumberTable`（`Marker$Impl.value/count()`、`Doc$Impl.label()`…；`run()`/`show()` 等 AST 方法带的 LineNumberTable 已完全对齐）。Kotlin 用 `at = el.decl.at`（元素名 token 的行号）打这条属性，而 rsf 语法器**从不**给 `ANNOTATION`/`ANNOTATION_ELEMENT` 赋 `.line`（`Parser.rsf:1263-1277`、`1182-1209`），AST 里取不到该值；工单 §0 只允许改 `Backend.rsf`，故**未**落这一行。Backend 侧已按 §7 写全（`emitAnnElemAccessors` 读 `AnnEl.line`，`>0` 才发 `visitLineNumber`）⇒ 今天恒 0、不发射、无回归；**补齐只需 `Parser.rsf` 的 `parseAnnotationElement` 加一行 `n.line = nameTok.line`**（`n` 构造之后、`rtn n` 之前）。

已在 `build/verify21/bootannot4a/scratch4a/`（副本，不动 `rsfSrc/`）实测该补丁：

```
$ java -jar $JAR build scratch4a/BackendCli.rsf -o scratch4a/A      # exit=0 classes=34
a1 exit=0 类字节差异=0 / a2 =0 / a3 =0 / a4 =0
pkg 探针: diff -r -q outk outss → (exit=0)
```

即：补上那一行，**a1–a4 全绿、pkg 探针 0 差异、`失败项合计` 12 → 8**（只剩 4b 的 rsf-interp 列）。AstDump 不打印行号（`grep -c line dumps/rsf_a3_annot_positions.txt` = 0），故该行不会动第 3 刀的 AST 对拍。是否授权落这一行、或另开一刀，请父代理裁定。
