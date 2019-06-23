package org.telegram.ui.Components.Crop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import static android.graphics.Paint.FILTER_BITMAP_FLAG;

public class CropView extends FrameLayout implements CropAreaView.AreaViewListener, CropGestureDetector.CropGestureListener {
    private static final float EPSILON = 0.00001f;
    private static final int RESULT_SIDE = 1280;
    private static final float MAX_SCALE = 30.0f;

    private CropAreaView areaView;
    private ImageView imageView;
    private Matrix presentationMatrix;

    private RectF previousAreaRect;
    private RectF initialAreaRect;
    private float rotationStartScale;

    private CropRectangle tempRect;
    private Matrix tempMatrix;

    private Bitmap bitmap;
    private boolean freeform;
    private float bottomPadding;

    private boolean isMoved;
    private boolean animating;
    private CropGestureDetector detector;

    private float scaleAnimEndValue;
    private float orientationAnimEndValue;

    private boolean hasAspectRatioDialog;

    private class CropState {
        private float width;
        private float height;

        private float x;
        private float y;
        private float scale;
        private float minimumScale;
        private float baseRotation;
        private float orientation;
        private float rotation;
        private Matrix matrix;

        private CropState(Bitmap bitmap, int bRotation) {
            width = bitmap.getWidth();
            height = bitmap.getHeight();

            x = 0.0f;
            y = 0.0f;
            scale = 1.0f;
            baseRotation = bRotation;
            rotation = 0.0f;
            matrix = new Matrix();
        }

        private void updateBitmap(Bitmap bitmap, int rotation) {
            float ps = width / bitmap.getWidth();
            scale *= ps;
            width = bitmap.getWidth();
            height = bitmap.getHeight();
            updateMinimumScale();
            float[] values = new float[9];
            matrix.getValues(values);
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(values[2], values[5]);
            updateMatrix();
        }

        private boolean hasChanges() {
            return Math.abs(x) > EPSILON || Math.abs(y) > EPSILON || Math.abs(scale - minimumScale) > EPSILON
                    || Math.abs(rotation) > EPSILON || Math.abs(orientation) > EPSILON;
        }

        private float getWidth() {
            return width;
        }

        private float getHeight() {
            return height;
        }

        private float getOrientedWidth() {
            return (orientation + baseRotation) % 180 != 0 ? height : width;
        }

        private float getOrientedHeight() {
            return (orientation + baseRotation) % 180 != 0 ? width : height;
        }

        private void translate(float x, float y) {
            this.x += x;
            this.y += y;
            matrix.postTranslate(x, y);
        }

        private float getX() {
            return x;
        }

        private float getY() {
            return y;
        }

        private void scale(float s, float pivotX, float pivotY) {
            scale *= s;
            matrix.postScale(s, s, pivotX, pivotY);
        }

        private float getScale() {
            return scale;
        }

        private float getMinimumScale() {
            return minimumScale;
        }

        private void rotate(float angle, float pivotX, float pivotY) {
            rotation += angle;
            matrix.postRotate(angle, pivotX, pivotY);
        }

        private float getRotation() {
            return rotation;
        }

        private float getOrientation() {
            return orientation + baseRotation;
        }

        private float getBaseRotation() {
            return baseRotation;
        }

        private void reset(CropAreaView areaView, float orient, boolean freeform) {
            matrix.reset();

            x = 0.0f;
            y = 0.0f;
            rotation = 0.0f;
            orientation = orient;
            updateMinimumScale();
            scale = minimumScale;

            matrix.postScale(scale, scale);
        }

        private void updateMinimumScale() {
            updateMinimumScale(false);
        }

        private void updateMinimumScale(boolean animValue) {
            float w = (orientation + baseRotation) % 180 != 0 ? height : width;
            float h = (orientation + baseRotation) % 180 != 0 ? width : height;
            float cropWidth = animValue ? areaView.getCropWidthAnimEndValue() : areaView.getCropWidth();
            float cropHeight = animValue ? areaView.getCropHeightAnimEndValue() : areaView.getCropHeight();
            if (freeform) {
                minimumScale = cropWidth / w;
            } else {
                float wScale = cropWidth / w;
                float hScale = cropHeight / h;
                minimumScale = Math.max(wScale, hScale);
            }
        }

        private void getConcatMatrix(Matrix toMatrix) {
            toMatrix.postConcat(matrix);
        }

        private Matrix getMatrix() {
            Matrix m = new Matrix();
            m.set(matrix);
            return m;
        }
    }

    private CropState state;

    public interface CropViewListener {
        void onChange(boolean reset);

        void onAspectLock(boolean enabled);
    }

    private CropViewListener listener;

    public CropView(Context context) {
        super(context);

        previousAreaRect = new RectF();
        initialAreaRect = new RectF();
        presentationMatrix = new Matrix();
        tempRect = new CropRectangle();
        tempMatrix = new Matrix();
        animating = false;

        imageView = new ImageView(context);
        imageView.setDrawingCacheEnabled(true);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        addView(imageView);

        detector = new CropGestureDetector(context);
        detector.setOnGestureListener(this);

        areaView = new CropAreaView(context);
        areaView.setListener(this);
        addView(areaView);
    }

    public boolean isReady() {
        return !detector.isScaling() && !detector.isDragging() && !areaView.isDragging();
    }

    public void setListener(CropViewListener l) {
        listener = l;
    }

    public void setBottomPadding(float value) {
        bottomPadding = value;
        areaView.setBottomPadding(value);
    }

    public void setAspectRatio(float ratio) {
        areaView.setActualRect(ratio);
    }

    public void setBitmap(Bitmap b, int rotation, boolean fform, boolean same) {
        freeform = fform;
        if (b == null) {
            bitmap = null;
            state = null;
            imageView.setImageDrawable(null);
        } else {
            bitmap = b;
            if (state == null || !same) {
                state = new CropState(bitmap, rotation);
                imageView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        reset(true);
                        imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                        return false;
                    }
                });
            } else {
                state.updateBitmap(bitmap, rotation);
                resetOrientation(false);
            }
            imageView.setImageBitmap(bitmap);
        }
    }

    public void willShow() {
        areaView.setFrameVisibility(true);
        areaView.setDimVisibility(true);
        areaView.invalidate();
    }

    public void setFreeform(boolean fform) {
        areaView.setFreeform(fform);
        freeform = fform;
    }

    public void show() {
        imageView.setVisibility(VISIBLE);
        areaView.setDimVisibility(true);
        areaView.setFrameVisibility(true);
        areaView.invalidate();
    }

    public void hide() {
        imageView.setVisibility(INVISIBLE);
        areaView.setDimVisibility(false);
        areaView.setFrameVisibility(false);
        areaView.invalidate();
    }

    private float lerp(float a, float b, float f) {
        return a + f * (b - a);
    }

    public void reset() {
        reset(false);
    }

    public void reset(boolean initiation) {
        if (initiation) {
            resetOrientation(false);
            areaView.resetAnimator();
            areaView.setBitmap(bitmap, state.getBaseRotation() % 180f != 0f, freeform, false);
            areaView.setLockedAspectRatio(freeform ? 0.0f : 1.0f);
            state.reset(areaView, 0f, freeform);
            areaView.getCropRect(initialAreaRect);
            updateMatrix();
        } else {
            resetOrientation(true);

            areaView.resetAnimator();
            areaView.setBitmap(bitmap, state.getBaseRotation() % 180f != 0f, freeform, true);
            areaView.setLockedAspectRatio(freeform ? 0.0f : 1.0f);

            float[] startValues = new float[9];
            state.matrix.getValues(startValues);

            state.updateMinimumScale(true);

            tempMatrix.reset();
            tempMatrix.postScale(state.minimumScale, state.minimumScale);
            float[] endValues = new float[9];
            tempMatrix.getValues(endValues);

            float[] values = new float[9];
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(300);
            animator.addUpdateListener(a -> {
                float fraction = a.getAnimatedFraction();
                for (int i = 0; i < 9; i++) {
                    values[i] = lerp(startValues[i], endValues[i], fraction);
                }
                state.matrix.setValues(values);
                updateMatrix();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    state.reset(areaView, 0f, freeform);
                    areaView.getCropRect(initialAreaRect);
                    updateMatrix();
                }
            });
            animator.start();
        }

        isMoved = false;
        resetRotationStartScale();

        if (listener != null) {
            listener.onChange(true);
            listener.onAspectLock(false);
        }
    }

    public void updateMatrix() {
        presentationMatrix.reset();
        presentationMatrix.postTranslate(-state.getWidth() / 2, -state.getHeight() / 2);
        presentationMatrix.postRotate(state.getOrientation());
        state.getConcatMatrix(presentationMatrix);
        presentationMatrix.postTranslate(areaView.getCropCenterX(), areaView.getCropCenterY());

        imageView.setImageMatrix(presentationMatrix);
    }

    private void fillAreaView(RectF targetRect, boolean allowZoomOut) {
        final float[] currentScale = new float[]{1.0f};
        float scale = Math.max(targetRect.width() / areaView.getCropWidth(),
                targetRect.height() / areaView.getCropHeight());

        float newScale = state.getScale() * scale;
        boolean ensureFit = false;
        if (newScale > MAX_SCALE) {
            scale = MAX_SCALE / state.getScale();
            ensureFit = true;
        }
        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0);

        final float x = (targetRect.centerX() - imageView.getWidth() / 2) / areaView.getCropWidth() * state.getOrientedWidth();
        final float y = (targetRect.centerY() - (imageView.getHeight() - bottomPadding + statusBarHeight) / 2) / areaView.getCropHeight() * state.getOrientedHeight();
        final float targetScale = scale;

        final boolean animEnsureFit = ensureFit;

        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        animator.addUpdateListener(animation -> {
            float value = (Float) animation.getAnimatedValue();
            float deltaScale = (1.0f + ((targetScale - 1.0f) * value)) / currentScale[0];
            currentScale[0] *= deltaScale;
            state.scale(deltaScale, x, y);
            updateMatrix();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animEnsureFit)
                    fitContentInBounds(false, false, true);
            }
        });
        areaView.fill(targetRect, animator, true);
        initialAreaRect.set(targetRect);
    }

    private float fitScale(RectF contentRect, float scale, float ratio) {
        float scaledW = contentRect.width() * ratio;
        float scaledH = contentRect.height() * ratio;

        float scaledX = (contentRect.width() - scaledW) / 2.0f;
        float scaledY = (contentRect.height() - scaledH) / 2.0f;

        contentRect.set(contentRect.left + scaledX, contentRect.top + scaledY,
                contentRect.left + scaledX + scaledW, contentRect.top + scaledY + scaledH);

        return scale * ratio;
    }

    private void fitTranslation(RectF contentRect, RectF boundsRect, PointF translation, float radians) {
        float frameLeft = boundsRect.left;
        float frameTop = boundsRect.top;
        float frameRight = boundsRect.right;
        float frameBottom = boundsRect.bottom;

        if (contentRect.left > frameLeft) {
            frameRight += contentRect.left - frameLeft;
            frameLeft = contentRect.left;
        }
        if (contentRect.top > frameTop) {
            frameBottom += contentRect.top - frameTop;
            frameTop = contentRect.top;
        }
        if (contentRect.right < frameRight) {
            frameLeft += contentRect.right - frameRight;
        }
        if (contentRect.bottom < frameBottom) {
            frameTop += contentRect.bottom - frameBottom;
        }

        float deltaX = boundsRect.centerX() - (frameLeft + boundsRect.width() / 2.0f);
        float deltaY = boundsRect.centerY() - (frameTop + boundsRect.height() / 2.0f);

        float xCompX = (float) (Math.sin(Math.PI / 2 - radians) * deltaX);
        float xCompY = (float) (Math.cos(Math.PI / 2 - radians) * deltaX);

        float yCompX = (float) (Math.cos(Math.PI / 2 + radians) * deltaY);
        float yCompY = (float) (Math.sin(Math.PI / 2 + radians) * deltaY);

        translation.set(translation.x + xCompX + yCompX, translation.y + xCompY + yCompY);
    }

    public RectF calculateBoundingBox(float w, float h, float rotation) {
        RectF result = new RectF(0, 0, w, h);
        Matrix m = new Matrix();
        m.postRotate(rotation, w / 2.0f, h / 2.0f);
        m.mapRect(result);
        return result;
    }

    public float scaleWidthToMaxSize(RectF sizeRect, RectF maxSizeRect) {
        float w = maxSizeRect.width();
        float h = (float) Math.floor(w * sizeRect.height() / sizeRect.width());
        if (h > maxSizeRect.height()) {
            h = maxSizeRect.height();
            w = (float) Math.floor(h * sizeRect.width() / sizeRect.height());
        }
        return w;
    }

    private class CropRectangle {
        float[] coords = new float[8];

        CropRectangle() {
        }

        void setRect(RectF rect) {
            coords[0] = rect.left;
            coords[1] = rect.top;
            coords[2] = rect.right;
            coords[3] = rect.top;
            coords[4] = rect.right;
            coords[5] = rect.bottom;
            coords[6] = rect.left;
            coords[7] = rect.bottom;
        }

        void applyMatrix(Matrix m) {
            m.mapPoints(coords);
        }

        void getRect(RectF rect) {
            rect.set(coords[0], coords[1], coords[2], coords[7]);
        }
    }

    private void fitContentInBounds(boolean allowScale, boolean maximize, boolean animated) {
        fitContentInBounds(allowScale, maximize, animated, false);
    }

    private void fitContentInBounds(final boolean allowScale, final boolean maximize, final boolean animated, final boolean fast) {
        if (state == null) {
            return;
        }
        float boundsW = areaView.getCropWidth();
        float boundsH = areaView.getCropHeight();
        float contentW = state.getOrientedWidth();
        float contentH = state.getOrientedHeight();
        float rotation = state.getRotation();
        float radians = (float) Math.toRadians(rotation);

        RectF boundsRect = calculateBoundingBox(boundsW, boundsH, rotation);
        RectF contentRect = new RectF(0.0f, 0.0f, contentW, contentH);

        float initialX = (boundsW - contentW) / 2.0f;
        float initialY = (boundsH - contentH) / 2.0f;

        float scale = state.getScale();

        tempRect.setRect(contentRect);

        Matrix matrix = state.getMatrix();
        matrix.preTranslate(initialX / scale, initialY / scale);

        tempMatrix.reset();
        tempMatrix.setTranslate(contentRect.centerX(), contentRect.centerY());
        tempMatrix.setConcat(tempMatrix, matrix);
        tempMatrix.preTranslate(-contentRect.centerX(), -contentRect.centerY());
        tempRect.applyMatrix(tempMatrix);

        tempMatrix.reset();
        tempMatrix.preRotate(-rotation, contentW / 2.0f, contentH / 2.0f);
        tempRect.applyMatrix(tempMatrix);
        tempRect.getRect(contentRect);

        PointF targetTranslation = new PointF(state.getX(), state.getY());
        float targetScale = scale;

        if (!contentRect.contains(boundsRect)) {
            if (allowScale && (boundsRect.width() > contentRect.width() || boundsRect.height() > contentRect.height())) {
                float ratio = boundsRect.width() / scaleWidthToMaxSize(boundsRect, contentRect);
                targetScale = fitScale(contentRect, scale, ratio);
            }

            fitTranslation(contentRect, boundsRect, targetTranslation, radians);
        } else if (maximize && rotationStartScale > 0) {
            float ratio = boundsRect.width() / scaleWidthToMaxSize(boundsRect, contentRect);
            float newScale = state.getScale() * ratio;
            if (newScale < rotationStartScale)
                ratio = 1.0f;
            targetScale = fitScale(contentRect, scale, ratio);

            fitTranslation(contentRect, boundsRect, targetTranslation, radians);
        }

        float dx = targetTranslation.x - state.getX();
        float dy = targetTranslation.y - state.getY();

        if (animated) {
            final float animScale = targetScale / scale;
            final float animDX = dx;
            final float animDY = dy;

            if (Math.abs(animScale - 1.0f) < EPSILON
                    && Math.abs(animDX) < EPSILON && Math.abs(animDY) < EPSILON) {
                return;
            }

            animating = true;

            final float[] currentValues = new float[]{1.0f, 0.0f, 0.0f};
            ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
            animator.addUpdateListener(animation -> {
                float value = (Float) animation.getAnimatedValue();

                float deltaX = animDX * value - currentValues[1];
                currentValues[1] += deltaX;
                float deltaY = animDY * value - currentValues[2];
                currentValues[2] += deltaY;
                state.translate(deltaX * currentValues[0], deltaY * currentValues[0]);

                float deltaScale = (1.0f + ((animScale - 1.0f) * value)) / currentValues[0];
                currentValues[0] *= deltaScale;
                state.scale(deltaScale, 0, 0);

                updateMatrix();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    animating = false;

                    if (!fast)
                        fitContentInBounds(allowScale, maximize, animated, true);
                }
            });
            animator.setInterpolator(areaView.getInterpolator());
            animator.setDuration(fast ? 100 : 200);
            animator.start();
        } else {
            state.translate(dx, dy);
            state.scale(targetScale / scale, 0, 0);
            updateMatrix();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        imageView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                fitViewScale(false, false);
                imageView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    private void ensurePivot() {
        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0f);
        float actualHeight = getHeight() - AndroidUtilities.dp(64f) - statusBarHeight;
        setPivotX(getWidth() / 2f);
        setPivotY(statusBarHeight + actualHeight / 2f);
    }

    private void fitViewScale(boolean animate, boolean startAnim) {
        if (orientationAnimEndValue % 180f != 0) {
            float statusBarHeight = (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0f);
            float actualHeight = getHeight() - AndroidUtilities.dp(64f) - statusBarHeight - AndroidUtilities.dp(32f);
            float actualWidth = getWidth() - AndroidUtilities.dp(32f);
            scaleAnimEndValue = Math.min(actualWidth / areaView.getCropHeightAnimEndValue(),
                    actualHeight / areaView.getCropWidthAnimEndValue());
        } else scaleAnimEndValue = 1f;

        areaView.setWheelTop(orientationAnimEndValue % 180 != 0 ? getHeight() - AndroidUtilities.dp(64f) - AndroidUtilities.dp(16f) : 0f);

        ensurePivot();
        if (animate && startAnim) {
            animate().setDuration(300)
                    .scaleX(scaleAnimEndValue)
                    .scaleY(scaleAnimEndValue)
                    .start();
        } else if (!animate) {
            setScaleX(scaleAnimEndValue);
            setScaleY(scaleAnimEndValue);
        }
    }

    private void startOrientationAnim() {
        ensurePivot();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            animate().setDuration(300)
                    .scaleX(scaleAnimEndValue)
                    .scaleY(scaleAnimEndValue)
                    .rotation(orientationAnimEndValue)
                    .setUpdateListener(a -> areaView.invalidate())
                    .start();
        } else {
            animate().setDuration(300)
                    .scaleX(scaleAnimEndValue)
                    .scaleY(scaleAnimEndValue)
                    .rotation(orientationAnimEndValue)
                    .withEndAction(() -> areaView.invalidate())
                    .start();
        }
    }

    private void resetOrientation(boolean animate) {
        areaView.setWheelTop(0f);
        scaleAnimEndValue = 1f;
        if (animate) {
            final float angle = orientationAnimEndValue % 360f;
            orientationAnimEndValue -= (angle == -270f) ? 90f : angle;
            startOrientationAnim();
        } else {
            ensurePivot();
            orientationAnimEndValue = 0f;
            setScaleX(scaleAnimEndValue);
            setScaleY(scaleAnimEndValue);
            super.setRotation(orientationAnimEndValue);
        }
    }

    public void toggleOrientation() {
        if (!ViewCompat.isLaidOut(this)) return;

        orientationAnimEndValue -= 90f;

        fitViewScale(true, false);
        startOrientationAnim();

        if (listener != null) {
            listener.onChange(isChanged());
        }
    }

    public boolean isChanged() {
        return orientationAnimEndValue % 360f == 0f && state.rotation == 0f &&
                areaView.getLockAspectRatio() == 0f && !isMoved;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (animating) {
            return true;
        }
        boolean result = false;
        if (areaView.onTouchEvent(event))
            return true;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                onScrollChangeBegan();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onScrollChangeEnded();
                break;
        }
        try {
            result = detector.onTouchEvent(event);
        } catch (Exception ignore) {
        }
        return result;
    }

    @SuppressWarnings("unused")
    public boolean pointInView(float localX, float localY, float slop) {
        return true; // take all unhandled touches from parent
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public void onAreaChangeBegan() {
        areaView.getCropRect(previousAreaRect);
        resetRotationStartScale();
        isMoved = true;
        if (listener != null) {
            listener.onChange(false);
        }
    }

    @Override
    public void onAreaChange() {
        areaView.setGridType(CropAreaView.GridType.MAJOR, false);

        float x = previousAreaRect.centerX() - areaView.getCropCenterX();
        float y = previousAreaRect.centerY() - areaView.getCropCenterY();
        state.translate(x, y);
        updateMatrix();

        areaView.getCropRect(previousAreaRect);

        fitContentInBounds(true, false, false);
    }

    @Override
    public void onAreaChangeEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
        fillAreaView(areaView.getTargetRectToFill(), false);
        fitViewScale(true, true);
    }

    public void onDrag(float dx, float dy) {
        if (animating) {
            return;
        }

        state.translate(dx, dy);
        updateMatrix();
    }

    public void onFling(float startX, float startY, float velocityX, float velocityY) {
    }

    public void onScrollChangeBegan() {
        if (animating) {
            return;
        }

        areaView.setGridType(CropAreaView.GridType.MAJOR, true);
        resetRotationStartScale();

        if (listener != null) {
            listener.onChange(false);
        }
    }

    public void onScrollChangeEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
        fitContentInBounds(true, false, true);
    }

    public void onScale(float scale, float x, float y) {
        if (animating) {
            return;
        }

        float newScale = state.getScale() * scale;
        if (newScale > MAX_SCALE)
            scale = MAX_SCALE / state.getScale();

        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 ? AndroidUtilities.statusBarHeight : 0);

        float pivotX = (x - imageView.getWidth() / 2) / areaView.getCropWidth() * state.getOrientedWidth();
        float pivotY = (y - (imageView.getHeight() - bottomPadding - statusBarHeight) / 2) / areaView.getCropHeight() * state.getOrientedHeight();

        state.scale(scale, pivotX, pivotY);
        updateMatrix();
    }

    public void onRotationBegan() {
        areaView.setGridType(CropAreaView.GridType.MINOR, false);
        if (rotationStartScale < 0.00001f) {
            rotationStartScale = state.getScale();
        }
    }

    public void onRotationEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
    }

    private void resetRotationStartScale() {
        rotationStartScale = 0.0f;
    }

    public void setRotation(float angle) {
        float deltaAngle = angle - state.getRotation();
        state.rotate(deltaAngle, 0, 0);
        fitContentInBounds(true, true, false);
    }

    public Bitmap getResult() {
        if ((state == null || !state.hasChanges() && state.getBaseRotation() < EPSILON && freeform) && orientationAnimEndValue % 360f == 0) {
            return bitmap;
        }

        RectF cropRect = new RectF();
        areaView.getCropRect(cropRect);
        RectF sizeRect = new RectF(0, 0, RESULT_SIDE, RESULT_SIDE);

        float w = scaleWidthToMaxSize(cropRect, sizeRect);
        int width = (int) Math.ceil(w);
        int height = (int) (Math.ceil(width / areaView.getAspectRatio()));

        Bitmap resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-state.getWidth() / 2, -state.getHeight() / 2);

        matrix.postRotate(state.getOrientation());
        state.getConcatMatrix(matrix);

        float scale = width / areaView.getCropWidth();
        matrix.postScale(scale, scale);
        matrix.postTranslate(width / 2, height / 2);

        new Canvas(resultBitmap).drawBitmap(bitmap, matrix, new Paint(FILTER_BITMAP_FLAG));

        Matrix orientationMatrix = new Matrix();
        orientationMatrix.postRotate(orientationAnimEndValue);
        return Bitmap.createBitmap(resultBitmap, 0, 0, resultBitmap.getWidth(),
                resultBitmap.getHeight(), orientationMatrix, true);
    }

    private void setLockedAspectRatio(float aspectRatio) {
        areaView.setLockedAspectRatio(aspectRatio);
        RectF targetRect = new RectF();
        areaView.calculateRect(targetRect, aspectRatio);
        fillAreaView(targetRect, true);
        fitViewScale(true, true);

        if (listener != null) {
            listener.onChange(false);
            listener.onAspectLock(true);
        }
    }

    public void showAspectRatioDialog() {
        if (areaView.getLockAspectRatio() > 0) {
            areaView.setLockedAspectRatio(0);

            if (listener != null) {
                listener.onAspectLock(false);
            }

            return;
        }

        if (hasAspectRatioDialog) {
            return;
        }

        hasAspectRatioDialog = true;

        String[] actions = new String[8];

        final Integer[][] ratios = new Integer[][]{
                new Integer[]{3, 2},
                new Integer[]{5, 3},
                new Integer[]{4, 3},
                new Integer[]{5, 4},
                new Integer[]{7, 5},
                new Integer[]{16, 9}
        };

        actions[0] = LocaleController.getString("CropOriginal", R.string.CropOriginal);
        actions[1] = LocaleController.getString("CropSquare", R.string.CropSquare);

        int i = 2;
        for (Integer[] ratioPair : ratios) {
            if (areaView.getAspectRatio() > 1.0f) {
                actions[i] = String.format("%d:%d", ratioPair[0], ratioPair[1]);
            } else {
                actions[i] = String.format("%d:%d", ratioPair[1], ratioPair[0]);
            }
            i++;
        }

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setItems(actions, (dialog12, which) -> {
                    hasAspectRatioDialog = false;
                    switch (which) {
                        case 0: {
                            float w = state.getBaseRotation() % 180 != 0 ? state.getHeight() : state.getWidth();
                            float h = state.getBaseRotation() % 180 != 0 ? state.getWidth() : state.getHeight();
                            setLockedAspectRatio(w / h);
                        }
                        break;

                        case 1: {
                            setLockedAspectRatio(1.0f);
                        }
                        break;

                        default: {
                            Integer[] ratioPair = ratios[which - 2];

                            if (areaView.getAspectRatio() > 1.0f) {
                                setLockedAspectRatio(ratioPair[0] / (float) ratioPair[1]);
                            } else {
                                setLockedAspectRatio(ratioPair[1] / (float) ratioPair[0]);
                            }
                        }
                        break;
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(dialog1 -> hasAspectRatioDialog = false);
        dialog.show();
    }

    public void updateLayout() {
        float w = areaView.getCropWidth();
        if (state != null) {
            areaView.calculateRect(initialAreaRect, state.getWidth() / state.getHeight());
            areaView.setActualRect(areaView.getAspectRatio());
            areaView.getCropRect(previousAreaRect);

            float ratio = areaView.getCropWidth() / w;
            state.scale(ratio, 0, 0);
            updateMatrix();
        }
    }

    public float getCropLeft() {
        return areaView.getCropLeft();
    }

    public float getCropTop() {
        return areaView.getCropTop();
    }

    public float getCropWidth() {
        return areaView.getCropWidth();
    }

    public float getCropHeight() {
        return areaView.getCropHeight();
    }
}
