#!/usr/bin/env python3
"""Temporary fixer: rename identifier uses of `fn` to `fnNode` in an rsf file.

Only touches code regions (skips comments/strings/char literals/backtick
labels) and only tokens whose next non-space char is not an identifier start
(so declaration keywords `fn name(...)` are left alone).
"""
import sys

BS = chr(92)


def fix(text):
    out = []
    n = len(text)
    i = 0
    changed = 0
    while i < n:
        if text[i:i + 2] == '//':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(text[i:j])
            i = j
            continue
        if text[i:i + 2] == '/*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(text[i:j])
            i = j
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
            j = min(j, n)
            out.append(text[i:j])
            i = j
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
            j = min(j, n)
            out.append(text[i:j])
            i = j
            continue
        if c == '`':
            k = text.find('`', i + 1)
            if k >= 0 and '\n' not in text[i:k]:
                out.append(text[i:k + 1])
                i = k + 1
            else:
                out.append(c)
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
                if not (m < n and (text[m].isalpha() or text[m] == '_')):
                    out.append('fnNode')
                    changed += 1
                    i = j
                    continue
            out.append(word)
            i = j
            continue
        out.append(c)
        i += 1
    return ''.join(out), changed


def main():
    path = sys.argv[1]
    apply = '--apply' in sys.argv
    with open(path, encoding='utf-8', newline='') as fh:
        text = fh.read()
    new, changed = fix(text)
    print('%s: ident-fn replaced = %d' % (path, changed))
    if apply and changed:
        with open(path, 'w', encoding='utf-8', newline='') as fh:
            fh.write(new)


if __name__ == '__main__':
    main()
