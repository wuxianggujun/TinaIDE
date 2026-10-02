package com.wuxianggujun.tinaide.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 版本号管理策略：
 *
 * AGP 在 **configuration 阶段** 就会固化 `defaultConfig.versionCode/versionName`，
 * 因此 release 自动自增必须在 apply() 阶段完成。
 *
 * apply() 阶段根据 `gradle.startParameter.taskNames` 判断本次构建是否包含 release
 * assemble/bundle/install，若是则**先自增**再读取版本号，这样 manifest、extension、
 * mapping backup、脚本重命名读到的是同一个值。
 * GitHub tag 发版使用提交中已锁定的 version.properties，不再二次自增，
 * 避免 tag、APK manifest 与 changelog 版本错位。
 */
class TinaAndroidAppVersioningPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val versionPropsFile = rootProject.file("version.properties")

            val autoIncrementReleaseVersionEnabled =
                resolveBooleanGradleProperty("tina.autoIncrementReleaseVersion", default = true)

            val isReleaseBuild = gradle.startParameter.taskNames.any(::isReleaseVersionBumpTask)
            val isGithubTagBuild =
                providers.environmentVariable("GITHUB_REF_TYPE")
                    .orNull
                    .equals("tag", ignoreCase = true)
            val shouldBumpNow = autoIncrementReleaseVersionEnabled && isReleaseBuild && !isGithubTagBuild

            val effectiveVersion = if (shouldBumpNow) {
                autoIncrementAppVersion(versionPropsFile, logger)
            } else {
                readAppVersionInfo(versionPropsFile)
            }

            val versioningExtension = TinaAppVersioningExtension(
                versionPropsFile,
                effectiveVersion,
                resolveBuildId(rootProject.projectDir),
            )
            extensions.add("tinaAppVersioning", versioningExtension)

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    defaultConfig {
                        versionCode = effectiveVersion.versionCode
                        versionName = effectiveVersion.versionName
                        manifestPlaceholders["TINA_BASE_VERSION_CODE"] =
                            effectiveVersion.versionCode.toString()
                    }
                }
            }

            // 保留 `autoIncrementVersion` 入口以便手动递增（例如热修发版前）。
            // 但**不再参与自动 release 构建**（configuration 阶段已处理），避免双重递增。
            tasks.register("autoIncrementVersion") {
                group = "versioning"
                description = "Manually bump version.properties patch number (configuration-stage bump is automatic for release builds)."
                doLast {
                    val next = autoIncrementAppVersion(versionPropsFile, logger)
                    logger.lifecycle(
                        "Version after manual auto-increment: ${next.versionName} (${next.versionCode})",
                    )
                }
            }
        }
    }
}
