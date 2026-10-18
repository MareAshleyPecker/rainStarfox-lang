# 阶段 24c 工单：自举后端认 `as` / `?as` / `is` / `!is`（顺手补 `__rsf_cast`）

## 目标

`rsfSrc/bootstrap/Backend.rsf` 能编译阶段 24 的四种写法，产出的字节码与 Kotlin 后端
（`src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt:2475-2490` +
`:2927-3060`）**逐字节一致**。现在自举侧报的是 `17d 后端暂不支持：表达式 AS` / `IS`。

## 允许改的文件

- `rsfSrc/bootstrap/Backend.rsf`（**只许改这一个**）

禁止：`src/**`（Kotlin 侧已定稿）、其它 `rsfSrc/**`、`build/verify24/**`、`build/verify17e|21|25|27/**`。

## 一、类型遍历：`walkExpr`（`Backend.rsf:3538`，分派链 `:3543-4029`）

在 `else { g.bad("表达式 " + kindName(k)) }`（`:4026`）**之前**加两支：

- `NodeKind.AS`：`walkExpr(children[0])`；目标类型 = `typeOfText(g, prop(children[1], "type"))`
  （`children[1]` 是 `NodeKind.TARGET`，**不要**单独走 `walkExpr`）；解析失败就 `rtn tVoid()`；
  `propBool(e, "safe")` 为真时结果 `withNullable(true)`，否则非空；`t = 目标`。
- `NodeKind.IS`：`walkExpr(children[0])`；目标同上；`t = tBool()`。

走完照旧 `g.putType(e, t); rtn t`（`:4031`）。

## 二、发码：`emitExpr`（`:5341`，分派链里插在 `FORCE` / `INDEX` 附近）

先算 `var target = typeOfText(g, prop(e.children[1], "type"))`，`null` 就
`g.setFailed(...)` 后 `rtn`（这类错误在 walkExpr 阶段已经拦过，这里是兜底）。

- `IS`：
  ```
  emitExpr(operand)
  mv.visitTypeInsn(193, internalNameOf(target))        // INSTANCEOF
  若 propBool(e, "negated")：mv.visitInsn(4); mv.visitInsn(130)   // ICONST_1; IXOR
  ```
  `INSTANCEOF` 对 null 回 0 ⇒ `null is A` 恒 false，与 Kotlin 一致（`:2475-2483`）。

- `AS`（**非** safe，`:2927-2937`）：
  ```
  emitExpr(operand)
  mv.visitLdcInsn(Type.getObjectType(internalNameOf(target)))      // 类常量
  mv.visitLdcInsn(runtimeNameOf(target))                            // 报错用的 JVM 全名
  mv.visitMethodInsn(184, g.owner, "__rsf_cast",
      "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false)
  mv.visitTypeInsn(192, internalNameOf(target))                    // CHECKCAST
  ```

- `AS`（safe，`:2960-2976`）：
  ```
  emitExpr(operand); DUP(89); INSTANCEOF(193, internal); IFEQ(153, lNull)
  CHECKCAST(192, internal); GOTO(167, lEnd)
  lNull: POP(87); ACONST_NULL(1)
  lEnd:
  ```

`internalNameOf` 已有（`:4493`，数组直接给描述符 `[LB;`，与 Kotlin 的
`castTargetInternal` 同口径）；`Type.getObjectType` 的用法见 `:7591`（注解那条路已经在用）。

## 三、新函数 `runtimeNameOf(t : BType) -> string`（镜像 Kotlin `SemanticModel.jvmRuntimeNameOf`）

`Class.getName()` 的写法，必须与 Kotlin 一字不差：

- 数组：`"[" + elem`，`elem` 递归 —— 数组再套 `"["`；基本类型用 `desc()`（`I`、`Z`…）；
  引用类型写 `"L" + 类名 + ";"`（类名是点分：`LA;` → `LA;` 里的 `A` 就点分形式）。
  例：`B[]` → `[LB;`、`int[][]` → `[[I`、`A[]` → `[LA;`。
- 非数组：`base == "external"` → `t.name.replace('/','.')`（`demo/B` → `demo.B`、`java/lang/String`
  → `java.lang.String`、`Marker$Impl` 保持 `$`）；否则 `desc()` 去壳：`Ljava/lang/String;` →
  `java.lang.String`、`I` → `I`。擦除后的类型参数是 `java/lang/Object` → `java.lang.Object`。

判断基本类型用现成的 `isPrimitiveBase` 一类工具（Kotlin 的 `element.isPrimitive`）。

## 四、新函数 `emitCastHelper(g)`（仿 `emitParseIntHelper`，`:4542`）

签名 `public static __rsf_cast(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;`，
方法体**逐条对齐** Kotlin `:3010-3060`：

```
if (v == null)             throw new RuntimeException("rsf: 不能把 null 当成 " + name);
if (!target.isInstance(v)) throw new RuntimeException("rsf: 不能把 "
                                    + v.getClass().getName() + " 当成 " + name);
return v;
```

- 用现成的 `eRuntimeExceptionHeader(mv)` / `eConcat(mv)` / `eRuntimeExceptionThrow(mv)`（`:4557-4563`
  就是同一个套路：header → LDC 前缀 → ALOAD → concat → LDC 尾巴 → concat）。
- 不发 LVT 条目（Kotlin 侧这个辅助方法没有 LocalVariableTable）。
- 调用点：`build` 里 `:7840-7843` 那段，在 `emitArrayHelpers(g)` **之后**、`emitOrElseRange(g,…)`
  **之前**插 `emitCastHelper(g)` —— 方法顺序影响 class 字节，必须与 Kotlin `:352-361`
  （parseInt → file → array → **cast** → orElse）完全一致。

## 五、判据（交付前自己跑一遍，父代理会独立复验）

1. `bash build/verify24/run-probes.sh all` → 「失败项合计: 0」（9 正例的 self / rsf 两列现在
   还报 `17d 后端暂不支持`，改完必须全绿）。
2. `bash build/verify21/knife-verify.sh stage24c` → 「OK：三条全绿」（gradle / J1 = 148 & diff 0 /
   verify2 失败项 0）。
3. `bash build/verify21/bootbackend/run-probes.sh both` → 失败项 0。
4. `bash build/verify27/run-probes.sh all` → 不过的段数 = 0（parity 那段的入口类豁免由父代理收）。

## 六、已知坑

- `emitExpr` 一进来会取走并清空 `g.discardExpr`（`:5345-5346`），自己发的操作数**不要**手动清。
- `visitLdcInsn` 的形参声明是 `Object`（`:2650`），传 `Type` / `String` 都行，别传内部名字符串当类常量。
- `walkExpr` 里 `putType` 必须对**每个**表达式节点各调一次（`tOf` 按节点身份查表）。
- `?as` 的结果类型是 `?目标`，`as` 是非空；`is`/`!is` 恒 `bool`。
- 泛型目标（`Box&<string>`）按擦除：`internalNameOf` 给的已经是 `Box`（`typeOfText` 的 `extraArgs`
  只用于调用点收敛，不参与 `desc()`）。
