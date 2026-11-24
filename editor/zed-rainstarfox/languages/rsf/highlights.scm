; rainStarfox syntax highlighting.
;
; Capture names follow Zed's supported set:
; https://zed.dev/docs/extensions/languages#syntax-highlighting

; ---------------------------------------------------------------------------
; Comments
; ---------------------------------------------------------------------------

(line_comment) @comment
(block_comment) @comment

; ---------------------------------------------------------------------------
; Keywords
; ---------------------------------------------------------------------------

[
  "fn"
  "lam"
  "class"
  "interface"
  "enum"
  "annotation"
  "companion"
  "entity"
  "constructor"
  "import"
  "module"
  "pkg"
  "var"
  "val"
] @keyword

[
  "pub"
  "prv"
  "prot"
  "stc"
  "override"
  "final"
  "abstract"
  "native"
  "jvm"
] @keyword

[
  "if"
  "else"
  "while"
  "for"
  "in"
  "goto"
  "switch"
  "case"
  "try"
  "catch"
  "fail"
  "rtn"
  "return"
  "new"
  "as"
  "is"
  "?as"
  "!is"
  "step"
] @keyword

; `break` / `continue` are single-token named rules, so they have no
; anonymous token to match; capture the statement node instead.
(break_statement) @keyword
(continue_statement) @keyword

[
  "void"
  "Int"
  "Long"
  "Float"
  "Double"
  "String"
  "Str"
  "Bool"
  "Boolean"
  "Byte"
  "Char"
] @type.builtin

(boolean) @boolean
(null) @constant.builtin
(this) @variable.builtin
(super) @variable.builtin

; ---------------------------------------------------------------------------
; Literals
; ---------------------------------------------------------------------------

(number) @number
(string) @string
(character) @string.special

; ---------------------------------------------------------------------------
; Declarations
; ---------------------------------------------------------------------------

(function_declaration
  name: (identifier) @function)

(parameter
  name: (identifier) @variable.parameter)

(primary_constructor_parameter
  name: (identifier) @variable.parameter)

(variable_declaration
  name: (identifier) @variable)

(class_declaration
  name: (identifier) @type)

(interface_declaration
  name: (identifier) @type)

(enum_declaration
  name: (identifier) @type)

(annotation_declaration
  name: (identifier) @type)

(entity_declaration
  name: (identifier) @type)

(enum_member
  name: (identifier) @constant)

(type_parameters
  (identifier) @type)

; Types in annotations, parameters, return types, casts and `new` expressions.
(type
  (identifier) @type)

; ---------------------------------------------------------------------------
; Expressions
; ---------------------------------------------------------------------------

(member_expression
  property: (identifier) @property)

(call_expression
  function: (identifier) @function)

(call_expression
  function: (member_expression
    property: (identifier) @function.method))

(label) @label
(backtick_identifier) @label

; ---------------------------------------------------------------------------
; Operators / punctuation
; ---------------------------------------------------------------------------

[
  "="
  "+"
  "-"
  "*"
  "/"
  "%"
  "<"
  ">"
  "<="
  ">="
  "=="
  "!="
  "&&"
  "||"
  "!"
  "?:"
  ".."
  "&"
  "!!"
  "->"
  "::"
] @operator

[
  "("
  ")"
  "["
  "]"
  "{"
  "}"
] @punctuation.bracket

[
  ","
  ";"
  ":"
  "."
] @punctuation.delimiter

"@" @punctuation.special
