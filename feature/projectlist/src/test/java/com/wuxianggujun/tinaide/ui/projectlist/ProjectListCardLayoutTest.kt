package com.wuxianggujun.tinaide.ui.projectlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectListItem
import com.wuxianggujun.tinaide.project.ProjectSourceLocation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectListCardLayoutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun completeProjectNameHasItsOwnWidthAndTagsWrapBelowItAtLargeFontScales() {
        val directory = temporary.newFolder("project").apply { resolve(".git").mkdir() }
        val project = ProjectListItem(
            dir = directory,
            displayName = "tina_course_07_vcpkg_long_name_to_distinguish_each_practice_project",
            buildSystem = ProjectBuildSystem.CMAKE,
            sourceLocation = ProjectSourceLocation.PRIVATE,
        )
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                MaterialTheme {
                    Column(Modifier.width(width.value).verticalScroll(rememberScrollState())) {
                        ProjectListCard(project, onClick = {}, onAction = {})
                    }
                }
            }
        }
        for ((screenWidth, scale) in listOf(280 to 1f, 320 to 1.5f, 280 to 2f, 420 to 1f)) {
            compose.runOnIdle { width.value = screenWidth.dp; fontScale.value = scale }
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
        }
    }
}
