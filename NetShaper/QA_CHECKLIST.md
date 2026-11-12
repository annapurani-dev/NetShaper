# VideoPD Android QA checklist (NetShaper)

Device: ______  Android: ______  App build: ______  Network: Wi-Fi / mobile  Date: ______

## Setup
- [ ] APK installed, app opens
- [ ] START SHAPING → VPN dialog accepted, key icon + "NetShaper is shaping ALL traffic" notification visible
- [ ] Baseline set (e.g. 5000/5000 Kbps), status shows **GOOD** with matching numbers
- [ ] Phone speed test is capped near the baseline (proves all-traffic shaping)

## Meeting tests (UAT URL opened on the SAME phone)
- [ ] Join a LiveKit / VideoPD meeting while GOOD – audio/video fine
- [ ] **Fluctuate ON (mild)** – quality wobbles but stays usable
- [ ] **Fluctuate ON (medium)** – visible quality changes over time
- [ ] **Fluctuate ON (aggressive)** – freezes / low quality / possible reconnect
- [ ] **SPIKE DOWN now (hard)** – degradation within ~1 s; status = SPIKE_DOWN with countdown
- [ ] Spike ends → returns to previous mode (GOOD or FLUCTUATING)
- [ ] **SPIKE DOWN (blackout)** – freeze / reconnect behaviour recorded: ______
- [ ] **SPIKE UP now** (from a bad state) – improvement within ~1 s; returns to previous mode afterwards
- [ ] Sequence `demo` runs: GOOD → FLUCTUATING → SPIKE_DOWN → SPIKE_UP → FLUCTUATING; Stop sequence works
- [ ] Mic/camera/screen-share still behave sensibly under degradation (note issues: ______)

## Recovery & safety
- [ ] **STOP SHAPING** → speed test and meeting return to normal immediately
- [ ] Start shaping, then **force-stop the app** (Settings → Apps → Force stop) → internet works normally, no VPN key icon
- [ ] Start shaping, swipe app away → internet still sensible (service may stop; no permanent break)
- [ ] Reboot with shaping active → normal network after boot

## Logs
- [ ] `shaper.log` contains timestamps, MODE changes, SPIKE start/end, settings (attach to bug report)

## Observations / bugs
