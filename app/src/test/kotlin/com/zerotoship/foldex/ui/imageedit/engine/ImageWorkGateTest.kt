// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageWorkGateTest {
    @Test fun `work from different callers cannot overlap and cancelled waiters never allocate`() = runBlocking {
        val gate = ImageWorkGate()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        val first = async(Dispatchers.Default) {
            gate.run {
                calls.incrementAndGet()
                started.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
                calls.decrementAndGet()
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run { fail("cancelled waiter entered") }
            }
            cancelled.cancelAndJoin()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run { assertEquals(0, calls.get()); 42 }
            }
            assertFalse(second.isCompleted)
            finish.countDown()
            first.await()
            assertEquals(42, second.await())
        } finally {
            finish.countDown()
            first.cancelAndJoin()
        }
    }

    @Test fun `failure and cancellation release the slot for later work`() = runBlocking {
        val gate = ImageWorkGate()
        val released = AtomicInteger()
        for (error in listOf(IllegalStateException(), CancellationException())) {
            try {
                gate.run {
                    try { throw error } finally { released.incrementAndGet() }
                }
            } catch (_: Exception) { }
            assertEquals(42, gate.run { 42 })
        }
        assertEquals(2, released.get())
    }

    @Test fun `a cancelled running job holds the slot until its cleanup finishes`() = runBlocking {
        val gate = ImageWorkGate()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cleaned = AtomicInteger()
        val first = launch(Dispatchers.Default) {
            val context = currentCoroutineContext()
            gate.run {
                try {
                    started.countDown()
                    assertTrue(finish.await(5, TimeUnit.SECONDS))
                    context.ensureActive()
                } finally { cleaned.incrementAndGet() }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            first.cancel()
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                gate.run { assertEquals(1, cleaned.get()) }
            }
            assertFalse(next.isCompleted)
            finish.countDown()
            first.join()
            next.await()
        } finally {
            finish.countDown()
            first.cancelAndJoin()
        }
    }
}
