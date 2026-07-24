# StreamLinkWear: Closed Testing Protocol

This document outlines the testing methodology and target metrics for the QA team during the final Closed Testing phase (Sprint 5) before production rollout.

## 1. Target Metrics (Success Criteria)
A release candidate is considered **PASS** only if it meets the following metrics during a 7-day soak test:
- **Crash-Free Rate**: `> 99.0%` (Monitored via Firebase Crashlytics).
- **Auto-Reconnect Latency**: `< 3.0 seconds` (Time from Wi-Fi drop to active stream recovery).
- **Thermal Safety**: Device must not exceed `40°C` during a continuous 30-minute 30fps stream.
- **Battery Drain**: `< 15% per hour` on the `ECO` profile.

## 2. QA Test Scenarios

### Scenario 1: The "Walk Away" Test
**Objective:** Verify `ConnectionManager` watchdog and socket recovery.
1. Start streaming from the Wear OS device.
2. Walk out of Wi-Fi / Bluetooth range until the video feed freezes.
3. Wait 30 seconds.
4. Walk back into range.
**Expected:** The watch automatically discovers the receiver and resumes streaming within 3 seconds without requiring a manual screen tap.

### Scenario 2: The "Oven" Test
**Objective:** Verify `UnifiedQualityAuthority` thermal throttling.
1. Start streaming at `FULL` profile (60fps, max bitrate).
2. Cover the watch or place it in a warm environment to accelerate thermal buildup.
3. Monitor the on-screen debug overlay.
**Expected:** As `thermalLevel` reaches 7+, the system must automatically downgrade to `BALANCED` or `ECO` profile. Frame drops are acceptable; app crashes or watch reboots are a **FAIL**.

### Scenario 3: Aggressive Thrashing
**Objective:** Verify `AtomicBoolean` state guards on the `StreamSessionController`.
1. Open the app.
2. Spam the "Start/Stop Stream" button as fast as possible for 10 seconds.
**Expected:** The app ignores concurrent invocations. No ANRs (Application Not Responding) dialogs. The final state must correctly reflect the UI.

## 3. Reporting
All failures must include:
1. Logcat dump with filter `com.streamlink`.
2. The exact `snapshotId` (Telemetry Version) where the issue occurred.
3. Device model and Wear OS version.
