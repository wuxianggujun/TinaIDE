package com.wuxianggujun.tinaide.core.compile

import android.content.Context
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
        val multiFile = lesson in setOf("cpp-functions", "cpp-collections", "cpp-raii", "cmake-practice")
        if (multiFile) {
            val functions = blocks("cpp-functions", "cpp")
            File(directory, "pricing.h").writeText(functions[0], Charsets.UTF_8)
            File(directory, "pricing.cpp").writeText(functions[1], Charsets.UTF_8)
        }
        val mainLesson = if (lesson == "cmake-practice") "cpp-raii" else lesson
        val main = blocks(mainLesson, "cpp")[if (lesson == "cpp-functions") 2 else 0]
        File(directory, "main.cpp").writeText(main, Charsets.UTF_8)
        val cmake = if (lesson in setOf("ide-workflow", "cmake-practice")) {
            blocks(lesson, "cmake").first()
        } else {
            "cmake_minimum_required(VERSION 3.22)\nproject(native_lab LANGUAGES CXX)\n" +
                "add_executable(native_lab main.cpp" + (if (multiFile) " pricing.cpp" else "") + ")\n" +
                "target_compile_features(native_lab PRIVATE cxx_std_17)\n"
        }
        File(directory, "CMakeLists.txt").writeText(cmake, Charsets.UTF_8)
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
        )
    }
}
