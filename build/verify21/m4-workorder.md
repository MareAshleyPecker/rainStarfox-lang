# 阶段 21 M4（解释器）派工单草案

> 状态：草案（M3 还在跑，落地后父代理复核一遍再发）。发给子代理时压缩「背景」段，只留可执行要求。

## 目标

解释器把注解的三件事做对，且与字节码后端 **stdout 逐字节一致**：

1. `new Marker("hi", 3)` / `new Marker::Impl("hi", 3)` 造出伴生对象，元素字段已按构造器参数赋值。
2. `m.value` 读元素字段（合成 `ClassDecl` 里已有该字段，多半自动，需测）。
3. `m.value()` 元素访问器 —— **这是 M4 的主要活**：M2 把元素访问器调用记成
   `UserMember.Method(className = 注解名, interfaceOwner = true)`，而合成注解接口**不进 `interfaceTable`**，
   所以走 `callMethod:712` 会**静默 null**（`callInterfaceMethod:749` / `findInterfaceImplementation:793` 也找不到实现）。
   要在 `evalMember:554` / `evalCall:1228` 里用 `SemanticModel.annotationAccessors` / `elementAccessorOf` / `accessorsOn` 直接取值。
4. 伴生体里按裸名读元素（编译期已解析成同一个成员）⇒ 同第 3 点。
5. 伴生体自己的行为方法（`describe` / `run`）走普通类方法，应已自动。

## 明确不做（要写进 DESIGN-21 §10 备案）

- `public static Marker()` / `Marker(string member)` 访问器与 `annotationCache`：**只有 Java 侧能调**，rsf 源码看不见 ⇒ 解释器不建缓存
  （DESIGN-21 §7 里那条按此收窄）。
- `annotationType()`：同样字节码独有（rsf 没有 class 字面量，Checker 里没这个成员）⇒ 解释器不建模。

## 坐标（父代理勘查，已核实）

- `interp/Interpreter.kt`：`class Interpreter` :103、`enumCache` :116、`evalMember` :554（`UserMember.Field` :579、`UserMember.Method` :583）、
  `callMethod` :712（**别改**，查不到会静默 null）、`callInterfaceMethod` :749、`findInterfaceImplementation` :793、`construct` :971、
  `runConstructor` :1012、`runFieldInitializers` :1037、`evalCall` :1228。
- 语义模型接口：`SemanticModel.annotationOf` / `companionOf` / `isAnnotationType` / `isCompanionClass` / `useSiteOf` /
  `isAnnotationConstruction` / `annotationConstructionOf` / `accessorsOn` / `elementAccessorOf`；
  `CompanionSpec(annotation, jvmName, decl: ClassDecl, constructor, accessors, elementFields)`。
- 伴生类 = 合成 `ClassDecl`（`jvmName = Marker$Impl` / 匿名 `Marker$companion`）已在 `ClassModel.classes` 里 ⇒ `construct` / `runConstructor` 直接复用。
- 元素字段的 `initializer` 是**占位**，真值来自构造器参数。

## 交付

- 新测试 `src/test/kotlin/org/example/AnnotationInterpTest.kt`（≥12 条）：`new Marker(...)` 的元素字段与访问器、`m.value` 与 `m.value()` 同值、
  具名实参、默认值生效、伴生行为方法、`new Marker::Impl(...)`。
- 探针 `build/verify21/m4/`：至少 3 个 `.rsf`，每个都跑 **`rsf run`** 与 **`rsf build` + `java`** 两条路，两边 stdout 逐字节相同。
- 交付时 `./gradlew.bat cleanTest test jar` 全绿（≥ M3 后的条数），不跑全量验收脚本（父代理跑）。

## 硬约束

- 不碰：`rsfSrc/**`、`build/verify17e|1*|20|21/**`（只准新建 `build/verify21/m4/`）、既有测试的期望、`DESIGN-*.md`、`BOOTSTRAP.md`、
  `README.md`、`codegen/**`（M3 的地盘）、`lsp/**`、`idea-rainstarfox/**`。
- `sema/**` 原则上不动：若发现 M2 的接口真的缺东西，**停下来在报告里说明**，别自己改。
- 不做 git 操作。
