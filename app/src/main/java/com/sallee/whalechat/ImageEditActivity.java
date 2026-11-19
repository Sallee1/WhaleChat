package com.sallee.whalechat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 全屏图片查看 / 编辑页。
 *
 * 入参：
 *  - EXTRA_INPUT_PATH：待处理的图片文件路径；
 *  - EXTRA_MODE："view" 只读查看，其它值进入编辑；
 *  - EXTRA_NAME：原始文件名（回传给 JS 时保持名字一致）。
 *
 * 出参（编辑并点击“完成”时）：EXTRA_OUTPUT_PATH 指向缓存目录里的 JPEG 结果。
 */
public class ImageEditActivity extends Activity {

    public static final String EXTRA_INPUT_PATH = "input_path";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_OUTPUT_PATH = "output_path";

    private static final int MAX_EDGE = 2048;
    private static final int[] PALETTE = {
            0xFF4D6BFE, // DeepSeek 蓝，默认画笔色
            0xFFFF5252,
            0xFFFFB300,
            0xFF00C853,
            0xFF12B5CB,
            0xFF111111,
    };

    private ImageEditorView editorView;
    private View loadingView;
    private LinearLayout toolsRow;
    private LinearLayout cropRow;
    private LinearLayout paletteRow;
    private TextView hintView;
    private Button brushBtn;
    private Button doneBtn;

    private boolean viewMode;
    private boolean brushActive;
    private boolean saving;
    private int selectedColor = PALETTE[0];
    private final java.util.List<ColorDot> colorDots = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewMode = "view".equals(getIntent().getStringExtra(EXTRA_MODE));
        buildUi();
        loadAsync(getIntent().getStringExtra(EXTRA_INPUT_PATH));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundBackground(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Button baseButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(0xFFE7EAF3);
        button.setBackground(roundBackground(0xFF1B2130, 10));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        button.setStateListAnimator(null);
        return button;
    }

    private Button toolButton(int textRes) {
        Button button = baseButton(getString(textRes));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button accentButton(String text) {
        Button button = baseButton(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundBackground(0xFF4D6BFE, 10));
        button.setPadding(dp(20), dp(9), dp(20), dp(9));
        return button;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);

        editorView = new ImageEditorView(this);
        root.addView(editorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loadingView = new TextView(this);
        ((TextView) loadingView).setText(R.string.editor_loading);
        ((TextView) loadingView).setTextColor(0xFFB4BAC8);
        ((TextView) loadingView).setTextSize(14f);
        root.addView(loadingView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // 顶栏：取消 / 完成
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(10), dp(10), dp(6));
        Button closeBtn = baseButton(getString(viewMode ? R.string.editor_close : R.string.editor_cancel));
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        doneBtn = accentButton(getString(R.string.editor_done));
        topBar.addView(doneBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 底栏：提示 + 色板 + 工具
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(10), dp(4), dp(10), dp(28));

        hintView = new TextView(this);
        hintView.setText(R.string.editor_crop_hint);
        hintView.setTextColor(0xFFCFD5E3);
        hintView.setTextSize(12f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.bottomMargin = dp(8);
        bottomBar.addView(hintView, hintParams);

        paletteRow = new LinearLayout(this);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setGravity(Gravity.CENTER);
        paletteRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams paletteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paletteParams.bottomMargin = dp(8);
        bottomBar.addView(paletteRow, paletteParams);
        buildPalette();

        toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button rotateBtn = toolButton(R.string.editor_rotate);
        Button cropBtn = toolButton(R.string.editor_crop);
        brushBtn = toolButton(R.string.editor_brush);
        Button undoBtn = toolButton(R.string.editor_undo);
        toolsRow.addView(rotateBtn);
        toolsRow.addView(cropBtn);
        toolsRow.addView(brushBtn);
        toolsRow.addView(undoBtn);
        bottomBar.addView(toolsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cropRow = new LinearLayout(this);
        cropRow.setOrientation(LinearLayout.HORIZONTAL);
        cropRow.setVisibility(View.GONE);
        Button cropCancelBtn = toolButton(R.string.editor_crop_cancel);
        Button cropApplyBtn = toolButton(R.string.editor_apply);
        cropApplyBtn.setTextColor(Color.WHITE);
        cropApplyBtn.setBackground(roundBackground(0xFF4D6BFE, 10));
        cropRow.addView(cropCancelBtn);
        cropRow.addView(cropApplyBtn);
        bottomBar.addView(cropRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        if (viewMode) {
            toolsRow.setVisibility(View.GONE);
            cropRow.setVisibility(View.GONE);
            paletteRow.setVisibility(View.GONE);
        }

        closeBtn.setOnClickListener(v -> finish());
        doneBtn.setOnClickListener(v -> {
            if (viewMode) {
                finish();
            } else {
                saveAndFinish();
            }
        });
        rotateBtn.setOnClickListener(v -> {
            if (!viewMode) {
                editorView.rotate();
            }
        });
        cropBtn.setOnClickListener(v -> {
            if (viewMode) {
                return;
            }
            if (editorView.getMode() == ImageEditorView.MODE_CROP) {
                exitCropMode();
            } else {
                enterCropMode();
            }
        });
        brushBtn.setOnClickListener(v -> {
            if (!viewMode) {
                toggleBrush();
            }
        });
        undoBtn.setOnClickListener(v -> {
            if (!editorView.undo()) {
                Toast.makeText(this, R.string.editor_nothing_to_undo, Toast.LENGTH_SHORT).show();
            }
        });
        cropCancelBtn.setOnClickListener(v -> exitCropMode());
        cropApplyBtn.setOnClickListener(v -> {
            if (editorView.applyCrop()) {
                exitCropMode();
            } else {
                Toast.makeText(this, R.string.editor_crop_empty, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void buildPalette() {
        for (int i = 0; i < PALETTE.length; i++) {
            int color = PALETTE[i];
            ColorDot dot = new ColorDot(color);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
            params.setMargins(dp(7), 0, dp(7), 0);
            dot.setLayoutParams(params);
            dot.setSelectedColor(i == 0);
            final int index = i;
            dot.setOnClickListener(v -> {
                selectedColor = color;
                editorView.setBrushColor(color);
                for (int k = 0; k < colorDots.size(); k++) {
                    colorDots.get(k).setSelectedColor(k == index);
                }
            });
            colorDots.add(dot);
            paletteRow.addView(dot);
        }
        selectedColor = PALETTE[0];
        editorView.setBrushColor(selectedColor);
    }

    private void enterCropMode() {
        if (!editorView.hasImage()) {
            return;
        }
        editorView.setMode(ImageEditorView.MODE_CROP);
        brushActive = false;
        refreshBrushButton();
        toolsRow.setVisibility(View.GONE);
        cropRow.setVisibility(View.VISIBLE);
        paletteRow.setVisibility(View.GONE);
        hintView.setVisibility(View.VISIBLE);
    }

    private void exitCropMode() {
        editorView.setMode(ImageEditorView.MODE_NONE);
        toolsRow.setVisibility(View.VISIBLE);
        cropRow.setVisibility(View.GONE);
        hintView.setVisibility(View.GONE);
        paletteRow.setVisibility(brushActive ? View.VISIBLE : View.GONE);
    }

    private void toggleBrush() {
        brushActive = !brushActive;
        editorView.setMode(brushActive ? ImageEditorView.MODE_DRAW : ImageEditorView.MODE_NONE);
        paletteRow.setVisibility(brushActive ? View.VISIBLE : View.GONE);
        refreshBrushButton();
    }

    private void refreshBrushButton() {
        if (brushActive) {
            brushBtn.setTextColor(Color.WHITE);
            brushBtn.setBackground(roundBackground(0xFF3A4FA8, 10));
        } else {
            brushBtn.setTextColor(0xFFE7EAF3);
            brushBtn.setBackground(roundBackground(0xFF1B2130, 10));
        }
    }

    private void loadAsync(final String path) {
        if (path == null) {
            Toast.makeText(this, R.string.editor_load_failed, Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = loadBitmap(path);
            } catch (Throwable ignored) {
                // 解码失败按 null 处理
            }
            File input = new File(path);
            if (input.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
                // 原始输入只用于解码，落盘后即可清理
                //noinspection ResultOfMethodCallIgnored
                input.delete();
            }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                loadingView.setVisibility(View.GONE);
                if (result == null || result.isRecycled()) {
                    Toast.makeText(this, R.string.editor_load_failed, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }
                editorView.setBitmap(result);
                if (viewMode) {
                    editorView.setMode(ImageEditorView.MODE_NONE);
                }
            });
        }, "image-load").start();
    }

    private Bitmap loadBitmap(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (sample * 2) >= MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longestSide = Math.max(width, height);
        if (longestSide > MAX_EDGE) {
            float scale = MAX_EDGE / (float) longestSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), true);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            bitmap = scaled;
        }
        return applyExifOrientation(path, bitmap);
    }

    @SuppressWarnings("deprecation")
    private Bitmap applyExifOrientation(String path, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.postScale(1, -1);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.postRotate(90);
                    matrix.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.postRotate(270);
                    matrix.postScale(-1, 1);
                    break;
                default:
                    return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap,
                    0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (Throwable ignored) {
            return bitmap;
        }
    }

    private void saveAndFinish() {
        if (saving || !editorView.hasImage()) {
            return;
        }
        saving = true;
        doneBtn.setEnabled(false);
        final File dir = new File(getCacheDir(), "images");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String rawName = getIntent().getStringExtra(EXTRA_NAME);
        if (rawName == null || rawName.trim().isEmpty()) {
            rawName = "image";
        }
        String baseName = rawName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        if (baseName.isEmpty()) {
            baseName = "image";
        }
        final File output = new File(dir, baseName + "_" + System.currentTimeMillis() + ".jpg");
        new Thread(() -> {
            boolean ok = false;
            try {
                Bitmap result = editorView.renderResult(MAX_EDGE);
                if (result != null) {
                    try (FileOutputStream fos = new FileOutputStream(output)) {
                        ok = result.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                    }
                }
            } catch (Throwable ignored) {
                // 失败在 UI 线程提示
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (!success) {
                    saving = false;
                    doneBtn.setEnabled(true);
                    Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_OUTPUT_PATH, output.getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            });
        }, "image-save").start();
    }

    /** 色板上的圆形色块。 */
    private final class ColorDot extends View {
        private final int color;
        private boolean selected;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ColorDot(int color) {
            super(ImageEditActivity.this);
            this.color = color;
        }

        void setSelectedColor(boolean value) {
            if (selected != value) {
                selected = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f - dp(3);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(selected ? 2.4f : 1f));
            paint.setColor(selected ? 0xFFFFFFFF : 0x33FFFFFF);
            canvas.drawCircle(cx, cy, radius, paint);
        }
    }
}
