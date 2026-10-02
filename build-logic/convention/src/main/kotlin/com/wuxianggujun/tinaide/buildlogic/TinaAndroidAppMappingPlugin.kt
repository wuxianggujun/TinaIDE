package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers the release R8 mapping backup pipeline for the application module.
 *
 * `backupMappingFiles` copies `build/outputs/mapping/<flavor>Release/mapping.txt`
 * into `app/mappings/<versionName>-<buildId>/<flavor>Release/mapping.txt`.
 *
 * `<buildId>` 与 APK `BuildConfig.BUILD_ID` 同源（见 [TinaAppVersioningExtension.buildId]），
 * 因此崩溃墓碑 "App version" 中的 buildId 可以反查到唯一匹配的 mapping 目录。
 *
 * The open-source build logic only archives mapping files locally. If
 * maintainers need external symbol storage, keep that integration outside this
 * public repository.
 *
 * Configuration properties:
 * - `tina.releaseMapping.enabled` (default `true`): toggle the backup pipeline;
 *   when `false`, the task becomes a no-op and no finalizer is attached.
 * - `tina.releaseMapping.backupEnabled` (default `true`): attach
 *   `backupMappingFiles` to release builds.
 *
 * Requires the consumer project to have applied [TinaAndroidAppVersioningPlugin]
 * because it reads version information from the [TinaAppVersioningExtension]
 * exposed by that plugin.
 */
class TinaAndroidAppMappingPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            val enabled = resolveBooleanGradleProperty(
                name = "tina.releaseMapping.enabled",
                default = true,
            )
            val backupEnabled = resolveBooleanGradleProperty(
                name = "tina.releaseMapping.backupEnabled",
                default = true,
            )

            // 保持任务注册（即使当前被 disable），方便手动做一次性归档。
            tasks.register("backupMappingFiles") {
                group = "build"
                description = "Backup R8 mapping files for crash log deobfuscation."
                onlyIf { enabled && backupEnabled }

                doLast {
                    val versioning = project.readVersioningExtension()
                    TinaMappingFileBackup.backupMappings(
                        mappingRoot = project.file("build/outputs/mapping"),
                        backupsRoot = project.file("mappings"),
                        versionName = versioning.versionName,
                        buildId = versioning.buildId,
                        logger = logger,
                    )
                }
            }

            if (!enabled) {
                logger.lifecycle(
                    "tina.android.app.mapping: tina.releaseMapping.enabled=false, " +
                        "skipping mapping backup finalizer for release assemble/bundle tasks.",
                )
                return@with
            }

            afterEvaluate {
                if (!backupEnabled) {
                    logger.lifecycle(
                        "tina.android.app.mapping: mapping backup finalizer disabled for release tasks.",
                    )
                    return@afterEvaluate
                }
                tasks.matching { it.name.matches(Regex("assemble.*Release")) }.configureEach {
                    finalizedBy("backupMappingFiles")
                }
                tasks.matching { it.name.matches(Regex("bundle.*Release")) }.configureEach {
                    finalizedBy("backupMappingFiles")
                }
            }
        }
    }

    private fun Project.readVersioningExtension(): TinaAppVersioningExtension {
        return extensions.findByType(TinaAppVersioningExtension::class.java)
            ?: throw GradleException(
                "tina.android.app.mapping requires tina.android.app.versioning to be applied " +
                    "first so that TinaAppVersioningExtension is available.",
            )
    }
}
