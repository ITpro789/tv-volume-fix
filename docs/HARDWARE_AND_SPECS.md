# Hardware Specifications & Platform Architecture

Comprehensive hardware audit, benchmarks, and platform constraints for the **Philips 65" 4K Android TV (TPM191E)**.

---

## 1. System Specifications

| Component | Specification | Real-World Performance & Implications |
|---|---|---|
| **SoC** | MediaTek MT5887 (MT5670 family) | Quad-Core ARM Cortex-A53 @ 1.30 GHz (64-bit armv8l running 32-bit userland) |
| **GPU** | ARM Mali-G51 MP2 | OpenGL ES 3.2, Vulkan 1.1; UI rendered strictly at 1080p (1920x1080 @ 60Hz, 320 dpi) |
| **Operating System** | Android 12 (Google TV / Android TV Leanback) | Linux Kernel 4.9.310 |
| **RAM** | **2.0 GB Physical LPDDR3/LPDDR4** | ~1.95 GB usable; baseline system consumes ~1.2 GB, leaving **500–800 MB available for user apps** |
| **ZRAM (Swap)** | 600 MB Compressed in RAM | Used heavily during extended uptimes; swap thrashing occurs if >12 cached processes are kept |
| **Internal Storage** | **16 GB eMMC 5.1 Flash** | Partition `/data` has ~2.7 GB free; **sequential write speed is capped at ~3.8 MB/s** |
| **eMMC Health** | `EmmcLifetime: 80%-90% used` | Flash memory is heavily worn. **Excessive disk writes must be strictly avoided** |
| **Display Panel** | 65" 4K UHD (3840x2160 @ 60Hz) | Supports Dolby Vision, HDR10, HDR10+, HLG |
| **Local Wi-Fi** | Dual-Band 802.11ac (2.4GHz / 5GHz `wlan0`) | **Recommended connection.** Achieves ~270 Mbps PHY link rate, delivering 110+ Mbps real-world throughput |
| **Physical Ethernet** | **10/100 Fast Ethernet (RJ45)** | **CRITICAL BOTTLENECK:** Capped at 100 Mbps PHY (~92 Mbps real-world). **Always use 5GHz Wi-Fi instead of Ethernet** |
| **HDMI Ports** | 4x HDMI 2.0b with HDCP 2.3 | HDMI 1 supports ARC (Audio Return Channel) + CEC (EasyLink) |

---

## 2. Critical Bottlenecks & Hardware Realities

### A. The 10/100 Ethernet Trap
Most users assume an Ethernet cable is faster than Wi-Fi. On this TV (and most Android TVs in this class), the physical Ethernet controller is limited to **10/100 Mbps Fast Ethernet**:
* **Real-world Ethernet bandwidth**: ~90–94 Mbps maximum.
* High-bitrate 4K streaming (e.g. 50–60 GB REMUX files with bitrate spikes over 100 Mbps) will buffer indefinitely over Ethernet.
* **Solution**: Keep the TV connected to the **5GHz Wi-Fi band** (`wlan0`). Real-world throughput reaches **130–180 Mbps**, completely eliminating network buffering for 4K streaming.

### B. Slow eMMC Flash (3.8 MB/s Sequential Write Speed)
Benchmarking the internal `/data` partition shows sequential write throughput of **3.8 MB/s** to **4.5 MB/s**. In addition, the TV's internal storage monitor logs:
```text
TVStorageMonitorService: EmmcLifetime new: EmmcLifetime 80%-90% device lifetime used
```
* **Implication**: Any app that downloads chunks or writes disk cache directly to storage (e.g., Stremio default disk caching) will saturate the eMMC I/O bus, driving system I/O wait to 100% and locking up the Android UI.
* **Solution**: Configure all streaming engines to **RAM-only caching (`cacheSize: 0`)**.

### C. 2.0 GB RAM Constraint & Process Eviction
With only 2 GB of RAM, running large video engines (Stremio + ExoPlayer), background launchers (Projectivy), and IPTV decoders simultaneously leads to aggressive memory pressure:
* If the system's `max_cached_processes` is set too high (e.g., default 32), the kernel runs out of pages and thrashes ZRAM (500+ MB swap usage).
* If set too low (e.g., 3), switching from YouTube to Stremio instantly kills Stremio.
* **The Sweet Spot**: **`max_cached_processes = 12`** and **`max_empty_processes = 6`**, locked persistently via Android's `device_config` test flag.
