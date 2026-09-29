plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.editorview"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:editor-api"))
    api(project(":core:text-engine"))

    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    implementation(libs.androidx.collection)
    coreLibraryDesugaring(libs.desugar)

    implementation(platform(libs.compose.bom))
    testImplementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.junit4)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation("androidx.compose.foundation:foundation")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.tree.sitter)
    api(project.dependencies.project(":core:tree-sitter"))
    testImplementation(libs.junit)
    testImplementation(libs.tests.google.truth)
    testImplementation(libs.tests.robolectric)
    testImplementation(libs.tests.mockk)
    testImplementation(libs.tests.kotlinx.coroutines)
}
