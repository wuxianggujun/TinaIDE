plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.linuxdesktop"

    // libXlorie.so 由 :termux-x11-lorie 提供，本模块不含自有 native 代码：
    // X server 的 JNI 入口是 lorie 的 CmdEntryPoint，我们只在 :x11 进程里调用它。
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // Lorie X11 server library (GPL-3.0)
    api(project.dependencies.project(":termux-x11-lorie"))

    // LinuxEnvironment / LinuxInteractiveProcess 等 Linux 运行时抽象
    api(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:i18n"))

    // 注意：不要依赖 :core:proot —— proot 已经 implementation 依赖本模块，
    // 反向依赖会构成 Gradle 循环。PRoot 侧由 PRootEnvironment 组装本模块的类型。

    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.tests.kotlinx.coroutines)
}
