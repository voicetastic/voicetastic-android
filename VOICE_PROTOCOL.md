# Voicetastic Voice Protocol

Voice messaging over the Meshtastic mesh network using AMR-NB audio chunked
into standard Meshtastic data packets.

---

## Overview

```
┌──────────┐   record    ┌──────────┐   chunk    ┌──────────┐  BLE/LoRa  ┌──────────┐
│  Micro-  │────────────▶│  AMR-NB  │───────────▶│  Mesh    │───────────▶│  Remote  │
│  phone   │   8 kHz     │  .amr    │  6-byte    │  packets │  PRIVATE   │  node(s) │
└──────────┘             │  file    │  header +  │  ≤230 B  │  _APP 256  └──────────┘
                         └──────────┘  payload   └──────────┘
```

1. **Record** — Android `MediaRecorder` captures audio in AMR-NB format (8 kHz, configurable bitrate).
2. **Chunk** — `VoiceChunker` splits the `.amr` file into ≤ 230-byte packets, each prefixed with a 6-byte header.
3. **Transmit** — Each chunk is sent as a Meshtastic `Data` packet on port `PRIVATE_APP` (256).
4. **Receive** — The receiver's `VoiceAssembler` collects chunks, reassembles the AMR-NB stream, and emits a playable `VoiceMessage`.
5. **Play** — `VoicePlayer` writes the reassembled bytes to a temp `.amr` file and plays it via `MediaPlayer`.

---

## Audio Codec

| Parameter        | Value                     |
|------------------|---------------------------|
| Codec            | AMR-NB (Adaptive Multi-Rate Narrowband) |
| Sampling rate    | 8 000 Hz                  |
| File header      | `#!AMR\n` (6 bytes: `23 21 41 4D 52 0A`) |
| Frame duration   | 20 ms                     |
| Android API      | `MediaRecorder.OutputFormat.AMR_NB` / `MediaRecorder.AudioEncoder.AMR_NB` |

### Supported Bitrates

| Enum value | Bitrate   | ~Bytes/sec |
|------------|-----------|------------|
| `MR475`    | 4.75 kbps | 594        |
| `MR515`    | 5.15 kbps | 644        |
| `MR59`     | 5.90 kbps | 738        |
| `MR67`     | 6.70 kbps | 838        |
| `MR74`     | 7.40 kbps | 925        |
| `MR795`    | 7.95 kbps | 994        |
| `MR102`    | 10.2 kbps | 1 275      |
| `MR122`    | 12.2 kbps | 1 525      |

Default bitrate: **MR795** (7.95 kbps).

---

## Chunk Format

Each chunk is a self-contained byte array of at most **230 bytes** — the
maximum payload that fits in a single Meshtastic LoRa packet.

```
 Byte offset   Size    Field           Encoding
 ──────────────────────────────────────────────────
  0 .. 1        2      messageId       UInt16, big-endian
  2 .. 3        2      chunkIndex      UInt16, big-endian
  4             1      totalChunks     UInt8  (max 255)
  5             1      bitrateIndex    UInt8  (AmrNbBitrate ordinal)
 ──────────────────────────────────────────────────
  6 .. N        ≤224   audio payload   raw AMR-NB bytes
```

### Header Fields

| Field          | Bytes | Range       | Description |
|----------------|-------|-------------|-------------|
| `messageId`    | 2     | 0 – 65 535  | Unique identifier for the voice message. Wraps at 16 bits. Used together with the sender node ID to track which chunks belong to the same message. |
| `chunkIndex`   | 2     | 0 – 65 535  | Zero-based index of this chunk within the message. In practice limited to 0–254 because `totalChunks ≤ 255`. |
| `totalChunks`  | 1     | 1 – 255     | Total number of chunks the message was split into. The receiver uses this to know when all chunks have arrived. |
| `bitrateIndex` | 1     | 0 – 7       | Ordinal index into the `AmrNbBitrate` enum. Tells the receiver what bitrate was used for encoding. |

### Constants

| Name               | Value | Notes |
|--------------------|-------|-------|
| `HEADER_SIZE`      | 6     | Fixed header preceding every chunk |
| `MAX_PACKET_SIZE`  | 230   | Maximum total chunk size (header + payload) |
| `MAX_PAYLOAD_SIZE` | 224   | Maximum audio bytes per chunk (230 − 6) |

### Capacity

A single voice message can contain at most **255 chunks × 224 bytes = 57 120 bytes** of AMR-NB audio.  
At the default bitrate (MR795 ≈ 994 B/s) this allows roughly **57 seconds** of audio.

---

## Meshtastic Transport

Voice chunks are carried as standard Meshtastic data packets:

```protobuf
// Meshtastic MeshPacket
MeshPacket {
  from:    <sender node num>       // fixed32
  to:      <destination or 0xFFFFFFFF for broadcast>  // fixed32
  channel: <channel index>         // uint32
  decoded: Data {
    portnum: PRIVATE_APP           // = 256
    payload: <chunk bytes>         // header + audio payload
  }
  want_ack: true
}
```

| Field       | Value |
|-------------|-------|
| Port number | `PRIVATE_APP` = **256** (Meshtastic private application port) |
| Destination | Broadcast (`0xFFFFFFFF`) or a specific node number for DMs |
| Channel     | The currently selected Meshtastic channel index |

### Sending Cadence

Chunks are sent sequentially with a **500 ms** delay between each packet to
avoid overwhelming the mesh network and radio duty cycle limits.

A progress indicator (`sendingProgress: Float`) tracks transmission from 0.0 to
1.0.

---

## Assembly (Receive Side)

The `VoiceAssembler` reconstructs voice messages from incoming chunks.

### Assembly Key

Each in-progress message is tracked by the tuple **(senderNodeId, messageId)**.
This allows concurrent messages from different senders (or different messages
from the same sender) to be assembled independently.

### Flow

```
chunk arrives
    │
    ▼
parse 6-byte header
    │
    ▼
lookup or create AssemblyState for (from, messageId)
    │
    ├── new state → start timeout timer
    │
    ▼
store chunk payload at chunks[chunkIndex]
    │  (duplicates are silently ignored)
    │
    ├── all chunks received? ──▶ cancel timer → finalize (complete)
    │
    └── timeout elapsed?     ──▶ finalize (partial) or discard
```

### Timeout & Partial Play

| Config field             | Default | Description |
|--------------------------|---------|-------------|
| `chunkTimeoutSeconds`    | 30      | Seconds to wait for remaining chunks before giving up |
| `partialPlayOnTimeout`   | true    | If `true`, assemble and emit whatever chunks arrived; if `false`, discard |

When a message is finalized (complete or partial):

1. Chunks are concatenated **in order** (0, 1, 2, …).
2. Missing chunks are replaced with an AMR-NB **silence frame** (`0x7C` — NO_DATA frame type 15).
3. The AMR file header (`#!AMR\n`) is prepended.
4. A `VoiceMessage` is emitted on the `completedMessages` SharedFlow.

### Out-of-Order Delivery

Chunks may arrive out of order over the mesh. The assembler stores each chunk
by its `chunkIndex` in a map and only concatenates them in sequence order
during finalization. This is transparent to the sender — no retransmission
protocol is needed.

---

## Data Model

### `VoiceMessage`

Emitted by the assembler when a voice message is complete or timed out.

```kotlin
data class VoiceMessage(
    val messageId: Int,         // from chunk header
    val from: String,           // sender node ID (e.g. "!a1b2c3d4")
    val to: String,             // destination node ID or "broadcast"
    val audioData: ByteArray,   // reassembled AMR-NB bytes (with file header)
    val timestamp: Long,        // time first chunk was received
    val isOutgoing: Boolean,
    val isComplete: Boolean,    // true if all chunks arrived
    val totalChunks: Int,
    val receivedChunks: Int,
    val bitrateIndex: Int,      // AmrNbBitrate ordinal
    val channel: Int            // Meshtastic channel index
)
```

### `VoiceConfig`

User-configurable settings for voice messaging.

```kotlin
data class VoiceConfig(
    val bitrate: AmrNbBitrate = AmrNbBitrate.MR795,   // encoding quality
    val maxDurationSeconds: Int = 20,                   // max recording length
    val chunkTimeoutSeconds: Int = 30,                  // assembly timeout
    val partialPlayOnTimeout: Boolean = true             // play incomplete messages
)
```

---

## Playback

The `VoicePlayer` writes the reassembled `audioData` byte array to a temporary
`.amr` file in the app's cache directory and plays it through Android's
`MediaPlayer`. The temp file is deleted after playback completes or on error.

Playback can be toggled (tap to play, tap again to stop) via the chat UI.

---

## Limitations

| Constraint                     | Value  | Reason |
|--------------------------------|--------|--------|
| Max chunks per message         | 255    | `totalChunks` field is UInt8 |
| Max audio per message          | ~57 KB | 255 × 224 bytes |
| Max duration (MR475, lowest)   | ~96 s  | 57 120 / 594 B/s |
| Max duration (MR122, highest)  | ~37 s  | 57 120 / 1 525 B/s |
| Max duration (MR795, default)  | ~57 s  | 57 120 / 994 B/s |
| Inter-chunk delay              | 500 ms | Prevent radio congestion |
| Min send time (255 chunks)     | ~127 s | 255 × 500 ms |
| No retransmission              | —      | Lost chunks become silence frames |
| No encryption (app layer)      | —      | Relies on Meshtastic channel encryption |

---

## Component Map

```
┌─────────────────────────────────────────────────────────────┐
│                     MessagingViewModel                       │
│                                                              │
│  startRecording()    stopRecordingAndSend()    playVoice()   │
│        │                    │                      │         │
│        ▼                    ▼                      ▼         │
│  VoiceRecorder ──▶ VoiceChunker ──▶ MeshService  VoicePlayer │
│  (MediaRecorder)   (split+header)   .sendData()  (MediaPlayer)│
│                                     port=256                  │
│                                                              │
│  MeshService.incomingDataMessages ──▶ VoiceAssembler         │
│  (port==256 filter)                   (reassemble)           │
│                                          │                   │
│                                          ▼                   │
│                               VoiceMessage ──▶ ChatItem.Voice│
└─────────────────────────────────────────────────────────────┘
```

| Class              | Role |
|--------------------|------|
| `VoiceRecorder`    | Records AMR-NB audio via `MediaRecorder` |
| `VoiceChunker`     | Splits audio into headed chunks; parses headers; extracts payloads |
| `VoiceAssembler`   | Collects chunks by (sender, messageId), handles timeout, emits `VoiceMessage` |
| `VoicePlayer`      | Plays AMR-NB byte arrays through `MediaPlayer` |
| `VoiceConfig`      | User settings: bitrate, max duration, timeout, partial play |
| `AmrNbBitrate`     | Enum of the 8 AMR-NB bitrate modes |
| `VoiceMessage`     | Assembled voice message data model |
| `ChatItem.Voice`   | UI-layer representation shown in the chat list |
| `Portnums`         | Defines `PRIVATE_APP = 256` |

