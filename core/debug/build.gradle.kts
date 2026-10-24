plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.debug"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(project.dependencies.project(":core:ndk"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
}
