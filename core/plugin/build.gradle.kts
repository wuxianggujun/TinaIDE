plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.plugin"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        aidl = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project.dependencies.project(":core:common"))
    api(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:lsp"))
    implementation(project.dependencies.project(":core:network"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:proot"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.luajava.lua54)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Library instrumentation tests do not inherit the app module's Lua native runtime.
    androidTestRuntimeOnly("party.iroiro.luajava:android:${libs.versions.luajava.get()}:lua54@aar")
}
