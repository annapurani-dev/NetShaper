# NetShaper – Android network fluctuation tester (VpnService)

Simulates bad / unstable networks for **LiveKit / VideoPD meetings on Android**.
It shapes **ALL traffic on the phone** (Chrome, WebView, WebRTC/UDP, speed tests) – no root, nothing deployed to UAT, no VideoPD changes.

> **Status:** first milestone. The source was written without access to the Android SDK, so it has **not been compiled or run yet**.
> Build it in Android Studio and expect to fix a few small compile errors / device quirks on the first pass. See "Known limitations" and the QA checklist.

## Why VpnService

| Option | Verdict |
|---|---|
| **Local VpnService shaper (chosen)** | No root, system-wide, captures UDP (WebRTC) and TCP, runs on the phone itself, auto-cleans up when the app dies. |
| Root + `tc netem` | Great fidelity but QA phones are not rooted. |
| PC hotspot shaper | Explicitly out of scope; extra hardware, not "all phone traffic". |

## How it works

```
apps ──> TUN (0.0.0.0/0) ──> PacketForwarder ──> Shaper (rate, latency, jitter, loss) ──> protected sockets ──> real network
                                   ▲                                   ▲
                             UDP relay / local TCP endpoint     ShaperEngine (GOOD / FLUCTUATING / SPIKE_*, sequences)
```

* **UDP** (WebRTC media, STUN/TURN, QUIC, DNS): relayed per flow; every packet is rate-limited, delayed, jittered and randomly dropped.
* **TCP** (signalling, HTTPS, TURN/TCP): terminated locally and re-originated; bytes are rate-limited and delayed. TCP "loss" is emulated as a retransmission-like delay (a real drop would be hidden by the local stack anyway).
* **Rate** is a token bucket re-evaluated every ≤20 ms, so a spike changes throughput within ~100 ms of the tap.
* **IPv6** is refused (TCP RST / drop) so apps fall back to IPv4 and cannot bypass the shaper (`blockIpv6` in config).
* ICMP (ping) is dropped.

### Modes (always shown in the big status)
`IDLE | GOOD | FLUCTUATING | SPIKE_DOWN | SPIKE_UP`

Priority: **spike > sequence step > base mode (GOOD or FLUCTUATING)**. A spike expires back to the base mode, i.e. "returns to previous mode".

## Build & install

Requirements: Android Studio (Koala or newer) or Gradle 8.7+, JDK 17, Android SDK 34.

```
# Android Studio: File > Open > this folder > Run
# or CLI (needs a Gradle wrapper or local Gradle):
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(`assembleRelease` also works; it is signed with the debug key for easy sideloading.)

* minSdk **24** (Android 7.0), compileSdk 34, targetSdk 33 (deliberate: avoids Android 14 foreground-service-type rules for this sideloaded tool).
* Tested versions: _none yet – fill in after the first QA run._

## Permissions & first run

1. Tap **START SHAPING**.
2. Android shows the **VPN connection request** → OK. (One-time per install; a key icon appears in the status bar while active.)
3. On Android 13+ allow notifications (the "NetShaper is shaping ALL traffic" notification has a **Stop** button).
4. Optional: **Battery optimisation settings** → set NetShaper to "Unrestricted" for long test sessions. Some OEMs (Xiaomi, Oppo, Vivo, Samsung "sleeping apps") kill background services aggressively.

## Testing a meeting

1. Install, grant VPN permission, set baseline (default 10 Mbps) and **Start shaping** (GOOD).
2. Open the UAT meeting on the **same phone** and join.
3. During the call: toggle **Fluctuate**, tap **SPIKE DOWN now** / **SPIKE UP now**, or run a **Sequence**.
4. **Stop** → normal network returns immediately.

Sanity checks: run a speed test during `SPIKE_DOWN` (should collapse), then after the spike / Stop (should recover).

## Controls

* **Baseline** – up/down Kbps (0 = unlimited), latency, jitter, loss.
* **Fluctuate** – profile (mild / medium / aggressive fills the fields), min/max Kbps (shared up & down), interval, latency range, jitter max, loss max, smooth ramps vs hard jumps. Throughput is picked log-uniformly between min and max, then ramped (smooth) or jumped (aggressive) every interval.
* **Spikes** – duration (3 / 5 / 10 / 30 s or custom), strength presets (down: mild / hard / blackout, up: mild / full) that fill editable target Kbps / latency / loss fields.
* **Sequence** – pick from config, Start / Stop.
* Field edits apply **live** while running.

## Config (editable without rebuild)

`Android/data/com.netshaper/files/config.json` (created on first run from the built-in default; shown at the bottom of the app).
Edit via a file manager, `adb push`, or `adb shell`; then tap **Reload config.json** (this resets the UI fields to the file's values).

```
adb pull /sdcard/Android/data/com.netshaper/files/config.json
# edit...
adb push config.json /sdcard/Android/data/com.netshaper/files/config.json
```

Sequences:

```json
"sequences": {
  "demo": [
    { "mode": "GOOD", "sec": 20 },
    { "mode": "FLUCTUATE", "sec": 30 },
    { "mode": "SPIKE_DOWN", "sec": 10 },
    { "mode": "SPIKE_UP", "sec": 5 },
    { "mode": "FLUCTUATE", "sec": 0 }       // sec 0 = hold until you tap Stop sequence
  ]
}
```
Modes: `GOOD`, `FLUCTUATE`, `SPIKE_DOWN`, `SPIKE_UP`. Spike steps use the current spike targets from the UI. A malformed config falls back to the built-in defaults with a warning toast.

## Log

`Android/data/com.netshaper/files/shaper.log` (rotated at 2 MB) and **View log** in the app: timestamp + mode changes + settings + fluctuation targets.

```
adb pull /sdcard/Android/data/com.netshaper/files/shaper.log
```

## Safety / recovery / uninstall

* **Stop** (button or notification action) closes the TUN → network is normal immediately.
* **Crash / force-stop / swipe-away / reboot:** Android removes a VPN when its owning process dies; the service is `START_NOT_STICKY`, so nothing restarts it. Internet returns to normal without any manual step.
* If the key icon is somehow still shown: Settings → Network → VPN → NetShaper → Forget / Disconnect, or just uninstall the app.
* Uninstall: remove the app normally; config/log are deleted with it.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| No internet right after Start | Check **View log**. Try `blockIpv6: false`. On an IPv6-only mobile network use Wi-Fi (the IPv4 route needs an IPv4-capable underlying network). |
| Shaping seems to have no effect | Another VPN/Private-DNS-over-VPN app is active (only one VPN at a time). Confirm the key icon + notification. |
| Speed test never reaches baseline | Userspace forwarding tops out around tens of Mbps on mid-range phones; set baseline ≤ 20 Mbps or 0 (unlimited) for a near-passthrough. |
| Meeting uses QUIC / falls back to TCP | Heavy loss makes Chrome abandon QUIC; expected. |
| Service killed in background | Disable battery optimisation (see above); keep the notification visible. |
| App can't start VPN | Permission was denied/revoked → tap Start again and accept the dialog. |

## Known limitations (v1)

* Not yet compiled/tested on a device (see top).
* Userspace TCP is deliberately simple: no window scaling / SACK; 64 KB window, MSS 1400. Per-connection TCP throughput is capped by 64 KB ÷ RTT, which is realistic for emulation but means "GOOD" is not a perfect passthrough on high-latency settings.
* IPv4 fragments, ICMP and non-TCP/UDP IPv6 are dropped; UDP datagrams > ~1472 bytes are dropped.
* Apps that opt out of VPNs (rare) or use a bypassing tethering path are not shaped.
* Shaped DNS: slow DNS during spikes is expected and part of the test.

## Source map

| File | Role |
|---|---|
| `ShaperEngine.kt` | Modes, fluctuation, spikes, sequences, effective params |
| `Shaper.kt` | Per-direction queue, token bucket, latency/jitter/loss |
| `PacketForwarder.kt` | TUN loop, UDP relay, local TCP endpoint |
| `ShaperVpnService.kt` | VpnService + foreground notification + cleanup |
| `MainActivity.kt` | UI |
| `Config.kt`, `AppLog.kt`, `Pkt.kt` | Config parsing, logging, packet helpers |
