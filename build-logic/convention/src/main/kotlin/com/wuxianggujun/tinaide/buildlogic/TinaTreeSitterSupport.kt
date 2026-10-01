package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.Project

/**
 * Shared helpers for Tree-sitter build tasks registered by
 * [TinaAndroidAppTreeSitterPlugin].
 *
 * Both `syncTreeSitterQueries` and `generateTreeSitterLanguageRegistry`
 * depend on the list of Tree-sitter grammars pulled in via the app
 * module's `implementation` configuration. Keeping the extraction logic
 * in one place avoids duplicating the `com.itsaky.androidide.treesitter`
 * filter across multiple task bodies.
 */
internal object TinaTreeSitterSupport {

    private const val TREE_SITTER_GROUP = "com.itsaky.androidide.treesitter"
    private const val GRAMMAR_NAME_PREFIX = "tree-sitter-"

    /**
     * Default Gradle path to the project that declares the Tree-sitter
     * grammar maven coordinates (currently `:core:tree-sitter-grammars`).
     */
    const val DEFAULT_GRAMMAR_SOURCE_PROJECT_PATH: String = ":core:tree-sitter-grammars"

    /**
     * Collect grammar language identifiers (e.g. `cpp`, `kotlin`) from
     * the specified project's `api` and `implementation` configurations.
     *
     * By default the caller should point this at `:core:tree-sitter-grammars`
     * which is the single source of truth for grammar maven coordinates.
     */
    fun treeSitterLangsFromDependencies(
        project: Project,
        sourceProjectPath: String = DEFAULT_GRAMMAR_SOURCE_PROJECT_PATH,
    ): Set<String> {
        val grammarSource = project.rootProject.findProject(sourceProjectPath)
            ?: error(
                "Grammar source project '$sourceProjectPath' not found under root project " +
                    "'${project.rootProject.path}'. Ensure the module is included in settings.gradle.kts " +
                    "or override sourceProjectPath.",
            )
        return sequenceOf("api", "implementation")
            .flatMap { configurationName ->
                grammarSource.configurations.findByName(configurationName)
                    ?.dependencies
                    ?.asSequence()
                    ?: emptySequence()
            }
            .filter { it.group == TREE_SITTER_GROUP }
            .mapNotNull { dep ->
                dep.name.takeIf { it.startsWith(GRAMMAR_NAME_PREFIX) }
                    ?.removePrefix(GRAMMAR_NAME_PREFIX)
            }
            .toSortedSet()
    }
}
