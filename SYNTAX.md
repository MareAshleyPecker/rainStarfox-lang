# rsf 语法总表

对应版本：v0.9 · 2026-10-19。权威细节以编译器实现与 `README.md` 为准；
本表是**一份从上到下能扫完的清单**，标了每条的落地状态。

## 0. 文件、注释、语句分隔

```rsf
// 行注释
/* 块注释，可跨行 */

pkg demo.tokens          // 包声明：文件必须真的在 ./demo/tokens/ 下

import java.lang.Math    // ① JVM 类（点分）
import "lib/util.rsf"    // ② 另一个 rsf 源文件（字符串路径，可跨行、空白忽略）
module "plugins"         // ③ 一个目录顶层的所有 .rsf（不递归，按文件名排序）
```

- 语句之间用**换行**分隔，也可以写可选的 `;`。
- `rtn` / `return` 独占一行 = 不带值返回；带值就把值写在**同一行**（`rtn x + 1`）。
- 一个程序就是**一个类**（类名取根文件名）；`import` 是包含式的，最终编成一个类。

### 0.1 文件级指令

```rsf
target native             // 整份文件按 native 侧编（指针只有 native 侧能用）
gc on                     // 这份产物要 GC（只认带 main 的文件）；默认 off
#NoWarning!!(unreachable) // 压掉这一类型的警告；也可以写 (all) 或逗号分隔多个
```

| 指令 | 写在哪 | 说明 |
| --- | --- | --- |
| `target native` / `target jvm` | 文件头 | 两份都写报错；声明级的 `native` / `jvm` 修饰符可以覆盖单个声明 |
| `gc on` / `gc off` | 文件头，且必须是带 `main` 的那份 | 整份产物一个开关；C 后端装内置标记-清扫 GC，JVM 侧是空操作 |
| `#NoWarning!!(类型, …)` | 任意位置（整行） | 只压**本文件**的、**指定类型**的警告；压不掉 error；类型写错会报错 |

`#NoWarning!!` 认识的类型：`unreachable`（不可达代码）、`redundant-static`（顶层函数上多余的 `stc`）、
`deprecated-case`（switch 里老写法 `case`）、`erasure-cast`（`as`/`is` 写了泛型实参，按擦除判）、
`all`（全部）。指令行在词法之前被整行挖空，所以**行列号与原文完全对齐**；写成别的 `#` 开头会报「不认识的字符 '#'」。

### 0.2 `.rsfs` 与构建脚本

`.rsfs` 和 `.rsf` 是**同一门语言**，差别只有一条：`.rsfs` 是**脚本**——顶层可以直接写语句，也可以没有 `main`。

```rsf
// build.rsfs 片段：这两行就是脚本的"主程序"
const val greeting : String = "hello"
println(greeting + " " + intToString(compileAll("src")))
```

顶层语句会被编译器**裹进一个合成的 `main`**（`Rsf.analyze` 之后就能在 `model.entry` 里看到它）。两条约束：

- 顶层语句与手写的 `fn main()` 不能共存（编译器不知道该先跑哪份，直接报错）。
- 顶层语句不能带注解（注解只能标在声明上）。

普通 `.rsf` 仍然只许写**声明**——把上面的 `println` 存成 `.rsf` 会报 `顶层只能写函数声明 … 实际读到 'println'`；
而顶层变量在两种文件里都得写 `const`（见下）。

### 0.2.1 全局变量：`const var` / `const val`

顶层定义变量**只有一种写法**——带 `const`：

```rsf
const val 名字 : 类型 = 初值    // 全局不可变（整份程序一份）
const var 名字 : 类型 = 初值    // 全局可变
```

- `const` 是**软关键字**：只有紧跟 `var` / `val` 时才是全局声明，别处仍是普通标识符。
- **位置自由**：写在哪一层、哪个文件的顶层都行（`const` 说的是「全局存储」，不是嵌套作用域）。
  所以「写哪里都可以」指的是排在函数/类前后都行，不是能写在函数体里。
- 全局变量对**所有文件**可见；初值按声明顺序跑一遍（`runEntry` 里、进 `main` 之前），
  所以前面的全局变量能出现在后面的初值里，反过来报 `未声明的变量`。
- 类型可以省（从初值推断），但初值必填——没有「未初始化」的全局变量。
- 不带 `const` 的顶层 `var` / `val` 直接报错，提示补 `const`（函数体里的 `var` / `val` 不受影响，那才是局部变量）。
- 给 `const val` 赋值报错：「全局常量 … 是 'const val' 声明的，不能重新赋值」。

> 现状：全局变量的**存储**目前只有解释器接了（`rsf run`、`rsf build` 的构建脚本都走解释器）；
> 字节码后端与 C 后端会明确报「还没接全局变量」，不会静默丢掉全局状态。

项目根目录的 `build.rsfs` 是构建脚本：在项目里裸跑 `rsf build` / `rsf build --c` 时由它决定编什么。
脚本里能调三个只有构建脚本才有的内置函数，其余地方（普通程序、`rsf build <文件>`、编成 class 之后）调用会明确报错：

| 内置 | 签名 | 作用 |
| --- | --- | --- |
| `outDir()` | `() -> String` | 产物目录（`-o` 给的，或默认 `build/out`） |
| `compile(path)` | `(String) -> String` | 编一个 `.rsf` / `.rsfs`，返回产物路径 |
| `compileAll(dir)` | `(String) -> Int` | 递归编目录下的 `.rsf` / `.rsfs`，返回编了几个 |

相对路径以**项目根目录**为基准。`rsf init demo` 生成的脚本就是 `println("编译 " + intToString(compileAll("src")) + " 个文件")`。

### 0.3 项目设置：`#dep` 与 `#build`

两条块状指令，写在文件最前面（可以和其他文件头指令混排）。块里一行一条，**整块在词法之前被挖成空格**，所以后面的行号、列号都不受影响。

```
#dep {
    java("libs/util.jar")
    c("native/helper.c")
    h("native/helper.h")
    rule("extraDeps")
}

#build {
    out("build/out")
    target("auto")
    meta(false)
    name("app")
}
```

| 指令 | 写在哪 | 内容 |
| --- | --- | --- |
| `#dep` | 任意源文件 | `java` / `c` / `h` 是相对项目根的路径，`rule` 是顶层函数名（返回更多依赖） |
| `#build` | 只能是项目根的 `build.rsfs` | `out` 产物目录、`target` 目标侧、`meta` 要不要带源码元数据、`name` 产物名 |

`target` 收四个值：`"jvm"`、`"c"`、`"native"`（等同 `c`）、`"auto"`。
`auto` 时**每份文件自己决定**：文件头写了 `target native`、或者里面有不带 `target` 别的侧标记的 `native` 声明，就编成 C；否则编成 `.class`。于是同一个 `src/` 里可以混着放两种文件。

`#dep` 里的文件在构建末尾被记进 `.rsfdeps/lock.json`（`kind` / `path` / `size` / `sha256`），缺文件直接报错。
命令行显式给的 `-o` / `--c` / `--meta` **压过** `#build` 里的设置。

## 1. 关键字（57 个）

| 类别 | 关键字（共 57，与两个词法器的表逐条对齐） |
| --- | --- |
| 声明 | `pkg` `import` `module` `class` `interface` `enum` `annotation` `companion` `entity` `constructor` `fn` `lam` `var` `val` `let`(已废，仅留报错) |
| 修饰 | `pub` `prv` `prot` `stc` `override` `final` `abstract` |
| 控制 | `if` `else` `while` `for` `in` `break` `continue` `goto` `switch` `case`(老写法) `try` `catch` `fail` `rtn` `return` |
| 类型 / 值 / 接收者 | `void` `Int` `Long` `Float` `Double` `String` `Str` `Bool` `Boolean` `Byte` `Char` `true` `false` `null` `new` `this` `super` |
| 运算 / 预留 | `as` `is` `mathematic`(预留) |

`open` 已删除（阶段 27），`fun` 已改名为 `fn`（阶段 29），`varis`/`let` 早已删除——写旧词会拿到**带改写示例**的报错。

## 2. 字面量与标识符

| 写法 | 类型 | 备注 |
| --- | --- | --- |
| `123` | Int | 十进制 |
| `123L` | Long | 后缀 `L` |
| `1.5` / `1.5d` | Double | 默认 Double |
| `1.5f` | Float | |
| `'a'` `'\n'` `'\u0041'` | Char | 单引号；转义 `\n \t \r \0 \\ \' \" \uXXXX` |
| `"hi"` `"a\nb"` | String | 双引号，同样的转义 |
| `true` `false` | Bool | |
| `null` | 可空类型 | |

标识符只收 Unicode 字母 / 数字 / 下划线（`isLetterOrDigit` 口径）。
**反引号只给标签名用**：`` `step-one`: ``、`goto `a.b$c``（阶段 28）；变量/函数名不能加反引号。

## 3. 类型

```
void  Int  Long  Float  Double  String(Str)  Bool(Boolean)  Byte  Char
T[]                        // 数组，可嵌套：Int[][]
?T                         // 可空：?String、?Box&<Int>、?Int[]
!T                         // 可能出错（错误返回）：!Int、!void
Box&<Int>                  // 泛型实参（class 级泛型）
Box&<T <: Animal>          // 类型参数的上界：T 必须是 Animal 的子类型（阶段 74）
(Int, String) -> Bool      // 函数类型（阶段 30；后端 30b 在做）
```

- **返回子句两种等价写法**：`-> Int` 与 `:: Int`（`: Int` 已删除）。
- **隐式加宽只有这几条**：`Byte→Int→Long→Float→Double`；`Char` 谁都不转（要 `charToInt`）。
  窄化一律走内置函数（`toByte` / `toChar`），rsf 没有 `(Byte) x` 这号语法。
- **可空**：`?T` 不接加宽（`var x : ?Long = 1` 报错，写 `1L`）；`?:` 兜底、`!!` 解包。
- **泛型**：`class Box&<T>`、`new Box&<Int>(5)`、泛型函数 `fn first&<T>(xs : T[]) -> T`。
- **上界**（阶段 74）：`class Box&<T <: Animal>`、`fn tell&<T <: Animal>(x : T)`——`T` 于是能调
  `Animal` 的成员；实参不满足上界直接报错。上界只写一个；JDK 泛型类型当上界时可以省实参
  （`T <: Comparable` 按擦除算）。
- **函数类型**只写在类型位置；`as` / `is` 的目标**不能**是函数类型。

## 4. 声明

### 4.1 函数

```
<权限> <static> fn <MainType> 名字(参数表) <返回子句> { 函数体 }
```

- 权限：`pub` / `prot` / `prv`，**省略 = `pub`**。
- `<MainType>`：`fn` 之后、名字之前的那个槽位，目前只接受 `void`。
- 返回子句省略 = `void`；可写 `-> T`、`:: T`、`!T`、`?T`。
- 参数：`名字 : 类型`，可用 `?T`、`T[]`、`Box&<Int>`、函数类型；不支持默认值与可变参数。
- **重载**：同名不同参（按擦除后类型），挑法 = 精确匹配优先、加宽其次、**同分报错**。
- 顶层 `stc` 方法、`override`、`final`、`abstract` 都按修饰词写；`abstract` 不许有体。

#### 4.1.1 扩展函数（阶段 80）

```rsf
fn Int.twice() -> Int { rtn this * 2 }
fn String.shout() -> String { rtn this + "!" }
fn Int.addUp(a : Int) -> Int { rtn this + a }
```

- 语法是 `fn 类型.名字(参数表)`，**只能写在顶层**（类/接口里写会报错）。
- 接收者就是 **0 号形参**，名字固定叫 `this`；`params` 表里你能看到它是第一项，
  所以 `fn Int.addUp(a : Int)` 实际是「接收者 + 一个参数」。
- 调用写 `3.twice()`、`"hi".shout()`、`10.addUp(5)`——和成员方法长得一模一样。
- **成员优先**：类/接口/JVM 类上本来就有同名同参数个数的方法时，调的是成员，扩展不会盖掉它
  （例：`fn String.length()` 写了也不影响 `"abc".length()` 仍是 3）。
- 同一接收者类型上**同名同参数个数**的扩展只能有一个，重复声明直接报错。
- 不能访问接收者的私有成员（和 Kotlin 一样，扩展是静态解析的）。
- 三端都支持：解释器、JVM 字节码、C 都摊成「静态调用 + 接收者当第一个实参」。

#### 4.1.2 `export`（阶段 80，语义为零）

- 只能写在**顶层函数**上：`export fn 名字(...)`。
- 现在没有任何语义差异，纯粹是给将来的「自举直出机器码 / 生成 C 的入口表」占位；
  写在类方法或字段上会报错。

### 4.2 变量与字段

```rsf
var x : Int = 0          // 可变
val y : Int = 1      // 不可变（只能赋一次）
var z = 3                // 类型可省（由初值推）
```

字段写在类体里，权限修饰词可选（省略 = `pub`）；字段**不能**写 `final` / `abstract` / `open`。

字段可以带**属性访问器**（阶段 79）：

```rsf
pub class Account() {
    prv var secret : Int = 7                    // 只有本类能读
    prot var half : Int = 3                     // 本类与子类能读
    var hp : Int = 100 { get; set }             // 自动：生成 getHp() / setHp(v)
    var alive : Bool = true { is }               // Bool 用 is：生成 isAlive()
    var name : String = "x" { get { rtn "n" } }  // 手写 getter
    var tag : String = "t" { set(v) { this.tag = "tag:" + v } }  // 手写 setter
}
```

- 访问器在编译期（`PropertyDesugar`）展开成真方法：`getHp()` / `setHp(v)` / `isAlive()`，名字按 JavaBean 约定；Java 侧反射能直接认。
- 不写体的自动访问器：getter 是 `rtn this.hp`，setter 是 `this.hp = v`。
- **使用侧也走访问器**（阶段 79 v2）：`a.hp` 摊成 `a.getHp()`、`a.hp = 5` 摊成 `a.setHp(5)`，三端都是真方法调用；所以 setter 里的钳位/校验对 `a.hp = -5` 一样生效。
- 访问器**自己的体里**不做改写：自动体 `rtn this.hp` / `this.hp = v` 直读直写字段，不会递归。
- 没写访问器的普通字段照旧直读直写（`fieldReads` / `fieldWrites` 那套不变）。
- `val` 字段不能有 `set`；生成的 `getX`/`setX`/`isX` 与已有方法撞名会直接报错。
- 访问器的可见性跟字段（字段写 `prv` 时访问器也是私有）；`prot` 在 native 侧只按检查期生效（C 没有可见性）。

### 4.3 类

```rsf
pub class Point(x0 : Int, y0 : Int) : Base(), Greeter {   // 主构造 + 继承/实现（共用 `:`）
    pub var x : Int = x0                                  // 字段初值 = 主构造的「体」
    pub val tag : String = "p"

    pub constructor() {                                   // 次构造：不写返回类型、不写权限（阶段 30b 前的口径）
        this(0, 0)                                           // 委托必须是第一句
    }

    pub override fn greet(name : String) -> String { rtn "hi " + name }
}
```

- 继承与实现都写在同一处 `: A(), I1, I2`；父类构造实参写在 `A(...)` 里（不写就是 `A()`）。
- `super` 三种用法：`super.f()`、`super(Iface).f()`、`super(args)`（构造器里调父构造）。
- **默认可继承、默认可覆盖**（阶段 27）：要封住就写 `final class` / `final fn`；`abstract` 隐式开放。
- 类头修饰顺序两种都收：`pub final class` 与 `final pub class`。

### 4.4 接口

```rsf
pub interface Greeter {
    fn greet(name : String) -> String                       // 抽象：不写体
    fn shout(name : String) -> String { rtn greet(name) }    // 默认方法：写体
}
pub interface Loud : Greeter { }        // 接口继承接口，逗号分隔
```

### 4.5 枚举

```rsf
pub enum TokenKind {
    IDENT,
    INT_LIT,
    EOF,          // 尾逗号可省
}
```

取值 `TokenKind.IDENT`；同枚举可 `==`/`!=`，跨枚举比较直接报错；打印出来就是值名。

JVM 侧它就是真的 `java.lang.Enum`：`TokenKind.values()` 给全部值（新数组）、
`TokenKind.valueOf("IDENT")` 按名字取（名字不对抛 `IllegalArgumentException`）、
`.name()` / `.ordinal()` 都有，Java 那边 `switch` 与 `EnumSet` 也能直接用。
native 侧（C 后端）只当整型常量用，没有 `values()`。

### 4.6 注解与伴生实体

```rsf
class Base(seed : String) {                     // 伴生体可以继承一个类（阶段 83）
    var tag : String = seed
    fn describe() -> String { rtn "base:" + tag }
}

annotation Marker(value : String = "x", count : Int = 0) {
    companion entity Impl : Base("s:" + value), Runnable {   // 行为，至多一个
        fn run() -> void { println(this.describe()) }
    }
}

@Marker(value = "hi", count = 3)                // 可标：类/接口/注解/方法/字段
class Greeter { }
```

元素按**裸名**读；`Marker::Impl` 可以 `new`；同一处同一个注解只能标一次。

伴生体的父类型列表和类头**同一套规矩**：父**类**要带实参表、排在**最前面**
（`: Base(实参), 接口名`），其余都是接口；父类得能被继承（默认就可以，写了 `final` 才禁止）。
继承来的字段/方法在伴生体里直接可用（`this.describe()`），也自动出现在 `new Marker(...)` 出来的实例上。

## 5. 语句

| 语句 | 写法 |
| --- | --- |
| 块 | `{ ... }` |
| if（三种） | `if (c) { ... }`、`if c : 单条`、`var y = if c ? a : b`（三目是**表达式**） |
| else | `else { ... }` / `else if ...` / `else : 单条` |
| while | `while (c) { ... }` |
| for（经典） | `for (var i : Int = 0; i < n; i = i + 1) { ... }` |
| for（区间） | `for (i in 0..9) { ... }`、`for (i in 0..9 step 2) { ... }`（数组**不能** for-in） |
| switch | `switch (k) { Color.RED : ... Color.BLUE : { ... } else : ... }`——分支**只收枚举常量**（`枚举名.值名`），不收字面量/区间/比较；`case` 老写法可选（会发弃用警告），写 `default` 会报错提示改用 `else` |
| try / catch | `try { ... } catch (e : String) { ... }`（老写法 `catch (e String)`）；`e` 是消息文本——rsf 自己的失败是原文，Java 互调抛出来的异常是 `完整类名: 消息` |
| fail | `fail "消息"`——只能在 `!T` 函数里或 `try` 里 |
| 跳转 | `loop:` + `goto loop`；标签名可用反引号 `` `step-one`: `` |
| 循环控制 | `break`、`continue` |
| 返回 | `rtn 值` / `rtn` / `return ...`（两种关键字等价） |

- `switch` 分支值必须是常量；没有 `else` 且没有命中时：枚举穷尽则抛 rsf 运行期错误（`try` 能接住），
  带 `else` 则走 `else`。
- 标签是**函数作用域**（全函数不许重名，不能跨函数跳）；后端会给每个带标签的方法发一条
  `@RsfLabels(value = [...])` 注解，按声明顺序记名字（阶段 28）。

## 6. 表达式与优先级

从松到紧：

```
三目 if c ? a : b
  <  ?:            （空值合并）
  <  ||
  <  &&
  <  == !=
  <  < > <= >=
  <  + -
  <  * / %
  <  一元 - !
  <  后缀：.成员  [下标]  (调用)  !!  as / ?as / is / !is
```

- 算术 `+ - * / %`（Int 除法截断，溢出按 JVM 回绕）；`Byte`/`Char` 参与算术一律提升成 Int。
- 任意一边是 `String` 时 `+` 是拼接（另一边自动转字符串）。
- `&&` / `||` 短路，两边必须是 `Bool`；`&` 只用于泛型实参 `&<Int>`，`|` 在词法阶段就报错。
- 空值：`a ?: b`、`x!!`、可空调用/赋值按 Checker 的空安全规则。
- 类型运算（阶段 24）：`x as A`、`x ?as A`、`x is A`、`x !is A`。
- 创建对象：`new Point(3, 4)`，**`new` 可省**（`Point(3, 4)`）。
- 成员/静态：`obj.f`、`Cls.staticFn()`、`obj.field`、`arr.length`、`arr[i]`。
- 切片（阶段 73d）：`s[a..b]` 取 UTF-16 码元的半开区间，返回 `String`，等价于
  `slice(s, a, b)`；两端都可以省：`s[..b]`（从头）、`s[a..]`（到末尾）、`s[..]`（整份）。
  越界钳进 `[0, length]`（不抛越界），`b < a` 当空串；切在 emoji 的代理对中间时
  边界外扩到整个码点，所以三端都不会产出「半个字符」。native 侧是零拷贝视图。
- 数组只有 `new T[n]` / `new T[n][m]`，**没有**数组字面量 `{1, 2}`。
- **lambda（阶段 30 前端已可用）**：`lam(x : Int) -> Int { rtn x + 1 }`，
  参数类型与返回子句都可省（靠期望类型）；捕获是**值快照**，只许捕获 `val` / 形参 / `this`，
  捕获 `var` 直接报错。可空的函数值要 `f!!(...)` 才能调。
  ⚠️ 后端（解释器 + 字节码）在 30b 落地前会报 `17c/17d 后端暂不支持：lambda 表达式`。

## 7. 落地状态

| 状态 | 内容 |
| --- | --- |
| ✅ 已实现并两端一致 | 上表除下面两行以外的一切（含类/接口/枚举/注解/泛型 class/继承/标签/`as`·`is`/`@RsfLabels`） |

