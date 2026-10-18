#!/usr/bin/env python3
"""Temporary report helper: per-directory counts of migrated rsf tokens.

Counts code-position `fn` keyword tokens (each one was `fun` before the
migration) plus `fnNode` identifiers (renamed from identifier `fn`).
"""
import os
import re
import sys

BS = chr(92)


def scan(text):
    n = len(text)
    i = 0
    kw = 0
    node = 0
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
            elif word == 'fnNode':
                node += 1
            i = j
            continue
        i += 1
    return kw, node


def main():
    root = sys.argv[1]
    stats = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('.git', '.gradle')]
        for name in filenames:
            if not name.endswith('.rsf'):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding='utf-8', newline='') as fh:
                text = fh.read()
            kw, node = scan(text)
            if kw == 0 and node == 0:
                continue
            rel = os.path.relpath(path, root).replace(os.sep, '/')
            parts = rel.split('/')
            if parts[0] == 'build' and len(parts) > 1:
                key = 'build/' + parts[1]
            else:
                key = parts[0] if len(parts) > 1 else '(root)'
            f, c = stats.get(key, (0, 0))
            stats[key] = (f + 1, c + kw + node)
    tot_f = tot_c = 0
    for key in sorted(stats):
        f, c = stats[key]
        tot_f += f
        tot_c += c
        print('%-16s %4d files %6d occurrences' % (key, f, c))
    print('TOTAL            %4d files %6d occurrences' % (tot_f, tot_c))


if __name__ == '__main__':
    main()
