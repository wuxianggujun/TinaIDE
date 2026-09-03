plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.linuxdesktop"

    // libXlorie.so 从 :termux-x11:lorie 提供，本模块仅提供 Kotlin API wrapper
    // 未来可能需要 JNI 桥接代码时再添加 externalNativeBuild
}

dependencies {
    // Lorie X11 server library (GPL-3.0)
    api(project(":termux-x11:lorie"))

    // PRoot 环境集成（需要在 PRoot 启动后配置 DISPLAY）
    api(project(":core:proot"))

    // 日志
    implementation(project(":core:logging"))

    // Koin DI
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // AndroidX
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
