# Auto Screenshot

[![Android Build](https://github.com/SimonBaars/Android-AutoScreenshot/actions/workflows/android-build.yml/badge.svg)](https://github.com/SimonBaars/Android-AutoScreenshot/actions/workflows/android-build.yml)

An Android app that takes screenshots every 10 seconds, organizing them by date and removing identical screenshots automatically.

## How to install

Download the app here: https://github.com/SimonBaars/Android-AutoScreenshot/releases/download/1.0/app-debug.apk

Accept the permissions and start the service. Select "entire screen" when prompted what to record. Navigate to `Screenshot/*` in your file manager to view the screenshots.

## Other versions
- Windows: https://github.com/SimonBaars/windows-auto-screenshot
- XFCE (Linux): https://github.com/SimonBaars/xfce-auto-screenshot
- Android (Magisk): https://github.com/SimonBaars/magisk-auto-screenshot
- Android (APK): https://github.com/SimonBaars/Android-AutoScreenshot

## Features

- Automatic screenshots every 10 seconds
- Organized folder structure by year/month/day
- Automatic removal of duplicate screenshots
- Runs in the background with a persistent notification
- Works on modern Android versions without root access

## How It Works

This app uses Android's MediaProjection API to capture screenshots, which requires user permission when first started. The app organizes screenshots in a similar way to the shell script it's based on:

```
/storage/emulated/0/Android/data/com.simonbrs.autoscreenshot/files/Screenshot/YYYY/MM/DD/HH_MM_SS.png
```

Each folder also contains a `.nomedia` file to prevent the screenshots from appearing in the gallery.

## Permissions Required

- Media projection permission (for taking screenshots)
- Storage access (to save screenshots)
- Notification permission (for foreground service on newer Android versions)

## Usage

1. Launch the app
2. Grant the required permissions
3. Press "Start Screenshot Service"
4. Confirm the screen capture permission
5. The app will start taking screenshots in the background

## Technical Details

- Uses a foreground service with MediaProjection API
- Handles various Android permission models (pre-Android 10, Android 10+)
- Compares files byte-by-byte to detect duplicates
- Written in Kotlin with Jetpack Compose UI 
