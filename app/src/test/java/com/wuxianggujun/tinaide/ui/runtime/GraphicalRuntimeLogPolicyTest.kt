package com.wuxianggujun.tinaide.ui.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GraphicalRuntimeLogPolicyTest {
    @Test
    fun vendorTouchDiagnosticsAreHiddenRegardlessOfGraphicalHost() {
        assertThat(GraphicalRuntimeLogPolicy.logcatFilters(false)).contains("DynamicFramerate [AnimationSpeedAware]:W")
        for (tag in listOf("DynamicFramerate", "DynamicFramerate [AnimationSpeedAware]", "DynamicFramerate [DynamicFrameRateController]")) {
            for (level in "VDI") assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isFalse()
            for (level in "WEF") assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isTrue()
            for (level in "VDIWEF") assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, true)).isTrue()
        }
        assertThat(GraphicalRuntimeLogPolicy.accepts("MyDynamicFramerate", 'I', false)).isTrue()
        assertThat(GraphicalRuntimeLogPolicy.accepts("SDL", 'I', false)).isTrue()
    }

    @Test
    fun decoratedFrameworkTagsIgnorePaddingBeforeTheDecoration() {
        for (tag in listOf("ViewRootImpl [ExternalSdlActivity]", "VRI [ExternalSdl2Activity]", "SurfaceView @abc")) {
            assertThat(GraphicalRuntimeLogPolicy.accepts(tag, 'I', false)).isFalse()
        }
    }

    @Test
    fun everyEngineAndArbitraryUserTagKeepsEveryPriorityByDefault() {
        val tags = listOf("TINA_USER_OUTPUT", "SDL", "raylib", "MyNativeActivity", "VulkanSample", "Qt", "自定义Tag", "System.out", "System.err")
        for (tag in tags) for (level in "VDIWEF") {
            assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isTrue()
        }
    }

    @Test
    fun windowAndInputNoiseIsHiddenForEveryGraphicalHost() {
        val tags = listOf("ViewRootImpl", "VRI[ExternalSdlActivity]@123", "ViewRootImpl[ExternalSdl2Activity]", "SurfaceView@abc", "VRI[ExternalNativeActivity]", "ImeTracker")
        for (tag in tags) for (level in "VDIWEF") {
            assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isFalse()
            assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, true)).isTrue()
        }
    }

    @Test
    fun hostLifecycleNoiseIsHiddenButItsWarningsAndErrorsRemainVisible() {
        for (tag in listOf("TINA_SDL_HOST", "ExternalSdlActivity", "ExternalSdl2Activity", "ExternalNativeActivity", "TinaApplication")) {
            for (level in "VDI") assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isFalse()
            for (level in "WEF") assertThat(GraphicalRuntimeLogPolicy.accepts(tag, level, false)).isTrue()
        }
    }

    @Test
    fun criticalJavaAndNativeFailuresAreNotTreatedAsWindowNoise() {
        assertThat(GraphicalRuntimeLogPolicy.accepts("AndroidRuntime", 'E', false)).isTrue()
        assertThat(GraphicalRuntimeLogPolicy.accepts("libc", 'F', false)).isTrue()
        assertThat(GraphicalRuntimeLogPolicy.accepts("TinaNativeActivity", 'E', false)).isTrue()
        assertThat(GraphicalRuntimeLogPolicy.accepts("NativeStdStreamRedirect", 'W', false)).isTrue()
    }

    @Test
    fun similarUserTagsAreNotMistakenForFrameworkTags() {
        for (tag in listOf("MyViewRootImpl", "SDL_CustomTag", "Custom[ViewRootImpl]", "ChoreographerDemo")) {
            assertThat(GraphicalRuntimeLogPolicy.accepts(tag, 'D', false)).isTrue()
        }
    }
}
