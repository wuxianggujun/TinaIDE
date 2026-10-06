plugins {
    id("tina.android.library")
    alias(libs.plugins.android.legacy.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.common"
}

dependencies {
    api(project(":core:editor-api"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:model"))
    implementation(project.dependencies.project(":tina-exec:integration"))
    // PtyProcess 依赖 Termux JNI
    implementation(project.dependencies.project(":termux-terminal:terminal-emulator"))
    // Koin DI — api 传递给所有依赖 core:common 的模块
    api(platform(libs.koin.bom))
    api(libs.koin.core)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.annotation)
    // TarExtractor 依赖 (支持 tar, tar.gz, tar.xz, tar.zst)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.zstd.jni)
    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
}
