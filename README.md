# WhaleChat

**English** · [简体中文](README.zh-CN.md)

A minimal Android WebView shell that runs the open-source [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) web UI as a native app — with real camera/gallery photo upload, a built-in image editor, voice input and file export.

> Unofficial project. Not affiliated with, endorsed by, or supported by DeepSeek. You supply your own API key; it is stored in the app's private `localStorage` and only ever sent to the DeepSeek API.

## Why a WebView shell

Opening the static build from `file://` or `content://` breaks it: relative chunk paths, `crypto.randomUUID`, `localStorage` and `getUserMedia` all need a proper secure origin. So the assets are served through `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/chat-demo/index.html`, which is a real HTTPS origin with working storage and microphone.

## Features

- **Chat UI** — the unmodified chat-demo static build, bundled in `app/src/main/assets/chat-demo/`.
- **Photo upload**
  - Take a photo with the system camera (no `CAMERA` permission needed — the camera app writes into our `FileProvider` URI).
  - Pick from the gallery through the **Android system photo picker** (`MediaStore.ACTION_PICK_IMAGES` + `EXTRA_PICK_IMAGES_MAX`), which supports multi-select and needs no storage permission. On Android 11/12 the module is backfilled by Play services (see the `ModuleDependencies` service in the manifest); if the picker is unavailable the app falls back to `ACTION_PICK` on the gallery, then to `ACTION_GET_CONTENT`.
  - Any other file through the system file picker.
- **Image editor** (`ImageEditActivity`)
  - Rotate 90°, rectangular crop, freehand brush with a colour palette (DeepSeek blue `#4D6BFE` by default), undo.
  - Crop: drag to draw the box, then drag any of the four corner handles to fine-tune — dragging elsewhere never redraws the box.
  - Tap an attachment thumbnail to edit it; tap an image inside a sent message for a read-only full-screen view.
  - The edited image is pushed back into the WebView as a new attachment (blob → base64 → `File` → `change` event) so the page re-uploads it.
- **Voice input** — `getUserMedia` + runtime `RECORD_AUDIO` permission.
- **Export** — chat-demo creates a `Blob` and clicks `<a download>`; the app intercepts that and writes the file to `Download/WhaleChat/` via MediaStore.
- **Links** — external `http/https/mailto/tel` links open in the system browser, in-site navigation stays in the WebView. The back key walks the web history first.

## Requirements

| | |
|---|---|
| JDK | 17 or newer (**21 recommended**) |
| Gradle | 8.10 (wrapper included) |
| AGP | 8.7.3 — needs Android Studio Ladybug (2024.2.1) or newer |
| Android SDK | compileSdk/targetSdk 34, minSdk 26 |

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

### Release build

Signing values are read from Gradle properties (or same-named environment variables), so the
keystore never enters the repository:

```properties
# ~/.gradle/gradle.properties
RELEASE_STORE_FILE=/absolute/path/to/whalechat.jks
RELEASE_STORE_PASSWORD=******
RELEASE_KEY_ALIAS=whalechat
RELEASE_KEY_PASSWORD=******
```

(A `keystore.properties` file in the repo root with `storeFile` / `storePassword` / `keyAlias` /
`keyPassword` is accepted as a fallback.)

Create the keystore once:

```bash
keytool -genkeypair -v -keystore whalechat.jks -alias whalechat \
        -keyalg RSA -keysize 2048 -validity 10000
```

Then:

```bash
./gradlew assembleRelease    # APK -> app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease      # AAB
```

Without any of those properties the release build is unsigned and still compiles.

## Install & debug

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sallee.whalechat/.MainActivity

# WebView debugging: open edge://inspect or chrome://inspect on the desktop
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
```

The `pid` changes every time the app restarts — run `adb forward --remove-all` before forwarding again.

## How it works

- `MainActivity` — hosts the `WebView`, wires `WebViewAssetLoader`, the file chooser, permissions and the JS bridge.
- `onShowFileChooser` → a bottom sheet with *Camera / Gallery / File*; the chosen URIs are handed back through `FilePathCallback`. Camera results go through the editor first; gallery and file picks go straight into the attachment list.
- `AndroidBridge` (`@JavascriptInterface`) — `saveFile`, and a chunked `beginImageTransfer / appendImageChunk / endImageTransfer` protocol used to move a `blob:` attachment into the editor without base64-ing the whole image in one JS call.
- Injected on `onPageFinished`:
  - `VIEWPORT_FALLBACK` — maps `dvh` to `100vh` on older WebViews;
  - `BLOB_DOWNLOAD_HOOK` — intercepts `a[download]` with `blob:` hrefs;
  - `IMAGE_TOOLS_HOOK` — click interception on attachment thumbnails and sent-message images; `window.__androidImageEdited(id, dataUrl)` writes the edited image back into the file input and fires `change`.
- `ImageEditorView` — bitmap + stroke list; rotate/crop bake the strokes into the bitmap first. `ImageEditActivity` samples the input (max edge 2048), applies EXIF orientation, and writes JPEG quality 88 to the cache.
- `app/src/main/assets/whalechat/ui.js` — UI patch: rebrands the title/brand to WhaleChat and lays an official-app-style full-page settings screen over chat-demo's own settings dialog. The original dialog is hidden by CSS and used as a headless engine — every tap on the new page is forwarded to the real engine control (read state, click button), so behaviour stays identical to chat-demo, only the skin changes. Copy follows the engine language (zh/en). Adds the `appVersion()` bridge for the About sheet and `window.__whaleBack()` so the hardware back key closes sheets/settings first.

## Updating the bundled web app

Replace `app/src/main/assets/chat-demo/` wholesale with the new `index.html` + `static/` + `LICENSE` from a chat-demo release, then rebuild. Keep the directory structure — `index.html` uses relative paths.

## Known limitations

- No PWA/Service Worker; opening the page offline works, chatting does not.
- Notification API is a no-op inside the WebView (chat-demo's "notify when the answer is ready" does not fire).
- Status bar chrome is fixed to the light theme.
- You need your own DeepSeek API key.

## Third-party

- `app/src/main/assets/chat-demo/` — [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo), MIT, © 2026 DeepSeek. Its license is kept at `app/src/main/assets/chat-demo/LICENSE`.
- [androidx.webkit](https://developer.android.com/jetpack/androidx/releases/webkit) — Apache-2.0.

## License

[MIT](LICENSE) © 2026 Sallee1
