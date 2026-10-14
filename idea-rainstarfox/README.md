# rainStarfox for IntelliJ IDEA

给 [rainStarfox](../../README.md)（`.rsf`）语言写的 IntelliJ 平台插件：语法高亮、实时诊断、代码补全、一键运行/编译。

装在任何基于 IntelliJ 平台 2025.3（build 253）的 IDE 上都能用——IDEA、CLion、GoLand、PyCharm、RustRover 都可以，插件只依赖 `com.intellij.modules.platform`。

## 它提供什么

| 功能 | 说明 |
|---|---|
| 文件类型 | `.rsf` 有独立的图标、语言 ID、括号配对、`Ctrl+/` 注释 |
| 语法高亮 | 关键字、修饰词、类型名、函数定义/调用、内置函数、字符串、数字、注释；配色可以在 Setting → Editor → Color Scheme → rainStarfox 里逐项改 |
| 实时诊断 | 停止输入约 0.5 秒后调用编译器检查**编辑器里的当前文本**（不必先保存），错误划红波浪线、警告划黄波浪线 |
| 代码补全 | 关键字、类型、内置函数、跨文件函数、参数、局部变量、`Math.` 这类 JVM 成员 |
| 运行 / 编译 | 右键或 Run 菜单里执行 `rsf run` / `rsf build`，输出进 Run 控制台，**报错行可点击跳转** |
| 设置 | Setting → Tools → rainStarfox：指定编译器路径、开关实时诊断与补全 |

## 诊断和补全从哪来

不是插件自己实现的，而是复用编译器自己的命令行：

```
rsf check    <文件> --json                    # 诊断
rsf complete <文件> --line L --col C          # 补全
```

理由很实在：语法规则、作用域、重载解析、import 展开这些逻辑，编译器里已经有一份权威实现，插件再写一遍必然两边走样。代价是每次请求要起一次 JVM（约 200~300 毫秒），所以诊断是**防抖**之后才跑的——你打字的时候它不会每敲一个键就跑一次。

缓冲区没保存时，插件会把当前文本写进临时文件，再用 `--base <真实目录>` 告诉编译器相对目录在哪，这样 `import "lib/greet.rsf"` 依然找得到旁边的文件。

## 插件不打包编译器

`rsf` 有 jar、有原生 exe、还可能装在 PATH 里，硬编码任何一种都是给用户找麻烦。插件按这个顺序找：

1. Setting → Tools → rainStarfox 里手填的路径（`rainStarfox.exe` 或 `.jar` 都行）
2. 项目里的 `build/native/rainStarfox/rainStarfox.exe`（`gradlew exe` 的产物）
3. 项目里的 `build/libs/*.jar`（`gradlew jar` 的产物）
4. PATH 里的 `rainStarfox` / `rsf`

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
    RsfExternalAnnotator.kt    诊断波浪线（后台线程跑编译器）
    RsfCompletionContributor.kt 补全
    RsfCli.kt                  调用编译器的唯一入口
    RsfJson.kt                 极小的 JSON 读取器
    RsfLauncher.kt             找编译器
    RsfSettings.kt             设置存储
    RsfConfigurable.kt         设置界面
    RsfRunner.kt               运行/编译 + 控制台
    RsfConsoleFilter.kt        把报错行变成可点击链接
    RsfActions.kt              Run / Build 两个动作
```
