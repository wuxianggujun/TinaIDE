; Local variable tracking for C

; Scopes
(function_definition) @local.scope
(compound_statement) @local.scope
(for_statement) @local.scope

; Function definitions
(function_declarator
  declarator: (identifier) @local.definition.function)

; Parameters
(parameter_declaration
  declarator: (identifier) @local.definition.parameter)

(parameter_declaration
  declarator: (pointer_declarator
    declarator: (identifier) @local.definition.parameter))

(parameter_declaration
  declarator: (array_declarator
    declarator: (identifier) @local.definition.parameter))

; Variable declarations
(declaration
  declarator: (init_declarator
    declarator: (identifier) @local.definition.var))

(declaration
  declarator: (identifier) @local.definition.var)

(declaration
  declarator: (pointer_declarator
    declarator: (identifier) @local.definition.var))

(declaration
  declarator: (array_declarator
    declarator: (identifier) @local.definition.var))

; Type definitions
(type_definition
  declarator: (type_identifier) @local.definition.type)

(struct_specifier
  name: (type_identifier) @local.definition.type)

(union_specifier
  name: (type_identifier) @local.definition.type)

(enum_specifier
  name: (type_identifier) @local.definition.type)

; Enum constants
(enumerator
  name: (identifier) @local.definition.constant)

; References
(identifier) @local.reference
(type_identifier) @local.reference

