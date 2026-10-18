# 阶段 24 刀 24a 工单：类型转换与类型判断（`as` / `?as` / `is` / `!is`）

> 一句话：给 rsf 加四个后缀式关键字，一次性做完**词法 → AST → 语法 → 语义 → 字节码 → 解释器**，
> 让同一份源码在解释器和字节码两端 stdout 逐字节相同。自举链（`rsfSrc/bootstrap/**`）**不在本刀**。

## 0 口径（用户 2026-10-17 拍板，不许改）

1. **失败文案**：`rsf: 不能把 <实际类型> 当成 <目标类型>`，两边都印 **JVM 全名**（`Class.getName()` 的点分名）。
   - rsf 用户类：默认包 → `B`；有 `package demo` → `demo.B`。
   - JDK 类型 → `java.lang.String`；数组 → `[LB;` / `[I`（就是 `getName()` 的形态）。
   - 实际值是 null → 印 `null`。
2. **抛 `java/lang/RuntimeException`**（rsf 自己的错误通道，`try { } catch (e : string)` 必须能接住并打出这条文案）。
   DESIGN-24 §1 原来写的是 `ClassCastException`，**以本工单为准**，并在 `DESIGN-24.md` §9 记一条决策。
3. **数组转型做**：`arr as Foo[]`、`arr is Foo[]`（JVM 的 `CHECKCAST`/`INSTANCEOF` 原生支持）。
4. **`null is Foo` → `false`**（`INSTANCEOF` 语义）；`null as Foo` → 抛（文案里实际类型印 `null`）；`null ?as Foo` → `null`。
5. **泛型实参允许写**，按擦除判；**每次写都发一条警告**（stderr；stdout 不许受影响）。
   警告文案建议：`rsf: 'x is Box&<int>' 的泛型实参只用于报错，运行期按擦除后的 Box 判（与 JVM 一致）`。
6. 编译期只查这四件事：目标**必须是引用类型或数组**、目标**不可空**、**不许链式**、**非泛型类型不许写实参**。
   **不做**「两个类不可能相关，所以静态报错」——那是 JVM 的事，运行期抛更符合两端一致。
7. 优先级：**postfix 级**，比 `.` / `[]` / `()` 松，比所有二元运算紧（`a.b as Foo` = `(a.b) as Foo`）。
   `?as` 的 `?` **前置**（rsf 的 `?` 永远前置，不写 Kotlin 的 `as?`）。
8. `is` 结果 = `bool`，`!is` 取反；**v1 不做智能转换**（`if (x is Foo)` 里面 `x` 仍是原来的静态类型）。
9. 操作数/目标带类型参数时按**擦除**（上界 Object）判。

## 1 允许改的文件

| 文件 | 为什么 |
|---|---|
| `src/main/kotlin/org/example/complie/lexing/Token.kt` | 加 `KW_AS` / `KW_IS` |
| `src/main/kotlin/org/example/complie/lexing/Lexer.kt` | `:19` 那张 `KEYWORDS` 表加 `as` / `is`（55 → 57） |
| `src/main/kotlin/org/example/complie/ast/Ast.kt` | 加 `AsExpr` / `IsExpr`（表达式区 `:1025-1152`） |
| `src/main/kotlin/org/example/complie/parsing/Parser.kt` | `parsePostfix()` `:2777` 里加 4 个分支；目标类型复用 `parseTypeRef()` `:1861` |
| `src/main/kotlin/org/example/complie/sema/Checker.kt` | `computeType()` `:5277` 加两支 + 四件事的静态检查 |
| `src/main/kotlin/org/example/complie/sema/SemanticModel.kt` | 若 `typeOf` 需要补登记（`?as` → `?T`、`is` → `bool`） |
| `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt` | `emitExpr()` `:2347` 加两支；`CHECKCAST` / `INSTANCEOF` |
| `src/main/kotlin/org/example/complie/interp/Interpreter.kt` | `eval()` `:447` 加两支；运行期判型 |
| `idea-rainstarfox/src/main/kotlin/org/rainstarfox/idea/RsfTokenType.kt` | **必须**：`EditorKeywordParityTest` 直接读源码文本比对，不加就红 |
| `src/test/kotlin/org/example/AstDump.kt` | dump 行：`AS safe=...` / `IS negated=...` + 类型子节点（后面自举 AST 对拍要用） |
| `src/test/kotlin/org/example/CastTest.kt`（新建） | 回归测试 |
| `DESIGN-24.md` | §7 待拍板改成已定、§9 记决策 |

**禁止动**：`rsfSrc/**`（自举同步是后续刀）、`build/verify24/**`（探针语料与脚本是父代理的判据，改了就是作弊）、
`build/verify21/**`、`build/verify17e/**`、`build/verify25/**` 既有脚本、以及 `idea-rainstarfox/**` 里现有的 4 个脏文件。

## 2 实现要点

**词法/语法**
- `as` / `is` 是**硬关键字**（语料里没有把它们当标识符用的，零迁移风险）。
- `parsePostfix()` 的 while 里，按这个顺序判：
  - `KW_AS` → `AsExpr(expr, parseTypeRef(), safe=false)`
  - `QUESTION` 且 `peek(1).kind == KW_AS` → 吃掉两个 token → `AsExpr(..., safe=true)`（三目 `if c ? a : b` 的 `?` 后面跟表达式，不会撞）
  - `KW_IS` → `IsExpr(..., negated=false)`
  - `BANG` 且 `peek(1).kind == KW_IS` → 吃掉两个 → `IsExpr(..., negated=true)`
  - 四种都在解析后**立刻 return**（不许链式；错误文案写清「`as` / `is` 只允许写一层」）。
- `parseTypeRef()` 自己会吃 `?`（`:1481` 一带），所以可空目标要**显式拒绝**（在拿到 TypeRef 后判 nullable，文案写「目标类型不许写可空：`x ?as ?A` 不合法」）。

**语义（`Checker.computeType`）**
- `AsExpr`：操作数必须是引用类型/数组（`int`/`bool` 等直接报「`as` 只用于引用类型；数值转换用 toByte / toChar / charToInt / intToString / parseInt」）；
  目标是引用类型/数组、非可空、写实参只许在泛型类型上；`safe=false` → `T`，`safe=true` → `?T`。
- `IsExpr`：操作数同上去引用类型（可空的也行）；目标同上；结果 `bool`。
- 泛型实参非空时发警告（一次性，按表达式），但不改变类型（擦除：把 `typeArgs` 在比较时清掉）。

**字节码（`BytecodeCompiler.emitExpr`）**
- `is` / `!is`：`emitExpr(operand)`（引用形式，装箱或本来就是引用）→ `INSTANCEOF <内部名或数组描述符>` → 得 int（即 bool）；`!is` 再 `ICONST_1 IXOR` 或 `IFEQ` 取反，两种都行，只要结果 int。
- `as`：用一个**独立辅助方法**（放在程序类上、public static，像 `ArrayRuntime` 那样）或内联分支都行，但**必须是**：
  `v = 求值`；`v == null` → 抛 `RuntimeException("rsf: 不能把 null 当成 <目标全名>")`；
  `!(v instanceof T)` → 抛 `RuntimeException("rsf: 不能把 " + v.getClass().getName() + " 当成 <目标全名>")`；
  否则 `CHECKCAST T`。
  目标全名在**编译期**就已知（点分；数组用 `getName()` 形态），用 `LDC` 发。
  建议学 `emitErrorCallElvis` 的教训（`BytecodeCompiler.kt:416-430` 的注释）：**别在表达式中间内联搭 if/throw 骨架**，栈上压着别的值时会 `VerifyError`——优先搬进独立的 static 辅助方法。
- `?as`：`v` → `DUP; INSTANCEOF T; IFEQ Lnull; CHECKCAST T; GOTO Lend; Lnull: POP; ACONST_NULL; Lend:`。
- 数组目标：`visitTypeInsn(CHECKCAST/INSTANCEOF, "[LB;")` 这种形态直接可用（见 `internalNameOf` `:2867` 一带的约定）。

**解释器（`Interpreter.eval`）**
- 判型走一条**共用** `fun runtimeIs(value: Any?, target: RsfType): Boolean`：
  非 `RsfObject` → `ClassPath`/`Class.forName` 的 `isInstance`（JDK 对象、字符串、数组都走这条）；
  `RsfObject` → `model` 里的类链 + 接口表（阶段 25 已有 `parentRecord` / `inheritedInterfaceRefs` 那套）。
- 失败文案与字节码**逐字**相同（包括 `null` 与 `[LB;` 形态）。

**AST dump**
- `AS safe=0/1` + 类型子树；`IS negated=0/1` + 类型子树。类型子树复用现有 TYPE 的 dump 写法（`AstDump.kt:512-540` 一带）。
- 泛型实参要 dump 出来（`Box&<int>` 的 `&<...>`），否则两端 `is Box&<int>` 看不出区别。

## 3 判据（父代理自己跑；你只要保证工作树可编译、测试绿）

```bash
bash build/verify24/run-probes.sh check    # 正例 9/9 过、负例 8/8 拒
bash build/verify24/run-probes.sh all      # golden 9/9（金标父代理冻结）、warn 段 p8 stderr 非空、run 9/9 两端同
./gradlew.bat cleanTest test jar --console=plain   # 全绿；tests 应 = 526 + 你新增的
```

不回归（父代理跑，你**不要**改这些脚本）：
`bash build/verify25/run-probes.sh all`、`bash build/verify21/bootbackend/run-probes.sh both`、
`bash build/verify21/knife-verify.sh knife24a`。

自己**不要**写 `build/verify24/expected/**`（那是父代理冻结的金标），也不要跑 `knife-verify`。

## 4 交付

- 不要 commit。
- 回复里按 `文件:行号 → 做了什么` 列清楚，附上你新增的测试名与 `./gradlew.bat` 的测试计数。
- 有偏离口径的地方（比如某条做不到）**必须先说**，不许改语料或改判据凑绿。
