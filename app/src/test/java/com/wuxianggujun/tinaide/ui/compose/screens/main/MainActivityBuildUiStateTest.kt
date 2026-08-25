package com.wuxianggujun.tinaide.ui.compose.screens.main

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.compile.BuildSystem
import com.wuxianggujun.tinaide.core.compile.RunConfiguration
import com.wuxianggujun.tinaide.core.compile.RunConfigurationManager
import org.junit.Test

class MainActivityBuildUiStateTest {

    @Test
    fun openAndCloseRunConfigDialog_shouldTrackEditingTarget() {
        val initialConfig = RunConfiguration(name = "Default")
        val state = MainActivityBuildUiState(
            initialRunConfigManager = RunConfigurationManager(
                configurations = listOf(initialConfig),
                selectedId = initialConfig.id
            )
        )
        val editingConfig = RunConfiguration(name = "Debug")

        state.openRunConfigDialog(editingConfig)

        assertThat(state.showRunConfigDialog).isTrue()
        assertThat(state.editingConfig).isEqualTo(editingConfig)

        state.closeRunConfigDialog()

        assertThat(state.showRunConfigDialog).isFalse()
        assertThat(state.editingConfig).isNull()
    }

    @Test
    fun openRunConfigDialog_withoutExplicitConfig_shouldUseSelectedConfiguration() {
        val initialConfig = RunConfiguration(name = "Release")
        val state = MainActivityBuildUiState(
            initialRunConfigManager = RunConfigurationManager(
                configurations = listOf(initialConfig),
                selectedId = initialConfig.id
            )
        )

        state.openRunConfigDialog()

        assertThat(state.showRunConfigDialog).isTrue()
        assertThat(state.editingConfig).isEqualTo(initialConfig)
    }

    @Test
    fun apkPackageDialogVisibility_shouldToggleIndependently() {
        val state = MainActivityBuildUiState(
            initialRunConfigManager = RunConfigurationManager()
        )

        state.openApkPackageDialog()
        assertThat(state.showApkPackageDialog).isTrue()

        state.closeApkPackageDialog()
        assertThat(state.showApkPackageDialog).isFalse()
    }

    @Test
    fun commitRunConfigManager_whenPersistenceSucceeds_shouldUpdateState() {
        val initialManager = runConfigManagerWithSelectedName("Debug")
        val updatedManager = runConfigManagerWithSelectedName("Release")
        val state = MainActivityBuildUiState(initialManager)

        val committed = state.commitRunConfigManager(updatedManager, persist = { true })

        assertThat(committed).isTrue()
        assertThat(state.runConfigManager).isEqualTo(updatedManager)
    }

    @Test
    fun commitRunConfigManager_whenPersistenceFails_shouldKeepPreviousState() {
        val initialManager = runConfigManagerWithSelectedName("Debug")
        val updatedManager = runConfigManagerWithSelectedName("Release")
        val state = MainActivityBuildUiState(initialManager)

        val committed = state.commitRunConfigManager(updatedManager, persist = { false })

        assertThat(committed).isFalse()
        assertThat(state.runConfigManager).isEqualTo(initialManager)
    }

    @Test
    fun commitRunConfigManager_whenPersistenceFails_shouldKeepPreviousSelection() {
        val debugConfig = RunConfiguration(name = "Debug")
        val releaseConfig = RunConfiguration(name = "Release")
        val initialManager = RunConfigurationManager(
            configurations = listOf(debugConfig, releaseConfig),
            selectedId = debugConfig.id
        )
        val state = MainActivityBuildUiState(initialManager)

        state.commitRunConfigManager(
            initialManager.selectConfig(releaseConfig.id),
            persist = { false },
        )

        assertThat(state.runConfigManager.selectedConfig).isEqualTo(debugConfig)
    }

    @Test
    fun commitRunConfigManager_whenSelectedSingleFileStandardChanges_shouldNotifyAfterPersistence() {
        val initialConfig = RunConfiguration(name = "Debug", singleFileCppStandard = "CPP_17")
        val initialManager = managerWithSelectedConfig(initialConfig)
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.SINGLE_FILE
        }
        var notificationCount = 0

        val committed = state.commitRunConfigManager(
            updated = initialManager.updateConfig(initialConfig.copy(singleFileCppStandard = "CPP_20")),
            persist = { true },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(committed).isTrue()
        assertThat(notificationCount).isEqualTo(1)
    }

    @Test
    fun commitRunConfigManager_whenPersistenceFails_shouldNotNotifyStandardChange() {
        val initialConfig = RunConfiguration(name = "Debug", singleFileCppStandard = "CPP_17")
        val initialManager = managerWithSelectedConfig(initialConfig)
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.SINGLE_FILE
        }
        var notificationCount = 0

        state.commitRunConfigManager(
            updated = initialManager.updateConfig(initialConfig.copy(singleFileCppStandard = "CPP_20")),
            persist = { false },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(notificationCount).isEqualTo(0)
    }

    @Test
    fun commitRunConfigManager_whenUnselectedStandardChanges_shouldNotNotify() {
        val selected = RunConfiguration(name = "Debug", singleFileCppStandard = "CPP_17")
        val unselected = RunConfiguration(name = "Release", singleFileCppStandard = "CPP_17")
        val initialManager = RunConfigurationManager(
            configurations = listOf(selected, unselected),
            selectedId = selected.id,
        )
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.SINGLE_FILE
        }
        var notificationCount = 0

        state.commitRunConfigManager(
            updated = initialManager.updateConfig(unselected.copy(singleFileCppStandard = "CPP_20")),
            persist = { true },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(notificationCount).isEqualTo(0)
    }

    @Test
    fun commitRunConfigManager_whenSelectionChangesToDifferentStandard_shouldNotify() {
        val cpp17 = RunConfiguration(name = "C++17", singleFileCppStandard = "CPP_17")
        val cpp20 = RunConfiguration(name = "C++20", singleFileCppStandard = "CPP_20")
        val initialManager = RunConfigurationManager(
            configurations = listOf(cpp17, cpp20),
            selectedId = cpp17.id,
        )
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.SINGLE_FILE
        }
        var notificationCount = 0

        state.commitRunConfigManager(
            updated = initialManager.selectConfig(cpp20.id),
            persist = { true },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(notificationCount).isEqualTo(1)
    }

    @Test
    fun commitRunConfigManager_whenSelectionKeepsSameStandard_shouldNotNotify() {
        val first = RunConfiguration(name = "First", singleFileCppStandard = "CPP_20")
        val second = RunConfiguration(name = "Second", singleFileCppStandard = "c++20")
        val initialManager = RunConfigurationManager(
            configurations = listOf(first, second),
            selectedId = first.id,
        )
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.SINGLE_FILE
        }
        var notificationCount = 0

        state.commitRunConfigManager(
            updated = initialManager.selectConfig(second.id),
            persist = { true },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(notificationCount).isEqualTo(0)
    }

    @Test
    fun commitRunConfigManager_forNonSingleFileProject_shouldNotNotify() {
        val initialConfig = RunConfiguration(name = "Debug", singleFileCppStandard = "CPP_17")
        val initialManager = managerWithSelectedConfig(initialConfig)
        val state = MainActivityBuildUiState(initialManager).apply {
            currentBuildSystem = BuildSystem.CMAKE
        }
        var notificationCount = 0

        state.commitRunConfigManager(
            updated = initialManager.updateConfig(initialConfig.copy(singleFileCppStandard = "CPP_20")),
            persist = { true },
            onSelectedSingleFileCppStandardChanged = { notificationCount++ },
        )

        assertThat(notificationCount).isEqualTo(0)
    }

    private fun runConfigManagerWithSelectedName(name: String): RunConfigurationManager {
        val config = RunConfiguration(name = name)
        return managerWithSelectedConfig(config)
    }

    private fun managerWithSelectedConfig(config: RunConfiguration): RunConfigurationManager =
        RunConfigurationManager(
            configurations = listOf(config),
            selectedId = config.id,
        )
}
