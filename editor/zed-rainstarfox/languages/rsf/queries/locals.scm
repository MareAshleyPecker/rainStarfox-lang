; Scopes
(block) @local.scope
(function_declaration) @local.scope
(lambda_expression) @local.scope
(class_declaration) @local.scope
(interface_declaration) @local.scope
(enum_declaration) @local.scope

; Definitions
(variable_declaration
  name: (identifier) @local.definition)

(parameter
  name: (identifier) @local.definition)

(primary_constructor_parameter
  name: (identifier) @local.definition)

; References
(identifier) @local.reference
