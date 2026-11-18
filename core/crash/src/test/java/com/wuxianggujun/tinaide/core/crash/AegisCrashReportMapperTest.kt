package com.wuxianggujun.tinaide.core.crash

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AegisCrashReportMapperTest {

    @Test
    fun javaTombstoneMapsToStructuredCrashReport() {
        val report = AegisCrashReportMapper.build(
            fileName = "tombstone_00001794660981085000_0.19.2__com.wuxianggujun.tinaide.java.xcrash",
            crashLog = JAVA_TOMBSTONE,
            fileModifiedAtMillis = 1_700_000_000_000L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = "device-fingerprint",
        )

        assertEquals("crash", report.typeKey)
        assertEquals(1, report.schemaVersion)
        assertEquals("0.19.2", report.release)
        assertEquals("46c59a2a71-20261114082348", report.buildId)
        assertEquals("production", report.environment)
        assertEquals("2026-11-14T12:56:21.161Z", report.occurredAt)
        assertEquals("device-fingerprint", report.userReference)
        assertTrue(report.attributes.isEmpty())
        assertNotNull(UUID.fromString(report.eventId))

        val exception = requireNotNull(report.exception)
        assertEquals("java.lang.NoClassDefFoundError", exception.type)
        assertEquals("Failed resolution of: Landroidx/tracing/Trace;", exception.message)
        assertTrue(exception.frames.isNotEmpty())
        assertEquals("androidx.test.runner.AndroidJUnitRunner.onCreate", exception.frames.first().function)
        assertEquals("r8-map-id-0a34940141a7ef5aa2d198c086805ffba5a9f0ba4440aec23e184d39935b62b2", exception.frames.first().file)
        assertEquals(26, exception.frames.first().line)
        assertTrue(report.message.startsWith("Java crash in com.wuxianggujun.tinaide"))

        val device = report.breadcrumbs.first { it.category == "device" }
        assertEquals("OnePlus", device.data["brand"])
        assertEquals("PMS110", device.data["model"])
        assertEquals("37", device.data["sdk"])
    }

    @Test
    fun eventIdIsStableAcrossRetries() {
        val first = AegisCrashReportMapper.build(
            fileName = "tombstone_a.java.xcrash",
            crashLog = JAVA_TOMBSTONE,
            fileModifiedAtMillis = 1234L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = null,
        )
        val second = AegisCrashReportMapper.build(
            fileName = "tombstone_a.java.xcrash",
            crashLog = JAVA_TOMBSTONE,
            fileModifiedAtMillis = 1234L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = null,
        )

        assertEquals(first.eventId, second.eventId)
        assertTrue(first.eventId != UUID(0L, 0L).toString())
    }

    @Test
    fun nativeTombstoneMapsSignalAndBacktrace() {
        val report = AegisCrashReportMapper.build(
            fileName = "tombstone_b.native.xcrash",
            crashLog = NATIVE_TOMBSTONE,
            fileModifiedAtMillis = 1_700_000_000_000L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = null,
        )

        val exception = requireNotNull(report.exception)
        assertEquals("SIGSEGV", exception.type)
        assertTrue(exception.message.contains("fault addr 0x0000000000000000"))
        assertEquals(2, exception.frames.size)
        assertEquals("abort", exception.frames[0].function)
        assertEquals("/system/lib64/libc.so", exception.frames[0].file)
        assertEquals("libc.so", exception.frames[0].module)
        assertEquals("Java_com_wuxianggujun_tinaide_Native_run", exception.frames[1].function)
        assertTrue(report.message.startsWith("Native crash in com.wuxianggujun.tinaide: SIGSEGV"))
    }

    @Test
    fun zeroLineNumbersAreDroppedInsteadOfRejected() {
        val tombstone = """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Tombstone maker: 'xCrash 3.1.0'
            Crash type: 'java'
            Crash time: '2026-11-14T12:56:21.161+0000'
            App ID: 'com.wuxianggujun.tinaide'

            java stacktrace:
            java.lang.IllegalStateException: boom
                at android.app.ActivityThread.-${'$'}${'$'}Nest${'$'}mhandleBindApplication(ActivityThread.java:0)
                at java.lang.reflect.Method.invoke(Native Method)
                at com.example.Real.call(Real.kt:42)
            """.trimIndent()

        val report = AegisCrashReportMapper.build(
            fileName = "tombstone_d.java.xcrash",
            crashLog = tombstone,
            fileModifiedAtMillis = 1_700_000_000_000L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = null,
        )

        val frames = requireNotNull(report.exception).frames
        assertEquals(3, frames.size)
        // AegisHub rejects frame.line == 0, so an unknown line must be omitted.
        assertEquals(null, frames[0].line)
        assertEquals("ActivityThread.java", frames[0].file)
        assertEquals(null, frames[1].line)
        assertEquals("Native Method", frames[1].file)
        assertEquals(42, frames[2].line)
    }

    @Test
    fun unparseableLogGetsFallbackFrameAndTimestamp() {
        val report = AegisCrashReportMapper.build(
            fileName = "tombstone_c.native.xcrash",
            crashLog = "no useful header here\nnothing else",
            fileModifiedAtMillis = 1_700_000_000_000L,
            packageName = "com.wuxianggujun.tinaide",
            environment = "production",
            userReference = null,
        )

        assertEquals("2023-11-14T22:13:20Z", report.occurredAt)
        assertEquals("unknown", report.release)
        assertEquals(null, report.buildId)
        val exception = requireNotNull(report.exception)
        assertEquals("Crash", exception.type)
        assertEquals(1, exception.frames.size)
        assertEquals("tombstone_c.native.xcrash", exception.frames.single().file)
    }

    @Test fun legacyTombstoneKeepsItsOwnReleaseWithoutGuessingTheMapping() {
        for (label in listOf("0.18.7", "0.18.7 (code 187)")) {
            val report = AegisCrashReportMapper.build("old.xcrash", "App version: '$label'", 1L, "test", "production", null)
            assertEquals("0.18.7", report.release)
            assertEquals(null, report.buildId)
        }
    }

    @Test fun malformedVersionLabelsNeverGuessTheCurrentReleaseOrMapping() {
        for (label in listOf("", "0.18.7 (code bad)", "0.18.7 (code 187, build )",
            "0.18.7 (code 187, build ../../current)", "x".repeat(65), "0.18.7 (code 187) trailing")) {
            val report = AegisCrashReportMapper.build("old.xcrash", "App version: '$label'", 1L, "test", "production", null)
            assertEquals(label, "unknown", report.release)
            assertEquals(label, null, report.buildId)
        }
    }

    private companion object {
        const val JAVA_TOMBSTONE = """*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
Tombstone maker: 'xCrash 3.1.0'
Crash type: 'java'
Start time: '2026-11-14T20:56:21.085+0800'
Crash time: '2026-11-14T20:56:21.161+0800'
App ID: 'com.wuxianggujun.tinaide'
App version: '0.19.2 (code 19032, build 46c59a2a71-20261114082348)'
Rooted: 'No'
API level: '37'
OS version: '17'
ABI list: 'arm64-v8a'
Manufacturer: 'OnePlus'
Brand: 'OnePlus'
Model: 'PMS110'
Build fingerprint: 'OnePlus/PMS110/OP64F1L1:17/CP2A.260605.016/C.147dc4b_1201fc3_11f9c73:user/release-keys'
pid: 21197, tid: 21197, name: main  >>> com.wuxianggujun.tinaide <<<

java stacktrace:
java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;
	at androidx.test.runner.AndroidJUnitRunner.onCreate(r8-map-id-0a34940141a7ef5aa2d198c086805ffba5a9f0ba4440aec23e184d39935b62b2:26)
	at android.app.ActivityThread.handleBindApplication(ActivityThread.java:9165)
	at android.os.Looper.loop(Looper.java:499)
Caused by: java.lang.ClassNotFoundException: androidx.tracing.Trace
	... 12 more

logcat:
11-14 20:56:21.161 21197 21197 E AndroidRuntime: FATAL EXCEPTION: main
"""

        const val NATIVE_TOMBSTONE = """*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
Tombstone maker: 'xCrash 3.1.0'
Crash type: 'native'
Crash time: '2026-11-14T20:56:21.161+0800'
pid: 21197, tid: 21198, name: main  >>> com.wuxianggujun.tinaide <<<

signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
backtrace:
    #00 pc 0000000000001234  /system/lib64/libc.so (abort+164)
    #01 pc 0000000000005678  /data/app/~~xx==/lib/arm64/libtina.so (Java_com_wuxianggujun_tinaide_Native_run+312)
"""
    }
}
