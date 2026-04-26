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

## Getting Started

1. **Install the app**
2. **Open CLIP Finder**
3. **Download models** when prompted
4. **Tap "Scan new photos (background)"** to index your library
5. Go to **Search** and run your first prompt

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
