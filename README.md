# LensLink

Remote camera control for **Canon EOS M50 Mark II** (and most Canon PTP
bodies) on Android. Competitor-grade feature set:

- **USB-OTG** auto-detection (UsbManager, `device_filter.xml` for Canon
  vendor `0x04a9`) and **Wi-Fi PTP/IP** connection to the camera's own
  hotspot (subnet probe + manual IP:port entry).
- **Live viewfinder**: MJPEG preview frames drawn on a Compose Canvas with
  aspect-fit scaling, pinch-zoom and pan, plus overlays — rule-of-thirds /
  golden-ratio / crosshair grids, a live RGB histogram and zebra (clipping)
  warnings.
- **Exposure-triangle controls** read live from the PTP configuration tree:
  shutter speed (including **Bulb** via the canonical `bulb` TOGGLE widget),
  aperture, ISO, white balance, picture style, drive/metering modes — any
  property the camera exposes shows up automatically.
- **Dashboard**: connection type, camera model, raw gphoto2 summary, battery
  level and SD-card usage.
- **File browser**: SD-card listing with streaming downloads (256 KiB chunks,
  OOM-safe for .CR3 RAWs) into app-private storage.
- **Foreground service** keeps the session alive while data streams in the
  background. Material 3 **Expressive** theming with dynamic color and
  `MaterialShapes` containers, dark & light.

## Stack

| Piece        | Choice                                             |
|--------------|----------------------------------------------------|
| Language     | Kotlin 2.3.21 (AGP 9 built-in Kotlin opted out for now)              |
| UI           | Jetpack Compose (BOM 2026.09), Material 3 `1.5.0-beta02` for `MaterialShapes` |
| Native       | libgphoto2 + libusb1 + libltdl, vendored under `camerabridge/src/main/cpp/vendor/` |
| Bridge       | JNI (`libcamera_bridge.so`), PTP/IP transport via a `ptpip` iolib + the `ptp2` camlib |
| Builds       | Gradle 9.3.1, AGP 9.1.0, JDK 17, NDK r27, minSdk 26, compile/target SDK 37 |

## Modules

```
:app           — the application (Compose UI, repository, service, USB/Wi-Fi)
:camerabridge  — AAR library: cross-compiled libgphoto2 stack + JNI facade
```

The bridge module is documented in [`camerabridge/README.md`](camerabridge/README.md);
it carries an *extended* JNI surface (config read/write, capture, storage,
PTP-IP init) on top of the original CameraNativeBridge lifecycle.

## Building

There is nothing local to install — CI is the build system:

```bash
git push            # CI runs on every push/PR (see .github/workflows/android.yml)
```

The workflow builds `:camerabridge:assembleRelease`, `:app:assembleDebug`,
`:app:assembleRelease` and `:app:lintDebug`, then uploads the APKs and AAR
as artifacts. Tagged `v*` pushes also attach APKs to a GitHub release.

For a *signed* release APK in CI, add repo secrets:

| Secret                | Meaning                                  |
|-----------------------|------------------------------------------|
| `KEYSTORE_BASE64`     | JKS/PKCS12 keystore, base64-encoded       |
| `KEYSTORE_PASSWORD`   | keystore password                         |
| `KEY_ALIAS`           | signing key alias                         |
| `KEY_PASSWORD`        | key password (defaults to keystore pass)  |

Without them the release APK is simply unsigned.

Local development requires the Android SDK (NDK r27 + CMake 3.22.1 are
pulled by AGP):

```bash
./gradlew :app:assembleDebug
```

> **Note (USB-on-emulator / non-OTG hosts):** the bridge talks to the camera
> through a file descriptor handed over from `UsbManager` (libgphoto2's
> `gp_port_usb_set_sys_device`), so Android remains the one permission
> gate. See `camerabridge/README.md` for the full explanation.

## Camera setup

**USB:** set the M50's USB connection mode to *PTP* (`Menu → Setup →
[USB connection]` — not *Canon Connect Station*/*MTP*), then plug in.

**Wi-Fi:** enable the camera hotspot (`Menu → Wi-Fi/Bluetooth → Wi-Fi remote
connection`), join it from the phone, then either press **Probe /24** to
discover the PTP/IP listener on TCP/15740 or enter `IP`/`Port` by hand.

## Known constraints

- Live view runs at USB-2 speed (~6–10 fps) and a few seconds to boot.
- Wi-Fi PTP/IP is far slower than USB; use it for framing + settings, not
  RAW review.
- libgphoto2 is single-threaded here: every native call is funneled through
  one dispatcher lane (`CameraRepository`), so the preview loop never races
  config writes or downloads.
- Lint: wire up `ACCESS_FINE_LOCATION` runtime grant on API &lt; 33 in your
  launcher flow if you want hotspot scan results instead of just manual
  IP entry (the app degrades gracefully without it).
