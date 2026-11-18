# TDriEV

A lightweight, private native Android companion app for Tata electric vehicles (Tiago.ev, Nexon.ev, Punch.ev, Tigor.ev, and Curvv.ev) — reverse-engineered from [Tata.ev](https://play.google.com/store/apps/details?id=com.tatamotors.evoneapp).

Built with Kotlin and Jetpack Compose. Fast, AMOLED-black, and connects directly to the vehicle API with no third-party tracking, analytics, or middleman servers.

---

## Features

### Fast, Offline-First Dashboard
* **0ms Cold-Start Launch:** Telemetry snapshots are stored in local encrypted cache (`stale-while-revalidate`). When you open the app, your last known battery percentage, range, odometer, and lock state appear instantly without waiting for a server round-trip.
* **Verified Telemetry:** Background sync quietly updates readings from the vehicle service when available. Stale data is never disguised as new observations.
* **Vehicle Specific Tuning:** Strips unsupported sensor clutter (e.g., boot and bonnet indicators are removed for Tiago.ev models that lack closures hardware).

### Remote Vehicle Controls
* **Direct Operations:** Lock, Unlock, Climate control (Start/Stop AC with configurable 5–20 minute timer), Horn alert, Approach lamps, Hazard lights, and Position lamps.
* **Instant Button Queuing:** Tapping any command immediately reflects a `Queued…` state with a progress spinner, giving immediate visual feedback while respecting the vehicle gateway's command-spacing window.
* **Optimistic Toggles:** Lamp controls reflect your toggled action immediately, then reconcile against server telemetry once the command finishes.

### Live Map & Navigation
* **Embedded Street Map:** Built-in vector map showing the vehicle's last reported location with a custom car marker pin.
* **One-Tap Directions:** Dedicated *Open in Maps* shortcut launches Google Maps or your preferred navigation app routed directly to the car's coordinates.
* **Live User Location:** Optional toggle in Account Settings to display your real-time GPS position alongside the car on the map.

### Home Screen Widgets
Add quick widgets directly to your Android launcher:
1. **TDriEV Remote Controls:** One-tap Lock and Unlock buttons directly from your home screen.
2. **TDriEV Battery & Range:** Glanceable battery percentage, distance to empty, and charging status.
3. **TDriEV Status & Location:** Live odometer, doors security, ignition state, and a quick navigation shortcut to find your car.

### App Lock & Biometrics
* **PIN & Password Guard:** Secure your vehicle dashboard and remote actions behind a 4-digit PIN.
* **Instant Biometric Unlock:** Seamless fingerprint and face recognition using Android's native `BiometricPrompt` with zero third-party tracking.
* **Automatic Background Lock:** Automatically secures the app whenever you switch away or lock your phone.

### Modern Android Design
* **Material You Dynamic Theming:** Adapts accent colors automatically to your phone's wallpaper palette on Android 12+.
* **Pure AMOLED Dark Mode:** Default true black (`#000000` / `#121212`) background to save power on OLED screens.

---

## Privacy & Security

* **Direct Transport:** The app communicates directly with official vehicle service endpoints using TLS. There are no intermediate cloud proxies, telemetry aggregators, or third-party loggers.
* **Hardware-Backed Encryption:** Session tokens and authentication keys are stored using `AndroidKeyStore` AES-256-GCM in no-backup app storage.
* **No Unnecessary Permissions:** The app does not request SMS reading permissions (OTP codes are entered manually by you). Location permission is only used if you explicitly enable the optional "Show my live location" toggle.

---

## Getting Started

### Download APK
Pre-built release packages are available in the [`releases/`](releases/) directory:
* [`releases/TDriEV-v0.2.6.apk`](releases/TDriEV-v0.2.6.apk)
* Checksum: See [`releases/SHA256SUMS`](releases/SHA256SUMS)

### Building from Source

Requirements:
* Android SDK 34+
* JDK 17+

Clone the repository and run:

```bash
# Build the owner debug APK
./gradlew :app:assembleOwnerDebug

# Run unit tests
./gradlew :core:test :app:testOwnerDebugUnitTest
```

The compiled APK will be located at:
`app/build/outputs/apk/owner/debug/app-owner-debug.apk`

---

## Disclaimer

This is an independent open-source project and is not affiliated with, endorsed by, or associated with Tata Motors or its subsidiaries. All product names, trademarks, and registered trademarks belong to their respective owners.
