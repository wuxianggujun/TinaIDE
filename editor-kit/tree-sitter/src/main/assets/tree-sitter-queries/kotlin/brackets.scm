; Bracket pairs for Kotlin constructs

(class_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(function_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(control_structure_body
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(lambda_literal
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(when_expression
  "{" @editor.brackets.open
  "}" @editor.brackets.close)

(value_arguments
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(parenthesized_expression
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(indexing_suffix
  "[" @editor.brackets.open
  "]" @editor.brackets.close)

(collection_literal
  "[" @editor.brackets.open
  "]" @editor.brackets.close)
