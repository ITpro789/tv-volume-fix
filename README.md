# Philips 65" Android TV (TPM191E) Optimization, Hardware Remapping & HDMI-CEC Volume Bridge

A production-grade, battle-tested engineering repository containing performance tuning, hardware button remappings, HDMI-ARC audio OSD, CEC runaway pacing, and 4K streaming optimizations for the **Philips 65" 4K Android TV (TPM191E / MediaTek MT5887 SoC, Android 12)**.

---

## 📑 Repository Structure & Documentation Index

```text
AndroidTV-Philips/
├── README.md                            # Complete master guide, architecture & quickstart
├── docs/
│   ├── HARDWARE_AND_SPECS.md            # SoC specs, eMMC health, 10/100 Ethernet trap & RAM limits
│   ├── STREMIO_STREAMING_GUIDE.md       # TrueHD lossless audio bottleneck, WEB-DL vs REMUX, RAM cache
│   ├── REMOTE_AND_IR_DEEP_DIVE.md       # Replica IR vs Bluetooth, physical line-of-sight & contact wear
│   ├── HDMI_CEC_VOLUME_BRIDGE.md        # 417-baud bus timing, 0.48s pacing & top-right volume OSD
│   └── SYSTEM_TUNING_AND_DEBLOAT.md     # Process limits, zero animations, AOT compilation & debloat
├── daemon/
│   ├── volume_bridge.c                  # Native C daemon (EVIOCGRAB shield + CEC sniffer + app remap)
│   ├── volume_bridge                    # Prebuilt ARMv7 native binary
│   └── build.ps1                        # 1-click NDK Clang compilation script
├── overlay-app/
│   ├── AndroidManifest.xml              # TV Leanback manifest with overlay permissions
│   ├── TvVolumeOverlay.apk              # Prebuilt, signed TV OSD APK with embedded AdbStarter
│   ├── build.ps1                        # Zero-dependency build script (javac + d8 + aapt2)
│   └── src/com/antigravity/tvvolume/
│       ├── TvVolumeService.java         # Real-time top-right OSD overlay service
│       ├── BootReceiver.java            # Auto-start on TV boot receiver
│       └── AdbStarter.java              # On-device localhost ADB loopback daemon starter
└── scripts/
    ├── apply_tuning.ps1                 # 1-click system performance & AOT compilation script
    ├── install.ps1                      # 1-click volume bridge & overlay APK installer
    ├── test_input.ps1                   # Screenshot capture & input device diagnostic tool
    └── start_bridge.sh                  # TV-side daemon launcher
```

---

## 🎯 What This Repository Solves

### 1. Dedicated Replica Remote Hardware Button Remappings
* **The Problem**: Replica and OEM replacement remotes have hardcoded vendor buttons (Netflix, Rakuten TV, Philips TV Collection) that cannot be changed through Android settings.
* **The Fix**: The native C daemon (`volume_bridge`) grabs `/dev/input/event1` (`EVIOCGRAB 1`), completely swallows vendor keycodes, and launches user apps instantly:
  * 🔴 **`NETFLIX` button** (Keycode `632`) $\rightarrow$ **Stremio** (`com.stremio.one`)
  * 🪟 **Windows / 4-Tile button** (Keycode `695`) $\rightarrow$ **SmartTube (Ad-Free YouTube)** (`org.smarttube.stable`)
  * 📺 **`Rakuten TV` button** (Keycode `779`) $\rightarrow$ **TiviMate (Live IPTV)** (`ar.tvplayer.tv`)
  * ⚙️ **`Settings / Sliders` button** (Keycode `357` / `757`) $\rightarrow$ **Philips Frequent Settings (Ambilight, Picture, Sound)** (`org.droidtv.action.EXPERIENCE_MENU`)

### 2. HDMI-ARC Soundbar Volume "Turbo Runaway" & Missing OSD
* **The Problem**: 
  1. Philips firmware hides the on-screen volume bar whenever an external soundbar is plugged in.
  2. Holding Volume Up/Down on the remote sends IR pulses faster than the 417-baud HDMI-CEC wire can process, causing external soundbars (Yamaha YSP/YAS) to enter **hardware turbo acceleration (+8 to +10 jump in 0.4s)**.
* **The Fix**: 
  - `volume_bridge` enforces a **60ms release debounce** and paces repeat pulses at **0.48s (480ms)** with a **20ms pulse width**, guaranteeing strictly 1-by-1 stepping with zero runaway.
  - Sniffs `<Report Audio Status>` from HDMI logcat and displays a high-contrast, frosted-glass volume pill in the **top-right corner** via `TvVolumeOverlay.apk`.

### 3. Stremio 4K Playback Freezing (Lossless Audio Bottleneck)
* **The Problem**: 50–60 GB 4K Blu-ray REMUXes stutter, freeze, or cause audio desync.
* **The Cause**: The MediaTek MT5887 SoC lacks a hardware decoder for **Dolby TrueHD 7.1** or **DTS-HD MA**, forcing the quad-core CPU to 100% load attempting software decoding.
* **The Fix**: Stream **15–25 GB 4K WEB-DL** releases featuring **DDP 5.1 / E-AC-3** (Dolby Digital Plus with Atmos), which hardware-decodes at 15% CPU load.
* **Storage Protection**: Set Stremio's **Cache Size to `No cache` (0)** to protect the worn (80–90% used) eMMC flash memory from 3.8 MB/s write bottlenecks.

### 4. Remote Input Lag & The "1 in 10 Presses" Diagnostic
* **The Problem**: Remote control appears sluggish or drops 9 out of 10 keypresses while Ambilight works instantly.
* **The Cause**: 
  1. **Physical Obstruction**: The TV's IR photodiode is at the very bottom chin. Soundbars placed directly in front block line-of-sight from the couch.
  2. **Keypad Contact Wear**: Carbon conductive pills under heavily used D-pad keys oxidize and wear down, raising resistance, while the rarely used Ambilight button retains factory conductivity.
  3. **Architecture**: Ambilight is routed directly to `org.droidtv.GlobalKey` at the TV chassis firmware level, bypassing Android's view hierarchy.

### 5. System Responsiveness & Memory Locking
* UI animations zeroed (`0.0x`) for instantaneous focus shifts.
* `max_cached_processes = 12` locked against Google Play Services reset via `device_config set_sync_disabled_for_tests persistent`.
* Core apps compiled to native ARM machine code (`cmd package compile -m speed -f`).
* Projectivy Launcher set as default home, dropping launcher RAM usage from ~250 MB down to ~35 MB.

---

## 🚀 1-Click Quickstart & Installation

### Prerequisites
* Android TV connected to local Wi-Fi with **Developer Options** and **Network Debugging** enabled.
* Target TV IP: `192.168.1.17:5555`.

### 1. Apply Full System Performance Tuning
From PowerShell on your PC:
```powershell
.\scripts\apply_tuning.ps1 -TvIp "192.168.1.17:5555"
```
*Locks cached process limits, sets 0.0x animation scales, disables bloatware daemons, and compiles all core media apps to native machine code.*

### 2. Deploy the Volume Bridge & Button Remapping Daemon
```powershell
.\scripts\install.ps1 -TvIp "192.168.1.17:5555"
```
*Pushes `volume_bridge` to `/data/local/tmp`, installs `TvVolumeOverlay.apk`, grants `SYSTEM_ALERT_WINDOW`, and starts the background service.*

---

## ⚡ Technical Summary Cards

| Topic | Verified Configuration | Key Benefit |
|---|---|---|
| **Network** | 5GHz Wi-Fi (`wlan0`) | Bypasses 100 Mbps physical Ethernet bottleneck; provides 130–180 Mbps throughput for 4K streams. |
| **Stremio Cache** | `cacheSize: 0` (RAM only) | Prevents thrashing the slow 3.8 MB/s eMMC flash drive (which has 80–90% lifetime used). |
| **Stremio Streams** | 15–25 GB 4K WEB-DL (`DDP 5.1` / `E-AC-3`) | Native hardware audio decoding with Dolby Atmos; avoids 100% CPU lockup from 7.1 TrueHD REMUXes. |
| **SmartTube PiP** | `PICTURE_IN_PICTURE` $\rightarrow$ `ignore` | Prevents YouTube from locking the hardware video decoder when switching to Stremio. |
| **CEC Volume Pacing** | 480 ms interval / 20 ms pulse | Eliminates Yamaha soundbar turbo acceleration jumps while maintaining responsive volume stepping. |
| **Cold Boot Loopback** | Embedded RSA key in `AdbStarter.java` | Automatically revives the native bridge daemon upon TV boot without requiring a PC. |

---

## 📖 Deep Dive Documentation Links
* [Hardware Specifications & Storage Benchmarks](docs/HARDWARE_AND_SPECS.md)
* [Stremio 4K Streaming & Codec Guide](docs/STREMIO_STREAMING_GUIDE.md)
* [Remote Control Engineering & Infrared Signal Analysis](docs/REMOTE_AND_IR_DEEP_DIVE.md)
* [HDMI-CEC Bus Timing & Volume Bridge Architecture](docs/HDMI_CEC_VOLUME_BRIDGE.md)
* [System Tuning, Process Limits & Debloating Guide](docs/SYSTEM_TUNING_AND_DEBLOAT.md)

---

## 📄 License
MIT License. Free to use, modify, and distribute.
