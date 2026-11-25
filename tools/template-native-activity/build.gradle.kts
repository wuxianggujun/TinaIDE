plugins {
    id("com.android.application")
}

android {
    namespace = "com.tinaide.template.nativeactivity"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tinaide.template.placeholder.padpadpadpadpad"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project.dependencies.project(":tools:template-common"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
