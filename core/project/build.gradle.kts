plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.project"
}

dependencies {
    api(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
}
