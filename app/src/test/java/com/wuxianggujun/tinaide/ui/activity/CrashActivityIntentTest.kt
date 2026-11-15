package com.wuxianggujun.tinaide.ui.activity

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.crash.CrashReportFiles
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashActivityIntentTest {
    @Test
    fun launchingACrashScreen_doesNotClearTheHostTask() {
        val context = RuntimeEnvironment.getApplication()
        val intent = CrashActivity.createIntent(context, "signal 11 (SIGSEGV)", "user_native", "", "${context.packageName}:sdl")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(intent.getStringExtra(CrashActivity.EXTRA_PROCESS_NAME)).isEqualTo("${context.packageName}:sdl")
    }

    @Test
    fun crashTaskAffinity_isDistinctFromTheIdeHost() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, CrashActivity::class.java), 0)
        assertThat(info.taskAffinity).isEqualTo("${context.packageName}.crash")
        assertThat(info.processName).isEqualTo("${context.packageName}:crash")
    }

    @Test
    fun truncatingThePreview_doesNotLoseTheProcessIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val intent = CrashActivity.createIntent(
            context, "x".repeat(CrashReportFiles.MAX_PREVIEW_CHARS + 1000), "app", "", "${context.packageName}:gui"
        )
        val origin = resolveCrashScreenOrigin(
            context.packageName,
            intent.getStringExtra(CrashActivity.EXTRA_PROCESS_NAME).orEmpty(),
            intent.getStringExtra(CrashActivity.EXTRA_SOURCE),
            intent.getStringExtra(CrashActivity.EXTRA_STACK_TRACE).orEmpty(),
        )
        assertThat(origin).isEqualTo(CrashScreenOrigin.USER_PROGRAM)
    }
}
