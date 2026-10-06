plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.config"
}

dependencies {
    api(project(":core:editor-api"))
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:network"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)
}
