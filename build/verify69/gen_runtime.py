#!/usr/bin/env python3
"""从 CBackend.kt 反推 rsfRuntimelib.h（复刻 runtimeLibSource 的四步变换）。

为什么要有这个脚本：运行时只有一个事实源（Kotlin 里的 RUNTIME / RuntimeErase /
SHARED_STATE / FIX_FUNCS 四个原始字符串），但 native 线需要能**在没有 JDK 的机器上**
单独编译、跑 ASan 验证那份头文件。脚本把「Kotlin 字符串 → 头文件」这一步照抄一遍，
于是 C 侧的改动可以只用 gcc 验证；Kotlin 侧只要保证同样的字符串就等价。

用法：
  python3 gen_runtime.py <CBackend.kt> -o rsfRuntimelib.h
  python3 gen_runtime.py <CBackend.kt> --self-check   # 与仓库里的旧头文件对拍
"""

import argparse
import re
import sys


def read_raw_string(src: str, name: str) -> str:
    """取出 `private val NAME = \"\"\"...\"\"\"` 的内容，并按需做 trimIndent。"""
    pat = re.compile(r'private val %s\s*=\s*"""' % re.escape(name))
    m = pat.search(src)
    if m is None:
        raise SystemExit("找不到常量 " + name)
    start = m.end()
    end = src.index('"""', start)
    body = src[start:end]
    after = src[end + 3:end + 40]
    if "trimIndent()" in after:
        body = trim_indent(body)
    return body


def trim_indent(s: str) -> str:
    """Kotlin trimIndent：去掉首尾空行 + 公共最小缩进（空行不参与计算）。"""
    lines = s.split("\n")
    if lines and lines[0].strip() == "":
        lines = lines[1:]
    if lines and lines[-1].strip() == "":
        lines = lines[:-1]
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    m = min(indents) if indents else 0
    lines = [l[m:] if len(l) >= m else l for l in lines]
    return "\n".join(lines)


def build(src: str) -> str:
    runtime = read_raw_string(src, "RUNTIME")
    erase = read_raw_string(src, "RuntimeErase")
    prefix = read_raw_string(src, "LIB_PREFIX")
    shared = read_raw_string(src, "SHARED_STATE")
    fix = read_raw_string(src, "FIX_FUNCS")

    body = runtime + erase

    # 1) 去掉文件级 error 变量（改由 extern + RSF_RUNTIME_IMPL 提供唯一份）
    body = body.replace("static bool rsf_err = false;\n", "")
    body = body.replace("static rsf_str rsf_err_msg;\n", "")

    # 2) 顶层 static 函数 -> static inline
    body = re.sub(r"(?m)^static ", "static inline ", body)

    # 3) 分配点 -> 分配器入口
    body = body.replace("malloc(", "rsf_malloc(")
    body = body.replace("calloc(", "rsf_calloc(")

    # 分配点必须全部走分配器（与 Kotlin 侧同一道守卫）
    hit = re.search(r"(?<![A-Za-z0-9_])(malloc|calloc|realloc|free)\(", body)
    if hit:
        raise SystemExit("运行时里还有裸内存调用：%r（偏移 %d）" % (hit.group(0), hit.start()))


    # 4) 在 rsf_arr_new 前插入共享状态 + 分配器实现
    marker = "static inline rsf_arr rsf_arr_new"
    idx = body.find(marker)
    if idx < 0:
        raise SystemExit("运行时里找不到 rsf_arr_new，无法定位插入点")
    head, tail = body[:idx], body[idx:]
    return prefix + head + shared + fix + tail + "#endif /* RSF_RUNTIME_LIB_H */\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("kt")
    ap.add_argument("-o", "--out")
    ap.add_argument("--self-check", action="store_true",
                    help="与仓库里已有的旧头文件逐字节对拍（验证脚本本身正确）")
    a = ap.parse_args()

    src = open(a.kt, encoding="utf-8").read()
    out = build(src)

    if a.self_check:
        old = open(a.out, encoding="utf-8").read()
        if old == out:
            print("OK：脚本生成的与旧头文件逐字节相同")
            return 0
        import difflib
        diff = list(difflib.unified_diff(old.splitlines(), out.splitlines(),
                                         "旧头文件", "脚本生成", lineterm=""))
        print("\n".join(diff[:80]))
        print("差异行数:", len(diff))
        return 1

    if not a.out:
        sys.stdout.write(out)
        return 0

    with open(a.out, "w", encoding="utf-8") as f:
        f.write(out)
    print("已写出", a.out, len(out), "字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
