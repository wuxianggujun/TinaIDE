package com.wuxianggujun.tinaide.settings

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.compose.screens.testing.DevTestActivity
import com.wuxianggujun.tinaide.ui.compose.screens.testing.DevTestActivitySupport
import com.wuxianggujun.tinaide.ui.compose.screens.testing.DevTestIds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
class SettingsActivitySupportTest {

    @Test
    fun buildStartIntent_shouldPopulateInitialRouteAndAddNewTaskForApplicationContext() {
        val application = RuntimeEnvironment.getApplication()

        val rootIntent = SettingsActivitySupport.buildStartIntent(application)
        val feedbackIntent = SettingsActivitySupport.buildStartIntent(
            application,
            SettingsRoute.Feedback,
            "plugin-quick-start",
        )

        assertThat(rootIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(feedbackIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(rootIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(feedbackIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(SettingsActivitySupport.extractInitialRouteId(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialRouteId(feedbackIntent))
            .isEqualTo(SettingsRoute.Feedback.route)
        assertThat(SettingsActivitySupport.extractInitialHelpDocumentId(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialHelpDocumentId(feedbackIntent))
            .isEqualTo("plugin-quick-start")
    }

    @Test
    fun buildStartIntent_shouldAvoidAddingNewTaskFlagForActivityContext() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val intent = SettingsActivitySupport.buildStartIntent(activity, SettingsRoute.Terminal)

        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        assertThat(SettingsActivitySupport.extractInitialRouteId(intent))
            .isEqualTo(SettingsRoute.Terminal.route)
    }

    @Test
    fun start_shouldLaunchSettingsActivityWithResolvedRoute() {
        val application = RuntimeEnvironment.getApplication()

        SettingsActivity.start(application, SettingsRoute.Help, "plugin-quick-start")

        val startedIntent = shadowOf(application).nextStartedActivity
        assertThat(startedIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(SettingsActivitySupport.extractInitialRouteId(startedIntent))
            .isEqualTo(SettingsRoute.Help.route)
        assertThat(SettingsActivitySupport.extractInitialHelpDocumentId(startedIntent))
            .isEqualTo("plugin-quick-start")
        assertThat(SettingsActivitySupport.resolveInitialRoute(startedIntent))
            .isEqualTo(SettingsRoute.Help)
    }

    @Test
    fun resolveInitialRoute_shouldHandleAllSupportedRoutesAndFallbackToRoot() {
        val supportedRoutes = listOf(
            SettingsRoute.Root,
            SettingsRoute.Editor,
            SettingsRoute.Lsp,
            SettingsRoute.Compiler,
            SettingsRoute.Project,
            SettingsRoute.Storage,
            SettingsRoute.StorageCleanup,
            SettingsRoute.Terminal,
            SettingsRoute.Ai,
            SettingsRoute.Git,
            SettingsRoute.Appearance,
            SettingsRoute.Keyboard,
            SettingsRoute.Plugins,
            SettingsRoute.Packages,
            SettingsRoute.PluginMarketplace,
            SettingsRoute.PluginLog,
            SettingsRoute.Help,
            SettingsRoute.Feedback,
            SettingsRoute.Developer,
            SettingsRoute.About
        )

        supportedRoutes.forEach { route ->
            assertThat(SettingsActivitySupport.resolveInitialRoute(route.route)).isEqualTo(route)
        }
        assertThat(SettingsActivitySupport.resolveInitialRoute("missing_route"))
            .isEqualTo(SettingsRoute.Root)
        assertThat(SettingsActivitySupport.resolveInitialRoute("")).isEqualTo(SettingsRoute.Root)
        assertThat(SettingsActivitySupport.resolveInitialRoute("   ")).isEqualTo(SettingsRoute.Root)
        assertThat(SettingsActivitySupport.resolveInitialRoute(null)).isEqualTo(SettingsRoute.Root)
    }

    @Test
    fun buildDevTestIntent_shouldReuseDeveloperTestActivityBuilder() {
        val application = RuntimeEnvironment.getApplication()

        val intent = SettingsActivitySupport.buildDevTestIntent(application, DevTestIds.Clangd)

        assertThat(intent.component?.className).isEqualTo(DevTestActivity::class.java.name)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(DevTestActivitySupport.extractInitialTestId(intent)).isEqualTo(DevTestIds.Clangd)
        assertThat(DevTestActivitySupport.extractFinishOnBackIfDirect(intent)).isFalse()
    }

    @Test
    fun buildDevTestIntent_shouldOpenUnifiedTestingToolsListWhenTestIdMissing() {
        val application = RuntimeEnvironment.getApplication()

        val intent = SettingsActivitySupport.buildDevTestIntent(application)

        assertThat(intent.component?.className).isEqualTo(DevTestActivity::class.java.name)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(DevTestActivitySupport.extractInitialTestId(intent)).isNull()
        assertThat(DevTestActivitySupport.extractFinishOnBackIfDirect(intent)).isFalse()
    }
}
