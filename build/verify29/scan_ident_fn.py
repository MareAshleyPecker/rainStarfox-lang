#!/usr/bin/env python3
"""Temporary scanner: find `fn` used as an identifier (not declaration keyword) in rsf sources.

Rule: inside code regions, a bare word `fn` whose next non-space char is NOT
an identifier start char (letter/_/digit) is an identifier use; otherwise it is
a declaration keyword. Skips comments, strings, char literals, backtick labels.
"""
import os
import sys

BS = chr(92)


def scan(text):
    n = len(text)
    i = 0
    kw = 0
    uses = []
    while i < n:
        if text[i:i + 2] == '//':
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if text[i:i + 2] == '/*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == BS:
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            i = min(j, n)
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if text[j] == BS:
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                if text[j] == '\n':
                    break
                j += 1
            i = min(j, n)
            continue
        if c == '`':
            k = text.find('`', i + 1)
            if k >= 0 and '\n' not in text[i:k]:
                i = k + 1
            else:
                i += 1
            continue
        if c.isalpha() or c == '_':
            j = i
            while j < n and (text[j].isalnum() or text[j] == '_'):
                j += 1
            word = text[i:j]
            if word == 'fn':
                m = j
                while m < n and text[m] in ' \t\r\n':
                    m += 1
                if m < n and (text[m].isalpha() or text[m] == '_'):
                    kw += 1
                else:
                    line_start = text.rfind('\n', 0, i) + 1
                    line_end = text.find('\n', j)
                    if line_end < 0:
                        line_end = n
                    uses.append((text.count('\n', 0, i) + 1,
                                 text[line_start:line_end].strip()))
            i = j
            continue
        i += 1
    return kw, uses


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    total = 0
    files = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ('.git', '.gradle', 'node_modules')]
        for name in sorted(filenames):
            if not name.endswith('.rsf'):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding='utf-8', newline='') as fh:
                text = fh.read()
            kw, uses = scan(text)
            if uses:
                files += 1
                total += len(uses)
                print('== %s  (kw-decl=%d, ident=%d)' % (path, kw, len(uses)))
                for lineno, snippet in uses:
                    print('   %6d  %s' % (lineno, snippet[:110]))
    print('TOTAL ident-fn occurrences: %d in %d file(s)' % (total, files))


if __name__ == '__main__':
    main()
