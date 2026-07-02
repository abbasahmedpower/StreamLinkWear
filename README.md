<div align="center">

<img src="docs/assets/Facebook Cover - Stream Your Phone to Your Watch(1).png" width="120" alt="Horus Link logo" />

# StreamLinkWear
### Real-time phone → Wear OS screen mirroring, built for hostile networks.

[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Wear%20OS-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-28%20(phone)%20%2F%2030%20(watch)-blue)](#)
[![Version](https://img.shields.io/badge/version-4.0--ultra-purple)](#)
[![Status](https://img.shields.io/badge/status-Phase%201%20%E2%80%94%20active%20development-orange)](#status)
[![License](https://img.shields.io/badge/license-Proprietary-red)](#license)

**[Features](#-features) · [Architecture](#-architecture) · [Protocol](#-wire-protocol) · [Security](#-security) · [Getting Started](#-getting-started) · [Status & Roadmap](#-status--roadmap)**

</div>

---

## 🧠 What is StreamLinkWear?

StreamLinkWear mirrors your Android phone's screen to a Wear OS watch **in real time**, and mirrors touch back — turning the watch into a genuine remote control surface, not just a passive viewer.

It is built around a custom **zero-copy binary wire protocol** ("Horus Protocol"), hardware-accelerated H.264 encode/decode, and a lock-free concurrency core designed to survive real-world conditions: congested Wi-Fi, thermal throttling, and unreliable power — not just clean lab networks.

> This project follows a **nano/micro-level engineering discipline**: every allocation, every byte on the wire, and every lock is deliberate. See [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`PROTOCOL.md`](PROTOCOL.md) for the full technical spec.

---

## 📱 Screenshots

<div align="center">
<img src="docs/assets/watch_interface.png" width="200" alt="Watch interface"/>&nbsp;&nbsp;
<img src="docs/assets/optimization_control.png" width="200" alt="Optimization control"/>&nbsp;&nbsp;
<img src="docs/assets/performance_stats.png" width="200" alt="Performance stats"/>
</div>

*Horus Link UI concept — dashboard, optimization, and performance views. See [`docs/design/`](docs/design/) for the full UI kit.*

---

## ✨ Features

| Feature | Status | Notes |
|---|---|---|
| Phone → Watch screen mirroring over LAN (TCP) | ✅ Stable | Hardware H.264 encode/decode, sub-16ms hot path |
| Encrypted reverse touch channel (Watch → Phone) | ✅ Stable | AES-256-GCM, continuous-gesture injection |
| Zero-configuration discovery (mDNS/NSD) | ✅ Stable | No manual IP entry |
| ECDH-P256 key exchange + Perfect Forward Secrecy | ✅ Stable | New session key every connection |
| Adaptive bitrate / backpressure control | ✅ Stable | Rule-based; on-device ML planned |
| Kinematic touch prediction (motion smoothing) | ✅ Stable | Physics-based extrapolation |
| WebRTC fallback for cross-network control | 🚧 Phase 2 | Signaling + data channel scaffolding exists on phone; watch-side implementation in progress |
| On-device neural touch prediction (TFLite) | 🚧 Phase 2 | Training pipeline in progress — current build uses kinematic prediction only |
| Cloud signaling backend (Ktor + Redis + TURN) | 🚧 Optional | Only required for cross-network / off-LAN sessions |

> We'd rather ship an honest status table than a feature list that doesn't match the code. This table is kept in sync with every release — see [Status & Roadmap](#-status--roadmap) for details.

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         PHONE (Android)                          │
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
│                         WATCH (Wear OS)                          │
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
├── app/            # Phone application (capture, encode, remote-control injection)
├── wear/           # Wear OS application (decode, render, touch capture)
├── shared/         # Protocol, crypto, transport, prediction — shared by both targets
├── backend/        # Optional Ktor signaling/TURN backend for cross-network sessions
├── dashboard/       # Web dashboard for live session metrics
├── ai_training/    # Data export tooling for on-device model training
├── ARCHITECTURE.md # Deep dive per layer
└── PROTOCOL.md     # Full wire-format specification
```

---

## 🔌 Wire Protocol

StreamLinkWear defines two independent binary framings — one for video, one for input — both authenticated and encrypted with the same per-session key.

| Channel | Magic | Frame size | Direction | Transport |
|---|---|---|---|---|
| Video (Horus Protocol) | `HORU` | 25-byte header + chunk payload | Phone → Watch | TCP (primary) |
| Touch (Horus Touch Control) | `HOTC` | 32 bytes, cache-aligned | Watch → Phone | TCP (primary) |
| Control | `HOCN` | 32 bytes | Watch → Phone | TCP (primary) |

Every frame is wrapped as `[4-byte length][IV(12) ‖ AES-256-GCM ciphertext ‖ tag(16)]`. Full field-by-field layout, chunking algorithm, and reassembly rules are documented in [`PROTOCOL.md`](PROTOCOL.md).

---

## 🔒 Security

- **ECDH-P256** ephemeral key exchange per session — a fresh keypair is generated on every connection (Perfect Forward Secrecy; no keys are ever persisted to disk).
- **HKDF-SHA256** (RFC 5869) session key derivation.
- **AES-256-GCM** for every frame on the wire — video, touch, and control.
- Peer public-key validation before deriving a session key, to reject malformed/invalid-curve keys.
- No cloud dependency for local sessions — the phone and watch never leave the LAN unless the (optional) WebRTC/TURN path is explicitly enabled.

Found a security issue? Please open a private security advisory rather than a public issue.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug+ / Hedgehog+
- JDK 17
- Android SDK 34
- A Wear OS device or emulator (API 30+) and a phone (API 28+) **on the same Wi-Fi network**
- Docker & Docker Compose (only if you want the optional cloud signaling backend)

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

1. On the **phone**: allow screen-capture (MediaProjection) when prompted.
2. On the **phone**: enable `RemoteControlAccessibilityService` under **Settings → Accessibility** — this is what allows the watch to control the phone.
3. On the **watch**: the app auto-discovers the phone over mDNS — no IP entry required.

### 4. (Optional) Cross-network / cloud backend

```bash
cp secrets.properties.example secrets.properties   # fill in TURN credentials
docker-compose up -d
```

See [`backend/README.md`](backend/) for the signaling/TURN setup.

---

## 📊 Status & Roadmap

**Phase 1 (current)** — LAN-only mirroring and control, fully encrypted, production-hardened hot path.

**Phase 2 (in progress)**
- [ ] Watch-side WebRTC implementation for the cross-network fallback path
- [ ] On-device TFLite model for neural touch prediction (kinematic engine remains as the always-on fallback)
- [ ] TURN server provisioning for public cross-network sessions
- [ ] Multi-device (more than one paired watch) support

Bug reports and architecture questions are welcome — please read [`ARCHITECTURE.md`](ARCHITECTURE.md) first so we're speaking the same language about layers and threading model.

---

## 🤝 Contributing

This is currently a solo/closed-development project. If you've been given access:

1. Read `ARCHITECTURE.md` and `PROTOCOL.md` before touching `shared/`.
2. Any change to a wire format (`StreamProtocol.kt`) must update `PROTOCOL.md` in the same PR.
3. No allocations on the hot path (`NalChunker`, `WireBufferPool`, encode/decode loops) — if you must allocate, justify it in the PR description.
4. Run `./gradlew test` (unit tests for `shared/`) before opening a PR.

---

## 📄 License

Proprietary — All Rights Reserved. See [`LICENSE`](LICENSE) for details, or contact the author for licensing inquiries.

---

<div align="center">
<sub>Built by Eng. Abbas AboAlatta — engineered for real conditions, not just demos.</sub>
</div>
