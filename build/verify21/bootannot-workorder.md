### 5.2 非法（**两侧都必须失败**；rsf 报错文本**不要求**与 Kotlin 一致，见 `RsfTest.kt:7169-7237`）

1. 注解体里直接写方法 / 字段 / 构造器：`annotation M { fun f() -> void { rtn } }`、`annotation M { var x : int = 1 }`、`annotation M { constructor() { } }`；
2. 两个伴生实体：`annotation M { companion entity A { } companion entity B { } }`；
3. 元素重名：`annotation M(value : string, value : int) { }`；
4. Java 风格元素：`annotation M(string value) { }`；
5. 默认值不是字面量：`annotation M(v : string = RED) { }`（枚举常量）、数组/注解默认值；
6. `@` 位置不对：`fun @Marker f() -> void { rtn }`；
7. `companion` 少 `entity`；伴生实体名后跟类型关键字（`companion entity int { }`）。

## 6. 交付

- 报告 `build/verify21/bootannot/REPORT.md`（≤60 行）：改了什么、判据 1–5 的**原始输出关键行**、`git diff --stat`、三个（或五个）文件的 sha256；
- 落盘 `git status --porcelain` 只应有 §0 表里的文件；
- 遇到任何「必须改 §0 之外的文件才能过」的情况：**停下来报给父代理**，不要自己扩大范围。
