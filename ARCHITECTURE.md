# StreamLinkWear — Architecture Deep Dive

## System Philosophy

StreamLinkWear is designed around three core principles:

1. **Zero-Allocation Hot Path** — No GC pressure during active streaming
2. **Adaptive Intelligence** — System self-tunes via AI, not static thresholds
3. **Defense in Depth** — Every layer has a fallback (Socket→WebRTC, AI→rules, LAN→Cloud)

---

## Layer Breakdown

### Layer 1: Capture (Phone)

```
MediaProjection → VirtualDisplay → Surface
                                     ↓
                             HardwareEncoder (MediaCodec)
                             - H.264 Baseline/High
                             - CBR mode (stable bitrate)
                             - Surface input (zero-copy capture)
                             - CircuitBreaker on encoder errors
```

**Key file:** `app/src/main/java/com/streamlink/app/capture/CaptureService.kt`

---

### Layer 2: Protocol Engine (shared)

The wire protocol is a **20-byte binary header** followed by payload:

```
┌─────────────────────────────────────────────────────┐
│ seq(4) │ total(2) │ idx(2) │ payloadSize(4) │ flags(1) │ nalType(1) │ reserved(6) │
└─────────────────────────────────────────────────────┘
```

**NalChunker** splits each H.264 NAL unit into 3900-byte chunks using a 4-byte word scan (SIMD-friendly). Each chunk gets the above header.

**WireBufferPool** (256-slot SPSC + SoftReference overflow) ensures zero allocation on the hot path.

**Key files:**
- `shared/.../StreamProtocol.kt` — header constants
- `shared/.../NalChunker.kt` — chunking + scanning
- `shared/.../WireBufferPool.kt` — buffer pool

---

### Layer 3: Transport (shared)

Dual-path transport with automatic selection:

```
              ┌─── DirectSocketServer (TCP, primary)
HardwareEncoder──┤
              └─── WebRtcTransport (DataChannel, fallback)
```

**BackpressureController** tracks in-flight chunks with an `AtomicInteger`. If in-flight > threshold, the encoder pipeline pauses to prevent buffer explosion.

**AdaptiveBufferChannel** (64-slot, DROP_OLDEST + exact `AtomicInteger` fill ratio) sits between encoder and network output.

---

### Layer 4: Network Discovery (shared)

```
Phone                          Watch
  │── NSD.registerService ──→    │
  │   (_streamlink._tcp)         │── NSD.discoverServices
  │                              │── resolve → IP address
  │                              │── DirectSocketClient.connect(ip)
```

**Zero configuration required.** Works on any LAN without router settings.

---

### Layer 5: Receive & Render (Wear)

```
TCP socket → DirectSocketClient.receiveLoop()
                     ↓
             FrameAssembler (reassembles chunks by seq+idx)
                     ↓
             MediaCodec (HW H.264 decoder)
                     ↓
             Surface → SurfaceView (full screen)
```

---

### Layer 6: AI Layer (Wear)

```
WristMotionSensor (accelerometer)
        ↓ (motion magnitude float)
LocalPredictiveEngine
        ↓ (TFLite Interpreter)
   input: [motionIntensity, rttMs]
   output: [recommendedBitrate]
        ↓
AIEventLogger → Room DB (AITrainingEvent)
        ↓
ai_training/train_stream_predictor.py → new .tflite model
```

This creates a **self-improving feedback loop**: the watch learns your usage patterns and improves bitrate decisions over time.

---

### Layer 7: Backend (Cloud)

```
Client → Nginx (ip_hash sticky) → Ktor Node 1 or 2
                                        ↓
                                  Redis pub/sub
                                  (cross-node session sync)
                                        ↓
                                  CoTURN (STUN/TURN)
                                  (for cross-network NAT traversal)
```

**Endpoints:**
- `GET /health` — node health check
- `WS /signal/{userId}/{deviceType}` — WebRTC signaling
- `WS /metrics` — real-time metrics broadcast
- `WS /stream/handoff/{roomId}/{deviceType}` — legacy compat

---

## State Machine

```
IDLE → CONNECTING → STREAM_STARTING → STREAMING
                                          ↓
                                       PAUSED ←→ STREAMING
                                          ↓
                                       STOPPED → IDLE
```

`GlobalStreamState` is a thread-safe `StateFlow` observable by all layers simultaneously.

---

## Dependency Graph (Hilt)

```
SharedModule
├── CoroutineScope (SupervisorJob + DefaultDispatcher)
├── EventPipeline(scope)
└── MetricsCollector(scope)

AppModule (Phone)
├── DirectSocketServer
├── HardwareEncoder
├── NalChunker
├── BackpressureController
└── StreamingOrchestrator

WearModule (Watch)
├── NetworkDiscovery(context)
├── DirectSocketClient(discovery)
├── DirectStreamPlayer(client)
├── AppDatabase → AITrainingDao
└── AIEventLogger(dao)
```

---

## Performance Engineering Decisions

| Decision | Reason |
|----------|--------|
| `ByteBuffer.allocateDirect` for WireBufferPool | Off-heap — no GC pressure |
| LockFree SPSC (256 slots + SoftRef overflow) | Zero lock contention on hot path |
| `AtomicInteger` for AdaptiveBufferChannel fill | Exact tracking without Channel internal scan |
| `ip_hash` in Nginx | WebSocket sticky sessions — same client always hits same node |
| Docker context = root (not `./backend`) | Gradle multi-module needs root `settings.gradle` |
| `POST_NOTIFICATIONS` permission | Required by ExoPlayer on Android 13+ |
