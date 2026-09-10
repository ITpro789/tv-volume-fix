# Android TV Volume Bridge, CEC Overlay & Remote Remapper (`tv-volume-fix`)

A complete, low-latency, fully automated system designed for **Android TVs (Philips TPM191E / TPM171E)** connected to an **external soundbar or AV receiver (Yamaha YSP / YAS series, Sonos, Denon)** via **HDMI-ARC/CEC**, with native hardware button remapping for **replica/replacement IR remotes**.

---

## 🎯 What This System Solves

### 1. Volume "Runaway" & CEC Timing Desync
* **The Problem**: Tapping Volume Up once jumps 3–4 steps, or holding it down enters **6x–7x turbo runaway mode**, jumping or dropping 40 numbers in 4 seconds with huge overshoot when released.
* **The Fix**: Intercepts the raw IR remote stream via `EVIOCGRAB` at the Linux kernel level, enforces a 60ms release debounce, and paces pulses at **0.48s** (the exact physical speed limit of the 417-baud HDMI-CEC bus).

### 2. Missing On-Screen Volume Display (OSD) on HDMI-ARC
* **The Problem**: Philips TV firmware **intentionally suppresses the on-screen volume bar** when an external HDMI-ARC soundbar is plugged in, forcing users to squint across the room at the soundbar's tiny front display.
* **The Fix**: Intercepts the soundbar's raw `<Report Audio Status>` messages directly from the HDMI-CEC wire in real time and paints a high-contrast, compact, frosted-glass volume pill in the **top-right corner** that matches the soundbar's display 1:1 and auto-dismisses after 2 seconds.

### 3. Dedicated Remote App Button Remapping
* **The Problem**: Replica and OEM remotes have hardcoded vendor buttons (Netflix, Rakuten TV, Philips TV Collection) that cannot be remapped through standard TV settings.
* **The Fix**: Intercepts the hardware key codes before Android receives them, completely swallowing the original action and launching your preferred apps instantly:
  * 🔴 **`NETFLIX` button** (Keycode `632`) $\rightarrow$ Opens **Stremio** (`com.stremio.one`)
  * 🪟 **"Windows" / 4-Tile button** (Keycode `695`) $\rightarrow$ Opens **YouTube (SmartTube)** (`org.smarttube.stable`)
  * 📺 **`Rakuten TV` button** (Keycode `779`) $\rightarrow$ Opens **TiviMate** (`ar.tvplayer.tv`)
  * ⚙️ **`Settings / Sliders` button** (Keycode `757`) $\rightarrow$ Opens **Philips Quick Settings (Picture, Sound, Ambilight)** (`org.droidtv.action.EXPERIENCE_MENU`)

### 4. 100% Automated Cold-Boot Revival (Zero PC Needed)
* **The Problem**: Native shell/input daemons normally terminate when an Android TV undergoes a hard power cycle (unplugged from the wall or cold reboot).
* **The Fix**: The installed Android app (`TvVolumeOverlay`) contains a built-in **Localhost ADB Loopback Client** (`AdbStarter.java`). When Android finishes booting (`BOOT_COMPLETED`), the app connects to `127.0.0.1:5555`, authenticates using an embedded authorized RSA key in $<100$ms, and revives the native bridge daemon automatically.

---

## 🔬 The Physics & Why Standard Fixes Fail

### 1. The 417-Baud HDMI-CEC Wire Limitation
HDMI-CEC is a single-wire serial bus running at **417 bits per second** (1 bit every 2.4ms).
A complete volume cycle requires:
1. TV sends `<User Control Pressed> [0x41]` (~88ms)
2. Android enforces CEC Inter-Repeat Timeout (IRT) (~300ms)
3. TV sends `<User Control Release> [0x45]` (~64ms)
4. Soundbar replies `<Report Audio Status> [0xXX]` (~88ms)

**Total bus round-trip time: ~350ms.**

### 2. The 0.40s "Turbo Jump" Threshold
If pulses are sent faster than ~480ms (e.g. 375ms or 400ms), wire silence collapses to $<25$ms. To the soundbar's DSP, this looks like an uninterrupted continuous keypress. After 2–3 seconds, the soundbar's internal firmware triggers **hardware turbo acceleration**, skipping +5 to +9 numbers in a single burst:
```text
[Live HDMI Bus Log at 400ms]
23:53:25.996 -> Vol 36
23:53:26.501 -> Vol 45   <--- JUMPED +9 NUMBERS IN 0.5 SECONDS!
...
23:53:33.293 -> Vol 39
23:53:33.745 -> Vol 31   <--- JUMPED -8 NUMBERS IN 0.4 SECONDS!
```

### 3. The 0.48s Sweet Spot
At **0.48s (480ms)** with a **20ms keypress pulse width**, the bus maintains a clean **~130ms silence window** between every beat. The soundbar cleanly hears every `Release` before the next `Pressed`, guaranteeing **strictly 1-by-1 stepping** (`31 -> 32 -> 33 -> 34`) with zero runaway jumps.

---

## 🏗️ Architecture

```
                                  [ Replica IR Remote ]
                                            │
                                            ▼
                           /dev/input/event1 (TPV_MutilRC)
                                            │
               ┌────────────────────────────┴────────────────────────────┐
               │              volume_bridge (Native C Daemon)             │
               │                                                         │
               │  [EVIOCGRAB 1]                                          │
               │  • Blocks Android from raw IR burst storms               │
               │  • Remaps: Netflix->Stremio, Tile->YouTube, Rakuten->Tivi│
               │  • Passes all other 748 non-volume keys to /dev/uinput   │
               │  • Enforces 60ms debounce & strictly paced 480ms pulses  │
               └────────────────────────────┬────────────────────────────┘
                                            │
                   ┌────────────────────────┴────────────────────────┐
                   ▼                                                 ▼
          [ /dev/uinput clone ]                           [ CEC Monitor Thread ]
                   │                                                 │
          Android AudioService                            Streams `logcat -s HDMI:D`
                   │                                      Extracts `<Report Audio Status>`
            HDMI-CEC Wire                                            │
                   │                                         Sends live volume via
                   ▼                                       localhost TCP (127.0.0.1:49200)
          Yamaha Soundbar DSP                                        │
          (Changes Volume +1)                                        ▼
                   │                                    TvVolumeOverlay (Android App)
                   ▼                                    • TYPE_APPLICATION_OVERLAY
           Sends CEC Status                             • Tucked in Top-Right corner
        <Report Audio Status> [XX]                      • Displays VOL XX & Cyan Bar
                                                        • Auto-fades after 2.0s
```

---

## 📦 Repository Structure

```text
tv-volume-fix/
├── README.md                      # Comprehensive technical guide & architecture
├── .gitignore
├── daemon/
│   ├── volume_bridge.c            # Native C daemon (EVIOCGRAB shield + CEC sniffer + app remap)
│   ├── volume_bridge              # Prebuilt ARMv7 native binary
│   └── build.ps1                  # 1-click NDK Clang compilation script
├── overlay-app/
│   ├── AndroidManifest.xml        # TV Leanback manifest with overlay permissions
│   ├── TvVolumeOverlay.apk        # Prebuilt, signed TV OSD APK with embedded AdbStarter
│   ├── build.ps1                  # Zero-dependency build script (javac + d8 + aapt2)
│   └── src/com/antigravity/tvvolume/
│       ├── TvVolumeService.java   # Real-time top-right OSD overlay service
│       ├── BootReceiver.java      # Auto-start on TV boot receiver
│       └── AdbStarter.java        # On-device localhost ADB loopback daemon starter
└── scripts/
    ├── install.ps1                # One-click ADB deployment script (TV IP argument)
    └── start_bridge.sh            # TV-side daemon launcher
```

---

## 🚀 Quick Start (Installation)

### Prerequisites
* Enable **Developer Options** and **Network Debugging** on your Android TV.
* Verify your TV's local IP address (e.g. `192.168.1.17`).

### One-Click Install
From PowerShell, run:
```powershell
.\scripts\install.ps1 -TvIp "192.168.1.17:5555"
```

The script will automatically:
1. Connect to the TV over ADB.
2. Push and deploy `volume_bridge` to `/data/local/tmp/volume_bridge`.
3. Install `TvVolumeOverlay.apk`.
4. Grant `SYSTEM_ALERT_WINDOW` permission.
5. Start both the overlay service and the native daemon.

---

## ⚙️ Configuration & Customization

### Pacing & Debounce
In `daemon/volume_bridge.c`:
* `HOLD_REPEAT_STEP_MS`: Repeat interval during hold (Default: `480`ms). Do NOT set below ~450ms or Yamaha soundbars will trigger turbo runaway.
* `RELEASE_DEBOUNCE_MS`: Time of silence before confirming physical release (Default: `60`ms).
* `HOLD_START_DELAY_MS`: Delay before repeat kicks in (Default: `380`ms).

### Button Remappings
In `daemon/volume_bridge.c`:
```c
// Code 632: NETFLIX -> Stremio
launch_cmd_async("am start -n com.stremio.one/com.stremio.tv.MainActivity");

// Code 695: Windows/4-Tile -> YouTube (SmartTube)
launch_cmd_async("am start -n org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity");

// Code 779: Rakuten TV -> TiviMate
launch_cmd_async("am start -n ar.tvplayer.tv/.ui.MainActivity");

// Code 757: Settings / Sliders -> Philips Quick Settings (Picture, Sound, Ambilight)
launch_cmd_async("am start -a org.droidtv.action.EXPERIENCE_MENU");
```

---

## 📄 License
MIT License. Free to use, modify, and distribute.
