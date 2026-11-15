plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.legacy.kapt)
}

android {
    namespace = "com.wuxianggujun.tinaide.database"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    // 依赖 core:common（接口层）
    implementation(project.dependencies.project(":core:common"))
    // Room 数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Kotlin 协程
    implementation(libs.kotlinx.coroutines)

    // Koin DI
    implementation(libs.koin.android)
}
