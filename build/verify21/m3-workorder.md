# 阶段 21 M3（代码生成）派工单草案

> 状态：草案，等 M2 落地并复验后再发出。发给子代理时把「背景」段压缩，只留可执行要求。

## 目标

`rsf build` 遇到注解时生成两样东西，且**没有注解的源一个字节不许变**：

1. 注解声明 `annotation Marker(value : string = "x", count : int = 0) { companion entity Impl : Runnable { … } }`
   → 生成注解接口类 `Marker`，并在同一目标上生成伴生类 `Marker$Impl`（或匿名时 `Marker$companion`）。
2. 使用点 `@Marker(value = "hi", count = 3)` 标在类/接口/注解声明/方法/字段上
   → 写 `RuntimeVisibleAnnotations`（类级 `cw.visitAnnotation`、方法级 `mv.visitAnnotation`、字段级 `fv.visitAnnotation`）。
3. 运行时机选 **B（访问器 + 懒构造）**：
   - 类型级目标 → 在**该目标类**上生成 `public static <伴生类型> Marker()`（注解名做方法名）；
   - 成员级目标 → 访问器多一个 `string member` 参数，取不到返回 null；
   - 访问器体 = 懒构造一个伴生实例（`ACC_SYNCHRONIZED`，每使用点一实例）；
   - 用户占了同名成员、或同一目标重复标同一注解 → 编译期由 M2 报错（M3 只需不再生成）。

## ASM 硬要求（`build/verify21/m3probe/` 实测，M3 必读）

- ASM jar：`build.gradle:19` 声明的 `asm-9.9.1`（caches 里另有旧 `asm-9.2`，别用）。
- 注解接口：`cw.visit(V17, ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT|ACC_ANNOTATION, 内部名, null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"})`。
  实测最终 `flags (0x2601)`；`ACC_ANNOTATION=0x2000`。
- **必须自带 `@Retention(RetentionPolicy.RUNTIME)` 元注解**：漏了也能生成，`javap` 也显示 `RuntimeVisibleAnnotations`，但运行期 `getAnnotation` 静默返回 `null`。
- 元素方法：`cw.visitMethod(ACC_PUBLIC|ACC_ABSTRACT, 名, 描述符, null, null)`；默认值 `mv.visitAnnotationDefault().visit(null, 盒装值)`。
  漏 `ACC_ABSTRACT` → 运行期 `ClassFormatError: Absent Code attribute in method that is not native or abstract`。
- 使用点 `visitAnnotation` 必须写在对应的 `visitEnd()` **之前**。
- 与 javac 对拍时，除常量池编号/顺序、`SourceFile`、`major version` 外应逐行相同。

## 插入点（`build/verify21/m3probe` 之外的勘查结论）

- 类循环 `codegen/BytecodeCompiler.kt:110`/`:137`/`:159` 之后加第四个（`:170` 后）；`annotationClass` 仿 `interfaceClass:830`。
- 使用点插在 `userClass:611`、`interfaceClass:840`、`emitUserMethod:1011`、字段 `:616` 的 `visitEnd` 之前。
- 访问器 access 走旁路，**别改 `accessOf:1016`**（20+ 调用点）。
- **不动点**：没有注解的源必须字节不变（J1 126 类 / J3 `diff -r` 0 / J5 八示例 sha256）。
- **接口点**：伴生类合成成 `ClassDecl` 进 `model.classModel.classes`，两端免费复用（M2 已按此约定向 `SemanticModel` 暴露）。

## 交付

- 新测试 `src/test/kotlin/org/example/AnnotationCodegenTest.kt`：至少覆盖注解接口 flags/父接口/`isAnnotation()`、元素默认值往返、类/方法/字段三处使用点、访问器行为、`Marker::Impl` 伴生类名。
- 证据目录 `build/verify21/m3/`。
- 报告：改了哪些文件（含行号）、`./gradlew.bat cleanTest test jar` 数字、accept-after/verify2 数字。

## 硬约束

- 不碰 `rsfSrc/**`、`build/verify17e|1*|20|21/**`（只准新建 `build/verify21/m3/`）、`DESIGN-21.md`、`BOOTSTRAP.md`、`README.md`、`RsfTest.kt`、`interp/**`、`ir/**`、`lsp/**`、`idea-rainstarfox/**`，不做 git 操作，不改已有测试期望，交付时工作树可编译且全绿。
