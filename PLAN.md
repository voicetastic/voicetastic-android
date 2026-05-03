# Voicetastic — Meshtastic Messaging App

A Kotlin + Jetpack Compose Android app that connects **directly** to Meshtastic hardware via Bluetooth Low Energy. Supports text messaging, voice messaging (AMR-NB), and device configuration. No Meshtastic Android app required.

---

## Architecture

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material3
- **Meshtastic communication:** Direct BLE (GATT) with protobuf-encoded messages
- **Protocol:** Meshtastic BLE protocol (`ToRadio`/`FromRadio` protobuf over GATT)
- **Navigation:** 4-tab bottom nav (Devices, Chat, Voice, Settings)
- **Testing:** Kotest (unit tests) + Cucumber/Gherkin (BDD tests)
- **Min SDK:** 29 (Android 10)

---

## Features

### 1. Device Connection (Devices tab)
- BLE scan for nearby Meshtastic devices (filtered by service UUID)
- One-tap connect to a discovered device
- Connection status display with node ID and firmware info
- List of all mesh nodes discovered via the connected device
- Disconnect control

### 2. Text Messaging (Chat tab)
- Send/receive text messages over the mesh network
- Broadcast to all nodes or direct message to a specific node
- Node picker dialog showing all discovered mesh nodes
- Real-time message display with sender info and timestamps

### 3. Voice Messaging (Voice tab)
- **Recording:** AMR-NB via `MediaRecorder`, configurable bitrate and max duration (default 20s)
- **Chunking:** Audio split into mesh-compatible packets (max 230 bytes each)
  - 6-byte binary header: 2B message ID, 2B chunk index, 1B total chunks, 1B bitrate enum
  - Up to 224 bytes of audio payload per chunk
  - Sent on Meshtastic port `PRIVATE_APP` (256)
- **Reception & reassembly:** `VoiceAssembler` collects incoming chunks by message ID
  - Handles out-of-order delivery
  - 30-second timeout (configurable) with partial-play fallback
  - Missing chunks replaced with AMR-NB silence frames
- **Playback:** `VoicePlayer` wraps `MediaPlayer` for AMR-NB playback
- **Progress indicator** during chunk transmission

### 4. Device Configuration (Settings tab)
- **Meshtastic device settings:**
  - Region (US, EU_868, EU_433, etc.)
  - Modem preset (LONG_FAST, SHORT_TURBO, etc.)
  - Channel name and PSK (pre-shared key)
- **Voice settings:**
  - AMR-NB bitrate (4.75–12.2 kbps)
  - Max recording duration (1–60s, default 20s)
  - Chunk receive timeout (5–120s, default 30s)
  - Partial play on timeout toggle

---

## Project Structure

```
app/src/main/
├── proto/meshtastic/
│   ├── portnums.proto               # PortNum enum (TEXT_MESSAGE_APP, PRIVATE_APP, etc.)
│   └── mesh.proto                   # MeshPacket, Data, ToRadio, FromRadio, NodeInfo, Config, Channel
├── java/re/chasam/voicetastic/
│   ├── MainActivity.kt              # Entry point, BLE permissions, lifecycle
│   ├── model/
│   │   ├── Message.kt               # Text message data class
│   │   ├── MeshNode.kt              # Mesh node data class
│   │   ├── VoiceConfig.kt           # Voice config + AmrNbBitrate enum
│   │   └── VoiceMessage.kt          # Voice message data class
│   ├── navigation/
│   │   └── AppNavigation.kt         # NavHost with 4-tab bottom nav
│   ├── service/
│   │   ├── MeshtasticBle.kt         # BLE UUIDs and helper constants
│   │   ├── MeshServiceManager.kt    # Direct BLE connection + protobuf protocol
│   │   └── Portnums.kt              # Port number integer constants
│   ├── ui/
│   │   ├── device/
│   │   │   └── DeviceScreen.kt      # BLE scanner + device selector UI
│   │   ├── chat/
│   │   │   ├── ChatScreen.kt        # Text messaging UI
│   │   │   └── MessagingViewModel.kt
│   │   ├── voice/
│   │   │   ├── VoiceScreen.kt       # Voice messaging UI
│   │   │   └── VoiceViewModel.kt
│   │   └── settings/
│   │       ├── SettingsScreen.kt     # Configuration UI
│   │       └── ConfigViewModel.kt
│   └── voice/
│       ├── VoiceRecorder.kt          # AMR-NB recording
│       ├── VoiceChunker.kt           # Audio → mesh packet chunking
│       ├── VoiceAssembler.kt         # Chunk reassembly + timeout
│       └── VoicePlayer.kt            # AMR-NB playback

app/src/test/
├── java/re/chasam/voicetastic/
│   ├── model/
│   │   └── VoiceConfigTest.kt       # Kotest: config defaults, bitrate validation
│   ├── service/
│   │   └── PortnumsTest.kt          # Kotest: port number constants
│   ├── voice/
│   │   ├── VoiceChunkerTest.kt      # Kotest: chunking, headers, reassembly
│   │   └── VoiceAssemblerTest.kt    # Kotest: assembly, timeout, partial play
│   └── bdd/
│       ├── CucumberRunnerTest.kt    # Cucumber JUnit4 runner
│       ├── MessagingSteps.kt        # Steps for messaging.feature
│       ├── VoiceMessagingSteps.kt   # Steps for voice_messaging.feature
│       └── ConfigurationSteps.kt   # Steps for configuration.feature
└── resources/features/
    ├── messaging.feature             # 5 scenarios: send/receive text
    ├── voice_messaging.feature       # 8 scenarios: record/chunk/send/receive/play
    └── configuration.feature         # 10 scenarios: device + voice config
```

---

## Direct BLE Communication

This app connects **directly** to Meshtastic hardware via Bluetooth Low Energy. No Meshtastic Android app is required.

### BLE GATT UUIDs

| Characteristic | UUID | Direction |
|---|---|---|
| Service | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` | — |
| ToRadio | `f75c76d2-129e-4dad-a1dd-7866124401e7` | Write (app → device) |
| FromRadio | `2c55e69e-4993-11ed-b878-0242ac120002` | Read (device → app) |
| FromNum | `ed9da18c-a800-4f66-a670-aa7547de15e6` | Notify (data available) |

### Connection Flow

1. **Scan** for BLE devices advertising the Meshtastic service UUID
2. **Connect** via GATT, request 512-byte MTU
3. **Discover services** and enable FromNum notifications
4. **Request config** by sending `ToRadio { want_config_id }` to the device
5. **Receive config** from sequential `FromRadio` reads: `MyNodeInfo`, `NodeInfo`, `Config`, `Channel`, `config_complete_id`
6. **Send packets** by writing `ToRadio { packet: MeshPacket }` to the ToRadio characteristic
7. **Receive packets** from `FromRadio { packet: MeshPacket }` triggered by FromNum notifications

### Protobuf Protocol

Messages are encoded using Protocol Buffers (lite). Proto definitions in `app/src/main/proto/meshtastic/`:

- **`ToRadio`** — Wrapper for outgoing messages: `MeshPacket` or `want_config_id`
- **`FromRadio`** — Wrapper for incoming messages: `MeshPacket`, `MyNodeInfo`, `NodeInfo`, `Config`, `Channel`
- **`MeshPacket`** — Mesh network packet containing `Data` (decoded) or `encrypted` bytes
- **`Data`** — Application payload with `portnum` and `payload` bytes
- **`Config`** — Device configuration including `LoRaConfig` (region, modem preset, hop limit)
- **`Channel`** / **`ChannelSettings`** — Channel configuration (name, PSK)

---

## Voice Chunk Protocol

Each voice chunk is a mesh data packet (max 230 bytes) sent on port `PRIVATE_APP` (256):

```
Offset  Size  Field
──────  ────  ─────────────────────────────────
0       2B    Message ID (UInt16, big-endian)
2       2B    Chunk Index (UInt16, big-endian)
4       1B    Total Chunks (UInt8, max 255)
5       1B    Bitrate Index (UInt8, AmrNbBitrate ordinal)
6       224B  Audio Payload (AMR-NB data)
```

### Reassembly
- Chunks are collected by `(senderNodeId, messageId)` key
- Out-of-order delivery is handled (chunks stored by index)
- On completion (all chunks received): concatenate payloads, prepend AMR-NB file header (`#!AMR\n`), emit as `VoiceMessage`
- On timeout (default 30s): emit partial message with silence frames (`0x7C`) for missing chunks
- Duplicate chunks are ignored

---

## Dependencies

| Dependency | Purpose |
|---|---|
| Compose BOM | Jetpack Compose UI framework |
| Material3 | Material Design 3 components |
| Navigation Compose | Tab-based navigation |
| Lifecycle ViewModel Compose | ViewModel integration |
| Protobuf Java Lite | Meshtastic protobuf message encoding/decoding |
| Kotest | Unit testing framework (FunSpec) |
| Cucumber JVM | Gherkin BDD tests |
| MockK | Kotlin mocking library |
| Kotlinx Coroutines Test | Coroutine test utilities |

---

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Voice message recording |
| `ACCESS_FINE_LOCATION` | BLE scanning (API < 31) |
| `ACCESS_COARSE_LOCATION` | BLE scanning (API < 31) |
| `BLUETOOTH` | BLE operations (API < 31) |
| `BLUETOOTH_ADMIN` | BLE scanning (API < 31) |
| `BLUETOOTH_SCAN` | BLE device discovery (API 31+) |
| `BLUETOOTH_CONNECT` | BLE GATT connection (API 31+) |

Hardware feature: `android.hardware.bluetooth_le` (required)

---

## Build & Test

```bash
# Build debug APK
./gradlew assembleDebug

# Run all unit tests (Kotest + Cucumber)
./gradlew testDebugUnitTest

# Run only Kotest tests
./gradlew testDebugUnitTest --tests "re.chasam.voicetastic.voice.*"
./gradlew testDebugUnitTest --tests "re.chasam.voicetastic.model.*"
./gradlew testDebugUnitTest --tests "re.chasam.voicetastic.service.*"

# Run Cucumber BDD tests
./gradlew testDebugUnitTest --tests "re.chasam.voicetastic.bdd.*"
```

---

## Design Decisions

1. **Direct BLE communication** — Connects directly to Meshtastic hardware via BLE GATT, eliminating the dependency on the Meshtastic Android app. Uses the official Meshtastic protobuf protocol for full compatibility.

2. **Protobuf-lite** — Uses `protobuf-javalite` for efficient message encoding/decoding. Proto definitions are minimal but wire-compatible with the official Meshtastic firmware.

3. **AMR-NB codec** — Chosen for extreme compression (593–1525 bytes/sec) to fit Meshtastic's ~230-byte packet limit. Configurable bitrate (4.75–12.2 kbps) and max duration (default 20s).

4. **6-byte chunk header** — Compact binary format balancing metadata needs with payload space. Supports up to 255 chunks × 224 bytes = ~55 KB of audio per message.

5. **30-second chunk timeout with partial play** — Mesh networks are lossy; rather than discarding incomplete messages, missing chunks are replaced with silence frames and the partial audio is still playable.

6. **Jetpack Compose + Material3** — Modern Android UI toolkit with declarative patterns and built-in Material Design 3 theming.

7. **4-tab navigation** — Devices tab for BLE scanning/connection, Chat for text, Voice for audio messages, Settings for device and voice configuration.
