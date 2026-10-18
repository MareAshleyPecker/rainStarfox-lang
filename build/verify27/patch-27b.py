#!/usr/bin/env python3
# 阶段 27b：自举链去掉 open（Kotlin 侧 27a 已改完，这里只对齐自举源）
import io, sys

def rd(p):
    with io.open(p, encoding='utf-8', newline='') as f:
        return f.read()

def wr(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

def sub(s, old, new, path, count=1):
    n = s.count(old)
    if n != count:
        print("!! MISMATCH(%d) in %s:\n%r" % (n, path, old[:200]))
        sys.exit(2)
    return s.replace(old, new, count)

# ---------------------------------------------------------------- Kotlin: 报错文案里的 open
p = 'src/main/kotlin/org/example/complie/parsing/Parser.kt'
s = rd(p)
s = sub(s, "字段不能写 'open' / 'final' / 'abstract'", "字段不能写 'final' / 'abstract'", p)
s = sub(s, "：'open' / 'final' / 'abstract' 是**类**的规矩", "：'final' / 'abstract' 是**类**的规矩", p)
s = sub(s, "：'open' / 'final' / 'abstract' 说的是", "：'final' / 'abstract' 说的是", p)
s = sub(s, "静态方法不能是 'open' / 'final' / 'abstract'", "静态方法不能是 'final' / 'abstract'", p)
wr(p, s)
print("ok Kotlin Parser.kt 文案")

# ---------------------------------------------------------------- Lexer.rsf
p = 'rsfSrc/bootstrap/Lexer.rsf'
s = rd(p)
s = sub(s, '    if (word == "open") { rtn "KW_OPEN" }\n', '', p)
s = sub(s,
    '// 2026-10-17 自举同步（阶段 24b，词法这一刀）：补上 `as` / `is`（55 → 57 项），\n',
    '// 2026-10-17 自举同步（阶段 27，词法这一刀）：删掉 `open`（57 → 56 项）。阶段 27 把\n'
    '// 「默认开放、写 `final` 才封」当唯一口径（DESIGN-27），`open` 词本身被硬删——它不再是\n'
    '// 关键字，写出来就是语法错。`final` / `abstract` / `super` 三个词原样保留。\n'
    '//\n'
    '// 2026-10-17 自举同步（阶段 24b，词法这一刀）：补上 `as` / `is`（55 → 57 项），\n', p)
wr(p, s)
print("ok Lexer.rsf")

# ---------------------------------------------------------------- Parser.rsf
p = 'rsfSrc/bootstrap/Parser.rsf'
s = rd(p)

s = sub(s, 'SUPER_CLASS, SUPER_CALL, OPEN, FINAL, ABSTRACT,', 'SUPER_CLASS, SUPER_CALL, FINAL, ABSTRACT,', p)
s = sub(s, '    // OPEN / FINAL / ABSTRACT **不是 AST 节点**', '    // FINAL / ABSTRACT **不是 AST 节点**', p)
s = sub(s, '    // TARGET 和上面 OPEN/FINAL/ABSTRACT 一样', '    // TARGET 和上面 FINAL/ABSTRACT 一样', p)
s = sub(s, '        NodeKind.OPEN : rtn "OPEN"\n', '', p)

s = sub(s,
    '// 阶段 25：三个**继承修饰词**（与 Kotlin 侧 TokenKind.isInheritKeyword 同口径，\n'
    '// 见 src/main/kotlin/org/example/complie/lexing/Token.kt:279-280）。\n'
    '// 注意 `super` **不在**这里：它不是修饰词，而是表达式位置上的接收者。\n'
    'fun isInheritKeyword(kind : string) -> bool {\n'
    '    if (kind == "KW_OPEN") { rtn true }\n'
    '    if (kind == "KW_FINAL") { rtn true }\n'
    '    rtn kind == "KW_ABSTRACT"\n'
    '}',
    '// 阶段 25：继承修饰词（阶段 27 起只剩 `final` / `abstract`，与 Kotlin 侧\n'
    '// TokenKind.isInheritKeyword 同口径，见 src/main/kotlin/org/example/complie/lexing/Token.kt）。\n'
    '// 注意 `super` **不在**这里：它不是修饰词，而是表达式位置上的接收者。\n'
    'fun isInheritKeyword(kind : string) -> bool {\n'
    '    if (kind == "KW_FINAL") { rtn true }\n'
    '    rtn kind == "KW_ABSTRACT"\n'
    '}', p)

s = sub(s,
    'public class InheritMods(isOpen : bool, isFinal : bool, isAbstract : bool) {\n'
    '    var isOpen : bool = isOpen\n'
    '    var isFinal : bool = isFinal\n',
    'public class InheritMods(isFinal : bool, isAbstract : bool) {\n'
    '    var isFinal : bool = isFinal\n', p)

s = sub(s, '        var inherit = new InheritMods(false, false, false)',
           '        var inherit = new InheritMods(false, false)', p)

s = sub(s, '        if (mods.isOpen) { n.add(new Node(NodeKind.OPEN)) }\n', '', p)
s = sub(s, '        if (inherit.isOpen) { n.add(new Node(NodeKind.OPEN)) }\n', '', p)

s = sub(s, '        //   CLASS → SUPERS → [SUPER_CLASS + 它的 ARGS] → [OPEN | FINAL | ABSTRACT] →',
           '        //   CLASS → SUPERS → [SUPER_CLASS + 它的 ARGS] → [FINAL | ABSTRACT] →', p)
s = sub(s, '        // Backend.rsf:5692 按位置读孩子）。父类槽位与三个修饰词节点**只在写了时**挂，',
           '        // Backend.rsf:5692 按位置读孩子）。父类槽位与两个修饰词节点**只在写了时**挂，', p)

# parseInheritModifiers 整体替换
start = '    fun parseInheritModifiers(kindWord : string) -> InheritMods {'
end = '        rtn new InheritMods(sawOpen, sawFinal, sawAbstract)\n    }\n'
i = s.index(start); j = s.index(end) + len(end)
new_fn = '''    fun parseInheritModifiers(kindWord : string) -> InheritMods {
        var sawFinal = false
        var sawAbstract = false
        while (isInheritKeyword(peek().kind) && !failed) {
            var t = advance()
            if (t.kind == "KW_FINAL") {
                if (sawFinal) { failAt(t, kindWord + " 上 'final' 写了两遍——写一次就够了") }
                sawFinal = true
            } else {
                if (sawAbstract) { failAt(t, kindWord + " 上 'abstract' 写了两遍——写一次就够了") }
                sawAbstract = true
            }
        }
        if (sawAbstract && sawFinal) {
            var why = "抽象成员本来就是要被子类实现的"
            if (kindWord == "类") { why = "抽象类本来就是要被继承的" }
            failAt(peek(), kindWord + " 上 'abstract' 不能和 'final' 写在一起：" + why + "（DESIGN-25 §6），写 'abstract' 一个就够了")
        }
        rtn new InheritMods(sawFinal, sawAbstract)
    }
'''
s = s[:i] + new_fn + s[j:]

# mergeInheritMods 整体替换
start = '    fun mergeInheritMods(cur : InheritMods, kindWord : string) -> InheritMods {'
end = '        rtn new InheritMods(o, f, a)\n    }\n'
i = s.index(start); j = s.index(end) + len(end)
new_fn = '''    fun mergeInheritMods(cur : InheritMods, kindWord : string) -> InheritMods {
        var next = parseInheritModifiers(kindWord)
        if (cur.isFinal && next.isFinal) { failAt(peek(), kindWord + " 上 'final' 写了两遍——写一次就够了") }
        if (cur.isAbstract && next.isAbstract) { failAt(peek(), kindWord + " 上 'abstract' 写了两遍——写一次就够了") }
        var f = cur.isFinal || next.isFinal
        var a = cur.isAbstract || next.isAbstract
        if (a && f) {
            var why = "抽象成员本来就是要被子类实现的"
            if (kindWord == "类") { why = "抽象类本来就是要被继承的" }
            failAt(peek(), kindWord + " 上 'abstract' 不能和 'final' 写在一起：" + why + "（DESIGN-25 §6），写 'abstract' 一个就够了")
        }
        rtn new InheritMods(f, a)
    }
'''
s = s[:i] + new_fn + s[j:]

s = sub(s, '        // 阶段 25：方法的继承修饰词（open / final / abstract）。缺省三个都为 false',
           '        // 阶段 25：方法的继承修饰词（阶段 27 起只剩 final / abstract）。缺省两个都为 false', p)
s = sub(s, '        // ⇒ 不写就一个 dump 行都不挂（AstDump 只在写了时打 OPEN / FINAL）。',
           '        // ⇒ 不写就一个 dump 行都不挂（AstDump 只在写了时打 FINAL）。', p)

s = sub(s, '        // 静态方法不参与继承（DESIGN-25 §3）：static 与 open/final/abstract 不能写到一起\n'
           '        if (isStatic && (inherit.isOpen || inherit.isFinal || inherit.isAbstract)) {\n'
           '            failAt(peek(), "静态方法不能是 \'open\' / \'final\' / \'abstract\'：静态方法只能写成 \'类名.方法名\' 调用（static 不参与继承，DESIGN-25 §3），把它上面的继承修饰词删掉")',
           '        // 静态方法不参与继承（DESIGN-25 §3）：static 与 final/abstract 不能写到一起\n'
           '        if (isStatic && (inherit.isFinal || inherit.isAbstract)) {\n'
           '            failAt(peek(), "静态方法不能是 \'final\' / \'abstract\'：静态方法只能写成 \'类名.方法名\' 调用（static 不参与继承，DESIGN-25 §3），把它上面的继承修饰词删掉")', p)

s = sub(s, "格式是 '[public|protected|private] [static] [override] [open|final|abstract] fun",
           "格式是 '[public|protected|private] [static] fun", p)

s = sub(s, "字段不能写 'open' / 'final' / 'abstract'：子类不能重复声明父类已有的字段",
           "字段不能写 'final' / 'abstract'：子类不能重复声明父类已有的字段", p)
s = sub(s, "                // 构造器：`constructor` 以及「修饰词 + constructor」。修饰词可以排一串\n"
           "                // （阶段 25 起还包括 open/final/abstract），所以按 Kotlin 版 modifierRun",
           "                // 构造器：`constructor` 以及「修饰词 + constructor」。修饰词可以排一串\n"
           "                // （阶段 25 起还包括 final/abstract），所以按 Kotlin 版 modifierRun", p)
s = sub(s, "                    // 阶段 25：构造器上的 open/final/abstract 收下来就丢掉（构造器不参与继承），",
           "                    // 阶段 25：构造器上的 final/abstract 收下来就丢掉（构造器不参与继承），", p)

s = sub(s, "    // 声明前面那一串修饰词的个数（权限 / static / override / open / final / abstract）。",
           "    // 声明前面那一串修饰词的个数（权限 / static / override / final / abstract）。", p)
s = sub(s, "    // `open` / `final` / `abstract` 只准写在**类头**与**类体里的方法**前面（阶段 25）——\n"
           "    // 这是 Kotlin 版 `parseInheritModifiers` 的 `allowInheritModifiers` 开关\n"
           "    // （Parser.kt:1505，缺省 false：接口体与顶层都不认这三个词）。同样用「置位/还原」",
           "    // `final` / `abstract` 只准写在**类头**与**类体里的方法**前面（阶段 25；阶段 27 起只剩这两个词）——\n"
           "    // 这是 Kotlin 版 `parseInheritModifiers` 的 `allowInheritModifiers` 开关\n"
           "    // （Parser.kt:1505，缺省 false：接口体与顶层都不认这两个词）。同样用「置位/还原」", p)
s = sub(s, "    // AstDump 的方法分支顺序是 FUNC 行 → OPEN → FINAL → PARAMS → [BLOCK] → ANNOTATION_USES，\n"
           "    // 而且**只在写了时**才有那两行（ABSTRACT 在方法上不打：抽象方法体现为没有 BLOCK 孩子）。",
           "    // AstDump 的方法分支顺序是 FUNC 行 → FINAL → PARAMS → [BLOCK] → ANNOTATION_USES，\n"
           "    // 而且**只在写了时**才有那一行（ABSTRACT 在方法上不打：抽象方法体现为没有 BLOCK 孩子）。", p)

wr(p, s)
print("ok Parser.rsf")

# ---------------------------------------------------------------- Backend.rsf
p = 'rsfSrc/bootstrap/Backend.rsf'
s = rd(p)
s = sub(s, '// 阶段 25 再更正一次：K5a 给方法加了 `[OPEN] [FINAL]` 两个孩子，位置在 PARAMS',
           '// 阶段 25 再更正一次：K5a 给方法加了 `[FINAL]` 这个孩子（阶段 27 起 `open` 被删，',
           p)
s = sub(s, '// **之前**（Parser.rsf:1797-1803），于是「第二个孩子是不是 BLOCK」对 `fun`',
           '// 只剩这一个），位置在 PARAMS **之前**（Parser.rsf:1797-1803），于是「第二个孩子是不是 BLOCK」对 `fun`', p)
s = sub(s, '            } else if (part.kind == NodeKind.OPEN) {\n'
           '                // 修饰词槽位：默认 final，`open` 在字节码层面没有对应标志位。\n'
           '            } else if (part.kind == NodeKind.FINAL) {\n'
           '                // 修饰词槽位：默认 final，字节码层面同样没有标志位。',
           '            } else if (part.kind == NodeKind.FINAL) {\n'
           '                // 修饰词槽位：阶段 27 起默认开放，`final` 才在字节码层面有对应标志位。', p)
wr(p, s)
print("ok Backend.rsf")

# ---------------------------------------------------------------- Interp.rsf
p = 'rsfSrc/bootstrap/Interp.rsf'
s = rd(p)
s = sub(s, '        // 阶段 25：FUNC 的孩子里 OPEN / FINAL **只在写了时才挂**，而且挂在 PARAMS **前面**',
           '        // 阶段 25：FUNC 的孩子里 FINAL **只在写了时才挂**，而且挂在 PARAMS **前面**', p)
wr(p, s)
print("ok Interp.rsf")

print("done")
