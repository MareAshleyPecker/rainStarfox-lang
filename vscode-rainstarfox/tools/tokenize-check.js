// 语法自检：用 vscode-textmate 真正跑一遍 tmLanguage，打印每段文本命中的作用域。
//
// VS Code 用的就是同一套引擎（vscode-textmate + Oniguruma），所以这个脚本报出来的
// 作用域就是编辑器里实际会用的。用来防「改了正则但没发现某段不再高亮」这类回归。
//
//   npm install
//   npm run check-grammar
//
const fs = require('fs');
const path = require('path');
const vsctm = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

const GRAMMAR = path.join(__dirname, '..', 'syntaxes', 'rainStarfox.tmLanguage.json');
const SCOPE = 'source.rsf';

/**
 * 期望的断言表。一条是 `[文本, 期望作用域]`，也可以写成：
 *
 *   [文本, '!作用域']                 断言这段文本**没有**这个作用域（负断言）
 *   [文本, 期望作用域, '样本里的片段'] 只在包含该片段的样本行里查（正负断言都适用）
 *
 * 样本片段都用**只在这条样本里出现**的标识符，免得两条样本互相污染断言。
 */
const EXPECTATIONS = [
  // ---- 修饰词 / 函数头
  ['public', 'storage.modifier.rsf'],
  ['protected', 'storage.modifier.rsf'],
  ['private', 'storage.modifier.rsf'],
  ['static', 'storage.modifier.rsf'],
  // 阶段 20：可选的 override，两种顺序都要上色
  ['override', 'storage.modifier.rsf'],
  ['fun', 'storage.type.function.rsf'],
  ['add', 'entity.name.function.rsf'],
  ['int', 'storage.type.rsf'],
  ['var', 'storage.modifier.rsf'],
  ['imutvar', 'storage.modifier.rsf'],
  ['if', 'keyword.control.rsf'],
  ['else', 'keyword.control.rsf'],
  ['while', 'keyword.control.rsf'],
  ['break', 'keyword.control.rsf'],
  ['for', 'keyword.control.rsf'],
  ['in', 'keyword.control.rsf'],
  ['step', 'keyword.control.rsf'],
  ['rtn', 'keyword.control.flow.rsf'],
  ['return', 'keyword.control.flow.rsf'],
  ['goto', 'keyword.control.flow.rsf'],
  ['true', 'constant.language.rsf'],
  ['null', 'constant.language.rsf'],
  ['println', 'support.function.builtin.rsf'],

  // ---- 类型关键字（逐个核对 Lexer.kt 的 KEYWORDS）
  ['void', 'storage.type.rsf'],
  ['int', 'storage.type.rsf'],
  ['long', 'storage.type.rsf'],
  ['float', 'storage.type.rsf'],
  ['double', 'storage.type.rsf'],
  ['string', 'storage.type.rsf'],
  ['bool', 'storage.type.rsf'],
  ['byte', 'storage.type.rsf'],
  ['char', 'storage.type.rsf'],

  // ---- 阶段 16：字符字面量与转义、新的内置函数
  ['A', 'string.quoted.single.rsf'],
  ['\\u0041', 'constant.character.escape.rsf'],
  ['isDigit', 'support.function.builtin.rsf'],
  ['parseInt', 'support.function.builtin.rsf'],
  ['42', 'constant.numeric.integer.rsf'],
  ['9000000000L', 'constant.numeric.integer.rsf'],
  ['3.14', 'constant.numeric.float.rsf'],
  ['1.5f', 'constant.numeric.float.rsf'],
  ['hi', 'string.quoted.double.rsf'],

  // ---- 运算符
  ['->', 'keyword.operator.return.rsf'],
  ['::', 'keyword.operator.return.rsf'],
  ['>=', 'keyword.operator.comparison.rsf'],
  ['==', 'keyword.operator.comparison.rsf'],
  ['+', 'keyword.operator.arithmetic.rsf'],
  ['*', 'keyword.operator.arithmetic.rsf'],
  ['..', 'keyword.operator.range.rsf'],
  ['?:', 'keyword.operator.elvis.rsf'],
  ['!!', 'keyword.operator.force.rsf'],
  ['import', 'keyword.control.import.rsf'],
  ['module', 'keyword.control.import.rsf'],

  // ---- 阶段 14 的错误处理关键字（阶段 13 补的债）
  ['fail', 'keyword.control.error.rsf'],
  ['try', 'keyword.control.error.rsf'],
  ['catch', 'keyword.control.error.rsf'],

  // ---- 阶段 12 / 15 / 18 / 19：类、泛型、构造器、新关键字
  ['[]', 'storage.type.array.rsf'],
  ['constructor', 'storage.type.function.rsf'],
  ['.', 'punctuation.accessor.rsf'],
  ['loop', 'entity.name.label.rsf'],
  ['pkg', 'keyword.control.import.rsf'],
  ['class', 'storage.type.class.rsf'],
  ['new', 'keyword.other.rsf'],
  ['this', 'keyword.other.rsf'],
  ['&<', 'punctuation.generics.rsf'],
  ['Anchor', 'entity.name.type.class.rsf'],
  // let 已经换成 var / imutvar，但词法器里仍是保留字：得看得出来它不是普通标识符
  ['let', 'invalid.deprecated.rsf'],

  // ---- 阶段 19b：变量 / 字段的后缀类型标注 `名字 : 类型`
  ['tally', 'variable.other.rsf', 'var tally : int'],
  ['plain', 'variable.other.rsf', 'var plain = '],
  ['slot', 'variable.other.rsf', 'var slot : string'],
  ['Marker', 'storage.type.rsf', 'var spot : Marker'],

  // ---- 阶段 19：参数两种风格都要高亮对
  ['who', 'variable.parameter.rsf', 'fun greet('],
  ['times', 'variable.parameter.rsf', 'fun greet('],
  ['id', 'variable.parameter.rsf', 'fun find('],
  ['x0', 'variable.parameter.rsf', 'class Q('],
  ['tag', 'variable.parameter.rsf', 'constructor(tag'],

  // ---- 阶段 19b：catch 两种写法
  ['err', 'variable.other.rsf', 'catch (err : string)'],
  ['caught', 'variable.other.rsf', 'catch (caught string)'],

  // ---- 负断言：返回子句只剩 -> 和 ::，老写法 `: T` 不该被当成返回子句
  ['LegacyMarker', '!keyword.operator.return.rsf', 'fun legacy()'],
  ['LegacyMarker', '!storage.type.rsf', 'fun legacy()'],
  // 变量/字段类型标注里那个冒号是「类型分隔符」，不是返回子句
  [':', '!keyword.operator.return.rsf', 'var tally : int'],
];

const SAMPLES = [
  // ---- 文件头：pkg / import / module
  'pkg demo.util',
  'import java.lang.Math',
  'import "lib/util.rsf"',
  'module "plugins"',

  // ---- 函数头与返回子句（只有 -> 和 ::）
  'public fun add() :: int {',
  'protected static fun util() :: int {',
  'public override fun overrideBoth() :: int {',
  'override public fun flippedOverride() :: int {',
  'private fun hidden() -> int {',
  'fun main() -> void {',
  'fun find(int id) :: ?string {',
  // 阶段 19：参数两种风格混写在同一个表里
  'fun greet(who : string, int times) -> string {',
  // 老写法 `: T` 返回子句已经删掉 —— 用它来断言「不会被当成返回子句 / 不会被当成类型」
  'fun legacy() : LegacyMarker { rtn 1 }',

  // ---- 变量与字段（阶段 19 起只认后缀式 `名字 : 类型`）
  '    var tally : int = 0',
  '    var plain = 42',
  '    var spot : Marker = null',
  '    imutvar big = 9000000000L',
  '    imutvar pi = 3.14',
  '    imutvar f = 1.5f',
  '    var l : long = 1L',
  '    var ratio : float = 1.5f',
  '    var prec : double = 2.5',
  '    var flag : bool = true',
  '    var e = tally + 2 * 3 - 1 / 2 % 3',

  // ---- 控制流
  '    if (tally >= 1) : println("hi")',
  '    else : rtn null',
  '    if (tally == 1) { }',
  '    while (true) { break }',
  '    for (i in 0..9 step 2) { println(i) }',
  'loop:',
  '    goto loop',
  '    return',

  // ---- 可空与错误处理
  '    var s = find(1) ?: "none"',
  '    println(s!!)',
  '    println(Math.max(tally, 1))',
  '    try {',
  '        fail "空字符串"',
  '    } catch (err : string) {',
  '        println(err)',
  '    }',
  '    } catch (caught string) {',

  // ---- 阶段 16：byte / char、字符字面量与转义、新的内置函数
  '    var b : byte = 100',
  "    imutvar c : char = 'a'",
  "    imutvar upper = 'A'",
  "    imutvar nl = '\\n'",
  "    var esc = '\\u0041'",
  '    if (isDigit(c)) : println(toUpper(c))',
  '    var n = parseInt("42") ?: 0',

  // ---- 阶段 13：数组类型、创建与下标
  '    var xs : int[] = new int[5]',
  '    var names : string[] = new string[3]',
  '    var grid : int[][] = new int[3][4]',
  '    xs[0] = 1',
  '    println(xs.length + xs[0])',

  // ---- 阶段 12 / 18：类、主构造、次构造
  'public class Anchor {',
  '    var slot : string = "x"',
  '}',
  'public class Q(x0 : int, y0 : int) {',
  '    constructor(tag : string) {',
  '        this(parseInt(tag) ?: 0, 0)',
  '    }',
  '}',

  // ---- 阶段 15：泛型 `&<...>`
  '    var boxed : Box&<int> = new Box&<int>(0)',

  // ---- let 已经不是合法语法（词法器里仍是保留字）
  '    let old = 1',
];

async function main() {
  const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')).buffer;
  await oniguruma.loadWASM(wasm);
  const onigLib = Promise.resolve({
    createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
    createOnigString: (s) => new oniguruma.OnigString(s),
  });

  const registry = new vsctm.Registry({
    onigLib,
    loadGrammar: async (scopeName) => {
      if (scopeName !== SCOPE) return null;
      return vsctm.parseRawGrammar(fs.readFileSync(GRAMMAR, 'utf8'), GRAMMAR);
    },
  });

  const grammar = await registry.loadGrammar(SCOPE);
  if (!grammar) throw new Error('语法加载失败：' + GRAMMAR);

  // 每行单独从 INITIAL 开始切（样本都是单行片段），收集 片段文本 → 作用域集合
  const perLine = SAMPLES.map((line) => {
    const result = grammar.tokenizeLine(line, vsctm.INITIAL);
    const map = new Map();
    for (const t of result.tokens) {
      const text = line.substring(t.startIndex, t.endIndex);
      if (!map.has(text)) map.set(text, new Set());
      for (const s of t.scopes) map.get(text).add(s);
    }
    return { line, result, map };
  });

  console.log('--- 每个记号命中的作用域 ---');
  for (const { line, result } of perLine) {
    console.log(line);
    for (const t of result.tokens) {
      const text = line.substring(t.startIndex, t.endIndex);
      if (!text.trim()) continue;
      console.log(`    ${JSON.stringify(text).padEnd(20)} ${t.scopes[t.scopes.length - 1]}`);
    }
  }

  /**
   * 某个 片段文本 在（可选的）样本行过滤下，命中的全部作用域。
   *
   * 与记号进行匹配时不能只做精确相等：语法经常把分隔符和内容打进同一个作用域，
   * 于是记号文本会是 `: LegacyMarker`、`-> int` 这种带前缀的样子，精确比较会查不到，
   * 断言就会误报成「样本里没出现」。所以这里再接受一种情况——去掉首尾空白后
   * **以目标结尾**，且目标前面是一个标识符边界（避免把 `XLegacyMarker` 也算命中）。
   */
  function scopesOf(text, lineFilter) {
    const lines = lineFilter ? perLine.filter((p) => p.line.includes(lineFilter)) : perLine;
    if (lines.length === 0) return null;
    const hit = new Set();
    let appeared = false;
    const matches = (tokenText) => {
      const t = tokenText.trim();
      if (t === text) return true;
      if (!t.endsWith(text)) return false;
      const before = t[t.length - text.length - 1];
      return before !== undefined && !/[A-Za-z0-9_]/.test(before);
    };
    for (const { map } of lines) {
      for (const [tokenText, set] of map) {
        if (!matches(tokenText)) continue;
        appeared = true;
        for (const s of set) hit.add(s);
      }
    }
    return appeared ? hit : null;
  }

  console.log('\n--- 断言 ---');
  let failed = 0;
  for (const [text, expected, lineFilter] of EXPECTATIONS) {
    const negative = expected.startsWith('!');
    const scope = negative ? expected.slice(1) : expected;
    const scopes = scopesOf(text, lineFilter);
    const ok = scopes !== null && (negative ? !scopes.has(scope) : scopes.has(scope));
    if (!ok) failed++;
    const what = negative ? `不应是 ${scope}` : `期望 ${scope}`;
    console.log(
      `${ok ? 'OK  ' : 'FAIL'} ${JSON.stringify(text).padEnd(20)} ${what}` +
        (lineFilter ? `  [在 ${JSON.stringify(lineFilter)} 里]` : '') +
        (scopes === null ? '  (样本里没出现)' : ''),
    );
  }

  if (failed) {
    console.error(`\n${failed} 条断言失败`);
    process.exitCode = 1;
    return;
  }
  console.log(`\n全部 ${EXPECTATIONS.length} 条断言通过`);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
