import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Zip
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

// AGP owns the APK output path (AGP 9 may use intermediates/apk). Never copy a
// stale outputs/apk file left by an earlier build and then stamp new checksums.
val templateApkDirectories = mutableMapOf<String, Provider<Directory>>()
subprojects {
    if (path in setOf(":tools:template-native-activity", ":tools:template-sdl3")) {
        val templateModuleName = name
        plugins.withId("com.android.application") {
            extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants(
                extensions.getByType<ApplicationAndroidComponentsExtension>().selector().withBuildType("release")
            ) { variant -> templateApkDirectories[templateModuleName] = variant.artifacts.get(SingleArtifact.APK) }
        }
    }
}

tasks.register("buildApkTemplates") {
    description = "Build built-in template APKs and copy to app assets"
    group = "build"

    dependsOn(":tools:template-native-activity:assembleRelease")
    dependsOn(":tools:template-sdl3:assembleRelease")

    doLast {
        val targetDir = file("app/src/main/assets/apk_templates")
        targetDir.mkdirs()

        for (module in listOf("template-native-activity", "template-sdl3")) {
            val directory = templateApkDirectories.getValue(module).get().asFile
            val apk = directory.listFiles()?.singleOrNull { it.extension == "apk" }
                ?: throw GradleException("Expected one freshly built template APK in $directory")
            if (module == "template-sdl3") {
                ZipFile(apk).use { zip ->
                    val missing = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                        .filter { zip.getEntry("lib/$it/libtina_sdl_probe.so") == null }
                    if (missing.isNotEmpty()) throw GradleException(
                        "SDL export template is missing ABIs: $missing. Build templates without android.injected.build.abi."
                    )
                }
            }
            apk.copyTo(File(targetDir, "$module.apk"), overwrite = true)
        }
        writeTemplateChecksums(targetDir)
        println("Template APKs copied to: ${targetDir.absolutePath}")
    }
}

tasks.register<Zip>("packageTerminalApkExportPlugin") {
    description = "Package the terminal APK export plugin as a .tinaplug archive"
    group = "build"

    dependsOn(":tools:template-terminal:assembleRelease")

    from("plugins/tinaide.apk-export.terminal")
    from(file("tools/template-terminal/build/outputs/apk/release/template-terminal-release-unsigned.apk")) {
        into("templates")
        rename { "template-terminal.apk" }
    }

    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("tinaide.apk-export.terminal.tinaplug")
}

tasks.register("checkApkTemplatesSync") {
    description = "Verify that app/src/main/assets/apk_templates/*.apk are in sync with tools/template-*/src/"
    group = "verification"
    mustRunAfter("buildApkTemplates")

    doLast {
        val targetDir = file("app/src/main/assets/apk_templates")
        val manifestFile = File(targetDir, TEMPLATE_CHECKSUMS_FILE)
        if (!manifestFile.exists()) {
            throw GradleException(
                "Template checksum manifest not found: ${manifestFile.absolutePath}\n" +
                    "Run ./gradlew buildApkTemplates to generate it.",
            )
        }
        val recorded = parseChecksumManifest(manifestFile)
        val actual = computeTemplateChecksums()
        val diff = actual.keys
            .filter { recorded[it] != actual[it] }
            .sorted()
        if (diff.isNotEmpty()) {
            val lines = diff.map { apk ->
                val rec = recorded[apk] ?: "<missing>"
                val act = actual.getValue(apk)
                "  - $apk\n      recorded: $rec\n      current : $act"
            }
            throw GradleException(
                "Template APK assets are out of sync with tools/template-*/src/:\n" +
                    lines.joinToString("\n") + "\n" +
                    "Run ./gradlew buildApkTemplates and commit the updated APKs + checksum file.",
            )
        }
        println("Template APK assets are in sync with tools/template-*/src/.")
    }
}

val TEMPLATE_CHECKSUMS_FILE = "template-checksums.txt"

fun templateCommonInputs(): List<File> = listOf(
    file("tools/template-common/src"),
    file("tools/template-common/build.gradle.kts"),
)

fun templateModuleInputs(module: String): List<File> = listOf(
    file("tools/$module/src"),
    file("tools/$module/build.gradle.kts"),
)

fun computeTemplateChecksums(): Map<String, String> {
    val common = templateCommonInputs()
    return mapOf(
        "template-native-activity.apk" to hashInputs(
            templateModuleInputs("template-native-activity") + common,
        ),
        "template-sdl3.apk" to hashInputs(
            templateModuleInputs("template-sdl3") + common +
                listOf(file("app/src/main/java/org/libsdl/app"), file("app/src/main/cpp/sdl_probe")),
        ),
    )
}

fun hashInputs(roots: List<File>): String {
    val projectRootPath = rootDir.toPath()
    val digest = MessageDigest.getInstance("SHA-256")
    val entries = mutableListOf<Pair<String, File>>()
    for (root in roots) {
        if (!root.exists()) continue
        if (root.isFile) {
            val rel = projectRootPath.relativize(root.toPath()).toString().replace('\\', '/')
            entries += rel to root
        } else {
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { f ->
                    val rel = projectRootPath.relativize(f.toPath()).toString().replace('\\', '/')
                    entries += rel to f
                }
        }
    }
    entries.sortBy { it.first }
    for ((rel, file) in entries) {
        digest.update(rel.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(file.readBytes())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun writeTemplateChecksums(targetDir: File) {
    val manifest = File(targetDir, TEMPLATE_CHECKSUMS_FILE)
    manifest.writeText(
        buildString {
            computeTemplateChecksums().toSortedMap().forEach { (apk, sha) ->
                append(apk).append('=').append(sha).append('\n')
            }
        },
    )
}

fun parseChecksumManifest(file: File): Map<String, String> =
    file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }
        .toMap()
