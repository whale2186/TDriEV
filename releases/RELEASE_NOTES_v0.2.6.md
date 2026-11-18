# Release v0.2.6 — TDriEV

Initial standalone release of **TDriEV**, a lightweight native Android companion application for Tata electric vehicles (Tiago.ev, Nexon.ev, Punch.ev, Tigor.ev, and Curvv.ev) — reverse-engineered from [Tata.ev](https://play.google.com/store/apps/details?id=com.tatamotors.evoneapp).

## What's New

### Dashboard & Performance
* **Instant Startup Cache:** Telemetry data (battery, range, odometer, lock state) is persisted to encrypted local storage, eliminating cold-start blank screens.
* **Streamlined Tiago.ev Layout:** Removed unsupported bonnet and boot closure flags to match physical vehicle sensors.
* **AMOLED True Black:** Clean dark theme with Android 12+ Material You dynamic color adaptation.

### Remote Controls
* **Instant Queuing Feedback:** Buttons show `Queued…` state immediately upon tapping while respecting backend command spacing.
* **Direct Controls:** Lock, Unlock, Climate control (configurable 5–20 min duration), Horn alert, Approach lamps, Hazard lights, and Position lamps.

### Live Map
* **Vector Street Map:** Embedded vector map centered on the vehicle's coordinates with a dedicated car marker badge and zoom controls.
* **Directions Shortcut:** One-tap button to launch Google Maps navigation directly to where the car is parked.
* **Live User Position:** Optional toggle to display real-time phone GPS location alongside the car on the map.

### Home Screen Widgets
* **Remote Controls Widget:** One-tap Lock and Unlock buttons directly from the launcher.
* **Battery & Range Widget:** State of charge percentage, distance to empty, and charging status.
* **Status & Location Widget:** Live odometer, doors status, ignition, and a navigation shortcut.

### App Lock & Biometrics
* **PIN & Password Access Guard:** Protect vehicle control access behind a 4-digit PIN with secure local salted SHA-256 validation.
* **Native Biometric Authentication:** Instant fingerprint and face unlock via Android `BiometricPrompt`.
* **Automatic Security:** Auto-locks when returning to background or locking your phone, with in-app manual "Lock Now" testing.

### Custom Theming & Appearance
* **Interactive 2D Color Picker:** Visual 2D saturation/brightness gradient canvas and rainbow hue slider with hex readout.
* **Universal Color Presets:** Single-row quick palette (Red, Orange, Green, Teal, Blue, Purple, Pink).
* **AMOLED True Black:** Option for pure `#000000` background for maximum OLED battery savings.
* **Persistent Preferences:** Live map user location, dynamic color, and theme settings persist across app restarts.

## Downloads & Verification

| File | SHA-256 Checksum |
| :--- | :--- |
| `TDriEV-v0.2.6.apk` | See `SHA256SUMS` |

Verify package integrity:
```bash
sha256sum -c SHA256SUMS
```
