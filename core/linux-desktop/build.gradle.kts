plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.linuxdesktop"

    // libXlorie.so 由 :termux-x11-lorie 提供，本模块仅提供 Kotlin API wrapper。
    // 未来需要自有 JNI 桥接代码时再添加 externalNativeBuild。
}

dependencies {
    // Lorie X11 server library (GPL-3.0)
    api(project.dependencies.project(":termux-x11-lorie"))

    // LinuxEnvironment / LinuxInteractiveProcess 等 Linux 运行时抽象
    api(project.dependencies.project(":core:common"))

    // 注意：不要依赖 :core:proot —— proot 已经 implementation 依赖本模块，
    // 反向依赖会构成 Gradle 循环。PRoot 侧由 PRootEnvironment 组装本模块的类型。

    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
}
