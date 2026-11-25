plugins {
    id("com.android.library")
}

android {
    namespace = "com.tinaide.template.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    api(project.dependencies.project(":xxpermissions-local"))
    api("androidx.fragment:fragment:1.8.8") {
        exclude(group = "org.jetbrains.kotlin")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
