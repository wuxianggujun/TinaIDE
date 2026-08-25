plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.git"

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation(project.dependencies.project(":core:i18n"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.security.crypto)

    // JGit
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache)
    implementation(libs.bouncycastle.prov)
    implementation("org.slf4j:slf4j-nop:1.7.36")
}
