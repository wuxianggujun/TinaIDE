package com.wuxianggujun.tinaide.editor.session

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class AutoSaveScheduler(
    private val scope: CoroutineScope,
    private val intervalProvider: () -> Long,
    private val action: suspend (DocumentSession) -> Boolean
) {

    private val jobs = ConcurrentHashMap<String, Job>()
    @Synchronized
    fun schedule(session: DocumentSession) {
        val interval = intervalProvider()
        if (interval <= 0L) {
            cancel(session.tabId)
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var nextInterval = interval
                while (true) {
                    delay(nextInterval)
                    if (!action(session)) break
                    nextInterval = intervalProvider()
                    if (nextInterval <= 0L) break
                }
            } finally {
                jobs.remove(session.tabId, currentCoroutineContext().job)
            }
        }
        jobs.put(session.tabId, job)?.cancel()
        job.start()
    }

    @Synchronized
    fun cancel(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
    }

    fun cancel(session: DocumentSession) {
        cancel(session.tabId)
    }

    @Synchronized
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
