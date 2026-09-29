plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wuxianggujun.tinaide.core.textengine"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.tests.google.truth)
    testImplementation(libs.tests.robolectric)
    testImplementation(libs.tests.mockk)
    testImplementation(libs.tests.kotlinx.coroutines)
}
