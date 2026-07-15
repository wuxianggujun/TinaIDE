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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wuxianggujun.tinaide.R
import com.wuxianggujun.tinaide.core.help.HelpDocument
import com.wuxianggujun.tinaide.core.help.HelpRepository
import com.wuxianggujun.tinaide.core.i18n.Arrays
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.settings.SettingsActivity
import com.wuxianggujun.tinaide.tutorial.TutorialViewModel
import com.wuxianggujun.tinaide.tutorial.data.ProgressStatus
import com.wuxianggujun.tinaide.tutorial.data.Tutorial
import com.wuxianggujun.tinaide.tutorial.data.TutorialCategory
import com.wuxianggujun.tinaide.tutorial.data.TutorialType
import com.wuxianggujun.tinaide.tutorial.data.TutorialWithProgress
import com.wuxianggujun.tinaide.ui.compose.components.MarkdownViewer
import com.wuxianggujun.tinaide.ui.compose.components.TinaBackHandlers
import com.wuxianggujun.tinaide.ui.compose.components.TinaCard
import com.wuxianggujun.tinaide.ui.compose.components.TinaTopBar
import com.wuxianggujun.tinaide.ui.compose.components.tinaBackAction
import com.wuxianggujun.tinaide.ui.compose.screens.settings.SettingsRoute
import com.wuxianggujun.tinaide.ui.wizard.NewProjectWizardActivity
import kotlinx.coroutines.CancellationException

private const val PLUGIN_QUICK_START_TUTORIAL_ID = "plugin_quick_start"

internal sealed interface TutorialArticleLoadState {
    data object Loading : TutorialArticleLoadState

    data class Content(val markdown: String) : TutorialArticleLoadState

    data object Error : TutorialArticleLoadState
}

internal suspend fun loadTutorialArticle(
    contentUrl: String?,
    loadContent: suspend (String) -> Result<String>,
): TutorialArticleLoadState {
    if (contentUrl.isNullOrBlank()) return TutorialArticleLoadState.Error

    return try {
        val content = loadContent(contentUrl).getOrThrow()
        if (content.isBlank()) {
            TutorialArticleLoadState.Error
        } else {
            TutorialArticleLoadState.Content(content)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        TutorialArticleLoadState.Error
    }
}

/**
 * 教程屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    modifier: Modifier = Modifier,
    viewModel: TutorialViewModel = viewModel()
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
            TutorialArticleContent(
                tutorial = selectedTutorial!!,
                onComplete = { viewModel.completeTutorial(selectedTutorial.id) },
                onCreatePluginProject = {
                    context.startActivity(NewProjectWizardActivity.createPluginProjectIntent(context))
                },
                onOpenPluginSettings = {
                    SettingsActivity.start(
                        context = context,
                        initialRoute = SettingsRoute.Plugins,
                    )
                },
                resolveTutorialByLinkTarget = viewModel::resolveTutorialByLinkTarget,
                resolveHelpDocumentByLinkTarget = helpRepository::resolveDocumentByLinkTarget,
                loadTutorialContent = helpRepository::loadDocumentContentByLinkTarget,
                onLinkClick = { target ->
                    if (target.startsWith("#")) {
                        return@TutorialArticleContent
                    }

                    val helpDocument = helpRepository.resolveDocumentByLinkTarget(target)
                    when {
                        viewModel.openTutorialByLinkTarget(target) -> Unit

                        helpDocument != null -> {
                            SettingsActivity.start(
                                context = context,
                                initialRoute = SettingsRoute.Help,
                                initialHelpDocumentId = helpDocument.id,
                            )
                        }

                        else -> {
                            runCatching { uriHandler.openUri(target) }
                        }
                    }
                },
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (catalogState.isLoading) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
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
private fun TutorialArticleContent(
    tutorial: Tutorial,
    onComplete: () -> Unit,
    onCreatePluginProject: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    resolveTutorialByLinkTarget: (String) -> Tutorial?,
    resolveHelpDocumentByLinkTarget: (String) -> HelpDocument?,
    loadTutorialContent: suspend (String) -> Result<String>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val contentUrl = tutorial.contentUrl
    val helpLoadFailedMessage = stringResource(Strings.help_load_failed)
    val relatedSectionTitles = remember(context) {
        context.resources
            .getStringArray(Arrays.tutorial_related_section_titles)
            .map(TutorialRelatedLearningSupport::normalizeSectionTitle)
            .toSet()
    }

    var loadState by remember(contentUrl) {
        mutableStateOf<TutorialArticleLoadState>(TutorialArticleLoadState.Loading)
    }
    var retryRequest by remember(contentUrl) { mutableStateOf(0) }

    LaunchedEffect(contentUrl, helpLoadFailedMessage, retryRequest) {
        loadState = TutorialArticleLoadState.Loading
        loadState = loadTutorialArticle(contentUrl, loadTutorialContent)
    }

    Column(modifier = modifier) {
        when (val currentState = loadState) {
            TutorialArticleLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is TutorialArticleLoadState.Content -> {
                val articlePresentation = TutorialRelatedLearningSupport.buildPresentation(
                    markdown = currentState.markdown,
                    currentTutorialId = tutorial.id,
                    resolveTutorial = resolveTutorialByLinkTarget,
                    resolveHelpDocument = resolveHelpDocumentByLinkTarget,
                    relatedSectionTitles = relatedSectionTitles,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp)
                ) {
                    TutorialPluginQuickActions(
                        tutorialId = tutorial.id,
                        onCreatePluginProject = onCreatePluginProject,
                        onOpenPluginSettings = onOpenPluginSettings,
                    )
                    MarkdownViewer(
                        markdown = articlePresentation.markdown,
                        modifier = Modifier.fillMaxWidth(),
                        onLinkClick = onLinkClick,
                    )
                    TutorialContinueLearningSection(
                        relatedDestinations = articlePresentation.relatedDestinations,
                        onDestinationClick = onLinkClick,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                }

                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(text = stringResource(Strings.tutorial_complete))
                }
            }

            TutorialArticleLoadState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = helpLoadFailedMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { retryRequest += 1 }) {
                        Text(text = stringResource(Strings.action_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialPluginQuickActions(
    tutorialId: String,
    onCreatePluginProject: () -> Unit,
    onOpenPluginSettings: () -> Unit,
) {
    if (tutorialId != PLUGIN_QUICK_START_TUTORIAL_ID) return

    TinaCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Strings.help_quick_actions_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TutorialPluginQuickActionCard(
                    text = stringResource(Strings.help_action_create_plugin_project),
                    onClick = onCreatePluginProject,
                    modifier = Modifier.weight(1f),
                )
                TutorialPluginQuickActionCard(
                    text = stringResource(Strings.help_action_open_plugin_settings),
                    onClick = onOpenPluginSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TutorialPluginQuickActionCard(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TinaCard(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        elevation = 0.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TutorialContinueLearningSection(
    relatedDestinations: List<TutorialRelatedDestination>,
    onDestinationClick: (String) -> Unit,
) {
    if (relatedDestinations.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Strings.tutorial_continue_learning_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Strings.tutorial_continue_learning_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            relatedDestinations.forEach { destination ->
                TutorialContinueLearningCard(
                    destination = destination,
                    onClick = { onDestinationClick(destination.linkTarget) },
                )
            }
        }
    }
}

@Composable
private fun TutorialContinueLearningCard(
    destination: TutorialRelatedDestination,
    onClick: () -> Unit,
) {
    val icon = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> Icons.Default.School
        TutorialRelatedDestinationType.HELP -> Icons.Default.Book
        TutorialRelatedDestinationType.EXTERNAL -> Icons.AutoMirrored.Filled.OpenInNew
    }
    val badgeText = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> {
            stringResource(Strings.tutorial_related_destination_tutorial)
        }

        TutorialRelatedDestinationType.HELP -> {
            stringResource(Strings.tutorial_related_destination_help)
        }

        TutorialRelatedDestinationType.EXTERNAL -> {
            stringResource(Strings.tutorial_related_destination_external)
        }
    }
    val routeText = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> {
            stringResource(Strings.tutorial_related_destination_tutorial_route)
        }

        TutorialRelatedDestinationType.HELP -> {
            stringResource(Strings.tutorial_related_destination_help_route)
        }

        TutorialRelatedDestinationType.EXTERNAL -> {
            stringResource(Strings.tutorial_related_destination_external_route)
        }
    }
    val actionText = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> {
            stringResource(Strings.tutorial_related_destination_tutorial_action)
        }

        TutorialRelatedDestinationType.HELP -> {
            stringResource(Strings.tutorial_related_destination_help_action)
        }

        TutorialRelatedDestinationType.EXTERNAL -> {
            stringResource(Strings.tutorial_related_destination_external_action)
        }
    }
    val title = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> {
            stringResource(destination.tutorial!!.titleRes)
        }

        TutorialRelatedDestinationType.HELP -> {
            destination.helpDocument!!.title
        }

        TutorialRelatedDestinationType.EXTERNAL -> {
            destination.label?.takeIf { it.isNotBlank() } ?: destination.linkTarget
        }
    }
    val summary = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> {
            stringResource(destination.tutorial!!.descriptionRes)
        }

        TutorialRelatedDestinationType.HELP -> {
            destination.helpDocument!!.summary
        }

        TutorialRelatedDestinationType.EXTERNAL -> destination.linkTarget
    }
    val accentColor = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> MaterialTheme.colorScheme.primary
        TutorialRelatedDestinationType.HELP -> MaterialTheme.colorScheme.tertiary
        TutorialRelatedDestinationType.EXTERNAL -> MaterialTheme.colorScheme.secondary
    }
    val badgeContainerColor = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> MaterialTheme.colorScheme.primaryContainer
        TutorialRelatedDestinationType.HELP -> MaterialTheme.colorScheme.tertiaryContainer
        TutorialRelatedDestinationType.EXTERNAL -> MaterialTheme.colorScheme.secondaryContainer
    }
    val badgeContentColor = when (destination.type) {
        TutorialRelatedDestinationType.TUTORIAL -> MaterialTheme.colorScheme.onPrimaryContainer
        TutorialRelatedDestinationType.HELP -> MaterialTheme.colorScheme.onTertiaryContainer
        TutorialRelatedDestinationType.EXTERNAL -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    TinaCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = badgeContainerColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeContentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = badgeContainerColor,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeContentColor,
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 4.dp,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = routeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                )

                if (summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
                                tutorial.type == TutorialType.INTERACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            status == ProgressStatus.COMPLETED -> Icons.Default.CheckCircle
                            tutorial.type == TutorialType.INTERACTIVE -> Icons.Default.TouchApp
                            else -> Icons.Default.Book
                        },
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = when {
                            status == ProgressStatus.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
                            tutorial.type == TutorialType.INTERACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
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
                        if (tutorial.type == TutorialType.INTERACTIVE) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Strings.tutorial_interactive),
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
                        if (tutorial.steps.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.tutorial_chapters, tutorial.steps.size),
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
                        if (tutorial.type == TutorialType.ARTICLE) {
                            Text(
                                text = stringResource(Strings.tutorial_reading),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
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
                        }
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
