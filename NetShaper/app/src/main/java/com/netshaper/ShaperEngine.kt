package com.netshaper

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class Snapshot(
    val mode: Mode,
    val params: NetParams,
    val spikeLeftMs: Long,
    val seqName: String?,
    val seqStep: Int,
    val seqSteps: Int,
    val seqStepLeftMs: Long,   // -1 = holds until stopped
    val seqTotalLeftMs: Long   // -1 = unbounded
)

/**
 * The brain: decides the current network conditions.
 *
 * Priority: manual/sequence SPIKE  >  sequence step  >  base mode (GOOD or FLUCTUATING).
 * Spikes simply expire back to whatever the base mode is, so they "return to the previous mode".
 * [effective] is a volatile snapshot that the packet shapers read for every packet, so a change
 * applies instantly (engine tick is 100 ms).
 */
object ShaperEngine {
    @Volatile var effective: NetParams = NetParams.UNLIMITED
    @Volatile var maxQueueMs: Long = 1500
    @Volatile var settings: Settings? = null
    @Volatile var config: AppConfig? = null

    @Volatile var mode: Mode = Mode.IDLE
        private set
    @Volatile var running: Boolean = false
        private set

    private val lock = Any()
    private var baseFluct = false
    private var spike: Mode? = null
    private var spikeEnd = 0L
    private var seqName: String? = null
    private var seqSteps: List<SeqStep> = emptyList()
    private var seqIdx = 0
    private var seqStepEnd = 0L
    private var executor: ScheduledExecutorService? = null

    // fluctuation state: [up, down, latency, jitter, loss]
    private val cur = DoubleArray(5)
    private val from = DoubleArray(5)
    private val to = DoubleArray(5)
    private var fStart = 0L
    private var fInit = false

    val baseFluctuating: Boolean get() = synchronized(lock) { baseFluct }

    fun fmt(p: NetParams): String = String.format(
        java.util.Locale.US, "up=%.0fkbps down=%.0fkbps latency=%.0fms jitter=%.0fms loss=%.1f%%",
        p.upKbps, p.downKbps, p.latencyMs, p.jitterMs, p.lossPct
    )

    private fun describe(s: Settings?): String {
        if (s == null) return "(none)"
        return "baseline[${fmt(s.baseline)}] fluct[up ${s.fluct.up.min}-${s.fluct.up.max} down ${s.fluct.down.min}-${s.fluct.down.max}" +
            " lat ${s.fluct.latency.min}-${s.fluct.latency.max} jit ${s.fluct.jitter.min}-${s.fluct.jitter.max}" +
            " loss ${s.fluct.loss.min}-${s.fluct.loss.max} every ${s.fluct.intervalMs}ms smooth=${s.fluct.smooth}]" +
            " spike ${s.spikeDurationSec}s down[${fmt(s.spikeDown)}] up[${fmt(s.spikeUp)}]"
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            fInit = false
            spike = null
            seqName = null
            val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "engine").apply { isDaemon = true } }
            executor = ex
            ex.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: Throwable) {
                    AppLog.log("engine tick error: $e")
                }
            }, 0, 100, TimeUnit.MILLISECONDS)
            AppLog.log("ENGINE start base=${if (baseFluct) "FLUCTUATING" else "GOOD"} ${describe(settings)}")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            executor?.shutdownNow()
            executor = null
            spike = null
            seqName = null
            mode = Mode.IDLE
            effective = NetParams.UNLIMITED
            AppLog.log("ENGINE stop -> IDLE")
        }
    }

    fun setFluctuate(on: Boolean) {
        synchronized(lock) {
            baseFluct = on
            AppLog.log("FLUCTUATE base ${if (on) "ON" else "OFF"}")
            if (running) recompute()
        }
    }

    fun spike(down: Boolean) {
        synchronized(lock) {
            if (!running) return
            val s = settings ?: return
            spike = if (down) Mode.SPIKE_DOWN else Mode.SPIKE_UP
            spikeEnd = System.currentTimeMillis() + s.spikeDurationSec * 1000L
            AppLog.log("SPIKE_${if (down) "DOWN" else "UP"} start ${s.spikeDurationSec}s params=${fmt(if (down) s.spikeDown else s.spikeUp)}")
            recompute()
        }
    }

    fun cancelSpike() {
        synchronized(lock) {
            if (spike != null) {
                AppLog.log("SPIKE cancelled")
                spike = null
                if (running) recompute()
            }
        }
    }

    fun startSequence(name: String): Boolean {
        return synchronized(lock) {
            val steps = config?.sequences?.get(name)
            if (!running || steps == null || steps.isEmpty()) {
                false
            } else {
                seqName = name
                seqSteps = steps
                seqIdx = 0
                beginStep(System.currentTimeMillis())
                recompute()
                true
            }
        }
    }

    fun stopSequence() {
        synchronized(lock) {
            if (seqName != null) {
                AppLog.log("SEQUENCE '$seqName' stopped")
                seqName = null
                if (running) recompute()
            }
        }
    }

    fun snapshot(): Snapshot {
        return synchronized(lock) {
            val now = System.currentTimeMillis()
            val spikeLeft = if (spike != null) max(0L, spikeEnd - now) else 0L
            var stepLeft = 0L
            var total = 0L
            if (seqName != null) {
                stepLeft = if (seqStepEnd == Long.MAX_VALUE) -1L else max(0L, seqStepEnd - now)
                if (stepLeft < 0) {
                    total = -1L
                } else {
                    total = stepLeft
                    for (i in seqIdx + 1 until seqSteps.size) {
                        val sec = seqSteps[i].sec
                        if (sec <= 0) { total = -1L; break }
                        total += sec * 1000L
                    }
                }
            }
            Snapshot(mode, effective, spikeLeft, seqName, seqIdx + 1, seqSteps.size, stepLeft, total)
        }
    }

    // ---- internals (call with lock held) ----

    private fun tick() {
        synchronized(lock) {
            if (!running) return
            val s = settings ?: return
            val now = System.currentTimeMillis()
            if (spike != null && now >= spikeEnd) {
                AppLog.log("${spike} ended -> back to ${if (seqName != null) "sequence step" else if (baseFluct) "FLUCTUATING" else "GOOD"}")
                spike = null
            }
            if (seqName != null && now >= seqStepEnd) {
                seqIdx++
                if (seqIdx >= seqSteps.size) {
                    AppLog.log("SEQUENCE '$seqName' finished")
                    seqName = null
                } else {
                    beginStep(now)
                }
            }
            stepFluct(now, s.fluct)
            recompute()
        }
    }

    private fun beginStep(now: Long) {
        val st = seqSteps[seqIdx]
        seqStepEnd = if (st.sec <= 0) Long.MAX_VALUE else now + st.sec * 1000L
        AppLog.log("SEQUENCE '$seqName' step ${seqIdx + 1}/${seqSteps.size}: ${st.mode} ${if (st.sec <= 0) "until stopped" else "${st.sec}s"}")
    }

    private fun recompute() {
        val s = settings ?: return
        val sq = seqName
        val m: Mode = spike
            ?: if (sq != null) seqSteps[seqIdx].mode
            else if (baseFluct) Mode.FLUCTUATING else Mode.GOOD
        val p = when (m) {
            Mode.GOOD -> s.baseline
            Mode.FLUCTUATING -> NetParams(cur[0], cur[1], cur[2], cur[3], cur[4])
            Mode.SPIKE_DOWN -> s.spikeDown
            Mode.SPIKE_UP -> s.spikeUp
            Mode.IDLE -> NetParams.UNLIMITED
        }
        if (m != mode) {
            AppLog.log("MODE $mode -> $m  ${fmt(p)}")
            mode = m
        }
        effective = p
        maxQueueMs = config?.maxQueueMs ?: 1500L
    }

    private fun stepFluct(now: Long, f: FluctProfile) {
        val interval = max(200L, f.intervalMs)
        if (!fInit) {
            pickTarget(f)
            System.arraycopy(to, 0, cur, 0, 5)
            System.arraycopy(to, 0, from, 0, 5)
            pickTarget(f)
            fStart = now
            fInit = true
            return
        }
        if (now - fStart >= interval) {
            System.arraycopy(to, 0, from, 0, 5)
            pickTarget(f)
            fStart = now
            if (!f.smooth) System.arraycopy(to, 0, cur, 0, 5)
            if (mode == Mode.FLUCTUATING) {
                AppLog.log("FLUCT target up=%.0f down=%.0f lat=%.0f jit=%.0f loss=%.1f".format(java.util.Locale.US, to[0], to[1], to[2], to[3], to[4]))
            }
        } else if (f.smooth) {
            val frac = min(1.0, (now - fStart).toDouble() / interval)
            for (i in 0..4) cur[i] = from[i] + (to[i] - from[i]) * frac
        }
    }

    private fun pickTarget(f: FluctProfile) {
        to[0] = logUniform(f.up)
        to[1] = logUniform(f.down)
        to[2] = uniform(f.latency)
        to[3] = uniform(f.jitter)
        to[4] = uniform(f.loss)
    }

    private fun logUniform(r: Rng): Double {
        val lo = max(1.0, min(r.min, r.max))
        val hi = max(lo, max(r.min, r.max))
        if (hi <= lo) return lo
        return exp(ln(lo) + ThreadLocalRandom.current().nextDouble() * (ln(hi) - ln(lo)))
    }

    private fun uniform(r: Rng): Double {
        val lo = min(r.min, r.max)
        val hi = max(r.min, r.max)
        if (hi <= lo) return lo
        return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo)
    }
}
