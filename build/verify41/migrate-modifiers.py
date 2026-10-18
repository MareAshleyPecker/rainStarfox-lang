import io, os, re, sys

MAP = {"public": "pub", "private": "prv", "protected": "prot", "static": "stc"}
WORD = re.compile(r"\b(" + "|".join(MAP) + r")\b")

def replace_word(m):
    return MAP[m.group(1)]

def is_ident(ch):
    return ch.isalnum() or ch == "_"

def rewrite_code(text):
    # .rsf：全局按整词替换（字符串/注释一起改，关键词在注释里也保持字面一致）
    return WORD.sub(replace_word, text)

def rewrite_literal(lit):
    quote = lit[0] if len(lit) >= 2 and lit[0] in ('"', "'") and lit[-1] == lit[0] else None
    body = lit[1:-1] if quote else lit
    if body.strip() in MAP:
        idx = body.index(body.strip())
        return quote + body[:idx] + MAP[body.strip()] + body[idx+len(body.strip()):] + quote
    if re.search(u"[一-鿿]", body) or re.search(r"\[(public|private|protected|static)[|\]]", body) or ("@" in body and WORD.search(body)):
        return quote + WORD.sub(replace_word, body) + quote
    chain = re.compile(r"\b(public|private|protected|static)\b(?=\s+(fn|class|var|val|imutvar|interface|enum|annotation|companion|constructor|override|final|abstract|new|public|private|protected|static|pub|prv|prot|stc)\b)")
    return chain.sub(replace_word, lit)

def rewrite_kt_literals(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        # 注释直接跳过（逐字保留）
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            if j == -1:
                out.append(text[i:]); break
            out.append(text[i:j]); i = j; continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(text[i:j]); i = j; continue
        # 原始字符串 """..."""
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append(rewrite_literal(text[i:j])); i = j; continue
        # 普通字符串
        if ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2; continue
                if text[j] == '"':
                    j += 1; break
                j += 1
            out.append(rewrite_literal(text[i:j])); i = j; continue
        # 字符字面量
        if ch == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2; continue
                if text[j] == "'":
                    j += 1; break
                j += 1
            out.append(text[i:j]); i = j; continue
        out.append(ch); i += 1
    return "".join(out)

MD_MODIFIER = re.compile(r"\b(public|private|protected|static)\b(?=\s+(fn|class|var|val|imutvar|interface|enum|annotation|companion|constructor|override|final|abstract|new)\b)")

def rewrite_md(text):
    return MD_MODIFIER.sub(replace_word, text)

def process(root, exts):
    changed = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build", ".gradle", ".idea", "out", "node_modules")]
        for name in filenames:
            ext = os.path.splitext(name)[1]
            if ext not in exts:
                continue
            p = os.path.join(dirpath, name)
            with io.open(p, encoding="utf-8", newline="") as f:
                src = f.read()
            if ext == ".rsf":
                new = rewrite_code(src)
            elif ext == ".kt":
                new = rewrite_kt_literals(src)
            else:
                new = rewrite_md(src)
            if new != src:
                with io.open(p, "w", encoding="utf-8", newline="") as f:
                    f.write(new)
                changed.append(p)
    return changed

if __name__ == "__main__":
    targets = sys.argv[1:]
    all_changed = []
    for t in targets:
        if os.path.isdir(t):
            all_changed += process(t, {".rsf", ".kt", ".md"})
        else:
            ext = os.path.splitext(t)[1]
            with io.open(t, encoding="utf-8", newline="") as f:
                src = f.read()
            new = rewrite_code(src) if ext == ".rsf" else (rewrite_kt_literals(src) if ext == ".kt" else rewrite_md(src))
            if new != src:
                with io.open(t, "w", encoding="utf-8", newline="") as f:
                    f.write(new)
                all_changed.append(t)
    print("changed files:", len(all_changed))
    for c in all_changed[:10]:
        print("  ", c)
