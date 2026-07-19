# StreamLinkWear — Apply Audit Fixes

## How to apply (try in this order)

### Option A — git apply (preferred)
```bash
cd <path-to-real-project-root>
git apply --check streamlinkwear_fixes.patch   # dry run, should print nothing
git apply streamlinkwear_fixes.patch
```

### Option B — plain patch command (if not a git repo)
```bash
cd <path-to-real-project-root>
patch -p1 --dry-run < streamlinkwear_fixes.patch   # verify first
patch -p1 < streamlinkwear_fixes.patch
```

### Option C — if either fails on a specific file
Some hunks may fail to apply if the real project has diverged from the reviewed
copy. If that happens, apply that one file manually using the "Manual changes"
section below (each entry is small and self-contained) instead of aborting the
whole patch.

## Do NOT touch
Do not modify: `*.jks`, `secrets.properties`, `local.properties`, `build/`, `bin/`,
or any `hs_err_pid*` / `replay_pid*` files. These are intentionally excluded from
this patch and should stay exactly as they are in the real project.

## Files this patch changes (24 total)

**Modified (9):**
- `app/src/main/java/com/streamlink/app/core/StreamingOrchestrator.kt` — adds `lastKnownLocalHost()` + `reportTransportIssue()`
- `app/src/main/java/com/streamlink/app/network/HandoverCoordinator.kt` — fixes hardcoded stub IPs (192.168.1.50:8080, relay.horuslink.com:3478), gates WAN relay behind `BuildConfig.WAN_RELAY_ENABLED`
- `app/build.gradle` — adds `WAN_RELAY_ENABLED` / `WAN_RELAY_HOST` / `WAN_RELAY_PORT` BuildConfig fields (default: disabled)
- `shared/src/main/java/com/streamlink/shared/di/SharedModule.kt` — wires previously-orphaned `TrustedDeviceStore` into Hilt
- `app/src/main/res/xml/accessibility_service_config.xml` — trims scope to only what's actually used (dispatchGesture only)
- `PRIVACY_POLICY.md` — adds missing Camera/Mic/Overlay/Accessibility permission disclosures
- `wear/src/main/AndroidManifest.xml` — adds `localeConfig`, registers `BootReconnectReceiver` and `PickupCountListenerService`
- `app/src/main/java/com/streamlink/app/control/RemoteControlAccessibilityService.kt` — records + syncs the "pickup avoided" counter on each interaction
- `wear/src/main/java/com/streamlink/wear/tile/StreamLinkTileService.kt` — displays the pickup-avoided count on the tile

**New (15):**
- `wear/src/main/res/xml/locales_config.xml`
- `wear/src/main/res/values-{ar,de,es,fr,it,ja,ko,pt,ru,tr,zh-rCN}/strings.xml` (11 files — full wear localization, previously English-only)
- `wear/src/main/java/com/streamlink/wear/boot/BootReconnectReceiver.kt` — auto-resumes service after reboot if a trusted device exists
- `shared/src/main/java/com/streamlink/shared/engagement/PickupAvoidanceTracker.kt` — daily local counter, no PII
- `wear/src/main/java/com/streamlink/wear/engagement/PickupCountListenerService.kt` — receives the synced count on the watch

## After applying
1. Sync Gradle (new BuildConfig fields + new source files).
2. Confirm `secrets.properties` in the real project still has real values (this patch never touches it).
3. `WAN_RELAY_ENABLED` defaults to `false` — leave it that way until a real TURN/relay server is deployed and its host/port are supplied.
