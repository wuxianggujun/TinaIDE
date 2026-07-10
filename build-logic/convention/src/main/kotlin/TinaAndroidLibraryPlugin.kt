import com.android.build.api.dsl.LibraryExtension
import com.wuxianggujun.tinaide.buildlogic.TinaVersions
import com.wuxianggujun.tinaide.buildlogic.TinaToolchainAssetsVerification
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class TinaAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("tina.kotlin.quality")
            pluginManager.apply("com.android.library")

            val nativeAbis = resolveNativeAbis()

            extensions.configure<LibraryExtension> {
                compileSdk = TinaVersions.COMPILE_SDK
                defaultConfig {
                    minSdk = TinaVersions.MIN_SDK
                    consumerProguardFiles("consumer-rules.pro")
                    ndk {
                        abiFilters += nativeAbis
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.toVersion(TinaVersions.JVM_TARGET)
                    targetCompatibility = JavaVersion.toVersion(TinaVersions.JVM_TARGET)
                }
            }


            // 统一测试依赖：所有使用 tina.android.library 的模块自动获得基础测试库
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
            dependencies {
                add("testImplementation", libs.findLibrary("junit").get())
                add("testImplementation", libs.findLibrary("tests-google-truth").get())
                add("testImplementation", libs.findLibrary("tests-robolectric").get())
                add("testImplementation", libs.findLibrary("tests-mockk").get())
                add("testImplementation", libs.findLibrary("tests-kotlinx-coroutines").get())
            }
        }
    }

    private fun Project.resolveNativeAbis(): Set<String> {
        if (TinaToolchainAssetsVerification.resolveBuildAllAbiRequested(this)) {
            return setOf("arm64-v8a", "x86_64")
        }
        return when (TinaToolchainAssetsVerification.resolveLocalDevAbi(this)) {
            "x86_64" -> setOf("x86_64")
            else -> setOf("arm64-v8a")
        }
    }
}
