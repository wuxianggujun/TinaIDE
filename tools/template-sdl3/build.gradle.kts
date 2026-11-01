plugins {
    id("com.android.application")
}

val copySDLSources = tasks.register<Copy>("copySDLSources") {
    from(rootProject.file("app/src/main/java/org/libsdl/app"))
    into(layout.buildDirectory.dir("generated/sdl/org/libsdl/app"))
}

val generatedSdlDir = layout.buildDirectory.dir("generated/sdl").get().asFile

android {
    namespace = "com.tinaide.template.sdl3"
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

    sourceSets {
        getByName("main") {
            // AGP 9 disallows Provider-backed source directories on SourceSet APIs.
            java.directories.add(generatedSdlDir.absolutePath)
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project.dependencies.project(":tools:template-common"))
    // The shared SDL bridge uses ContextCompat.registerReceiver's API 33 flags.
    implementation(libs.androidx.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    dependsOn(copySDLSources)
}

// AGP's built-in Kotlin compilation also reads the generated Java source roots.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(copySDLSources)
}
