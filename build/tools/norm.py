#!/usr/bin/env python3
# 把 javap -p -c 的输出归一化，用来比较「同一份源码、不同 class 写出器」的产物：
#   * 去掉指令偏移，把跳转目标换算成「方法内第几条指令」
#   * 常量压栈的编码方式归一（iconst_0 / bipush 0 / ldc 0 等价），值取 javap 的注释
#   * ldc_w 与 ldc 归一，常量池编号一律 #N
import re
import sys

JUMPS = set('ifeq ifne iflt ifge ifgt ifle if_icmpeq if_icmpne if_icmplt if_icmpge '
            'if_icmpgt if_icmple if_acmpeq if_acmpne goto goto_w jsr jsr_w ifnull ifnonnull'.split())
WIDE = {'aload': 25, 'astore': 58, 'iload': 21, 'istore': 54, 'lload': 22, 'lstore': 55,
        'fload': 23, 'fstore': 56, 'dload': 24, 'dstore': 57}
ICONST = {'iconst_m1': '-1', 'iconst_0': '0', 'iconst_1': '1', 'iconst_2': '2',
          'iconst_3': '3', 'iconst_4': '4', 'iconst_5': '5',
          'lconst_0': '0', 'lconst_1': '1'}


def canon(text):
    out = []
    ins = []

    def flush():
        if not ins:
            return
        off2idx = {ins[i][0]: i for i in range(len(ins))}
        for off, op, rest, comment in ins:
            toks = rest.split()
            if op in JUMPS and toks and toks[0].lstrip('-').isdigit():
                toks[0] = '@%d' % off2idx.get(int(toks[0]), -1)
            if op in ICONST:
                val = ICONST[op]
                op = 'ldc2_w' if op.startswith('lconst') else 'ldc'
                toks = [val]
            elif op in ('bipush', 'sipush'):
                op, toks = 'ldc', toks[:1]
            elif op in ('ldc', 'ldc_w', 'ldc2_w'):
                if comment:
                    toks = [re.sub(r'\s+', ' ', comment)]
                if op == 'ldc_w':
                    op = 'ldc'
            toks = [re.sub(r'#[0-9]+', '#N', t) for t in toks]
            out.append(('%s %s' % (op, ' '.join(toks))).strip())
        ins.clear()

    for ln in text.split('\n'):
        ln = ln.rstrip()
        if re.match(r'  \S.*\(.*\).*$', ln) and not ln.strip().startswith(
                ('Code:', 'Exception', 'LineNumber', 'LocalVariable', 'StackMap')):
            flush()
            out.append('## ' + re.sub(r'\s+', ' ', ln.strip()))
            continue
        m = re.match(r'\s+(\d+): (\S+)\s*(.*)', ln)
        if not m:
            continue
        off, op = int(m.group(1)), m.group(2)
        raw = m.group(3)
        rest = raw.split('//')[0].strip()
        comment = raw.split('//', 1)[1].strip() if '//' in raw else ''
        mm = re.match(r'([a-z]+)_([0-3])$', op)
        if mm and mm.group(1) in WIDE:
            op, rest = mm.group(1), mm.group(2) + ' ' + rest
        ins.append((off, op, rest, comment))
    flush()
    return out


print('\n'.join(canon(sys.stdin.read())))
