; Basic bracket pairs for C/C++ constructs

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

(lambda_capture_specifier
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(template_parameter_list
  "<" @editor.brackets.open
  ">" @editor.brackets.close)

(template_argument_list
  "<" @editor.brackets.open
  ">" @editor.brackets.close)
