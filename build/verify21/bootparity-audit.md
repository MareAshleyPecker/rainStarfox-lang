# 自举同步：差距实测清单（2026-10-15）

## 已经绿的部分（自举链验证到的）

- J1/J3：9 个 `rsfSrc/bootstrap/*.rsf` → 126 类，自举产物与 Kotlin 产物 `diff -r` 0 差异。
- J2：4 个 CLI 源的 Kotlin 版 vs 自举版，残差已逐类备案。
- verify2 §1：多文件 `SOURCE_IMPORT` 端到端（类逐字节 + stdout/stderr 一致）。
- verify2 §2：自举解释器跑 8 示例，与 Kotlin 版逐字节一致。

## 8 示例覆盖到的语法

`main / class / generics / errors / stdlib / arrays / files / enums` 里出现了：
switch、enum、for、while、try/fail、错误返回 `!T`、泛型 `&<`、数组、类与字段、static/private 函数、
`imutvar`、`new`、`?:`、`!!`、`ArrayList`、`File`、`System.out`、`System.getProperty`。
