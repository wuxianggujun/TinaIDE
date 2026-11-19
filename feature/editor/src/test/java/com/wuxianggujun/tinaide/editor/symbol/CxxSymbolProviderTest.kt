package com.wuxianggujun.tinaide.editor.symbol

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSPoint
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class CxxSymbolProviderTest {
    @Test
    fun overloadedDefinitions_keepTheirSignaturesAndLocations() {
        val syntax = Syntax("void run(int x) {}\nvoid run(double x) {}")
        val symbols = syntax.extract(
            syntax.function("void run(int x)", "run"),
            syntax.function("void run(double x)", "run"),
        )
        assertThat(symbols.map { it.signature }).containsExactly("void run(int x)", "void run(double x)")
        assertThat(symbols.map { it.location?.line }).containsExactly(0, 1)
    }

    @Test
    fun sameNamesInNamespaces_keepTheirOwners() {
        val syntax = Syntax("namespace A { void run() {} }\nnamespace B { void run() {} }")
        val symbols = syntax.extract(
            syntax.scope("namespace_definition", "A", syntax.function("void run()", "run")),
            syntax.scope("namespace_definition", "B", syntax.function("void run()", "run", from = 30)),
        ).filter { it.name == "run" }
        assertThat(symbols.map { it.containerName }).containsExactly("A", "B")
        assertThat(symbols.map { it.kind }).containsExactly(SymbolKind.Function, SymbolKind.Function)
    }

    @Test
    fun parametersLocalsAndLocalTypes_areNotProjectSymbols() {
        val syntax = Syntax("int global;\nint work(int arg) { int local; class Local {}; }")
        val global = syntax.declaration("int global", syntax.node("identifier", "global"))
        val parameter = syntax.node("parameter_declaration", "int arg", children = listOf(syntax.node("identifier", "arg")))
        val local = syntax.declaration("int local", syntax.node("identifier", "local"))
        val localType = syntax.scope("class_specifier", "Local")
        val body = syntax.node("compound_statement", "{ int local; class Local {}; }", children = listOf(local, localType))
        val function = syntax.function("int work(int arg)", "work", "int", extra = listOf(parameter, body))
        val symbols = syntax.extract(global, function)
        assertThat(symbols.map { it.name }).containsExactly("global", "work")
    }

    @Test
    fun globalVariable_preservesPositionAfterUnicodeText() {
        val syntax = Syntax("// 中文注释\n/* 中文 */ int counter;")
        val symbols = syntax.extract(syntax.declaration("int counter", syntax.node("identifier", "counter")))
        assertThat(symbols.single().kind).isEqualTo(SymbolKind.Variable)
        assertThat(symbols.single().location).isEqualTo(SymbolLocation(1, 13))
    }

    @Test
    fun classDeclarations_preserveFieldsMethodsAndOverloads() {
        val syntax = Syntax("class Box { int value; void run(int); void run(double); };")
        val field = syntax.declaration("int value", syntax.node("field_identifier", "value"), field = true)
        val first = syntax.function("void run(int)", "run", definition = false)
        val second = syntax.function("void run(double)", "run", definition = false)
        val symbols = syntax.extract(syntax.scope("class_specifier", "Box", field, first, second))
        assertThat(symbols.filter { it.name == "run" }.map { it.kind })
            .containsExactly(SymbolKind.Method, SymbolKind.Method)
        assertThat(symbols.first { it.name == "value" }.kind).isEqualTo(SymbolKind.Field)
        assertThat(symbols.filter { it.name != "Box" }.map { it.containerName }).containsExactly("Box", "Box", "Box")
    }

    @Test
    fun functionPointer_isAVariableWhilePointerReturningFunctionIsAFunction() {
        val syntax = Syntax("int (*callback)(int); int *create();")
        val pointer = syntax.node("pointer_declarator", "*callback", fields = mapOf("declarator" to syntax.node("identifier", "callback")))
        val parens = syntax.node("parenthesized_declarator", "(*callback)", children = listOf(pointer))
        val callback = syntax.node("function_declarator", "(*callback)(int)", fields = mapOf("declarator" to parens))
        val createFunction = syntax.node("function_declarator", "create()", fields = mapOf("declarator" to syntax.node("identifier", "create")))
        val create = syntax.node("pointer_declarator", "*create()", fields = mapOf("declarator" to createFunction))
        val symbols = syntax.extract(syntax.declaration("int (*callback)(int)", callback), syntax.declaration("int *create()", create))
        assertThat(symbols.first { it.name == "callback" }.kind).isEqualTo(SymbolKind.Variable)
        assertThat(symbols.first { it.name == "create" }.kind).isEqualTo(SymbolKind.Function)
    }

    @Test
    fun outOfClassDefinition_resolvesOwnerWithoutUsingNamespaceAsName() {
        val syntax = Syntax("class Box {}; void Box::run() {}")
        val qualified = syntax.node("qualified_identifier", "Box::run", fields = mapOf("name" to syntax.node("identifier", "run")))
        val symbols = syntax.extract(
            syntax.scope("class_specifier", "Box"),
            syntax.function("void Box::run()", "run", declaredName = qualified),
        )
        val method = symbols.first { it.name == "run" }
        assertThat(method.containerName).isEqualTo("Box")
        assertThat(method.kind).isEqualTo(SymbolKind.Method)
        assertThat(method.location?.column).isEqualTo(24)
    }

    @Test
    fun constructorsDestructorsAndOperators_keepTheirNamesWithoutInventingReturnTypes() {
        val syntax = Syntax("class Box { Box() {} ~Box() {} int operator()(int) {} };")
        val constructor = syntax.function("Box()", "Box", returnType = null)
        val destructor = syntax.function("~Box()", "~Box", returnType = null, nameType = "destructor_name")
        val operator = syntax.function("int operator()(int)", "operator()", "int", nameType = "operator_name")
        val symbols = syntax.extract(syntax.scope("class_specifier", "Box", constructor, destructor, operator))
        assertThat(symbols.filter { it.kind == SymbolKind.Method }.map { it.signature })
            .containsExactly("Box()", "~Box()", "int operator()(int)")
    }

    /** AST fixtures use the grammar's declarator/parameter/body fields, without loading Android JNI. */
    private class Syntax(val source: String) {
        fun extract(vararg children: TSNode): List<GlobalSymbol> =
            CxxSymbolProvider().extractSymbols(node("translation_unit", source, children = children.toList()), source)

        fun node(
            type: String,
            text: String,
            from: Int = 0,
            fields: Map<String, TSNode> = emptyMap(),
            children: List<TSNode> = fields.values.toList(),
        ): TSNode {
            val start = source.indexOf(text, from)
            require(start >= 0) { "Missing fixture text: $text" }
            val row = source.take(start).count { it == '\n' }
            val column = start - (source.lastIndexOf('\n', start - 1) + 1)
            val point = mockk<TSPoint>()
            every { point.row } returns row
            every { point.column } returns column * 2
            val node = mockk<TSNode>()
            every { node.canAccess() } returns true
            every { node.isNull } returns false
            every { node.type } returns type
            every { node.startByte } returns start * 2
            every { node.endByte } returns (start + text.length) * 2
            every { node.startPoint } returns point
            every { node.namedChildCount } returns children.size
            every { node.getChildByFieldName(any()) } answers { fields[firstArg()] }
            every { node.getNamedChild(any()) } answers { children[firstArg()] }
            return node
        }

        fun scope(type: String, name: String, vararg children: TSNode): TSNode {
            val nameNode = node("type_identifier", name)
            val body = node("declaration_list", source, children = children.toList())
            return node(type, source, fields = mapOf("name" to nameNode, "body" to body))
        }

        fun declaration(text: String, declarator: TSNode, field: Boolean = false): TSNode = node(
            if (field) "field_declaration" else "declaration", text,
            fields = mapOf("type" to node("primitive_type", "int"), "declarator" to declarator),
        )

        fun function(
            signature: String,
            name: String,
            returnType: String? = "void",
            from: Int = 0,
            definition: Boolean = true,
            extra: List<TSNode> = emptyList(),
            nameType: String = "identifier",
            declaredName: TSNode? = null,
        ): TSNode {
            val start = source.indexOf(signature, from)
            val declaratorText = returnType?.let { signature.removePrefix(it).trim() } ?: signature
            val identifier = declaredName ?: node(nameType, name, from = start)
            val declarator = node("function_declarator", declaratorText, from = start, fields = mapOf("declarator" to identifier))
            val fields = buildMap {
                put("declarator", declarator)
                returnType?.let { put("type", node("primitive_type", it, from = start)) }
            }
            return node(if (definition) "function_definition" else "declaration", signature, from = start,
                fields = fields, children = fields.values.toList() + extra)
        }
    }
}
