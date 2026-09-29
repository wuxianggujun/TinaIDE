; Bracket pairs for Java constructs

(block
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(expression
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(array_initializer
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(class_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(enum_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(interface_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(annotation_type_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(dimensions_expr
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(array_access
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(dimensions
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(argument_list
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(formal_parameters
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(parenthesized_expression
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(switch_block
  "{" @editor.brackets.open
  "}" @editor.brackets.close)
