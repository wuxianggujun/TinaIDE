package com.wuxianggujun.tinaide.plugin

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
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
class PluginProjectTemplateBuildSystemTest {

    private lateinit var context: Application
    private lateinit var pluginDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        pluginDir = Files.createTempDirectory("plugin-template-build-system").toFile()
    }

    @After
    fun tearDown() {
        pluginDir.deleteRecursively()
    }

    @Test
    fun `project template should accept plugin build system`() {
        File(pluginDir, "templates").mkdirs()
        ZipOutputStream(File(pluginDir, "templates/demo-plugin.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{\"id\":\"demo.plugin\"}".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        File(pluginDir, PluginManager.MANIFEST_FILE_NAME).writeText(
            """
            {
              "id": "demo.plugin.starters",
              "name": "Demo Plugin Starters",
              "version": "1.0.0",
              "type": "config",
              "contributions": {
                "projectTemplates": [
                  {
                    "id": "demo-plugin",
                    "name": "Demo Plugin",
                    "description": "Demo plugin project template.",
                    "templatePath": "templates/demo-plugin.zip",
                    "buildSystem": "plugin",
                    "primaryLanguage": "MIXED"
                  }
                ]
              }
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val report = PluginDoctor.inspectDirectory(context, pluginDir)

        assertThat(report.entries).isEmpty()
    }
}
