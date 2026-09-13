# Android TV System Tuning, Performance & Debloating Guide

A complete reference of all system-level optimizations, memory limits, ahead-of-time bytecode compilation, and debloating applied to the **Philips 65" Android TV (TPM191E)**.

---

## 1. Locked Memory & Cached Process Limits

On a 2.0 GB RAM TV, Android's default memory management kills active streaming apps too quickly or allows too many background apps to exhaust RAM.

### The Optimization: Lock to 12 Cached Processes
Standard Android settings (`settings put global max_cached_processes 12`) are frequently reset to default by Google Play Services during background sync cycles. To lock the configuration permanently:

```powershell
# 1. Lock device_config from being overwritten by Google server sync
adb connect 192.168.1.17:5555; adb -s 192.168.1.17:5555 shell "cmd device_config set_sync_disabled_for_tests persistent"

# 2. Enforce limits across both device_config and global settings
adb -s 192.168.1.17:5555 shell "device_config put activity_manager max_cached_processes 12"
adb -s 192.168.1.17:5555 shell "device_config put activity_manager max_empty_processes 6"
adb -s 192.168.1.17:5555 shell "settings put global max_cached_processes 12"
adb -s 192.168.1.17:5555 shell "settings put global background_process_limit 3"
```

### Verification
```powershell
adb -s 192.168.1.17:5555 shell "dumpsys activity settings | grep -E 'CUR_MAX_CACHED_PROCESSES|CUR_MAX_EMPTY_PROCESSES'"
```
Expected output:
```text
CUR_MAX_CACHED_PROCESSES=12
CUR_MAX_EMPTY_PROCESSES=6
```

---

## 2. Zero Animation Scales (Instant UI Snappiness)

Default Android TV animations introduce artificial delays (1.0x scale = 300–500 ms per window/dialog transition). Zeroing animations makes focus changes and app opening instantaneous:

```powershell
adb connect 192.168.1.17:5555; adb -s 192.168.1.17:5555 shell "settings put global window_animation_scale 0.0"
adb -s 192.168.1.17:5555 shell "settings put global transition_animation_scale 0.0"
adb -s 192.168.1.17:5555 shell "settings put global animator_duration_scale 0.0"
```

---

## 3. Native Ahead-Of-Time (AOT) Machine Code Compilation

By default, Android apps run through an interpreted/JIT compiler, causing UI stutter and high CPU spikes when screens first render.

Force-compiling all core media apps to **native ARM machine code (`-m speed`)** completely bypasses runtime JIT interpretation:

```powershell
# Compile core media apps to native machine code
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f ar.tvplayer.tv"
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f org.smarttube.stable"
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f com.stremio.one"
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f com.spocky.projengmenu"
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f com.antigravity.tvvolume"

# Compile system frameworks
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f com.android.systemui"
adb -s 192.168.1.17:5555 shell "cmd package compile -m speed -f com.google.android.webview"
```

---

## 4. Debloating Unnecessary Background Daemons

Several system services consume precious RAM and CPU cycles on a TV that has no corresponding hardware:

| Service / Daemon | Purpose | Why Disabled | Command |
|---|---|---|---|
| `com.android.se` | Secure Element OMAPI | TV has no SIM card or NFC smart card hardware. | `adb shell pm disable-user com.android.se` |
| `org.droidtv.dlna` | Philips DLNA Media Server | Runs in background listening on ports; unnecessary when using Stremio/Plex. | `adb shell pm disable-user org.droidtv.dlna` |
| `traced` (Perfetto) | System tracing service | Writes continuous profiling buffers. | `adb shell setprop persist.traced.enable 0` |
| `logcat` buffer | System log buffer | Default 8 MB buffer wastes kernel ashmem; shrunk to 256 KB. | `adb shell logcat -G 256K` |

---

## 5. SmartTube & Multi-App Audio/Video Decoders

### Prevent SmartTube Picture-in-Picture (PiP) Decoder Hijacking
When exiting SmartTube to Stremio, SmartTube's default behavior is to shrink the video into a PiP window. On Android TVs with limited hardware video decoders, this locks the hardware HEVC decoder, causing Stremio to fail with "DecoderInitException" or audio-only playback.

```powershell
# Block SmartTube from taking over Picture-in-Picture
adb connect 192.168.1.17:5555; adb -s 192.168.1.17:5555 shell "appops set org.smarttube.stable PICTURE_IN_PICTURE ignore"

# Allow overlay permissions for UI controls
adb -s 192.168.1.17:5555 shell "appops set org.smarttube.stable SYSTEM_ALERT_WINDOW allow"
```

---

## 6. Default Launcher: Projectivy Launcher Setup

The stock Google TV launcher (`com.google.android.tvlauncher`) consumes 250+ MB of RAM and runs auto-playing video trailers, recommendations, and analytics in the background.

Replacing it with **Projectivy Launcher** (`com.spocky.projengmenu`):
- Memory usage drops from ~250 MB to ~35 MB.
- Instant home screen load with zero advertisements.
- Clean rows for Stremio, SmartTube, and TiviMate.

```powershell
# Set Projectivy as preferred home
adb connect 192.168.1.17:5555; adb -s 192.168.1.17:5555 shell "cmd package set-home-activity com.spocky.projengmenu/.ui.home.MainActivity"

# Disable stock Google TV launcher (optional, reversible)
adb -s 192.168.1.17:5555 shell "pm disable-user --user 0 com.google.android.tvlauncher"
```
*(To re-enable stock launcher: `adb shell pm enable com.google.android.tvlauncher`)*
