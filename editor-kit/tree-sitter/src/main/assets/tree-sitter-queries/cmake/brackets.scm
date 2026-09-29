; CMake bracket pairs

(normal_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(if_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(foreach_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(while_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(function_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)

(macro_command
  "(" @editor.brackets.open
  ")" @editor.brackets.close)
