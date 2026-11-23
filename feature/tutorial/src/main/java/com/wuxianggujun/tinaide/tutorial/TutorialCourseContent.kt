package com.wuxianggujun.tinaide.tutorial

import kotlinx.coroutines.CancellationException

sealed interface TutorialCourseContentState {
    data object Loading : TutorialCourseContentState
    data class Content(val markdown: String, val hints: List<String> = emptyList()) : TutorialCourseContentState
    data object Error : TutorialCourseContentState
}

/** The host provides asset access; course loading and cancellation belong to the tutorial feature. */
suspend fun loadTutorialCourseContent(
    linkTarget: String?,
    loadContent: suspend (String) -> Result<String>,
): TutorialCourseContentState {
    if (linkTarget.isNullOrBlank()) return TutorialCourseContentState.Error
    return try {
        val content = loadContent(linkTarget).getOrThrow()
        val parsed = parseCourseContent(content)
        if (parsed.markdown.isBlank()) TutorialCourseContentState.Error else parsed
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        TutorialCourseContentState.Error
    }
}

private const val HINT_MARKER = "<!-- tutorial-hint -->"
private val fenceOpening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val projectFileMarker = Regex("^<!-- tutorial-project-file: (?:CMakeLists\\.txt|main\\.cpp|pricing\\.h|pricing\\.cpp|vcpkg\\.json) -->$")

/** Hints live after the lesson in the same localized asset; fenced code remains verbatim. */
private fun parseCourseContent(markdown: String): TutorialCourseContentState.Content {
    val sections = mutableListOf(StringBuilder())
    var fence: String? = null
    var removedProjectMarker = false
    for (line in markdown.lineSequence()) {
        val currentFence = fence
        if (currentFence == null && line.trim() == HINT_MARKER) {
            sections.add(StringBuilder())
            continue
        }
        if (currentFence == null && projectFileMarker.matches(line.trim())) {
            removedProjectMarker = true
            continue
        }
        if (currentFence == null) {
            fenceOpening.matchEntire(line)?.let { match ->
                val delimiter = match.groupValues[1]
                if (delimiter.first() != '`' || '`' !in match.groupValues[2]) fence = delimiter
            }
        } else {
            val trimmed = line.trimStart(' ')
            val indent = line.length - trimmed.length
            val closing = trimmed.takeWhile { it == currentFence.first() }
            if (indent <= 3 && closing.length >= currentFence.length && trimmed.drop(closing.length).isBlank()) {
                fence = null
            }
        }
        sections.last().appendLine(line)
    }
    // Keep old assets unchanged when no hint marker is present.
    if (sections.size == 1 && !removedProjectMarker) return TutorialCourseContentState.Content(markdown)
    return TutorialCourseContentState.Content(
        markdown = sections.first().toString().trimEnd(),
        hints = sections.drop(1).map { it.toString().trim() }.filter { it.isNotBlank() },
    )
}
