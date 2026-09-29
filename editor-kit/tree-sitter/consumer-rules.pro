# Tree-sitter grammar bindings are loaded reflectively via
# TreeSitterLanguageRegistry.resolveLanguage() -> Class.forName(...)
-keep class com.itsaky.androidide.treesitter.**.TSLanguage* { *; }
