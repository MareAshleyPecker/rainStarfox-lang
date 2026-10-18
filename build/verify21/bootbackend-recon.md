# 自举同步第 2 刀备料：rsf 版 Backend / Interp 支持 interface + override + 父类型子句

只读勘查。本轮**只新增本文件**（未跑 gradle、未动 git、未改 `build/verify21/` 已有文件；探针与日志是早前同任务已建的）。

## 0. 快照（本报告所有行号以此为准）

| 文件 | 行数 | mtime | sha256(前 12) |
|---|---|---|---|
| `rsfSrc/bootstrap/Backend.rsf` | 5402 | 2026-10-14 18:47 | `0a1fc835a2b4` |
| `rsfSrc/bootstrap/Interp.rsf` | 2277 | 2026-10-11 18:24 | `ffd8b7282cce` |
| `rsfSrc/bootstrap/Parser.rsf` | 1951 | 2026-10-15 22:54:37 | `eb64a1513292` |
| `rsfSrc/bootstrap/Lexer.rsf` | 1031 | 2026-10-15 22:20:42 | `f460a2033744` |
| `build/libs/rainStarfox-0.9.0.jar` | — | 2026-10-15 22:43:19 | `6ad3fef5b99c` |

⚠ **`Parser.rsf` 在勘查期间被并行修改**（第 1 刀在飞）。上面是快照；行号可能漂移。

## 1. 三句结论

- `Backend.rsf` / `Interp.rsf` **今天完全没有父类型概念**：类头超类硬编码 `java/lang/Object`、interfaces 表恒 `null`（`Backend.rsf:5132`）；解释器 `Cls` 连"父类型"字段都没有（`Interp.rsf:508-517`），方法查找只按名字线性扫本类（`Interp.rsf:528-535`）。
- 第 1 刀只落到**词法 + 枚举**：`Lexer.rsf:260/263` 已产出 `KW_OVERRIDE`/`KW_INTERFACE`；`Parser.rsf:95` 已加 `INTERFACES, INTERFACE, SUPERS, SUPER`、`Parser.rsf:131-134` 已加 kindName 四个 case、`Parser.rsf:626` 注释已把 Program 孩子顺序改成 `PKG / IMPORTS / FUNCS / CLASSES / ENUMS / INTERFACES`。但**全仓没有任何地方 `new` 出这四种节点**（`grep -rn "NodeKind\.(INTERFACE|INTERFACES|SUPERS|SUPER)" rsfSrc/` ⇒ 0 命中）。
- 因此"接口源"在 rsf 链路里的落点有**两个阶段**，都实测/定位过：解析期直接失败（§1.3 实测），或假设 SUPERS 节点已存在则落 `Backend.rsf:4870` 兜底 / 被 `Backend.rsf:5318-5330`、`Interp.rsf:717-721`、`Interp.rsf:768-773` **静默丢弃**（§3.3）。

## 2. Q1 — `Backend.rsf` 里「类声明」是怎么编译的

### 2.1 从哪个函数进来
- 入口：`public fun compileProgram(g : Gen, ast : Node, sourceName : string) -> void` **@5298**
  - 5299 `g.sourceName = sourceName`；5304-5313 先吃 `PKG`（拼 `g.pkgPrefix` / `g.owner`）
  - 5318-5330 第二趟挑顶层孩子：5322 `FUNCS`→`funcs = c`；5324 `CLASSES`→`classes = c`；5326 `ENUMS`→`enums = c`；**没有 else**
  - 5339 `collectImports(g, imps)` → 5340 `collectClasses(g, classes, enums)` → 5341 `if (g.failed) { rtn }`
  - 5344+ 注册函数；**5373-5377 逐个 `emitUserClass`**；5379-5383 逐个 `emitEnumClass`
  - 程序类本体：5351-5369（5355 `visit(v17(), accPublic() + 16 + accSuper(), g.owner, null, "java/lang/Object", null)`）

### 2.2 类头 / 父类型 / 字段 / 方法各自在哪几行生成
- **类头（唯一一处，父类型硬编码点）**：`emitUserClass` **@5129**
  - **5132 `g.cw.visit(v17(), accPublic() + accSuper(), ci.internal, null, "java/lang/Object", null)`**
  - ⇒ 超类恒 `java/lang/Object`，`interfaces` 参数恒 `null`。**今天没有任何父类型（extends/implements）支持**，也没有任何 `ACC_INTERFACE` / `ACC_ABSTRACT` 用法（`grep -i "interface" Backend.rsf` 只命中注释与 `accSuper`）。
- 字段：5135-5139（5137 `g.cw.visitField(accPublic(), f.name, f.type.desc(), null, null).visitEnd()`）
- 字段初值预走（定 slot）：5140-5176（primary 存在 5151-5166；否则 5168-5175）
- 方法顺序：5178-5189（先构造器、后普通方法）；隐式无参构造器：5191-5196（5194 `emitUserCtor(g, ci, null)`）
- 发射：5209-5220（5214 `emitUserCtor(g, ci, meth2)` / 5216 `emitUserMethod(g, ci, meth2)`）
- `toString` 兜底 5221-5223；`emitOrElseRange` 5224；`g.cw.visitEnd()` 5225
- 方法体本人：
  - `fun emitUserCtor(g : Gen, ci : CInfo, m : ?CMeth) -> void` **@4969**（super 调用 5010-5013：`mv.visitMethodInsn(183, "java/lang/Object", "<init>", "()V", false)`）
  - `fun emitUserMethod(g : Gen, ci : CInfo, m : CMeth) -> void` **@5025**：5026 `var access = accessOf(m.node)`、5027 `if (m.isStatic) { access = access + accStatic() }`、5028 `var mv = g.cw.visitMethod(access, m.name, m.mdesc(), null, null)`、**5030 `mv.visitCode()`（无条件）** ← 抽象方法（无体）走这里必然产出坏 class
- 类登记（第一趟，建表和类型）：`fun collectClasses(g : Gen, classes : Node, enums : Node) -> void` **@4775**
  - 4777-4795 先登枚举；4800-4823 类名 + 类型参数（4806 `ci.node = cn`、4807 `g.addClass(ci)`、4816 `ci.addTypeParam`）
  - 4826-4876 第二趟按孩子种类：4833 `TYPE_PARAMS` 跳过、4835-4843 `FIELDS`（4841 `ci.addField(new CField(prop(f,"name"), ft!!, f.children[0], prop(f,"mutable")=="true"))`）、4844-4868 `METHODS`（4866 `ci.addMeth(new CMeth(m, prop(m,"name"), pts, pns, ret, propBool(m,"static"), prop(m,"control"), isCtor, propBool(m,"primary")))`）
  - **4869-4871 else 兜底：`g.bad("类成员 " + kindName(part.kind))` 然后 `rtn`**
- 方法/字段表：`class CMeth` **@1690**（`opcode()` **@1725-1729**：`if (isStatic) { rtn 184 }` / `if (control == "PRIVATE") { rtn 183 }` / `rtn 182`）；`class CInfo` **@1733**（`methOf` @1818-1826、`hasMeth` @1828-1835、`ctorCount` @1837-1845、`ctorOf` @1848-1856、`hasToString` @1858-1866）；`typeParams` @1743-1745

### 2.3 `class Dog : Greeter` / `interface` 今天用 rsf 版编译器会走到哪、报什么错（**实测**）
探针目录（已存在，早前同任务建）：`build/verify21/bootbackend/recon/`（`probe_iface.rsf`、`probe_parentonly.rsf`、`probe_overrideonly.rsf`）+ 同目录 `log_rsf_*.txt` / `log_kt_*.txt`。
驱动口径：`rsfSrc/bootstrap/BackendCli.rsf:53`
`java -Drsf.backend.file=<源.rsf> -Drsf.backend.out=<目录> -Drsf.backend.name=<类名> -cp "out;build/libs/rainStarfox-0.9.0.jar" BackendCli`（错误前缀 `[rsf-17d]`，见 `BackendCli.rsf:65/77/86`）

rsf 版编译器（`build/libs/*.rsf` 2026-10-13 的解析器 + 新 jar）实测：
- `probe_iface.rsf` → `[rsf-17d] 解析失败：顶层只能写函数声明，格式是 '[public|protected|private] [static] fun [类型] 名字() 返回子句 函数体'`，exit=1
- `probe_overrideonly.rsf` → 同上一条（`KW_OVERRIDE` 也没有消费者）
- `probe_parentonly.rsf` → `[rsf-17d] 解析失败：类 'Dog' 缺少 '{'`，exit=1

Kotlin 版对照实测：
- `probe_iface.rsf` → 编译通过并运行，exit=0，产出 `recon/outK_iface/{Greeter,Dog,probe_iface}.class`
- `probe_parentonly.rsf` → `error[checker]: 类 'Dog' 想继承 'Greeter'，但 'Greeter' 是个类——rsf 现在还不支持类继承（本阶段只做接口）…`，exit=1
- `probe_overrideonly.rsf` → `error[checker]: 类 'Dog' 的 'greet' 写了 override，但父类型里没有可覆盖的方法：接口里没有人要求 greet()`，exit=1

⇒ **今天的答案是"走到解析阶段就死"，进不到 `compileProgram`。** 一旦第 1 刀开始产出节点、rsf 后端未改，则：
- `SUPERS` 作为 `CLASS` 的孩子 → `Backend.rsf:4869-4871` `g.bad("类成员 " + kindName(part.kind))` → 消息 **`类成员 SUPERS`**
- 顶层 `INTERFACES` → `Backend.rsf:5318-5330` **无 else，静默丢弃**（类照编，接口消失 → 运行期 NoSuchMethod / 校验错误）

## 3. Q2 — `Interp.rsf` 里「类 / 对象 / 方法调用」的位置

- 装载：`public fun load() -> void` **@716-726**：717-721 只认 `FUNCS`/`CLASSES`/`ENUMS`（**无 else**）
- 类体登记：`fun loadClasses(cs : Node) -> void` **@762-779**
  - 766 `var c = new Cls(attr(cn, "name"))`
  - 768-773 逐孩子：**770 `if (part.kind == NodeKind.FIELDS) { loadFields(c, part) }`**、**771 `else if (part.kind == NodeKind.METHODS) { loadMethods(c, part) }`** —— **无 else ⇒ `SUPERS` 静默忽略**
  - 774 `classes = growCls(classes, ccount, c)`、775 `ccount = ccount + 1`
  - `loadFields` @781-799（789-797 收 name/type/init）；`loadMethods` @801-814（806 `c.ctors`、809 `c.methods`）
- 类模型：`public class Cls(nm : string)` **@508-536**
  - 字段 509-517：`name / fnames / ftypes / finits / fcount / ctors / ccount / methods / mcount` —— **没有父类型、没有 isInterface、没有 override**
  - **`fieldIndex` @519-526**；**`methodNamed(nm2) -> ?Fn` @528-535**：`if (methods[i].name == nm2) { rtn methods[i] }` —— **只按名字，无 arity、无父链、无可见性**
- 方法模型：`public class Fn(nm : string)` **@495-506**：`name/pnames/ptypes/pcount/body/retType/mayError/isCtor/isStatic/isPrimary` —— **无 override**；`makeFn` **@728-750**（743 `f.body = kid(node, 1)`、745-748 读 `mayError/ctor/static/primary`）
- 调用与分派：
  - **`public fun invokeMember(recv : Val, nm : string, args : Val[], env : Env) -> !Val` @1745-1752**：1746 `STRV`→`stringMember`、1747 `LISTV`→`listMember`、1748 `CTYPEV`→`staticMember`、1749 `OBJV`→`objMember`、1750 `FILEV`→`fileMember`、1751 兜底 `fail unsupported("成员方法 " + ("" + recv.k) + "." + nm + "()")`
  - **`public fun objMember(o : Obj, nm : string, args : Val[], env : Env) -> !Val` @1841-1846**：1842 `var c = findCls(o.cls)`、1843 找不到类 → `fail "17c 解释器：类 '…' 不在本程序里"`、1844 `var m = c!!.methodNamed(nm)`、1845 找不到方法 → `fail "类 '…' 里没有方法 '…'"`、1846 `rtn callFn(m!!, o, c!!, args)!!`
  - `staticMember` @1849-1865（1852 `methodNamed`、1853 报"里没有静态方法"、1857-1862 只有 `System.getProperty` 特判、1863 兜底）
  - `public fun callFn(f : Fn, self : ?Obj, c : ?Cls, args : Val[]) -> !Val` **@1544**
- **多态 / 接口分派今天有没有？没有。** 接收者是运行期值 `Val`，`objMember` 用 `o.cls`（**运行时类名**）在**该类自己的 methods** 里按名字扫；没有任何"静态类型 → 接口方法/父类型方法"的概念（Kotlin 侧正是靠**静态类型**的 `member.interfaceOwner` 决定 `INVOKEINTERFACE`，见 §5 A4）。
- **`override` 与否会影响查找吗？不会。** `override` 在 rsf 解析器里根本没被消费（`Parser.rsf` 全文无 `KW_OVERRIDE` 使用），AST 上没有该属性，`makeFn` 不读，`Cls`/`Fn` 不存，`methodNamed` 只看名字。
- 表达式/语句兜底：`public fun ev(n : Node, env : Env) -> !Val` **@1270**（`switch (n.kind)` @1271，兜底 **@1292**）；`public fun st(n : Node, env : Env) -> !int` **@1985**（`switch (n.kind)` @1986，兜底 **@2005**）

## 4. Q3 — 因 `NodeKind` 新增 INTERFACE 需要改的地方

### 4.1 编译期会拦（穷尽 switch）—— 只有 Parser.rsf
- `Parser.rsf:108` `public fun kindName(k : NodeKind) -> string`：**无 else 的穷尽 switch**（`Parser.rsf:86-89` 注释：「**没有 else 的 switch 必须穷尽**，所以漏了一个种类编译器会直接报错」）。四个新 case 已在 **`Parser.rsf:131-134`**，末尾 `case NodeKind.ERROR : rtn "ERROR"` @177，函数收在 @182 ⇒ 第 1 刀已处理。
- **`Backend.rsf` 没有任何 kind switch**：`grep -c "switch (" Backend.rsf` ⇒ **0**（全是 if/else 链）。
- **`Interp.rsf` 只有 3 个 switch**：`@247`（kindName，Over Val 的 Kind）、`@1271`、`@1986`（后两个都带 `else`）。
- ⇒ **新增 NodeKind 不会在编译期逼你改 Backend/Interp**，只会运行期兜底或静默忽略。

### 4.2 运行期兜底报错位置（行号 + 原文）
| # | 位置 | 原文 | 所属 |
|---|---|---|---|
| 1 | `Interp.rsf:1292` | `else : fail unsupported("表达式节点 " + kindName(n.kind))` | `ev` @1270 |
| 2 | `Interp.rsf:2005` | `else : fail unsupported("语句 " + kindName(n.kind))` | `st` @1985 |
| 3 | `Backend.rsf:2801` | `g.bad("语句 " + kindName(k))` | `walkStmtInner` @2343 |
| 4 | `Backend.rsf:3148` | `g.bad("表达式 " + kindName(k))` | `walkExpr` @2841 |
| 5 | `Backend.rsf:4522` | `g.bad("发射表达式 " + kindName(k) + "（在 " + g.curLabel + " 里）")` | `emitExpr` @4304 |
| 6 | `Backend.rsf:2142` | `g.setFailed("内部错误：表达式缺少类型（" + kindName(n.kind) + "）")` | `tOf` @2139 |
| 7 | `Backend.rsf:4768` | `g.bad("import 形式 " + kindName(im.kind))` | `collectImports` @4744 |
| 8 | **`Backend.rsf:4870`** | `g.bad("类成员 " + kindName(part.kind))` | `collectClasses` @4775 ← **SUPERS 会落这里** |
（`emitIns` @4527 的兜底 `Backend.rsf:4681 g.bad("指令 " + k)` 用的是 `Ins.kind`（string），**不受新 NodeKind 影响**。另有一批非 kindName 的 `g.bad`（类型/赋值/构造/算术等）也都不受 NodeKind 影响。）

### 4.3 静默忽略 —— 危险点（不报错、行为错）
| 位置 | 现象 |
|---|---|
| **`Backend.rsf:5318-5330`** | 顶层只认 `PKG/FUNCS/CLASSES/ENUMS`，**无 else** ⇒ 顶层 `INTERFACES` 被无声丢弃 |
| **`Interp.rsf:717-721`** | `load()` 同样只认 `FUNCS/CLASSES/ENUMS`，**无 else** ⇒ 接口静默消失 |
| **`Interp.rsf:768-773`** | `loadClasses` 只认 `FIELDS/METHODS`，**无 else** ⇒ `SUPERS` 静默消失（父类型收集不到，程序照跑） |
| **`Backend.rsf:5025-5030`** | `emitUserMethod` 无条件 `mv.visitCode()` ⇒ 接口抽象方法被当有体方法发射，产出坏 class（不是被拦） |
| **`Backend.rsf:5132`** | 超类/接口表硬编码 ⇒ 实现接口的类**照样编得出来**，只是 JVM 层不认父类型（`INVOKEVIRTUAL` 打接口方法 → `IncompatibleClassChangeError`/校验失败） |
| **`Interp.rsf:1844-1845`** | 只扫本类 methods ⇒ 父类型方法没被物理复制进本类时报"类 'X' 里没有方法 'y'"（晚到且消息误导） |
| **`rsfSrc/bootstrap/Sources.rsf:481-482 / 514-550`**（第 3 份文件，超出本刀指定的两份） | 多文件/模块装载器只搬运 `FUNCS/CLASSES/ENUMS`（`appendFns` @241 / `appendClasses` @253 / `appendEnums` @265），`build()` @514 拼的 PROGRAM 孩子形状照 `PKG?/IMPORTS/FUNCS/CLASSES/ENUMS`（注释 @509-510）⇒ **接口在多文件模式下同样被丢**；未知 import 形式倒是有 `fail`（@503） |

### 4.4 对拍面（测试侧，会挡住验收）
- `src/test/kotlin/org/example/AstDump.kt:102-127` 只打 `PKG/IMPORTS/FUNCS/CLASSES/ENUMS`；`classDecl` @153-166 只打 `CLASS/TYPE_PARAMS/FIELDS/METHODS` ⇒ **不打 `INTERFACES`、不打 `SUPERS`**。
- 而 `Parser.rsf:131-134` 已给这四个种类定好 dump 名字，`Parser.rsf:626` 注释已把 Program 孩子顺序写成 `… / ENUMS / INTERFACES`（与 AstDump 对齐是硬约定）⇒ **第 1 刀若不补 AstDump，接口上"rsf dump == Kotlin dump"没有基准可对**。

## 5. Q4 — 最小改动方案（先跑通 interface 语义）

★ = 与 Kotlin 版逐字节一致所必需。

**A. 后端：父类型收集与类头**（`Backend.rsf`）
- A1 数据结构：`CInfo` @1733 加 `supers : string[]` / `superCount`（存内部名）、`isIface : bool`。★接口自身也走 `CInfo`（Kotlin 侧 `model.interfaces` 是独立 map，`BytecodeCompiler.kt:148/587`，rsf 侧用 `isIface` 区分即可）。
- A2 收集：`collectClasses` @4775 的 4826-4876 循环里，**在 4870 兜底之前**新增 `else if (part.kind == NodeKind.SUPERS)`，逐 `SUPER` 孩子取类型名入表。
- A3 类头：`emitUserClass` @5129 把 **5132** 的 `visit(...)` 改成带 `superName` + `interfaces[]`。★口径：`class Dog : Greeter, Runnable` **全部进 interfaces 数组，不是父类**（Kotlin `BytecodeCompiler.kt:643` 原话）。
- A4 接口类发射：新增 `emitIfaceClass(g, ci)`（仿 `emitUserClass` @5129 / `emitEnumClass` @5232）：
  - ★`ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT`（Kotlin `BytecodeCompiler.kt:888`；rsf 需补 `accInterface()`/`accAbstract()` 常量，现有常量只有 @68-74 的 `accPublic/accPrivate/accProtected/accStatic/accSuper` + @73 `v17()` + @74 `computeAll()`）
  - ★无 `<init>`；★超类仍 `java/lang/Object`（Kotlin 口径）
  - ★抽象方法 `ACC_PUBLIC|ACC_ABSTRACT` 且**不发 Code**（对应 §4.3 的 `emitUserMethod:5030`）；★接口默认方法 = 有体方法，走普通 `emitUserMethod`
  - 接线：`compileProgram` @5318-5330 加 `INTERFACES` 分支（★顺序在 `ENUMS` 之后，即 `Parser.rsf:626`），发射循环 @5373-5383 相应扩一行
- A5 ★调用指令 `INVOKEINTERFACE`：`CMeth.opcode()` @1725-1729 恒返回 182/183/184；接收者静态类型是接口时必须 **185**，且 `visitMethodInsn` 末参 `itf=true`。**三个 call site**：`Backend.rsf:3936`（`emitCall` 类内直调）、`Backend.rsf:4473`（`emitExpr` 成员调用）、`Backend.rsf:5116`（`?:` 合成方法内）。**ASM 互操作表已支持 5 参**：`Backend.rsf:2050` `asmRow(t, i, "org/objectweb/asm/MethodVisitor", "visitMethodInsn", 5, "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", tVoid(), memP5(tInt(), tString(), tString(), tString(), tBool()))` ⇒ 只把硬编码的 `false` 换成动态值。Kotlin 对应点：`BytecodeCompiler.kt:1504/1511`（`if (member.interfaceOwner) INVOKEINTERFACE else callOpcode(member.decl)`）、`2370-2375`（`mv.visitMethodInsn(callOpcode(member), owner, decl.name, descriptorOf(decl), member.interfaceOwner)`）、`2535/2545`（`interfaceOwner` 定义）、`2619/2663/2764-2769`。
- A6 ★构造器 `super()`：`Backend.rsf:5010-5013` 保持 `java/lang/Object.<init>`（类实现接口不换超类）。
- A7 ★`CInfo.hasToString` @1858-1866 与 `emitUserClass:5221-5223` 自动 `toString` 对接口要跳过（接口不该合成 `toString`）。

**B. 语义检查**（Kotlin 在 `sema/Checker.kt`；rsf 版**没有独立 checker** ⇒ 只能在 `collectClasses` @4775 里就地 `g.bad`，或本刀不做）
- B1 ★类里写 `override`、父类型没有可覆盖方法 → 报错（Kotlin 实测消息见 `recon/log_kt_overrideonly.txt`）
- B2 ★`class Dog : Greeter` 而 `Greeter` 是类 → 报错（`recon/log_kt_parentonly.txt`）
- B3 ★接口多重默认方法冲突 → 报错（探针 `build/verify21/bootbackend/probes/n2_ambiguous_default.rsf`，Kotlin 错误文本在 `probes/chk.err`）
- B4 接口不能 `new`、接口字段语义（Kotlin Checker 侧已拦）

**C. 解释器**（`Interp.rsf`）
- C1 `load()` @716-726 加 `else if (c.kind == NodeKind.INTERFACES) { loadIfaces(c) }`，并给未知种类补 `fail`（替掉静默忽略）
- C2 `Cls` @508 加 `supers : string[]` / `scount` / `isIface`；`loadClasses` @768-773 加 `SUPERS` 分支；接口 → 也建 `Cls`（`isIface=true`），方法进 `methods`
- C3 方法解析沿父链：新增 `resolveMethod(cls, nm)`（或扩 `methodNamed` @528-535），`objMember` @1844-1845 改用它；★接口**默认方法**也要能被找到，且默认方法体内 `rtn greet() + "!"` 的 `greet()` 要能派回到实现类（`THIS` 调用链，`stThisCall` / `callFn` @1544）
- C4 `Fn` @495 加 `isAbstract`（+ 本刀若做覆盖检查则再加 `overrideOf`）
- C5 接口抽象方法被调用且实现类没有 → 给出与 Kotlin 版同口径的错误消息

**D. 解析器（第 1 刀，不在本刀文件范围，但决定第 2 刀能否开工）**
- 已有：`Parser.rsf:95` 枚举、`131-134` kindName、`626` Program 孩子顺序注释
- 缺：四种节点的**生产者**（`new Node(NodeKind.INTERFACES/INTERFACE/SUPERS/SUPER)` 全仓 0 个）、`KW_INTERFACE`/`KW_OVERRIDE` 消费、`AstDump.kt` 的 `INTERFACES`/`SUPERS` 打印（§4.4）

## 6. Q5 — 风险与未确认

- **未确认（高优先）｜`Parser.rsf` 正在被并行修改**：勘查期间 mtime 从早前的 20:46 变成 **22:54:37**，且 `:626` 的孩子顺序注释、`:131-134` 的 case、`:95` 的枚举都是近改的。本报告行号对应 sha256 `eb64a1513292…`。**只读手段核对**（`grep` / `sed -n` / `sha256sum`），未改动，也未跑编译器验证其可编译。
- **未确认｜rsf 的 `switch` 是否真的强制穷尽**：`Parser.rsf:86-89` 注释声称强制，但 `Backend.rsf` 里一个 kind switch 都没有，无法反证。判定依据是"读 kindName 源码 + `grep -c "switch ("`"，**没有实跑编译器验证**（硬约束：不跑 gradle）。
- **未确认｜"SUPERS 节点存在时后端会怎样"是静态推断，非实跑**：今天的 rsf 驱动在**解析期**就失败（`recon/log_rsf_*.txt`），根本进不到 `compileProgram:5298`。§2.3 后半段的落点（`Backend.rsf:4870` / `5318-5330`）来自源码阅读。
- **未确认｜JVM 层面**：rsf 侧生成的"实现用户接口的类"能否通过 ASM 校验/类校验，要等 A1-A5 落地后才能比对（今天只验证到 Kotlin 版 `outK_iface/Dog.class` 可运行）。
- **未确认｜第 2 刀的验收基准**：`build/verify21/` 里已有 `m5-checklist.md`、`bootparser-workorder.md` 等，本轮未逐条核对第 2 刀验收项；且因 `AstDump.kt` 未打 `INTERFACES/SUPERS`（§4.4），**接口上目前没有可对拍的 dump 基准**。
- **未确认｜是否还有第四处 NodeKind 分派**：只按 `switch`/`kindName`/`NodeKind.` 检索定位，未逐行通读 `Sources.rsf`（只见 §4.3 的那处）、`InterpCli.rsf`（仅 `NodeKind.ERROR` @43）、`BackendCli.rsf`（仅 `NodeKind.ERROR` @66 + 错误打印 @77/86）。
- **硬约束遵守**：本轮仅新增 `build/verify21/bootbackend-recon.md`；未跑 gradle、未动 git、未改 `build/verify21/` 已有文件（探针 `build/verify21/bootbackend/recon/*.rsf` 与日志为早前同任务已建，本轮未新增未修改）。

---

# 追加（2026-10-15 22:56–23:02）：自跑基线 + 对工单 §10/§11 的勘误

父代理收窄后的两项交差。仍**未跑 gradle**、未动 git、未改任何生产文件；按父代理指定跑了 `run-probes.sh self`，它会写 `build/verify21/bootbackend/{A/,out_self/,*.self.txt,A.log}`（父代理自己的台，非本报告改动）。

## 7. Q6 — `bash build/verify21/bootbackend/run-probes.sh self` 的实测基线

### 7.1 跑了两次，差别只在第 1 刀当时落没落完

| 时刻 | `Parser.rsf` sha256(前 16) | A（Kotlin 编 `BackendCli.rsf`） | 结果 |
|---|---|---|---|
| 22:58 首跑 | 当时 `:793` 仍传 1 参 | **编不出**：`A exit=1 classes=0` | 6 正例 `self(build_exit=1 stdout=异)`、2 负例 `self_build_exit=1`，**失败项合计 6** |
| 22:59:4x 复跑（父代理刚修完） | `b0a2096f624c85d3`（跑完 `ad8bd953f164b589`） | **编得出**：`A exit=0 classes=30` | 完全同上：`build_exit=1 stdout=异` ×6、`self_build_exit=1` ×2，**失败项合计 6** |

首跑 A 编不过的**原始报错**（`build/verify21/bootbackend/A.log`；GBK 重定向会花屏，下面是加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` 复现的干净原文，已存 `recon/self-baseline-2259.log`）：

```
Parser.rsf:793:22: error[checker]: 调用 'parseSupertypeList' 的参数个数不对：实际给了 1 个，候选有 1 个 —— parseSupertypeList()
  793 |         var supers = parseSupertypeList(nameTok)
      |                      ^
```

- **成因（第 1 刀未接完）**：定义 `rsfSrc/bootstrap/Parser.rsf:892 fun parseSupertypeList() -> Node` 收 0 参、接口侧调用 `Parser.rsf:968` 已按 0 参写，只有类头侧 `Parser.rsf:793` 还在传 `nameTok`。父代理 22:59:38 改盘后该行成了 `Parser.rsf:797 var supers = parseSupertypeList()`，A 立刻通。
- **只有这一个错误**（报错后即停，没有第二个），A 一编通就是 30 个类（`BackendCli`/`Backend`/`Parser`/`Lexer`/`Sources` 全套）。
- 为什么 A 会被 Parser.rsf 卡住：`BackendCli.rsf:16 import "Backend.rsf"`、`:20 import "Sources.rsf"`、`Sources.rsf:31 import "Parser.rsf"`。解释器侧同理：`InterpCli.rsf:18 import "Interp.rsf"`、`:22 import "Sources.rsf"`、`Interp.rsf:60-61 import Lexer/Lexer+Parser` ⇒ 同一时刻编 `InterpCli.rsf` 也报**同一条** `Parser.rsf:793:22`。

### 7.2 A 通了之后：自举后端在 6 正例 2 负例上的原始 stderr（逐字）

命令（日志 `recon/self-<p>.log`）：

```
java -Drsf.backend.file=build/verify21/bootbackend/probes/<p>.rsf \
     -Drsf.backend.out=build/verify21/bootbackend/recon/out_self/<p> \
     -cp "build/verify21/bootbackend/A;build/libs/rainStarfox-0.9.0.jar" BackendCli
```

| 探针 | self build exit | 自举后端原始报错（逐字） |
|---|---|---|
| p1_default_method | 1 | `[rsf-17d] 17d 后端暂不支持：类成员 SUPERS` |
| p2_default_calls_abstract | 1 | 同上 |
| p3_dispatch | 1 | 同上 |
| p4_iface_inherit | 1 | 同上 |
| p5_multi_iface | 1 | 同上 |
| p6_default_dispatch | 1 | 同上 |
| n1_new_interface（负例） | 1 | `[rsf-17d] 17d 后端暂不支持：类型 I` |
| n2_ambiguous_default（负例） | 1 | `[rsf-17d] 17d 后端暂不支持：类成员 SUPERS` |

- `类成员 SUPERS` ← `rsfSrc/bootstrap/Backend.rsf:4870 g.bad("类成员 " + kindName(part.kind))`（`collectClasses` 第二遍，4826-4876）。
- `类型 I` ← `rsfSrc/bootstrap/Backend.rsf:1128 g.bad("类型 " + text)`（类型名兜底；`I` 从未进类表，因为顶层 `INTERFACES` 被 `compileProgram:5317-5330` 静默丢弃）。
- 前缀 `[rsf-17d] ` 来自 `BackendCli.rsf:77/86`；`17d 后端暂不支持：` 来自 `Backend.rsf:868 setFailed(...)`。
- **★基线的假红警告**：`n1` 的 `exit=1` 不是"接口不能 new"，`n2` 也不是"重载歧义"——两条都是被 `SUPERS`/`类型 I` 顶掉的。第 2 刀做完必须重跑，那时 `self_build_exit` 是否仍非 0 才算真判据。

### 7.3 补测：rsf 版**解释器**（`Interp.rsf`）在 6 正例上的真实表现

> ⚠ **`run-probes.sh` 的 `interp` 列测的不是 rsf 解释器**：`:43 java -jar "$JAR" run "$P/$p.rsf"` 走 `Main.kt` 的 `cmdRun`（`Main.kt:52-58` → `Rsf.kt:71 Rsf.interpret` → `org.example.complie.interp.Interpreter`，**Kotlin 版**）。全仓没有把 `rsf run` 接到 `Interp.rsf` 的开关。所以那一列 6/6 `exit=0` 只是 Kotlin 解释器自证，**对第 2 刀要改的 `Interp.rsf` 零覆盖**。

rsf 解释器的正确驱动（`rsfSrc/bootstrap/InterpCli.rsf:26-33` 读 `-Drsf.interp.file`；`InterpCli.rsf:10` 声称 stdout 与 `rsf run` 逐字节相同）：

```
java -jar $JAR build rsfSrc/bootstrap/InterpCli.rsf -o <I>     # 实测 exit=0 classes=19
java -Drsf.interp.file=build/verify21/bootbackend/probes/<p>.rsf -cp "<I>;$JAR" InterpCli
```

实测（`<I>` = `build/verify21/bootbackend/recon/I`，23:00，`Parser.rsf` 已修）：

| 探针 | rsf 解释器 exit | 原始输出（`|` = 换行） | Kotlin 期望 |
|---|---|---|---|
| p1_default_method | 1 | `运行期错误: 类 'Dog' 里没有方法 'shout'` | `woof!` / `woof!` |
| p2_default_calls_abstract | 1 | `运行期错误: 类 'Doubler' 里没有方法 'quad'` | `12` |
| p3_dispatch | **0** | `meow` / `moo` | `meow` / `moo`（**已同**） |
| p4_iface_inherit | 1 | `运行期错误: 类 'Person' 里没有方法 'describe'` | `p#7` / `7` |
| p5_multi_iface | **0** | `AB` / `A` | `AB` / `A`（**已同**） |
| p6_default_dispatch | 1 | `C` / `运行期错误: 类 'C' 里没有方法 'w'` | `C` / `CB` / `CB` / `C` |

- 4 条失败的消息**全部**来自 `rsfSrc/bootstrap/Interp.rsf:1845 fail "类 '" + o.cls + "' 里没有方法 '" + nm + "'"`（`objMember` 1841-1846）；根因是 `load()` 把顶层 `INTERFACES` 静默丢了（`Interp.rsf:720-722` if/else 无 else），与 §11.1 判断一致，且把"哪一处最先炸"钉死在 `objMember`。
- p3/p5 今天已过：`override` 只是同名同签名的方法，`methodNamed` @528-535（只按名字线性扫）就能命中；纯抽象分派走运行期真实类，不需要接口表。
- p2 炸在默认方法**体内**对 `quad()` 的调用（`Interp.rsf:1693`）——它报的是 `quad`（接口里的抽象方法）而不是入口方法，说明"类方法表里找不到"发生在默认方法被执行之后。

## 8. 对工单 §10 / §11 的勘误

**§10（Backend.rsf）：1 处小错 + 2 处漏点（方向无误）。**
1. （off-by-one）§10.4 引的 `:1503`、`:3588` 是多行调用的中间行；`visitMethodInsn` 的调用行是 `:1502`（`eBox`，`184 String.valueOf`）与 `:3587`（`Paths.get`）。
2. （漏）§10.4 该点名而没点名：真正要改成动态 `itf` 的只有**三处**，都是 `opcode` 来自 `CMeth.opcode()` 的通用发射点 —— `Backend.rsf:3936 mv.visitMethodInsn(mi0.opcode, mi0.owner, mi0.name, mi0.desc, false)`（`emitCall` 类内直调）、`Backend.rsf:4473 mv.visitMethodInsn(mi.opcode, mi.owner, mi.name, mi.desc, false)`（`emitExpr` 成员调用）、`Backend.rsf:5116 mv.visitMethodInsn(m.opcode, m.owner, m.target, m.targetDesc, false)`（`?:` 合成方法内）。同形的 `Backend.rsf:4270` 是 PRIVATE 固定走 `183`，**不需要** itf。管道已在：`Backend.rsf:2050` 的 `asmRow(..., "visitMethodInsn", 5, "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", …)` 已是 5 参 ⇒ 只换硬编码 `false` 为动态值，不新增 ASM 行。
3. （措辞）§10.3 的 `:5026-5028` 实测是：`fun emitUserMethod` 在 `Backend.rsf:5025`、`accessOf(m.node)` 在 `:5026`、`if (m.isStatic)` 在 `:5027`、`g.cw.visitMethod(access, m.name, m.mdesc(), null, null)` 在 `:5028`。§10.3 的另外两个引用核对无误：签名驱动 `:4716` ✓（该行确为 `visitMethod(accessOf(fn) + accStatic(), name, descriptorOfSig(g, sg, entry), null, null)`）、`accessOf` 定义 `:3518` ✓。
4. （漏，§10 完全没有）接口在第 2 刀还会撞到另外两处**今天就已在丢节点**的地方：`Backend.rsf:5317-5330`（`compileProgram` 顶层分类只有 `PKG/FUNCS/CLASSES/ENUMS`，**无 `INTERFACES` 分支也无 else** ⇒ 顶层接口被静默丢弃，n1 报 `类型 I` 的根因）；`Backend.rsf:4826-4876`（`collectClasses` 第二遍只认 `TYPE_PARAMS/FIELDS/METHODS`，`SUPERS` 落到 `:4870`）。
5. §10.1 核对**无误**：`:68-73` helper（`accPublic 68`/`accPrivate 69`/`accProtected 70`/`accStatic 71`/`accSuper 72`/`v17 73`）、`512`/`1024` 取值、三处类头模板 `:5132`/`:5238`/`:5355`、以及"全文没有 185"（`grep` 复核，5402 行内 0 命中）全部与源码一致。

**§11（Interp.rsf）：1 处判据会写错 + 2 处漏点（行号基本无误）。**
1. ★（判据会错）§11.2 的"抽象方法 = 没有 `BLOCK` 的 Fn"**不能按"body 为空"实现**：`Interp.rsf:499 var body : Node = new Node(NodeKind.ERROR)` 是 `Fn.body` 初值，`Interp.rsf:743 f.body = kid(node, 1)`，而 `Interp.rsf:220-223` 的 `kid` 在越界时**返回一个新造的 ERROR 节点**（不是 null）。接口抽象方法只有 1 个子节点（`Parser.rsf:1137 if (hasBody) { n.add(body) }`）⇒ 它拿到的 `body` 是 ERROR 节点。判据必须写成 **`f.body.kind != NodeKind.BLOCK`**；否则抽象方法会被当"有体默认方法"登记，`callFn` 去 `blk(ERROR)` 会静默返回（不报错、结果错）。
2. （漏）§11 只覆盖了顶层 `:721-722` 的静默忽略，漏了同一模式的 `rsfSrc/bootstrap/Interp.rsf:770-771`（`loadClasses` 只认 `FIELDS`/`METHODS`，**无 else** ⇒ `SUPERS` 子节点被无声吞掉）。两处要一起补 `fail`。
3. （漏）§11 没提 `Sources.rsf`（多文件装载）这一级：`Sources.rsf:31 import "Parser.rsf"`，第 1 刀新增的顶层 `INTERFACES` 节点要能被合并逻辑原样带上并保序，单文件探针覆盖不到。
4. （行号）`methodNamed` 全部出现处（`grep` 实测 6 处 = 定义 1 + 调用 5）：定义 `Interp.rsf:528`；调用 `:1693`（隐式 `this`，§11.3 写对）、`:1844`（`objMember`）、`:1852`（`staticMember`）、`:1878`、`:1887`（后两处在 `isErrorCapable`）。父代理消息里写的 `:1691` 是 `var cur = env.curClass()`，`methodNamed` 在它下面两行。
5. （实测补充）§11.3 说"四个调用点都要能看见接口默认方法"方向对，但**今天真正会炸的只有 `objMember`（`:1844-1845`）**：`staticMember` 与 `isErrorCapable` 在 6 正例里一次都没被触发 ⇒ 台里**缺两个探针**：静态接口方法、以及接口默认方法出现在 `?:` 左边（属未确认项）。

**一句话基线**：A 编不过的原因**有且仅有** `Parser.rsf:793` 那一个多传的实参（父代理 22:59:38 已修，修完 A 直接通、30 类）；修完后自举后端 6/6 正例红在 `Backend.rsf:4870 类成员 SUPERS`，rsf 解释器 4/6 红在 `Interp.rsf:1845 类 'X' 里没有方法 'nm'`（另 2 条已同）；两个负例今天的"红"是假红，第 2 刀落地后必须重判。
