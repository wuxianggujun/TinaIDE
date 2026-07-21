package com.wuxianggujun.tinaide.core.editorlsp

import java.io.File
import java.net.URI
import org.eclipse.lsp4j.FileSystemWatcher

class LspFileWatchPattern private constructor(
    private val basePath: String?,
    val glob: String,
    private val eventMask: Int,
    private val regex: Regex,
) {
    fun matches(filePath: String, event: Int): Boolean {
        if (eventMask and event == 0) return false

        val normalizedPath = normalizePath(filePath)
        val candidate = if (basePath == null) {
            normalizedPath
        } else {
            relativePathOrNull(normalizedPath, basePath) ?: return false
        }
        return regex.matches(candidate)
    }

    override fun toString(): String = if (basePath == null) glob else "$basePath::$glob"

    companion object {
        const val CREATE_EVENT = 1
        const val CHANGE_EVENT = 2
        const val DELETE_EVENT = 4
        const val ALL_EVENTS = CREATE_EVENT or CHANGE_EVENT or DELETE_EVENT

        private val windowsAbsolutePath = Regex("^[A-Za-z]:[/\\\\]")

        fun fromWatcher(
            watcher: FileSystemWatcher,
            workspaceRoot: String?,
        ): LspFileWatchPattern? {
            val protocolPattern = watcher.globPattern ?: return null
            val eventMask = watcher.kind ?: ALL_EVENTS
            if (protocolPattern.isLeft) {
                return create(
                    basePath = workspaceRoot,
                    glob = protocolPattern.left ?: return null,
                    eventMask = eventMask,
                )
            }

            val relativePattern = protocolPattern.right ?: return null
            val baseUri = relativePattern.baseUri
            val baseUriValue = when {
                baseUri.isLeft -> baseUri.left?.uri
                baseUri.isRight -> baseUri.right
                else -> null
            } ?: return null
            val basePath = resolveFilePath(baseUriValue) ?: return null
            return create(
                basePath = basePath,
                glob = relativePattern.pattern,
                eventMask = eventMask,
            )
        }

        fun create(
            basePath: String?,
            glob: String,
            eventMask: Int = ALL_EVENTS,
        ): LspFileWatchPattern? {
            val normalizedGlob = normalizeGlob(glob)
            if (normalizedGlob.isBlank()) return null
            val normalizedEventMask = eventMask and ALL_EVENTS
            if (normalizedEventMask == 0) return null
            val effectiveBasePath = if (isAbsoluteGlob(normalizedGlob)) {
                null
            } else {
                basePath?.let(::normalizePath)
            }
            val regex = runCatching { compileGlob(normalizedGlob) }.getOrNull() ?: return null
            return LspFileWatchPattern(
                basePath = effectiveBasePath,
                glob = normalizedGlob,
                eventMask = normalizedEventMask,
                regex = regex,
            )
        }

        private fun resolveFilePath(uriOrPath: String): String? = runCatching {
            if (windowsAbsolutePath.containsMatchIn(uriOrPath)) {
                return@runCatching File(uriOrPath).absolutePath
            }
            val uri = URI(uriOrPath)
            when {
                uri.scheme == null -> File(uriOrPath).absolutePath
                uri.scheme.equals("file", ignoreCase = true) -> File(uri).absolutePath
                else -> null
            }
        }.getOrNull()

        private fun normalizePath(path: String): String =
            File(path).toPath().toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')

        private fun normalizeGlob(glob: String): String =
            glob.trim().replace('\\', '/').removePrefix("./")

        private fun isAbsoluteGlob(glob: String): Boolean =
            glob.startsWith('/') || windowsAbsolutePath.containsMatchIn(glob)

        private fun relativePathOrNull(path: String, basePath: String): String? {
            val ignoreCase = File.separatorChar == '\\'
            if (path.equals(basePath, ignoreCase = ignoreCase)) return ""
            val prefix = "$basePath/"
            if (!path.startsWith(prefix, ignoreCase = ignoreCase)) return null
            return path.substring(prefix.length)
        }

        private fun compileGlob(glob: String): Regex {
            val regex = buildString {
                append('^')
                appendGlob(glob, this)
                append('$')
            }
            val options = if (File.separatorChar == '\\') {
                setOf(RegexOption.IGNORE_CASE)
            } else {
                emptySet()
            }
            return Regex(regex, options)
        }

        private fun appendGlob(glob: String, output: StringBuilder) {
            var index = 0
            while (index < glob.length) {
                when (val character = glob[index]) {
                    '*' -> {
                        var starEnd = index + 1
                        while (starEnd < glob.length && glob[starEnd] == '*') starEnd++
                        if (starEnd - index >= 2) {
                            if (starEnd < glob.length && glob[starEnd] == '/') {
                                output.append("(?:.*/)?")
                                starEnd++
                            } else {
                                output.append(".*")
                            }
                        } else {
                            output.append("[^/]*")
                        }
                        index = starEnd
                    }

                    '?' -> {
                        output.append("[^/]")
                        index++
                    }

                    '[' -> {
                        val closing = glob.indexOf(']', startIndex = index + 1)
                        if (closing < 0) {
                            output.append("\\[")
                            index++
                        } else {
                            val content = glob.substring(index + 1, closing)
                            output.append('[')
                            if (content.startsWith('!')) {
                                output.append('^')
                                output.append(escapeCharacterClass(content.substring(1)))
                            } else {
                                output.append(escapeCharacterClass(content))
                            }
                            output.append(']')
                            index = closing + 1
                        }
                    }

                    '{' -> {
                        val closing = findClosingBrace(glob, index)
                        if (closing < 0) {
                            output.append("\\{")
                            index++
                        } else {
                            val alternatives = splitAlternatives(glob.substring(index + 1, closing))
                            output.append("(?:")
                            alternatives.forEachIndexed { alternativeIndex, alternative ->
                                if (alternativeIndex > 0) output.append('|')
                                appendGlob(alternative, output)
                            }
                            output.append(')')
                            index = closing + 1
                        }
                    }

                    '.', '^', '$', '+', '(', ')', '|', '\\' -> {
                        output.append('\\').append(character)
                        index++
                    }

                    else -> {
                        output.append(character)
                        index++
                    }
                }
            }
        }

        private fun findClosingBrace(glob: String, openingIndex: Int): Int {
            var depth = 0
            for (index in openingIndex until glob.length) {
                when (glob[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            return -1
        }

        private fun splitAlternatives(content: String): List<String> {
            val alternatives = mutableListOf<String>()
            var depth = 0
            var segmentStart = 0
            content.forEachIndexed { index, character ->
                when (character) {
                    '{' -> depth++
                    '}' -> depth--
                    ',' -> if (depth == 0) {
                        alternatives += content.substring(segmentStart, index)
                        segmentStart = index + 1
                    }
                }
            }
            alternatives += content.substring(segmentStart)
            return alternatives
        }

        private fun escapeCharacterClass(content: String): String = buildString {
            content.forEach { character ->
                if (character == '\\' || character == ']') append('\\')
                append(character)
            }
        }
    }
}
