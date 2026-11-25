package com.wuxianggujun.tinaide.startup

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacyServerCleanupTest {
    private lateinit var context: Context
    private val preferenceNames = listOf(
        "tina_server_config_cleanup_test",
        "server_config_cleanup_test",
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferenceNames.forEach(context::deleteSharedPreferences)
    }

    @After
    fun tearDown() {
        preferenceNames.forEach(context::deleteSharedPreferences)
    }

    @Test
    fun emptyOrMissingLegacyPreferencesAreAccepted() {
        preferenceNames.forEach { name ->
            LegacyServerCleanup.removeLegacyPreferences(context, name)
            assertThat(context.getSharedPreferences(name, Context.MODE_PRIVATE).all).isEmpty()
        }
    }

    @Test
    fun existingLegacyEndpointConfigurationIsDeleted() {
        val name = preferenceNames.first()
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", "https://legacy.example")
            .commit()

        LegacyServerCleanup.removeLegacyPreferences(context, name)

        assertThat(context.getSharedPreferences(name, Context.MODE_PRIVATE).all).isEmpty()
    }
}
