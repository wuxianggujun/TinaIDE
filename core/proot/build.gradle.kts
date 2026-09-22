plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.proot"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:linux-desktop"))
    implementation(project.dependencies.project(":core:linux-distro"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:ndk"))
    implementation(project.dependencies.project(":core:network"))
    implementation(project.dependencies.project(":core:security"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(project.dependencies.project(":tina-exec:integration"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.okhttp)
    implementation(libs.androidx.annotation)
}
