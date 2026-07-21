# Coffee Shop POS

Native Android tablet POS written in Kotlin with Jetpack Compose and Room.

## Demo PINs

- Manager: `1111`
- Cashier: `2222`

## Run

1. Install Android Studio, JDK 17+, and the Android SDK.
2. Start an Android emulator or connect an Android tablet with USB debugging.
3. Double-click `start_app.bat`.

This workspace includes project-local Gradle and Android SDK command-line tools under `tools/`. The launcher uses Android Studio's bundled Java, the local SDK, and local Gradle when available, so it does not need global PATH setup for those pieces.

If no Android emulator or device is connected, `start_app.bat` will stop and ask you to start one.
