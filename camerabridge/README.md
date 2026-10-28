# camerabridge

Kotlin + JNI bridge over **libgphoto2 / libusb1 / libltdl**, cross-compiled
for Android (`arm64-v8a`) by AGP's external native build.

Output: `camerabridge-release.aar` — consumed by `:app` and reusable as a
standalone `com.mav.cameranativebridge` facade.

## What's inside the AAR

| `.so`                    | Role                                                  |
|--------------------------|-------------------------------------------------------|
| `libcamera_bridge.so`    | JNI layer (two TUs, below)                             |
| `libgphoto2.so`          | vendored libgphoto2 core                               |
| `libgphoto2_port.so`     | port core                                              |
| `libptp2.so`             | PTP camlib (EOS bodies; also speaks to the M50 Mark II) |
| `liblibusb1.so`          | USB port iolib (wraps the UsbManager fd)               |
| `libptpip.so`            | **added here**: PTP/IP port iolib for Wi-Fi sessions   |

`useLegacyPackaging = true` unpacks these to `nativeLibraryDir`, which is
passed to libgphoto2 as both `IOLIBS` and `CAMLIBS`; the bundled `ltdl`
dlopen()s them at runtime.

## Translation units

- `camera_bridge.cpp` — original lifecycle/preview/file-listing surface:
  `nativeInitWithFd`, `nativeGetPreviewFrame`, `nativeGetFileList`,
  `nativeDownloadFileTo`, `nativeClose`, `nativeVersion`.
- `camera_control.cpp` — **new control surface** added for LensLink:
  `nativeGetSummary`, `nativeGetCameraModel`, `nativeListConfigNames`,
  `nativeGetConfig`, `nativeSetConfig`, `nativeReadConfigText`,
  `nativeCaptureImage`, `nativeGetStorageInfo`, `nativeInitWithIp`.
- `gp_session.h` — shared session struct/globals (in namespace `gpbridge`)
  and error helpers used by both TUs.

## JNI surface (mirrored by `NativeCameraInterface`)

| Kotlin API                        | Notes                                                    |
|-----------------------------------|----------------------------------------------------------|
| `init(UsbManager, UsbDevice)`     | opens via UsbManager, passes raw fd to `nativeInitWithFd` |
| `initWifi(host, port)`            | `gp_port_usb_set_sys_device(-1)` first, then `<ip>:<port>` string into the `ptpip` port |
| `getSummary()` / `getCameraModel()` | gphoto2 summary + abilities model                       |
| `listConfigNames()`               | flat config names (ptp2 uses widget *names* like `iso`, `bulb`) |
| `getConfig(name)`` → CameraConfig | `[label, type, readonly, current, choices…]`              |
| `setConfig(name, value)`          | string form: choice text for RADIO/MENU, "1"/"0" for TOGGLE |
| `readConfigText(name)`            | e.g. `batterylevel` → "100%/75%/50%/25%/Low"              |
| `captureImage()` → CameraCaptureResult? | `gp_camera_capture(GP_CAPTURE_IMAGE)`              |
| `getStorageInfo()` → CameraStorage[] | `gp_camera_get_storageinfo`, tab-separated rows        |
| `listFiles()` / `downloadFileTo()` | depth-limited folder listing; streaming raw-fd downloads |
| `capturePreviewOnce()`            | one `gp_camera_capture_preview` frame (JPEG)             |

## USB fd workaround (why not plain libgphoto2 enumeration)

Android's `UsbManager` owns USB device discovery and permission gating, so
libusb may not enumerate devices itself. The Kotlin layer opens the device,
and the C side calls `gp_port_usb_set_sys_device(fd)` *before* any port
load; the `usb1` iolib then wraps the fd with
`libusb_wrap_sys_device(...)` under `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`, and
only the wrapped device is ever visible to libgphoto2.

The same knob is reset (`-1`) before Wi-Fi (ptpip) sessions so a stale USB
fd never gets wrapped.

## Threading

libgphoto2 is **not** thread-safe. `NativeCameraInterface` serializes with
`@Synchronized` on init/close/download, and the app-side repository funnels
*every* native call through a single-lane `Dispatchers.IO.limitedParallelism(1)`
dispatcher — no double locking, no deadlock, no concurrent gphoto calls.

## Building

```bash
./gradlew :camerabridge:assembleRelease
```

Consumer proguard rules are in `consumer-rules.pro` (keeps the native
declarations and marshalling data classes). The heavy verification of the
bionic quirks is done by the compiler — the vendored tree is upstream
libgphoto2 with its `.git` directories stripped.
