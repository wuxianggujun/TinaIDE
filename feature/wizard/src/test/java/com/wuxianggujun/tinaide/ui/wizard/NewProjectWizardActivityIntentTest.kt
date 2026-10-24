package com.wuxianggujun.tinaide.ui.wizard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Application
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class NewProjectWizardActivityIntentTest {

    @Test
    fun createPluginProjectIntent_shouldOpenWizardInPluginMode() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        val intent = NewProjectWizardActivity.createPluginProjectIntent(context)

        assertThat(intent.component?.className).isEqualTo(NewProjectWizardActivity::class.java.name)
        assertThat(intent.getBooleanExtra(NewProjectWizardActivity.EXTRA_PREFER_PLUGIN_TEMPLATE, false)).isTrue()
        assertThat(intent.getStringExtra(NewProjectWizardActivity.EXTRA_INITIAL_TEMPLATE_ID))
            .isEqualTo("plugin:tinaide.plugin.starters:config-basic")
    }

    @Test
    fun createIntent_shouldKeepDefaultWizardMode() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        val intent = NewProjectWizardActivity.createIntent(context)

        assertThat(intent.component?.className).isEqualTo(NewProjectWizardActivity::class.java.name)
        assertThat(intent.getBooleanExtra(NewProjectWizardActivity.EXTRA_PREFER_PLUGIN_TEMPLATE, false)).isFalse()
        assertThat(intent.getStringExtra(NewProjectWizardActivity.EXTRA_INITIAL_TEMPLATE_ID)).isNull()
    }
}
