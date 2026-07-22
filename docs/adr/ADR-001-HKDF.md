# Architecture Decision Record: HKDF Key Rotation

## Status
Accepted

## Context
StreamLinkWear establishes a secure tunnel between the phone and smartwatch. Using a static pre-shared key is vulnerable to prolonged cryptanalysis and replay attacks if the key lifetime exceeds recommended cryptographic limits.

## Decision
We will implement HKDF (HMAC-based Extract-and-Expand Key Derivation Function) to derive fresh session keys dynamically.
The rotation triggers are strictly defined as:
1. Every 10 minutes.
2. Every 500 MB of data transferred.
3. Upon any Network Handover (e.g., switching from WiFi to Cellular/Hotspot).

## Consequences
- Requires strict epoch synchronization between the phone encoder and watch decoder.
- Packet parsers must read the epoch ID from the header to know which key to use.
- Significantly increases Forward Secrecy.
