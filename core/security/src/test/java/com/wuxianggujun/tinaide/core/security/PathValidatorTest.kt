package com.wuxianggujun.tinaide.core.security

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.wuxianggujun.tinaide.core.exception.TinaIDEException
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.storage.ProjectPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * PathValidator 单元测试（Robolectric）
 *
 * 测试路径遍历防护、白名单校验、normalizePath 逻辑
 */
@RunWith(RobolectricTestRunner::class)
class PathValidatorTest {

    private lateinit var context: Context
    private lateinit var validator: PathValidator

    @Before
    fun setUp() {
        val baseContext = RuntimeEnvironment.getApplication()
        context = mockk(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns baseContext.filesDir
            every { cacheDir } returns baseContext.cacheDir
            every { getExternalFilesDir(null) } returns baseContext.getExternalFilesDir(null)
            every { getString(Strings.path_error_forbidden, *anyVararg()) } returns "forbidden"
            every { getString(Strings.path_error_forbidden_suggestion) } returns "forbidden suggestion"
            every { getString(Strings.path_error_not_allowed_guest, *anyVararg()) } returns "guest not allowed"
            every { getString(Strings.path_error_allowed_prefixes, *anyVararg()) } returns "allowed prefixes"
            every { getString(Strings.path_error_cannot_resolve, *anyVararg()) } returns "cannot resolve"
            every { getString(Strings.path_error_format_suggestion) } returns "format suggestion"
            every { getString(Strings.path_error_not_allowed_host, *anyVararg()) } returns "host not allowed"
            every { getString(Strings.path_error_host_suggestion) } returns "host suggestion"
        }

        // Mock ProjectPaths.getWorkspaceRoot 返回一个可控路径
        mockkObject(ProjectPaths)
        every { ProjectPaths.getWorkspaceRoot(any()) } returns File(context.filesDir, "workspace")

        validator = PathValidator(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== Guest 路径白名单测试 ====================

    @Test
    fun `guest path under workspace is allowed`() {
        assertThat(validator.isGuestPathAllowed("/workspace/main.cpp")).isTrue()
    }

    @Test
    fun `guest path under projects is allowed`() {
        assertThat(validator.isGuestPathAllowed("/projects/demo/main.c")).isTrue()
    }

    @Test
    fun `guest path with workspace prefix sibling is rejected`() {
        assertThat(validator.isGuestPathAllowed("/workspace2/main.cpp")).isFalse()
        assertThat(validator.isGuestPathAllowed("/workspace-backup/main.cpp")).isFalse()
    }

    @Test
    fun `guest path with projects prefix sibling is rejected`() {
        assertThat(validator.isGuestPathAllowed("/projects2/main.c")).isFalse()
        assertThat(validator.isGuestPathAllowed("/projects-backup/main.c")).isFalse()
    }

    @Test
    fun `guest path under tmp is allowed`() {
        assertThat(validator.isGuestPathAllowed("/tmp/build.log")).isTrue()
    }

    @Test
    fun `guest path under home is allowed`() {
        assertThat(validator.isGuestPathAllowed("/home/user/.bashrc")).isTrue()
    }

    @Test
    fun `guest path outside whitelist is rejected`() {
        assertThat(validator.isGuestPathAllowed("/usr/bin/gcc")).isFalse()
    }

    @Test
    fun `guest path to root is rejected`() {
        assertThat(validator.isGuestPathAllowed("/")).isFalse()
    }

    // ==================== Guest 禁止路径测试 ====================

    @Test
    fun `guest path to etc passwd is forbidden`() {
        assertThat(validator.isGuestPathAllowed("/etc/passwd")).isFalse()
    }

    @Test
    fun `guest path to etc shadow is forbidden`() {
        assertThat(validator.isGuestPathAllowed("/etc/shadow")).isFalse()
    }

    @Test
    fun `guest path to etc sudoers is forbidden`() {
        assertThat(validator.isGuestPathAllowed("/etc/sudoers")).isFalse()
    }

    // ==================== 路径遍历攻击防护 ====================

    @Test
    fun `path traversal from workspace to etc passwd is blocked`() {
        assertThat(validator.isGuestPathAllowed("/workspace/../etc/passwd")).isFalse()
    }

    @Test
    fun `path traversal with multiple dotdot is blocked`() {
        assertThat(validator.isGuestPathAllowed("/workspace/../../etc/shadow")).isFalse()
    }

    @Test
    fun `path traversal from home to forbidden path is blocked`() {
        assertThat(validator.isGuestPathAllowed("/home/user/../../etc/passwd")).isFalse()
    }

    @Test
    fun `path with dot segments is normalized correctly`() {
        // /workspace/./subdir/./main.cpp → /workspace/subdir/main.cpp (仍在白名单内)
        assertThat(validator.isGuestPathAllowed("/workspace/./subdir/./main.cpp")).isTrue()
    }

    @Test
    fun `path traversal escaping all whitelisted dirs is blocked`() {
        assertThat(validator.isGuestPathAllowed("/workspace/../../../bin/sh")).isFalse()
    }

    // ==================== validateGuestPath 异常测试 ====================

    @Test(expected = TinaIDEException.PathValidationException::class)
    fun `validateGuestPath throws for forbidden path`() {
        validator.validateGuestPath("/etc/passwd")
    }

    @Test(expected = TinaIDEException.PathValidationException::class)
    fun `validateGuestPath throws for path outside whitelist`() {
        validator.validateGuestPath("/usr/local/bin/gcc")
    }

    @Test
    fun `validateGuestPath does not throw for valid path`() {
        // 不应抛出异常
        validator.validateGuestPath("/workspace/project/main.c")
    }

    @Test
    fun `validateGuestPath exception contains the offending path`() {
        try {
            validator.validateGuestPath("/etc/passwd")
            throw AssertionError("Expected PathValidationException")
        } catch (e: TinaIDEException.PathValidationException) {
            assertThat(e.path).isEqualTo("/etc/passwd")
        }
    }

    // ==================== Host 路径测试 ====================

    @Test
    fun `host path under app filesDir is allowed`() {
        val path = File(context.filesDir, "workspace/project/main.c").absolutePath
        assertThat(validator.isHostPathAllowed(path)).isTrue()
    }

    @Test
    fun `host path with app filesDir prefix sibling is rejected`() {
        val sibling = File(context.filesDir.parentFile, "${context.filesDir.name}-sibling")
        sibling.mkdirs()

        assertThat(validator.isHostPathAllowed(File(sibling, "main.c").absolutePath)).isFalse()
    }

    @Test
    fun `host path under cacheDir is allowed`() {
        val path = File(context.cacheDir, "temp.log").absolutePath
        assertThat(validator.isHostPathAllowed(path)).isTrue()
    }

    @Test
    fun `host path outside allowed dirs is rejected`() {
        assertThat(validator.isHostPathAllowed("/system/bin/sh")).isFalse()
    }

    @Test
    fun `host path under rootfs is allowed`() {
        val rootfsPath = File(context.filesDir, "rootfs/usr/bin/gcc").absolutePath
        // 确保目录存在以便 canonicalPath 能解析
        File(context.filesDir, "rootfs/usr/bin").mkdirs()
        File(context.filesDir, "rootfs/usr/bin/gcc").createNewFile()
        assertThat(validator.isHostPathAllowed(rootfsPath)).isTrue()
    }

    // ==================== 辅助方法测试 ====================

    @Test
    fun `getAllowedGuestPrefixes returns expected prefixes`() {
        val prefixes = validator.getAllowedGuestPrefixes()
        assertThat(prefixes).containsExactly("/workspace", "/projects", "/tmp", "/home")
    }

    @Test
    fun `getAllowedHostPrefixes is not empty`() {
        val prefixes = validator.getAllowedHostPrefixes()
        assertThat(prefixes).isNotEmpty()
    }
}
