package com.wuxianggujun.tinaide.core.compile

import android.content.Context
import com.wuxianggujun.tinaide.tutorial.project.TutorialProjectBlueprints
import java.io.File

/** Read shipped lessons and their hints so the device fixtures cannot silently drift. */
internal class CourseLessonAssets(private val context: Context, private val language: String) {
    init {
        require(language in setOf("zh", "en"))
    }

    fun blocks(lesson: String, tag: String): List<String> {
        require(lesson in LESSONS)
        val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
        val markdown = context.assets.open("$prefix/$lesson.md")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Regex("(?s)```$tag\\r?\\n(.*?)\\r?\\n```").findAll(markdown)
            .map { it.groupValues[1] }.toList()
    }

    fun writeProject(directory: File, lesson: String) {
        require(lesson in LESSONS)
        val prefix = if (language == "en") "tutorial-course/en" else "tutorial-course"
        val blueprint = TutorialProjectBlueprints.load(lesson.replace('-', '_')) { sourceLesson ->
            context.assets.open("$prefix/$sourceLesson.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        blueprint.files.forEach { (name, contents) -> File(directory, name).writeText(contents, Charsets.UTF_8) }
    }

    fun basicsWithInputAndDiscount(): String {
        val cpp = blocks("cpp-basics", "cpp")
        check(cpp.size == 3) { "Update the assessment fixture when the lesson snippets change" }
        val initial = cpp[0]
        check(initial.contains("const int count = 3;") && initial.contains("    std::cout"))
        return initial.replace("const int count = 3;", cpp[1])
            .replace("    std::cout", cpp[2].prependIndent("    ") + "\n    std::cout")
    }

    private companion object {
        val LESSONS = setOf(
            "ide-workflow",
            "cpp-basics",
            "cpp-functions",
            "cpp-collections",
            "cpp-raii",
            "cmake-practice",
            "ai-practice",
        )
    }
}
