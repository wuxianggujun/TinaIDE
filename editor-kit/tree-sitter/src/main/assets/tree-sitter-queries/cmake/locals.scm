; CMake local variable tracking

; Function/macro definitions create scope
(function_def) @local.scope
(macro_def) @local.scope

; Variable definitions via set() command
(normal_command
  (identifier) @_cmd
  (argument_list
    .
    (argument
      (unquoted_argument) @local.definition.var))
  (#match? @_cmd "^[sS][eE][tT]$"))

; Function parameters
(function_command
  (argument_list
    .
    (argument) @local.definition.function
    (argument)* @local.definition.parameter))

; Macro parameters
(macro_command
  (argument_list
    .
    (argument) @local.definition.function
    (argument)* @local.definition.parameter))

; Variable references
(variable) @local.reference
(normal_var) @local.reference
(env_var) @local.reference
(cache_var) @local.reference
