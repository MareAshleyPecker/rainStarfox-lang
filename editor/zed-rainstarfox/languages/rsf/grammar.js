/**
 * Tree-sitter grammar for rainStarfox (.rsf).
 *
 * Design notes
 * ------------
 * rainStarfox separates statements by newlines, but also accepts `;`.  To keep
 * the grammar small and error tolerant (this grammar is used for syntax
 * highlighting, not for compiling), newlines are treated as ordinary
 * whitespace: a statement list is simply a repeated sequence of statements and
 * an optional `;` is accepted as an empty statement.  The consequence is that
 * two statements on one line parse as well, which is fine for highlighting.
 *
 * The authoritative lexical tables live in:
 *   - src/main/kotlin/org/example/complie/lexing/Token.kt   (TokenKind)
 *   - src/main/kotlin/org/example/complie/lexing/Lexer.kt   (KEYWORDS)
 *   - rsfSrc/bootstrap/Lexer.rsf                            (self-hosted lexer)
 *   - SYNTAX.md                                             (language manual)
 */

const PRIMITIVE_TYPES = [
  'void',
  'Int',
  'Long',
  'Float',
  'Double',
  'String',
  'Str',
  'Bool',
  'Boolean',
  'Byte',
  'Char',
];

// Declaration modifiers. Order is free: `pub final class` == `final pub class`.
const MODIFIERS = [
  'pub',
  'prv',
  'prot',
  'stc',
  'override',
  'final',
  'abstract',
  'native',
  'jvm',
];

module.exports = grammar({
  name: 'rsf',

  extras: $ => [
    /\s/,
    $.line_comment,
    $.block_comment,
  ],

  word: $ => $.identifier,

  rules: {
    source_file: $ => repeat($._item),

    _item: $ => choice(
      $.import_declaration,
      $.package_declaration,
      $.module_declaration,
      $.declaration,
      $.statement,
    ),

    // ------------------------------------------------------------------
    // File-level declarations
    // ------------------------------------------------------------------

    import_declaration: $ => seq('import', choice($.string, $._qualified_name)),

    package_declaration: $ => seq('pkg', $._qualified_name),

    module_declaration: $ => seq('module', $._qualified_name),

    type_parameters: $ => seq(
      '&',
      '<',
      $.identifier,
      repeat(seq(',', $.identifier)),
      '>',
    ),

    _qualified_name: $ => seq(
      $.identifier,
      repeat(seq('.', $.identifier)),
    ),

    annotation_use: $ => seq('@', $.type, optional($.arguments)),

    declaration: $ => seq(
      repeat($.annotation_use),
      repeat($.modifier),
      choice(
        $.function_declaration,
        $.class_declaration,
        $.interface_declaration,
        $.enum_declaration,
        $.annotation_declaration,
        $.companion_declaration,
        $.entity_declaration,
        $.constructor_declaration,
      ),
    ),

    modifier: $ => choice(...MODIFIERS),

    function_declaration: $ => prec.right(seq(
      'fn',
      optional('void'),
      field('name', $.identifier),
      optional($.type_parameters),
      $.parameters,
      optional($._return_type),
      optional($.block),
    )),

    _return_type: $ => seq(choice('->', '::', ':'), $.type),

    parameters: $ => seq(
      '(',
      optional(seq(
        $.parameter,
        repeat(seq(',', $.parameter)),
        optional(','),
      )),
      ')',
    ),

    parameter: $ => seq(
      field('name', $.identifier),
      optional($.type_annotation),
      optional(seq('=', $._expression)),
    ),

    class_declaration: $ => seq(
      'class',
      field('name', $.identifier),
      optional($.type_parameters),
      optional($.primary_constructor_parameters),
      optional($._super_types),
      $.class_body,
    ),

    primary_constructor_parameters: $ => seq(
      '(',
      optional(seq(
        $.primary_constructor_parameter,
        repeat(seq(',', $.primary_constructor_parameter)),
        optional(','),
      )),
      ')',
    ),

    primary_constructor_parameter: $ => seq(
      field('name', $.identifier),
      optional($.type_annotation),
      optional(seq('=', $._expression)),
    ),

    _super_types: $ => seq(':', $.super_type, repeat(seq(',', $.super_type))),

    super_type: $ => seq(
      field('name', $.type),
      optional($.arguments),
    ),

    interface_declaration: $ => seq(
      'interface',
      field('name', $.identifier),
      optional($.type_parameters),
      optional(seq(':', $.super_type, repeat(seq(',', $.super_type)))),
      $.class_body,
    ),

    enum_declaration: $ => seq(
      'enum',
      field('name', $.identifier),
      optional($.type_parameters),
      '{',
      optional(seq(
        $.enum_member,
        repeat(seq(',', $.enum_member)),
        optional(','),
      )),
      '}',
    ),

    enum_member: $ => field('name', $.identifier),

    annotation_declaration: $ => seq(
      'annotation',
      field('name', $.identifier),
      optional($.type_parameters),
      optional($.parameters),
      $.class_body,
    ),

    companion_declaration: $ => seq(
      'companion',
      $.entity_declaration,
    ),

    entity_declaration: $ => seq(
      'entity',
      field('name', $.identifier),
      optional($.type_parameters),
      optional(seq(':', $.super_type, repeat(seq(',', $.super_type)))),
      $.class_body,
    ),

    constructor_declaration: $ => seq(
      'constructor',
      $.parameters,
      $.block,
    ),

    class_body: $ => seq(
      '{',
      repeat($._item),
      '}',
    ),

    // ------------------------------------------------------------------
    // Statements
    // ------------------------------------------------------------------

    statement: $ => choice(
      $.empty_statement,
      $.block,
      $.variable_declaration,
      $.assignment_statement,
      $.expression_statement,
      $.if_statement,
      $.while_statement,
      $.for_statement,
      $.switch_statement,
      $.try_statement,
      $.fail_statement,
      $.break_statement,
      $.continue_statement,
      $.return_statement,
      $.goto_statement,
      $.labeled_statement,
    ),

    empty_statement: $ => ';',

    block: $ => seq('{', repeat($._item), '}'),

    variable_declaration: $ => seq(
      repeat($.annotation_use),
      repeat($.modifier),
      choice('var', 'val'),
      field('name', $.identifier),
      optional($.type_annotation),
      optional(seq('=', $._expression)),
    ),

    type_annotation: $ => seq(':', $.type),

    assignment_statement: $ => seq(
      field('left', $._expression),
      '=',
      field('right', $._expression),
    ),

    expression_statement: $ => $._expression,

    if_statement: $ => prec.right(seq(
      'if',
      choice(
        seq('(', $._expression, ')', $.block),
        seq($._expression, ':', $.statement),
      ),
      optional($._else_clause),
    )),

    _else_clause: $ => seq(
      'else',
      choice(
        $.block,
        $.if_statement,
        seq(':', $.statement),
      ),
    ),

    while_statement: $ => seq(
      'while',
      '(',
      $._expression,
      ')',
      $.block,
    ),

    for_statement: $ => seq(
      'for',
      '(',
      choice(
        $.for_classic_clause,
        $.for_in_clause,
      ),
      ')',
      $.block,
    ),

    for_classic_clause: $ => seq(
      optional(choice($.variable_declaration, $._expression)),
      ';',
      optional($._expression),
      ';',
      optional(choice($.assignment_statement, $._expression)),
    ),

    for_in_clause: $ => seq(
      field('name', $.identifier),
      'in',
      field('range', $._expression),
      optional(seq('step', field('step', $._expression))),
    ),

    switch_statement: $ => seq(
      'switch',
      '(',
      $._expression,
      ')',
      '{',
      repeat(choice($.switch_case, $.switch_else_clause)),
      '}',
    ),

    switch_case: $ => seq(
      optional('case'),
      field('label', $._qualified_name),
      ':',
      $.statement,
    ),

    switch_else_clause: $ => seq('else', ':', $.statement),

    try_statement: $ => seq(
      'try',
      $.block,
      repeat1($.catch_clause),
    ),

    catch_clause: $ => seq(
      'catch',
      '(',
      field('name', $.identifier),
      optional($.type_annotation),
      ')',
      $.block,
    ),

    fail_statement: $ => seq('fail', $._expression),

    break_statement: $ => 'break',

    continue_statement: $ => 'continue',

    return_statement: $ => prec.right(1, seq(
      choice('rtn', 'return'),
      optional($._expression),
    )),

    goto_statement: $ => seq('goto', $.label),

    labeled_statement: $ => seq($.label, ':'),

    label: $ => choice($.identifier, $.backtick_identifier),

    // ------------------------------------------------------------------
    // Expressions
    // ------------------------------------------------------------------

    _expression: $ => choice(
      $.ternary_expression,
      $.binary_expression,
      $.unary_expression,
      $.postfix_expression,
      $.cast_expression,
      $.member_expression,
      $.call_expression,
      $.index_expression,
      $.new_expression,
      $.lambda_expression,
      $.parenthesized_expression,
      $._primary,
    ),

    _primary: $ => choice(
      $.identifier,
      $.this,
      $.super,
      $.number,
      $.string,
      $.character,
      $.boolean,
      $.null,
    ),

    this: $ => 'this',

    super: $ => 'super',

    boolean: $ => choice('true', 'false'),

    null: $ => 'null',

    parenthesized_expression: $ => seq('(', $._expression, ')'),

    ternary_expression: $ => prec.right(20, seq(
      'if',
      field('condition', $._expression),
      '?',
      field('consequence', $._expression),
      ':',
      field('alternative', $._expression),
    )),

    binary_expression: $ => {
      const table = [
        ['?:', 2],
        ['||', 3],
        ['&&', 4],
        ['==', 5],
        ['!=', 5],
        ['<', 6],
        ['>', 6],
        ['<=', 6],
        ['>=', 6],
        ['..', 7],
        ['+', 8],
        ['-', 8],
        ['*', 9],
        ['/', 9],
        ['%', 9],
      ];
      return choice(...table.map(([op, p]) => prec.left(p, seq(
        field('left', $._expression),
        field('operator', op),
        field('right', $._expression),
      ))));
    },

    unary_expression: $ => prec(10, seq(
      field('operator', choice('-', '!')),
      field('operand', $._expression),
    )),

    postfix_expression: $ => prec(12, seq(
      field('operand', $._expression),
      '!!',
    )),

    cast_expression: $ => prec(11, seq(
      field('value', $._expression),
      field('operator', choice('as', 'is', '?as', '!is')),
      field('type', $.type),
    )),

    member_expression: $ => prec(13, seq(
      field('object', $._expression),
      '.',
      field('property', choice($.identifier, $.number)),
    )),

    call_expression: $ => prec(13, seq(
      field('function', $._expression),
      field('arguments', $.arguments),
    )),

    index_expression: $ => prec(13, seq(
      field('object', $._expression),
      '[',
      field('index', $._expression),
      ']',
    )),

    arguments: $ => seq(
      '(',
      optional(seq(
        $._expression,
        repeat(seq(',', $._expression)),
        optional(','),
      )),
      ')',
    ),

    new_expression: $ => prec(13, seq(
      'new',
      field('type', $.type),
      choice(
        field('arguments', $.arguments),
        seq('[', field('size', $._expression), ']'),
      ),
    )),

    lambda_expression: $ => seq(
      'lam',
      $.parameters,
      optional($._return_type),
      $.block,
    ),

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    type: $ => choice(
      $._primitive_type,
      $.identifier,
      $.array_type,
      $.nullable_type,
      $.non_null_type,
      $.generic_type,
      $.function_type,
      $.parenthesized_type,
    ),

    _primitive_type: $ => choice(...PRIMITIVE_TYPES),

    array_type: $ => prec(2, seq($.type, '[', ']')),

    nullable_type: $ => prec(1, seq('?', $.type)),

    non_null_type: $ => prec(1, seq('!', $.type)),

    generic_type: $ => prec(2, seq(
      $.type,
      '&',
      '<',
      $.type,
      repeat(seq(',', $.type)),
      '>',
    )),

    function_type: $ => seq(
      '(',
      optional(seq($.type, repeat(seq(',', $.type)))),
      ')',
      '->',
      $.type,
    ),

    parenthesized_type: $ => seq('(', $.type, ')'),

    // ------------------------------------------------------------------
    // Lexical tokens
    // ------------------------------------------------------------------

    identifier: _ => /[a-zA-Z_][a-zA-Z0-9_]*/,

    backtick_identifier: _ => /`[^`\n]+`/,

    number: _ => token(seq(
      /[0-9][0-9_]*/,
      optional(seq('.', /[0-9][0-9_]*/)),
      optional(/[lLfFdD]/),
    )),

    string: _ => token(seq(
      '"',
      repeat(choice(
        /[^"\\]/,
        /\\./,
      )),
      '"',
    )),

    character: _ => token(seq(
      "'",
      choice(
        /\\u[0-9a-fA-F]{4}/,
        /\\./,
        /[^'\\\n]/,
      ),
      "'",
    )),

    line_comment: _ => token(seq('//', /[^\n]*/)),

    block_comment: _ => token(seq('/*', /[^*]*\*+([^/*][^*]*\*+)*/, '/')),
  },
});











