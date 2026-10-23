package com.example.ui.components

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureQrScanGateTest {
    @Test
    fun consume_deliversOnlyOneResultForConcurrentAttempts() {
        val gate = SecureQrScanGate()
        val delivered = AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)

        repeat(20) {
            pool.execute {
                start.await()
                gate.consume("qr-payload") { delivered.incrementAndGet() }
            }
        }
        start.countDown()
        pool.shutdown()
        while (!pool.isTerminated) Thread.yield()

        assertEquals(1, delivered.get())
        assertEquals(false, gate.consume("", {}))
    }
}
