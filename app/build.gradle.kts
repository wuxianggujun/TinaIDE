import com.wuxianggujun.tinaide.buildlogic.TinaAppAbiAggregationExtension
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    id("tina.android.app.versioning")
    id("tina.android.app.abi-aggregation")
    id("tina.android.app.guardrails")
    id("tina.android.app.toolchain.assets")
    id("tina.android.app.treesitter")
    id("tina.android.app.mapping")
}

// Load release signing config from keystore.properties if present
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(keystorePropsFile.inputStream())
}
// `appVersionCode` / `appVersionName` 由 `tina.android.app.versioning` 插件
// 写入 Android defaultConfig；版本相关消费方通过插件扩展访问。
val abiAggregation =
    extensions.getByType(TinaAppAbiAggregationExtension::class.java)
val localDevAbi = abiAggregation.localDevAbi
val buildAllAbiRequested = abiAggregation.buildAllAbiRequested
val buildProotFromSource = providers.gradleProperty("tina.buildProotFromSource")
    .map { value ->
        value.equals("true", ignoreCase = true) ||
            value == "1" ||
            value.equals("yes", ignoreCase = true) ||
            value.equals("on", ignoreCase = true)
    }
    .getOrElse(true)
// `tina.releaseMapping.enabled` / `tina.releaseMapping.serverUrl` 由
// `tina.android.app.mapping` 插件消费（见 build-logic/convention）。

android {
    namespace = "com.wuxianggujun.tinaide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wuxianggujun.tinaide"
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // /api/server-config（MessagePack）签名密钥：建议仅放在本地 keystore.properties 或环境变量中
        val serverConfigHmacSecret = keystoreProps.getProperty("SERVER_CONFIG_HMAC_SECRET")
            ?: System.getenv("SERVER_CONFIG_HMAC_SECRET")
            ?: ""
        buildConfigField("String", "SERVER_CONFIG_HMAC_SECRET", "\"$serverConfigHmacSecret\"")

        // NDK 配置
        // 注意：当启用 ABI splits 时，不要在这里设置 abiFilters，否则会冲突
        // ndk {
        //     abiFilters += listOf("arm64-v8a", "x86_64")
        // }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DTINA_BUILD_PROOT_FROM_SOURCE=${if (buildProotFromSource) "ON" else "OFF"}"
                )
                // 注意：当启用 ABI splits 时，不要在这里设置 abiFilters，否则会冲突
                // Gradle 会根据 splits.abi 配置自动构建对应的 ABI
            }
        }
    }

    // Define signing config before buildTypes so it can be referenced below
    if (keystoreProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                val storeFileProp = keystoreProps.getProperty("storeFile")
                val storePasswordProp = keystoreProps.getProperty("storePassword")
                val keyAliasProp = keystoreProps.getProperty("keyAlias")
                val keyPasswordProp = keystoreProps.getProperty("keyPassword")

                if (!storeFileProp.isNullOrBlank()) storeFile = rootProject.file(storeFileProp)
                if (!storePasswordProp.isNullOrBlank()) storePassword = storePasswordProp
                if (!keyAliasProp.isNullOrBlank()) keyAlias = keyAliasProp
                if (!keyPasswordProp.isNullOrBlank()) keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        debug {}
        release {
            // 启用代码压缩和混淆
            isMinifyEnabled = true
            // 启用资源压缩（移除未使用的资源）
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attach signing config when keystore.properties is available
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }

            // 发布版本：仅排除"工具链整包 rootfs"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
        aidl = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Native packaging: old devices still rely on legacy jni layout
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 不要 strip proot 相关的二进制文件
            // proot 是 PIE executable，strip 会破坏其执行能力
            keepDebugSymbols += listOf(
                "**/libproot.so",
                "**/libproot-loader.so",
                "**/libproot-loader32.so"
            )
        }
        resources {
            // JGit 和 JGit SSH Apache 都包含同名 OSGI 元数据文件，Android 运行时不使用
            pickFirsts += "OSGI-INF/l10n/plugin.properties"
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // zstd-jni 桌面平台 native（commons-compress 传递依赖,Android 上不可用）
                "win/**",
                "darwin/**",
                "linux/amd64/**",
                "linux/i386/**",
                "linux/aarch64/**",
                "linux/ppc64/**",
                "linux/mips64/**",
                "linux/s390x/**",
                // BouncyCastle Picnic 后量子签名查找表（JGit SSH 不使用）
                "org/bouncycastle/pqc/crypto/picnic/**",
                // Apache SSHD DH moduli（仅 SSH server 模式需要,本 app 是 client）
                "org/apache/sshd/moduli",
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            if (!buildProotFromSource) {
                jniLibs.srcDir("src/prebuiltProot/jniLibs")
            }
        }
    }

    // Ensure AAPT does not ignore libc++ private headers under c++/v1/__ios
    // Some default ignore patterns may exclude double-underscore directories in assets.
    @Suppress("UnstableApiUsage")
    androidResources {
        // Do not ignore any assets to ensure libc++ internals like c++/v1/__ios are packaged
        ignoreAssetsPattern = ""
        // 禁止 AAPT 压缩已压缩的文件（tar.gz, proot binary 等）
        // 否则 assets.open() 可能无法正确读取
        // 注意：使用 tar_gz 而不是 tar.gz，因为 AAPT 会自动解压 .gz 文件导致 APK 体积增大
        noCompress += listOf("gz", "tar_gz", "proot", "xz", "sh")
    }

    // Lint: 先用 baseline 挡住历史遗留问题；后续迭代逐步消减（避免一次性修 500+ 个问题）
    lint {
        baseline = file("lint-baseline.xml")
        disable += setOf(
            // 当前项目存在大量历史遗留/未完整覆盖的多语言资源；短期不把“补齐所有翻译”作为阻断项（YAGNI）。
            "MissingTranslation",
        )
    }

    // ========== Tree-sitter Queries ==========
    // queries 由 :app:syncTreeSitterQueries 手动同步到 src/main/assets 并提交到仓库；
    // 不在构建时自动生成（避免每次 build 联网/耗时，也避免 build/clean 导致资源丢失）。

    // ========== ABI 分离构建 ==========
    // 重要：splits.abi 只会拆分 native libs（so），不会为不同 split 生成不同的 assets。
    // 如果需要将 assets/android-sysroot、assets/proot 等“按 ABI 分离打包”，必须使用 productFlavors。
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        create("x86_64") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    // 为不同 ABI 的 APK 设置不同的 versionCode / 文件名
    // Google Play 多 APK 分发要求每个 APK 有唯一的 versionCode
    // arm64-v8a: versionCode + 2；x86_64: versionCode + 1
    applicationVariants.all {
        val variant = this
        val targetAbi = when (variant.flavorName) {
            "arm64" -> "arm64-v8a"
            "x86_64" -> "x86_64"
            else -> null
        }
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val abiVersionCode = when (targetAbi) {
                "arm64-v8a" -> 2
                "x86_64" -> 1
                else -> 0
            }
            output.versionCodeOverride = variant.versionCode * 10 + abiVersionCode
            if (targetAbi != null) {
                output.outputFileName = "app-$targetAbi-${variant.buildType.name}.apk"
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val abiFlavor = variantBuilder.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "abi" }
            ?.second
            ?: return@beforeVariants
        if (!buildAllAbiRequested && abiFlavor != localDevAbi) {
            variantBuilder.enable = false
        }
    }
}

// proot 现在直接放在 jniLibs 目录中（作为 libproot.so），不再从 assets 复制
// PRoot 已不再依赖 libtalloc（不从 assets 解压）

// ABI 专属 assets 通过 flavor/buildType sourceSet 目录提供：
// - sysroot: app/src/arm64/assets/android-sysroot/、app/src/x86_64/assets/android-sysroot/
// - proot:   app/src/<abiFlavor>/assets/proot/<abi>/（debug 的 rootfs.tar.gz 在 app/src/<abiFlavor>Debug/assets/）

// 本地默认只启用一个 ABI 变体（默认 arm64，可用 -Ptina.devAbi=x86_64 切换）。
// 显式运行 assembleReleaseAllAbi / assembleDebugAllAbi，或传 -Ptina.allAbi=true 时恢复全部 ABI。
// ABI 聚合任务与 CMake native 挂接已由 `tina.android.app.abi-aggregation`
// 插件统一注册（见 build-logic/convention）。

// `verifyTinaToolchainAssets` 任务与相关挂接逻辑已由
// `tina.android.app.toolchain.assets` 插件注册（见 build-logic/convention）。

// Tree-sitter 查询同步（syncTreeSitterQueries）、语言注册表生成
// （generateTreeSitterLanguageRegistry）与 main 源集注入均由
// `tina.android.app.treesitter` 插件提供（见 build-logic/convention）。

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    // ===== 内部模块 =====
    implementation(project(":core:apk-builder"))
    implementation(project(":core:common"))
    implementation(project(":core:compile"))
    implementation(project(":core:config"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:crash"))
    implementation(project(":core:debug"))
    implementation(project(":core:git"))
    implementation(project(":core:i18n"))
    implementation(project(":core:logging"))
    implementation(project(":core:lsp"))
    implementation(project(":core:model"))
    implementation(project(":core:ndk"))
    implementation(project(":core:network"))
    implementation(project(":core:packages"))
    implementation(project(":core:plugin"))
    implementation(project(":core:project"))
    implementation(project(":core:security"))
    implementation(project(":core:storage"))
    implementation(project(":core:proot"))
    implementation(project(":core:search"))
    implementation(project(":core:text-engine"))
    implementation(project(":core:tree-sitter"))
    implementation(project(":core:editor-view"))
    implementation(project(":core:editor-lsp"))

    // ===== 功能层 =====
    implementation(project(":feature:ai"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:help"))
    implementation(project(":feature:output"))
    implementation(project(":feature:packages"))
    implementation(project(":feature:projectlist"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:tutorial"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:wizard"))
    implementation(project(":feature:workspace"))

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)

    // AndroidX Preference (Material Design)
    implementation(libs.androidx.preference.ktx)

    // Tree-sitter grammar 依赖由 :core:tree-sitter 统一管理
    // GenerateTreeSitterLanguageRegistry task 仍需要解析 implementation deps，
    // 因此保留对 :core:tree-sitter 的依赖即可（grammar jars 通过传递依赖到达 classpath）。

    // CMake 解析器
    implementation(project(":core:cmake"))

    // Termux 终端模块（Apache 2.0 许可证）
    implementation(project(":termux-terminal:terminal-view"))

    // Apache Commons Compress for tar.gz/tar.xz extraction (PRoot rootfs, NDK sysroot)
    implementation(libs.commons.compress) {
        exclude(group = "com.github.luben", module = "zstd-jni")
    }
    // XZ compression support (required by commons-compress for .tar.xz files)
    implementation(libs.tukaani.xz)

    // Kotlin Coroutines for async operations
    implementation(libs.kotlinx.coroutines)
    coreLibraryDesugaring(libs.desugar)

    // AndroidX Lifecycle (ViewModel + StateFlow)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)

    // Jetpack Compose (Open source licenses page)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    // 沉浸式状态栏和导航栏（本地源码模块，避免 JitPack 网络超时）
    implementation(project(":immersionbar-local"))
    implementation(project(":immersionbar-ktx-local"))

    // Android rsync - 用于远程 LSP 项目同步（增量传输）
    // https://github.com/ribbons/android-rsync
    // 注意：使用本地构建的 16KB 对齐版本（位于 jniLibs/），不再使用 Maven 依赖
    // 原因：Maven 版本不支持 Android 15+ 的 16KB 页面大小要求
    // 详见：docs/design/16KB-Page-Alignment-Fix.md
    // implementation("com.nerdoftheherd:android-rsync:3.4.1")

    // Security - 加密存储 Token
    implementation(libs.androidx.security.crypto)

    // DataStore - 用户偏好存储
    implementation(libs.datastore.preferences)

    // WorkManager - 后台任务调度（服务器配置同步等）
    implementation(libs.work.runtime)

    // xCrash - Native 崩溃捕获（支持 Java/Native/ANR）
    // 使用本地编译版本，支持 16KB page alignment (Android 15+)
    implementation(project(":xcrash"))

    // Timber - 日志框架（支持文件日志）
    implementation(libs.timber)

    // LuaJava - Lua 脚本引擎（用于脚本插件）
    // 使用 gudzpoz/luajava 库，Native Lua 5.4（通过 JNI）
    // https://github.com/gudzpoz/luajava
    implementation(libs.luajava.lua54)

    // Coil - 图片加载（用于图片预览器和 Markdown 渲染）
    // Coil 3.3.0 会携带更高版本的 kotlin-stdlib 约束，统一排除后交由项目 Kotlin 版本管理。
    implementation(libs.coil.compose) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.coil.network.okhttp) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.coil.svg) {
        // SVG 支持
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.coil.gif) {
        // GIF 支持
        exclude(group = "org.jetbrains.kotlin")
    }

    // ExifInterface - 图片方向信息读取（用于头像旋转校正）
    implementation(libs.androidx.exifinterface)

    // JetBrains Markdown Parser - Markdown AST 解析（AI 聊天自研渲染层）
    implementation(libs.jetbrains.markdown)

    // LaTeX 数学公式渲染（AI 回复中的数学公式）
    // 这些库的 POM 会把 kotlin-stdlib 严格钉到 2.3.0，会破坏项目统一的 Kotlin 编译链。
    implementation(libs.jlatexmath) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.jlatexmath.font.greek) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.jlatexmath.font.cyrillic) {
        exclude(group = "org.jetbrains.kotlin")
    }

    // HTML 块解析（Markdown 中嵌入的 HTML 内容）
    implementation(libs.jsoup)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.tests.google.truth)
    testImplementation(libs.tests.kotlinx.coroutines)
    testImplementation(libs.tests.mockk)
    testImplementation(libs.tests.robolectric)

    // Instrumentation tests (Android)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Kotlin 2.x 编译器选项（替代已废弃的 kotlinOptions.jvmTarget）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// `backupMappingFiles` / `uploadMappingFiles` 两个任务及其 release
// `finalizedBy` 挂接逻辑已由 `tina.android.app.mapping` 插件统一注册
// （见 build-logic/convention）。可通过以下 gradle 属性控制：
// - `tina.releaseMapping.enabled`（默认 true）
// - `tina.releaseMapping.backupEnabled`（默认 true）
// - `tina.releaseMapping.uploadEnabled`（默认 false）
// - `tina.releaseMapping.serverUrl`（显式启用上传时使用）
// `checkNoAndroidUtilLog` 守卫任务与 `preBuild` 挂接已由
// `tina.android.app.guardrails` 插件统一注册（见 build-logic/convention）。
