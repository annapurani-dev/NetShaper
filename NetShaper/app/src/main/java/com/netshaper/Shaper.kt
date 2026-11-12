package com.netshaper

import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One direction of the emulated link (up = phone -> network, down = network -> phone).
 *
 * Pipeline per packet:  enqueue -> FIFO queue -> token bucket (rate) -> latency/jitter delay -> deliver.
 * - Rate is re-read from [ShaperEngine.effective] continuously, so a spike changes throughput within ~20 ms.
 * - Loss: UDP packets are dropped; TCP "chunks" are delayed instead (emulating a retransmission),
 *   because the TCP bytestream is terminated locally and must stay reliable.
 */
class Shaper(private val name: String, private val isUp: Boolean) {
    private class Item(val bytes: Int, val orderer: LongArray?, val extraMs: Long, val deliver: () -> Unit)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val queue = ArrayDeque<Item>()
    private var queuedBytes = 0L
    private var tokens = 0.0
    private var lastRefill = System.nanoTime()

    @Volatile private var running = true
    private val delivery = ScheduledThreadPoolExecutor(
        1, ThreadFactory { r -> Thread(r, "deliver-$name").apply { isDaemon = true } }
    )
    val dropped = AtomicLong()
    val passedBytes = AtomicLong()

    init {
        Thread({ pumpLoop() }, "pump-$name").apply { isDaemon = true }.start()
    }

    private fun rate(p: NetParams) = if (isUp) p.upKbps else p.downKbps

    private fun limitBytes(p: NetParams): Long {
        val k = rate(p)
        if (k <= 0.0) return Long.MAX_VALUE
        return maxOf(24_000L, (k * ShaperEngine.maxQueueMs / 8.0).toLong())
    }

    /**
     * Queue a packet/chunk for shaped delivery.
     * @param orderer per-flow monotonic clock (nanos) that keeps TCP chunks in order despite jitter; null for UDP.
     * @param dropAllowed true for UDP (loss + tail drop applies); false for TCP.
     * @return false if the packet was dropped.
     */
    fun enqueue(bytes: Int, orderer: LongArray?, dropAllowed: Boolean, deliver: () -> Unit): Boolean {
        if (!running) return false
        val p = ShaperEngine.effective
        var extra = 0L
        if (p.lossPct > 0.0 && ThreadLocalRandom.current().nextDouble() * 100.0 < p.lossPct) {
            if (dropAllowed) {
                dropped.incrementAndGet()
                return false
            }
            extra = (200.0 + 2.0 * p.latencyMs).toLong()
        }
        lock.withLock {
            if (dropAllowed && queuedBytes + bytes > limitBytes(p)) {
                dropped.incrementAndGet()
                return false
            }
            queue.addLast(Item(bytes, orderer, extra, deliver))
            queuedBytes += bytes
            cond.signalAll()
        }
        return true
    }

    /** Back-pressure for TCP readers: blocks while the queue is over its limit. */
    fun awaitSpace(maxWaitMs: Long = 3000L) {
        val deadline = System.nanoTime() + maxWaitMs * 1_000_000L
        lock.withLock {
            while (running && queuedBytes > limitBytes(ShaperEngine.effective)) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return
                cond.await(minOf(left, 50_000_000L), TimeUnit.NANOSECONDS)
            }
        }
    }

    private fun pumpLoop() {
        while (running) {
            var item: Item? = null
            lock.lock()
            try {
                while (running && queue.isEmpty()) cond.await(200, TimeUnit.MILLISECONDS)
                if (!running) return
                val kbps = rate(ShaperEngine.effective)
                val head = queue.peekFirst()
                if (head != null) {
                    if (kbps <= 0.0) {
                        item = queue.pollFirst()
                        lastRefill = System.nanoTime()
                        tokens = 0.0
                    } else {
                        val bytesPerSec = kbps * 125.0
                        val now = System.nanoTime()
                        val burst = maxOf(1600.0, bytesPerSec * 0.02)
                        tokens = minOf(burst, tokens + (now - lastRefill) / 1e9 * bytesPerSec)
                        lastRefill = now
                        if (tokens >= head.bytes) {
                            tokens -= head.bytes
                            item = queue.pollFirst()
                        } else {
                            val waitMs = ((head.bytes - tokens) / bytesPerSec * 1000.0).toLong().coerceIn(1L, 20L)
                            cond.await(waitMs, TimeUnit.MILLISECONDS)
                        }
                    }
                    val it = item
                    if (it != null) {
                        queuedBytes -= it.bytes
                        cond.signalAll()
                    }
                }
            } catch (e: InterruptedException) {
                return
            } finally {
                lock.unlock()
            }
            val toSend = item
            if (toSend != null) dispatch(toSend)
        }
    }

    private fun dispatch(item: Item) {
        val p = ShaperEngine.effective
        var delayMs = p.latencyMs + item.extraMs
        if (p.jitterMs > 0.0) delayMs += ThreadLocalRandom.current().nextDouble() * p.jitterMs
        var atNs = System.nanoTime() + (delayMs * 1_000_000.0).toLong()
        val o = item.orderer
        if (o != null) {
            synchronized(o) {
                if (atNs < o[0]) atNs = o[0]
                o[0] = atNs
            }
        }
        passedBytes.addAndGet(item.bytes.toLong())
        val d = maxOf(0L, atNs - System.nanoTime())
        try {
            delivery.schedule({
                try {
                    item.deliver()
                } catch (e: Throwable) {
                    AppLog.log("deliver error ($name): $e")
                }
            }, d, TimeUnit.NANOSECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    fun stop() {
        running = false
        lock.withLock {
            queue.clear()
            queuedBytes = 0
            cond.signalAll()
        }
        delivery.shutdownNow()
    }
}
