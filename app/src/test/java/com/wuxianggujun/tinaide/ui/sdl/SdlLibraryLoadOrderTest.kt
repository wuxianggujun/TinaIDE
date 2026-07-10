package com.wuxianggujun.tinaide.ui.sdl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SdlLibraryLoadOrderTest {
    @Test
    fun `load order places SDL dependencies before SDL and extensions after SDL`() {
        val result = buildSdlLibraryLoadOrder(
            preSdlLibraryPaths = listOf("/runtime/libc++_shared.so"),
            sdlLibraryPath = "/runtime/libSDL3.so",
            preloadLibraryPaths = listOf("/runtime/libcodec.so", "/runtime/libSDL3_image.so"),
            mainLibraryPath = "/project/libmain.so"
        )

        assertThat(result).containsExactly(
            "/runtime/libc++_shared.so",
            "/runtime/libSDL3.so",
            "/runtime/libcodec.so",
            "/runtime/libSDL3_image.so",
            "/project/libmain.so"
        ).inOrder()
    }

    @Test
    fun `load order removes duplicate absolute paths`() {
        val result = buildSdlLibraryLoadOrder(
            preSdlLibraryPaths = listOf("/runtime/libc++_shared.so"),
            sdlLibraryPath = "/runtime/libSDL3.so",
            preloadLibraryPaths = listOf("/runtime/libc++_shared.so", "/runtime/libSDL3.so"),
            mainLibraryPath = "/project/libmain.so"
        )

        assertThat(result).containsExactly(
            "/runtime/libc++_shared.so",
            "/runtime/libSDL3.so",
            "/project/libmain.so"
        ).inOrder()
    }
}
