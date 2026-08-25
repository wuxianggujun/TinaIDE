plugins {
    id("tina.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.legacy.kapt)
}

android {
    namespace = "com.wuxianggujun.tinaide.feature.editor"
}

kapt {
    arguments {
        arg("room.schemaLocation", project.file("schemas").absolutePath)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:lsp"))
    implementation(project.dependencies.project(":core:plugin"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:search"))
    implementation(project.dependencies.project(":core:storage"))
    implementation(project.dependencies.project(":core:cmake"))
    implementation(project.dependencies.project(":core:tree-sitter"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.core.ktx)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
}
