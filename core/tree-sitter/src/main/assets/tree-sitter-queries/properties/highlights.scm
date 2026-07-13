(comment) @comment @spell

(property
  key: (property_name) @property
  separator: (sep) @operator
  value: (property_value) @string)

(escape_sequence) @string.escape

(continuation
  operator: "\\" @punctuation.special)

((property_value) @boolean
  (#any-of? @boolean "true" "false"))

((property_value) @number
  (#lua-match? @number "^%d+$"))
