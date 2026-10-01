package com.hdlee73.sajeonapp

/** Callback-only queue. All calls and backend callbacks belong to the UI thread. */
internal class ExampleTranslationQueue(
    private val download: ((Boolean) -> Unit) -> Unit,
    private val translate: (String, (String?) -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val now: () -> Long
) {
    private class Job(val callbacks: MutableList<(String?) -> Unit>)
    private val waiting = linkedMapOf<String, Job>()
    private val active = mutableMapOf<String, Job>()
    private val cache = linkedMapOf<String, String>()
    private var ready = false
    private var downloading = false
    private var generation = 0
    private var retryAt = 0L
    private var closed = false

    fun request(text: String, callback: (String?) -> Unit) {
        if (closed) return
        cache[text]?.let { callback(it); return }
        (active[text] ?: waiting[text])?.let { it.callbacks += callback; return }
        val job = Job(mutableListOf(callback))
        if (ready) { start(text, job); return }
        if (!downloading && now() < retryAt) { callback(null); return }
        waiting[text] = job
        if (downloading) return
        downloading = true
        val attempt = ++generation
        schedule(60_000) { completeDownload(attempt, false) }
        try { download { completeDownload(attempt, it) } }
        catch (_: Exception) { completeDownload(attempt, false) }
    }

    private fun completeDownload(attempt: Int, success: Boolean) {
        if (closed || !downloading || attempt != generation) return
        downloading = false
        ready = success
        if (!success) retryAt = now() + 60_000
        val jobs = waiting.toList()
        waiting.clear()
        jobs.forEach { (text, job) ->
            if (success) start(text, job) else job.callbacks.forEach { it(null) }
        }
    }

    private fun start(text: String, job: Job) {
        active[text] = job
        schedule(20_000) { complete(text, job, null) }
        try { translate(text) { complete(text, job, it) } }
        catch (_: Exception) { complete(text, job, null) }
    }

    private fun complete(text: String, job: Job, result: String?) {
        if (closed || active[text] !== job) return
        active.remove(text)
        val korean = result?.trim()?.takeIf { it.any { c -> c in '가'..'힣' } }
        if (korean != null) {
            if (cache.size >= 1000) cache.remove(cache.keys.first())
            cache[text] = korean
        }
        job.callbacks.forEach { it(korean) }
    }

    fun close() {
        closed = true
        generation++
        waiting.clear()
        active.clear()
        cache.clear()
    }
}
