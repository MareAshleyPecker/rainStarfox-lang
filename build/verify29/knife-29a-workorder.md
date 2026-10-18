# 阶段 29a 工单：关键字 `fun` → `fn`

日期：2026-10-18
上游设计：`DESIGN-29.md`（词表、兼容策略、判据都在里面，先读它）

## 0 一句话

把 rsf 的函数关键字从 `fun` 改成 `fn`：两个词法器同步、全仓 `.rsf` 与文档示例迁移、旧写法给一句带改写示例的硬报错。

## 1 硬性口径（逐字，不许改写）

- 新关键字：`fn`。词法表条数**仍是 56**（这是改名不是加词），`TokenKind` 名字**仍是 `KW_FUN`**。
- 旧词 `fun` 不再入词法表，由一条独立判断拦下，报：

      'fun' 已改名为 'fn'：请写成 'fn main() { … }'

  Kotlin 侧与 `rsfSrc/bootstrap/Lexer.rsf` 侧**必须一字不差**（对照 `varis` 的既有先例：
  `src/main/kotlin/org/example/complie/lexing/Lexer.kt:11-22` 的 `LEGACY_VAR_KEYWORD` / `LEGACY_VAR_MESSAGE`，
  以及 `readIdentOrKeyword` 里那条判断；自举侧 `rsfSrc/bootstrap/Lexer.rsf` 里也有对应的一段）。
- 不保留别名、不留兼容期。

## 2 允许改 / 禁止改

允许：

- `src/main/kotlin/org/example/complie/lexing/Lexer.kt`、`Token.kt`
- `rsfSrc/bootstrap/Lexer.rsf`
- `rsfSrc/lang/*.rsf`（示例与注释）
- `src/test/**`（**只改字符串字面量里的 rsf 代码**，不许动 Kotlin 自己的 `fun`）
- `idea-rainstarfox/**`（独立词法器 `RsfLexer.kt`、高亮表 `RsfTokenType.kt`、`EditorSupport.kt` 的关键字表若有）
- 全仓 `*.rsf`（含 `build/**` 下的探针台语料；`build/verify29/**` 除外）
- 文档：`README.md` / `BOOTSTRAP.md` / `DESIGN-29.md`

禁止：

- 改任何 `.class` / 冻结金标 `build/verify*/expected/**` / `build/verify*/**/*.sh` 判据脚本
- 改 `build/verify29/**`（那是判据台本身）
- 改 `src/main/kotlin/**` 除了上面两个词法文件
- 改 `rsfSrc/bootstrap/` 除 `Lexer.rsf` 以外的文件（`Parser.rsf` 等在解析层不认词面，若确实有硬编码 `"fun"` 字样才允许改注释）

## 3 迁移脚本规范（重要）

不要用裸 `sed` 全局替换。写一次性脚本（放 `build/verify29/rename.py`，用 Python 3）：

1. 对 `*.rsf`：逐字符扫描，识别三种状态——普通代码、`//` 行注释、`"…"` 字符串（含转义 `\"`）。
   **只在普通代码状态**把独立单词 `fun` 替换成 `fn`（前后是标识符字符就不算独立单词）。
   注释与字符串**原样保留**（避免改掉文案断言里的历史描述）。
2. 对 `*.kt`：同样做状态机，但**只在字符串字面量内部**替换（含 `"""…"""`；Kotlin 的 `fun` 是代码，绝不能碰）。
   字符串里的替换也要求独立单词（`fun main()` → `fn main()`；`"function"`、`"funny"` 不动）。
3. 对 `*.md`：只替换行内代码与围栏代码块里的独立单词 `fun`；叙述文字里如果出现 `'fun'`（带引号）也一并改（那是旧关键字的名字）。
4. 跑完打印：每个后缀改了多少文件、多少处；`grep -rnI '\bfun\b' --include='*.rsf' .` 的残留清单（应为空）。
5. 脚本本身**不要**提交（放 `build/` 下，`build/` 不进 git 的话无所谓；若 `build/` 被忽略就不要 `git add`）。

## 4 逐项 checklist

- [ ] `Lexer.kt`：`put("fun", KW_FUN)` → `put("fn", KW_FUN)`；新增 `LEGACY_FUN_KEYWORD` / `LEGACY_FUN_MESSAGE` 常量（紧挨 `varis` 那两个）与 `readIdentOrKeyword` 里的拦截；文件头注释里「55 项 / 56 项」沿革不动。
- [ ] `Token.kt`：`KW_FUN("fun")` → `KW_FUN("fn")`，文档注释里 `fun` → `fn`。
- [ ] `rsfSrc/bootstrap/Lexer.rsf`：关键字表 `"fun"` → `"fn"`；legacy `fun` 判断与同一句文案；注释里的条数不变。
- [ ] `idea-rainstarfox`：`RsfLexer.kt` 的 `"fun"`、`RsfTokenType.kt` 的 `"fun"`（若有）、`EditorSupport.kt` 关键字表（若有）。
- [ ] 全仓 `*.rsf` 迁移（含 `build/**` 的语料；`rsfSrc/bootstrap/**` 只在 `Lexer.rsf` 里改词表，源码本身不许有 `fun` 声明——如果里面有，说明写法不对，先报告）。
- [ ] `src/test/**` 里字符串内的 rsf 代码迁移；特别检查含 `'fun'` 的旧文案断言，改成新文案。
- [ ] 文档：`README.md`、`BOOTSTRAP.md`、`DESIGN-29.md` 里示例代码改 `fn`。
- [ ] 若 `build/verify17e/**` 下有 `.rsf` 被改（它可能是冻结基线），必须重跑 `build/verify17e/kotlin-baseline.sh` 重新冻结，并在报告里说明。

## 5 判据（必须逐条实跑，把命令尾部输出贴进报告）

1. `./gradlew.bat test` → BUILD SUCCESSFUL，测试数 564 / 0 failed（若有新增测试则按实际数报，失败必须 0）。
2. `bash build/verify21/knife-verify.sh stage29` → 「OK：三条全绿」（J1 148 / diff 0 / verify2 0）。
3. `bash build/verify24/run-probes.sh all`、`bash build/verify24/ast-verify.sh`、`bash build/verify27/run-probes.sh all`、`bash build/verify28/run-probes.sh all`、`bash build/verify21/bootbackend/run-probes.sh both` → 失败项全 0。
4. `bash build/verify29/run-probes.sh all` → 失败项 0（旧 `fun` 必须被拒且文案一致）。
5. `bash build/verify28/bootstrap-parity.sh` → 零差异。

## 6 报告格式

- 改动清单（按目录分组，文件数 + 处数）
- 关键字表条数自证：`grep -c 'put("\|rtn "' ` 不行——用 `grep -c 'TokenKind.KW_' Lexer.kt` 与自举侧 `grep -c 'rtn "KW_'`，两边都应为 56
- 五条判据的实际命令与输出尾部
- 任何你**偏离**本工单的地方（例如某个文件必须额外改），逐条说明原因
- 未解决问题（若有），附最小复现
