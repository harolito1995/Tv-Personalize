package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Miniature of the home screen painted with a theme's real palette: fallback background,
 * header type, three tiles and the dock (band or floating). Used on the theme cards in
 * Personalize so each theme is recognisable before applying it.
 */
public class ThemeCardView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private LauncherTheme theme = LauncherTheme.byId(LauncherTheme.DEFAULT_ID);
    private boolean band = true;

    public ThemeCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    void setTheme(LauncherTheme t, boolean band) {
        this.theme = t;
        this.band = band;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float d = density;

        // Fallback background.
        rect.set(0, 0, w, h);
        paint.setColor(theme.screenBg);
        canvas.drawRoundRect(rect, 12 * d, 12 * d, paint);

        // Header mock: primary and dim type lines, accent dot.
        paint.setColor(theme.textPrimary);
        canvas.drawRoundRect(new RectF(10 * d, 10 * d, 10 * d + Math.min(w * 0.34f, 46 * d), 15 * d),
                2.5f * d, 2.5f * d, paint);
        paint.setColor(theme.textDim);
        canvas.drawRoundRect(new RectF(10 * d, 19 * d, 10 * d + Math.min(w * 0.22f, 30 * d), 22.5f * d),
                1.75f * d, 1.75f * d, paint);
        paint.setColor(theme.accent);
        canvas.drawCircle(w - 12 * d, 15 * d, 3.5f * d, paint);

        // Three tiles.
        float gap = 6 * d;
        float cw = (w - 20 * d - 2 * gap) / 3f;
        float top = 34 * d;
        paint.setColor(theme.card);
        for (int i = 0; i < 3; i++) {
            float x = 10 * d + i * (cw + gap);
            canvas.drawRoundRect(new RectF(x, top, x + cw, top + 22 * d), 6 * d, 6 * d, paint);
        }

        // Dock: banded or floating, matching the current Interface setting.
        float miniH = 13 * d;
        if (band) {
            float bandH = 24 * d;
            float bandTop = h - 6 * d - bandH;
            rect.set(8 * d, bandTop, w - 8 * d, h - 6 * d);
            paint.setColor(theme.dockBand);
            canvas.drawRoundRect(rect, 9 * d, 9 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(theme.dockEdge);
            canvas.drawRoundRect(rect, 9 * d, 9 * d, paint);
            paint.setStyle(Paint.Style.FILL);
            miniRow(canvas, 13 * d, rect.width() - 10 * d, bandTop + (bandH - miniH) / 2f, miniH);
        } else {
            miniRow(canvas, 10 * d, w - 20 * d, h - 6 * d - miniH - 2 * d, miniH);
        }
    }

    private void miniRow(Canvas c, float left, float rowW, float top, float itemH) {
        float d = density;
        float gap = 4 * d;
        float iw = (rowW - 3 * gap) / 4f;
        float ih = Math.min(itemH, iw * 0.75f);
        paint.setColor(theme.card);
        for (int i = 0; i < 4; i++) {
            float x = left + i * (iw + gap);
            c.drawRoundRect(new RectF(x, top, x + iw, top + ih), 4 * d, 4 * d, paint);
        }
    }
}
