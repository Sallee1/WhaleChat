# WhaleChat

[English](README.md) · **简体中文**

把开源项目 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的静态产物包进一个最小 Android WebView App，并补齐了网页本身做不到的部分：调用系统相机拍照、走系统照片选择器选图、内置图片编辑器、语音输入、文件导出。

> 非官方项目，与 DeepSeek 官方无关，也未获得其认可或支持。API key 由使用者自行填写，只存在 App 私有 `localStorage` 里，也只发给 DeepSeek API。

## 为什么要套一层 WebView

直接用 `file://` 或 `content://` 打开静态包会坏：相对路径的异步 chunk、`crypto.randomUUID`、`localStorage`、`getUserMedia` 都要求真正的安全上下文。所以静态资源通过 `WebViewAssetLoader` 以 `https://appassets.androidplatform.net/assets/chat-demo/index.html` 加载——这是一个真正的 HTTPS origin，存储和麦克风都按标准行为工作。

## 功能

- **聊天界面** —— chat-demo 原样打包在 `app/src/main/assets/chat-demo/`。
- **图片上传**
  - 拍照：调系统相机，不需要 `CAMERA` 权限（相机 App 直接写入我们给的 `FileProvider` URI）。
  - 相册：走 **Android 系统照片选择器**（`MediaStore.ACTION_PICK_IMAGES` + `EXTRA_PICK_IMAGES_MAX`），支持多选、不需要存储权限。Android 11/12 由 Play 服务补装该模块（见 manifest 里的 `ModuleDependencies` service）；系统没有选择器时依次回退到相册 `ACTION_PICK`、再回退到 `ACTION_GET_CONTENT`。
  - 其他文件走系统文件选择器。
- **图片编辑器**（`ImageEditActivity`）
  - 90° 旋转、矩形裁剪、自由笔刷（调色板默认 DeepSeek 蓝 `#4D6BFE`）、撤销。
  - 裁剪：先拖出选框，之后拖四角的控制柄微调——拖别处不会重新画框。
  - 点附件缩略图进入编辑；点已发送消息里的图片进入只读大图。
  - 编辑结果以新附件的形式回填 WebView（blob → base64 → `File` → `change` 事件），由页面重新上传。
- **语音输入** —— `getUserMedia` + 运行时申请 `RECORD_AUDIO`。
- **导出** —— chat-demo 用 `Blob` + `<a download>`，App 拦截后经 MediaStore 写到 `Download/WhaleChat/`。
- **链接** —— 外部 `http/https/mailto/tel` 交给系统浏览器，站内导航留在 WebView；返回键先回退页面历史。

## 环境要求

| | |
|---|---|
| JDK | 17 及以上（**推荐 21**）|
| Gradle | 8.10（wrapper 已带）|
| AGP | 8.7.3 —— 需要 Android Studio Ladybug（2024.2.1）或更新 |
| Android SDK | compileSdk / targetSdk 34，minSdk 26 |

## 构建

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 正式包签名

签名信息从仓库根目录的 `keystore.properties` 读取，该文件**不进版本库**：

```properties
storeFile=../keystore/whalechat.jks
storePassword=******
keyAlias=whalechat
keyPassword=******
```

keystore 生成一次即可：

```bash
keytool -genkeypair -v -keystore keystore/whalechat.jks -alias whalechat \
        -keyalg RSA -keysize 2048 -validity 10000
```

之后：

```bash
./gradlew assembleRelease    # APK
./gradlew bundleRelease      # AAB
```

没有 `keystore.properties` 时 release 包不签名，但照样能编译。

## 安装与调试

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sallee.whalechat/.MainActivity

# WebView 调试：桌面 Edge/Chrome 打开 edge://inspect 或 chrome://inspect
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
```

`pid` 每次重启 App 都会变，重新转发前先 `adb forward --remove-all`。

## 实现要点

- `MainActivity` —— 承载 `WebView`，装配 `WebViewAssetLoader`、文件选择、权限与 JS bridge。
- `onShowFileChooser` → 底部「拍照 / 相册 / 文件」三宫格，选中的 URI 经 `FilePathCallback` 交回页面。拍照结果先进编辑器；相册和文件直接进附件列表。
- `AndroidBridge`（`@JavascriptInterface`）—— `saveFile`，以及 `beginImageTransfer / appendImageChunk / endImageTransfer` 分块传输协议：把 `blob:` 附件搬进编辑器时不用一次性在 JS 里 base64 整张图。
- `onPageFinished` 注入脚本：
  - `VIEWPORT_FALLBACK` —— 老 WebView 上把 `dvh` 映射成 `100vh`；
  - `BLOB_DOWNLOAD_HOOK` —— 拦截 `a[download]` 的 `blob:` 地址；
  - `IMAGE_TOOLS_HOOK` —— 拦截附件缩略图与已发送图片的点击；`window.__androidImageEdited(id, dataUrl)` 把编辑结果写回 file input 并触发 `change`。
- `ImageEditorView` —— 位图 + 笔迹列表；旋转/裁剪前先把笔迹烘焙进位图。`ImageEditActivity` 按长边 2048 采样输入、按 EXIF 摆正，输出 JPEG 质量 88 到缓存目录。
- `app/src/main/assets/whalechat/ui.js` —— 界面补丁：把标题/品牌替换成 WhaleChat，并在 chat-demo 自带的设置对话框之上盖一层官方 App 风格的整页设置页。原对话框被 CSS 隐藏后当「无头引擎」用，新页面上每次点击都会转发到引擎的真实控件（读状态、点按钮），所以功能与 chat-demo 完全一致，只是换了一层皮。文案跟随引擎语言（中/英）。新增原生桥 `appVersion()` 供「关于」弹窗显示版本，`window.__whaleBack()` 供返回键先关弹层/设置页。

## 更新内置网页

用 chat-demo 新版本的 `index.html` + `static/` + `LICENSE` 整体替换 `app/src/main/assets/chat-demo/`，重新构建即可。注意保持目录结构，`index.html` 用的是相对路径。

## 已知边界

- 没有 PWA/Service Worker；断网时页面能打开，但不能聊天。
- WebView 里通知 API 无效（chat-demo 的「回答完成通知」不会触发）。
- 状态栏固定浅色，不跟随深色模式。
- 需要自备 DeepSeek API key。

## 第三方

- `app/src/main/assets/chat-demo/` —— [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)，MIT，© 2026 DeepSeek，许可证见 `app/src/main/assets/chat-demo/LICENSE`。
- [androidx.webkit](https://developer.android.com/jetpack/androidx/releases/webkit) —— Apache-2.0。

## 许可证

[MIT](LICENSE) © 2026 Sallee1
