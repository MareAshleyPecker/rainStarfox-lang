import io
import re

PATTERN = re.compile(r'"((?:[^"\\]|\\.)*)"')


def diags(path):
    lines = io.open(path, encoding='utf-8').read().split('\n')
    out = set()
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
            out.add(''.join(PATTERN.findall(text)))
            i = j + 1
        else:
            i += 1
    return out


head = diags('build/verify21/m1/ParserHead.kt')
now = diags('src/main/kotlin/org/example/complie/parsing/Parser.kt')
added = sorted(m for m in now if m not in head)
io.open('build/verify21/m1/new_diags.txt', 'w', encoding='utf-8').write('\n---\n'.join(added))
print(len(added), 'new diagnostics')
