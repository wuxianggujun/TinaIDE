package com.wuxianggujun.tinaide.ui.compose.editor

import android.app.Instrumentation
import android.os.SystemClock
import android.view.Choreographer
import com.wuxianggujun.tinaide.core.editorview.EditorRenderPerformanceSnapshot
import com.wuxianggujun.tinaide.core.editorview.EditorState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Advance only after the previous scroll was drawn; Espresso idling is not part of the sample. */
internal fun sampleEditorScroll(
    instrumentation: Instrumentation,
    state: EditorState,
    readSnapshot: () -> EditorRenderPerformanceSnapshot,
    count: Int,
    timeoutMs: Long = 20_000,
): EditorScrollSamples {
    val completed = CountDownLatch(1)
    val samples = mutableListOf<Long>()
    val completedCount = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    lateinit var before: EditorRenderPerformanceSnapshot
    lateinit var after: EditorRenderPerformanceSnapshot
    lateinit var choreographer: Choreographer
    lateinit var callback: Choreographer.FrameCallback
    val started = SystemClock.elapsedRealtime()
    instrumentation.runOnMainSync {
        before = readSnapshot()
        var previousFrames = before.totalRenderedFrames
        choreographer = Choreographer.getInstance()
        callback = Choreographer.FrameCallback {
            try {
                val current = readSnapshot()
                if (current.totalRenderedFrames > previousFrames) {
                    samples += current.lastRenderDurationMs
                    previousFrames = current.totalRenderedFrames
                    completedCount.set(samples.size)
                    if (samples.size == count) {
                        after = current
                        completed.countDown()
                    } else {
                        state.scrollBy(if (samples.size < count / 2) 9f else -9f)
                    }
                }
                if (completed.count > 0) choreographer.postFrameCallback(callback)
            } catch (error: Throwable) {
                failure.set(error)
                completed.countDown()
            }
        }
        state.scrollBy(9f)
        choreographer.postFrameCallback(callback)
    }
    try {
        check(completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Scroll sampling timed out: ${completedCount.get()}/$count drawn frames"
        }
        failure.get()?.let { throw it }
        return EditorScrollSamples(before, after, samples.toList(), SystemClock.elapsedRealtime() - started)
    } finally {
        instrumentation.runOnMainSync { choreographer.removeFrameCallback(callback) }
    }
}

internal data class EditorScrollSamples(
    val before: EditorRenderPerformanceSnapshot,
    val after: EditorRenderPerformanceSnapshot,
    val renderMs: List<Long>,
    val elapsedMs: Long,
)
