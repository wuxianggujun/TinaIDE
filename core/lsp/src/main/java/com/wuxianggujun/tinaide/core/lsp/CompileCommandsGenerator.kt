package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.core.lang.CxxFileSupport
import com.wuxianggujun.tinaide.core.util.ClangResourceDirLocator
import com.wuxianggujun.tinaide.core.util.RootfsTargetDetector
import com.wuxianggujun.tinaide.core.util.ToolchainBinaryLocator
import com.wuxianggujun.tinaide.project.ProjectCppStandardResolver
import java.io.File
import timber.log.Timber

/**
 * 负责生成 clangd 需要的 compile_commands.json。
 * 脱离旧的 LspEditorManager，便于 Native LSP 与工具链复用。
 */
object CompileCommandsGenerator {

    private const val TAG = "CompileCommandsGen"

    // 保留作为 fallback，但优先使用自动检测
    private const val DEFAULT_TARGET_ANDROID = "aarch64-linux-android28"

    enum class BuildVariant(val dirName: String) {
        Debug("debug"),
        Release("release")
    }

    fun getCompileCommandsFile(
        projectPath: String,
        variant: BuildVariant = BuildVariant.Debug
    ): File {
        val buildDir = File(File(projectPath, "build"), variant.dirName)
        if (!buildDir.exists()) {
            buildDir.mkdirs()
        }
        return File(buildDir, "compile_commands.json")
    }

    fun generate(
        projectPath: String,
        sysrootDir: File?,
        sourceFiles: List<String>,
        includeDirs: List<String>,
        defines: List<String> = emptyList(),
        isCxx: Boolean = true,
        target: String? = null,
        variant: BuildVariant = BuildVariant.Debug,
        cppStandardFlag: String = ProjectCppStandardResolver.DEFAULT_FLAG,
        extraCFlags: List<String> = emptyList(),
        extraCppFlags: List<String> = emptyList(),
        clangPathOverride: String? = null,
        clangppPathOverride: String? = null,
        resourceDirOverride: File? = null,
        outputFileOverride: File? = null,
    ): File {
        val canonicalProjectPath = runCatching { File(projectPath).canonicalPath }.getOrDefault(projectPath)
        val compileCommandsFile = outputFileOverride ?: getCompileCommandsFile(projectPath, variant)
        val compileDir = compileCommandsFile.parentFile
            ?: throw IllegalStateException("compile_commands.json must reside inside a directory")
        if (!compileDir.exists()) {
            compileDir.mkdirs()
        }
        val objDir = File(compileDir, "obj")
        if (!objDir.exists()) {
            objDir.mkdirs()
        }

        // 自动检测 rootfs 类型以确定 target 三元组
        val detection = sysrootDir?.let { RootfsTargetDetector.detect(it) }
        val resolvedTarget = target ?: detection?.target ?: DEFAULT_TARGET_ANDROID
        val isGnuRootfs = detection?.type == RootfsTargetDetector.RootfsType.GNU_LINUX
        Timber.tag(TAG).i("Rootfs type: ${detection?.type}, target: $resolvedTarget")

        val sysrootPath = sysrootDir?.absolutePath
        val resourceDir = resourceDirOverride ?: sysrootDir?.let { ClangResourceDirLocator.find(it) }
        val resourceIncludeDir = resourceDir?.let { File(it, "include") }?.takeIf { it.exists() }
        if (sysrootDir != null && resourceDir == null) {
            Timber.tag(TAG).w("Clang resource dir missing under ${sysrootDir.absolutePath}")
        }

        val clang = clangPathOverride ?: (sysrootDir?.let { ToolchainBinaryLocator.findClangExecutable(it) } ?: "clang")
        val clangpp = clangppPathOverride ?: (sysrootDir?.let { ToolchainBinaryLocator.findClangPlusPlusExecutable(it) } ?: "clang++")
        val tripleBase = detection?.tripleBase ?: deriveTripleBase(resolvedTarget)
        val apiLevel = deriveApiLevel(resolvedTarget)
        val resolvedIncludeDirs = (includeDirs + projectPath).distinct()
        val normalizedExtraCFlags = extraCFlags.map { it.trim() }.filter { it.isNotBlank() }
        val normalizedExtraCppFlags = extraCppFlags.map { it.trim() }.filter { it.isNotBlank() }

        val commands = sourceFiles.map { sourceFile ->
            val canonicalSourceFile = runCatching { File(sourceFile).canonicalPath }.getOrDefault(sourceFile)
            val ext = sourceFile.substringAfterLast('.', "").lowercase()
            val inferredIsCxx = when (ext) {
                "c" -> false
                "m" -> false
                "mm" -> true
                in CxxFileSupport.cxxSourceExtensions -> true
                else -> isCxx
            }
            val languageFlag = when (ext) {
                "m" -> "objective-c"
                "mm" -> "objective-c++"
                else -> null
            }
            val args = mutableListOf<String>().apply {
                add(if (inferredIsCxx) clangpp else clang)
                add("-target")
                add(resolvedTarget)
                languageFlag?.let { lang ->
                    add("-x")
                    add(lang)
                }
                sysrootPath?.let { sysroot ->
                    add("--sysroot=$sysroot")
                }
                resourceDir?.let { dir ->
                    add("-resource-dir")
                    add(dir.absolutePath)
                }
                // 关键：C++ 标准库头文件必须在所有其他系统头文件之前
                // 这样 libc++ 的 <ctype.h> 包装器才能被正确找到
                if (inferredIsCxx) {
                    sysrootPath?.let { sysroot ->
                        if (isGnuRootfs) {
                            // GNU/Linux: 使用 libstdc++ 头文件路径
                            addGnuCxxIncludes(this, sysroot)
                        } else {
                            // Android NDK: 使用 libc++ 头文件路径
                            add("-isystem")
                            add("$sysroot/usr/include/c++/v1")
                        }
                    }
                }
                // clang 内置头文件（stdarg.h 等）
                resourceIncludeDir?.let { include ->
                    add("-isystem")
                    add(include.absolutePath)
                }
                addAll(if (inferredIsCxx) normalizedExtraCppFlags else normalizedExtraCFlags)
                if (inferredIsCxx) {
                    // Keep the resolved project/run-config standard as the final effective -std.
                    add("-std=$cppStandardFlag")
                }
                add("-c")
                add(canonicalSourceFile)
                if (!isGnuRootfs) {
                    add("-DANDROID")
                    add("-D__ANDROID__")
                }
                sysrootPath?.let { sysroot ->
                    if (!isGnuRootfs) {
                        add("-D__ANDROID_API__=$apiLevel")
                    }
                    // C 标准库和架构特定头文件放在最后
                    add("-isystem")
                    add("$sysroot/usr/include")
                    if (tripleBase.isNotEmpty()) {
                        add("-isystem")
                        add("$sysroot/usr/include/$tripleBase")
                    }
                }
                resolvedIncludeDirs.forEach { dir ->
                    add("-I$dir")
                }
                defines.forEach { define ->
                    add("-D$define")
                }
                val objFile = File(objDir, File(sourceFile).nameWithoutExtension + ".o")
                add("-o")
                add(objFile.absolutePath)
            }
            mapOf(
                "directory" to canonicalProjectPath,
                "file" to canonicalSourceFile,
                "arguments" to args
            )
        }

        val json = buildString {
            append("[\n")
            commands.forEachIndexed { index, cmd ->
                val directory = cmd["directory"] as String
                val file = cmd["file"] as String
                append("  {\n")
                append("    \"directory\": \"${escape(directory)}\",\n")
                append("    \"file\": \"${escape(file)}\",\n")
                append("    \"arguments\": [\n")
                @Suppress("UNCHECKED_CAST")
                val args = cmd["arguments"] as List<String>
                args.forEachIndexed { argIndex, arg ->
                    append("      \"${escape(arg)}\"")
                    if (argIndex < args.size - 1) append(",")
                    append("\n")
                }
                append("    ]\n")
                append("  }")
                if (index < commands.size - 1) append(",")
                append("\n")
            }
            append("]\n")
        }

        compileCommandsFile.writeText(json)
        runCatching { Timber.tag(TAG).i("Generated compile_commands.json at: ${compileCommandsFile.absolutePath}") }
        return compileCommandsFile
    }

    private fun deriveTripleBase(target: String): String {
        if (target.isEmpty()) return ""
        return target.trimEnd { it.isDigit() }
    }

    private fun deriveApiLevel(target: String): String {
        val digits = target.takeLastWhile { it.isDigit() }
        return digits.ifEmpty { "24" }
    }

    /**
     * 添加 GNU/Linux libstdc++ 头文件路径。
     *
     * Ubuntu/Debian 的 libstdc++ 头文件通常位于：
     * - /usr/include/c++/{version}
     * - /usr/include/{arch}/c++/{version}
     */
    private fun addGnuCxxIncludes(args: MutableList<String>, sysroot: String) {
        val cxxIncludeBase = File(sysroot, "usr/include/c++")
        if (!cxxIncludeBase.isDirectory) return

        // 查找最高版本的 libstdc++ 头文件目录
        val versionDir = cxxIncludeBase.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)*")) }
            ?.maxWithOrNull { a, b -> compareVersionNames(a.name, b.name) }
            ?: return

        args.add("-isystem")
        args.add(versionDir.absolutePath)

        // 架构特定的 bits 目录（如 aarch64-linux-gnu）
        versionDir.listFiles()?.forEach { subDir ->
            if (subDir.isDirectory && subDir.name.contains("linux")) {
                args.add("-isystem")
                args.add(subDir.absolutePath)
            }
        }
    }

    private fun compareVersionNames(a: String, b: String): Int {
        val aTokens = a.split('.')
        val bTokens = b.split('.')
        val maxSize = maxOf(aTokens.size, bTokens.size)
        for (i in 0 until maxSize) {
            val ai = aTokens.getOrNull(i)?.toIntOrNull() ?: -1
            val bi = bTokens.getOrNull(i)?.toIntOrNull() ?: -1
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}
