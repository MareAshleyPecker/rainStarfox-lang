import io
import re

path = 'src/main/kotlin/org/example/complie/parsing/Parser.kt'
src = io.open(path, encoding='utf-8').read()
lines = src.split('\n')

pattern = re.compile(r'"((?:[^"\\]|\\.)*)"')

out = []
i = 0
while i < len(lines):
    if 'throw error(' in lines[i] or 'throw RsfError(' in lines[i]:
        buf = []
        depth = 0
        started = False
        j = i
        while j < len(lines):
            for ch in lines[j]:
                if ch == '(':
                    depth += 1
                    started = True
                elif ch == ')':
                    depth -= 1
            buf.append(lines[j])
            if started and depth <= 0:
                break
            j += 1
        text = ' '.join(x.strip() for x in buf)
        lits = pattern.findall(text)
        msg = ''.join(lits).replace('\\n', '\\\\n')
        out.append((i + 1, msg))
        i = j + 1
    else:
        i += 1

io.open('build/verify21/m1/parser_diags.txt', 'w', encoding='utf-8').write(
    '\n'.join('%d\t%s' % (n, m) for n, m in out))
print(len(out), 'diagnostics')
