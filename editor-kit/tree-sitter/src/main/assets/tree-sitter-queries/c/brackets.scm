; Basic bracket pairs for C constructs

(compound_statement
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(initializer_list
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(parameter_list
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(argument_list
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(parenthesized_expression
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(subscript_expression
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(array_declarator
  "[" @editor.brackets.open
  "]" @editor.brackets.close)
