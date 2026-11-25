plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.crash"
}

dependencies {
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:logging"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":core:network"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(project.dependencies.project(":xcrash"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.kotlinx.serialization.json)
}
