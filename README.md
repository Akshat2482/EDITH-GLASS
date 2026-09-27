# EDITH Glasses — ESP32-S3 + Android Smart Glasses System

```
PHONE MICROPHONE → ANDROID SPEECH-TO-TEXT → TRANSCRIBED TEXT → BLE →
ESP32-S3 → SSD1306 → HORIZONTAL FRAMEBUFFER FLIP → REFLECTIVE OPTIC → USER
```

Project layout:

```
EDITH-Glasses/
├── firmware/
│   └── EDITH_Glasses.ino      ← ESP32-S3 Arduino sketch
├── android/                   ← full Android Studio project (Kotlin, Compose)
│   └── app/src/main/java/com/akshat/edithglasses/
│       ├── MainActivity.kt        UI (Jetpack Compose)
│       ├── EdithViewModel.kt      glues BLE + speech state to the UI
│       ├── BleManager.kt          scanning, connecting, chunked writes
│       ├── SpeechHelper.kt        wraps Android SpeechRecognizer
│       └── BleProtocol.kt         UUIDs + framing constants
└── BLE_PROTOCOL.md            ← full protocol documentation
```

---

## 1. Hardware wiring

| SSD1306 pin | ESP32-S3 pin |
|---|---|
| VCC | 3V3 |
| GND | GND |
| SDA | GPIO 8 |
| SCL | GPIO 9 |

Confirm your OLED module's I2C address is `0x3C` (most 0.96" yellow/blue SSD1306 modules are). If your board's silkscreen uses different GPIO numbering, just edit `OLED_SDA` / `OLED_SCL` at the top of `EDITH_Glasses.ino` — everything else in the firmware references those two constants.

Mount the OLED so its output is viewed through your reflective combiner/prism. No hardware mirror is required — the firmware flips the framebuffer in software before every single frame (`pushMirrored()` in the sketch is the only path that ever reaches the physical display).

## 2. Flashing the firmware

1. In Arduino IDE, install board support: **Tools → Board → Boards Manager → search "esp32" → install "esp32 by Espressif Systems"**.
2. Select **Tools → Board → ESP32 Arduino → ESP32S3 Dev Module** (or the specific S3 variant matching your board).
3. Install libraries via **Sketch → Include Library → Manage Libraries**:
   - `Adafruit GFX Library`
   - `Adafruit SSD1306`
   - (the BLE library used, `BLEDevice.h` etc., ships with the ESP32 board package — nothing extra to install)
4. Open `firmware/EDITH_Glasses.ino`, select the correct COM/serial port, and click **Upload**.
5. Open the Serial Monitor at 115200 baud. On boot you should see the animated sequence on the OLED, ending with `SYSTEM ONLINE` printed to Serial and an idle HUD (crosshair + "STANDBY") shown on the display.

The board is now advertising as `EDITH-GLASSES`.

## 3. Building and installing the Android app

1. Open the `android/` folder (not the repo root) directly in **Android Studio** (Koala/Ladybug or newer). It will detect the Gradle project automatically via `settings.gradle.kts`.
2. Let Gradle sync — it will download the Compose BOM, AndroidX libraries, and the Android Gradle Plugin.
3. Connect your Android phone (Android 8.0 / API 26 or newer) via USB with **USB debugging** enabled, or use an emulator with Bluetooth support (physical device strongly recommended for BLE).
4. Click **Run ▶** with the `app` module selected. The app installs and launches as **EDITH Glasses**.
5. On first launch the app requests:
   - **Nearby devices** (Bluetooth scan/connect — Android 12+) or **Location** (Android 11 and below, required by the OS for BLE scanning even though this app does not use your location)
   - **Microphone**

   Grant both — the app is hands-free: the instant permissions are granted it starts scanning for `EDITH-GLASSES` and starts listening continuously. Every time it finalizes a phrase it sends that phrase to the glasses automatically and immediately goes back to listening for the next one — no mic tap, no send tap needed on a normal run. Tap the mic button any time to pause the loop (it stops listening and stops auto-sending); tap again to resume. There is no manual SEND button in the normal hands-free flow.

## 4A. JARVIS / Groq

When the live EDITH transcription hears a phrase beginning with **"Jarvis"**, the app treats the rest of the phrase as a question instead of sending the question itself to the OLED.

Example:

```
"Jarvis, what is the capital of France?"
                ↓
             Groq
                ↓
"The capital of France is Paris."
                ↓
             EDITH OLED
```

JARVIS answers are sent automatically to the glasses. Long answers smoothly scroll vertically on the OLED instead of jumping directly between pages.

For a local Android Studio build, put your Groq API key in `android/local.properties` (this file is ignored by Git):

```properties
GROQ_API_KEY=gsk-your-key-here
```

Do **not** commit the key or paste it into a public GitHub file. Treat the Groq API key like a password and never store it in source code or a public repository. For a production version, move the Groq request behind a small server so the key never ships inside the APK.

The app uses Groq's OpenAI-compatible Chat Completions endpoint with the `llama-3.3-70b-versatile` model.

## 4. Pairing / connecting the phone to the ESP32

BLE here does **not** use the standard Android Bluetooth "pairing" dialog — the app connects directly via GATT, which is how BLE peripherals like this are normally used (no PIN, no OS-level bonding required):

1. Power on the ESP32-S3 — confirm it's shown the idle HUD (meaning `bootAnimation()` finished and it's advertising).
2. Open the EDITH Glasses app.
3. Tap **CONNECT**. The app scans specifically for a device advertising the EDITH service UUID and named `EDITH-GLASSES`, and connects automatically the moment it's found.
4. The status panel turns green and shows "Connected" once the app has discovered the text characteristic on the ESP32.
5. Tap the microphone button, speak, and the live transcription appears in the **TRANSCRIPTION** box.
6. Normal speech is sent automatically (chunked + reassembled per `BLE_PROTOCOL.md`) and appears mirrored on the OLED inside the HUD frame, auto-wrapped and smoothly scrolled if it's long.
7. Tap **DISCONNECT** to close the BLE connection. The ESP32 automatically resumes advertising so you can reconnect at any time.

If **CONNECT** doesn't find the device: confirm the ESP32 finished its boot animation (it isn't advertising until `setup()` completes), confirm Bluetooth is on, and confirm the phone granted the Bluetooth permissions (the status line under the CONNECT/DISCONNECT buttons will say so).

## 5. Building via GitHub Actions (no Android Studio needed)

The repo includes a working Gradle wrapper (`android/gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) and a workflow at `.github/workflows/build-apk.yml`, so you can push this straight from Termux and let GitHub build the APK — same setup as `nexus-map-app`.

1. Create a new GitHub repo (or reuse one) and push this project:
   ```
   cd EDITH-Glasses
   git init
   git add .
   git commit -m "Initial EDITH Glasses project"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<repo>.git
   git push -u origin main
   ```
   On a fresh Termux install/device, if `git` complains about "dubious ownership", run the usual fix first:
   ```
   git config --global --add safe.directory /storage/emulated/0/Download/<repo>
   ```
2. The workflow triggers automatically on every push to `main`/`master` that touches `android/**`, or manually via **Actions → Build EDITH Glasses APK → Run workflow**.
3. It sets up JDK 17, runs `./gradlew assembleDebug` (with `-Xmx4g` to avoid the OOM issue seen on other projects), and uploads the result as a build artifact.
4. Once the run finishes (green check), open the run in the **Actions** tab and download the `edith-glasses-debug-apk` artifact — it's a zip containing `app-debug.apk`. Transfer it to your phone (or `wget` it via a signed artifact URL) and install it directly, no Play Store / Android Studio required.

The workflow only builds the Android app — the ESP32 firmware is still flashed locally via Arduino IDE as in step 2, since GitHub Actions has no way to reach your board over USB.

## 6. Notes on the mirrored display

Every rendering path in the firmware — the boot animation, the idle HUD, and `displayMirroredText()` — draws normally into the SSD1306 library's in-memory buffer and only ever reaches the physical panel through `pushMirrored()`, which reverses the column order of the buffer before calling `display.display()`. There is no code path in the sketch that calls `display.display()` directly, so it's not possible for an unflipped frame to reach the glass.
