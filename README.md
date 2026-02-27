# Nordic DFU Looper (Android)

Minimal Android utility app that repeatedly performs Nordic DFU from a selected ZIP file to a selected BLE device.

## What it does

- Lets you pick a DFU ZIP with Android's document picker.
- Lets you scan/select a BLE target device.
- Starts a loop that:
  - scans for the selected target (including incremented DFU MAC),
  - runs DFU using Nordic Android DFU Library,
  - increments and displays successful iteration count,
  - retries automatically after completion or error.

## Library

This app uses Nordic's library:

- https://github.com/NordicSemiconductor/Android-DFU-Library

Dependency is declared in `app/build.gradle.kts` as:

- `no.nordicsemi.android:dfu:2.4.0`

If you want a newer version, update this line.

## Open and run

1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle and generate/update wrapper files if prompted.
3. Run on a physical Android device with BLE.
4. In app: select ZIP, select device, then tap **Start Loop**.

## Notes

- Test utility only; no persistence layer is included.
- Keep the app in foreground while stress-testing DFU behavior.
