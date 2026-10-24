package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.lsp.Diagnostic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns replacement/cancellation; publishing and tab removal share the host's state lock. */
internal class LspDiagnosticsScheduler(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val jobs = mutableMapOf<String, Job>()

    fun schedule(tabId: String, load: suspend () -> List<Diagnostic>, publish: (List<Diagnostic>) -> Unit) {
        lateinit var job: Job
        job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val result = load()
            synchronized(lock) {
                if (jobs[tabId] === job && job.isActive) publish(result)
            }
        }
        val previous = synchronized(lock) { jobs.put(tabId, job) }
        previous?.cancel()
        job.invokeOnCompletion { synchronized(lock) { if (jobs[tabId] === job) jobs.remove(tabId) } }
        job.start()
    }

    fun remove(tabId: String): Job? = synchronized(lock) { jobs.remove(tabId) }

    fun cancelAll() {
        val removed = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        removed.forEach { it.cancel() }
    }
}
