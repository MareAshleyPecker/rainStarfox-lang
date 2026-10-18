# 自举路线（bootstrap plan）

自举 = **用 rsf 写一个 rsf 编译器，然后让它编译它自己**。

现在的编译器（`src/main/kotlin/org/example/`）功能上够用了，但它是用 Kotlin 写的。
要自举，缺的不是语法糖，而是「写编译器本身」这件事需要的那几样基础设施：**表示数据**、
**装一列数据**、**出错时能撑住**、**把字节写出去**。下面按这个顺序排。

---

## 现状核对（2026-10-08 逐项对着代码查过）

| 需要的能力             | 现状            | 证据                                                                                                                                                                         |
|-------------------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 读源文件              | ✅ 可用          | `import java.nio.file.Files` + `Paths.get` 走静态方法互操作                                                                                                                        |
| 字符串处理             | ✅ 可用          | `string` 的实例方法直接调 JVM（`length`/`substring`/`charAt`…）                                                                                                                      |
| 递归下降 / 优先级爬升      | ✅ 够用          | 函数、递归、`if`/`else`/`while`/`for`/`goto` 都在                                                                                                                                  |
| 表示 Token / AST 节点 | ✅ 可用          | 阶段 12：`class` + `new`（`new` 还可以省）+ 字段/方法/构造器；类的类型复用 `RsfType.external(类名)`，**没有** `RsfBase.CLASS`（`sema/RsfType.kt`）                                                       |
| `new` 出对象         | ✅ 可用          | `new` 是关键字（阶段 19 起可省）；用户类走解释器对象模型 / `NEW`+`INVOKESPECIAL`，JVM 类走 `ClassPath` 的构造器解析                                                                                        |
| 装一列 Token / 子节点   | ✅ 可用（阶段 13）   | 数组 `T[]`：`new int[n]`、`a[i]`、`a.length`；两端逐字节一致（`rsfSrc/lang/arrays.rsf`）                                                                                                  |
| 报错后继续             | ✅ 可用          | 阶段 14：`fail` / `!T` / `try`+`catch`，漏处理编译期就报错                                                                                                                              |
| 写出 `.class`       | ✅ 通了（16b 已完成） | `byte[]` 接得住 `ClassWriter.toByteArray()`，四个文件函数（`readText`/`writeText`/`readBytes`/`writeBytes`）把它交给 `java.nio.file`；互操作也认数组签名（`[B` → `byte[]`）。示例：`rsfSrc/lang/files.rsf` |

---

## 阶段 12：用户自定义类 + `new`

**解除的卡点**：Token / AST 节点没地方放、对象 new 不出来。

*语法（**需要你拍板**，下面是提议）*

```
public class Point(x : int, y : int) {      // 主构造写在类头（阶段 18 起的写法）
    var x0 : int = x                    // 字段：var 名字 : 类型 = 初值
    var y0 : int = y

    public fn sum() -> int {
        rtn x0 + y0
    }
}

fn main() -> void {
    var p = new Point(1, 2)
    println(p.sum())
    p.x0 = 10
    rtn
}
```

新增关键字：`class`、`new`、`this`（阶段 18 又加了 `constructor`）。新增节点：
`ClassDecl`、`FieldDecl`、`NewExpr`、`ThisExpr`（阶段 18 加了 `ThisCallStmt`）。

*要改的地方*

| 文件                            | 改什么                                                                                             |
|-------------------------------|-------------------------------------------------------------------------------------------------|
| `lexing/Lexer.kt`             | 加 `class` / `new` / `this` 三个关键字                                                                |
| `ast/Ast.kt`                  | `ClassDecl`、`FieldDecl`、`NewExpr`、`ThisExpr`；`Program` 增加 `classes`                             |
| `parsing/Parser.kt`           | 顶层 `class`；成员声明（字段 / 方法 / 构造器）；`new 类名(实参)`；`this.x`                                            |
| `sema/RsfType.kt`             | 新增 `RsfBase.CLASS`（内部类名走 `externalName` 那一栏），描述符 `L<内部名>;`                                      |
| `sema/Checker.kt`             | 类符号表、字段/方法解析、构造器与 `new` 的实参匹配、`this` 的类型                                                        |
| `ir/Lowering.kt`              | 字段访问降级成「对象槽 + 字段序号」；`new` 降级成分配对象                                                               |
| `interp/Interpreter.kt`       | 对象模型：字段值放一条按声明顺序排的数组，类名 + 该数组 = 一个对象                                                            |
| `codegen/BytecodeCompiler.kt` | 为每个类生成 `<init>`、字段、方法；`new` → `NEW` + `DUP` + `INVOKESPECIAL <init>`；字段 → `GETFIELD`/`PUTFIELD` |
| `interop/ClassPath.kt`        | 不再跳过 `<init>`（外部类的构造器要能被 `new` 调用）                                                              |

*验收*

- 新增测试：字段读写、构造器、实例方法、`new` 嵌在表达式里
- **解释器与字节码两端输出逐字节一致**（老规矩，这条最重要——对象模型是两端最容易分歧的地方）
- `rsf build` 出来的 `.class` 能用 `java` 直接跑

*风险*：解释器的对象模型必须和 JVM 对齐（字段默认值、`this` 语义、引用相等）。这是整个自举里最容易埋雷的一步。

---

## 阶段 13：数组类型 —— ✅ 已完成（2026-09-24）

**解除的卡点**：存不下一列 Token / 一列子节点。

*语法（C 风格后缀，已定案）* —— `var a : int[] = new int[5]`、`a[0] = 1`、`a.length`。
类型后缀 `[]` 出现在所有类型位置（局部变量、字段、参数、返回类型、泛型实参
`Box&<int[]>`）；`a.length` 是**属性**（不是 `a.length()`）。

*实际改的地方*

| 文件                             | 改什么                                                                                                                                                              |
|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `lexing/Token.kt` + `Lexer.kt` | 新增 `LBRACKET` / `RBRACKET`（`[` `]` 原来在编译器词法里**根本没有**，只有 IDE 那边的 lexer 有）                                                                                         |
| `ast/Ast.kt`                   | `TypeRef.arrayDepth`；新节点 `NewArrayExpr`（`new T[n]`/`new T[n][m]`）、`IndexExpr`（`a[i]`）、`IndexAssignStmt`（`a[i] = v`）                                              |
| `parsing/Parser.kt`            | `parseTypeRef` 吃 `[]` 后缀（可叠多层）；`parsePrimary` 的 `new` 分支分流「对象 / 数组」；`parsePostfix` 加下标；语句位置的下标赋值（和成员赋值同一套收尾逻辑）                                                   |
| `sema/RsfType.kt`              | 类型加 `arrayDepth: Int`（**不是**递归的元素类型，也不加 `RsfBase` 枚举值）；描述符 = `"[".repeat(depth) + 元素描述符`                                                                         |
| `sema/Checker.kt`              | 下标必须是 `int`；`new T[n]` 的长度必须是 int；元素的赋值/传参/返回检查；`.length` 只有数组才有；数组不能直接打印/拼字符串（身份哈希两端没法一致）                                                                       |
| `sema/Builtins.kt`             | `ArrayRuntime`：越界 / 负长度的消息格式与两个辅助方法名（**两端唯一的真源**）                                                                                                                |
| `ir/Lowering.kt`               | 新增 `IIndexAssign`（读/建/长度都在表达式里，不需要新指令）                                                                                                                           |
| `codegen/BytecodeCompiler.kt`  | `NEWARRAY` / `ANEWARRAY` / `MULTIANEWARRAY`、`*ALOAD` / `*ASTORE`（byte→`BALOAD`、char→`CALOAD`）、`ARRAYLENGTH`、程序类上的 `__rsf_arrayIndex$rt` / `__rsf_arrayLength$rt` |
| `interp/Interpreter.kt`        | 数组直接用 `java.lang.reflect.Array`（默认值/边界/引用语义天然与 JVM 一致）                                                                                                           |

*验收*：创建 / 下标读写 / `length` / 默认值、传参与返回的引用语义、用户类数组与泛型类数组、
数组当字段、嵌套数组、越界（读 / 写 / 负数下标 / 负长度）——**8 条新测试，两端逐字节一致**；
`rsfSrc/lang/arrays.rsf` 是完整示例。

*实现口径（和原计划的差异）*

- **数组用 `arrayDepth` 表示**，不是「元素类型」的递归结构：JVM 的数组类型本来就完全擦除，
  `int[][]` 就是 `[[I`，「套了几层 `[]`」已经是全部信息；`RsfBase` 不加 `ARRAY`（加了的话
  全项目几十个 `when (base)` 都要改，而数组对 `rank` / `isArithmetic` 这些问题根本没有答案）。
- **越界两端都做显式检查**，不走 JVM 自己的 `ArrayIndexOutOfBoundsException`（消息是
  `Index 5 out of bounds for length 3`，和解释器那套对不上）。辅助方法而不是内联 try：
  内联的异常处理器进栈时只剩异常对象，表达式中间的操作数栈还原不了（同 `parseInt`）。
- **不做**：数组字面量 `[1,2,3]`、切片、`for (x in arr)`、锯齿数组 `new int[3][]`、
  元素可空性（`?T[]` 直接报错）、数组直接打印/拼字符串。数组**可以**赋给 `Object`
  （JVM 上就是引用，两端一致），但 `Object` 不能直接当数组用（没有强制转换语法）。

---

## 阶段 14：`!T` 错误返回 + `try` / `catch` —— ✅ 已完成

**解除的卡点**：编译器碰到坏输入要能往回走。现在遇到错误只能一路回传特殊值，或者干脆崩。

*建议的实现口径*：`!T` 在 JVM 上**编译成「正常返回 T，出错抛异常」**（两端天然一致，解释器按同样的规则抛）。`try`/`catch` 兜住它。

*语法（**需要你拍板**）*

```
fn int! parseInt(string s) {
    if (s.length() == 0) { fail "空字符串" }     // 或者 throw
    rtn 42
}

fn main() -> void {
    try {
        var n = parseInt("abc")!!    // !! 在 !T 语境下 = 出错就抛出
        println(n)
    } catch (e : string) {
        println("出错了: " + e)
    }
    rtn
}
```

> 上面那段是当时的**提案**（`fn int!` 那种签名最后没采纳，落地的写法是 `fn f() -> !T`）。
> 阶段 19b 起 `catch` 的类型标注也统一成 Kotlin 风格 `catch (e : string)`，老写法
> `catch (e string)` 仍然接受——所以这一行现在是两种都能写。

*要改的地方*

| 文件                            | 改什么                                                                  |
|-------------------------------|----------------------------------------------------------------------|
| `parsing/Parser.kt`           | `try` / `catch` 语句；`fail` 或 `throw` 表达式（`ReturnSpec.mayError` 已经解析了） |
| `sema/Checker.kt`             | `!T` 的传播规则：调用 `!T` 函数必须显式处理（用 `!!`、`?:`、或者放进 `try`）；没处理直接编译报错        |
| `ir/Lowering.kt`              | 每个可能出错的点降级成「抛出分支 + 正常分支」；记录 try 区间                                   |
| `codegen/BytecodeCompiler.kt` | `visitTryCatchBlock` + `ATHROW`；异常类型用 rsf 自己的一个类                     |
| `interp/Interpreter.kt`       | 解释器里的异常帧（不需要 JVM 反射异常，保持一致即可）                                        |

*验收*：错误跨函数传播、`try` 兜得住、漏处理**编译期**就报错（这条是给自举用的关键：自举后的编译器要靠它把「语法错误」变回正常控制流）。

---

## 阶段 15：泛型（可选，看走哪条路）

**这条是分岔口。**

- **走数组路线**（阶段 13 已完成）：不需要泛型。编译器里用 `Token[]`、`Node[]` 这种就够了，代价是要自己手写「动态数组扩容」。
- **走集合互操作路线**：需要泛型。因为 `java.util.ArrayList.get()` 返回 `Object`，rsf 现在**没有顶层类型能接住它**
  ，所以不实现泛型就用不了 JVM 集合。

若做，建议按 JVM 擦除语义来（跟 Java 互通，`List<Token>` 走 `List`），边界插入检查、取值插强转。单态化（每种实参类型生成一份特化）能让解释器更简单，但会让两端行为分叉，不建议。

---

## 阶段 16：标准库切片 + `byte[]`

**解除的卡点**：把编译器生成的东西写进文件。

**16a（已完成）——`byte` / `char` + 标准库切片**：

- `byte` / `char` 基础类型（JVM 描述符 `B` / `C`，各占 1 格槽；`?byte` / `?char` 装箱）
- 字符字面量 `'a'` / `'\n'` / `'\u0041'`；隐式转换边界写在 `RsfType.kt` 的加宽表里
- 内置函数：`toByte` / `toChar` / `charToInt` / `intToString` / `parseInt`（`!int`）/
  `isDigit` / `isLetter` / `isWhitespace` / `toUpper` / `toLower`
- 字符串切片走 String 自己的方法：`length()` / `charAt(int) -> char` / `substring(int, int)`

**16b（✅ 已完成 2026-10-08）——`byte[]` 与文件落盘**：

- `byte[]` 能声明、能传递、能原样交给 `Files.write(Path, byte[])` ✅
- 数组本身早就有（阶段 13）：`var raw : byte[] = new byte[16]`、`raw[i] = toByte(200)`、
  `raw.length` 两端都通（`rsfSrc/lang/arrays.rsf` 里有）✅
- **互操作认数组签名**：`ClassPath.typeFromDescriptor` 先数 `[` 前缀，
  `[B` → `byte[]`、`[[Ljava/lang/String;` → `string[][]`、`[LPoint;` → `Point[]` ✅
- **四个文件函数做成内置**（`sema/Builtins.kt` 的 `FileRuntime`，两端唯一真源）：
  `readText(string) -> !string`、`writeText(string, string) -> !void`、
  `readBytes(string) -> !byte[]`、`writeBytes(string, byte[]) -> !void`。
  失败消息只有一处定义，解释器抛 rsf 错误、编译产物抛 `RuntimeException`，两边一字不差；
  字节码端**手写 JDK 调用序列**（`Paths.get` 传空 `String[]`、`Files.write` 传空 `OpenOption[]`）
  绕开 varargs 解析，而不是内联 try/catch（内联的话异常处理器一进栈就没法还原操作数栈，生成出来的类会 VerifyError 报废） ✅
- **示例**：`rsfSrc/lang/files.rsf`（读文本 / 写文本 / 写二进制再读回来 / 故意失败后用 `try` 接住 /
  `?:` 给兜底值；**只在临时目录里读写，不给仓库留文件**） ✅
- **测试**：文件读写与失败处理两端逐字节一致（`try` 与 `?:` 两条路都覆盖） ✅

**没有这步，自举的最后一公里走不通**：ASM 的 `ClassWriter.toByteArray()` 返回 `byte[]`，
rsf 里必须接得住才能落盘成 `.class`。这一步做完，落盘那条路是通的。

---

## 阶段 17：闭环（分五小步，每步都能对拍）——17a ✅ / 17b ✅ / 17c ✅ / 17d ✅ 已完成

用 rsf 重写编译器，**按前端到后端的顺序一块块替换**，每块都能和 Kotlin 版对拍：

| 小步  | 写什么        | 对拍方式                                                                                                                                | 状态                                                                                                                                                                                  |
|-----|------------|-------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 17a | rsf 版词法器   | 同一个 `.rsf` 输入，token 流（类型 + 文本 + 行列）与 Kotlin 版逐条相同                                                                                   | ✅ 2026-10-08                                                                                                                                                                        |
| 17b | rsf 版语法器   | AST 打印成文本，两边逐行相同                                                                                                                    | ✅ 2026-10-08                                                                                                                                                                        |
| 17c | rsf 版解释器   | 跑 `rsfSrc/lang/` 下所有示例，stdout 逐字节相同                                                                                                 | ✅ 2026-10-08                                                                                                                                                                        |
| 17d | rsf 版字节码后端 | 生成 `.class`，反汇编（`rsf build --dump`）逐行相同                                                                                             | ✅ 2026-10-13（**做到字节级相同**，见「17d 实际做法」）                                                                                                                                               |
| 17e | **三阶段自举**  | ① Kotlin 版编译 rsf 版编译器 → 编译器 A；② A 编译 rsf 版源码 → 编译器 B；③ B 编译自己 → C；要求 **B 与 C 逐字节一致**，且 A、B 对 `rsfSrc/lang/main.rsf` 的输出与 Kotlin 版一致 | ✅ **闭环达成 2026-10-13**（B ≡ C：126 个 `.class` 逐字节相同）；三处已知缺口 2026-10-14 补齐（`pkg` 声明、`module "目录"` 导入、`?:` 左边的成员/隐式调用）；**J2「与 Kotlin 产物逐字节相同」2026-10-14 全同**（次构造器的 `LineNumberTable` 补齐） |

### 17a 实际做法（2026-10-08）

**写了什么**：`rsfSrc/bootstrap/Lexer.rsf`（955 行，含注释）——**纯库**，没有 `main`：

- `class Token(kind, text, line, col)`：主构造 + `kindName()` / `literalType()` /
  `literalValue()` / `hasLiteral()`（`literal` 值不存，dump 时按 kind 与文本形状还原）
- `public class Lexer(source)`：主构造 + 次构造 `Lexer(source, safeMode)`，
  字段 `pos / line / column / out / outCount / failed / failLine / failCol / failMessage`；
  内部方法分四类：字符级（`advance` / `peekAt` / `atEnd`）、空白与注释（`skipSpace` /
  `skipBlockComment`）、各 token（`readIdentOrKeyword` / `readNumber` / `readString` /
  `readCharLiteral` / `readCharEscape` / `readCharUnicodeEscape` / `readPunct`）、主循环 `run`
- 顶部模块级纯函数：字符分类（`isTriviaChar` / `isIdentStart` / `isIdentPart` /
  `isAsciiDigit` / `isAsciiLetter` / `isHexDigit` / `hexDigitValue`）、`keywordKind`
  （42 条关键字，与 Kotlin 版 `KEYWORDS` 逐条对应）、`punctKind`、`stripUnderscore`、
  `numberSuffix`；dump 侧 `padLeft` / `padRight` / `dumpToken` / `dumpTokens` /
  `charLiteralValue`
- `TokenKind` 是一串 `public static fn xxx() -> string` 常量（rsf 还没有 `enum`）
- 对外：`lex(source) -> Token[]`、`lexSafe(source) -> Token[]`（容错版，错误落成
  `ILLEGAL` token）、`lexError(source) -> string`、`lexFile(path) -> !Token[]`

`rsfSrc/bootstrap/LexerCli.rsf` 是**只用于跑起来看的入口**（40 行）：读
`System.getProperty("rsf.lex.file")`，没给就 dump `rsfSrc/bootstrap/Lexer.rsf`
（相对路径，不写死绝对路径），调 `dumpTokens` 打印。**故意不放进 Lexer.rsf**：
rsf 一个程序里只能有一个 `main`（重名报「重复定义」），库带 `main` 就没人能 `import` 它了。

**对拍方式**：rsf 版的 `dumpToken` **逐字复刻** Kotlin 版 `cmdLex`（Main.kt）的格式——

```
行号右对齐宽 4 + ":" + 列号左对齐宽 4 + " " + kind 左对齐宽 12 + " '" + 文本 + "'"
有值的字面量再跟 "  = 值 (" + 类型名 + ")"
```

`rsf lex` 的输出格式**一个字都没动**（只在 rsf 侧复刻它）。对拍命令：

```bat
java -jar build\libs\rainStarfox-0.9.0.jar lex rsfSrc/lang/arrays.rsf > kt.txt
java -jar build\libs\rainStarfox-0.9.0.jar build rsfSrc/bootstrap/LexerCli.rsf -o build\bootstrap
java -Drsf.lex.file=rsfSrc/lang/arrays.rsf -cp "build\bootstrap;build\libs\rainStarfox-0.9.0.jar" LexerCli > rsf.txt
fc kt.txt rsf.txt
```

覆盖输入：`rsfSrc/lang/**` 下**全部 16 个 `.rsf`**（7 个示例 + `pt.rsf`（空文件）+
`interop/` 与 `multifile/` 两个子目录），单个文件最多 4821 行 token（就是词法器自己的源码）；
另加一条刁钻写法的合成源码（char 转义 / `\uXXXX` / 跨行字符串 / 行注释与块注释 / `1..5` /
`1.5f` / `2.5D` / `9000000000L` / `1_000_000` / `&<int>` / `?:` / `->`）。

**对拍里的一个坑**（踩过，记下来）：rsf 侧是 `println`，Windows 上是 CRLF；测试里拼出的
参照行用的是 `\n`。所以
① 比的是「每一行的内容」，行尾统一成 LF；
② **不能按行比**——`'\n'` 这种字符字面量的「值」、跨行字符串的值里就是真的换行符，
一条 token 的 dump 会横跨好几个物理行，两边一旦在哪一处错开半行，按行比会报出一个假的
「差一个空格」（实测在第 18 条报了半小时）。测试里比的是「所有 token 的 dump 拼起来」
这一个字符串，出错时再按字符偏移换算行号。

**结果**：逐条一致（编译产物与解释执行两条路都验过）。四处曾经不一致、最后按「以 Kotlin
版为准」改的是：
① `true`/`false` 的 literal 尾巴少打类型名（应是 `(Boolean)`）；
② 数字字面量的值是**去掉后缀**的（`9000000000l` 的 `literal` 是 Long `9000000000`）；
③ Float / Double 的值还要按 JVM 的显示规则补 `.0`（`7d` 的值是 `7.0` 不是 `7`）；
④ 空字符字面量 `''` 的出错位置差一列（Kotlin 是吃掉开引号之后才报，所以是 `1:20` 不是 `1:19`）。

②③ 这两条说明一件事：**`rsf lex` 打出来的 `= 值 (类型)` 尾巴依赖 Kotlin 版的
「值怎么算、怎么显示」，而 rsf 版是「按文本形状猜出来的」**（不真算值）。
只要碰上有多种写法的字面量（后缀、下划线、`.0`），猜法就得一条条对齐。
真要把这条尾巴也做成「算出来的」，rsf 侧得有 int / long / float / double 的解析与
`toString` 规则——那是 17c（解释器）的事，17a 阶段先用「按形状还原」顶住，并把
边界写进测试。

**两端本来就不同的边界**（照实钉在测试里，不假装一致）：

| 输入                           | Kotlin 版                    | rsf 版                                                                      |
|------------------------------|-----------------------------|----------------------------------------------------------------------------|
| 未闭合字符串                       | 抛 `RsfError`（1:20）          | `lex()` 返回空数组 + `lexError()` 给同一条消息；`lexSafe()` 落一个 `ILLEGAL`（位置同为 1:20）后停 |
| 整数字面量超 int 范围（`99999999999`） | 词法阶段按 int 解析 → 报「超出 int 范围」 | 不算值（只按形状切），照常给 `INT_LIT`                                                   |
| 块注释没闭合                       | 抛 `RsfError`                | 同「未闭合字符串」：错误位置指向 `/*`                                                      |

**测试**（`RsfTest.kt`，5 条）：`rsf 版词法器与 Kotlin 版 token 流逐条一致`、
`rsf 版词法器能处理字面量与注释的刁钻写法`、`rsf 版词法器覆盖关键字别名与各种边角形态`、
`rsf 版词法器遇到非法输入的行为被钉住`、
`rsf 版词法器的入口读不到系统属性时会兜底`（`System.getProperty` 在 rsf 里声明成
`string` 但可能是 null，直接调方法会在编译产物上 NPE——用 `?string` + `?:` 接住）。
测试用「把源码写进临时文件 → rsf 侧 `readText` 读进来切」的方式喂输入，避免拼接字符串
字面量时转义出错导致两边比的不是同一份输入。

**写的时候正好暴露的缺口**（17b 之前值得先补的，详见下面「顺手该补的小项」）：
没有 `&&` / `||`（嵌套 `if` 硬写，词法器的判断被撑得很长）、没有 `enum`（token kind 只能
用字符串常量，拼错要等对拍才发现）、没有 `switch` / `when`（token 分派是一长串 `if`）、
`main` 收不到命令行参数（用系统属性绕）、没有方法重载（`add` 只能写两个名字）、
字符串拼接链长了以后括号层数很难看。

> **2026-10-08 更新（阶段 19c-c 已完成）**：上面前三条已经补掉了——
> `&&` / `||`（真短路）、`enum`（编译成普通类 + `<clinit>` 单例）、
> `switch`（只对枚举、`else` 当默认分支、无 else 时编译期查穷尽）。
> 见 README 的「20：为 17b 补的三样」一节。**下面的排序与清单保留原样**，
> 当作当时的第一手记录，不再逐条划掉。
>
> **2026-10-11 更新（阶段 19d）：方法重载也补掉了。** 顶层函数与用户类方法现在都能重载
> （见下表 19d 行与「阶段 19d」一节）。但 `rsfSrc/bootstrap/*.rsf` 里那些绕开重载的写法
> （`matchAny2` / `matchAny3` / `matchAny4`、`Env.find` / `Env.lookup` / `Env.assign`
> 各自一个名字）**刻意保持原样**：它们已被自举差分测试逐字节钉住，改写法会牵动那些
> 已验证的输出。重载能力只对**新写的** rsf 代码开放。

*验收脚本*：`tools/bootstrap-check.ps1` 目前**还不存在**（上面那些命令是手跑的口令）；
真要做成脚本，注意别用 PowerShell 文本 cmdlet 碰含中文的文件。

### 17b 实际做法（2026-10-08）

**第 0 步：给 17a 的词法器补上阶段 19c 的三样。** `rsfSrc/bootstrap/Lexer.rsf` 是 17a 的
冻结产物，关键字表停在 42 项、记号表没有 `&&` / `||`。17b 补了：

| 补什么                        | 改哪                                                            | 结果            |
|----------------------------|---------------------------------------------------------------|---------------|
| `enum` / `switch` / `case` | `keywordKind` 加三行（表 42 → **45** 项，与 Kotlin 版 `KEYWORDS` 逐条对齐） | 不再是标识符        |
| `&&`                       | `readPunct` 里先看第二个字符再决定 `ANDAND` / `AMP`                      | 一个记号，不是两个 `&` |
| `                          |                                                               | `             | 同上；**孤零零一个 `|`** 报「逻辑或要写成两个竖线 '||'（rsf 里没有按位或，单独一个 '|' 不是运算符）」——文案与 Kotlin 版**逐字相同** | 一个记号 + 一致的失败 |

补完之后差分对拍**不再分流任何样例**：原来那条 `usesPhase20Syntax` 筛子整套删掉，
`rsfSrc/lang/**` 下全部 `.rsf`（含 `enums.rsf`）都进覆盖；原来那条「还不认识新记号」的
测试**翻转**成 `rsf 版词法器已经认识阶段 19c 的新记号并与 Kotlin 版一致`（同样三件事反过来钉）。
另加一条 `rsf 版词法器与 Kotlin 版的关键字表与记号表逐条对齐`：45 个关键字 + 31 个标点
**全表**过一遍——漏一个关键字的表现是「悄悄变成标识符」，抽查抓不住，只有全表才行。
`pkg` / `constructor` / `this` / `static` / 三档权限 / `byte` / `char` / `&<` / `[]`
逐条核对过，17a 时已经覆盖，不用补。

**写了什么**：`rsfSrc/bootstrap/Parser.rsf`（**1919 行**，含注释；非空非注释的代码 1516 行）——**纯库**，没有 `main`。
同目录的 `ParserCli.rsf`（62 行）是只用于跑起来看的入口，读
`System.getProperty("rsf.ast.file")`。

**AST —— 胖节点**（rsf 现在没有继承/接口，写不出多态节点层次）：

```
public enum NodeKind { PROGRAM, FUNC, PARAM, BLOCK, VAR_DECL, ..., ERROR }   // 64 个值
public class Node(kind : NodeKind, text : string)
    kind      哪一种节点
    text      这一层的**属性串**，形如 "name=f mutable=true"（字段之间正好一个空格）
    children  子节点数组（add 追加，满 8 个翻倍）
```

遍历一律 `switch (node.kind)`。`kindName(k)` 是一个**没有 `else`** 的 switch，必须穷尽
`NodeKind` 的每一个值——漏一个当场报「switch 没有 else 分支，就必须覆盖 NodeKind 的
每一个值；缺了：ERROR」（实测过）。这是胖节点设计里唯一还留着的一点类型安全，
也正好把 19c 的 `switch` 用到了刀刃上。

**children 的约定**（dump 里靠显式列表节点表达，不靠缩进猜）：

| 节点                                         | children 顺序                                                  |
|--------------------------------------------|--------------------------------------------------------------|
| `PROGRAM`                                  | `PKG`（有 `pkg` 才有）/ `IMPORTS` / `FUNCS` / `CLASSES` / `ENUMS` |
| `FUNC`                                     | `PARAMS`（`PARAM…`）/ `BLOCK`                                  |
| `CLASS`                                    | `TYPE_PARAMS` / `FIELDS` / `METHODS`（主构造排在最前，与 Kotlin 版一致）   |
| `IF`                                       | 条件 / 体 `BLOCK` / else 体（有才加）                                 |
| `WHILE`                                    | 条件 / 体 `BLOCK`                                               |
| `FOR`                                      | 条件（有才加）/ 更新（有才加）/ 体 `BLOCK`                                  |
| `SWITCH`                                   | 主体 / `CASES`（`CASE…`）/ else 体（有才加）                           |
| `CASE`                                     | 体 `BLOCK`                                                    |
| `TRY`                                      | try 体 / catch 体                                              |
| `MEMBER`                                   | 接收者 / `ARGS`（只有方法调用才有）                                       |
| `INDEX` / `BINARY` / `ELVIS` / `IF_EXPR` … | 按源码里的左右/前后顺序                                                 |

`TypeRef` / `ReturnSpec` **内联成属性**，不单独成节点（它们没有孩子）：`type=Box&<int>[]`、
`ret=ARROW mayError=true retType=!int`。注意 `type` 的写法与 `TypeRef.display()` **不同**
（那个逗号后面带空格，是打给人看的；dump 的值里不能有空格）。

**解糖：做，而且照着 Kotlin 版做。** `for (i in a..b step n)` 在 Kotlin 版里是**解析期**
解糖成 `{ imutvar __rsf_forEnd<k> = b; imutvar __rsf_forStep<k> = n; imutvar i = a;
for (步长 >= 0 ? i <= 终点 : i >= 终点 ; i = i + 步长) 循环体 }`，rsf 版连
「计数器在解析完 body 之后自增」这个顺序都复刻了（所以嵌套时内层的编号更小）。
理由：Kotlin 版手里的树**已经是解糖后的样子**，原始写法（区间、`step`）在树上根本不存在；
rsf 版若保留一个「FOR_IN」原始节点，两边永远比不平——除非在 dump 里把解糖后的树
反解糖回源码形状，那是自欺欺人。

**对拍方式**（本轮最关键的设计）：

| 侧        | 产出                      | 在哪                                                          |
|----------|-------------------------|-------------------------------------------------------------|
| Kotlin 版 | `AstDump.dump(program)` | `src/test/kotlin/org/example/AstDump.kt`（**测试源集**，不挂任何 CLI） |
| rsf 版    | `dumpAst(node)`         | `rsfSrc/bootstrap/Parser.rsf`                               |

dump 格式：一行一个节点，缩进 = 2 空格 × 深度，行尾统一 `\n`；
行 = `<KIND>` + 若干 `名字=值`，值里的 `\` `换行` `回车` `制表` **空格** 各自一个转义、
其余 `\xNN`，所以「按空格切字段」无歧义；列表是显式节点并带 `count=N`。
确定性来自四件事：**不打对象地址/hashCode**、**顺序就是 AST 里的 `List` 顺序**（不做任何
集合重排，不碰 `HashSet`/`Map`）、**浮点/长整型值统一走 JVM 的 `toString`**
（rsf 侧用 `Double.toString(Double.parseDouble(...))` 等**同一批 JVM 方法**，所以
`3.141592653589793238`、`007`、`2.5D` 这种「源码文本 ≠ 值的写法」两端也能对上）、
**转义表固定**（两处实现互为复刻：`AstDump.esc` ↔ `Parser.escapeValue`）。
`SourceLine`（行列）**刻意不进 dump**：那是实现细节，本阶段比的是语法结构。

放在测试源集里是刻意的：`rsf check` / `rsf lex` / `rsf run` 的输出格式有测试依赖，
一个字都不能动，所以这份 dump 不挂在任何 CLI 入口上，只有测试会调它。

**对拍命令**（和 17a 一样是手跑的口令）：

```bat
:: Kotlin 侧：测试里调的 AstDump（没有 CLI 入口，见上）
:: rsf 侧：
java -jar build\libs\rainStarfox-0.9.0.jar build rsfSrc/bootstrap/ParserCli.rsf -o build\bootstrap
java -Drsf.ast.file=rsfSrc/lang/arrays.rsf -cp build\bootstrap ParserCli > rsf.txt
:: 解释执行那条路等价（对拍测试用这条，因为能一次分析、多次跑）：
java -Drsf.ast.file=rsfSrc/lang/arrays.rsf -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/bootstrap/ParserCli.rsf
```

**覆盖输入**：`rsfSrc/lang/**` 下全部 16 个 `.rsf`（含 `pt.rsf` 这个 **0 字节**空文件——
「读不到文件」和「文件是空的」是两回事，空文件是一份合法的空程序，实测踩到过，CLI 里
用 `try` 包住 `readText(path)!!` 才分得开），另加 9 份刁钻语料
（顶层与三种 import / 类与两种构造器与 `this()` 委托 / 枚举与 `switch` / 语句全家桶 /
两种 `if` 与两种 `for` 与 `for-in` 的 step / `fail` 与双层 `try` / 表达式全家桶 /
各种字面量写法 / 跨行字符串与注释）。合计比了 **2100+ 行 AST**（实测示例部分 1500+ 行、
语料部分 733 行），测试里给两条总量下限守着（「一致」必须建立在真的比了很多行之上）。

**错误处理：不做异常，做成 flag。** Kotlin 版遇错抛 `RsfError`，rsf 版**不用** `fail`
（`fail` 会停掉整个程序，`!T` 又要顺着调用链标到每一个 parse 函数上，写起来比语法本身还长），
改成「`Parser` 上挂 `failed` + 消息 + 行列，出错就 `failAt(...)` 再返回占位节点」。
合法输入上这条路一次都不走，所以对拍不受影响；每个循环都有 EOF / `failed` 出口，不会转死循环。

**非法输入的边界（照实钉住，不假装一致）**：只要求「**两边都判定为失败**」，
**不要求错误消息逐字相同**。Kotlin 版是遇错即停、消息写给用户看（带上下文、常给「应该怎么写」
的建议）；rsf 版没有异常可用，走的是 flag 那条路。逐字复刻几十条消息既做不到、
也不是这一阶段的目标——**语法**边界一致才是。测试 `rsf 版语法器对非法输入与 Kotlin 版
一样判失败` 用 20 条确定非法的输入逐条验「Kotlin 拒 + rsf 也拒」，并额外要求
rsf 侧的失败**必须带消息**（不能静默失败）。

**结果**：`rsfSrc/lang/**` 全部示例 + 9 份刁钻语料上**逐行一致，零处不一致**；
`ParserCli` 编译产物与解释执行两条路的输出在 8 个示例上**逐字节相同**。
「测试有没有用」也验过：故意把 `put("ret", ...)` 写成 `put("retX", ...)`，
对拍立刻报「第一个不同的行是第 9 行」并给出两侧内容。

**顺手修的一个构建问题**：Gradle 的 `test` 任务默认只看 Kotlin 源码，改了
`rsfSrc/bootstrap/*.rsf` 而没改 `.kt` 时会认为 test 是最新的、**直接跳过**——那对拍就等于
没跑（实测踩到：改坏了 Parser.rsf，`gradlew test` 报 BUILD SUCCESSFUL）。
现在 `build.gradle` 的 `test` 声明了 `inputs.dir(rsfSrc)`，改一个 `.rsf` 就会重跑。

**测试**（`RsfTest.kt`，+6 条：211 → 216）：`rsf 版语法器与 Kotlin 版 AST 逐条一致`、
`rsf 版语法器在刁钻语料上与 Kotlin 版 AST 逐条一致`、
`rsf 版语法器对非法输入与 Kotlin 版一样判失败`、
`rsf 版语法器的入口编译执行与解释执行一致`、
`rsf 版词法器已经认识阶段 19c 的新记号并与 Kotlin 版一致`、
`rsf 版词法器与 Kotlin 版的关键字表与记号表逐条对齐`。

**七个旧示例的输出 SHA256 与字节数全部不变**（main 606B/`28208184`、class 295B/`D2ECC25E`、
generics 234B/`056FD6FE`、errors 371B/`E9C8CDB6`、stdlib 948B/`8F397D2A`、
arrays 1119B/`6D1A2875`、files 666B/`6A0C940A`，第八个 enums 763B/`3A33A504`），
`rsf run` 与 `rsf build` + `java -cp` 两端逐字节一致——本阶段**一行 Kotlin 主源码都没动**
（只加了测试源集里的 `AstDump.kt`、改了 `build.gradle` 的 test inputs）。

**写的时候卡住 / 绕开 / 写得别扭的地方**（决定 17c / 17d 的可行性）：

| 缺口                                          | 具体卡在哪                                                                      | 现在怎么绕的                                                                                                         |
|---------------------------------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| **没有继承/接口**                                 | 写不出 `Expr`/`Stmt`/`Decl` 的多态节点层次                                           | 胖节点 + `switch (kind)`。**能用**，但属性只能拼成字符串，把 `ret` 写成 `retType` 编译器一句话不说——对拍成了唯一的正确性防线                            |
| **数组元素不可空**                                 | `Node[]` 放不进 `null`，而「可选的子节点」（`for` 的条件/更新、`if`/`switch` 的 else、`pkg`）到处都是 | 按需 `add`（不存在就不占位）；代价是局部变量层面还得备一个占位节点（`var cond = errorNode()`），因为 rsf 里没法声明「可能没有的值」                            |
| **没有 `HashSet` / `Map`**                    | 类类型参数重名检查要线性查 `string[]` + 手写数组扩容                                          | 手写 8 行扩容。**17c 的第一号拦路虎**：解释器要符号表、函数表、类表，全都得手写                                                                  |
| **没有异常（只有 `fail` + `!T`）**                  | 语法器没法像 Kotlin 版那样「抛 + 在任意层恢复」；`!T` 会顺调用链传染到每一个 parse 函数                    | flag + 占位节点。直接后果是**错误消息没法逐字复刻**（那条边界写进测试了）                                                                     |
| **没有方法重载 / 可变参数 / 默认参数**                    | Kotlin 的 `match(vararg kinds)` 写不出来                                        | 拆成 `matchAny2` / `matchAny3` / `matchAny4`，调用点各写各的。**方法重载后来在阶段 19d 补上了**（2026-10-11），但这些绕开写法按「自举产物冻结」的原则**不改** |
| **没有多返回值 / 元组**                             | `parseTypeRef()` 要同时给「名字、泛型实参、数组维度」三样                                      | 专门加了一个 `TypeInfo` 类。**没有 data class，也没有解构**，类得手写                                                               |
| **字符串只有 `length` / `charAt` / `substring`** | 没有 `split` / `join` / `trim` / `repeat`                                    | `normalizeImportPath` 手写 30 行；`repeatText` 手写；缩进、`hex2` 都手写                                                    |
| **没有数组字面量**                                 | `new string[]{"a","b"}` 不存在                                                | 只能 `new string[8]` + 逐个下标赋值                                                                                    |
| **`main` 收不到命令行参数**                         | 入口没法接输入路径                                                                  | 系统属性 `-Drsf.ast.file=`（17a 已经这么干）                                                                              |
| **没有 `?.`**                                 | JVM 互操作返回值可能是 null                                                         | `?string` 接住再 `?:` 兜底（`System.getProperty` 这条 17a 踩过 NPE）                                                      |
| **枚举不能带字段/方法**                              | `NodeKind` 挂不了「dump 里的名字」                                                  | 用一个穷尽 switch 的 `kindName()` 代替（顺带换来了穷尽性检查）                                                                     |
| **没有模板字符串**                                 | 报错消息一拼就是五六段 `+`，括号层级难看                                                     | 硬拼                                                                                                             |
| **`!!` / `fail` 只能在 `!T` 函数或 `try` 里**      | 入口读文件要用 `readText(path)!!`                                                 | 用 `try { ... } catch (readError : string) { ... }` 包住                                                          |

**一句话结论**：**17c 可以做**，但「手写 Map / 符号表」会是主要工作量；
若想省事，先补一个内置的 `HashMap` 式容器（或者阶段 20 的 `interface` + `java.util.HashMap`
互操作）比先补接口更划算——语法器通篇没觉得缺继承，但缺字典缺得很实在。

### 17c 实际做法（2026-10-08）

**写了什么**：`rsfSrc/bootstrap/Interp.rsf`（**2277 行**，含注释）——**纯库**，没有 `main`；
同目录的 `InterpCli.rsf`（64 行）是入口，读 `System.getProperty("rsf.interp.file")`。

内部流程是 **rsf 版 `lex` → rsf 版 `parse` → 在这棵胖节点树上遍历执行**，
而 Kotlin 版跑的是降级后的扁平 IR——**两条实现路径不同，观察到的语义必须相同**，
这就是 17c 的对拍口径。

#### 第 0 步：互操作探路（四问的答案）

先写了几个**临时** rsf 程序回答「值与环境怎么表示」（验证完已删，不进仓库）：

| 问题                                                           | 答案                                                                                                                             | 影响                                           |
|--------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `import java.util.HashMap` + `new HashMap()` / `put` / `get` | ❌ **当时不能用**（**2026-10-11 已修**，见「17d 前置三件」）。两个类型实参时 `put` 的形参停在擦除后的 `Object` 上，`m.put("a", 7)` 报「没有匹配的方法 'put'（实参：string, int）」 | 17c 的符号表 / 函数表 / 类表**手写**（17d 不用再手写）         |
| `import java.util.ArrayList`                                 | ✅ **能用**（`ArrayList&<int>` / `&<string>`），`add` / `get` / `size` 都通——**恰好一个类型实参**时擦除的 `Object` 形参会被替换回元素类型                     | 动态数组用它（`JList`）                              |
| `import java.util.HashSet`                                   | ⚠️ 一个类型实参、规则同上，理论上可用，本阶段没用到                                                                                                    | —                                            |
| 外部类写进类型标注（`Object` / `?Object` / 用户类传给 `Object` 形参）          | ✅ **能用**。`var s : Object = "hi"` / `?Object` / `fn f(x : Object)` 都编译通过，`"" + obj` 走 `toString`                               | 本来可以做标签联合的 `objVal : ?Object`——**实测后没用**（见下） |
| `?T` 与外部类组合（`?Object`）                                       | ✅ 能用                                                                                                                           | —                                            |

**据此选的值表示：纯 rsf 标签联合 + 自己的对象模型。**
`Val` 上挂一个 `kind : Kind` 枚举（`INTV / LONGV / FLOATV / DOUBLEV / BOOLV / CHARV /
BYTEV / STRV / OBJV / ARRV / ENUMV / LISTV / CTYPEV / FILEV`）+ 每种载荷一个字段
（`iv : int`、`lv : long`、`fv : float`、`dv : double`、`cv : char`、`yv : byte`、
`sv : string`、`ov : ?Obj`、`av : ?Arr`、`lsv : ?JList`）：

```
public class Val(kind : Kind) { var k : Kind = kind; var iv : int = 0; ... var ov : ?Obj = null ... }
public class Obj(cn : string) { var cls : string = cn; var fs : Val[] = new Val[0] }   // 类名 + 字段数组
public class Arr(d : int)    { var depth : int = d; var es : Val[] = new Val[0]; var elemType : string = "" }
public class JList()         { var es : Val[] = new Val[8]; var n : int = 0 }          // ArrayList 的替身
public class Env(p : ?Env)   { var names : string[]; var vals : Val[]; var n : int; var me : ?Obj; var cl : ?Cls }
```

四个刻意的决定：

1. **分 INT / LONG / FLOAT / DOUBLE 四档，不合并成 double**：Kotlin 版有一整套 Checker
   算出来的静态类型，`10 / 3` 与 `10 / 3.0` 的差别、`render` 按哪个类型打、`byte` 怎么截断
   全靠它。树遍历器没有那张表，用的是**运行期标签**，所以在每个类型边界
   （变量声明 / 参数 / 返回 / 字段 / 数组元素）按标注做一次 `coerceTo`——
   这一层不是可选的优化，是「运行期标签 ≡ 静态类型」这条不变式的载体。
   INT 档**一定用 `int` 运算**（不是用 long 算再截），溢出回绕才和 JVM 的 `IADD/IMUL` 一致。
2. **对象 / 数组 / 列表是独立的类，`Val` 里存它们的引用**：于是 `var b = a` 天然共享引用，
   和 JVM 一致，不用手写堆 + 下标。
3. **没用 `?Object` 兜对象**：能编译，但取出来是 `Object`，既不能访问字段也不能比较，
   反而要再写一套「按名字反射」的路——标签联合 + 自己的类表更直接。
4. **手写符号表**：`Env` 是两条平行数组（`string[]` + `Val[]`）+ 线性查找 + 满 8 翻倍。
   `HashMap` 用不了，只能这样。

#### 控制流怎么表示：把 `fail` 当成非局部跳转

rsf 没有异常，只有 `fail` / `!T`。解释器需要四种「跳出去」：`return` / `break` /
`continue` / `goto`。做法是**借用 `fail` 做非局部跳转**，用一条带前缀的消息区分信号与真错误：

```
消息 = toChar(1) + 种类 + ":" + 载荷      // 种类：R / B / C / G / X
```

前缀是运行期算出来的 `toChar(1)`（不在源码里出现 `\u0001` 这种转义，免得踩词法器的边角），
用户 `fail` 出来的消息不可能以它开头。每种跳出在**离它最近的、能处理它的那一层**被接住，
接不住就原样重新 `fail`：

| 谁接      | 接什么                                                          |
|---------|--------------------------------------------------------------|
| `BLOCK` | `goto`（标签在本块里才接，否则往上传）                                       |
| `WHILE` | `break` / `continue`                                         |
| `FOR`   | `break` / `continue`（`continue` 要先把循环的「更新」跑一遍再进下一轮）          |
| `TRY`   | 真错误；**信号一律原样往上传**（否则 `try { rtn 5 } catch ...` 会把 return 吃掉） |
| 函数调用    | `return`（值放在 `retVal` 字段上，信号消息只能是字符串）                        |

另外还有一个**不可接**的信号 `X`：`!!` 打到 null、除以零。Kotlin 版那边抛的是
`NullPointerException` / `ArithmeticException`，而解释器的 `try` **只接 rsf 自己的错误**，
所以它必须穿过去，由入口打印成 `运行期错误: …`。

#### 覆盖与结果

| 项                                                                                        | 结果                                                                                                                                                                                                                                                                                                 |
|------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 八个示例（`main` / `class` / `generics` / `errors` / `stdlib` / `arrays` / `files` / `enums`） | stdout 与 Kotlin 版**逐字节相同**（实测合计 222 行）                                                                                                                                                                                                                                                             |
| 8 份刁钻语料                                                                                  | 同上（递归与数值提升 / 数组越界与负长度 / `&&` `                                                                                                                                                                                                                                                                     ||` 短路副作用 / 枚举 `switch` 分派 / 构造器重载与 `this()` 委托 / 字符串与错误处理 / 控制流全家桶 / 泛型集合与对象数组），实测比了 97 行 stdout |
| 解释执行 vs `rsf build` + `java -cp`                                                         | 八例与语料都逐字节相同（`InterpCli` 走两条路）                                                                                                                                                                                                                                                                      |
| 测试                                                                                       | 216 → **220**（+4），全绿                                                                                                                                                                                                                                                                               |
| 八示例 SHA256                                                                               | 与 17b 记录的**逐个相同**（main 606B/`28208184`、class 295B/`D2ECC25E`、generics 234B/`056FD6FE`、errors 371B/`E9C8CDB6`、stdlib 948B/`8F397D2A`、arrays 1119B/`6D1A2875`、files 666B/`6A0C940A`、enums 763B/`3A33A504`）——`rsf run` 与 `rsf build` + `java -cp` 两条路也都逐字节相同。**本阶段一行 Kotlin 主源码都没动**（只加了测试源集里的 4 条测试） |

> 注：SHA256 是用 JVM 的**默认 stdout 编码**（本机 cp936）+ CRLF 采样的——中文按 GBK
> 是 2 字节、按 UTF-8 是 3 字节，编码一变字节数就变。两条后端各自跑、各自采样，
> 结果一致。

**对拍命令**：

```bat
:: 解释执行
java -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/lang/arrays.rsf > kt.txt
java -Drsf.interp.file=rsfSrc/lang/arrays.rsf -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/bootstrap/InterpCli.rsf > rsf.txt
fc kt.txt rsf.txt

:: 编译执行（rsf 版解释器自己编出来再跑）
java -jar build\libs\rainStarfox-0.9.0.jar build rsfSrc/bootstrap/InterpCli.rsf -o build\bootstrap
java -Drsf.interp.file=rsfSrc/lang/arrays.rsf -cp build\bootstrap InterpCli > rsf2.txt
fc kt.txt rsf2.txt
```

#### 17c 暂不支持清单（**一律显式报错，绝不静默算错**）

凡是不支持的语法/语义，都走 `unsupported(what)` → `fail "17c 解释器暂不支持：…"`，
由入口打成 `运行期错误: 17c 解释器暂不支持：…` 并 `System.exit(1)`。

| 不支持的东西                                                                                                              | 报什么                                                              |
|---------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| **JVM 互操作**：除 `java.util.ArrayList` / `java.io.File` / `System.getProperty` 之外的外部类与静态方法                             | `17c 解释器暂不支持：JVM 静态方法 Math.max()`、`构造 JVM 类 'TreeMap'…`、`成员方法 …` |
| `pkg` 声明                                                                                                            | **不报错也不做事**（对 `rsf run` 的 stdout 没有可观察影响；`rsf build` 才用得上包名）     |
| `import "别的文件.rsf"`（多文件）                                                                                            | 被导入文件里的东西**不会**被装载，引用到就报 `找不到名字 '…'`——不会静默算出一个空值                 |
| 还没在 `switch (node.kind)` 里列出的语句 / 表达式节点                                                                             | `17c 解释器暂不支持：语句 X` / `表达式节点 X`                                   |
| `string` 上除 `length` / `charAt` / `substring` / `isEmpty` / `contains` / `equals` / `startsWith` / `endsWith` 之外的方法 | `17c 解释器暂不支持：string 的方法 'x'`                                     |

**刻意不管的两条边界**（照实写清楚，不假装一致）：

- **不做类型检查**。Kotlin 版会拒绝的程序（`var x : int = "a"`），rsf 版解释器可能照跑——
  但那类程序在 Kotlin 版根本进不了对拍，所以对拍不受影响；真碰到算不下去的地方
  （`1 + true`）会报 `算术运算 '+' 用在 BOOLV 与 INTV 上`，不会算出一个假值。
- **`charAt` / `substring` 越界的异常类型不同**：两边都不是 rsf 自己的错误，
  所以两边都「`try` 接不住」；Kotlin 版包在 `InvocationTargetException` 里，
  rsf 版直接是 `StringIndexOutOfBoundsException`。这种程序本身就会崩，不影响对拍。

#### 测试能否失败（自验，实测过两次）

1. 把 `render` 里 `Kind.INTV : rtn "" + v.iv` 改成 `"" + (v.iv + 1)`
   （一处静默算错）→ 两条对拍测试立刻红，并打出「第 N 行应该是 X、实际是 Y」的差异
   （`add(1, 2) = 3` vs `= 4`、`fact(5) = 120` vs `121`…）。
2. 把 `&&` 的短路拆掉（右边无条件求值）→ **刁钻语料**那条测试报出
   `r1=false log=ab`（应当是 `log=a`）——证明「短路副作用」那份语料不是摆设；
   同时 `enums.rsf` 因为 `i < s.length() && s.charAt(i) == '/'` 变成越界读而抛异常，
   说明示例侧也能察觉。

#### 顺手发现的编译器缺陷（本阶段**不动** Kotlin 编译器，只在 rsf 源码里绕开）

**一元负号用在 `byte` / `char` 上时，字节码后端会崩**：`rsf check` 放行，
`rsf build` 抛 `IllegalStateException: 一元减号的操作数不是数值`
（`BytecodeCompiler.kt:1286` 只认 INT / LONG / FLOAT / DOUBLE 四种静态类型，
而 `RsfType.promote` 把 byte/char 的一元负号结果定成 int）。
17c 的解释器要在 `evUnary` 里对 byte 取负，于是把 `-v.yv` 写成 `0 - v.yv` 绕开。
**这是一条真实的既有缺陷**，建议单独排一步修（后端应当按「操作数提升后的类型」发指令）。
→ **2026-10-11 已修**，见下一节「17d 前置三件 · 前置 1」。

### 17d 前置三件 —— ✅ 已完成（2026-10-11）

开 17d 之前先把三件拦路的事做掉。三件都改在 `src/main/kotlin/**`，
**没有动 `rsfSrc/**` 一个字节**，八个示例的 stdout 与 SHA256 逐个不变。

#### 前置 1：`byte` / `char` 上的一元负号（三方不一致）

*症状*（原样复现）：

| 命令          | 结果                                          |
|-------------|---------------------------------------------|
| `rsf check` | 放行（stderr 0 字节）                             |
| `rsf run`   | 正常，打 `-5`                                   |
| `rsf build` | **崩**：`IllegalStateException: 一元减号的操作数不是数值` |

*根因*：`codegen/BytecodeCompiler.kt` 的一元减号发射只比 `RsfType.INT/LONG/FLOAT/DOUBLE`
四种**静态类型**，而 `RsfType.promote`（Checker 的 `UnaryExpr`）把 `byte` / `char` 的
一元负号结果定成 `int`——Checker 认为是合法的 int 运算，后端却拒绝。

*修法*：按**提升后**的基础类型发指令（`model.typeOf(expr.operand).base.promoted()`），
byte / char 在 JVM 栈上本来就是 int，所以仍然是一条 `INEG`：

```kotlin
TokenKind.MINUS -> mv.visitInsn(
when (val base = model.typeOf(expr.operand).base.promoted()) {
    RsfBase.INT -> INEG      // byte / char 也落在这里
    RsfBase.LONG -> LNEG
    RsfBase.FLOAT -> FNEG
    RsfBase.DOUBLE -> DNEG
    else -> throw IllegalStateException("一元减号的操作数不是数值（$base）")
},
)
```

*同类隐患的排查结论*（**三方一致性自查**）：`BytecodeCompiler` 里按基础类型分派的地方
只有两处——一元负号（已修）与**二元算术**（`emitBinary` 里按 `resultType` 分派）。
后者安全：`resultType` 来自 Checker 的算术结果类型，`byte` / `char` 一定已经被提升成
`INT`，永远落不进 `else`。另外两处「看起来像」的地方也确认过：

- `widenInsn(from, to)`：byte / char 的每条出口（→int/long/float/double）都列全了，
  唯一的例外是 `char` 不往任何数值隐式转（那是**语言规定**，不是缺陷）；
- 一元 `+`：**语法上就不存在**（`Parser` 的一元分支只收 `-` 和 `!`），没有对应的隐患。

回归测试在 `src/test/kotlin/org/example/InteropTest.kt`：
`byte 与 char 上的一元负号三条路都一致`（check / run / build 三条路一起钉）与
`基础类型的一元与二元运算三条路都一致`（每个基础类型的一元与二元运算全过一遍）。

#### 前置 2：`HashMap&<K, V>` 这类多类型实参的擦除互操作

*原来的样子*：`Checker.singleTypeArg` 只对「**恰好一个**类型实参」做
`Object → 元素类型` 的还原。所以 `ArrayList&<int>` 能用，而
`HashMap&<string, int>` 的 `put(Object, Object)` 一个实参都接不住
（报「'HashMap' 里没有匹配的方法 'put'（实参：string, int）」）。

*选择：修擦除互操作（方案 a），不加内置容器（方案 b）。* 理由：

- 方案 b 的容器只能返回 `?Object`，而 rsf **没有强制转换语法**——
  取出来既不能访问字段也不能参与算术，做符号表反而要再写一套「按名字反射」的路
  （这正是 17c 弃用 `?Object` 的原因）。要让 b 可用，就得让内置函数按接收者的类型实参
  决定返回类型，那本质上还是在做 a 的事，却多了一层专门的运行时。
- 方案 a 是**通用的**：`TreeMap` / `LinkedHashMap` / `Hashtable` 这些同形状的类一并受益，
  而且没有新增语言表面，`Builtins` 一行没动。

*做法*：读 `.class` 时把方法的**泛型签名**也解析出来，记下「第 i 个形参 / 返回值是
类的第几个形式类型参数」，Checker 再按接收者的类型实参还原：

| 文件                                                    | 改什么                                                                                                                                                                                                                                                                     |
|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `interop/ClassPath.kt`                                | `JvmClass.typeParams`（类签名里形式参数的名字，`<K:...;V:...;>` → `["K","V"]`）；`JvmMethod.paramTypeVars` / `returnTypeVar`（方法签名 `put(TK;TV;)TV;` → 形参 `[0,1]`、返回 `1`）；三个签名解析函数（`classTypeParams` / `methodTypeVars` / `readSignatureType`）。方法**自己的**形式类型参数（`<X:...>`）先跳过——它们遮蔽类的同名参数 |
| `sema/Checker.kt`                                     | 新增 `erasedActual(type, varIndex, typeArgs)`：形参/返回值是**裸的类类型变量**时按类型实参还原；拿不到签名信息就退回原来的 `substituteElement`（单实参那条老路），所以既有行为不变。`findMethod` 的「装箱后再试一趟」从「只有一个类型实参时」放宽到「只要写了类型实参」——`put("a", 7)` 到 Java 侧是 `put(String, Integer)`，形参是擦除后的 `Object`，不装箱就选不中                    |
| `codegen/BytecodeCompiler.kt`、`interp/Interpreter.kt` | **结果被丢弃时不收敛**（`emitExpr(discard = true)` / `eval(discard = true)`）。这是**必须**的：`put` 的返回值是**旧值**，第一次 put 返回 `null`，静态类型却是 `int`；语句位置照样 CHECKCAST + 拆箱就会 NPE。Java 里 `map.put(k, v)` 当语句写也不拆箱，所以这条跟 Java 一致                                                                 |

*已知边界*：类型变量只用**下标**对齐，所以要求子类与父类的形式类型参数顺序一致
（`LinkedHashMap<K,V> extends HashMap<K,V>`、`TreeMap<K,V> extends AbstractMap<K,V>`
都满足；JDK 集合全族都是这个形状）。换序继承（`Foo<A,B> extends Bar<B,A>`）要改成沿
类签名的父子映射走——目前没有这种类。

*「能不能用它重写 `Interp.rsf` 的 `Env`」的验证结论：**能***。
在 `rsfSrc/bootstrap/` 下用临时程序验过（已删，仓库里不留），照 17c 那个 Env 的形状写了一遍：
`table : HashMap&<string, ?Val>` + `parent : ?Env`，`define` / `lookup`（沿作用域链向上找）/
`containsKey` / `size` / 覆盖写全部两端一致。**17d 的表因此不用再手写**：
常量池去重、槽分配、跳转回填都可以直接用 `java.util.HashMap`。
（17c 那个手写的 `Env` 保持原样——它已经跑通了八个示例，为它重写解释器不划算；
17d 直接写新表。）

#### 前置 3：外部类的 `new`（17d 的硬前提）

*现状核对*：`new` **早就不是只支持用户自定义类**了。`ClassPath` 把 `<init>` 收进独立的
`constructors` 表（阶段 12 计划里那行「不再跳过 `<init>`」已经落地），
`MemberResolver.findConstructor` 按「个数 → 类型打分」挑重载，两条后端分别发
`NEW + DUP + 实参 + INVOKESPECIAL <init>` 与反射 `Constructor.newInstance`。
实测（`rsfSrc/` 下的临时程序，已删）：

| 写法                                                               | 结果                                               |
|------------------------------------------------------------------|--------------------------------------------------|
| `new File("x")` / `new File("dir", "two")`                       | ✅ 两端一致，重载按个数+类型挑中                                |
| `new ArrayList&<string>()`                                       | ✅                                                |
| `new HashMap()`（不写类型实参）                                          | ❌ 报「类型实参个数不对：期望 2 个，实际给了 0 个」——**元数检查**是有意的，不是缺口 |
| `new StringBuilder(16)` / `.length()` / `.charAt` / `.substring` | ❌ → ✅ **这轮修的**，见下                                |
| `new ArrayList&<string>(4)`（泛型类 + 基本类型实参）                        | ❌ → ✅ **这轮修的**，见下                                |

补掉的两个真缺口（都加了两端一致的回归测试）：

1. **泛型外部类的构造器挑不中基本类型实参**：`checkExternalConstructor` 先按**原类型**
   试一遍构造器、再按装箱后的形态试一遍。以前只试装箱那一趟，而 `argCost(int, ?int)`
   打不出分，于是 `new ArrayList&<string>(4)`（Java 里再普通不过的写法）挑不到
   `<init>(int)`。
2. **从包私有父类继承来的方法两端都跑不起来**：`StringBuilder.length` / `charAt` /
   `substring` 声明在 `java.lang.AbstractStringBuilder` 上，而那个类不是 `public`。
   字节码发 `INVOKEVIRTUAL java/lang/AbstractStringBuilder.length()I` ⇒ 运行期
   `IllegalAccessError`；解释器 `Class.forName` + `setAccessible(true)` ⇒
   `InaccessibleObjectException`。修法是 javac 那一套：**包私有的声明类不当 owner，
   用接收者的静态类型当 owner**（JVM 的方法解析本来就沿父类链走，
   `javap` 里看到的就是 `invokevirtual java/lang/StringBuilder.length:()I`）。
   解释器侧同时把 `getDeclaredMethod + setAccessible(true)` 换成
   `getMethod`（public + 沿继承链，正是 `INVOKEVIRTUAL` 的语义）、退路才用
   `getDeclaredMethod` + `trySetAccessible()`。

*验证 ASM 可用性*：**能**。临时 rsf 程序里 `new ClassWriter(ClassWriter.COMPUTE_FRAMES)`

+ `visit` / `visitMethod` / `visitInsn` / `toByteArray()` 全部走通，
  生成的 `byte[]` 拿给 `ClassLoader.defineClass` 定义出来、反射调它的方法返回 42——
  一轮真实的「生成 → 变形 → 调用」闭环。

ASM 在**编译器的运行期 classpath** 上（`build.gradle` 的
`implementation 'org.ow2.asm:asm:9.9.1'` + `asm-util`，`gradlew jar` 打的是胖包），
所以 `rsf run` 直接就能用。**但要注意两条路的 classpath 不一样**：
`rsf build` 出来的 `.class` 要自己把 `rainStarfox-0.9.0.jar` 挂进 `-cp` 才找得到 ASM——
17d 的验证脚本要按 `java -cp "out;build\libs\rainStarfox-0.9.0.jar" RsfBackend ...` 这么写。
（`asm-util` 也在包里，`Textifier` 反汇编那一步同样不缺。）

### 17d 的下一步（rsf 版字节码后端）—— **可以开始了**

17a / 17b / 17c 合起来已经把「读源码 → 建树 → 执行」整条路用 rsf 走通了，
上面三件前置也做完了。17d 要写的是**把同一棵胖节点树变成 `.class`**，
对拍口径是 `rsf build --dump` 的反汇编逐行相同。

**起手式**（按 17c 摸出来的路，三件前置之后的版本）：

1. **先用容器把表立起来**（原「先补容器，再开后端」那条已经兑现）：
   `HashMap&<string, int>` 现在能用了，常量池去重 / 槽分配 / 跳转回填三张表直接用
   `java.util.HashMap` 写。注意 `m.put(k, v)` 写语句是安全的（结果被丢弃时不拆箱），
   但**别把返回值赋给非空基本类型**——第一次 put 的旧值是 `null`。
2. **先拿 ASM 立骨架**：`new ClassWriter(ClassWriter.COMPUTE_FRAMES)` 已经验过可用；
   按 `rsfSrc/bootstrap/` 的老规矩，后端写成**纯库**（没有 `main`），
   入口另起一个 `BackendCli.rsf`（读 `System.getProperty("rsf.backend.file")`——
   `main` 还接不到命令行参数）。
3. **对拍口径先定死**：`rsf build --dump` 的反汇编逐行比。注意 Kotlin 版的 dump 用的是
   ASM 的 `Textifier`，输出里**不能有对象地址 / `HashMap` 迭代顺序**这类不确定的东西——
   17b 的 AST dump 已经把「确定性从哪来」写清楚了（不打地址、顺序就是声明顺序、
   浮点走 JVM 的 `toString`），照抄同一套纪律。
   ⚠️ 反汇编里会进 owner 的名字，所以「包私有声明类改用接收者静态类型当 owner」这条
   改动会体现在 dump 上——两边必须都用**改过之后**的 Kotlin 版当参照。
4. **`!T` 的控制流在 17d 反而更简单**：后端不需要像解释器那样拿 `fail` 当跳转用——
   字节码本来就有异常表（`visitTryCatchBlock`）。**17c 那套「信号消息」是解释器专属的绕路，
   17d 不要照抄。**
5. **`byte[]` 已经是通的**（16b）：`ClassWriter.toByteArray()` 接得住、`writeBytes` 能落盘，
   17d 不需要再碰这块。
6. **一元负号那个坑已经填了**（前置 1）：后端按类型发指令时不用再绕它。

### 17d 实际做法（2026-10-13）—— 八示例 `.class` **逐字节相同**

**写了什么**：`rsfSrc/bootstrap/Backend.rsf`（4929 行，纯库）+ `BackendCli.rsf`（111 行入口，
读 `-Drsf.backend.file` / `-Drsf.backend.out`；rsf 的 `main` 收不到命令行参数）。
架构是**两阶段**，与 Kotlin 版一一对应：

| rsf 侧                             | Kotlin 侧                      | 干什么                                  |
|-----------------------------------|-------------------------------|--------------------------------------|
| `Walker`（`walkStmt` / `walkExpr`） | `ir/Lowering.kt`              | 建符号表 / 槽位 / 跳转回填，把语句降成一维 `Ins[]` 指令表 |
| `Gen` + `emitIns` / `emitExpr`    | `codegen/BytecodeCompiler.kt` | 拿 ASM 的 `MethodVisitor` 逐条发指令        |

类表 / 函数表 / 枚举表 / 字段表**都是手写平行数组 + 线性查 + 满则翻倍**
（`findImport:735` / `findClass:744` / `findEnum:753` / `putFld:769` / `fldOf:785`）——rsf 用不了
`HashMap&<K,V>`；常量池去重交给 ASM 的 `ClassWriter` 自己做。（2026-10-13 实测纠正：本行原文写的是
「类表 / 函数表 / 枚举表 / 常量池去重直接用 `HashMap&<K, V>`」，与实现不符。）
异常表→`visitTryCatchBlock`、`switch`→`DUP`+`GETSTATIC`+`IF_ACMPEQ` 跳板、泛型擦除、
装箱拆箱、`?:` 合成方法都与 Kotlin 版同一套口径。`ClassWriter` 也是
`COMPUTE_FRAMES or COMPUTE_MAXS`（与 Kotlin 版一样；rsf 没有继承、写不出子类，
`getCommonSuperClass` 用 ASM 默认实现即可——当前子集里的引用类型只有 JDK 类，两者结果相同）。

**对拍口径**（三层，缺一不可）：

1. **`.class` 字节级**：同一份 `.rsf`，Kotlin 版 `rsf build` 与 rsf 版后端各产一套类，
   逐个文件逐字节比（比 SHA256 等价）；
2. **反汇编**：`rsf build --dump`（ASM `TraceClassVisitor`）逐行比；
   ⚠️ **不要用 `javap` 输出直接 diff**——它把常量池下标（`#157`）打出来，池子一错位就满屏假差异；
   属性级诊断用 `javap -v -p`，指令级对比用**字节级比较**；
3. **运行期**：`java -cp <产物> <主类>` 的 stdout / stderr / 退出码比。

**实际达到的一致程度：第 1 层就相同**——八示例共 **20 个 `.class` 逐个字节相同**
（`main` 1 / `class` 3 / `generics` 3 / `errors` 1 / `stdlib` 1 / `arrays` 4 / `files` 2 / `enums` 5），
运行期 stdout / stderr 也逐个一致。于是第 2 层（dump 逐行相同）自动成立。
`rsfSrc/lang/**` 一行没动，Kotlin 侧编译器一行没动（测试与八示例 SHA256 因此不变）。

**最后那点差异是怎么找出来的**（都是字节级对拍，不是文本 diff）：

1. **`LineNumberTable`：Kotlin 版有、rsf 版一个都没有 → rsf 侧要补**。做法与 Kotlin 版同构：
   `Parser.rsf` 的 `Node` 加 `var line`（语句节点由 `parseStmt` 包装统一补，表达式节点不带），
   Walker 的 `Ins` 带行号（`walkStmt` 存/取 `curLine`），`flushBody` 逐条
   `visitLabel` + `visitLineNumber(line, label)`，行号下限 1。
2. **最隐蔽的一个真因**：自由函数走的是 `emitFunction`，它**原先自己抄了一份发射循环**，
   绕过了统一的 `flushBody` → 行号只有用户方法里有。把它并回 `flushBody` 才通。
3. **行号的归属要一个个对**：else-if 链上每个 `if` 属于自己那一行；`枚举.X : 语句` 的
   分支体标签属于**分支体**，而 `else` 标签与末尾标签属于 **`switch` 本身**；
   块的行号 = 它第一条语句的行号。
4. 中途还修掉三处**结构性**差异（后端漏发/发错指令，与行号无关，会连带把后面的偏移全部带偏）：
   `byte`/`char` → `int` 的加宽必须真发一条 `NOP`；`?:` 要做类型收敛 + 装箱/拆箱；
   用户类的**构造器要发在普通方法之前**（Kotlin 侧的顺序是 字段 → 构造器 → 普通方法）。

**暂不支持清单（一律显式报错，绝不静默算错）**：

所有不支持的构造都走 `g.bad(what)` → `setFailed("17d 后端暂不支持：" + what)`，
由入口打成 `17d 后端暂不支持：…` 并以非 0 退出码结束。按类别：

| 类别                 | 报错文本（`…` 是具体名字/类型）                                                                                                                                                                                                                                                                                                                                                             |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 顶层声明               | `包声明 pkg …`（pkg 还不支持）、`import 形式 …`、`类成员 …`、`函数 …`                                                                                                                                                                                                                                                                                                                             |
| 类型 / 泛型            | `泛型类型 …`（`&<` 括号不配对或实参解析失败）、`非泛型类型 …`（内置/JVM 类型带了实参）、`类型实参个数不符 …`、`类型参数不能带类型实参 …`、`可空数组类型 …`（`?T[]`）、`可空 void`、`空类型`、`类型 …`                                                                                                                                                                                                                                                    |
| 类型转换               | `可空类型转换 A → B`、`数值转换 A → B`                                                                                                                                                                                                                                                                                                                                                    |
| 调用 / 构造            | `函数调用 …()`、`重载调用 …（实参个数 n 没有匹配）`、`成员方法 T.x()`、`按类名调用实例方法 R.x()`、`类 R 的方法 x()（n 个参数）`、`构造对象 new …`、`构造 … 的第 n 个实参类型不匹配`、`类 … 没有 n 个参数的构造器`、`类 … 只有一个隐式无参构造器，却给了 n 个实参`、`构造器委托 this(...)`、`JDK 构造器 …（n 个参数）`、`JDK 静态方法 R.x()`                                                                                                                                                    |
| 成员 / 下标            | `成员访问 .…`、`成员 …`、`静态字段 R.x`、`类 R 没有字段 x`、`对非数组做下标访问`、`下标赋值`（对非数组）                                                                                                                                                                                                                                                                                                              |
| 运算符                | `对非数值取负`、`算术运算 A + B`、`比较 A 与 B`、`三目两支的类型推不出来（A / B）`、`?: 两支的类型推不出来（A / B）`                                                                                                                                                                                                                                                                                                    |
| 语句 / 赋值            | `语句 …`、`变量声明`、`变量 …`、`未声明的变量 …`、`赋值`、`字段赋值`、`给不可变字段赋值：…`、`给字段赋值 T.x`、`把 T 赋给 U`、`对未声明变量赋值：…`、`指令 …`（内部错误）                                                                                                                                                                                                                                                                      |
| 控制流                | `循环外的 break`、`循环外的 continue`、`switch`（形状不对）、`switch 的表达式不是枚举（T）`、`switch 分支的枚举常量 E.V`、`switch 分支枚举类型不一致（A / B）`、`goto / 分支的标签没有定义（内部错误）`                                                                                                                                                                                                                                       |
| 枚举                 | `对枚举 R 调方法`、`枚举 R 没有常量 x`                                                                                                                                                                                                                                                                                                                                                      |
| `this`             | `… 需要 this，但当前是 static 方法`、`…（不在类里）`                                                                                                                                                                                                                                                                                                                                           |
| 阶段 20 的新语法（D10 缺口） | `interface` / `override`：**已同步（2026-10-15，两刀）**——第 1 刀 `Parser.rsf`（`interface` / `override` / 父类型子句 + AST 与 Kotlin 版逐字节对拍）、第 2 刀 `Backend.rsf` / `Interp.rsf` / `Sources.rsf`（`ACC_INTERFACE` / 默认方法 / `INVOKEINTERFACE` / 接口链查找 / 多文件顶层 `INTERFACES`）。旧状（已修复，留档）：rsf 版编译器编到这类源码在**解析层**就退出——`[rsf-17d] 解析失败：顶层只能写函数声明…`（探针 `build/verify20/scratch/rsfIf/{I,O}.rsf`）。补表时**必须按接口发 `INVOKEINTERFACE`**，否则 B 与 Kotlin 产物分叉（第 2 刀已按此实现：`Backend.rsf` 四处 `visitMethodInsn` 末位布尔改动态） |
| JDK 成员表（**2026-10-16 扩充**） | 表是**手写白名单**（rsf 侧没有反射），现在覆盖：`System.getProperty` / `exit` / `out` / `err`；`Math` 的 `max` / `min` / `abs`（int / long / float / double **四份都按实参类型选**）、`sqrt` / `floor` / `ceil` / `pow`（`(D)D` / `(DD)D`）、`round`（`(D)J` / `(F)I`）与静态字段 `PI` / `E`；包装类 `valueOf` / `toString` / `parseXxx`；`Integer.toHexString` / `toBinaryString` / `toOctalString`；`String.valueOf`（按实参选 `(I)` / `(J)` / `(F)` / `(D)` / `(Z)` / `(C)` / `(Ljava/lang/Object;)`）；`java/util/HashMap` 成员（`put` / `get` / `remove` / `containsKey` / `containsValue` / `size` / `isEmpty` / `clear`，键值按接收者的两个类型实参还原）；既有 `java/util/ArrayList` / `java/io/File` / `java/lang/StringBuilder` / `java/io/PrintStream`。**表外的成员仍然显式报错**（`JDK 静态方法 R.x()` / `成员方法 T.x()`），所以「加表」是纯加法。判据：`interop/usejava.rsf`（`Math.sqrt` / `Double` 重载 / `Math.PI` / `Integer.toHexString` / `String.valueOf(double)`）与 `HashMap` 探针的 Kotlin 产物 vs 自举产物 `diff -r` 均为 **0**（`bash build/verify21/bootparity/backend-sweep.sh`） |

**测试与回归**：本阶段**只动 rsf 源码**（`rsfSrc/bootstrap/Backend.rsf`、`Parser.rsf`）与文档，
Kotlin 主源码一行没改 → 测试仍是 **248 全绿**（RsfTest 221 + InteropTest 13 + OverloadTest 14，
`cleanTest test` 实跑过），八示例 `rsf run` / `rsf build`+`java` 的 SHA256 逐个不变
（Kotlin 编译路径一行未动，因此不变；这一条由 17d 的独立对拍台复验）。
`Parser.rsf` 给 `Node` 加了 `line` 字段，而 `RsfTest` 里 17a/17b 的差分测试会打开
`rsfSrc/bootstrap/Parser.rsf` 自己的源码——它们仍然逐行相同，因为 AST dump **不打印行号**。

**17e 的下一步（三阶段自举闭环）**：

1. ① Kotlin 版编译 rsf 版编译器 → **编译器 A**：`rsf build rsfSrc/bootstrap/BackendCli.rsf` 等
   （已有产物），A 就是「Kotlin 编出来的 rsf 编译器」；
2. ② A 编译 rsf 版源码 → **编译器 B**：用 A 去编 `rsfSrc/bootstrap/*.rsf`（含 `Backend.rsf` 自己）；
3. ③ B 编译自己 → **编译器 C**；要求 **B 与 C 逐字节一致**（这是「闭」的判据）；
4. 顺带要求：A、B 对 `rsfSrc/lang/**` 八示例的输出与 Kotlin 版**逐字节一致**
   （17d 已经证明了这一层）。
   注意 ② 要用的类路径：`rsf build` 出来的类要自己把 `build\libs\rainStarfox-0.9.0.jar`
   挂进 `-cp` 才找得到 ASM。

   **判据 J1–J5 与完整设计见 `DESIGN-17e.md`**：J1 A 能编全部 8 个源；J2 A 的产物与 Kotlin 产物
   **逐字节相同**（最强口径，成立则 J3 自动成立）；J3 B ≡ C 逐字节；J4 B/C 对八示例的产物与 Kotlin 版
   逐字节相同；J5 回归（`gradlew test` 全绿 + 八示例 SHA256 与历轮锚点逐个相同）。

⚠️ **17e 的前置（2026-10-13 实测，顺序被重排了）**：

**第一道墙是 `SOURCE_IMPORT`，比互操作更靠前。** rsf 版后端完全不支持 `import "X.rsf"`（多文件
装载）。用 Kotlin 编出的编译器去编 `rsfSrc/bootstrap/*.rsf`，8 个源里 **7 个在第一行 import 就被拦**：

```
[rsf-17d] 17d 后端暂不支持：import 形式 SOURCE_IMPORT
```

唯一编过的是**没有 import 的 `Lexer.rsf`**（产出 `Lexer.class` 11968 B、`Token.class` 2719 B、
`TokenKind.class` 968 B）——即「纯单文件库」这条路是通的，缺的只是装载。做法：新增
`rsfSrc/bootstrap/Sources.rsf`（装载器，语义对齐 Kotlin 侧 `complie/loading/SourceLoader.kt`：
包含式合并、`canonicalPath` 去重、本文件声明在前再按 import 顺序深度优先、路径三处搜索基准、
被导入文件的 JVM 类 import 全局合并、`SOURCE_IMPORT` 条目剔除），四个 CLI 改成先
`loadProgram(entry)` 再编译。

**第二道墙是 JVM 互操作手写表**（`jdkClassInternal` / `jdkNewRef` / `jdkMember` / `jdkStatic`）。
实测（从 Kotlin 编出的 `.class` 常量池抓源级调用）要补的清单：

- ASM **26 条**：`ClassWriter` 7（`<init>(I)V` / `toByteArray` / `visit` / `visitEnd` / `visitField` /
  `visitMethod` / `visitSource`）、`MethodVisitor` 16（16 个 `visit*`）、`FieldVisitor.visitEnd`、
  `Label.<init>()`、`Handle.<init>(…)`。每条 `(owner, 名字, 实参个数)` 都唯一，现有按 argc 匹配够用；
  `visitField`/`visitMethod` 的返回值（`FieldVisitor`/`MethodVisitor`）要能继续当接收者。
- `java/lang/{Integer,Long,Float,Double}` 的 `valueOf`/`toString`/`parse*` 共 12 条静态方法 +
  `System.exit(I)V` + `String.lastIndexOf(String)I`。
- 一个**新机制**：JDK 静态字段读（`System.out` / `System.err` → `GETSTATIC`，类型
  `java/io/PrintStream`）配 `java/io/PrintStream.println(String)V`；现在报「静态字段 R.x 暂不支持」。

**不需要补 `HashMap`**：2026-10-13 实测 `rsfSrc/bootstrap/**` 里没有任何 `HashMap` / `ArrayList` 的
声明或调用（`Backend.rsf` 表条目里那两行是当年预留的死条目）。探针 `build\tmp17d\probe_hashmap.rsf`
报的 `成员方法 Ljava/util/HashMap;.put()` 是**探针**的需求，不是编译器源码的需求。

→ 两道墙都是显式报错、不产出错误字节码（这正是 17d 的硬要求，也让缺口只能一条条暴露）。
→ 完整清单、判据与里程碑见 **`DESIGN-17e.md`**。

#### 17e 第一步实际做法与结果（2026-10-13）

**新增 `rsfSrc/bootstrap/Sources.rsf`**（纯库）：对外只有 `loadProgram(entryPath) -> !Node`，
语义逐条对齐 `SourceLoader.kt`（包含式合并、本文件声明在前再按 import 顺序深度优先、
三个列表各自按该顺序追加、`SOURCE_IMPORT` 条目全部剔除、被导入文件的 JVM 类 import 合并进根
PROGRAM、路径搜索基准「发起文件目录 → `.` → 根文件目录」、去重键用自做路径串——
rsf 没有 `canonicalFile`，注释里写明了不影响生成字节）。失败走 `!T` 前缀分流，
「读不到文件：…」「解析失败：…」两条消息与 17e 之前逐字相同。

**接线**：`BackendCli` / `InterpCli` / `LexerCli` 先 `loadProgram` 再往下走（系统属性名、
输出格式、退出码都不变）。**`ParserCli` 有意不接线**——它的契约是「纯语法器的 AST」，
`RsfTest` 拿 Kotlin 侧 `Rsf.parse`（不走装载器）当参照，dump 里必须留着 `SOURCE_IMPORT` 条目。
**`LexerCli` 也刻意不接**——`rsf lex` 是纯词法阶段，token dump 与能不能装载无关
（接上后「入口能读、但 import 目标缺失」的文件不再 dump token，会偏离 Kotlin 版出口口径）。

**`Backend.rsf` 补互操作表**：ASM 26 条（`ClassWriter` 7 / `MethodVisitor` 16 /
`FieldVisitor.visitEnd` / `Label.<init>` / `Handle.<init>`）、`java/lang/{Integer,Long,Float,Double}`
12 条静态 + `System.exit` + `String.lastIndexOf`、新机制 JDK 静态字段（`System.out`/`System.err`）、
`PrintStream.println/print`。`(owner, 名字, 实参个数)` 三元组撞车**显式报错**，不取第一个。

**顺带修掉 6 处 17d 遗留缺陷**（都被 J1 暴露）：① `emitExpr` 缺 `THIS` 分支（`ALOAD 0`），
缺它时 ASM 重算栈帧直接抛 `Frame.getConcreteOutputType`，把真正的报错盖住；② `flushBody`
循环首句加 `if (g.failed) { rtn }`，报错后**立即停止发码**；③ `collectClasses` 改两遍
（先登记全部类、再填成员），解决类间前向引用（`Subst` 引用在它后面声明的 `BType`）；
④ `emitUserCtor` 把委托目标的读取移到 `prepBody` 之后——原来提前读永远是 null，
次构造器的 `this(...)` 委托失效、会按主构造器槽号发出非法字节码；⑤ `isErrorCallExpr` 扩到
类方法与成员（否则 `!!` 对它们多发一遍 null 检查）；⑥ `?:` 合成方法调用点的 owner 由
`g.owner` 改成当前 writer 的类名（对应 Kotlin `ctx.owner`），否则 `Interp.class` 里的
`__rsf_orelse$1` 调用点指向另一路径，rsf 编出的 `InterpCli` 一跑就 `NoSuchMethodError`。

**独立验收结果（父代理执行，脚本在 `build/verify17e/`）**：A（Kotlin 编 `BackendCli.rsf`）
**30 个类**；J1 用 A 编 `rsfSrc/bootstrap/*.rsf` **9/9 exit 0**（Backend 29 / BackendCli 30 /
Interp 17 / InterpCli 19 / Lexer 3 / LexerCli 4 / Parser 7 / ParserCli 8 / Sources 9）；
**J3 不动点：A 的产物与 B 的产物 126 个 `.class` 逐字节相同（0 差异）⇒ B ≡ C 成立**；
J4 八示例结构 + 运行 **8/8**，备注逐条「字节级也全同」；J5 `gradlew test` **248 全绿**、
八示例 SHA256 与历轮锚点逐个相同。另两条端到端：**多文件装载**（`App.rsf` + `import "lib.rsf"`）
两边各 2 个 `.class` 逐字节相同且运行输出相同；**自举解释器**（A 编出的 `InterpCli`）
对八示例的 stdout / stderr 与 Kotlin 编的那份逐字节一致。

**J2（与 Kotlin 产物逐字节相同）已于 2026-10-14 全同**。此前只差 3 个类（`BType` / `Node` / `Lexer`），
根因是**次构造器少一条 `LineNumberTable`**：Kotlin 的 `emitBody` 对**每条**体里的指令都发
`visitLabel + visitLineNumber`，而 `this(...)` 对应的 `ICtorDelegate` 虽然自己不发码，仍会把它那一行
写进行号表（`Node` / `BType` 因此整个 LNT 属性 −12 字节，`Lexer` 少一条 −4 字节）。
rsf 侧 `walkStmt` 的 `THIS_CALL` 分支原来什么都不登记，行号便无处附着。修法：该分支**也登记一条
零码的 `label` 指令**（`new Ins("label")` + `lbl` + `line = st.line`），`flushBody` 就会照常发
`visitLabel + visitLineNumber`，与 Kotlin 的 `ICtorDelegate` 逐位对齐。修后
`diff -r build/verify17e/J1/<源> build/verify17e/K/<源>` 对四个 CLI 源**全部 0 差异**，
八示例 acc17d 8/8（备注逐条「字节级也全同」）、`gradlew test` 248 全绿、八示例锚点不变。

**三条遗留缺口已于 2026-10-14 补齐**（子代理 `3d93f715` 实现、父代理独立验收）：
① `pkg a.b` —— 装载器校验（`Sources.rsf` 的 `checkPackage`，报错文案与 Kotlin 逐字对齐）＋后端按包命名
（程序类/用户类内部名、字段与方法描述符、枚举静态 owner、`.class` 落盘路径全带包前缀，见 `pkgPathOf`）；
② `module "目录"` 导入 —— 目录扫描、`*.rsf` 按名排序、`resolveDir` 三个搜索基准（发起文件目录 →
`.` → 根文件目录）、空目录报错，全部对齐 `SourceLoader.kt`；
③ **`?:` 左边的成员调用 / 类内隐式调用** —— 那条硬报错已删除，改按 `BytecodeCompiler.kt:1795-1872`
的 `emitOrElseCall` 四条路走（内置 → 隐式调用 → 顶层/类方法 → 成员），合成方法仍挂 `ctx.owner`
对应的 writer 类（`g.cwNames[g.cwCount - 1]`）。
**独立验收**：6 个探针（`build/verify17e/probeBC/{S,ARG,ModMain}.rsf` + `pkgapp/a/b/PkgMain.rsf`

+ 既有 `build/verify17e/probe/{M,I}.rsf`）与 Kotlin 产物**逐字节相同、运行输出相同**；3 条负例
  （`pkg` 目录不匹配 / `module` 目录不存在 / `module` 空目录）两端都 exit 1、零 `.class`；
  J1 9/9（126 类）、J3 不动点 0 差异、J5 248 全绿、八示例锚点 8/8 不变。
  **仍存边界（已定格，不静默算错）**：装载器报错里的路径候选是**相对路径**（Kotlin 是绝对路径，
  `Sources.rsf` 头部已声明该刻意差异）；rsf CLI 的装载器错误仍只有 `[rsf-17d]` 前缀、没有
  `file:line:col`；`module` 内文件自己写 `pkg` 时只做位置校验、其 `pkg` 被忽略（`rootPackage` 只有一个
  名额，与 Kotlin 一致）；`File.listFiles()` 返回 null 无兜底（调用前已过 `isDirectory()`，实际不可达）；
  `?:` 左边的**外部 JVM 成员调用**按 Kotlin `isErrorCall` 口径走普通可空检查、不进 `emitErrorCallElvis`。
  ④ 其余按 `g.bad` 站点归类的既定边界不变（泛型类型实参文本、`switch` 非枚举、按类名调实例方法、
  非 `PrintStream`/`File`/`StringBuilder`/`ArrayList`/`HashMap` 的 JDK 成员等）。

### 17c 的下一步（rsf 版解释器）—— 已完成，保留作历史记录

按 17b 摸出来的路，第一步应当是**先探一次容器**，而不是直接开写解释器：

1. **先试 `java.util.HashMap` 互操作**（`import java.util.HashMap`，`put` / `get` / `containsKey`）。
   rsf 已经能 `import java.util.ArrayList` 并用泛型集合（阶段 15 的 `generics.rsf`），
   所以大概率能通。**能通就先写一个 `Env` 类把 HashMap 包起来**，符号表就有了；
   不通再手写开放寻址的开放表。
2. **给 17b 的 `Node` 加几个查询辅助**（`childAt(i)` / `attrOf(node, name)`），
   否则 `switch` 分支里到处是 `text` 字符串比较——这是胖节点最难受的地方。
3. **对拍口径先定死**：`rsf run <示例>` 的 stdout 与 rsf 版解释器的 stdout 逐字节比。
   注意 `println` 的换行（CRLF vs LF）与 UTF-8 编码，17a/17b 都是先 `normalize` 再比。
4. **从 `print` / 算术 / 变量开始**，每加一批语法就对拍一次；`rsfSrc/lang/main.rsf`
   是最先能跑通的示例，`files.rsf`（要文件读写）放最后。
5. **别忘 `fail` 那条路**：rsf 版解释器要复刻 `RsfFailure` 的消息文本，
   否则 `errors.rsf` 的 `catch` 打印对不上（Kotlin 版两端一致的前提就是消息一字不差）。

### 阶段 19c 落地后的结论：`enum` + `switch` 能直接用于 17b 的 TokenKind

**结论：能。** 在临时程序里把 `Lexer.rsf` 里 `TokenKind` 的**每一种用法**都按 enum 重写了一遍
（临时程序已删，不进仓库），两条后端输出逐字节一致（372 字节 / SHA256 `87E525F1…`）。
逐条对应关系：

| `Lexer.rsf` 现状（阶段 17a）                                                     | 用 enum + switch 怎么写                                                                                    |
|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| `class TokenKind { public static fn eof() -> string { rtn "EOF" } ... }`  | `enum TokenKind { EOF, IDENT, INT_LIT, ... }` + `TokenKind.EOF`                                        |
| `var kind : string = TokenKind.intLit()`                                   | `var kind : TokenKind = TokenKind.INT_LIT`                                                             |
| `if (kind == TokenKind.longLit()) { rtn "Long" }`（`typeNameOf` 那种十几条 if 链） | `switch (kind) { TokenKind.LONG_LIT : rtn "Long" ... }`；**保留 if 链也照样能编译**                         |
| `if (kind == "KW_TRUE") { ... }`（和字符串字面量比）                                 | `if (kind == TokenKind.KW_TRUE) { ... }`                                                               |
| `fn keywordKind(word : string) -> string { ... rtn "" }`（**空串**当「没找到」）    | `fn keywordKind(word : string) -> ?TokenKind { ... rtn null }` + `if (k == null)` / `k!!` / `k ?: 兜底` |
| `fn punctKind(c : char) -> string`                                        | 同上，返回 `?TokenKind`                                                                                     |
| `fn add(kind : string, text : string, ...)`（kind 当普通参数）                   | `fn add(kind : TokenKind, ...)`                                                                       |
| `var kind = punctKind(c); if (kind.length() == 0) { ... }`                 | `var k = punctKind(c); if (k == null) { ... }`（**「空串哨兵」换成 null，这是唯一需要动脑的地方**）                          |
| `padRight(t.kindName(), 12)`（dump 需要**名字文本**）                              | `padRight("" + t.kind, 12)`——字符串拼接自动调 `toString()`，出来的就是值名                                             |
| 42 条关键字表（`put("if", "KW_IF")` 之类）                                          | 照旧一张表，只是值的类型从 `string` 变成 `TokenKind`（数组 `TokenKind[]` 两端都通）                                           |
| `if (c == '/' && peek(1) == '/')`（只能嵌套 if 写）                               | 一行写完，右边在左边为假时**不会被求值**                                                                                 |

两个必须记住的差别（不影响可行性，但改写时要按新形状写）：

1. **没有「枚举 → 名字字符串」的内置函数**：dump 那类需要文本的地方用 `"" + k`
   （`toString` 就是值名），不要想当然地写 `k.name()` 之类。
2. **空串哨兵改 null**：`?TokenKind` + `?:` / `!!` / `== null` 已经够用，
   比「约定空串代表没有」更不容易错——**漏了判断编译器会拦下来**。

⚠️ **17b 的第一步仍然是把 `rsfSrc/bootstrap/Lexer.rsf` 的词法表补到阶段 19c**
（三个关键字 `enum` / `switch` / `case` + 两个记号 `&&` / `||`）：它是阶段 17a 冻结的产物，
不认识新记号，所以现在那条「rsf 版词法器与 Kotlin 版 token 流逐条一致」的差分对拍
只覆盖阶段 19c 之前的样例（用了新语法的 `enums.rsf` 被分流到另一条测试里显式记录差异）。

**另一件顺手的小事**：两处编辑器关键字表按惯例要一起改（编译器 `EditorSupport.KEYWORDS`、
IDEA 插件的 `RsfKeywords` + `RsfLexer` 运算符表）。阶段 19c 只改了**编译器自己那一处**，
IDEA 插件那张表漏了 `enum`/`switch`/`case`——那边还有一条「与 `Lexer.KEYWORDS` 逐条对齐」的
快照测试要跟着更新。阶段 21 补齐了这三只词，并加了 `EditorKeywordParityTest` 直接读源码守着两边。纯高亮问题，不挡自举。

---

## 依赖关系与顺序

```
12 类 + new ──┬─→ 13 数组 ──┬─→ 16b byte[] + 文件落盘（✅） ──→ 17 闭环（下一步）
              │             │
              └─→ 14 !T ────┘
16a byte/char + 标准库切片（已完成，与 13 无关）
15 泛型（可选，走集合路线才需要）
11 Language Server（与自举无关，随时可插）
```

**12 / 13 / 14 / 15 / 16a / 16b / 18 都已落地**，剩下的关键路径只有 **17（自举闭环）**。

### 阶段 18（自举之前追加的一步）：构造器改成 Kotlin 风格

自举时「用一个类表示 Token / AST 节点」会大量用到构造器，而老写法（`fn 类名(...)`）一个类只能有一个，
于是把构造器的语法与语义重新定了一遍：主构造写在类头、次构造用 `constructor(...)`、委托用 `this(...)`，
并放开**构造器重载**（方法重载后来在阶段 19d 单独做了，见下文；当时仍然不做）。它不挡 16b / 17，但先做完能让后面写 rsf
版编译器时少绕路。

## 顺手该补的小项（不挡路，但自举时天天用）

**17a 写完之后的实测排序**（用 rsf 写词法器时真被卡住/绕开的地方，按疼度排）：

1. **没有 `&&` / `||`**（疼）：`if (c == '/' && peek(1) == '/')` 只能写成
   `if (c == '/') { if (peekAt(1) == '/') { ... } }`，嵌套一深，缩进和括号层数就爆了。
   词法器里十来处判断被这么拆开写。**这是 17b 之前最值得补的一条。**
   → ✅ **已补（阶段 19c，2026-10-08）**：`&&` / `||` 真短路，`&` 仍专用于 `&<...>`。
2. **没有 `enum`**（疼）：`TokenKind` 只能用一串 `public static fn eof() -> string`。
   `switch` 分派也就跟着没了——token 分派是一长串 `if`，而且**拼错一个 kind 名编译器
   不吭声**，要靠对拍测试报「第 N 条不一样」才发现。要么补 `enum`，要么补「字符串常量」
   的专门检查。
   → ✅ **已补（阶段 19c，2026-10-08）**：`enum TokenKind { IDENT, ... }` + `TokenKind.IDENT`，
   写错值名当场报错并列出全部值；编译成普通类（`<clinit>` 里的具名单例）。
3. **`main` 收不到命令行参数**（绕开了）：`fn main() -> void`，JVM 的
   `main(String[])` 接不到。现在用 `System.getProperty("rsf.lex.file")` 绕，跑起来是
   `java -Drsf.lex.file=... -cp out LexerCli`。**自举验证脚本会天天用命令行参数**，
   建议补一个 `fn main(args : string[])`（或内置 `argCount()` / `arg(i)`）。
4. **没有 `switch` / `when`**（别扭）：同 2，token 分派、转义分派都是一长串 `if` + 提前 `rtn`。
   → ✅ **已补（阶段 19c，2026-10-08）**：`switch (k) { case TokenKind.IDENT : ... else : ... }`，
   漏分支在**编译期**报错（不用 else 时必须穷尽，并列出缺的值）。
5. **没有方法重载**（别扭）：`add(kind, text, line, col)` 与 `add(token)` 得写成两个名字；
   库函数想给「默认参数」也只能靠次构造（`Lexer(source)` + `Lexer(source, safeMode)`，
   这个倒是能忍）。
   → ✅ **已补（阶段 19d，2026-10-11）**：顶层函数与类方法都能重载，按「实参个数 → 类型打分
   （精确 0 分、加宽按档位距离）」挑；**最低分并列就报错并列出候选**（不静默取声明在前）。
   `rsfSrc/bootstrap/*.rsf` 里的绕开写法按「自举产物冻结」原则不改。
6. **没有可空返回的 `?` 收尾**（小别扭）：`failLine` / `failCol` / `failMessage` 三个字段
   在「没出错」时是 0 / 0 / ""，靠约定而不是靠类型。
7. **字符串拼接链**（小别扭）：`"a" + x + "b" + y` 长起来以后，嵌在 `if` 里可读性明显下降；
   也**没有字符串插值**。
8. **没有「未使用」警告**（小）：写废的两个辅助方法（`failHere`）编译器不会提醒，
   只能靠读代码发现。
9. **没有 `const`**（小）：`TokenKind` 那串常量每次调用都是一个静态方法调用，
   没法做成编译期常量（对正确性没影响，只是啰嗦）。
10. 接口 / 抽象方法、`?.` 可选链：17a 还没碰到（词法器里没有多少 null 判断），
    但写 AST 遍历（17b）时会立刻需要——**建议和 1、2 一起补**。

- `?.` 可选链（写 AST 遍历时省掉大量 null 判断）
- ~~`enum` 或「类 + 常量」的等价物（TokenKind 用得上）~~ → ✅ 阶段 19c
- ~~`switch` / `when`（可以用 if-else 硬写，但 token 分派会很难看）~~ → ✅ 阶段 19c
- 接口或抽象方法（做 visitor 模式用；没有的话 AST 遍历要写成一堆 if-else）
- ~~`&&` / `||`（现在连逻辑与都得嵌套 if 写）~~ → ✅ 阶段 19c
- `main(args : string[])`（自举脚本要传文件名）

## 已采用的语法（2026-10-08 起按这套写，2026-10-11 更新；要改随时说，代码里就几行）

1. **构造器**（阶段 18 改定）：主构造写在**类头括号**里，次构造用 `constructor(...)`，委托是
   Java 风格的 `this(...)` 首句；`fn 类名(...)` 不再是构造器（明确报错）
   ```
   public class Point(x0 : int, y0 : int) {      // 主构造：参数在字段初值里能用
       var x : int = x0
       var y : int = y0
       constructor() { this(0, 0) }          // 次构造：必须委托（有主构造时）
   }
   ```
2. **字段**：`var 名字 : 类型 = 初值` —— **Kotlin 后缀风格**（类型写在名字后面，
   与局部变量一致），**初值必填**（避开「字段没初始化」，JVM 默认值那套就不用操心了）
3. **变量**：`var 名字 : 类型 = 初值`，类型可省（`var x = 0` 靠推断）。
    - **`var` 是唯一写法**（2026-10-11 用户拍板）：可变变量/字段的关键字由 `varis` **改名**而来。
      `varis` 不再接受，写了直接报
      `error[lexer]: 'varis' 已改名为 'var'：请写成 'var x : int = 0'`。
      `imutvar` **没改名、语义也没变**。
    - 它**不在关键字表里**（`Lexer.KEYWORDS` / `Lexer.rsf` 的 `keywordKind` 现在是 **51 项**、
      与 rsf 版逐条对齐；2026-10-15 自举同步从 45 项补到 51 项），拦它靠词法器里一条独立判断——所以 `varis` 出现在哪儿都报同一句话。
    - 老写法 `varis int x = 0`（类型写在名字**前面**）**已废弃**（2026-10-08 起报错，提示改成后缀风格）
4. **参数**：**两种风格都收、同一个表里可以混写** —— Java 风格 `int a` / Kotlin 风格 `a : int`。
   泛型 `&<...>`、数组 `[]`、可空 `?T` 两种风格下都自动可用
    - **泛型实参表的规范写法是无空格**的 `Box&<int>`（2026-10-11 起全库统一）。
      带空格的 `Box &<int>` **仍然能编译**（词法上空白本来就不重要，**刻意不打死**），
      但新写的代码与文档一律不带空格。
5. **返回子句**：只有 `-> T` 和 `:: T` 两种写法（`:` 那条别名 2026-10-08 已删除，
   理由：`:` 现在专门留给「名字 : 类型」和「if c : 语句」）
6. **构造对象**：`new` 可以省 —— `Point(1, 2)` 等价于 `new Point(1, 2)`。
   判定依据是名字解析（是类 → 构造；是函数 → 调用），前提是**类名与函数名不能重名**
7. **访问控制**：写在最左，**什么都不写就是 `public`**（编译成 `ACC_PUBLIC`）。
   `rsf check` 的清单里缺省那一档也显示成 `public`
8. **数组**：`int[] a`（C 风格后缀）—— ✅ 阶段 13 已落地
9. **出错**：`fail "消息"`（阶段 14 定稿前先按这个写）
10. **`this`**：能省则省；只有字段与参数/局部变量重名时才必须写 `this.x`
11. **`static`**：写在 `<ControlType>` 和 `fn` 之间 —— `public static fn origin() -> Point`
    - 类里：`static` = 用 `类名.方法()` 调；不写 = 用 `对象.方法()` 调
    - 顶层：本来就是静态的，写了只给一条「多余」警告，不拦

## 阶段进度

| 阶段                                 | 状态                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `protected` 关键字（本文件之外的追加需求）        | ✅ 已实现：`ACC_PROTECTED`；Checker 补了可见性规则                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `static` 关键字                       | ✅ 已实现：签名里的 `<static>` 槽                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 12 · 语法层                           | ✅ **完成**：`class` / 字段 / 方法 / 构造器 / `new` / `this` 都能解析成 AST，`Program.classes` 与 `SourceLoader` 已带上类；编辑器关键字表（编译器补全 / IDEA 插件）同步                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| 12 · 语义层                           | ✅ **完成**：类符号表、字段与方法解析、构造器实参匹配、`static`/实例调用方式校验、`private` 成员、`imutvar` 字段只读、未知成员报错时列出可用成员；类型引用接受类名（字段/参数/返回/局部变量都行）；字段赋值语法（`p.x = 1`、`this.x = 1`、方法体里的裸字段名 `x = ...`）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 12 · 后端                            | ✅ **完成**：解释器对象模型 + 字节码生成（`NEW` / `<init>` / `GETFIELD` / `PUTFIELD`）+ 两端一致性测试                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 13 · 数组 `T[]`                      | ✅ **完成**：类型后缀 / `new T[n]` / `a[i]` / `a.length` / 嵌套数组 / 越界两端同一条错误；`rsfSrc/lang/arrays.rsf` + 8 条新测试                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 14 · `!T` + `try`/`catch`          | ✅ **完成**：`fail` / `?:` 兜底 / `!!` 上抛 / `try`+`catch`（按 pc 区间的处理器表，和 JVM 异常表同构）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| 15 · 泛型（可选）                        | ✅ **完成**：`class Box&<T>` + 擦除语义 + JVM 集合互操作                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 16a · `byte`/`char` + 标准库切片        | ✅ **完成**：`toByte` / `toChar` / `charToInt` / `parseInt` / 字符分类…                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 16b · `byte[]` + 文件落盘              | ✅ **完成**：`ClassPath.typeFromDescriptor` 认数组签名（`[B` → `byte[]`）、四个文件函数（`readText`/`writeText`/`readBytes`/`writeBytes`，都是 `!T`）两端消息一字不差、`rsfSrc/lang/files.rsf` 示例 + 两端一致性测试                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 17 · 自举闭环                          | 🚧 **17a 已完成**（2026-10-08）：`rsfSrc/bootstrap/Lexer.rsf`（1011 行，纯库；17a 冻结版是 955 行，17b 补了阶段 19c 的三关键字两记号，2026-10-11 的 `varis`→`var` 迁移又补了老写法拦截）+ `LexerCli.rsf`（40 行入口）；token 流与 Kotlin 版逐条相同（`rsfSrc/lang/**` 全部 16 个 .rsf + 刁钻写法 + 边角形态，编译产物与解释执行两条路都验过），5 条差分测试。**17b 也已完成**（2026-10-08）：`Parser.rsf`（1919 行，纯库）+ `ParserCli.rsf`（62 行入口）；语法树与 Kotlin 版逐行相同（见下面「17b 实际做法」）；17a 的词法器补齐阶段 19c 的三关键字两记号；测试 211 → 216。**17c 也已完成**（2026-10-08）：`Interp.rsf`（2277 行，纯库）+ `InterpCli.rsf`（64 行入口）；八个示例 + 8 份刁钻语料的 stdout 与 Kotlin 版**逐字节相同**，解释与编译两条路都验过；测试 216 → 220。**17d 前置三件也已完成**（2026-10-11）：`byte`/`char` 一元负号的后端缺陷、`HashMap&<K, V>` 多类型实参的擦除互操作、外部类 `new` 的确认与补全（见上面「17d 前置三件」）；测试 221 → **234**（新增 `src/test/kotlin/org/example/InteropTest.kt` 13 条），八示例 SHA256 逐个不变；**17d 字节码后端现在可以开始**。**17d 也已完成**（2026-10-13）：`Backend.rsf`（4929 行，纯库）+ `BackendCli.rsf`（111 行入口）；八示例共 **20 个 `.class` 与 Kotlin 版逐字节相同**（对拍口径与暂不支持清单见上面「17d 实际做法」），运行期 stdout / stderr 也逐个一致；测试仍是 248（本阶段只加 rsf 源码，Kotlin 侧一行没动）。**17e 也已完成**（2026-10-13）：新增装载器 `rsfSrc/bootstrap/Sources.rsf`（多文件 `import "x.rsf"`，语义对齐 `SourceLoader.kt`）、补齐 ASM 26 条 + `java/*` 12 条互操作与 JDK 静态字段机制、顺带修掉 6 处 17d 遗留缺陷；**三阶段闭环成立**——A（Kotlin 编 `BackendCli.rsf`，30 个类）能编 `rsfSrc/bootstrap/` 下 9 个源，A 的产物与 B 的产物 **126 个 `.class` 逐字节相同（B ≡ C）**，八示例经 rsf 版后端编出来的产物与 Kotlin 版**字节级也全同**，`gradlew test` **248 全绿**、八示例 SHA256 与历轮锚点逐个不变；与 Kotlin 产物逐字节相同这一条 **2026-10-14 也已全同**（补齐次构造器的 `LineNumberTable`：`walkStmt` 的 `THIS_CALL` 分支多登记一条零码 `label` 指令，对齐 Kotlin 的 `ICtorDelegate`；修后四个 CLI 源 `diff -r` 全部 0 差异），三处已知缺口于 **2026-10-14 补齐**（`pkg` 声明、`module "目录"` 导入、`?:` 左边的成员/隐式调用；实现对齐 `SourceLoader.kt` 与 `BytecodeCompiler.kt:1795-1872`，6 个探针与 Kotlin 产物逐字节相同 + 运行输出相同，3 条负例两端都拒绝，回归仍 248 全绿、八示例锚点不变）。细节见上面「17e 第一步实际做法与结果」 |
| 18 · 构造器（主构造 + 次构造 + 委托）           | ✅ **完成**：`class P(参数)` 主构造（参数在字段初值里可见）/ `constructor(...)` 可写多个（按实参在编译期挑；阶段 19d 起构造器**不再是**唯一能重载的东西）/ `this(...)` 首句委托（有主构造时次构造必须委托，链不能成环）；每个构造器一条 `<init>`，两端逐字节一致（5 条新测试 + `rsfSrc/lang/class.rsf`、`rsfSrc/lang/arrays.rsf` 已改用新写法）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 19 · 语法现代化（Kotlin 对齐）              | ✅ **完成**：变量/字段类型改后缀 `名字 : 类型`；参数两种风格可混写；返回子句删掉 `:` 只留 `->` / `::`；`fn`/`class` 缺省即 `public` 并在清单里显式显示；构造对象可省 `new`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| — · 语法/风格迁移（`varis`→`var`、泛型实参无空格） | ✅ **完成（2026-10-11）**：可变变量/字段的关键字由 `varis` **改名**为 `var`，`varis` 不再接受（**词法层**一条独立判断拦下，报 `'varis' 已改名为 'var'：请写成 'var x : int = 0'`；关键字表当时是 **45 项**（2026-10-15 自举同步补到 51 项）、与 rsf 版 `keywordKind` 逐条对齐，`varis` **不在表内**）；`imutvar` 未改名未改语义。全库 ` &<` → `&<`（`Box&<int>` 为规范写法，**带空格仍可编译**，刻意不打死）。覆盖面：编译器词法/语法/KDoc、**自举六个 `.rsf`**（`Lexer.rsf` 补同一条拦截 + `legacyVarMessage()`）、`rsfSrc/lang/**` 全部示例、`RsfTest.kt` 源码字符串、README/BOOTSTRAP、IDEA 插件（`RsfTokenType`/`RsfLexer`/`RsfColorSettingsPage`/快照测试）。实测替换：`KW_VARIS`→`KW_VAR` 11 处、`varis`→`var` 1707 处、` &<`→`&<` 325 处（45 个文件）；新增 1 条测试（220 → 221），八示例 SHA256 逐个不变                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 19b · `catch` 双风格 + 编辑器关键字表补全      | ✅ **完成**：`Parser.parseTry` 同时收 `catch (e : string)`（推荐）与 `catch (e string)`（老写法不打死），漏类型时两种写法都提示；`rsfSrc/lang/**`、`RsfTest.kt` 的源码字符串、README/BOOTSTRAP 的示例块统一迁到 Kotlin 风格；IDEA 插件的关键字表补 `pkg`、加了「类型位置上的用户类名」上色，编译器补全表补 `pkg` / `let` / `str` / `boolean`；新增测试 `catch 两种类型写法都能用`（两端逐字节一致）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| — · 拼接对象两端一致（既有缺陷）                 | ✅ **修复**：`emitToString` 对对象类型一律发 `String.valueOf(Ljava/lang/Object;)`（原先发的是不存在的 `valueOf(LXxx;)`，运行期 `NoSuchMethodError`）；给每个用户类生成确定性 `toString()`，格式规则与解释器共用 `sema/Builtins.kt` 的 `ObjectFormat`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 19c · `&&`/`                       |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |` + `enum` + `switch`（为 17b 补的三样） | ✅ **完成（2026-10-08）**：`&&` / `||` 真短路（解释器先算左、假就不算右；字节码 `IFEQ`/`IFNE` + `GOTO` 把右边夹在跳转之后），优先级插在 `?:` 与 `==` 之间；`enum`（顶层声明、允许尾逗号、至少一值、值名/重名检查）**编译成普通类**（`public static final` 单例 + 私有 `$name` 字段 + `<clinit>` 构造 + `toString()` 返回值名），两端打印名字逐字节一致；`switch`（只对枚举、`case 枚举名.值名`、默认分支写 `else`、无 fallthrough、**无 else 时必须穷尽**并列出缺的值）用新指令 `ISwitch` 表达，字节码 `DUP`+`GETSTATIC`+`IF_ACMPEQ` 过一层 `POP` 跳板进分支体。测试 190 → 211（+21），七个示例输出 SHA256 不变，新增 `rsfSrc/lang/enums.rsf` 示例 |
| 19d · 函数/方法重载                      | ✅ **完成（2026-10-11）**：顶层函数与用户类方法（实例 + `static`）**共用一套重载解析**——先按实参个数筛，再按类型打分（精确 0 分、加宽按档位距离），最低分者胜；**最低分并列报错并列出全部候选签名**，不静默取声明在前。擦除冲突（`f(Box&<int>)` 与 `f(Box&<string>)`）编译期拒绝、`main` 唯一入口、`!T` 重载、泛型类方法重载、递归/互相调用都覆盖；选中结果只由 Checker 决定一次（`callTargets` / `implicitCalls` / `userMembers`），两端读同一份。测试 234 → **248**（`OverloadTest.kt` 14 条），八示例 stdout 的 SHA256 逐个不变                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| 20 · `interface`                   | ✅ **完成（2026-10-15）**：`interface`（抽象方法 + 默认方法）、接口继承接口、类实现多个接口、`override` 修饰符（可选，写了必须真覆盖）、JVM 接口互操作（`Runnable` / `Comparator&<string>` / `Iterator`）、源码实现类交给 JDK 代码回调（桥方法 + 解释器 `Proxy`）。设计 `DESIGN-20.md`（D1–D10 全按批准值），M0–M5 全绿，实际做法与遗留见下「阶段 20 实际做法与结果」                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 21 · `@interface`（注解）              | ✅ **完成（2026-10-15）**：注解声明（元素 + 默认值 + 伴生实体）、`@` 使用点、方案 B 访问器、字节码与解释器两端逐字节一致；设计 `DESIGN-21.md`，M0–M4 全绿（456 条测试、J1 126 类不动点、八示例锚点不变），M5 收尾中。**自举链**：第 3 刀（2026-10-15）`Parser.rsf` 收 `annotation` / 元素表 / 伴生实体 / `@` 使用点，AST 与 Kotlin 版逐字节一致（`PROGRAM` 的 `ANNOTATIONS` 桶恒在最后）；自举后端与自举解释器暂按「忽略注解」处理（`Backend.rsf` 跳过 `ANNOTATION_USES`、`Sources.rsf` 保序带上 `ANNOTATIONS`），注解语义仍只在 Kotlin 版里 |
| 28 · 标签名特殊字符 + `@RsfLabels` 元数据 | ✅ **完成（2026-10-18，两刀）**：标签名可用反引号包任意字符（`` `a-b`: ``、``goto `a-b` ``），反引号只给标签名用；每个**有标签**的方法发一条 runtime-visible `@RsfLabels(value = string[])`（声明顺序、不生成注解接口类、描述符悬空、没有标签的方法零字节变化）。28a `5edbd27`（Token/Lexer/Parser/BytecodeCompiler + IDEA 词法 + `LabelMetadataTest`）、28b `cc1bf17`（`rsfSrc/bootstrap/{Lexer,Parser,Backend}.rsf`，asmTable 31→32 补 `AnnotationVisitor.visitArray`）；判据 `build/verify28/`：4 正例 + 5 负例，`bootstrap-parity.sh` 两端 class 逐字节相同（含注解字节），J1 148 类 / diff 0 / 564 tests |
| 27 · 默认开放（删 `open`） | ✅ **完成（2026-10-18，两刀）**：类默认可继承、方法默认可覆盖，`final` 是唯一收窄手段；`open` 变成普通标识符（`open class` 是语法错误），关键字 57→56。27a `5df701f`（Kotlin 词法/AST/语法/语义 + 测试与语料迁移 173 + 35 + 1 处）、27b `20a5e13`（自举 Lexer/Parser/Backend/Interp 同步，`InheritMods` 降为两参）；判据 `build/verify27/` 五条（pos/interp/neg/ast/parity）全 0 + `build/verify24` 回归，551 tests；见 `DESIGN-27.md` |
| 25 · 类继承 | ✅ **K1–K4 已落（2026-10-16）**：Kotlin 式单继承（父类唯一且排最前、`open` / `override` / `final` / `abstract`、`super.f()` 与 `super(接口).f()`、`static` 不继承、禁止字段遮蔽、泛型父类）+ **允许继承 JDK 类**（`Exception` / `AbstractList&<int>` / `ClassLoader`：final 父类编译期拒、抽象成员漏实现编译期拒、`super.getMessage()` 发 `INVOKESPECIAL` 打到声明类、父类构造器由 Checker 选中一次记在 `SuperDelegation.jvmCtor`、`protected` 构造器解释器走子类桥）。设计 `DESIGN-25.md`，K1 `6acda2e` / K3 `7f8e62e` / K2 `622770c` / K4 收口（探针台 `build/verify25/` 16 正例 + 16 负例）。**K5 自举同步（`rsfSrc/bootstrap/**`）✅ 完成（2026-10-17）**：K5a `14a6b0a` 词法+解析 / K5a-fix `1ca8abd` 类头修饰词顺序 / K5b `69916ab` 字节码后端 / K5c `45201d7` 解释器 / K5d 收口（`rsfSrc/lang/inherit.rsf` 闭环示例 + 九示例锚点）；J1 148 类、diff 0 |
| 23 · 显式指针（C 风格）                  | ⏳ **计划（2026-10-15 用户提出）**：想要 `&x` 取地址 / `*p` 解引用 / `p->f` 这套写法（Java/Kotlin 默认隐式引用）；JVM 上没有裸地址，计划分 L1 引用可见 / L2 可写基本类型指针 / L3 数组指针与偏移三档，只加新写法、旧语料零改动，语法等用户写进 `rsfSrc/语法预案/`；见 `DESIGN-23.md` |
| 24 · 类型转换与类型判断（`as` / `?as` / `is` / `!is`） | ✅ **完成（2026-10-18，四刀）**：`x as Foo` 失败抛 `RuntimeException`（文案 `rsf: 不能把 <实际 JVM 全名> 当成 <目标 JVM 全名>`）、`x ?as Foo` 失败给 `null`（`?` 前置）、`x is Foo` / `x !is Foo` 判类型；只做引用类型 / 数组 / 非空收窄，泛型按擦除判（写实参发 stderr 警告），不做智能转换、不许链式。24a `076aeee` Kotlin 两端（词法/AST/语法/语义/字节码/解释器/编辑器 + `CastTest`）、24b `5b5d7aa` 自举词法+解析（关键字 55→57）、24c `707cbe3` 自举字节码（`__rsf_cast` 辅助方法 + `INSTANCEOF`/`CHECKCAST`）、24d `51bd707` 自举解释器（`isInstanceOf` 值种类分派 + 类链/接口闭包）；判据台 `build/verify24/`（9 正例 + 8 负例 + `rsfinterp-verify.sh` 14 份），J1 148 类 / diff 0 / 551 tests；见 `DESIGN-24.md` |

**阶段 25 K5（自举同步）收口（2026-10-17）**：K5a 把 `open` / `final` / `abstract` / `super` 四个关键字与类头、`super(...)` / `super.f()` 收进 `Lexer.rsf` / `Parser.rsf`；K5a-fix 把类头修饰词的顺序对齐 Kotlin；K5b 在 `Backend.rsf` 收字段布局、构造链与 `INVOKESPECIAL`；K5c 在 `Interp.rsf` 收父链字段、虚分派与构造顺序；K5d 用 `rsfSrc/lang/inherit.rsf` 把它钉成九示例锚点。**限制**：自举后端在有类继承的程序上会把类头骨架写进产物目录，跑产物时要把该目录挂上 `-cp`；无继承程序逐字节与旧版一致。


**第 28 刀：标签名特殊字符与 `@RsfLabels`（2026-10-18）**：标签名现在有两种写法——普通标识符，或用反引号包住任意字符（`` `a-b`: ``，`goto` 后面同理）；反引号**只**给标签名用，变量 / 字段 / 函数名仍只收 Unicode 字母、数字、下划线，写错位置会得到专门文案。标签仍是函数作用域、查重在**去掉引号之后**的名字上做，所以 `` `finish` `` 与 `finish` 是同一个标签。每个**有标签**的方法在 `visitMaxs` 之前发一条 runtime-visible 注解 `@RsfLabels(value = string[])`，`value` 按**声明顺序**列出该方法里的标签名（`goto` 首次提到但没声明的不算），描述符故意悬空、不生成注解接口类——`javap -v` 与 ASM 看得见，反射读不到；没有用户标签的方法一个字节都不发，旧语料产物逐字节不变。两端（Kotlin `BytecodeCompiler.emitLabelMetadata` 与自举 `Backend.rsf` 的 `flushBody`）产出的 class 由 `build/verify28/bootstrap-parity.sh` 逐字节对拍。
**第 26 刀：`LocalVariableTable`（2026-10-17）**：Kotlin 版与自举版后端现在都在**每个有 `Code` 的方法**上发方法级 `LocalVariableTable`（`javap -l` 与 IDEA 调试器可见参数名/局部变量名）。两端同一口径：`visitCode()` 后立刻发起点标签、实例方法登记 0 号槽的 `this`；条目只来自 rsf 符号表——形参按声明序、局部变量按槽位升序（含 `catch` 绑定），`signature` 一律 `null`（不发 `LocalVariableTypeTable`）；`visitMaxs(0, 0)` 前发结束标签再逐条 `visitLocalVariable(...)`，起止都是**整个方法**。编译器自己造的槽（入口 `main` 的 `args`、异常处理槽、注解访问器合成槽、枚举 `<clinit>` 的合成变量）不进表；桥方法 / `toString` / 枚举 `<init>`·`toString` / 隐式无参构造器 / 注解元素访问器只有 `this` 一条。指令、常量池顺序、行号表一个字节都没动（`LocalVariableTable` 是追加的调试属性），旧语料 stdout 不变；`Backend.rsf` 的 `asmRow` 表补第 31 条 `MethodVisitor.visitLocalVariable`。

## 阶段 20 / 21：`interface` 与 `@interface`（排在自举闭环之后）

这两样此前不在计划里，2026-10-08 定位如下。

**为什么排在 17b~17e 之后**：

1. **自举不需要接口。** 17a 与 17b 已经用事实证明了：只靠类、构造器、`enum`、数组、`!T` 就能写出词法器（1011 行）与语法器（1919
   行），token 流与语法树都与 Kotlin 版逐字节/逐行一致。而且 `switch` 到位之后，「按 kind 分派的树遍历」（
   `switch (node.kind) { NodeKind.BINARY : ... }`）就是这门语言的惯用写法，访问者模式不是必需的——17b 的 `kindName`
   那个穷尽 switch 还顺手换来了「漏一个节点种类就编译报错」。把接口插在 17c/17d 前面只会推迟自举，不会让它更容易。
    - 例外：如果希望自举版编译器**用访问者模式写得漂亮**，接口就得提前——先让它跑起来、之后再重构，是更划算的顺序。
2. **`@interface` 必须排在 `interface` 后面，顺序不能反。** 这是 JVM 的硬约束：`public @interface Marker { }` 在字节码里就是
   **一个继承 `java.lang.annotation.Annotation` 的接口**，元素是接口方法，`@Retention` / `@Target` 是加在它头上的元注解。没有接口就没有注解。

**各自解锁什么**：

| 特性           | 解锁的能力                                                                                                                 |
|--------------|-----------------------------------------------------------------------------------------------------------------------|
| `interface`  | 多态 + **JVM 互操作**：rsf 类可以**实现别人已有的接口**（`Comparator`、`Runnable`、`Iterable`），把 rsf 对象交给 Java API 才真正可用；也让「一个变量装多种实现」成为可能 |
| `@interface` | ① **标记编译产物**——「`.class` 别被完全当 Java 看」的另一条实现路径（`@RsfCompiled` 注解，用来替代或补充自定义 class 属性）② 语言内的标记与弃用警告 ③ 将来给 codegen 的提示   |

**语法提案（Kotlin 风格，沿用既定的继承写法，待拍板）**：

```
public interface Greeter {
    fn greet(name : string) -> string
    fn shout(name : string) -> string {   // 接口里的默认实现（可选）
        rtn toUpper(greet(name))
    }
}

class Dog(name : string) : Greeter {       // 实现：沿用继承的 ":"
    public fn greet(n : string) -> string { rtn "woof " + n }
}

class Robot : Greeter, Runnable { }        // 多个用逗号

public @interface Marker { }               // 阶段 21
@Marker class Foo { }
```

继承与实现**共用 `:`**（与已定的 `class Dog : Animal` 一致）。**阶段 20 的细节已在 `DESIGN-20.md` 定完并全部落地**
（默认方法、多接口同签名冲突、接口继承接口、接口里不许有字段、类继承本批不做，见下面「阶段 20 实际做法与结果」；
**「类继承本批不做」是阶段 20 当时的排期**——2026-10-16 的阶段 25 已经把它做完了，见上表第 25 行）；
**阶段 21 的注解细节（元素类型、是否带参数、`@Retention`/`@Target` 的处理）仍未定**，开工前照规矩先出设计。

### 阶段 20 实际做法与结果（2026-10-15 完成）

实现设计见 `DESIGN-20.md`（用户已批准按推荐值 D1–D10 全做；§9 是 M0–M5 顺序，§10 M4 缺口表，
§11 `override` 语义，§12–§14 是三轮独立复验记录）。**M0–M5 全部完成**，测试 **314 → 318**。

**采用的语法**（除 `override` 外都是原提案，`override` 是用户 2026-10-14 拍板的增量）：

```
public interface Greeter {
    fn greet(name : string) -> string          // 抽象方法，不写体
    fn shout(name : string) -> string { rtn greet(name) + "!" }   // 默认方法，有体
}
public interface Loud : Greeter { ... }          // 接口继承接口（D4）
class Dog(name : string) : Greeter { ... }       // 实现；多个用逗号：class Robot : Greeter, Runnable
class Dog2(name : string) : Greeter {
    public override fn greet(name : string) -> string { ... }    // override 可选，写了必须真覆盖
}
```

**M0（开工前的 P0 修复）**：接口类型的接收者调用，字节码后端原来恒发 `INVOKEVIRTUAL` ⇒ 运行期
`IncompatibleClassChangeError: Found interface java.util.Iterator, but class was expected`，而解释器照常输出——**两端背离
**。
探针 `build/verify17e/probeIF/Iter.rsf`。修法：`ResolvedMethod` 加 `interfaceOwner`（解析时由 `JvmClass.isInterface` 定一次），
调用点按它选 `INVOKEINTERFACE`，`visitMethodInsn` 第 5 个实参 `isInterface` 同步为 true。

**M1+M2（语法 + 语义）**：`KW_INTERFACE`、`InterfaceDecl`、`ClassDecl.supertypes`、`FuncDecl.body` 可空（抽象方法）、
编辑器两处（编译器补全 / IDEA）；Checker 侧接口表、实现配对（rsf 接口按签名严格配，JVM 接口按名字+参数个数配，
因为擦除后的签名 rsf 侧写不出来）、源码子类型判定、`new I()` 拒绝、多接口同签名默认方法冲突（D5：类里必须显式覆盖）、
接口继承成环报错、接口里不许写字段（D3）。父代理补 `promoteCommon`：`?:` 与三目在「错误返回的接口类型 vs 实现类」上
收敛到**接口**而不是 `Object`（`RsfType.promote` 两类型不同就只给 `java/lang/Object`）。

**M3（两端代码生成）**：接口 `.class` 是 `ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT`、超类 `java/lang/Object` 且*
*不带 `ACC_SUPER`**、
不发字段不发 `<init>`、抽象方法 `ACC_ABSTRACT` 且无 `Code`、默认方法走普通方法发射；类头的 interfaces 数组来自
`model.superInterfacesOf`；接口方法/默认方法/接口里的隐式调用一律 `INVOKEINTERFACE`（*
*漏一处就 `IncompatibleClassChangeError`**）；
接口里的 `__rsf_orelse$N` 必须发 InterfaceMethodref；解释器按运行期真实类虚分派（`callInterfaceMethod` /
`findInterfaceImplementation` / `findInterfaceDefault`），并新增 `RsfFailure.dispatched` 让默认方法里的 `?:` 兜底认得出失败来源。

**`override`（用户 2026-10-14 拍板：可选、类与接口都能写、写了必须真覆盖）**：独立复验抓出并修掉两个真缺陷——
① 解释器 `callInterfaceMethod` 对默认方法做**静态分派**（静态类型是父接口、运行期对象是覆盖过该默认方法的子接口实现时，
解释器打 `n`、字节码打 `p`），改成「先 `findInterfaceImplementation` 再回退」；② `Checker.checkClassCovers` 找实现时
只比名字与形参，**private/protected/static 方法被当成接口实现**（`check`+`run` 都过，`java` 一调就 `AbstractMethodError`），
拆成 `shapeOk`/`shapeMatches` 两个谓词并加专门报错分支，同一判据也进 `checkDefaultConflicts`。细节见 `DESIGN-20.md` §13。

**M4（互操作验收）**：三条缺口一对一消掉，`build/verify20/run-probes.sh` 从 19/22 变 **22/22**——
① `interop/MemberResolver.kt` 加 `sourceSubtype` 谓词、`argCost` 认「源码类 → JDK 接口」这一跳
（`Collections.sort(xs, new ByValue())`、`new Thread(job)` 不再报「没有匹配的静态方法/构造器」）；
② `codegen/BytecodeCompiler.kt` 的 `userClass` 调 `emitBridges` 发 `ACC_PUBLIC|ACC_BRIDGE|ACC_SYNTHETIC` 桥
（擦除后接口是 `compare(Object,Object)I`、实现类是收窄签名，没有桥经接口回调就 `AbstractMethodError`），
**只在描述符真不同时才发**、**只允许收窄**；③ `interp/Interpreter.kt` 给 `RsfObject` 套 `java.lang.reflect.Proxy`
（`adaptJdkArg` / `javaProxyOf` / `javaInterfacesOf` / `javaHandler` / `unwrapProxy`），消掉解释器里
`RsfObject is not an instance of Runnable` 与 `argument type mismatch`。细节见 `DESIGN-20.md` §14。

**验收（父代理每轮自己重跑，不看自报）**：

| 项                                                            | 结果                                                                                                                                    |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `gradlew cleanTest test jar`                                 | **318 全绿**（`RsfTest` 222 / `InterfaceTest` 41 / `InterfaceRuntimeTest` 15 / `InteropTest` 14 / `OverloadTest` 14 / `OverrideTest` 12） |
| `bash build/verify20/run-probes.sh`                          | **22 通过 / 0 失败**（9 条运行时两端逐字节 + 13 条 javap 形状）                                                                                         |
| `bash build/verify20/verify-override/check-override.sh`      | **13 通过 / 0 失败**（含擦除性：同名源只差 `override` 关键字，三个 `.class` 逐字节相同）                                                                         |
| `bash build/verify17e/accept-after.sh`                       | J1 9/9＝126 类、`diff -r 差异条目=0`、J2 残差段空、J5 八示例锚点 8/8、**失败项合计 0**（自举链无回归）                                                                |
| `pwsh -File build/tmp17d/acc17d.ps1 -Bout build/verify17e/A` | 八示例**结构+运行 8/8**，逐条「字节级也全同」                                                                                                           |

**遗留与暂不支持（都显式报错或已定格，不静默算错）**：

- **自举版已同步（2026-10-15）**：`rsfSrc/bootstrap/` 的 `Lexer.rsf`（关键字 45 → 51 + `@` 记号）、
  `Parser.rsf`（`interface` / `override` / 父类型子句 / `annotation` 声明与 `@` 使用点，AST 与 Kotlin 版逐字节一致）、
  `Backend.rsf` + `Interp.rsf` + `Sources.rsf`（按接口发 `ACC_INTERFACE` / `INVOKEINTERFACE`、默认方法分派、
  接口链查找、多文件顶层 `INTERFACES` 保序）；**阶段 21 的注解也整条同步**：第 4a 刀 `Backend.rsf`
  （注解接口 `ACC_ANNOTATION` + `@Retention(RUNTIME)`、元素方法 + `AnnotationDefault`、伴生类与
  `RuntimeVisibleAnnotations` 使用点、方案 B 懒构造访问器——**宿主是接口/注解声明时不发
  `ACC_SYNCHRONIZED`**，Kotlin 侧同样的 bug 已修）、第 4b 刀 `Interp.rsf`（元素字段与默认值回填、
  伴生实体执行、元素访问器非虚分派）、第 5 刀嵌套类型名 `Marker::Impl`（类型/字段/形参位置 +
  `new Marker::Impl(...)`）。判据：`build/verify21/bootbackend/run-probes.sh both` 的 12 个正例
  （`p1`–`p7` / `a1`–`a6`）四列 stdout 一致且**类字节差异 0**，`build/verify21/knife-verify.sh` 三件套全绿
  （**J1 134 类** / diff 0、八示例一致）。**注意**：自举源自身的代码风格还没改写成接口 / 注解风格。
  `Backend.rsf` 的 JDK 成员表仍没有 `iterator()` 这类成员（落在既有的「暂不支持清单」里）。
- **类继承不做**（D2，**阶段 20 当时的结论**）：`class A : SomeClass` 会在 Checker 里显式报「rsf 现在还不支持类继承（本阶段只做接口）」。
  → **阶段 25 已经做掉了**（2026-10-16，K1–K4，见上表第 25 行与 `DESIGN-25.md`）：Kotlin 版能编能跑，
  但**自举链还没同步**（K5 待做），所以 `rsfSrc/bootstrap/**` 里目前仍写不出类继承。
- **跨边界时不支持 `Object` 成员**：`p.toString()` 仍报「类 'P' 里没有方法 'toString'」（属 D2/类继承遗留；阶段 25 只放开了**父类链上 JDK 类**继承来的成员，`Object` 三兄弟本身仍未接）。
- **`pkg a.b` 下的接口落在默认包**（`classInternalName` 只给 `classes`/`enums` 加包前缀）：实测两端都跑通、
  产物是 `out/Greeter.class` + `out/app/Dog.class`，一个程序只有一个 `pkg` 所以不会撞名，暂不改。
- **桥只发「能收窄」的那些**（`fromPrim && !toPrim` 一类放弃）：宁可运行期 `AbstractMethodError`（能定位），
  也不发语义不对的桥（会静默算错）。
- **JVM 接口的实现配对刻意宽松**：只按名字 + 参数个数配，返回类型不严比（`isAbstract`/擦除签名在 rsf 侧写不出来）。

### 阶段 19d：函数/方法重载（2026-10-11 完成）

**它当初被排在「与阶段 20 / 继承同批做」**，理由是继承与接口都要求同一张方法表从「单条」变成
「候选集」。实际做的时候发现**机器早就齐了**（`ClassInfo.methods` 之外，方法的参数表、返回类型、
降级结果本来就按 `FuncDecl` 索引），不需要等继承，于是单独做了这一阶段。接口（阶段 20）已在
**2026-10-15 完成**——它做的正是「候选集之间按签名配对（覆盖/实现）」；**阶段 21（`@interface`）
仍未开工**，顺序上必须排在接口之后（JVM 里注解就是继承 `java.lang.annotation.Annotation` 的接口）。

#### 口径（用户拍板，不可擅改）

重载分三种情况，**只有第三种动了**：

| 场景                                    | 支持重载吗                | 挑选规则                                                         |
|---------------------------------------|----------------------|--------------------------------------------------------------|
| 用户自定义的**顶层函数**与**类方法**（实例 + `static`） | ✅ 支持（阶段 19d）         | 见下                                                           |
| **构造器**                               | ✅ 支持（阶段 18，**规则不变**） | 按个数筛 → 类型打分（精确 0 分、可隐式转换 1 分）→ **同分取声明在前**（主构造排最前）           |
| **JVM 互操作**（外部类的方法 / 构造器）             | ✅ 天然支持（未改）           | `MemberResolver` 按精确/加宽打分选；解释器按「声明类 + 精确参数」反射取方法，与字节码选到同一个重载 |

**函数/方法重载的挑选规则**（`Checker.resolveOverload`，顶层函数与类方法**共用同一套机器**）：

1. **先按实参个数筛**：个数不同的候选直接出局；
2. 再按类型打分，**每个实参的代价相加**：
    - 精确匹配 → **0 分**；
    - 隐式**加宽** → 按**档位距离**计分。阶梯是 `byte` 0 < `int` 1 < `long` 2 < `float` 3 < `double` 4，
      所以 `byte → int` 1 分、`byte → long` 2 分、`int → double` 3 分——**窄加宽压过宽加宽**；
      `char` **不在阶梯上**（它跟任何数值之间都不能隐式转，见 `RsfType.kt` 的加宽表）；
    - `null` 字面量给引用类型、`0..127` 的整数字面量给 `byte` 各记 1 分；
    - 任何一项对不上（含窄化、`char`/`int` 互转、`bool` 给数值形参）→ 这个候选**整个出局**；
3. 总分**最低**者胜；**最低分并列就报错**，把并列的候选签名全列出来，
   **绝不静默取「声明在前」**（这正是与构造器规则的**唯一差异**，见下）。

**与构造器的规则差异（必须记住的一条）**：构造器同分时取声明在前的那个（阶段 18 的老规矩，
有既有测试钉着，本次**不动**）；函数/方法同分时**报错**。两者因此可能在同一个程序里
对「同分」给出不同结果——这是**有意**的，不是漏洞。文档与报错文案都写明了各自的规则。

#### 擦除冲突（编译期硬拦）

同名重载必须在 JVM 上是**不同的方法**。判据是**参数擦除后的描述符**（`RsfType.descriptor`）：

- `f(Box&<int>)` 与 `f(Box&<string>)` 描述符都是 `(LBox;)…` ⇒ **拒绝**，报错里写明
  「参数擦除后的描述符都是 (LBox;)」并列出两处签名与行号；
- **顶层函数之间、同一个类的两个方法之间都适用**（顶层函数全编进同一个程序类的静态方法）；
- **返回类型不参与判据**：`f(int) -> int` 与 `f(int) -> string` 一样拒绝（调用点 `f(1)` 说不清该返回什么）；
- 泛型类体里的类型参数擦成 `Object`，所以 `fn put(v : T)` 与 `fn put(v : int)` 不算冲突
  （`Ljava/lang/Object;` vs `I`）；
- **构造器不走这条检查**：构造器的重复签名检查还是阶段 18 那一套。

签名**源码写法也完全一样**的（`f(a : int)` 两遍）报的是老的「重复定义」文案，
只有「源码写法不同、擦除后撞车」才报擦除冲突——两种错因分开说。

#### 入口 `main` 唯一

- 入口判据仍是 `name == "main" && params.isEmpty()`（`BytecodeCompiler.isEntry`）。
  `main(a : int)` 现在是**合法重载**，但**不是入口**——`rsf run` 与 `main(String[])` 都只认无参的；
  `SemanticModel.entryProblem` 专门把「有 main 却带参数」说成参数的问题，而不是「没有 main」。
- **两个无参 `main()`** 报「入口歧义」（它们当然也是擦除冲突，但入口这条文案更贴用户意图）。

#### 选择结果只算一次，两端读同一份

「这次调用选中哪个 `FuncDecl`」由 Checker 决定一次，写进语义模型：

| 调用形态                      | 记在哪                                                   |
|---------------------------|-------------------------------------------------------|
| 顶层函数 `f(...)`             | `SemanticModel.callTargets[CallExpr]`                 |
| `对象.m(...)` / `类名.m(...)` | `ClassModel.members[MemberExpr]`（`UserMember.Method`） |
| 类体里裸写 `m(...)`            | `ClassModel.implicitCalls[CallExpr]`                  |

解释器与字节码后端**都只读这三张表**，谁都不再自己解析一遍——这正是「两端逐字节一致」
在重载上的保证方式。字节码那边 `INVOKEVIRTUAL` / `INVOKESTATIC` 本来就带描述符，发射侧没改；
解释器按「声明类 + 精确参数」反射取方法，也没改。

#### 可见性与 static 的过滤顺序

候选**先按可见性过滤、再按 static/实例一致性过滤、最后才打分**：

- 顶层 `private` 函数只有它自己那个文件能调（跨文件过滤掉），全都调不动才报 private 的错；
- 类方法的 `private` 只在类体内可见，同理；
- `对象.m(...)` 只要 `isStatic == false` 的候选、`类名.m(...)` 只要 `isStatic == true` 的候选；
  一个名字下既有 static 又有实例重载时，各挑各的，不会互相挤掉；
- 类体里裸写 `m(...)`（隐式 `this` / 隐式类名）**不做** static 过滤——两种都能这么调。

**这条顺序是有意的**：如果先打分再过滤，一个「看不见的重载」可能把看得见的那个挤掉，
用户会收到一句莫名其妙的歧义报错。

#### 泛型函数不支持（明确报错）

`fn pick&<T>(a : T)` **不支持**——rsf 的类型参数只能写在 `class` 上（阶段 15 的既定范围，
与重载无关）。以前这种情况只会得到一句「函数名后面缺少 '('」；阶段 19d 在 `Parser.parseFuncDecl`
里加了一条专门判断，看见函数名后的 `&` 就报：
「泛型函数还不支持：类型参数只能写在 class 上……想要「什么类型都能收」的函数，就为每种类型
各写一个重载」。**泛型类的方法可以重载**，解析时会先把类型实参替换进去再打分
（`Box&<int>` 上 `put(T)` 的形参就是 `int`）。

#### 落地清单

1. `Checker.ClassInfo.methods`：`LinkedHashMap<String, FuncDecl>` → `LinkedHashMap<String, MutableList<FuncDecl>>`；
2. **顶层函数的四张表也一并按 `FuncDecl` 索引**（`paramBindings` / `paramTypes` / `returnTypes` / `lowered`），
   `functionTable` 改成 `名字 → 候选列表`——顶层函数不再是「按名字唯一」，否则同名重载互相覆盖；
3. `SemanticModel` 的 `functionTable` / `paramBindings` / `paramTypes` / `returnTypes` / `lowered`
   同步改型，`paramsOf` / `paramTypesOf` / `returnTypeOf` / `loweredOf` 一律先按 `FuncDecl` 查；
4. 新增 `Checker.resolveOverload` / `argCost` / `widenDistance` / `OverloadCandidate`（通用打分器）
   与 `checkErasureConflicts`（擦除冲突 + 入口歧义 + 重复定义）；
5. 五个读点改完：`checkCall`（顶层）、`checkUserMember`（`对象.m`）、`checkImplicitCall`（类内裸写）、
   `checkMemberVisible`、`signatureOf`；
6. 新增 `src/test/kotlin/org/example/OverloadTest.kt`（**14 条**），
   既有那条 `方法不许重载` 改成 `类名不能和函数名重名 方法可以重载`（断言新行为）；
7. 回归：234 → **248** 条全绿，八个示例两端一致、stdout 的 SHA256 逐个不变。

#### 明确不做

- **`rsfSrc/bootstrap/*.rsf` 一行不改**：`matchAny2/3/4`、`Env.find` / `lookup` / `assign` 那些
  绕开重载的写法保持原样（它们被自举差分测试逐字节钉住）。重载只对**新写的** rsf 代码开放。
- **构造器的重载规则不动**（同分取声明在前）。
- **`MemberResolver`（外部类方法的重载选择）不动**。
- 可变参数 / 默认参数仍然不做（那是另一件事）。

**阶段 18 的实现口径（和最初的计划不同）**：构造器**复用 `FuncDecl`**（加 `isConstructor` /
`isPrimary` 两个标记），主构造在解析时合成一个「名字 = 类名、体为空块」的 FuncDecl 塞进
`ClassDecl.methods` 的最前面。于是槽位分配、降级、解释器、字节码后端一行都不用为构造器分家
（`emitUserConstructor` 只是按构造器个数发多条 `<init>`）。两个配套改动：`ControlFlow.definitelyAssigned`
要能吃**空指令序列**（主构造体是空的），解释器的 `construct` 改成「实参在调用方求值 → 走到委托链的根
→ 在根构造器的帧里跑字段初值 → 各层构造器体依次跑完」（字段初值因此能引用主构造参数）。

期间的安全网：类与 `new` 在**后端**接通之前一律明确报错（「阶段 12 进行中：已经能通过类型检查与成员解析，解释器对象模型与字节码生成是下一步
12-C」），
不会被静默忽略——「编译成功但什么都没生成」是最难查的失败方式。这条守卫在 `check()` 的**最后**，
所以类相关的诊断（字段类型、构造器实参、调用方式、可见性……）都会先正常报出来。

### 阶段 12 落地时的两个实现决定（与原计划不同，但更省事）

- **用户类的类型不用新加 `RsfBase.CLASS`**：直接复用 `RsfType.external(类名)`。rsf 生成的类都在默认包里，
  类名本身就是合法内部名，JVM 描述符正好是 `LPoint;`——少加一个枚举值，就少一批 `when` 要跟着改。
- **字段赋值单列 IR 指令 `IFieldAssign`**，不复用 `IAssign`：后者写的是局部变量槽（有 `binding.slot`），
  前者要先求值接收者再 `PUTFIELD`，两端实现路径不同。

## 搁置：`mathematic`（数学类修饰词）—— 2026-10-15 移出计划

**2026-10-15 用户决定**：`mathematic` 保留为关键字，但这一阶段**移出计划** ——用户原话「目前很长一段时间我应该都想不出该干嘛」。本节只留历史意图与待决点，将来想起来再捡回来（阶段号 22 空出）。

**来源（2026-10-13 用户提出）**：加一个 `mathematic` 修饰词（与 `class` / `interface` 同级），声明
「这是一个数学类」，便于**按数学习惯写纯函数计算**。类里具体放什么（一个值、若干函数……）
**用户明确说到做到的时候再定** —— 所以这一阶段现在只登记意图与待决点，**不拍板语法、不动代码**。

**为什么排在 17e 之后**：它不影响自举（自举只需要类、构造器、`enum`、数组、`!T` 与 `switch`，
17a~17d 已经用事实证明），而且它要动的是「类声明头 + 成员表」这片语义区域，与阶段 20
（`interface`）、21（`@interface`）落在同一处，挨着做能共用同一批改动。

**意图**：数学类里的东西天然是**无状态的纯函数**，「先建对象再调方法」在数学写法里是多余噪音。
目标是让这类代码长成 `square(3.0)` 而不是 `Math.square(3.0)`、让函数体直接写 `= x * x`
而不是 `{ rtn x * x }`。

**待决点（开工前逐条定，用户拍板后才写代码）**：

| # | 待决          | 备选                                                                       |
|---|-------------|--------------------------------------------------------------------------|
| 1 | 成员默认性       | 成员是否**隐式 `static`**（省掉「建实例」这一步）；要不要禁止实例字段                                |
| 2 | 类能否实例化      | 像 Java `Math` 那样不可实例化（`final` + 私有构造器），还是允许                              |
| 3 | 函数体写法       | 表达式体 `fn f(x : int) -> int = x * x`；更激进的 `f(x) = x * x` 纯数学形状           |
| 4 | 调用时「打开」     | 类内成员在别处能否**裸调用**（`using Math` 之类），否则只省了 `new`、没省类名                       |
| 5 | 那个「值」       | 常量怎么声明/访问、是否编译期常量（`static final` 内联进常量池）                                 |
| 6 | 符号与运算符      | 数学符号是否进语言（`^` 乘方、`√`、`∑`）；`^` 目前不是 rsf 运算符，加了就占掉一个符号，且**必须两端一致 + 解释器同步** |
| 7 | 数值语义        | 是否强制浮点、整除怎么写；混算提升**建议沿用现有 `RsfType` 规则**，不新开一套                           |
| 8 | 生成物         | 大概率仍是「全 `static` 方法的普通类」，不新增字节码形态 —— 与「两端逐字节一致」的硬约束天然兼容                  |
| 9 | 与 20/21 的关系 | `mathematic` 是否算 `interface` 的一种特例；若先做了 20，能不能复用成员表机制                    |

**形状草案（仅示意，未拍板）**：

```rsf
public mathematic Math {
    imutvar pi : double = 3.141592653589793        // 待决点 5
    fn square(x : double) -> double = x * x       // 待决点 3：表达式体
    fn hyp(a : double, b : double) -> double = sqrt(square(a) + square(b))
}

using Math                                         // 待决点 4：裸调用
println(hyp(3.0, 4.0))
```

**落地口径**：与 20/21 相同 —— 开工前先把实现设计（数据链路、分层、构建/运行期细节、难点风险、
上表待决点）交用户审阅，确认后再动代码；「解释器与字节码两端逐字节一致」这条硬约束不变。

**已经落地的第一步（2026-10-15，用户拍板）**：`mathematic` 先收成**保留关键字**——进词法表、
IDEA 高亮表与它的快照（`EditorKeywordParityTest` 盯着），但不进访问控制 / 修饰词 / 类型集合。
现在写它会在顶层报「阶段 22 的预留关键字（数学类）：语法还没拍板」，当名字用也会被拦下。
语法本身仍未拍板，本节其余内容不变。


