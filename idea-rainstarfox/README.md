# rainStarfox for IntelliJ IDEA

给 [rainStarfox](../../README.md)（`.rsf`）语言写的 IntelliJ 平台插件：语法高亮、实时诊断、代码补全、一键运行/编译。

装在任何基于 IntelliJ 平台 2025.3（build 253）的 IDE 上都能用——IDEA、CLion、GoLand、PyCharm、RustRover 都可以，插件只依赖 `com.intellij.modules.platform`。

## 它提供什么

| 功能 | 说明 |
|---|---|
| 文件类型 | `.rsf` 有独立的图标、语言 ID、括号配对、`Ctrl+/` 注释 |
| 语法高亮 | 关键字、修饰词、类型名、函数定义/调用、内置函数、字符串、数字、注释；配色可以在 Setting → Editor → Color Scheme → rainStarfox 里逐项改 |
| 实时诊断 | 停止输入约 0.3 秒后让语言服务器检查**编辑器里的当前文本**（不必先保存），错误划红波浪线、警告划黄波浪线、提示划灰线 |
| 代码补全 | 关键字、类型、内置函数、跨文件函数、参数、局部变量、`Math.` 这类 JVM 成员 |
| 运行 / 编译 | 右键或 Run 菜单里执行 `rsf run` / `rsf build`，输出进 Run 控制台，**报错行可点击跳转** |
| 设置 | Setting → Tools → rainStarfox：指定编译器路径、开关实时诊断与补全 |

## 诊断和补全从哪来

走 **LSP**：插件起一个常驻的 `rsf lsp` 进程，通过 stdio 上的 JSON-RPC 说话。

```
rsf lsp        # 语言服务器：initialize / didOpen / didChange / diagnostic / completion
```

这不是「每次起一个 JVM 跑一条命令」：服务器只起一次，之后每次诊断/补全只花一次管道往返（毫秒级）——这才是编辑器里「边打边报」的手感。语言规则仍然只有编译器那一份权威实现，插件不重复写。

插件把**整篇当前文本**发过去（LSP 全量同步），服务器负责算诊断和补全；未保存的缓冲区不需要插件再落临时文件，服务器自己按文档所在目录解 `imp` 的相对路径。

## 插件不打包编译器

`rsf` 有 jar、有原生 exe、还可能装在 PATH 里，硬编码任何一种都是给用户找麻烦。插件按这个顺序找：

1. Setting → Tools → rainStarfox 里手填的路径（`rainStarfox.exe` 或 `.jar` 都行）
2. 项目里的 `build/native/rainStarfox/rainStarfox.exe`（`gradlew exe` 的产物）
3. 项目里的 `build/libs/*.jar`（`gradlew jar` 的产物）
4. PATH 里的 `rainStarfox` / `rsf`

⚠️ jar 模式对 **java 版本**有要求：编译器 jar 按 JDK 25 编译（class 版本 69），IDEA 自带的 JBR 21 跑不了它。要么用原生的 `rainStarfox.exe`（推荐），要么在设置里把 `java` 指到 JDK 25。

找不到时不会静默失败：用到编译器相关功能会弹一次通知，告诉你去哪配。

## 编译与安装

需要 JDK 21+（本插件的编译目标是 21），并且本机装了 IntelliJ IDEA 2025.3。构建脚本用你本机那份 IDEA 当 SDK，**不下载** 1GB 的 IDE 分发包：

```bash
cd idea-rainstarfox
./gradlew buildPlugin        # 产物：build/distributions/idea-rainstarfox-0.1.0.zip
./gradlew test               # 词法器与 JSON 协议的单测
```

路径在 `build.gradle.kts` 的 `intellijPlatform { local(...) }` 里，换机器要改这一行。

安装：Setting → Plugins → ⚙ → Install Plugin from Disk…，选上面那个 zip，重启 IDE。

想直接在沙箱 IDE 里试：

```bash
./gradlew runIde             # 会启动一个装了本插件的 IDEA
```

## 已知边界

- **没有真正的语法树**。解析器是扁平的 token 树，所以 IDEA 的「结构」视图、重命名、跳转定义这类基于 PSI 的重构用不了。高亮、括号配对、注释、诊断、补全都不依赖语法树，所以它们照常工作。要做上面那些功能，得往 `RsfParser` 里填真正的语法分析。
- **一次只报一个错**。编译器前端是遇错即停的，修掉当前这条才能看到下一条。这是编译器前端的既有设计，插件如实反映。
- **补全的作用域过滤偏宽松**：同一个函数里在光标之前声明过的变量都会给出，宁可多给几个也不漏。文件没写完（解析失败）时退化成关键字扫描。
- **`.rsf` 之外的文件不参与**：目前 `import` 进来的其他 JVM 语言（.java/.kt）不会带来补全。

## 代码地图

```
src/main/kotlin/org/rainstarfox/idea/
    RsfLanguage.kt             语言本体
    RsfFileType.kt             .rsf 文件类型与图标
    RsfLexer.kt                词法器（永不抛异常，供高亮与括号配对）
    RsfTokenType.kt            词法单元与关键字表（与编译器 Lexer.kt 对齐）
    RsfParserDefinition.kt     扁平 PSI（故意不做语法树）
    RsfSyntaxHighlighter.kt    词法单元 → 颜色
    RsfTextAttributes.kt       颜色键
    RsfColorSettingsPage.kt    配色预览页
    RsfCommenter.kt            注释
    RsfBraceMatcher.kt         括号配对
    RsfExternalAnnotator.kt    诊断波浪线（后台线程问语言服务器）
    RsfCompletionContributor.kt 补全
    RsfLspClient.kt            LSP 客户端：起 `rsf lsp`、收发 JSON-RPC、连接池
    RsfDiagnostics.kt          诊断对齐（只留本文档 / 去重）
    RsfCli.kt                  跑 run / build 的一次性进程
    RsfJson.kt                 极小的 JSON 读取器（读 LSP 响应）
    RsfLauncher.kt             找编译器
    RsfSettings.kt             设置存储
    RsfConfigurable.kt         设置界面
    RsfRunner.kt               运行/编译 + 控制台
    RsfConsoleFilter.kt        把报错行变成可点击链接
    RsfActions.kt              Run / Build 两个动作
```
