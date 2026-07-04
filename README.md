# CLIP Finder

CLIP Finder is an on-device Android app that helps you search your photo library with natural language prompts and optional person filters.

Everything runs locally on your device:
- image indexing
- text-to-image matching
- person alias matching

No cloud account is required.

## Key Features

- **Natural language photo search**  
  Search using prompts like "sunset at the beach", "dog in the park", or "red car at night".

- **Optional negative prompt**  
  Exclude unwanted concepts while searching.

- **People aliases**  
  Create a person alias from sample photos, then filter search results by that person.

- **Background processing**  
  Photo scanning and alias refinement run in the background with progress updates.

- **On-device privacy**  
  Embeddings and alias data are stored locally in app storage.

## Requirements

- Android device (Android 8.0+)
- Photo library permission
- Internet connection for first-time model download
- Optional notification permission for background status updates

## Installation

CLIP Finder is not published on Google Play. You can either sideload the prebuilt APK or build it yourself from source.

### Option 1: Install the prebuilt APK

1. On your Android device, go to **Settings → Apps → Special access → Install unknown apps** and allow your browser or file manager to install apps.
2. Download `CLIPFinder.apk` from [this link](https://drive.google.com/file/d/15chZ0NtQJkGCO1U1cvQ5F1mHzcleAco7/view?usp=drive_link) (or transfer it via USB / cloud storage).
3. Open the APK file from your file manager and tap **Install**.
4. If prompted by Play Protect, tap **Install anyway**.
5. Launch **CLIP Finder** from your app drawer.

### Option 2: Build from source

Prerequisites:
- JDK 17+
- Android SDK (API 34 or newer) with build-tools installed
- ADB and a connected Android device or emulator (Android 8.0+)

Steps:

```bash
git clone <repo-url> CLIPFinder
cd CLIPFinder

./gradlew assembleDebug

./gradlew installDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Getting Started

1. **Install the app** (see [Installation](#installation))
2. **Open CLIP Finder**
3. **Grant photo library permission** when prompted
4. **Download models** when prompted (requires internet)
5. **Tap "Scan new photos (background)"** to index your library
6. Go to **Search** and run your first prompt

## Search Workflow

1. Enter a **positive prompt**
2. (Optional) Enter a **negative prompt**
3. Set **Top k** (number of results)
4. Tap **Search library**
5. Tap any result to open full preview and scores

## Person Alias Workflow

1. Go to **People**
2. Enter an alias name
3. Pick example photos of that person
4. Tap **Create alias and start refinement**

   > **Note:** The first alias creation is slow (typically around an hour) because the app builds its initial face index. Subsequent aliases usually complete in seconds. Refinement runs in the background, so you can leave the app and return once you receive the completion notification. If the job stalls or fails, delete the alias and recreate it — progress is cached, so it will resume near the point of failure within a few seconds.
5. (Optional) Confirm/refuse items in **Validation preview**
6. Use the alias filter in **Search**

You can also:
- tune per-alias match threshold
- reclassify from cache after threshold changes
- delete aliases

## Privacy & Data Handling

- CLIP Finder performs matching on-device.
- Photo embeddings and face-match metadata are stored locally.
- The app does not require a user account.
- Model files are downloaded once and stored in app-internal storage.

## Troubleshooting

- **No results found**
  - Run a full photo scan first
  - Try a broader positive prompt
  - Remove or relax the negative prompt

- **Alias quality is poor**
  - Use clearer sample photos
  - Include frontal and side-angle examples
  - Tune the match threshold in the People screen
  - Reclassify after threshold changes

- **Background jobs paused**
  - Reopen app and use resume actions where available
  - Ensure battery restrictions are not aggressively stopping background work

## Support

If you need help, include:
- your Android version
- what action you were performing
- the exact error message shown in the app
