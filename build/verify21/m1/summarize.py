import glob
import html
import io
import re

# 汇总 build/test-results/test/*.xml 的计数，并把 AnnotationSyntaxTest 的
# 用例名单 + 它打印的 AST 形状（system-out）落成一份 UTF-8 证据文件。
rows = []
total = failures = errors = 0
for path in sorted(glob.glob('build/test-results/test/*.xml')):
    text = io.open(path, encoding='utf-8', errors='replace').read()
    m = re.search(
        r'tests="(\d+)"[^>]*skipped="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', text)
    name = re.search(r'<testsuite[^>]*name="([^"]+)"', text)
    if not m or not name:
        continue
    rows.append((name.group(1), m.group(1), m.group(3), m.group(4), m.group(2)))
    total += int(m.group(1))
    failures += int(m.group(3))
    errors += int(m.group(4))

lines = ['%-40s tests=%-5s failures=%-3s errors=%-3s skipped=%s' % r for r in rows]
lines.append('TOTAL tests=%d failures=%d errors=%d' % (total, failures, errors))
lines.append('')

ann = 'build/test-results/test/TEST-org.example.AnnotationSyntaxTest.xml'
text = io.open(ann, encoding='utf-8').read()
names = [html.unescape(n) for n in re.findall(r'<testcase name="([^"]+)"', text)]
lines.append('AnnotationSyntaxTest 用例（%d 条）：' % len(names))
for i, n in enumerate(names, 1):
    lines.append('%2d. %s' % (i, n))
out = re.search(r'<system-out>(.*?)</system-out>', text, re.S)
if out:
    body = html.unescape(out.group(1))
    body = re.sub(r'^<!\[CDATA\[', '', body)
    body = re.sub(r'\]\]>\s*$', '', body)
    lines.append('')
    lines.append('解析证据：一整套注解语法的 AST 形状（测试自己 println 的输出）')
    lines.append(body.rstrip())

io.open('build/verify21/m1/report_counts.txt', 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print('\n'.join(lines[:12]))
