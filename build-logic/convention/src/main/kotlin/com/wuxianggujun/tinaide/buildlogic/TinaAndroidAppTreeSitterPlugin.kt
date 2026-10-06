package com.wuxianggujun.tinaide.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Registers Tree-sitter related build tasks that used to be inlined in
 * `app/build.gradle.kts`:
 *
 * - `syncTreeSitterQueries`: manual, run-once helper that downloads
 *   upstream `nvim-treesitter` `highlights.scm` files.
 * - `generateTreeSitterLanguageRegistry`: runs on every build via
 *   `preBuild`, generates `GeneratedTreeSitterLanguageRegistry.kt`, and
 *   adds the output directory as an additional `src/main/java` source
 *   folder so the generated code is available to the Kotlin compiler.
 *
 * Both tasks derive the grammar list from the consuming module's
 * `implementation` configuration via [TinaTreeSitterSupport].
 */
class TinaAndroidAppTreeSitterPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val generatedRegistryDir = layout.buildDirectory
                .dir("generated/source/ts-language-registry/main/kotlin")

            // `syncTreeSitterQueries` is a manual utility. Do NOT wire it
            // into the standard build lifecycle (avoid hitting the network
            // on every build).
            tasks.register("syncTreeSitterQueries") {
                description = "Download upstream Tree-sitter query assets (highlights.scm) into src/main/assets (manual, run-once, commit-friendly)."
                group = "tina"

                val assetsRoot = project.file("src/main/assets")
                val upstreamRefProvider = providers.gradleProperty("treeSitterQueriesRef")
                    .orElse("master")
                val upstreamZipUrlProvider = providers.gradleProperty("treeSitterQueriesZipUrl")
                    .orElse(upstreamRefProvider.map {
                        "https://codeload.github.com/nvim-treesitter/nvim-treesitter/zip/$it"
                    })
                val tmpRootProvider = layout.buildDirectory.dir("tmp/tree-sitter-queries")

                doLast {
                    val langs = TinaTreeSitterSupport.treeSitterLangsFromDependencies(project)
                    TinaTreeSitterQueriesSync.syncQueries(
                        langs = langs,
                        assetsRoot = assetsRoot,
                        upstreamRef = upstreamRefProvider.get(),
                        upstreamZipUrl = upstreamZipUrlProvider.get(),
                        tmpRoot = tmpRootProvider.get().asFile,
                        logger = logger,
                    )
                }
            }

            val grammarModulesRootFile = rootProject
                .file("external/tina-android-tree-sitter/grammar-modules")

            // 强制 grammar pack 先 evaluate,这样在 task 执行阶段读取其
            // `api` / `implementation` configuration 才能命中(configure-on-demand 下
            // 如果不 trigger, 会抛 "Configuration with name 'implementation' not found")。
            evaluationDependsOn(TinaTreeSitterSupport.DEFAULT_GRAMMAR_SOURCE_PROJECT_PATH)

            val generateRegistryTask = tasks.register<TinaGenerateTreeSitterLanguageRegistryTask>(
                "generateTreeSitterLanguageRegistry",
            ) {
                group = "tina"
                description = "Generate Tree-sitter language registry for app module."
                outputDir.set(generatedRegistryDir)
                grammarModulesRoot.set(grammarModulesRootFile)
                bindingSourceFiles.from(
                    fileTree(grammarModulesRootFile) {
                        include("*/src/main/java/**/TSLanguage*.java")
                    },
                )
                grammarLanguages.set(provider {
                    TinaTreeSitterSupport.treeSitterLangsFromDependencies(project)
                })
            }

            val generatedRegistryDirFile = generatedRegistryDir.get().asFile

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    // AGP 9 disallows Provider-backed source directories on SourceSet APIs.
                    sourceSets.getByName("main").java.directories.add(generatedRegistryDirFile.absolutePath)
                }
                tasks.named("preBuild").configure { dependsOn(generateRegistryTask) }
                listOf(
                    "runKtlintCheckOverMainSourceSet",
                    "runKtlintFormatOverMainSourceSet",
                ).forEach { ktlintTaskName ->
                    tasks.matching { it.name == ktlintTaskName }.configureEach {
                        dependsOn(generateRegistryTask)
                    }
                }
            }
        }
    }
}
