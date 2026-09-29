; Code block patterns for C/C++ constructs
; Captures named with '.marked' flag the end position at the last terminal node

(namespace_definition
  body: (_) @scope.marked)

(class_specifier
  body: (_) @scope.marked)

(struct_specifier
  body: (_) @scope.marked)

(union_specifier
  body: (_) @scope.marked)

(compound_statement) @scope.marked

(lambda_expression
  body: (_) @scope.marked)

(try_statement
  body: (_) @scope.marked)

(catch_clause
  body: (_) @scope.marked)

(initializer_list) @scope.marked
