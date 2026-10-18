import io

p = 'DESIGN-28.md'
with io.open(p, 'r', encoding='utf-8', newline='') as f:
    s = f.read()

s = s.replace(
    "状态：🔄 2026-10-18 设计定稿，28a/28b/28c 三刀待做。",
    "状态：✅ 2026-10-18 完成（28a `5edbd27` Kotlin 侧 / 28b `cc1bf17` 自举链；判据见 §5，落地记录见 §7）。",
    1)

s = s.replace(
    "- `rsfSrc/bootstrap/Backend.rsf`：`Gen` 上加「本方法的标签名列表」，`LABEL` 语句处登记；"
    "方法收尾发 `MethodVisitor.visitAnnotation(\"LRsfLabels;\", true)` + `AnnotationVisitor.visitArray(\"value\")`；"
    "**asmTable 加一行** `AnnotationVisitor.visitArray(String)`（现在只有 `visit` / `visitEnum` / `visitEnd`，见 `Backend.rsf:2724-2733`）。",
    "- `rsfSrc/bootstrap/Backend.rsf`：**每个 `Walker`（= 一次函数体编译）**上放「标签声明顺序表」"
    "（`declLabels` / `declCount`，`resetLabels()` 一并清零），只在 `LABEL` 语句处登记、`goto` 分支绝不登记；"
    "`flushBody` 里 `lvtEnd` 之后、`visitMaxs` 之前发 `MethodVisitor.visitAnnotation(\"LRsfLabels;\", true)` + "
    "`AnnotationVisitor.visitArray(\"value\")`；**asmTable 加一行** `AnnotationVisitor.visitArray(String)`"
    "（原表只有 `visit` / `visitEnum` / `visitEnd`，见 `Backend.rsf:2724-2734`，表长 31→32）。",
    1)

old5 = """## 5 判据

- 新探针台 `build/verify28/`：正例（特殊字符标签、跨块 goto、反引号与普通名混用、多个方法各自的注解）+ 负例（空名、未闭合、名字里带反引号、表达式位置用反引号、重复标签名、跨函数 goto）。
- 注解读回：ASM 逐方法读 `LRsfLabels;` 的 `value`，与源码声明顺序比对。
- `javap -v` 能看到 `RsfLabels`（人工确认一次，写进报告）。
- 两端字节一致：`bootstrap-parity.sh` 式对拍（28c）。
- 不动点：`J1 148` 类、`J5` 九示例锚点、`./gradlew.bat test` 全绿、`verify24` / `verify27` 回归。"""
new5 = """## 5 判据（全部实跑过）

- 探针台 `build/verify28/run-probes.sh`，五段：`check`（4 正例过 / 5 负例拒）、`msg`（负例文案逐字）、
  `golden`（解释器 stdout vs 冻结金标）、`run`（解释器 vs 字节码 stdout 逐字节）、`javap`（有 `RsfLabels`、
  且**没有** `RsfLabels.class`）→ `失败项合计: 0`。
- 正例：`p1_special_chars`（`` `step-one` ``、`` `a.b$c@d/e` ``）、`p2_mixed`、`p3_two_methods`（两方法同名 `loop`）、
  `p4_unicode`（`` `第一步 开始` ``）；负例：空名 / 未收尾 / 表达式位置 / `` `finish` `` 与 `finish` 重名 / 跨函数 goto。
- 两端字节一致：`build/verify28/bootstrap-parity.sh` 4 份 `diff -r=0` 且 stdout 逐字节相同（注：p1 的 class
  md5 与 Kotlin 产物全同，`13dac514…`）。
- 注解读回：`LabelMetadataTest`（13 条，ASM 读回 `value` 与声明顺序比对；构造器里写标签也有注解）。
- 不动点：`J1 148` 类、`diff 0`、`verify2 0`、`./gradlew.bat test` 564/0、`verify24`（9 份 parity + 探针 0）、
  `verify27`（0）、`bootbackend both`（0）。"""
assert old5 in s
s = s.replace(old5, new5, 1)

s = s.replace(
    "3. 反引号不扩展到变量 / 函数名：`var `a-b`` 仍然不合法。",
    "3. 反引号不扩展到变量 / 函数名：`var `a-b`` 仍然不合法。\n"
    "4. 标签名里不能再有反引号（第一个反引号即收尾，多出来的会在 parser 报「反引号只用来写标签名」）。\n"
    "5. 自举解释器（`InterpCli`）打印中文时按平台默认编码写出（Windows 上是 GBK），与 UTF-8 金标不同——"
    "这是阶段 21 起的既有问题，与阶段 28 无关；`run-probes.sh` 的 `golden` / `run` 两段只跑 Kotlin 侧。",
    1)

s = s.rstrip('\n') + """

## 7 落地记录（2026-10-18）

- **28a `5edbd27`**（Kotlin 侧）：`Token.kt:250-258` 新记号 `LABEL_NAME`；`Lexer.kt:171-210` `readLabelName`
  （同一行内取到下一个反引号，不做标识符检查，三种报错位置都取开引号）；`Parser.kt:2067-2072`（`goto` 收两种记号）、
  `:2110-2114`（行首 `IDENT|LABEL_NAME + COLON`）、`:2120-2122`/`:2495-2497`/`:3039-3042`/`:3075-3078`（
  其它位置统一 `labelNameOnlyError`）；`BytecodeCompiler.kt:2323`（`endLvt` 之后、`visitMaxs` 之前调
  `emitLabelMetadata`）、`:2341-2352`（按 `loweredOf(fn).instructions` 里 `ILabel` 顺序收集，滤掉
  `\\u0000` 前缀的内部标签，空表直接 return）、`:3917` `SYNTHETIC_LABEL_PREFIX`；`LabelMetadataTest.kt` 13 条；
  IDEA `RsfLexer.kt` 识别反引号名（不高亮错、不抛）。
- **28b `cc1bf17`**（自举链）：`Lexer.rsf:32-33` / `:775-818` / `:964-971`；`Parser.rsf:433-435`（文案函数）、
  `:2038-2047`（goto）、`:2070-2075`（标签声明）、`:2082-2085` 与 `:2957-2960`（两处兜底）；
  `Backend.rsf:2641`（asmTable 31→32）、`:2724-2734`（`visitArray` 行）、`:2980-2994`（Walker 上
  `declLabels`/`declCount` + `resetLabels` 清零）、`:3357-3367`（LABEL 分支登记）、`:6097-6100` / `:6252-6269`
  （`new Walker` 后 `resetLabels()`）、`:6325-6340`（`flushBody` 发注解）。`Interp.rsf` 未改。
- **工单前提的一处更正**：28b 工单里「`resetLabels()` 从未被调用 ⇒ 两个方法同名标签共用 Label」不成立——
  `labNames` 等状态在 `Walker` 上，每个函数体都 `new Walker`，隔离本来就是结构性的；`resetLabels` 确是死代码，
  28b 已把它接到 `prepBody` / `emitFunction` 里，并把真实机制写进注释。
- 判据全绿（见 §5），工作树只剩 4 个既有 `idea-rainstarfox/**` 脏文件。
"""
with io.open(p, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('ok')
