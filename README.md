# StreamLinkWear 🚀

> **Real-time adaptive screen streaming from Android phone → Wear OS smartwatch**
> Built with Zero-Lag Engine · AI-driven bitrate · Cross-device Protocol

[![Android CI](https://github.com/abbasahmedpower/StreamLinkWear/actions/workflows/ci.yml/badge.svg)](https://github.com/abbasahmedpower/StreamLinkWear/actions)
![Version](https://img.shields.io/badge/version-26.1.0.1-purple)
![Platform](https://img.shields.io/badge/platform-Android%20+%20WearOS-blue)
![License](https://img.shields.io/badge/license-Proprietary-red)

---

## 🧠 What is StreamLinkWear?

StreamLinkWear is a **production-grade adaptive streaming system** that mirrors your Android phone's screen to a Wear OS smartwatch in real-time. It combines:

- ⚡ **Zero-Copy Transport Engine** — NalChunker + LockFreeFramePool at sub-16ms frame delivery
- 🤖 **On-device AI** — TFLite predictor that adapts bitrate based on motion + network state
- 🌐 **WebRTC + Direct Socket** — dual transport with automatic failover
- 📡 **mDNS Auto-Discovery** — no manual IP configuration required
- ☁️ **Ktor Backend** — Redis-backed signaling cluster with Nginx sticky sessions

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    PHONE (Android)                           │
│  ┌──────────┐  ┌───────────┐  ┌──────────────────────────┐  │
│  │ Capture  │→ │HardwareEnc│→ │  NalChunker + Backpressure│  │
│  │(MediaProj│  │(H.264 CBR)│  │  WireProtocol(20B header) │  │
│  └──────────┘  └───────────┘  └────────────┬─────────────┘  │
│                                             │                 │
│                              ┌──────────────▼─────────────┐  │
│                              │   DirectSocketServer        │  │
│                              │   + WebRtcTransport         │  │
│                              └──────────────┬─────────────┘  │
└─────────────────────────────────────────────┼────────────────┘
                          NSD/mDNS ───────────┘
                          TCP:9876 / WebRTC
┌─────────────────────────────────────────────┼────────────────┐
│                    WATCH (Wear OS)           │                │
│  ┌──────────────┐  ┌───────────────┐        │                │
│  │DirectSocket  │←─│NetworkDiscovery│←───────┘                │
│  │   Client     │  │  (mDNS NSD)   │                         │
│  └──────┬───────┘  └───────────────┘                         │
│         │                                                     │
│  ┌──────▼───────┐  ┌───────────────┐  ┌───────────────────┐  │
│  │FrameAssembler│→ │MediaCodec Dec │→ │   SurfaceView     │  │
│  │(20B Header)  │  │(H.264 HW Dec) │  │  (Full Screen)    │  │
│  └──────────────┘  └───────────────┘  └───────────────────┘  │
│                                                               │
│  ┌──────────────────────────────────────────────────────┐     │
│  │  AI Layer: WristMotionSensor → LocalPredictiveEngine │     │
│  │  → TFLite Inference → BitrateRecommendation          │     │
│  │  → Room DB (AITrainingEvent) → Training Export       │     │
│  └──────────────────────────────────────────────────────┘     │
└───────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────┐
│              BACKEND (Cloud / LAN)                            │
│  Nginx (ip_hash) → Ktor Node 1 ┐                             │
│                → Ktor Node 2 ┘─→ Redis pub/sub               │
│  /signal/{userId}/{deviceType} (WebRTC signaling WS)         │
│  /metrics (Real-time metrics WebSocket)                       │
│  /health                                                      │
│  CoTURN (STUN/TURN for cross-network streaming)               │
└───────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- Android Studio Hedgehog+
- Android SDK 34
- JDK 17
- Docker & Docker Compose (for backend)

### 1. Clone & Build

```bash
git clone https://github.com/abbasahmedpower/StreamLinkWear.git
cd StreamLinkWear
./gradlew assembleDebug
```

### 2. Install Apps

```bash
# Phone APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Wear APK (ensure watch is connected via ADB/Wi-Fi)
adb -s <watch-serial> install wear/build/outputs/apk/debug/wear-debug.apk
```

### 3. Start Backend (optional — for cross-network)

```bash
docker-compose up -d
# Dashboard available at: http://localhost/dashboard
# Health check: http://localhost/health
```

### 4. Stream!

1. Open **StreamLink** on your phone
2. Tap **▶ Start Casting Screen** → grant screen capture permission
3. Your Wear OS watch auto-discovers the phone via mDNS and begins receiving
4. AI adapts bitrate automatically based on your wrist motion 🤖

---

## 📦 Module Structure

| Module | Description |
|--------|-------------|
| `app/` | Phone: Screen capture, encoding, streaming |
| `wear/` | Watch: Receiving, decoding, rendering + AI |
| `shared/` | Core engine shared by both (Protocol, Codecs, Transport) |
| `backend/` | Ktor signaling server (WebSocket + Redis) |
| `ai_training/` | Python training scripts for TFLite bitrate model |
| `dashboard/` | Web dashboard for live metrics monitoring |

---

## 🔧 Key Technical Details

| Component | Technology |
|-----------|------------|
| Video Encoding | MediaCodec H.264 CBR, Surface mode |
| Frame Pool | LockFreeFramePool (cache-line padded, lock-free SPSC) |
| NAL Chunking | 4-byte word scan, 3900-byte MTU chunks |
| Wire Protocol | 20-byte binary header (seq, total, idx, payloadSize, flags, nalType) |
| Transport | Direct TCP Socket (primary) + WebRTC DataChannel (fallback) |
| Discovery | Android NSD (mDNS/Bonjour) — zero config |
| AI Engine | TFLite on-device inference (motion + RTT → bitrate) |
| Database | Room (SQLite) for AI training event logging |
| Backend | Ktor + Netty + Redis + Nginx + CoTURN |
| DI | Dagger Hilt |

---

## 📊 Performance Targets

| Metric | Target | Achieved |
|--------|--------|---------|
| Glass-to-Glass Latency | < 80ms | ~60-90ms (LAN) |
| Frame Rate | 30-60 FPS | Adaptive |
| Bitrate Range | 400–4000 kbps | AI-controlled |
| Buffer Fill | < 64 frames | Backpressure-enforced |

---

## 👨‍💻 Author

**Eng. Abbas AboAlatta**
**Horus el Fardos**
Version: `26.1.0.1`

---

## 📄 Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — Deep system design
- [PROTOCOL.md](PROTOCOL.md) — Wire protocol specification
