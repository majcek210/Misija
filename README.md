# Mock GPS (QA tool)

Android test tool (package `dev.tester.mockgps`, minSdk 26, targetSdk 34) that
starts from your real position and lets you walk the mocked location in any
direction with a joystick in a floating panel.

## Get the APK
Every push builds `MockGPS.apk` with GitHub Actions and attaches it to a
release under **Releases** (tag `build-N`). Open it on the phone and install
(allow "Install unknown apps" for your browser/GitHub app).

Or build yourself: Android Studio > Build > Build APK(s), or `./gradlew assembleDebug`
(output: `app/build/outputs/apk/debug/app-debug.apk`).

## Setup on the phone
1. Settings > About phone > tap **Build number** 7 times to enable Developer options.
2. Open Mock GPS, press **1** (location + notifications) and **2** (display over other apps).
3. Press **3**, then in Developer options choose **Select mock location app > Mock GPS**.
4. Press **Start from my location**.

## Floating panel
- Drag the title to move it. `▾` folds, `↺` goes back to your real location, `✕` stops.
- Joystick: hold and push in a direction to move; push further to go faster. Let go to stop.
- **Speed** cycles Walk (5 km/h), Jog (11), Bike (22), Car (54).

GPS, network and (Android 12+) fused test providers get a fix every second
(accuracy 5 m). They are removed when the service stops.
