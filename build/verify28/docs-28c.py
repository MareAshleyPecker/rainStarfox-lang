import io, sys

def read(p):
    with io.open(p, 'r', encoding='utf-8', newline='') as f:
        return f.read()

def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)

# ---------- README ----------
rp = 'README.md'
r = read(rp)
old27 = "| 27 | **默认开放**：删掉 `open` 关键字，类默认可继承、方法默认可覆盖，`final` 成为唯一收窄手段（推翻阶段 25 的「默认 final」） | 🔄 2026-10-17 开工（`DESIGN-27.md`；27a Kotlin 侧 / 27b 自举同步 / 27c 收口） |"
new27 = "| 27 | **默认开放**：删掉 `open` 关键字，类默认可继承、方法默认可覆盖，`final` 成为唯一收窄手段（推翻阶段 25 的「默认 final」） | ✅ 2026-10-18（27a `5df701f` Kotlin 前端 / 27b `20a5e13` 自举同步，关键字 57→56；判据 `build/verify27/` 五条 + `build/verify24` 回归全绿） |"
row28 = "\n| 28 | **标签名特殊字符 + 元数据**：标签名可用反引号包任意字符（`` `a-b` ``），每个有标签的方法发一条 runtime-visible `@RsfLabels(value = string[])`，按声明顺序把名字保留进 class 文件 | ✅ 2026-10-18（28a `5edbd27` Kotlin 侧 / 28b `cc1bf17` 自举链；判据 `build/verify28/`：4 正例 + 5 负例 + 两端 class 逐字节对拍；564 tests） |"
assert old27 in r, 'README 27 row not found'
r = r.replace(old27, new27 + row28, 1)
write(rp, r)

# ---------- BOOTSTRAP ----------
bp = 'BOOTSTRAP.md'
b = read(bp)
anchor25 = "| 25 · 类继承 |"
assert anchor25 in b, 'BOOTSTRAP 25 row not found'
brows = (
    "| 28 · 标签名特殊字符 + `@RsfLabels` 元数据 | ✅ **完成（2026-10-18，两刀）**：标签名可用反引号包任意字符（`` `a-b`: ``、``goto `a-b` ``），反引号只给标签名用；每个**有标签**的方法发一条 runtime-visible `@RsfLabels(value = string[])`（声明顺序、不生成注解接口类、描述符悬空、没有标签的方法零字节变化）。28a `5edbd27`（Token/Lexer/Parser/BytecodeCompiler + IDEA 词法 + `LabelMetadataTest`）、28b `cc1bf17`（`rsfSrc/bootstrap/{Lexer,Parser,Backend}.rsf`，asmTable 31→32 补 `AnnotationVisitor.visitArray`）；判据 `build/verify28/`：4 正例 + 5 负例，`bootstrap-parity.sh` 两端 class 逐字节相同（含注解字节），J1 148 类 / diff 0 / 564 tests |\n"
    "| 27 · 默认开放（删 `open`） | ✅ **完成（2026-10-18，两刀）**：类默认可继承、方法默认可覆盖，`final` 是唯一收窄手段；`open` 变成普通标识符（`open class` 是语法错误），关键字 57→56。27a `5df701f`（Kotlin 词法/AST/语法/语义 + 测试与语料迁移 173 + 35 + 1 处）、27b `20a5e13`（自举 Lexer/Parser/Backend/Interp 同步，`InheritMods` 降为两参）；判据 `build/verify27/` 五条（pos/interp/neg/ast/parity）全 0 + `build/verify24` 回归，551 tests；见 `DESIGN-27.md` |\n"
)
b = b.replace(anchor25, brows + anchor25, 1)

para_anchor = "**第 26 刀：`LocalVariableTable`（2026-10-17）**"
assert para_anchor in b
para = (
    "\n**第 28 刀：标签名特殊字符与 `@RsfLabels`（2026-10-18）**：标签名现在有两种写法——普通标识符，或用反引号包住任意字符（`` `a-b`: ``，`goto` 后面同理）；反引号**只**给标签名用，变量 / 字段 / 函数名仍只收 Unicode 字母、数字、下划线，写错位置会得到专门文案。标签仍是函数作用域、查重在**去掉引号之后**的名字上做，所以 `` `finish` `` 与 `finish` 是同一个标签。每个**有标签**的方法在 `visitMaxs` 之前发一条 runtime-visible 注解 `@RsfLabels(value = string[])`，`value` 按**声明顺序**列出该方法里的标签名（`goto` 首次提到但没声明的不算），描述符故意悬空、不生成注解接口类——`javap -v` 与 ASM 看得见，反射读不到；没有用户标签的方法一个字节都不发，旧语料产物逐字节不变。两端（Kotlin `BytecodeCompiler.emitLabelMetadata` 与自举 `Backend.rsf` 的 `flushBody`）产出的 class 由 `build/verify28/bootstrap-parity.sh` 逐字节对拍。"
)
b = b.replace(para_anchor, para + "\n" + para_anchor, 1)
write(bp, b)
print('ok')
