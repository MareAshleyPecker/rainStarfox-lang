## 4. 对拍语料（`RsfTest.kt` 的手写刁钻语料 :6949-7167）

**只准往语料里加新用例、不准改已有期望值**。至少覆盖：

- 抽象方法接口 + 默认方法接口（各一个函数）。
- `interface Loud : Greeter { }`（接口继承接口，一个父类型）。
- `public class Dog : Greeter { override fun greet(...) -> string { ... } }`（单父类型 + override）。
- 一个类带两个父类型（`class C : A, B`）。
- 带类型参数的类 + 父类型（`class Box&<T> : Holder { }`）。
- 没写 `:` 的类（确认 dump 里出现 `SUPERS count=0`）。

**注意**：这些语料里**不许出现** rsf 版语法器还不认的东西（注解 `@`、`mathematic`、`companion entity` 都没有同步，
别用）。语料要能被**当前** rsf 语法器跑（它只认 `->` 与 `:` 返回子句——M0 已同步 `COLON`）。

## 5. 硬约束

- 只准改：`rsfSrc/bootstrap/Parser.rsf`、`src/test/kotlin/org/example/AstDump.kt`、
  `src/test/kotlin/org/example/RsfTest.kt`（只加语料），并新建 `build/verify21/bootparser/**` 放证据。
- **不准**碰：`rsfSrc/lang/**`、`rsfSrc/bootstrap/{Lexer,Backend,Interp}.rsf`、`src/main/**`、
  `src/main/kotlin/org/example/complie/**`、`DESIGN-*.md`、`BOOTSTRAP.md`、`README.md`、
  `build/verify17e/**`、`build/verify1*/**`、`build/verify20/**`、`build/verify21/**` 里的**已有**文件。
- **不准**在任何 `rsfSrc/**` 文件里使用新语法（`interface` / `override` / `class X : Y`）：
  自举源必须仍然能被「同步前」的 rsf 编译器编（D10 是分阶段的）。
- **不准**做任何 git 操作（`add`/`commit`/`checkout`/`stash`/`restore` 都不行）。
- 文件是 **LF** 行尾；不要用会把 CRLF 写进去的方式改文件。
- 改完必须能编译（Kotlin 侧 `AstDump.kt` 与 rsf 侧 `Parser.rsf` 是同一条链）。

## 6. 怎么验证（命令照抄）

```
./gradlew.bat cleanTest test jar --console=plain
```
期望：全绿（基线 456 条，不许减少；RsfTest 的三条对拍用例必须过）。

对拍自检（拿真的 jar 跑真编译器）：
```
./gradlew.bat jar
java -jar build/libs/rainStarfox-0.9.0.jar build rsfSrc/bootstrap/ParserCli.rsf -o build/verify21/bootparser/out
java -Drsf.ast.file=build/verify21/bootparser/iface.rsf -cp build/verify21/bootparser/out ParserCli
```
（入口名与系统属性照 `rsfSrc/bootstrap/ParserCli.rsf` 里的写法；上一刀词法的证据在
`build/verify21/bootlex/`，可以照着抄跑法。）

## 7. 报告要写什么

