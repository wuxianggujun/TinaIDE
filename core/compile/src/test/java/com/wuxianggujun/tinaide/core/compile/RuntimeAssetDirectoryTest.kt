package com.wuxianggujun.tinaide.core.compile

import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.i18n.AppStrings
import org.robolectric.RuntimeEnvironment
import java.io.File
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RuntimeAssetDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initializeStrings() { AppStrings.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun defaultPrefersAssetsAndFallsBackToProjectRoot() {
        val root = temporary.newFolder("project")
        val context = BuildVariables.BuildContext(root, "project")
        assertThat(resolveRuntimeAssetDirectory(root, "", context)).isEqualTo(root.canonicalFile)
        val assets = File(root, "assets").apply { mkdir() }
        assertThat(resolveRuntimeAssetDirectory(root, "", context)).isEqualTo(assets.canonicalFile)
    }

    @Test fun customRelativeAbsoluteAndVariablesResolveToTheSameUnicodeDirectory() {
        val root = temporary.newFolder("project")
        val assets = File(root, "资源😀").apply { mkdir() }
        val context = BuildVariables.BuildContext(root, "project")
        for (input in listOf("资源😀", assets.absolutePath, "\$ProjectDir\$/资源😀")) {
            assertThat(resolveRuntimeAssetDirectory(root, input, context)).isEqualTo(assets.canonicalFile)
        }
    }

    @Test fun missingAndEscapingDirectoriesAreRejected() {
        val root = temporary.newFolder("project")
        val outside = temporary.newFolder("outside")
        val context = BuildVariables.BuildContext(root, "project")
        for (input in listOf("missing", "../outside", outside.absolutePath)) {
            assertThrows(IllegalArgumentException::class.java) { resolveRuntimeAssetDirectory(root, input, context) }
        }
    }

    @Test fun exportUsesCustomAssetsWithoutImplicitlyPackagingTheProject() {
        val root = temporary.newFolder("project")
        val context = BuildVariables.BuildContext(root, "project")
        assertThat(resolveExportAssetDirectories(root, "", context)).isEmpty()
        val custom = File(root, "resources").apply { mkdir() }
        assertThat(resolveExportAssetDirectories(root, "resources", context)).containsExactly(custom.canonicalFile)
        val assets = File(root, "assets").apply { mkdir() }
        assertThat(resolveExportAssetDirectories(root, "", context)).containsExactly(assets.canonicalFile)
    }
}
