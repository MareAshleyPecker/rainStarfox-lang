# 自举同步第 3 刀（注解）备料：待定的 dump 格式决策

第 1 刀（interface/override/父类型）、第 2 刀（Backend/Interp 支持接口）之后再做。**格式此刻不冻结**，
先记下要拍板的点，等第 2 刀收工时按当时情况定稿；定稿后两边（`AstDump.kt` 与 `Parser.rsf`）必须同时改。

## 背景事实

- Kotlin 侧 AST 已有：`AnnotationDecl(control, name, elements, at, companion, annotations)`（`Ast.kt:77`）、
  `AnnotationElement(name, type, default: Expr?, at)`（`:104`）、
  `CompanionEntityDecl(name: String?, fields, methods, supertypes, at)`（`:124`）、
  `AnnotationUse(name, arguments, at)`（`:149`）、`AnnotationArg` = 位置/具名两种（`:193-215`）；
  `ClassDecl`/`InterfaceDecl`/`FuncDecl`/`FieldDecl`/`Program` 各有 `annotations: List<AnnotationUse>`。
- `AstDump.kt` 今天**一个注解都不打**；`Parser.rsf` 的 `NodeKind` 里也没有注解相关成员 ⇒ 两边都要加。
- rsf 侧注解默认值 v1 只收常量字面量（枚举常量/数组/注解默认值都不支持），所以 dump 的默认值表达式只会是字面量。

## 要拍板的点

1. `PROGRAM` 的孩子里加不加 `ANNOTATIONS count=N`？（第 1 刀已把它排成 `… / ENUMS / INTERFACES`，
   注解声明建议排最后，与 `Program.annotations` 的字段顺序一致。）
2. `ANNOTATION` 节点长什么样：`ANNOTATION control=… name=…` + `ELEMENTS count=N` + `ELEMENT name= type= default=?`
   —— `default` 是打「有没有默认值」的标记，还是把默认值**当成表达式子树**照 `fieldDecl` 那样打？
   （建议：表达式子树，与 `FIELD` 的 initializer 一致；rsf 侧只可能是字面量，代价可控。）
3. 伴生实体怎么落：`COMPANION name=…`（匿名用 `companion`）+ `METHODS`/`FIELDS` 与类同形？它可继承接口，
   `SUPERS` 与第 1 刀同形。
4. **使用点**（`@Marker("hi")`）落在哪：建议每个能被标注的声明节点（`FUNC`/`CLASS`/`INTERFACE`/`FIELD`）
   末尾加 `USES count=N`；实参怎么打（位置实参 vs 具名实参要能区分，否则对拍丢信息）。
5. 注解写在**声明前**还是和修饰词混写，dump 的字段顺序要不要固定（建议：`USES` 追加在末尾，避免动已有字段顺序）。

## 为什么这些必须先定

对拍是**逐行严格比较**（`RsfTest.kt:7346-7368`，只做 CRLF→LF 归一）。字段顺序或节点名两边差一点就必红，
而 rsf 侧的 `Node.put` 顺序是解析时定的、`AstDump` 的顺序是手写的 —— 一旦定稿就不能单边改。

---

## 父代理抄好的对照答案：Kotlin 侧注解语法（2026-10-15 23:15 抄自 `src/main/kotlin/org/example/complie/parsing/Parser.kt`）

第 3 刀落盘时不必再勘探这一块，照下面的行号与形状镜像即可。

### 入口与分派
- 顶层：`:226` `if (kind == TokenKind.AT)` 先收注解再进声明分派；成员/其它上下文同形：`:975`、`:1266`、`:1287`、`:1415`（`while (check(TokenKind.AT))`）。
- 位置不对的 `@`：`:1743` 报错（rsf 侧镜像时文案不要求一致）。

### 使用点（`@Marker("hi")`）
- `parseAnnotations()` `:295-309` → `List<AnnotationUse>`，`while (check(AT))` 循环。
- `parseAnnotationUse()` `:310-326`：`expect(AT, "注解要写在 '@' 后面")` → 名字 → 可选实参表。
- `parseAnnotationArgs()` `:327-360` / `parseAnnotationArg()` `:361-383`：位置实参与**具名实参**两种（`AnnotationArg` 两种子类，`Ast.kt:193-215`）——dump 必须能区分，否则对拍丢信息。

### 注解声明（`annotation Marker(value : string = "x") { companion entity ... }`）
- `parseAnnotationDecl(annotations)` `:384-418`：`[control]`（`:386-388`）→ `KW_ANNOTATION` → 名字（`:390`）→ 元素表（`:392` 只有写了 `(` 才收）→ 体（`:393-409`，`{` 之后**至多一个** companion entity，第二个当场报错）。
- 元素表 `parseAnnotationElementList()` `:421-448`：`( 名字 : 类型 [= 默认值] (, …)* )`，**允许尾逗号**（`:428`），元素名重复在**解析期**就报错（`:433-446`）。
- 元素 `parseAnnotationElement()` `:456-469`：`名字 : 类型`（**后缀风格**，Java 风格 `string value` 报错）`[= 默认值]`。
- 默认值 `parseAnnotationDefault()` `:478-483` → 先 `parseExpr()` 再按形状收窄 `validateAnnotationDefault()` `:492-504`：只允许**一个字面量**，或数值字面量前**一个** `-`；白名单 `isDefaultConstantLiteral()` `:507-513` = `IntLit/LongLit/FloatLit/DoubleLit/StringLit/CharLit/BoolLit/NullLit`。枚举常量/注解/数组默认值 v1 明确不支持（报「暂不支持」并指向伴生实体）。

### 伴生实体（`companion entity [名字] [: 父接口, …] { 普通类体 }`）
- `parseAnnotationBody(annotationName)` `:521-533`：先 `parseAnnotations()`（伴生实体自己能带注解），再只认 `KW_COMPANION`；空体返回 `null`；别的成员一律报「注解不是类：字段/方法/构造器都要写进伴生实体里」。
- `parseCompanionEntity(annotationName)` `:547-`：`companion` → `entity` → **名字可省**（匿名 `companion entity : Runnable {}`，`:553`）→ 名字后跟类型关键字是专门报的错（`:554-561`）→ 父接口用 `parseSupertypeList("注解 'X' 的伴生实体")` `:562` → 体复用 `parseFieldDecl`/`parseConstructor`/`parseFuncDecl`。

### 与第 1 刀的协同（省事点）
第 1 刀已经在 `rsfSrc/bootstrap/Parser.rsf:899-935` 写好了 `parseSupertypeList()`（返回 `SUPERS` 节点、含三条负例判据），
第 3 刀解析 `companion entity` 的父接口列表时可以**直接复用**它——不必再写一份。
