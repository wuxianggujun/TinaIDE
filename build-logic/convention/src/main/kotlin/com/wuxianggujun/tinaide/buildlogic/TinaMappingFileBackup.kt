package com.wuxianggujun.tinaide.buildlogic

import org.gradle.api.logging.Logger
import java.io.File

/**
 * Core logic for the `backupMappingFiles` task registered by
 * [TinaAndroidAppMappingPlugin].
 *
 * After a release build completes, this helper copies every
 * `build/outputs/mapping/<flavor>Release/mapping.txt` into
 * `app/mappings/<versionName>-<timestamp>/<flavor>Release/mapping.txt`
 * so that crash log de-obfuscation data can be committed to version
 * control.
 */
internal object TinaMappingFileBackup {

    fun backupMappings(
        mappingRoot: File,
        backupsRoot: File,
        versionName: String,
        buildId: String,
        logger: Logger,
    ) {
        if (!mappingRoot.exists()) {
            logger.warn("No mapping files found. Run a release build first.")
            return
        }

        // 目录名带 buildId，与 APK BuildConfig 里的 BUILD_ID 同源；
        // 崩溃墓碑的 "App version" 会带上同一 buildId，据此反查唯一匹配的 mapping。
        val backupRoot = backupsRoot.resolve("$versionName-$buildId")

        mappingRoot.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith("Release") }
            ?.forEach { flavorDir ->
                val mappingFile = flavorDir.resolve("mapping.txt")
                if (mappingFile.exists()) {
                    val destDir = backupRoot.resolve(flavorDir.name)
                    destDir.mkdirs()
                    mappingFile.copyTo(destDir.resolve("mapping.txt"), overwrite = true)
                    logger.lifecycle(
                        "Backed up: ${mappingFile.absolutePath} -> ${destDir.absolutePath}",
                    )
                }
            }

        if (backupRoot.exists()) {
            logger.lifecycle("Mapping files backed up to: ${backupRoot.absolutePath}")
            logger.lifecycle(
                "IMPORTANT: Commit this folder to version control for future crash analysis!",
            )
        }
    }
}
