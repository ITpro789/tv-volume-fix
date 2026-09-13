# Stremio 4K Streaming Optimization & Hardware Codec Guide

Comprehensive optimization guide for running **Stremio on Philips Android TV (TPM191E)** without buffering, audio desync, or system freezing.

---

## 1. The 50–60 GB REMUX Mystery: Bandwidth or TV Decoder?

### The Question
> *"My Stremio struggles with 50–60 GB movies. Why is that? Is it bandwidth or can the TV not decode files that big?"*

### The Answer: **It is an Audio Decoding Bottleneck (Lossless Audio), NOT Video or Bandwidth.**

Many users assume high-bitrate 4K files buffer because of file size or video resolution. On this MediaTek MT5887 TV:
1. **Video Hardware Decoder**:
   - The TV possesses a dedicated hardware **HEVC / H.265 Main 10** decoder that can easily decode 4K 10-bit HDR video up to 80–100 Mbps.
2. **Audio Hardware Decoder (The Bottleneck)**:
   - 50–60 GB 4K Blu-ray REMUX files almost always feature uncompressed, lossless audio tracks:
     - **Dolby TrueHD (with Dolby Atmos)**
     - **DTS-HD Master Audio (DTS-HD MA)**
   - The Philips TV's internal MediaTek audio DSP **does NOT have a hardware license/decoder for TrueHD 7.1 or DTS-HD MA**.
   - When Stremio or ExoPlayer encounters a TrueHD track without an AV receiver capable of raw bitstreaming, it attempts to **software-decode 8 channels of 24-bit 96kHz lossless audio using the TV's quad-core Cortex-A53 CPU**.
   - Software decoding a 7.1 TrueHD stream consumes **100% of all 4 CPU cores**.
   - Result: Video frames drop, audio goes out of sync, the UI becomes unresponsive, and playback halts completely.

---

## 2. Stream Selection: What to Choose vs. What to Avoid

| Quality Tier | Stream Naming Example | Video Codec | Audio Track | File Size | TV Playback Result |
|---|---|---|---|---|---|
| 🟢 **PERFECT (Recommended)** | `Movie.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR10.H.265` | HEVC / H.265 10-bit | **DDP 5.1 / E-AC-3** (Dolby Digital Plus with Atmos) | **15 – 25 GB** | **Flawless.** Instant start, 60fps, full Dolby Atmos passed to soundbar via HDMI-ARC, 15% CPU load. |
| 🟢 **EXCELLENT** | `Movie.2024.2160p.HDR.DDP5.1.x265` | HEVC / H.265 | **DDP 5.1 / AC-3** | **8 – 16 GB** | **Flawless.** Super lightweight, great HDR color. |
| 🟡 **ACCEPTABLE** | `Movie.2024.2160p.UHD.BluRay.x265.DDP5.1` | HEVC / H.265 (Encode) | **DDP 5.1** (Secondary track) | **20 – 35 GB** | **Smooth**, provided the audio stream is DDP 5.1 and not TrueHD. |
| 🔴 **AVOID (Buffering & Lag)** | `Movie.2024.2160p.UHD.BluRay.REMUX.HEVC.TrueHD.7.1.Atmos` | HEVC / H.265 (Remux) | **Dolby TrueHD 7.1** | **55 – 80 GB** | **Stutters / Freezes.** CPU hits 100% software-decoding TrueHD. |
| 🔴 **AVOID** | `Movie.2024.2160p.REMUX.DTS-HD.MA.7.1` | HEVC / H.265 (Remux) | **DTS-HD MA 7.1** | **50 – 70 GB** | **Stutters / Freezes.** Lossless DTS software decode overload. |

> **Pro Tip**: In Stremio / Torrentio / Debrid, look for streams tagged **`WEB-DL`** or **`WEBRip`** with **`DDP5.1`** or **`EAC3`**. These are identical to the official streams served by Apple TV+, Netflix, Disney+, and Prime Video, delivering pristine 4K Dolby Vision/HDR with native Dolby Atmos that your Yamaha soundbar decodes in hardware.

---

## 3. The "Cache Size: No Cache" (0) Tweak

### The Problem with Default Caching
By default, Stremio's streaming server attempts to cache downloaded video chunks to internal flash storage (`/data/user/0/com.stremio.one/cache`).
* The TV's internal eMMC flash has a sequential write speed of only **~3.8 MB/s**.
* A 4K movie downloading at 8–12 MB/s violently overruns the flash write buffer.
* The kernel blocks all processes waiting for I/O (`D` state processes), freezing the remote control and Android UI.
* Constant writing also rapidly wears down the eMMC chip (which already has **80%–90% of its lifetime used**).

### The Fix
In Stremio:
1. Go to **Settings $\rightarrow$ Streaming / Server**.
2. Set **Cache Size** to **`No cache`** (or `0`).
3. Set **Torrent Profile** to **`Fast`** or **`Ultra Fast`**.

### Does "No Cache" Impact Rewinding / Fast-Forwarding?
* **Common Concern**: *"If I set No cache, does that mean I can't rewind or fast-forward easily?"*
* **Reality**:
  * With **Debrid streaming (Real-Debrid / AllDebrid / Premiumize)**, you are streaming over high-speed HTTPS directly from high-bandwidth cloud servers.
  * When you skip forward or backward, Stremio simply sends an HTTP Range request (`bytes=...`) to Debrid's CDN. Debrid seeks almost instantly (<1 second) regardless of whether local disk cache exists.
  * Setting **Cache Size to 0** forces Stremio to keep a lightweight sliding buffer in **RAM only**, saving your flash drive from destruction while keeping seeks fast.

---

## 4. Stremio Streaming Server Configuration (`127.0.0.1:11470`)

Stremio on Android TV runs a persistent local HTTP streaming proxy on port `11470`. You can inspect or update its settings over ADB:

```powershell
# Check current streaming server settings
adb connect 192.168.1.17:5555; adb -s 192.168.1.17:5555 shell "curl -s http://127.0.0.1:11470/settings"
```

Verified optimal JSON configuration:
```json
{
  "cacheSize": 0,
  "btMaxConnections": 55,
  "btHandshakeTimeout": 20000,
  "btRequestTimeout": 4000,
  "btDownloadSpeedLimit": 0,
  "btUploadSpeedLimit": 0
}
```

---

## 5. Summary Checklist for Flawless Playback
- [x] Stremio Cache Size set to **No cache (0)**.
- [x] Select **15–25 GB 4K WEB-DL** streams with **DDP 5.1 / Atmos** instead of 60 GB REMUX TrueHD.
- [x] TV connected via **5GHz Wi-Fi** (not 10/100 Ethernet).
- [x] SmartTube PiP set to **`ignore`** so Stremio's video hardware decoder is never held hostage.
