# 语言面缺口：rsf 语法器不认**嵌套类型名**（`X::Y`）

日期：2026-10-16 · 父代理记录（第 4 刀开工时撞到）· 状态：**未修，未排刀**

## 现象

Kotlin 版把 `Marker::Impl` 当类型名（阶段 21 注解伴生类的写法），自举语法器直接 `<解析失败>`：

```
$ java -Drsf.ast.file=build/verify21/bootannot-runtime/gap-nested-type/nested.rsf \
       -cp "build/verify21/scratch4a/eyeball;build/libs/rainStarfox-0.9.0.jar" ParserCli
<解析失败> build/verify21/bootannot-runtime/gap-nested-type/nested.rsf
```

同一份文件把 `var m : Marker::Impl = new Marker("hi", 3)` 改成 `var m = new Marker("hi", 3)` 就能解析
（证据：`build/verify21/bootbackend/probes/a1_annot_basic.rsf` 改写前后对比；`t1/t2/t3` 三个只含注解语法、
不含 `::` 的探针全部 `PROGRAM`）⇒ **注解语法本身是好的，缺的只有嵌套类型名**。

## 影响面

- 受影响的写法：`var m : Marker::Impl = ...`、任何把 `注解::伴生` 写在类型位置的代码（Kotlin 侧允许，
  见 `src/main/kotlin/org/example/complie/parsing/Parser.kt:1481-1513`：`parseType` 里 `while (check(DOUBLE_COLON))`，
  嵌套名用 `.` 拼进 `TypeRef.name`）。
- 为了不让第 4a 刀被它挡住，探针台里 5 个文件（`probes/a1..a4`、`pkgroot/app/meta/pkgprobe.rsf`）已改成
  **类型推断写法** `var m = new Marker("hi", 3)`（输出与行为完全不变，`run-probes.sh kotlin` 复跑 0 失败）。
- 真正的修法不止语法器：后端还要把嵌套名映射成 JVM 内部名 `Marker$Impl`
  （Kotlin 侧 `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt:557-575` 的 `classInternalName`
  注解特例），AST dump 侧 `typeText` 打印 `Marker.Impl`（Kotlin）也要对齐。
  ⇒ 建议单开一刀（暂称 4c「注解语言面收尾」），**不要塞进 4a/4b**。

## 证据文件

- `nested.rsf`：最小复现（纯类 + `A::B` 类型名）。
- `build/verify21/scratch4a/{a1_noNested,nested,t1,t2,t3}.rsf`：逐项隔离用的临时语料。
