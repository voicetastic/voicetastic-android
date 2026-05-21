# Voicetastic

> Push-to-talk voice messaging over the [Meshtastic](https://meshtastic.org/)
> LoRa mesh, plus a full-fat configuration UI for your radio — all in a
> single native Android app.

Voicetastic talks to a Meshtastic node over BLE, splices AMR-NB voice into
small packets that fit a LoRa frame, and lets you reach anyone on the mesh
without an internet connection.

> ⚠️ **Voice messaging is experimental and has not been field-tested yet.**
> The chunking, reassembly and playback paths are implemented end-to-end
> per [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md), but they have not been
> validated over real LoRa hardware between two devices. Expect edge
> cases (timing, lost-chunk recovery, partial playback) to need tuning.
> Text messaging and the configuration UI are the supported paths today.

---

## ✨ What you get

* **🔊 Voice messages over LoRa** *(⚠️ experimental — not yet tested on
  real hardware)* — record, broadcast or DM, receive, play. Audio is
  encoded with AMR-NB (8 selectable bitrates), chunked, and reassembled
  on the other side. Lost chunks become silence frames so the audio
  timeline stays in sync.
* **💬 Text chat** — standard Meshtastic text messaging on the channel and
  destination of your choice.
* **🛰️ Live mesh roster** — see every node your radio knows about: long
  name, short name, ID, last-heard, battery, SNR.
* **⚙️ Full radio configuration** — LoRa, Device, Position, Power, Network,
  Display, Bluetooth, Channels, and Owner — all editable from a single
  Compose screen, with dirty-tracking so an inbound refresh never clobbers
  your edits.
* **🛠️ Device actions** — refresh full config, reboot (with delay),
  factory reset.
* **🎛️ Voice tuning** — bitrate, max recording length, reassembly timeout,
  and "play partial on timeout" toggle.

---

## 📦 Requirements

| | |
|---|---|
| Android | API 29 (Android 10) or newer |
| Hardware | Bluetooth LE (required) |
| Radio | A [Meshtastic](https://meshtastic.org/)-compatible node running modern firmware, paired and powered on |
| Build | Android Studio (Hedgehog or newer) and JDK 17+ |

---

## 🚀 Quickstart

### Build & install

```bash
git clone <this-repo>
cd voicetastic

# Debug build straight to a connected device
./gradlew :app:installDebug

# Or just produce the APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

### First run

1. Power on your Meshtastic node and make sure Bluetooth is enabled on
   both phone and radio.
2. Launch Voicetastic and grant the requested permissions
   (`RECORD_AUDIO`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`,
   `ACCESS_FINE_LOCATION` on older devices).
3. Open the **Devices** tab → tap **Scan** → tap your radio in the list to
   connect.
4. Wait until the status card shows **✅ Connected to Meshtastic** and the
   node count appears.

### Send a text message

1. Switch to the **Chat** tab.
2. Optionally select a destination node (otherwise it broadcasts on the
   current channel).
3. Type your message → ➤.

### Send a voice message

> ⚠️ The voice path is **not yet validated end-to-end on physical
> radios**. The steps below describe the intended flow; expect rough
> edges and please file an issue if you try it.

1. From the **Chat** tab, tap and **hold** the mic button to record
   (release or wait for the configured max-duration cap to send).
2. The chunks are transmitted at ~500 ms cadence; the progress bar moves
   from 0 → 1.
3. On the receiving side, the message appears in the chat list with a
   ▶ button as soon as either the last chunk arrives or the assembly
   timeout fires (with partial play if enabled).

### Configure the radio

1. **Settings** tab → expand any of the section cards.
2. Edit the fields you want, then tap the section's **Apply** button.
3. The change is sent as a Meshtastic `AdminMessage` to your local node;
   the firmware persists it and re-emits the updated config back to the
   app.

---

## 📂 Project layout (high-level)

```
app/src/main/java/re/chasam/voicetastic/
├── MainActivity.kt          # entry point, runtime permissions, VM wiring
├── model/                   # plain data classes (chat, mesh, voice)
├── navigation/              # 3-tab Compose Scaffold (Devices / Chat / Settings)
├── service/
│   ├── MeshServiceManager.kt    # all BLE / protobuf plumbing
│   ├── MeshtasticBle.kt         # GATT UUIDs + node-id helpers
│   └── Portnums.kt              # Meshtastic port constants
├── ui/
│   ├── chat/                # chat screen + messaging view-model
│   ├── device/              # scan / list / connect screen
│   └── settings/            # per-section config screen + view-model
└── voice/                   # recorder, chunker, assembler, player
```

Full file-by-file map is in [`FEATURES.md`](./FEATURES.md#project-layout).

---

## 📚 Documentation

| Doc | What's in it |
|---|---|
| [`FEATURES.md`](./FEATURES.md) | App features, architecture, BLE GATT transport, Meshtastic protobuf protocol surface, admin/config flow, permissions, project layout |
| [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md) | Voice wire format: chunk header, AMR-NB handling, capacity, reassembly rules, timeout/partial-play behaviour |

---

## 🔌 How it talks to the radio (TL;DR)

```
   Voicetastic (Android, Kotlin/Compose)
            │
            │  BLE GATT — Meshtastic service
            │  6ba1b218-15a8-461f-9fa8-5dcae273eafd
            ▼
   ┌──────────────────────────┐
   │  TORADIO  (Write)        │  ← protobuf ToRadio
   │  FROMRADIO (Read)        │  → protobuf FromRadio
   │  FROMNUM  (Notify)       │  → "drain me" tickle
   └──────────────────────────┘
            │
            ▼
   Meshtastic firmware → LoRa → mesh
```

Text rides on `TEXT_MESSAGE_APP` (port 1), voice on `PRIVATE_APP` (port
256), config writes on `ADMIN_APP` (port 6) — see [`FEATURES.md`](./FEATURES.md)
for the full breakdown.

---

## 🤝 Contributing

This is a hobby/research project — issues, ideas, and merge requests are
welcome. When changing protocol-affecting code, please update
[`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md) and/or
[`FEATURES.md`](./FEATURES.md) in the same MR so the spec keeps tracking
the implementation.

---

## 🙏 Credits

* The [Meshtastic project](https://meshtastic.org/) for the open mesh
  firmware, BLE protocol, and protobuf definitions.
* AMR-NB encoding/decoding via Android's built-in `MediaRecorder` and
  `MediaPlayer`.

---

## 📄 License

Voicetastic is distributed under the
[GNU General Public License v3.0 or later](./LICENSE)
(`SPDX-License-Identifier: GPL-3.0-or-later`). The bundled
`voicetastic-desktop` core (under `third_party/`) carries the same
licence; see its own [`LICENSE`](./third_party/voicetastic-desktop/LICENSE)
for the full text.




