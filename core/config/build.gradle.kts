plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.config"

    testOptions {
        // Prefs is process-scoped; a different Robolectric Application must not inherit its Context.
        unitTests.all { test -> test.forkEvery = 1L }
    }
}

dependencies {
    api(project(":core:editor-api"))
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)
}
