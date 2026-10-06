package com.wuxianggujun.tinaide.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import kotlinx.coroutines.delay

@Composable
fun GoToLineDialog(
    onDismiss: () -> Unit,
    onGoToLine: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var lineText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val parsedLine = lineText.toIntOrNull()
    val isLineValid = parsedLine != null && parsedLine > 0
    val hasLineError = lineText.isNotEmpty() && !isLineValid

    fun submit() {
        val line = parsedLine ?: return
        if (line <= 0) return
        keyboardController?.hide()
        onGoToLine(line)
    }

    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    TinaAlertDialog(
        onDismissRequest = {
            keyboardController?.hide()
            onDismiss()
        },
        title = { TinaDialogTitleText(stringResource(Strings.cmd_editor_goto_line)) },
        text = {
            TinaDialogContentColumn(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = lineText,
                    onValueChange = {
                        lineText = it.filter { c -> c.isDigit() }.take(9)
                    },
                    label = { Text(stringResource(Strings.label_line_number)) },
                    singleLine = true,
                    isError = hasLineError,
                    supportingText = if (hasLineError) {
                        {
                            Text(
                                text = stringResource(Strings.label_line_number),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TinaPrimaryButton(
                text = stringResource(Strings.btn_goto),
                onClick = ::submit,
                enabled = isLineValid
            )
        },
        dismissButton = {
            TinaTextButton(
                text = stringResource(Strings.btn_cancel),
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                }
            )
        }
    )
}
