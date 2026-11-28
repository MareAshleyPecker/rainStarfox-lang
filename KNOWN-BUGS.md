# 已知 bug / 缺口（给电脑端接手的人 / AI）

> 这份是**可复现**的清单：每条都有最小复现、三端实际输出、期望行为、以及改哪里。
> 写于 2026-11-28。跑法见 `HANDOFF.md` §1（`sh tools/dev/kb.sh` → `sh tools/dev/quick.sh`）。
> 改完请至少跑一遍：判据 `sh build/verify84…100/run.sh` + `parity: 9/9`。

## 1. C 后端：`var s : ?String = null` 生成的 C 编不过

**复现**

```rsf
fn main() {
    var s : ?String = null
    println("x: " + s!!)
}
```

```sh
java -cp "build/dev/classes:$(cat build/dev/rt.cp)" org.example.MainKt build a.rsf --c --link -o out
```

**实际**：gcc 报错（`<source>` 是给 gcc 的伪路径）

```
    rsf_str v_s = NULL;
                  ^~~~
<source>: error[build]: 链接失败（gcc 退出码 1）
```

**期望**：`rsf_str v_s = rsf_str_null();`，然后程序跑起来打 `运行期错误: rsf: 对 null 使用了 '!!'`（解释器/字节码现在就是这个）。

**改哪里**：`CBackend.kt` 的 `stmt()` → `is VarDeclStmt` 分支，现在是

```kotlin
pad(indent) + "$v${cType(type)} ${paramName(s.name)} = ${expr(s.initializer)};\n"
```

`expr()` 对 `NullLit` 只看 `expectedType`，而这里没设，就落到 C 的 `NULL`（String 在 C 里是结构体，不能这么赋）。
同源问题本轮已经修了两处可以参考：字段写入（`AssignStmt` / `MemberAssignStmt` 走 `adapt(field.type, …)`）、
`?:` 的兜底值（`elvis()` 里把 `expectedType` 设成结果类型）。**VarDecl 这一处还漏着**，
建议统一成一个「按声明类型求初值」的入口，别再一个分支一个分支地补。

## 2. 除零：三端三种行为

**复现**

```rsf
fn main() {
    var z = 0
    println("商: " + intToString(10 / z))
}
```

| 后端 | 实际输出 | 退出码 |
|---|---|---|
| 解释器 | `运行期错误: 除以零` | 1 |
| 字节码 | `运行期错误: / by zero`（Java 的 ArithmeticException 文案） | 1 |
| C（`--c --link`） | `商: 0` —— **静默算错** | 0 |

**期望**：三端同一条 rsf 消息（建议 `除以零` 或 `除数为零`），C 侧也要自己检查（现在整数除零是 UB，
这台机器上 gcc 直接给出 0）。`%` 同理。

**改哪里**

- `CBackend.kt` 的 `binary()`：`/` `%` 前面插一句检查（照 `rsf_arr_check` 的写法：置错 + `rsf_throw()`）。
- `BytecodeCompiler.kt`：整数除法现在直接发 `IDIV`/`LDIV`，异常文案是 Java 的。要么在入口兜底里把
  `ArithmeticException` 换成 rsf 消息，要么像数组那样包一层辅助方法。
- 消息常量建议放 `ArrayRuntime` 旁边（新建一个 `RuntimeFailures` 也行），三端都读它。

## 3. 字符串下标越界：三端不一致，解释器直接吐 Java 栈

**复现**

```rsf
fn main() {
    var s = "abc"
    println("c: " + s.charAt(9))
}
```

| 后端 | 实际 | 退出码 |
|---|---|---|
| 解释器 | `Exception in thread "main" java.lang.reflect.InvocationTargetException` + 一整段 JVM 栈，深层原因 `StringIndexOutOfBoundsException: Index 9 out of bounds for length 3` | 1 |
| 字节码 | `运行期错误: Index 9 out of bounds for length 3`（Java 文案） | 1 |
| C | `c:  ` （打了半截、然后正常退出） | 0 |

`substring(from, to)` 越界同理；`rsfSrc/lang/stdlib.rsf` 里 `text.substring(4, 6)` 这类正常用法不受影响。

**期望**：`运行期错误: 字符串下标越界：长度 3，下标 9`，三端一致（格式照 `ArrayRuntime.indexFailure`）。

**改哪里**

- 解释器：`Interpreter.evalMember` 走反射调 `String.charAt`，抛出来的是 `InvocationTargetException`；
  要拆包成 `RsfFailure`（`Thrown.message` 已有同类逻辑，参考 `callBuiltin` 里的 `fileCall`）。
  这也是「解释器的运行期错误吐 Java 栈」这一类问题的代表，值得一起收。
- 字节码：`BytecodeCompiler` 里 `charAt` / `substring` 直接发 `java/lang/String` 的调用；
  要像数组那样换成程序类上的 `__rsf_*` 辅助方法（自己判界 + rsf 消息）。
- C：`rsf_str_char_at` / `rsf_str_substr` 现在**不判界**（前者返回垃圾、后者钳位），要改成置错 + 中止。

## 4. `examples/ui_demo.rsf` 编不过（清单门禁）

```sh
java -cp "build/dev/classes:$(cat build/dev/rt.cp)" org.example.MainKt check examples/ui_demo.rsf
```

```
ui_demo.rsf:2:1: error[loader]: 'Renderer.rsf' 没有出现在 module-impex.rsf 里：…
  2 | import "../rsfSrc/ui/Renderer.rsf"
```

**原因**：`rsfSrc/ui/` 没在 `module-impex.rsf` 里声明成模块，而示例是跨目录 `import "路径"`。

**修法**：清单里加一行（成员表按 `rsfSrc/ui` 实际对外的东西写），例如

```
module "rsfSrc/ui" to ui { Renderer, Node }
```

或在示例里改成别名 import。注意清单是**逐行**解析的，成员表必须写在同一行。

## 5. 自举 Checker 没有内置函数表

`rsfSrc/bootstrap/Checker.rsf` 的 `findFn` 只查用户函数，所以 `parseInt` / `readText` /
`lastErrorMessage()` 这类内置在自举版 `CheckCli` 里都报「未定义的函数 'xxx'」——
实际上自举 checker 现在检查任何用到内置的示例都会挂。

**修法**：给自举 Checker 加一张内置签名表（名字 → 形参 / 返回 / 是否 mayError），
和 Kotlin 侧 `sema/Builtins.kt` 的 `ALL` 对齐；顺带补一条判据把 `CheckCli` 跑起来
（现在 `build/` 里没有它的用例，坏了没人发现）。

## 6. 不是 bug，但容易踩

- `--ll` 只覆盖整数 / 比较 / 控制流 / 调用 / 打印；字符串拼接、数组、类、枚举、闭包、
  `fail`、文件 IO 都还没有（路线见 `HANDOFF.md` §11）。
- 自举解释器（`build/dev/native/interp`）读输入靠环境变量 `rsf.interp.file`，接不到命令行参数。
- `kb.sh` 以前把 kotlinc 版本写死成 2.3.10；2026-11-28 缓存刷新后只剩 2.4.21，
  脚本直接报「依赖 jar 找不全」。**已修**（改成取缓存里版本号最大的那一份），这里留个记录。
