# 28a 工单：标签名的反引号写法 + `@RsfLabels` 元数据（Kotlin 侧）

日期：2026-10-18　依据：[DESIGN-28.md](DESIGN-28.md) §1–§3（已定稿，不许改口径）

## 0 一句话

让 `` `任意字符`: `` 与 `` goto `任意字符` `` 合法（反引号只给标签名用），并给每个**有标签**的方法发一条 runtime-visible 注解 `@RsfLabels(value = string[])`，内容是该方法内标签的**声明顺序**。

## 1 允许改的文件（严格）

- `src/main/kotlin/org/example/complie/lexing/Token.kt`
- `src/main/kotlin/org/example/complie/lexing/Lexer.kt`
- `src/main/kotlin/org/example/complie/parsing/Parser.kt`
- `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt`
- `src/test/kotlin/org/example/LabelMetadataTest.kt`（新建）
- `idea-rainstarfox/src/main/kotlin/org/rainstarfox/idea/RsfLexer.kt`（+ 必要时 `RsfTokenType.kt`）

**禁止**改：`rsfSrc/**`（28b 才动）、`src/main/kotlin/org/example/complie/{sema,ast,lowering,interp,cli,lsp}/**`、其它测试、`build/verify28/**` 的探针与金标（`expected/*.txt` 已冻结）、`build/verify21|24|27/**`、`DESIGN-28.md`、`README.md`/`BOOTSTRAP.md`（28c 收口才写）。

## 2 词法（Token / Lexer）

- 新记号 `LABEL_NAME`，**文本是去掉一对反引号之后的名字**（不含引号）。
- 扫描规则：`Lexer.kt` 的 `next()` 里，遇到 `` ` `` 就扫到**同一行**下一个 `` ` ``；`at` 取反引号那个位置。
- 三种错误（`rsf: ` 前缀沿用现有 `Lexer` 报错格式，位置取反引号处）：
  - 反引号内为空 → 文案必须含 **`标签名不能是空的`**
  - 到行尾都没遇到收尾反引号 → 必须含 **`反引号标签名没有收尾`**
  - 反引号内出现换行按「没有收尾」处理
- 反引号内**允许**：空格、中文、`-` `.` `$` `@` `/` `:` 与任何非反引号、非换行字符。
- 反引号内的名字**不再**做标识符字符检查。

## 3 语法（Parser）

- 语句开头：`IDENT` 或 `LABEL_NAME` 后面紧跟 `COLON` → `LabelStmt(name, at)`（`name` 取 `lexeme`，LABEL_NAME 的 lexeme 已是去引号的名字）。检查顺序放在现有的「`IDENT` + `COLON`」判断里，两种记号都收。
- `goto` 后面：`IDENT` 或 `LABEL_NAME` 都收（`GotoStmt(label, at)`）。
- **其它任何位置**出现 `LABEL_NAME`（表达式里、声明位置等）→ 报错，文案必须含 **`反引号只用来写标签名`**。
- 不加新关键字（`goto` 仍是 `KW_GOTO`）；反引号写法对旧语料零影响。

## 4 编码（BytecodeCompiler）

- 位置：`emitUserMethod` 里，方法体指令发完、`visitMaxs` **之前**。
- 收集：按 `body.instructions` 顺序收集其中所有 `ILabel` 的 `label`（**声明顺序，不去重**——重名已被 Checker 拦住）。空表 → **不发注解**。
- 发射（ASM）：
  ```kotlin
  val av = mv.visitAnnotation("LRsfLabels;", true)   // runtime-visible
  val arr = av.visitArray("value")
  for (name in labelNames) arr.visit(null, name)
  arr.visitEnd()
  av.visitEnd()
  ```
- **不生成** `RsfLabels` 注解接口类（DESIGN-28 §2）。
- 没有标签的方法必须**逐字节**与改动前相同（J1 148 类、J5 九示例锚点不许动）。

## 5 IDEA 插件词法（`RsfLexer.kt`）

- 认反引号标签名：`goto` 后面一个、以及行首 `名字:` 的 `名字` 位置；高亮沿用现有标签/标识符 token 类型即可，**不要求**新颜色。
- 反引号未收尾时不崩、不抛异常（能给出错误高亮更好，不给也行）。
- 不许改动这个模块的其它行为。

## 6 测试（`LabelMetadataTest.kt`）

用 ASM `ClassReader` 读回，至少覆盖：

1. 有标签的方法 → 有 `LRsfLabels;` 注解，`value` 数组 = 声明顺序（例如 `loop`、`出口`）。
2. 没有标签的方法 → **没有**该注解。
3. 两个方法各有标签 → 各自的注解互不串联（同名标签在不同方法里不冲突）。
4. 特殊字符名（`` `a.b$c@d/e` ``、`` `第一步 开始` ``）原样出现在 `value` 里。
5. 注释里写清楚：注解是 runtime-visible、不生成注解类。

## 7 判据（逐条实跑，结果贴报告）

1. `./gradlew.bat test` → BUILD SUCCESSFUL，失败 0（基线 551 + 新增）。
2. `bash build/verify28/run-probes.sh all` → `失败项合计: 0`（含 check / msg / golden / run / javap 五段）。
3. `bash build/verify21/knife-verify.sh stage28a` → 三绿：J1 类总数 148 / diff 0 / verify2 不一致 0。
4. 回归（都不许被我改坏）：
   - `bash build/verify24/run-probes.sh all` → 失败项 0
   - `bash build/verify27/run-probes.sh all` → 不过段数 0
   - `bash build/verify24/bootstrap-parity.sh` → 9 份 0
   - `bash build/verify21/bootbackend/run-probes.sh both` → 失败项 0

## 8 交付要求

- 不许改判据脚本；跑不过就修代码，不许调低断言。
- 报告里给：改动文件清单（含行号）、`gradlew test` 的测试计数、五段判据的原始输出片段、以及「没有标签的方法字节未变」的证据（例如 J1 148 与 J5 九示例的结果）。
- 遇到口径不明（例如某个报错位置该取哪个 token）按 DESIGN-28 §1 的精神自行决定，并在报告里写明决定与理由。
