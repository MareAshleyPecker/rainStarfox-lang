# tools/

按用途分两个目录，别混用。

## dev/ —— 日常开发循环

绕开 gradle 编译、跑对拍用的脚本（本机没有 JDK 25，`jvmToolchain(25)` 过不去）。
详见 [dev/README.md](dev/README.md)。

```sh
sh tools/dev/quick.sh                    # 编编译器 → 出原生解释器 → 九示例对拍
sh tools/dev/probe.sh --cmp /tmp/x.rsf   # 单文件探针 + 与 Kotlin 版对拍
```

`devbuild.ps1` / `rundev.ps1` 是同一批事的 PowerShell 版本（Windows 侧用）。

## recovery/ —— 一次性事故恢复工具

2026-11-19 那次 **CP936/UTF-8 双重编码事故**期间写的一次性工具，用来定位、恢复、
校验被吃掉的中文与被合并的代码行。全部是当时用过的临时工具，**日常不需要**，
保留是为了可复现、可审计（想复查当时怎么判定的，直接看这些脚本）。

- `*.java`：扫描/比对/恢复/校验（`Recover2` 是主力，`VerifyFrags` 拿 class 文件里的
  中文字符串做金标比对）
- `*.ps1`：同一套流程的 PowerShell 封装
- `_probe_line.txt`：当时的单行探针样本

注意：这些脚本里有些路径是写死的、有些只支持 Windows PowerShell 5.1（会被 ANSI
代码页影响），照原样保留，不做现代化改造。
