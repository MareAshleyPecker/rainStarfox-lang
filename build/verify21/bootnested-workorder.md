**运行时基线（未做，`n4_nested_runtime.rsf` 实测）**

- Kotlin 两条路都输出 `run:hi` / `run:yo` / `2` / `run:slot`。
- 自举后端：`[rsf-17d] 编译失败：变量 'm' 声明时的类型没定义`；自举解释器：`运行出错: 编译失败：变量 'm' 声明时的类型没定义` ⇒ 就是本刀要消的两条红。

**待做**：`Backend.rsf` / `Interp.rsf` 的运行时归一 `X::Y` → `X$Y`（§3 最后一条），以及把 `n4` 挂进 `run-probes.sh`（**等第 4b 刀交付后再动 `run-probes.sh`，避免与它的验收撞车**）。

