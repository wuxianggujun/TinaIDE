plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.treesitter"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:editor-api"))
    implementation(project(":core:text-engine"))
    implementation(libs.timber)

    // Tree-sitter core
    api(libs.tree.sitter)

    // Grammar bindings 使用 api：app 模块的 GeneratedTreeSitterLanguageRegistry.kt
    // 通过 preBuild 生成，需要直接引用 TSLanguageAidl/TSLanguageBash 等类。
    api("com.itsaky.androidide.treesitter:tree-sitter-aidl:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-bash:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-c:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-cmake:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-cpp:4.3.2")
    api(libs.tree.sitter.java)
    api("com.itsaky.androidide.treesitter:tree-sitter-json:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-kotlin:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-log:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-make:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-properties:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-python:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-rust:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-toml:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-xml:4.3.2")
    api("com.itsaky.androidide.treesitter:tree-sitter-yaml:4.3.2")
    testImplementation(libs.junit)
    testImplementation(libs.tests.google.truth)
    testImplementation(libs.tests.robolectric)
    testImplementation(libs.tests.mockk)
    testImplementation(libs.tests.kotlinx.coroutines)
}
