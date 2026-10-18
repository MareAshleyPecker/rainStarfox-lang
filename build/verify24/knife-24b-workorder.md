# 阶段 24b 工单：自举链的词法与解析同步 `as` / `?as` / `is` / `!is`

## 1 边界

- 只许改：`rsfSrc/bootstrap/Lexer.rsf`、`rsfSrc/bootstrap/Parser.rsf`。
- 不许碰：`src/**`（含测试）、`rsfSrc/bootstrap/{Backend,Interp}.rsf` 与其它自举源、`build/verify24/**`（语料与脚本是判据）、`build/verify21|17e|25/**`、`idea-rainstarfox/**`。**不要 commit**。
- 交付时工作树必须可编译、`./gradlew.bat cleanTest test jar` 全绿。

## 2 要做的

1. **Lexer.rsf**：关键字表 55 → **57**，加 `as` → `KW_AS`、`is` → `KW_IS`（位置对齐 Kotlin 的 `src/main/kotlin/org/example/complie/lexing/Lexer.kt`）；文件里所有「55」的计数注释改 57。
2. **Parser.rsf**
   - `NodeKind` **末尾**追加 `AS, IS`，`kindName` 同步（现有 `:188` 一带）。
   - `parsePostfix()`（现 `:2669`）的 while 里加四支，顺序照 Kotlin `src/main/kotlin/org/example/complie/parsing/Parser.kt:2814-2840`：`KW_AS` / `QUESTION`+`KW_AS` / `KW_IS` / `BANG`+`KW_IS`。**每支解析完立刻 `rtn`**（只允许一层，`x as A as B` 必须拒）。
   - `parseCastTarget(opText)`：复用 `parseTypeRef()`（现 `:1891`）；以 `?` 开头单独报错（目标不许可空）；后面不是类型记号也报错。
   - `expectNoChainedCast(opText)`：目标后面紧跟 `as` / `?as` / `is` / `!is` 就报错。
3. **dump 行必须与 Kotlin 逐字节一致**（`src/test/kotlin/org/example/AstDump.kt:543-553`）：
   - `AS safe=<true|false>` → 子节点 operand → `TARGET type=<类型文本>`
   - `IS negated=<true|false>` → 子节点 operand → `TARGET type=<类型文本>`
   - 类型文本的口径照现有 VAR / NEW 节点是怎么把 `TypeInfo` 转成 dump 文本的（同一套，别另写）。
4. **中间态必须响亮报错**：`Backend.rsf` / `Interp.rsf` 这一刀不认 `AS` / `IS`，必须落到「`17d 后端暂不支持：…`」/「`17c 解释器暂不支持：…`」，**不许静默产错字节码或错值**（用 `build/verify24/probes/p1_cast_ok.rsf` 分别跑自举后端与自举解释器，两端都要 exit≠0）。

## 3 判据（父代理会自己重跑，你不用写自验报告）

- `bash build/verify24/ast-verify.sh` → 正例 5/5 逐字节一致、负例 4/4 两侧都拒。
- `bash build/verify24/run-probes.sh all` → 失败项 0（Kotlin 侧零回归）。
- `bash build/verify21/knife-verify.sh stage24b` → 三条全绿。
- `./gradlew.bat cleanTest test jar` → 547 条全绿（基线不变）。
- 自举后端 / 自举解释器对 `as` 探针 exit≠0。

## 4 陷阱

- rsf 没有 `continue`，循环用 `going` 风格（照现有 `parsePostfix`）。
- Git bash 用绝对路径 `& 'C:\Program Files\Git\bin\bash.exe' -lc '…'`；自举 CLI 的输出是 GBK（`iconv -f CP936 -t UTF-8`），但 `ast-verify.sh` 自己的 stdout 是 UTF-8，**别对脚本输出乱转**。
- `build/verify24/astparity/{out,dumps}` 是脚本产物，可删；`ast-verify.sh` 每次都会用当前 `Parser.rsf` 重建 ParserCli。
- 别把 `as` / `is` 当普通标识符留在 `isModifierKeyword` / 类型关键字里——它们是**后缀运算符关键字**。
