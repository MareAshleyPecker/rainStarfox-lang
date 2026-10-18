# 工单 · 第 4b 刀：注解语义同步进自举解释器（`rsfSrc/bootstrap/Interp.rsf`）

2026-10-16 · 只改一个文件 · 父代理独立复验

## 1. 现状与判据

- 红：探针 `a1_annot_basic` / `a2_annot_defaults` / `a3_annot_positions` / `a4_annot_arg_kinds` 在 `rsf-interp` 列全 exit=1，报
  `解析错误: 17c 编译器暂不支持：外部 JVM 类 'Marker'（阶段只支持 java.util.ArrayList 与 java.io.File）`
  —— 注解声明没进解释器的类表，`new Marker(...)` 掉进 JVM 互操作兜底（`Interp.rsf:1607 evNew`）。
- 判据（父代理终审）：`bash build/verify21/bootbackend/run-probes.sh both` → **`rsf-interp` 列 8 项红全绿**，`interp`/`kotlin`/`self` 三列维持 0 失败、`类字节差异=0` 不变。
- 允许改：**只有** `rsfSrc/bootstrap/Interp.rsf`。需要动别的文件（`Parser.rsf`/`Backend.rsf`/Kotlin 侧）先问父代理。
- 禁止：跑 `gradlew`（构建目录归父代理）；改 Kotlin 侧；`git` 操作。

## 2. 参照实现（Kotlin 解释器，`src/main/kotlin/org/example/complie/interp/Interpreter.kt`）

- 注解对象 = 伴生类实例（`RsfObject`，`className` = 伴生类名），元素是它的前 N 个字段。
  - 伴生类名：`Marker$Impl`（具名伴生）/ `Marker$companion`（匿名）。
  - `new Marker("hi")` 走伴生类构造器；注解接口本身不是普通类。
- `fillElementDefaults:1087-1106`：链根构造器里、`runFieldInitializers` 之前，把**省略掉的实参**对应的形参槽填成该元素的默认值表达式（元素字段的 initializer）。
  调用条件 `:1035-1038`；构造器允许少给尾部实参 `:1028`。
- `annotationElementCall:1132-1136`：从外部对注解对象取元素（`m.value` / `m.value()`）当**非虚**方法调，元素访问器在伴生类上。
- 使用点（`@Marker` 标在类/字段/方法上）**运行期是空操作**，解释器不需要为它做什么。
- 参考桩：`build/verify21/bootannot-backend-prep.md` §B（Kotlin 解释器行号 + 行为）与 §C2（rsf 落点行号）。

## 3. rsf 侧落点（都为改动前行号）

- `Cls :508-522`、`addSuper :524`、`defaultNamed :536`、`anyNamed :547`、`fieldIndex :557`、`methodNamed :566`、类表 `findCls`。
- `load() :777-788` 只认 `FUNCS/CLASSES/ENUMS/INTERFACES` —— **加 `ANNOTATIONS` 分支**。
- `loadClasses :824-842`（只认 `FIELDS`/`METHODS`/`SUPERS`）、`loadIfaces :846`、`loadSupers :869`、`loadFields :878`。
- `evNew :1607`、`constructUser :1620`、`pickCtor :1645`（**要求 `pcount == args.length` 精确个数**）、`runCtor :1699-1719`（`:1704-1709` 缺省填 `vNull()` ← 坑）、`runFieldInits :1690`。
- `objMember :2035-2050`、`invokeCall :1855-1894`、`isErrorCapable :2073`。

## 4. 要做的事（按依赖顺序）

1. `load()` 认 `ANNOTATIONS`，对每个 `ANNOTATION` 子节点（`name` / `ANNOTATION_ELEMENTS` / 可选 `COMPANION`）合成一个 `Cls`：
   - 名字 `name + "$Impl"`（`COMPANION` 有非空名字）或 `name + "$companion"`（匿名）；**同时把「注解名 → 同一个 `Cls`」登记成别名行**，好让 `new Marker(...)` 找到它。
   - 字段 = 元素（按声明序，名字/类型取 `ANNOTATION_ELEMENT`，默认值表达式为该元素的默认值）+ 伴生体自己的 `FIELDS`。
   - 合成一个构造器：形参 = 元素（序、名、类型），体为空。
   - 方法 = `COMPANION` 的 `METHODS`。
   - 元素字段必须能被伴生体方法按**裸名**读到（隐式 this）——若 `Cls` 结构允许多名，保持 `fnames` 与 `fieldIndex` 一致。
2. `evNew`：`findCls(base)` 命中注解别名时构造伴生类（不要掉进 JVM 兜底）。
3. 缺省实参：镜像 `fillElementDefaults` —— 注解伴生类在 `runCtor` 里、`runFieldInits` 之前，把未给实参的元素槽用**元素默认值表达式**求值填入（**不是 `vNull()`**）；`pickCtor` 对注解伴生类允许前缀实参（`args.length <= pcount`）。
4. 元素访问器：从外部对注解对象取元素名（`m.value` / `m.value()`）时，若方法表没命中就落到元素字段/访问器（非虚）。
5. `isErrorCapable` 认注解元素（可选，探针未覆盖，做了更好）。

## 5. 交付

- 报告 `build/verify21/bootannot-knife4b-report.md`（≤40 行）：改了什么（行号）、`run-probes.sh both` 输出尾部、剩余红项与原因。
- 不需要自验对拍，父代理会独立复跑四条判据（探针台 / J1 / knife-verify / 八示例）。
