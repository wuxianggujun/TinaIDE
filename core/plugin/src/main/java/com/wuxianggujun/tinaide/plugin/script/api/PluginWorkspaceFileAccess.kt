package com.wuxianggujun.tinaide.plugin.script.api

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import timber.log.Timber

internal class PluginWorkspaceFileAccess(
    private val projectRootProvider: () -> String?
) {
    companion object {
        const val DEFAULT_FIND_FILES_LIMIT = 200
        const val MAX_FIND_FILES_LIMIT = 1000
        private const val MAX_FIND_FILES_SCANNED_ENTRIES = 50_000

        private val windowsAbsolutePathPattern = Regex("^[A-Za-z]:/")

        private val skippedFindDirs = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".cxx",
            "build",
            "node_modules",
        )
    }

    fun resolveSafePath(path: String): File? {
        return resolveSafeWorkspacePath(path)?.target?.toFile()
    }

    fun writeUtf8File(path: String, content: String): Boolean {
        val initialPath = resolveSafeWorkspacePath(path) ?: return false
        if (!createDirectoriesNoFollow(initialPath.root, initialPath.target.parent)) return false
        val verifiedPath = resolveSafeWorkspacePath(path) ?: return false
        Files.write(
            verifiedPath.target,
            content.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return true
    }

    fun openFileForRead(path: String): InputStream? {
        val safePath = resolveSafeWorkspacePath(path) ?: return null
        if (!Files.isRegularFile(safePath.target, LinkOption.NOFOLLOW_LINKS)) return null
        val channel = Files.newByteChannel(
            safePath.target,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
        return Channels.newInputStream(channel)
    }

    fun createDirectories(path: String): Boolean {
        val mutationPath = resolveSafeWorkspacePath(path) ?: return false
        return createDirectoriesNoFollow(mutationPath.root, mutationPath.target)
    }

    fun exists(path: String?): Boolean = path
        ?.let(::resolveSafeWorkspacePath)
        ?.let { safePath -> Files.exists(safePath.target, LinkOption.NOFOLLOW_LINKS) }
        ?: false

    fun isDirectory(path: String?): Boolean = path
        ?.let(::resolveSafeWorkspacePath)
        ?.let { safePath -> Files.isDirectory(safePath.target, LinkOption.NOFOLLOW_LINKS) }
        ?: false

    fun listDirectory(path: String?, maxEntries: Int): List<String>? {
        val safePath = path?.let(::resolveSafeWorkspacePath) ?: return null
        if (!Files.isDirectory(safePath.target, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.newDirectoryStream(safePath.target).use { entries ->
            entries.asSequence()
                .map { entry -> entry.fileName.toString() }
                .take(maxEntries.coerceAtLeast(0))
                .toList()
        }
    }

    fun findFiles(pattern: String?, maxResults: Int = DEFAULT_FIND_FILES_LIMIT): List<String> {
        val projectRoot = resolveProjectRoot() ?: return emptyList()
        val normalizedPattern = normalizeGlobPattern(pattern) ?: return emptyList()
        val matcher = globToRegex(normalizedPattern)
        val limit = maxResults.coerceIn(1, MAX_FIND_FILES_LIMIT)
        val results = mutableListOf<String>()
        val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrNull() ?: return emptyList()
        val rootPrefix = canonicalRoot.path + File.separator
        var scannedEntries = 0

        for (file in projectRoot.walkTopDown().onEnter { dir ->
            dir == projectRoot || (
                dir.name !in skippedFindDirs &&
                    !Files.isSymbolicLink(dir.toPath()) &&
                    isWithinRoot(canonicalRoot, rootPrefix, dir)
                )
        }) {
            if (file == projectRoot) continue
            scannedEntries += 1
            if (scannedEntries > MAX_FIND_FILES_SCANNED_ENTRIES) {
                Timber.w("Plugin workspace scan stopped after $MAX_FIND_FILES_SCANNED_ENTRIES entries")
                break
            }
            if (!file.isFile || Files.isSymbolicLink(file.toPath())) continue

            val relativePath = toRelativePath(rootPrefix, file) ?: continue
            if (matcher.matches(relativePath)) {
                results += relativePath
            }
        }

        return results.sorted().take(limit)
    }

    /** Converts a host path/URI into the only path form visible to isolated Lua: workspace-relative. */
    fun toPluginVisiblePath(path: String?): String? {
        val rawPath = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val decodedPath = runCatching {
            if (rawPath.startsWith("file:", ignoreCase = true)) File(URI(rawPath)).path else rawPath
        }.getOrDefault(rawPath)
        val root = resolveProjectRoot()?.let { runCatching { it.canonicalFile }.getOrNull() }
        val target = runCatching { File(decodedPath).canonicalFile }.getOrNull()
        if (root != null && target != null) {
            if (target == root) return "."
            val prefix = root.path + File.separator
            if (target.path.startsWith(prefix)) {
                return target.path.removePrefix(prefix).replace('\\', '/')
            }
        }
        if (!File(decodedPath).isAbsolute && !rawPath.startsWith("file:", ignoreCase = true)) {
            return decodedPath.replace('\\', '/')
        }
        return target?.name?.takeIf { it.isNotBlank() } ?: "<host-path>"
    }

    private fun resolveProjectRoot(): File? {
        val projectRoot = projectRootProvider()?.takeIf { it.isNotBlank() } ?: return null
        val rootFile = File(projectRoot)
        return rootFile.takeIf { it.exists() && it.isDirectory }
    }

    private fun resolveSafeWorkspacePath(path: String): WorkspacePath? {
        val normalizedPath = normalizeRelativePath(path) ?: return null
        val canonicalRoot = resolveProjectRoot()?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return null
        val rootPath = canonicalRoot.toPath()
        val targetPath = rootPath.resolve(normalizedPath).normalize()
        if (!targetPath.startsWith(rootPath) || containsSymbolicLink(rootPath, targetPath)) return null

        val canonicalTarget = runCatching { targetPath.toFile().canonicalFile }.getOrNull() ?: return null
        if (canonicalTarget != canonicalRoot && !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
            return null
        }
        return WorkspacePath(rootPath, targetPath)
    }

    private fun containsSymbolicLink(root: Path, target: Path): Boolean {
        var current = root
        for (segment in root.relativize(target)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun createDirectoriesNoFollow(root: Path, targetDirectory: Path?): Boolean {
        val target = targetDirectory ?: return false
        if (!target.startsWith(root)) return false
        var current = root
        for (segment in root.relativize(target)) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { Files.createDirectory(current) }
                    .onFailure { error ->
                        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) throw error
                    }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
        }
        return true
    }

    private fun normalizeRelativePath(path: String): String? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank()) return null
        if (!isSafeRelativePath(normalized)) return null
        return normalized
    }

    private fun normalizeGlobPattern(pattern: String?): String? {
        val normalized = pattern
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('\\', '/')
            ?: "**/*"
        if (!isSafeRelativePath(normalized)) return null
        return if ('/' in normalized) normalized else "**/$normalized"
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith("/") || windowsAbsolutePathPattern.containsMatchIn(path)) return false
        return path.split('/').none { segment -> segment == ".." }
    }

    private fun isWithinRoot(canonicalRoot: File, rootPrefix: String, file: File): Boolean {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonicalFile == canonicalRoot || canonicalFile.path.startsWith(rootPrefix)
    }

    private fun toRelativePath(rootPrefix: String, file: File): String? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonicalFile.path.startsWith(rootPrefix)) return null
        return canonicalFile.path
            .removePrefix(rootPrefix)
            .replace('\\', '/')
    }

    private fun globToRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when (char) {
                '*' -> {
                    val isDoubleStar = index + 1 < pattern.length && pattern[index + 1] == '*'
                    val isDoubleStarSlash = isDoubleStar &&
                        index + 2 < pattern.length &&
                        pattern[index + 2] == '/'
                    when {
                        isDoubleStarSlash -> {
                            builder.append("(?:.*/)?")
                            index += 3
                        }
                        isDoubleStar -> {
                            builder.append(".*")
                            index += 2
                        }
                        else -> {
                            builder.append("[^/]*")
                            index++
                        }
                    }
                }
                '?' -> {
                    builder.append("[^/]")
                    index++
                }
                '/', '-' -> {
                    builder.append(char)
                    index++
                }
                else -> {
                    if (char in "\\.[]{}()+-^$|") {
                        builder.append('\\')
                    }
                    builder.append(char)
                    index++
                }
            }
        }
        builder.append('$')
        return Regex(builder.toString())
    }

    private data class WorkspacePath(
        val root: Path,
        val target: Path,
    )
}
