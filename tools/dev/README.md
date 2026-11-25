# tools/dev —— 开发循环脚本

这台机器上没有 JDK 25（`build.gradle` 里的 `jvmToolchain(25)` 过不去），
所以这几个脚本绕开 gradle，直接用 `~/.gradle/caches` 里的 kotlinc 与依赖编译。

| 脚本 | 作用 |
|---|---|
| `kb.sh` | 编译 Kotlin 编译器本体 → `build/dev/classes` |
| `native.sh` | 用刚编好的编译器把 `InterpCli.rsf` 编成 C，再用 gcc 出 `build/dev/native/interp` |
| `parity.sh` | 九个示例：原生解释器 vs Kotlin 版解释器，逐字节对拍 |
| `quick.sh` | 上面三步一条命令跑完 |
| `probe.sh` | 用原生解释器跑单个 `.rsf`；`--cmp` 顺带和 Kotlin 版比 |

产物全部在 `build/dev/`（已被 `.gitignore` 忽略），源码里不留垃圾。

## 典型用法

```sh
sh tools/dev/quick.sh                  # 全量回归，正常只打两行
sh tools/dev/probe.sh --cmp /tmp/x.rsf # 单个探针，顺便对拍
```

## 注意

- `kb.sh` 依赖 `~/.gradle/caches/modules-2/files-2.1` 里的
  `kotlin-compiler-embeddable:2.3.10` 和 `asm:9.9.1`；换机器先把 `~/.gradle` 拷过来。
- 原生解释器读输入用环境变量 `rsf.interp.file`（rsf 的 `main` 接不到命令行参数，
  这是语言当前的一个缺口）。
