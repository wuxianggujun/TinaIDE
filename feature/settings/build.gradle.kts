plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.settings"
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:compile"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:crash"))
    implementation(project.dependencies.project(":core:designsystem"))
    implementation(project.dependencies.project(":core:editor-view"))
    implementation(project.dependencies.project(":core:git"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:linux-desktop"))
    implementation(project.dependencies.project(":core:logging"))
    implementation(project.dependencies.project(":core:lsp"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:ndk"))
    implementation(project.dependencies.project(":core:network"))
    implementation(project.dependencies.project(":core:plugin"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(project.dependencies.project(":core:storage"))
    // feature:terminal 依赖已移除，通过 Koin DI 注入接口
    implementation(libs.androidx.activity)
    implementation(libs.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
}
