# Voicetastic Voice Protocol

Voice messaging over the Meshtastic mesh network using AMR-NB audio chunked
into standard Meshtastic data packets.

**Protocol version: 1**

---

## Overview

```
┌──────────┐   record    ┌──────────┐   chunk    ┌──────────┐  BLE/LoRa  ┌──────────┐
│  Micro-  │────────────▶│  AMR-NB  │───────────▶│  Mesh    │───────────▶│  Remote  │
│  phone   │   8 kHz     │  .amr    │  6-byte    │  packets │  PRIVATE   │  node(s) │
└──────────┘             │  file    │  header +  │  ≤231 B  │  _APP 256  └──────────┘
                         └──────────┘  payload   └──────────┘
```

1. **Record** — Android `MediaRecorder` captures audio in AMR-NB format (8 kHz, configurable bitrate).
2. **Strip** — The 6-byte AMR file header (`#!AMR\n`) is removed before chunking. Only raw AMR frames are transmitted.
3. **Chunk** — `VoiceChunker` splits the frame data into ≤ 231-byte packets, each prefixed with a 6-byte header.
4. **Transmit** — Each chunk is sent as a Meshtastic `Data` packet on port `PRIVATE_APP` (256).
5. **Receive** — The receiver's `VoiceAssembler` collects chunks, reassembles the AMR-NB stream (re-adding the file header), and emits a playable `VoiceMessage`.
6. **Play** — `VoicePlayer` writes the reassembled bytes to a temp `.amr` file and plays it via `MediaPlayer`.

---

## Audio Codec

| Parameter        | Value                     |
|------------------|---------------------------|
| Codec            | AMR-NB (Adaptive Multi-Rate Narrowband) |
| Sampling rate    | 8 000 Hz                  |
| File header      | `#!AMR\n` (6 bytes: `23 21 41 4D 52 0A`) — stripped before sending, re-added on receive |
| Frame duration   | 20 ms                     |
| Android API      | `MediaRecorder.OutputFormat.AMR_NB` / `MediaRecorder.AudioEncoder.AMR_NB` |

### Supported Bitrates

| Enum value | Bitrate   | ~Bytes/sec | Frame size (bytes) |
|------------|-----------|------------|--------------------|
| `MR475`    | 4.75 kbps | 594        | 13                 |
| `MR515`    | 5.15 kbps | 644        | 14                 |
| `MR59`     | 5.90 kbps | 738        | 16                 |
| `MR67`     | 6.70 kbps | 838        | 18                 |
| `MR74`     | 7.40 kbps | 925        | 20                 |
| `MR795`    | 7.95 kbps | 994        | 21                 |
| `MR102`    | 10.2 kbps | 1 275      | 27                 |
| `MR122`    | 12.2 kbps | 1 525      | 32                 |

Default bitrate: **MR795** (7.95 kbps).

Frame size includes the 1-byte frame header (ToC byte). Each frame represents
exactly 20 ms of audio.

---

## Chunk Format (Protocol v1)

Each chunk is a self-contained byte array of at most **231 bytes** — sized to
fit within a single Meshtastic LoRa packet.

```
 Byte offset   Size    Field           Encoding
 ──────────────────────────────────────────────────
  0             1      version         UInt8  (must be 1)
  1 .. 2        2      messageId       UInt16, big-endian
  3             1      chunkIndex      UInt8  (0–254)
  4             1      totalChunks     UInt8  (1–255)
  5             1      bitrateIndex    UInt8  (AmrNbBitrate ordinal)
 ──────────────────────────────────────────────────
  6 .. N        ≤225   audio payload   raw AMR-NB frames (no file header)
```

### Header Fields

| Field          | Bytes | Range       | Description |
|----------------|-------|-------------|-------------|
| `version`      | 1     | 1           | Protocol version. Receivers must reject unknown versions. |
| `messageId`    | 2     | 0 – 65 535  | Unique identifier for the voice message. Wraps at 16 bits. Used together with the sender node ID to track which chunks belong to the same message. |
| `chunkIndex`   | 1     | 0 – 254     | Zero-based index of this chunk within the message. |
| `totalChunks`  | 1     | 1 – 255     | Total number of chunks the message was split into. The receiver uses this to know when all chunks have arrived. |
| `bitrateIndex` | 1     | 0 – 7       | Ordinal index into the `AmrNbBitrate` enum. Tells the receiver what bitrate was used for encoding, enabling proper silence frame generation for missing chunks. |

### Constants

| Name               | Value | Notes |
|--------------------|-------|-------|
| `PROTOCOL_VERSION` | 1     | Current protocol version |
| `HEADER_SIZE`      | 6     | Fixed header preceding every chunk |
| `MAX_PACKET_SIZE`  | 231   | Maximum total chunk size (header + payload) |
| `MAX_PAYLOAD_SIZE` | 225   | Maximum audio bytes per chunk (231 − 6) |

### AMR Header Handling

The AMR-NB file header (`#!AMR\n`, 6 bytes) is **stripped before chunking**
on the sender side. Chunk payloads contain only raw AMR frames. During
reassembly, the receiver prepends the file header exactly once. This prevents
the duplicate-header bug and saves 6 bytes in chunk 0.

### Capacity

A single voice message can contain at most **255 chunks × 225 bytes = 57 375 bytes** of AMR-NB frame data.  
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
parse 6-byte header (reject if version ≠ 1)
    │
    ▼
check recently-completed blacklist ──▶ reject if already finalized
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

1. The AMR-NB file header (`#!AMR\n`) is prepended (chunks carry raw frames only).
2. Chunks are concatenated **in order** (0, 1, 2, …).
3. Missing chunks are replaced with the **correct number of silence frames**
   based on the bitrate's frame size, preserving audio timeline alignment.
   Each AMR-NB NO_DATA frame is 1 byte (`0x7C`, frame type 15). The number
   of silence frames per missing chunk is `floor(MAX_PAYLOAD_SIZE / frameSizeBytes)`.
4. A `VoiceMessage` is emitted on the `completedMessages` SharedFlow.
5. The message key is added to a **recently-completed blacklist** (60 s TTL,
   max 100 entries) to reject late duplicate chunks from the mesh.

### Out-of-Order Delivery

Chunks may arrive out of order over the mesh. The assembler stores each chunk
by its `chunkIndex` in a map and only concatenates them in sequence order
during finalization. This is transparent to the sender — no retransmission
protocol is needed.

### Duplicate Protection

Two levels of deduplication:

1. **Within assembly**: If the same `chunkIndex` arrives multiple times for an
   in-progress message, duplicates are silently ignored.
2. **After completion**: Recently-finalized message keys are blacklisted for
   60 seconds. Late chunks arriving after completion are rejected instead of
   starting a phantom new assembly.

### Thread Safety

All assembler state is protected by a coroutine `Mutex` (not `synchronized`),
ensuring suspension-friendly locking without blocking threads.

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
| Max audio per message          | ~57 KB | 255 × 225 bytes |
| Max duration (MR475, lowest)   | ~96 s  | 57 375 / 594 B/s |
| Max duration (MR122, highest)  | ~37 s  | 57 375 / 1 525 B/s |
| Max duration (MR795, default)  | ~57 s  | 57 375 / 994 B/s |
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
│                   (strips AMR hdr)  port=256                  │
│                                                              │
│  MeshService.incomingDataMessages ──▶ VoiceAssembler         │
│  (port==256 filter)                   (reassemble)           │
│                                       (adds AMR hdr)         │
│                                          │                   │
│                                          ▼                   │
│                               VoiceMessage ──▶ ChatItem.Voice│
└─────────────────────────────────────────────────────────────┘
```

| Class              | Role |
|--------------------|------|
| `VoiceRecorder`    | Records AMR-NB audio via `MediaRecorder` |
| `VoiceChunker`     | Strips AMR file header, splits frames into headed chunks; parses headers; extracts payloads |
| `VoiceAssembler`   | Collects chunks by (sender, messageId), handles timeout, emits `VoiceMessage` with AMR header restored |
| `VoicePlayer`      | Plays AMR-NB byte arrays through `MediaPlayer` |
| `VoiceConfig`      | User settings: bitrate, max duration, timeout, partial play |
| `AmrNbBitrate`     | Enum of the 8 AMR-NB bitrate modes with frame sizes |
| `VoiceMessage`     | Assembled voice message data model |
| `ChatItem.Voice`   | UI-layer representation shown in the chat list |
| `Portnums`         | Defines `PRIVATE_APP = 256` |
