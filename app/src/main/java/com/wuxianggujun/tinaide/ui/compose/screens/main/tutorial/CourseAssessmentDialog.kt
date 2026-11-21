package com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.editor.IEditorManager
import com.wuxianggujun.tinaide.file.IProjectContext
import com.wuxianggujun.tinaide.tutorial.CourseAssessment
import com.wuxianggujun.tinaide.tutorial.CourseAssessmentResult
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.repository.TutorialProgressStore
import com.wuxianggujun.tinaide.tutorial.repository.TutorialRepository
import com.wuxianggujun.tinaide.ui.CompilerViewModel
import com.wuxianggujun.tinaide.ui.compose.components.TinaCustomDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

@Composable
internal fun CourseAssessmentDialog(compiler: CompilerViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val editor: IEditorManager = koinInject()
    val projects: IProjectContext = koinInject()
    val scope = rememberCoroutineScope()
    val store = remember { TutorialProgressStore(context) }
    var course by remember { mutableStateOf<Tutorial?>(null) }
    var stepIndex by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CourseAssessmentResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val project = remember { projects.getCurrentProject() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(Unit) {
        try {
            course = TutorialRepository(context, store).getTutorial(TutorialRepository.CPP_PRACTICE_COURSE_ID)
            val progress = store.getProgress(TutorialRepository.CPP_PRACTICE_COURSE_ID)
            stepIndex = progress?.currentStepIndex?.coerceIn(0, requireNotNull(course).courseSteps.lastIndex) ?: 0
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Timber.w(error, "Cannot load course assessment"); failed = true }
    }
    val step = course?.courseSteps?.getOrNull(stepIndex)
    TinaCustomDialog(onDismissRequest = { job?.cancel(); onDismiss() }) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Strings.course_assessment_title), style = MaterialTheme.typography.titleLarge)
            Text(project?.name ?: stringResource(Strings.empty_editor_title))
            step?.let { Text(stringResource(it.titleRes), style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(Strings.course_assessment_description))
            if (result == null && step != null) CourseAssessment.checks(step.id).forEachIndexed { index, expected ->
                Text(stringResource(Strings.course_assessment_expected, index + 1, expected.input.trim(), expected.exitCode, expected.expectedOutput), style = MaterialTheme.typography.bodySmall)
            }
            if (step != null && CourseAssessment.checks(step.id).isEmpty()) Text(stringResource(Strings.course_assessment_manual))
            if (running) CircularProgressIndicator()
            if (failed) Text(stringResource(Strings.course_assessment_failed), color = MaterialTheme.colorScheme.error)
            result?.let { checked ->
                Text(stringResource(if (checked.sourceChanged) Strings.course_assessment_stale else if (checked.passed) Strings.course_assessment_passed else Strings.course_assessment_mismatch))
                checked.checks.forEachIndexed { index, (expected, actual) ->
                    Text(stringResource(Strings.course_assessment_case, index + 1, expected.input.trim(), expected.exitCode, expected.expectedOutput, actual.exitCode, actual.output.take(1500)))
                    if (actual.timedOut) Text(stringResource(Strings.course_assessment_timeout))
                }
            }
            if (saved) Text(stringResource(Strings.course_assessment_recorded))
            Button(
                enabled = ready && !running && project != null && step != null && CourseAssessment.checks(step.id).isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val selectedStep = step ?: return@Button
                    running = true; failed = false; result = null; saved = false
                    job = scope.launch {
                        try {
                            check(projects.getCurrentProject()?.rootPath == project?.rootPath)
                            val baseline = store.getProgress(TutorialRepository.CPP_PRACTICE_COURSE_ID)
                            check((baseline?.currentStepIndex ?: 0) == stepIndex)
                            val checked = compiler.assessCourse(selectedStep.id, editor)
                            result = checked
                            if (checked.passed) {
                                val previousSteps = requireNotNull(course).courseSteps.take(stepIndex)
                                saved = store.recordVerifiedStep(TutorialRepository.CPP_PRACTICE_COURSE_ID,
                                    selectedStep.id, stepIndex, previousSteps.map { it.id }.toSet(), baseline)
                                if (!saved) result = checked.copy(sourceChanged = true)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { Timber.w(error, "Course assessment failed"); failed = true }
                        finally { running = false }
                    }
                },
            ) { Text(stringResource(Strings.course_assessment_run)) }
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(stringResource(Strings.action_close)) }
        }
    }
}
