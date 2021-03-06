package com.xiaopo.flying.sticker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

/**
 * Sticker View
 *
 * @author wupanjie
 */
public class StickerView extends FrameLayout {

//    private static final String TAG = StickerView.class.getSimpleName();

    final GestureDetector gestureDetector;

    private final boolean showIcons;
    private final boolean showAuxiliaryLines;
    private final boolean showBorder;
    private final boolean bringToFrontCurrentSticker;

    @IntDef({
            ActionMode.NONE, ActionMode.DRAG, ActionMode.ZOOM_WITH_TWO_FINGER, ActionMode.ICON,
            ActionMode.CLICK
    })
    @Retention(RetentionPolicy.SOURCE)
    protected @interface ActionMode {
        int NONE = 0;
        int DRAG = 1;
        int ZOOM_WITH_TWO_FINGER = 2;
        int ICON = 3;
        int CLICK = 4;
    }

    @IntDef(flag = true, value = {FLIP_HORIZONTALLY, FLIP_VERTICALLY})
    @Retention(RetentionPolicy.SOURCE)
    protected @interface Flip {
    }


    private static final int DEFAULT_MIN_CLICK_DELAY_TIME = 200;

    public static final int FLIP_HORIZONTALLY = 1;
    public static final int FLIP_VERTICALLY = 1 << 1;

    private final List<Sticker> stickers = new ArrayList<>();
    private final List<BitmapStickerIcon> icons = new ArrayList<>(4);

    private final Paint borderPaint = new Paint();
    private final Paint auxiliaryLinePaint = new Paint();
    private final DashPathEffect dashPathEffect = new DashPathEffect(new float[]{10f, 10f}, 0);

    private final RectF stickerRect = new RectF();

    private final Matrix sizeMatrix = new Matrix();
    private final Matrix downMatrix = new Matrix();
    private final Matrix moveMatrix = new Matrix();

    // region storing variables
    private final float[] bitmapPoints = new float[8];
    private final float[] bounds = new float[8];
    private final float[] point = new float[2];
    private final PointF currentCenterPoint = new PointF();
    private final float[] tmp = new float[2];
    private PointF midPoint = new PointF();
    // endregion
    private final int touchSlop;

    private BitmapStickerIcon currentIcon;
    //the first point down position
    private float downX;
    private float downY;

    private float oldDistance = 0f;
    private float oldRotation = 0f;
    private float previousRotation = 0f;

    @ActionMode
    private int currentMode = ActionMode.NONE;

    private Sticker handlingSticker;

    private boolean locked;
    private boolean constrained;

    private OnStickerOperationListener onStickerOperationListener;
    private OnStickerAreaTouchListener onStickerAreaTouchListener;

    private long lastClickTime = 0;
    private int minClickDelayTime = DEFAULT_MIN_CLICK_DELAY_TIME;

    private final boolean withStyleIcon;

    @ColorInt
    private final int accentColor;

    @ColorInt
    private final int dashColor;

    public StickerView(Context context) {
        this(context, null);
    }

    public StickerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        TypedArray a = null;
        try {
            a = context.obtainStyledAttributes(attrs, R.styleable.StickerView);
            showIcons = a.getBoolean(R.styleable.StickerView_showIcons, false);
            showBorder = a.getBoolean(R.styleable.StickerView_showBorder, false);
            showAuxiliaryLines = a.getBoolean(R.styleable.StickerView_showAuxiliaryLines, false);
            bringToFrontCurrentSticker =
                    a.getBoolean(R.styleable.StickerView_bringToFrontCurrentSticker, false);
            withStyleIcon = a.getBoolean(R.styleable.StickerView_withStyleIcon, false);
            accentColor = a.getColor(R.styleable.StickerView_accentColor, Color.GRAY);
            dashColor = a.getColor(R.styleable.StickerView_dashColor, Color.GREEN);

            borderPaint.setAntiAlias(true);
            borderPaint.setStrokeWidth(3);
            borderPaint.setColor(a.getColor(R.styleable.StickerView_borderColor, Color.BLACK));
            borderPaint.setAlpha(a.getInteger(R.styleable.StickerView_borderAlpha, 128));

            auxiliaryLinePaint.setAntiAlias(true);
            auxiliaryLinePaint.setStrokeWidth(3);
            auxiliaryLinePaint.setColor(accentColor);
            auxiliaryLinePaint.setAlpha(a.getInteger(R.styleable.StickerView_borderAlpha, 128));

            configDefaultIcons();
        } finally {
            if (a != null) {
                a.recycle();
            }
        }

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            public void onLongPress(MotionEvent event) {
                Timber.e("LongPress_detected");
//                setHapticFeedbackEnabled(true);
                if (currentMode == ActionMode.ICON) {
                    if (currentIcon != null) {
                        currentIcon.onActionLongPress(StickerView.this, event);
                    }
                }
            }
        });
    }


    public void configDefaultIcons() {
        BitmapStickerIcon deleteIcon = new BitmapStickerIcon(
                getContext(), R.drawable.ic_close, BitmapStickerIcon.LEFT_TOP);
        deleteIcon.setIconEvent(new DeleteIconEvent());

        BitmapStickerIcon zoomIcon = new BitmapStickerIcon(
                getContext(), R.drawable.ic_scale, BitmapStickerIcon.RIGHT_BOTTOM);
        zoomIcon.setIconEvent(new ZoomIconEvent());

        icons.clear();
        icons.add(deleteIcon);
        icons.add(zoomIcon);

        if (withStyleIcon) {
            Timber.d("withStyleIcon_!");
//            BitmapStickerIcon styleIcon = new BitmapStickerIcon(
//                    getContext(), R.drawable.ic_underline, BitmapStickerIcon.LEFT_BOTTOM);
//            styleIcon.setIconEvent(new StyleIconEvent());
//            icons.add(styleIcon);
        } else {
            BitmapStickerIcon flipIcon = new BitmapStickerIcon(
                    getContext(), R.drawable.ic_flip, BitmapStickerIcon.RIGHT_TOP);
            flipIcon.setIconEvent(new FlipHorizontallyEvent());
            icons.add(flipIcon);
        }
    }

    /**
     * Swaps sticker at layer [[oldPos]] with the one at layer [[newPos]].
     * Does nothing if either of the specified layers doesn't exist.
     */
    public void swapLayers(int oldPos, int newPos) {
        if (stickers.size() >= oldPos && stickers.size() >= newPos) {
            Collections.swap(stickers, oldPos, newPos);
            invalidate();
        }
    }

    /**
     * Sends sticker from layer [[oldPos]] to layer [[newPos]].
     * Does nothing if either of the specified layers doesn't exist.
     */
    public void sendToLayer(int oldPos, int newPos) {
        if (stickers.size() >= oldPos && stickers.size() >= newPos) {
            Sticker s = stickers.get(oldPos);
            stickers.remove(oldPos);
            stickers.add(newPos, s);
            invalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            stickerRect.left = left;
            stickerRect.top = top;
            stickerRect.right = right;
            stickerRect.bottom = bottom;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawStickers(canvas);
    }

    protected void drawStickers(Canvas canvas) {
        for (int i = 0; i < stickers.size(); i++) {
            Sticker sticker = stickers.get(i);
            if (sticker != null) {
                if (sticker.isVisible()) {
                    sticker.draw(canvas);
                }
            }
        }

        if (handlingSticker != null && !locked && (showBorder || showIcons)) {

            getStickerPoints(handlingSticker, bitmapPoints);

            float x1 = bitmapPoints[0];
            float y1 = bitmapPoints[1];
            float x2 = bitmapPoints[2];
            float y2 = bitmapPoints[3];
            float x3 = bitmapPoints[4];
            float y3 = bitmapPoints[5];
            float x4 = bitmapPoints[6];
            float y4 = bitmapPoints[7];

            if (handlingSticker != null && handlingSticker.isVisible()) {

                if (showBorder) {
                    canvas.drawLine(x1, y1, x2, y2, borderPaint);
                    canvas.drawLine(x1, y1, x3, y3, borderPaint);
                    canvas.drawLine(x2, y2, x4, y4, borderPaint);
                    canvas.drawLine(x4, y4, x3, y3, borderPaint);
                }

                float rotation = calculateRotation(x4, y4, x3, y3);
                float roundedRotation = roundOff(rotation);

                if (showAuxiliaryLines) {
                    if (previousRotation != roundedRotation) {
                        previousRotation = roundedRotation;
                        float quotient = Math.abs(rotation) / 45;
                        float diff = Math.round(quotient) - quotient;
//                        Timber.d("rotation__: %s", rotation);
//                        Timber.d("previousRotation: %s", previousRotation);
//                        Timber.d("Math.abs(rotation) / 45: %s", quotient);
//                        Timber.d("round: %s", Math.round(quotient));
//                        Timber.d("diff: %s", diff);
                        if (Math.abs(diff) < 0.0075) {
                            auxiliaryLinePaint.setColor(dashColor);
                            auxiliaryLinePaint.setPathEffect(dashPathEffect);
                            float startX = midValue(x1, x3);
                            float startY = midValue(y1, y3);
                            float stopX = midValue(x2, x4);
                            float stopY = midValue(y2, y4);
                            float deltaX = stopX - startX;
                            if (deltaX != 0) {
                                float deltaY = stopY - startY;
                                float slope = deltaY / deltaX;
                                float yLeft = slope * (0 - startX) + startY;
                                float yRight = slope * (this.getWidth() - startX) + startY;
                                canvas.drawLine(0, yLeft, this.getWidth(), yRight, auxiliaryLinePaint);
                            } else {
                                canvas.drawLine(startX, 0, startX, this.getHeight(), auxiliaryLinePaint);
                            }
                        }
                    }

                    if (currentMode == ActionMode.DRAG) {

                        auxiliaryLinePaint.setColor(accentColor);
                        auxiliaryLinePaint.setPathEffect(null);

                        int maxX = this.getWidth();
                        int middleX = maxX / 2;
//                        Timber.d("StickerView__ getWidth: %s, half %s", maxX, middleX);
                        int maxY = this.getHeight();
                        int middleY = maxY / 2;
//                        Timber.d("StickerView__ getHeight: %s, half %s", maxY, middleY);

                        float stickerCenterX = StickerView.midValue(x1, x4);
                        float stickerCenterY = StickerView.midValue(y1, y4);

                        double sensitivity = 2;

                        float diffX = Math.abs(stickerCenterX - middleX);
                        float diffY = Math.abs(stickerCenterY - middleY);

                        if (diffX < sensitivity) {
                            canvas.drawLine(middleX, 0, middleX, maxY, auxiliaryLinePaint);
                        }

                        if (diffY < sensitivity) {
                            canvas.drawLine(0, middleY, maxX, middleY, auxiliaryLinePaint);
                        }

                        if (diffX < sensitivity || diffY < sensitivity) {
                            if (onStickerOperationListener != null) {
                                float minDiff = Math.min(diffX, diffY);
                                if (minDiff < sensitivity / 3) {
                                    onStickerOperationListener.onStickerTouchedAuxiliaryLines(handlingSticker);
                                }
                            }
                        }


                    } else if (currentMode == ActionMode.NONE) {
                        Timber.d("currentMode == ActionMode_NONE");
                    }
                }

                //draw icons
                if (showIcons) {

                    for (int i = 0; i < icons.size(); i++) {
                        BitmapStickerIcon icon = icons.get(i);
                        switch (icon.getPosition()) {
                            case BitmapStickerIcon.LEFT_TOP:
                                configIconMatrix(icon, x1, y1, rotation);
                                break;

                            case BitmapStickerIcon.RIGHT_TOP:
                                configIconMatrix(icon, x2, y2, rotation);
                                break;

                            case BitmapStickerIcon.LEFT_BOTTOM:
                                configIconMatrix(icon, x3, y3, rotation);
                                break;

                            case BitmapStickerIcon.RIGHT_BOTTOM:
                                configIconMatrix(icon, x4, y4, rotation);
                                break;
                        }
                        icon.draw(canvas, borderPaint);
                    }
                }
            }

        }
    }

    private float roundOff(float rotation) {
        return Math.round(rotation * 100f) / 100f;
    }

    public static float midValue(float n1, float n2) {
        return n1 / 2 + n2 / 2 + (n1 % 2 + n2 % 2) / 2;
    }

    protected void configIconMatrix(@NonNull BitmapStickerIcon icon, float x, float y, float rotation) {
        icon.setX(x);
        icon.setY(y);
        icon.getMatrix().reset();

        icon.getMatrix().postRotate(rotation, icon.getWidth() / 2f, icon.getHeight() / 2f);
        icon.getMatrix().postTranslate(x - icon.getWidth() / 2f, y - icon.getHeight() / 2f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (locked) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();

                return findCurrentIconTouched() != null || findHandlingSticker() != null;
        }

        return super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (locked) {
            return super.onTouchEvent(event);
        }

        //deprecated
//        int action = MotionEventCompat.getActionMasked(event);
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                Timber.d("MotionEvent.ACTION_DOWN event__: %s", event.toString());

                if (onStickerAreaTouchListener != null) {
                    onStickerAreaTouchListener.onStickerAreaTouch();
                }

                if (!onTouchDown(event)) {
                    return false;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                oldDistance = calculateDistance(event);
                oldRotation = calculateRotation(event);

                midPoint = calculateMidPoint(event);

                if (handlingSticker != null && isInStickerArea(handlingSticker, event.getX(1),
                        event.getY(1)) && findCurrentIconTouched() == null) {
                    currentMode = ActionMode.ZOOM_WITH_TWO_FINGER;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                handleCurrentMode(event);
                invalidate();
                break;

            case MotionEvent.ACTION_UP:
                onTouchUp(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (currentMode == ActionMode.ZOOM_WITH_TWO_FINGER && handlingSticker != null) {
                    if (onStickerOperationListener != null) {
                        onStickerOperationListener.onStickerZoomFinished(handlingSticker);
                    }
                }
                currentMode = ActionMode.NONE;
                break;
        }

        return true;
    }

    /**
     * @param event MotionEvent received from {@link #onTouchEvent)
     * @return true if has touch something
     */
    protected boolean onTouchDown(@NonNull MotionEvent event) {

        currentMode = ActionMode.DRAG;

        downX = event.getX();
        downY = event.getY();

        midPoint = calculateMidPoint();
        oldDistance = calculateDistance(midPoint.x, midPoint.y, downX, downY);
        oldRotation = calculateRotation(midPoint.x, midPoint.y, downX, downY);

        currentIcon = findCurrentIconTouched();
        gestureDetector.onTouchEvent(event);

        if (currentIcon != null) {
            currentMode = ActionMode.ICON;
            currentIcon.onActionDown(this, event);
            Timber.d("current_icon: %s", currentIcon.getDrawableName());
        } else {
            handlingSticker = findHandlingSticker();
        }

//		handlingSticker = findHandlingSticker();

        if (handlingSticker != null) {
            downMatrix.set(handlingSticker.getMatrix());
            if (bringToFrontCurrentSticker) {
                stickers.remove(handlingSticker);
                stickers.add(handlingSticker);
            }
            if (onStickerOperationListener != null) {
                onStickerOperationListener.onStickerTouchedDown(handlingSticker);
            }
        }

        invalidate();
        return currentIcon != null || handlingSticker != null;
    }

    protected void onTouchUp(@NonNull MotionEvent event) {
        long currentTime = SystemClock.uptimeMillis();

        if (currentMode == ActionMode.ICON && currentIcon != null && handlingSticker != null) {
            currentIcon.onActionUp(this, event);
            Timber.d("current_icon: %s", currentIcon.getDrawableName());
        }

        if (currentMode == ActionMode.DRAG
                && Math.abs(event.getX() - downX) < touchSlop
                && Math.abs(event.getY() - downY) < touchSlop
                && handlingSticker != null) {
            currentMode = ActionMode.CLICK;
            if (onStickerOperationListener != null) {
                onStickerOperationListener.onStickerClicked(handlingSticker);
            }
            if (currentTime - lastClickTime < minClickDelayTime) {
                if (onStickerOperationListener != null) {
                    onStickerOperationListener.onStickerDoubleTapped(handlingSticker);
                }
            }
        }

        if (currentMode == ActionMode.DRAG && handlingSticker != null) {
            if (onStickerOperationListener != null) {
                onStickerOperationListener.onStickerDragFinished(handlingSticker);
            }
        }

        currentMode = ActionMode.NONE;
        lastClickTime = currentTime;
    }

    protected void handleCurrentMode(@NonNull MotionEvent event) {
        switch (currentMode) {
            case ActionMode.NONE:
            case ActionMode.CLICK:
                break;
            case ActionMode.DRAG:
                if (handlingSticker != null) {
                    moveMatrix.set(downMatrix);
                    moveMatrix.postTranslate(event.getX() - downX, event.getY() - downY);
                    handlingSticker.setMatrix(moveMatrix);
                    if (constrained) {
                        constrainSticker(handlingSticker);
                    }
                    if (onStickerOperationListener != null) {
                        Timber.d("MotionEvent.ACTION_MOVE event__: %s", event.toString());
                        onStickerOperationListener.onStickerMoved(handlingSticker);
                    }
                }
                break;
            case ActionMode.ZOOM_WITH_TWO_FINGER:
                if (handlingSticker != null) {
                    float newDistance = calculateDistance(event);
                    float newRotation = calculateRotation(event);

                    moveMatrix.set(downMatrix);
                    moveMatrix.postScale(newDistance / oldDistance, newDistance / oldDistance, midPoint.x,
                            midPoint.y);
                    moveMatrix.postRotate(newRotation - oldRotation, midPoint.x, midPoint.y);
                    handlingSticker.setMatrix(moveMatrix);
                }

                break;

            case ActionMode.ICON:
                if (handlingSticker != null && currentIcon != null) {
                    currentIcon.onActionMove(this, event);
                }
                break;
        }
    }

    public void zoomAndRotateCurrentSticker(@NonNull MotionEvent event) {
        zoomAndRotateSticker(handlingSticker, event);
    }

    public void zoomAndRotateSticker(@Nullable Sticker sticker, @NonNull MotionEvent event) {
        if (sticker != null) {
            float newDistance = calculateDistance(midPoint.x, midPoint.y, event.getX(), event.getY());
            float newRotation = calculateRotation(midPoint.x, midPoint.y, event.getX(), event.getY());

            moveMatrix.set(downMatrix);
            moveMatrix.postScale(newDistance / oldDistance, newDistance / oldDistance, midPoint.x,
                    midPoint.y);
            moveMatrix.postRotate(newRotation - oldRotation, midPoint.x, midPoint.y);
            handlingSticker.setMatrix(moveMatrix);
        }
    }

    protected void constrainSticker(@NonNull Sticker sticker) {
        float moveX = 0;
        float moveY = 0;
        int width = getWidth();
        int height = getHeight();
        sticker.getMappedCenterPoint(currentCenterPoint, point, tmp);
        if (currentCenterPoint.x < 0) {
            moveX = -currentCenterPoint.x;
        }

        if (currentCenterPoint.x > width) {
            moveX = width - currentCenterPoint.x;
        }

        if (currentCenterPoint.y < 0) {
            moveY = -currentCenterPoint.y;
        }

        if (currentCenterPoint.y > height) {
            moveY = height - currentCenterPoint.y;
        }

        sticker.getMatrix().postTranslate(moveX, moveY);
    }

    @Nullable
    protected BitmapStickerIcon findCurrentIconTouched() {
        for (BitmapStickerIcon icon : icons) {
            float x = icon.getX() + icon.getIconRadius() - downX;
            float y = icon.getY() + icon.getIconRadius() - downY;
            float distance_pow_2 = x * x + y * y;
            if (distance_pow_2 <= Math.pow((icon.getIconRadius() + icon.getIconRadius()) * 1.2f, 2)) {
                return icon;
            }
        }

        return null;
    }

    /**
     * find the touched Sticker
     **/
    @Nullable
    protected Sticker findHandlingSticker() {
        for (int i = stickers.size() - 1; i >= 0; i--) {
            if (isInStickerArea(stickers.get(i), downX, downY)) {
                return stickers.get(i);
            }
        }
        return null;
    }

    protected boolean isInStickerArea(@NonNull Sticker sticker, float downX, float downY) {
        tmp[0] = downX;
        tmp[1] = downY;
        return sticker.contains(tmp);
    }

    @NonNull
    protected PointF calculateMidPoint(@Nullable MotionEvent event) {
        if (event == null || event.getPointerCount() < 2) {
            midPoint.set(0, 0);
            return midPoint;
        }
        float x = (event.getX(0) + event.getX(1)) / 2;
        float y = (event.getY(0) + event.getY(1)) / 2;
        midPoint.set(x, y);
        return midPoint;
    }

    @NonNull
    protected PointF calculateMidPoint() {
        if (handlingSticker == null) {
            midPoint.set(0, 0);
            return midPoint;
        }
        handlingSticker.getMappedCenterPoint(midPoint, point, tmp);
        return midPoint;
    }

    /**
     * calculate rotation in line with two fingers and x-axis
     **/
    protected float calculateRotation(@Nullable MotionEvent event) {
        if (event == null || event.getPointerCount() < 2) {
            return 0f;
        }
        return calculateRotation(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
    }

    protected float calculateRotation(float x1, float y1, float x2, float y2) {
        double x = x1 - x2;
        double y = y1 - y2;
        double radians = Math.atan2(y, x);
        return (float) Math.toDegrees(radians);
    }

    /**
     * calculate Distance in two fingers
     **/
    protected float calculateDistance(@Nullable MotionEvent event) {
        if (event == null || event.getPointerCount() < 2) {
            return 0f;
        }
        return calculateDistance(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
    }

    protected float calculateDistance(float x1, float y1, float x2, float y2) {
        double x = x1 - x2;
        double y = y1 - y2;

        return (float) Math.sqrt(x * x + y * y);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        for (int i = 0; i < stickers.size(); i++) {
            Sticker sticker = stickers.get(i);
            if (sticker != null) {
                transformSticker(sticker);
            }
        }
    }

    /**
     * Sticker's drawable will be too bigger or smaller
     * This method is to transform it to fit
     * step 1：let the center of the sticker image is coincident with the center of the View.
     * step 2：Calculate the zoom and zoom
     **/
    protected void transformSticker(@Nullable Sticker sticker) {
        if (sticker == null) {
            Timber.e("transformSticker: the bitmapSticker is null or the bitmapSticker bitmap is null");
            return;
        }

        sizeMatrix.reset();

        float width = getWidth();
        float height = getHeight();
        float stickerWidth = sticker.getWidth();
        float stickerHeight = sticker.getHeight();
        //step 1
        float offsetX = (width - stickerWidth) / 2;
        float offsetY = (height - stickerHeight) / 2;

        sizeMatrix.postTranslate(offsetX, offsetY);

        //step 2
        float scaleFactor;
        if (width < height) {
            scaleFactor = width / stickerWidth;
        } else {
            scaleFactor = height / stickerHeight;
        }

        sizeMatrix.postScale(scaleFactor / 2f, scaleFactor / 2f, width / 2f, height / 2f);

        sticker.getMatrix().reset();
        sticker.setMatrix(sizeMatrix);

        invalidate();
    }

    public void flipCurrentSticker(int direction) {
        flip(handlingSticker, direction);
    }

    public void flip(@Nullable Sticker sticker, @Flip int direction) {
        if (sticker != null) {
            sticker.getCenterPoint(midPoint);
            if ((direction & FLIP_HORIZONTALLY) > 0) {
                sticker.getMatrix().preScale(-1, 1, midPoint.x, midPoint.y);
                sticker.setFlippedHorizontally(!sticker.isFlippedHorizontally());
            }
            if ((direction & FLIP_VERTICALLY) > 0) {
                sticker.getMatrix().preScale(1, -1, midPoint.x, midPoint.y);
                sticker.setFlippedVertically(!sticker.isFlippedVertically());
            }

            if (onStickerOperationListener != null) {
                onStickerOperationListener.onStickerFlipped(sticker);
            }

            invalidate();
        }
    }

    public boolean replace(@Nullable Sticker sticker) {
        return replace(sticker, true);
    }

    public boolean replace(@Nullable Sticker sticker, boolean needStayState) {
        if (handlingSticker != null && sticker != null) {
            float width = getWidth();
            float height = getHeight();
            if (needStayState) {
                sticker.setMatrix(handlingSticker.getMatrix());
                sticker.setFlippedVertically(handlingSticker.isFlippedVertically());
                sticker.setFlippedHorizontally(handlingSticker.isFlippedHorizontally());
            } else {
                handlingSticker.getMatrix().reset();
                // reset scale, angle, and put it in center
                float offsetX = (width - handlingSticker.getWidth()) / 2f;
                float offsetY = (height - handlingSticker.getHeight()) / 2f;
                sticker.getMatrix().postTranslate(offsetX, offsetY);

                float scaleFactor;
                if (width < height) {
                    scaleFactor = width / handlingSticker.getDrawable().getIntrinsicWidth();
                } else {
                    scaleFactor = height / handlingSticker.getDrawable().getIntrinsicHeight();
                }
                sticker.getMatrix().postScale(scaleFactor / 2f, scaleFactor / 2f, width / 2f, height / 2f);
            }
            int index = stickers.indexOf(handlingSticker);
            stickers.set(index, sticker);
            handlingSticker = sticker;

            invalidate();
            return true;
        } else {
            return false;
        }
    }

    public boolean remove(@Nullable Sticker sticker) {
        if (stickers.contains(sticker)) {
            stickers.remove(sticker);
            if (onStickerOperationListener != null) {
                onStickerOperationListener.onStickerDeleted(sticker);
            }
            if (handlingSticker == sticker) {
                handlingSticker = null;
            }
            invalidate();

            return true;
        } else {
            Timber.d("remove: the sticker is not in this StickerView");
            return false;
        }
    }

    public void showCurrentSticker() {
        handlingSticker.setVisible(true);
    }

    public void hideCurrentSticker() {
        handlingSticker.setVisible(false);
    }

    public boolean removeCurrentSticker() {
        return remove(handlingSticker);
    }

    public void removeAllStickers() {
        stickers.clear();
        if (handlingSticker != null) {
            handlingSticker.release();
            handlingSticker = null;
        }
        invalidate();
    }

    @NonNull
    public StickerView addSticker(@NonNull Sticker sticker) {
        return addSticker(sticker, Sticker.Position.CENTER);
    }

    public StickerView addSticker(@NonNull final Sticker sticker,
                                  final @Sticker.Position int position) {
        if (ViewCompat.isLaidOut(this)) {
            addStickerImmediately(sticker, position);
        } else {
            post(() -> addStickerImmediately(sticker, position));
        }
        return this;
    }

    protected void addStickerImmediately(@NonNull Sticker sticker, @Sticker.Position int position) {
        setStickerPosition(sticker, position);

        float scaleFactor, widthScaleFactor, heightScaleFactor;

        widthScaleFactor = (float) getWidth() / sticker.getDrawable().getIntrinsicWidth();
        heightScaleFactor = (float) getHeight() / sticker.getDrawable().getIntrinsicHeight();
        scaleFactor = Math.min(widthScaleFactor, heightScaleFactor);

        sticker.getMatrix()
                .postScale(scaleFactor / 2, scaleFactor / 2, getWidth() / 2f, getHeight() / 2f);

        handlingSticker = sticker;
        stickers.add(sticker);
        if (onStickerOperationListener != null) {
            onStickerOperationListener.onStickerAdded(sticker);
        }
        invalidate();
    }

    protected void setStickerPosition(@NonNull Sticker sticker, @Sticker.Position int position) {
        float width = getWidth();
        float height = getHeight();
        float offsetX = width - sticker.getWidth();
        float offsetY = height - sticker.getHeight();
        if ((position & Sticker.Position.TOP) > 0) {
            offsetY /= 4f;
        } else if ((position & Sticker.Position.BOTTOM) > 0) {
            offsetY *= 3f / 4f;
        } else {
            offsetY /= 2f;
        }
        if ((position & Sticker.Position.LEFT) > 0) {
            offsetX /= 4f;
        } else if ((position & Sticker.Position.RIGHT) > 0) {
            offsetX *= 3f / 4f;
        } else {
            offsetX /= 2f;
        }
        sticker.getMatrix().postTranslate(offsetX, offsetY);
    }

    @NonNull
    public float[] getStickerPoints(@Nullable Sticker sticker) {
        float[] points = new float[8];
        getStickerPoints(sticker, points);
        return points;
    }

    public void getStickerPoints(@Nullable Sticker sticker, @NonNull float[] dst) {
        if (sticker == null) {
            Arrays.fill(dst, 0);
            return;
        }
        sticker.getBoundPoints(bounds);
        sticker.getMappedPoints(dst, bounds);
    }

    public void save(@NonNull File file) {
        try {
            StickerUtils.saveImageToGallery(file, createBitmap());
            StickerUtils.notifySystemGallery(getContext(), file);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            //
        }
    }

    @NonNull
    public Bitmap createBitmap() throws OutOfMemoryError {
        handlingSticker = null;
        Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        this.draw(canvas);
        return bitmap;
    }

    public int getStickerCount() {
        return stickers.size();
    }

    public boolean isNoneSticker() {
        return getStickerCount() == 0;
    }

    public boolean isLocked() {
        return locked;
    }

    @NonNull
    public StickerView setLocked(boolean locked) {
        this.locked = locked;
        invalidate();
        return this;
    }

    @NonNull
    public StickerView setMinClickDelayTime(int minClickDelayTime) {
        this.minClickDelayTime = minClickDelayTime;
        return this;
    }

    public int getMinClickDelayTime() {
        return minClickDelayTime;
    }

    public boolean isConstrained() {
        return constrained;
    }

    @NonNull
    public StickerView setConstrained(boolean constrained) {
        this.constrained = constrained;
        postInvalidate();
        return this;
    }

    @NonNull
    public StickerView setOnStickerOperationListener(
            @Nullable OnStickerOperationListener onStickerOperationListener) {
        this.onStickerOperationListener = onStickerOperationListener;
        return this;
    }

    @Nullable
    public OnStickerOperationListener getOnStickerOperationListener() {
        return onStickerOperationListener;
    }

    @Nullable
    public Sticker getCurrentSticker() {
        return handlingSticker;
    }

    @NonNull
    public List<BitmapStickerIcon> getIcons() {
        return icons;
    }

    public void setIcons(@NonNull List<BitmapStickerIcon> icons) {
        this.icons.clear();
        this.icons.addAll(icons);
        invalidate();
    }

    public void removeFrame() { //remove icons and border from current sticker
        //through this code, condition in line 218 becomes false
        // if (handlingSticker != null && !locked && (showBorder || showIcons))
        handlingSticker = null;
        invalidate(); //refresh canvas (by calling dispatchDraw(canvas))
    }

    public interface OnStickerOperationListener {
        void onStickerAdded(@NonNull Sticker sticker);

        void onStickerClicked(@NonNull Sticker sticker);

        void onStickerDeleted(@NonNull Sticker sticker);

        void onStickerDragFinished(@NonNull Sticker sticker);

        void onStickerTouchedDown(@NonNull Sticker sticker);

        void onStickerZoomFinished(@NonNull Sticker sticker);

        void onStickerFlipped(@NonNull Sticker sticker);

        void onStickerDoubleTapped(@NonNull Sticker sticker);

        void onStickerMoved(@NonNull Sticker sticker);

        void onStickerTouchedAuxiliaryLines(@NonNull Sticker sticker);
    }

    public interface OnStickerAreaTouchListener {
        void onStickerAreaTouch();
    }

    public void setOnStickerAreaTouchListener(OnStickerAreaTouchListener onStickerAreaTouchListener) {
        this.onStickerAreaTouchListener = onStickerAreaTouchListener;
    }

    public void setCurrentIcon(BitmapStickerIcon currentIcon) {
        this.currentIcon = currentIcon;
    }

    public void deselectAnySticker() {
        this.handlingSticker = null;
    }

    public List<Sticker> getStickers() {
        return stickers;
    }
}
