# 第 2 刀（Backend.rsf / Interp.rsf 支持接口）探针台

跑法：`bash build/verify21/bootbackend/run-probes.sh kotlin|self|both`（`both` 是正式验收：两套编译器产物逐字节相同 + 解释器与字节码 stdout 逐字节相同 + 负例两边都失败）。

## 正例与期望输出（2026-10-15 用当前 Kotlin 版实测锚定）

| 探针 | 覆盖 | 期望 stdout |
| --- | --- | --- |
| `p1_default_method.rsf` | 类类型接收者调继承来的默认方法；接口类型接收者同调 | `woof!` / `woof!` |
| `p2_default_calls_abstract.rsf` | 默认方法内部调抽象方法（隐式 `this`） | `12` |
| `p3_dispatch.rsf` | 接口类型变量按运行期真实类分派，同一接口两个实现 | `meow` / `moo` |
| `p4_iface_inherit.rsf` | 接口继承接口 + 实现类；默认方法穿过父接口 | `p#7` / `7` |
| `p5_multi_iface.rsf` | 类实现两个 rsf 接口 + `override` 修饰词 | `AB` / `A` |
| `p6_default_dispatch.rsf` | 类覆盖默认方法后，默认方法里的 `v()` 按运行期分派（4 条路径） | `C` / `CB` / `CB` / `C` |

## 负例

- `n1_new_interface.rsf`：`new I()` —— 接口不能被 new，两边都必须失败。
- `n2_ambiguous_default.rsf`：接口 `B : A` 两边都定义**同签名默认方法** `v()`，类 `C : B` 调 `c.v()`。
  当前 Checker 报 `调用 'v' 无法确定用哪个重载：实参类型（）下有 2 个候选并列最低分（0 分）—— 候选有 2 个 —— v()；v()`。
  **这是与 Java/Kotlin 的语义分歧**（那边 `B.v()` 覆盖 `A.v()`，只剩一个候选），本刀不修，只作为「两边行为一致」的负例。

## 环境备注

- Windows 控制台看中文报错：`java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar …`（不加则 GBK 花屏，重定向也救不回）。
- 探针文件名必须能当 Java 类名（不能带 `-`），本目录一律下划线。
- 自举侧调用形式（见 `build/verify17e/verify.sh`）：`java -Drsf.backend.file=<src> -Drsf.backend.out=<out> -cp "<A或B>;$JAR" BackendCli`（类路径分隔符是 Windows 的 `;`，`$JAR` 必须带上，运行期要 ASM）。
