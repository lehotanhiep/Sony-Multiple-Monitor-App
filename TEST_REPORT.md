# Sony Multiple Monitor V1.0.0 - Validation Report

This report belongs to the **Sony Multiple Monitor V1.0.0** source release.

## Release identity

- Product: Sony Multiple Monitor
- Release: V1.0.0
- Android `versionName`: `1.0.0`
- Android `versionCode`: `10000`
- Launcher label: `Sony Multiple Monitor V1.0.0`
- Gradle root project: `SonyMultipleMonitor`
- Application ID: `com.example.sonymultilive`

The application ID is intentionally retained for Android update compatibility; it is an internal package identifier, not the product branding.

## Stable behaviors retained

- Sony PTP/IP command/event transport on TCP 15740.
- Sony SDIO initialization.
- Live View through object `0xFFFFC002`.
- Event-driven property synchronization through `0xC203` / `0x4006`.
- Multi-camera REC/STOP behavior.
- ZV-E10M2 Playback PLAY/PAUSE through physical `D309 ENTER 0x0005`.
- Previous/Next Playback navigation through physical LEFT/RIGHT button commands.
- Static `PLAY` button UI: repeated taps toggle play/pause without changing label or color.
- Automatic camera assignment and 1/2/3/4-camera layouts.
- Startup 4-frame `2x2` monitor canvas.
- Swipe up hides application controls for full-screen monitoring; swipe down restores them.

## V1.0.0 regression results

The following JVM protocol tests were executed from this release tree:

- `PtpStableTransportSelfTest`: **PASS** - Sony PTP/IP + SDIO + `FFFFC002` baseline.
- `PtpPlaybackZve10m2CenterSelfTest`: **PASS** - ZV-E10M2 routes PLAY/PAUSE through `D309 ENTER 0x0005`.
- `PtpPlaybackTileTransportSelfTest`: **PASS** - PLAY/PAUSE center, Previous left, Next right.
- `PtpC203BulkSnapshotSelfTest`: **PASS** - `0x9209` refresh updates dynamic REC/EV properties.
- `PtpCriticalDynamicRawScanSelfTest`: **PASS** - malformed descriptors do not hide critical dynamic properties.
- `FocusCompactSelfTest`: **PASS** - focus variants compact correctly to AF/MF.
- `ExposureLogicSelfTest`: **PASS** - EV Auto/Manual gating remains correct.

## Source-release checks

- No development-version branding remains in the active app source, release README, or validation report.
- Sony discovery class is version-neutral: `SonyPtpDiscovery`.
- Camera-session startup method is version-neutral: `startPtpSession()`.
- README documents the full-screen gesture: swipe up to hide controls, swipe down to restore them.
- Development-era markdown/change logs are excluded from the V1.0.0 release package.

## Build environment note

Android APK assembly is not performed by this source-package validation when the local Android SDK or Gradle wrapper JAR is unavailable. The project is intended to be opened and built in Android Studio with JDK 17 and Android SDK 35.
