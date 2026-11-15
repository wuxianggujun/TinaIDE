package com.wuxianggujun.tinaide.core.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogcatCrashBufferTest {
    private val packageName = "com.wuxianggujun.tinaide"

    @Test
    fun `filter keeps only crash blocks that belong to the app`() {
        val raw = """
            --------- beginning of crash
            11-15 10:00:00.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main
            Process: com.other.app, PID: 1000
            java.lang.RuntimeException: other app failed
                at com.other.app.Main.onCreate(Main.java:1)
            11-15 10:01:00.000  2000  2000 E AndroidRuntime: FATAL EXCEPTION: main
            Process: $packageName, PID: 2000
            java.lang.IllegalStateException: ours
                at $packageName.MainActivity.onCreate(MainActivity.kt:42)
        """.trimIndent()

        val filtered = LogcatCrashBuffer.filter(raw, packageName)

        assertThat(filtered).contains("IllegalStateException")
        assertThat(filtered).contains("MainActivity.kt:42")
        assertThat(filtered).doesNotContain("com.other.app")
        assertThat(filtered).doesNotContain("RuntimeException: other app failed")
    }

    @Test
    fun `filter returns empty when no block mentions the app`() {
        val raw = """
            --------- beginning of crash
            11-15 10:00:00.000  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main
            Process: com.other.app, PID: 1000
            java.lang.RuntimeException: other
        """.trimIndent()

        assertThat(LogcatCrashBuffer.filter(raw, packageName)).isEmpty()
    }

    @Test
    fun `filter keeps the tail when the selected blocks exceed the limit`() {
        val marker = "TAIL-MARKER"
        val raw = buildString {
            append("--------- beginning of crash\n")
            append("11-15 10:00:00.000  2000  2000 E AndroidRuntime: FATAL EXCEPTION: main\n")
            append("Process: $packageName, PID: 2000\n")
            repeat(200) { append("filler line $it\n") }
            append(marker).append('\n')
        }

        val filtered = LogcatCrashBuffer.filter(raw, packageName, maxBytes = 256)

        assertThat(filtered).contains(marker)
        assertThat(filtered.toByteArray(Charsets.UTF_8).size).isAtMost(260)
    }
}
