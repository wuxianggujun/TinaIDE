plugins {
    id("tina.android.library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.legacy.kapt)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.terminal"
    buildFeatures {
        compose = true
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", project.file("schemas").absolutePath)
    }
}

dependencies {
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:designsystem"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(project.dependencies.project(":termux-terminal:terminal-emulator"))
    implementation(project.dependencies.project(":termux-terminal:terminal-view"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
}
