package com.wuxianggujun.tinaide.ui.projectlist

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.ProjectSourceLocation
import com.wuxianggujun.tinaide.ui.theme.TinaIDETheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectListCardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun completeProjectNameHasItsOwnWidthAndTagsWrapBelowItAtLargeFontScales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = temporary.newFolder("project").apply { resolve(".git").mkdir() }
        val project = ProjectListItem(
            dir = directory,
            displayName = "tina_course_07_vcpkg_long_name_to_distinguish_each_practice_project",
            buildSystem = ProjectBuildSystem.CMAKE,
            sourceLocation = ProjectSourceLocation.PRIVATE,
        )
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        val language = mutableStateOf("zh")
        compose.setContent {
            val density = LocalDensity.current
            val localized = remember(language.value) {
                context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(Locale(language.value))
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme {
                    Column(Modifier.width(width.value).verticalScroll(rememberScrollState())) {
                        ProjectListCard(project, onClick = {}, onAction = {})
                    }
                }
            }
        }
        val variants = listOf("zh", "en").flatMap { locale ->
            listOf(280 to 1f, 320 to 1.5f, 280 to 2f, 420 to 1f).map { (width, scale) -> Triple(locale, width, scale) }
        }
        for ((locale, screenWidth, scale) in variants) {
            compose.runOnIdle { language.value = locale; width.value = screenWidth.dp; fontScale.value = scale }
            val title = compose.onNodeWithTag("project-card-title", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertThat(layout.layoutInput.text.text).isEqualTo(project.displayName)
            assertThat(layout.hasVisualOverflow).isFalse()
            assertThat((0 until layout.lineCount).any { layout.isLineEllipsized(it) }).isFalse()
            val titleNode = title.fetchSemanticsNode()
            val tagsNode = compose.onNodeWithTag("project-card-tags", useUnmergedTree = true).fetchSemanticsNode()
            val moreNode = compose.onNodeWithTag("project-card-more", useUnmergedTree = true).fetchSemanticsNode()
            assertThat(tagsNode.positionInRoot.y).isAtLeast(titleNode.positionInRoot.y + titleNode.size.height)
            assertThat(titleNode.positionInRoot.x + titleNode.size.width).isAtMost(moreNode.positionInRoot.x)
            assertHeadingAlignment()
        }
    }

    @Test
    fun oneAndTwoLineTitlesKeepTagsImmediatelyBelowTheNameBesideTheIcon() {
        val project = ProjectListItem(
            dir = temporary.newFolder("short-project"),
            displayName = "test",
            buildSystem = ProjectBuildSystem.CMAKE,
            sourceLocation = ProjectSourceLocation.PRIVATE,
        )
        val name = mutableStateOf(project.displayName)
        compose.setContent {
            TinaIDETheme {
                Column(Modifier.width(280.dp)) {
                    ProjectListCard(project.copy(displayName = name.value), onClick = {}, onAction = {})
                }
            }
        }
        for ((title, expectedLines) in listOf("test" to 1, "tina_course_07_vcpkg" to 2)) {
            compose.runOnIdle { name.value = title }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("project-card-title", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertThat(layouts.single().lineCount).isEqualTo(expectedLines)
            assertThat(layouts.single().hasVisualOverflow).isFalse()
            assertHeadingAlignment()
            saveCardSnapshot("project-card-${expectedLines}-line")
        }
    }

    @Test
    fun recentRibbonIsExplicitNonInteractiveAndDoesNotReserveAnExtraRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val project = ProjectListItem(
            dir = temporary.newFolder("recent-project").apply { resolve(".git").mkdir() },
            displayName = "test",
            buildSystem = ProjectBuildSystem.CMAKE,
            sourceLocation = ProjectSourceLocation.PRIVATE,
        )
        val recent = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        var openCount = 0
        var action: ProjectAction? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                TinaIDETheme {
                    Column(Modifier.width(320.dp)) {
                        ProjectListCard(project, onClick = { openCount++ }, onAction = { action = it }, isRecent = recent.value)
                    }
                }
            }
        }
        compose.onNodeWithTag("project-card-recent-ribbon", useUnmergedTree = true).assertDoesNotExist()
        val originalHeight = compose.onNodeWithTag("project-list-card").fetchSemanticsNode().size.height
        compose.runOnIdle { recent.value = true }
        val card = compose.onNodeWithTag("project-list-card").fetchSemanticsNode()
        val ribbon = compose.onNodeWithTag("project-card-recent-ribbon", useUnmergedTree = true)
        ribbon.assertContentDescriptionEquals(context.getString(Strings.section_recent_projects))
        val ribbonNode = ribbon.fetchSemanticsNode()
        val title = compose.onNodeWithTag("project-card-title", useUnmergedTree = true).fetchSemanticsNode()
        assertThat(card.size.height).isEqualTo(originalHeight)
        assertThat(ribbonNode.positionInRoot).isEqualTo(card.positionInRoot)
        assertThat(ribbonNode.positionInRoot.x + ribbonNode.size.width).isAtMost(title.positionInRoot.x)
        assertThat(ribbonNode.config.contains(SemanticsActions.OnClick)).isFalse()
        assertHeadingAlignment()
        compose.onNodeWithText("_BRANCH").assertDoesNotExist()
        compose.onNodeWithText("main").assertDoesNotExist()
        saveCardSnapshot("project-card-recent")
        for (scale in listOf(1.5f, 2f)) {
            compose.runOnIdle { fontScale.value = scale }
            assertHeadingAlignment()
            val largeFontRibbon = ribbon.fetchSemanticsNode()
            val largeFontTitle = compose.onNodeWithTag("project-card-title", useUnmergedTree = true).fetchSemanticsNode()
            assertThat(largeFontRibbon.positionInRoot.x + largeFontRibbon.size.width).isAtMost(largeFontTitle.positionInRoot.x)
            ribbon.assertContentDescriptionEquals(context.getString(Strings.section_recent_projects))
        }
        saveCardSnapshot("project-card-recent-large-font")
        compose.runOnIdle { fontScale.value = 1f }

        compose.onNodeWithTag("project-card-more", useUnmergedTree = true).performClick()
        compose.onNodeWithText(context.getString(Strings.action_open_project)).performClick()
        compose.runOnIdle {
            assertThat(action).isEqualTo(ProjectAction.OPEN)
            assertThat(openCount).isEqualTo(0)
        }
        compose.onNodeWithTag("project-list-card").performClick()
        compose.runOnIdle { assertThat(openCount).isEqualTo(1) }
    }

    private fun saveCardSnapshot(name: String) {
        val bounds = compose.onNodeWithTag("project-list-card").fetchSemanticsNode().boundsInRoot
        // Robolectric has no real window/vsync for PixelCopy; draw the same view tree to a CPU canvas.
        val bitmap = compose.runOnIdle {
            val content = compose.activity.findViewById<View>(android.R.id.content)
            val window = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(window))
                Bitmap.createBitmap(window, bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.width.roundToInt(), bounds.height.roundToInt())
            } finally {
                window.recycle()
            }
        }
        val output = File("build/verification/card-layout-20261124", "$name.png")
        val directory = requireNotNull(output.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertHeadingAlignment() {
        val title = compose.onNodeWithTag("project-card-title", useUnmergedTree = true).fetchSemanticsNode()
        val tags = compose.onNodeWithTag("project-card-tags", useUnmergedTree = true).fetchSemanticsNode()
        val heading = compose.onNodeWithTag("project-card-heading", useUnmergedTree = true).fetchSemanticsNode()
        val icon = compose.onNodeWithTag("project-card-icon", useUnmergedTree = true).fetchSemanticsNode()
        val more = compose.onNodeWithTag("project-card-more", useUnmergedTree = true).fetchSemanticsNode()
        val expectedGap = with(compose.density) { 4.dp.toPx() }
        assertThat(tags.positionInRoot.x).isWithin(1f).of(title.positionInRoot.x)
        assertThat(tags.positionInRoot.y - title.positionInRoot.y - title.size.height)
            .isWithin(1f).of(expectedGap)
        assertThat(heading.positionInRoot.x).isAtLeast(icon.positionInRoot.x + icon.size.width)
        assertThat(heading.positionInRoot.y + heading.size.height / 2f)
            .isWithin(1f).of(icon.positionInRoot.y + icon.size.height / 2f)
        assertThat(title.positionInRoot.x + title.size.width).isAtMost(more.positionInRoot.x)
        assertThat(more.size.width).isAtLeast(with(compose.density) { 48.dp.roundToPx() })
    }
}
