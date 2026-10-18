# 工单：阶段 31 第一刀 31a（Kotlin 前端 + 语义）

## 目标
让 Kotlin 前端认「泛型函数」，并把**调用点推导 / 冲突 / 推不出来 / 三类拒收**全部做实。本刀**不发码**：两个后端遇到泛型函数一律抛
`RsfError("ir", "[rsf-17d] 后端暂不支持：泛型函数（阶段 31b）")`，`golden` / `run` 两段允许红，31b 接。

## 允许改的文件
- `src/main/kotlin/org/example/complie/ast/Ast.kt`（`FuncDecl` 加 `typeParams: List<String> = emptyList()`）
- `src/main/kotlin/org/example/complie/parsing/Parser.kt`
- `src/main/kotlin/org/example/complie/sema/Checker.kt`
- `src/main/kotlin/org/example/complie/sema/SemanticModel.kt`（只加「调用点类型实参」的记录，供 31b 用）
- `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt`、`interp/Interpreter.kt`（只加 17d 报错）
- `src/test/kotlin/org/example/AstDump.kt`（`FUNC` 下非空才发 `TYPE_PARAMS` / `TYPE_PARAM name=…`，与 class 同形）
- 新增 `src/test/kotlin/org/example/GenericFnTest.kt`

## 禁止
- 改 `build/**` 任何判据台与语料；改 `rsfSrc/**`；放宽既有测试断言；`gradlew clean`（只允许 `cleanTest`）。

## 规格
1. 语法：`fn 名字&<T, U>(参数) -> 返回 { … }`；`&<` 在名字之后、`(` 之前。空表 `&<>`、重复名各报一条（见 §文案）。
2. 构造器写 `&<` 直接拒；泛型方法写 `override` / `abstract` 直接拒。
3. 检查：`checkFunction` 进入时把函数级类型参数并入 `typeParams` 环境（**函数级遮蔽类级**），退出还原；函数体里 `T` 按现有 `RsfType.typeParam` 的不透明语义走。
4. 调用点绑定（只看实参，按声明顺序）：
   - `T` ↔ 实参类型；`T[]` ↔ 数组（逐层）；`?T` ↔ `?X`；`Box&<T>` ↔ `Box&<int>`（同基名、实参逐个）。
   - 同一 `T` 两次绑成不同类型 → 冲突（文案带第几个实参）。
   - 全部实参走完仍有未绑定的 `T` → 「推不出来」。
5. 重载：**非泛型候选优先**——先按今天的方式在所有非泛型候选里挑；一个都没接住时才让泛型候选参与（泛型候选之间同分仍按今天的并列报错）。
6. 返回类型：把绑定结果代进 `decl.ret` 得到调用表达式的类型；同时把 `Map<String, RsfType>` 记进 `SemanticModel`（键 = `CallExpr`），31b 的字节码后端要按它插 cast。
7. 17d：`BytecodeCompiler` 编译任何 `typeParams` 非空的 `FuncDecl`、或调用目标带 `typeParams` 时抛 17d；`Interpreter` 在注册/调用时同样抛。

## 逐字文案（会逐字比对）
```
泛型函数的类型参数表是空的：写 '&<T>'，或干脆删掉 '&<>'
类型参数 'T' 写了两遍
构造器不能是泛型的：类型参数写在类上（class Box&<T>）
泛型方法不能写 'override'（v1 不支持泛型覆盖，DESIGN-31）
泛型方法不能写 'abstract'（v1 不支持泛型抽象方法，DESIGN-31）
类型参数 'T' 推不出来：它只出现在返回类型里，让它出现在某个参数类型里（例如 'fn f(x : T) -> T'）
类型参数 'T' 同时被要求是 'int' 和 'string'（来自第 1 个和第 2 个实参）
```

## 判据（父代理独立复跑；你只需保证交付时树可编译、测试全绿）
1. `bash build/verify31/run-probes.sh check` → 失败项 0（6 正例全过）
2. `bash build/verify31/run-probes.sh msg` → 失败项 0（5 负例文案）
3. `./gradlew.bat cleanTest test` → BUILD SUCCESSFUL，0 failed（基线 581，新增测试另计）
4. `bash build/verify21/knife-verify.sh stage31a` → 三绿
5. 回归：`build/verify24/{run-probes.sh all,ast-verify.sh}`、`build/verify27/{run-probes.sh all,ast-verify.sh}`、`build/verify28/run-probes.sh all`、`build/verify29/run-probes.sh all`、`build/verify30/run-probes.sh all`、`build/verify21/bootbackend/run-probes.sh both` 全 0
6. `golden` / `run` 两段在 31a **允许红**（17d），31b 再转绿

## 提示（已勘实，省你找路）
- `Parser.kt:965 parseTypeParamList(nameTok, kindWord, declWord)` 就是 class 用的那份，直接复用。
- `Parser.kt:1649-1659` 是现在拒收泛型函数的那段，删掉它、换成解析。
- `AstDump.kt:197-198` 是 class 的 `TYPE_PARAMS` 写法，FUNC 分支照抄（非空才发）。
- `Checker.kt:4571 checkFunction` 是函数级 env 的落点；`Checker.kt:6987/6994 substitutionFor` 是现成的替换表助手。
- `Checker.kt:3968 resolveOverload` 是重载入口；泛型候选的「非泛型优先」就在那里加。
- `Checker.kt:7005 acceptsValue` 是参数可接受性检查。
- `RsfType` 的泛型实参在 `typeArgs`；`isTypeParam` / `base` / `nullable` 三个属性够用。
