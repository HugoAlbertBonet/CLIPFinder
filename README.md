# CLIP Finder (Android)

On-device CLIP (ViT-B/32) image search for your gallery using ONNX Runtime Mobile.

## ONNX models and tokenizer

- **Tokenizer (bundled at build time)**: `app/src/main/assets/clip/bpe_simple_vocab_16e6.txt.gz`  
  Downloaded automatically by the Gradle `downloadClipBpe` task (runs before `preBuild`).

- **Vision / text ONNX (not bundled by default)**:
  - `vision_model_quantized.onnx`
  - `text_model_quantized.onnx`

The app downloads these from Hugging Face on first use (see `ClipModelStore`), or you can bundle them under `app/src/main/assets/models/` using:

```bash
./gradlew :app:downloadClipOnnxModels
```

Bundled assets are copied into app-internal storage on startup when present.

## Build

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
