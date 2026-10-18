# 阶段 21 M3（注解的代码生成）报告

工作区 `D:\java\rainStarfox`。只做 Kotlin 后端的字节码生成（解释器侧留 M4）。
`./gradlew.bat cleanTest test jar` = **437 条 / 0 fail**（M3 前基线 414，新增 `AnnotationCodegenTest` 23 条）。

## 1. 改了哪些文件

| 文件 | 行区间 | 内容 |
| --- | --- | --- |
| `src/main/kotlin/org/example/complie/codegen/BytecodeCompiler.kt` | 全文件 2997 → 3399 行 | 唯一改动的生产文件，见 §2 |
| `src/test/kotlin/org/example/AnnotationCodegenTest.kt` | 1–1028（新建） | 23 条正反例（ASM 类文件层 + URLClassLoader 运行期反射） |
| `build/verify21/m3/demo1.rsf` | 新建 | 证据源码：注解 + 伴生实体 + 类/字段/方法三处使用点 + `new` 伴生实例 |
| `build/verify21/m3/Check.java` | 新建 | 纯 JDK 反射 + URLClassLoader 的运行期检查（62 条断言） |
| `build/verify21/m3/ProbeEnum.java` | 新建 | 枚举注解元素的运行期限制探针 |
| `build/verify21/m3/run-all.sh` | 新建 | 证据脚本：jar 编 demo1 → 解释器/JVM 各跑 → javap → Check |
| `build/verify21/m3/fixedpoint.sh` | 新建 | 不动点自查（J1/J3/J5），不替代父代理的 `build/verify17e/*.sh` |
| `build/verify21/m3/log/*`、`out1/*` | 新建 | javap 摘录、Check 输出、不动点日志、生成的 .class |

没有碰 `sema/**`、`interp/**`、`ir/**`、`lsp/**`、`rsfSrc/**`、`DESIGN-21.md`、既有测试与 `build/verify17e|1*|20|21/**`；没做任何 git 操作。

## 2. BytecodeCompiler.kt 的改动（关键函数）

**改的既有函数**

| 位置 | 改动 |
| --- | --- |
| `compile():103` | 三个类循环之后加**第四个循环**：`for (spec in model.annotations.values)` → 注释接口 class（含与程序类的重名检查） |
| `classInternalName():557` | 注释里写明**注解接口不跟着包走**（理由见 §5） |
| `userClass():641` | `visitSource` 后发类级使用点；字段循环改成 `val fv = cw.visitField(...)` + `emitUseSites` + `fv.visitEnd()`；尾部（`visitEnd()` 之前）加伴生类的 `emitAnnotationCaches` / `emitAnnotationType` 与 `emitAnnotationAccessors` |
| `interfaceClass():884` | 接口自己的使用点 + 每个抽象方法的 `mv.visitAnnotation`（都在 `visitEnd()` 之前）+ `emitAnnotationAccessors` |
| `emitUserConstructor():1403` | 伴生类的前 N 个字段（元素字段）用**构造器形参**赋值（`loadSlot(mv, param.type, param.slot)`），其余字段仍用声明初值；`paramsOf(ctor)[i].slot` 是权威槽位 |
| `emitUserMethod():1479`、`emitFunction():1662` | `cw.visitMethod(...)` 之后、`visitCode()` 之前发方法级使用点 |
| `emitNew():2278` | `model.annotationConstructionOf(expr)` 时按元素声明顺序**补齐省略的实参**（M2 允许少给；不补会 NoSuchMethodError） |
| `internalNameOf():2414` | 保持「只有用户**类**跟包走」（接口/注解接口都不跟） |
| `descriptorOfType():596` | 注解接口类型的描述符就是 `type.descriptor`（`LMarker;`），刻意不接 `classInternalName` |
| companion object `:3245` | 新增 `ACC_ANNOTATION`、`ACC_SYNCHRONIZED` |

**新增的一组函数**（`BytecodeCompiler.kt:920-1318`，一整节 `// 注解（阶段 21 M3）`）

```
annotationClass(internalName, spec): ClassWriter        943   注解接口：0x2601 + java/lang/annotation/Annotation + @Retention(RUNTIME)
elementDescriptor(element): String                      983   "()" + 元素描述符
companionDescriptor(companionName): String              987   "L<classInternalName(伴生)>;"
useSitesOn(node): List<AnnotationUseSite>               996   按目标身份配对（===）
emitUseSites(av, node)                                 1016   往 RuntimeVisibleAnnotations 写 @Marker(...)
explicitValues(site): List<Expr?>                      1045   位置实参按下标、具名实参按名字（与 M2 同规则）
annotationConstant(type, expr): Any?                   1069   按**元素声明类型**装箱；枚举/嵌套注解单独走
badAnnotationValue(type, expr): RsfError               1108   非常量值的报错
pushAnnotationValue(mv, type, elementName, expr)       1123   把常量压栈
emitAnnotationCaches(cw, companionName)                1164   伴生类上的 static 缓存字段（每使用点一个）
cacheFieldName(accessor): String                       1172   __rsf_ann$宿主$成员
emitAnnotationAccessors(cw, hostName)                  1187   方案 B 的访问器（按注解+层级分组）
emitTypeLevelAccessor(cw, accessor, desc)              1208   public static synchronized <伴生> Marker()
emitMemberLevelAccessor(cw, group, desc)               1230   public static synchronized <伴生> Marker(string)
emitAccessorNew(mv, accessor)                          1269   new 伴生(实参…)，省略的元素补默认值
emitAnnotationType(cw, annotationName)                 1307   annotationType() → ldc Marker.class
```

访问器的 access 直接写 `Opcodes.ACC_PUBLIC|ACC_STATIC|ACC_SYNCHRONIZED`，**没有走 `accessOf`**（那是给 rsf `FuncDecl` 用的，20+ 调用点）。

## 3. 实测到的生成物形状（`javap -v -p` 原文）

`Marker`（注解接口）：

```
public interface Marker extends java.lang.annotation.Annotation
  flags: (0x2601) ACC_PUBLIC, ACC_INTERFACE, ACC_ABSTRACT, ACC_ANNOTATION
  interfaces: 1, fields: 0, methods: 3          # 构造器 0
  public abstract java.lang.String value();  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
    AnnotationDefault: default_value: s#13 "x"
  public abstract int count();               flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
    AnnotationDefault: default_value: I#16 0
  public abstract Level level();             flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT   # 无默认值
RuntimeVisibleAnnotations:
  java.lang.annotation.Retention(value=Ljava/lang/annotation/RetentionPolicy;.RUNTIME)
```

使用点（类 / 字段 / 方法都是 `RuntimeVisibleAnnotations`，写在对应 `visitEnd()` 之前）：

```
