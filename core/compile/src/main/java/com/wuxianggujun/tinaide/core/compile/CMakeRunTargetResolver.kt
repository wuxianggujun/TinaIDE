package com.wuxianggujun.tinaide.core.compile

import com.wuxianggujun.tinaide.project.ProjectMetadata

object CMakeRunTargetResolver {

    data class ManagerRepairResult(
        val manager: RunConfigurationManager,
        val repairedCount: Int,
    )

    data class TargetRepairResult(
        val targetName: String?,
        val requestedTargetName: String?,
        val repaired: Boolean,
    )

    fun defaultOutputMode(metadata: ProjectMetadata?): OutputMode {
        return if (metadata?.getSdlVersionOrNull() != null) {
            OutputMode.SDL
        } else {
            OutputMode.TERMINAL
        }
    }

    fun defaultTargetName(metadata: ProjectMetadata?, outputMode: OutputMode): String? {
        if (metadata == null) return null
        return if (outputMode.isSdlGraphical()) {
            metadata.normalizedDefaultSdlTargetName()
                ?: metadata.normalizedDefaultRunTargetName()
        } else {
            metadata.normalizedDefaultRunTargetName()
        }
    }

    fun createDefaultRunConfiguration(
        metadata: ProjectMetadata?,
        requireTargetForNonSdl: Boolean = false,
    ): RunConfiguration? {
        val outputMode = defaultOutputMode(metadata)
        val targetName = defaultTargetName(metadata, outputMode)
        if (requireTargetForNonSdl && outputMode != OutputMode.SDL && targetName.isNullOrBlank()) {
            return null
        }
        return RunConfiguration(
            name = "Debug",
            outputMode = outputMode,
            targetName = targetName.orEmpty(),
        )
    }

    fun repairBlankTargets(
        manager: RunConfigurationManager,
        metadata: ProjectMetadata?,
    ): ManagerRepairResult {
        if (metadata == null) return ManagerRepairResult(manager, repairedCount = 0)

        var repairedCount = 0
        val repairedConfigs = manager.configurations.map { config ->
            if (config.targetName.isNotBlank()) {
                config
            } else {
                val defaultTargetName = defaultTargetName(metadata, config.outputMode)
                if (defaultTargetName.isNullOrBlank()) {
                    config
                } else {
                    repairedCount++
                    config.copy(targetName = defaultTargetName)
                }
            }
        }

        val repairedManager = if (repairedCount > 0) {
            manager.copy(configurations = repairedConfigs)
        } else {
            manager
        }
        return ManagerRepairResult(repairedManager, repairedCount)
    }

    fun repairMissingOrInvalidTarget(
        requestedTargetName: String?,
        outputMode: OutputMode,
        metadata: ProjectMetadata?,
        targets: List<TargetInfo>,
    ): TargetRepairResult {
        val requested = requestedTargetName?.trim()?.takeIf { it.isNotBlank() }
        val defaultTargetName = defaultTargetName(metadata, outputMode)
        if (defaultTargetName.isNullOrBlank() || targets.isEmpty()) {
            return TargetRepairResult(
                targetName = requested,
                requestedTargetName = requested,
                repaired = false,
            )
        }

        val requestedExists = requested?.let { name -> targets.any { it.name == name } } ?: false
        val defaultTargetExists = targets.any { it.name == defaultTargetName }
        return if (!requestedExists && defaultTargetExists) {
            TargetRepairResult(
                targetName = defaultTargetName,
                requestedTargetName = requested,
                repaired = true,
            )
        } else {
            TargetRepairResult(
                targetName = requested,
                requestedTargetName = requested,
                repaired = false,
            )
        }
    }
}
