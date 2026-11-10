package com.wuxianggujun.tinaide.core.apkbuilder

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ProjectAssetsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test
    fun `collect resolves equivalent paths before deduplicating`() {
        val directory = tempDir()
        File(directory, "child").mkdir()
        writeFile(directory, "asset.txt")
        val plan = ProjectAssets.collect(listOf(directory, File(directory, "child/..")), false)
        assertThat(plan.entries.keys).containsExactly("assets/asset.txt")
        assertThat(plan.conflicts).isEmpty()
    }

    private fun tempDir(): File = temporary.newFolder()

    private fun writeFile(root: File, relative: String, content: String = "x"): File =
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    @Test
    fun `collect maps project assets directory to apk assets root`() {
        val assetsDir = tempDir()
        val font = writeFile(assetsDir, "ZiTiQuanWeiJunHei-W2-2.ttf")
        writeFile(assetsDir, "shaders/basic.glsl")

        val plan = ProjectAssets.collect(
            directories = listOf(assetsDir),
            mirrorUnderAssetsNamespace = false,
        )

        assertThat(plan.entries.keys).containsExactly(
            "assets/ZiTiQuanWeiJunHei-W2-2.ttf",
            "assets/shaders/basic.glsl",
        )
        assertThat(plan.entries["assets/ZiTiQuanWeiJunHei-W2-2.ttf"]).isEqualTo(font)
        assertThat(plan.conflicts).isEmpty()
    }

    @Test
    fun `collect mirrors entries under assets namespace so prefixed paths keep working`() {
        val assetsDir = tempDir()
        writeFile(assetsDir, "font.ttf")

        val plan = ProjectAssets.collect(
            directories = listOf(assetsDir),
            mirrorUnderAssetsNamespace = true,
        )

        // 裸名（APK assets 根）与 assets/ 前缀（项目目录写法）两种写法都要能命中。
        assertThat(plan.entries.keys).containsExactly(
            "assets/font.ttf",
            "assets/assets/font.ttf",
        )
    }

    @Test
    fun `collect skips directories that do not exist`() {
        val missing = File(tempDir(), "assets")
        assertThat(missing.exists()).isFalse()

        val plan = ProjectAssets.collect(directories = listOf(missing))

        assertThat(plan.isEmpty).isTrue()
        assertThat(plan.conflicts).isEmpty()
    }

    @Test
    fun `collect never overwrites entries already taken by the template`() {
        val assetsDir = tempDir()
        writeFile(assetsDir, "dexopt/baseline.prof", "project")
        writeFile(assetsDir, "font.ttf")

        val plan = ProjectAssets.collect(
            directories = listOf(assetsDir),
            mirrorUnderAssetsNamespace = false,
            isEntryTaken = { it == "assets/dexopt/baseline.prof" },
        )

        assertThat(plan.entries.keys).containsExactly("assets/font.ttf")
        assertThat(plan.conflicts).hasSize(1)
        assertThat(plan.conflicts.single()).contains("assets/dexopt/baseline.prof")
    }

    @Test
    fun `collect keeps the first directory when two directories provide the same entry`() {
        val first = tempDir()
        val second = tempDir()
        val firstFont = writeFile(first, "font.ttf", "first")
        writeFile(second, "font.ttf", "second")

        val plan = ProjectAssets.collect(
            directories = listOf(first, second),
            mirrorUnderAssetsNamespace = false,
        )

        assertThat(plan.entries).hasSize(1)
        assertThat(plan.entries["assets/font.ttf"]).isEqualTo(firstFont)
        assertThat(plan.conflicts).hasSize(1)
    }

    @Test
    fun `collect ignores duplicate directory references`() {
        val assetsDir = tempDir()
        writeFile(assetsDir, "font.ttf")

        val plan = ProjectAssets.collect(
            directories = listOf(assetsDir, assetsDir),
            mirrorUnderAssetsNamespace = false,
        )

        assertThat(plan.entries).hasSize(1)
        assertThat(plan.conflicts).isEmpty()
    }

    @Test
    fun `collect produces deterministic ordering for nested directories`() {
        val assetsDir = tempDir()
        writeFile(assetsDir, "b/b2.txt")
        writeFile(assetsDir, "a/a1.txt")
        writeFile(assetsDir, "z.txt")

        val plan = ProjectAssets.collect(
            directories = listOf(assetsDir),
            mirrorUnderAssetsNamespace = false,
        )

        assertThat(plan.entries.keys).containsExactly(
            "assets/a/a1.txt",
            "assets/b/b2.txt",
            "assets/z.txt",
        ).inOrder()
    }
}
