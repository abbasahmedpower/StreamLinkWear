# StreamLinkWear Battery Optimization Guide

Android's aggressive battery management (especially on Samsung, Xiaomi, and OnePlus devices) can kill background services, causing your stream to abruptly disconnect or freeze.

To ensure **Unbreakable Streaming**, you must instruct your users to exclude StreamLinkWear from these optimizations.

## General Android Instructions (Android 12+)

1. Go to **Settings** > **Apps** > **StreamLinkWear**.
2. Tap on **Battery**.
3. Change the setting from **Optimized** to **Unrestricted**.

## OEM Specific Instructions

### Samsung
1. Go to **Settings** > **Battery and device care** > **Battery**.
2. Tap on **Background usage limits**.
3. Add StreamLinkWear to the **Never sleeping apps** list.

### Xiaomi / MIUI
1. Go to **Settings** > **Apps** > **Manage apps** > **StreamLinkWear**.
2. Tap on **Battery saver**.
3. Select **No restrictions**.
4. Enable **Autostart** for the app.

### OnePlus / OxygenOS
1. Go to **Settings** > **Battery** > **Battery optimization**.
2. Find **StreamLinkWear** and select **Don't optimize**.
3. Lock the app in the "Recent Apps" menu by swiping down or tapping the lock icon.

## Why is this necessary?
StreamLinkWear uses a `FOREGROUND_SERVICE_MEDIA_PROJECTION` to continuously capture the screen and a `FOREGROUND_SERVICE_CONNECTED_DEVICE` to maintain the TCP socket with the smartwatch. If Android puts the app to sleep, these services are killed instantly, terminating the connection and breaking the experience.
