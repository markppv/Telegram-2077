package org.telegram.ui.Components.Crop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public class CropRotationWheel extends FrameLayout {

    private int dp2p5 = AndroidUtilities.dp(2.5f);
    private int dp22 = AndroidUtilities.dp(22);
    private int dp2 = AndroidUtilities.dp(2);
    private int dp70 = AndroidUtilities.dp(70);
    private int dp16= AndroidUtilities.dp(16);
    private int dp12 = AndroidUtilities.dp(12);

    public interface RotationWheelListener {
        void onStart();
        void onChange(float angle);
        void onEnd(float angle);

        void aspectRatioPressed();
        void rotate90Pressed();
    }

    private static final int MAX_ANGLE = 45;
    private static final int DELTA_ANGLE = 5;

    private Paint whitePaint;
    private Paint bluePaint;

    private ImageView aspectRatioButton;
    private TextView degreesLabel;

    protected float actualRotation;
    protected float rotation;
    private RectF tempRect;
    private float prevX;

    private RotationWheelListener rotationListener;

    public CropRotationWheel(Context context) {
        super(context);

        tempRect = new RectF(0, 0, 0, 0);

        whitePaint = new Paint();
        whitePaint.setStyle(Paint.Style.FILL);
        whitePaint.setColor(Color.WHITE);
        whitePaint.setAlpha(255);
        whitePaint.setAntiAlias(true);

        bluePaint = new Paint();
        bluePaint.setStyle(Paint.Style.FILL);
        bluePaint.setColor(0xff51bdf3);
        bluePaint.setAlpha(255);
        bluePaint.setAntiAlias(true);

        aspectRatioButton = new ImageView(context);
        aspectRatioButton.setImageResource(R.drawable.tool_cropfix);
        aspectRatioButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.ACTION_BAR_WHITE_SELECTOR_COLOR));
        aspectRatioButton.setScaleType(ImageView.ScaleType.CENTER);
        aspectRatioButton.setOnClickListener(v -> {
            if (rotationListener != null)
                rotationListener.aspectRatioPressed();
        });
        aspectRatioButton.setContentDescription(LocaleController.getString("AccDescrAspectRatio", R.string.AccDescrAspectRatio));
        addView(aspectRatioButton, LayoutHelper.createFrame(70, 64, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        ImageView rotation90Button = new ImageView(context);
        rotation90Button.setImageResource(R.drawable.tool_rotate);
        rotation90Button.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.ACTION_BAR_WHITE_SELECTOR_COLOR));
        rotation90Button.setScaleType(ImageView.ScaleType.CENTER);
        rotation90Button.setOnClickListener(v -> {
            if (rotationListener != null) {
                rotationListener.rotate90Pressed();
            }
        });
        rotation90Button.setContentDescription(LocaleController.getString("AccDescrRotate", R.string.AccDescrRotate));
        addView(rotation90Button, LayoutHelper.createFrame(70, 64, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        degreesLabel = new TextView(context);
        degreesLabel.setTextColor(Color.WHITE);
        degreesLabel.setGravity(Gravity.CENTER);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(18));
        addView(degreesLabel, lp);

        setWillNotDraw(false);

        setRotation(0.0f, true);
    }

    public void setFreeform(boolean freeform) {
        aspectRatioButton.setVisibility(freeform ? VISIBLE : GONE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(width, AndroidUtilities.dp(400)), MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    public void reset() {
        setRotation(0.0f, true);
    }

    public void setListener(RotationWheelListener listener) {
        rotationListener = listener;
    }

    @Override
    public float getRotation() {
        return rotation;
    }

    public void setRotation(float rotation, boolean silent) {
        this.rotation = rotation;
        float value = this.rotation;
        if (Math.abs(value) < 0.1f - 0.001f)
            value = Math.abs(value);
        degreesLabel.setText(String.format("%.1fº", value));
        if (!silent && rotationListener != null) {
            rotationListener.onChange(this.rotation);
        }
        invalidate();
    }

    public void setAspectLock(boolean enabled) {
        aspectRatioButton.setColorFilter(enabled ? new PorterDuffColorFilter(0xff51bdf3, PorterDuff.Mode.MULTIPLY) : null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();

        if (action == MotionEvent.ACTION_DOWN) {
            prevX = x;

            if (rotationListener != null)
                rotationListener.onStart();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (rotationListener != null)
                rotationListener.onEnd(this.rotation);
            AndroidUtilities.makeAccessibilityAnnouncement(String.format("%.1f°", this.rotation));
        } else if (action == MotionEvent.ACTION_MOVE) {
            float delta = prevX - x;

            float newAngle = this.rotation + (float)(delta / AndroidUtilities.density / Math.PI / 1.65f);
            newAngle = Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, newAngle));

            if (Math.abs(newAngle - this.rotation) > 0.001f) {
                if (Math.abs(newAngle) < 0.5f)
                    newAngle = 0f;

                setRotation(newAngle, false);

                prevX = x;
            }
        }

        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float widthF = getWidth();
        float heightF = getHeight();

        float angle = -rotation * 2f;
        float delta = angle % DELTA_ANGLE;
        int segments = (int)Math.floor(angle / DELTA_ANGLE);

        for (int i = 0; i < 16; i++) {
            Paint paint = whitePaint;

            int a = i;
            if (a < segments || (a == 0 && delta < 0)) paint = bluePaint;
            drawLine(canvas, a, delta, widthF, heightF, (a == segments || a == 0 && segments == - 1), paint);

            if (i != 0) {
                a = -i;
                paint = a > segments ? bluePaint : whitePaint;
                drawLine(canvas, a, delta, widthF, heightF, a == segments + 1, paint);
            }
        }

        bluePaint.setAlpha(255);

        tempRect.left = (widthF - dp2p5) / 2f;
        tempRect.top = (heightF - dp22) / 2f;
        tempRect.right = (widthF + dp2p5) / 2f;
        tempRect.bottom =  (heightF + dp22) / 2f;
        canvas.drawRoundRect(tempRect, dp2, dp2, bluePaint);
    }

    protected void drawLine(Canvas canvas, int i, float delta, float width, float height, boolean center, Paint paint) {
        float radius = width / 2.0f - dp70;

        float angle = 90 - (i * DELTA_ANGLE + delta);
        float val = radius * (float) Math.cos(Math.toRadians(angle));
        float x = width / 2f + val;

        float f = Math.abs(val) / radius;
        int alpha = Math.min(255, Math.max(0, (int)((1.0f - f * f) * 255)));

        if (center) paint = bluePaint;

        float w = center ? 4f : 2f;
        float h = center ? dp16 : dp12;

        paint.setAlpha(alpha);
        paint.setStrokeWidth(w);

        canvas.drawLine(x, (height - h) / 2f, x, (height + h) / 2f, paint);

        //canvas.drawRect(x - w / 2, (height - h) / 2, x + w / 2, (height + h) / 2, paint);
    }
}
