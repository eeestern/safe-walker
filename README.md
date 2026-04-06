# SafeWalker

An Android app that uses the rear-facing camera to detect obstacles and warn you of potential dangers while walking — even when using other apps.

## Features

- **Real-time obstacle detection** using Google ML Kit Object Detection
- **Background operation** via foreground service — keeps working while you text
- **Smart danger assessment** categorizes threats by type and proximity:
  - Collision risks (people, poles, walls)
  - Trip hazards (curbs, objects on ground)
  - Street/traffic dangers (vehicles, road)
- **Multi-modal alerts**: voice warnings (TTS), vibration patterns, and notifications
- **Adaptive alert frequency**: urgent threats trigger immediate alerts; low-risk items are less intrusive

## Tech Stack

- Kotlin + Jetpack Compose
- CameraX for camera access
- Google ML Kit Object Detection
- Material 3 design

## Requirements

- Android 8.0 (API 26) or higher
- Device with rear camera

## Building

```bash
./gradlew assembleDebug
```

## Permissions

- **Camera** — for obstacle detection
- **Notifications** — for danger alerts
- **Vibrate** — for haptic warnings
- **Foreground Service** — to run in background
