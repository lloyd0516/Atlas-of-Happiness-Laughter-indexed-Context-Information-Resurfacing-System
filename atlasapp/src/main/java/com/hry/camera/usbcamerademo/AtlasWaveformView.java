package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.view.View;

public final class AtlasWaveformView extends View {
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint remainingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] amplitudes = new float[0];
    private float progress;
    private boolean playbackActive;

    public AtlasWaveformView(Context context) {
        this(context, null);
    }

    public AtlasWaveformView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AtlasWaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        playedPaint.setColor(ContextCompat.getColor(context, R.color.mock_orange_dark));
        playedPaint.setStrokeCap(Paint.Cap.ROUND);
        remainingPaint.setColor(ContextCompat.getColor(context, R.color.mock_border));
        remainingPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setAmplitudes(float[] values) {
        if (values == null || values.length == 0) {
            amplitudes = new float[0];
        } else {
            amplitudes = values.clone();
            for (int i = 0; i < amplitudes.length; i++) {
                amplitudes[i] = clampProgress(amplitudes[i]);
            }
        }
        invalidate();
    }

    public void setProgress(float value) {
        progress = clampProgress(value);
        invalidate();
    }

    public void setPlaybackActive(boolean active) {
        playbackActive = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = amplitudes.length;
        if (count == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float step = width / count;
        float strokeWidth = Math.max(dp(1.5f), step * 0.45f);
        float minHeight = Math.min(height, dp(2f));
        float centerY = height / 2f;
        playedPaint.setStrokeWidth(strokeWidth);
        playedPaint.setAlpha(playbackActive ? 255 : 220);
        remainingPaint.setStrokeWidth(strokeWidth);

        for (int i = 0; i < count; i++) {
            float x = step * (i + 0.5f);
            float barHeight = computeBarHeight(amplitudes[i], height, minHeight);
            float top = centerY - barHeight / 2f;
            float bottom = centerY + barHeight / 2f;
            float barProgress = (i + 0.5f) / count;
            Paint paint = barProgress <= progress ? playedPaint : remainingPaint;
            canvas.drawLine(x, top, x, bottom, paint);
        }
    }

    static float computeBarHeight(float amplitude, float availableHeight, float minHeight) {
        float safeHeight = Math.max(0f, availableHeight);
        float safeMinimum = Math.max(0f, Math.min(minHeight, safeHeight));
        float clampedAmplitude = clampProgress(amplitude);
        return Math.max(safeMinimum, safeHeight * clampedAmplitude);
    }

    static float clampProgress(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
