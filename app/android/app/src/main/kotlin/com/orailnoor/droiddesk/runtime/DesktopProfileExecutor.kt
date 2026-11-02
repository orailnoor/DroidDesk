package com.orailnoor.droiddesk.runtime

import java.util.concurrent.Executor

class DesktopProfileExecutor(
    private val worker: Executor,
    private val dispatch: (() -> Unit) -> Unit,
) {
    private data class Work(val revision: Long, val run: () -> Result<Unit>)
    private val lock = Any()
    private var revision = 0L
    private var pending: Work? = null
    private var scheduled = false
    private val completions = mutableListOf<(Result<Unit>) -> Unit>()

    fun apply(run: () -> Result<Unit>, onComplete: (Result<Unit>) -> Unit) {
        val schedule = synchronized(lock) {
            pending = Work(++revision, run)
            completions += onComplete
            if (scheduled) false else { scheduled = true; true }
        }
        if (schedule) worker.execute { drain() }
    }

    fun cancel() {
        val callbacks = synchronized(lock) {
            revision++
            pending = null
            completions.toList().also { completions.clear() }
        }
        dispatch {
            val cancelled = Result.failure<Unit>(IllegalStateException("Profile application was cancelled before confirmation"))
            callbacks.forEach { it(cancelled) }
        }
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val next = pending
                if (next == null) {
                    scheduled = false
                    null
                } else {
                    pending = null
                    next
                }
            } ?: return
            val outcome = work.run()
            dispatch {
                val callbacks = synchronized(lock) {
                    if (work.revision != revision) emptyList()
                    else completions.toList().also { completions.clear() }
                }
                callbacks.forEach { it(outcome) }
            }
        }
    }
}
