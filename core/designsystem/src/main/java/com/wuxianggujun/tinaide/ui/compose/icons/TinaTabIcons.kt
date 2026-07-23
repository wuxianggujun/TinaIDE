package com.wuxianggujun.tinaide.ui.compose.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 统一管理抽屉栏与设置页的核心 Tab 图标。
 *
 * 这里使用代码 ImageVector，避免在 Release 环境继续走 XML Vector 解析链。
 */
object TinaTabIcons {
    val Files: ImageVector
        get() = Icons.Default.Folder

    val Symbols: ImageVector
        get() = Icons.Default.Code

    val Git: ImageVector
        get() = Icons.Default.AccountTree

    val RikkaHub: ImageVector
        get() = Icons.Default.SmartToy
}
