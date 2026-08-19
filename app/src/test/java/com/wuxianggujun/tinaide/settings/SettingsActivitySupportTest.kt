package com.wuxianggujun.tinaide.settings

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.packages.PackageManagerNavigation
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
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
        val helpIntent = SettingsActivitySupport.buildStartIntent(
            application,
            SettingsRoute.Help,
            "plugin-quick-start",
            "tinaide.plugin.cpp-snippets",
        )

        assertThat(rootIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(helpIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(rootIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(helpIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(SettingsActivitySupport.extractInitialRouteId(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialRouteId(helpIntent))
            .isEqualTo(SettingsRoute.Help.route)
        assertThat(SettingsActivitySupport.extractInitialHelpDocumentId(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialHelpDocumentId(helpIntent))
            .isEqualTo("plugin-quick-start")
        assertThat(SettingsActivitySupport.extractInitialPluginDetailId(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialPluginDetailId(helpIntent))
            .isEqualTo("tinaide.plugin.cpp-snippets")
        assertThat(SettingsActivitySupport.extractInitialPackageSearchQuery(rootIntent)).isNull()
        assertThat(SettingsActivitySupport.extractInitialPackageSearchQuery(helpIntent)).isNull()
    }

    @Test
    fun buildStartIntent_shouldAvoidAddingNewTaskFlagForActivityContext() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val intent = SettingsActivitySupport.buildStartIntent(activity, SettingsRoute.Terminal)

        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(0)
        assertThat(SettingsActivitySupport.extractInitialRouteId(intent))
            .isEqualTo(SettingsRoute.Terminal.route)
        assertThat(SettingsActivitySupport.extractInitialPluginDetailId(intent)).isNull()
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
    fun startPluginDetail_shouldLaunchPluginSettingsWithInitialPluginId() {
        val application = RuntimeEnvironment.getApplication()

        SettingsActivity.startPluginDetail(application, "tinaide.plugin.cpp-snippets")

        val startedIntent = shadowOf(application).nextStartedActivity
        assertThat(startedIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(SettingsActivitySupport.extractInitialRouteId(startedIntent))
            .isEqualTo(SettingsRoute.Plugins.route)
        assertThat(SettingsActivitySupport.extractInitialPluginDetailId(startedIntent))
            .isEqualTo("tinaide.plugin.cpp-snippets")
        assertThat(SettingsActivitySupport.resolveInitialRoute(startedIntent))
            .isEqualTo(SettingsRoute.Plugins)
    }

    @Test
    fun startPackages_shouldLaunchPackagesSettingsWithInitialSearchQuery() {
        val application = RuntimeEnvironment.getApplication()

        SettingsActivity.startPackages(application, "sdl3-image")

        val startedIntent = shadowOf(application).nextStartedActivity
        assertThat(startedIntent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            .isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(SettingsActivitySupport.extractInitialRouteId(startedIntent))
            .isEqualTo(SettingsRoute.Packages.route)
        assertThat(SettingsActivitySupport.extractInitialPackageSearchQuery(startedIntent))
            .isEqualTo("sdl3-image")
        assertThat(SettingsActivitySupport.resolveInitialRoute(startedIntent))
            .isEqualTo(SettingsRoute.Packages)
    }

    @Test
    fun packageManagerNavigationIntent_shouldResolvePackagesRouteAndSearchQuery() {
        val application = RuntimeEnvironment.getApplication()

        val intent = PackageManagerNavigation.createIntent(application, "sdl2")

        assertThat(intent.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(SettingsActivitySupport.resolveInitialRoute(intent)).isEqualTo(SettingsRoute.Packages)
        assertThat(SettingsActivitySupport.extractInitialPackageSearchQuery(intent)).isEqualTo("sdl2")
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
}
