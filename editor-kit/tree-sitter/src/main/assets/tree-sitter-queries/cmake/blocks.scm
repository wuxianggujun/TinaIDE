; CMake folding/code-block queries for editor query runtime
; Based on official tree-sitter-cmake `queries/folds.scm`

[
  (if_condition)
  (foreach_loop)
  (while_loop)
  (function_def)
  (macro_def)
  (block_def)
] @fold.marked
