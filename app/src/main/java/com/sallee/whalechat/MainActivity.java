package com.sallee.whalechat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/chat-demo/index.html";
    /** WhaleChat 自己的界面补丁（标题、官方风格设置页、关于），放在 assets/whalechat/ui.js。 */
    private static final String UI_PATCH_URL = "https://" + ASSET_HOST + "/assets/whalechat/ui.js";

    private static final int REQ_FILE = 1001;
    private static final int REQ_AUDIO = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_ALBUM = 1004;
    private static final int REQ_EDIT_CAMERA = 1005;
    private static final int REQ_EDIT_WEB = 1006;
    private static final int REQ_IMPORT_ZIP = 1007;

    /**
     * chat-demo 导出历史时用 Blob + <a download> 触发下载，WebView 默认不处理 blob:。
     * 这里把 Blob 读成 dataURL 交给原生侧落盘。
     * 注意：页面是 createElement('a') 后直接 a.click()，锚点没插进文档，
     * 事件不会冒泡/捕获到 document，所以既要拦事件，也要拦 HTMLAnchorElement.prototype.click。
     */
    private static final String BLOB_DOWNLOAD_HOOK = """
            (function () {
              if (window.__androidDownloadHook) return;
              window.__androidDownloadHook = true;
              function save(href, name) {
                fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                  var fr = new FileReader();
                  fr.onload = function () {
                    try { window.AndroidBridge.saveFile(name || 'download', String(fr.result)); } catch (err) {}
                  };
                  fr.readAsDataURL(b);
                }).catch(function () {});
              }
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
                if (!a) return;
                var href = a.href || '';
                if (href.indexOf('blob:') !== 0) return;
                e.preventDefault();
                save(href, a.getAttribute('download'));
              }, true);
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                var href = this.href || '';
                if (href.indexOf('blob:') === 0) {
                  save(href, this.getAttribute('download'));
                  return;
                }
                return origClick.apply(this, arguments);
              };
            })();
            """;

    /**
     * 老版本 Android System WebView（如 Chromium 87）不支持 dvh 单位，
     * 而 chat-demo 的手机布局是 height: var(--app-viewport-height, 100dvh)，
     * 变量未设置时整条 height 声明失效 → 容器高度塌陷 → 白屏。
     * index.html 里已内联同样的兜底；这里再注入一次，保证升级 chat-demo 资源后仍然生效。
     */
    private static final String VIEWPORT_FALLBACK = """
            (function () {
              try {
                if (window.CSS && CSS.supports && CSS.supports('height', '100dvh')) return;
                if (document.getElementById('__viewportFallback')) return;
                var s = document.createElement('style');
                s.id = '__viewportFallback';
                s.textContent = ':root{--app-viewport-height:100vh}';
                (document.head || document.documentElement).appendChild(s);
              } catch (e) {}
            })();
            """;

    /**
     * 图片工具钩子：
     *  - 输入框附件条里的缩略图（<div><img src="blob:..."><button aria-label="删除">）点击 → 大图编辑；
     *     编辑结果回传后，先点掉旧附件，再把编辑后的 File 塞回页面的 file input，
     *     让 chat-demo 重新上传并生成新的附件记录。
     *  - 消息里已发送的图片（<a href="blob:...">）点击 → 大图查看（只读）。
     */
    private static final String IMAGE_TOOLS_HOOK = """
            (function () {
              if (window.__androidImageTools) return;
              window.__androidImageTools = true;
              var pending = {};

              function chunkSend(id, bytes) {
                var CHUNK = 48 * 1024;
                var i = 0;
                function step() {
                  if (i >= bytes.length) { AndroidBridge.endImageTransfer(id); return; }
                  var end = Math.min(bytes.length, i + CHUNK);
                  var s = '';
                  for (var k = i; k < end; k++) s += String.fromCharCode(bytes[k]);
                  AndroidBridge.appendImageChunk(id, btoa(s));
                  i = end;
                  setTimeout(step, 0);
                }
                step();
              }

              function transfer(img, mode) {
                var src = img.getAttribute('src') || '';
                if (src.indexOf('blob:') !== 0) return;
                var name = img.getAttribute('alt') || ('image_' + Date.now() + '.jpg');
                fetch(src).then(function (r) { return r.blob(); }).then(function (blob) {
                  var id = AndroidBridge.beginImageTransfer(mode, name, blob.type || 'image/jpeg');
                  if (id < 0) return;
                  pending[id] = { img: img, box: img.parentElement, mode: mode, name: name };
                  var fr = new FileReader();
                  fr.onload = function () { chunkSend(id, new Uint8Array(fr.result)); };
                  fr.readAsArrayBuffer(blob);
                }).catch(function () {});
              }

              function deleteButtonIn(box) {
                if (!box) return null;
                var buttons = box.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                  var label = (buttons[i].getAttribute('aria-label') || '').trim();
                  if (label === '删除' || label === 'Delete') return buttons[i];
                }
                return null;
              }

              document.addEventListener('click', function (e) {
                var t = e.target;
                if (!t || t.tagName !== 'IMG') return;
                var src = t.getAttribute('src') || '';
                if (src.indexOf('blob:') !== 0) return;

                var link = t.closest ? t.closest('a') : null;
                if (link && (link.getAttribute('href') || '').indexOf('blob:') === 0) {
                  e.preventDefault();
                  e.stopPropagation();
                  transfer(t, 'view');
                  return;
                }

                var box = t.parentElement;
                if (box && deleteButtonIn(box)) {
                  e.preventDefault();
                  e.stopPropagation();
                  transfer(t, 'edit');
                }
              }, true);

              window.__androidImageEdited = function (id, dataUrl) {
                var p = pending[id];
                delete pending[id];
                if (!p || p.mode !== 'edit') return;
                try {
                  var oldButton = deleteButtonIn(p.box);
                  if (oldButton) oldButton.click();
                  var comma = dataUrl.indexOf(',');
                  var bin = atob(dataUrl.slice(comma + 1));
                  var bytes = new Uint8Array(bin.length);
                  for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                  var name = (p.name || 'image.jpg').replace(/\\.[A-Za-z0-9]+$/, '');
                  var file = new File([bytes], name + '.jpg', { type: 'image/jpeg' });
                  var input = document.querySelector('input[type="file"][multiple]');
                  if (!input) return;
                  var dt = new DataTransfer();
                  dt.items.add(file);
                  input.files = dt.files;
                  input.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (err) {}
              };

              window.__androidImageEditCancelled = function (id) {
                delete pending[id];
              };
            })();
            """;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingAudioRequest;
    private File pendingCameraFile;
    private int pendingWebEditId = -1;
    /** 页面发起的「导入历史对话」请求 id，选择器回来后用它回填。 */
    private int pendingImportId = -1;

    private int transferSeq = 0;
    private final Map<Integer, ImageTransfer> transfers = new LinkedHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                // 导入历史对话时，原生把用户选中的 ZIP 复制到 cache/imports/，
                // 页面再通过 https://<host>/local/... 以同源方式取回，避免几百 MB 走 base64。
                .addPathHandler("/local/", new WebViewAssetLoader.InternalStoragePathHandler(
                        this, importCacheDir()))
                .build();

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(0xFFFFFFFF);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Bridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if ("/favicon.ico".equals(request.getUrl().getPath())) {
                    return new WebResourceResponse("image/x-icon", null, 204, "No Content",
                            java.util.Collections.emptyMap(),
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (ASSET_HOST.equals(uri.getHost())) {
                    return false;
                }
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)
                        || "mailto".equals(scheme) || "tel".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    return true;
                }
                // blob: 点击由页面内注入的 IMAGE_TOOLS_HOOK 处理，这里阻止 WebView 直接导航过去
                if ("blob".equals(scheme)) {
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(VIEWPORT_FALLBACK, null);
                view.evaluateJavascript(BLOB_DOWNLOAD_HOOK, null);
                view.evaluateJavascript(IMAGE_TOOLS_HOOK, null);
                view.evaluateJavascript(uiPatchLoader(), null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                showUploadSourceSheet();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsAudio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        wantsAudio = true;
                    }
                }
                if (!wantsAudio) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingAudioRequest = request;
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
            }
        });

        setContentView(webView);
        webView.loadUrl(START_URL);
    }

    // ------------------------------------------------------------------
    // 上传来源选择：拍照 / 相册 / 文件
    // ------------------------------------------------------------------

    private void showUploadSourceSheet() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_upload_source);
        dialog.setCanceledOnTouchOutside(true);
        final boolean[] launched = new boolean[]{false};

        dialog.findViewById(R.id.btn_camera).setOnClickListener(v -> {
            launched[0] = true;
            dialog.dismiss();
            startCamera();
        });
        dialog.findViewById(R.id.btn_album).setOnClickListener(v -> {
            launched[0] = true;
            dialog.dismiss();
            startAlbum();
        });
        dialog.findViewById(R.id.btn_file).setOnClickListener(v -> {
            launched[0] = true;
            dialog.dismiss();
            startFilePicker();
        });
        dialog.setOnDismissListener(d -> {
            if (!launched[0]) {
                deliverFileChooser(null);
            }
        });

        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
    }

    private File imageCacheDir() {
        File dir = new File(getCacheDir(), "images");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 导入历史对话的临时目录，通过 /local/ 暴露给页面。 */
    private File importCacheDir() {
        File dir = new File(getCacheDir(), "imports");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private void startCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photo = new File(imageCacheDir(), "camera_" + System.currentTimeMillis() + ".jpg");
        try {
            if (!photo.createNewFile()) {
                throw new IOException("无法创建拍照文件");
            }
        } catch (IOException e) {
            Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_SHORT).show();
            deliverFileChooser(null);
            return;
        }
        Uri outputUri;
        try {
            outputUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
        } catch (IllegalArgumentException e) {
            //noinspection ResultOfMethodCallIgnored
            photo.delete();
            deliverFileChooser(null);
            return;
        }
        intent.putExtra(MediaStore.EXTRA_OUTPUT, outputUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingCameraFile = photo;
        try {
            startActivityForResult(intent, REQ_CAMERA);
        } catch (ActivityNotFoundException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            photo.delete();
            pendingCameraFile = null;
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show();
            deliverFileChooser(null);
        }
    }

    private void startAlbum() {
        // 1. 系统照片选择器：Android 13+ 内置，Android 11/12 由 Play 服务补装的模块提供。
        //    多选上限用 EXTRA_PICK_IMAGES_MAX（EXTRA_ALLOW_MULTIPLE 只对 GET_CONTENT 有效）。
        Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
        picker.setType("image/*");
        picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, pickImagesMax());
        try {
            startActivityForResult(picker, REQ_ALBUM);
            return;
        } catch (ActivityNotFoundException ignored) {
        }

        // 2. 直接打开系统相册。这里不能加 EXTRA_ALLOW_MULTIPLE：系统会把不支持多选的
        //    相册从候选里过滤掉，结果就落到 DocumentsUI 文件管理器上了。
        Intent gallery = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        ComponentName galleryComponent = findGalleryComponent(gallery);
        if (galleryComponent != null) {
            // 指定组件，跳过系统的「选择要使用的应用」弹窗
            gallery.setComponent(galleryComponent);
        }
        try {
            startActivityForResult(gallery, REQ_ALBUM);
            return;
        } catch (ActivityNotFoundException ignored) {
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_photo)), REQ_ALBUM);
        } catch (ActivityNotFoundException e) {
            deliverFileChooser(null);
        }
    }

    /**
     * EXTRA_PICK_IMAGES_MAX 必须是 &gt; 1 且 ≤ getPickImagesMaxLimit()，否则选择器直接返回取消。
     */
    private int pickImagesMax() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                int limit = MediaStore.getPickImagesMaxLimit();
                if (limit > 1) {
                    return limit;
                }
            } catch (Exception ignored) {
            }
        }
        return 10;
    }

    /**
     * 在 ACTION_PICK 的候选里挑出相册，跳过 DocumentsUI 文件管理器。
     */
    private ComponentName findGalleryComponent(Intent probe) {
        java.util.List<ResolveInfo> candidates;
        try {
            candidates = getPackageManager().queryIntentActivities(probe, 0);
        } catch (Exception e) {
            return null;
        }
        ComponentName fallback = null;
        for (ResolveInfo info : candidates) {
            if (info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals("android") || pkg.contains("documentsui")) {
                continue;
            }
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            if (pkg.toLowerCase().contains("gallery")) {
                return component;
            }
            if (fallback == null) {
                fallback = component;
            }
        }
        return fallback;
    }

    private void startFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.choose_file)), REQ_FILE);
        } catch (ActivityNotFoundException e) {
            deliverFileChooser(null);
        }
    }

    private void deliverFileChooser(Uri[] uris) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(uris);
            filePathCallback = null;
        }
    }

    private static Uri[] urisFromResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return null;
        }
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            Uri[] results = new Uri[count];
            for (int i = 0; i < count; i++) {
                results[i] = data.getClipData().getItemAt(i).getUri();
            }
            return results;
        }
        if (data.getData() != null) {
            return new Uri[]{data.getData()};
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 相机 / 相册 / 文件 结果
    // ------------------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IMPORT_ZIP) {
            deliverImport(resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }

        if (requestCode == REQ_FILE || requestCode == REQ_ALBUM) {
            deliverFileChooser(urisFromResult(resultCode, data));
            return;
        }

        if (requestCode == REQ_CAMERA) {
            File photo = pendingCameraFile;
            if (resultCode == RESULT_OK && photo != null && photo.length() > 0) {
                openCameraEditor(photo);
                return;
            }
            // 部分相机 App 不写 EXTRA_OUTPUT，只回传缩略图
            if (resultCode == RESULT_OK && data != null && data.getParcelableExtra("data") instanceof Bitmap) {
                Bitmap thumbnail = data.getParcelableExtra("data");
                File fallback = new File(imageCacheDir(), "camera_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(fallback)) {
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                } catch (IOException ignored) {
                }
                if (photo != null) {
                    //noinspection ResultOfMethodCallIgnored
                    photo.delete();
                }
                pendingCameraFile = fallback;
                openCameraEditor(fallback);
                return;
            }
            if (photo != null) {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
            }
            pendingCameraFile = null;
            deliverFileChooser(null);
            return;
        }

        if (requestCode == REQ_EDIT_CAMERA) {
            if (pendingCameraFile != null) {
                //noinspection ResultOfMethodCallIgnored
                pendingCameraFile.delete();
                pendingCameraFile = null;
            }
            String outputPath = resultCode == RESULT_OK && data != null
                    ? data.getStringExtra(ImageEditActivity.EXTRA_OUTPUT_PATH) : null;
            if (outputPath != null) {
                try {
                    File output = new File(outputPath);
                    Uri uri = FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", output);
                    deliverFileChooser(new Uri[]{uri});
                    return;
                } catch (IllegalArgumentException ignored) {
                }
            }
            deliverFileChooser(null);
            return;
        }

        if (requestCode == REQ_EDIT_WEB) {
            int id = pendingWebEditId;
            pendingWebEditId = -1;
            String outputPath = resultCode == RESULT_OK && data != null
                    ? data.getStringExtra(ImageEditActivity.EXTRA_OUTPUT_PATH) : null;
            if (id < 0) {
                return;
            }
            if (outputPath == null) {
                evaluateJavascript("window.__androidImageEditCancelled(" + id + ")");
                return;
            }
            File output = new File(outputPath);
            byte[] bytes = null;
            try {
                bytes = Files.readAllBytes(output.toPath());
            } catch (IOException ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            if (bytes == null || bytes.length == 0) {
                evaluateJavascript("window.__androidImageEditCancelled(" + id + ")");
                return;
            }
            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            evaluateJavascript("window.__androidImageEdited(" + id + "," + JSONObject.quote(dataUrl) + ")");
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    // ------------------------------------------------------------------
    // 导入历史对话：直接调系统文件选择器（DocumentsUI），选中后复制进 cache，
    // 再把同源 URL 交给页面，页面 fetch 成 File 喂给 chat-demo 的隐藏 input。
    // ------------------------------------------------------------------

    private void startImportPicker(int id) {
        pendingImportId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_IMPORT_ZIP);
        } catch (ActivityNotFoundException e) {
            deliverImport(null);
        }
    }

    private void deliverImport(Uri uri) {
        int id = pendingImportId;
        pendingImportId = -1;

        File dir = importCacheDir();
        File[] stale = dir.listFiles();
        if (stale != null) {
            for (File f : stale) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        String url = null;
        if (uri != null) {
            File out = new File(dir, "import_" + System.currentTimeMillis() + ".zip");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in != null) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.flush();
                    url = "https://" + ASSET_HOST + "/local/" + out.getName();
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                url = null;
            }
        }

        evaluateJavascript(url != null
                ? "window.__whaleImportReady(" + id + "," + JSONObject.quote(url) + ");"
                : "window.__whaleImportCancelled(" + id + ");");
    }

    private void openCameraEditor(File photo) {
        Intent intent = new Intent(this, ImageEditActivity.class);
        intent.putExtra(ImageEditActivity.EXTRA_INPUT_PATH, photo.getAbsolutePath());
        intent.putExtra(ImageEditActivity.EXTRA_MODE, "edit");
        intent.putExtra(ImageEditActivity.EXTRA_NAME, "photo.jpg");
        try {
            startActivityForResult(intent, REQ_EDIT_CAMERA);
        } catch (ActivityNotFoundException e) {
            //noinspection ResultOfMethodCallIgnored
            photo.delete();
            pendingCameraFile = null;
            deliverFileChooser(null);
        }
    }

    private void evaluateJavascript(String script) {
        if (webView != null) {
            webView.evaluateJavascript(script, null);
        }
    }

    /** 注入 WhaleChat 界面补丁脚本（带版本号，避免升级后命中 WebView 缓存）。 */
    private String uiPatchLoader() {
        long version = 0;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception ignored) {
        }
        return "(function(){if(document.getElementById('whale-ui'))return;" +
                "var s=document.createElement('script');s.id='whale-ui';s.src='" + UI_PATCH_URL +
                "?v=" + version + "';document.head.appendChild(s);})();";
    }

    // ------------------------------------------------------------------
    // 页面内附件编辑：分块把 blob 传进原生
    // ------------------------------------------------------------------

    private int startImageTransfer(String mode, String name, String mimeType) {
        String safeMode = "view".equals(mode) ? "view" : "edit";
        int id;
        synchronized (transfers) {
            id = ++transferSeq;
        }
        File file = new File(imageCacheDir(), "in_" + id + ".img");
        try {
            FileOutputStream out = new FileOutputStream(file);
            synchronized (transfers) {
                transfers.put(id, new ImageTransfer(id, safeMode, name, mimeType, file, out));
            }
            return id;
        } catch (IOException e) {
            return -1;
        }
    }

    private boolean appendImageTransfer(int id, String base64) {
        ImageTransfer transfer;
        synchronized (transfers) {
            transfer = transfers.get(id);
        }
        if (transfer == null) {
            return false;
        }
        try {
            transfer.out.write(Base64.decode(base64, Base64.NO_WRAP));
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private void finishImageTransfer(int id) {
        ImageTransfer transfer;
        synchronized (transfers) {
            transfer = transfers.remove(id);
        }
        if (transfer == null) {
            return;
        }
        try {
            transfer.out.close();
        } catch (IOException ignored) {
        }
        runOnUiThread(() -> openEditorForTransfer(transfer));
    }

    private void openEditorForTransfer(ImageTransfer transfer) {
        pendingWebEditId = transfer.id;
        Intent intent = new Intent(this, ImageEditActivity.class);
        intent.putExtra(ImageEditActivity.EXTRA_INPUT_PATH, transfer.file.getAbsolutePath());
        intent.putExtra(ImageEditActivity.EXTRA_MODE, transfer.mode);
        intent.putExtra(ImageEditActivity.EXTRA_NAME, transfer.name);
        try {
            startActivityForResult(intent, REQ_EDIT_WEB);
        } catch (ActivityNotFoundException e) {
            evaluateJavascript("window.__androidImageEditCancelled(" + transfer.id + ")");
        }
    }

    private static final class ImageTransfer {
        final int id;
        final String mode;
        final String name;
        @SuppressWarnings("unused")
        final String mimeType;
        final File file;
        final OutputStream out;

        ImageTransfer(int id, String mode, String name, String mimeType, File file, OutputStream out) {
            this.id = id;
            this.mode = mode;
            this.name = name;
            this.mimeType = mimeType;
            this.file = file;
            this.out = out;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_AUDIO && pendingAudioRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingAudioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingAudioRequest.deny();
            }
            pendingAudioRequest = null;
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        // 先让页面自己处理（设置页 / 弹层），没人处理时再走 WebView 历史或退出
        webView.evaluateJavascript(
                "(window.__whaleBack ? window.__whaleBack() : 'none')",
                value -> {
                    if (value != null && value.contains("closed")) {
                        return;
                    }
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        backOut();
                    }
                });
    }

    private void backOut() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public static final class Bridge {

        private final MainActivity activity;

        Bridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void saveFile(String name, String dataUrl) {
            try {
                int comma = dataUrl.indexOf(',');
                String base64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                final String location = activity.writeToDownloads(name, bytes);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.saved_to, location), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        e.toString(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public int beginImageTransfer(String mode, String name, String mimeType) {
            return activity.startImageTransfer(mode, name, mimeType);
        }

        @JavascriptInterface
        public boolean appendImageChunk(int id, String base64) {
            return activity.appendImageTransfer(id, base64);
        }

        @JavascriptInterface
        public void endImageTransfer(int id) {
            activity.finishImageTransfer(id);
        }

        /** 供「关于」弹窗显示：版本名 + 版本号。 */
        @JavascriptInterface
        public String appVersion() {
            try {
                android.content.pm.PackageInfo info = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0);
                return info.versionName + " (" + info.getLongVersionCode() + ")";
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * 导入历史对话：页面传入请求 id，原生打开系统文件选择器（DocumentsUI）。
         * 选完后回调 window.__whaleImportReady(id, url) 或 __whaleImportCancelled(id)。
         */
        @JavascriptInterface
        public void pickImportZip(final int id) {
            activity.runOnUiThread(() -> activity.startImportPicker(id));
        }
    }

    private String writeToDownloads(String name, byte[] data) throws Exception {
        String fileName = (name == null || name.trim().isEmpty()) ? "download" : name.trim();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(fileName));
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/WhaleChat");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("无法创建下载项");
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("无法写入下载项");
                }
                os.write(data);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/WhaleChat/" + fileName;
        }
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "WhaleChat");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建目录 " + dir);
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(data);
        }
        return out.getAbsolutePath();
    }

    private static String mimeTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
