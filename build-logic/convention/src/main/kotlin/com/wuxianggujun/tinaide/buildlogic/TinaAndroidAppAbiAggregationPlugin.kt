package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the cross-ABI aggregation tasks used by local development and CI:
 *
 * - `buildAllAbiNative` groups the per-ABI CMake debug build tasks so that
 *   a single command can drive native compilation for both `arm64-v8a`
 *   and `x86_64` when available.
 * - `assembleReleaseAllAbi` / `assembleDebugAllAbi` act as thin shortcuts
 *   over `assembleRelease` / `assembleDebug` that make it explicit the
 *   invocation should cover every ABI flavor (and set `tina.allAbi`-style
 *   heuristics downstream).
 *
 * The plugin also wires `buildAllAbiNative` as a `mergeDebugNativeLibs`
 * dependency when both ABI CMake tasks exist, so that `assembleDebugAllAbi`
 * assembles native libraries for all ABIs before packaging the APK. When
 * those per-ABI CMake tasks are not available (e.g. single-ABI dev mode),
 * the plugin logs a lifecycle hint instead of failing.
 *
 * ABI flavor declarations themselves (Android DSL `productFlavors`,
 * `beforeVariants` filters, per-ABI `versionCodeOverride` / output file
 * naming) are intentionally **not** migrated into this plugin: those are
 * business-level declarations of what ABIs the app supports and belong in
 * `app/build.gradle.kts` for visibility.
 */
class TinaAndroidAppAbiAggregationPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            val buildAllAbiRequested =
                TinaToolchainAssetsVerification.resolveBuildAllAbiRequested(this)
            val localDevAbi = TinaToolchainAssetsVerification.resolveLocalDevAbi(this)
            extensions.add(
                "tinaAppAbiAggregation",
                TinaAppAbiAggregationExtension(
                    localDevAbi = localDevAbi,
                    buildAllAbiRequested = buildAllAbiRequested,
                ),
            )

            val buildAllAbiNative = tasks.register("buildAllAbiNative") {
                group = "build"
                description =
                    "Aggregate task that depends on every per-ABI CMake debug build (arm64-v8a + x86_64)."
            }

            tasks.register("assembleReleaseAllAbi") {
                group = "build"
                description = "Assembles all release APKs for all ABI flavors."
                dependsOn("assembleRelease")
            }

            tasks.register("assembleDebugAllAbi") {
                group = "build"
                description =
                    "Builds native libraries for arm64-v8a and x86_64, then assembles the debug APK."
                dependsOn(buildAllAbiNative)
                dependsOn("assembleDebug")
            }

            afterEvaluate {
                val arm64Task = tasks.findByName("buildCMakeDebug[arm64-v8a]")
                val x86Task = tasks.findByName("buildCMakeDebug[x86_64]")
                val mergeNativeTask = tasks.findByName("mergeDebugNativeLibs")

                if (arm64Task != null && x86Task != null) {
                    buildAllAbiNative.configure {
                        dependsOn(arm64Task, x86Task)
                    }
                    mergeNativeTask?.dependsOn(buildAllAbiNative)
                    return@afterEvaluate
                }

                if (buildAllAbiRequested) {
                    logger.warn(
                        "Native ABI build tasks not found (arm64=$arm64Task, x86=$x86Task). " +
                            "Gradle will only build ABIs requested by the current variant/device.",
                    )
                } else {
                    logger.lifecycle(
                        "Single-ABI local mode active (tina.devAbi=$localDevAbi); " +
                            "only native tasks for the enabled ABI are wired.",
                    )
                }
            }
        }
    }
}
