package com.wuxianggujun.tinaide.startup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppProcessRoleClassifierTest {
    private val packageName = "com.wuxianggujun.tinaide"

    @Test
    fun `classifies every supported application process`() {
        assertThat(AppProcessRoleClassifier.classify(packageName, packageName))
            .isEqualTo(AppProcessRole.HOST)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:crash"))
            .isEqualTo(AppProcessRole.CRASH)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:toolchain"))
            .isEqualTo(AppProcessRole.TOOLCHAIN)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:plugin_runtime"))
            .isEqualTo(AppProcessRole.PLUGIN_RUNTIME)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:gui"))
            .isEqualTo(AppProcessRole.USER_RUNTIME)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:sdl"))
            .isEqualTo(AppProcessRole.USER_RUNTIME)
        assertThat(AppProcessRoleClassifier.classify(packageName, "$packageName:unexpected"))
            .isEqualTo(AppProcessRole.OTHER)
    }

    @Test
    fun `does not treat blank process as host`() {
        assertThat(AppProcessRoleClassifier.classify(packageName, ""))
            .isEqualTo(AppProcessRole.OTHER)
        assertThat(AppProcessRoleClassifier.classify("", ":crash"))
            .isEqualTo(AppProcessRole.OTHER)
    }
}
