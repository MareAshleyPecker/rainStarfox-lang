#!/usr/bin/env python3
"""Temporary: migrate rsf declaration keyword in shell heredoc corpora.

Replaces a bare `fun` that is followed (after spaces) by an identifier and then
`(` -- i.e. an rsf function declaration inside a heredoc -- with `fn`.
Only lines inside `<<'EOF' ... EOF` heredoc bodies are considered, and only
when the heredoc content looks like rsf (`{`/`}` braces present).
"""
import re
import sys

DECL = re.compile(r'\bfun\b(?=\s+[A-Za-z_][A-Za-z0-9_]*\s*\()')


def migrate(path, apply):
    with open(path, encoding='utf-8', newline='') as fh:
        lines = fh.read().split('\n')
    out = []
    in_heredoc = False
    rsf_like = False
    changed = 0
    for line in lines:
        if not in_heredoc:
            m = re.search(r"<<'([A-Za-z_][A-Za-z0-9_]*)'", line)
            if m:
                in_heredoc = True
                rsf_like = False
            out.append(line)
            continue
        if line.strip() == 'EOF':
            in_heredoc = False
            out.append(line)
            continue
        if '{' in line or '}' in line:
            rsf_like = True
        if rsf_like:
            new = DECL.sub('fn', line)
            if new != line:
                changed += 1
                line = new
        out.append(line)
    print('%s: lines changed = %d' % (path, changed))
    if apply and changed:
        with open(path, 'w', encoding='utf-8', newline='') as fh:
            fh.write('\n'.join(out))


if __name__ == '__main__':
    for arg in sys.argv[1:]:
        if arg == '--apply':
            continue
        migrate(arg, '--apply' in sys.argv)
