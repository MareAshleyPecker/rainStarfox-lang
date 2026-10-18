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

## 1. 关键字（57 个）

| 类别 | 关键字（共 57，与两个词法器的表逐条对齐） |
| --- | --- |
| 声明 | `pkg` `import` `module` `class` `interface` `enum` `annotation` `companion` `entity` `constructor` `fn` `lam` `var` `imutvar` `let`(已废，仅留报错) |
| 修饰 | `pub` `prv` `prot` `stc` `override` `final` `abstract` |
| 控制 | `if` `else` `while` `for` `in` `break` `continue` `goto` `switch` `case`(老写法) `try` `catch` `fail` `rtn` `return` |
| 类型 / 值 / 接收者 | `void` `int` `long` `float` `double` `string` `str` `bool` `boolean` `byte` `char` `true` `false` `null` `new` `this` `super` |
| 运算 / 预留 | `as` `is` `mathematic`(预留) |

`open` 已删除（阶段 27），`fun` 已改名为 `fn`（阶段 29），`varis`/`let` 早已删除——写旧词会拿到**带改写示例**的报错。

## 2. 字面量与标识符

| 写法 | 类型 | 备注 |
| --- | --- | --- |
| `123` | int | 十进制 |
| `123L` | long | 后缀 `L` |
| `1.5` / `1.5d` | double | 默认 double |
| `1.5f` | float | |
| `'a'` `'\n'` `'\u0041'` | char | 单引号；转义 `\n \t \r \0 \\ \' \" \uXXXX` |
| `"hi"` `"a\nb"` | string | 双引号，同样的转义 |
| `true` `false` | bool | |
| `null` | 可空类型 | |

标识符只收 Unicode 字母 / 数字 / 下划线（`isLetterOrDigit` 口径）。
**反引号只给标签名用**：`` `step-one`: ``、`goto `a.b$c``（阶段 28）；变量/函数名不能加反引号。

## 3. 类型

```
void  int  long  float  double  string(str)  bool(boolean)  byte  char
T[]                        // 数组，可嵌套：int[][]
?T                         // 可空：?string、?Box&<int>、?int[]
!T                         // 可能出错（错误返回）：!int、!void
Box&<int>                  // 泛型实参（class 级泛型）
(int, string) -> bool      // 函数类型（阶段 30；后端 30b 在做）
```

- **返回子句两种等价写法**：`-> int` 与 `:: int`（`: int` 已删除）。
- **隐式加宽只有这几条**：`byte→int→long→float→double`；`char` 谁都不转（要 `charToInt`）。
  窄化一律走内置函数（`toByte` / `toChar`），rsf 没有 `(byte) x` 这号语法。
- **可空**：`?T` 不接加宽（`var x : ?long = 1` 报错，写 `1L`）；`?:` 兜底、`!!` 解包。
- **泛型只在 class 上**：`class Box&<T>`、`new Box&<int>(5)`；泛型**函数**还没有（写了报
  「泛型函数还不支持：类型参数只能写在 class 上」）。
- **函数类型**只写在类型位置；`as` / `is` 的目标**不能**是函数类型。

## 4. 声明

### 4.1 函数

```
<权限> <static> fn <MainType> 名字(参数表) <返回子句> { 函数体 }
```

- 权限：`pub` / `prot` / `prv`，**省略 = `pub`**。
- `<MainType>`：`fn` 之后、名字之前的那个槽位，目前只接受 `void`。
- 返回子句省略 = `void`；可写 `-> T`、`:: T`、`!T`、`?T`。
- 参数：`名字 : 类型`，可用 `?T`、`T[]`、`Box&<int>`、函数类型；不支持默认值与可变参数。
- **重载**：同名不同参（按擦除后类型），挑法 = 精确匹配优先、加宽其次、**同分报错**。
- 顶层 `stc` 方法、`override`、`final`、`abstract` 都按修饰词写；`abstract` 不许有体。

### 4.2 变量与字段

```rsf
var x : int = 0          // 可变
imutvar y : int = 1      // 不可变（只能赋一次）
var z = 3                // 类型可省（由初值推）
```

字段写在类体里，权限修饰词可选（省略 = `pub`）；字段**不能**写 `final` / `abstract` / `open`。

### 4.3 类

```rsf
pub class Point(x0 : int, y0 : int) : Base(), Greeter {   // 主构造 + 继承/实现（共用 `:`）
    pub var x : int = x0                                  // 字段初值 = 主构造的「体」
    pub imutvar tag : string = "p"

    pub constructor() {                                   // 次构造：不写返回类型、不写权限（阶段 30b 前的口径）
        this(0, 0)                                           // 委托必须是第一句
    }

    pub override fn greet(name : string) -> string { rtn "hi " + name }
}
```

- 继承与实现都写在同一处 `: A(), I1, I2`；父类构造实参写在 `A(...)` 里（不写就是 `A()`）。
- `super` 三种用法：`super.f()`、`super(Iface).f()`、`super(args)`（构造器里调父构造）。
- **默认可继承、默认可覆盖**（阶段 27）：要封住就写 `final class` / `final fn`；`abstract` 隐式开放。
- 类头修饰顺序两种都收：`pub final class` 与 `final pub class`。

### 4.4 接口

```rsf
pub interface Greeter {
    fn greet(name : string) -> string                       // 抽象：不写体
    fn shout(name : string) -> string { rtn greet(name) }    // 默认方法：写体
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

### 4.6 注解与伴生实体

```rsf
annotation Marker(value : string = "x", count : int = 0) {
    companion entity Impl : Runnable {          // 行为，至多一个；只能继承接口
        fn run() -> void { println(value) }
    }
}

@Marker(value = "hi", count = 3)                // 可标：类/接口/注解/方法/字段
class Greeter { }
```

元素按**裸名**读；`Marker::Impl` 可以 `new`；同一处同一个注解只能标一次。

## 5. 语句

| 语句 | 写法 |
| --- | --- |
| 块 | `{ ... }` |
| if（三种） | `if (c) { ... }`、`if c : 单条`、`var y = if c ? a : b`（三目是**表达式**） |
| else | `else { ... }` / `else if ...` / `else : 单条` |
| while | `while (c) { ... }` |
| for（经典） | `for (var i : int = 0; i < n; i = i + 1) { ... }` |
| for（区间） | `for (i in 0..9) { ... }`、`for (i in 0..9 step 2) { ... }`（数组**不能** for-in） |
| switch | `switch (k) { Color.RED : ... Color.BLUE : { ... } else : ... }`——分支**只收枚举常量**（`枚举名.值名`），不收字面量/区间/比较；`case` 老写法可选（会发弃用警告），写 `default` 会报错提示改用 `else` |
| try / catch | `try { ... } catch (e : string) { ... }`（老写法 `catch (e string)`） |
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

- 算术 `+ - * / %`（int 除法截断，溢出按 JVM 回绕）；`byte`/`char` 参与算术一律提升成 int。
- 任意一边是 `string` 时 `+` 是拼接（另一边自动转字符串）。
- `&&` / `||` 短路，两边必须是 `bool`；`&` 只用于泛型实参 `&<int>`，`|` 在词法阶段就报错。
- 空值：`a ?: b`、`x!!`、可空调用/赋值按 Checker 的空安全规则。
- 类型运算（阶段 24）：`x as A`、`x ?as A`、`x is A`、`x !is A`。
- 创建对象：`new Point(3, 4)`，**`new` 可省**（`Point(3, 4)`）。
- 成员/静态：`obj.f`、`Cls.staticFn()`、`obj.field`、`arr.length`、`arr[i]`。
- 数组只有 `new T[n]` / `new T[n][m]`，**没有**数组字面量 `{1, 2}`。
- **lambda（阶段 30 前端已可用）**：`lam(x : int) -> int { rtn x + 1 }`，
  参数类型与返回子句都可省（靠期望类型）；捕获是**值快照**，只许捕获 `imutvar` / 形参 / `this`，
  捕获 `var` 直接报错。可空的函数值要 `f!!(...)` 才能调。
  ⚠️ 后端（解释器 + 字节码）在 30b 落地前会报 `17c/17d 后端暂不支持：lambda 表达式`。

## 7. 落地状态

| 状态 | 内容 |
| --- | --- |
| ✅ 已实现并两端一致 | 上表除下面两行以外的一切（含类/接口/枚举/注解/泛型 class/继承/标签/`as`·`is`/`@RsfLabels`） |

