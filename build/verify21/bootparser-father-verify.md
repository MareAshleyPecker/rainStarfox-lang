# 第 1 刀 · 父代理独立复验（不依赖子代理的自证）

工具：`java -jar build/libs/rainStarfox-0.9.0.jar build rsfSrc/bootstrap/ParserCli.rsf -o build/verify21/parsereyeball`，以 `-Drsf.ast.file=<语料>` 直跑；不跑 gradle，避免与子代理争用构建目录。

## 已过的检查（2026-10-15）

| 检查 | 结果 |
| --- | --- |
| 正例 dump 与冻结格式逐行核对（`interface Greeter` / `Empty` / `Box&<T> : Greeter` / `class Impl(x0:int) : Greeter, Box&<int>`） | 全部相符：`INTERFACES` 排最后；CLASS 的 `SUPERS` 在 `TYPE_PARAMS` 之前；`SUPER name= display= typeArgs=`；`override=` 在 `primary=` 之后；抽象方法不打 `BLOCK`、默认方法打 `BLOCK`；`control=DEFAULT` 与 Kotlin `ControlType.DEFAULT` 一致 |
| 10 条负例（`class C : {}` / `: ?Greeter` / `: int` / `: int[]` / `interface I : {}` / 接口字段 / 接口构造器 / 接口 `static fun` / 接口 `private fun` / 顶层 `override fun`） | 全部报 `<解析失败>`，无漏网 |
| 全语料回归 `rsfSrc/lang/**/*.rsf`（15 个） | 15/15 可解析，无回归 |
| 自举源 `rsfSrc/bootstrap/*.rsf`（9 个） | 9/9 可解析（说明 bootstrap 源码里没有真实注解用法） |
| 注解前缀 `@` 在 `rsfSrc` 的真实用法 | 0 处（只在注释/字符串里出现）⇒ 第 3 刀（注解）对现有自举链也是**加法**，与第 1 刀同理 |

## 仍待父代理在子代理交付后跑

`bash build/verify21/knife-verify.sh bootparser-verify`：全量 `cleanTest test jar` + `accept-after`（J1 126 类 / diff 0）+ `verify2`（八示例）；以及 `RsfTest` 两侧 AST 逐字节对拍（含新加的接口语料）。
