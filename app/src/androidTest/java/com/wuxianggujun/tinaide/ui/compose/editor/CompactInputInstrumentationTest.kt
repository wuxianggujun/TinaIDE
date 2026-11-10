package com.wuxianggujun.tinaide.ui.compose.editor

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuxianggujun.tinaide.ui.compose.components.TinaTextField
import com.wuxianggujun.tinaide.ui.compose.components.VariableTextField
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import com.wuxianggujun.tinaide.ui.regressionEvidenceDirectory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactInputInstrumentationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun compactField_preservesMiddleSelectionAndExternalUpdates() {
        var text by mutableStateOf("abcdef")
        composeRule.setContent { TinaIDETheme {
            Column { TinaTextField(text, { text = it }, Modifier.testTag("compact")) }
        } }
        val field = composeRule.onNodeWithTag("compact")
        field.assertHeightIsEqualTo(44.dp)
        field.performClick()
        field.performTextInputSelection(TextRange(2, 4))
        field.performTextInput("中文😀")
        composeRule.runOnIdle { assertEquals("ab中文😀ef", text); text = "short" }
        field.assertTextEquals("short")
        field.performTextInputSelection(TextRange(5))
        field.performTextInput("!")
        composeRule.runOnIdle { assertEquals("short!", text) }
        screenshot("compact-input")
    }

    @Test fun variableCompletion_usesActualCaretAndKeepsSuffix() {
        var text by mutableStateOf("prefix  tail")
        composeRule.setContent { TinaIDETheme {
            VariableTextField(text, { text = it }, "Command", "", {}, Modifier.testTag("variable"))
        } }
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange(7))
        field.performTextInput("\$Pro")
        composeRule.onNodeWithText("\$ProjectDir\$").performClick()
        composeRule.runOnIdle { assertEquals("prefix \$ProjectDir\$ tail", text) }
        field.performTextInput("X")
        composeRule.runOnIdle { assertEquals("prefix \$ProjectDir\$X tail", text) }
        screenshot("variable-caret")
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        SystemClock.sleep(200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(regressionEvidenceDirectory(instrumentation.targetContext), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
