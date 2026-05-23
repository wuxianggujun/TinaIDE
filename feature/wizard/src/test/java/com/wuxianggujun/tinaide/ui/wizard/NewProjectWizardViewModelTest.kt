package com.wuxianggujun.tinaide.ui.wizard

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.project.ProjectBuildSystem
import com.wuxianggujun.tinaide.project.ProjectLanguage
import com.wuxianggujun.tinaide.project.ProjectMetadataStore
import com.wuxianggujun.tinaide.project.ProjectTemplateOption
import com.wuxianggujun.tinaide.project.ProjectTemplateSpec
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    application = Application::class
)
@OptIn(ExperimentalCoroutinesApi::class)
class NewProjectWizardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun syncTemplateSelection_shouldKeepExistingSelectionWhenAvailable() {
        val viewModel = NewProjectWizardViewModel()
        val selectedTemplate = template(
            id = "template:cpp",
            language = ProjectLanguage.CPP,
        )
        val otherTemplate = template(
            id = "template:plugin",
            buildSystem = ProjectBuildSystem.PLUGIN,
            language = ProjectLanguage.MIXED,
        )

        viewModel.setTemplate(selectedTemplate)
        viewModel.syncTemplateSelection(listOf(otherTemplate, selectedTemplate))

        val state = viewModel.state.value
        assertThat(state.selectedTemplateId).isEqualTo(selectedTemplate.id)
        assertThat(state.showsCppStandard).isTrue()
        assertThat(state.isNdkTemplate).isFalse()
    }

    @Test
    fun syncTemplateSelection_shouldFallbackAndSyncDerivedStateWhenSelectionMissing() {
        val viewModel = NewProjectWizardViewModel()
        val staleTemplate = template(
            id = "template:stale",
            language = ProjectLanguage.CPP,
        )
        val fallbackTemplate = template(
            id = "template:plugin",
            buildSystem = ProjectBuildSystem.PLUGIN,
            language = ProjectLanguage.MIXED,
            isNdkTemplate = true,
        )

        viewModel.setTemplate(staleTemplate)
        viewModel.syncTemplateSelection(listOf(fallbackTemplate))

        val state = viewModel.state.value
        assertThat(state.selectedTemplateId).isEqualTo(fallbackTemplate.id)
        assertThat(state.showsCppStandard).isFalse()
        assertThat(state.isNdkTemplate).isTrue()
    }

    @Test
    fun syncTemplateSelection_shouldLeaveStateUnchangedWhenNoTemplatesAvailable() {
        val viewModel = NewProjectWizardViewModel()
        val selectedTemplate = template(
            id = "template:cpp",
            language = ProjectLanguage.CPP,
        )

        viewModel.setTemplate(selectedTemplate)
        viewModel.syncTemplateSelection(emptyList())

        val state = viewModel.state.value
        assertThat(state.selectedTemplateId).isEqualTo(selectedTemplate.id)
        assertThat(state.showsCppStandard).isTrue()
        assertThat(state.isNdkTemplate).isFalse()
    }

    @Test
    fun createProject_shouldInstallSelectedUserZipTemplate() = runTest {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val projectRoot = tempDir("wizard-user-template-root")
        val templateZip = projectRoot.resolve("custom_template.zip")
        ZipOutputStream(templateZip.outputStream().buffered()).use { zip ->
            zip.writeEntry("README.md", "# {{PROJECT_NAME}}\n")
            zip.writeEntry("src/{{PROJECT_NAME}}.cpp", "int main() { return 0; }\n")
        }
        val userTemplate = template(
            id = "${UserProjectTemplates.TEMPLATE_ID_PREFIX}custom-template",
            buildSystem = ProjectBuildSystem.CMAKE,
            language = ProjectLanguage.CPP,
            zipFile = templateZip,
        )
        val viewModel = NewProjectWizardViewModel(ioDispatcher = mainDispatcherRule.dispatcher)
        val createdProjects = mutableListOf<File>()
        val errors = mutableListOf<String>()

        viewModel.setTemplate(userTemplate)
        viewModel.setProjectName("DemoApp")
        viewModel.createProject(
            context = context,
            projectPath = projectRoot.absolutePath,
            availableTemplates = listOf(userTemplate),
            onSuccess = { createdProjects += it },
            onError = { errors += it }
        )
        advanceUntilIdle()

        assertThat(errors).isEmpty()
        assertThat(createdProjects).containsExactly(projectRoot.resolve("DemoApp").canonicalFile)
        val projectDir = createdProjects.single()
        assertThat(projectDir.resolve("README.md").readText(Charsets.UTF_8))
            .isEqualTo("# DemoApp\n")
        assertThat(projectDir.resolve("src/DemoApp.cpp").readText(Charsets.UTF_8))
            .contains("int main()")
        val metadata = ProjectMetadataStore.read(projectDir)
        assertThat(metadata?.buildSystem).isEqualTo(ProjectBuildSystem.CMAKE)
        assertThat(metadata?.primaryLanguage).isEqualTo(ProjectLanguage.CPP.name)
    }

    private fun template(
        id: String,
        buildSystem: ProjectBuildSystem = ProjectBuildSystem.CMAKE,
        language: ProjectLanguage,
        isNdkTemplate: Boolean = false,
        zipFile: File = File("$id.zip"),
    ): ProjectTemplateOption {
        return ProjectTemplateOption(
            id = id,
            displayName = id,
            description = id,
            spec = ProjectTemplateSpec.Zip(
                id = id,
                zipFile = zipFile,
                buildSystem = buildSystem,
                primaryLanguage = language,
                isNdkTemplate = isNdkTemplate,
            ),
        )
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
