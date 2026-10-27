package com.harolhome.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts and caches vibrant, saturated colors from app icons or banners
 * for dynamic ambient background tinting.
 */
final class AppColorExtractor {

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    private AppColorExtractor() {}

    static int getOrExtract(String key, Drawable live, int fallback) {
        if (key != null && !key.isEmpty()) {
            Integer cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        if (live == null) return fallback;
        int color = vividColor(live);
        if (color == 0 || color == 0xFF000000) color = fallback;
        if (key != null && !key.isEmpty()) {
            CACHE.put(key, color);
        }
        return color;
    }

    static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        try {
            Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
            Rect saved = new Rect(d.getBounds());
            int n = 16;
            Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(saved);
            int[] px = new int[n * n];
            bmp.getPixels(px, 0, n, 0, 0, n, n);
            bmp.recycle();

            float[] hsv = new float[3];
            int best = 0xFF444444;
            float bestScore = -1f;
            long r = 0, g = 0, b = 0;
            for (int p : px) {
                if (Color.alpha(p) < 128) continue;
                int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
                r += pr;
                g += pg;
                b += pb;
                int max = Math.max(pr, Math.max(pg, pb));
                int min = Math.min(pr, Math.min(pg, pb));
                float sat = max == 0 ? 0f : (max - min) / (float) max;
                float val = max / 255f;
                // Higher score for saturated, visible hues (not pure black or white)
                float score = sat * Math.min(1f, val * 1.4f);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            if (bestScore < 0.20f) {
                int count = Math.max(1, n * n);
                int avg = Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
                Color.colorToHSV(avg, hsv);
                hsv[2] = Math.max(hsv[2], 0.55f);
                return Color.HSVToColor(hsv);
            }
            Color.colorToHSV(best, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.15f);
            hsv[2] = Math.max(0.55f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return 0xFF444444;
        }
    }
}
