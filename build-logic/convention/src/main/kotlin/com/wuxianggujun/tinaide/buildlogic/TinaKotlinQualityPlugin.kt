package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class TinaKotlinQualityPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")

            extensions.configure<KtlintExtension> {
                version.set("1.5.0")
                android.set(true)
                outputToConsole.set(true)
                ignoreFailures.set(false)
                filter {
                    exclude("**/generated/**")
                    exclude("**/build/**")
                }
            }

            configurations.configureEach {
                exclude(
                    mapOf(
                        "group" to "org.jetbrains",
                        "module" to "annotations-java5",
                    ),
                )
            }
        }
    }
}
