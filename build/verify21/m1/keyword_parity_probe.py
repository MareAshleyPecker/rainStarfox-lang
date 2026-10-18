import json, re
lex = open('src/main/kotlin/org/example/complie/lexing/Lexer.kt', encoding='utf-8').read()
comp = set(re.findall(r'put\("([A-Za-z_][A-Za-z0-9_]*)",\s*TokenKind\.KW_', lex))
raw = open('vscode-rainstarfox/syntaxes/rainstarfox.tmLanguage.json', encoding='utf-8').read()
tm = set()
for grp in re.findall(r'\\\\b\(([^)]*)\)\\\\b', raw):
    for w in grp.split('|'):
        if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', w):
            tm.add(w)
tk = open('idea-rainstarfox/src/main/kotlin/org/rainstarfox/idea/RsfTokenType.kt', encoding='utf-8').read()
idea = set()
for body in re.findall(r'setOf\(([^)]*)\)', tk, re.S):
    idea |= set(re.findall(r'"([A-Za-z_][A-Za-z0-9_]*)"', body))
print('编译器 %d:' % len(comp), sorted(comp))
print('tmLanguage %d, 缺:' % len(tm), sorted(comp - tm))
print('IDEA %d, 缺:' % len(idea), sorted(comp - idea))
print('tm 多出:', sorted(tm - comp))
print('IDEA 多出:', sorted(idea - comp))
