# Voicetastic — Features & Protocols

Voicetastic is an Android client for the [Meshtastic](https://meshtastic.org/)
mesh-radio ecosystem. In addition to the standard text-messaging surface it
adds **voice messaging** carried over the same LoRa mesh, and exposes the
full set of Meshtastic device-configuration knobs from a single Compose UI.

> **Companion document:** the wire format used by voice messages is specified
> in [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md). This file describes
> everything *around* that — the app's features, the BLE link, the Meshtastic
> protobuf protocol surface we use, and how the pieces fit together.

---

## Table of contents

1. [Top-level features](#top-level-features)
2. [Architecture](#architecture)
3. [Transport protocol — Meshtastic BLE GATT](#transport-protocol--meshtastic-ble-gatt)
4. [Application protocol — Meshtastic protobuf](#application-protocol--meshtastic-protobuf)
5. [Voice protocol](#voice-protocol)
6. [Configuration & admin protocol](#configuration--admin-protocol)
7. [Permissions & manifests](#permissions--manifests)
8. [Project layout](#project-layout)

---

## Top-level features

| Feature | Notes |
|---|---|
| **BLE device discovery & connection** | Scans for any peripheral advertising the Meshtastic GATT service UUID and presents them in a unified list. |
| **Persistent mesh status** | Live connection state, my node ID, firmware version, and the count + roster of nodes the radio currently knows about. |
| **Text messaging** | Send/receive on `TEXT_MESSAGE_APP` (port 1). Broadcast or directed to a specific node ID, on the user-selected channel. |
| **Voice messaging** | *⚠️ Experimental — not yet validated on real LoRa hardware.* Records AMR-NB audio, splits it into ≤ 231-byte chunks, ships them on `PRIVATE_APP` (port 256), reassembles + plays on the receive side. See [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md). |
| **Mesh node browser** | Live list of nodes (long name, short name, node ID, last-heard, battery, SNR). |
| **Full radio configuration UI** | LoRa, Device, Position, Power, Network, Display, Bluetooth, Channels, Owner. Every section is read-on-connect, dirty-tracked, and writable through admin messages. |
| **Voice-message tuning** | Bitrate (8 AMR-NB modes), max recording duration, assembly timeout, and "play partial on timeout" toggle. |
| **Device actions** | Refresh full config, reboot (with delay), factory reset. |
| **Out-of-order / partial voice play** | Voice chunks may arrive out of order or be lost; the receiver fills missing slots with AMR-NB NO_DATA frames so the audio timeline stays aligned. |

---

## Architecture

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
       │  • BLE scan / connect / GATT plumbing                 │
       │  • Service+characteristic discovery, MTU, CCCD        │
       │  • ToRadio writes (semaphore-serialised)              │
       │  • FromRadio polling + FromNum notify                 │
       │  • want_config_id boot-strap                          │
       │  • Per-section StateFlow caches (LoRa, Device, …)     │
       │  • Admin writes (setConfig / setChannel / setOwner /  │
       │    reboot / factory_reset)                            │
       │  • Inbound packet routing → text / data / nodeInfo /  │
       │    config / channel / configCompleteId                │
       └────────────────────┬──────────────────────────────────┘
                            │
                ┌───────────▼────────────┐
                │  Android BLE GATT API  │
                └────────────────────────┘
```

### Key observable state (StateFlow / SharedFlow)

| Flow | Type | What it carries |
|---|---|---|
| `connectionState` | `StateFlow<String>` | `"DISCONNECTED" \| "CONNECTING" \| "CONNECTED"` |
| `discoveredDevices` | `StateFlow<List<BluetoothDevice>>` | BLE scan results filtered by Meshtastic service UUID |
| `myNodeId` | `StateFlow<String?>` | Local node, formatted as `!aabbccdd` |
| `firmwareVersion` | `StateFlow<String?>` | From `DeviceMetadata.firmware_version` |
| `nodes` | `StateFlow<List<MeshNode>>` | Roster known to the connected node |
| `radioConfig` / `deviceConfig` / `positionConfig` / `powerConfig` / `networkConfig` / `displayConfig` / `bluetoothConfig` | `StateFlow<…?>` | Per-section LoRa/etc. config protos |
| `channels` | `StateFlow<List<Channel>>` | The 8 Meshtastic channels |
| `owner` | `StateFlow<User?>` | Local node's user record |
| `moduleConfigs` | `StateFlow<Map<String, ModuleConfig>>` | Modules section (MQTT, Serial, External Notification, etc.) |
| `incomingTextMessages` | `SharedFlow<IncomingText>` | Decrypted text frames from the mesh |
| `incomingDataMessages` | `SharedFlow<IncomingData>` | Non-text data frames (used for voice) |
| `configComplete` | `SharedFlow<Int>` | Fires when the firmware finishes a `want_config_id` burst |

---

## Transport protocol — Meshtastic BLE GATT

Voicetastic talks to the radio over the standard Meshtastic BLE GATT
profile. The relevant UUIDs live in
[`MeshtasticBle.kt`](./app/src/main/java/re/chasam/voicetastic/service/MeshtasticBle.kt):

| Role | UUID |
|---|---|
| Service | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` |
| `TORADIO` characteristic (Write) | `f75c76d2-129e-4dad-a1dd-7866124401e7` |
| `FROMRADIO` characteristic (Read) | `2c55e69e-4993-11ed-b878-0242ac120002` |
| `FROMNUM` characteristic (Notify) | `ed9da18c-a800-4f66-a670-aa7547de15e6` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

### Connection flow

```
scan (filter by SERVICE_UUID)
        │
        ▼
connectGatt()  ──▶  GATT_CONNECTED
        │
        ▼
refresh GATT cache (reflection)        ── stale-cache workaround
        │
        ▼
requestMtu(MTU_SIZE)                    ── larger MTU = fewer round-trips
        │
        ▼
discoverServices()                      ── retried up to 3× on transient failures
        │
        ▼
locate TORADIO + FROMRADIO + FROMNUM
        │
        ▼
enable notifications on FROMNUM (write CCCD = 0x0001)
        │
        ▼
onSetupComplete()
        │
        ▼
state = "CONNECTED"
        │
        ▼
delay 300 ms → requestConfig()          ── starts the boot-strap
```

### Read/write semantics

* **`writeCharacteristic`** is serialised through a `Mutex` and gated by a
  `Semaphore` so the next `ToRadio` is only sent after the previous BLE
  write callback fires (or a 2 s timeout). This avoids the
  `GATT_WRITE_REQUEST_BUSY` failure that BLE stacks return when callers
  fire-and-forget.
* **`readCharacteristic`** on `FROMRADIO` is the firmware's "give me one
  queued `FromRadio`" primitive — the radio will return an empty payload
  when the queue is drained.
* **`FROMNUM` notification** is an opaque 4-byte counter; we treat any
  notification as "there is at least one new `FromRadio` to drain" and
  read until the queue is empty.
* A **safety-net polling loop** (`POLL_INTERVAL_MS`) re-reads `FROMRADIO`
  in case a notification was missed/coalesced.

### `want_config_id` bootstrap

After the GATT setup completes, the manager sends:

```protobuf
ToRadio { want_config_id = <non-zero u32> }
```

The firmware replies with a **burst** of framed `FromRadio` messages —
typically:

1. `MyNodeInfo` (carries `my_node_num`)
2. `DeviceMetadata` (firmware version, hardware model, role)
3. `NodeInfo[]` (every node the radio knows)
4. `Channel[]` (8 entries)
5. `Config[]` (LoRa, Device, Position, Power, Network, Display, Bluetooth)
6. `ModuleConfig[]` (MQTT, Serial, External Notification, …)
7. `config_complete_id` (echoes the requested id; UI uses this to drop
   "loading" spinners)

The same flow is re-used by the **Refresh** button on the Settings screen.

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

### Inbound packet routing

`MeshServiceManager.handleFromRadio()` is a `when {…}` that fans out by the
`oneof` variant set on the incoming `FromRadio`:

| `FromRadio` variant | Action |
|---|---|
| `my_info` | Cache `myNodeNum`, derive `myNodeId` |
| `metadata` | Cache `firmwareVersion` |
| `node_info` | Insert/update entry in `nodeMap`, emit `nodes`; if it's our own node, also stash `_owner` |
| `packet` (text) | `_incomingTextMessages.tryEmit(IncomingText(...))` |
| `packet` (data) | Either route to admin handler (`portnum == ADMIN_APP`) or `_incomingDataMessages.tryEmit(IncomingData(...))` |
| `config` | Update the matching per-section StateFlow (`_radioConfig`, `_deviceConfig`, …) |
| `module_config` | Update `_moduleConfigs[name]` |
| `channel` | Replace/append in `_channels` |
| `config_complete_id` | Emit on `_configComplete` |

### Node IDs

Meshtastic uses an `int` node number internally; the textual form used in
the UI is `"!" + 8-hex-digit lowercase` (e.g. `!a1b2c3d4`), produced by
`MeshtasticBle.nodeNumToId()` and parsed back by `nodeIdToNum()`. The
broadcast destination is `0xFFFFFFFF` (`MeshtasticBle.BROADCAST_ADDR`).

---

## Voice protocol

> ⚠️ **Status: experimental.** The voice path is implemented end-to-end
> against the spec below, but has **not yet been validated over real LoRa
> hardware** between two devices. Treat the numbers as design targets,
> not production-tested guarantees.

Voicetastic ships voice through the Meshtastic data plane on `PRIVATE_APP`
(port 256). Each chunk has a 6-byte header (`version | messageId(BE16) |
chunkIndex | totalChunks | bitrateIndex`) followed by raw AMR-NB frames.

The full specification — chunk format, capacity, assembly key, timeout
behaviour, silence-frame substitution, duplicate protection — lives in a
dedicated document:

📄 **[`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md)**

Key numbers at a glance:

| | Value |
|---|---|
| Codec | AMR-NB @ 8 kHz, 8 selectable bitrates (4.75 – 12.2 kbps) |
| Default bitrate | MR795 (7.95 kbps) |
| Max chunk size | 231 B (6 B header + 225 B payload) |
| Max chunks per message | 255 |
| Max audio per message | ~57 KB (≈ 57 s at MR795) |
| Inter-chunk delay | 500 ms |
| Reassembly timeout | 30 s (configurable) |

---

## Configuration & admin protocol

Settings UI sections are backed by Meshtastic's standard config protos and
written through `AdminMessage` packets sent **to the local node**.

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

### Admin write envelope

```protobuf
ToRadio.packet = MeshPacket {
  to       = <my_node_num>            // ← admin packets always go to the local radio
  decoded  = Data {
    portnum      = ADMIN_APP          // 6
    payload      = AdminMessage(...).serialize()
    want_response = true
  }
  want_ack = true
}
```

`MeshServiceManager.sendAdminMessage()` refuses to send if either we're
not connected or `myNodeNum == 0` (we never received `MyNodeInfo`). Both
gates log a precise reason so the failure mode is observable.

### Dirty tracking

`ConfigViewModel` keeps a `Set<String>` of dirtied sections (`"lora"`,
`"device"`, `"channels"`, …). The per-section flow collectors only
overwrite UI state when the section is **clean**, so an inbound config
refresh during editing will never blow away unsaved local edits.

### Sample lifecycle: editing LoRa

```
User opens Settings  ──▶  per-section collectors hydrate UI from
                          MeshServiceManager StateFlows
User edits "region"  ──▶  dirty.add("lora")
User taps "Apply"    ──▶  build LoRaConfig from UI state
                          ──▶ writeConfig(Config(lora=…))
                          ──▶ AdminMessage.set_config sent
                          ──▶ dirty.remove("lora") on success
Firmware persists,
re-emits config       ──▶  UI re-hydrates (now clean)
```

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
│   ├── MeshServiceManager.kt        # ★ all the BLE / protobuf plumbing
│   ├── MeshtasticBle.kt             # GATT UUIDs + node-id helpers
│   └── Portnums.kt                  # Meshtastic port constants
│
├── ui/
│   ├── chat/
│   │   ├── ChatScreen.kt            # message list + composer + record button
│   │   └── MessagingViewModel.kt    # text + voice send/receive coordination
│   ├── device/
│   │   └── DeviceScreen.kt          # scan / list / connect / disconnect
│   └── settings/
│       ├── ConfigViewModel.kt       # per-section UI state + dirty tracking
│       └── SettingsScreen.kt        # expandable cards for each config section
│
└── voice/
    ├── VoiceRecorder.kt             # MediaRecorder → AMR-NB bytes
    ├── VoiceChunker.kt              # strip AMR header, split into ≤231-B chunks
    ├── VoiceAssembler.kt            # collect chunks by (sender, msgId), timeout, emit
    └── VoicePlayer.kt               # MediaPlayer playback of reassembled AMR
```

Top-level docs:

| File | Purpose |
|---|---|
| `FEATURES.md` (this file) | What the app does + protocols overview |
| [`VOICE_PROTOCOL.md`](./VOICE_PROTOCOL.md) | Wire format & assembly rules for voice |



