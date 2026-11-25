import org.gradle.api.tasks.bundling.Jar

plugins {
    id("tina.android.library")
}

android {
    namespace = "com.wuxianggujun.tinaide.core.apkbuilder"
}

// ARSCLib's standalone JAR includes Android/XML API stubs for desktop callers.
// Feeding them to R8 as program classes makes framework XmlResourceParser values
// appear uninstantiated and turns Compose's vector parser into an unconditional NPE.
val arscLibUpstream = configurations.create("arscLibUpstream") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false // The upstream 1.3.8 POM has no dependencies.
}

val androidArscLibJar = tasks.register<Jar>("androidArscLibJar") {
    description = "Prepare ARSCLib without classes supplied by the Android framework"
    archiveFileName.set("arsclib-android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/android-arsclib"))
    from(arscLibUpstream.elements.map { artifacts ->
        artifacts.map { zipTree(it.asFile) }
    })
    exclude("android/**", "org/xmlpull/**", "META-INF/MANIFEST.MF")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    add(arscLibUpstream.name, libs.arsclib)
    implementation(project.dependencies.project(":core:i18n"))
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.android.tools.apksig)
    implementation(files(androidArscLibJar.flatMap { it.archiveFile }))
}
