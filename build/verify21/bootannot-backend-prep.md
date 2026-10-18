2026-10-16 · 只读勘查 · 供第 4 刀工单使用

范围：把阶段 21 注解的**语义**同步到 rsf 自举链。只给坐标与原文，不给实现方案（方案父代理自己写）。

硬约束：`Backend.rsf` 产出的 `.class` 与 Kotlin 后端**逐字节相同**（`build/verify17e/accept-after.sh`、`build/verify21/bootbackend/run-probes.sh`）；`Interp.rsf` 的 stdout 与 Kotlin 解释器一致。
四列口径：`build/verify21/bootbackend/README.md:29-34`（`interp`=Kotlin 解释器 · `kotlin`=Kotlin 后端 · `self`=Backend.rsf · `rsf-interp`=Interp.rsf）；跑法 `build/verify21/bootbackend/README.md:3`。

## A. Kotlin 代码生成侧（`src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt`）

### A0 发射顺序（`BytecodeCompiler.kt:114-198`）
- `:119` 程序类 → `:121-142` 用户类（**伴生类 `Marker$Impl` 走的就是这个循环**：M2 已把它包成普通 `ClassDecl` 放进 `model.classModel.classes`，见 `:185-186` 注释）→ `:148-165` 接口 → `:170-181` 枚举 → `:187-198` 注解接口。
- `:187` `for (spec in model.annotations.values)`，内部 `:197` `pending += internalName to annotationClass(internalName, spec).toByteArray()`。
- 结论：**没有**「先发注解接口」「伴生类单独一趟」——注解接口在最后，伴生类在用户类那一趟里。

### A1 注解接口 `.class`（`BytecodeCompiler.kt:943-980`）
- 类头原文 `:945-952`：
  ```
  cw.visit(
      V17,
      ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT or ACC_ANNOTATION,
      internalName,
      null,
      "java/lang/Object",
      arrayOf("java/lang/annotation/Annotation"),
  )
  ```
- `:953` `cw.visitSource(sourceName, null)`。
- `@Retention(RUNTIME)`（**硬编码，rsf 源码写不出**）`:955-958`：
  ```
  val retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true)
  retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME")
  retention.visitEnd()
  ```
  位置：在 `cw.visitSource` 之后、元素方法之前、`cw.visitEnd()`（`:978`）之前。
- 元素方法 `:961-973`：`cw.visitMethod(ACC_PUBLIC or ACC_ABSTRACT, element.name, elementDescriptor(element), null, null)`；有默认值时 `:969` `mv.visitAnnotationDefault().visit(null, value)`（**名字槽是 `null`**，`:968` 注释）；末 `:972` `mv.visitEnd()`。
- `:983-984` `elementDescriptor` = `"()" + descriptorOfType(element.type)`。
- `:976` 注解声明自身的注解使用点 → `:977` `emitAnnotationAccessors(cw, spec.name)` → `:978` `cw.visitEnd()`。
- 常量：`ACC_INTERFACE` `:3238`、`ACC_ABSTRACT` `:3239`、`ACC_ANNOTATION` `:3245`（`0x2601` 注释在 `:3243`）、`ACC_SYNCHRONIZED` `:3246`、`V17` `:3249`。

### A2 伴生实体类（无专用发射函数，全走普通类路径）
- JVM 名：由 M2 决定（`Marker$Impl` / `Marker$companion`），codegen 只当它是个普通类；`companionDescriptor` `:986-988` `"L${classInternalName(companionName)};"`。
- 类头：`userClass` `:646-649` `cw.visit(V17, ACC_PUBLIC or ACC_SUPER, internalName, null, "java/lang/Object", superInterfaceNames(decl.name))`；父接口数组来自 `:698-699` `model.superInterfacesOf(name)`（Checker 算好，后端不解析 AST）。
- 元素字段：走普通字段循环 `:656-662`（`cw.visitField(ACC_PUBLIC, f.name, descriptorOfType(type), null, null)` + `emitUseSites(fv…)` + `fv.visitEnd()`）；`<init>` 走 `:666-672` → `emitUserConstructor`。
- 伴生类**额外三样**在 `:679-686`：`emitAnnotationCaches(cw, decl.name)`（`:684`）、`model.companionAnnotationOf(decl.name)?.let { emitAnnotationType(cw, it.name) }`（`:685`）；判据 `:683` `if (model.isCompanionClass(decl.name))`。
- `annotationType()` `:1307-1314`（形态 = `ACC_PUBLIC`、`()Ljava/lang/Class;`、`visitLdcInsn(Type.getObjectType(内部名))` + `ARETURN` + `visitMaxs(0,0)`）：`cw.visitMethod(ACC_PUBLIC, "annotationType", "()Ljava/lang/Class;", null, null)`。
- 伴生实例构造的实参表来自 `:1276` `model.paramTypesOf(companion.constructor)`，`<init>` 描述符拼接 `:1292-1295` `"(" + params.joinToString("") { descriptorOfType(it) } + ")V"`。

### A3 使用点：`RuntimeVisibleAnnotations`
- 唯一 helper `emitUseSites(av, node)` `:1016-1036`；配对表 `useSitesOn(node)` `:996-1005`（判定用**同一性 `===`**），只读 `model.annotationUses`（`:1014` 注释：后端不再拿 AST 对一遍）。
- 逐注解：`:1018` `av("L${classInternalName(site.annotation.name)};", true)`（**第二参 `visible=true`**）；实参 `:1019` `explicitValues(site)`；每元素 `:1021` `explicit[index] ?: element.decl.default ?: continue`；`:1024` `if (value is NullLit) continue`（类文件没有 null 注解值编码）；`:1025-1029` 枚举 → `visitor.visitEnum(element.name, enumDescriptorOf(enumRef.enumName), enumRef.valueName)`；`:1030-1032` 其余 → `visitor.visit(element.name, constant)`；`:1034` `visitor.visitEnd()`。
- 实参编码 `explicitValues` `:1045-1058`：位置式 `PositionalAnnotationArg` 按**实参下标**对元素下标（`:1050`）；具名 `NamedAnnotationArg` 按名字 `indexOfFirst`（`:1052-1053`）；剩下位置留 null，调用方退默认值。
- 常量编码 `annotationConstant(type, expr)` `:1069-1106`：字面量 Int/Long/Float/Double/String/Char/Bool → 原值；`UnaryExpr` 只认 `MINUS`（`:1080`，操作数须是数值字面量 `:1081-1087`）；装箱**按元素声明类型**（`:1091-1105` `BOOL/BYTE/CHAR/INT/LONG/FLOAT/DOUBLE/STRING`）；`NullLit` 返回 null `:1078`；其它 `throw badAnnotationValue`（`:1089`/`:1104`）。**不支持类型（数组 / 注解类型 / 非常量）的报错**：`:1108-1114` `RsfError("codegen", "注解元素的值必须是编译期常量（字面量、取负的字面量或枚举常量）：'…' 这里拿不到常量。数组/注解类型的元素 v1 只能用声明里的默认值（rsf 还没有数组字面量）。", expr.at.line, expr.at.column, null)`。
- 六个调用点（**都写在对应 `visitEnd()` 之前**）：
  - `:653` 类级 `emitUseSites({ d, v -> cw.visitAnnotation(d, v) }, decl)`，紧接 `:650` `cw.visitSource`；
  - `:660` 字段级 `fv.visitAnnotation`，`:661` 才是 `fv.visitEnd()`；
  - `:903` 接口抽象方法 `mv.visitAnnotation`，`:904` 才是 `mv.visitEnd()`（`:902` 注释「写在 mv.visitEnd() 之前」）；
  - `:976` 注解声明自身，`:978` `cw.visitEnd()`；
  - `:1483` `emitUserMethod` 里 `mv.visitAnnotation`，`:1487` `mv.visitEnd()`；
  - `:1678` `emitFunction`（顶层函数），`:1683` `mv.visitEnd()`；`:1676-1677` 注释：顶层函数 hostName=null，但标注照样写。
- `:1011-1012` 注释原文：「三处都必须在对应的 `visitEnd()` **之前**调，之后写进去的会被 ASM 直接丢掉（DESIGN-21 §7）」。

### A4 方案 B 访问器懒构造（`BytecodeCompiler.kt:1157-1296`）
- 缓存字段挂**伴生类**上，`emitAnnotationCaches` `:1164-1170`：`cw.visitField(ACC_STATIC, cacheFieldName(accessor), companionDescriptor(companionName), null, null).visitEnd()`。
- 字段名 `:1172-1173` `"__rsf_ann\$${accessor.hostName}\$${accessor.memberName ?: ""}"`（运行期字符串 = `__rsf_ann$宿主$成员`）；`:1160-1162` 注释：rsf 标识符写不出 `$`，不可能撞名。
- 分发 `emitAnnotationAccessors(cw, hostName)` `:1187-1206`：`:1189` `model.accessorsOn(hostName)`，按 `注解名 + (#type|#member)` 分组 `:1192-1196`。
- 类型级 `emitTypeLevelAccessor` `:1208-1228`：`visitMethod(ACC_PUBLIC or ACC_STATIC or ACC_SYNCHRONIZED, accessor.annotation.name, "()$desc", null, null)`，体为 `GETSTATIC 缓存 → ASTORE 0 → ALOAD 0 → IFNONNULL ready → emitAccessorNew → DUP → PUTSTATIC → ASTORE 0 → label ready → ALOAD 0 → ARETURN`。
- 成员级 `emitMemberLevelAccessor` `:1230-1263`：描述符 `"(Ljava/lang/String;)$desc"`；逐 accessor `ALOAD 0 → LDC 成员名 → INVOKEVIRTUAL String.equals → IFEQ next → …`；`member` 对不上 `:1259-1260` `ACONST_NULL` + `ARETURN`。
- `emitAccessorNew` `:1269-1296`：`NEW 伴生类 → DUP →` 逐元素 `pushAnnotationValue`（`:1283` `explicit[index] ?: element.decl.default ?: throw`）`→ INVOKESPECIAL <init>`。
- `pushAnnotationValue` `:1123-1155`：枚举 → `GETSTATIC`（`:1125-1128`）；数组元素直接报错 `:1131-1138`；null 报错 `:1139-1143`；基本类型指令 `:1144-1153`（`ICONST_0/1`、`pushInt`、`visitLdcInsn`）。
- 访问标志**不走** `accessOf`（`:1184-1185` 注释），直接发 `ACC_PUBLIC|ACC_STATIC|ACC_SYNCHRONIZED`。

## B. Kotlin 解释器侧（`src/main/kotlin/org/example/complie/interp/Interpreter.kt`）

### B1 分流条件（抄原文）
- `evalMember:554`，注解那条在 `:589-594`：
  ```
  if (member.interfaceOwner && !method.isStatic) {
      val self = eval(expr.receiver, slots, body) as RsfObject
      // 阶段 21：静态类型是**注解接口**（`Marker`）——伴生对象的元素访问器
      // 走这条路，不能交给 callInterfaceMethod（注解接口不在 interfaces 表里）
      annotationElementCall(self, method.name, args)?.let { return it }
      return callInterfaceMethod(self, method, args)
  }
  ```
- `evalCall:1314`，注解那条在 `:1339-1348`（`model.classModel.implicitCalls[expr]` 分支内）：`:1346` `annotationElementCall(self, method.name, args)?.let { return it }`，`:1347` 才是 `callInterfaceMethod`。
- `annotationElementCall` `:1132-1136` 全文：
  ```
  private fun annotationElementCall(self: RsfObject, member: String, args: List<Any?>): Any? {
      val annotation = model.companionAnnotationOf(self.className) ?: return null
      val accessor = model.elementAccessorOf(annotation.name, member) ?: return null
      return callMethod(self.className, accessor, self, args)
  }
  ```
  `:1124-1128` 注释：注解接口**不进 `interfaces` 表**，元素访问器是**非虚**的，查到就当普通实例方法调。

### B2 `fillElementDefaults:1087-1106`
- 做什么：把**省略掉的实参**对应的构造器形参槽填成元素默认值。`:1097-1105` 逐元素：取形参槽 `:1098`，已给实参且槽非空就跳过 `:1099`，否则 `frame[slot] = coerce(eval(field.initializer, frame, owner), fieldTypes…)`（`:1101-1104`，`field.initializer` 对元素字段就是元素默认值，见 `:1084-1085` 注释）。
- 何时填：`:1035-1038`，在**链根构造器**、`runFieldInitializers` **之前**（`:1039`）、`execute` 之前；条件是 `model.companionAnnotationOf(decl.name) != null`。
- 配套：`companionElementCount` `:1117-1118` = `model.companionAnnotationOf(decl.name)?.companion?.elementFields?.size ?: 0`；`runFieldInitializers` `:1051-1075`（`:1066-1067` 前 n 个元素字段取形参槽，其余才 `eval(initializer)`）。
- `:1028` 构造器允许少给尾部实参（`if (i >= args.size) break`）。

### B3 注解对象在解释器里的形态
- 就是一个 `RsfObject`（`:667` 起），`className` = **伴生类类名**；元素字段是它的前 n 个字段，行为方法（伴生体里写的 `describe` 之类）就是伴生类的普通实例方法。
- 创建：`new Marker("hi")` 走伴生类的构造器（`:1020-1045`），**没有** `run()` 之类的触发点；伴生体代码在方法被调用时由 `callMethod`/`call(target,…)` 正常执行。

## C. 自举侧落点

### C1 `rsfSrc/bootstrap/Backend.rsf`
- `collectClasses` `:5145-5232`（两遍：`:5166-5193` 登记名字/类型参数，`:5194-5231` 填字段与方法）；`ANNOTATION_USES` 放行分支 `:5222-5223`（原文注释「注解语义在阶段 21 由 Kotlin 侧实现，自举后端本刀忽略」）；未知成员 `:5225` `g.bad("类成员 " + kindName(part.kind))`。接口侧同样放行 `:5764-5765`，`g.bad("接口成员 …")` `:5767`。
- `CInfo` `:1955-1972`：`name` / `internal` / `fields: CField[]`+`fieldCount` / `meths: CMeth[]`+`methCount` / `node: Node` / `typeParams: string[]`+`typeParamCount` / `isIface` / `supers: string[]`+`scount`；`addSuper` `:1974-1992`，接口方法查找 `:1994` 起。
- 发射函数：`emitUserClass` `:5496-5597`、`emitEnumClass` `:5599-5649`、`emitUserInterface` `:5784-5809`、`compileProgram` `:5812-5912`。
- 类头三处模板：
  - 普通类 `:5499` `g.cw.visit(v17(), accPublic() + accSuper(), ci.internal, null, "java/lang/Object", superArr(ci))`
  - 枚举 `:5605` `g.cw.visit(v17(), accPublic() + 16 + accSuper(), ei.internal, null, "java/lang/Object", null)`
  - 程序类 `:5875` `g.cw.visit(v17(), accPublic() + 16 + accSuper(), g.owner, null, "java/lang/Object", null)`
  - 接口 `:5790` `g.cw.visit(v17(), accPublic() + accInterface() + accAbstract(), ci.internal, null, "java/lang/Object", superArr(ci))`
- `visitEnd()`：`:5592`（类）/ `:5649`（枚举）/ `:5805`（接口）/ `:5889`（程序类）。
- 访问标志 helper：`accPublic` `:68`（1）/ `accPrivate` `:69`（2）/ `accProtected` `:70`（4）/ `accStatic` `:71`（8）/ `accSuper` `:72`（**32**）/ `v17` `:73`（61）/ `computeAll` `:74`（3）/ `accInterface` `:76`（512）/ `accAbstract` `:77`（1024）。**没有 `ACC_ANNOTATION`（0x2000）也没有 `ACC_SYNCHRONIZED`（0x20，与 `accSuper` 同值）**。
- ASM 字段名：`Gen.cw : ClassWriter` `:658`，`cws: ClassWriter[]` `:660` / `cwNames` `:661` / `cwCount` `:662`，`newWriter` `:735-752`。
- **ASM 互操作白名单** `asmTable()` `:2266-2332`（`var t : AsmMem[] = new AsmMem[23]` `:2267`）：`ClassWriter` 6 条 `:2270-2285`（`toByteArray` / `visit`(6) / `visitEnd` / `visitField`(5, 返回 `FieldVisitor`) / `visitMethod`(5, 返回 `MethodVisitor`) / `visitSource`）；`MethodVisitor` 16 条 `:2287-2325`；`FieldVisitor` 只有 `visitEnd` `:2327-2329`。查表 `asmLookup` `:2336` 起（按 owner+name+argc 精确匹配，撞车即报错），调用侧 `:2394` `rtn asmLookup(g, recv.name, nm, argc)`。
- **确认：全表没有任何 `visitAnnotation` / `visitAnnotationDefault` / `visitEnum` / `AnnotationVisitor` 行**（`visitAnnotation` 在整个 `rsfSrc/` 里 0 命中）。
- `compileProgram` 分桶 `:5833-5847` 只认 `PKG/FUNCS/CLASSES/ENUMS/INTERFACES`，**没有 `ANNOTATIONS` 分支**；发射顺序 `:5894-5911` = classes → interfaces → enums（注释 `:5891-5893` 对齐 Kotlin 的 program→classes→interfaces→enums）。

### C2 `rsfSrc/bootstrap/Interp.rsf`
- `Fn` `:495-506`（`name/pnames/ptypes/pcount/body/retType/mayError/isCtor/isStatic/isPrimary`）。
- `Cls` `:508-522`（`name/fnames/ftypes/finits/fcount/ctors/ccount/methods/mcount/isIface/supers/scount`）；`addSuper` `:524-533`、`defaultNamed` `:536-544`、`anyNamed` `:547-555`、`fieldIndex` `:557-564`、`methodNamed` `:566` 起。
- `load()` `:777-788`：只认 `FUNCS`/`CLASSES`/`ENUMS`/`INTERFACES` —— **没有 `ANNOTATIONS` 分支**。`loadClasses` `:824-842`（`:832-834` 只认 `FIELDS`/`METHODS`/`SUPERS`）；`loadIfaces` `:846-864`；`loadEnums` `:914` 起；`loadSupers` `:869-876`；`loadFields` `:878-896`。
- `invokeCall` `:1855-1894`（`:1869-1889` 隐式 this / 接口默认方法链；无注解分支）；`evMember` `:1898` 起；`objMember` `:2035-2050`（`:2038-2039` `c.methodNamed(nm)` → `callFn`；`:2043` `ifaceDefaultScan`）；`isErrorCapable` `:2073-2085+`（`:2081-2083` 查 `cur.methodNamed(nm).mayError`）。
- 构造/默认值：`runCtor` `:1699-1719`（`:1704-1709` 形参缺省填 `vNull()`！，`:1710` `if (!hasThisCall(f.body)) { runFieldInits(c, o, env)!! }`）、`callFn` `:1723-1735`、`runFieldInits` `:1690` 起。
- **确认：`Interp.rsf` 里注解相关的东西为零**（全文件 0 条 `ANNOTATION` 命中）。

### C3 `rsfSrc/bootstrap/Sources.rsf`
- `appendAnns(list)` `:297-308`（注释 `:297` 「阶段 21：照 appendIfcs 的样子收注解声明（`ANNOTATIONS` 桶）」）。
- 装载调用点 `:517-518` `appendAnns(srcChildOf(ast, NodeKind.ANNOTATIONS))`（紧跟 `:516` `appendIfcs(...)`）。
- `build()` `:549-603`：注解桶 `:593-600` 排在 `INTERFACES`（`:585-591`）之后，`program.add(an.countTag())` `:600`；`:547-548` 注释写明孩子顺序 `PKG? / IMPORTS / FUNCS / CLASSES / ENUMS / INTERFACES / ANNOTATIONS`。

## D. 风险与未知

### D1 拆刀判断：**必须拆两刀**（后端一刀 / 解释器一刀）
- 验收列本来就分开：后端对拍走 `self` 列（逐字节），解释器走 `rsf-interp` 列（stdout），见 `build/verify21/bootbackend/README.md:31-34`；一列变绿不需要另一列。
- 代码量不对称：后端要动 6 块（`asmTable` 加行、`compileProgram` 接 `ANNOTATIONS` 桶、注解接口发射、**伴生类发射**、访问器+缓存、使用点写 `RuntimeVisibleAnnotations` + 常量编码），对应 Kotlin `BytecodeCompiler.kt:920-1318` 约 400 行；解释器只需 `load()` 加分支 + 元素访问器分派 + 默认值回填，对应 Kotlin `Interpreter.kt` 约 60-80 行。
- 后端这一刀本身还可再切（如 4a=注解接口+伴生类类文件，4b=访问器+使用点），但与解释器合并会把两套判据搅在一起。

### D2 需要拍板（逐条）
1. **伴生类从哪来**：Kotlin 靠 M2 把 `COMPANION` 包成普通 `ClassDecl` 塞进 `model.classModel.classes`（`BytecodeCompiler.kt:185-186`）；自举链**没有 sema 层**（`rsfSrc/bootstrap/` 只有 Lexer/Parser/Sources/Backend/Interp + Cli，无 Checker），AST 里 `COMPANION` 是 `ANNOTATION` 的孩子（`rsfSrc/bootstrap/Parser.rsf:1215-1223`）。第 4 刀是自己写一条 COMPANION→`.class` 的发射路，还是先在 Sources/Parser 侧把它摊进 `CLASSES` 桶？（决定工单形态）
2. **语义表缺席**：Kotlin 后端读的是 `model.annotationUses` / `model.annotationAccessors` / `companionAnnotationOf` / `elementAccessorOf` / `enumValueOf` / `paramTypesOf`（`BytecodeCompiler.kt:996-1005`、`:1189`、`:685`、`:1276`），自举后端只有 AST。是后端自己按 AST 重算（与 R1「同一判定不做两遍」冲突），还是补一个 rsf 侧语义层？
3. **`$` 与标识符**：`Marker$Impl`、`__rsf_ann$宿主$成员` 在 rsf 源码里只能当**字符串字面量**写。需实测 rsf 的 Lexer/Parser 对这些 `$` 是否原样保留（Kotlin 侧靠 `$` 不可用作标识符来保证不撞名，见 `BytecodeCompiler.kt:1160-1162`）。
4. **`annotationType()` 的 LDC class 常量**：Kotlin 用 `Type.getObjectType(...)`（`BytecodeCompiler.kt:1310`），`Type` 的静态方法/构造器**不在** `asmTable`（三条构造器在 `jdkNewRef`，`Backend.rsf:2264-2265` 注释）。要不要给 `Type.getObjectType` 加互操作行？
5. **`@Retention(RUNTIME)`** 在 Kotlin 侧是硬编码发的（`BytecodeCompiler.kt:955-958`，rsf 源码无对应语法），自举侧同样硬编码——确认这一条不进语言层。
6. **解释器刀的探针范围**：是否包含「伴生体行为方法读元素字段」和「省略实参取默认值」两类？若含，`Interp.rsf:1710` 之前必须补 `fillElementDefaults` 的等价物（Kotlin `Interpreter.kt:1035-1038`），且 `runCtor:1704-1709` 的缺省 `vNull()` **不能**当默认值。
7. **报错消息是否要对拍**：Kotlin 的两条注解报错（`BytecodeCompiler.kt:1108-1114`、`:1131-1143`）是否要逐字复刻进 `Backend.rsf`（负例对拍）。

### D3 Kotlin 有、rsf 结构上做不到／要先扩的地方
- ASM 互操作是**白名单**（`Backend.rsf:2266-2332`，23 条）：`visitAnnotation` / `visitAnnotationDefault` / `visitEnum` 与 `AnnotationVisitor` 类型**一行都没有**，且表长写死 `new AsmMem[23]`（`:2267`）。加行时必须同时改长度常量，否则 `t[i]` 越界。
- 链式调用先例只有 `g.cw.visitField(...).visitEnd()`（`:5504`、`:5582`）：`visitAnnotation(...)` 返回 `AnnotationVisitor` 后还要 `.visitEnum(...)`/`.visit(...)`/`.visitEnd()` 三级链，rsf 侧要能表达「接收者是上一句返回值」且**返回类型会变**（`ClassWriter.visitAnnotation`→`AnnotationVisitor`，`MethodVisitor.visitAnnotation`→`AnnotationVisitor`，三处 owner 不同、同名同 argc=2）。这是表结构本身的压力点。
- `visitAnnotationDefault()` 是**无参**调用且返回 `AnnotationVisitor`，`ClassWriter.visitField` 返回 `FieldVisitor` —— `FieldVisitor` 目前只有 `visitEnd` 一条成员（`:2327-2329`），字段级注解要给它加 `visitAnnotation`。
- Kotlin 的 `explicitValues`/元素默认值依赖「注解声明 → 元素表」的语义对象；自举侧要从 `ANNOTATION_ELEMENTS` 子节点现算（`Parser.rsf:1188-1191`、`:1230-1272` 的元素节点形状），实现上可行但属于新代码。
- 注解接口**不进 `interfaces` 表**（`Interpreter.kt:1124-1128`），自举解释器同样不能沿接口链找元素访问器；`Cls.anyNamed/defaultNamed`（`Interp.rsf:536-555`）都不会命中它。
