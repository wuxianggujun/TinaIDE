plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.compile"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:config"))
    // 策略/格式化/检测器直接使用 CxxFileSupport
    implementation(project.dependencies.project(":core:language-support"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:ndk"))
    implementation(project.dependencies.project(":core:packages"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(project.dependencies.project(":core:security"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(project.dependencies.project(":core:cmake"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.annotation)
    implementation(libs.koin.android)
}
