package com.netshaper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private companion object {
        const val REQ_VPN = 100
        const val REQ_NOTIF = 101
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cfg: AppConfig
    private val fields = HashMap<String, EditText>()
    private var uiReady = false

    private lateinit var tvMode: TextView
    private lateinit var tvNums: TextView
    private lateinit var tvLeft: TextView
    private lateinit var btnStart: Button
    private lateinit var cbSmooth: CheckBox
    private lateinit var swFluct: Switch
    private lateinit var spProfile: Spinner
    private lateinit var spDown: Spinner
    private lateinit var spUp: Spinner
    private lateinit var spSeq: Spinner

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = applyUi()
    }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        loadConfig(reset = false)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun loadConfig(reset: Boolean) {
        val (c, warn) = ConfigStore.load(this)
        cfg = c
        ShaperEngine.config = c
        if (reset || ShaperEngine.settings == null) ShaperEngine.settings = c.defaultSettings()
        if (warn != null) toast(warn)
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fmtNum(v: Double): String =
        if (v == Math.floor(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun field(parent: LinearLayout, key: String, label: String, value: Double) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val et = EditText(this).apply {
            setText(fmtNum(value))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(watcher)
        }
        fields[key] = et
        row.addView(et)
        parent.addView(row)
    }

    private fun button(text: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(bg)
        setOnClickListener { onClick() }
    }

    private fun spinner(parent: LinearLayout, items: List<String>, selected: String, onPick: (String) -> Unit): Spinner {
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        val idx = items.indexOf(selected)
        if (idx >= 0) sp.setSelection(idx)
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return } // ignore the automatic initial selection
                onPick(items[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        parent.addView(sp)
        return sp
    }

    private fun buildUi() {
        val st = ShaperEngine.settings!!
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // --- status
        tvMode = TextView(this).apply {
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.GRAY)
            setPadding(0, dp(20), 0, dp(20))
        }
        tvNums = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        tvLeft = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.DKGRAY) }
        root.addView(tvMode)
        root.addView(tvNums)
        root.addView(tvLeft)

        btnStart = button("START SHAPING", Color.parseColor("#2E7D32")) { toggleShaping() }
        root.addView(btnStart)
        root.addView(TextView(this).apply {
            text = "This shapes ALL traffic on this Android phone (Chrome, WebView, WebRTC/UDP, speed tests...). " +
                "Stop restores normal network."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        // --- baseline
        title(root, "Baseline (GOOD)")
        field(root, "bUp", "Upload Kbps (0 = unlimited)", st.baseline.upKbps)
        field(root, "bDown", "Download Kbps (0 = unlimited)", st.baseline.downKbps)
        field(root, "bLat", "Latency ms", st.baseline.latencyMs)
        field(root, "bJit", "Jitter ms", st.baseline.jitterMs)
        field(root, "bLoss", "Loss %", st.baseline.lossPct)

        // --- fluctuate
        title(root, "Fluctuate")
        swFluct = Switch(this).apply {
            text = "Fluctuate ON / OFF"
            isChecked = ShaperEngine.baseFluctuating
            setOnCheckedChangeListener { _, on -> ShaperEngine.setFluctuate(on) }
        }
        root.addView(swFluct)
        spProfile = spinner(root, cfg.profiles.keys.toList(), cfg.defaultProfile) { applyProfile(it) }
        field(root, "fMin", "Min Kbps (up & down)", st.fluct.up.min)
        field(root, "fMax", "Max Kbps (up & down)", st.fluct.up.max)
        field(root, "fInt", "Change interval ms", st.fluct.intervalMs.toDouble())
        field(root, "fLatMin", "Latency min ms", st.fluct.latency.min)
        field(root, "fLatMax", "Latency max ms", st.fluct.latency.max)
        field(root, "fJitMax", "Jitter max ms", st.fluct.jitter.max)
        field(root, "fLossMax", "Loss max %", st.fluct.loss.max)
        cbSmooth = CheckBox(this).apply {
            text = "Smooth ramps (off = hard jumps every interval)"
            isChecked = st.fluct.smooth
            setOnCheckedChangeListener { _, _ -> applyUi() }
        }
        root.addView(cbSmooth)

        // --- spikes
        title(root, "Spikes")
        field(root, "sDur", "Spike duration sec", st.spikeDurationSec.toDouble())
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in listOf(3, 5, 10, 30)) {
            quick.addView(Button(this).apply {
                text = "${s}s"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { fields["sDur"]?.setText(s.toString()) }
            })
        }
        root.addView(quick)
        root.addView(TextView(this).apply { text = "Spike DOWN strength" })
        spDown = spinner(root, cfg.spikeDownPresets.keys.toList(), cfg.defaultDownStrength) { applyDownPreset(it) }
        field(root, "sdKbps", "Spike down Kbps (up & down)", st.spikeDown.downKbps)
        field(root, "sdLat", "Spike down latency ms", st.spikeDown.latencyMs)
        field(root, "sdLoss", "Spike down loss %", st.spikeDown.lossPct)
        root.addView(TextView(this).apply { text = "Spike UP strength" })
        spUp = spinner(root, cfg.spikeUpPresets.keys.toList(), cfg.defaultUpStrength) { applyUpPreset(it) }
        field(root, "suKbps", "Spike up Kbps (up & down)", st.spikeUp.downKbps)

        val spikeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        spikeRow.addView(button("SPIKE DOWN now", Color.parseColor("#C62828")) { doSpike(true) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        spikeRow.addView(button("SPIKE UP now", Color.parseColor("#1565C0")) { doSpike(false) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(spikeRow)

        // --- sequences
        title(root, "Sequence")
        val seqNames = cfg.sequences.keys.toList()
        spSeq = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, seqNames)
        }
        root.addView(spSeq)
        val seqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seqRow.addView(button("Start sequence", Color.parseColor("#6A1B9A")) {
            val name = spSeq.selectedItem as? String
            if (name == null) toast("No sequences in config.json")
            else if (!ShaperEngine.startSequence(name)) toast("Start shaping first")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        seqRow.addView(button("Stop sequence", Color.DKGRAY) { ShaperEngine.stopSequence() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(seqRow)

        // --- tools
        title(root, "Tools")
        root.addView(button("Reload config.json (resets fields)", Color.DKGRAY) {
            loadConfig(reset = true)
            toast("Config reloaded")
            recreate()
        })
        root.addView(button("View log", Color.DKGRAY) { showLog() })
        root.addView(button("Battery optimisation settings", Color.DKGRAY) {
            try {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Not available on this device")
            }
        })
        root.addView(TextView(this).apply {
            text = "Config file: ${ConfigStore.externalFile(this@MainActivity).absolutePath}\nLog file: ${AppLog.path()}"
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        uiReady = true
    }

    // ------------------------------------------------------------------ presets & settings

    private fun setField(key: String, v: Double) {
        fields[key]?.setText(fmtNum(v))
    }

    private fun applyProfile(name: String) {
        val p = cfg.profiles[name] ?: return
        setField("fMin", p.up.min)
        setField("fMax", p.up.max)
        setField("fInt", p.intervalMs.toDouble())
        setField("fLatMin", p.latency.min)
        setField("fLatMax", p.latency.max)
        setField("fJitMax", p.jitter.max)
        setField("fLossMax", p.loss.max)
        cbSmooth.isChecked = p.smooth
        applyUi()
    }

    private fun applyDownPreset(name: String) {
        val p = cfg.spikeDownPresets[name] ?: return
        setField("sdKbps", p.downKbps)
        setField("sdLat", p.latencyMs)
        setField("sdLoss", p.lossPct)
        applyUi()
    }

    private fun applyUpPreset(name: String) {
        val p = cfg.spikeUpPresets[name] ?: return
        setField("suKbps", p.downKbps)
        applyUi()
    }

    private fun d(key: String, def: Double): Double =
        fields[key]?.text?.toString()?.trim()?.toDoubleOrNull() ?: def

    /** Pushes the current field values into the engine; changes apply live while running. */
    private fun applyUi() {
        if (!uiReady) return
        val cur = ShaperEngine.settings ?: return
        val baseline = NetParams(
            d("bUp", cur.baseline.upKbps), d("bDown", cur.baseline.downKbps),
            d("bLat", cur.baseline.latencyMs), d("bJit", cur.baseline.jitterMs), d("bLoss", cur.baseline.lossPct)
        )
        val kbps = Rng(d("fMin", cur.fluct.up.min), d("fMax", cur.fluct.up.max))
        val fl = FluctProfile(
            up = kbps, down = kbps,
            latency = Rng(d("fLatMin", cur.fluct.latency.min), d("fLatMax", cur.fluct.latency.max)),
            jitter = Rng(0.0, d("fJitMax", cur.fluct.jitter.max)),
            loss = Rng(0.0, d("fLossMax", cur.fluct.loss.max)),
            intervalMs = d("fInt", cur.fluct.intervalMs.toDouble()).toLong(),
            smooth = cbSmooth.isChecked
        )
        val sdK = d("sdKbps", cur.spikeDown.downKbps)
        val spikeDown = NetParams(sdK, sdK, d("sdLat", cur.spikeDown.latencyMs), 0.0, d("sdLoss", cur.spikeDown.lossPct))
        val suK = d("suKbps", cur.spikeUp.downKbps)
        val spikeUp = NetParams(suK, suK, 0.0, 0.0, 0.0)
        ShaperEngine.settings = Settings(baseline, fl, d("sDur", cur.spikeDurationSec.toDouble()).toInt().coerceAtLeast(1), spikeDown, spikeUp)
    }

    // ------------------------------------------------------------------ actions

    private fun toggleShaping() {
        if (ShaperVpnService.running) {
            startService(Intent(this, ShaperVpnService::class.java).setAction(ShaperVpnService.ACTION_STOP))
            return
        }
        applyUi()
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, REQ_VPN) else launchService()
    }

    private fun launchService() {
        val i = Intent(this, ShaperVpnService::class.java).setAction(ShaperVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) launchService() else toast("VPN permission denied - shaping cannot start")
        }
    }

    private fun doSpike(down: Boolean) {
        if (!ShaperVpnService.running) {
            toast("Start shaping first")
            return
        }
        applyUi()
        ShaperEngine.spike(down)
    }

    private fun showLog() {
        val tv = TextView(this).apply {
            text = AppLog.tail(200)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Log")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ status refresh

    private fun kbpsStr(k: Double) = if (k <= 0.0) "unlimited" else if (k >= 1000) String.format(Locale.US, "%.1f Mbps", k / 1000) else String.format(Locale.US, "%.0f Kbps", k)
    private fun secs(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)

    private fun refresh() {
        if (!uiReady) return
        val running = ShaperVpnService.running
        val snap = ShaperEngine.snapshot()
        val mode = if (running) snap.mode else Mode.IDLE
        tvMode.text = mode.name
        tvMode.setBackgroundColor(
            when (mode) {
                Mode.IDLE -> Color.GRAY
                Mode.GOOD -> Color.parseColor("#2E7D32")
                Mode.FLUCTUATING -> Color.parseColor("#EF6C00")
                Mode.SPIKE_DOWN -> Color.parseColor("#C62828")
                Mode.SPIKE_UP -> Color.parseColor("#1565C0")
            }
        )
        btnStart.text = if (running) "STOP SHAPING (restore normal network)" else "START SHAPING"
        btnStart.setBackgroundColor(if (running) Color.parseColor("#B71C1C") else Color.parseColor("#2E7D32"))
        if (!running) {
            tvNums.text = "Shaping is off - phone network is normal"
            tvLeft.text = ""
            return
        }
        val p = snap.params
        tvNums.text = String.format(
            Locale.US, "UP %s   DOWN %s\nlatency %.0f ms (+0..%.0f jitter)   loss %.1f%%",
            kbpsStr(p.upKbps), kbpsStr(p.downKbps), p.latencyMs, p.jitterMs, p.lossPct
        )
        val lines = ArrayList<String>()
        if (snap.spikeLeftMs > 0) lines.add("Spike ends in ${secs(snap.spikeLeftMs)}")
        if (snap.seqName != null) {
            val step = if (snap.seqStepLeftMs < 0) "until stopped" else "${secs(snap.seqStepLeftMs)} left in step"
            val total = if (snap.seqTotalLeftMs < 0) "open-ended" else "${secs(snap.seqTotalLeftMs)} total"
            lines.add("Sequence '${snap.seqName}' step ${snap.seqStep}/${snap.seqSteps}: $step ($total)")
        }
        tvLeft.text = lines.joinToString("\n")
    }
}
