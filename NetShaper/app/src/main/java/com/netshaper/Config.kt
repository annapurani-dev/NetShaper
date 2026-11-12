package com.netshaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Mode { IDLE, GOOD, FLUCTUATING, SPIKE_DOWN, SPIKE_UP }

/** Effective network conditions. A rate of 0 kbps means "unlimited". */
data class NetParams(
    val upKbps: Double,
    val downKbps: Double,
    val latencyMs: Double,
    val jitterMs: Double,
    val lossPct: Double
) {
    companion object {
        val UNLIMITED = NetParams(0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

data class Rng(val min: Double, val max: Double)

data class FluctProfile(
    val up: Rng,
    val down: Rng,
    val latency: Rng,
    val jitter: Rng,
    val loss: Rng,
    val intervalMs: Long,
    val smooth: Boolean
)

data class SeqStep(val mode: Mode, val sec: Int)

/** Everything the UI edits live. */
data class Settings(
    val baseline: NetParams,
    val fluct: FluctProfile,
    val spikeDurationSec: Int,
    val spikeDown: NetParams,
    val spikeUp: NetParams
)

class AppConfig(
    val baseline: NetParams,
    val profiles: Map<String, FluctProfile>,
    val defaultProfile: String,
    val spikeDurationSec: Int,
    val spikeDownPresets: Map<String, NetParams>,
    val spikeUpPresets: Map<String, NetParams>,
    val defaultDownStrength: String,
    val defaultUpStrength: String,
    val sequences: Map<String, List<SeqStep>>,
    val blockIpv6: Boolean,
    val maxQueueMs: Long,
    val dnsServers: List<String>
) {
    fun defaultSettings(): Settings = Settings(
        baseline,
        profiles[defaultProfile] ?: profiles.values.first(),
        spikeDurationSec,
        spikeDownPresets[defaultDownStrength] ?: spikeDownPresets.values.first(),
        spikeUpPresets[defaultUpStrength] ?: spikeUpPresets.values.first()
    )

    companion object {
        private fun JSONObject.params(): NetParams = NetParams(
            optDouble("upKbps", 0.0), optDouble("downKbps", 0.0),
            optDouble("latencyMs", 0.0), optDouble("jitterMs", 0.0), optDouble("lossPct", 0.0)
        )

        private fun JSONObject.rng(key: String, def: Rng): Rng {
            val a: JSONArray = optJSONArray(key) ?: return def
            return Rng(a.getDouble(0), a.getDouble(1))
        }

        private fun parseMode(s: String): Mode = when (s.trim().uppercase()) {
            "GOOD" -> Mode.GOOD
            "FLUCTUATE", "FLUCTUATING" -> Mode.FLUCTUATING
            "SPIKE_DOWN" -> Mode.SPIKE_DOWN
            "SPIKE_UP" -> Mode.SPIKE_UP
            else -> throw IllegalArgumentException("unknown sequence mode '$s'")
        }

        fun parse(text: String): AppConfig {
            val root = JSONObject(text)
            val baseline = root.getJSONObject("baseline").params()

            val fl = root.getJSONObject("fluctuate")
            val profiles = LinkedHashMap<String, FluctProfile>()
            val pj = fl.getJSONObject("profiles")
            for (name in pj.keys()) {
                if (name.startsWith("_")) continue
                val o = pj.getJSONObject(name)
                val shared = o.rng("kbps", Rng(500.0, 5000.0))
                profiles[name] = FluctProfile(
                    up = o.rng("upKbps", shared),
                    down = o.rng("downKbps", shared),
                    latency = o.rng("latencyMs", Rng(0.0, 100.0)),
                    jitter = o.rng("jitterMs", Rng(0.0, 20.0)),
                    loss = o.rng("lossPct", Rng(0.0, 2.0)),
                    intervalMs = o.optLong("intervalMs", 2000L),
                    smooth = o.optBoolean("smooth", true)
                )
            }
            require(profiles.isNotEmpty()) { "no fluctuate profiles" }

            val sp = root.getJSONObject("spike")
            val downs = LinkedHashMap<String, NetParams>()
            val ups = LinkedHashMap<String, NetParams>()
            val dj = sp.getJSONObject("down")
            for (k in dj.keys()) if (!k.startsWith("_")) downs[k] = dj.getJSONObject(k).params()
            val uj = sp.getJSONObject("up")
            for (k in uj.keys()) if (!k.startsWith("_")) ups[k] = uj.getJSONObject(k).params()
            require(downs.isNotEmpty() && ups.isNotEmpty()) { "spike presets missing" }

            val seqs = LinkedHashMap<String, List<SeqStep>>()
            val sj = root.optJSONObject("sequences")
            if (sj != null) {
                for (name in sj.keys()) {
                    if (name.startsWith("_")) continue
                    val arr = sj.getJSONArray(name)
                    val steps = ArrayList<SeqStep>()
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        steps.add(SeqStep(parseMode(s.getString("mode")), s.optInt("sec", 0)))
                    }
                    seqs[name] = steps
                }
            }

            val g = root.optJSONObject("general") ?: JSONObject()
            val dns = ArrayList<String>()
            g.optJSONArray("dnsServers")?.let { a -> for (i in 0 until a.length()) dns.add(a.getString(i)) }

            return AppConfig(
                baseline = baseline,
                profiles = profiles,
                defaultProfile = fl.optString("defaultProfile", profiles.keys.first()),
                spikeDurationSec = sp.optInt("durationSec", 10),
                spikeDownPresets = downs,
                spikeUpPresets = ups,
                defaultDownStrength = sp.optString("defaultDownStrength", downs.keys.first()),
                defaultUpStrength = sp.optString("defaultUpStrength", ups.keys.first()),
                sequences = seqs,
                blockIpv6 = g.optBoolean("blockIpv6", true),
                maxQueueMs = g.optLong("maxQueueMs", 1500L),
                dnsServers = dns
            )
        }
    }
}

object ConfigStore {
    fun externalFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "config.json")

    private fun assetText(ctx: Context): String =
        ctx.assets.open("config.json").bufferedReader().use { it.readText() }

    /** Loads the editable config (copying the built-in default on first run). Returns config + optional warning. */
    fun load(ctx: Context): Pair<AppConfig, String?> {
        val f = externalFile(ctx)
        try {
            if (!f.exists()) f.writeText(assetText(ctx))
        } catch (_: Exception) {
        }
        return try {
            Pair(AppConfig.parse(f.readText()), null)
        } catch (e: Exception) {
            Pair(AppConfig.parse(assetText(ctx)), "config.json invalid (${e.message}); using built-in defaults")
        }
    }
}
