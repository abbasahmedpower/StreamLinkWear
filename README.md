<div align="center">

<img src="docs/assets/Facebook Cover - Stream Your Phone to Your Watch(1).png" width="100%" alt="StreamLinkWear — Horus Link banner" />

# StreamLinkWear
### A phone-to-watch mirroring engine engineered at the byte, cache-line, and nanosecond level — not just the feature level.

[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Wear%20OS-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](#)
[![LOC](https://img.shields.io/badge/Kotlin-~17.3K%20LOC-informational)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-28%20phone%20%2F%2030%20watch-blue)](#)
[![Crypto](https://img.shields.io/badge/crypto-ECDH--P256%20%2B%20AES--256--GCM-critical)](#-security-model)
[![Status](https://img.shields.io/badge/status-Phase%201%20%E2%80%94%20LAN%20hardening%20complete-orange)](#-status--roadmap)
[![License](https://img.shields.io/badge/license-Proprietary-red)](#-license)

**[Overview](#-overview) · [Why It's Different](#-why-this-is-not-another-mirroring-app) · [Features](#-feature-matrix) · [Architecture](#-architecture) · [Engineering Discipline](#-engineering-discipline-the-nanomicro-level-layer) · [Protocol](#-wire-protocol) · [Security](#-security-model) · [Getting Started](#-getting-started) · [Roadmap](#-status--roadmap)**

</div>

---

## 🧠 Overview

**StreamLinkWear** mirrors an Android phone's screen to a Wear OS watch in real time, and mirrors touch input *back* — turning the watch into a real remote-control surface, not a passive preview.

Under the hood it is not "screen recording piped over Bluetooth." It is a purpose-built **binary streaming stack**: a custom zero-copy wire protocol (**Horus Protocol**), hardware-accelerated H.264 encode/decode, a lock-free concurrency core, GOP-aware backpressure, thermal-aware bitrate governance, and an authenticated-encryption channel with forward secrecy — designed to survive congested Wi-Fi, thermal throttling, and unreliable power, not just a clean lab bench.

> **Design philosophy:** every allocation on the hot path, every byte on the wire, every lock (or deliberate absence of one) is a decision, not a default. This README documents both *what the system does* and *why it was built this way down to the nano/micro level* — see [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`PROTOCOL.md`](PROTOCOL.md) for the full technical spec.

---

## 🥊 Why this is not "another mirroring app"

Most phone↔wearable mirroring tools pick one of two extremes: a generic screen-recorder pointed at a video encoder, or a heavyweight WebRTC stack borrowed wholesale from a video-calling SDK. StreamLinkWear was built as neither — a protocol sized for a 466×466 round display and a battery budget measured in minutes, not a browser tab.

| | Generic screen mirroring | Browser-based WebRTC mirroring | **StreamLinkWear** |
|---|---|---|---|
| Wire format | Raw/MJPEG over HTTP | Full WebRTC (SRTP/SCTP, ICE, SDP) | Custom 25-byte binary header, purpose-sized for a wearable |
| Reverse control channel | ❌ Usually none | Data channel, general-purpose | Dedicated 32-byte cache-aligned **HOTC** frame |
| Encryption | Often TLS or none | DTLS-SRTP | **ECDH-P256 + HKDF-SHA256 + AES-256-GCM**, per-session, forward-secret |
| Frame drop policy | Drops whatever's oldest | Congestion-controlled, generic | **GOP-aware** — protects I-frames, sacrifices P-frames first |
| Thermal awareness | ❌ | ❌ | Android thermal status feeds directly into bitrate/resolution governor |
| Hot-path allocation | Varies | Managed by the browser engine | Explicit **zero-GC** buffer pools + lock-free ring buffers |
| Cloud dependency | Sometimes required | Usually required (STUN/TURN) | **None for LAN sessions** — cloud signaling is opt-in, off by default |

This is the "strongest possible" claim this README is willing to make: not superlatives, but a system where every one of these rows is backed by a file you can open in [`shared/`](shared/src/main/java/com/streamlink/shared/).

---

## ✨ Feature Matrix

| Feature | Status | Notes |
|---|---|---|
| Phone → Watch screen mirroring over LAN (TCP) | ✅ Stable | Hardware H.264 encode/decode, sub-16ms target hot path |
| Encrypted reverse touch channel (Watch → Phone) | ✅ Stable | AES-256-GCM, continuous-gesture injection via `RemoteControlAccessibilityService` |
| Zero-configuration discovery (mDNS/NSD) | ✅ Stable | `NetworkDiscovery` wired into both `AppModule` (phone) and `WearModule` (watch) DI graphs; no manual IP entry |
| ECDH-P256 key exchange + Perfect Forward Secrecy | ✅ Stable | Fresh keypair every session, invalid-curve peer-key validation |
| GOP-aware adaptive backpressure | ✅ Stable | Never drops I-frames; sheds P-frames under queue pressure |
| Thermal-aware quality governor | ✅ Stable | Android `PowerManager` thermal status (0–6) feeds bitrate/resolution decisions |
| Self-healing reconnect | ✅ Stable | Exponential backoff with ±20% jitter, capped retry budget |
| Kinematic touch prediction (motion smoothing) | ✅ Stable | Physics-based extrapolation, always-on fallback |
| WebRTC fallback for cross-network control | 🚧 Phase 2 | Signaling + `WebRtcHotcChannel` scaffolding exists; watch-side path in progress |
| On-device neural touch prediction (TFLite) | 🚧 Phase 2 | Training pipeline (`ai_training/`) in progress — kinematic engine remains the shipped default |
| Cloud signaling backend (Ktor + Redis + TURN) | 🚧 Optional | Only needed for cross-network / off-LAN sessions |

> The status column is kept honest on purpose — a feature table that oversells is worse than no feature table. If a row is wrong, it's a bug in this document, not a design intent.

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         PHONE (Android, minSdk 28)                │
│  ┌──────────┐   ┌───────────┐   ┌────────────────────────────┐  │
│  │ Capture  │→  │HardwareEnc│→  │  NalChunker + Backpressure  │  │
│  │(MediaProj│   │(H.264 CBR)│   │  Horus Wire Header (25B)    │  │
│  └──────────┘   └───────────┘   └──────────────┬───────────────┘ │
│                                                  │                 │
│                                   ┌──────────────▼─────────────┐  │
│                                   │      DirectSocketServer     │  │
│                                   │   (AES-256-GCM over TCP)    │  │
│                                   └──────────────┬─────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ RemoteControlAccessibilityService                          │  │
│  │  → continuous-gesture injection (drag/swipe accurate)      │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────┬─────────────────────────────┘
                        NSD/mDNS ──┘
                        TCP :8999 (encrypted)
┌──────────────────────────────────┼─────────────────────────────┐
│                    WATCH (Wear OS, minSdk 30)                    │
│  ┌──────────────┐   ┌───────────────┐        │                  │
│  │DirectSocket  │←──│NetworkDiscovery│←───────┘                 │
│  │   Client     │   │  (mDNS NSD)   │                           │
│  └──────┬───────┘   └───────────────┘                           │
│         │                                                        │
│  ┌──────▼───────┐   ┌───────────────┐   ┌───────────────────┐   │
│  │FrameAssembler│→  │MediaCodec Dec │→  │ HardenedTextureView │   │
│  │(25B Header)  │   │(H.264 HW Dec) │   │  (full screen)     │   │
│  └──────────────┘   └───────────────┘   └───────────────────┘   │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  TouchInputController → KinematicPredictionEngine        │   │
│  │  → 32-byte Horus Touch Control (HOTC) frame → encrypted  │   │
│  │  → DirectSocketClient (reverse channel)                  │   │
│  └──────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────┘
```

**Module layout**

```
StreamLinkWear/
├── app/            # Phone application — capture, encode, remote-control injection      (35 Kotlin files)
├── wear/           # Wear OS application — decode, render, touch capture                (34 Kotlin files)
├── shared/         # Protocol, crypto, transport, concurrency, prediction — both targets (94 Kotlin files)
├── backend/        # Optional Ktor signaling/TURN backend for cross-network sessions      (8 Kotlin files)
├── dashboard/      # Web dashboard for live session metrics
├── ai_training/    # Data export + training pipeline for on-device touch prediction
├── ARCHITECTURE.md # Deep dive per layer
└── PROTOCOL.md     # Full wire-format specification
```

---

## 🔬 Engineering Discipline: the nano/micro-level layer

This is the section most mirroring-app READMEs skip. StreamLinkWear's actual differentiation isn't the feature list above — it's what's *underneath* each row. Four concrete examples, each traceable to a real file:

<details>
<summary><b>1. Cache-line padding to kill false sharing</b> — <code>LockFreeFramePool.kt</code></summary>

<br>

Every pooled frame packet is explicitly padded with unused `Long` fields (`_p1`…`_p7`, 56 bytes) surrounding its `@Volatile` state, so that producer and consumer threads writing adjacent packets don't bounce the same CPU cache line back and forth (false sharing). This is a manual, hand-placed version of what `@Contended` does on the JVM — done because Android's ART runtime doesn't honor it.
</details>

<details>
<summary><b>2. GOP-aware frame dropping, never the keyframe</b> — <code>GopFrameDropper.kt</code></summary>

<br>

Under backpressure, most naive systems drop whatever frame is oldest — including I-frames (IDR/keyframes). Dropping an I-frame poisons every P-frame that depends on it until the next keyframe arrives, causing a visible stall or corruption. `GopFrameDropper` enforces a hard rule: **I-frames are never dropped**; P-frames are shed progressively above a 12-frame queue depth and aggressively above 20.
</details>

<details>
<summary><b>3. Real-time thread priority, not just a background thread</b> — <code>RealtimeThread.kt</code></summary>

<br>

The video/audio hot path runs on a `HandlerThread` explicitly created with `Process.THREAD_PRIORITY_URGENT_DISPLAY` — the same priority class Android reserves for UI rendering — specifically to avoid Linux kernel context-switch delays stealing frame budget from a background-priority thread.
</details>

<details>
<summary><b>4. A circuit breaker with a real HALF_OPEN state</b> — <code>CircuitBreaker.kt</code> + <code>RecoveryManager.kt</code></summary>

<br>

Connection recovery isn't "just retry." `CircuitBreaker` implements the full `CLOSED → OPEN → HALF_OPEN → CLOSED` state machine so a flapping link doesn't hammer a struggling connection. `RecoveryManager` layers exponential backoff with **±20% jitter** on top (to avoid thundering-herd reconnects) and caps retries at 10 attempts per session rather than looping forever.
</details>

Other load-bearing details in the same spirit: a `LockFreeRingBuffer` that *requires* power-of-2 capacity (enforced at construction) so index masking can replace modulo division; a dedicated `ByteBufferPool` sized for 1080p frames specifically to keep GC pauses off the hot path; and a `ThermalMonitor` that maps Android's 7-level thermal status (`NONE`→`SHUTDOWN`) directly into the bitrate/resolution governor instead of waiting for the OS to throttle the app for you.

---

## 🔌 Wire Protocol

StreamLinkWear defines independent binary framings — one per direction — authenticated and encrypted with the same per-session key. Sizes below are read directly from `StreamProtocol.kt`, not aspirational documentation.

| Channel | Magic | Frame size | Direction | Transport |
|---|---|---|---|---|
| Video (**Horus Protocol**) | `HORU` | 25-byte header (`seq`, `chunkIdx`, `totalChunks`, `flags`, `nalType`, `payloadSize`, `timestampUs`) + up to 3900-byte chunk payload | Phone → Watch | TCP (primary), WebRTC data channel (fallback) |
| Touch (**Horus Touch Control**) | `HOTC` | 32 bytes, cache-aligned (`phase`, `pointerId`, quantized `nx`/`ny` as UInt16, `seq`, `timestampUs`, padding) | Watch → Phone | TCP (primary), dedicated unordered/unreliable SCTP data channel (fallback) |
| Control (**Horus Control Network**) | `HOCN` | Compact command/value frame | Watch → Phone | TCP |

Every frame is wrapped as `[4-byte length][IV(12) ‖ AES-256-GCM ciphertext ‖ tag(16)]`. Touch coordinates are quantized from `Float` to `UInt16` (0–65535), giving ~0.0015% positional precision — more than sufficient for accurate touch injection while keeping the frame fixed-size and cache-friendly. Full field-by-field layout and reassembly rules live in [`PROTOCOL.md`](PROTOCOL.md).

---

## 🔒 Security Model

- **ECDH-P256** (`secp256r1`) ephemeral key exchange per session — a fresh keypair is generated on every connection (Perfect Forward Secrecy; no long-term keys ever touch disk).
- **HKDF-SHA256** (RFC 5869) session-key derivation from the shared secret.
- **AES-256-GCM** authenticated encryption for every frame on the wire — video, touch, and control — each with a fresh random IV.
- **Invalid-curve attack prevention**: peer public keys are validated as genuine P-256 curve points before any session key is derived.
- **Replay protection**: a nonce cache tracks seen IVs/sequence numbers per session.
- **Certificate pinning** on the optional cloud signaling path (`SignalingClient`), with primary + backup pin.
- **No cloud dependency for local sessions** — phone and watch traffic never leaves the LAN unless the (optional, explicitly enabled) WebRTC/TURN path is turned on.

Found a security issue? Please open a private security advisory rather than a public issue.

---

## 📈 Repository at a glance

| Metric | Value |
|---|---|
| Kotlin source | ~17.3K lines across `app/`, `wear/`, `shared/`, `backend/` |
| Modules | 4 Gradle modules (`app`, `wear`, `shared`, plus standalone `backend`) |
| Shared core files | 94 (protocol, crypto, transport, concurrency, AI/prediction) |
| Unit tests | `SecurityManagerTest`, `CircuitBreakerTest`, `RecoveryManagerTest`, `GopFrameDropperTest`, `KinematicPredictionEngineTest`, `StreamConnectionLifecycleTest` |
| Languages | Kotlin (app/wear/shared/backend), Python (`ai_training/`, chaos-testing tooling), GLSL (watch fragment shader) |
| Locales | 11 (`values-ar`, `de`, `es`, `fr`, `it`, `ja`, `ko`, `pt`, `ru`, `tr`, `zh-rCN`) |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug+ / Hedgehog+
- JDK 17
- Android SDK 34
- A Wear OS device or emulator (API 30+) and a phone (API 28+) **on the same Wi-Fi network**
- Docker & Docker Compose (only for the optional cloud signaling backend)

### 1. Clone & build
```bash
git clone https://github.com/abbasahmedpower/StreamLinkWear.git
cd StreamLinkWear
./gradlew assembleDebug
```

### 2. Install
```bash
# Phone
adb install app/build/outputs/apk/debug/app-debug.apk

# Watch (ensure it's paired/connected via ADB over Wi-Fi)
adb -s <watch-serial> install wear/build/outputs/apk/debug/wear-debug.apk
```

### 3. Grant permissions on first launch
1. On the **phone**: allow screen capture (MediaProjection) when prompted.
2. On the **phone**: enable `RemoteControlAccessibilityService` under **Settings → Accessibility** — this is what lets the watch control the phone.
3. On the **watch**: the app auto-discovers the phone over mDNS — no IP entry required.

### 4. (Optional) Cross-network / cloud backend
```bash
cp secrets.properties.example secrets.properties   # fill in your own TURN credentials — never commit this file
docker-compose up -d
```

> ⚠️ `secrets.properties`, `local.properties`, and `google-services.json` contain environment-specific credentials. Confirm they're listed in `.gitignore` before pushing — they should never reach a public remote.

---

## 📊 Status & Roadmap

**Phase 1 (current)** — LAN-only mirroring and control, fully encrypted, hardened hot path (backpressure, thermal governance, self-healing reconnect all in place).

**Phase 2 (in progress)**
- [ ] Watch-side WebRTC implementation for the cross-network fallback path
- [ ] On-device TFLite model for neural touch prediction (kinematic engine remains the always-on fallback)
- [ ] TURN server provisioning for public cross-network sessions
- [ ] Multi-device (more than one paired watch) support

---

## 🤝 Contributing

This is currently a solo/closed-development project. If you've been given access:

1. Read `ARCHITECTURE.md` and `PROTOCOL.md` before touching `shared/`.
2. Any change to a wire format (`StreamProtocol.kt`, `TouchEvent.kt`, `ControlCodec.kt`) must update `PROTOCOL.md` in the same PR.
3. No allocations on the hot path (`NalChunker`, `WireBufferPool`, `ByteBufferPool`, encode/decode loops) — if you must allocate, justify it in the PR description.
4. Run `./gradlew test` (unit tests live under `shared/src/test/`) before opening a PR.

---




## 📄 License

Proprietary — All Rights Reserved. See [`LICENSE`](LICENSE) for details, or contact the author for licensing inquiries.

---

<div align="center">
<sub>Built by <big>Eng. Abbas Ahmed AboAlatta</big> — engineered for real conditions, down to the byte and the cache line, not just for demos.</sub>
</div>
