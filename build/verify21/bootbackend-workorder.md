## §10 rsf 侧落点（2026-10-15 父代理实读 `rsfSrc/bootstrap/Backend.rsf` 补）

1. **访问标志是 helper，不是常量名**：`:68-73` 有 `accPublic()=1` / `accPrivate()=2` / `accProtected()=4`
   / `accStatic()=8` / `accSuper()=32` / `v17()=61`；**没有** interface/abstract/annotation 的 helper。
   现有先例：枚举类头直接写数字 —— `:5238 g.cw.visit(v17(), accPublic() + 16 + accSuper(), ei.internal, null, "java/lang/Object", null)`（16 = ACC_FINAL）。
   本刀加 `accInterface()`=512、`accAbstract()`=1024 两个 helper（放在 :73 后），
   接口类头 = `g.cw.visit(v17(), accPublic() + accInterface() + accAbstract(), iface.internal, null, "java/lang/Object", 父接口数组)`，
   **不加 `accSuper()`**、父接口数组不能再传 `null`。
2. **类发射三处模板**：普通类 `:5132`、枚举 `:5238`、顶层程序类 `:5355`；接口要仿它们但走上面那套 flag。
3. **方法发射两处**：签名驱动 `:4716`、用户方法 `:5026-5028`（`accessOf(m.node)` 再 `+ accStatic()`；`accessOf` 在 `:3518`）。
   抽象方法 = `accPublic() + accAbstract()`，`visitMethod` 之后**直接 `visitEnd()`**，不许 `visitCode()`/`visitMaxs()`。
4. **opcode 全是裸数字 + 注释**：`:1636` 注释写着 `182=INVOKEVIRTUAL 183=INVOKESPECIAL 184=INVOKESTATIC`，
   `:1300-1303` 静态方法表项 `m.opcode = 184`，`:1725-1728 opcode()` 是 `if (isStatic) { rtn 184 } … rtn 182`。
   **全文没有 185（INVOKEINTERFACE）**，本刀要加第三个分支；并且 `visitMethodInsn` 的最后一个布尔参数
   （现有调用一律 `false`，见 `:1416`、`:1503`、`:1509-1515`、`:3568`、`:3588`、`:3612`）在接口调用时必须是 `true`。
5. 判「该不该发 INVOKEINTERFACE」需要「静态类型是不是 rsf 接口」这条信息 —— Kotlin 侧是 `UserMember.interfaceOwner`，
   rsf 侧的成员表结构要在本刀里补一个同义字段（语义层第 1 刀已在 AST 里给出 `INTERFACE` 节点与 `SUPERS`）。

## §11 rsf 侧落点：解释器（2026-10-15 父代理实读 `rsfSrc/bootstrap/Interp.rsf` 补）

1. 装载分派 `:721-722`（`c.kind == NodeKind.CLASSES` → `loadClasses`、`ENUMS` → `loadEnums`）要加 `INTERFACES` 分支。
2. `public class Cls`（`:508-536`）现有字段：`name / fnames / ftypes / finits / fcount / ctors / ccount / methods / mcount`；
   `methodNamed(nm)`（`:528-535`）**只线性扫自己的方法：无父链、不比对签名**。
   接口需要新的 `Ifc` 表（或给 `Cls` 加 `isInterface` + `superNames`）与 `loadInterfaces`（仿 `loadClasses` `:762-779`
   与 `loadMethods` `:801-815`：抽象方法 = 没有 `BLOCK` 的 Fn，默认方法 = 有 `BLOCK`）。
3. 四个调用点都要能看见接口默认方法（否则探针 p1/p4/p6 必红）：
   - `objMember`（`:1841-1847`，按运行期 `o.cls` 找方法，找不到报 `类 'X' 里没有方法 'nm'`）；
   - `staticMember`（`:1849-1855`）；
   - 隐式 `this` 调用（`:1693 var m = cur!!.methodNamed(nm)`）；
   - `isErrorCapable`（`:1878` 与 `:1887`）—— 它判 `?:` 的「会不会失败」，漏了默认方法会**静默判错**。
4. **抽象方法的分派已经天然正确**（`objMember` 走运行期真实类），本刀真正要补的是
   「类自己没这个方法时，沿它的接口链找**带体默认方法**」这一条兜底：建议加
   `methodNamedWithDefaults(c, nm)`（先 `c.methodNamed`，再沿接口链找默认方法，子接口优先），四处统一换掉。
5. 语义分歧不用在 rsf 侧自己解决：同签名默认方法被子接口覆盖时 Kotlin 侧 Checker 会报重载歧义
   （见 `build/verify21/bootbackend/probes/n2_ambiguous_default.rsf`），rsf 侧只要**与 Kotlin 侧同判**即可。

## §12 勘误（2026-10-15 勘查子代理实读后修正，**冲突时以本节为准**）

来源：`build/verify21/bootbackend-recon.md` §7（自跑基线）/ §8（勘误）。

### §12.1 §10 的修正
1. `:1503`/`:3588` 是**多行调用的中间行**，真正的调用行是 `:1502`（`eBox`）/`:3587`（`Paths.get`）。
2. §10.4 漏点名：要改「第三位布尔」的**只有三处** —— `:3936`、`:4473`、`:5116`；`:4270` 是 PRIVATE 固定 183，不用改；
   管道在 `:2050` 的 5 参 `asmRow`。
3. §10.3 行号修正：`accessOf` 在 `:5026`、`visitMethod` 在 `:5028`（`:5025` 是 def）。`:4716`、`:3518` 核对无误。
4. **§10 漏了两处今天就在丢节点的地方**（必须一并改）：
   - `compileProgram:5317-5330` 没有 `INTERFACES` 分支、**也没有 else** ⇒ 顶层接口被静默丢掉；
   - `collectClasses:4826-4876` 只认 `TYPE_PARAMS`/`FIELDS`/`METHODS`，`SUPERS` 落到 `:4870` 的兜底报错。
5. §10.1（`:68-73` helper、512/1024、三处类头模板、「全文没有 185」）全部核对无误。

### §12.2 §11 的修正
1. ★**判据会写错**：不能按「body 为空」判抽象方法 —— `Fn.body` 初值是 `ERROR` 节点（`Interp.rsf:499`），
   `:743 f.body = kid(node,1)`，而 `kid` 越界返回**新造的 ERROR 节点**（`:220-223`），
   接口抽象方法只有 1 个子节点（`Parser.rsf:1137`）⇒ 必须写成 **`f.body.kind != NodeKind.BLOCK`**，
   否则抽象方法被当默认方法登记、`blk(ERROR)` 静默返回。
2. 漏点：`loadClasses` `:770-771` 没有 else，`SUPERS` 被无声吞掉（与 `:721-722` 同模式）；
   `Sources.rsf:31` 多文件装载要把顶层 `INTERFACES` **保序**带上。
3. 行号：`methodNamed` 定义 `:528` + 调用点 `:1693/:1844/:1852/:1878/:1887`（`：1691` 那个是 `env.curClass()`）。

### §12.3 探针台基线（第 1 刀落盘前后实测，**红都要重判**）
- 第 1 刀中途 `run-probes.sh self` 的 A 编不过：`Parser.rsf:793:22: error[checker]: 调用 'parseSupertypeList' 的参数个数不对：实际给了 1 个，候选有 1 个 —— parseSupertypeList()`；
  子代理把 `:793`→`:797` 改对后 `A exit=0 classes=30`（原始日志 `recon/self-baseline-2259.log`）。
- 之后 6 个正例的自举侧一律 `[rsf-17d] 17d 后端暂不支持：类成员 SUPERS`（`Backend.rsf:4870`）；
  `n1` 是 `…后端暂不支持：类型 I`（`Backend.rsf:1128`，顶层 `INTERFACES` 被 `compileProgram:5317-5330` 丢弃）、`n2` 又是 `类成员 SUPERS`。
- ★**基线假红**：n1/n2 今天的红不是它们要测的理由，第 2 刀落地后必须**重新判定**。
- `run-probes.sh:43` 的 `interp` 列走的是 **Kotlin 解释器**（`Main.kt:52-58 → Rsf.kt:71`），对 `Interp.rsf` **零覆盖**；
  补测 rsf 解释器（`InterpCli.rsf:26-33`，exit=0/19 类）：p1 `类 'Dog' 里没有方法 'shout'`、p2 `类 'Doubler' 里没有方法 'quad'`、
  p3 exit=0 与期望同、p4 `类 'Person' 里没有方法 'describe'`、p5 exit=0 同、p6 打 `C` 后 `类 'C' 里没有方法 'w'`；
  4 条失败全出自 `Interp.rsf:1845 objMember`。
- 因此**探针台缺两个用例**：「静态接口方法」、「接口默认方法出现在 `?:` 左边」（`isErrorCapable`）。

---

## §13 Kotlin 侧参考语义（接口）——父代理抄好的对照答案，冲突时以本节为准

第 2 刀的目标是「两边逐字节一致」，所以先把 Kotlin 侧**究竟怎么发、怎么分派**抄全。以下都是 `src/main/kotlin/org/example/complie/` 下的实测行号。

### 13.1 字节码（`codegen/BytecodeCompiler.kt`）

- **接口类头** `:884-893`：`V17` + `ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT`（**= 1537**）、签名 `null`、
  超类写死 `"java/lang/Object"`、父接口数组 = `superInterfaceNames(decl.name)`（**不能传 null**，无父接口就用空数组）。
  ★注意 `visitSource(sourceName, null)` 也要发（`:894`），否则和 Kotlin 产物对不上。
- **抽象方法** `:900-904`：`cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, name, descriptor, null, null)`
  → 打注解使用点 → **`mv.visitEnd()`，全程不碰 `visitCode`/`visitMaxs`**。`ACC_ABSTRACT` 漏了会在加载期报
  `ClassFormatError: Absent Code attribute in method that is not native or abstract`。
- **默认方法** `:905-907`：走 `emitUserMethod(cw, internalName, method)`，和类方法同一套。
- 接口**不发字段、不发 `<init>`**（D3 已由 Parser/Checker 拦住）。
- `emitOrElseMethods(cw)` `:913`：`?:` 的合成静态方法挂在**当前这个接口**上，必须跟着本接口的 class 发；
  调用它时 `itf` 传 `true`（见 `:2510` + `isInterfaceInternalName` `:587-588`），否则运行期
  `IncompatibleClassChangeError: ... must be InterfaceMethodref constant`。
- **调用点指令**：`callOpcode(member)` 与 `member.interfaceOwner` 配对 —— `:1511`（`if (member.interfaceOwner) INVOKEINTERFACE else callOpcode(member.decl)`）、
  `:2375`（`mv.visitMethodInsn(callOpcode(member), owner, decl.name, descriptorOf(decl), member.interfaceOwner)`）；
  JDK 接口那条 `:2146-2156` 同理。`interfaceOwner` 定义在 `interop/MemberResolver.kt:12-19`。
  ⇒ 自举侧要加的「静态类型是不是 rsf 接口」标志就是它的对应物；而 `visitMethodInsn` 末位布尔在 rsf 侧**必须跟着这个标志走**，不能一律 `false`。
- 注解接口 `:943-949` 是 1537 **再 or `ACC_ANNOTATION`**（= 0x2601），父接口写死 `java/lang/annotation/Annotation` —— 那是第 3 刀的事。

### 13.2 解释器（`interp/Interpreter.kt`）——`Interp.rsf` 要照抄的分派算法

- 入口 `:583-597`：`UserMember.Method` 且 `member.interfaceOwner && !method.isStatic` → `callInterfaceMethod`；
  （`:591-593` 的注解接口特例是第 3 刀的事）。JDK 接口那条 `:613-628`。
- `callInterfaceMethod(self, decl, args)` `:753-771`：**先解析、后回退**
  1. `findInterfaceImplementation(self.className, decl.name, paramTypesOf(decl))`；
  2. `impl == null || impl === decl` 时：`decl.body != null` → 跑 `decl` 那份体（静态类型自己的默认方法）；
     `body == null` → 内部错误 `内部错误：'X' 没有实现接口方法 'nm'`；
  3. 否则 `callMethod(self.className, impl, self, args)`；抛 `RsfFailure` 时补记 `failure.dispatched[impl] = decl`
     （`?:` 的身份判断要看这条对应关系，`:766-769`）。
  ⇒ **默认方法也必须解析**：子接口 `override` 掉父接口默认方法时，静态类型写父接口的调用照样要跑子接口那份，
  否则解释器打父的、字节码打子的，两端不一致（`:745-748` 的原话）。
- `findInterfaceImplementation` `:797-806`：先运行期类的 `plainMethods`（名字 + `paramsMatch`），再 `findInterfaceDefault`。
- `findInterfaceDefault` `:815-833`：对 `model.superInterfacesOf(owner)` 做 DFS，**跳过 `fromClassPath`**、
  用 `visited` 保证菱形只走一次，命中条件 = 接口的 `defaultMethods` 里名字相同 + `paramsMatch`，再递归父接口。
- `paramsMatch` `:842-846`：个数相同，且逐位 `w.isTypeParam || w.descriptor == "Ljava/lang/Object;" || own[i].descriptor == w.descriptor`
  （即 **类型变量与 Object 视为万能匹配**，其余一位不差——这就是擦除后 `INVOKEINTERFACE` 的解析口径）。
- JDK 接口的默认方法**不在这条链上**（实现不在语义模型里）——跳过是明确口径，不是漏写（`:808-813`）。

### 13.3 验收口径

第 2 刀的最终判据仍是四列探针台 `bash build/verify21/bootbackend/run-probes.sh both`
（`kotlin` 列 7 正 + 3 负 = 0 失败；`self` 与 `rsf-interp` 列从基线 17 红走到全绿），
外加 `bash build/verify21/knife-verify.sh` 的三件套。

---

## §14 与第 1 刀的耦合：本刀落地前 accept-after 必红（验收口径）

第 1 刀让 `Parser.rsf:899-935 parseSupertypeList()` **无条件**给每个 CLASS 发 `SUPERS` 子节点（与 `AstDump.kt:184` 对每个类都打 `SUPERS count=N` 同口径，改不得），
于是旧 `Backend.rsf` 立刻在 `collectClasses:4826-4876`（`SUPERS` 落到 `:4870` 兜底）上把 9/9 个自举源全判失败：
J1 类总数 0 ⇒ J3/J2 连带红 ⇒ `build/verify17e/accept-after.sh` 失败项 5、`verify2.sh` 不一致 9 行（`ClassNotFoundException: InterpCli`）。

**这就是第 2 刀要做的第一件事**：让 `compileProgram` 认 `INTERFACES`、`collectClasses` 认 `SUPERS`（以及 `SUPER`）。
在它落地之前：

- 第 1 刀的验收 = `knife-verify.sh` 的第 1 段（gradle 全绿）+ 语法器侧冻结格式核对；
- 第 2 段的红用 `KNIFE_ALLOW_RED_ACCEPT=1` 降级为告警（脚本已支持）；
- **三条全绿是第 1+2 刀共同的判据**，不是第 1 刀单独的判据。

另外提醒：`build/verify21/knife-verify.sh` 的判决**已改**（旧版只看子脚本退出码 ⇒ 会打印假绿）。
复盘既有日志用 `KNIFE_SKIP_RUN=1 bash build/verify21/knife-verify.sh <证据目录>`。
