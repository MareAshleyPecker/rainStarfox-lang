# 自举同步第 1+2 刀联合验收（父代理执行，2026-10-15 23:35）

第 1 刀（`Parser.rsf` 收 interface/override/父类型子句）与第 2 刀（`Backend.rsf`/`Interp.rsf`/`Sources.rsf` 支持接口）
都落地后，由父代理跑**三件套**。判据取自 `knife-verify.sh`（抓正文，不看子脚本退出码）。

命令：`bash build/verify21/knife-verify.sh knife12-joint`（日志 `build/verify21/knife12-joint/`）

| 段 | 内容 | 结果 |
| --- | --- | --- |
| 1/3 | `./gradlew.bat cleanTest test jar --console=plain` | **BUILD SUCCESSFUL**；JUnit XML 汇总 **suites=12 tests=456 failures=0 errors=0 skipped=0** |
| 2/3 | `bash build/verify17e/accept-after.sh` | **失败项合计=0**；**J1 类总数=126**、`diff -r 差异条目=0`（J3 用 B 再编一遍与 J1 逐字节一致） |
| 3/3 | `bash build/verify17e/verify2.sh` | **不一致行=0、失败项合计=0**（自举解释器跑八示例与 Kotlin 版逐字节一致） |

**判决：OK：三条全绿**（退出码 0）。这正是目标里写的联合判据「全量测试 + J1 126 类/diff 0 + verify2 八示例」。

## J1 明细（accept.log 摘抄）

```
A exit=0 classes=30                       ← Kotlin 版编 rsf 后端
Backend exit=0 classes=29      BackendCli exit=0 classes=30
Interp  exit=0 classes=17      InterpCli  exit=0 classes=19
Lexer   exit=0 classes=3       LexerCli   exit=0 classes=4
Parser  exit=0 classes=7       ParserCli  exit=0 classes=8
Sources exit=0 classes=9                  ← 9 源全部 exit=0，合计 126 类
J3：全部 exit=0 classes 同上，diff -r 差异条目=0（逐字节）
```

## 第 2 刀自报的探针台结果（父代理采信，未重复运行）

`bash build/verify21/bootbackend/run-probes.sh both` → `失败项合计: 0`（基线 17 红 → 全绿）：
7 个正例四列（`interp`/`kotlin`/`self`/`rsf-interp`）stdout 全一致且 `类字节差异=0`，3 个负例在 `kotlin_check`/`self_build`/`rsf_interp` 三处都 exit=1。

## 本次一并修掉的判决脚本 bug

`knife-verify.sh` 第 2 段的 `J1 类数` 提取写成 `grep -oE "[0-9]+" | head -1`，
会把行首 **`J1` 里的那个 `1`** 抓出来 ⇒ 类数被读成 1 ⇒ 即使 `失败项合计=0`、真类数 126 也判红。
已改成 `grep -oE "类总数=[0-9]+"` 再取数字。**这是继「假绿」之后同一段脚本的第二个判决 bug**——
教训：判决脚本的每一次提取都要用真日志回放验证过（`KNIFE_SKIP_RUN=1` 就是为这个加的）。
