# StreamLinkWear Audit Ledger

This ledger acts as the **Single Source of Truth** for all codebase audits and findings.
No report or agent output is considered valid unless verified with tool-backed evidence and logged here.

| Finding ID | Severity | Status | Evidence | Fixed Commit | Regression Test |
|---|---|---|---|---|---|
| M-1 (Dead UseCase/Guards) | High | ✅ CONFIRMED-BY-CODE | `grep` showed 0 usages of `StartStreamingUseCase`. `StreamSessionController.kt:85` lacked guards. | PENDING | N/A (Sprint 1) |
| M-2 (Dead Scaffold) | Low | 🔴 CONTRADICTED-BY-CODE | `Test-Path` returned `False` for `app/.../com/example`. `wear/.../ui` returned `True`. | PENDING | N/A (Sprint 2) |
| M-3 (Crashlytics Upload) | Medium | ✅ CONFIRMED-BY-CODE | `app/build.gradle:96` contained `mappingFileUploadEnabled System.getenv("CRASHLYTICS_UPLOAD") == "true"`. | PENDING | N/A (Sprint 2) |
| M-5 (TOFU Handshake) | High | ✅ CONFIRMED-BY-CODE | `DirectSocketServer.kt:244` validates auth block but skips deviceId enforcement. | PENDING | N/A |
| M-6 (Hardcoded Telemetry) | Medium | ✅ CONFIRMED-BY-CODE | `StreamingOrchestrator.kt:148` missed `jitterMs` despite it being calculated in `latencyTracker.report()`. | PENDING | N/A (Sprint 1) |
| M-7 (Empty Catches) | Low | ✅ CONFIRMED-BY-CODE | 9 matches in `DirectSocketServer.kt` (lines 110, 124, 130, 252, 296, 308, 501, 516, 583), all intentionally logged. | PENDING | N/A (Sprint 2) |
