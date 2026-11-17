package com.example.investment.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

public class SignalLightDrawable extends Drawable {
    private static final float MIDDLE_RADIUS_RATIO = 0.72f;
    private static final float CORE_RADIUS_RATIO = 0.435f;

    private static final int HALO_ALPHA = 0x29;
    private static final int MIDDLE_ALPHA = 0x80;
    private static final int CORE_ALPHA = 0xFF;

    private final int sizePx;
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint middlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private @ColorInt int color = 0;
    private boolean hasColor = false;
    private int alpha = 0xFF;

    public SignalLightDrawable(int sizePx) {
        this.sizePx = sizePx;
    }

    public void setColor(@ColorInt int color) {
        if (hasColor && this.color == color) return;
        this.color = color;
        this.hasColor = true;
        updatePaints();
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!hasColor) return;
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        final float centerX = bounds.exactCenterX();
        final float centerY = bounds.exactCenterY();
        final float haloRadius = Math.min(bounds.width(), bounds.height()) / 2f;

        canvas.drawCircle(centerX, centerY, haloRadius, haloPaint);
        canvas.drawCircle(centerX, centerY, haloRadius * MIDDLE_RADIUS_RATIO, middlePaint);
        canvas.drawCircle(centerX, centerY, haloRadius * CORE_RADIUS_RATIO, corePaint);
    }

    private void updatePaints() {
        haloPaint.setColor(ColorUtils.setAlphaComponent(color, scaleAlpha(HALO_ALPHA)));
        middlePaint.setColor(ColorUtils.setAlphaComponent(color, scaleAlpha(MIDDLE_ALPHA)));
        corePaint.setColor(ColorUtils.setAlphaComponent(color, scaleAlpha(CORE_ALPHA)));
    }

    private int scaleAlpha(int layerAlpha) {
        return layerAlpha * alpha / 0xFF;
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        if (hasColor) updatePaints();
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        haloPaint.setColorFilter(colorFilter);
        middlePaint.setColorFilter(colorFilter);
        corePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}