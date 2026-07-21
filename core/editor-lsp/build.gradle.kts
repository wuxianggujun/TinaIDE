plugins {
    id("tina.android.library")
}
android {
    namespace = "com.wuxianggujun.tinaide.core.editorlsp"
}

dependencies {
    implementation(project.dependencies.project(":core:text-engine"))
    implementation(project.dependencies.project(":core:lsp"))
    implementation(project.dependencies.project(":core:common"))
    implementation(project.dependencies.project(":core:config"))
    implementation(project.dependencies.project(":core:i18n"))
    implementation(project.dependencies.project(":core:ndk"))
    implementation(project.dependencies.project(":core:plugin"))
    implementation(project.dependencies.project(":core:project"))
    implementation(project.dependencies.project(":core:cmake"))
    implementation(project.dependencies.project(":core:tree-sitter"))

    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    api(libs.lsp4j)
}
