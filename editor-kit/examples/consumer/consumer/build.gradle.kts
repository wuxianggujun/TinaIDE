plugins {
    id("com.android.library") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

android {
    namespace = "io.github.tinaide.editor.example"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("io.github.tinaide.editor:editor-view:0.1.0-SNAPSHOT")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
}
