# Integrating `voicetastic-core` (Rust) into the Android app

This document describes the refactoring that was done in this PR and the
follow-up steps required to actually link the upstream Rust crate
[`voicetastic-core`](https://git.cha-sam.re/acarteron/voicetastic-desktop)
into the Android build.

---

## 1. What the upstream "core" actually is

The desktop project at `https://git.cha-sam.re/acarteron/voicetastic-desktop`
is a Rust workspace. The shareable bits live in
`crates/voicetastic-core/` and expose:

| Module                | Purpose                                                                     |
|-----------------------|-----------------------------------------------------------------------------|
| `proto`               | prost-generated Meshtastic protobuf bindings                                |
| `ids`                 | `!aabbccdd` ↔ `u32` helpers                                                 |
| `ports`               | `TEXT_MESSAGE_APP` / `ADMIN_APP` / `PRIVATE_APP` / `MAX_TEXT_BYTES` constants |
| `voice` (v2)          | 12-byte header, Reed-Solomon FEC, AES-GCM crypto, NACKs                     |
| `transport::Transport`| Async byte-frame trait — the **integration seam**                           |
| `service::MeshService`| High-level facade: `WantConfigId` handshake, state machine, watch/broadcast streams |
| `ble`, `serial`       | **Optional**, feature-gated (`ble-btleplug`, `serial-tokio`)                |

Crucially, `Cargo.toml` already advertises this seam:

> *"Optional desktop transports. Off-by-default for Android / embedded
> consumers that supply their own `Transport` implementation."*

So integration is **not** a port — it's: build the crate as a native lib
for Android, expose its `MeshService` API through JNI / UniFFI, and have
the Android side supply a `Transport` implementation backed by the
existing BLE (`MeshServiceManager`) and USB (`UsbMeshTransport`) stacks.

The Android voice protocol is currently **v1** (6-byte header,
[`VoiceChunker`](app/src/main/java/re/chasam/voicetastic/voice/VoiceChunker.kt)).
The Rust core ships **v2** (12-byte header, FEC, crypto). Both start with
the same `PROTOCOL_VERSION = 0x01` byte and share `PRIVATE_APP = 256`, so
a v1-only Android peer and a v2 desktop peer can coexist on the same
mesh until the Android side adopts v2.

---

## 2. What this PR did (historical — most seam files since removed)

A **pure-Kotlin** package `re.chasam.voicetastic.core` was introduced as
the Android-side mirror of the Rust core's public surface. With the
UniFFI integration now complete (PRs 2–5), most of these seam files have
been **removed** — the Rust bridge is the real implementation.

### Files introduced (production)

> **Status key:** ✅ retained — ❌ removed (superseded by UniFFI bridge)

| File                                                 | Mirrors Rust source    | Status |
|------------------------------------------------------|------------------------|--------|
| `core/Clock.kt`                                      | `chrono::Utc::now`     | ❌     |
| `core/Logger.kt`                                     | `tracing`              | ❌     |
| `core/NodeIds.kt`                                    | `ids.rs`               | ✅     |
| `core/Ports.kt`                                      | `ports.rs`             | ✅     |
| `core/VoiceProtocol.kt`                              | `voice/consts.rs`      | ❌     |
| `core/MeshTransport.kt`                              | `transport.rs`         | ❌     |
| `core/ConnectionState.kt`                            | `service/types.rs`     | ❌     |
| `core/MeshService.kt`                                | `service/mod.rs`       | ❌     |

### Refactored existing files

- `service/MeshtasticBle.kt` — `nodeNumToId` / `nodeIdToNum` / `BROADCAST_ADDR` now delegate to `core.NodeIds`; UUIDs stay (platform-specific).
- `service/Portnums.kt` — re-exports of `core.Ports`; adds `MAX_TEXT_BYTES`. Callers compile unchanged.

### Tests

| File                                               | Status |
|----------------------------------------------------|--------|
| `core/NodeIdsTest.kt`                              | ✅     |
| `core/PortsTest.kt`                                | ✅     |
| `core/VoiceProtocolTest.kt`                        | ❌     |
| `core/ClockAndLoggerTest.kt`                       | ❌     |
| `core/FakeMeshTransport.kt` + `MeshTransportContractTest.kt` | ❌ |

---

## 3. Recommended path to actually link the Rust core

There are three viable approaches; pick one before doing more work.

### 3.0 Does this pull in the Meshtastic protobufs?

**Yes — partially, and only on certain paths.** The upstream core is split:

| Rust module                                                | Touches `crate::proto`? |
|------------------------------------------------------------|-------------------------|
| `voice::` (assembler, builder, chunker, header, nack, crypto, consts) | **No** — pure bytes-in / bytes-out over `Transport`. |
| `service::{mod, inbound, outbound, types, voice_tx}`       | **Yes** — `NodeInfo`, `User`, `MeshPacket`, `ToRadio`, `FromRadio`, `Routing`, `Data`, `Channel`, `Config`, `ModuleConfig`. |
| `ble::`, `serial::`                                        | No (raw bytes). Off-by-default features anyway. |

Mechanically, `voicetastic-core/Cargo.toml` lists `prost` + a
`prost-build` build-dep, and `build.rs` invokes `protoc` against
`../../proto/meshtastic/*.proto` (a git submodule of the upstream
[`meshtastic/protobufs`](https://github.com/meshtastic/protobufs)
snapshot). `src/proto.rs` then `include!`s the generated `meshtastic.rs`.

**The Android app already compiles the same protos** via
`com.google.protobuf` + `protobuf-javalite`, with vendored copies of
`mesh.proto` and `portnums.proto` under
`app/src/main/proto/meshtastic/`. So there is no *new* proto tooling
requirement — only a **snapshot-pinning** requirement: both sides must
build from the same upstream revision. Recommendation: replace the
vendored `app/src/main/proto/meshtastic/` files with a git submodule
pointing at the same SHA as `voicetastic-desktop/proto`, so a `git
submodule update` keeps Android and desktop in lock-step.

Per option, what each implies:

- **A (UniFFI / JNI):** Rust still generates `prost` bindings inside the
  `.so`; Android still generates `javalite` classes. Two parallel
  generated trees for the *same* `.proto` snapshot. They never meet at
  type level — only the byte-level BLE/USB wire crosses JNI — so they
  stay compatible iff they're compiled from the same upstream SHA.
- **B (Kotlin port of `voice::` only, recommended):** **zero new proto
  work.** The voice subtree is proto-free; `service::` is reimplemented
  on top of the existing javalite `MeshPacket` / `Data` / `PortNum`
  classes the app already has.
- **C (Kotlin port of `voice::` + `service::`):** still no new tooling,
  but additional `.proto` files must be added to
  `app/src/main/proto/meshtastic/` for `ToRadio`, `FromRadio`,
  `NodeInfo`, `User`, `Channel`, `Config`, `ModuleConfig`, `Routing`
  (the javalite plugin compiles them automatically).

### Option A — UniFFI ✅ (chosen and implemented)

Mozilla's [`uniffi-rs`](https://github.com/mozilla/uniffi-rs) generates a
Kotlin wrapper from a `.udl` file describing the public Rust API. The
bridge crate lives at
`third_party/voicetastic-desktop/crates/voicetastic-android-bridge/`
and the UDL definition is `src/voicetastic.udl`.

Key adapters:
- `BleMeshTransport` — Kotlin class implementing the UniFFI `MeshTransport`
  foreign trait for BLE GATT.
- `UsbMeshTransportV2` — same, wrapping the legacy `UsbMeshTransport`.
- `RustMeshSession` — lifecycle owner tying a transport + sink to the
  Rust `MeshService`.
- `MeshServiceManager` — registers Rust-side listeners (state, text,
  data, config) and routes all send/admin calls through the bridge.

### Option B — Raw JNI

Hand-roll `extern "C"` wrappers in Rust + `external fun` declarations in
Kotlin. More boilerplate, more pitfalls around `JNIEnv` thread attachment
and pinning byte arrays, but no extra dependency. Not recommended unless
the Rust API surface is going to stay tiny.

### Option C — Straight Kotlin port (no Rust dependency)

Re-implement `voice/v2` (header, FEC via a Kotlin RS library, AES-GCM via
`javax.crypto`) in Kotlin, keeping the Rust crate purely as a reference
implementation. Simplest build, doubles the maintenance surface — every
protocol change must be ported twice.

---

## 4. Integration progress

Steps 1–4 are **done**. Step 5 is the remaining item.

1. ~~**Pick A / B / C**~~ → **A (UniFFI)** was chosen.
2. ~~**Wire the upstream crate**~~ — done in PR 2. The desktop repo is a
   git submodule at `third_party/voicetastic-desktop`; a Gradle task
   runs `uniffi-bindgen` and cross-compiles the bridge crate for
   `aarch64-linux-android` / `x86_64-linux-android`.
3. ~~**Adapt `MeshServiceManager`**~~ — done across PRs 2–4:
   - BLE extracted → `BleMeshTransport` (PR 2).
   - USB extracted → `UsbMeshTransportV2` wrapping legacy `UsbMeshTransport` (PR 2).
   - Send path routed through Rust (PR 3).
   - Listeners wired to Rust bridge callbacks (PR 3).
   - Dead legacy inbound handlers removed (PR 4).
4. ~~**Remove scaffolding**~~ — done in PR 5. The `core/` seam package
   is reduced to `NodeIds.kt` and `Ports.kt`; everything else was
   superseded by the UniFFI-generated bindings.
5. ~~**Adopt voice protocol v2**~~ — done. `MessagingViewModel` uses
   Rust `buildMessage` + `VoiceAssembler` via UniFFI for both encode
   and decode. Legacy `VoiceChunker` / `VoiceAssembler` Kotlin files
   have been deleted.
6. ~~**CI**~~ — Rust toolchain and `cargo test` are part of `.gitlab-ci.yml`.

**All primary integration steps are complete.**

### Remaining minor items

- **`moduleConfigs` StateFlow is not populated** — the Rust bridge's
  `MeshConfigListener` does not expose a module-config callback yet.
  Adding it requires an upstream UDL addition in `voicetastic-desktop`.
  The Settings UI field is retained but always empty.
- **`MeshService.sendVoice` (paced TX)** — the UDL exposes a
  `send_voice` method with built-in inter-frame pacing, but Kotlin
  currently calls `buildMessage` + per-frame `sendData` manually.
  Switching to `sendVoice` would let Rust handle pacing and simplify
  the ViewModel.

