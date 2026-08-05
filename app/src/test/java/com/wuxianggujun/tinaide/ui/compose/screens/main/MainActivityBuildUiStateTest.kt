package com.wuxianggujun.tinaide.ui.compose.screens.main

import com.google.common.truth.Truth.assertThat
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

        val committed = state.commitRunConfigManager(updatedManager) { true }

        assertThat(committed).isTrue()
        assertThat(state.runConfigManager).isEqualTo(updatedManager)
    }

    @Test
    fun commitRunConfigManager_whenPersistenceFails_shouldKeepPreviousState() {
        val initialManager = runConfigManagerWithSelectedName("Debug")
        val updatedManager = runConfigManagerWithSelectedName("Release")
        val state = MainActivityBuildUiState(initialManager)

        val committed = state.commitRunConfigManager(updatedManager) { false }

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

        state.commitRunConfigManager(initialManager.selectConfig(releaseConfig.id)) { false }

        assertThat(state.runConfigManager.selectedConfig).isEqualTo(debugConfig)
    }

    private fun runConfigManagerWithSelectedName(name: String): RunConfigurationManager {
        val config = RunConfiguration(name = name)
        return RunConfigurationManager(
            configurations = listOf(config),
            selectedId = config.id
        )
    }
}
