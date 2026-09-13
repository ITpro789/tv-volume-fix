# HDMI-CEC Bus Timing, Volume Runaway & Audio OSD Architecture

Engineering specifications for the custom **HDMI-CEC Volume Bridge, Debounce Shield & Real-Time On-Screen Display (OSD)** (`tv-volume-fix`).

---

## 1. The Physics: The 417-Baud HDMI-CEC Wire Limitation

HDMI Consumer Electronics Control (CEC) is a legacy single-wire serial bus running across pin 13 of an HDMI cable at **417 bits per second** (1 bit every 2.4 ms).

A single volume step transaction across HDMI-CEC requires:
1. TV sends `<User Control Pressed> [0x41]` (Volume Up) $\rightarrow$ ~88 ms
2. Android enforces CEC Inter-Repeat Timeout (IRT) $\rightarrow$ ~300 ms
3. TV sends `<User Control Release> [0x45]` $\rightarrow$ ~64 ms
4. External soundbar replies with `<Report Audio Status> [0xXX]` $\rightarrow$ ~88 ms

$$\text{Total Bus Round-Trip Time} \approx 350\text{ ms}$$

### The "Turbo Runaway" Phenomenon
When holding the physical Volume button on a remote:
* If volume pulses are fired into Android faster than ~450 ms (e.g. 350 ms or 400 ms), the quiet "silence window" between pulses collapses to $<25$ ms.
* To the Yamaha soundbar's internal digital signal processor (DSP), this looks like an uninterrupted continuous keypress.
* After 2–3 seconds, the soundbar firmware enters **hardware turbo acceleration**, skipping **+5 to +9 volume steps in a fraction of a second**:
  ```text
  [Live HDMI Bus Log at 400ms]
  23:53:25.996 -> Vol 36
  23:53:26.501 -> Vol 45   <--- JUMPED +9 NUMBERS IN 0.5 SECONDS!
  ...
  23:53:33.293 -> Vol 39
  23:53:33.745 -> Vol 31   <--- DROPPED -8 NUMBERS IN 0.4 SECONDS!
  ```
* When released, the bus is still backlogged, causing a massive overshoot where volume continues ramping for several seconds.

### The 0.48s Sweet Spot
Through empirical testing on the physical bus, **0.48s (480 ms)** with a **20 ms keypress pulse width** is the exact mathematical sweet spot:
* It maintains a clean **~130 ms silence window** between every beat.
* The soundbar cleanly registers every `<User Control Release>` before the next `<User Control Pressed>`.
* Volume steps strictly **1-by-1 (`31 -> 32 -> 33 -> 34`)**, with **zero runaway acceleration**.

---

## 2. Missing On-Screen Display (OSD) on HDMI-ARC

On Philips Android TVs (TPM191E / TPM171E), the firmware **intentionally hides the native volume bar** whenever an external HDMI-ARC soundbar is detected, assuming the user will look at the soundbar's front display.

### The Fix
1. **Sniff Raw Status**: The native C daemon streams `logcat -v brief -s HDMI:D` to extract the raw CEC status frame:
   ```text
   <Report Audio Status> 50:7A:XX
   ```
   where `XX` is the hex-encoded volume byte (bits 0–6 = volume 0–100, bit 7 = mute flag).
2. **Localhost Socket Dispatch**: The daemon sends the decoded volume over local TCP (`127.0.0.1:49200`).
3. **Android Overlay Service**: A lightweight companion app (`TvVolumeOverlay`) displays a high-contrast frosted-glass volume pill in the **top-right corner**:
   - Matches the soundbar display 1:1.
   - Auto-fades smoothly after 2.0 seconds of inactivity.

---

## 3. Architecture Overview

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
               │  • Shields Android from raw IR burst storms             │
               │  • Remaps: Netflix->Stremio, Tile->YouTube, Rakuten->Tivi│
               │  • Passes all non-volume keys cleanly to /dev/uinput     │
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

## 4. Cold-Boot Revival: Localhost ADB Loopback (`AdbStarter.java`)

### The Problem
When an Android TV undergoes a hard power cycle (unplugged or cold reboot), native background daemons running under `/data/local/tmp` are terminated, and standard non-system apps cannot execute root-level commands.

### The Innovation
The `TvVolumeOverlay` APK contains an embedded, zero-dependency Java ADB client (`AdbStarter.java`):
1. Upon `BOOT_COMPLETED`, `BootReceiver` invokes `AdbStarter.ensureBridgeRunningAsync()`.
2. Connects to the local Android ADB daemon over loopback TCP (`127.0.0.1:5555`).
3. Authenticates using an **embedded authorized RSA private key** (matching the TV's `/data/misc/adb/adb_keys`).
4. Spawns `/data/local/tmp/volume_bridge` in the background with zero user interaction and zero computer connection required.
