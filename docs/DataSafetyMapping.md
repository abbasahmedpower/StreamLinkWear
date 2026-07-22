# StreamLinkWear Data Safety & Compliance Guide

Before publishing to the Google Play Store, you must fill out the **Data Safety form** and the **Accessibility Service declaration**. This document contains the exact mappings you need to pass Google's review.

## 1. Data Safety Form Mapping

### Data Types Collected

| Data Category | Data Type | Collected | Shared | Required? | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **App activity** | App interactions | Yes | No | Required | Analytics (Firebase Crashlytics) to measure startup times and feature usage. |
| **App info and performance** | Crash logs | Yes | No | Required | Analytics (Firebase Crashlytics) to capture Rich Crash Snapshots (Thermals, RAM, GC). |
| **App info and performance** | Diagnostics | Yes | No | Required | Analytics (Firebase Crashlytics) to track Frame Drops and ANRs. |
| **Device or other IDs** | Device IDs | Yes | No | Required | App functionality (Play Integrity API / Socket Binding). |

### Important Disclosures
* **Data Encryption**: All data in transit is encrypted using our custom HKDF engine with ECDH keys.
* **Account Deletion**: Not applicable (No user accounts).

---

## 2. Accessibility Service Declaration

Google Play is extremely strict about the `android.permission.BIND_ACCESSIBILITY_SERVICE`. You must explicitly state why it is needed and what it does.

**Why is it needed?**
StreamLinkWear requires the Accessibility Service to translate remote touch gestures, swipes, and hardware button presses from the Smartwatch (Wear OS) and inject them natively onto the phone's screen.

**Does it collect data?**
No. The Accessibility Service is purely used for action injection (`performGlobalAction`, `dispatchGesture`). It does **not** read screen content, keystrokes, or personal data.

**User Disclosure**
We have implemented the `AccessibilityDisclosureDialog.kt` which explicitly forces the user to read and accept these terms before enabling the service.

---

## 3. Background Services & Permissions Justification

If prompted by Google Play Console regarding Foreground Services (`android.permission.FOREGROUND_SERVICE`), use the following justifications:

* **FOREGROUND_SERVICE_MEDIA_PROJECTION**: Used strictly to capture the screen in real-time to encode and broadcast it to the connected Wear OS device.
* **FOREGROUND_SERVICE_CONNECTED_DEVICE**: Used to maintain a persistent Bluetooth/WIFI socket connection with the paired smartwatch while the phone is locked or the app is minimized, preventing immediate teardown of the stream.
