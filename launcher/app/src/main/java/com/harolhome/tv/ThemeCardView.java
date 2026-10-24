package com.harolhome.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Miniature of the B.3 home screen painted with a theme's real palette: fallback background,
 * compact header (clock, date, weather pill, three circular actions), two 16:9 Continue Watching
 * cards with subtle progress bar, and the dock (band or floating). Used on the theme cards in
 * Personalize so each theme is recognisable before applying it.
 *
 * <p>Geometry is partitioned into two non-overlapping zones: the miniature Home layout occupies the
 * upper 60dp (header + Continue Watching + mini-Dock), while the bottom 22dp is a clean footer
 * reserved exclusively for {@code @id/themeName} so the label never collides with the mini-Dock.
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
        float radius = getResources().getDimension(R.dimen.tile_radius);

        // Fallback background surface.
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.screenBg);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Header mock: compact clock, date, weather capsule and 3 top-right circular actions.
        paint.setColor(theme.textPrimary);
        canvas.drawRoundRect(new RectF(10 * d, 7 * d, 25 * d, 11.5f * d), 2f * d, 2f * d, paint);
        paint.setColor(theme.textDim);
        canvas.drawRoundRect(new RectF(28 * d, 8 * d, 41 * d, 10.8f * d), 1.4f * d, 1.4f * d, paint);
        paint.setColor(theme.pillFillTop);
        canvas.drawRoundRect(new RectF(44 * d, 7.5f * d, 57 * d, 11.2f * d), 2f * d, 2f * d, paint);

        for (int i = 0; i < 3; i++) {
            float cx = w - 12 * d - i * 7.5f * d;
            paint.setColor(i == 0 ? theme.accent : theme.pillFillTop);
            canvas.drawCircle(cx, 9.4f * d, 2.3f * d, paint);
        }

        // Two compact 16:9 "Continue Watching" cards with external caption and 1dp progress.
        float shelfTop = 15.5f * d;
        float cardW = 31 * d;
        float cardH = 17.5f * d;
        float cardGap = 4.5f * d;
        for (int i = 0; i < 2; i++) {
            float x = 10 * d + i * (cardW + cardGap);
            paint.setColor(theme.card);
            canvas.drawRoundRect(new RectF(x, shelfTop, x + cardW, shelfTop + cardH), 3.5f * d, 3.5f * d, paint);
            if (i == 0) {
                paint.setStyle(Paint.Style.STROKE);
                float focusStroke = 3.5f * d;
                float focusInset = focusStroke * 0.5f;
                paint.setStrokeWidth(focusStroke);
                paint.setColor(0xD9000000);
                canvas.drawRoundRect(new RectF(x + focusInset, shelfTop + focusInset,
                        x + cardW - focusInset, shelfTop + cardH - focusInset),
                        3.5f * d - focusInset, 3.5f * d - focusInset, paint);
                float core = 2f * d;
                paint.setStrokeWidth(core);
                paint.setColor(theme.cardFocusStroke);
                canvas.drawRoundRect(new RectF(x + focusInset, shelfTop + focusInset,
                        x + cardW - focusInset, shelfTop + cardH - focusInset),
                        3.5f * d - focusInset, 3.5f * d - focusInset, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(theme.accent);
                canvas.drawRoundRect(
                        new RectF(x + 2 * d, shelfTop + cardH - 2f * d, x + cardW * 0.62f, shelfTop + cardH - 0.8f * d),
                        d, d, paint);
            }
            paint.setColor(theme.textSecondary);
            canvas.drawRoundRect(
                    new RectF(x + d, shelfTop + cardH + 1.8f * d, x + cardW * 0.72f, shelfTop + cardH + 3.4f * d),
                    d, d, paint);
        }

        // Reserved bottom footer (y = h - 22dp .. h) for @id/themeName so label and mini-Dock never overlap.
        float footerTop = h - 22f * d;
        float miniH = 8.5f * d;
        if (band) {
            float bandH = 13.5f * d;
            float bandTop = footerTop - bandH - 1.5f * d;
            rect.set(8 * d, bandTop, w - 8 * d, bandTop + bandH);
            paint.setColor(theme.dockBand);
            canvas.drawRoundRect(rect, 5.5f * d, 5.5f * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(theme.dockEdge);
            canvas.drawRoundRect(rect, 5.5f * d, 5.5f * d, paint);
            paint.setStyle(Paint.Style.FILL);
            miniRow(canvas, 11.5f * d, rect.width() - 7 * d, bandTop + (bandH - miniH) / 2f, miniH);
        } else {
            float rowTop = footerTop - miniH - 3.5f * d;
            miniRow(canvas, 10 * d, w - 20 * d, rowTop, miniH);
        }

        // Subtle footer strip behind @id/themeName for crisp legibility in both dark and light themes.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.isLight() ? 0xD9E4ECF7 : 0xB3070B12);
        canvas.drawRect(0, footerTop, w, h, paint);
    }

    private void miniRow(Canvas c, float left, float rowW, float top, float itemH) {
        float d = density;
        int slots = 6;
        float gap = 3f * d;
        float iw = (rowW - (slots - 1) * gap) / slots;
        float ih = Math.min(itemH, iw * 0.58f);
        for (int i = 0; i < slots; i++) {
            float x = left + i * (iw + gap);
            paint.setColor(i == 0 ? theme.accent : theme.card);
            c.drawRoundRect(new RectF(x, top, x + iw, top + ih), 2.5f * d, 2.5f * d, paint);
        }
    }
}
