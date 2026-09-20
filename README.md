# mGBA Android Personal

Standalone Android frontend built directly on the official mGBA `mCore` API from the main source tree. It does not use Libretro or require RetroArch. The first build targets ARM64 handhelds such as the AYN Thor Max.

## Included

- Game Boy Advance, Game Boy and Game Boy Color
- Physical gamepad input
- Aspect-correct video and stereo audio
- Battery saves and three save-state slots
- Adjustable fast-forward
- GameShark / Action Replay cheat entry
- Android Storage Access Framework ROM picker
- Exported `ACTION_VIEW` activity for NeoStation and other frontends

## Build

1. Open this directory in Android Studio.
2. Install Android SDK 36, NDK 28.2.13676358 and CMake 3.22.1 when prompted.
3. Let Gradle sync.
4. Select **Build > Build APK(s)** for a test APK.
5. For a signed build, configure `signingConfigs.release` or use **Build > Generate Signed App Bundle or APK**.

The APK output is under `app/build/outputs/apk/`.

## NeoStation launch contract

Package: `com.aaron.mgbaandroid`

Action: `android.intent.action.VIEW`

Data: the ROM's granted `content://` URI. The activity accepts `.gba`, `.gb`, `.gbc` and `.zip` declarations. Direct ZIP extraction is planned; use uncompressed ROMs in the first test build.

## Status

This is an initial source build and still requires compilation and device testing. Keep backups of saves while testing. The Android frontend source is original work; the bundled main mGBA source retains its MPL-2.0 license.
