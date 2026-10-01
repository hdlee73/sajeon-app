package com.hdlee73.sajeonapp

import org.junit.Assert.*
import org.junit.Test

class ExampleTranslationQueueTest {
    private class Harness {
        var clock = 0L
        val downloads = mutableListOf<(Boolean) -> Unit>()
        val translations = mutableListOf<Pair<String, (String?) -> Unit>>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val queue = ExampleTranslationQueue(
            { downloads += it }, { text, done -> translations += text to done },
            { delay, action -> timers += (clock + delay) to action }, { clock })
        fun advance(ms: Long) {
            val target = clock + ms
            while (timers.any { it.first <= target }) {
                val timer = timers.filter { it.first <= target }.minBy { it.first }
                timers.remove(timer)
                clock = timer.first
                timer.second()
            }
            clock = target
        }
    }

    @Test fun slowDownloadDoesNotBlockAnotherRequestAndDownloadsOnlyOnce() {
        val h = Harness()
        val results = mutableListOf<String?>()
        h.queue.request("The dog ran home.") { results += it }
        h.queue.request("The bird flew away.") { results += it }
        h.queue.request("The dog ran home.") { results += it }
        assertEquals(1, h.downloads.size)
        assertTrue(results.isEmpty())
        assertTrue(h.translations.isEmpty())
        h.downloads.single()(true)
        assertEquals(2, h.translations.size)
        h.translations[0].second("개가 집으로 달려갔다.")
        h.translations[1].second("새가 날아갔다.")
        assertEquals(3, results.size)
        h.queue.request("The dog ran home.") { assertEquals("개가 집으로 달려갔다.", it) }
        assertEquals(2, h.translations.size)
    }

    @Test fun downloadTimeoutFinishesWaitersAndStaleCompletionCannotCompleteRetry() {
        val h = Harness()
        val results = mutableListOf<String?>()
        h.queue.request("One sentence.") { results += it }
        h.advance(60_000)
        assertEquals(listOf<String?>(null), results)
        h.queue.request("Another sentence.") { results += it }
        assertEquals(1, h.downloads.size)
        h.advance(60_000)
        h.queue.request("A fresh sentence.") { results += it }
        assertEquals(2, h.downloads.size)
        h.downloads[0](true)
        assertTrue(h.translations.isEmpty())
        h.downloads[1](true)
        assertEquals("A fresh sentence.", h.translations.single().first)
    }

    @Test fun failedModelDoesNotRestartOnEverySearchAndCanRetryLater() {
        val h = Harness()
        var calls = 0
        h.queue.request("First.") { assertNull(it); calls++ }
        h.downloads.single()(false)
        repeat(5) { h.queue.request("Other $it.") { assertNull(it); calls++ } }
        assertEquals(6, calls)
        assertEquals(1, h.downloads.size)
        h.advance(60_000)
        h.queue.request("Retry.") { calls++ }
        assertEquals(2, h.downloads.size)
    }

    @Test fun translationTimeoutAndLateReplyInvokeCallbackOnlyOnce() {
        val h = Harness()
        val results = mutableListOf<String?>()
        h.queue.request("Some example.") { results += it }
        h.downloads.single()(true)
        h.advance(20_000)
        h.translations.single().second("예문입니다.")
        assertEquals(listOf<String?>(null), results)
        h.queue.request("Some example.") { results += it }
        assertEquals(2, h.translations.size)
        h.translations.last().second("예문입니다.")
        assertEquals(listOf(null, "예문입니다."), results)
    }

    @Test fun closeDiscardsCallbacksAndTimers() {
        val h = Harness()
        h.queue.request("Some example.") { fail("Closed activity received a result") }
        h.queue.close()
        h.downloads.single()(true)
        h.advance(80_000)
        assertTrue(h.translations.isEmpty())
    }
}
