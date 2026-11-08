package com.wuxianggujun.tinaide.core.compile.cmake

import com.wuxianggujun.tinaide.project.VcpkgProjectSettings
import com.wuxianggujun.tinaide.core.i18n.Strings
import com.wuxianggujun.tinaide.core.packages.VcpkgException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object VcpkgCMakeIntegration {
    private val nativeToolchainKeys = setOf(
        "CMAKE_SYSTEM_VERSION", "ANDROID_ABI", "CMAKE_SYSROOT", "CMAKE_AR", "CMAKE_RANLIB",
        "CMAKE_EXE_LINKER_FLAGS", "CMAKE_SHARED_LINKER_FLAGS", "CMAKE_SIZEOF_VOID_P", "CMAKE_TRY_COMPILE_TARGET_TYPE",
    ) + listOf("C", "CXX").flatMap { language ->
        listOf("COMPILER", "COMPILER_ARG1", "COMPILER_TARGET", "FLAGS", "ABI_COMPILED", "COMPILER_WORKS",
            "COMPILER_ABI", "BYTE_ORDER", "SIZEOF_DATA_PTR", "LIBRARY_ARCHITECTURE").map { "CMAKE_${language}_$it" }
    }

    data class Plan(
        val arguments: List<String>,
        val installCommand: List<String>,
        val environment: Map<String, String>,
    )

    fun prepare(
        projectDir: File,
        buildDir: File,
        arguments: List<String>,
        androidAbi: String,
        nativeCMakeArguments: List<String>,
    ): Plan? {
        val config = VcpkgProjectSettings.from(arguments)
        if (!config.enabled) return null
        if (config.mode !in setOf(VcpkgProjectSettings.MANIFEST, VcpkgProjectSettings.PREBUILT)) {
            throw VcpkgException(Strings.vcpkg_invalid_mode)
        }
        if (androidAbi !in setOf("arm64-v8a", "x86_64")) throw VcpkgException(Strings.vcpkg_invalid_cli)
        if (config.root.isBlank()) throw VcpkgException(Strings.vcpkg_root_required)
        val root = resolvePath(projectDir, config.root)
        val toolchain = File(root, "scripts/buildsystems/vcpkg.cmake")
        if (!toolchain.isFile) throw VcpkgException(Strings.vcpkg_toolchain_missing)
        val manifestMode = config.mode == VcpkgProjectSettings.MANIFEST
        val installed = if (config.installedDir.isNotBlank()) resolvePath(projectDir, config.installedDir) else {
            if (manifestMode) File(projectDir, "vcpkg_installed") else File(root, "installed")
        }.canonicalFile
        val triplet = config.targetTriplet(androidAbi)
        val passthrough = VcpkgProjectSettings.stripManagedArguments(arguments)
        if (passthrough.any { argument ->
            val key = argument.trim().removePrefix("-D").substringBefore('=').substringBefore(':')
            key == "CMAKE_TOOLCHAIN_FILE" || key.startsWith("VCPKG_") || key == "TINA_VCPKG_INTEGRATION"
        }) throw VcpkgException(Strings.vcpkg_conflicting_arguments)
        val integrationDir = File(buildDir, "tina-vcpkg").canonicalFile
        if (!integrationDir.isDirectory && !integrationDir.mkdirs()) throw VcpkgException(Strings.vcpkg_write_failed)
        val nativeToolchain = File(integrationDir, "native-toolchain.cmake")
        // Ports choose their own generator, dependencies and build type. Only share the native compiler/sysroot.
        val nativeSettings = nativeCMakeArguments.filter {
            it.startsWith("-D") && it.removePrefix("-D").substringBefore('=').substringBefore(':') in nativeToolchainKeys
        }
        writeIfChanged(nativeToolchain, buildString {
            appendLine("# Generated from TinaIDE's active native toolchain and sysroot.")
            appendLine("set(CMAKE_SYSTEM_NAME Linux)")
            appendLine("set(ANDROID TRUE)")
            appendLine("set(CMAKE_SYSTEM_PROCESSOR ${quote(if (androidAbi == "arm64-v8a") "aarch64" else "x86_64")})")
            nativeSettings.forEach { argument ->
                val key = argument.removePrefix("-D").substringBefore('=').substringBefore(':')
                if (!key.matches(Regex("[A-Za-z0-9_]+"))) throw VcpkgException(Strings.vcpkg_conflicting_arguments)
                val type = if (key in setOf("CMAKE_C_COMPILER", "CMAKE_CXX_COMPILER", "CMAKE_AR", "CMAKE_RANLIB")) "FILEPATH" else "STRING"
                appendLine("set($key ${quote(argument.substringAfter('='))} CACHE $type \"\" FORCE)")
            }
        })
        val installCommand = if (manifestMode) {
            if (!File(projectDir, "vcpkg.json").isFile) throw VcpkgException(Strings.vcpkg_manifest_required)
            val executable = File(root, "vcpkg")
            validateExecutable(executable, androidAbi)
            writeIfChanged(File(integrationDir, "$triplet.cmake"), buildString {
                appendLine("set(VCPKG_TARGET_ARCHITECTURE ${if (androidAbi == "arm64-v8a") "arm64" else "x64"})")
                appendLine("set(VCPKG_CMAKE_SYSTEM_NAME Android)")
                appendLine("set(VCPKG_CRT_LINKAGE static)")
                appendLine("set(VCPKG_LIBRARY_LINKAGE static)")
                appendLine("set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE ${quote(nativeToolchain.absolutePath)})")
                // Per-command TMPDIR and launcher state must not invalidate every cached package ABI.
                appendLine("set(VCPKG_ENV_PASSTHROUGH_UNTRACKED PATH LD_LIBRARY_PATH LD_PRELOAD TMPDIR HOME)")
            })
            listOf(
                executable.absolutePath, "install", "--triplet=$triplet", "--host-triplet=$triplet",
                "--x-manifest-root=${projectDir.canonicalPath}", "--x-install-root=${installed.absolutePath}",
                "--overlay-triplets=${integrationDir.absolutePath}", "--disable-metrics",
            )
        } else {
            if (!File(installed, triplet).isDirectory) throw VcpkgException(Strings.vcpkg_prebuilt_missing)
            emptyList()
        }
        return Plan(
            arguments = listOf(
                "-DTINA_VCPKG_INTEGRATION=ON",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
                "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=${nativeToolchain.absolutePath}",
                "-DVCPKG_TARGET_TRIPLET=$triplet",
                "-DVCPKG_HOST_TRIPLET=$triplet",
                "-DVCPKG_INSTALLED_DIR=${installed.absolutePath}",
                "-DVCPKG_MANIFEST_MODE=${if (manifestMode) "ON" else "OFF"}",
                // The host runs the CLI through linker64/tina-exec before configuring.
                "-DVCPKG_MANIFEST_INSTALL=OFF",
            ),
            installCommand = installCommand,
            environment = mapOf(
                "VCPKG_ROOT" to root.absolutePath,
                "VCPKG_FORCE_SYSTEM_BINARIES" to "1",
                "VCPKG_DISABLE_METRICS" to "1",
            ),
        )
    }

    private fun resolvePath(projectDir: File, value: String): File {
        if (value.any { it == '\u0000' || it == '\n' || it == '\r' || it == ';' }) throw VcpkgException(Strings.vcpkg_invalid_path)
        val file = File(value)
        return (if (file.isAbsolute) file else File(projectDir, value)).canonicalFile
    }

    private fun validateExecutable(file: File, androidAbi: String) {
        if (!file.isFile) throw VcpkgException(Strings.vcpkg_invalid_cli)
        val header = ByteArray(20)
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < header.size) {
                val count = stream.read(header, offset, header.size - offset)
                if (count < 0) throw VcpkgException(Strings.vcpkg_invalid_cli)
                offset += count
            }
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) ||
            header[4] != 2.toByte() || header[5] != 1.toByte() ||
            machine != if (androidAbi == "arm64-v8a") 183 else 62
        ) throw VcpkgException(Strings.vcpkg_invalid_cli)
    }

    private fun quote(value: String): String {
        var equals = ""
        while ("]$equals]" in value) equals += "="
        return "[$equals[$value]$equals]"
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && file.readText(Charsets.UTF_8) == content) return
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temporary.writeText(content, Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
