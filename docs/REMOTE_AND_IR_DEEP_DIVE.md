# Remote Control Engineering, Infrared Signals & Hardware Remapping

In-depth technical analysis of the **Philips Android TV remote control architecture**, physical infrared (IR) line-of-sight constraints, keypad contact degradation, and low-level kernel button remapping.

---

## 1. Remote Control Architecture: Replica IR vs. OEM Bluetooth

### The Discovery
Many Philips Android TVs ship from the factory with a dual IR/Bluetooth hybrid remote (which pairs over Bluetooth Low Energy for Google Assistant voice commands). However, replacement and replica remotes sold online are **strictly Infrared (IR) devices**:

* **Hardware**: Operates exclusively via standard **38 kHz modulated Infrared LED pulses** (Philips RC6 / NEC protocol).
* **Bluetooth Status**: Contains **NO Bluetooth radio chip or RF antenna**.
* **System Confirmation**:
  Running `dumpsys bluetooth_manager` on the TV confirms:
  ```text
  AdapterProperties
    Bonded devices:
  GATT Scanner Map
    Entries: 0
  ```
  The TV's Bluetooth controller has **0 bonded devices** and has never been paired with a remote.
* **Why This Matters**: When troubleshooting unresponsive remote controls on this setup, **never attempt Bluetooth pairing** (e.g. holding "PAIR" or launching Bluetooth discovery). The remote does not have Bluetooth hardware.

---

## 2. Physical IR Line-of-Sight & Soundbar Interference

### The TV's IR Sensor Placement
On the Philips 65" 4K TV (TPM191E platform), the hardware IR photodiode is positioned at the **very bottom edge/chin of the television** (directly under or beside the Philips logo at the bottom center/right).

### The Soundbar Blockade
The TV is paired with an external soundbar (e.g. Yamaha YSP / YAS series):
* Soundbars are typically 6 to 8 cm tall.
* When placed directly on the media console in front of the TV's feet, the soundbar physically sits in the direct line of sight between the TV's bottom IR receiver and couch level.
* **Result**: Direct IR light cannot reach the receiver. Signals only reach the TV by reflecting off the ceiling, floor, or opposite walls, attenuating the signal power by 85%–95%.
* **The Symptom**: The remote appears to "miss buttons" or requires pressing 10 times for 1 to register.
* **Fix**: Ensure the soundbar is placed slightly forward or lower, keeping the TV's bottom center chin completely unobstructed.

---

## 3. The "Ambilight Works Instantly, But D-Pad Misses" Paradox

During deep troubleshooting, a puzzling phenomenon was observed:
> *"When I press the Ambilight button, the Ambilight menu and lights react immediately on the very first touch. But Up, Down, Left, and Right hardly work and require 10 presses."*

This was investigated down to the hardware contacts and Linux kernel drivers. The cause is a combination of **mechanical wear** and **software routing**:

### A. Mechanical Keypad Contact Degradation
1. **Silicone Membrane Design**:
   Underneath the remote's rubber buttons are small conductive carbon/graphite pills. When a button is pressed, the pill bridges two interleaved copper traces on the circuit board, closing the circuit.
2. **Usage Disparity**:
   - **D-Pad (Up/Down/Left/Right/OK)**: Pressed tens of thousands of times over the life of the remote. The conductive carbon wears down, develops micro-fissures, and accumulates microscopic hand oils and board oxidation. Contact resistance increases from $<100\ \Omega$ to tens of kilo-ohms, requiring hard mashing or multiple presses for the remote microcontroller to detect a logic LOW.
   - **Ambilight Button**: Rarely pressed. The conductive carbon pill remains in factory-fresh condition, producing instant, low-resistance electrical closure on the lightest tap.
3. **Low Battery Amplification**:
   When AAA batteries drop below ~1.2V, the remote's internal logic thresholds become much less sensitive to high-resistance contact closures, compounding the D-pad hesitation.

### B. Firmware vs. Android View Hierarchy Routing
In addition to the physical contact condition, the TV processes these two button types through completely different code paths:

```
[ Ambilight Key (Scancode 228) ]
       │
       ▼
Linux Kernel /dev/input/event1
       │
       ▼
MediaTek tvremoteservice (JNI)
       │
       ▼
org.droidtv.GlobalKey (handleNonIntKey)  <── Bypasses Android window focus entirely!
       │
       ▼
Fires org.droidtv.action.AMBILIGHT_OSD (Instant Chassis Response)


[ D-Pad Keys (Scancode 103, 105, 106, 108) ]
       │
       ▼
Linux Kernel /dev/input/event1
       │
       ▼
Android EventHub
       │
       ▼
InputReader & InputDispatcher
       │
       ▼
Focused App Window (com.stremio.one)
       │
       ▼
App UI Thread / ViewRootImpl / RecyclerView (Requires active event loop)
```

Ambilight acts as a TV chassis hardware interrupt, while navigation keys must traverse the entire Android window focus and UI rendering pipeline.

---

## 4. Hardware Button Remapping (`volume_bridge`)

The replica remote includes vendor-branded shortcut buttons that standard Android TV settings cannot remap. The native C daemon (`/data/local/tmp/volume_bridge`) intercepts these raw scancodes at the Linux kernel level (`EVIOCGRAB 1`) and translates them into custom app launches:

| Remote Button | Hardware Scancode | Original Factory Action | Custom Remapped Action | Intent Executed |
|---|---|---|---|---|
| 🔴 **NETFLIX** | `632` | Netflix App | **Stremio** | `am start -n com.stremio.one/com.stremio.tv.MainActivity` |
| 🪟 **4-Tile / Windows** | `695` | Philips App Collection | **SmartTube (YouTube)** | `am start -n org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity` |
| 📺 **Rakuten TV** | `779` | Rakuten TV | **TiviMate (Live TV)** | `am start -n ar.tvplayer.tv/.ui.MainActivity` |
| ⚙️ **Settings / Sliders** | `357` / `757` | Android Full Settings | **Philips Frequent Settings** | `am start -a org.droidtv.action.EXPERIENCE_MENU` |

All other 748 remote keys (D-Pad, Back, Home, Numbers, Colors) are forwarded transparently to Android's virtual input device (`/dev/uinput`) with zero modification.

---

## 5. Instant Zero-Lag Fallback: Wi-Fi Virtual Remote

If the physical remote's batteries are depleted or line of sight is obstructed, the TV can be controlled with **zero lag and 100% reliability over local Wi-Fi**:
* **Samsung Galaxy S25 / Android**:
  Pull down the Quick Settings notification shade and tap **TV Remote** (or open the **Google TV** app).
* **Benefits**:
  - Connects directly to the TV's `com.google.android.tv.remote.service` over local Wi-Fi.
  - Zero line-of-sight required; works through walls.
  - Full touch D-pad, Back, Home, Volume, and full mobile keyboard input for text entry.
