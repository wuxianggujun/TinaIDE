; Rust code blocks (for code folding)
; Simplified for editor query-runtime compatibility

; Function bodies
(function_item
  body: (block) @block)

; Impl blocks
(impl_item
  body: (declaration_list) @block)

; Trait blocks
(trait_item
  body: (declaration_list) @block)

; Struct definitions
(struct_item
  body: (field_declaration_list) @block)

; Enum definitions
(enum_item
  body: (enum_variant_list) @block)

; Match expressions
(match_expression
  body: (match_block) @block)

; Module blocks
(mod_item
  body: (declaration_list) @block)
