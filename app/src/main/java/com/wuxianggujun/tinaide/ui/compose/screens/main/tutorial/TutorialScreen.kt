package com.wuxianggujun.tinaide.ui.compose.screens.main.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.help.HelpRepository
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialCourseAction
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.tutorial.TutorialCourseContentState
import com.wuxianggujun.tinaide.tutorial.firstUnlockedCourseStepIndex
import com.wuxianggujun.tinaide.tutorial.loadTutorialCourseContent
import com.wuxianggujun.tinaide.ui.compose.components.MarkdownViewer
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.screens.main.market.MarketTab
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute

/**
 * 教程屏幕
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TutorialScreen(
    modifier: Modifier = Modifier,
    viewModel: TutorialViewModel = viewModel(),
    onOpenProjectTab: () -> Unit = {},
    onOpenMarketTab: (MarketTab) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val helpRepository = remember(context.applicationContext) {
        HelpRepository(context.applicationContext)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogState by viewModel.tutorialCatalogState.collectAsStateWithLifecycle()
    val tutorialsByCategory = catalogState.tutorialsByCategory

    val selectedTutorial = uiState.selectedTutorial
    val showContent = uiState.showTutorialContent && selectedTutorial != null
    val closeTutorialContent = viewModel::closeTutorialContent

    TinaBackHandlers(
        tinaBackAction(enabled = showContent, onBack = closeTutorialContent)
    )

    Scaffold(
        topBar = {
            if (showContent) {
                TinaTopBar(
                    title = stringResource(selectedTutorial!!.titleRes),
                    modifier = Modifier.semantics { testTagsAsResourceId = true }
                        .testTag("tutorial-reader-${selectedTutorial.id}"),
                    onNavigateBack = closeTutorialContent
                )
            } else {
                TinaTopBar(
                    title = stringResource(Strings.tutorial_title)
                )
            }
        }
    ) { padding ->
        if (showContent) {
            val selected = selectedTutorial!!
            val openLink: (String) -> Unit = { target ->
                if (!target.startsWith("#")) {
                    val helpDocument = helpRepository.resolveDocumentByLinkTarget(target)
                    when {
                        selected.type == TutorialType.GUIDED &&
                            selected.courseSteps.getOrNull(uiState.currentCourseStepIndex)?.helpLinkTarget == target &&
                            helpDocument != null -> {
                            SettingsActivity.start(
                                context = context,
                                initialRoute = SettingsRoute.Help,
                                initialHelpDocumentId = helpDocument.id,
                            )
                        }

                        selected.type == TutorialType.GUIDED &&
                            viewModel.openCourseStepByLinkTarget(selected, target) -> Unit

                        viewModel.openTutorialByLinkTarget(target) -> Unit

                        helpDocument != null -> {
                            SettingsActivity.start(
                                context = context,
                                initialRoute = SettingsRoute.Help,
                                initialHelpDocumentId = helpDocument.id,
                            )
                        }

                        else -> runCatching { uriHandler.openUri(target) }
                    }
                }
            }
            if (selected.type == TutorialType.GUIDED) {
                TutorialGuidedCourseContent(
                    tutorial = selected,
                    currentStepIndex = uiState.currentCourseStepIndex,
                    completedStepIds = uiState.completedCourseStepIds,
                    onOpenProjectTab = onOpenProjectTab,
                    onOpenMarketTab = onOpenMarketTab,
                    onOpenHelp = openLink,
                    loadCourseContent = helpRepository::loadTutorialCourseContentByLinkTarget,
                    onPrevious = viewModel::previousCourseStep,
                    onNext = viewModel::nextCourseStep,
                    onSelectStep = viewModel::selectCourseStep,
                    onStepCompletion = { stepId, completed ->
                        viewModel.setCourseStepCompleted(selected.id, stepId, completed)
                    },
                    onComplete = { viewModel.completeTutorial(selected.id) },
                    modifier = modifier.fillMaxSize().padding(padding),
                )
            }
        } else if (catalogState.isLoading) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (catalogState.hasLoadError) {
            Column(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(Strings.tutorial_catalog_load_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = viewModel::retryCatalogLoading) {
                    Text(stringResource(Strings.action_retry))
                }
            }
        } else if (tutorialsByCategory.isEmpty()) {
            // 空状态
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Strings.tutorial_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                tutorialsByCategory.forEach { (category, tutorials) ->
                    val isExpanded = uiState.expandedCategories.contains(category)

                    item(key = "header_${category.name}") {
                        TutorialCategoryHeader(
                            category = category,
                            tutorialCount = tutorials.size,
                            isExpanded = isExpanded,
                            onClick = { viewModel.toggleCategory(category) }
                        )
                    }

                    if (isExpanded) {
                        items(
                            items = tutorials,
                            key = { it.tutorial.id }
                        ) { tutorialWithProgress ->
                            TutorialCard(
                                tutorialWithProgress = tutorialWithProgress,
                                onClick = {
                                    val tutorial = tutorialWithProgress.tutorial
                                    if (tutorialWithProgress.isInProgress) {
                                        viewModel.continueTutorial(tutorial)
                                    } else {
                                        viewModel.startTutorial(tutorial)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialGuidedCourseContent(
    tutorial: Tutorial,
    currentStepIndex: Int,
    completedStepIds: Set<String>,
    onOpenProjectTab: () -> Unit,
    onOpenMarketTab: (MarketTab) -> Unit,
    onOpenHelp: (String) -> Unit,
    loadCourseContent: suspend (String) -> Result<String>,
    onPrevious: (Tutorial) -> Unit,
    onNext: (Tutorial) -> Unit,
    onSelectStep: (Tutorial, Int) -> Unit,
    onStepCompletion: (String, Boolean) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = tutorial.courseSteps
    val stepIndex = currentStepIndex.coerceIn(0, steps.lastIndex.coerceAtLeast(0))
    val step = steps.getOrNull(stepIndex) ?: return
    val scrollState = key(tutorial.id, step.id) { rememberScrollState() }
    val quiz = step.quiz
    val contentLinkTarget = step.contentLinkTarget
    var contentLoadState by remember(contentLinkTarget) {
        mutableStateOf<TutorialCourseContentState?>(
            if (contentLinkTarget == null) null else TutorialCourseContentState.Loading,
        )
    }
    var retryRequest by remember(contentLinkTarget) { mutableIntStateOf(0) }
    var selectedAnswerIndex by rememberSaveable(tutorial.id, step.id) { mutableIntStateOf(-1) }
    var showChapterMenu by rememberSaveable(tutorial.id, step.id) { mutableStateOf(false) }
    val isStepCompleted = step.id in completedStepIds
    val isLastStep = stepIndex == steps.lastIndex
    val lastUnlockedStepIndex = firstUnlockedCourseStepIndex(steps, completedStepIds)
    val hasPassedQuiz = quiz == null || isStepCompleted || selectedAnswerIndex == quiz.correctAnswerIndex
    val contentLoaded = contentLinkTarget == null || contentLoadState is TutorialCourseContentState.Content
    val canContinue = if (quiz != null) hasPassedQuiz else isStepCompleted && contentLoaded

    LaunchedEffect(contentLinkTarget, retryRequest) {
        if (contentLinkTarget != null) {
            contentLoadState = TutorialCourseContentState.Loading
            contentLoadState = loadTutorialCourseContent(contentLinkTarget, loadCourseContent)
        }
    }

    LaunchedEffect(tutorial.id, step.id, isStepCompleted, quiz?.correctAnswerIndex) {
        if (quiz != null && isStepCompleted) {
            selectedAnswerIndex = quiz.correctAnswerIndex
        }
    }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Strings.tutorial_course_step_progress, stepIndex + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Box {
                    TextButton(
                        onClick = { showChapterMenu = true },
                        modifier = Modifier.testTag("tutorial-course-outline"),
                    ) {
                        Text(stringResource(Strings.tutorial_course_outline))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(
                        expanded = showChapterMenu,
                        onDismissRequest = { showChapterMenu = false },
                    ) {
                        steps.forEachIndexed { index, courseStep ->
                            val statusRes = when {
                                index == stepIndex -> Strings.tutorial_course_chapter_current
                                index > lastUnlockedStepIndex -> Strings.tutorial_course_chapter_locked
                                courseStep.id in completedStepIds -> Strings.tutorial_course_chapter_completed
                                else -> Strings.tutorial_course_chapter_available
                            }
                            DropdownMenuItem(
                                enabled = index <= lastUnlockedStepIndex,
                                modifier = Modifier.testTag("tutorial-course-outline-step-${courseStep.id}"),
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(courseStep.titleRes),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = stringResource(statusRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    showChapterMenu = false
                                    if (index != stepIndex) onSelectStep(tutorial, index)
                                },
                            )
                        }
                    }
                }
            }
            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / steps.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TinaCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(step.descriptionRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            when (step.action) {
                TutorialCourseAction.OPEN_PROJECTS -> Button(
                    onClick = onOpenProjectTab,
                    modifier = Modifier.fillMaxWidth().testTag("tutorial-course-open-projects"),
                ) {
                    Text(stringResource(Strings.tutorial_course_action_open_projects))
                }

                TutorialCourseAction.OPEN_MARKET -> Button(
                    onClick = { onOpenMarketTab(MarketTab.VCPKG) },
                    modifier = Modifier.fillMaxWidth().testTag("tutorial-course-open-market"),
                ) {
                    Text(stringResource(Strings.tutorial_course_action_open_market))
                }

                TutorialCourseAction.OPEN_MARKET_PACKAGES -> Button(
                    onClick = { onOpenMarketTab(MarketTab.PACKAGES) },
                    modifier = Modifier.fillMaxWidth().testTag("tutorial-course-open-market-packages"),
                ) {
                    Text(stringResource(Strings.tutorial_course_action_open_market_packages))
                }

                null -> Unit
            }

            step.helpLinkTarget?.let { target ->
                Button(
                    onClick = { onOpenHelp(target) },
                    modifier = Modifier.fillMaxWidth().testTag("tutorial-course-open-help"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Text(stringResource(Strings.tutorial_course_open_help))
                }
            }

            when (val contentState = contentLoadState) {
                null -> Unit
                TutorialCourseContentState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                is TutorialCourseContentState.Content -> MarkdownViewer(
                    markdown = contentState.markdown,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                    onLinkClick = onOpenHelp,
                )

                TutorialCourseContentState.Error -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Strings.help_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { retryRequest += 1 },
                        modifier = Modifier.testTag("tutorial-course-retry-${step.id}"),
                    ) {
                        Text(stringResource(Strings.action_retry))
                    }
                }
            }

            if (quiz == null && contentLoaded) {
                TinaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStepCompletion(step.id, !isStepCompleted) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("tutorial-course-confirm-${step.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isStepCompleted,
                            onCheckedChange = { onStepCompletion(step.id, it) },
                        )
                        Text(
                            text = stringResource(Strings.tutorial_course_confirm_step),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            quiz?.let { courseQuiz ->
                TinaCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(courseQuiz.questionRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        courseQuiz.answerRes.forEachIndexed { index, answerRes ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selectedAnswerIndex = index
                                        onStepCompletion(step.id, index == courseQuiz.correctAnswerIndex)
                                    }
                                    .testTag("tutorial-course-quiz-answer-$index")
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedAnswerIndex == index,
                                    onClick = {
                                        selectedAnswerIndex = index
                                        onStepCompletion(step.id, index == courseQuiz.correctAnswerIndex)
                                    },
                                )
                                Text(
                                    text = stringResource(answerRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                                )
                            }
                        }
                        if (selectedAnswerIndex >= 0) {
                            Text(
                                text = stringResource(
                                    if (hasPassedQuiz) Strings.tutorial_course_quiz_correct
                                    else Strings.tutorial_course_quiz_wrong,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasPassedQuiz) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onPrevious(tutorial) },
                enabled = stepIndex > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(stringResource(Strings.tutorial_previous))
            }
            Button(
                onClick = if (isLastStep) onComplete else ({ onNext(tutorial) }),
                enabled = canContinue,
                modifier = Modifier.weight(1f).testTag("tutorial-course-continue"),
            ) {
                Text(
                    stringResource(
                        if (isLastStep) Strings.tutorial_course_finish
                        else Strings.tutorial_course_next_step,
                    ),
                )
            }
        }
    }
}

/**
 * 教程分类标题
 */
@Composable
private fun TutorialCategoryHeader(
    category: TutorialCategory,
    tutorialCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.tutorial_chapters, tutorialCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(category.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp, top = 6.dp)
        )
    }
}

/**
 * 教程卡片
 */
@Composable
private fun TutorialCard(
    tutorialWithProgress: TutorialWithProgress,
    onClick: () -> Unit
) {
    val tutorial = tutorialWithProgress.tutorial
    val progress = tutorialWithProgress.progressPercent
    val status = tutorialWithProgress.status

    TinaCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // 图标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                status == ProgressStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            status == ProgressStatus.COMPLETED -> Icons.Default.CheckCircle
                            else -> Icons.Default.TouchApp
                        },
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = when {
                            status == ProgressStatus.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 内容
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(tutorial.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (tutorial.type == TutorialType.GUIDED) Strings.tutorial_guided
                                else Strings.tutorial_interactive,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = stringResource(tutorial.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(Strings.tutorial_duration, tutorial.estimatedMinutes.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val chapterCount = tutorial.steps.size.takeIf { it > 0 } ?: tutorial.courseSteps.size
                        if (chapterCount > 0) {
                            Text(
                                text = stringResource(R.string.tutorial_chapters, chapterCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度或开始按钮
            when (status) {
                ProgressStatus.COMPLETED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(Strings.tutorial_completed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Button(
                            onClick = onClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(text = stringResource(Strings.tutorial_relearn))
                        }
                    }
                }

                ProgressStatus.IN_PROGRESS -> {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(Strings.tutorial_progress, progress),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(Strings.tutorial_continue),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(Strings.tutorial_continue))
                        }
                    }
                }

                ProgressStatus.NOT_STARTED -> {
                    Button(
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(Strings.tutorial_start))
                    }
                }
            }
        }
    }
}
