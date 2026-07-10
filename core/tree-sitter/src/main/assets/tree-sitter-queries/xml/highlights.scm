; XML declaration
(xml_decl
  decl: "xml" @keyword.directive)

(xml_version
  version_attr: "version" @property)

(xml_encoding
  encoding_attr: "encoding" @property)

(xml_version_value) @string
(xml_encoding_value) @string

; Processing instructions
(pi
  (name) @keyword.directive)

; Tags
(empty_element
  tag_name: (name) @type)

(tag_start
  tag_name: (name) @type)

(tag_end
  tag_name: (name) @type)

; Namespaces and attributes
"xmlns" @keyword

(ns_decl
  xmlns_prefix: (name) @type)

(xml_attr
  ns_prefix: (name) @type)

(xml_attr
  attr_name: (name) @property)

(attr_value) @string

; References
(entity_ref) @constant
(char_ref) @constant

; CDATA and comments
(cdata_start) @keyword
(cdata_end) @keyword
(cdata) @string
(comment) @comment

; Operators and delimiters
(eq) @operator

[
  "<"
  ">"
  "/"
  "<?"
  "?>"
  ":"
] @punctuation.delimiter
