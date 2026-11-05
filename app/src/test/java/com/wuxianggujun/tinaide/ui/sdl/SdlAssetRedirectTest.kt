package com.wuxianggujun.tinaide.ui.sdl

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * [SdlAssetRedirect] 的失败路径必须"可见且安全"：不能吞异常、不能假装安装成功。
 *
 * 这里只覆盖不需要加载 native 库、也不需要文件系统访问的分支。
 * 真正的路径重定向逻辑在 native 侧，见 `app/src/main/cpp/sdl_asset_redirect`。
 */
class SdlAssetRedirectTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `install rejects blank project root`() {
        assertThat(SdlAssetRedirect.install(context, "")).isFalse()
    }

    @Test
    fun `install rejects whitespace-only project root`() {
        assertThat(SdlAssetRedirect.install(context, "   \t ")).isFalse()
    }
}
