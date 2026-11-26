# 标准库（stdlib）

工具链共享的一份标准库目录。

- `rsf_runtime.h` —— **生成的**，不进版本库：它由 `CBackend.runtimeLibSource()`
  派生，`rsf build` 每次按需重写（内容不同才写）。生成的 `.c` 靠
  `#include "rsf_runtime.h"` 找它，所以 gcc 命令行要带 `-I stdlib`。
- 以后用 rsf 写的标准库模块（`core/*.rsf` 之类）也放这里，构建时默认进 import 搜索路径。

查找顺序见 `org.example.build.Stdlib`：`RSF_STDLIB` → 编译器位置往上找 →
当前目录往上找 → `./stdlib`。
