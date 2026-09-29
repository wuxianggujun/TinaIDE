; AIDL highlights (based on Java grammar)

; Variables
(identifier) @variable

; Methods
(method_declaration
  name: (identifier) @function.method)

; Parameters
(formal_parameter
  name: (identifier) @variable.parameter)

; Operators
[
  "+"
  "-"
  "*"
  "/"
  "%"
  "<"
  "<="
  ">"
  ">="
  "="
  "=="
  "!="
  "&"
  "|"
  "^"
  "~"
  "<<"
  ">>"
] @operator

; Types
(interface_declaration
  name: (identifier) @type)

(parcelable_declaration
  name: (identifier) @type)

(type_identifier) @type

[
  (boolean_type)
  (integral_type)
  (floating_point_type)
  (void_type)
] @type.builtin

; Annotations
(annotation
  "@" @attribute
  name: (identifier) @attribute)

(marker_annotation
  "@" @attribute
  name: (identifier) @attribute)

; Literals
(string_literal) @string
(character_literal) @character

[
  (hex_integer_literal)
  (decimal_integer_literal)
  (octal_integer_literal)
  (binary_integer_literal)
] @number

[
  (decimal_floating_point_literal)
  (hex_floating_point_literal)
] @number.float

[
  (true)
  (false)
] @boolean

(null_literal) @constant.builtin

; Keywords
[
  "interface"
  "parcelable"
  "oneway"
  "in"
  "out"
  "inout"
] @keyword

[
  "import"
  "package"
] @keyword.import

; Punctuation
[
  ";"
  "."
  ","
] @punctuation.delimiter

[
  "{"
  "}"
  "["
  "]"
  "("
  ")"
  "<"
  ">"
] @punctuation.bracket

; Comments
[
  (line_comment)
  (block_comment)
] @comment
