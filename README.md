# DroidPerf

A lightweight, real-time performance overlay for Android. It renders an on-screen HUD over apps and games to display live hardware telemetry: target FPS, frame times, CPU/GPU utilization, clock speeds, thermals, RAM, and battery draw.

Unlike typical overlays that measure their own frame render callbacks, DroidPerf hooks directly into Android's graphics compositor (`SurfaceFlinger`) to measure true frame delivery from the foreground app.

## Features

- **Game FPS & Frame Times**: Sub-millisecond latency tracking via SurfaceFlinger timestamps (requires Root or Shizuku).
- **CPU & GPU Metrics**: Total/per-core usage, current frequencies, thermal zones, and vendor-specific GPU load (Adreno KGSL, devfreq).
- **Memory & System**: Detailed RAM breakdown (active, cached, available) and foreground package detection.
- **Battery Telemetry**: Real-time voltage, current, and wattage draw.
- **Multiple OSD Styles**: Includes RTSS (RivaTuner style), Classic compact, and Minimal HUD modes.
- **Zero Fabrication**: If a sensor or sysfs node is restricted by OEM firmware, it cleanly displays `N/A` rather than guessing or interpolating.

## Access Modes

Telemetry depth depends on device privileges. DroidPerf automatically detects available methods and saves your choice:

| Mode | Requirements | Capabilities |
|---|---|---|
| **Standard** | None | CPU usage/frequency, RAM, battery, network, basic thermals |
| **Shizuku** | Shizuku running (Wireless ADB) | Target FPS, frame times, shell-level app detection |
| **Root** | Magisk, KernelSU, or APatch | Full hardware sysfs access, dumpsys SurfaceFlinger, low-level thermals |

### Shizuku Setup
1. Install and start [Shizuku](https://shizuku.rikka.app/).
2. In DroidPerf, select Shizuku as your access mode and approve the authorization prompt.

DroidPerf communicates with a standalone `UserService` binder running under UID 2000 (shell).

### Root Setup
Open DroidPerf and grant superuser access when prompted by KernelSU, Magisk, or APatch. Shell commands are strictly filtered through a static whitelist (`SafeCommands`).

## How FPS Measurement Works

Android apps cannot inspect another app's Choreographer render pipeline without platform signature permissions. To measure game FPS accurately without modifying the target:

1. DroidPerf resolves the active `SurfaceView` or window layer from `dumpsys SurfaceFlinger --list`.
2. It samples timestamps via `dumpsys SurfaceFlinger --latency <layer>` (or `dumpsys gfxinfo` fallback).
3. Frame delta is calculated from hardware `actual-present` timestamps:
   `FPS = (presentedFrames - 1) / (lastPresent - firstPresent)`
4. Sentinels and pending VSYNC timestamps (`Long.MAX_VALUE`) are discarded to avoid skew during scene loads.

## Hardware & OEM Support

- **Qualcomm Adreno**: Direct utilization reads via `/sys/class/kgsl/kgsl-3d0/gpubusy`.
- **ARM Mali / Samsung Xclipse**: Polled via `/sys/class/devfreq/*/load` where exposed by the vendor kernel.
- **Thermals**: Scans `/sys/class/thermal/thermal_zone*` and matches zone types (e.g., `cpu-1-usr`, `gpu-thermal`, `battery`) instead of relying on fragile index numbers.

## Building

Requires JDK 17 and Android SDK Platform 35.

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest

# Build optimized release
./gradlew :app:assembleRelease
```

Built APKs are located in `app/build/outputs/apk/`.

## Architecture

```
app/src/main/java/com/droidperf/
├── domain/        Metric data structures, snapshot models, access enums
├── system/        SysFs readers, hardware capability probing, shell wrappers
├── monitoring/    Isolated metric collectors (FPS, CPU, GPU, RAM, Battery, Thermal)
├── overlay/       Floating window lifecycle, Canvas renderer, RTSS layout engine
├── shizuku/       Shizuku IPC binder & UserService bridge
├── settings/      Preferences datastore, layout presets, position state
├── ui/            Configuration activities, setup wizard, access selector
└── di/            ServiceLocator
```

Every metric is isolated: if a specific sysfs interface or shell command fails, only that single gauge marks itself unavailable while the rest of the overlay continues updating.

## License & Privacy

DroidPerf runs completely offline. It contains no tracking, telemetry SDKs, or network request endpoints. Network permissions are used solely to measure local Wi-Fi/cellular throughput and ping latency on user request.
