; Rust locals (variable scope tracking)
; Simplified for editor query-runtime compatibility

; =======================================================================
; Scopes
; =======================================================================

(function_item) @local.scope
(closure_expression) @local.scope
(block) @local.scope
(for_expression) @local.scope
(while_expression) @local.scope
(loop_expression) @local.scope
(if_expression) @local.scope
(match_expression) @local.scope
(match_arm) @local.scope

; =======================================================================
; Definitions
; =======================================================================

; Variable definitions
(let_declaration
  pattern: (identifier) @local.definition)

; Parameters
(parameter
  pattern: (identifier) @local.definition)
(closure_parameters
  (identifier) @local.definition)

; For loop variable
(for_expression
  pattern: (identifier) @local.definition)

; Function definitions
(function_item
  name: (identifier) @local.definition)

; Type definitions
(struct_item
  name: (type_identifier) @local.definition)
(enum_item
  name: (type_identifier) @local.definition)
(trait_item
  name: (type_identifier) @local.definition)
(type_item
  name: (type_identifier) @local.definition)

; =======================================================================
; References
; =======================================================================

(identifier) @local.reference
