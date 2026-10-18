# 自举同步第 4a 刀 · 注解的**代码生成**同步（`Backend.rsf`）

日期：2026-10-16 · 父代理冻结稿。**冲突时以本文件为准。**

## 0. 目标与范围

把 Kotlin 后端 `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt:920-1318`（阶段 21 注解）
的行为同步进自举后端 `rsfSrc/bootstrap/Backend.rsf`，判据是**同一份 .rsf 源码两边编出的 `.class` 逐字节相同**。

本刀**只允许改一个文件**：`rsfSrc/bootstrap/Backend.rsf`。

- 不碰 `rsfSrc/bootstrap/Parser.rsf`（第 3 刀已交，注解 AST 已就位）、`Sources.rsf`（`appendAnns` + `ANNOTATIONS` 桶已就位，见 `Sources.rsf:297-308`/`:517-518`/`:593-600`）、`Interp.rsf`（**4b 刀**的事）。
- 不碰 `src/main/**`、`src/test/**`。
- **全程不跑 gradle**（避免与父代理的验收争构建目录）。

父代理已做完的探针台改造（你直接跑）：

```
bash build/verify21/bootbackend/run-probes.sh both     # 4 列口径见 build/verify21/bootbackend/README.md
bash build/verify21/bootbackend/j1-knife2.sh           # J1 不动点：126 类 / diff -r 0
```

**基线（4a 落盘前，2026-10-16 实测）**：`失败项合计: 17（其中 rsf-interp 列 8）`。
- `rsf-interp` 那 8 项 = a1–a4 的解释器列全红 → **4b 刀的事，本刀不管**（`run-probes.sh` 已把这一列单独计数）。
- 本刀要消掉的 9 项 = a1–a4 的 `self(build_exit=1 stdout=异)` + `类字节差异=4/5/7/5`（8 项）+ `an1_annot_nonconst` 的 `self_build_exit=0`（1 项，本刀必须让它变成非 0）。
- 接口探针 p1–p7 / n1–n3 现在全绿，**不许弄红**。

## 1. 七条拍板（勘查 `build/verify21/bootannot-backend-prep.md` D2 的答案）

1. **伴生类从哪来** → 后端**直接从 AST 的 `COMPANION` 孩子发射**，不改 Parser/Sources。AST 形状：`ANNOTATION` 的孩子里有 `COMPANION`（`Parser.rsf:1215-1223`）；没有 `COMPANION` 就是纯标注用的注解（只管使用点，不产生额外类）。
   - 注解接口内部名 = **简名（不带包）**；伴生类内部名 = **程序包 + 简名 + `$` + (companion 名字 ?: `companion`)**。
     这不是笔误，Kotlin 侧就是这么发的：`pkg app.meta` 下 `annotation Tag` 的接口落在 `out/Tag.class`，伴生落在 `out/app/meta/Tag$Impl.class`（已实测，见 §4 的 pkg 探针；Kotlin 侧对应 `AnnotationCodegenTest` 里 `classInternalName` 的注解特例注释 `BytecodeCompiler.kt:557-575`）。
2. **语义表缺席** → 后端**按 AST 现算**（自举链没有 Checker，这条不违反「同一判定不做两遍」）。要求：把「注解声明 → 元素表 / 使用点实参配对 / 元素默认值 / 常量编码」集中写在一节 helper 里，别散在各发射函数里。
3. **`$` 字符串字面量** → **原样保留**，已实测：`"Marker$Impl"`、`"__rsf_ann$Demo$tag"`、`"a$b"` 三家引擎都原样输出。缓存字段名照 Kotlin 的拼法写字符串即可。
4. **`Type.getObjectType`** → 加互操作行（3 行改动，用既有机制）：

   `Backend.rsf` 顶部 import 区加 `import org.objectweb.asm.Type`，并在 `jdkStatic`（`Backend.rsf:1532` 起）里加一支：

   ```
   if (owner == "org/objectweb/asm/Type") {
       if (nm == "getObjectType" && argc == 1) {
           rtn staticM(owner, "getObjectType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;", tExternal("org/objectweb/asm/Type"), memP1(tString()))
       }
       rtn null
   }
   ```
   （Kotlin 侧不用改：`org.objectweb.asm.Type` 在真实 classpath 上，`Type.getObjectType(x)` 本来就编得过。）
5. **`@Retention(RUNTIME)`** → 两侧都**硬编码**，不进语言层。
6. **解释器探针范围** → 归 4b：4b 的探针必须含「伴生体行为方法读元素字段」与「省略实参取默认值」两类，届时要补 `fillElementDefaults` 等价物（`Interp.rsf:1704-1709` 的缺省 `vNull()` **不能**当默认值）。本刀一行都不碰 `Interp.rsf`。
7. **报错文案** → **不要求逐字一致**（沿用 `RsfTest.kt:7169-7237` 的口径：非法输入只要求两边都失败）。但 `an1_annot_nonconst`（注解实参不是编译期常量）**自举后端必须 `build` 失败**（Kotlin 侧在 codegen 阶段报 `注解元素的值必须是编译期常量…`，`BytecodeCompiler.kt:1108-1114`）。

## 2. 冻结的接口细节（照抄 Kotlin，逐条对齐）

### 2.1 ASM 互操作白名单（`asmTable()` `Backend.rsf:2266` 起）

现有 `var t : AsmMem[] = new AsmMem[23]`，**必须同时改长度**（越界会 `t[i]` 炸）。新增 7 行 → **30**：

| owner | name | argc | desc | ret | params |
|---|---|---|---|---|---|
| `org/objectweb/asm/ClassWriter` | `visitAnnotation` | 2 | `(Ljava/lang/String;Z)Lorg/objectweb/asm/AnnotationVisitor;` | `tExternal("org/objectweb/asm/AnnotationVisitor")` | `memP2(tString(), tBool())` |
| `org/objectweb/asm/MethodVisitor` | `visitAnnotation` | 2 | 同上 | 同上 | `memP2(tString(), tBool())` |
| `org/objectweb/asm/MethodVisitor` | `visitAnnotationDefault` | 0 | `()Lorg/objectweb/asm/AnnotationVisitor;` | 同上 | `memP0()` |
| `org/objectweb/asm/FieldVisitor` | `visitAnnotation` | 2 | 同 ClassWriter 那条 | 同上 | `memP2(tString(), tBool())` |
| `org/objectweb/asm/AnnotationVisitor` | `visit` | 2 | `(Ljava/lang/String;Ljava/lang/Object;)V` | `tVoid()` | `memP2(tString(), tObject())` |
| `org/objectweb/asm/AnnotationVisitor` | `visitEnum` | 3 | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V` | `tVoid()` | `memP3(tString(), tString(), tString())`（没有 `memP3` 就照 `Handle` 那条自己拼数组） |
| `org/objectweb/asm/AnnotationVisitor` | `visitEnd` | 0 | `()V` | `tVoid()` | `memP0()` |

- `asmLookup` 按 **owner+name+argc 精确匹配**，所以 `ClassWriter.visitAnnotation` / `MethodVisitor.visitAnnotation` / `FieldVisitor.visitAnnotation` 三条同名不冲突（owner 不同）——这正是 `g.cw.visitAnnotation(...)` 与 `mv.visitAnnotation(...)` 能共存的原因。
- 调用链是**三级**：`g.cw.visitAnnotation(d, true).visitEnum(n, d2, v).visitEnd()`、`mv.visitAnnotationDefault().visit(null, 常量)`。rsf 侧「接收者是上一句返回值」的现有先例是 `g.cw.visitField(...).visitEnd()`（`Backend.rsf:5504`），用法照抄。
- `FieldVisitor` 现在只有 `visitEnd`（`Backend.rsf:2327-2329`），字段级注解要补 `visitAnnotation` 这一条。

### 2.2 注解接口 `.class`（对应 Kotlin `annotationClass` `:943-980`）

- writer：`newWriter(<简名>)`（**不带包**，见 §1.1）。
- 类头：`g.cw.visit(v17(), accPublic() + accInterface() + accAbstract() + accAnnotation(), 简名, null, "java/lang/Object", <父接口数组>)`。
  - **新增 `fun accAnnotation() -> int { rtn 8192 }`**（0x2000）。注意 `accSuper()` 的值是 **32**，那正好是 `ACC_SYNCHRONIZED`，两者别混用。
  - 父接口数组 = 恰好一个 `"java/lang/annotation/Annotation"`（Kotlin 是 `arrayOf("java/lang/annotation/Annotation")`）。自举侧造 `string[]`：照 `superArr(ci)`（`Backend.rsf` 里接口父类型数组那段）的写法自己拼一个长度 1 的数组。
- `g.cw.visitSource(<源文件名>, null)`：与用户类同一个 `sourceName` 来源，**别漏**（漏了 class 文件字节就不同）。
- 紧接着 `@Retention(RUNTIME)`（**位置固定：`visitSource` 之后、元素方法之前**）：
  `g.cw.visitAnnotation("Ljava/lang/annotation/Retention;", true).visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME").visitEnd()`
- 元素方法（按**声明顺序**逐条）：
  `g.cw.visitMethod(accPublic() + accAbstract(), <元素名>, "()" + <元素类型描述符>, null, null)`
  - 有默认值 → `mv.visitAnnotationDefault().visit(null, <常量>)`（**名字槽是 `null`**，Kotlin `:968` 有注释），然后 `mv.visitEnd()`。
  - 没有默认值 → 直接 `mv.visitEnd()`。
- 注解声明**自身**的注解使用点（元注解，如 `@Meta annotation Doc`）→ 写在本 writer 的 `visitEnd()` **之前**，**且访问器的同步位要去掉**（见 §2.4）。
- 最后 `g.cw.visitEnd()`。

### 2.3 伴生类 `.class`（Kotlin 走普通类路径，`BytecodeCompiler.kt:646-672` + `:679-686`）

- writer：`newWriter(<程序包>/<简名>$<suffix>)`；`suffix` = `COMPANION` 自己的名字，没写名字用 `companion`。
- 类头：`visit(v17(), accPublic() + accSuper(), <伴生内部名>, null, "java/lang/Object", <伴生父接口数组>)`——父接口来自 `COMPANION` 的 `SUPERS`（第 1 刀的形状；复用 `superArr` 那条路，但要喂 `COMPANION` 节点）。
- 元素字段：`g.cw.visitField(accPublic(), <元素名>, <元素类型描述符>, null, null)` → `emitUseSites` 等价物 → `visitEnd()`。
- `<init>`：与用户类同一个 `emitUserConstructor` 路径（元素字段按构造器形参赋值）。
- 伴生体里写的普通方法：走既有 `emitUserMethod`。
- 伴生类**额外两样**（Kotlin `:679-686`）：
  1. **访问器的懒构造缓存字段**（每个访问器一条，`accStatic()`，名字见 §2.4）；
  2. `annotationType()`：`visitMethod(accPublic(), "annotationType", "()Ljava/lang/Class;", null, null)`，体 = `LDC Type.getObjectType(<注解接口内部名>)` → `ARETURN` → `visitMaxs(0,0)`（`BytecodeCompiler.kt:1307-1314`）。`Type.getObjectType` 见 §1.4。
- `visitEnd()`（`BytecodeCompiler.kt:686` 的顺序：caches → annotationType → 访问器 → `cw.visitEnd()`）。

### 2.4 方案 B 访问器 + 缓存（Kotlin `:1157-1296`）

- 缓存字段挂**伴生类**：名字 = `"__rsf_ann$" + <宿主内部名> + "$" + <成员名或空串>`，类型 = 伴生类描述符 `"L<伴生内部名>;"`，flags = `accStatic()`（`BytecodeCompiler.kt:1164-1173`）。
- 访问器挂在**宿主**上（类 / 接口 / 注解声明 / 顶层函数？——看使用点宿主；顶层函数的 `hostName=null`，Kotlin `emitAnnotationAccessors` 直接 return，照抄）。
- 类型级：`visitMethod(<flags>, <注解名>, "()L<伴生内部名>;", null, null)`；成员级：描述符 `"(Ljava/lang/String;)L<伴生内部名>;"`，逐 accessor 比对成员名，对不上 `ACONST_NULL` + `ARETURN`。
- **flags 的同步位规则（2026-10-16 父代理刚在 Kotlin 侧修掉的真 bug，务必镜像）**：
  - 宿主是**类** → `accPublic() + accStatic() + 32`（32 = `ACC_SYNCHRONIZED`，可直接复用 `accSuper()` 的数值，**建议单独加 `accSync()` 以免误读**）；
  - 宿主是**接口 / 注解声明** → `accPublic() + accStatic()`，**绝不要带 32**。带了接口里的方法就非法，类文件在**加载期**报
    `java.lang.ClassFormatError: Method Marker in class Named has illegal modifiers: 0x29`（0x29 = public|static|synchronized）。
  - 探针 `a3_annot_positions` 的 `@Meta annotation Doc` 正好踩这条；`a5`/pkg 探针里接口宿主也踩。
- 方法体顺序（逐条同 Kotlin，`BytecodeCompiler.kt:1208-1263`）：
  类型级 `GETSTATIC 缓存 → ASTORE 0 → ALOAD 0 → IFNONNULL ready → NEW 伴生 → DUP → <逐元素 push> → INVOKESPECIAL <init> → DUP → PUTSTATIC 缓存 → ASTORE 0 → label ready → ALOAD 0 → ARETURN`；
  成员级外面先 `ALOAD 0 → LDC 成员名 → INVOKEVIRTUAL java/lang/String.equals(Ljava/lang/Object;)Z → IFEQ next`。
- 实参 push（Kotlin `pushAnnotationValue:1123-1155`）：显式实参 → 否则元素默认值 → 元素是枚举则 `GETSTATIC`，基本类型按 `ICONST_0/1` / `pushInt` / `LDC`，字符串 `LDC`；**数组元素 / null 在这里报错**（`an1` 那条走的是显式实参不是常量 → 必须在常量编码处报错）。

### 2.5 使用点 `RuntimeVisibleAnnotations`（Kotlin `emitUseSites:1016-1036`）

- 每个使用点：`av("L<注解接口内部名>;", true)`（**第二参 `visible=true`**）；实参配对：位置式按**实参下标**对元素下标，具名按名字 `indexOfFirst`；`explicit[index] ?: 元素默认值 ?: 跳过`；**`NullLit` 跳过**（类文件没有 null 值编码）；元素是枚举 → `av.visitEnum(元素名, "L<枚举内部名>;", 值名)`；其余 → `av.visit(元素名, <常量>)`；末 `av.visitEnd()`。
- 常量编码（`annotationConstant:1069-1106`）：Int/Long/Float/Double/String/Char/Bool 字面量；`UnaryExpr` 只认 `MINUS` 且操作数必须是数值字面量；**按元素声明类型装箱**（bool/byte/char/int/long/float/double/string）；其它（调用、变量、字段…）→ **报错**（文案不要求逐字）。
- **必须在对应 `visitEnd()` 之前调**（`BytecodeCompiler.kt:1011-1012` 注释：之后写进去的会被 ASM 丢掉）。六处：类级（`:653`，`visitSource` 之后）、字段级（`:660`）、接口抽象方法（`:903`）、注解声明自身（`:976`）、`emitUserMethod`（`:1483`）、顶层函数（`:1678`）。
- 注解接口**不进** `interfaces`/`cis` 表（自举侧别指望沿接口链找元素访问器；那是 4b 的事）。

### 2.6 编译入口

- `compileProgram`（`Backend.rsf:5812` 起）分桶 `:5833-5847` 现在只认 `PKG/FUNCS/CLASSES/ENUMS/INTERFACES`：**加 `ANNOTATIONS` 一支**。
- 发射顺序：程序类 → 类（伴生类在内）→ 接口 → 枚举 → **注解接口**（与 Kotlin `compile():114-198` 的顺序一致）。
  - 注意 `newWriter` 会把 `g.cw` 换成新 writer，**发完注解/伴生要保证程序类仍是 `cws[0]`**（`writeMain` 取的就是 `cws[0]`）；`compileProgram` 结尾的 `g.cwCount` 是 CLI 写文件的依据。
- 没有 `COMPANION` 的注解：只发注解接口，**不发**伴生类、不发缓存字段、不发访问器（Kotlin `:738-759` 的「没有伴生实体的注解只写使用点」）。

## 3. 验收（你必须自己跑，并把原始输出贴进报告）

1. `bash build/verify21/bootbackend/run-probes.sh both`
   - 目标：**`self` 列在 a1–a4 上全绿**（`build_exit=0 stdout=同 类字节差异=0`），`an1_annot_nonconst` 的 `self_build_exit` **非 0**；
   - `rsf-interp` 列继续红 8 项是**允许的**（4b 刀的事），报告里要照抄 `失败项合计` 那一行并说明拆分。
2. `bash build/verify21/bootbackend/j1-knife2.sh`
   - 目标：**J1 类总数=126、J3 `diff -r` 差异条目=0**（不动点：注解支持不许改变现有 9 个自举源 + 八示例的产物）。
3. pkg 探针（注解接口不带包 / 伴生带包）：

   ```
   cd build/verify21/bootbackend/pkgroot
   rm -rf outk outs
   java -jar ../../../build/libs/rainStarfox-0.9.0.jar build app/meta/pkgprobe.rsf -o outk
   java -Drsf.backend.file=app/meta/pkgprobe.rsf -Drsf.backend.out=outs \
        -cp "../A;../../../build/libs/rainStarfox-0.9.0.jar" BackendCli
   find outk -name '*.class' | sort   # 期望：outk/Tag.class + outk/app/meta/{Box,pkgprobe,Tag$Impl}.class
   diff -r -q outk outs               # 期望：0 条差异
   ```
4. **不许跑 gradle**（`./gradlew test`/`accept-after.sh`/`verify2.sh` 由父代理在两刀落地后统一跑）。

## 4. 交付物

- 只改 `rsfSrc/bootstrap/Backend.rsf`（若确实需要新 helper，加在该文件内）。
- 报告 `build/verify21/bootannot-knife4a-report.md`（≤60 行）：改了哪些块（带行号）、上面三条验收命令的原始输出、没做到的地方与原因。
- **不调用 git**（父代理统一提交）。

## 5. 已知坑（前人踩过，照抄结论）

- ASM 白名单表长写死：加行必须同时改 `new AsmMem[23]`。
- `visitLdcInsn` 的形参声明是 `tObject()`，基本类型由 `emitWiden` 走既有装箱路径——**别给 LDC 单独开一条路**。
- `visitAnnotation` 返回的 `AnnotationVisitor` 是外部对象，返回值链路与 `visitField` 返回 `FieldVisitor` 同构。
- 自举编译器编译**自己**的源码：你新加的 `jdkStatic` 行必须能让 `Backend.rsf` 自己被 A 编过（J1 就是干这个的）。
- Windows 上跑 java 打印中文报错要加 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`；MSYS 下 `;` 分隔的 classpath 必须写**相对路径**（写 `/d/...` 会 `NoClassDefFoundError: org/objectweb/asm/MethodVisitor`）。
