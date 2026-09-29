package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompletionKindStyleTest {

    @Test
    fun completionKindStyle_shouldGroupCallableKindsWithSharedIconAndTint() {
        val expected = CompletionKindStyle(
            icon = CompletionKindIconKey.CALLABLE,
            tintColor = Color(0xFFBD6A7D)
        )

        assertThat(completionKindStyle(EditorCompletionKind.FUNCTION)).isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.METHOD))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.CONSTRUCTOR))
            .isEqualTo(expected)
    }

    @Test
    fun completionKindStyle_shouldKeepValueLikeKindsOnSharedPropertyIcon() {
        val expected = CompletionKindStyle(
            icon = CompletionKindIconKey.PROPERTY,
            tintColor = Color(0xFFB98A2C)
        )

        assertThat(completionKindStyle(EditorCompletionKind.FIELD))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.VARIABLE))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.VALUE))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.CONSTANT))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.TYPE_PARAMETER))
            .isEqualTo(expected)
    }

    @Test
    fun completionKindStyle_shouldReserveDescriptionIconForMutedTextualKinds() {
        val expected = CompletionKindStyle(
            icon = CompletionKindIconKey.DESCRIPTION,
            tintColor = Color(0xFF7D8791)
        )

        assertThat(completionKindStyle(EditorCompletionKind.UNIT))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.SNIPPET))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.REFERENCE))
            .isEqualTo(expected)
        assertThat(completionKindStyle(EditorCompletionKind.TEXT))
            .isEqualTo(expected)
    }

    @Test
    fun completionKindStyle_shouldUseFilesystemIconsForFileAndFolderKinds() {
        val mutedColor = Color(0xFF7D8791)

        assertThat(completionKindStyle(EditorCompletionKind.FILE))
            .isEqualTo(CompletionKindStyle(CompletionKindIconKey.FILE, mutedColor))
        assertThat(completionKindStyle(EditorCompletionKind.FOLDER))
            .isEqualTo(CompletionKindStyle(CompletionKindIconKey.FOLDER, mutedColor))
    }
}
