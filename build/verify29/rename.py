#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 29：把语言关键字 `fun` 改名为 `fn` 的一次性迁移工具。

用法（在仓库根目录跑）：

    python build/verify29/rename.py --dry-run   # 只统计，不落盘
    python build/verify29/rename.py             # 落盘

规则（见 build/verify29/knife-29a-workorder.md §3）：

  *.rsf  只改**代码位置**的独立单词 fun；跳过 `//` 行注释、`/* */` 块注释、
         字符串（含转义）、字符字面量、反引号标签。
  *.kt   只改**字符串字面量内部**的独立单词 fun（含 `"""` 原始串、`"` 普通串的
         文本部分）；Kotlin 自己的代码与注释一个字都不动；`${...}` 里的代码也不动。
  *.md   只改围栏代码块与行内 `代码` 里的独立单词 fun，外加叙述里的 'fun'。

不改的范围：`.git`、`build/verify29/**`（本阶段的探针/判据/设计）、
`build/**/*.md`（历史报告）、`DESIGN-29.md` / `DESIGN-30.md`（它们描述的就是这次改名）。
"""

import argparse
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

# 顶层 md 里不迁的两个：它们逐字记录「fun -> fn」这件事本身。
MD_SKIP = {"DESIGN-29.md", "DESIGN-30.md"}


def is_word_char(c):
    return c.isalnum() or c == "_"


def replace_words(text):
    """把 text 里所有独立单词 fun 换成 fn，返回 (新串, 处数)。"""
    out = []
    i = 0
    n = len(text)
    count = 0
    while i < n:
        c = text[i]
        if is_word_char(c):
            j = i
            while j < n and is_word_char(text[j]):
                j += 1
            if text[i:j] == "fun":
                out.append("fn")
                count += 1
            else:
                out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out), count


def _skip_until(text, i, closing):
    """从 i 起找 closing，返回闭括号之后的下标；找不到返回 len(text)。"""
    j = text.find(closing, i)
    return len(text) if j == -1 else j + len(closing)


def _skip_rsf_opaque(text, i):
    """rsf 的不透明区域（注释/字符串/字符/反引号标签）起点在 i，返回结束下标。"""
    n = len(text)
    c = text[i]
    two = text[i:i + 2]
    if two == "//":
        j = text.find("\n", i)
        return n if j == -1 else j
    if two == "/*":
        return _skip_until(text, i + 2, "*/")
    if c == '"':
        j = i + 1
        while j < n:
            if text[j] == "\\":
                j += 2
                continue
            if text[j] == '"':
                return j + 1
            j += 1
        return n
    if c == "'":
        j = i + 1
        while j < n:
            if text[j] == "\\":
                j += 2
                continue
            if text[j] == "'":
                return j + 1
            if text[j] == "\n":
                return j
            j += 1
        return n
    if c == "`":
        k = text.find("`", i + 1)
        if k == -1 or "\n" in text[i:k]:
            return i + 1
        return k + 1
    return i + 1


def transform_rsf(text):
    """rsf 状态机：代码位置做 fun->fn，其余区域原样拷贝。"""
    out = []
    i = 0
    n = len(text)
    count = 0
    while i < n:
        c = text[i]
        if text[i:i + 2] in ("//", "/*") or c in "\"'`":
            j = _skip_rsf_opaque(text, i)
            out.append(text[i:j])
            i = j
            continue
        if is_word_char(c):
            j = i
            while j < n and is_word_char(text[j]):
                j += 1
            if text[i:j] == "fun":
                out.append("fn")
                count += 1
            else:
                out.append(text[i:j])
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out), count


def _skip_interpolation(text, i):
    """text[i:i+2] == '${'：返回 ${...} 结束（含右花括号）后的下标。"""
    depth = 0
    n = len(text)
    j = i + 1  # 指向 '{'
    while j < n:
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        elif c in "\"'":
            j = _skip_rsf_opaque(text, j)
            continue
        j += 1
    return n


def transform_kt(text):
    """kt 状态机：只在字符串字面量的文本部分替换；代码/注释/插值一律原样。"""
    out = []
    i = 0
    n = len(text)
    count = 0
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(text[i:j])
            i = j
            continue
        if two == "/*":
            depth = 1
            j = i + 2
            while j < n and depth > 0:
                if text[j:j + 2] == "/*":
                    depth += 1
                    j += 2
                elif text[j:j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            out.append(text[i:j])
            i = j
            continue
        if text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            closed = j != -1
            if not closed:
                j = n
            body = text[i + 3:j]
            new_body, c2 = _kt_string_body(body)
            count += c2
            out.append('"""' + new_body + ('"""' if closed else ""))
            i = j + 3 if closed else n
            continue
        if c == '"':
            j = i + 1
            closed = False
            while j < n:
                d = text[j]
                if d == "\\":
                    j += 2
                    continue
                if d == '"':
                    closed = True
                    break
                j += 1
            body = text[i + 1:min(j, n)]
            new_body, c2 = _kt_string_body(body)
            count += c2
            out.append('"' + new_body + ('"' if closed else ""))
            i = j + 1 if closed else n
            continue
        if c == "'":
            j = i + 1
            closed = False
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    closed = True
                    break
                j += 1
            out.append(text[i:min(j + 1, n)])
            i = j + 1 if closed else n
            continue
        out.append(c)
        i += 1
    return "".join(out), count


def _kt_string_body(body):
    """字符串字面量的内容：跳过 ${...} 与转义序列，只替换独立单词 fun。"""
    out = []
    i = 0
    n = len(body)
    count = 0
    while i < n:
        c = body[i]
        if body[i:i + 2] == "${":
            e = _skip_interpolation(body, i)
            out.append(body[i:e])
            i = e
            continue
        if c == "\\":
            out.append(body[i:i + 2])
            i += 2
            continue
        if is_word_char(c):
            j = i
            while j < n and is_word_char(body[j]):
                j += 1
            if body[i:j] == "fun":
                out.append("fn")
                count += 1
            else:
                out.append(body[i:j])
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out), count


FENCE_RE = re.compile(r"^(\s{0,3})(```+|~~~+)")


def transform_md(text):
    """md：围栏代码块整段替换；行内 `代码` 替换；叙述里的 'fun' 替换。"""
    out = []
    count = 0
    fence = None
    for line in text.split("\n"):
        m = FENCE_RE.match(line)
        if fence is not None:
            if m and m.group(2).startswith(fence[:3]):
                fence = None
                out.append(line)
                continue
            new, c = replace_words(line)
            out.append(new)
            count += c
            continue
        if m:
            fence = m.group(2)
            out.append(line)
            continue
        new, c = _md_inline(line)
        out.append(new)
        count += c
    return "\n".join(out), count


def _md_inline(line):
    """行内 `代码` 里的独立单词替换 + 叙述里的 'fun'。"""
    out = []
    i = 0
    n = len(line)
    count = 0
    while i < n:
        if line[i] == "`":
            j = i
            while j < n and line[j] == "`":
                j += 1
            ticks = line[i:j]
            k = line.find(ticks, j)
            if k == -1:
                out.append(line[i:])
                return "".join(out), count
            chunk, c = replace_words(line[j:k])
            count += c
            out.append(ticks + chunk + ticks)
            i = k + len(ticks)
            continue
        out.append(line[i])
        i += 1
    seg = "".join(out)
    seg, c = re.subn(r"'fun'", "'fn'", seg)
    count += c
    return seg, count


def collect():
    groups = {"rsf": [], "kt": [], "md": []}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        rel_dir = os.path.relpath(dirpath, ROOT).replace(os.sep, "/")
        pruned = []
        for d in dirnames:
            rel = d if rel_dir == "." else rel_dir + "/" + d
            if d in (".git", ".gradle", "node_modules") or rel == "build/verify29":
                continue
            pruned.append(d)
        dirnames[:] = pruned
        for name in filenames:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            if name.endswith(".rsf"):
                groups["rsf"].append(path)
            elif name.endswith(".kt"):
                groups["kt"].append(path)
            elif name.endswith(".md"):
                if "/" in rel:
                    continue  # build/** 等处的历史报告不动
                if name in MD_SKIP:
                    continue
                groups["md"].append(path)
    for v in groups.values():
        v.sort()
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    groups = collect()
    fns = {"rsf": transform_rsf, "kt": transform_kt, "md": transform_md}
    total_files = 0
    total_hits = 0
    for suffix in ("rsf", "kt", "md"):
        files = 0
        hits = 0
        print("== *.%s ==" % suffix)
        for path in groups[suffix]:
            with open(path, "r", encoding="utf-8", newline="") as f:
                text = f.read()
            new, count = fns[suffix](text)
            if count:
                rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
                print("  %-72s %4d" % (rel, count))
                files += 1
                hits += count
                if not args.dry_run and new != text:
                    with open(path, "w", encoding="utf-8", newline="") as f:
                        f.write(new)
        print("  -- 文件 %d / 处数 %d" % (files, hits))
        total_files += files
        total_hits += hits
    print("合计：文件 %d / 处数 %d%s" % (total_files, total_hits, "（dry-run，未落盘）" if args.dry_run else ""))

    left = []
    for path in groups["rsf"]:
        with open(path, "r", encoding="utf-8", newline="") as f:
            text = f.read()
        _, count = transform_rsf(text)
        if count:
            left.append((os.path.relpath(path, ROOT).replace(os.sep, "/"), count))
    if left:
        print("残留（rsf 代码位置的 fun）：")
        for rel, c in left:
            print("  %s  %d" % (rel, c))
    else:
        print("残留（rsf 代码位置的 fun）：无")


if __name__ == "__main__":
    main()
