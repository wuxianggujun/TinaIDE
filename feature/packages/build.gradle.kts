plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.packages"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project.dependencies.project(":core:designsystem"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:packages"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.lifecycle.viewmodel)
}
