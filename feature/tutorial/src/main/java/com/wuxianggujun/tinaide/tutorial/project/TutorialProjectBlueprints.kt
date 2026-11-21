package com.wuxianggujun.tinaide.tutorial.project

/** Complete starter files come from the shipped lessons, not a second copy of their source code. */
data class TutorialProjectBlueprint(
    val stepId: String,
    val directoryName: String,
    val files: Map<String, String>,
    val useVcpkg: Boolean = false,
    val isSdl3: Boolean = false,
)

object TutorialProjectBlueprints {
    const val RUN_TARGET = "native_lab"

    private data class LessonPlan(
        val lesson: String,
        val directoryName: String,
        val mainLesson: String = lesson,
        val cmakeLesson: String = "cpp-functions",
        val includePricing: Boolean = true,
        val useVcpkg: Boolean = false,
        val isSdl3: Boolean = false,
    )

    private val plans = linkedMapOf(
        "ide_workflow" to LessonPlan("ide-workflow", "tina_course_01_ide", cmakeLesson = "ide-workflow", includePricing = false),
        "cpp_basics" to LessonPlan("cpp-basics", "tina_course_02_basics", cmakeLesson = "ide-workflow", includePricing = false),
        "cpp_functions" to LessonPlan("cpp-functions", "tina_course_03_functions"),
        "cpp_collections" to LessonPlan("cpp-collections", "tina_course_04_collections"),
        "cpp_raii" to LessonPlan("cpp-raii", "tina_course_05_raii"),
        "cmake_practice" to LessonPlan("cmake-practice", "tina_course_06_cmake", mainLesson = "cpp-raii", cmakeLesson = "cmake-practice"),
        "vcpkg_guide" to LessonPlan("vcpkg-practice", "tina_course_07_vcpkg", mainLesson = "cpp-raii", cmakeLesson = "vcpkg-practice", useVcpkg = true),
        "graphics_sdl3" to LessonPlan("graphics-sdl3", "tina_course_08_sdl3", cmakeLesson = "graphics-sdl3", includePricing = false, isSdl3 = true),
        "ai_practice" to LessonPlan("ai-practice", "tina_course_09_ai", cmakeLesson = "ai-practice"),
    )

    val stepIds: Set<String> get() = plans.keys

    fun supports(stepId: String): Boolean = stepId in plans

    fun load(stepId: String, readLesson: (String) -> String): TutorialProjectBlueprint {
        val plan = requireNotNull(plans[stepId]) { "Unknown course project: $stepId" }
        val loadedFiles = mutableMapOf<String, Map<String, String>>()
        fun source(lesson: String, fileName: String): String =
            requireNotNull(loadedFiles.getOrPut(lesson) { extractProjectFiles(readLesson(lesson)) }[fileName]) {
                "Course starter is missing $lesson/$fileName"
            }

        val files = linkedMapOf(
            "CMakeLists.txt" to source(plan.cmakeLesson, "CMakeLists.txt"),
            "main.cpp" to source(plan.mainLesson, "main.cpp"),
        )
        if (plan.includePricing) {
            files["pricing.h"] = source("cpp-functions", "pricing.h")
            files["pricing.cpp"] = source("cpp-functions", "pricing.cpp")
        }
        return TutorialProjectBlueprint(stepId, plan.directoryName, files, plan.useVcpkg, plan.isSdl3)
    }

    /** Named fences keep added examples and progressive hints from changing the selected starter. */
    internal fun extractProjectFiles(markdown: String): Map<String, String> {
        val files = linkedMapOf<String, String>()
        PROJECT_FILE_BLOCK.findAll(markdown).forEach { match ->
            val name = match.groupValues[1]
            val contents = match.groupValues[2].trimEnd() + "\n"
            require(contents.isNotBlank()) { "Empty course starter file: $name" }
            require(files.put(name, contents) == null) { "Duplicate course starter file: $name" }
        }
        return files
    }

    private val PROJECT_FILE_BLOCK = Regex(
        "(?m)^<!-- tutorial-project-file: (CMakeLists\\.txt|main\\.cpp|pricing\\.h|pricing\\.cpp) -->[ \\t]*\\r?\\n" +
            "(?:[ \\t]*\\r?\\n)*```(?:cpp|cmake)\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*\\r?$",
    )
}
