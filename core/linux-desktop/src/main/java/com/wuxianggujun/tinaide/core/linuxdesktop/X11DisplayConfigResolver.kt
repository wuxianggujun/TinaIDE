package com.wuxianggujun.tinaide.core.linuxdesktop

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import timber.log.Timber

/**
 * 按真机屏幕算出 X server 的**开机分辨率**。
 *
 * 为什么不能一直用 [X11DisplayConfig.default] 的 1920x1080：guest 的 XFCE 现在先于桌面窗口
 * 启动（会话跑在 `:x11`，窗口可开可关），面板和桌面图标会按开机时拿到的 root window 尺寸
 * 布一次版。开机值和真机差太多时，第一次打开窗口会看到面板宽度不对、图标挤在角落——
 * 之后 `LorieView.sendWindowChange()` 会把真实几何推给 X server，但那已经是"先错后纠"了。
 *
 * 取的是**整块屏幕**而不是当前应用窗口：桌面窗口是全屏 Activity，分屏下的 IDE 窗口尺寸
 * 不代表桌面将来会拿到多大。
 */
fun resolveX11DisplayConfig(context: Context): X11DisplayConfig {
    val metrics = context.resources.displayMetrics
    val bounds = screenBounds(context, metrics)
    val config = x11DisplayConfigOf(
        screenWidth = bounds.first,
        screenHeight = bounds.second,
        densityDpi = metrics.densityDpi,
    )
    Timber.tag(TAG).i("Resolved X11 display config from device screen: %s", config)
    return config
}

/**
 * 纯计算部分，独立出来是为了能不依赖 Robolectric 直接测。
 *
 * 横竖方向会被归一化成"长边当宽"：桌面按横屏布版，竖屏手机上直接用竖直尺寸
 * 会让 XFCE 面板挤成一条。
 */
internal fun x11DisplayConfigOf(
    screenWidth: Int,
    screenHeight: Int,
    densityDpi: Int,
): X11DisplayConfig {
    val fallback = X11DisplayConfig.default()
    val usable = screenWidth > 0 && screenHeight > 0
    val longEdge = if (usable) maxOf(screenWidth, screenHeight) else fallback.width
    val shortEdge = if (usable) minOf(screenWidth, screenHeight) else fallback.height
    return X11DisplayConfig(
        width = longEdge.coerceIn(MIN_DIMENSION, MAX_DIMENSION),
        height = shortEdge.coerceIn(MIN_DIMENSION, MAX_DIMENSION),
        dpi = if (densityDpi > 0) densityDpi.coerceIn(MIN_DPI, MAX_DPI) else fallback.dpi,
    )
}

/** @return `width to height`，未旋转的整屏像素尺寸。 */
private fun screenBounds(context: Context, fallback: DisplayMetrics): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
    }
    // API 28/29 没有 maximumWindowMetrics；displayMetrics 在这两个版本上就是整屏尺寸。
    if (fallback.widthPixels > 0 && fallback.heightPixels > 0) {
        return fallback.widthPixels to fallback.heightPixels
    }
    Timber.tag(TAG).w("Device screen metrics unavailable; falling back to the default geometry")
    val default = X11DisplayConfig.default()
    return default.width to default.height
}

private const val TAG = "X11DisplayConfig"

// X server 对退化尺寸没有保护，1x1 的 root window 会让 XFCE 起来就崩。
private const val MIN_DIMENSION = 640
private const val MAX_DIMENSION = 7680
private const val MIN_DPI = 72
private const val MAX_DPI = 640
private const val DEFAULT_DPI = 160
