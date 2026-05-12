# Voicetastic — Features & Protocols

Voicetastic is an Android client for the [Meshtastic](https://meshtastic.org/)
mesh-radio ecosystem. In addition to the standard text-messaging surface it
adds **voice messaging** carried over the same LoRa mesh, and exposes the
full set of Meshtastic device-configuration knobs from a single Compose UI.

> **Companion documents:**
> - [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md) — wire format for voice messages.
> - [`INTEGRATION.md`](./INTEGRATION.md) — how the Rust `voicetastic-core`
>   crate is linked via UniFFI into the Android build.

---

## Table of contents

1. [Top-level features](#top-level-features)
2. [Architecture](#architecture)
3. [Transport layer](#transport-layer)
4. [Application protocol — Meshtastic protobuf](#application-protocol--meshtastic-protobuf)
5. [Voice protocol](#voice-protocol)
6. [Configuration & admin protocol](#configuration--admin-protocol)
7. [Permissions & manifests](#permissions--manifests)
8. [Project layout](#project-layout)

---

## Top-level features

| Feature | Notes |
|---|---|
| **BLE device discovery & connection** | Connects to any Meshtastic peripheral via BLE GATT; transport adapter handles characteristic I/O. |
| **USB serial connection** | USB-OTG via CDC / CP210x / CH34x / FTDI at 115 200 baud, with 0x94 stream-framing. |
| **Persistent mesh status** | Live connection state, my node ID, firmware version, and the count + roster of nodes the radio currently knows about. |
| **Text messaging** | Send/receive on `TEXT_MESSAGE_APP` (port 1). Broadcast or directed to a specific node ID, on the user-selected channel. |
| **Voice messaging (v2)** | Records AMR-NB audio → Rust `voicetastic_core::voice::build_message` chunks with 12-byte v2 header, optional Reed-Solomon FEC + AES-GCM crypto → shipped on `PRIVATE_APP` (port 256) → Rust `VoiceAssembler` reassembles + NACK support. See [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md). |
| **Mesh node browser** | Live list of nodes (long name, short name, node ID, last-heard, battery, SNR). |
| **Full radio configuration UI** | LoRa, Device, Position, Power, Network, Display, Bluetooth, Channels, Owner. Every section is read-on-connect, dirty-tracked, and writable through admin messages. |
| **Voice-message tuning** | Bitrate (8 AMR-NB modes), max recording duration, assembly timeout, and "play partial on timeout" toggle. |
| **Device actions** | Refresh full config, reboot (with delay), factory reset. |
| **Out-of-order / partial voice play** | Voice chunks may arrive out of order or be lost; the Rust assembler fills missing slots so the audio timeline stays aligned. |

---

## Architecture

The runtime is split between Kotlin (UI, platform I/O) and an embedded
Rust library (`voicetastic-core`) linked via
[UniFFI](https://github.com/mozilla/uniffi-rs). Rust owns the Meshtastic
state machine, protobuf codec, and voice protocol; Kotlin supplies the
platform transports (BLE GATT, USB serial) and the Compose UI.

```
┌────────────────────────────────────────────────────────────────────┐
│                           MainActivity                             │
│  • runtime permissions  • wires VMs                                │
└────────────────┬────────────────┬────────────────┬─────────────────┘
                 │                │                │
        ┌────────▼─────┐ ┌────────▼──────┐ ┌───────▼─────────┐
        │ DeviceScreen │ │  ChatScreen   │ │ SettingsScreen  │
        └────────┬─────┘ └───────┬───────┘ └─────────┬───────┘
                 │               │                   │
                 │       ┌───────▼────────┐ ┌────────▼──────────┐
                 │       │ MessagingVM    │ │ ConfigViewModel   │
                 │       │ (text+voice)   │ │ (per-section UI   │
                 │       └───────┬────────┘ │  state + dirty    │
                 │               │          │  tracking)        │
                 │               │          └────────┬──────────┘
                 │               │                   │
                 ▼               ▼                   ▼
       ┌───────────────────────────────────────────────────────┐
       │                MeshServiceManager                     │
       │                                                       │
       │  • Registers Rust-side listeners (state, text, data,  │
       │    config) and publishes StateFlows to the UI layer    │
       │  • Routes send calls through Rust MeshService          │
       │  • Admin writes (writeConfig / writeChannel /          │
       │    writeOwner / reboot / factory_reset)               │
       │  • Per-section StateFlow caches (LoRa, Device, …)     │
       └──────────┬───────────────────────┬────────────────────┘
                  │                       │
       ┌──────────▼───────────┐  ┌────────▼──────────┐
       │  Rust MeshService    │  │  RustMeshSession   │
       │  (UniFFI bindings)   │  │  (lifecycle owner) │
       │                      │  └────────┬───────────┘
       │  • WantConfigId      │           │
       │  • state machine     │  ┌────────▼───────────────────┐
       │  • packet routing    │  │ BleMeshTransport (GATT)    │
       │  • voice build/asm   │  │ UsbMeshTransportV2 (serial)│
       └──────────────────────┘  │ ← implements UniFFI        │
                                 │   MeshTransport trait      │
                                 └────────┬───────────────────┘
                                          │
                              ┌───────────▼────────────┐
                              │  Android BLE / USB API │
                              └────────────────────────┘
```

### Key observable state (StateFlow / SharedFlow)

| Flow | Type | What it carries |
|---|---|---|
| `connectionState` | `StateFlow<String>` | `"DISCONNECTED" \| "CONNECTING" \| "CONNECTED"` |
| `activeTransport` | `StateFlow<TransportType>` | `NONE \| BLE \| USB` |
| `myNodeId` | `StateFlow<String?>` | Local node, formatted as `!aabbccdd` |
| `firmwareVersion` | `StateFlow<String?>` | From `DeviceMetadata.firmware_version` |
| `nodes` | `StateFlow<List<MeshNode>>` | Roster known to the connected node |
| `radioConfig` / `deviceConfig` / … | `StateFlow<…?>` | Per-section LoRa/etc. config protos |
| `channels` | `StateFlow<List<Channel>>` | The 8 Meshtastic channels |
| `owner` | `StateFlow<User?>` | Local node's user record |
| `incomingTextMessages` | `SharedFlow<IncomingText>` | Decrypted text frames from the mesh |
| `incomingDataMessages` | `SharedFlow<IncomingData>` | Non-text data frames (used for voice) |
| `configComplete` | `SharedFlow<Int>` | Fires when the firmware finishes a `want_config_id` burst |

---

## Transport layer

Two transport adapters implement the UniFFI `MeshTransport` foreign trait
so the Rust `MeshService` can drive any Meshtastic radio:

### BLE (`BleMeshTransport`)

Opens a GATT connection, discovers the Meshtastic service, negotiates
MTU, enables `FROMNUM` notifications, then:

- **Outbound:** `writeToRadio()` → serialised BLE characteristic write.
- **Inbound:** `FROMNUM` notify triggers drain reads of `FROMRADIO`;
  payloads are pushed into a Rust-side `MeshTransportSink`.
- **Safety net:** A 30 s polling loop re-reads `FROMRADIO` to catch
  missed notifications.

UUIDs are defined in `MeshtasticBle.kt`.

### USB Serial (`UsbMeshTransportV2`)

Wraps the platform-level `UsbMeshTransport` (CDC/CP210x/CH34x/FTDI at
115 200 8N1) and adapts it to the UniFFI trait. Inbound `FromRadio`
frames — already deframed by `MeshSerialFraming` (0x94 SLIP variant) —
are pumped into the Rust sink.

### Session lifecycle (`RustMeshSession`)

`RustMeshSession` is the lifecycle owner that ties a transport adapter
to the Rust `MeshService`. On BLE it waits for the GATT setup callback;
on USB it connects immediately. `close()` tears everything down:
Rust disconnect → transport shutdown → sink shutdown.

### `want_config_id` bootstrap

The Rust `MeshService` automatically sends:

```protobuf
ToRadio { want_config_id = <non-zero u32> }
```

The firmware replies with a burst of `FromRadio` messages (MyNodeInfo,
NodeInfo[], Channel[], Config[], ModuleConfig[], config_complete_id).
Rust parses these internally and fires the registered Kotlin listeners
(`MeshStateListener`, `MeshConfigListener`, etc.) which populate the
`MeshServiceManager` StateFlows.

---

## Application protocol — Meshtastic protobuf

All wire payloads are Meshtastic's `meshtastic.proto`. The two top-level
messages are `ToRadio` (host → radio) and `FromRadio` (radio → host); both
are `oneof payload_variant`.

### Port numbers used by Voicetastic

Defined in [`Portnums.kt`](./app/src/main/java/re/chasam/voicetastic/service/Portnums.kt):

| Constant | Value | Purpose |
|---|---|---|
| `TEXT_MESSAGE_APP` | 1 | Plain text chat |
| `POSITION_APP` | 3 | Position broadcast (read-only here) |
| `NODEINFO_APP` | 4 | Node info beacons (read-only here) |
| `ADMIN_APP` | 6 | Config / channel / owner writes & device actions |
| `PRIVATE_APP` | 256 | **Voice chunks** (see voice protocol) |

### Outbound: text

```protobuf
ToRadio.packet = MeshPacket {
  to       = <node_num | 0xFFFFFFFF (broadcast)>
  channel  = <channel index, 0..7>
  decoded  = Data {
    portnum = TEXT_MESSAGE_APP   // 1
    payload = <UTF-8 bytes>
  }
  want_ack = true
}
```

### Outbound: arbitrary data (voice)

```protobuf
ToRadio.packet = MeshPacket {
  to       = <dest>
  channel  = <ch>
  decoded  = Data {
    portnum = <port, 1..255 or 256+ for private>
    payload = <bytes>
  }
  want_ack = true
}
```

### Inbound event routing

The Rust `MeshService` parses all inbound `FromRadio` frames internally
and fires registered Kotlin listener callbacks:

| Listener | Callback | `MeshServiceManager` action |
|---|---|---|
| `MeshStateListener` | `onState(state)` | Update `_connectionState` |
| `MeshTextListener` | `onText(msg)` | Emit on `_incomingTextMessages` |
| `MeshDataListener` | `onData(msg)` | Emit on `_incomingDataMessages` |
| `MeshConfigListener` | `onMyInfo(bytes)` | Parse `MyNodeInfo`, cache `myNodeNum` |
| | `onNodeInfo(bytes)` | Update `nodeMap` → `_nodes` |
| | `onConfig(bytes)` | Route to per-section StateFlow |
| | `onChannel(bytes)` | Replace/append in `_channels` |
| | `onOwner(bytes)` | Update `_owner` |
| | `onMetadata(bytes)` | Update `_firmwareVersion` |
| | `onConfigComplete(nonce)` | Emit on `_configComplete` |

### Node IDs

Meshtastic uses an `int` node number internally; the textual form used in
the UI is `"!" + 8-hex-digit lowercase` (e.g. `!a1b2c3d4`), produced by
`MeshtasticBle.nodeNumToId()` and parsed back by `nodeIdToNum()`. The
broadcast destination is `0xFFFFFFFF` (`MeshtasticBle.BROADCAST_ADDR`).

---

## Voice protocol

Voice messaging uses the **v2 protocol** implemented in the Rust
`voicetastic-core` crate. The Android `MessagingViewModel` calls UniFFI
bindings for both encoding (`buildMessage`) and decoding
(`VoiceAssembler`).

Features:
- 12-byte header (version, message ID, stream sequence, chunk/parity
  indices, codec, codec-param)
- Optional Reed-Solomon FEC (parity chunks)
- Optional AES-GCM encryption keyed from channel PSK
- NACK-based retransmission for missing chunks

The full specification lives in:

📄 **[`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md)**

Key numbers at a glance:

| | Value |
|---|---|
| Codec | AMR-NB @ 8 kHz, 8 selectable bitrates (4.75 – 12.2 kbps) |
| Default bitrate | MR795 (7.95 kbps) |
| Max chunk size | 231 B (12 B header + 219 B payload) |
| Max data chunks per message | 255 |
| Reassembly timeout | configurable (default 30 s) |

---

## Configuration & admin protocol

Settings UI sections are backed by Meshtastic's standard config protos and
written through `AdminMessage` packets sent **to the local node** via
`MeshServiceManager.writeConfig()` / `writeChannel()` / `writeOwner()`,
which delegate to the Rust bridge's `writeAdmin()`.

### Sections

| UI card | Backing proto | Source of truth |
|---|---|---|
| User / Owner | `User` | `AdminMessage.set_owner` |
| LoRa / Radio | `Config.LoRaConfig` | `AdminMessage.set_config { lora }` |
| Device | `Config.DeviceConfig` | `AdminMessage.set_config { device }` |
| Position | `Config.PositionConfig` | `AdminMessage.set_config { position }` |
| Power | `Config.PowerConfig` | `AdminMessage.set_config { power }` |
| Network | `Config.NetworkConfig` | `AdminMessage.set_config { network }` |
| Display | `Config.DisplayConfig` | `AdminMessage.set_config { display }` |
| Bluetooth | `Config.BluetoothConfig` | `AdminMessage.set_config { bluetooth }` |
| Channels (8) | `Channel` | `AdminMessage.set_channel` |
| Voice | `VoiceConfig` (in-app) | App preferences only — never sent to the radio |
| Device Actions | n/a | `AdminMessage.reboot_seconds` / `AdminMessage.factory_reset` |

### Dirty tracking

`ConfigViewModel` keeps a `Set<String>` of dirtied sections (`"lora"`,
`"device"`, `"channels"`, …). The per-section flow collectors only
overwrite UI state when the section is **clean**, so an inbound config
refresh during editing will never blow away unsaved local edits.

---

## Permissions & manifests

Declared in [`AndroidManifest.xml`](./app/src/main/AndroidManifest.xml):

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Voice recording (AMR-NB capture via `MediaRecorder`) |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | BLE scan on Android < 12 (legacy requirement) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | API ≤ 30 only (`maxSdkVersion=30`) |
| `BLUETOOTH_SCAN` (with `neverForLocation`) | API 31+ scanning |
| `BLUETOOTH_CONNECT` | API 31+ connecting |

Required hardware features:
* `android.hardware.bluetooth_le` (required = true)

Runtime permissions are requested upfront from `MainActivity` via
`ActivityResultContracts.RequestMultiplePermissions`.

---

## Project layout

```
app/src/main/java/re/chasam/voicetastic/
├── MainActivity.kt                  # entry, runtime permissions, VM wiring
│
├── core/
│   ├── NodeIds.kt                   # !aabbccdd ↔ Int helpers
│   └── Ports.kt                     # Meshtastic port constants
│
├── model/
│   ├── ChatItem.kt                  # sealed UI model for chat list (text|voice)
│   ├── MeshNode.kt                  # node roster row
│   ├── Message.kt                   # text message
│   ├── VoiceConfig.kt               # bitrate / duration / timeout settings
│   └── VoiceMessage.kt              # assembled voice payload
│
├── navigation/
│   └── AppNavigation.kt             # 3-tab Scaffold (Devices / Chat / Settings)
│
├── service/
│   ├── MeshServiceManager.kt        # ★ orchestrates Rust bridge + StateFlows
│   ├── BleMeshTransport.kt          # BLE GATT ↔ UniFFI MeshTransport
│   ├── UsbMeshTransport.kt          # platform USB serial (CDC/FTDI/…)
│   ├── UsbMeshTransportV2.kt        # USB ↔ UniFFI MeshTransport adapter
│   ├── RustMeshSession.kt           # lifecycle owner (transport + sink)
│   ├── MeshSerialFraming.kt         # 0x94 SLIP deframer for USB serial
│   ├── MeshtasticBle.kt             # GATT UUIDs + node-id helpers
│   └── Portnums.kt                  # Meshtastic port constants
│
├── ui/
│   ├── chat/
│   │   ├── ChatScreen.kt            # message list + composer + record button
│   │   └── MessagingViewModel.kt    # text + voice send/receive (uses UniFFI)
│   ├── device/
│   │   └── DeviceScreen.kt          # scan / list / connect / disconnect
│   └── settings/
│       ├── ConfigViewModel.kt       # per-section UI state + dirty tracking
│       └── SettingsScreen.kt        # expandable cards for each config section
│
└── voice/
    ├── VoiceRecorder.kt             # MediaRecorder → AMR-NB bytes
    └── VoicePlayer.kt               # MediaPlayer playback of reassembled AMR

third_party/voicetastic-desktop/     # git submodule
└── crates/
    └── voicetastic-android-bridge/  # UniFFI bridge crate → libvoicetastic.so
        ├── src/voicetastic.udl      # interface definition
        └── uniffi.toml              # Kotlin bindgen config
```

Top-level docs:

| File | Purpose |
|---|---|
| `FEATURES.md` (this file) | What the app does + architecture overview |
| [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md) | Wire format & assembly rules for voice |
| [`INTEGRATION.md`](./INTEGRATION.md) | Rust core integration roadmap & progress |
