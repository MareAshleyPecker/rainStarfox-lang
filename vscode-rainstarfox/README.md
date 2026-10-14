# rainStarfox for VS Code

rainStarfox 是一门自制玩具语言（源文件 `.rsf`），编译器用 Kotlin 写、跑在 JVM 上。
这个扩展提供编辑器支持：

- **语法高亮**（TextMate 语法，作用域 `source.rsf`）
- **报错诊断**：保存/输入后自动检查，错误和警告画波浪线，悬停看消息
- **代码补全**：关键字、类型、内置函数、本文件与导入文件里的函数（带签名）、
  当前函数的参数、已声明的局部变量；`Math.` 这种成员访问会列出类的方法与字段
- **代码片段**、括号配对、注释切换

## 诊断与补全是怎么来的

扩展**不自己实现分析**，而是调用编译器自己的 CLI：

```
rsf check    <file> --json
rsf complete <file> --line L --col C
```

编译器前端本来就能给出带位置的诊断和带类型的作用域信息，复用它最不容易走样：
语言加了新特性，编辑器这边不用跟着改。

扩展会把**未保存的缓冲区**写成临时文件再交给编译器，所以没保存也能看到报错；
用 `--base` 告诉编译器真实的目录，这样 `import "lib/util.rsf"` 之类的相对路径仍然解析得对。

代价是每次请求要起一次 JVM，所以编辑器侧做了防抖（默认停手 500ms 后跑一次，
可用 `rainstarfox.diagnosticsDelay` 调）。补全是按需触发的，感觉不到延迟。

## 前置条件

扩展需要能找到 rsf 启动器，按这个顺序找：

1. 设置项 `rainstarfox.launcher` 指定的路径
2. 工作区里的 `build/libs/rainStarfox-*.jar`
3. 工作区里的 `build/native/rainStarfox/rainStarfox.exe`

所以在项目里先跑一次 `gradlew jar`（或 `gradlew exe`）就行。

## 安装

```bat
code --install-extension rainstarfox-0.2.0.vsix
```

或者 `Ctrl+Shift+P` → **Extensions: Install from VSIX...**。

### 开发

```bat
npm install
npm run build        # esbuild 打包成 out/extension.js
npm run typecheck
npm run check-grammar # 语法自检（用 vscode-textmate 真的分词一遍）
```

用 VS Code 打开这个文件夹按 `F5` 会启动扩展开发宿主，在里面打开 `.rsf` 文件即可。

## 设置项

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| `rainstarfox.launcher` | 空（自动找） | rsf 启动器（jar 或 exe）的路径 |
| `rainstarfox.javaCommand` | `java` | 启动器是 jar 时用哪个 java |
| `rainstarfox.diagnosticsDelay` | `500` | 停手多少毫秒后检查一次 |

## 已知限制

- 一次只报**一条** error——编译器前端是遇错即停的（修掉之后会看到下一条）。
  warning 是全部给出的。
- 没有跳转定义、悬停看类型、重命名、格式化。
- 补全的作用域过滤偏宽松：同一个函数里在光标**之前**声明过的变量都会给出，
  不做块级作用域的精确过滤（宁可多给几个，也别一个都不给）。
- 未保存的改动只对**当前文件**生效；被 import 的其他文件如果没保存，
  编译器读到的还是磁盘上的版本。

