# 阶段 24d 工单：自举解释器 `Interp.rsf` 支持 `as` / `?as` / `is` / `!is`

> 24a（Kotlin 两端）、24b（自举词法+解析）、24c（自举后端）已落地并提交（`076aeee` / `5b5d7aa` / `707cbe3`）。
> 本刀只补**最后一块**：`rsfSrc/bootstrap/Interp.rsf`（自举解释器）。跑 `rsf run` 之外的那一列
> （`InterpCli` 驱动的 rsf-interp）现在遇到这四种写法报 `17c 解释器暂不支持：表达式节点 AS/IS`。

## 0. 边界

允许改动：**只有** `rsfSrc/bootstrap/Interp.rsf`。

禁止：
- 改 Kotlin 侧任何文件（`src/**`、`DESIGN-24.md` 等）——这一刀是纯自举同步。
- 改 `Lexer.rsf` / `Parser.rsf` / `Backend.rsf` / `Sources.rsf`：24b/24c 已把树和字节码做好，
  本轮只消费 `AS` / `IS` / `TARGET` 节点。
- 改 `build/verify24/rsfinterp-verify.sh`（这是**父代理写的判据**，不许调低）与 `build/verify24/**`
  其它语料。判据红了就改代码，不改判据。
- 往判据语料里加「能过」的特例，或按文件名/内容打补丁。

## 1. 判据（六条全绿才算完）

```bash
bash build/verify24/rsfinterp-verify.sh          # 本轮主判据：14 份语料，失败项 0
bash build/verify21/bootbackend/run-probes.sh both   # 失败项合计 0（rsf-interp 列 0）
bash build/verify24/run-probes.sh all            # 失败项 0
bash build/verify24/bootstrap-parity.sh          # 9 份，失败项 0
bash build/verify27/run-probes.sh all            # 失败项 0
./gradlew.bat test                               # 551 tests / 0 failures
```

`rsfinterp-verify.sh` 的基线是「14 份语料，失败项合计: 13」——这就是本刀要清掉的差距。
它比 stdout（逐字节）**和退出码**，不比 stderr（Kotlin 版未捕获错误会带栈，不逐字相等）。

## 2. 语义规格（照抄，不要自由发挥）

### 2.1 节点形态（24b 已定）

- `AS` 的孩子 = `[操作数, TARGET]`，属性 `safe` = `"true"` / `"false"`。
- `IS` 的孩子 = `[操作数, TARGET]`，属性 `negated` = `"true"` / `"false"`。
- `TARGET` 的属性 `type` 是**类型文本**：`A`、`A[]`、`int[][]`、`Box&<int>`、`A::Nested`。
- 取属性用现成的 `attr(n, "type")`（`Interp.rsf:191`）、`attrBool(n, "safe")`（`Interp.rsf:209`）、
  `kid(n, 0)` / `kid(n, 1)`。

### 2.2 接进 `ev`

`Interp.rsf:1788-1815` 的 `switch (n.kind)` 里加两条（放在 `else` 兜底之前即可）：

```rsf
NodeKind.AS : rtn evAs(n, env)!!
NodeKind.IS : rtn evIs(n, env)!!
```

### 2.3 判型：`isInstanceOf(v : Val, typeText : string) -> bool`

1. **拆类型文本**：`depth` = 末尾 `[]` 的层数；`elem` = 去掉这些 `[]` 之后的文本。
   多层数组 `int[][]` → depth 2、elem `int`。
2. `depth > 0`：值必须是 `Kind.ARRV`，且 `v.av!!.depth == depth`，且
   `elemCompatible(v.av!!.elemType, elem)` 为真；否则 `false`。
   （`Arr.elemType` 是**最内层**元素文本，`makeArray` 把同一个 `elemText` 层层下传，
   见 `Interp.rsf:1752-1770`；`Arr.depth` 是「这一层还套着几层」。）
3. `Kind.NULLV` → `false`（`null is A` 恒 false）。
4. `Kind.OBJV` → 类链（`chainArray(v.ov!!.cls)`，父类在前）里任一 `c.name == 目标名字`；
   或者类链上任一层的接口闭包含目标名字（见 2.5）。
5. `Kind.ENUMV` → `v.en == 目标名字`。
6. `Kind.STRV` → 目标名字 == `"string"`。
7. `Kind.INTV` / `LONGV` / `FLOATV` / `DOUBLEV` / `BOOLV` / `CHARV` / `BYTEV` →
   目标名字分别是 `int` / `long` / `float` / `double` / `bool` / `char` / `byte`。
   （合法程序里 Checker 会拒 `is int`，这条只为「不静默算错」。）
8. `Kind.LISTV` → 目标名字 == `"ArrayList"`；`Kind.FILEV` → `"File"`
   （对应 `isInteropClassName`，`Interp.rsf:2255-2260`）。
9. 其余 kind（如 `CTYPEV`）→ `fail unsupported("is 判型：值种类 " + ("" + v.k))`，**不许兜底 false**。

目标名字 = `elem` 再擦掉泛型实参（`&<...>` 之前的部分）与可空前缀 `?`（目标不许可空，
解析期已拒，这里只为健壮）。`A::Nested` 保持原文本，与 `Cls.name` 的命名口径一致
（先看 `findCls("A::Nested")` 能不能命中；命不中再退回原文本比较）。

### 2.4 `!is` 就是 `is` 取反：`if (attrBool(n, "negated")) { r = !r }`。

### 2.5 接口闭包：`ifaceClosureHas(startName : string, want : string) -> bool`

BFS：从 `startName` 的 `Cls.supers`（或 `findIface(startName)!!.supers`）出发，逐层展开
父接口，`visited` 用 `string[]` + 线性查重（rsf 没有 HashMap）。写法照抄
`ifaceDefaultScan`（`Interp.rsf:1294-1350`）的队列+`seen` 结构即可。

对 `Kind.OBJV`：先对整条类链的每一层调 `ifaceClosureHas(层名, 目标)`；命中即真。
（对齐 Kotlin `Interpreter.rsfTypeIs`：`classChainOf(className).any { interfaceClosureContains(it.name, …) }`，
见 `Interpreter.kt:1788-1812`。）

### 2.6 `as` / `?as`

```rsf
public fun evAs(n : Node, env : Env) -> !Val {
    var v = ev(kid(n, 0), env)!!
    var target = attr(kid(n, 1), "type")
    if (isInstanceOf(v, target)) { rtn v }        // 不需要 coerce：rsf-interp 按运行期标签，as 不换载体
    if (attrBool(n, "safe")) { rtn vNull() }
    fail castFailure(v, target)
    rtn vNull()
}
```

**必须 `fail`，不能抛 JVM 异常**：rsf 的 `try { } catch (e : string)` 只接 `fail` 的消息，
p2/p5/p6 的判据就是「用户 catch 到之后 println(e)」。

### 2.7 报错文案（逐字，两端一致）

格式：`"rsf: 不能把 " + 实际名字 + " 当成 " + 目标名字`（没有句号、`rsf: ` 前缀在消息里）。

**实际名字**（对齐 Kotlin `Interpreter.runtimeNameOfValue` + `SemanticModel.jvmRuntimeNameOf`，
`Interpreter.kt:1846-1860`、`SemanticModel.kt:834-885`）：

| 值 | 名字 |
| --- | --- |
| `NULLV` | `null` |
| `OBJV` | 对象类名（`Obj.cls`） |
| `ENUMV` | `v.en` |
| `ARRV` | JVM 数组名 = `[` × depth + 元素描述符 |
| `STRV` | `java.lang.String` |
| `INTV` / `LONGV` / `FLOATV` / `DOUBLEV` / `BOOLV` / `CHARV` / `BYTEV` | `java.lang.Integer` / `Long` / `Float` / `Double` / `Boolean` / `Character` / `Byte` |
| `LISTV` | `java.util.ArrayList` |
| `FILEV` | `java.io.File` |

元素描述符：`int`→`I`、`long`→`J`、`float`→`F`、`double`→`D`、`bool`→`Z`、`char`→`C`、
`byte`→`B`、`string`→`Ljava/lang/String;`、其它 → `L<内部名>;`，
内部名 = 类名里 `.` → `/`、`::` → `$`。
例：`C[]` → `[LC;`；`A[][]` → `[[LA;`；`int[][]` → `[[I`。

**目标名字**：`string` → `java.lang.String`；数组按上面的数组规则（元素先擦泛型、擦 `?`）；
其它类名原样（`A`、`Box`——泛型实参擦掉）。`A::Nested` → `A$Nested`。

## 3. 现成可用的东西（别重复造）

| 用途 | 位置 |
| --- | --- |
| `attr(n, key) -> string` | `Interp.rsf:191` |
| `attrBool(n, key) -> bool` | `Interp.rsf:209` |
| `kid(n, i) -> Node` | `Interp.rsf` 内已有 |
| `findCls(nm) -> ?Cls` | `Interp.rsf:1130` 附近 |
| `findIface(nm) -> ?Cls` | `Interp.rsf:1137` |
| `chainArray(root) -> Cls[]`（父类在前、自己在最后） | `Interp.rsf:1168-1186` |
| `baseTypeName(typeText)`（剥 `?` / `&<...>` / `[]`） | `Interp.rsf:1370-1380` |
| `isPrimitiveName(nm)` | `Interp.rsf:1382-1392` |
| `ifaceDefaultScan` 的 BFS + `seen` 写法 | `Interp.rsf:1294-1350` |
| `growText` / `indexOfTextIn` / `indexOfCharIn` / `intToString` | `Interp.rsf` 工具区 |
| `unsupported(what)` | `Interp.rsf:101-105` |

## 4. 陷阱（都会直接红判据）

1. **包名不自举**：`Interp.rsf` 完全不认识 `pkg`（全文件没有 pkg 相关代码），`Cls.name` 是简单名。
   所以带 `pkg` 的程序里 `as` 失败文案会印 `C` 而不是 `demo.C`。判据语料没有 pkg，
   **在代码注释里写明这条已知差异**即可，不要去改 `Sources.rsf`。
2. **JDK 父类不支持**：`Interp.rsf:1241-1254` 在装载期就 `fail "K5c 暂不支持 JDK 父类…"`。
   所以 `is` 不需要 Kotlin 那套「问真实父类实例」的跳转（`Interpreter.kt:1821-1826`）。
3. **不许兜底 false**：未知 kind / 未知值种类要 `unsupported`，静默 false 是对拍抓不住的那种错。
4. **rsf 的 string 方法有限**：`substring` / `length` / `charAt` / `indexOf`（走 `indexOfTextIn`）……
   没有 `endsWith` / `startsWith`，写完先 `rsf check rsfSrc/bootstrap/InterpCli.rsf`。
5. **每个 `switch` 后面要留 `rtn` 落点**（rsf 只看最后一条语句），照现有 `ev` 的写法。
6. **`Val.k` 是 `Kind` 枚举**：拼错误消息要 `("" + v.k)` 或 `kindName` 之类，别直接加。
7. `Interp.rsf` 改完必须能**被 Kotlin 前端编过**——`./gradlew.bat test` 里的 `RsfTest`
   会编 `rsfSrc`，语法/类型错会直接红。
8. 用 `rsf build rsfSrc/bootstrap/InterpCli.rsf -o /tmp/x` 自查语法最快，但正式判据是上面六条命令。

## 5. 交付

1. 改动摘要：新增/修改的函数名 + 行号（`Interp.rsf` 行号以改完为准）。
2. 六条判据的**实跑输出**（命令 + 末尾结论行 + 失败项数字）。
3. 「工单外发现」小节：任何与工单假设不符的地方（例如某个探针暴露了别的差异），
   写清现象与你的处理；**不许默默扩大改动范围**。
