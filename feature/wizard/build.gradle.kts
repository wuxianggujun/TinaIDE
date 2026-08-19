plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.wizard"
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:compile"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:designsystem"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:packages"))
    implementation(project.dependencies.project(":core:plugin"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.viewmodel)
    implementation(project.dependencies.project(":immersionbar-local"))
    implementation(project.dependencies.project(":immersionbar-ktx-local"))
}
