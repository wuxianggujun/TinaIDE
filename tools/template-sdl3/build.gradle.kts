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

    externalNativeBuild {
        cmake {
            path = rootProject.file("app/src/main/cpp/sdl_probe/CMakeLists.txt")
            version = "3.22.1"
        }
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
    testImplementation(libs.junit)
}

// 生成目录里的 Java 源码来自 app 的 SDL 桥接层，因此所有编译任务都必须先跑 copySDLSources。
// 只给 JavaCompile 加依赖不够：KGP 还会创建 compileXxxKotlin（它同样会看到这些 .java 源文件），
// Gradle 会以 "implicit dependency" 校验失败告终。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    dependsOn(copySDLSources)
}

// AGP's built-in Kotlin compilation also reads the generated Java source roots.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(copySDLSources)
}
tasks.matching { task ->
    task.name.startsWith("compile") && task.name.endsWith("Kotlin")
}.configureEach {
    dependsOn(copySDLSources)
}
