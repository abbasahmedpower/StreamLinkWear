# Privacy Policy for StreamLinkWear

**Effective Date:** July 4, 2026

## 1. Introduction
Welcome to **StreamLinkWear**. We respect your privacy and are committed to protecting the data collected by our application. This Privacy Policy explains what information we collect, why we collect it, and how it is handled, especially concerning our Context Intelligence Engine and telemetry systems.

## 2. Information We Collect
StreamLinkWear is designed to operate primarily on-device with minimal data collection. However, to optimize stream quality dynamically (e.g., adaptive resolution and bitrate) and to train our AI predictive models, we collect specific performance telemetry data.

### 2.1 Telemetry and AI Training Data (`AITrainingEvent`)
We collect non-personally identifiable telemetry data ("Events") to monitor network and device health during streaming sessions. The specific data points collected include:
- **Device Health:** Battery level and thermal status.
- **Network Performance:** Round Trip Time (RTT), network latency, and estimated packet loss percentage.
- **Motion Data:** Wrist motion intensity (derived from device sensors as a generic intensity metric, not recording specific steps or health data).
- **Stream Metrics:** Recommended and chosen bitrates and framerates.

## 3. How We Use the Information
The telemetry data collected is used strictly for the following purposes:
- **Real-time Stream Optimization:** To dynamically adjust video quality and prevent frame drops based on network and thermal conditions.
- **AI Model Training:** To train our on-device Context Intelligence Engine (`stream_predictor.tflite`). This model predicts the optimal streaming profile (IDLE, PRELOAD, STREAM_SMOOTH, DEGRADED) based on historical performance.

## 4. Data Storage and Retention
- **Local Storage:** Telemetry data is stored locally on your Wear OS device using an encrypted local database (Room DB).
- **Export for Training:** If you opt-in to export synthetic data for model training, the data is anonymized and stripped of any potential identifiers before being processed.
- **No PII:** We **do not** collect, store, or transmit any Personally Identifiable Information (PII) such as names, email addresses, or precise location data.

## 5. Third-Party Access
We do not sell, trade, or otherwise transfer your telemetry data to outside parties. All AI training and inference occur locally on the device or via explicit, anonymized synthetic data exports controlled by the user.

## 6. Permissions Required
To function correctly, the app requests the following permissions:
- **Internet / Wi-Fi / Network State:** Required to establish the WebRTC/Direct Socket connection for screen mirroring.
- **Wake Lock:** To keep the stream active during a session.
- **Vibrate:** For haptic feedback during connection states.
- **Body Sensors (Motion):** To read generic wrist motion intensity to predict network drop-offs during movement.
- **Camera:** Used only to scan the QR code shown on the watch during first-time pairing. StreamLinkWear does not access the camera at any other time and does not store camera images.
- **Microphone (Record Audio):** Used only to capture your phone's internal/system audio so it can be streamed to the watch during an active mirroring session. StreamLinkWear does not record ambient sound through the physical microphone and does not store audio.
- **Display Over Other Apps (System Alert Window):** Used only for the optional "Privacy Blackout" feature, which dims your phone's local screen while it continues streaming to the watch, so bystanders cannot see your screen content. This overlay is only shown while an active session requests it.
- **Accessibility Service:** Used only to inject the touch gestures you perform on the watch back onto the phone (tap/swipe/drag), so the watch can act as a remote control. This service does not read screen content, does not log what you type, and does not monitor other apps' accessibility events.
- **Notifications:** Used to show the persistent foreground-service notification required by Android while streaming or listening for a connection, and to alert you when a session is interrupted (e.g. network lost).
- **Camera:** Used only to scan the QR code shown on the watch during first-time pairing. StreamLinkWear does not access the camera at any other time and does not store camera images.
- **Microphone (Record Audio):** Used only to capture your phone's internal/system audio so it can be streamed to the watch during an active mirroring session. StreamLinkWear does not record ambient sound through the physical microphone and does not store audio.
- **Display Over Other Apps (System Alert Window):** Used only for the optional "Privacy Blackout" feature, which dims your phone's local screen while it continues streaming to the watch, so bystanders cannot see your screen content. This overlay is only shown while an active session requests it.
- **Accessibility Service:** Used only to inject the touch gestures you perform on the watch back onto the phone (tap/swipe/drag), so the watch can act as a remote control. This service does not read screen content, does not log what you type, and does not monitor other apps' accessibility events.
- **Notifications:** Used to show the persistent foreground-service notification required by Android while streaming or listening for a connection, and to alert you when a session is interrupted (e.g. network lost).
- **Camera:** Used only to scan the QR code shown on the watch during first-time pairing. StreamLinkWear does not access the camera at any other time and does not store camera images.
- **Microphone (Record Audio):** Used only to capture your phone's internal/system audio so it can be streamed to the watch during an active mirroring session. StreamLinkWear does not record ambient sound through the physical microphone and does not store audio.
- **Display Over Other Apps (System Alert Window):** Used only for the optional "Privacy Blackout" feature, which dims your phone's local screen while it continues streaming to the watch, so bystanders cannot see your screen content. This overlay is only shown while an active session requests it.
- **Accessibility Service:** Used only to inject the touch gestures you perform on the watch back onto the phone (tap/swipe/drag), so the watch can act as a remote control. This service does not read screen content, does not log what you type, and does not monitor other apps' accessibility events.
- **Notifications:** Used to show the persistent foreground-service notification required by Android while streaming or listening for a connection, and to alert you when a session is interrupted (e.g. network lost).
- **Camera:** Used only to scan the QR code shown on the watch during first-time pairing. StreamLinkWear does not access the camera at any other time and does not store camera images.
- **Microphone (Record Audio):** Used only to capture your phone's internal/system audio so it can be streamed to the watch during an active mirroring session. StreamLinkWear does not record ambient sound through the physical microphone and does not store audio.
- **Display Over Other Apps (System Alert Window):** Used only for the optional "Privacy Blackout" feature, which dims your phone's local screen while it continues streaming to the watch, so bystanders cannot see your screen content. This overlay is only shown while an active session requests it.
- **Accessibility Service:** Used only to inject the touch gestures you perform on the watch back onto the phone (tap/swipe/drag), so the watch can act as a remote control. This service does not read screen content, does not log what you type, and does not monitor other apps' accessibility events.
- **Notifications:** Used to show the persistent foreground-service notification required by Android while streaming or listening for a connection, and to alert you when a session is interrupted (e.g. network lost).

## 7. Changes to This Privacy Policy
We may update this Privacy Policy from time to time. We will notify users of any material changes by updating the "Effective Date" at the top of this policy and publishing the new version within the app.

## 8. Contact Us
If you have any questions or suggestions about our Privacy Policy, do not hesitate to contact the development team at [support@streamlinkwear.com].
