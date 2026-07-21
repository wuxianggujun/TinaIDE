package com.wuxianggujun.tinaide.terminal.locale

import android.content.Context
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.i18n.strOr
import com.wuxianggujun.tinaide.core.linux.LinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.linux.UnavailableLinuxEnvironmentProvider
import com.wuxianggujun.tinaide.core.proot.GuestSystemPackageManager
import com.wuxianggujun.tinaide.core.proot.RootfsPackageManager
import com.wuxianggujun.tinaide.core.proot.displayName
import com.wuxianggujun.tinaide.core.proot.resolveGuestPackageManager
import com.wuxianggujun.tinaide.core.terminal.ILocaleInstaller
import com.wuxianggujun.tinaide.core.terminal.LocaleInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Locale 语言包安装器
 *
 * 根据当前 rootfs 的包管理器检测并安装 locale 支持。
 */
class LocaleInstaller(
    private val context: Context,
    private val linuxEnvironmentProvider: LinuxEnvironmentProvider = UnavailableLinuxEnvironmentProvider,
) : ILocaleInstaller {

    /**
     * 检查目标 locale 是否已生成
     */
    suspend fun isLocalePackageInstalled(locale: String): Boolean = withContext(Dispatchers.IO) {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) return@withContext false

        val result = linuxEnvironment.execute(
            command = listOf("/bin/sh", "-lc", "command -v locale >/dev/null 2>&1 && locale -a || true"),
            workDir = "/",
            timeout = 10_000
        )

        val variants = setOf(
            locale.lowercase(),
            locale.replace("UTF-8", "utf8", ignoreCase = true).lowercase(),
            locale.substringBefore('.', locale).lowercase(),
        )

        result.stdout.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { candidate -> candidate in variants }
    }

    /**
     * 安装 locales 并生成目标 locale
     *
     * @param locale 目标 locale（如 zh_CN.UTF-8）
     * @param onProgress 进度回调
     * @return 安装结果
     */
    override suspend fun installLocalePackage(
        locale: String,
        force: Boolean,
        onProgress: (String) -> Unit
    ): LocaleInstallResult = withContext(Dispatchers.IO) {
        val linuxEnvironment = linuxEnvironmentProvider.get()
        if (!linuxEnvironment.isAvailable()) {
            return@withContext LocaleInstallResult.Error(
                Strings.locale_error_proot_not_installed.strOr(context)
            )
        }

        try {
            if (!force && isLocalePackageInstalled(locale)) {
                return@withContext LocaleInstallResult.Success
            }

            val packageManager = linuxEnvironment.resolveGuestPackageManager()
            val installPlan = buildInstallPlan(locale, packageManager)
                ?: return@withContext LocaleInstallResult.Error(
                    Strings.locale_error_unsupported_package_manager.strOr(
                        context,
                        packageManager.displayName()
                    )
                )

            onProgress(Strings.locale_updating_index.strOr(context))

            val updateResult = GuestSystemPackageManager.updateIndex(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                timeoutMs = 120_000,
            )

            if (updateResult.exitCode != 0) {
                return@withContext LocaleInstallResult.Error(
                    Strings.locale_error_update_failed.strOr(
                        context,
                        updateResult.combinedOutput.ifBlank { "${packageManager.displayName()} update failed" }
                    )
                )
            }

            onProgress(Strings.locale_installing.strOr(context))

            val installResult = GuestSystemPackageManager.installPackages(
                linuxEnvironment = linuxEnvironment,
                packageManager = packageManager,
                packages = installPlan.packages,
                timeoutMs = 300_000,
                force = force,
            )

            if (installResult.exitCode != 0) {
                return@withContext LocaleInstallResult.Error(
                    Strings.locale_error_install_failed.strOr(
                        context,
                        installResult.combinedOutput.ifBlank {
                            "${packageManager.displayName()} install ${installPlan.packages.joinToString(" ")} failed"
                        }
                    )
                )
            }

            onProgress(Strings.locale_generating.strOr(context))

            installPlan.configureCommand?.let { command ->
                val configureResult = linuxEnvironment.execute(
                    command = listOf("/bin/sh", "-lc", command),
                    workDir = "/",
                    timeout = 120_000,
                )
                if (configureResult.exitCode != 0) {
                    return@withContext LocaleInstallResult.Error(
                        Strings.locale_error_generate_failed.strOr(
                            context,
                            configureResult.combinedOutput.ifBlank { "locale configuration failed" }
                        )
                    )
                }
            }

            onProgress(Strings.locale_verifying.strOr(context))

            // 验证安装
            if (isLocalePackageInstalled(locale)) {
                LocaleInstallResult.Success
            } else {
                LocaleInstallResult.Error(Strings.locale_error_verify_failed.strOr(context))
            }
        } catch (e: Exception) {
            LocaleInstallResult.Error(
                Strings.locale_error_exception.strOr(context, e.message)
            )
        }
    }

    /**
     * 检查是否需要安装语言包
     *
     * @param locale 目标 locale
     * @return 如果是非 C.UTF-8 且语言包未安装，返回 true
     */
    override suspend fun needsLocalePackage(locale: String): Boolean {
        if (locale == "C.UTF-8") return false
        return !isLocalePackageInstalled(locale)
    }
}

private data class LocaleInstallPlan(
    val packages: List<String>,
    val configureCommand: String? = null,
)

private fun buildInstallPlan(
    locale: String,
    packageManager: RootfsPackageManager,
): LocaleInstallPlan? = when (packageManager) {
    RootfsPackageManager.APK -> LocaleInstallPlan(
        packages = listOf("musl-locales", "musl-locales-lang"),
    )
    RootfsPackageManager.APT -> LocaleInstallPlan(
        packages = listOf("locales"),
        configureCommand = buildGlibcLocaleCommand(locale),
    )
    RootfsPackageManager.PACMAN -> LocaleInstallPlan(
        packages = listOf("glibc", "glibc-locales"),
        configureCommand = buildGlibcLocaleCommand(locale),
    )
    RootfsPackageManager.DNF,
    RootfsPackageManager.UNKNOWN -> null
}

private fun buildGlibcLocaleCommand(locale: String): String {
    val localeSource = locale.substringBefore('.')
    return """
        set -e
        if command -v localedef >/dev/null 2>&1; then
          localedef -i '$localeSource' -f UTF-8 '$locale'
        elif command -v locale-gen >/dev/null 2>&1; then
          line='$locale UTF-8'
          if [ -f /etc/locale.gen ]; then
            if grep -Fqx "# ${'$'}line" /etc/locale.gen; then
              sed -i "s|^# ${'$'}line${'$'}|${'$'}line|" /etc/locale.gen
            elif ! grep -Fqx "${'$'}line" /etc/locale.gen; then
              printf '%s\n' "${'$'}line" >> /etc/locale.gen
            fi
          fi
          locale-gen '$locale' || locale-gen
        else
          exit 1
        fi
        if command -v update-locale >/dev/null 2>&1; then
          update-locale LANG='$locale' || true
        fi
    """.trimIndent()
}
