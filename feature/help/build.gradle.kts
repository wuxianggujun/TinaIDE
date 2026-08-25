plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.help"
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:designsystem"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:network"))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(platform(libs.compose.bom))
    testImplementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test.junit4)
    implementation(libs.lifecycle.viewmodel)
    debugImplementation(libs.compose.ui.test.manifest)
}
