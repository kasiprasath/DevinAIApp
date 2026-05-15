# Devin AI — Android App

A production-ready Android WebView wrapper for [Devin AI](https://app.devin.ai/). This app provides a native-feeling experience for accessing Devin AI on Android devices.

## Features

- **Native Wrapper** — Opens `app.devin.ai` inside a full-screen WebView that feels like a real app, not a browser.
- **URL Filtering** — Only `devin.ai` and `*.devin.ai` load inside the app. All other external links open in the user's default browser.
- **Authentication Support** — Google, GitHub, Microsoft, and Auth0 login flows work seamlessly inside the WebView. Cookies and sessions persist across app restarts.
- **Offline Handling** — Detects internet loss and shows a retry screen. Automatically recovers when connectivity returns.
- **Pull-to-Refresh** — Swipe down to reload the current page.
- **Dark Theme** — Follows the system dark/light mode using Material Design 3.
- **Splash Screen** — Uses the Android 12+ SplashScreen API with backward compatibility.
- **File Upload** — Supports file chooser for uploading files to Devin.
- **Camera & Microphone** — Handles runtime permission requests for camera and mic access.
- **Download Support** — Files downloaded from Devin are saved to the Downloads folder via DownloadManager.
- **Fullscreen Media** — Supports fullscreen video/media playback.
- **Back Button** — Navigates web history first; exits the app only when there's no history left.
- **Security** — HTTPS-only, no file access, no cleartext traffic, SSL errors are rejected.
- **Hardware Acceleration** — Enabled globally for smooth scrolling and rendering.
- **Orientation Changes** — Handled via `configChanges` to prevent WebView reload.
- **Low Memory** — Calls `freeMemory()` on the WebView when the system is low on memory.

## Requirements

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- **Android SDK 35** (compileSdk / targetSdk)
- **Min SDK 24** (Android 7.0+)

## Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/kasiprasath/DevinAIApp.git
   cd DevinAIApp
   ```

2. **Open in Android Studio:**
   - File → Open → select the `DevinAIApp` directory.
   - Let Gradle sync complete.

3. **Run on device/emulator:**
   - Select a device or emulator (API 24+).
   - Click **Run** (▶) or press `Shift+F10`.

## Build

### Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (Signed)

1. **Create a keystore** (one-time):
   ```bash
   keytool -genkey -v -keystore devinai-release.jks \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -alias devinai
   ```

2. **Create `local.properties`** (not committed to git):
   ```properties
   RELEASE_STORE_FILE=../devinai-release.jks
   RELEASE_STORE_PASSWORD=your_store_password
   RELEASE_KEY_ALIAS=devinai
   RELEASE_KEY_PASSWORD=your_key_password
   ```

3. **Build the release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`

### AAB (Android App Bundle) for Play Store

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Project Structure

```
DevinAIApp/
├── app/
│   ├── build.gradle.kts          # App-level build config
│   ├── proguard-rules.pro        # ProGuard/R8 rules
│   └── src/main/
│       ├── AndroidManifest.xml   # App manifest
│       ├── java/com/devinai/app/
│       │   ├── DevinApplication.kt   # Application class
│       │   └── MainActivity.kt       # Main activity with WebView
│       └── res/
│           ├── drawable/         # Vector icons & drawables
│           ├── layout/           # XML layouts
│           ├── mipmap-*/         # Launcher icons (all densities)
│           ├── values/           # Strings, colors, themes (light)
│           ├── values-night/     # Dark theme overrides
│           └── xml/              # Network security & file provider config
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Project settings
├── gradle.properties             # Gradle properties
├── gradle/wrapper/               # Gradle wrapper
├── gradlew / gradlew.bat         # Gradle wrapper scripts
└── README.md
```

## Security Notes

- Only HTTPS connections are allowed (cleartext traffic is disabled).
- File access from the WebView is disabled.
- SSL errors are always cancelled (never bypassed).
- WebView debugging is only enabled in debug builds.
- The network security config enforces system trust anchors only.

## License

This project is proprietary. All rights reserved.
