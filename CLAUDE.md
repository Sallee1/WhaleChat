# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概况

WhaleChat 是一个 Android WebView 壳：把 DeepSeek 开源的 [chat-demo](https://github.com/deepseek-ai/chat-demo) 静态构建（`app/src/main/assets/chat-demo/`）运行在系统 WebView 里，再补上拍照/相册/图片编辑/文件导入导出等原生能力。包名 `com.sallee.whalechat`。用户面向的说明在 `README.md` / `README.zh-CN.md`，这里只记实现要点。

## 常用命令

```bash
# 构建（JDK 21；本机路径 C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot）
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot"
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # 需要 RELEASE_* 属性，否则不签名但仍能编译

# 安装 / 启动
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sallee.whalechat/.MainActivity

# 给 chat-demo 的 index.html 打旧 WebView 兼容补丁（幂等；替换 chat-demo 后要重跑）
python tools/apply-compat-patches.py
```

仓库没有测试框架，验证方式是编译 + 真机/模拟器运行。

### 调试 WebView

```bash
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
# desktop 的 edge://inspect / chrome://inspect，或直接用 CDP
```

pid 每次重启都变；重新 forward 前先 `adb forward --remove-all`。WebView 调试在 `onCreate` 里无条件开启。

## 架构

三层，改动前先确认该改哪层：

1. **原生壳** — `MainActivity.java`
   - `WebViewAssetLoader` 把 assets 挂在 `https://appassets.androidplatform.net/assets/`。必须用真实 HTTPS origin：`file://` / `content://` 下 `localStorage`、`crypto.randomUUID`、`getUserMedia` 和相对 chunk 路径都会坏。
   - 额外挂 `/local/` → `WebViewAssetLoader.InternalStoragePathHandler(cache/imports)`，导入历史时页面据此同源 fetch ZIP。
   - `onShowFileChooser` 被替换成原生底部弹窗（拍照/相册/文件）；`/favicon.ico` 直接返回 204。
   - `onPageFinished` 按顺序注入：`VIEWPORT_FALLBACK`、`BLOB_DOWNLOAD_HOOK`、`IMAGE_TOOLS_HOOK`、`uiPatchLoader()`。
   - `Bridge`（`@JavascriptInterface`，名字 `AndroidBridge`）：`saveFile`、分块图片传输 `beginImageTransfer/appendImageChunk/endImageTransfer`、`appVersion()`、`pickImportZip(id)`、生成期保活 `beginGeneration()/endGeneration()`。
   - **生成期保活** — `GenerationService`（`foregroundServiceType="dataSync"`）：`ui.js` 包装 `window.fetch`，命中发往 `api.deepseek.com` 且 body 含 `"stream":true` 的请求时调 `beginGeneration()`，起前台服务 + 10 分钟 `PARTIAL_WAKE_LOCK`（15 分钟看门狗兜底）；最后一个流结束（含用户点“停止生成”导致 reader cancel）后延迟 3 秒 `endGeneration()`，避免引擎 1~2 秒重试让通知闪烁。服务只活到这份回复结束，平时不驻留。
   - 直连官方 API 没有服务端副本：进程被划掉/强杀时流必断，只能靠引擎已保存的部分内容 + 消息下方的「继续生成」手动续写。
2. **图片编辑** — `ImageEditActivity` + `ImageEditorView`：旋转/裁剪/自由笔刷（默认 `#4d6bfe`）/撤销；`renderResult` 前先把笔画烘进 bitmap。入参 `input_path`/`mode`/`name`，出参 `output_path`。
3. **界面补丁** — `app/src/main/assets/whalechat/ui.js`（约 830 行，所有 DOM 补丁集中在这里）。

### ui.js 的“无头引擎”模式（最重要的设计）

chat-demo 自带的设置对话框没有被替换掉，而是被 CSS 隐藏（`body.wc-open` 下 `display:none`）当作**引擎**：

- 点击 chat-demo 的设置按钮会打开引擎对话框 → `MutationObserver` 触发 `sync()` → 检测到 `[role="dialog"][aria-modal="true"]` 里有 `[role="tablist"]` 就渲染 WhaleChat 整页设置页。
- 新页面的每次点击都转发到引擎里的真实控件（`useTab(i)` 切 tab、`engineCells()` 取 tabpanel 的子元素、读 `.click()` 结果），所以行为与 chat-demo 完全一致，只是换了皮。
- **选择器只按 role / 结构位置 / `aria-label` 匹配，绝不依赖 CSS Modules 哈希类名**——chat-demo 重新构建后类名会变。
- `paint()` 从引擎读状态（主题 radio、语言/音色 trigger 文案、位置、API key、导出按钮 disabled）；引擎语言变了就 `rebuildForLang()` 整页重建。
- 返回键：原生 `onBackPressed` 调 `window.__whaleBack()`，顺序是 关于弹窗 → 我们的 layer → 设置页。

### 已踩过的坑（改这里之前必读）

- **真实触摸 vs 合成点击**：引擎下拉在 **pointerdown** 时判断“点到外面就关”，会立刻卸载 `[role="listbox"]`。缓存下来的 option 节点已脱离文档，点它没反应（表现为“窗口关了但没设置成功”）。`pickEngineOption` 因此先看 `listboxFor(trigger)` 是否还开着、收起重开后再现找 option。用 CDP 验证时必须 `Input.dispatchTouchEvent`，`.click()` 会掩盖这类 bug。
- **API key 已保存态的判定**：保存后那行「输入 API Key 开始使用」标题**仍在**，输入框被换成打码值 + 纯图标按钮（文案在 `aria-label="修改 API Key"`，`innerText` 为空）。所以只能按结构判断：没有 input 但单元格里还有 button = 已保存；`findKeyInput()` 在已保存态先点该按钮切回编辑。校验轮询 60s 超时不能当失败。
- **导出历史**：chat-demo 用一个**未插入文档**的 `<a download href="blob:...">` 触发下载，document 上的 click 捕获收不到；必须拦 `HTMLAnchorElement.prototype.click`，落盘 `Download/WhaleChat/`。
- **导入历史**：隐藏对话框里的 `<input type=file>` 无法弹出系统选择器。走原生 `ACTION_OPEN_DOCUMENT` → 复制到 `cache/imports/` → 页面通过 `/local/` URL `fetch` 成 `File` → `DataTransfer` 塞回引擎的 file input 并派发 `change`。
- **位置信息是两级**：中国区先选省份再选市；选完省份要重新 `openLocationSheet()` 回到子窗口，否则用户得重开两次。
- **ui.js 缓存**：注入 URL 的 `?v=` 取脚本内容 CRC32（`uiPatchLoader()`），改了 `ui.js` 必然生效；不要退回用 `versionCode`。
- **旧 WebView 兼容**：`tools/apply-compat-patches.py` 往 `index.html` 注入 `dvh` CSS 回退 + `Array.prototype.at` / `Object.hasOwn` / `structuredClone` / `toSorted` 等 polyfill；`VIEWPORT_FALLBACK` 是运行时兜底。替换 chat-demo 资源后须重跑脚本。
- **生成流的识别**：chat-demo 的请求函数调用时才查全局 `fetch`，所以注入 wrapper 有效；但请求是 SSE，包装响应体必须 `getReader()` + `new ReadableStream` 原样转发，不能吞掉 body。非聊天请求（`/models`、非 2xx）不要动。判断请求靠 URL 含 `api.deepseek.com` 且 body 是字符串并匹配 `"stream":true`，不要按函数名或模块内部符号来定位。

## 发布

- `versionCode` / `versionName` 在 `app/build.gradle.kts` 的 `defaultConfig` 里；每次发版都要改。
- 签名按优先级读取：Gradle 属性 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` → 同名环境变量 → 仓库根的 `keystore.properties`。密钥材料不进仓库。
- 发布走 GitHub Actions：推 `v*` tag 触发 `.github/workflows/release.yml`（或手动 `workflow_dispatch` 填 tag），按 tag checkout、构建签名 APK、验签、生成 release notes 并上传。`tools/release.sh <版本号>` 封装了“本地先构建 → 打 annotated tag → 推送”。
- Release notes 由 `tools/changelog.py` 从上一个 `v*` tag 到当前 tag 的提交生成，**只收录 feat / fix / perf**（build/ci/docs/refactor 等不出现在用户可见说明里），再调 DeepSeek chat API 翻成中文（secret `DEEPSEEK_API_KEY`）。输出是**英文在前、中文在后**（`---` 分隔），末尾是 `Full Changelog` 对比链接；没有 key 或翻译失败时只发英文，不阻塞发布。若仓库存在 `.release-notes-<版本号>.md`，优先使用该手写文件。
- CI secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`、`DEEPSEEK_API_KEY`。

## 改动约定

- **不要改 `app/src/main/assets/chat-demo/` 里的上游产物**（除兼容补丁脚本的注入块外）；界面/交互定制一律放 `ui.js`，原生能力放 `MainActivity` / `ImageEditActivity`。
- 升级 chat-demo：整体替换 `index.html` + `static/` + `LICENSE`，保持目录结构，然后重跑兼容补丁并真机回归上传/导出/设置页。
- 提交信息用英文祈使句，格式为 Conventional Commits：`<type>: <summary>`，正文里每个改动文件一条 `- <path>: <what changed>`（类型用 feat/fix/refactor/perf/docs/build/ci/test/chore/revert）。`tools/changelog.py` 依赖这个格式生成 release notes，别改回自由格式。
