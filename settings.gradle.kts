@file:Suppress("UnstableApiUsage")

import java.util.Properties

fun findAndroidSdkDir(): String? {
    val localPropertiesFile = file("local.properties")
    if (localPropertiesFile.isFile) {
        val props = Properties()
        localPropertiesFile.inputStream().use(props::load)
        props.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }

    return System.getenv("ANDROID_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
}

fun escapePropertiesValue(value: String): String =
    value.replace("\\", "\\\\").replace(":", "\\:")

fun ensureIncludedBuildLocalProperties(includedBuildDir: File) {
    val target = includedBuildDir.resolve("local.properties")
    if (target.exists()) return

    val sdkDir = findAndroidSdkDir() ?: return
    target.writeText("sdk.dir=${escapePropertiesValue(sdkDir)}\n", Charsets.UTF_8)
}

val requestedGradleTasks = (
    gradle.startParameter.taskNames +
        gradle.startParameter.taskRequests.flatMap { request -> request.args }
    )
    .distinct()
    .filterNot { arg -> arg.startsWith("-") || arg.contains(".") }
fun isTaskUnderModule(taskName: String, modulePath: String): Boolean {
    val normalizedTaskName = if (taskName.startsWith(":")) taskName else ":$taskName"
    return normalizedTaskName == modulePath || normalizedTaskName.startsWith("$modulePath:")
}

fun isDependencyFreeInspectionTask(taskName: String): Boolean =
    taskName.substringAfterLast(":") in setOf("help", "tasks", "projects", "properties")

fun mayNeedTreeSitterComposite(taskName: String): Boolean {
    if (isDependencyFreeInspectionTask(taskName)) return false
    if (!taskName.startsWith(":")) return true
    return isTaskUnderModule(taskName, ":app") ||
        isTaskUnderModule(taskName, ":core:tree-sitter") ||
        isTaskUnderModule(taskName, ":core:editor-view") ||
        isTaskUnderModule(taskName, ":core:editor-lsp") ||
        isTaskUnderModule(taskName, ":feature:editor")
}

val shouldIncludeTreeSitterComposite =
    requestedGradleTasks.isEmpty() || requestedGradleTasks.any(::mayNeedTreeSitterComposite)

fun mayNeedRikkaHubComposite(taskName: String): Boolean {
    if (isDependencyFreeInspectionTask(taskName)) return false
    if (!taskName.startsWith(":")) return true
    return isTaskUnderModule(taskName, ":app") ||
        isTaskUnderModule(taskName, ":rikkahub")
}

val shouldIncludeRikkaHubComposite =
    requestedGradleTasks.isEmpty() || requestedGradleTasks.any(::mayNeedRikkaHubComposite)

// 让 composite build 的 Android 工程也能找到 SDK（避免单独配置时失败）
if (shouldIncludeTreeSitterComposite) {
    ensureIncludedBuildLocalProperties(file("external/tina-android-tree-sitter"))
}

pluginManagement {
    val pluginRequestTasks = gradle.startParameter.taskNames
    val rootInspectionTasks = setOf("help", "tasks", "projects", "properties")
    val shouldIncludeBuildLogic = !gradle.startParameter.isConfigureOnDemand ||
        pluginRequestTasks.isEmpty() ||
        pluginRequestTasks.any { taskName ->
            val leafTaskName = taskName.substringAfterLast(":")
            val rootScoped = !taskName.startsWith(":") || taskName.count { it == ':' } == 1
            leafTaskName !in rootInspectionTasks || !rootScoped
        }
    if (shouldIncludeBuildLogic) {
        includeBuild("build-logic")
    }
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (!preferOfficialRepositories) {
            // Windows/国内网络环境下，Maven Central 偶发 TLS 握手失败时优先走镜像。
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogleMirror"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public") {
                name = "AliyunPublicMirror"
            }
            maven("https://maven.aliyun.com/repository/gradle-plugin") {
                name = "AliyunGradlePluginMirror"
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// 包含本地的 tina-android-tree-sitter 项目，替换 Maven 依赖
if (shouldIncludeTreeSitterComposite) {
    includeBuild("external/tina-android-tree-sitter") {
        dependencySubstitution {
            substitute(module("com.itsaky.androidide.treesitter:android-tree-sitter")).using(project(":android-tree-sitter"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-java")).using(project(":tree-sitter-java"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-json")).using(project(":tree-sitter-json"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-c")).using(project(":tree-sitter-c"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-cpp")).using(project(":tree-sitter-cpp"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-bash")).using(project(":tree-sitter-bash"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-yaml")).using(project(":tree-sitter-yaml"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-make")).using(project(":tree-sitter-make"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-cmake")).using(project(":tree-sitter-cmake"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-rust")).using(project(":tree-sitter-rust"))
            substitute(module("com.itsaky.androidide.treesitter:tree-sitter-toml")).using(project(":tree-sitter-toml"))
        }
    }
} else {
    logger.lifecycle("Skipping tina-android-tree-sitter included build for tasks that do not consume it.")
}

val rikkahubBuildDir = file("external/rikkahub")
if (shouldIncludeRikkaHubComposite && rikkahubBuildDir.isDirectory) {
    ensureIncludedBuildLocalProperties(rikkahubBuildDir)
    includeBuild(rikkahubBuildDir) {
        dependencySubstitution {
            substitute(module("me.rerere.rikkahub:rikkahub-embedded")).using(project(":embedded"))
        }
    }
} else if (!rikkahubBuildDir.isDirectory) {
    logger.lifecycle("Skipping RikkaHub included build because external/rikkahub is missing.")
} else {
    logger.lifecycle("Skipping RikkaHub included build for tasks that do not consume rikkahub-embedded.")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val preferOfficialRepositories = System.getenv("CI").equals("true", ignoreCase = true)
    repositories {
        if (!preferOfficialRepositories) {
            // Windows/国内网络环境下，Maven Central 偶发 TLS 握手失败时优先走镜像。
            maven("https://maven.aliyun.com/repository/google") {
                name = "AliyunGoogleMirror"
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            maven("https://maven.aliyun.com/repository/public") {
                name = "AliyunPublicMirror"
            }
        }
        google()
        mavenCentral()
        // 本地 AAR 目录（用于将无法从网络下载的 JitPack 依赖内嵌到项目中）
        // 将 AAR 文件放到 libs/ 目录后，Gradle 会优先从此处查找
        flatDir {
            dirs(rootProject.projectDir.resolve("libs"))
        }
        // For libraries published on JitPack (e.g., getActivity/XXPermissions)
        // ImmersionBar AAR 已内嵌到 libs/ 目录，但保留 JitPack 作为其他库的备用源
        maven("https://jitpack.io")
        // 字节系依赖备用仓库
        maven("https://artifact.bytedance.com/repository/pangle")
    }
}

rootProject.name = "TinaIDE"
include(":app")

// ===== 基础层 =====
include(":core:cmake")
include(":core:common")
include(":core:config")
include(":core:database")
include(":core:designsystem")
include(":core:i18n")
include(":core:logging")
include(":core:model")
include(":core:network")
include(":core:security")
include(":core:storage")
include(":core:text-engine")
include(":core:tree-sitter")
include(":core:editor-view")
include(":core:editor-lsp")

// ===== 功能层 =====
include(":feature:editor")
include(":feature:help")
include(":feature:output")
include(":feature:packages")
include(":feature:projectlist")
include(":feature:settings")
include(":feature:terminal")
include(":feature:tutorial")
include(":feature:viewer")
include(":feature:wizard")
include(":feature:workspace")

// ===== 中间层 =====
include(":core:apk-builder")
include(":core:compile")
include(":core:crash")
include(":core:debug")
include(":core:git")
include(":core:linux-desktop")
include(":core:linux-distro")
include(":core:lsp")
include(":core:ndk")
include(":core:packages")
include(":core:plugin")
include(":core:project")
include(":core:proot")
include(":core:search")

// Include Termux terminal modules (Apache 2.0 license)
include(":termux-terminal:terminal-emulator")
include(":termux-terminal:terminal-view")
project(":termux-terminal").projectDir = file("external/termux-terminal")
project(":termux-terminal:terminal-emulator").projectDir = file("external/termux-terminal/terminal-emulator")
project(":termux-terminal:terminal-view").projectDir = file("external/termux-terminal/terminal-view")


// Include Tina exec runtime modules (vendored from upstream termux-exec/core with local wrapping)
include(":tina-exec:runtime")
include(":tina-exec:integration")
project(":tina-exec").projectDir = file("external/tina-exec")
project(":tina-exec:runtime").projectDir = file("external/tina-exec/runtime")
project(":tina-exec:integration").projectDir = file("external/tina-exec/integration")

// Include xCrash (local build with 16KB page alignment)
include(":xcrash")
project(":xcrash").projectDir = file("external/xcrash")

// Include ImmersionBar local modules (避免依赖 JitPack，使用本地源码)
include(":immersionbar-local")
project(":immersionbar-local").projectDir = file("external/immersionbar/immersionbar")
include(":immersionbar-ktx-local")
project(":immersionbar-ktx-local").projectDir = file("external/immersionbar/immersionbar-ktx")

// Include XXPermissions + DeviceCompat local modules (避免依赖 JitPack)
include(":devicecompat-local")
project(":devicecompat-local").projectDir = file("external/devicecompat/library")
include(":xxpermissions-local")
project(":xxpermissions-local").projectDir = file("external/xxpermissions/library")

// APK template modules (built on-demand, not part of default build)
include(":tools:template-common")
include(":tools:template-native-activity")
include(":tools:template-sdl3")
include(":tools:template-terminal")

// Include termux-x11 lorie library (GPL-3.0)
// :shell-loader:stub 提供 lorie 编译期需要的 Android 隐藏 API 桩，仅 compileOnly
// 注意：只引入所需子模块，不引入 termux-x11 根项目（它有自己的 build.gradle 会与主项目冲突）
include(":termux-x11-lorie")
include(":termux-x11-shell-loader-stub")
project(":termux-x11-lorie").projectDir = file("external/termux-x11/lorie")
project(":termux-x11-shell-loader-stub").projectDir = file("external/termux-x11/shell-loader/stub")
