package com.wuxianggujun.tinaide.ui.sdl

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class,
)
class SdlEntryPointResolverTest {

    @Test
    fun `resolve reports SDL_main export`() {
        val info = SdlEntryPointResolver.resolve(tempLibrary()) { setOf("SDL_main", "SDL_AppInit") }
            ?: error("expected EntryPointInfo")

        assertThat(info.hasSdlMain).isTrue()
        assertThat(info.hasPlainMain).isFalse()
    }

    @Test
    fun `resolve reports plain main without SDL_main`() {
        val info = SdlEntryPointResolver.resolve(tempLibrary()) { setOf("main", "SDL_Init") }
            ?: error("expected EntryPointInfo")

        assertThat(info.hasSdlMain).isFalse()
        assertThat(info.hasPlainMain).isTrue()
    }

    @Test
    fun `resolve returns null when symbols are unreadable`() {
        val info = SdlEntryPointResolver.resolve(tempLibrary()) { null }

        assertThat(info).isNull()
    }

    @Test
    fun `entry point message guides SDL3 projects to include SDL_main h`() {
        val message = SdlEntryPointResolver.entryPointErrorMessage(
            context = RuntimeEnvironment.getApplication(),
            info = SdlEntryPointResolver.EntryPointInfo(hasSdlMain = false, hasPlainMain = false),
            sdlMajor = 3,
        )

        assertThat(message).contains("SDL3/SDL_main.h")
    }

    @Test
    fun `entry point message guides SDL2 projects to include SDL_main h`() {
        val message = SdlEntryPointResolver.entryPointErrorMessage(
            context = RuntimeEnvironment.getApplication(),
            info = SdlEntryPointResolver.EntryPointInfo(hasSdlMain = false, hasPlainMain = true),
            sdlMajor = 2,
        )

        assertThat(message).contains("SDL2/SDL_main.h")
    }

    @Test
    fun `entry point message is null when SDL_main exists or symbols are unknown`() {
        val context = RuntimeEnvironment.getApplication()

        assertThat(
            SdlEntryPointResolver.entryPointErrorMessage(
                context = context,
                info = SdlEntryPointResolver.EntryPointInfo(hasSdlMain = true, hasPlainMain = false),
                sdlMajor = 3,
            )
        ).isNull()
        assertThat(
            SdlEntryPointResolver.entryPointErrorMessage(
                context = context,
                info = null,
                sdlMajor = 3,
            )
        ).isNull()
    }

    private fun tempLibrary(): File =
        Files.createTempDirectory("sdl-entry-point-test").toFile().resolve("libmain.so")
}
