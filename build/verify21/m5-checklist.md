# 阶段 21 M5（验收 / 文档 / 记忆 / 提交）清单

> 父代理自己跑，不派子代理。

## 1 代码验收（三条都要）

| 命令 | 期望 |
|---|---|
| `./gradlew.bat cleanTest test jar` | 全绿、0 fail / 0 err，条数 ≥ M4 交付数 |
| `bash build/verify17e/accept-after.sh` | exit 0；`J1 类总数=126`、`diff -r 差异条目=0`；**失败项合计 0** |
| `bash build/verify17e/verify2.sh` | exit 0；自举解释器八示例 **8/8 一致**（accept-after 不含这段） |

八示例锚点（raw stdout、默认编码，别加 `-Dstdout.encoding=UTF-8`）：main 606/`28208184`、class 295/`D2ECC25E`、
generics 234/`056FD6FE`、errors 371/`E9C8CDB6`、stdlib 948/`8F397D2A`、arrays 1119/`6D1A2875`、files 666/`6A0C940A`、
enums 763/`3A33A504`。

## 2 注解端到端（本阶段新增，必须有）

- 探针台复跑：`bash build/verify21/m2/run-checks.sh`（37 个）、M3 的 `build/verify21/m3/`、M4 的 `build/verify21/m4/`。
- 每个 M4 探针用 `rsf run` 与 `rsf build` + `java` **两条路**跑，stdout 逐字节相同。
- 运行期反射确认：注解接口 `isAnnotation()`、元素默认值往返、`getAnnotation` 能读到（ASM 探针的教训：漏 `@Retention` 会静默 null）。

## 3 文档

- `DESIGN-21.md`：状态行改「M0–M4 完成」；§8 里程碑；新增 `### 9d M3 验收记录` / `### 9e M4 验收记录`；§10 补两条（解释器不建模访问器与 `annotationType()`）。
- `BOOTSTRAP.md`：阶段进度表加阶段 21 一行（M0–M4 + 验收数字）；自举同步那趟的待办保持。
- `README.md`：语言说明补 `annotation` 一节（短句）；路线图加阶段 21。

## 4 记忆

- `memory_log(kind=fact)`：M3/M4 交付与验收数字。
- `memory_note(kind=plan)`：白板「当前进度」卡改成阶段 21 完成。
- `memory_procedure`：若形成可复用流程（ASM 注解已有一条 `proc_pre_b5a0c804296` 在审批列表），按需激活或补条目。

## 5 提交（**先问用户**）

工作树里除了阶段 21 的改动，还混着**用户自己的未提交改动**：`BOOTSTRAP.md`（表格重排）、`rsfSrc/语法预案/mathematic.rsf`、
`interp/Interpreter.kt` 与 `interop/ClassPath.kt`（风格重构）。`BOOTSTRAP.md` 与 `DESIGN-21.md` 是我和用户都改过的文件，**无法按文件切分**。

⇒ 提交前问用户：这一批一起提交，还是只提交阶段 21 相关、把用户改动留在工作树。
按惯例若要提交：`BOOTSTRAP.md`/`README.md`/`DESIGN-21.md` 加阶段 21 说明，tag 建议 `stage21-m2`…`stage21-m5` 或单个 `stage21`。
