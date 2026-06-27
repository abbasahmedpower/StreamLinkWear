# StreamLinkWear — Wire Protocol Specification

Version: `1.0`  
Author: Eng. Abbas AboAlatta / Horus el Fardos

---

## 1. Overview

StreamLinkWear uses a **binary framing protocol** over TCP (and WebRTC DataChannel) to transmit H.264 video frames from phone to watch. The design prioritizes:

- **Minimal overhead** — 20-byte fixed header
- **Chunk reassembly** — large NAL units split into 3900-byte chunks
- **Loss resilience** — per-frame sequence numbers + chunk index

---

## 2. Wire Header Format (20 bytes)

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├───────────────────────────────────────────────────────────────────┤
│                    seq (4 bytes, Big-Endian)                      │  Frame sequence number (wraps at UInt.MAX)
├───────────────────────┬───────────────────────────────────────────┤
│  total (2 bytes, BE)  │     idx (2 bytes, BE)                     │  Total chunks in frame / this chunk index
├───────────────────────────────────────────────────────────────────┤
│                 payloadSize (4 bytes, Big-Endian)                 │  Actual bytes in this chunk's payload
├───────────────────────┬───────────────────────────────────────────┤
│  flags (1 byte)       │  nalType (1 byte)                         │  Control flags / H.264 NAL unit type
├───────────────────────────────────────────────────────────────────┤
│                    reserved (6 bytes, zeros)                      │  Future use (encryption nonce, etc.)
└───────────────────────────────────────────────────────────────────┘
```

### Field Definitions

| Field | Size | Type | Description |
|-------|------|------|-------------|
| `seq` | 4 | UInt32 BE | Frame sequence number. Monotonically increasing. Wraps at `0xFFFFFFFF`. |
| `total` | 2 | UInt16 BE | Total number of chunks this frame was split into (1 = single chunk). |
| `idx` | 2 | UInt16 BE | Zero-based index of this chunk within the frame. |
| `payloadSize` | 4 | UInt32 BE | Exact byte count of the payload following this header. |
| `flags` | 1 | Bitmask | `0x01` = keyframe (IDR), `0x02` = last chunk of stream, `0x04` = encrypted |
| `nalType` | 1 | UInt8 | H.264 NAL unit type (0x65=IDR, 0x41=P-frame, 0x67=SPS, 0x68=PPS) |
| `reserved` | 6 | Bytes | Must be zero. Reserved for future protocol extensions. |

**Total header size:** `4 + 2 + 2 + 4 + 1 + 1 + 6 = 20 bytes`

Defined in `shared/.../StreamProtocol.kt`:
```kotlin
const val WIRE_HEADER_SIZE = 20
const val CHUNK_MTU = 3900
```

---

## 3. Chunking Algorithm

```
NAL Unit (variable size, up to ~1MB for IDR frames)
        ↓
NalChunker.chunk(data: ByteArray): List<ByteArray>
        ↓
for each 3900-byte slice:
    build header: seq=frameSeq, total=N, idx=i, payloadSize=slice.size, ...
    prepend header to slice
    → send chunk (header + payload) over socket
```

**Scanning:** Uses 4-byte word-at-a-time scan to find H.264 start codes (`00 00 00 01`).

---

## 4. Reassembly (Watch Side)

```
FrameAssembler receives chunks in any order:
    buffer[seq][idx] = chunk.payload

When buffer[seq].size == header.total:
    concat all payloads in idx order
    → output complete NAL unit to MediaCodec
    → evict seq from buffer
```

**Timeout:** Incomplete frames older than 500ms are discarded to prevent memory growth.

---

## 5. Session Setup Sequence

```
Phone                                    Watch
  │                                        │
  │── NSD.registerService ───────────────→ │
  │   ("_streamlink._tcp", port=9876)      │── NSD.discoverServices
  │                                        │── resolve → IP
  │                                     ←──│── TCP connect(ip, 9876)
  │ ←── TCP accept ─────────────────────── │
  │                                        │
  │ ←── [optional] KeyExchange (ECDH) ──── │
  │ ─── KeyExchange response ───────────→  │
  │                                        │
  │ ─── Stream chunks ──────────────────→  │  (continuous)
```

---

## 6. Transport Selection Logic

```
Primary:   DirectSocket (TCP, LAN, ~1ms overhead)
Fallback:  WebRTC DataChannel (cross-network, via TURN)

Selection: SignalingClient detects if peer is reachable directly.
           If RTT > 500ms or connection fails → switch to WebRTC.
```

---

## 7. Error Handling

| Error | Behavior |
|-------|----------|
| Chunk out of order | Buffer and reassemble |
| Missing chunk (timeout 500ms) | Drop frame, request IDR |
| Socket disconnect | RecoveryManager retries 5× with exponential backoff |
| Encoder crash | CircuitBreaker trips, re-init after 2s |
| Backpressure overflow | DROP_OLDEST policy, pool release triggered |

---

## 8. Versioning

The `reserved` field (6 bytes) will carry a 2-byte **protocol version** in future:

```
reserved[0..1] = protocol_version (currently 0x00 0x01)
reserved[2..5] = future (encryption nonce, etc.)
```

Backward compatibility is maintained by checking `reserved[0..1]` before parsing extended fields.
