# zed-rainstarfox

[Zed](https://zed.dev) 扩展：为 rainStarfox（`.rsf`）提供 tree-sitter 语法高亮和 LSP 支持。

- 语法：`languages/rsf/grammar.js`（附已生成的 `src/parser.c`）
- 查询：`languages/rsf/highlights.scm`、`brackets.scm`、`indents.scm`、`locals.scm`
- LSP：`src/lib.rs` 用 `java -jar <worktree>/build/libs/rainStarfox-0.9.0.jar lsp` 启动编译器自带的语言服务器

```
editor/zed-rainstarfox/
├── extension.toml
├── Cargo.toml
├── src/lib.rs
├── README.md
├── grammars/                                                 # 安装时 Zed 从这里 clone + 编译（已 gitignore）
└── languages/rsf/                                            # 独立的 grammar git 仓库（内嵌 .git）
    ├── config.toml                                           # Zed 语言配置（不进内嵌仓库）
    ├── grammar.js
    ├── tree-sitter.json
    ├── highlights.scm  brackets.scm  indents.scm  locals.scm  # Zed 实际加载的副本
    ├── queries/                                               # 同内容，供 tree-sitter CLI / tree-sitter.json
    └── src/parser.c  src/grammar.json  src/node-types.json    # 已生成，无需本机 tree-sitter
```

## 1. LSP 的真实启动方式

`rsf lsp` 不是端口服务，也不是可配置的 `--stdio`：`src/main/kotlin/org/example/Main.kt` 的
`cmdLsp()` 直接构造 `LanguageServer(System.in, System.out).serve()`，用 stdin/stdout 讲 LSP 协议，没有额外参数。

```powershell
cd D:\java\rainStarfox
java -jar build\libs\rainStarfox-0.9.0.jar lsp
```

- jar 由 `.\gradlew jar` 生成（`build.gradle` 的 `version = 0.9.0`，`mainClass = org.example.MainKt`，胖包）。
- **工作目录必须是工程根**：服务端按相对路径解析 `pkg`/`import`。Zed 启动语言服务器时以 worktree 根为 cwd，
  `src/lib.rs` 也用 `worktree.root_path()` 拼 jar 的绝对路径，因此从工程根打开 Zed 即可。
- 服务端支持：initialize / didOpen / didChange / didClose / diagnostic / completion / definition / hover /
  references / rename（`textDocumentSync` 为 2，增量同步，completion 触发字符 `.`）；不支持格式化和 workspace symbol。
- 升级版本后 jar 名会变（`rainStarfox-<version>.jar`），需同步修改 `src/lib.rs` 里的 `JAR_RELATIVE_PATH`。

手动冒烟测试（不需要 Zed）：往进程 stdin 写一行 `Content-Length` 头的 `initialize` 请求，应收到
`serverInfo = {name: "rainStarfox", version: "0.9.0"}` 的响应。

## 2. 安装为 dev extension

前置：

1. Rust 工具链 + cargo（`cargo --version` 可用）。
2. **wasm 编译目标**（Zed 用 wasm 跑扩展，默认 Rust 安装通常没有）：
   ```powershell
   rustup target add wasm32-wasip1
   ```
   若你的 Zed 版本报 `wasm32-wasip2`，把上面的参数换成 `wasm32-wasip2`。
3. `java` 在 PATH 中，且 `build\libs\rainStarfox-0.9.0.jar` 存在。

安装步骤：

1. 打开 Zed → 命令面板执行 `zed: install dev extension`（或 `Ctrl+Shift+X` 打开 Extensions → 右上角 `Install Dev Extension`）。
2. 选择目录 `D:\java\rainStarfox\editor\zed-rainstarfox`（**不要**选 `languages` 子目录）。
3. 等待编译；首次会从 crates.io 拉取 `zed_extension_api`，离线环境会失败。
4. 打开任意 `.rsf` 文件，状态栏语言应显示 `rainStarfox`；高亮生效。
5. 排查：命令面板 `zed: open log` 看扩展/LSP 报错；LSP 相关日志会带 `rainstarfox-lsp`。

扩展清单里 `[grammars.rsf]` **必须**指向一个 git 仓库 —— Zed 的 `GrammarManifestEntry`
（`crates/extension/src/extension_manifest.rs`）把 `repository` 和 `rev` 都定义为必填字段
（`path` 才是可选的子目录），只写本地目录会直接报 `missing field 'repository'`：

```toml
[grammars.rsf]
repository = "file:///D:/java/rainStarfox/editor/zed-rainstarfox/languages/rsf"
rev = "2e2b4f59cd8f5b69d45622577b8fba51ba17355c"
```

安装时 Zed 会：把 `repository` clone 到本扩展目录下的 `grammars\rsf\`，`git fetch --depth 1 origin <rev>`
+ `git checkout <rev>`，再把该 checkout 根目录（或 `path` 子目录）里的 `src/parser.c` 编译成
`grammars\rsf.wasm`。因此 `languages\rsf` 被初始化成了一个**独立的小 git 仓库**（只跟踪 `grammar.js`、
`tree-sitter.json`、`queries/`、`src/`；`config.toml` 和根目录 4 个 `.scm` 是 Zed 侧的配置，不进这个仓库），
`rev` 钉在当前提交上，整个过程不需要网络。

注意：

- `grammars\` 是 Zed 的 checkout 目录，已写进 `.gitignore`，不要提交。
- 改完语法必须重新提交并更新 `rev`（见第 3 节），否则 Zed 仍编译旧的 `parser.c`。
- 不要把 `languages\rsf` 当普通目录 `git add` 进主仓：它是内嵌 git 仓库，主仓只会得到一个 gitlink，内容不会上传。
- 发布到 GitHub 时推荐把 grammar 单独做成公共仓库（`repository` 指向它、`rev` 用它的提交、不需要 `path`）；
  若坚持放主仓，先删除 `languages\rsf\.git` 让内容变成普通文件再提交，然后写
  `repository = "https://github.com/MareAshleyPecker/rainStarfox-lang"`、
  `rev = "<含 grammar 的提交>"`、`path = "editor/zed-rainstarfox/languages/rsf"`（此时本地 `file://` 安装方式失效）。

## 3. 重新生成 parser（只有改 grammar.js 后才需要）

仓库里已经提交了 `languages/rsf/src/parser.c`，所以装扩展**不需要** tree-sitter CLI。改了 `grammar.js` 后：

```powershell
cd D:\java\rainStarfox\editor\zed-rainstarfox\languages\rsf
tree-sitter generate --abi 14
tree-sitter parse D:\java\rainStarfox\examples\ui_app.rsf

# 必须提交并更新 extension.toml 里的 rev，Zed 才会编译新 parser
git add -A
git commit -m "update rsf grammar"
git rev-parse HEAD
```

把 `git rev-parse HEAD` 的输出填回 `extension.toml` 的 `[grammars.rsf].rev`，再在 Zed 里重新
`zed: install dev extension`（或命令面板 `zed: reload extensions`）。

也可以整仓自检：

```powershell
$files = Get-ChildItem D:\java\rainStarfox\rsfSrc,D:\java\rainStarfox\examples -Recurse -Filter *.rsf
tree-sitter parse --stat $files
```

当前基线：61 个 `.rsf` 文件全部解析成功（100%）。`queries/*.scm` 与根目录的 4 个 `.scm` 内容一致；
**Zed 读的是 `languages/rsf/` 根目录下的 `.scm`**（官方扩展如 kotlin/html 都是这么放的），`queries/`
只是给 tree-sitter CLI 和 `tree-sitter.json` 用的副本，改动时两处都要更新。

## 4. 不用扩展时，只在 settings.json 里配 LSP

先说限制：**纯 settings.json 无法注册新语言/语法**。`lsp` 配置只能覆盖「已经由某个扩展注册过的语言服务器」的启动命令，
所以下面这段的前提是本扩展（或其他提供 `rainStarfox` 语言的扩展）已安装；它适合调试 LSP 或临时换 jar 路径。

`~/.config/zed/settings.json`（Windows 为 `C:\Users\<user>\AppData\Roaming\Zed\settings.json`）：

```json
{
  "lsp": {
    "rainstarfox-lsp": {
      "binary": {
        "path": "java",
        "arguments": [
          "-jar",
          "D:/java/rainStarfox/build/libs/rainStarfox-0.9.0.jar",
          "lsp"
        ]
      }
    }
  },
  "languages": {
    "rainStarfox": {
      "language_servers": ["rainstarfox-lsp"]
    }
  }
}
```

注意 `arguments` 在 `binary` 里（Zed 的 schema 如此），不是与 `binary` 平级。若只想临时禁用扩展提供的命令，
也可以只写 `"lsp": {"rainstarfox-lsp": {"binary": {"path": "java", "arguments": [...]}}}`。

## 5. 已知限制

- 标识符目前只匹配 ASCII（`[A-Za-z_][A-Za-z0-9_]*`）和反引号标签；Unicode 标识符未支持。
- `op` 是软关键字，按普通标识符处理，不做关键字高亮；`default`、`target` 不是关键字。
- 服务端未实现格式化、workspace symbol，扩展也没有对应配置。
- `src/lib.rs` 里 jar 路径固定为 `build/libs/rainStarfox-0.9.0.jar`，版本升级需改常量（或改这里改成扫描目录）。
- 扩展的 wasm 编译未在本机端到端验证（本机 `rustup target list --installed` 只有
  `x86_64-pc-windows-msvc`，执行 `cargo build --target wasm32-wasip1` 前必须先装 target）。
  已验证的部分：`tree-sitter generate --abi 14` 零冲突、61/61 个 `.rsf` 解析通过、4 个 `.scm`
  查询编译通过、3 个 TOML 可被 `tomllib` 解析、`rustfmt --check` 与 `cargo metadata` 通过、
  以及用 `build\tmp_zed\zsim` 完整模拟了 Zed 的 grammar 安装流程（`file://` + `git fetch --depth 1
  origin <rev>` + `git checkout <rev>` 后 checkout 内含 `src/parser.c`）。
- 如果 Zed 日志里 grammar 编译失败，先确认 `extension.toml` 的 `rev` 是 `languages\rsf` 里**已提交**
  的哈希（`git -C languages\rsf cat-file -e <rev>` 应成功），且该提交包含 `src\parser.c`。
