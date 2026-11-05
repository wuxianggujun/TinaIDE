package com.wuxianggujun.tinaide.core.apkbuilder

import java.io.File

/**
 * 把项目的资源目录映射成 APK 的 `assets/` 条目。
 *
 * 背景：TinaIDE 的导出链路不经过 AGP（`aapt`/`PackageApplication`），
 * 因此 CMake 的 `ANDROID_ASSETS_DIRECTORIES` 属性没有任何阶段会去消费。
 * 本对象负责把「项目里能看到的资源目录」补齐成 APK 里的 assets 条目。
 *
 * 约定（与 `docs/` 和 App 内帮助保持一致）：
 * - 目录内容递归映射为 `assets/<相对路径>`，即 Android/CMake 的常规语义：
 *   项目 `<root>/assets/font.ttf` → APK `assets/font.ttf`，
 *   程序里用 `SDL_IOFromFile("font.ttf")` / `AAssetManager_open("font.ttf")` 即可读到。
 * - 为了兼容「按项目目录名写路径」的既有代码（`"assets/font.ttf"`），
 *   默认额外生成一份 `<mirrorEntryRoot>/<相对路径>`（即 `assets/assets/font.ttf`）。
 *   这样同一份资源在「IDE 直接运行」和「导出 APK」两种模式下，两种写法都能命中。
 *   镜像会增加 APK 体积（资源通常只有字体/图片，量级可接受），可用
 *   [ApkBuildConfig.mirrorProjectAssetsUnderAssetsNamespace] 关闭。
 *
 * 该对象只做纯映射，不做 I/O 之外的任何事情：方便单测，也方便调用方决定
 * 「哪些条目已经被模板占用」。
 */
internal object ProjectAssets {

    /** 一级映射：项目资源目录 → APK `assets/` 根。 */
    const val PRIMARY_ENTRY_ROOT = "assets"

    /** 兼容映射：项目资源目录 → APK `assets/assets/`（保持 `assets/` 前缀写法可用）。 */
    const val MIRROR_ENTRY_ROOT = "assets/assets"

    /**
     * 资源打包计划。
     *
     * @param entries 目标条目名 → 源文件，保持稳定的遍历顺序（目录顺序 + 文件名排序）。
     * @param conflicts 因为目标条目已被占用（模板条目 / 更早的目录）而被丢弃的来源说明。
     */
    data class Plan(
        val entries: Map<String, File>,
        val conflicts: List<String>,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /**
     * @param directories 候选资源目录，按优先级排列；不存在的目录会被跳过，
     *   重复目录（同一 canonicalPath）只取第一个。
     * @param mirrorUnderAssetsNamespace 是否额外生成 `assets/assets/<相对路径>` 兼容条目。
     * @param isEntryTaken 判断某个条目名是否已被模板占用（模板优先，不覆盖）。
     */
    fun collect(
        directories: List<File>,
        mirrorUnderAssetsNamespace: Boolean = true,
        isEntryTaken: (String) -> Boolean = { false },
    ): Plan {
        val entries = linkedMapOf<String, File>()
        val conflicts = mutableListOf<String>()
        val seenRoots = mutableSetOf<String>()

        directories.asSequence()
            .filter { it.isDirectory }
            .filter { seenRoots.add(it.absolutePath) }
            .forEach { directory ->
                val files = directory.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
                files.forEach { file ->
                    val relative = file.relativeTo(directory).invariantSeparatorsPath
                    if (relative.isEmpty()) return@forEach
                    val targets = buildList {
                        add("$PRIMARY_ENTRY_ROOT/$relative")
                        if (mirrorUnderAssetsNamespace) add("$MIRROR_ENTRY_ROOT/$relative")
                    }
                    targets.forEach { entryName ->
                        when {
                            isEntryTaken(entryName) -> conflicts += "$entryName <- ${file.absolutePath} (模板已占用)"
                            entries.containsKey(entryName) ->
                                conflicts += "$entryName <- ${file.absolutePath} (与 ${entries[entryName]?.absolutePath} 重复)"
                            else -> entries[entryName] = file
                        }
                    }
                }
            }

        return Plan(entries = entries, conflicts = conflicts)
    }
}
