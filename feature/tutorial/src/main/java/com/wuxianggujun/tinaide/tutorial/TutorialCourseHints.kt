package com.wuxianggujun.tinaide.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.ui.compose.components.EditorMarkdownViewer as MarkdownViewer
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard

/** Revealing help is independent from completing or unlocking a course stage. */
@Composable
fun TutorialCourseHints(
    stepId: String,
    hints: List<String>,
    onCodeCopy: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    var revealedCount by rememberSaveable(stepId) { mutableIntStateOf(0) }
    val visibleCount = revealedCount.coerceIn(0, hints.size)

    TinaCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(Strings.tutorial_course_hints_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Strings.tutorial_course_hints_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hints.take(visibleCount).forEachIndexed { index, hint ->
                Column(modifier = Modifier.testTag("tutorial-course-hint-$stepId-$index")) {
                    Text(
                        stringResource(Strings.tutorial_course_hint_number, index + 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MarkdownViewer(
                        markdown = hint,
                        contentPadding = PaddingValues(0.dp),
                        onLinkClick = onLinkClick,
                        onCodeCopy = onCodeCopy,
                    )
                }
            }
            if (visibleCount < hints.size) {
                OutlinedButton(
                    onClick = { revealedCount = visibleCount + 1 },
                    modifier = Modifier.fillMaxWidth().testTag("tutorial-course-hint-next-$stepId"),
                ) {
                    Text(stringResource(Strings.tutorial_course_hint_reveal, visibleCount + 1, hints.size))
                }
            }
            if (visibleCount > 0) {
                TextButton(
                    onClick = { revealedCount = 0 },
                    modifier = Modifier.testTag("tutorial-course-hints-hide-$stepId"),
                ) {
                    Text(stringResource(Strings.tutorial_course_hints_hide))
                }
            }
        }
    }
}
