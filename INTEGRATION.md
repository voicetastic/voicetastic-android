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

## 2. What this PR did

A **pure-Kotlin** package `re.chasam.voicetastic.core` was introduced as
the Android-side mirror of the Rust core's public surface. Nothing was
moved into a separate Gradle module yet — that split is straightforward
and can happen once the integration approach (JNI vs. UniFFI vs. straight
port) is locked in.

### New files (production)

| File                                                                                                  | Mirrors Rust source                  |
|-------------------------------------------------------------------------------------------------------|--------------------------------------|
| `app/src/main/java/re/chasam/voicetastic/core/Clock.kt`                                               | `chrono::Utc::now` usage             |
| `app/src/main/java/re/chasam/voicetastic/core/Logger.kt`                                              | `tracing` usage                      |
| `app/src/main/java/re/chasam/voicetastic/core/NodeIds.kt`                                             | `ids.rs`                             |
| `app/src/main/java/re/chasam/voicetastic/core/Ports.kt`                                               | `ports.rs`                           |
| `app/src/main/java/re/chasam/voicetastic/core/VoiceProtocol.kt`                                       | `voice/consts.rs`, `voice/mod.rs`    |
| `app/src/main/java/re/chasam/voicetastic/core/MeshTransport.kt`                                       | `transport.rs` (`Transport` trait)   |
| `app/src/main/java/re/chasam/voicetastic/core/ConnectionState.kt`                                     | `service/types.rs`                   |
| `app/src/main/java/re/chasam/voicetastic/core/MeshService.kt`                                         | `service/mod.rs` (facade contract)   |

### Refactored existing files

- `service/MeshtasticBle.kt` — `nodeNumToId` / `nodeIdToNum` / `BROADCAST_ADDR` now delegate to `core.NodeIds`; UUIDs stay (platform-specific).
- `service/Portnums.kt` — re-exports of `core.Ports`; adds `MAX_TEXT_BYTES`. Callers compile unchanged.
- `voice/VoiceAssembler.kt` — accepts an injected `Clock` and `Logger`; defaults preserve the current Android behaviour (System clock + `android.util.Log`). Internal `Log.*` calls and `System.currentTimeMillis()` are routed through the seam.

### New tests (18, all passing)

| File                                                                                                       | What it locks in                                            |
|------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| `app/src/test/.../core/NodeIdsTest.kt`                                                                     | Round-trip + every malformed-input branch from Rust `ids.rs` |
| `app/src/test/.../core/PortsTest.kt`                                                                       | Raw literal values vs. Meshtastic wire                       |
| `app/src/test/.../core/VoiceProtocolTest.kt`                                                               | v1 and v2 geometry constants                                 |
| `app/src/test/.../core/ClockAndLoggerTest.kt`                                                              | Fake / recording fixtures behave                             |
| `app/src/test/.../core/FakeMeshTransport.kt` + `MeshTransportContractTest.kt`                              | Reusable loopback fixture + contract suite for any `MeshTransport` impl |
| `app/src/test/.../voice/VoiceAssemblerInjectionTest.kt`                                                    | The new `Clock`/`Logger` seam is wired correctly             |

The existing test suite (Voice chunker/assembler/framing/portnums/config — 60 tests) continues to pass without modification.

**Total: 78 unit tests, 0 failures.**

---

## 3. Recommended path to actually link the Rust core

There are three viable approaches; pick one before doing more work.

### Option A — UniFFI (recommended)

Mozilla's [`uniffi-rs`](https://github.com/mozilla/uniffi-rs) generates a
Kotlin wrapper from a `.udl` file describing the public Rust API. It
already supports `async fn`, `Result<T, E>`, `Vec<u8>`, and lets you
expose a Kotlin-implemented `Transport` interface back to the Rust side
via `[Trait, with_foreign]`.

**Pros:** type-safe, generated bindings, works with Kotlin coroutines via
`uniffi-bindgen-kotlin-multiplatform`. **Cons:** adds a code-gen step.

Outline:

1. Add a `voicetastic-android-bridge` crate in the desktop workspace
   (or vendor a copy) that depends on `voicetastic-core` with
   `default-features = false` (no btleplug, no tokio-serial) and exposes
   a slimmed-down UniFFI scenario:
   ```udl
   namespace voicetastic { };
   [Trait, with_foreign]
   interface Transport {
       [Async, Throws=CoreError] void write_to_radio(bytes data);
       [Async] void disconnect();
   };
   interface MeshService {
       constructor();
       [Async, Throws=CoreError]
       void connect_with_transport(Transport t, bytes inbound, u64 settle_ms);
       // …watch_state, send_text, send_data, refresh_config, disconnect…
   };
   ```
2. Build for Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`,
   `x86_64-linux-android`, `i686-linux-android` (e.g. via `cargo-ndk` from a
   Gradle task).
3. Bundle the resulting `.so`s under `app/src/main/jniLibs/<abi>/` and the
   generated Kotlin under `app/src/main/java/uniffi/voicetastic/`.
4. Have the bridge's generated `MeshService` implement the Kotlin
   [`MeshService`](app/src/main/java/re/chasam/voicetastic/core/MeshService.kt)
   interface introduced in this PR (or write a thin adapter), and have a
   Kotlin `BleMeshTransport` / `UsbMeshTransport` adapter implement
   [`MeshTransport`](app/src/main/java/re/chasam/voicetastic/core/MeshTransport.kt)
   on top of `MeshServiceManager` / `UsbMeshTransport`.

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

## 4. Concrete next steps

1. **Pick A / B / C** (recommendation: A).
2. **Split into Gradle modules:** `:core` (pure JVM, holds today's
   `re.chasam.voicetastic.core.*`), `:platform-android` (BLE/USB
   transport adapters + audio I/O), `:app` (UI + Activity). The current
   single-module layout was kept to minimise diff size.
3. **Wire the upstream crate:**
   - Add a `voicetastic-core` git submodule or vendor it at
     `third_party/voicetastic-core`.
   - Add `cargo-ndk` Gradle integration (e.g. via
     [`mozilla/rust-android-gradle`](https://github.com/mozilla/rust-android-gradle)).
   - Generate UniFFI bindings into `:core`.
4. **Adapt `MeshServiceManager`:**
   - Extract its BLE half into `BleMeshTransport : MeshTransport`.
   - Extract its USB half — already present in
     [`UsbMeshTransport`](app/src/main/java/re/chasam/voicetastic/service/UsbMeshTransport.kt) — into `UsbMeshTransport : MeshTransport`.
   - Delete the rest; `MeshService` (now Rust-backed) owns the
     `WantConfigId` handshake, packet dedup, state machine.
5. **Adopt voice protocol v2:**
   - Replace `VoiceChunker` / `VoiceAssembler` with calls into
     `voicetastic_core::voice::{build_message, VoiceAssembler}`.
   - The `Clock` / `Logger` seam added in this PR makes the existing
     pure-logic tests easy to repoint at the new implementation.
6. **CI:** add a Rust toolchain layer to `.gitlab-ci.yml` that runs
   `cargo test -p voicetastic-core` before `./gradlew :app:testDebugUnitTest`.

---

## 5. Files touched in this PR

```
A  app/src/main/java/re/chasam/voicetastic/core/Clock.kt
A  app/src/main/java/re/chasam/voicetastic/core/Logger.kt
A  app/src/main/java/re/chasam/voicetastic/core/NodeIds.kt
A  app/src/main/java/re/chasam/voicetastic/core/Ports.kt
A  app/src/main/java/re/chasam/voicetastic/core/VoiceProtocol.kt
A  app/src/main/java/re/chasam/voicetastic/core/MeshTransport.kt
A  app/src/main/java/re/chasam/voicetastic/core/ConnectionState.kt
A  app/src/main/java/re/chasam/voicetastic/core/MeshService.kt
M  app/src/main/java/re/chasam/voicetastic/service/MeshtasticBle.kt
M  app/src/main/java/re/chasam/voicetastic/service/Portnums.kt
M  app/src/main/java/re/chasam/voicetastic/voice/VoiceAssembler.kt
A  app/src/test/java/re/chasam/voicetastic/core/NodeIdsTest.kt
A  app/src/test/java/re/chasam/voicetastic/core/PortsTest.kt
A  app/src/test/java/re/chasam/voicetastic/core/VoiceProtocolTest.kt
A  app/src/test/java/re/chasam/voicetastic/core/ClockAndLoggerTest.kt
A  app/src/test/java/re/chasam/voicetastic/core/FakeMeshTransport.kt
A  app/src/test/java/re/chasam/voicetastic/core/MeshTransportContractTest.kt
A  app/src/test/java/re/chasam/voicetastic/voice/VoiceAssemblerInjectionTest.kt
A  INTEGRATION.md
```

