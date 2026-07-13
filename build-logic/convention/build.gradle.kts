plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.plugins.android.application.toDep())
    implementation(libs.plugins.android.library.toDep())
    implementation(libs.plugins.compose.compiler.toDep())
    implementation(libs.plugins.ktlint.toDep())
}

fun Provider<PluginDependency>.toDep() = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "tina.android.library"
            implementationClass = "TinaAndroidLibraryPlugin"
        }
        register("androidLibraryCompose") {
            id = "tina.android.library.compose"
            implementationClass = "TinaAndroidLibraryComposePlugin"
        }
        register("kotlinQuality") {
            id = "tina.kotlin.quality"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaKotlinQualityPlugin"
        }
        register("androidAppVersioning") {
            id = "tina.android.app.versioning"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppVersioningPlugin"
        }
        register("androidAppToolchainAssets") {
            id = "tina.android.app.toolchain.assets"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppToolchainAssetsPlugin"
        }
        register("androidAppAbiAggregation") {
            id = "tina.android.app.abi-aggregation"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppAbiAggregationPlugin"
        }
        register("androidAppGuardrails") {
            id = "tina.android.app.guardrails"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppGuardrailsPlugin"
        }
        register("androidAppTreeSitter") {
            id = "tina.android.app.treesitter"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppTreeSitterPlugin"
        }
        register("androidAppMapping") {
            id = "tina.android.app.mapping"
            implementationClass = "com.wuxianggujun.tinaide.buildlogic.TinaAndroidAppMappingPlugin"
        }
    }
}
