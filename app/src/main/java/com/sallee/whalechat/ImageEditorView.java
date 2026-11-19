package com.sallee.whalechat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片查看 / 编辑视图：支持 90° 旋转、矩形裁剪、自由笔刷标记（默认 DeepSeek 蓝 #4D6BFE）。
 *
 * 坐标约定：
 *  - 图片坐标：以当前 base 位图的像素为单位，笔刷、裁剪框都记录在该坐标系；
 *  - 视图坐标：屏幕像素，通过 display 矩阵（图片 -> 视图）与 inverse 互相换算。
 *
 * 编辑采用“烘焙”模型：旋转 / 裁剪前先把已完成的笔迹画进 base，再变换 base，
 * 这样不需要维护复杂的坐标变换历史；撤销只能撤销尚未烘焙的笔迹。
 */
public class ImageEditorView extends View {

    public static final int MODE_NONE = 0;
    public static final int MODE_DRAW = 1;
    public static final int MODE_CROP = 2;

    private static final int DEFAULT_BRUSH_COLOR = 0xFF4D6BFE;

    private Bitmap base;
    private final List<Stroke> strokes = new ArrayList<>();

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Matrix display = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] point = new float[2];
    private final RectF cropView = new RectF();

    private int mode = MODE_NONE;
    private int brushColor = DEFAULT_BRUSH_COLOR;
    private float brushWidth = 16f;

    private static final int CORNER_NONE = -1;
    private static final int CORNER_LEFT_TOP = 0;
    private static final int CORNER_RIGHT_TOP = 1;
    private static final int CORNER_LEFT_BOTTOM = 2;
    private static final int CORNER_RIGHT_BOTTOM = 3;

    /** 裁剪框最小边长（图片像素），与 {@link #applyCrop()} 的下限一致。 */
    private static final float CROP_MIN_PX = 24f;

    private Stroke activeStroke;
    private RectF cropRect;
    private float cropStartX;
    private float cropStartY;
    /** 正在拖动哪个控制柄；{@link #CORNER_NONE} 表示没有。 */
    private int cropCorner = CORNER_NONE;
    /** 本次手势是否在「还没有裁剪框」时新画一个框。 */
    private boolean cropCreating;

    public ImageEditorView(Context context) {
        super(context);
        setBackgroundColor(0xFF0B0D12);
        dimPaint.setColor(0xB3000000);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(1.5f));
        framePaint.setColor(Color.WHITE);
        setFocusable(true);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setBitmap(Bitmap bitmap) {
        if (base != null && base != bitmap) {
            base.recycle();
        }
        base = bitmap;
        strokes.clear();
        activeStroke = null;
        cropRect = null;
        updateDisplay();
        invalidate();
    }

    public boolean hasImage() {
        return base != null && !base.isRecycled();
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int newMode) {
        if (mode == newMode) {
            return;
        }
        mode = newMode;
        if (mode != MODE_CROP) {
            cropRect = null;
        }
        activeStroke = null;
        invalidate();
    }

    public void setBrushColor(int color) {
        brushColor = color;
    }

    public boolean undo() {
        if (activeStroke == null && !strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
            return true;
        }
        return false;
    }

    public boolean rotate() {
        if (base == null) {
            return false;
        }
        bakeStrokes();
        Matrix matrix = new Matrix();
        matrix.setRotate(90);
        Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), matrix, true);
        base.recycle();
        base = rotated;
        cropRect = null;
        updateDisplay();
        invalidate();
        return true;
    }

    public boolean applyCrop() {
        if (base == null || cropRect == null) {
            return false;
        }
        int left = Math.max(0, Math.round(cropRect.left));
        int top = Math.max(0, Math.round(cropRect.top));
        int right = Math.min(base.getWidth(), Math.round(cropRect.right));
        int bottom = Math.min(base.getHeight(), Math.round(cropRect.bottom));
        if (right - left < 24 || bottom - top < 24) {
            return false;
        }
        bakeStrokes();
        Bitmap cropped = Bitmap.createBitmap(base, left, top, right - left, bottom - top);
        base.recycle();
        base = cropped;
        cropRect = null;
        updateDisplay();
        invalidate();
        return true;
    }

    /** 输出编辑结果：先把笔迹烘焙进 base，超长边则等比缩小。返回的位图可能复用 base。 */
    public Bitmap renderResult(int maxEdge) {
        if (base == null) {
            return null;
        }
        bakeStrokes();
        int width = base.getWidth();
        int height = base.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return base;
        }
        float scale = maxEdge / (float) longest;
        return Bitmap.createScaledBitmap(base,
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private void bakeStrokes() {
        if (base == null || strokes.isEmpty()) {
            return;
        }
        Bitmap baked = base.copy(Bitmap.Config.ARGB_8888, true);
        if (baked == null) {
            return;
        }
        Canvas canvas = new Canvas(baked);
        for (Stroke stroke : strokes) {
            drawStroke(canvas, stroke);
        }
        base.recycle();
        base = baked;
        strokes.clear();
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(stroke.color);
        strokePaint.setStrokeWidth(stroke.width);
        canvas.drawPath(stroke.path, strokePaint);
    }

    private void updateDisplay() {
        if (base == null) {
            return;
        }
        float viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float viewHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float scale = Math.min(viewWidth / base.getWidth(), viewHeight / base.getHeight());
        float dx = getPaddingLeft() + (viewWidth - base.getWidth() * scale) / 2f;
        float dy = getPaddingTop() + (viewHeight - base.getHeight() * scale) / 2f;
        display.reset();
        display.setScale(scale, scale);
        display.postTranslate(dx, dy);
        inverse.set(display);
        inverse.invert(inverse);
        brushWidth = Math.max(6f, Math.min(base.getWidth(), base.getHeight()) * 0.008f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDisplay();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (base == null || base.isRecycled()) {
            return;
        }
        canvas.save();
        canvas.concat(display);
        canvas.drawBitmap(base, 0, 0, bitmapPaint);
        for (Stroke stroke : strokes) {
            drawStroke(canvas, stroke);
        }
        if (activeStroke != null) {
            drawStroke(canvas, activeStroke);
        }
        canvas.restore();

        if (mode == MODE_CROP && cropRect != null) {
            drawCropOverlay(canvas);
        }
    }

    private void drawCropOverlay(Canvas canvas) {
        cropView.set(cropRect);
        display.mapRect(cropView);

        canvas.drawRect(0, 0, getWidth(), cropView.top, dimPaint);
        canvas.drawRect(0, cropView.bottom, getWidth(), getHeight(), dimPaint);
        canvas.drawRect(0, cropView.top, cropView.left, cropView.bottom, dimPaint);
        canvas.drawRect(cropView.right, cropView.top, getWidth(), cropView.bottom, dimPaint);

        canvas.drawRect(cropView, framePaint);

        Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(dp(3));
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
        handlePaint.setColor(Color.WHITE);
        float h = dp(18);
        float l = cropView.left;
        float t = cropView.top;
        float r = cropView.right;
        float b = cropView.bottom;
        canvas.drawLine(l, t, l + h, t, handlePaint);
        canvas.drawLine(l, t, l, t + h, handlePaint);
        canvas.drawLine(r - h, t, r, t, handlePaint);
        canvas.drawLine(r, t, r, t + h, handlePaint);
        canvas.drawLine(l, b - h, l, b, handlePaint);
        canvas.drawLine(l, b, l + h, b, handlePaint);
        canvas.drawLine(r - h, b, r, b, handlePaint);
        canvas.drawLine(r, b - h, r, b, handlePaint);
    }

    /**
     * 判断按下点是否落在某个角的控制柄上（视图坐标）。框很小的时候缩小判定半径，
     * 免得四个角互相抢。
     */
    private int hitCropCorner(float viewX, float viewY) {
        if (cropRect == null) {
            return CORNER_NONE;
        }
        cropView.set(cropRect);
        display.mapRect(cropView);
        float slop = dp(28);
        float smaller = Math.min(cropView.width(), cropView.height()) * 0.4f;
        if (smaller < slop) {
            slop = Math.max(dp(12), smaller);
        }
        if (Math.hypot(viewX - cropView.left, viewY - cropView.top) <= slop) {
            return CORNER_LEFT_TOP;
        }
        if (Math.hypot(viewX - cropView.right, viewY - cropView.top) <= slop) {
            return CORNER_RIGHT_TOP;
        }
        if (Math.hypot(viewX - cropView.left, viewY - cropView.bottom) <= slop) {
            return CORNER_LEFT_BOTTOM;
        }
        if (Math.hypot(viewX - cropView.right, viewY - cropView.bottom) <= slop) {
            return CORNER_RIGHT_BOTTOM;
        }
        return CORNER_NONE;
    }

    /** 只移动被抓住的那个角，对角保持不动，边长不小于 {@link #CROP_MIN_PX}。 */
    private void moveCropCorner(float cx, float cy) {
        float left = cropRect.left;
        float top = cropRect.top;
        float right = cropRect.right;
        float bottom = cropRect.bottom;
        switch (cropCorner) {
            case CORNER_LEFT_TOP:
                left = clamp(cx, 0, right - CROP_MIN_PX);
                top = clamp(cy, 0, bottom - CROP_MIN_PX);
                break;
            case CORNER_RIGHT_TOP:
                right = clamp(cx, left + CROP_MIN_PX, base.getWidth());
                top = clamp(cy, 0, bottom - CROP_MIN_PX);
                break;
            case CORNER_LEFT_BOTTOM:
                left = clamp(cx, 0, right - CROP_MIN_PX);
                bottom = clamp(cy, top + CROP_MIN_PX, base.getHeight());
                break;
            case CORNER_RIGHT_BOTTOM:
                right = clamp(cx, left + CROP_MIN_PX, base.getWidth());
                bottom = clamp(cy, top + CROP_MIN_PX, base.getHeight());
                break;
            default:
                return;
        }
        cropRect.set(left, top, right, bottom);
    }

    private void mapToImage(float x, float y) {
        point[0] = x;
        point[1] = y;
        inverse.mapPoints(point);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (base == null || base.isRecycled()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mode == MODE_DRAW) {
                    activeStroke = new Stroke(brushColor, brushWidth);
                    mapToImage(x, y);
                    activeStroke.path.moveTo(point[0], point[1]);
                    activeStroke.path.lineTo(point[0] + 0.01f, point[1] + 0.01f);
                    invalidate();
                    return true;
                }
                if (mode == MODE_CROP) {
                    mapToImage(x, y);
                    float cx = clamp(point[0], 0, base.getWidth());
                    float cy = clamp(point[1], 0, base.getHeight());
                    cropCreating = false;
                    cropCorner = hitCropCorner(x, y);
                    if (cropCorner == CORNER_NONE) {
                        if (cropRect != null) {
                            // 已有裁剪框：拖动空白处不再重画，只有四角控制柄能调整
                            return true;
                        }
                        cropCreating = true;
                        cropStartX = cx;
                        cropStartY = cy;
                        cropRect = new RectF(cx, cy, cx, cy);
                    }
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_DRAW && activeStroke != null) {
                    mapToImage(x, y);
                    activeStroke.path.lineTo(
                            clamp(point[0], 0, base.getWidth()),
                            clamp(point[1], 0, base.getHeight()));
                    invalidate();
                    return true;
                }
                if (mode == MODE_CROP && cropRect != null) {
                    mapToImage(x, y);
                    float cx = clamp(point[0], 0, base.getWidth());
                    float cy = clamp(point[1], 0, base.getHeight());
                    if (cropCreating) {
                        cropRect.set(Math.min(cropStartX, cx), Math.min(cropStartY, cy),
                                Math.max(cropStartX, cx), Math.max(cropStartY, cy));
                    } else if (cropCorner != CORNER_NONE) {
                        moveCropCorner(cx, cy);
                    } else {
                        return true;
                    }
                    invalidate();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == MODE_DRAW && activeStroke != null) {
                    strokes.add(activeStroke);
                    activeStroke = null;
                    invalidate();
                    return true;
                }
                if (mode == MODE_CROP) {
                    if (cropRect != null && (cropRect.width() < CROP_MIN_PX || cropRect.height() < CROP_MIN_PX)) {
                        // 只是点了一下、没拖出有效范围：丢掉这个框，保持原状
                        cropRect = null;
                    }
                    cropCreating = false;
                    cropCorner = CORNER_NONE;
                    invalidate();
                    return true;
                }
                return false;

            default:
                return super.onTouchEvent(event);
        }
    }

    private static final class Stroke {
        final Path path = new Path();
        final int color;
        final float width;

        Stroke(int color, float width) {
            this.color = color;
            this.width = width;
        }
    }
}
