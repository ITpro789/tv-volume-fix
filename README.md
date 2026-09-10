# Android TV Volume Bridge & Real-Time CEC Overlay (`tv-volume-fix`)

A complete, low-latency, two-part system designed for **Android TVs (Philips TPM191E / TPM171E)** connected to an **external soundbar or AV receiver (Yamaha YSP / YAS series, Sonos, Denon)** via **HDMI-ARC/CEC**, especially when using **replica/replacement IR remotes**.

---

## 🎯 Problems Solved

### 1. The Replica IR Remote "Burst Storm" & Runaway Volume
* **Symptom**: Tapping Volume Up once jumps 3–4 numbers, or holding it down lags and suddenly enters **6x–7x turbo runaway mode**, jumping or dropping 40 numbers in 4 seconds with huge overshoot after releasing the button.
* **Root Cause**: Replica remotes flood the IR sensor with continuous rapid repeat bursts without proper debounce. Android's `InputReader` and `VolumeControlAction` get overwhelmed, bunching messages on the slow HDMI-CEC bus and tripping the soundbar's hardware hold-ramp DSP.

### 2. Missing On-Screen Volume Display (OSD) on HDMI-ARC
* **Symptom**: When an external audio system (soundbar/receiver) is connected via HDMI-ARC, Philips TV firmware **intentionally disables the TV's on-screen volume bar**. 
* **Result**: The user is forced to squint across the living room at the soundbar's tiny front LED digits.

---

## 🔬 The Physics & Why Standard Fixes Fail

### 1. The 417-Baud HDMI-CEC Wire Speed Limit
HDMI-CEC is an ancient single-wire serial bus running at **417 bits per second** (1 bit every 2.4ms).
A single volume cycle requires:
1. TV sends `<User Control Pressed> [0x41]` (~88ms)
2. Android enforces CEC Inter-Repeat Timeout (IRT) (~300ms)
3. TV sends `<User Control Release> [0x45]` (~64ms)
4. Soundbar replies `<Report Audio Status> [0xXX]` (~88ms)

**Total bus round-trip time: ~350ms.**

### 2. The 0.40s "Turbo Jump" Threshold
If pulses are sent faster than ~480ms (e.g. 375ms or 400ms), wire silence collapses to $<25$ms. To the soundbar's DSP, this looks like an uninterrupted continuous keypress. After 2–3 seconds, the soundbar's internal firmware triggers **turbo acceleration mode**, skipping +5 to +9 numbers in a single burst:
```text
[Live HDMI Log at 400ms]
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
               │  • Blocks Android from seeing raw IR bursts             │
               │  • Passes all 751 non-volume keys to /dev/uinput clone  │
               │  • Enforces 60ms release debounce                       │
               │  • Emits clean 20ms pulses at strictly paced 480ms gap  │
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

## 📦 Components

1. **`daemon/volume_bridge.c`** *(Native C Daemon)*:
   * Grabs `/dev/input/event1` exclusively (`ioctl EVIOCGRAB`).
   * Creates a virtual clone via `/dev/uinput` forwarding all 751 remote keys transparently.
   * Debounces physical key release within 60ms.
   * Paces volume repeat to 480ms with 20ms pulse duration.
   * Monitors real-time HDMI logcat for `<Report Audio Status>` and dispatches it over TCP port 49200 in $<0.5$ms.

2. **`overlay-app/`** *(TvVolumeOverlay APK)*:
   * Lightweight foreground service using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
   * Floating, frosted-glass dark card with high-contrast bold white text (`VOL 36`) and cyan progress bar.
   * Tucked into the **Top-Right** corner so it never interferes with video, menus, or subtitles.
   * Auto-fades smoothly 2 seconds after volume adjustment stops.
   * Listens on `127.0.0.1:49200` and `com.antigravity.tvvolume.UPDATE_VOLUME` broadcast.
   * Autostarts on boot via `BootReceiver`.

3. **`scripts/install.ps1`** *(One-Click ADB Installer)*:
   * Connects to the TV, deploys the daemon, installs the APK, sets permissions, and starts both services automatically.

---

## 🚀 Quick Start (Installation)

### Prerequisites
* Enable **Developer Options** and **USB / Network Debugging** on your Android TV.
* Ensure your PC has ADB connected to your TV's local IP address (e.g. `192.168.1.17:5555`).

### One-Click Install
From PowerShell, run:
```powershell
.\scripts\install.ps1 -TvIp "192.168.1.17:5555"
```

The installer will:
1. Connect to your TV over ADB.
2. Push and deploy `volume_bridge` to `/data/local/tmp/volume_bridge`.
3. Install `TvVolumeOverlay.apk`.
4. Grant `SYSTEM_ALERT_WINDOW` permission.
5. Start both the overlay service and the native daemon.

---

## 🛠️ Building from Source

### 1. Compile the Native Daemon
Requires Android NDK Clang:
```powershell
cd daemon
.\build.ps1 -NdkPath "C:\path\to\android-ndk"
```

### 2. Build the Overlay APK
Requires Java 17 and Android SDK build-tools (zero Gradle/Android Studio needed):
```powershell
cd overlay-app
.\build.ps1 -SdkPath "C:\path\to\Android\Sdk"
```

---

## ⚙️ Configuration & Tuning

In `daemon/volume_bridge.c`:
* `HOLD_REPEAT_STEP_MS`: Repeat interval during hold (Default: `480`ms). Do NOT set below ~450ms or Yamaha soundbars will trigger turbo runaway.
* `RELEASE_DEBOUNCE_MS`: Time of silence before confirming physical release (Default: `60`ms).
* `HOLD_START_DELAY_MS`: Delay before repeat kicks in (Default: `380`ms).

In `overlay-app/src/com/antigravity/tvvolume/TvVolumeService.java`:
* Position: `params.x = dpToPx(16); params.y = dpToPx(16);` (`Gravity.TOP | Gravity.END`).
* Dismiss timeout: `mHandler.postDelayed(mHideRunnable, 2000);` (Default: 2.0 seconds).
* Accent color: Cyan (`#00E5FF`) on dark frosted charcoal (`#EE181818`).

---

## 📄 License
MIT License. Free to use, modify, and distribute.
