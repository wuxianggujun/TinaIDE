plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.editorapi"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
