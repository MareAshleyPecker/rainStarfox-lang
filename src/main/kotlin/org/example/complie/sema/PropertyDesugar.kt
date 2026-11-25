package org.example.complie.sema

import org.example.complie.ast.Accessor
import org.example.complie.ast.AccessorKind
import org.example.complie.ast.Block
import org.example.complie.ast.ClassDecl
import org.example.complie.ast.ControlType
import org.example.complie.ast.FieldDecl
import org.example.complie.ast.FuncDecl
import org.example.complie.ast.MemberAssignStmt
import org.example.complie.ast.MemberExpr
import org.example.complie.ast.Param
import org.example.complie.ast.Program
import org.example.complie.ast.ReturnSpec
import org.example.complie.ast.ReturnStmt
import org.example.complie.ast.ReturnStyle
import org.example.complie.ast.ThisExpr
import org.example.complie.ast.TypeRef
import org.example.complie.ast.VarExpr
import org.example.complie.diag.RsfError

/**
 * 阶段 79：把字段上的 `{ get; set(v) { … } }` 编译期展开成真方法。
 *
 * 用户写的语法是缝合的：
 *
 * ```
 * var hp : Int = 100 { get; set(v) { if (v < 0) { fail "hp 不能是负数" } this.hp = v } }
 * ```
 *
 * 展开成（[apply] 之后 AST 里真实存在的东西）：
 *
 * ```
 * pub var hp : Int = 100
 * pub fn getHp() -> Int { rtn this.hp }
 * pub fn setHp(v : Int) -> void { if (v < 0) { … } this.hp = v }
 * ```
 *
 * 两条设计上的取舍：
 *
 * 1. **只合成方法，不重命名字段、也不改使用侧**（这一版）。所以 `p.hp` 仍然是字段直读、
 *    `p.hp = 1` 仍然是字段直接赋值——不会触发 setter。这么做的理由：访问侧改写需要
 *    「这个成员读到的是属性还是普通字段」的静态类型信息，而合法的起点只有一处——
 *    [Checker] 的成员解析；把改写塞进 Parser（不知道类型）或塞进一个独立的 AST
 *    重写 pass（`p.hp += 1`、`this.hp`、与字段同名的方法全都要自己判断，出错最难查）都更糟。
 *    等 [Checker] 记下「这里是属性」之后，三端各加一条分支即可（DESIGN-79 的实现顺序）。
 *
 * 2. **方法名按 JavaBean 约定**：`getHp` / `setHp`；写了 `{ is }` 的（Bool）叫 `isAlive`。
 *    名字是确定的（不依赖类型信息），Java 侧反射、Kotlin 侧 `kotlin.properties` 都能直接认。
 *
 * 生成的方法体照抄用户写的体；自动访问器（不写 `{ … }`）的体由这里补：
 * getter 是 `rtn this.x`，setter 是 `this.x = v`。字段名没改，所以这两句不会递归。
 */
object PropertyDesugar {

    /** 展开整份程序里的属性访问器；没有访问器时原样返回（不复制 AST）。 */
    fun apply(program: Program): Program {
        if (program.classes.none { cls -> cls.fields.any { it.accessors.isNotEmpty() } }) return program
        return program.copy(classes = program.classes.map { desugar(it) })
    }

    /** 属性 `x` 的 getter 名：写了 `{ is }` 就是 `isX`，否则 `{ get }` 的 `getX`；没写返回 null。 */
    fun getterName(field: FieldDecl): String? {
        val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
        return when {
            field.accessors.any { it.kind == AccessorKind.IS } -> "is$suffix"
            field.accessors.any { it.kind == AccessorKind.GET } -> "get$suffix"
            else -> null
        }
    }

    /** 属性 `x` 的 setter 名 `setX`；字段上没写 `set` 返回 null。 */
    fun setterName(field: FieldDecl): String? {
        if (field.accessors.none { it.kind == AccessorKind.SET }) return null
        return "set" + field.name.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * [fn] 是不是 [field] 的访问器（自动生成的那份或用户手写的体都算）。
     *
     * 访问器自己的体里不许再改写：`getX(){ rtn this.x }` 若把 `this.x` 也摊成 `getX()`
     * 就是无限递归。Checker 用它跳过这条边。
     */
    fun isAccessorOf(field: FieldDecl, fn: FuncDecl?): Boolean =
        fn != null && (fn.name == getterName(field) || fn.name == setterName(field))

    /** 展开一个类里所有属性；方法按字段声明顺序追加，原方法顺序不变。 */
    private fun desugar(cls: ClassDecl): ClassDecl {
        if (cls.fields.none { it.accessors.isNotEmpty() }) return cls
        val methods = ArrayList(cls.methods)
        val taken = cls.methods.mapTo(HashSet()) { it.name }
        for (field in cls.fields) {
            for (accessor in field.accessors) {
                synthesize(cls, field, accessor, methods, taken)
            }
        }
        return cls.copy(methods = methods)
    }

    private fun synthesize(
        cls: ClassDecl,
        field: FieldDecl,
        accessor: Accessor,
        methods: MutableList<FuncDecl>,
        taken: MutableSet<String>,
    ) {
        val at = accessor.at
        val suffix = field.name.replaceFirstChar { it.uppercaseChar() }
        if (accessor.kind == AccessorKind.SET) {
            if (!field.mutable) {
                throw RsfError(
                    "checker",
                    "'${cls.name}.${field.name}' 是 val（不可变），不能有 'set'。" +
                        "想让它能改就写成 'var'，否则只能留 'get'（或 Bool 的 'is'）。",
                    at.line,
                    at.column,
                )
            }
            val param = accessor.param ?: DEFAULT_SETTER_PARAM
            val body = accessor.body ?: Block(
                listOf(
                    MemberAssignStmt(
                        MemberExpr(ThisExpr(at), field.name, null, at),
                        VarExpr(param, at),
                        at,
                    ),
                ),
                at,
            )
            add(
                cls = cls,
                name = "set$suffix",
                params = listOf(Param(field.type, param, at)),
                returnType = TypeRef("void", at),
                body = body,
                at = at,
                methods = methods,
                taken = taken,
            )
            return
        }

        val name = if (accessor.kind == AccessorKind.IS) "is$suffix" else "get$suffix"
        val body = accessor.body ?: Block(
            listOf(
                ReturnStmt(MemberExpr(ThisExpr(at), field.name, null, at), at),
            ),
            at,
        )
        add(cls, name, emptyList(), field.type, body, at, methods, taken)
    }

    private fun add(
        cls: ClassDecl,
        name: String,
        params: List<Param>,
        returnType: TypeRef,
        body: Block,
        at: org.example.complie.lexing.SourceLine,
        methods: MutableList<FuncDecl>,
        taken: MutableSet<String>,
    ) {
        if (!taken.add(name)) {
            throw RsfError(
                "checker",
                "'${cls.name}' 上已经有方法 '$name'，属性访问器没地方生成。改个属性名，" +
                    "或者把那个方法改名（属性名按 JavaBean 约定展开成 get<名字> / set<名字> / is<名字>）。",
                at.line,
                at.column,
            )
        }
        methods.add(
            FuncDecl(
                control = ControlType.DEFAULT,
                mainType = TypeRef("void", at),
                name = name,
                params = params,
                ret = ReturnSpec(returnType, ReturnStyle.ARROW, false, at),
                body = body,
                at = at,
            ),
        )
    }

    private const val DEFAULT_SETTER_PARAM = "value"
}
