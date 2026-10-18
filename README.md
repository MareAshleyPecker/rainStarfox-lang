# rainStarfox (rsf)

一个自制玩具语言。用 **Kotlin** 写的编译器，跑在 **JVM** 上；同一份前端后面挂了三个出口：

- **解释执行** —— `rsf run xxx.rsf`，不落地任何 `.class`
- **编译成字节码** —— `rsf build xxx.rsf`，产出 `.class`，可以直接 `java xxx`
- **打包成原生 exe** —— `gradlew exe`，产物自带精简 JVM，目标机器不用装 Java

两个后端的输出经过测试保证**逐字节一致**（见 `RsfTest` 里的 `解释执行与编译执行输出逐字节一致`）。

---

## 快速开始

```bat
:: 解释执行
gradlew run --args="run rsfSrc/lang/main.rsf"

:: 编译成 .class（默认输出到 out/）
gradlew run --args="build rsfSrc/lang/main.rsf"
java -cp out main

:: 打包成可执行胖 jar
gradlew jar
java -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/lang/main.rsf

:: 打成自带精简 JVM 的原生启动器（Windows 下就是 rainStarfox.exe）
gradlew exe
build\native\rainStarfox\rainStarfox.exe run rsfSrc\lang\main.rsf
```

`gradlew exe` 用的是 JDK 自带的 `jpackage`，**不需要 GraalVM，也不需要 Launch4j 之类的外部工具**。
产物 `build/native/rainStarfox/` 里是：

```
rainStarfox.exe      原生启动器（0.5 MB）
runtime/             用 jlink 裁过的 JVM，只含 java.base（约 44 MB）
app/                 胖 jar
```

整个目录可以直接拷给别人，对方不需要装 Java。注意要整个目录一起拷贝，不能只拿 exe。

---

## 语法

### import / module（多文件）

三种写法，对应三种来源：

```rsf
import java.lang.Math        // JVM 类（点分路径，Java/Kotlin 风格）
import "lib/util.rsf"        // 另一个 rsf 源文件（字符串路径，Go 风格）
module "plugins"             // 一个目录下的所有 .rsf
```

字符串路径**可以跨行写**，空白会被去掉：

```rsf
import "lib/
       math.rsf"
```

语义要点：

- rsf 的模块是**包含式**的：被导入文件里的函数都并进同一个程序，最终编译成**一个类**
  （类名取根文件名）。这跟 Go 的「同包多文件」是一回事，不是分别编译再链接。
- 同一个文件被多处 import 只会加载一次，所以**互相 import 不会死循环**。
- 路径先相对**发起 import 的那个文件所在目录**解析，找不到再相对当前工作目录试一次。
- `module "目录"` 只收该目录**顶层**的 `.rsf`（不递归），按文件名排序，保证结果确定。
- 报错会指向**真正出错的文件**，而不是根文件。

```
bad.rsf:2:5: error[checker]: 返回类型不匹配：'helper' 声明返回 int，实际 rtn 的是 string
  2 |     rtn "not an int"
    |     ^
```

`rsfSrc/lang/multifile/` 演示**三种 import 语法同时用在一个程序里**（源文件 / JVM 类 / 目录）。

⚠️ 它**不能直接跑**：`main.rsf` 里那句 `import multifile.rsf` 指向的是旁边的
`rsf.java`，而**Java 源文件不参与 rsf 编译**——rsf 只从 classpath 上读 `.class` 的签名。
所以要先手工编一次，把输出目录挂到 classpath 上：

```bat
:: ① 手工编那个 Java 类（rsf 不管 .java）
javac -encoding UTF-8 -d build\java-multifile rsfSrc\lang\multifile\rsf.java

:: ② 再跑 rsf 程序（build.gradle 已经把 build\java-multifile 加进 run 的 classpath）
gradlew run --args="run rsfSrc/lang/multifile/main.rsf"

:: 或者不用 Gradle：把那个目录直接加进 -cp（-jar 会忽略 -cp，所以要写主类名）
gradlew jar
java -cp "build\libs\rainStarfox-0.9.0.jar;build\java-multifile" org.example.MainKt run rsfSrc/lang/multifile/main.rsf
```

`gradlew clean` 会把 `build/java-multifile/` 一起删掉，之后重跑第 ① 步就行。
忘了 ① 的话报错会说清楚这件事：

```
main.rsf:17:1: error[checker]: classpath 上找不到 JVM 类 'multifile.rsf'：点分路径按 JVM 的类名解析
（`a.b.C` 要能对应上 classpath 里的 a/b/C.class）。如果它是个 Java/Kotlin 写的伴生类，
那它**不参与 rsf 编译**——先用 `javac -d <目录> X.java` 编出来，再把 <目录> 挂到 classpath 上…
```

### 与 Java / Kotlin 互调用

**rsf → Java**：导入类之后用 `.` 访问静态成员，或者在对象上访问实例成员。

```rsf
import java.lang.Math
import java.lang.Integer

fn main() -> void {
    println(Math.max(3, 7))              // 静态方法（按实参类型选重载）
    println(Math.PI)                     // 静态字段
    System.out.println("走 Java 的输出")  // 静态字段 + 实例方法链
    println(Integer.parseInt("42"))

    var s = "rainStarfox"
    println(s.length())                  // 实例方法
    println(s.substring(0, 4))
    println(s.contains("Star"))          // String 当 CharSequence 用
    rtn
}
```

- `java.lang` 下的类（`Math` / `Integer` / `String` / `System`…）**和 Java 一样可以省掉 import**。
- 类信息是**从 classpath 上的 `.class` 里读的**（用 ASM 解析签名，跳方法体），
  所以任何 Java/Kotlin 编译出来的类都能调，不限于白名单。
- 重载按「精确匹配优先、加宽其次」选；找不到时会**把同名候选签名列出来**：
  ```
  'string' 里没有匹配的方法 'contains'（实参：int）。同名的有：String.contains(CharSequence) -> bool
  ```
- 引用类型之间按类层次判断子类型（`String` → `CharSequence`、任意类 → `Object`）
- **Kotlin 互调用和 Java 是同一件事**——Kotlin 也编译成 JVM 类；顶层函数在
  `文件名Kt` 类里，用 `import` 那个类名就能调。

**Java → rsf**：rsf 的 `public` 函数就是普通的 `public static` 方法，Java 侧直接调，不用胶水代码。

```java
System.out.println(rsflib.greet("Java"));   // rsflib.rsf → rsflib.class
System.out.println(rsflib.fact(5));
```

`rsfSrc/lang/interop/` 里有完整的双向例子（`UseRsf.java` 是**普通 Java 类**，
放在默认包里——因为 `rsflib.rsf` 没有 `pkg`，编出来的 `rsflib` 也在默认包，
Java 侧直接按类名调即可，**不需要 `import`**）：

```bat
:: rsf → Java
gradlew run --args="run rsfSrc/lang/interop/usejava.rsf"

:: Java → rsf（先编 rsf，再用 javac 编 Java 侧）
gradlew run --args="build rsfSrc/lang/interop/rsflib.rsf -o out"
javac -encoding UTF-8 -d out -cp out rsfSrc/lang/interop/UseRsf.java
java -cp out UseRsf
```

### 泛型（`&<...>`）

类型参数写成 **`&<...>`**：`&` 让它一眼跟比较运算的 `<` 分开，也不会跟
`rsfSrc/lang` 草稿里的 `<ControlType>` / `<MainType>` 占位符混淆（`[]` 留给数组）。

> **规范写法是紧跟的 `Box&<int>`（`&<` 与类型名之间没有空格）**，2026-10-11 起全库
> 统一成这样。词法上空白本来就不重要，所以带空格的 `Box &<int>` **一样能编译**——
> 这里刻意不打死它（老代码/手写草稿不会突然报错），但新写的和文档里的例子一律不带空格。
> `&<`（泛型引子）、`&&`（逻辑与）、单独的 `&` 在词法上是三个不同的记号，互相不打架。

```rsf
public class Box&<T> {              // 声明类型参数
    var value : T = ...              // 类体里 T 可以当类型用（字段 / 参数 / 返回 / 局部变量）
    public fn get() -> T { rtn value }
    public fn set(v : T) -> void { value = v }
}

public class Pair&<K, V> {          // 多个类型参数用逗号分隔
    var key : K = ...
    var val : V = ...
}

fn main() -> void {
    var b : Box&<int> = new Box&<int>(0)      // 实例化：类型名后面&<实参类型>
    b.set(7)
    println(b.get() + 1)                        // 8

    var s : Box&<string> = new Box&<string>("hi")
    var nested : Box&<Box&<int>> = ...        // 嵌套（词法上是 `>` `>`，不是移位）
    var p : Pair&<string, int> = new Pair&<string, int>("a", 1)

    // JVM 泛型集合直接用
    var xs : ArrayList&<int> = new ArrayList&<int>()
    xs.add(3)                                   // 实参装箱成对象
    println(xs.get(0) + 1)                      // 取出来 CHECKCAST + 拆箱
    rtn
}
```

**语义按 JVM 的擦除走**（这也是两条后端能逐字节一致的原因）：

- `Box&<int>` 与 `Box&<string>` 擦除后是**同一个 JVM 类** `LBox;`，
  `var T value` 编译出来是 `Ljava/lang/Object; value`；类型实参只活在编译期。
- 所以 **`Box&<int>` 不能赋给 `Box&<string>`**（反过来的也不行）：
  JVM 一点都拦不住（两边都是引用），只有编译期能拦，报错会把两个实例化写清楚：
  ```
  error[checker]: 不能把 Box&<int> 赋给 Box&<string> 变量 'b'
  ```
- **元数在编译期查**，报错说清期望几个、实际几个：
  ```
  error[checker]: 类型实参个数不对：'Box' 期望 1 个类型实参，实际给了 2 个（声明是 class Box&<T>）
  ```
- 成员的类型按实参替换：`Box&<int>.get()` 是 `int`（字节码里是
  `INVOKEVIRTUAL Box.get()Ljava/lang/Object;` + `CHECKCAST java/lang/Integer` + `intValue`，
  解释器里是按静态类型收敛）；`Box&<int>.set(7)` 的实参按 `int` 检查、传进去时装箱。
- **JVM 泛型集合**同理：`ArrayList&<int>` 的元素是 int，而 Java 的
  `add(Object)` / `get(): Object` 在描述符上看是 `Object`——元素位置正是擦除掉的那个
  类型参数，所以 `add` 装箱、`get` 拆箱。`Signature` 属性一律不看（看擦除后的描述符）；
  元数从类签名里数出来，数不出来就不拦。

**不做**：泛型函数（`fn <T> T pick(...)`，阶段 19d 起会明确报「泛型函数还不支持」而不是
一句含糊的语法错）、上界（`T : Number`）、通配符、泛型继承。

`rsfSrc/lang/generics.rsf` 是完整示例：

```bat
gradlew run --args="run rsfSrc/lang/generics.rsf"
gradlew run --args="build rsfSrc/lang/generics.rsf -o out"
java -cp out generics
```

### 函数声明

```
<ControlType> fn <MainType> 名字(参数表) <返回子句> { 函数体 }
```

| 槽位 | 取值 | 说明 |
| --- | --- | --- |
| `<ControlType>` | `public` / `protected` / `private` / 省略 | 访问控制，写在**最左**；**省略就是 `public`**（见下） |
| `fn` | `fn` | 函数定义关键字 |
| `<MainType>` | 类型，缺省 `void` | 在 `fn` 之后、函数名之前；**目前只接受 `void`** |
| 返回子句 | 见下 | 可省略，缺省 `void` |

**访问控制：什么都不写就是 `public`。** 不是 `protected`、也不是「模块内可见」——
缺省编译成 `ACC_PUBLIC`，和显式写 `public` 出来的字节码一模一样。
`public` / 缺省 = 谁都能调（包括外部 Java/Kotlin）；`protected` = 本文件 + 同一次编译展开进来的
其他文件；`private` = 只有声明它的那个 `.rsf` 文件能调。

`rsf check` 打印函数/方法清单时，**缺省那一档也显示成 `public`**，不会留空让人误以为是「没有权限」：

```bat
rsf check rsfSrc/lang/main.rsf
      public     fn greeting() -> string
      public     fn add(a : int, b : int) -> int
      private    fn secret() :: int
      ...
```

**返回子句有两种等价写法**（`->` 和 `::`）：

```
public fn add(a : int, b : int) -> int { rtn a + b }
public fn add(a : int, b : int) :: int { rtn a + b }
```

> ⚠️ 曾经还有第三种 `: T`，**已经删除**（2026-10-08）。`: T` 不再被接受，报错会提示改用
> `->` 或 `::`。原因是 `:` 现在有两个更要紧的用途：变量/字段的类型标注（`var x : int = 0`）
> 和冒号形式的 `if c : 语句`，三种返回写法挤在一起太容易看混。

还可以带 `!`（可能出错）前缀——**这是阶段 14 的错误返回**，出错时走异常，调用方必须显式处理
（三种写法见下面的「错误返回 `!T` 与 `try` / `catch`」）：

```
fn f() -> !int { ... }     // 类似 Zig 的错误返回：正常返回 int，出错走异常
fn g() -> !void { ... }    // !void 也一样，函数体里用不带值的 rtn
```

> ⚠️ 因为 `<MainType>` 槽位在函数名前面，**类型关键字不能当函数名**：
> `fn double()` 里的 `double` 会被当成类型槽。报错时会提示改名。

### 参数

参数**两种写法都接受，而且可以在同一个参数表里混着写**，逗号分隔：

| 风格 | 写法 | 例子 |
| --- | --- | --- |
| Java 风格（类型在前） | `类型 名字` | `int a`、`Point p`、`Box&<int> b`、`int[] xs`、`?string s` |
| Kotlin 风格（名字在前） | `名字 : 类型` | `a : int`、`p : Point`、`b : Box&<int>`、`xs : int[]`、`s : ?string` |

```rsf
public fn add(a : int, b : int) -> int {          // 全 Kotlin 风格
    rtn a + b
}

fn mix(int a, b : long, c : double) -> double {   // 混着写也行
    rtn a + b + c
}

fn greet(who : string) -> string {
    rtn "hello, " + who
}

fn fact(n : int) -> int {
    if (n <= 1) { rtn 1 }
    rtn n * fact(n - 1)        // 递归
}
```

判定靠**两个记号的前瞻**，无歧义：`标识符` 后面跟 `:` 就是 Kotlin 风格；类型关键字或者 `?`
开头就是 Java 风格；`标识符` 后面还跟 `标识符` / `&` / `[` 也是 Java 风格（类名当类型）。
光写一个名字（`fn f(x) -> int`）两种都不是，会同时给出两种写法的提示：

```
参数 'x' 缺少类型。两种写法都可以：Java 风格 'int x'，或者 Kotlin 风格 'x : int'
```

泛型（`&<...>`）、数组（`[]`）、可空（`?T`）在两种风格下都自动可用。

**`catch` 的错误变量走的也是这套规矩**（阶段 19b）：`catch (e : string)` 与
`catch (e string)` 都收，推荐前者。它固定是 `string`，所以这里没有类型可选，
两种写法只是「冒号写不写」的区别。

> **变量与字段没有这个自由度**：它们只接受 Kotlin 后缀风格 `var 名字 : 类型 = 初值`
> （见下面「变量」那一节）。

- **参数按 C/C++/Java 的惯例是可变的**：函数体里可以直接 `n = n * 2`。
  之所以不套用 `imutvar`：参数的语法里没有写 `var`/`imutvar` 的位置，
  设成不可变就彻底表达不出「我要改这个参数」，只能先拷到局部变量。
- 参数与函数体**顶层**共用作用域 —— 在 body 顶层再声明同名变量会报重复声明；
  但在内层块里可以正常遮蔽。
- 实参会自动**加宽**到形参类型（`f(1)` 传给 `long a` 没问题），窄化不行。
- 参数和局部变量在检查之后是同一种东西（`Binding`），槽位也一起排 ——
  JVM 的调用约定本来就是参数占最前面几格，`long`/`double` 占两格。
- **`main` 不能带参数**：JVM 启动器只认 `main(String[])`。带参数的 `main` 会被编译成
  普通静态方法（rsf 内部照样能调），但 `rsf run` 会明确报错。

### 重载（阶段 19d）

**顶层函数与类方法（实例 + `static`）都能重载**，两处走的是同一套解析机器：

```rsf
fn tag() -> string { rtn "无参" }
fn tag(a : int) -> string { rtn "一参：" + a }
fn tag(a : int, b : int) -> string { rtn "两参：" + (a + b) }

class Greeter {
    fn greet() -> string { rtn "hello" }
    fn greet(name : string) -> string { rtn "hello, " + name }

    static fn kind(a : int) -> string { rtn "int" }
    static fn kind(a : string) -> string { rtn "string" }
}
```

**怎么挑**（口径固定，两端逐字节一致）：

1. **先按实参个数筛**——个数不同的候选直接出局；
2. **再按类型打分**，每个实参的代价相加，分**最低**者胜：
   - 精确匹配 → **0 分**；
   - 隐式**加宽** → 按**档位距离**计分。阶梯 `byte` < `int` < `long` < `float` < `double`，
     所以 `byte` 实参在 `f(int)` / `f(long)` / `f(double)` 之间**选 `f(int)`**（1 分 < 2 分 < 4 分）
     ——「精确优先、窄加宽优先」就是这一条；
   - `null` 给引用类型、`0..127` 的整数字面量给 `byte` 各记 1 分；
   - 对不上的候选（窄化、`char` 与数值互转、`bool` 给数值形参…）**整个出局**。
3. **最低分并列就报错**，并把候选签名全列出来——**不静默取「声明在前」的那个**：

```
error[checker]: 调用 'pick' 无法确定用哪个重载：实参类型（null）下有 2 个候选并列最低分（1 分）
    —— 候选有 2 个 —— pick(v : ?A)；pick(v : ?B)。
    rsf 不替你按声明顺序挑一个：把实参写成与其中一个候选**完全一致**的类型……
```

挑不中时同样列候选（`候选有 2 个 —— f(a : int)；f(a : string)`），
签名一律渲染成 Kotlin 后缀风格 `名字(参数 : 类型)`。

**擦除冲突：编译期就拦。** 同名重载在 JVM 上必须是**不同的方法**，判据是**参数擦除后的描述符**：

- `f(Box&<int>)` 与 `f(Box&<string>)` 描述符都是 `(LBox;)…` ⇒ 报错（顶层函数之间、
  同一个类的两个方法之间都适用），文案里写明「参数擦除后的描述符都是 (LBox;)」与两处签名；
- **返回类型不参与**：`f(int) -> int` 与 `f(int) -> string` 一样拒绝——调用点 `f(1)` 说不清该返回什么；
- 而签名**源码写法也完全一样**的（`f(a : int)` 写两遍）报的是老的「重复定义」；
- `fn put(v : T)` 与 `fn put(v : int)` **不**冲突（`T` 擦成 `Object`，描述符不同）。
  **泛型类的方法可以重载**，解析时先把类型实参替换进去再打分。

**与构造器的规则差异（有意为之）**：构造器同分时取**声明在前**的那个（阶段 18 的老规矩，
有既有测试钉着）；函数/方法同分时**报错**。改动前请先看 `BOOTSTRAP.md` 的「阶段 19d」一节。

**可见性与 `static` 的过滤顺序**：候选**先按可见性过滤、再按 static/实例一致性过滤、最后才打分**
——`private` 顶层函数只有它自己那个文件参与候选，`对象.m(...)` 只挑实例方法、
`类名.m(...)` 只挑 `static` 方法；类体里裸写 `m(...)`（隐式 `this` / 隐式类名）两种都收。
这样「看不见的重载」不会把看得见的挤掉。全都调不动时才报原来的 `private` / 调用方式错误。

**`main` 只有无参的那个是入口**：`main(a : int)` 现在是合法重载，但它只是普通函数
（`rsf run` 会提示「入口函数 main 不能有参数」）；**两个无参 `main()`** 报「入口歧义」。

**泛型函数不支持**（与重载无关，类型参数只能写在 `class` 上）：写 `fn pick&<T>(a : T)`
会明确报「泛型函数还不支持：类型参数只能写在 class 上……想要「什么类型都能收」的函数，
就为每种类型各写一个重载」。

> **`rsfSrc/bootstrap/*.rsf` 里的绕开写法保持原样**（`matchAny2/3/4` 等）：
> 那些已被自举差分测试逐字节钉住，重载只对**新写的** rsf 代码开放。

### 入口

```
fn main() -> void {
    println("hello, rainStarfox!")
    rtn null
}
```

名字叫 `main` 的函数会被编译成 `public static void main(String[])`，所以 `main.rsf`
编译出的 `main.class` 能直接 `java main` 启动。

### 变量

两个关键字，区别只在**能不能重新赋值**：

```
var x = 1        // 可变
x = x + 1          // OK

imutvar y = 2      // 不可变
y = 3              // 编译错误：变量 'y' 是 'imutvar' 声明的，不能重新赋值
```

两者都必须带初始化式。不写类型就从初始化式推断，之后不能改类型。

**要写类型就用 Kotlin 后缀风格 `名字 : 类型`，类型写在名字后面**（2026-10-08 起；
老写法 `varis int x = 0`（类型写在名字前面）已不再接受）：

```rsf
var x : int = 0                    // 原来写 varis int x = 0
imutvar name : string = "hi"
var p : Point = new Point(1, 2)
var box : Box&<int> = new Box&<int>(0)
var a : int[] = new int[3]
var maybe : ?string = null
```

> **可变变量的关键字 2026-10-11 由 `varis` 改名为 `var`**（用户拍板）。现在 `var` 是
> 唯一写法，`varis` **不再接受**——写了会直接报
> `error[lexer]: 'varis' 已改名为 'var'：请写成 'var x : int = 0'`。
> `imutvar` 没改名、语义也没变；`var` 与 `imutvar` 的区别仍然只是**能不能重新赋值**。

- **冒号两侧的空格随意**（`x:int` / `x : int` 都行），但报错文案统一按 `名字 : 类型` 写。
- 冒号后面必须是**类型**：`var x : 5 = 1` 会明确报「类型标注要写成 `x : 类型`」，
  而不是含糊地说一句「这里需要类型」。
- **字段**（类里的 `var` / `imutvar`）与局部变量同一条规矩：`var count : int = 0`，
  而且字段**必须**写类型（不能靠推断，否则各个方法里看到的类型可能不一致）且**必须**给初值。
- 只有 `:` 这一个地方变了——`if c : 语句`、标签 `名字:`、三目 `? :` 都不受影响。

### 语句

| 语句 | 例子 |
| --- | --- |
| 变量声明 | `var x = 1 + 2` / `imutvar y = 3` / `var n : int = 0` |
| 赋值 | `x = x * 3`（只能给 `var` 声明的变量赋） |
| 返回 | `rtn 42` / `rtn` / `rtn null` / `return 42` |
| 表达式语句 | `println("hi")` |
| 块 | `{ imutvar y = 1 }` |
| 条件 | `if (c) { ... } else { ... }` / `if c : 单条语句` |
| 循环 | `while (c) { ... }` / `for (i in 0..9)` / `for (init; cond; step)` |
| 跳出 / 下一轮 | `break` / `continue` |
| 枚举分派 | `switch (k) { TokenKind.IDENT : ... else : ... }` |
| 抛错 | `fail "出错了"`（只能在 `!T` 函数里，或者 `try` 里） |
| 接错 | `try { ... } catch (e : string) { ... }`（也接受老写法 `catch (e string)`） |
| 标签 / 跳转 | `loop:` / `goto loop` |

- `rtn` 和 `return` **完全等价**。
- `rtn` 或 `return` **独占一行**时表示"不带值返回"；要给返回值就把值写在**同一行**。
- 语句之间可以用换行分隔，也可以写可选的分号。

### `if` 的写法

```
if (x > 2) { rtn 1 }              // 花括号
if x > 2 : rtn 1                  // 冒号 + 单条语句
var y = if x > 2 ? 1 : 2        // 三目——它是**表达式**，不是语句
```

三种写法都能跟 `else`，`else` 后面接另一个 `if` 就形成 `else if` 链：

```
fn classify(x : int) -> string {
    if (x < 0) {
        rtn "negative"
    } else if (x == 0) {
        rtn "zero"
    } else if (x < 5) {
        rtn "small"
    } else {
        rtn "big"
    }
}

if n > 3 : println("big")
else : println("small")
```

因为三目是表达式，所以可以直接喂给 `rtn`：

```
fn pick() -> int {
    imutvar x = 3
    rtn if x > 2 ? 100 : 200
}
```

括号是可选的，条件都是完整表达式——`if (a) + (b) > 0 { ... }` 也解析得对。

### `while` / `break` / `continue`

```
fn fibOf10() -> int {
    var a = 0
    var b = 1
    var i = 0
    while (i < 10) {
        imutvar t = a + b
        a = b
        b = t
        i = i + 1
    }
    rtn a
}
```

`while (true)` 且循环体里没有能逃出本层的 `break` 时，被识别为死循环，
所以下面这种写法**不会**误报"函数可能不返回"：

```
fn f() -> int {
    while (true) {
        rtn 7
    }
}
```

### `for`

**Kotlin 式** —— 区间**两端都含**，可选 `step`：

```
for (i in 0..10 step 2) { println(i) }      // 0 2 4 6 8 10
for (i in 3..0 step -1) { println(i) }      // 3 2 1 0（步长为负自动递减）
```

起点、终点、步长都只求值一次（和 Java / Kotlin 一致）。`step` 是**上下文关键字**——
只有在这个位置才当关键字，别处照样可以叫 `step`。

**Java 式** —— 三段都可以省略，用 `,` 分隔多项：

```
for (var i = 0; i < 10; i = i + 1) { ... }
for (;;) { ... if (done) { break } }        // 无限循环
```

两种写法里 `continue` 都跳到**步进**而不是条件——这正是 `for` 和 `while` 的语义差别。
把 `for` 草率地解糖成 `while` 会让 `continue` 跳过步进而死循环，所以 `for` 在 IR 里
有自己的一等节点和独立的更新标签，有专门的测试守着这条。

### `goto` 与标签

C/C++ 意义上的无条件跳转。标签是**函数作用域**，不能跨函数跳。

```
fn main() -> void {
    var n = 0
countdown:
    if (n >= 3) { goto finished }
    println("countdown " + (3 - n))
    n = n + 1
    goto countdown
finished:
    rtn
}
```

### 块作用域

`{ }` 引入新作用域，内层可以遮蔽外层的同名变量：

```
var x = 1
{
    var x = 99        // 这是另一个变量
    println(x)          // 99
}
println(x)              // 1
```

### 类型

`void` `int` `long` `float` `double` `string` `bool` `byte` `char`
（`str` 是 `string` 的别名，`boolean` 是 `bool` 的别名）

字面量后缀：`123` int、`123L` long、`1.5` double、`1.5f` float、`1.5d` double。
字符字面量用单引号：`'a'`、`'\n'`、`'\u0041'`（认得的转义：`\n` `\t` `\r` `\0` `\\` `\'` `\"` 和四位十六进制的 `\uXXXX`）。

`byte` 与 `char`（阶段 16）在 JVM 上分别占 1 格槽、描述符是 `B` / `C`，
栈上和局部变量里都是 int；`?byte` / `?char` 装箱成 `java/lang/Byte` / `java/lang/Character`。

**类型规则**（隐式转换边界就这么多，一张表说完）：

| 源类型 | 能隐式赋给 |
| --- | --- |
| `byte` | `int`、`long`、`float`、`double` |
| `char` | **一个都没有**（只能赋给 `char` / `?char`） |
| `int` | `long`、`float`、`double` |
| `long` | `float`、`double` |
| `float` | `double` |

- **算术一律按 int 提升**（Java 的 binary numeric promotion）：`b + b` 是 `int`、
  `'a' + 1` 是 `int`、`c < 100` 也能比。byte 和 char 因此没有自己的一套溢出规则。
- **`char` 不是整数**：`char` 与任何数值之间都要显式转 —— `charToInt(c)` 取码点、
  `toChar(n)` 反向转。`var n : int = 'a'` 和 `var c : char = 65` 都会报错。
- **窄化要显式**：`int → byte` 用 `toByte(n)`（就是 Java 的 `(byte) n`，会截断）。
  rsf 没有 `(byte) x` 这种强制转换语法，全靠这几个内置函数。
- **字面量特例**：`0..127` 的整数字面量可以直接赋给 `byte`（`var b : byte = 100`），
  否则 byte 类型根本没法初始化。只认直接写出来的字面量，变量和表达式一律不认。
- 可空形式 `?byte` / `?char` 跟既有的 `?T` 机制完全一致（装箱、`?:` 兜底、`!!` 解包）。
  注意既有规矩照旧：可空目标**不接加宽**（`var x : ?long = 1` 报错，写 `1L`）。

数值按 `int → long → float → double` 自动加宽；反过来（窄化）需要显式转换函数。

### 可空类型 `?T`

类型名前面加 `?` 表示「可能是 null」：

```rsf
fn findName(id : int) :: ?string {
    if (id == 1) { rtn "rainStarfox" }
    rtn null
}

fn greet(who : ?string) -> string {
    rtn "hello, " + (who ?: "stranger")     // 参数也可以可空
}

fn maybeInt(n : int) -> ?int {               // 可空的基本类型
    if (n > 0) { rtn n * 10 }
    rtn null
}
```

配套的三件工具：

| 写法 | 作用 |
| --- | --- |
| `x == null` / `x != null` | 判断是不是 null |
| `a ?: b` | 左边是 null 就取右边（**结果是非空版**，这是主要的解包方式） |
| `x!!` | 强制解包，是 null 就抛 `NullPointerException` |

变量也可以显式标注类型，这是声明「一开始就是 null」的可空变量唯一的办法：

```
var s : ?string = null
var k : int = 7            // 非空也可以写，只是没必要
```

**检查规则**（可空的意义就在这几条）：

- `?T` 不能赋给 `T` —— 这是可空要拦住的核心事情
- `rtn null` 只能出现在返回类型可空的函数里；非空函数会提示你改成 `?T`
- 非空值跟 `null` 比较会被**直接拦下**（恒为真/假，多半是类型写错了）
- 可空值不能直接参与算术：「先用 `?:` 给个默认值，或者用 `!!` 解包」
- `var x = null` 不写标注会报错（没法从 null 推断类型）
- 可空值**可以**直接拼进字符串（`"x = " + maybeNull`），null 会拼成 `"null"`

**JVM 上的表示**：引用类型（`?string`）零成本——null 本来就是引用类型的一个值。
基本类型（`?int` 等）必须**装箱**成 `Integer`/`Long`/…，所以 `?int` 在字节码里是引用、
占一格槽，参与运算前要先解包。`?:` 的代码生成会让两条分支都汇成「结果的装箱引用」，
汇合之后再按需要拆箱——这样栈上类型在汇合点永远一致（JVM 校验器的硬要求）。

### 错误返回 `!T` 与 `try` / `catch`

返回子句前面加 `!`，表示**这个函数可能失败**（阶段 14，类似 Zig 的错误返回）：

```rsf
public fn parseInt(s : string) -> !int {
    if (s.length() == 0) {
        fail "空字符串没法转数字"          // fail 抛出一个错误
    }
    rtn 42                                // 正常路径返回的就是 int
}
```

**`!T` 的含义**：正常路径返回 `T`，出错时走**异常**这条路。所以 `!T` 在 JVM 上和 `T`
是同一个描述符，不新造类型、不装箱；`!void` 就是「可能失败、没有返回值」。

**`fail <消息>`** 只能写在两种地方，否则编译期报错（`fail` 要用在 `!T` 函数里，或者包进 `try`）：

- 返回类型是 `!T` 的函数里；
- 或者 `try` 块里（由这个 `try` 的 `catch` 接住）。

消息必须是 `string` 表达式（可以拼：`fail "编号不能是负数：" + id`）。

**调用方必须显式处理错误**，三种写法任选一种；都做不到就是编译错误，报错里会把三种都列出来：

```rsf
fn main() -> void {
    // (a) 包进 try：catch 变量是 string，就是 fail 的那条消息
    try {
        var n = parseInt("")            // try 块里可以直接调 !T 函数（视为已处理）
        println("n = " + n)               // 上面失败了，这句不会执行
    } catch (e : string) {
        println("出错了：" + e)
    }

    // (b) ?: 给兜底值：出错取右边，结果类型是非 ! 的正常类型
    println(parseInt("") ?: -1)           // -1

    // (c) !! 往上抛：本函数自己也得是 !T（写 !int / !void …）
    println(parseInt("12")!!)             // 本函数是 !T 才行，否则编译错误

    // 跨多层函数传播：main → describe → lookup，最后由 main 的 try 接住
    try {
        println(describe(-1))
    } catch (e : string) {
        println("跨层接住：" + e)
    }
    rtn
}
```

规则一览：

| 规则 | 说明 |
| --- | --- |
| `fail` | 只在 `!T` 函数里，或 `try` 块里 |
| 调用 `!T` 函数 | 必须用 `try` / `?:` / `!!` 之一处理，否则编译错误 |
| `?:` | 出错取右边的值；**结果类型是非 `!` 的正常类型** |
| `!!` | 出错就往上抛；只有本函数也是 `!T`（或包在 `try` 里）才行 |
| 正常路径 | `!T` 调用拿到的就是 `T` |
| `try` / `catch` | 只能一个 `catch (名字 : string)`；没有 `finally`、没有多重 catch |
| `rtn` | `!T` 里的 `rtn x` 返回正常值；不带值的 `rtn` 只允许在 `!void` 里 |
| 构造器 | 不能是 `!T`（而且阶段 18 起构造器**根本没有返回类型**）：`new C(...)` 没有处理错误的写法，把可能失败的部分挪到普通方法里 |

**两端一致怎么做到的**：`fail` 在字节码里是 `new java/lang/RuntimeException(msg)` + `ATHROW`
（直接用 JDK 现成的异常类型，不新造类）；解释器里抛一个内部的 `RsfFailure`，消息一模一样。
`try` 在字节码里就是 `visitTryCatchBlock`（保护范围 → handler，handler 里 `getMessage()`
存进 catch 槽），解释器里按同一张「pc 区间 → handler」的表查——两边用的是同一个模型，
所以从 `try` 块里 `goto` / `rtn` 出去都不会串味。

`?:` 稍微特别：JVM 的异常处理器一进去操作数栈就被清空了，没法在**表达式中间**直接放
处理器（还会写出 `VerifyError` 的类），所以编译产物把「调用 + 捕获」搬进一个合成静态方法
（出错返回 `null`），调用点只留下一个普通静态调用加一次判空。解释器用「错误穿过的函数帧」
做同样的区分：**实参求值时的失败不算这次调用出错**，会继续往外层传（两端一致）。

**边界（照实写清楚）**：

- `try` / `?:` 只接 **rsf 自己的** `fail`。Java 互操作抛出来的异常（例如
  `Integer.parseInt("abc")` 的 `NumberFormatException`）在解释器里不会被子解释器的 `try`
  接住，编译产物那边却会——这种跨语言异常的差异本版**不做**。
- 可空与错误返回**不能在同一个地方混用**：`fn f() -> !?int` 这样的函数，写 `f()!!`
  或 `f() ?: 0` 会明确报「暂不支持在同一处同时用可空与错误返回」（`!!` 到底是解包还是
  抛错说不清）。拆成两句写就行：先用 `try` 把错误处理掉，再用 `?:` 处理 null。

`rsfSrc/lang/errors.rsf` 是完整示例：

```bat
gradlew run --args="run rsfSrc/lang/errors.rsf"
gradlew run --args="build rsfSrc/lang/errors.rsf -o out"
java -cp out errors
```

### 表达式

- 算术：`+ - * / %`（int 的 `/` 是截断除法，溢出按 JVM 语义回绕）
- 比较：`== != < > <= >=`，结果是 `bool`
- 逻辑与 / 或：`&&` / `||`（**短路**，两边都必须是 `bool`）
- 逻辑非：`!`（只作用于 `bool`）
- 一元负号：`-`
- 字符串拼接：任意一边是 `string` 时 `+` 按拼接处理，另一边自动转字符串
- 三目：`if 条件 ? 值1 : 值2`
- 空值合并：`a ?: b`
- 强制解包：`x!!`（后缀）
- 函数调用：`foo(a, b)`

非数值的 `==` / `!=` 走引用语义（字符串比内容、可空装箱值比 `equals`、`null` 比引用、枚举比单例）——
解释器和编译产物都统一按 `Objects.equals` 处理，这是两端输出能逐字节一致的原因之一。

#### `&&` / `||`（阶段 19c）

优先级从松到紧（和 Java / Kotlin 完全一致）：

```
?:   <   ||   <   &&   <   == !=   <   < > <= >=   <   + -   <   * / %   <   一元   <   后缀
```

所以 `a == b && c == d` 是 `(a == b) && (c == d)`，`x || y && z` 是 `x || (y && z)`。

**两边都必须是 `bool`**（可空 `?bool` 也不行，要先 `?:` 兜底或 `!!` 解包），结果也是 `bool`：

```rsf
if (c == '/' && peek(1) == '/') { rtn true }     // 没有 && 时这里得写成两层嵌套 if
if (i < n && xs[i] == want) { ... }              // 短路顺带挡住越界
```

**短路是真的短路**：`a && b` 先算 `a`，`a` 为假时 `b` **一次都不会被求值**；`a || b` 对称。
两条后端都按这个顺序实现：

- 解释器：`if (!a) return false else eval(b)`；
- 字节码：`算 a → IFEQ 假 → 算 b → GOTO 结束 → 假: ICONST_0 → 结束:`（`||` 对称）。
  右边被夹在一条条件跳转和一条 `GOTO` 之间，**不在直路上**，所以不是「两边都算完再合并」。

`&` 与 `&&` 是**两个不同的记号**：`&` 只用来引出泛型实参表 `&<int>`，`&&` 是逻辑与，
两者在同一行里（`Box&<int>` + `a && b`）互不干扰。rsf 里没有按位或，所以单独的 `|`
会在词法阶段直接报「逻辑或要写两个竖线」。

### `enum`（阶段 19c）

```rsf
public enum TokenKind {
    IDENT,
    INT_LIT,
    STRING,
    EOF,          // 尾逗号可写可不写
}
```

- 顶层声明，权限修饰词可选（缺省 `public`，和 `fn` / `class` 一样）；至少要有一个值；
  值名重复、枚举名与类名/函数名重名都会在编译期报错（枚举和类**共用类型命名空间**）。
- **类型位置随便用**：字段、参数、返回值、局部变量、数组 `TokenKind[]`、可空 `?TokenKind`。
- **取值**：`TokenKind.IDENT`。枚举值只能从**类型**上取，不能拿变量当接收者
  （`k.IDENT` 表达的其实是「k 是不是 IDENT」，那是 `==` 的活，编译器会直接告诉你）。
- **比较**：同一个枚举之间可以 `==` / `!=`；**不同枚举之间比较直接报错**。
- **打印就是值名**：`println(k)` 打 `IDENT`，`"kind = " + k` 同样是 `IDENT`；
  枚举字段出现在对象的 `toString()` 里也是值名（`Lexeme(kind=INT_LIT, ...)`）。

#### 编译口径：枚举是**普通类**，不是 JVM 的 `enum`

这一点必须写清楚，因为它决定了能和不能和 Java 互操作到什么程度。一个
`public enum TokenKind { IDENT, INT_LIT }` 生成出来的类是这样的：

```java
public final class TokenKind extends java.lang.Object {
    public static final TokenKind IDENT;      // 每个值一个单例
    public static final TokenKind INT_LIT;
    private final String $name;                // 私有的名字字段（$ 前缀见下）
    private TokenKind(String name) { super(); this.$name = name; }
    public String toString() { return this.$name; }
    static {                                   // <clinit> 里构造全部单例
        IDENT = new TokenKind("IDENT");
        INT_LIT = new TokenKind("INT_LIT");
    }
}
```

- **不用 `java.lang.Enum` / `ACC_ENUM`**：那要连带 `values()` / `valueOf()` /
  `getEnumConstants` 一整套。我们真正要的只有「一组具名单例」+「打印出名字」，
  用普通类表达，两条后端都是最直接的那条路（字节码 `GETSTATIC` 取单例、
  解释器取一张常量表里的同一个对象）。
- **`==` 等价于「同一个常量」**：值字段是 `static final`、构造器是 `private`，
  外面造不出第二个实例，所以引用相等就够了（两端都不会退化成比字符串）。
- **打印名字两端同源**：编译产物靠生成的 `toString()`（`String.valueOf` /
  `PrintStream.println` / `StringBuilder.append` 都会调到它），解释器靠
  `RsfEnumValue.toString()`，两者返回的都是值名。
- 私有的名字字段叫 `$name` 而不是 `name`：rsf 的标识符写不出 `$`
  （见 `Lexer.isIdentStart`），所以它和任何值名都不可能撞车——`enum E { name }` 是合法的。
- 访问 `TokenKind.IDENT` 会触发 `<clinit>`，单例在那之前不会存在，这一点和 Java 静态常量一样。

**已知边界**：它在 JVM 层面不是一个真正的 enum，所以**外部 Java 代码不能用
`switch` / `EnumSet` / `values()` 去用它**（rsf 内部完全够用）。真要和 Java 的 enum 互操作，
等将来有需求再补 `ACC_ENUM`。

### `switch`：枚举分派（阶段 19c；阶段 21 起分支不写 `case`）

```rsf
switch (kind) {
    TokenKind.IDENT : println("标识符")
    TokenKind.INT_LIT : {
        var n = parseInt(text) ?: 0
        println("数字 " + n)
    }
    TokenKind.STRING : println("字符串")
    else : println("其它")
}
```

> **阶段 21**：分支头不用再写 `case`，直接写 `枚举名.值名 : 语句`——和 Kotlin `when` 的臂一个样子。
> 旧写法 `case 枚举名.值名 : ...` 仍然能编译（`case` 还是关键字），只是每个分支会发一条弃用警告：
> `'case' 已弃用（is deprecated since 0.9.0）：switch 的分支直接写 '枚举名.值名 : 语句'，不用再写 'case'`。

四条定死的规矩：

1. **被分派的值必须是枚举**（`switch` 只能对枚举做）。要按数值 / 字符串分派就写 `if` / `else if` 链。
2. **分支头只接受枚举常量**（`枚举名.值名`）：没有区间、没有比较、没有字面量。
3. **默认分支写 `else :`**，不是 Java 的 `default :`——语言里已经有一个「都不是就走这里」的关键字了。
4. **没有 fallthrough**：每个分支执行完就跳出 `switch`，不需要（也不能）靠 `break` 收尾；
   分支体跟 `if c : 语句` 同一套规则（单条语句，或者 `{ ... }` 多句）。

编译期的三道检查（都带「有哪些值」的提示）：

- 分支的值必须是**同一个枚举**的常量：写别的枚举的常量、写不存在的值名都直接报错；
- **同一个值不能有两个分支**（先后顺序没有定义，与其定规矩不如拦住）；
- **没有 `else` 时必须穷尽**：缺哪个值就报哪个值。只匹配枚举常量时穷尽性是编译期算得准的，
  而「漏了分支却静默什么都不做」是最难查的一类 bug，所以宁可不让它编译过。

`switch` 是**语句**不是表达式（要取值就用三目）。它**不消费 `break` / `continue`**：
循环里的 `switch` 分支里写 `break` 跳出的是那层循环（既然没有 fallthrough，就没有
「跳出 switch」的需求）。

**两端的实现**（都只对被分派的值求值一次）：

- 解释器：先算一次主体，再按声明顺序用 `===`（引用相等，枚举是单例）逐个比常量，
  命中就跳进那一支；都不命中且写了 `else` 就进 `else`。
- 字节码：`DUP; GETSTATIC 枚举.值名; IF_ACMPEQ 跳板` 逐个比，跳板只做 `POP; GOTO 分支体`
  （跳转时栈上还留着被分派的值，必须弹掉，否则分支体入口的栈帧校验过不去）。

**没写 `else` 且穷尽时的唯一旁路**：引用类型数组的元素默认是 `null`（`switch (arr[1])`），
而枚举常量都不是 null，于是哪一支都不匹配。这时两端**抛同一条 rsf 运行期错误**
（消息在 `sema/Builtins.kt` 的 `EnumRuntime` 里），`try { ... } catch (e : string)` 能接住；
带 `else` 的 `switch` 则走 `else`，不抛错。**不会**悄悄什么都不做——静默跳过会让
`catch` 接不到、输出也看不出问题。

### `interface` 与 `override`（阶段 20）

```rsf
public interface Greeter {
    fn greet(name : string) -> string                                   // 抽象方法：不写体
    fn shout(name : string) -> string { rtn greet(name) + "!" }         // 默认方法：写体
}

public interface Loud : Greeter { }        // 接口可以继承接口，多个用逗号

class Dog(name : string) : Greeter {       // 类实现接口；继承与实现共用 `:`
    public override fn greet(name : string) -> string {                 // override 可选
        rtn "woof " + name
    }
}

fn main() -> void {
    var g : Greeter = new Dog("rex")
    println(g.greet("bob"))                 // woof bob（虚分派）
    println(g.shout("bob"))                 // woof bob!
    rtn
}
```

几条定死的规矩（设计见 `DESIGN-20.md`，D1–D10 是用户批准过的口径）：

- **接口里只能写方法**（D3）：写字段/变量直接报「接口里只能写方法…」。接口也**不发字段、不发 `<init>`**，
  类文件里是 `ACC_INTERFACE | ACC_ABSTRACT` 的接口，抽象方法没有 `Code`。
- **接口可以继承接口**（D4）：`public interface Loud : Greeter { }`；继承成环会报错。
- **类继承**（阶段 25 起支持）：`class A : SomeClass` 里的父类唯一、排最前，后面跟接口
  （`class D : Base, Greeter`）；父类在阶段 20 那会儿是**明确不做**的（D2），现在按 Kotlin 的写法做完了，
  见下面的「继承与封口：`final` / `override` / `abstract`（阶段 25，阶段 27 反转默认值）」。
- **实现配对**：rsf 接口按签名严格配（参数类型 + 返回类型都对得上），**JVM 接口按名字 + 参数个数配**
  （擦除后的签名 rsf 侧写不出来）；漏实现会列出缺哪些方法，签名/返回类型对不上会说明差在哪。
  **`private` / `protected` / `static` 方法不算实现**（`check` 会拦；不拦的话 JVM 一调就 `AbstractMethodError`）。
- **多接口同签名默认方法冲突**（D5）：类里必须显式写一个同签名方法拍板，否则报「说不清该用哪一份」。
- **`override` 是可选的**（Java `@Override` 的风格）：不写也合法；**写了就必须真覆盖**，否则报
  「父类型里没有可覆盖的方法」。类里查父接口（含 JDK 接口与默认方法），接口里查父接口。
  写在顶层函数/构造器/字段上直接报错。它**完全不进字节码**：同名源只差这一个关键字，产物逐字节相同。
- **互操作**：可以直接 `class Job : Runnable` / `class ByValue : Comparator&<string>`，把 rsf 对象
  交给 JDK 代码回调（`new Thread(job)`、`Collections.sort(xs, new ByValue())`）。编译器会给收窄的实现
  补 `ACC_BRIDGE | ACC_SYNTHETIC` 桥方法，解释器会给对象套 `java.lang.reflect.Proxy`——两条路都走通，
  不会出现「解释器能跑、`java` 一跑 `AbstractMethodError`」。

**已知边界**：`interface` / `override` **已同步到自举链**（2026-10-15，第 1 刀 `Parser.rsf` 收语法 +
第 2 刀 `Backend.rsf` / `Interp.rsf` / `Sources.rsf` 收语义：接口类头、默认方法、`INVOKEINTERFACE`、
多文件顶层接口），探针台 `build/verify21/bootbackend/run-probes.sh both` 四列全绿。
自举源（`rsfSrc/bootstrap/**`）**自身**还没改写成接口风格——只是「能写」了。
注解（阶段 21）**已整条同步到自举链**（2026-10-15）：第 3 刀 `Parser.rsf` 收 `annotation` 声明 /
元素表 / 伴生实体 / `@` 使用点（AST 与 Kotlin 版逐字节一致），第 4a 刀 `Backend.rsf` 收代码生成
（注解接口 `ACC_ANNOTATION` + `@Retention(RUNTIME)`、元素方法 + `AnnotationDefault`、伴生类、
`RuntimeVisibleAnnotations` 使用点、方案 B 懒构造访问器），第 4b 刀 `Interp.rsf` 收解释器语义
（元素字段/默认值回填、伴生实体执行、元素访问器非虚分派），第 5 刀收嵌套类型名 `Marker::Impl`
（类型位置 + 字段/形参位置 + `new Marker::Impl(...)`）。`Marker` 这类注解接口当静态类型时，
元素**读**发 `getfield Marker$Impl.value`、元素**调用**发 `invokeinterface Marker.value()`。
探针台 12 个正例（`p1`–`p7` / `a1`–`a6`）四列 stdout 一致且类字节差异 0，见 `BOOTSTRAP.md`。

### 继承与封口：`final` / `override` / `abstract`（阶段 25，阶段 27 反转默认值）

```rsf
public class Base(x : int) {                             // 类默认可被继承
    public fn tag() -> string { rtn "base" }            // 方法默认可被覆盖
    public final fn id() -> int { rtn x }               // final：到此为止，子类不能再覆盖
}

public abstract class Shape(size : int) {
    public abstract fn area() -> double                 // 没有函数体，子类必须实现
}

public class Box&<T>(v : T) : Base(1) { }                // 泛型类也能当父类

public final class Sealed(x : int) : Base(x) { }         // final class：别人不能再继承它

public class D(y : int) : Base(y * 2), Greeter {         // 父类唯一且排最前，后面只跟接口
    public override fn tag() -> string { rtn "derived" }
    public fn use() -> string { rtn super.tag() + "/" + tag() }   // "base/derived"
}

public class D2(v : int) : Base(v) {
    constructor() { super(1) }                           // 次构造的父类委托 = 第一句
}

fn main() -> void {
    var base : Base = new D(3)
    println(base.tag())        // derived（虚分派）
    println(base.id())         // 继承来的 final 方法照常调用
    rtn
}
```

几条定死的规矩（设计见 `DESIGN-25.md`，默认值在阶段 27 按 `DESIGN-27.md` 反转）：

- **默认开放，`final` 封口**：类不写 `final` 就能当父类，方法不写 `final` 就能被子类覆盖；
  `final class` 不能再被继承、`final fn` 不能再被覆盖（`override final fn` 把一条覆盖链就地封住）。
  旧法（阶段 25–26）要求写 `open` 才有这些自由，阶段 27 删掉了 `open` 关键字。
- **禁止字段遮蔽**（与 Kotlin 的故意偏离，理由同样是「两端逐字节对拍」）。
- **构造顺序**：父 `<init>(实参)` → 自己的字段初值 → 构造体；`super(...)` 与 `this(...)` 互斥且只能第一句。
- **`super` 两种形态**：`super.f()` 走直接父类那一份实现，`super(Greeter).f()` 走指定接口的默认实现
  （Q1 选了圆括号写法，因为 `<`/`&<` 已经占满了尖括号）。
- **`abstract`**：`abstract class` 不能 `new`；它的抽象成员由具体子类实现，漏一个就报错并列出缺哪些；
  抽象成员不能带函数体、不能是 `private`/`static`，也不能和 `final` 写在一起。
- **`static` 不继承**：只能写 `类名.成员`；子类里写 `Base.make(...)` 是唯一写法，报错会点明这一点。
- **可以继承 JDK 类**（Q3）：`class MyError(msg : string) : Exception(msg)` 能直接用，
  `getMessage()` 这类继承来的方法照常调用、`super.getMessage()` 打到声明类（`java.lang.Throwable`）那一份。
  父类必须是**非 final** 的类（`StringBuilder` 这类会被编译期拒掉）；`protected` 构造器（`ClassLoader()`）
  两端都建得出来。`java/lang/Object` 的方法只放行 rsf 自己定义语义的 `toString` / `equals` / `hashCode`
  （`toString()` 与裸 `println(对象)` 同一串，`equals` 是对象同一性），别的（`wait` / `notify` / `clone`）按
  「没有这个方法」处理、`getClass()` 编译期直接报错。**已知边界**见下面「语言限制」里的类继承四条。

### 注解 `annotation`（阶段 21）

```rsf
annotation Marker(value : string = "x", count : int = 0) {
    companion entity Impl : Runnable {          // 注解的行为写在这里，至多一个
        fn run() -> void {
            println(value + " x" + intToString(count))   // 元素按裸名读
            rtn
        }
    }
}

@Marker(value = "hi", count = 3)                 // 可标：类/接口/注解声明/方法/字段
class Greeter { }

fn main() -> void {
    var m : Marker::Impl = new Marker("hi", 3)   // 在 rsf 这边把行为 new 出来跑
    m.run()                                      // hi x3
    rtn
}
```

- 手写预案里「元素 + 伴生实体」两半：元素是名字后的圆括号参数表、可带默认值；行为写进 `companion entity`。
- 伴生实体可具名（`Marker::Impl`）也可匿名（`Marker$companion`）；**继承本阶段只收接口**，不能继承类。
- 一个地方同一个注解只能标一次；没有默认值的元素必须给值。
- 注解接口带 `@Retention(RUNTIME)`、伴生类带 `annotationType()`；解释器与字节码两条路输出逐字节一致（设计见 `DESIGN-21.md`）。
- 已知边界：枚举元素的默认值还不支持；rsf 枚举不是 `java.lang.Enum`，用 Java 反射读枚举元素会 `AnnotationTypeMismatchException`。

### 内置函数

`print(x)` / `println(x)`，也支持 `println()` 只换行。

阶段 16 补上了「自己写词法器」要用的那一批（签名在 `sema/Builtins.kt`，解释器与字节码后端各有一份实现）：

| 函数 | 作用 | 编译产物里是什么 |
| --- | --- | --- |
| `toByte(int) -> byte` | 显式窄化（Java 的 `(byte)`） | `I2B` |
| `toChar(int) -> char` | 显式转换（Java 的 `(char)`） | `I2C` |
| `charToInt(char) -> int` | 取码点 | 什么都不用发（char 在栈上就是 int） |
| `intToString(int) -> string` | 整数转字符串 | `String.valueOf(int)` |
| `parseInt(string) -> !int` | 字符串转整数，失败就 `fail` | `Integer.parseInt` + 换成 rsf 的错误消息 |
| `isDigit` / `isLetter` / `isWhitespace(char) -> bool` | 字符分类 | `Character.isDigit` / `isLetter` / `isWhitespace` |
| `toUpper` / `toLower(char) -> char` | 大小写转换 | `Character.toUpperCase` / `toLowerCase` |

阶段 16b 又补上了四个**文件读写**函数（自举时把 `.class` 写出去要用）：

| 函数 | 作用 | 编译产物里是什么 |
| --- | --- | --- |
| `readText(string) -> !string` | 读整个文本文件（UTF-8） | `Files.readString(Paths.get(path))` |
| `writeText(string, string) -> !void` | 把字符串按 UTF-8 覆盖写入 | `Files.write(path, text.getBytes(UTF_8), OpenOption[0])` |
| `readBytes(string) -> !byte[]` | 读整个二进制文件 | `Files.readAllBytes(Paths.get(path))` |
| `writeBytes(string, byte[]) -> !void` | 把 `byte[]` 原样覆盖写入 | `Files.write(path, data, OpenOption[0])` |

四个都是 `!T`（可能失败），调用方必须显式处理（`try` / `?:` / `!!`）。
**两端为什么能一字不差**：两边调的是**同一组 `java.nio.file` 方法**，同一个失败得到的异常消息
本来就相同；差的只是「抛什么」——解释器抛 rsf 自己的错误、编译产物抛 `RuntimeException`，
消息格式的唯一真源是 `sema/Builtins.kt` 的 `FileRuntime`。失败消息长得像：

```
readText: 文件操作失败 'no-such.txt'：no-such.txt
```

`rsfSrc/lang/files.rsf` 是完整示例（读文本、写文本、写二进制再读回来、
故意读一个不存在的文件并用 `try` 接住、用 `?:` 给兜底值）。

分类与大小写**直接调 `java.lang.Character` 的对应方法**（解释器也是），所以 Unicode 边界
情况两端一定一模一样。`parseInt` 与四个文件函数会失败，处理方式和 rsf 自己写的 `!T`
函数完全一样：`try` / `?:` / `!!` 三选一，不处理就编译报错。

`string` 的切片**不是内置函数**，它们是 `java.lang.String` 的真方法，走 JVM 互操作：

```rsf
var s : string = "rainStarfox"
println(s.length())            // 11
println(s.charAt(0))           // r —— 返回类型就是 char
println(s.substring(5, 8))     // Sta
println("首字母 " + s.charAt(0))
```

`rsfSrc/lang/stdlib.rsf` 把上面这些串成了一个能跑的例子（字符分类、转义、切片、`parseInt` 的三种处理方式）：

```bat
gradlew run --args="run rsfSrc/lang/stdlib.rsf"
gradlew run --args="build rsfSrc/lang/stdlib.rsf -o out"
java -cp out stdlib
```

---

## 构造器：主构造 + 次构造（阶段 18）

构造器有**两种写法**，都学 Kotlin：主构造写在**类头的括号**里，次构造用关键字
`constructor`，一个类可以写多个次构造（阶段 18 时构造器是 rsf 里**唯一**允许重载的东西；
**阶段 19d 起顶层函数与类方法也能重载了**，见上面「重载（阶段 19d）」一节——
两者的挑选规则有一处**故意不同**：构造器同分取声明在前，函数/方法同分报错）。

```rsf
// ① 主构造：参数写在类头，字段初值里能直接用
public class Point(x0 : int, y0 : int) {
    var x : int = x0                 // 主构造的「体」就是字段初值
    var y : int = y0
    var doubled : int = x0 * 2 + y0
    public fn sum() -> int { rtn x + y }
}
var p = new Point(3, 4)

// ② 次构造：constructor(参数) { ... }，可以写多个，按实参挑
public class Q {
    var v : int = 0
    constructor() { }                       // 次构造
    constructor(n : int) { v = n }            // 次次构造
    constructor(s : string) { v = s.length() }
}
var a = new Q()  var b = new Q(7)  var c = new Q("abcd")

// ③ 委托：Java 风格的 this(...)，写在构造器体的**第一句**
public class R(x0 : int) {
    var x : int = x0
    constructor() {
        this(5)                     // 先跑主构造（字段初值也在那时跑）
        println("回来之后再跑这句")
    }
    constructor(s : string) {
        this(s.length())            // 委托链：string -> 主构造
    }
}
```

规则一览：

| 规则 | 说明 |
| --- | --- |
| 主构造 | `class P(参数) { ... }`。参数作用域 = **字段初值**（主构造的体）；普通方法里看不到它 |
| 次构造 | `constructor(参数) { ... }`。**不写返回类型**、不能写权限修饰词、不能 `static` |
| 能写几个 | 次构造随便写几个；参数表（按擦除后的类型）不能重复 |
| 挑哪一个 | `new P(实参)` 在**编译期**按「个数 → 类型打分」挑好（精确匹配优先、加宽其次；**同分取声明在前**——这一条与函数/方法重载**故意不同**，后者同分报错），两端读同一份决定 |
| 委托 | `this(实参)` 必须是构造器体的第一句，目标是同类另一个构造器；不能成环、不能委托给自己 |
| 有主构造时 | **每个次构造都必须委托**（和 Kotlin 一样）：这样字段初值里用的主构造参数一定有值 |
| 一个都没有 | 等价于隐式无参构造器；写了任何一个之后就没有隐式的了 |
| 字段初值 | 只跑一次，跑在**委托链的根**（最终落到主构造）那条 `<init>` 里 —— 和 JVM 一致 |
| 不能再这样写 | `fn 类名(...)` 不再是构造器（明确报错，提示改用 `constructor`） |
| 仍然不支持 | 构造器不能是 `!T`。（**方法重载自阶段 19d 起已经支持**，构造器不再是唯一能重载的东西） |

### 自动 `new`：`new` 可以省

构造对象时 `new` 可以省略，`Point(3, 4)` 与 `new Point(3, 4)` **完全等价**：

```rsf
var p = Point(3, 4)          // 自动 new
var q = new Point(3, 4)      // 显式 new 仍然接受（两种都可以，new 关键字没有删）
var r = makePoint(3, 4)      // 名字解析到**函数** → 照旧是函数调用
var b = Box&<int>(0)        // 泛型类也可以省
```

- **判定依据是名字解析**，没有任何前瞻或猜：「这个名字是不是一个可以构造的类型」
  （用户自定义类，或者导入的 JVM 类，含 `java.lang.*` 隐式可用的那些）。
  是 → 构造；不是 → 按函数调用处理。
  这条无歧义的前提是**类名与函数名不能重名**（Checker 在检查一开始就报
  `'X' 已经是一个类名了，函数名和类名不能重名`），所以一个名字不可能既是函数又是类。
- **两种写法走的是同一段代码**：类型、泛型元数检查、构造器挑选（个数 → 类型打分、
  精确优先、同分取声明在前）、实参检查、以及交给两个后端的那份解析结果，都是**同一份**。
  所以 `Point(1,2).sum()`、作为实参/返回值/字段初值、放进数组元素、嵌套在表达式里，
  两种写法出来的字节码与解释器行为一模一样（有测试守着：`自动 new 与显式 new 等价`）。
- **JVM 类一视同仁**：`String(bytes)` 和 `new String(bytes)` 一样能用
  （`new` 现在能出现的地方，省掉 `new` 也能出现）。
- 参数个数/类型不对时，报错里会同时点出「`new` 可以省」这件事。
- 泛型实参写法 `X&<...>(...)` 只可能是构造（函数没有类型参数），parser 直接认成构造。

**两端一致怎么做到的**：每个构造器编译成一条 `<init>`（描述符按各自的参数，
JVM 本来就靠描述符区分构造器重载）：链的根那条发 `Object.<init>` + 字段初值，
委托的那些发 `ALOAD 0; 实参; INVOKESPECIAL <init>`（javac 生成的委托构造器是同一个形状）。
解释器按同一个次序跑：实参在调用方求值 → 走到链的根 → 跑字段初值 → 各层构造器体依次跑完。
`rsfSrc/lang/class.rsf`（次构造）、`rsfSrc/lang/arrays.rsf`（主构造 + 次构造）里都有例子。



数组类型写成**后缀** `T[]`，能出现在所有类型位置：局部变量、字段、参数、返回类型、
泛型实参（`Box&<int[]>`）。

```
var a : int[] = new int[5]                     // 基础类型数组，元素默认 0
var names : string[] = new string[3]           // 引用类型数组，元素默认 null
var ps : Point[] = new Point[2]                // 用户类数组（元素默认 null）
var boxes : Box&<int>[] = new Box&<int>[2]   // 泛型类数组（擦除后是 Box[]）
var grid : int[][] = new int[3][4]             // 嵌套：按「数组的数组」处理
var raw : byte[] = new byte[16]                // byte 数组（阶段 16b 落盘的前提）

a[0] = 1                                       // 下标写
println(a[0])                                  // 下标读
println(a.length)                              // 长度：是**属性**，不是 a.length()
```

| 规则 | 说明 |
| --- | --- |
| 长度 | `new T[n]` 的 n 是**运行时求值**的 int 表达式（可以算出来、可以在循环里按需分配） |
| 默认值 | 按 JVM 规则：数字 `0`、`bool` `false`、引用 `null` |
| 元素类型 | 数字 / bool / 引用都行；下标必须是 `int`（`char`、`long` 不接受） |
| 引用语义 | 数组是引用：传参、返回、赋给另一个变量拿到的都是**同一个**数组，在函数里改元素外面看得见 |
| 越界 | 两端抛**同一条** rsf 运行期错误：`数组下标越界：长度 3，下标 5`（负数下标同理） |
| 负长度 | `new int[-1]` 也是同一条格式的错误：`数组长度不能是负数：-1` |
| 嵌套 | `int[][]` 就是「元素为 `int[]` 的数组」，`new int[3][4]` 一次分配出 3 行 |
| 不做 | 数组字面量 `[1, 2, 3]`、`a.length()` 调用式、切片、`for (x in arr)`、锯齿数组 `new int[3][]`、数组直接打印 |
| 与 `Object` | 数组**可以**赋给 `Object`（JVM 上就是引用，两端都通）；反过来 `Object` 不能直接当数组用（需要强制转换，本版没有） |

**类型怎么表示**：`RsfType` 上多了一个 `arrayDepth: Int`（`int[]` → 1、`int[][]` → 2），
而不是把类型改成「元素类型」的递归结构。理由：JVM 的数组类型本来就是完全擦除的，
`int[][]` 的描述符就是 `[[I`——「套了几层 `[]`」已经是全部信息，描述符因此只有一条规则
`"[".repeat(depth) + 元素描述符`。`RsfBase` **没有**新增枚举值：数组是「类型的修饰」，
不是新的基础类型（真加了 `ARRAY`，全项目几十个 `when (base)` 都得跟着改）。

**越界为什么两端一样**：JVM 自己抛的 `ArrayIndexOutOfBoundsException` 消息是
`Index 5 out of bounds for length 3`，而解释器抛的是自己那套——直接放任就会分叉。
所以**两端都做显式边界检查**，都走同一套 rsf 运行期错误（解释器抛 `RsfFailure`、
编译产物抛 `RuntimeException`），消息格式只定义在 `ArrayRuntime` 一处，
字节码那边由程序类上的 `__rsf_arrayIndex$rt` 辅助方法生成（辅助方法而不是内联 try，
理由和 `parseInt` 一样：内联的异常处理器会让表达式中间的操作数栈没法还原）。

**解释器里的数组就是 JVM 的数组**（`java.lang.reflect.Array.newInstance`）：
默认值、边界行为、引用语义天然和字节码那一端是同一个东西，省掉一整类分歧。

`rsfSrc/lang/arrays.rsf` 是完整示例（创建 / 下标读写 / length / 默认值 / 引用语义 /
用户类与泛型类数组 / 数组当字段 / 嵌套数组 / 越界三种写法）：

```bat
gradlew run --args="run rsfSrc/lang/arrays.rsf"
gradlew run --args="build rsfSrc/lang/arrays.rsf -o out"
java -cp out arrays
```

### 注释

```
// 行注释
/* 块注释 */
```

---

## 控制流分析

goto 和循环会让"变量到底赋值了没有"变成一道真正的数据流题。编译器在语义检查的第二趟
会把函数体降级成扁平指令、建 CFG，做三项分析：

| 分析 | 作用 |
| --- | --- |
| 可达性 | 找出永远执行不到的代码，给警告 |
| **确定性赋值** | 拦住"读到可能还没赋值的变量" |
| 所有路径 return | 非 void 函数必须每条路径都 `rtn`（含 `else` 分支） |

第一项不是可选的。不做的话，`goto` 跳过变量声明再读那个变量，会一路混到**类加载时**
才以 `VerifyError` 炸出来——这是所有报错形式里最难查的一种。做了就是一条带源码位置的
编译错误：

```
a_skip_init.rsf:5:13: error[checker]: 读到变量 'x' 时它可能还没被赋值：存在一条绕过了它前面那句声明的路径（向后跳的 goto 或循环回边）。这就是 C++ 里「不能跳过初始化跳进作用域」那条规矩。
  5 |     println(x)
    |             ^
```

跳进块里绕过初始化同样会被拦住——这正是 C++ 那条规矩。

函数参数不参与这项分析（入口就有值），`for` 的循环变量则由 `for` 自己保证。

---

## 命令行

```
rsf run   <file.rsf>                        解释执行，不生成 .class
rsf build <file.rsf> [-o <dir>] [-n 类名]    编译成 .class（默认输出到 out/）
rsf build <file.rsf> --dump                 顺便打印生成的字节码（ASM 文本）
rsf check <file.rsf>                        只做检查，列出函数表
rsf lex   <file.rsf>                        打印 token 流
rsf repl                                    交互式解释器
rsf help | version
```

### REPL 的会话模型

REPL 里敲的每一条顶层语句都会被记住，之后每条新输入都会把整个会话**静默重放**一遍
（不产生输出），再执行新的一句。所以 `while` / `if` 这类复合语句对变量的修改也能跨行保留，
而且不会把之前打印过的东西又打一遍。

代价是：重复声明同一个变量是含糊的（要改哪一次？后面怎么重放？），所以会直接拒绝，
提示你改用赋值或 `:reset`。

---

## 项目结构

```
src/main/kotlin/org/example/
├── Main.kt                        CLI 入口（run / build / check / lex / repl）
└── complie/                       （沿用最初建项目时的包名拼写）
    ├── Rsf.kt                     流水线门面：源码 → token → AST → 语义模型 → 后端
    ├── diag/                      SourceFile、统一的 RsfError（带源码插入符）
    ├── lexing/                    SourceLine、Token、Lexer
    ├── ast/                       AST 节点
    ├── parsing/                   Parser（递归下降 + 优先级爬升）
    ├── sema/                      RsfType、Binding、SemanticModel、Checker（含控制流分析）
    ├── ir/                        降级成扁平指令 + CFG + 确定性赋值
    ├── interp/                    解释器（PC + 槽位数组，与 JVM 执行模型同构）
    ├── codegen/                   ASM 字节码后端 + 反汇编器
    └── util/                      input() 辅助函数

rsfSrc/lang/main.rsf                示例程序
rsfSrc/lang/class.rsf               类的示例（字段/次构造/实例与静态方法/可空类字段/私有方法）
rsfSrc/lang/generics.rsf            泛型示例（自定义泛型类、嵌套、多类型参数、JVM 泛型集合）
rsfSrc/lang/errors.rsf              错误返回 !T 与 try/catch 示例（三种处理方式、跨层传播、!void）
rsfSrc/lang/stdlib.rsf              byte/char 与标准库切片示例（字符分类、转义、parseInt 的三种处理）
rsfSrc/lang/arrays.rsf              数组 T[] 示例（创建/下标/length/引用语义/类数组/嵌套/越界；主构造 + 次构造）
rsfSrc/lang/files.rsf               文件读写示例（readText/writeText/readBytes/writeBytes + try 接住 + ?: 兜底）
rsfSrc/lang/enums.rsf               枚举示例（enum 声明/取值/当字段与数组/可空 + switch 分派 + && || 短路）
rsfSrc/lang/example.rsf             语言设计草稿
rsfSrc/bootstrap/Lexer.rsf          **用 rsf 写的词法器**（阶段 17a，纯库：lex / dumpTokens，与 Kotlin 版逐条对拍）
rsfSrc/bootstrap/LexerCli.rsf       rsf 版词法器的命令行入口（dump 与 `rsf lex` 逐行相同）
rsfSrc/bootstrap/Parser.rsf         **用 rsf 写的语法器**（阶段 17b，纯库：parseSource / dumpAst，胖节点 AST）
rsfSrc/bootstrap/ParserCli.rsf      rsf 版语法器的命令行入口（AST dump 与测试侧 AstDump 逐行相同）
rsfSrc/bootstrap/Interp.rsf         **用 rsf 写的解释器**（阶段 17c，纯库：runSource，胖节点树遍历，stdout 与 `rsf run` 逐字节相同）
rsfSrc/bootstrap/InterpCli.rsf      rsf 版解释器的命令行入口（读 `-Drsf.interp.file`）
rsfSrc/bootstrap/Backend.rsf        **用 rsf 写的字节码后端**（阶段 17d，纯库：Walker 降级 + ASM 发射，产物与 Kotlin 版逐字节相同）
rsfSrc/bootstrap/BackendCli.rsf     rsf 版字节码后端的命令行入口（读 `-Drsf.backend.file` / `-Drsf.backend.out`）
rsfSrc/bootstrap/Sources.rsf        **用 rsf 写的多文件装载器**（阶段 17e，纯库：loadProgram，语义对齐 Kotlin 侧 SourceLoader.kt）
tools/devbuild.ps1, rundev.ps1      不依赖 Gradle 的开发编译/运行脚本
idea-rainstarfox/                   IntelliJ IDEA 插件（高亮 / 诊断 / 补全 / 运行）
src/test/kotlin/org/example/        测试（359 个：两端一致性、rsf 版词法器/语法器/解释器/字节码后端的差分、注解语法、编辑器关键字表守卫）
```

### 关于 `Binding`

参数和局部变量在语义检查之后被统一成同一种东西（`sema/Binding.kt`）：都需要
「类型 + 能不能赋值 + 在局部变量表里占哪一格」。这样解释器和字节码后端就不必到处
分情况判断「这是参数还是局部变量」，槽位分配（`Lowering`）也只需要一套逻辑。

同一套名字与槽位还会写进 `.class` 的 `LocalVariableTable`（第 26 刀），所以
`javap -l` 和 IDEA 调试器里看得到参数名、局部变量名，而不是 `slot#2`。

### IntelliJ IDEA 插件

`idea-rainstarfox/` 是给 IntelliJ 平台写的插件（文件类型、语法高亮、实时诊断、
代码补全、一键运行/编译），诊断与补全走 `rsf check --json` 和 `rsf complete`
两个 CLI 命令——报错和补全全部来自编译器，只有一份语义实现。用本机已装的
IDEA 当 SDK 编译，不下载 IDE 分发包：

```bat
cd idea-rainstarfox
gradlew.bat buildPlugin test      :: 产物 build/distributions/idea-rainstarfox-0.1.0.zip
gradlew.bat verifyPlugin          :: 官方校验器静态检查
```

装到 IDE 里：Setting → Plugins → ⚙ → Install Plugin from Disk…。IDEA 插件用的是
**自己写的词法器**（高亮、括号配对着色），但报错和补全依然全部来自编译器——
只有一份语义实现这件事没有变。

### 为什么有两个开发脚本

`tools/` 下的两个脚本直接从 Gradle 模块缓存里取 `kotlin-compiler-embeddable` 和 `asm`
来编译，用于在**文件沙箱**里工作时不碰 `GRADLE_USER_HOME`。正常开发用 `gradlew` 就行。
注意脚本是纯 ASCII 的：Windows PowerShell 5.1 按系统 ANSI 码页读 `.ps1`，混进中文会解析失败。

---

## 当前限制（v0.9）

明确**尚未实现**的东西：

- 数组：**没有**数组字面量 `[1, 2, 3]`、切片、`for (x in arr)` 迭代语法、锯齿数组
  `new int[3][]`（每一维都要写长度）、元素可空性（`?T[]` 直接报错）；
  数组**不能直接打印或拼进字符串**（JVM 打出来是身份哈希，两端没法保证一致）。
  数组**可以**赋给 `Object`（引用语义），但 `Object` 不能直接当数组用（没有强制转换语法）。
  数组类型本身（创建 / 下标读写 / `a.length` / 嵌套 / 越界）已经完整可用——见上面数组那一节
- **JVM 互操作里的数组签名**：已支持（`ClassPath.typeFromDescriptor` 会数 `[` 前缀，
  `[B` → `byte[]`、`[[Ljava/lang/String;` → `string[][]`）。`Files.write(Path, byte[])`、
  `String.getBytes() -> byte[]` 这类方法现在都能正常解析与调用；阶段 16b 的四个文件函数
  （`readBytes` / `writeBytes` 等）就是骑在这条路上的
- 泛型函数（`fn <T> T pick(...)`）、上界（`T : Number`）、通配符、泛型继承
- `Map&<K, V>` 这种**多个类型参数的外部类**：擦除后 `Object` 形参分不清对应哪个实参，
  所以只按 `Object` 处理（一个类型参数的外部类，如 `ArrayList&<int>`，是通的）
- 可空与错误返回混用（`fn f() -> !?int` 上的 `f()!!` / `f() ?: x`）：明确报「暂不支持」
- 构造器不能是 `!T`（构造对象没有处理错误的写法；阶段 18 起构造器干脆没有返回类型）。
  `new` 可省之后这一点没变：`C(...)` 与 `new C(...)` 一样都没有错误处理
- ~~**`catch (e string)` 仍然是 Java 风格**~~ —— **已经改掉（阶段 19b）**：`catch` 变量的
  类型标注现在也是两种写法都收，**推荐 Kotlin 风格 `catch (e : string)`**（和
  `var x : int`、和函数参数一致），老写法 `catch (e string)` 照样能编译。
  两种写法解析出来的 `TryStmt` 完全一样，所以两条后端的输出一字不差。漏写类型时
  （`catch (e)`）报错会**同时列出两种写法**
- **类名与函数名不能重名**（报 `'X' 已经是一个类名了，函数名和类名不能重名`）——
  这正是「自动 `new`」判定无歧义的前提，所以这条检查会一直留着
- 名称既不是函数也不是可构造的类型时，报「未定义的函数 'x'」（不是「没有这个类」）
- ~~方法重载仍然不支持（`fn f(int)` 与 `fn f(string)` 同名报错）~~ —— **已经支持（阶段 19d）**：
  顶层函数与类方法（实例 + `static`）都能重载，两处共用一套解析机器（先按个数筛 → 类型打分，
  **最低分并列就报错并列出候选**）。**注意与构造器的规则差异**：构造器同分取「声明在前」，
  函数/方法同分报错。擦除冲突（`f(Box&<int>)` 与 `f(Box&<string>)`）编译期拒绝。
  详见上面「重载（阶段 19d）」一节
- **`rsfSrc/bootstrap/*.rsf` 里绕开重载的写法不改**（`matchAny2/3/4` 等）：那些产物被
  自举差分测试逐字节钉住，重载只对**新写的** rsf 代码开放
- **泛型函数**（`fn pick&<T>(...)`）不支持：类型参数只能写在 `class` 上（报错会提示
  改用「每种类型写一个重载」或把数据放进泛型类）
- 构造器没有初始化块（`init { }`）：语句就写在次构造的体里，或者做成普通方法
- `try` / `?:` 只接 rsf 自己的 `fail`，接不住 Java 互操作抛出来的异常（跨语言异常本版不做）
- `try` 没有 `finally`、没有多重 catch，`catch` 只接一个 `string` 变量
- 智能转换（`if (x != null)` 之后不会自动把 `x` 当成非空）
- `?.` 安全调用
- 默认参数、可变参数（varargs）、按名传参
- `(byte) x` 这种强制转换语法：窄化与 `char`/`int` 互转都只能走内置函数
  （`toByte` / `toChar` / `charToInt`）
- 可空目标不接加宽（`var x : ?long = 1` 报错，得写 `1L`）——这是既有 `?T` 机制本来的规矩
- 全局变量、常量（跨文件共享只能靠函数）
- 类型关键字不能当函数名（`fn double()` 会被当成 `<MainType>`）
- `imutvar` 只禁止**重新赋值**，不禁止通过它去改指向的对象
- `for (i in a..b)` 的起点与步长类型要能相容（例如 int 计数器配 `step 2L` 会报类型错）
- 字符串字面量现在可以跨行了，漏掉收尾引号时会一路吃到文件末尾（报错仍指向开引号）
- 泛型类里的 static 方法拿不到类型实参（`Box.origin()` 里的 `T` 仍是占位类型）
- **枚举（阶段 19c）**：生成的是**普通类**而不是 JVM 的 `enum`，所以外部 Java 代码
  不能用 `switch` / `EnumSet` / `values()` 去消费它；枚举里不能带字段与方法（只有值）；
  没有 `values()` / `ordinal()` 这类反射接口，也没有「枚举 → 名字字符串」的内置函数
  （要文本就用字符串拼接：`"" + k`）
- **`switch`（阶段 19c）**：只能对枚举做（数值 / 字符串的分派写 `if` / `else if` 链）；
  是**语句**不是表达式（要取值用三目）；分支头只接受枚举常量，不支持区间 / 比较 / 字面量；
  旧写法 `case 枚举名.值名 :` 仍可编译，但会发弃用警告（阶段 21）；
  没有 fallthrough，也不接受 `break` 跳出 `switch`（分支里写的 `break` 作用于外层循环）
- **`&&` / `||`**：两边必须是 `bool`（`?bool` 要先解包 / 兜底）；没有按位运算（单独的 `|` 报错）
- **注解（阶段 21）**：元素默认值只支持常量字面量（枚举常量 / 注解 / 数组都不行）；`@` 只能标
  类 / 接口 / 注解声明 / 方法 / 字段（参数、局部变量、枚举声明都不行）；不做元注解 / `@Repeatable` /
  内建 `@Deprecated`；伴生实体只继承接口，注解也不能当父类型；`rsfSrc/**` 的注解**只到语法层**（自举链能解析、与 Kotlin 版 AST 逐字节一致，但忽略其语义）
- **类型转换与类型判断**：`(byte) x` 那类窄化仍只能走内置函数；计划里的 `as` / `?as` / `is` / `!is`
  （`DESIGN-24.md`）还没实现
- **类继承（阶段 25）**：不做多继承 / `sealed` / `data` / `super.字段`；继承 JDK 类时有四条已知限制——
  ① **不能覆盖 JDK 父类的方法**（解释器把 JDK 父类对象建成真实 JVM 实例，rsf 的 `override` 落不到它身上，
  所以这种源码在**编译期**就被拒，而不是留到运行期两端分叉）；② **JDK 父类的字段不能写**（只有读的那条路接了实例）；
  ③ **JDK 父类只挂一跳**（`RsfObject.jdkParent` 只有一个槽位）；④ **用户类的 `getClass()` 在编译期被拒**
  （两端拿不到同一个 `Class`；接收者本来就是 JDK 对象时照旧可用）。另外 `rsfSrc/**` 里的类继承**还没同步到自举链**（K5 待做）。
  这一支上 `java/lang/Object` 的方法只放行 rsf 自己定义语义的 `toString` / `equals` / `hashCode`
  （分别是 ObjectFormat 渲染、对象同一性、identity hash），`wait` / `notify` / `clone` 之类一律按「查不到」处理

## 路线图

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 签名升级（ControlType / MainType / `::`）+ 块作用域 + CFG 地基 | ✅ |
| 2 | `if`（花括号 / 冒号 / 三目）+ `while` + `break` / `continue` + `goto` + 标签 | ✅ |
| 2.5 | `else` 与 `else if`；`var` / `imutvar` 取代 `let` | ✅ |
| 3 | `for`（Java 式与 Kotlin 式） | ✅ |
| 3.5 | 编辑器语法高亮（关键字 / 运算符上色） | ✅ |
| 4 | `?T` 可空（null 一等值、`?:`、`!!`、基本类型装箱） | ✅ |
| 5 | 函数参数（含递归、加宽、参数可变） | ✅ |
| 6a | `import`（三种语法）+ `module` 多文件 | ✅ |
| 6b | JVM 类导入 + `.` 成员访问 + Java/Kotlin 互调用 | ✅ |
| 7 | 构造对象（`new`，含 `new 导入的 JVM 类(...)`） | ✅ |
| 8 | `!T` 错误返回 | ✅（阶段 14） |
| 9 | 用户自定义类/结构体 | ✅（阶段 12） |
| 10 | 编辑器支持：诊断 + 补全（走 `rsf check --json` / `rsf complete`） | ✅ |
| 11 | Language Server（增量分析 / 跳转定义 / 悬停类型） | 待做 |
| 12 | 用户自定义类 + `new` 构造 | ✅ |
| 13 | 数组类型 `T[]` | ✅ |
| 14 | `!T` 错误返回 + `try`/`catch` | ✅ |
| 15 | 泛型（自定义泛型类 + JVM 泛型集合，`&<...>`） | ✅ |
| 16 | `byte`/`char` + 标准库切片（字符分类、`charToInt`/`toChar`、`parseInt`） | ✅ |
| 16b | `byte[]` + 文件落盘（`readText` / `writeText` / `readBytes` / `writeBytes`，互操作认数组签名） | ✅ |
| 18 | 构造器：主构造（类头）+ 次构造 `constructor(...)` + `this(...)` 委托 | ✅ |
| 19 | 语法现代化：后缀类型 `名字 : 类型`、参数双风格、返回子句只留 `->`/`:`、缺省即 `public`、构造可省 `new` | ✅ |
| 19b | `catch (e : string)`：错误变量的类型标注也改成 Kotlin 风格（老写法 `catch (e string)` 继续接受）；编辑器关键字表补全 | ✅ |
| 17 | **自举**：用 rsf 写 rsf 编译器 → 编译自己 → 与 Kotlin 版输出逐字节一致 | 🚧 17a 词法器 ✅ / 17b 语法器 ✅ / 17c 解释器 ✅ / 17d 字节码后端 ✅ / 17e 闭环 ✅ |
| 17a | rsf 版词法器（`rsfSrc/bootstrap/Lexer.rsf`）：token 流与 Kotlin 版逐条相同 | ✅ |
| 17b | rsf 版语法器（`rsfSrc/bootstrap/Parser.rsf`）：AST 打印成文本，两边逐行相同 | ✅ |
| 17c | rsf 版解释器（`rsfSrc/bootstrap/Interp.rsf`）：跑 `rsfSrc/lang/` 下全部示例，stdout 逐字节相同 | ✅ |
| 17d 前置 | 开 17d 之前的三件：`byte`/`char` 一元负号的后端缺陷、`HashMap&<K, V>` 多类型实参擦除、外部类 `new` 的确认与补全 | ✅ 2026-10-11 |
| 17d | rsf 版字节码后端（`rsfSrc/bootstrap/Backend.rsf`）：八示例共 20 个 `.class` 与 Kotlin 版**逐字节相同** | ✅ 2026-10-13 |
| 17e | 三阶段自举：Kotlin 编 rsf 版得 A，A 编自己得 B，B 编自己得 C，要求 **B ≡ C** | ✅ 2026-10-13（B ≡ C：126 个 `.class` 逐字节相同；与 Kotlin 产物逐字节相同还差 3 个类） |
| 19c | 为 17b 补的三样：`&&` / `||` 短路 + `enum` + `switch` 枚举分派 | ✅ |
| 19d | **函数/方法重载**：顶层函数与类方法共用一套解析（个数 → 类型打分 → 同分报错）、擦除冲突编译期拒绝、`main` 唯一入口 | ✅ 2026-10-11 |
| 20 | `interface`（抽象/默认方法、接口继承接口、`class X : A, B` 多接口实现、`override`、JVM 接口互操作与桥方法） | ✅ 2026-10-15 |
| 21 | 注解 `annotation`：元素 + 默认值 + `companion entity` 行为、`@` 使用点、`Marker::Impl` 嵌套类型、字节码与解释器两端逐字节一致 | ✅ 2026-10-15 |
| 25 | **类继承**（Kotlin 式单继承：`override` / `abstract` / `final`、`super.f()` 与 `super(接口).f()`、`static` 不继承、允许继承 JDK 类） | ✅ 2026-10-16（K1 前端+语义 / K3 解释器 / K2 字节码 / K4 收口；自举同步已含于阶段 27） |
| 27 | **默认开放**：删掉 `open` 关键字，类默认可继承、方法默认可覆盖，`final` 成为唯一收窄手段（推翻阶段 25 的「默认 final」） | ✅ 2026-10-18（27a `5df701f` Kotlin 前端 / 27b `20a5e13` 自举同步，关键字 57→56；判据 `build/verify27/` 五条 + `build/verify24` 回归全绿） |
| 28 | **标签名特殊字符 + 元数据**：标签名可用反引号包任意字符（`` `a-b` ``），每个有标签的方法发一条 runtime-visible `@RsfLabels(value = string[])`，按声明顺序把名字保留进 class 文件 | ✅ 2026-10-18（28a `5edbd27` Kotlin 侧 / 28b `cc1bf17` 自举链；判据 `build/verify28/`：4 正例 + 5 负例 + 两端 class 逐字节对拍；564 tests） |
| 29 | **关键字改名**：`fun` → `fn`（硬删 + 统一改名声，词法表仍 56 项、`TokenKind` 仍 `KW_FUN`） | ✅ 2026-10-18（`017a2cb`；判据 `build/verify29/` 旧词负例 + 全部探针台回归；564 tests） |
| 30 | **函数类型与 `lam`**：`(int, string) -> bool` / `?(int) -> int`，字面量 `lam(x : int) -> int { … }`，值捕获只许 `imutvar`/形参/`this`；JVM 走按 arity 生成的 `__RsfFn<n>` SAM 接口 + `LambdaMetafactory` | ✅ 2026-10-18（30a `d2d124e` 前端 / 30b `822e600` Kotlin 两端 / 30c `f54dcf8` 自举链；判据 `build/verify30/` 6 正例 + 5 负例 + 两端 class 逐字节零差异；581 tests；关键字 56→57） |
| 31 | **泛型函数**（v1）：`fn id&<T>(x : T) -> T`，调用点按实参推导、JVM 擦成 `Object`；泛型方法不能 `override`/`abstract`、构造器不能泛型、`T[]` 只接引用元素数组 | ✅ 2026-10-18（31a `3317a0b` 前端+语义 / 31b `5700962` Kotlin 两端 / 31c `cf252e2` 自举链；判据 `build/verify31/`：6 正例 + 6 负例 + 两端 6 份 class 逐字节零差异；604 tests） |
| 23 | 显式指针（`&` 取地址 / `*` 解引用，C 风格） | ⏸ 2026-10-18 用户定：JVM 上不实现，整体移交 native（`DESIGN-23.md`） |
| 24 | 类型转换与类型判断（`as` / `?as` / `is` / `!is`） | ✅ 2026-10-18（24a `076aeee` / 24b `5b5d7aa` / 24c `707cbe3` / 24d `51bd707`；判据 `build/verify24/`：9 正例 + 8 负例 + 自举解释器对拍 14 份） |

### 19c：为 17b 补的三样（enum / switch / `&&` `||`）

写自举的词法器（17a）时暴露的三个缺口，都在这一阶段补上了：

| 缺口 | 不补的话 | 补完之后 |
| --- | --- | --- |
| 没有 `&&` / `||` | `if (c == '/' && peek(1) == '/')` 只能写成三层嵌套 `if` | 一行写完，两侧必须是 `bool`，**真短路**（右边可能一次都不求值） |
| 没有 `enum` | `TokenKind` 只能用一串 `static fn xxx() -> string` 拼字符串常量，**拼错名字编译器不吭声** | `TokenKind.IDENT` 写错值名当场报错并列出全部值；`==` 只管同类型 |
| 没有 `switch` | 按 kind 分派只能写成十几条 `if (kind == ...)` 长链 | `switch (k) { TokenKind.IDENT : ... }`；**漏分支会在编译期报错**（不用 else 时必须穷尽） |

**对 17b 的直接结论**：`rsfSrc/bootstrap/Lexer.rsf` 里 `TokenKind` 的**全部**用法形态
（字段、参数、查表返回、与字符串字面量比较、dump 需要名字文本、空串当「没找到」的哨兵）
都能用 `enum` + `switch` 一比一表达，所以 17b 的语法器可以直接用 enum 写 token 种类。
逐条对应关系见 [`BOOTSTRAP.md`](BOOTSTRAP.md)。

### 17a：用 rsf 写的词法器（自举第一刀）

`rsfSrc/bootstrap/Lexer.rsf` 是**用 rsf 自己写的词法器**，基准是 Kotlin 版
`complie/lexing/Lexer.kt`：同一份 `.rsf` 输入，两边切出来的 token 流（kind 名字、
token 文本、行、列）逐条相同。

```bat
:: rsf 版 dump（编译产物；rsf 侧同样能解释执行）
gradlew run --args="build rsfSrc/bootstrap/LexerCli.rsf -o out"
java -cp out LexerCli
:: 指定输入：路径走系统属性（rsf 的 main 收不到命令行参数）
java -Drsf.lex.file=rsfSrc/lang/main.rsf -cp out LexerCli

:: 与 Kotlin 版对拍：两条命令的输出应当一字不差
gradlew run --args="lex rsfSrc/lang/main.rsf"
```

`rsfSrc/bootstrap/Lexer.rsf` 是**纯库**（`lex(source)` / `lexSafe(source)` /
`dumpTokens(tokens)`），入口在旁边的 `LexerCli.rsf`——rsf 一个程序里只能有一个
`main`，库带 main 就没法被 `import` 了。

对拍由测试守着：`rsf 版词法器与 Kotlin 版 token 流逐条一致`（`rsfSrc/lang/**` 下
全部 `.rsf`）、`rsf 版词法器能处理字面量与注释的刁钻写法`（`'a'` / `'\u0041'` /
跨行字符串 / 两种注释 / `1..5` / `1.5f` / `9000000000L` / `&<int>` / `?:` / `->`）、
`rsf 版词法器覆盖关键字别名与各种边角形态`（`str` / `boolean` / `_x` / `pkg a\b` / `1.:2` /
`1.5e3` / `0xFF`）、`rsf 版词法器遇到非法输入的行为被钉住`（两端本来就不同的边界，
照实写清楚）、`rsf 版词法器的入口读不到系统属性时会兜底`（JVM 方法可能返回 null）。

⚠️ **阶段 17b 已补齐**：`rsfSrc/bootstrap/Lexer.rsf` 的关键字表与记号表在 17b 里补到了
阶段 19c——加了 `enum` / `switch` / `case` 三个关键字与 `&&` / `||` 两个记号（单独的 `|`
两端报同一条消息）。所以上面那条差分对拍**不再分流任何样例**：`rsfSrc/lang/**` 下全部
`.rsf`（含 `enums.rsf`）都在覆盖范围内，另有一条
`rsf 版词法器已经认识阶段 19c 的新记号并与 Kotlin 版一致` 反过来钉住这件事，
还有一条 `rsf 版词法器与 Kotlin 版的关键字表与记号表逐条对齐` 把 45 个关键字与
31 个标点**全表**过一遍（不抽查——漏一个关键字的表现是「悄悄变成标识符」，不报错）。

### 17e：三阶段自举（自举第五刀）

阶段 17e 把「用 rsf 写的编译器」真的转起来了，并且**闭上了环**：

```bat
:: ① Kotlin 版编出 rsf 版编译器（A）
java -jar build\libs\rainStarfox-0.9.0.jar build rsfSrc\bootstrap\BackendCli.rsf -o build\A
:: ② A 编自己 -> B（要挂 rainStarfox 的胖包才找得到 ASM）
java -Drsf.backend.file=rsfSrc\bootstrap\BackendCli.rsf -Drsf.backend.out=build\B ^
     -cp build\A;build\libs\rainStarfox-0.9.0.jar BackendCli
:: ③ B 再编自己 -> C，要求 B ≡ C
java -Drsf.backend.file=rsfSrc\bootstrap\BackendCli.rsf -Drsf.backend.out=build\C ^
     -cp build\B;build\libs\rainStarfox-0.9.0.jar BackendCli
```

**新增 `Sources.rsf`**：rsf 写的多文件装载器，语义对齐 Kotlin 侧 `loading/SourceLoader.kt`
（`import "x.rsf"` 指向的源码被包含式地并进同一棵树）。`BackendCli` / `InterpCli` /
`LexerCli` 三个入口都改成先 `loadProgram(entry)` 再往下走；`ParserCli` 与 `LexerCli`
**刻意不接**——前者要保「纯语法器的 AST」（对拍里必须留着 `SOURCE_IMPORT` 条目），
后者要保「纯词法阶段」的出口口径。

**实测结果**：A 有 30 个类；A 能编 `rsfSrc/bootstrap/` 下 **9 个源全部成功**；
**B 与 C 的输出 126 个 `.class` 逐字节相同**（环闭上）；八示例经 rsf 版后端编出来的产物
与 Kotlin 版**字节级也全同**；多文件 `import "lib.rsf"` 的端到端产物与运行输出两边一致；
自举解释器（A 编出的 `InterpCli`）对八示例的 stdout / stderr 与 Kotlin 版逐字节相同；
`gradlew test` 248 全绿、八示例 SHA256 与历轮锚点逐个不变。

**与 Kotlin 产物逐字节相同这一条还差 3 个类**（`BType` / `Node` / `Lexer`：次构造器少一条
行号表）；**还不支持**（一律显式报错、一个 `.class` 都不写）：`pkg` 声明、`module "目录"` 导入、
`?:` 左边的成员/隐式方法调用。细节见 [`BOOTSTRAP.md`](BOOTSTRAP.md) 的「17e 第一步实际做法与结果」。

### 17d：用 rsf 写的字节码后端（自举第四刀）

`rsfSrc/bootstrap/Backend.rsf` 是**用 rsf 自己写的字节码后端**（4929 行，纯库），
形状与 Kotlin 版一一对应：先由 `Walker` 把胖节点树降成一维 `Ins[]` 指令表
（对应 `ir/Lowering.kt`），再由 `Gen` / `emitIns` / `emitExpr` 拿 ASM 的
`MethodVisitor` 逐条发指令（对应 `codegen/BytecodeCompiler.kt`）。
类表 / 函数表 / 枚举表 / 常量池去重都用 `HashMap&<K, V>` 写。
入口在旁边的 `BackendCli.rsf`（读 `-Drsf.backend.file` / `-Drsf.backend.out`）。

```bat
:: 用 rsf 版后端编一个示例
java -Drsf.backend.file=rsfSrc/lang/arrays.rsf -Drsf.backend.out=build\bootstrap \
     -cp build\bootstrap;build\libs\rainStarfox-0.9.0.jar BackendCli
:: 跑它（要挂 ASM 那个 jar 才能找齐常量池引用）
java -cp build\bootstrap;build\libs\rainStarfox-0.9.0.jar arrays
```

**对拍口径是三层**：① 与 Kotlin 版 `rsf build` 的 `.class` **逐字节比**；
② `rsf build --dump` 的反汇编逐行比；③ `java -cp` 跑出来的 stdout / stderr 比。

**实际达到的一致程度：第 ① 层就相同**——八示例共 **20 个 `.class` 与 Kotlin 版逐字节相同**
（`main` 1 / `class` 3 / `generics` 3 / `errors` 1 / `stdlib` 1 / `arrays` 4 / `files` 2 / `enums` 5），
运行期输出也逐个一致，于是第 ② 层自动成立。怎么找出来的、以及
**还不支持的构造清单**（一律显式报错，绝不产出错字节码），见
[`BOOTSTRAP.md`](BOOTSTRAP.md) 的「17d 实际做法」。

**17e 已经做到**：Kotlin 版编 rsf 版编译器得 A，A 编自己得 B，B 编自己得 C，
**B 与 C 的输出逐字节相同**（126 个 `.class`）；见上面「17e：三阶段自举」。

### 17c：用 rsf 写的解释器（自举第三刀）

`rsfSrc/bootstrap/Interp.rsf` 是**用 rsf 自己写的树遍历解释器**（2277 行，纯库），
内部流程 = **rsf 版 `lex` → rsf 版 `parse` → 在这棵胖节点树上遍历执行**；
入口在旁边的 `InterpCli.rsf`（读 `-Drsf.interp.file`，rsf 的 `main` 收不到命令行参数）。

```bat
:: 解释执行
gradlew run --args="run rsfSrc/bootstrap/InterpCli.rsf"                 :: 默认跑 rsfSrc/lang/main.rsf
java -Drsf.interp.file=rsfSrc/lang/arrays.rsf -jar build\libs\rainStarfox-0.9.0.jar run rsfSrc/bootstrap/InterpCli.rsf
:: 编译执行
gradlew run --args="build rsfSrc/bootstrap/InterpCli.rsf -o build\bootstrap"
java -Drsf.interp.file=rsfSrc/lang/arrays.rsf -cp build\bootstrap InterpCli
:: 与 Kotlin 版对拍：两条命令的 stdout 应当逐字节相同
gradlew run --args="run rsfSrc/lang/arrays.rsf"
```

**八个示例全部逐字节一致**（`main` / `class` / `generics` / `errors` / `stdlib` /
`arrays` / `files` / `enums`），解释执行与编译执行两条路也都是这个结果；
八份刁钻语料（递归与数值提升 / 数组越界与负长度 / `&&` `||` 短路副作用 /
枚举 `switch` 分派 / 构造器重载与 `this()` 委托 / 字符串与错误处理 /
控制流全家桶 / 泛型集合与对象数组）同样对拍通过。

值怎么表示、环境怎么存、`return`/`break`/`continue`/`goto` 在只有 `fail` 的语言里
怎么跳出去，见 [`BOOTSTRAP.md`](BOOTSTRAP.md) 的「17c 实际做法」。

**本解释器还不支持的东西一律显式报错**（`17c 解释器暂不支持：…`），绝不静默算错。
已知缺口见 [`BOOTSTRAP.md`](BOOTSTRAP.md) 的「17c 暂不支持清单」——最主要的一条是
**JVM 互操作只做了示例用到的那三个口子**（`java.util.ArrayList`、`java.io.File`、
`System.getProperty`），其余外部类调用会明确报错。

对拍由测试守着：`rsf 版解释器与 Kotlin 版输出逐字节一致`（八个示例，实测比了 222 行
stdout，另按「单例 ≥ 5 行」+「合计 ≥ 200 行」两道闸门防止覆盖悄悄退化）、
`rsf 版解释器在刁钻程序上与 Kotlin 版一致`（8 份合成语料，实测比了 97 行）、
`rsf 版解释器对不支持的语法报清楚错误`（4 条已知缺口 + 一条「受支持的语法不许误报」的反面）、
`rsf 版解释器的入口编译执行与解释执行一致`。

### 17b：用 rsf 写的语法器（自举第二刀）

`rsfSrc/bootstrap/Parser.rsf` 是**用 rsf 自己写的语法器**，基准是 Kotlin 版
`complie/parsing/Parser.kt`：同一份 `.rsf` 输入，两边建出来的语法树**结构完全相同**。

```bat
:: rsf 版 AST dump（编译产物；rsf 侧同样能解释执行）
gradlew run --args="build rsfSrc/bootstrap/ParserCli.rsf -o out"
java -cp out ParserCli
:: 指定输入：路径走系统属性（rsf 的 main 收不到命令行参数）
java -Drsf.ast.file=rsfSrc/lang/enums.rsf -cp out ParserCli
:: 解释执行那条路等价：
gradlew run --args="run rsfSrc/bootstrap/ParserCli.rsf"      :: 配合 -Drsf.ast.file
```

**AST 怎么表示（胖节点）**：rsf 现在没有继承/接口（阶段 20 才有），写不出多态的节点层次，
所以用一个「胖节点」：

```
public class Node(kind : NodeKind, text : string)      // kind 是 enum，text 是属性串
    text      形如 "name=f mutable=true"（这一层需要的字面量；顺序固定）
    children  子节点数组（add 追加，满了翻倍）
```

遍历一律 `switch (node.kind)`——这正是阶段 19c 加 `switch` 的原因；`kindName` 那个
没有 `else` 的 switch **必须穷尽 `NodeKind` 的每一个值**，漏一个当场编译报错。

**对拍方式**：两边各自把树打成**同一份规范化文本**再逐行比。

| 侧 | 产出 | 在哪 |
| --- | --- | --- |
| Kotlin 版 | `AstDump.dump(program)` | `src/test/kotlin/org/example/AstDump.kt`（**测试源集**，不挂在任何 CLI 上，`rsf check`/`rsf lex` 的输出格式一个字没动） |
| rsf 版 | `dumpAst(node)` | `rsfSrc/bootstrap/Parser.rsf` |

格式：缩进 = 2 空格 × 深度、**一行一个节点**、行尾统一 `\n`；行 = `<KIND>` + 若干
`名字=值`（值里的空格/换行/反斜杠都转义掉，所以「按空格切字段」无歧义）；列表用显式的
列表节点包起来（`IMPORTS` / `FUNCS` / `PARAMS` / `ARGS` / `BLOCK` …）并带 `count=N`。
**不带对象地址、不带 hashCode、不做任何集合重排**，所以是确定性的。
`SourceLine`（行列）刻意不进 dump：那是实现细节，这一阶段比的是语法结构。

**解糖做不做**：**做，而且照着 Kotlin 版做**。`for (i in a..b step n)` 在 Kotlin 版里是
**解析期解糖**（合成 `__rsf_forEnd<k>` / `__rsf_forStep<k>` 两个不可变变量 + 一个
`步长 >= 0 ? i <= 终点 : i >= 终点` 的条件），rsf 版连合成名字的编号顺序都一致。
理由：Kotlin 版手上的树**已经是解糖后的样子**，原始写法在树上根本不存在；
rsf 版若保留一个「FOR_IN」原始节点，两边永远比不平（除非在 dump 里反解糖回源码形状，
那是自欺欺人）。

**错误处理**：Kotlin 版遇错抛 `RsfError`（消息很具体）；rsf 版不用异常（`fail` 会停掉
整个程序，`!T` 又要顺着调用链标到每一个 parse 函数上），改成「失败标志 + 占位节点」。
合法输入上这条路根本不走，所以对拍不受影响。**非法输入只要求「两边都判定为失败」**，
不要求消息逐字相同——这条边界有测试钉着，报告里也写明了理由。

对拍由测试守着：`rsf 版语法器与 Kotlin 版 AST 逐条一致`（`rsfSrc/lang/**` 下全部 `.rsf`，
实测比了 1500+ 行 AST）、`rsf 版语法器在刁钻语料上与 Kotlin 版 AST 逐条一致`
（9 份合成语料：两种参数风格混写 / 两种返回子句 / `-> !T` / `-> null` / 主次构造与
`this()` 委托 / 泛型实参与数组类型 / 两种 `if` / 两种 `for` / `for-in` 的 `step` /
`switch` + `else` / 三目 / `?:` / `!!` / 成员与下标赋值 / 各种字面量写法 / 全部优先级）、
`rsf 版语法器对非法输入与 Kotlin 版一样判失败`、
`rsf 版语法器的入口编译执行与解释执行一致`。

### 离自举还有多远

自举 = 「用 rsf 写的 rsf 编译器」编译它自己。17a（词法器）、17b（语法器）与
17c（解释器）已经把**前端 + 执行**走通了，剩下的不是语法糖，而是**写编译器本身需要的那几样基础设施**：

| 需要的能力 | 现状 | 卡在哪 |
| --- | --- | --- |
| 表示 Token / AST 节点 | ✅ | 用户自定义类（阶段 12）+ 泛型（阶段 15）已经够用；AST 用「胖节点 + `switch (kind)`」绕开「没有继承」（17b 实证） |
| 存一列 Token / 子节点 | ✅ | 数组 `T[]`（阶段 13）：`new Token[n]`、`tokens[i]`、`tokens.length` 两端都通 |
| 构造对象 | ✅ | `new 类名(...)` 和 `new 导入的 JVM 类(...)` 都能用了 |
| 报错与恢复 | ✅ | `try`/`catch` 与 `!T` 已经有了（阶段 14）——不过 17b 实测：`!T` 会顺调用链传染，语法器最后选了「flag + 占位节点」这条路，代价是错误消息没法逐字复刻 |
| 写 `.class` 文件 | ✅ | `import org.objectweb.asm.*` + `new ClassWriter(...)` 已经能走了（和 Kotlin 版同一条路） |
| 读源文件 | ✅ | `import java.nio.file.Files` 已经能用 |
| 词法层的字符处理 | ✅ | `byte`/`char` + `isDigit`/`isLetter`/`isWhitespace`/`toUpper`/`charToInt`（阶段 16） |
| 递归下降 + 优先级爬升 | ✅ | 函数、递归、`if`/`while`/`goto` 够用；17b 实测「方法重载 / 可变参数 / 默认参数」缺了有点啰嗦（`match` 只能拆成 `matchAny2/3/4`）——**方法重载已在阶段 19d 补上**（`rsfSrc/bootstrap/*.rsf` 里的绕开写法按冻结原则不改），可变参数 / 默认参数仍未做，但拦不住 |
| **字典 / 集合** | ✅ **已解决（2026-10-11）** | 擦除互操作现在按**方法签名**里的类型变量对齐，`HashMap&<string, int>` 的 `put` / `get` / `containsKey` / `size` 两端都能用（`put` 当语句写也不会因为旧值是 null 而拆箱炸掉）。`HashMap&<K, V>` 可以直接当符号表用，17c 那种手写平行数组不再是必须 |
| 字符串工具 | ❌ 缺 | `string` 只有 `length` / `charAt` / `substring`，没有 `split` / `join` / `trim` / `repeat`（17b 手写了 30 行 `normalizeImportPath`） |
| 非局部跳转 | 🚧 要自己想辙 | 没有异常，只有 `fail` + `!T`：17c 用**带前缀的错误消息**当信号（`toChar(1) + 种类 + ":" + 载荷`）实现 `return` / `break` / `continue` / `goto`，并在每一层按种类决定「接住」还是「原样往上传」。**能跑，但这是解释器的头号复杂度来源** |

按最省事的排法，剩下的就是 17（自举闭环）——阶段 13 的数组、14 的错误返回、
15 的泛型、16a 的 `byte`/`char`、16b 的 `byte[]` 落盘都已经落地。
**这一步已经走完（2026-10-13，阶段 17e）**：用 Kotlin 版把 rsf 版编译器编出来（A），
A 编自己得（B），B 再编自己得（C），**B 与 C 的输出 126 个 `.class` 逐字节相同**
（经典的三阶段自举验证）；八示例经 rsf 版后端编出来的产物也与 Kotlin 版字节级全同。

**「探一次容器」这件事已经有结论了（2026-10-11 更新）：`HashMap&<K, V>` 能用。**
17c 当时的结论是「一半能用」——`ArrayList&<T>`（一个类型实参）能用，
`HashMap&<K, V>`（两个）不能用。现在**擦除互操作按方法签名对齐**了：
`ClassPath` 读 `.class` 时把方法的**泛型签名**（`put(TK;TV;)TV;`）解析成
「第 i 个形参 / 返回值是类的第几个形式类型参数」，Checker 再按接收者的类型实参还原。
于是 `HashMap&<string, int>` 的 `put` 期望 `(string, int)`、`get()` 给回 `int`，
和 `ArrayList&<int>` 走同一条路（也顺带支持了 `TreeMap` / `LinkedHashMap` 这些同形状的类）。

`HashMap&<K, V>` 可用，但 **17d 的表最后并没有用它**：`Backend.rsf` 里的类表 / 函数表 /
枚举表 / 字段表都是**手写平行数组 + 线性查 + 满则翻倍**（`findImport` / `findClass` /
`findEnum` / `putFld` / `fldOf`），常量池去重交给 ASM 自己的 `ClassWriter`。
按 2026-10-13 的实测，`rsfSrc/bootstrap/**` 里根本没有 `HashMap` / `ArrayList` 的声明或调用
（17d 表条目里那两行是当年预留的死条目）。
写 `m.put(k, v)` 这种**丢弃返回值**的语句不会因为旧值是 `null` 而炸——
两端都在「结果被丢弃」时跳过泛型结果收敛（Java 的 `map.put(...)` 当语句也不拆箱）。
细节见 [`BOOTSTRAP.md`](BOOTSTRAP.md) 的「17d 前置三件」与「17d 的下一步」。

**2026-10-15 现状**：17e 闭环（B ≡ C）之后，离「rsf 版顶替 Kotlin 版」还差这些（详见
[`BOOTSTRAP.md`](BOOTSTRAP.md)）：① **阶段 20 的 `interface` / `override` 已同步**（词法 45 → 51 项 +
`@` 记号 → 第 1 刀 `Parser.rsf` 语法 → 第 2 刀 `Backend.rsf` / `Interp.rsf` / `Sources.rsf` 语义；
三件套 `bash build/verify21/knife-verify.sh` 全绿：J1 134 类 / diff 0、八示例一致）；
**阶段 21 的注解已整条同步**（第 3 刀 `Parser.rsf` 收声明 / 元素 / 伴生实体 / `@` 使用点，
第 4a 刀 `Backend.rsf` 收代码生成，第 4b 刀 `Interp.rsf` 收解释器语义，第 5 刀收 `Marker::Impl`
嵌套类型名；探针台 12 正例四列全绿、类字节差异 0）；② 后端 / 解释器的
「暂不支持清单」（JDK 成员表是**手写白名单**；2026-10-16 已扩充到 `Math` 的全部数值重载 +
`sqrt|floor|ceil|pow|round` + 静态字段 `PI|E`、`String.valueOf`（按实参选重载）、
`Integer.toHexString|toBinaryString|toOctalString`、`java/util/HashMap` 的
`put|get|remove|containsKey|containsValue|size|isEmpty|clear` —— 判据是
`interop/usejava.rsf` 与 `HashMap` 探针的 Kotlin 产物 vs 自举产物 `diff -r` 均为 0
（`bash build/verify21/bootparity/backend-sweep.sh`）；表外成员仍显式报错，自举源自身也没有接口风格 /
注解风格）；③ **阶段 25 的类继承已整条同步到自举链**（五刀：K5a `14a6b0a` 词法+解析 → K5a-fix `1ca8abd`
类头修饰词顺序 → K5b `69916ab` 字节码后端 → K5c `45201d7` 解释器 → K5d 收口：新增
`rsfSrc/lang/inherit.rsf` 闭环示例、八示例锚点扩成九示例；实跑 `J1 类总数=148`、`diff -r` 差异条目 0，
`k5corpus` 15 份 / `astparity` 7 正例+5 负例 / 探针 17+17；自举源自身仍沿用旧写法，没改成类继承风格）；
④ 工具面（自举侧只有 4 个单文件 CLI，没有统一 CLI / LSP）。

**每个阶段改哪些文件、验收标准、风险、以及要先拍板的 5 个语法点，都在 [`BOOTSTRAP.md`](BOOTSTRAP.md)。**
